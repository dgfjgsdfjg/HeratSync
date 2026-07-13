package com.heartsync.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek 流式 LLM 调用封装
 * DeepSeek 兼容 OpenAI 接口，使用 langchain4j-open-ai 模块
 */
public class LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final StreamingChatLanguageModel model;
    private final ChatLanguageModel syncModel;  // 同一实例，用同步接口

    public LlmClient(String apiKey, String baseUrl, String modelName) {
        this.model = OpenAiStreamingChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .modelName(modelName)
            .timeout(Duration.ofSeconds(60))
            .build();
        this.syncModel = this.model;  // StreamingChatLanguageModel extends ChatLanguageModel
    }

    /**
     * 流式对话，返回 token 流
     */
    public Flux<String> streamResponse(String userMessage, String systemPrompt,
                                        List<ChatMessage> history,
                                        String memories) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        // 拼装完整消息列表
        List<ChatMessage> messages = new ArrayList<>();

        // 1. system prompt（人设 + 记忆）
        StringBuilder fullSystem = new StringBuilder(systemPrompt);
        if (memories != null && !memories.isEmpty()) {
            fullSystem.append("\n\n## 关于用户的记忆\n").append(memories);
        }
        messages.add(new SystemMessage(fullSystem.toString()));

        // 2. 历史对话
        messages.addAll(history);

        // 3. 当前用户消息
        messages.add(new UserMessage(userMessage));

        // 流式调用
        model.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                sink.tryEmitNext(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                sink.tryEmitComplete();
                log.info("LLM 流式回复完成");
            }

            @Override
            public void onError(Throwable error) {
                log.error("LLM 调用失败", error);
                sink.tryEmitError(error);
            }
        });

        return sink.asFlux();
    }

    /**
     * 同步补全：给一个 prompt，阻塞返回完整回复。
     * 直接调 ChatLanguageModel.chat()，用于记忆事实抽取等非流式场景。
     */
    public String complete(String prompt) {
        if (prompt == null || prompt.isBlank()) return "";
        try {
            ChatResponse resp = syncModel.chat(new UserMessage(prompt));
            if (resp != null && resp.aiMessage() != null) {
                return resp.aiMessage().text();
            }
            return "";
        } catch (Exception e) {
            log.warn("同步补全失败, prompt 长度={}", prompt.length(), e);
            return "";
        }
    }
}
