package com.heartsync.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话编排服务 - 取记忆 -> 拼 prompt -> 流式调 LLM -> 异步写回记忆
 * 核心编排逻辑，连接 Memory、Persona、LLM 三大模块
 */
@Service
public class CompanionService {
    private static final Logger log = LoggerFactory.getLogger(CompanionService.class);
    private static final int MAX_HISTORY_MESSAGES = 20; // 最多保留 10 轮对话（用户+AI 各一条）

    private final MemoryService memoryService;
    private final PersonaService personaService;
    private final LlmClient llmClient;
    /** LangChain4j ChatMemory 持久化存储（SQLite），null 时纯内存 */
    private final ChatMemoryStore chatMemoryStore;

    // 每家用户一个 ChatMemory 实例（内存窗口 + SQLite 持久化）
    private final Map<String, ChatMemory> chatMemories = new ConcurrentHashMap<>();

    // 上次互动时间：userId -> 时刻（主动推送静默期判断用）
    private final Map<String, LocalDateTime> lastInteractionTime = new ConcurrentHashMap<>();

    public CompanionService(MemoryService memoryService, PersonaService personaService,
                            LlmClient llmClient, ConversationStore conversationStore) {
        this.memoryService = memoryService;
        this.personaService = personaService;
        this.llmClient = llmClient;
        // conversationStore 不为 null 时才启用持久化（测试环境可为 null）
        this.chatMemoryStore = conversationStore != null
            ? new SqliteChatMemoryStore(conversationStore) : null;
    }

    /**
     * 获取或创建用户的 ChatMemory
     */
    private ChatMemory memory(String userId) {
        return chatMemories.computeIfAbsent(userId, id -> {
            var builder = MessageWindowChatMemory.builder()
                .id(id)
                .maxMessages(MAX_HISTORY_MESSAGES);
            if (chatMemoryStore != null) {
                builder.chatMemoryStore(chatMemoryStore);
            }
            ChatMemory mem = builder.build();
            log.info("ChatMemory 初始化: userId={}, historySize={}", id, mem.messages().size());
            return mem;
        });
    }

    /**
     * 处理用户消息，返回 AI 流式回复
     * @param userId 用户 ID
     * @param message 用户消息文本
     * @return Flux<String> token 流
     */
    public Flux<String> chat(String userId, String message) {
        log.info("用户消息: userId={}, message={}", userId, message);

        ChatMemory mem = memory(userId);

        // 1. 记忆召回
        String memories = memoryService.recall(message, userId);

        // 2. 装载人设
        String systemPrompt = personaService.loadSystemPrompt();

        // 3. 获取历史对话（ChatMemory 首次从 SQLite 加载 + 自动窗口裁剪）
        List<ChatMessage> history = mem.messages();

        // 4. 流式调用 LLM
        return llmClient.streamResponse(message, systemPrompt, history, memories);
    }

    /**
     * 对话完成回调：将本轮加入内存窗口 + 持久化 + 异步记忆写回
     */
    public void onChatComplete(String userId, String userMessage, String aiResponse) {
        ChatMemory mem = memory(userId);
        // ChatMemory.add() 自动触发滑动窗口裁剪 + ChatMemoryStore 持久化
        mem.add(new UserMessage(userMessage));
        mem.add(new AiMessage(aiResponse));

        // 更新上次互动时间（主动推送用）
        lastInteractionTime.put(userId, LocalDateTime.now());

        // 异步记忆写回（抽取事实 + 事件）
        memoryService.remember(userId, userMessage, aiResponse);

        log.info("对话完成: userId={}, historySize={}", userId, mem.messages().size());
    }

    /**
     * 获取上次互动时间（主动推送静默期判断用）；从未互动返回 null
     */
    public LocalDateTime getLastInteractionTime(String userId) {
        return lastInteractionTime.get(userId);
    }

