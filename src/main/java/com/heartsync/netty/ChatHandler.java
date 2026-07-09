package com.heartsync.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartsync.model.ChatMessage;
import com.heartsync.service.CompanionService;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket 业务处理 Handler
 * 收发消息、流式推送 token
 */
public class ChatHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final Logger log = LoggerFactory.getLogger(ChatHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AttributeKey<String> USER_ID_KEY = AttributeKey.valueOf("userId");

    private final CompanionService companionService;
    private final WsSessionManager sessionManager;

    public ChatHandler(CompanionService companionService, WsSessionManager sessionManager) {
        this.companionService = companionService;
        this.sessionManager = sessionManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        // 注意：SimpleChannelInboundHandler 会在本方法返回后自动释放 frame，
        // 这里不能再手动 release，否则双重释放触发 IllegalReferenceCountException
        String text = frame.text();

        ChatMessage msg;
        try {
            msg = MAPPER.readValue(text, ChatMessage.class);
        } catch (Exception e) {
            log.warn("消息解析失败: {}", text, e);
            return;
        }

        String userId = ctx.channel().attr(USER_ID_KEY).get();
        if (userId == null) {
            log.warn("未认证的连接发送消息");
            return;
        }

        if ("chat".equals(msg.getType())) {
            handleChat(ctx, userId, msg.getContent());
        }
    }

    /**
     * 处理聊天消息：流式推送 AI 回复
     */
    private void handleChat(ChannelHandlerContext ctx, String userId, String userMessage) {
        StringBuilder fullResponse = new StringBuilder();

        companionService.chat(userId, userMessage)
            .doOnNext(token -> {
                // 每个 token 推送给客户端
                String json;
                try {
                    json = MAPPER.writeValueAsString(new ChatMessage("token", token));
                } catch (Exception e) {
                    log.error("token JSON 序列化失败", e);
                    return;
                }
                ctx.writeAndFlush(new TextWebSocketFrame(json));
                fullResponse.append(token);
            })
            .doOnComplete(() -> {
                // 流结束，发送 done 消息
                String messageId = "msg_" + System.currentTimeMillis();
                ChatMessage done = new ChatMessage("done", "");
                done.setMessageId(messageId);
                try {
                    String json = MAPPER.writeValueAsString(done);
                    ctx.writeAndFlush(new TextWebSocketFrame(json));
                } catch (Exception e) {
                    log.error("done JSON 序列化失败", e);
                }

                // 触发对话完成回调
                companionService.onChatComplete(userId, userMessage, fullResponse.toString());
            })
            .doOnError(error -> {
                log.error("对话流式处理失败: userId={}", userId, error);
                // 兜底消息
                try {
                    String json = MAPPER.writeValueAsString(new ChatMessage("token", "（出了点问题，能再说一遍吗？）"));
                    ctx.writeAndFlush(new TextWebSocketFrame(json));
                } catch (Exception e) {
                    log.error("兜底消息发送失败", e);
                }
            })
            .subscribe(); // 触发订阅
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        sessionManager.onDisconnect(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("WebSocket 异常: channelId={}", ctx.channel().id().asShortText(), cause);
        ctx.close();
    }
}
