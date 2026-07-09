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
     * 不踢旧连接：单用户多标签页都保留，避免同 userId 互踢造成重连风暴。
     * 主动推送发往最近一次登录的连接。
     */
    public void onConnect(String userId, Channel channel) {
        userChannels.put(userId, channel);
        channelUsers.put(channel.id().asShortText(), userId);
        allChannels.add(channel);
        log.info("用户上线: userId={}, channelId={}, 当前连接数: {}", userId, channel.id().asShortText(), allChannels.size());
    }

    /**
     * 用户下线，清理映射
     * 只在 userChannels 里当前映射的就是本连接时才移除，
     * 否则会把「同用户更新的连接」误删（旧连接下线不该影响新连接）
     */
    public void onDisconnect(Channel channel) {
        String channelId = channel.id().asShortText();
        String userId = channelUsers.remove(channelId);
        if (userId != null) {
            // 条件移除：仅当映射值仍是本连接
            userChannels.remove(userId, channel);
            log.info("用户下线: userId={}, channelId={}, 当前连接数: {}", userId, channelId, allChannels.size());
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
