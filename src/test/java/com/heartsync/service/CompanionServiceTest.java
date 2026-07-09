package com.heartsync.service;

import com.heartsync.vault.VaultStore;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompanionServiceTest {

    private CompanionService companionService;
    private MemoryService memoryService;
    private PersonaService personaService;
    private LlmClient llmClient;

    @BeforeEach
    void setUp() {
        // 使用 mock/假实现
        // ponytail: 不引入 Mockito，手写匿名类
        memoryService = new MemoryService(null, null, null, null) {
            @Override
            public String recall(String query) {
                return "用户养了猫叫橘子";
            }
            @Override
            public String recall(String query, String userId) {
                return "用户养了猫叫橘子";
            }
        };
        personaService = new PersonaService(null) {
            @Override
            public String loadSystemPrompt() {
                return "你是一个温柔贴心的恋人";
            }
            @Override
            public void updateStateField(String key, String value) {
                // 测试环境无 vaultStore，空实现避免 NPE
            }
        };
        llmClient = new LlmClient("fake-key", "http://localhost", "test") {
            @Override
            public Flux<String> streamResponse(String userMessage, String systemPrompt,
                                                List<dev.langchain4j.data.message.ChatMessage> history,
                                                String memories) {
                return Flux.just("抱", "抱", "你");
            }
        };
        companionService = new CompanionService(memoryService, personaService, llmClient, null, 10);
    }

    @Test
    void shouldReturnTokenStream() {
        Flux<String> tokens = companionService.chat("user-1", "心情不好");
        StepVerifier.create(tokens)
            .expectNext("抱", "抱", "你")
            .verifyComplete();
    }

    @Test
    void shouldMaintainConversationHistory() {
        // 发两轮，历史应有 2 条用户消息和 2 条 AI 回复
        companionService.chat("user-1", "第一轮").blockLast();
        companionService.onChatComplete("user-1", "第一轮", "回复1");

        companionService.chat("user-1", "第二轮").blockLast();
        companionService.onChatComplete("user-1", "第二轮", "回复2");

        // 此时历史应包含 4 条消息（2 轮 × 2 条）
        // 验证不会超过 10 轮（20 条消息）
        assertEquals(4, companionService.getHistorySize("user-1"));
    }
}
