package com.heartsync.netty;

import com.heartsync.service.CompanionService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

/**
 * Netty WebSocket 服务端
 * 独立端口 8081，处理 WebSocket 长连接
 */
@Component
public class NettyWsServer {
    private static final Logger log = LoggerFactory.getLogger(NettyWsServer.class);

    private final int port;
    private final String wsPath;
    private final String authToken;
    private final CompanionService companionService;

    private EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();
    private Channel serverChannel;
    private final WsSessionManager sessionManager = new WsSessionManager();

    /**
     * 构造函数，通过 Spring @Value 注入配置
     */
    public NettyWsServer(
        @Value("${heartsync.netty.port}") int port,
        @Value("${heartsync.netty.ws-path}") String wsPath,
        @Value("${heartsync.auth.token}") String authToken,
        CompanionService companionService
    ) {
        this.port = port;
        this.wsPath = wsPath;
        this.authToken = authToken;
        this.companionService = companionService;
    }

    /**
     * 启动 Netty WebSocket 服务
     */
    @PostConstruct
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);   // boss 线程：接收连接

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    // HTTP 编解码
                    pipeline.addLast(new HttpServerCodec());
                    // HTTP 消息聚合（最大 64KB）
                    pipeline.addLast(new HttpObjectAggregator(65536));
                    // 认证（WebSocket 握手前）
                    pipeline.addLast(new AuthHandler(authToken, sessionManager));
                    // WebSocket 协议升级
                    // checkStartsWith=true: URI 带 query 参数(?token=xxx)时用前缀匹配，
                    // 否则默认精确匹配会因 "/ws/chat?token=xxx" != "/ws/chat" 而不握手
                    WebSocketServerProtocolConfig wsConfig = WebSocketServerProtocolConfig.newBuilder()
                        .websocketPath(wsPath)
                        .checkStartsWith(true)
                        .build();
                    pipeline.addLast(new WebSocketServerProtocolHandler(wsConfig));
                    // 空闲检测（60 秒无读 -> 断开）
                    pipeline.addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
                    // 心跳处理
                    pipeline.addLast(new HeartbeatHandler());
                    // 业务处理
                    pipeline.addLast(new ChatHandler(companionService, sessionManager));
                }
            })
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.SO_KEEPALIVE, true);

        serverChannel = bootstrap.bind(port).sync().channel();
        log.info("Netty WebSocket 服务启动: port={}, path={}", port, wsPath);
    }

    /**
     * 关闭 Netty WebSocket 服务
     */
    @PreDestroy
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        workerGroup.shutdownGracefully();
        log.info("Netty WebSocket 服务已关闭");
    }

    /**
     * 获取会话管理器（供外部推送用）
     */
    public WsSessionManager getSessionManager() {
        return sessionManager;
    }
}
