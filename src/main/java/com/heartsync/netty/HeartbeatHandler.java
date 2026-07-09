package com.heartsync.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 心跳检测 Handler
 * 60 秒无读触发空闲 -> 发送 Ping，120 秒无读 -> 断开连接
 */
public class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatHandler.class);

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.READER_IDLE) {
                log.info("心跳超时，关闭连接: {}", ctx.channel().id().asShortText());
                ctx.close();
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 处理客户端心跳 ping/pong
        if (msg instanceof TextWebSocketFrame) {
            String text = ((TextWebSocketFrame) msg).text();
            if ("{\"type\":\"ping\"}".equals(text)) {
                // 回复 pong
                ctx.writeAndFlush(new TextWebSocketFrame("{\"type\":\"pong\"}"));
                ReferenceCountUtil.release(msg);
                return;
            }
        }
        super.channelRead(ctx, msg);
    }
}
