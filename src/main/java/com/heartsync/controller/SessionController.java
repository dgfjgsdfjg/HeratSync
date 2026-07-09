package com.heartsync.controller;

import com.heartsync.netty.WsSessionManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SessionController {
    private final WsSessionManager sessionManager;

    public SessionController(WsSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @GetMapping("/sessions")
    public Map<String, Object> getSessions() {
        return Map.of(
            "onlineCount", sessionManager.getOnlineCount(),
            "onlineUsers", sessionManager.getOnlineUsers()
        );
    }
}
