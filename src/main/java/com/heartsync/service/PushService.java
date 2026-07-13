package com.heartsync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartsync.model.ChatMessage;
import com.heartsync.model.EventEntity;
import com.heartsync.netty.WsSessionManager;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
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
    /** 事件存储，用于每日日历检查 */
    private final EventStore eventStore;

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
        EventStore eventStore,
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
        this.eventStore = eventStore;
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
        if (!enabled) return;
        if (inQuietHours()) return;

        Set<String> onlineUsers = sessionManager.getOnlineUsers();
        for (String userId : onlineUsers) {
            try {
                // 优先：检查今天是否有日历事件（生日/纪念日等），有则推且跳过冷却
                if (tryCalendarPush(userId)) continue;
                // 常规：LLM 决策推不推
                tryPushForUser(userId);
            } catch (Exception e) {
                log.error("主动推送处理异常, userId={}", userId, e);
            }
        }
    }

    /**
     * 检查用户今天是否有事件（生日/纪念日等），有则让 LLM 生成自然关心的文案
     * @return true 表示已推送日历消息
     */
    private boolean tryCalendarPush(String userId) {
        if (eventStore == null) return false;
        String today = java.time.LocalDate.now().toString();
        List<EventEntity> todayEvents = eventStore.todayAndUpcoming(userId).stream()
            .filter(e -> today.equals(e.getEventDate()))
            .toList();
        if (todayEvents.isEmpty()) return false;

        // 让 LLM 结合人设 + 记忆生成自然关心的消息
        String persona = companionService.loadPersona();
        String history = companionService.getRecentHistory(userId);
        String eventsText = todayEvents.stream()
            .map(e -> e.getTitle() + (e.getContent() != null && !e.getContent().isBlank()
                ? "：" + e.getContent() : ""))
            .reduce((a, b) -> a + "；" + b).orElse("");

        String prompt = """
            %s

            今天是 %s，你和对方的聊天中，今天对对方来说有这些特别的日子：
            %s

            你们最近的对话：
            %s

            请你用自然的语气给对方发一条消息，把关心和祝福融进日常对话里。
            要求：不机械、不死板、像真人一样自然地提起今天是ta的XXX，不要用"⏰"这种图标，
            不要用"今天是XXX～"这种模板语气。可以撒娇、可以调皮、可以温柔，但要把心意表达出来。
            严格只输出你要发的那条消息：
            """.formatted(persona, today, eventsText,
            history.isEmpty() ? "（暂无最近对话）" : history);

        String llmMessage = companionService.generateMessage(prompt);
        if (llmMessage == null || llmMessage.isBlank()) return false;

        sendPush(userId, llmMessage);
        return true;
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
