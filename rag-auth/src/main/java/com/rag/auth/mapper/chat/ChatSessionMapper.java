package com.rag.auth.mapper.chat;

import com.rag.common.entity.ChatSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对话会话表 MyBatis Mapper（v5 新增，对应表 chat_session）。
 * <p>
 * 提供会话的插入、按 sessionId 查询、按租户+用户列表查询、更新会话头信息、
 * 逻辑删除（deleted=1）等能力。所有 SELECT 均显式过滤 deleted=0，保证逻辑删除后不残留。
 * </p>
 *
 * @author rag-tool
 * @since 5.0
 */
@Mapper
public interface ChatSessionMapper {

    /**
     * 插入一条会话记录（首次创建会话时调用，审计/逻辑删除标记由调用方填充）。
     *
     * @param entity 会话实体
     * @return 影响行数
     */
    int insert(ChatSessionEntity entity);

    /**
     * 按会话业务 ID（session_id, UUID）查询会话头，过滤已逻辑删除的记录。
     *
     * @param sessionId 会话 ID
     * @return 会话实体；不存在返回 null
     */
    ChatSessionEntity findBySessionId(@Param("sessionId") String sessionId);

    /**
     * 按租户 + 用户维度查询会话列表（按最后访问时间倒序），过滤已逻辑删除的记录。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @return 会话列表；无数据返回空列表
     */
    List<ChatSessionEntity> listByTenantUser(@Param("tenantId") Long tenantId,
                                             @Param("userId") Long userId);

    /**
     * 按用户 ID 维度查询会话列表（按最后访问时间倒序），过滤已逻辑删除的记录。
     * 用于无租户维度可用的列表场景（当前请求上下文仅有 userId 时）。
     *
     * @param userId 用户 ID
     * @return 会话列表；无数据返回空列表
     */
    List<ChatSessionEntity> listByUserOnly(@Param("userId") Long userId);

    /**
     * 更新会话头信息（title / model_name / status / last_access_time），
     * 仅更新非空字段，逻辑删除的记录不更新。
     *
     * @param entity 会话实体（session_id 为定位条件）
     * @return 影响行数
     */
    int updateByIdempotent(ChatSessionEntity entity);

    /**
     * 逻辑删除会话（deleted=1），仅更新未删除的记录。
     *
     * @param sessionId 会话 ID
     * @return 影响行数
     */
    int logicDeleteBySessionId(@Param("sessionId") String sessionId);
}
