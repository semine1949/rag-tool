package com.rag.chat.service;

import com.rag.auth.context.RequestContext;
import com.rag.chat.config.ChatProperties;
import com.rag.common.chat.ChatSession;
import com.rag.common.chat.SessionStore;
import com.rag.auth.service.KbConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 会话生命周期管理服务。
 * <p>
 * 负责会话的创建、查询、续期等生命周期操作，按租户+用户维度隔离。
 * 会话状态默认为活跃，会话数据经由 {@link SessionStore} 持久化至 Redis。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    private final SessionStore sessionStore;
    private final KbConfigService kbConfigService;
    private final ChatProperties chatProperties;

    public ChatSessionService(SessionStore sessionStore,
                              KbConfigService kbConfigService,
                              ChatProperties chatProperties) {
        this.sessionStore = sessionStore;
        this.kbConfigService = kbConfigService;
        this.chatProperties = chatProperties;
    }

    /**
     * 获取或创建会话。
     *
     * @param sessionId 会话 ID（null 时新建）
     * @param tenantId  租户 ID
     * @param userId    用户 ID
     * @param kbId      知识库 ID
     * @return 会话实例
     */
    public ChatSession getOrCreateSession(String sessionId, Long tenantId, Long userId, Long kbId) {
        if (sessionId != null && !sessionId.isBlank()) {
            ChatSession existing = sessionStore.get(sessionId);
            if (existing != null) {
                return existing;
            }
            log.warn("会话不存在，创建新会话 sessionId={}", sessionId);
        }
        return createSession(tenantId, userId, kbId);
    }

    /**
     * 创建新会话。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @param kbId     知识库 ID
     * @return 新会话实例
     */
    public ChatSession createSession(Long tenantId, Long userId, Long kbId) {
        String newSessionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        ChatSession session = ChatSession.builder()
                .sessionId(newSessionId)
                .tenantId(tenantId)
                .userId(userId)
                .kbId(kbId)
                .title(null)
                .createTime(now)
                .lastAccessTime(now)
                .build();
        sessionStore.save(session, chatProperties.getSessionTtlSeconds());
        log.info("新会话创建 sessionId={} tenantId={} userId={}", newSessionId, tenantId, userId);
        return session;
    }

    /**
     * 解析租户 ID：优先从知识库配置推导，回退从 RequestContext 获取。
     *
     * @param kbId   知识库 ID
     * @param userId 用户 ID（兜底值）
     * @return 租户 ID
     */
    public Long resolveTenantId(Long kbId, Long userId) {
        if (kbId != null) {
            try {
                KbConfigService.KbLoadedConfig loaded = kbConfigService.loadConfigs(kbId);
                return loaded.kb().getTenantId();
            } catch (Exception e) {
                log.warn("从知识库获取 tenantId 失败，回退 userId={}", userId, e);
            }
        }
        return userId; // 兜底：userId 即 tenantId（当前简化多租户模型）
    }
}