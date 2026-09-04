package com.rag.auth.mapper.chat;

import com.rag.common.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对话消息表 MyBatis Mapper（v5 新增，对应表 chat_message）。
 * <p>
 * 提供消息批量插入（user+assistant 一轮两条，单条 INSERT 原子写入）、
 * 按 sessionId 升序查询（完整回放）、按会话逻辑删除等能力。
 * 所有 SELECT 均显式过滤 deleted=0。
 * </p>
 *
 * @author rag-tool
 * @since 5.0
 */
@Mapper
public interface ChatMessageMapper {

    /**
     * 插入单条消息。
     *
     * @param entity 消息实体
     * @return 影响行数
     */
    int insert(ChatMessageEntity entity);

    /**
     * 批量插入消息（每轮问答的 user + assistant 两条合并为一条 INSERT，保证原子性）。
     *
     * @param entities 消息实体列表
     * @return 影响行数
     */
    int batchInsert(@Param("list") List<ChatMessageEntity> entities);

    /**
     * 按会话 ID 查询该会话的全部消息（按 create_time 升序，保证回放顺序），
     * 过滤已逻辑删除的记录。
     *
     * @param sessionId 会话 ID
     * @return 消息列表；无数据返回空列表
     */
    List<ChatMessageEntity> listBySessionId(@Param("sessionId") String sessionId);

    /**
     * 逻辑删除某会话下的全部消息（deleted=1），仅更新未删除的记录。
     *
     * @param sessionId 会话 ID
     * @return 影响行数
     */
    int logicDeleteBySessionId(@Param("sessionId") String sessionId);
}
