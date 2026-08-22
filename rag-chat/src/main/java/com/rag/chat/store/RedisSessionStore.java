package com.rag.chat.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.chat.ChatSession;
import com.rag.common.chat.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@link SessionStore} 的 Redis 实现。
 * <p>
 * 会话主体存储为 String（JSON 序列化），key 前缀 {@code rag:chat:session:}；
 * 用户会话索引存储为 Set，key 前缀 {@code rag:chat:user:}，便于按租户+用户维度列表查询。
 * 会话与索引同步设置 TTL，超时未访问自动清理，符合"会话数据按租户+用户维度隔离"约定。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Component
public class RedisSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionStore.class);

    /** 会话主体 key 前缀 */
    private static final String SESSION_KEY_PREFIX = "rag:chat:session:";

    /** 用户会话索引 key 前缀 */
    private static final String USER_INDEX_PREFIX = "rag:chat:user:";

    /** JSON 序列化器（Spring 容器注入或默认创建） */
    private final StringRedisTemplate redisTemplate;

    /** JSON 序列化器 */
    private final ObjectMapper objectMapper;

    /**
     * 构造函数注入。
     *
     * @param redisTemplate Redis 操作模板
     * @param objectMapper  JSON 序列化器
     */
    public RedisSessionStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(ChatSession session, long ttlSeconds) {
        try {
            // 序列化会话主体
            String json = objectMapper.writeValueAsString(session);
            String sessionKey = SESSION_KEY_PREFIX + session.getSessionId();

            // 写入会话主体并设置 TTL
            if (ttlSeconds > 0) {
                redisTemplate.opsForValue().set(sessionKey, json, ttlSeconds, TimeUnit.SECONDS);
            } else {
                redisTemplate.opsForValue().set(sessionKey, json);
            }

            // 维护用户会话索引（Set 结构，便于列表查询）
            String userIndexKey = buildUserIndexKey(session.getTenantId(), session.getUserId());
            redisTemplate.opsForSet().add(userIndexKey, session.getSessionId());
            if (ttlSeconds > 0) {
                // 索引 TTL 与会话保持一致（简化处理，不精确到单条）
                redisTemplate.expire(userIndexKey, ttlSeconds, TimeUnit.SECONDS);
            }
        } catch (JsonProcessingException e) {
            log.error("会话序列化失败 sessionId={}", session.getSessionId(), e);
            throw new RuntimeException("会话保存失败", e);
        }
    }

    @Override
    public ChatSession get(String sessionId) {
        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        String json = redisTemplate.opsForValue().get(sessionKey);
        if (json == null) {
            return null;
        }
        try {
            ChatSession session = objectMapper.readValue(json, ChatSession.class);
            // 刷新最后访问时间并续期 TTL（由调用方传入 ttlSeconds，此处仅更新 lastAccessTime）
            session.setLastAccessTime(System.currentTimeMillis());
            return session;
        } catch (JsonProcessingException e) {
            log.error("会话反序列化失败 sessionId={}", sessionId, e);
            return null;
        }
    }

    @Override
    public void delete(String sessionId) {
        // 先读取会话以获取租户+用户信息，再清理索引
        ChatSession session = get(sessionId);
        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        redisTemplate.delete(sessionKey);

        if (session != null) {
            String userIndexKey = buildUserIndexKey(session.getTenantId(), session.getUserId());
            redisTemplate.opsForSet().remove(userIndexKey, sessionId);
        }
    }

    @Override
    public List<ChatSession> listByUser(Long tenantId, Long userId) {
        String userIndexKey = buildUserIndexKey(tenantId, userId);
        Set<String> sessionIds = redisTemplate.opsForSet().members(userIndexKey);
        if (sessionIds == null || sessionIds.isEmpty()) {
            return new ArrayList<>();
        }

        List<ChatSession> sessions = new ArrayList<>(sessionIds.size());
        for (String sessionId : sessionIds) {
            ChatSession session = get(sessionId);
            if (session != null) {
                sessions.add(session);
            } else {
                // 会话已过期但索引未清理，移除脏索引
                redisTemplate.opsForSet().remove(userIndexKey, sessionId);
            }
        }
        // 按最后访问时间倒序
        sessions.sort((a, b) -> Long.compare(b.getLastAccessTime(), a.getLastAccessTime()));
        return sessions;
    }

    /**
     * 构建用户会话索引 key。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @return Redis key
     */
    private String buildUserIndexKey(Long tenantId, Long userId) {
        return USER_INDEX_PREFIX + tenantId + ":" + userId;
    }
}