package com.heartsync.service;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heartsync.model.MessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话消息 Mapper（只做追加 + 按用户时序查，不需要复杂多表查询）
 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {
}
