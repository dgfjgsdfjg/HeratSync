package com.heartsync.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * WebSocket 握手阶段认证 Handler
 * 从 URL query 参数提取 token，校验后放行
 */
public class AuthHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(AuthHandler.class);

    private final String expectedToken;
    private final WsSessionManager sessionManager;

    public AuthHandler(String expectedToken, WsSessionManager sessionManager) {
        this.expectedToken = expectedToken;
        this.sessionManager = sessionManager;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            FullHttpRequest request = (FullHttpRequest) msg;
            String token = extractToken(request.uri());

            if (token == null || !token.equals(expectedToken)) {
                log.warn("认证失败: token={}", token);
                // 返回 401 并关闭连接
                var response = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                    request.protocolVersion(), HttpResponseStatus.UNAUTHORIZED);
                ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
                io.netty.util.ReferenceCountUtil.release(msg);
                return;
            }

            // 认证成功，绑定 userId（阶段 1 用 token 作为 userId）
            String userId = "user-" + token.substring(0, Math.min(8, token.length()));
            sessionManager.onConnect(userId, ctx.channel());

            // 将 userId 存到 channel attr 中，后续 Handler 使用
            ctx.channel().attr(io.netty.util.AttributeKey.valueOf("userId")).set(userId);

            // 移除自身（认证只做一次）
            ctx.pipeline().remove(this);
            super.channelRead(ctx, msg);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    /**
     * 从 URI query 参数中提取 token
     */
    private String extractToken(String uri) {
        try {
            URI u = new URI(uri);
            String query = u.getQuery();
            if (query == null) return null;
            // 解析 query 参数
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && "token".equals(kv[0])) {
                    return kv[1];
                }
            }
        } catch (Exception e) {
            log.error("URI 解析失败: {}", uri, e);
        }
        return null;
    }
}
