package com.heartsync.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangChain4j ChatMemoryStore 的 SQLite 实现
 * 将对话历史持久化到 SQLite，同时对接 ChatMemory 自动窗口裁剪
 */
public class SqliteChatMemoryStore implements ChatMemoryStore {
    private static final Logger log = LoggerFactory.getLogger(SqliteChatMemoryStore.class);
    private static final int LOAD_ROUNDS = 10;

    private final ConversationStore conversationStore;
    /** 每条用户最后持久化时的消息条数，用于增量写入防重复 */
    private final ConcurrentHashMap<String, Integer> lastPersistedSize = new ConcurrentHashMap<>();

    public SqliteChatMemoryStore(ConversationStore conversationStore) {
        this.conversationStore = conversationStore;
    }

    /**
     * 从 SQLite 加载最近 N 轮对话
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        if (memoryId == null) return Collections.emptyList();
        try {
            List<ChatMessage> msgs = conversationStore.loadRecent(memoryId.toString(), LOAD_ROUNDS);
            // 记录当前已持久化的条数
            lastPersistedSize.put(memoryId.toString(), msgs.size());
            return msgs;
        } catch (Exception e) {
            log.error("加载对话历史失败, userId={}", memoryId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 增量写入：只持久化新增的消息，防止每次 add 都重复写
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (memoryId == null || messages == null || messages.isEmpty()) return;
        String userId = memoryId.toString();
        int prevSize = lastPersistedSize.getOrDefault(userId, 0);
        // 新消息从 prevSize 开始，已持久化的跳过
        for (int i = prevSize; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            String role = (msg instanceof AiMessage) ? ConversationStore.ROLE_AI : ConversationStore.ROLE_USER;
            conversationStore.append(userId, role, textOf(msg));
        }
        lastPersistedSize.put(userId, messages.size());
    }

    /**
     * 删除指定用户的全部对话历史
     */
    @Override
    public void deleteMessages(Object memoryId) {
        // SQLite 里归档即可，暂不实现物理删除
        log.info("deleteMessages 暂未实现, userId={}", memoryId);
    }

    private String textOf(ChatMessage msg) {
        if (msg instanceof AiMessage) return ((AiMessage) msg).text();
        if (msg instanceof UserMessage) return ((UserMessage) msg).singleText();
        return msg.toString();
    }
}
