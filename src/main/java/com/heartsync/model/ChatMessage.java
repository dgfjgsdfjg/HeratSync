package com.heartsync.model;

/**
 * WebSocket 消息协议对象
 * 五种类型: chat / token / done / push / ping / pong
 */
public class ChatMessage {
    private String type;      // 消息类型: chat, token, done, push, ping, pong
    private String content;   // 消息内容
    private String messageId; // 消息 ID（done 消息时使用）

    public ChatMessage() {}

    public ChatMessage(String type, String content) {
        this.type = type;
        this.content = content;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
}
