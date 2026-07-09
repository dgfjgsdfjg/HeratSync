package com.heartsync.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 对话消息持久化实体（SQLite message 表）
 */
@TableName("message")
public class MessageEntity {

    /** 自增主键，天然按插入顺序单调递增，用于时序排序 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 */
    private String userId;

    /** 角色：user（用户）/ ai（恋人回复） */
    private String role;

    /** 消息内容原文 */
    private String content;

    /** 记录时刻（epoch millis） */
    private Long ts;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Long getTs() { return ts; }
    public void setTs(Long ts) { this.ts = ts; }
}
