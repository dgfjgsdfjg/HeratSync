package com.heartsync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartsync.model.ChatMessage;
import com.heartsync.netty.WsSessionManager;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 主动推送服务 — 定时检查并推送问候消息
 * ponytail: 简单规则触发，阶段 2 加 LLM 生成个性化内容
 */
@Service
public class PushService {
    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final WsSessionManager sessionManager;
    private final CompanionService companionService;

    // 上次推送时间（ponytail: 单用户，内存记录）
    private LocalDateTime lastPushTime = null;

    public PushService(WsSessionManager sessionManager, CompanionService companionService) {
        this.sessionManager = sessionManager;
        this.companionService = companionService;
    }

    /**
     * 每 30 分钟检查一次，满足条件则推送
     */
    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void checkAndPush() {
        Set<String> onlineUsers = sessionManager.getOnlineUsers();
        if (onlineUsers.isEmpty()) {
            return;
        }

        // 一小时最多推送一次
        if (lastPushTime != null && lastPushTime.plusHours(1).isAfter(LocalDateTime.now())) {
            return;
        }

        // 根据时间段生成推送内容
        String message = generatePushMessage();
        if (message == null) {
            return;
        }

        // 推送给所有在线用户
        for (String userId : onlineUsers) {
            Channel channel = sessionManager.getChannel(userId);
            if (channel != null && channel.isActive()) {
                try {
                    String json = MAPPER.writeValueAsString(new ChatMessage("push", message));
                    channel.writeAndFlush(new TextWebSocketFrame(json));
                    log.info("主动推送: userId={}, message={}", userId, message);
                } catch (Exception e) {
                    log.error("主动推送失败: userId={}", userId, e);
                }
            }
        }
        lastPushTime = LocalDateTime.now();
    }

    /**
     * 根据时间段生成推送消息
     */
    private String generatePushMessage() {
        int hour = LocalTime.now().getHour();
        if (hour >= 6 && hour < 9) {
            return "早安~ 今天有什么计划吗？";
        } else if (hour >= 22 || hour < 2) {
            return "这么晚了还没睡？";
        } else if (hour >= 12 && hour < 14) {
            return "中午好，吃饭了吗？";
        } else {
            return "在忙什么呢？";
        }
    }
}
