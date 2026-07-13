package com.heartsync.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LangChain4j ChatMemoryStore 的 SQLite 实现
 * 将对话历史持久化到 SQLite，同时对接 ChatMemory 自动窗口裁剪
 */
public class SqliteChatMemoryStore implements ChatMemoryStore {
    private static final Logger log = LoggerFactory.getLogger(SqliteChatMemoryStore.class);
    private static final int LOAD_ROUNDS = 10;

    private final ConversationStore conversationStore;

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
            return conversationStore.loadRecent(memoryId.toString(), LOAD_ROUNDS);
        } catch (Exception e) {
            log.error("加载对话历史失败, userId={}", memoryId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 写入对话变更：只追加新增的消息（首尾各一轮）
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (memoryId == null || messages == null || messages.isEmpty()) return;
        String userId = memoryId.toString();
        try {
            // ChatMemory 回调时会传全量消息列表，
            // 这里做增量持久化：只追加列表中末两条（最新一轮）
            int size = messages.size();
            if (size >= 1) {
                ChatMessage last = messages.get(size - 1);
                String role = (last instanceof AiMessage) ? ConversationStore.ROLE_AI : ConversationStore.ROLE_USER;
                conversationStore.append(userId, role, textOf(last));
            }
            if (size >= 2) {
                ChatMessage secondLast = messages.get(size - 2);
                String role = (secondLast instanceof AiMessage) ? ConversationStore.ROLE_AI : ConversationStore.ROLE_USER;
                conversationStore.append(userId, role, textOf(secondLast));
            }
        } catch (Exception e) {
            log.error("持久化对话失败, userId={}", userId, e);
        }
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
