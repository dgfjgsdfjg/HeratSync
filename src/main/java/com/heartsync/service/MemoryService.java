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
                String factPrompt = buildFactExtractionPrompt(userMessage, aiResponse);
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
     * 构建事实抽取 prompt
     */
    private String buildFactExtractionPrompt(String userMessage, String aiResponse) {
        return """
            你是一个记忆抽取器。从下面对话中抽取所有值得长期记住的事实（用户的个人信息、喜好、习惯、关系、经历等）。

            规则：
            1. 每条事实单独一行，格式严格为：实体名 | 事实内容 | 动作(create/update)
            2. 一次可以抽取多条，每条一行
            3. 实体名是这条事实归属的对象（人名/宠物名/物品名等），会作为记忆文件名
            4. 只输出事实行，不要任何解释、编号、前后缀
            5. 如果没有任何值得记住的新信息，只输出 NONE

            示例输出：
            小明 | 在杭州做后端开发 | create
            橘子 | 小明养的橘猫 | create

            对话：
            用户: %s
            恋人: %s
            """.formatted(userMessage, aiResponse);
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

        String entity = sanitizeEntity(parts[0].trim());
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
}
