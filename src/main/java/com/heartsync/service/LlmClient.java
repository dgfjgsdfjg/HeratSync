package com.heartsync.service;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * DeepSeek 流式 LLM 调用封装
 * DeepSeek 兼容 OpenAI 接口，使用 langchain4j-open-ai 模块
 */
public class LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final StreamingChatLanguageModel model;

    public LlmClient(String apiKey, String baseUrl, String modelName) {
        this.model = OpenAiStreamingChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .modelName(modelName)
            .timeout(Duration.ofSeconds(60))
            .build();
    }

    /**
     * 流式对话，返回 token 流
     * @param userMessage 用户输入
     * @param systemPrompt 系统人设 prompt
     * @param history 最近 10 轮对话历史（UserMessage/AiMessage 交替）
     * @param memories 召回的 Vault 记忆片段文本
     * @return Flux<String> token 流
     */
    public Flux<String> streamResponse(String userMessage, String systemPrompt,
                                        List<dev.langchain4j.data.message.ChatMessage> history,
                                        String memories) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        // 拼装完整消息列表
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

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
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
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
     * 同步补全：给一个 prompt，阻塞返回完整回复
     * 复用流式 model，收集全部 token 后拼成整串，用于记忆事实抽取等非流式场景
     * @param prompt 单轮提示词
     * @return 完整回复文本；失败返回空串
     */
    public String complete(String prompt) {
        CompletableFuture<String> future = new CompletableFuture<>();
        StringBuilder sb = new StringBuilder();
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(prompt));

        model.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                sb.append(partialResponse);
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
                future.complete(sb.toString());
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });

        try {
            // 阻塞等待，最多 60 秒
            return future.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("同步补全失败, prompt={}", prompt, e);
            return "";
        }
    }
}
