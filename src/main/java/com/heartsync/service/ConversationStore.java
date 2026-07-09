package com.heartsync.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heartsync.model.MessageEntity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话历史持久化存储（SQLite via MyBatis-Plus）
 * 职责：逐条追加对话、按用户载入最近 N 轮、查最后互动时刻
 */
@Service
public class ConversationStore {
    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    public static final String ROLE_USER = "user";
    public static final String ROLE_AI = "ai";

    private final MessageMapper messageMapper;

    public ConversationStore(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    /**
     * 追加一条消息
     */
    public void append(String userId, String role, String content) {
        MessageEntity e = new MessageEntity();
        e.setUserId(userId);
        e.setRole(role);
        e.setContent(content);
        e.setTs(System.currentTimeMillis());
        messageMapper.insert(e);
    }

    /**
     * 载入某用户最近 N 轮对话（N*2 条），按时间正序返回，可直接作 LLM history
     */
    public List<ChatMessage> loadRecent(String userId, int rounds) {
        List<MessageEntity> rows = messageMapper.selectList(
            new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getUserId, userId)
                .orderByDesc(MessageEntity::getId)      // 按自增主键取最新
                .last("LIMIT " + (rounds * 2)));
        Collections.reverse(rows);                       // 翻回时间正序
        List<ChatMessage> out = new ArrayList<>(rows.size());
        for (MessageEntity r : rows) {
            out.add(ROLE_USER.equals(r.getRole())
                ? new UserMessage(r.getContent())
                : new AiMessage(r.getContent()));
        }
        return out;
    }

    /**
     * 查某用户最后一条消息的时刻（epoch millis）；无记录返回 null
     */
    public Long lastTs(String userId) {
        MessageEntity r = messageMapper.selectOne(
            new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getUserId, userId)
                .orderByDesc(MessageEntity::getId)
                .last("LIMIT 1"));
        return r == null ? null : r.getTs();
    }
}
