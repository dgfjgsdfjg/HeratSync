package com.heartsync.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话编排服务 - 取记忆 -> 拼 prompt -> 流式调 LLM -> 异步写回记忆
 * 核心编排逻辑，连接 Memory、Persona、LLM 三大模块
 */
@Service
public class CompanionService {
    private static final Logger log = LoggerFactory.getLogger(CompanionService.class);
    private static final int MAX_HISTORY_ROUNDS = 10; // 最多保留 10 轮对话
    private static final int MAX_HISTORY_MESSAGES = MAX_HISTORY_ROUNDS * 2; // 用户+AI 各一条

    private final MemoryService memoryService;
    private final PersonaService personaService;
    private final LlmClient llmClient;

    // 会话历史：userId -> 消息列表（ponytail: 内存 Map，单机够用，阶段 2 上 Redis）
    private final Map<String, List<ChatMessage>> conversationHistory = new ConcurrentHashMap<>();

    public CompanionService(MemoryService memoryService, PersonaService personaService, LlmClient llmClient) {
        this.memoryService = memoryService;
        this.personaService = personaService;
        this.llmClient = llmClient;
    }

    /**
     * 处理用户消息，返回 AI 流式回复
     * @param userId 用户 ID
     * @param message 用户消息文本
     * @return Flux<String> token 流
     */
    public Flux<String> chat(String userId, String message) {
        log.info("用户消息: userId={}, message={}", userId, message);

        // 1. 记忆召回
        String memories = memoryService.recall(message);

        // 2. 装载人设
        String systemPrompt = personaService.loadSystemPrompt();

        // 3. 获取历史对话
        List<ChatMessage> history = conversationHistory
            .getOrDefault(userId, Collections.emptyList());

        // 4. 流式调用 LLM
        return llmClient.streamResponse(message, systemPrompt, history, memories);
    }

    /**
     * 对话完成回调：将本轮对话加入历史，触发异步记忆写回
     */
    public void onChatComplete(String userId, String userMessage, String aiResponse) {
        // 更新对话历史
        List<ChatMessage> history = conversationHistory
            .computeIfAbsent(userId, k -> new ArrayList<>());
        history.add(new UserMessage(userMessage));
        history.add(new AiMessage(aiResponse));

        // 滑动窗口：超过最大轮数时移除最早的
        while (history.size() > MAX_HISTORY_MESSAGES) {
            history.remove(0);
        }

        // 更新状态：上次互动时间
        personaService.updateStateField("上次互动", java.time.LocalDateTime.now().toString());

        // 异步记忆写回
        memoryService.remember(userMessage, aiResponse);

        log.info("对话完成: userId={}, historySize={}", userId, history.size());
    }

    /**
     * 获取历史对话轮数（测试用）
     */
    public int getHistorySize(String userId) {
        return conversationHistory.getOrDefault(userId, Collections.emptyList()).size();
    }
}
