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
            从以下对话中抽取值得记住的事实。如果用户分享了新信息或更新了旧信息，返回事实。
            如果没有任何值得记住的新信息，返回 NONE。

            格式: 实体名 | 事实内容 | 动作(create/update)
            示例: 橘子 | 最近不爱吃猫粮，主人换了皇家猫粮 | update

            用户: %s
            恋人: %s
            """.formatted(userMessage, aiResponse);
    }

    /**
     * 同步调用 LLM 抽取事实
     * 占位实现：Task 8 完成 LlmClient 同步方法后替换
     */
    private String extractFactSync(String prompt) {
        // 阶段 2 改为 LangChain4j 的 AiServices 结构化输出
        // 实际实现见 Task 8 中对 DeepSeek 的同步调用封装
        return NO_FACT_FLAG;
    }

    /**
     * 将抽取的事实写入 vault
     * 解析 "实体名 | 事实内容 | 动作" 格式
     */
    private void applyFactToVault(String extracted) {
        String[] parts = extracted.split("\\|");
        if (parts.length < 3) {
            log.warn("事实格式不合法，跳过写入: {}", extracted);
            return;
        }

        String entity = parts[0].trim();
        String fact = parts[1].trim();
        String action = parts[2].trim();
        String fileName = "facts/" + entity + ".md";

        try {
            VaultPage page;
            try {
                // 文件已存在 -> update: 追加事实
                page = vaultStore.readPage(fileName);
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
}
