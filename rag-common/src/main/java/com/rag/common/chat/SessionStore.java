package com.rag.common.chat;

import java.util.List;

/**
 * 会话存储抽象接口。
 * <p>
 * 定义会话生命周期管理契约：保存、读取、删除、列表查询。
 * 抽象下沉至 {@code rag-common} 模块，具体实现（如 Redis）置于 {@code rag-config} 装配层，
 * 业务层仅依赖此接口，便于后续替换存储介质（如 MySQL / 本地缓存）。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
public interface SessionStore {

    /**
     * 保存或更新会话，并设置 TTL 过期时间。
     *
     * @param session     会话实例
     * @param ttlSeconds  过期时间（秒），<=0 表示不过期
     */
    void save(ChatSession session, long ttlSeconds);

    /**
     * 按会话 ID 读取会话，并刷新最后访问时间（TTL 续期）。
     *
     * @param sessionId 会话 ID
     * @return 会话实例；不存在时返回 {@code null}
     */
    ChatSession get(String sessionId);

    /**
     * 按会话 ID 删除会话及其关联索引。
     *
     * @param sessionId 会话 ID
     */
    void delete(String sessionId);

    /**
     * 查询指定租户 + 用户下的全部会话列表（按最后访问时间倒序）。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @return 会话列表；无数据时返回空列表
     */
    List<ChatSession> listByUser(Long tenantId, Long userId);
}
