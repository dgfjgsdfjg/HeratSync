package com.heartsync.service;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heartsync.model.EventEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 事件 Mapper —— 按用户 + 时间检索
 */
@Mapper
public interface EventMapper extends BaseMapper<EventEntity> {
}
