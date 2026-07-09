package com.heartsync.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 事件实体（SQLite event 表）—— 有时间点的事：明天爬山、第一次约会、纪念日
 * 区别于 facts/ 里的永久属性（喜好、身份），事件天生按时间检索。
 */
@TableName("event")
public class EventEntity {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 */
    private String userId;

    /** 事件标题（简短，如「爬岳麓山」） */
    private String title;

    /** 事件日期（YYYY-MM-DD） */
    private String eventDate;

    /** 补充描述，可为空 */
    private String content;

    /** 记录时刻（epoch millis） */
    private Long createdTs;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getEventDate() { return eventDate; }
    public void setEventDate(String eventDate) { this.eventDate = eventDate; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Long getCreatedTs() { return createdTs; }
    public void setCreatedTs(Long createdTs) { this.createdTs = createdTs; }
}