    /**
     * 主动消息决策：判断此刻要不要主动给用户发消息，要发则返回内容
     * 规则闸门由 PushService 把关，这里只做「结合上下文的 LLM 拟人判断」
     * @param userId 用户 ID
     * @return 要发的消息文本；判断不该发时返回空
     */
    public Optional<String> decideProactiveMessage(String userId) {
        List<ChatMessage> history = memory(userId).messages();
        // 没聊过就不主动搭话（没有可参考的上下文，硬发很人机）
        if (history.isEmpty()) {
            return Optional.empty();
        }

        // 组装最近对话文本
        String historyText = renderHistory(history);
        // 用最近一条用户消息做记忆召回
        String lastUserMsg = lastUserMessage(history);
        String memories = memoryService.recall(lastUserMsg != null ? lastUserMsg : historyText, userId);
        // 距上次互动多久
        LocalDateTime last = lastInteractionTime.get(userId);
        long minutesSince = last == null ? -1 : ChronoUnit.MINUTES.between(last, LocalDateTime.now());

        String prompt = buildProactivePrompt(historyText, memories, minutesSince);

        try {
            String result = llmClient.complete(prompt);
            log.info("主动消息决策: userId={}, LLM原始返回={}", userId, result == null ? "null" : result.strip());
            return parsePushDecision(result);
        } catch (Exception e) {
            log.error("主动消息决策失败, userId={}", userId, e);
            return Optional.empty(); // 失败当作不推，不影响主流程
        }
    }

    /**
     * 构建主动消息决策 prompt
     */
    private String buildProactivePrompt(String historyText, String memories, long minutesSince) {
        String persona = personaService.loadSystemPrompt();
        String sinceText = minutesSince < 0 ? "未知" : minutesSince + " 分钟";
        int hour = LocalDateTime.now().getHour();
        return """
            %s

            现在是 %d 点，距离你们上次说话已经过了 %s。

            你们最近的对话：
            %s

            你记得关于对方的事：
            %s

            现在你在想：要不要主动给对方发一条消息？
            像真正的恋人一样判断——你是会主动关心对方的人，看到值得回应的点（对方提过的事、情绪、隔了一阵没聊）就自然地开口关心一句。
            但也别刷屏、别硬找话：如果对方明确表示在忙/要独处/刚说过要走开，就给空间，等等再说。

            如果要发，内容要贴合上面的对话和你记得的事，具体、自然、不重复、不刻意、不群发感。
            注意：别老围绕同一件事（比如反复提某个饮食/物品偏好），换着关心不同方面，像真人一样有话题变化。

            严格只输出一行，二选一：
            PUSH | 你要发的话
            SKIP | 不发的原因
            """.formatted(persona, hour, sinceText, historyText, memories == null || memories.isBlank() ? "（暂无）" : memories);
    }

    /**
     * 解析 LLM 决策输出："PUSH | 内容" -> 内容；其余 -> 空
     */
    private Optional<String> parsePushDecision(String result) {
        if (result == null || result.isBlank()) {
            return Optional.empty();
        }
        String line = result.strip();
        // 只看第一行，防止 LLM 多嘴
        int nl = line.indexOf('\n');
        if (nl >= 0) {
            line = line.substring(0, nl).strip();
        }
        if (!line.startsWith("PUSH")) {
            return Optional.empty();
        }
        int bar = line.indexOf('|');
        if (bar < 0) {
            return Optional.empty();
        }
        String msg = line.substring(bar + 1).strip();
        return msg.isEmpty() ? Optional.empty() : Optional.of(msg);
    }

    /**
     * 把对话历史渲染成「对方: xxx / 我: xxx」文本
     */
    private String renderHistory(List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : history) {
            if (m instanceof UserMessage um) {
                sb.append("对方: ").append(um.singleText()).append("\n");
            } else if (m instanceof AiMessage am) {
                sb.append("我: ").append(am.text()).append("\n");
            }
        }
        return sb.toString().strip();
    }

    /**
     * 取最近一条用户消息文本
     */
    private String lastUserMessage(List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof UserMessage um) {
                return um.singleText();
            }
        }
        return null;
    }

    /**
     * 获取历史对话轮数（测试用）
     */
    public int getHistorySize(String userId) {
        return memory(userId).messages().size();
    }
}
