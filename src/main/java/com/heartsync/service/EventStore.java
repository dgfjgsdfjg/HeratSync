package com.heartsync.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.heartsync.model.EventEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 事件存储（SQLite event 表）—— 按时间检索事件
 */
@Service
public class EventStore {
    private static final Logger log = LoggerFactory.getLogger(EventStore.class);

    private final EventMapper eventMapper;

    public EventStore(EventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    /**
     * 追加事件（同标题 + 同日期 skip 防重）
     */
    public void append(String userId, String title, String eventDate, String content) {
        Long cnt = eventMapper.selectCount(new LambdaQueryWrapper<EventEntity>()
            .eq(EventEntity::getUserId, userId)
            .eq(EventEntity::getTitle, title)
            .eq(EventEntity::getEventDate, eventDate));
        if (cnt != null && cnt > 0) {
            return; // 已存在，幂等跳过
        }
        EventEntity e = new EventEntity();
        e.setUserId(userId);
        e.setTitle(title);
        e.setEventDate(eventDate);
        e.setContent(content == null || content.isBlank() ? "" : content);
        e.setCreatedTs(System.currentTimeMillis());
        eventMapper.insert(e);
        log.info("事件写入: userId={}, title={}, date={}", userId, title, eventDate);
    }

    /**
     * 查某用户最近 N 条事件（含过去和未来），按日期近→远
     */
    public List<EventEntity> recent(String userId, int n) {
        return eventMapper.selectList(
            new LambdaQueryWrapper<EventEntity>()
                .eq(EventEntity::getUserId, userId)
                .orderByDesc(EventEntity::getEventDate)
                .last("LIMIT " + n));
    }

    /**
     * 查今天的 + 即将到来的未来事件（用于主动提醒）
     */
    public List<EventEntity> todayAndUpcoming(String userId) {
        String today = LocalDate.now().toString();
        List<EventEntity> all = eventMapper.selectList(
            new LambdaQueryWrapper<EventEntity>()
                .eq(EventEntity::getUserId, userId)
                .ge(EventEntity::getEventDate, today)
                .orderByAsc(EventEntity::getEventDate));
        return all == null ? Collections.emptyList() : all;
    }

    /**
     * 渲染为 prompt 可用的文本（最近事件 + 即将到来）
     */
    public String renderForRecall(String userId) {
        // 取最近 8 条事件 + 即将到来
        String today = LocalDate.now().toString();
        List<EventEntity> all = eventMapper.selectList(
            new LambdaQueryWrapper<EventEntity>()
                .eq(EventEntity::getUserId, userId)
                .orderByDesc(EventEntity::getEventDate));
        if (all == null || all.isEmpty()) {
            return "";
        }
        return all.stream()
            .map(e -> formatEvent(e))
            .collect(Collectors.joining("\n"));
    }

    private String formatEvent(EventEntity e) {
        StringBuilder sb = new StringBuilder()
            .append("[").append(e.getEventDate()).append("] ")
            .append(e.getTitle());
        if (e.getContent() != null && !e.getContent().isBlank()) {
            sb.append(" - ").append(e.getContent());
        }
        // 标注过去/今天/未来
        String today = LocalDate.now().toString();
        if (e.getEventDate().compareTo(today) < 0) {
            sb.append("（已过去）");
        } else if (e.getEventDate().equals(today)) {
            sb.append("★（今天！）");
        } else {
            sb.append("（未来/将发生）");
        }
        return sb.toString();
    }
}
