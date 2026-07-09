package com.heartsync.service;

import com.heartsync.model.VaultPage;
import com.heartsync.vault.LuceneIndex;
import com.heartsync.vault.VaultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 记忆服务 - BM25 召回 + wikilink 图谱扩展 + 事实抽取写回
 */
@Service
public class MemoryService {
    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** BM25 检索返回的最大条目数 */
    private static final int RECALL_TOP_N = 5;

    /** 事实抽取返回的无事实标记 */
    private static final String NO_FACT_FLAG = "NONE";

    /** 两个固定角色对应的规范记忆页名 */
    private static final String CANONICAL_USER = "用户";       // 角色「自己」→ 此页
    private static final String CANONICAL_COMPANION = "恋人";  // 角色「恋人」→ 此页

    /** 角色标签容错：LLM 可能输出的等价说法，归一到规范页（仅两个角色，非用户别名穷举） */
    private static final Set<String> SELF_LABELS = Set.of("自己", "我", "用户", "用户本人");
    private static final Set<String> COMPANION_LABELS = Set.of("恋人", "对方", "ai", "AI");

    private final VaultStore vaultStore;
    private final LuceneIndex luceneIndex;
    /** 用于 remember 阶段的事实/事件抽取，可能为 null（测试时） */
    private final LlmClient llmClient;
    /** 事件持久化存储；可为 null（测试时） */
    private final EventStore eventStore;

    public MemoryService(VaultStore vaultStore, LuceneIndex luceneIndex, LlmClient llmClient, EventStore eventStore) {
        this.vaultStore = vaultStore;
        this.luceneIndex = luceneIndex;
        this.llmClient = llmClient;
        this.eventStore = eventStore;
    }

    /**
     * 记忆召回：BM25 全文检索 Top-5 + wikilink 图谱一跳扩展 + 按标题去重
     * @param query 用户输入
     * @return 拼装好的记忆片段文本，可直接拼入 prompt；无结果时返回空串
     */
    public String recall(String query) {
        return recall(query, null);
    }

    /**
     * 记忆召回（带事件）：facts 走 BM25+图谱、events 走 SQLite 按时序，
     * 合并返回，无结果时返回空串
     */
    public String recall(String query, String userId) {
        StringBuilder sb = new StringBuilder();

        // === 事实（vault BM25+图谱）===
        if (query != null && !query.isBlank()) {
            try {
                List<VaultPage> bm25Results = luceneIndex.search(query, RECALL_TOP_N);
                if (bm25Results != null && !bm25Results.isEmpty()) {
                    Set<String> seenTitles = new HashSet<>();
                    List<VaultPage> recalled = new ArrayList<>();
                    for (VaultPage page : bm25Results) {
                        if (page.getTitle() != null && seenTitles.add(page.getTitle())) {
                            recalled.add(page);
                        }
                    }
                    for (VaultPage page : new ArrayList<>(recalled)) {
                        for (String link : VaultStore.extractWikilinks(page.getContent())) {
                            VaultPage linked = vaultStore.findByTitle(link);
                            if (linked != null && linked.getTitle() != null && seenTitles.add(linked.getTitle())) {
                                recalled.add(linked);
                            }
                        }
                    }
                    sb.append(recalled.stream()
                        .map(p -> p.getContent() != null ? p.getContent() : "")
                        .collect(Collectors.joining("\n")));
                }
            } catch (IOException e) {
                log.error("记忆召回失败, query={}", query, e);
            }
        }

        // === 事件（SQLite）===
        if (userId != null && eventStore != null) {
            String events = eventStore.renderForRecall(userId);
            if (!events.isEmpty()) {
                if (sb.length() > 0) {
                sb.append("\n\n## 近期事件\n");
            }
                sb.append(events);
            }
        }

        return sb.toString();
    }

