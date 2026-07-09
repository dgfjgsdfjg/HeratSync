package com.heartsync.controller;

import com.heartsync.netty.NettyWsServer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SessionController {
    private final NettyWsServer nettyWsServer;

    public SessionController(NettyWsServer nettyWsServer) {
        this.nettyWsServer = nettyWsServer;
    }

    @GetMapping("/sessions")
    public Map<String, Object> getSessions() {
        return Map.of(
            "onlineCount", nettyWsServer.getSessionManager().getOnlineCount(),
            "onlineUsers", nettyWsServer.getSessionManager().getOnlineUsers()
        );
    }
}
