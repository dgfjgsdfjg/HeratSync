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

            === 事实（永久属性，无时间点：喜好、身份、关系等）===
            格式：事实 | 角色 | 内容
            - 角色只三类：自己（说话的用户本人，不管自称什么）、恋人（AI/用户的恋人/你扮演的角色）、第三方本名（宠物等）
            - 只抽「已知记忆」里没有的新事实，语义重复的（如「爱喝红茶」vs「喜欢红茶」）不要再输出

            === 事件（有时间点的事：约会、爬山、纪念日、计划等）===
            格式：事件 | 标题 | 日期(YYYY-MM-DD) | 描述
            - 今天是 %s。把「明天/下周/后天」等相对时间换算成具体日期。
            - 时间完全无法判断时，用今天的日期。
            - 标题简短（如「爬岳麓山」）

            通用规则：每条一行，只输出数据行，不要解释/编号/前后缀。没有任何新信息就只输出 NONE。

            示例输出：
            事实 | 自己 | 在杭州做后端开发
            事件 | 爬岳麓山 | %s | 用户计划的户外活动
            事件 | 第一次一起逛漫展 | 2026-06-01 | 重要回忆

            已知记忆（这些事实都记过了，不要重复）：
            %s

            本轮对话：
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
     * 将抽取结果分流写入：事实→vault、事件→SQLite
     * 格式「事实 | 角色 | 内容」或「事件 | 标题 | 日期 | 描述」
     */
    private void applyExtraction(String userId, String extracted) {
        for (String line : extracted.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || NO_FACT_FLAG.equals(trimmed)) {
                continue;
            }
            if (trimmed.startsWith("事件")) {
                applyEvent(userId, trimmed);
            } else if (trimmed.startsWith("事实")) {
                applyFact(userId, trimmed);
            } else {
                // 兼容旧格式（角色 | 内容 | 动作），视为事实
                applyFactLegacy(trimmed);
            }
        }
    }

    /** 新格式「事实 | 角色 | 内容」 */
    private void applyFact(String userId, String line) {
        String[] parts = line.split("\\|");
        if (parts.length < 3) return;
        String role = parts[1].trim();
        String fact = parts[2].trim();
        if (role.isEmpty() || fact.isEmpty()) return;
        String entity = resolveEntityFromRole(sanitizeEntity(role));
        writeFactToFile(entity, fact);
    }

    /** 兼容旧格式「角色 | 内容 | 动作」 */
    private void applyFactLegacy(String line) {
        String[] parts = line.split("\\|");
        if (parts.length < 3) return;
        String entity = resolveEntityFromRole(sanitizeEntity(parts[0].trim()));
        String fact = parts[1].trim();
        if (entity.isEmpty() || fact.isEmpty()) return;
        writeFactToFile(entity, fact);
    }

    /** 写一条事实到 vault */
    private void writeFactToFile(String entity, String fact) {
        String fileName = "facts/" + entity + ".md";
        try {
            VaultPage page;
            try {
                page = vaultStore.readPage(fileName);
                if (page.getContent() != null && page.getContent().contains(fact)) return;
                page.setContent(page.getContent() + "\n" + fact);
            } catch (IOException e) {
                page = VaultPage.builder().title(entity).type("fact").content(fact).build();
            }
            vaultStore.writePage(fileName, page);
            luceneIndex.addPage(fileName, page);
            log.info("记忆写入: entity={}", entity);
        } catch (IOException e) {
            log.error("记忆写入 vault 失败: file={}", fileName, e);
        }
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
