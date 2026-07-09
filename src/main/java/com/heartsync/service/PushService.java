package com.heartsync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartsync.model.ChatMessage;
import com.heartsync.netty.WsSessionManager;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 主动消息推送服务 — 拟人化：规则闸门 + LLM 决策
 * 只在页面开着(WebSocket 在线)时推。绝大多数轮询被规则挡掉，不烧 LLM；
 * 过闸后交给 CompanionService 结合最近对话+记忆判断「该不该推、推什么」。
 */
@Service
public class PushService {
    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WsSessionManager sessionManager;
    private final CompanionService companionService;

    // 配置项（application.yml 的 heartsync.push.*，可自行修改）
    private final boolean enabled;
    private final int quietPeriodSeconds;
    private final int cooldownSeconds;
    private final int jitterSeconds;
    private final boolean quietHoursEnabled;
    private final int quietHoursStart;
    private final int quietHoursEnd;

    // 每用户下次允许主动推的时刻（冷却 + 随机抖动后设定）
    private final ConcurrentHashMap<String, LocalDateTime> nextPushAllowedAt = new ConcurrentHashMap<>();

    public PushService(
        WsSessionManager sessionManager,
        CompanionService companionService,
        @Value("${heartsync.push.enabled:true}") boolean enabled,
        @Value("${heartsync.push.quiet-period-seconds:300}") int quietPeriodSeconds,
        @Value("${heartsync.push.cooldown-seconds:720}") int cooldownSeconds,
        @Value("${heartsync.push.jitter-seconds:180}") int jitterSeconds,
        @Value("${heartsync.push.quiet-hours-enabled:true}") boolean quietHoursEnabled,
        @Value("${heartsync.push.quiet-hours-start:1}") int quietHoursStart,
        @Value("${heartsync.push.quiet-hours-end:7}") int quietHoursEnd
    ) {
        this.sessionManager = sessionManager;
        this.companionService = companionService;
        this.enabled = enabled;
        this.quietPeriodSeconds = quietPeriodSeconds;
        this.cooldownSeconds = cooldownSeconds;
        this.jitterSeconds = jitterSeconds;
        this.quietHoursEnabled = quietHoursEnabled;
        this.quietHoursStart = quietHoursStart;
        this.quietHoursEnd = quietHoursEnd;
    }

    /**
     * 轮询检查：间隔由 heartsync.push.poll-interval-ms 控制
     */
    @Scheduled(fixedRateString = "${heartsync.push.poll-interval-ms:60000}")
    public void checkAndPush() {
        if (!enabled) {
            return;
        }
        // 免打扰时段：直接跳过
        if (inQuietHours()) {
            return;
        }
        Set<String> onlineUsers = sessionManager.getOnlineUsers();
        for (String userId : onlineUsers) {
            try {
                tryPushForUser(userId);
            } catch (Exception e) {
                log.error("主动推送处理异常, userId={}", userId, e);
            }
        }
    }

    /**
     * 单个用户的推送判断 + 执行
     */
    private void tryPushForUser(String userId) {
        LocalDateTime now = LocalDateTime.now();

        // 闸门1：静默期——刚聊完不打扰
        LocalDateTime lastInteraction = companionService.getLastInteractionTime(userId);
        if (lastInteraction == null) {
            return; // 没聊过不主动搭话
        }
        if (lastInteraction.plusSeconds(quietPeriodSeconds).isAfter(now)) {
            return;
        }

        // 闸门2：冷却——两次主动推之间的间隔
        LocalDateTime allowedAt = nextPushAllowedAt.get(userId);
        if (allowedAt != null && allowedAt.isAfter(now)) {
            return;
        }

        // 过闸：交给 LLM 决策该不该推、推什么
        log.info("主动推送过闸，咨询 LLM: userId={}", userId);
        Optional<String> decision = companionService.decideProactiveMessage(userId);
        // 无论推不推，都重置冷却窗口，避免过闸后每轮都调 LLM
        nextPushAllowedAt.put(userId, now.plusSeconds(nextCooldown()));

        if (decision.isEmpty()) {
            return; // LLM 判断此刻不必打扰
        }
        sendPush(userId, decision.get());
    }

    /**
     * 通过 WebSocket 推送主动消息
     */
    private void sendPush(String userId, String message) {
        Channel channel = sessionManager.getChannel(userId);
        if (channel == null || !channel.isActive()) {
            return;
        }
        try {
            String json = MAPPER.writeValueAsString(new ChatMessage("push", message));
            channel.writeAndFlush(new TextWebSocketFrame(json));
            log.info("主动推送: userId={}, message={}", userId, message);
        } catch (Exception e) {
            log.error("主动推送发送失败: userId={}", userId, e);
        }
    }

    /**
     * 冷却时长 = 基准 + [0, jitter) 随机抖动，避免精确定时太机械
     */
    private int nextCooldown() {
        int jitter = jitterSeconds > 0 ? ThreadLocalRandom.current().nextInt(jitterSeconds) : 0;
        return cooldownSeconds + jitter;
    }

    /**
     * 是否处于免打扰时段
     */
    private boolean inQuietHours() {
        if (!quietHoursEnabled) {
            return false;
        }
        int hour = LocalDateTime.now().getHour();
        if (quietHoursStart <= quietHoursEnd) {
            return hour >= quietHoursStart && hour < quietHoursEnd;
        }
        // 跨午夜（如 22-7）
        return hour >= quietHoursStart || hour < quietHoursEnd;
    }
}
