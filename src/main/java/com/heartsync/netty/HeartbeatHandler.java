package com.heartsync.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 心跳检测 Handler
 * - ALL_IDLE(默认 40 秒无收发)：服务端主动发 WebSocket PING 控制帧，
 *   浏览器在协议层自动回 PONG（不依赖前端 JS 定时器，后台标签页被冻结也照回），
 *   PONG 是一次 inbound read，会重置 IdleStateHandler 的 READER_IDLE。
 * - READER_IDLE(默认 120 秒完全无读)：判定连接已死，关闭。
 * 依赖 WebSocketServerProtocolConfig.dropPongFrames(false)，否则 PONG 被上游吞掉，
 * READER_IDLE 无法重置。
 */
public class HeartbeatHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(HeartbeatHandler.class);

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) evt;
            if (e.state() == IdleState.READER_IDLE) {
                // 完全无读到达上限，判定死连接
                log.info("心跳读超时，关闭连接: {}", ctx.channel().id().asShortText());
                ctx.close();
            } else if (e.state() == IdleState.ALL_IDLE) {
                // 一段时间无收发，主动发 PING 探活（浏览器协议层自动回 PONG）
                ctx.writeAndFlush(new PingWebSocketFrame());
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 兼容前端应用层心跳 {"type":"ping"}（前台标签页时的备用保活）
        if (msg instanceof TextWebSocketFrame) {
            String text = ((TextWebSocketFrame) msg).text();
            if ("{\"type\":\"ping\"}".equals(text)) {
                ctx.writeAndFlush(new TextWebSocketFrame("{\"type\":\"pong\"}"));
                ReferenceCountUtil.release(msg);
                return;
            }
        }
        // 其余帧（含浏览器回的 PongWebSocketFrame）继续向下传递
        super.channelRead(ctx, msg);
    }
}