    /**
     * 记忆写回：从本轮对话抽取【事实】(→vault) 和【事件】(→SQLite)
     * 异步执行，失败不影响对话主流程
     */
    public void remember(String userId, String userMessage, String aiResponse) {
        if (llmClient == null) {
            log.warn("LlmClient 未初始化，跳过记忆写回");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                String knownFacts = renderKnownFacts();
                String prompt = buildExtractionPrompt(userMessage, aiResponse, knownFacts);
                String extracted = extractFactSync(prompt);
                if (extracted != null && !extracted.isBlank()
                    && !NO_FACT_FLAG.equals(extracted.trim())) {
                    applyExtraction(userId, extracted);
                }
            } catch (Exception e) {
                log.error("记忆写回失败，不影响主流程, userMessage={}", userMessage, e);
            }
        });
    }

    /**
     * 渲染当前全部已知事实，供抽取器去重参考
     * 单用户 vault 规模小，全量读取可接受
     */
    private String renderKnownFacts() {
        try {
            return vaultStore.readAllPages().stream()
                .filter(p -> "fact".equals(p.getType()) && p.getContent() != null && !p.getContent().isBlank())
                .map(p -> "[" + p.getTitle() + "] " + p.getContent().replace("\n", "；"))
                .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            log.error("读取已知事实失败", e);
            return "";
        }
    }

    /**
     * 构建抽取 prompt：同时抽【事实】和【事件】
     * @param knownFacts 已知事实文本，避免重复
     */
    private String buildExtractionPrompt(String userMessage, String aiResponse, String knownFacts) {
        String today = java.time.LocalDate.now().toString();
        return """
            你是记忆抽取器。从下面对话中抽取值得记住的【新】信息，分两类：

            【最重要的原则】只抽取「用户真正透露的客观信息」和「双方约定的真实事件」。
            绝对不要把【恋人(AI)自己说的抒情台词、承诺、想象、表演】当成事实。
            例如 AI 说「我每晚泡红茶想你」「我会给你准备红茶」——这是 AI 的表演，不是事实，忽略。
            恋人(AI)的事实只记【稳定人设】（如性格害羞、深爱用户），不要记它每一句甜言蜜语。
            不要围绕单一话题反复抽取（比如已经记了「喜欢红茶」，就别再抽任何红茶相关的新条目）。

            === 事实（永久属性，无时间点：喜好、身份、关系等）===
            格式：事实 | 角色 | 内容
            - 角色只三类：自己（说话的用户本人，不管自称什么）、恋人（AI/用户的恋人/你扮演的角色）、第三方本名（宠物等）
            - 只抽「已知记忆」里没有的新事实，语义重复或同话题的都不要再输出

            === 事件（有时间点的事：约会、爬山、纪念日、计划等）===
            格式：事件 | 标题 | 日期(YYYY-MM-DD) | 描述
            - 今天是 %s。把「明天/下周/后天」等相对时间换算成具体日期。
            - 时间完全无法判断时，用今天的日期。
            - 标题简短（如「爬岳麓山」）；只记真实发生/已约定的事，不记 AI 幻想的场景

            通用规则：每条一行，只输出数据行，不要解释/编号/前后缀。没有任何新信息就只输出 NONE。

            示例输出：
            事实 | 自己 | 在杭州做后端开发
            事件 | 爬岳麓山 | %s | 用户计划的户外活动

            已知记忆（这些事实都记过了，不要重复，也不要抽同话题的新变体）：
            %s

            本轮对话（【用户】说的话才是事实来源；【恋人】的话只用来理解上下文）：
            用户: %s
            恋人: %s
            """.formatted(today, today, knownFacts == null || knownFacts.isBlank() ? "（暂无）" : knownFacts, userMessage, aiResponse);
    }

    /**
     * 同步调用 LLM 抽取事实
     */
    private String extractFactSync(String prompt) {
        String result = llmClient.complete(prompt);
        return (result == null || result.isBlank()) ? NO_FACT_FLAG : result.trim();
    }

    /**
     * 将抽取结果分流写入：事实→vault(经消解)、事件→SQLite
     * 事实先按实体分组，每个实体做一次 ADD/UPDATE/DELETE/NOOP 消解，避免同话题堆积/矛盾并存
     */
    private void applyExtraction(String userId, String extracted) {
        // 按实体聚合本轮新事实
        Map<String, List<String>> factsByEntity = new LinkedHashMap<>();
        for (String line : extracted.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || NO_FACT_FLAG.equals(trimmed)) {
                continue;
            }
            if (trimmed.startsWith("事件")) {
                applyEvent(userId, trimmed);
            } else if (trimmed.startsWith("事实")) {
                collectFact(factsByEntity, trimmed, 1, 2); // 事实 | 角色 | 内容
            } else {
                collectFact(factsByEntity, trimmed, 0, 1); // 旧格式 角色 | 内容 | 动作
            }
        }
        // 逐实体消解并写回
        for (Map.Entry<String, List<String>> e : factsByEntity.entrySet()) {
            reconcileEntity(e.getKey(), e.getValue());
        }
    }

    /** 从一行抽取结果取「角色/实体 + 事实内容」，归一实体后收集 */
    private void collectFact(Map<String, List<String>> byEntity, String line, int roleIdx, int factIdx) {
        String[] parts = line.split("\\|");
        if (parts.length <= Math.max(roleIdx, factIdx)) return;
        String role = parts[roleIdx].trim();
        String fact = parts[factIdx].trim();
        if (role.isEmpty() || fact.isEmpty()) return;
        String entity = resolveEntityFromRole(sanitizeEntity(role));
        if (entity.isEmpty()) return;
        byEntity.computeIfAbsent(entity, k -> new ArrayList<>()).add(fact);
    }

    /**
     * mem0 式消解：把「实体现有事实 + 本轮新候选」交给 LLM，产出 ADD/UPDATE/DELETE 操作并应用。
     * 新实体或消解失败时，降级为简单追加去重。
     */
    private void reconcileEntity(String entity, List<String> newFacts) {
        String fileName = "facts/" + entity + ".md";
        VaultPage page;
        List<String> existing;
        try {
            page = vaultStore.readPage(fileName);
            existing = splitLines(page.getContent());
        } catch (IOException notExist) {
            // 新实体：无需消解，直接建页（候选内部已由抽取器去重）
            writeEntityPage(entity, new ArrayList<>(newFacts));
            return;
        }

        try {
            String prompt = buildReconcilePrompt(entity, existing, newFacts);
            String ops = llmClient.complete(prompt);
            List<String> merged = applyReconcileOps(existing, ops);
            writeEntityPage(entity, merged);
        } catch (Exception ex) {
            log.error("记忆消解失败，降级为追加去重: entity={}", entity, ex);
            for (String f : newFacts) {
                if (!existing.contains(f)) existing.add(f);
            }
            writeEntityPage(entity, existing);
        }
    }

    /** 构建消解 prompt（行号引用，稳健） */
    private String buildReconcilePrompt(String entity, List<String> existing, List<String> newFacts) {
        StringBuilder ex = new StringBuilder();
        for (int i = 0; i < existing.size(); i++) {
            ex.append(i + 1).append(". ").append(existing.get(i)).append("\n");
        }
        String cand = newFacts.stream().map(f -> "- " + f).collect(Collectors.joining("\n"));
        return """
            你在维护「%s」的长期记忆。下面是现有记忆（带行号）和本轮新抽取的候选事实。
            为每条候选决定如何并入，输出操作（每行一条），严格用这三种格式：
              ADD | 内容              —— 全新信息，追加
              UPDATE | 行号 | 新内容   —— 候选是某行的更新/更准确版本，替换该行（如名字变了、状态变了）
              DELETE | 行号           —— 候选表明某行已过时/矛盾，删除该行
            规则：
            - 候选与现有语义重复（哪怕措辞不同）→ 不输出任何操作（丢弃）
            - 只输出操作行，没有任何操作就只输出 NONE
            - 行号必须是下面列出的真实行号

            现有记忆：
            %s
            本轮候选：
            %s
            """.formatted(entity, ex.length() == 0 ? "（空）" : ex.toString(), cand.isEmpty() ? "（无）" : cand);
    }

    /** 应用消解操作到现有行，返回合并后的新行列表 */
    private List<String> applyReconcileOps(List<String> existing, String ops) {
        List<String> lines = new ArrayList<>(existing);
        Set<Integer> toDelete = new HashSet<>();
        Map<Integer, String> toUpdate = new HashMap<>();
        List<String> toAdd = new ArrayList<>();

        if (ops != null && !"NONE".equals(ops.trim())) {
            for (String raw : ops.split("\\r?\\n")) {
                String op = raw.trim();
                if (op.isEmpty()) continue;
                String[] p = op.split("\\|");
                String kind = p[0].trim().toUpperCase();
                try {
                    if (kind.startsWith("ADD") && p.length >= 2) {
                        String c = p[1].trim();
                        if (!c.isEmpty()) toAdd.add(c);
                    } else if (kind.startsWith("UPDATE") && p.length >= 3) {
                        int idx = Integer.parseInt(p[1].trim()) - 1;
                        String c = p[2].trim();
                        if (idx >= 0 && idx < lines.size() && !c.isEmpty()) toUpdate.put(idx, c);
                    } else if (kind.startsWith("DELETE") && p.length >= 2) {
                        int idx = Integer.parseInt(p[1].trim()) - 1;
                        if (idx >= 0 && idx < lines.size()) toDelete.add(idx);
                    }
                } catch (NumberFormatException ignore) {
                    // 行号解析失败，跳过该操作
                }
            }
        }

        List<String> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (toDelete.contains(i)) continue;
            result.add(toUpdate.getOrDefault(i, lines.get(i)));
        }
        result.addAll(toAdd);
        return result;
    }

    /** 把合并后的事实行写回实体页并更新索引 */
    private void writeEntityPage(String entity, List<String> lines) {
        String fileName = "facts/" + entity + ".md";
        // 去重保序 + 去空
        List<String> clean = new ArrayList<>();
        for (String l : lines) {
            String t = l == null ? "" : l.trim();
            if (!t.isEmpty() && !clean.contains(t)) clean.add(t);
        }
        try {
            VaultPage page;
            try {
                page = vaultStore.readPage(fileName);
            } catch (IOException notExist) {
                page = VaultPage.builder().title(entity).type("fact").content("").build();
            }
            page.setContent(String.join("\n", clean));
            vaultStore.writePage(fileName, page);
            luceneIndex.addPage(fileName, page);
            log.info("记忆消解写入: entity={}, 行数={}", entity, clean.size());
        } catch (IOException e) {
            log.error("记忆写入 vault 失败: file={}", fileName, e);
        }
    }

    /** 正文按行拆分，去空行 */
    private List<String> splitLines(String content) {
        List<String> out = new ArrayList<>();
        if (content == null) return out;
        for (String l : content.split("\\r?\\n")) {
            String t = l.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 事件 → SQLite */
    private void applyEvent(String userId, String line) {
        if (eventStore == null) return;
        String[] parts = line.split("\\|");
        if (parts.length < 3) return;
        String title = parts[1].trim();
        String date = parts[2].trim();
        String desc = parts.length > 3 ? parts[3].trim() : "";
        if (title.isEmpty() || date.isEmpty()) return;
        eventStore.append(userId, title, date, desc);
    }

    /**
     * 清洗实体名，去掉不能作文件名的字符
     */
    private String sanitizeEntity(String entity) {
        return entity.replaceAll("[\\\\/:*?\"<>|]", "").trim();
    }

    /**
     * 把 LLM 输出的「角色」标签解析为规范记忆页名。
     * 自己→用户页、恋人→恋人页（含少量容错说法），其余按第三方本名。
     * 与「用户自称什么」无关——角色由 LLM 按事实归属判断，代码只做固定映射，天然支持多用户/多语言。
     */
    private String resolveEntityFromRole(String roleLabel) {
        if (SELF_LABELS.contains(roleLabel)) {
            return CANONICAL_USER;
        }
        if (COMPANION_LABELS.contains(roleLabel)) {
            return CANONICAL_COMPANION;
        }
        return roleLabel; // 第三方对象，用本名
    }
}
