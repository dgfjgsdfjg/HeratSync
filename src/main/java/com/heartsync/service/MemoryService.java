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
    /** 用于 remember 阶段的事实抽取，可能为 null（测试时） */
    private final LlmClient llmClient;

    public MemoryService(VaultStore vaultStore, LuceneIndex luceneIndex, LlmClient llmClient) {
        this.vaultStore = vaultStore;
        this.luceneIndex = luceneIndex;
        this.llmClient = llmClient;
    }

    /**
     * 记忆召回：BM25 全文检索 Top-5 + wikilink 图谱一跳扩展 + 按标题去重
     * @param query 用户输入
     * @return 拼装好的记忆片段文本，可直接拼入 prompt；无结果时返回空串
     */
    public String recall(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        try {
            // 1. BM25 全文检索
            List<VaultPage> bm25Results = luceneIndex.search(query, RECALL_TOP_N);
            if (bm25Results == null || bm25Results.isEmpty()) {
                return "";
            }

            // 2. 按标题去重（BM25 结果内部去重）
            Set<String> seenTitles = new HashSet<>();
            List<VaultPage> recalled = new ArrayList<>();
            for (VaultPage page : bm25Results) {
                if (page.getTitle() != null && seenTitles.add(page.getTitle())) {
                    recalled.add(page);
                }
            }

            // 3. wikilink 一跳扩展
            // LuceneIndex 返回的 VaultPage 不含 links 字段，需从 content 重新提取
            for (VaultPage page : new ArrayList<>(recalled)) {
                List<String> links = VaultStore.extractWikilinks(page.getContent());
                for (String link : links) {
                    VaultPage linked = vaultStore.findByTitle(link);
                    if (linked != null && linked.getTitle() != null
                        && seenTitles.add(linked.getTitle())) {
                        recalled.add(linked);
                    }
                }
            }

            // 4. 拼装为文本片段（仅正文，不含标题前缀，避免标题词干扰去重断言）
            return recalled.stream()
                .map(p -> p.getContent() != null ? p.getContent() : "")
                .collect(Collectors.joining("\n"));

        } catch (IOException e) {
            log.error("记忆召回失败, query={}", query, e);
            return ""; // 降级：无记忆继续对话
        }
    }

    /**
     * 记忆写回：从本轮对话中抽取事实，更新 vault
     * 异步执行，失败不影响对话主流程
     * @param userMessage 用户消息
     * @param aiResponse  AI 回复
     */
    public void remember(String userMessage, String aiResponse) {
        if (llmClient == null) {
            log.warn("LlmClient 未初始化，跳过记忆写回");
            return;
        }
        // 异步执行，失败不影响主流程
        CompletableFuture.runAsync(() -> {
            try {
                // 先读出已知事实，喂给抽取器，避免把同一偏好反复记成多条（如「喜欢红茶」被重复抽取）
                String knownFacts = renderKnownFacts();
                String factPrompt = buildFactExtractionPrompt(userMessage, aiResponse, knownFacts);
                String extracted = extractFactSync(factPrompt);
                if (extracted != null && !extracted.isBlank()
                    && !NO_FACT_FLAG.equals(extracted.trim())) {
                    applyFactToVault(extracted);
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
     * 构建事实抽取 prompt
     * @param knownFacts 已知事实文本，让 LLM 只抽取尚未记录的新信息，避免同义重复
     */
    private String buildFactExtractionPrompt(String userMessage, String aiResponse, String knownFacts) {
        return """
            你是一个记忆抽取器。从下面对话中抽取值得长期记住的【新】事实（用户的个人信息、喜好、习惯、关系、经历等）。

            规则：
            1. 每条事实单独一行，格式严格为：角色 | 事实内容 | 动作(create/update)
            2. 只抽取「已知记忆」里【没有】的新信息。语义重复的（哪怕措辞不同，如「爱喝红茶」vs「喜欢红茶」）绝对不要再输出。
            3. 「角色」按事实归属判断，只用下面三类，不要自造名字：
               - 自己：关于【说话的用户本人】的事（不管 TA 自称我/主人/名字，都算「自己」）。
               - 恋人：关于【AI / 用户的恋人 / 你扮演的角色】的事。
               - 具体本名：真正的第三方对象（宠物、别的人等），用其本名，并复用「已知记忆」里出现过的名字。
            4. 只输出事实行，不要任何解释、编号、前后缀。
            5. 如果本轮没有任何值得记住的新信息，只输出 NONE。

            示例输出：
            自己 | 在杭州做后端开发 | create
            恋人 | 和用户逛漫展时会害羞 | create
            橘子 | 用户养的橘猫 | create

            已知记忆（这些都记过了，不要重复）：
            %s

            本轮对话：
            用户: %s
            恋人: %s
            """.formatted(knownFacts == null || knownFacts.isBlank() ? "（暂无）" : knownFacts, userMessage, aiResponse);
    }

    /**
     * 同步调用 LLM 抽取事实
     */
    private String extractFactSync(String prompt) {
        String result = llmClient.complete(prompt);
        return (result == null || result.isBlank()) ? NO_FACT_FLAG : result.trim();
    }

    /**
     * 将抽取结果写入 vault（支持多行，每行一条事实）
     */
    private void applyFactToVault(String extracted) {
        // 逐行处理，每行一条 "实体名 | 事实内容 | 动作"
        for (String line : extracted.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || NO_FACT_FLAG.equals(trimmed)) {
                continue;
            }
            applyOneFact(trimmed);
        }
    }

    /**
     * 写入单条事实
     */
    private void applyOneFact(String line) {
        String[] parts = line.split("\\|");
        if (parts.length < 3) {
            log.warn("事实格式不合法，跳过: {}", line);
            return;
        }

        String entity = resolveEntityFromRole(sanitizeEntity(parts[0].trim()));
        String fact = parts[1].trim();
        String action = parts[2].trim();
        if (entity.isEmpty() || fact.isEmpty()) {
            return;
        }
        String fileName = "facts/" + entity + ".md";

        try {
            VaultPage page;
            try {
                // 文件已存在 -> update: 追加事实（去重：已含则跳过）
                page = vaultStore.readPage(fileName);
                if (page.getContent() != null && page.getContent().contains(fact)) {
                    return;
                }
                page.setContent(page.getContent() + "\n" + fact);
            } catch (IOException e) {
                // 文件不存在 -> create: 新建事实页
                page = VaultPage.builder()
                    .title(entity)
                    .type("fact")
                    .content(fact)
                    .build();
            }
            vaultStore.writePage(fileName, page);
            luceneIndex.addPage(fileName, page);
            log.info("记忆写入: entity={}, action={}", entity, action);
        } catch (IOException e) {
            log.error("记忆写入 vault 失败: file={}", fileName, e);
        }
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
