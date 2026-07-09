package com.heartsync.netty;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 连接会话管理器
 * 维护 userId ↔ Channel 双向映射，支持主动推送
 */
public class WsSessionManager {
    private static final Logger log = LoggerFactory.getLogger(WsSessionManager.class);

    // userId -> Channel（主动推送用）
    private final ConcurrentHashMap<String, Channel> userChannels = new ConcurrentHashMap<>();
    // ChannelId -> userId（断连清理用）
    private final ConcurrentHashMap<String, String> channelUsers = new ConcurrentHashMap<>();
    // 所有活跃连接
    private final ChannelGroup allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    /**
     * 用户上线，绑定连接
     */
    public void onConnect(String userId, Channel channel) {
        Channel old = userChannels.put(userId, channel);
        if (old != null && old != channel) {
            // 同一用户旧连接存在，关闭旧连接
            log.info("用户重复登录，关闭旧连接: userId={}", userId);
            old.close();
        }
        channelUsers.put(channel.id().asShortText(), userId);
        allChannels.add(channel);
        log.info("用户上线: userId={}, channelId={}, 当前在线: {}", userId, channel.id().asShortText(), allChannels.size());
    }

    /**
     * 用户下线，清理映射
     */
    public void onDisconnect(Channel channel) {
        String channelId = channel.id().asShortText();
        String userId = channelUsers.remove(channelId);
        if (userId != null) {
            userChannels.remove(userId);
            log.info("用户下线: userId={}, channelId={}, 当前在线: {}", userId, channelId, allChannels.size());
        }
        allChannels.remove(channel);
    }

    /**
     * 根据 userId 查找连接（主动推送用）
     */
    public Channel getChannel(String userId) {
        return userChannels.get(userId);
    }

    /**
     * 获取在线用户数
     */
    public int getOnlineCount() {
        return allChannels.size();
    }

    /**
     * 获取所有在线 userId
     */
    public java.util.Set<String> getOnlineUsers() {
        return new java.util.HashSet<>(userChannels.keySet());
    }
}
