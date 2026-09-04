package com.rag.chat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.auth.context.RequestContext;
import com.rag.auth.mapper.chat.ChatMessageMapper;
import com.rag.auth.mapper.chat.ChatSessionMapper;
import com.rag.chat.config.ChatProperties;
import com.rag.common.chat.ChatHistoryMessage;
import com.rag.common.chat.ChatMessage;
import com.rag.common.chat.ChatSession;
import com.rag.common.chat.Citation;
import com.rag.common.chat.SessionStore;
import com.rag.common.entity.ChatMessageEntity;
import com.rag.common.entity.ChatSessionEntity;
import com.rag.auth.service.KbConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 会话生命周期管理服务（v5：MySQL + Redis 双存储）。
 * <p>
 * 负责会话的创建、查询、续期、清空等生命周期操作，按租户+用户维度隔离。
 * 会话数据采用双存储策略：
 * <ul>
 *   <li>MySQL（{@code chat_session}/{@code chat_message} 双表）：全量持久化，进程重启不丢、可完整回放；</li>
 *   <li>Redis（{@link SessionStore}）：实时上下文缓存（带 TTL），会话消息在 Redis 中累积，多轮对话不每轮查库。</li>
 * </ul>
 * Redis 数据丢失时，本服务可从 MySQL 按 sessionId 重建会话与消息并回写 Redis，
 * 满足"Redis 丢失可用 MySQL 重建"的验收约束。
 * </p>
 *
 * @author rag-tool
 * @since 5.0
 */
@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    /** 状态：活跃 */
    private static final int STATUS_ACTIVE = 1;
    /** 逻辑删除标记：未删除 */
    private static final int NOT_DELETED = 0;
    /** 逻辑删除标记：已删除 */
    private static final int DELETED = 1;
    /** 会话标题最大长度（由首条用户消息截取） */
    private static final int TITLE_MAX_LEN = 50;

    private final SessionStore sessionStore;
    private final KbConfigService kbConfigService;
    private final ChatProperties chatProperties;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public ChatSessionService(SessionStore sessionStore,
                              KbConfigService kbConfigService,
                              ChatProperties chatProperties,
                              ChatSessionMapper sessionMapper,
                              ChatMessageMapper messageMapper,
                              ObjectMapper objectMapper) {
        this.sessionStore = sessionStore;
        this.kbConfigService = kbConfigService;
        this.chatProperties = chatProperties;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取或创建会话（Redis → MySQL 双层读取）。
     * <p>
     * 命中 Redis 直接返回；Redis 未命中时先从 MySQL 按 sessionId 重建（含历史消息并回写 Redis），
     * 实现多轮对话上下文在 Redis 丢失后的降级恢复；均不存在才新建会话。
     * </p>
     *
     * @param sessionId 会话 ID（null 时新建）
     * @param tenantId  租户 ID
     * @param userId    用户 ID
     * @param kbId      知识库 ID
     * @return 会话实例
     */
    public ChatSession getOrCreateSession(String sessionId, Long tenantId, Long userId, Long kbId) {
        if (sessionId != null && !sessionId.isBlank()) {
            // ① 优先 Redis（实时上下文缓存）
            ChatSession existing = sessionStore.get(sessionId);
            if (existing != null) {
                return existing;
            }
            log.warn("会话 Redis 未命中，尝试从 MySQL 重建 sessionId={}", sessionId);
            // ② Redis 丢失时，从 MySQL 按 sessionId 重建会话上下文
            ChatSession rebuilt = rebuildFromDb(sessionId, tenantId, userId);
            if (rebuilt != null) {
                // 回写 Redis 缓存，续 TTL，避免后续每轮查库
                sessionStore.save(rebuilt, chatProperties.getSessionTtlSeconds());
                log.info("会话已由 MySQL 重建并回写 Redis sessionId={} messages={}",
                        sessionId, rebuilt.getMessages().size());
                return rebuilt;
            }
            log.warn("会话 MySQL 亦不存在，创建新会话 sessionId={}", sessionId);
        }
        return createSession(tenantId, userId, kbId);
    }

    /**
     * 创建新会话：写入 MySQL（chat_session 一行，deleted=0）并同步 Redis。
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

        // ① MySQL 持久化会话头
        try {
            ChatSessionEntity entity = toSessionEntity(session);
            sessionMapper.insert(entity);
            log.info("新会话已写入 MySQL sessionId={} tenantId={} userId={}", newSessionId, tenantId, userId);
        } catch (Exception e) {
            // MySQL 写入失败不阻断对话主流程，仅记录；Redis 仍可用，后续 saveRound 会重试落库
            log.error("会话 MySQL 写入失败 sessionId={}", newSessionId, e);
        }

        // ② 同步写入 Redis（实时上下文）
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

    /**
     * 每轮问答统一持久化（MySQL 全量 + Redis 上下文，双写）。
     * <p>
     * 由 {@code ChatMessageService} 在各入口调用。逻辑：
     * ① 确保会话头在 MySQL 存在并更新（title/last_access/model_name）；
     * ② 将 user + assistant 两条消息原子批量写入 {@code chat_message}（同事务）；
     * ③ 事务提交后将已追加消息的会话同步写回 Redis（实时上下文，续 TTL）。
     * </p>
     *
     * @param session          当前会话（调用方已 {@code appendMessage} 填充本轮消息）
     * @param userContent      用户本轮提问内容
     * @param assistantContent 助手本轮回答内容（可为空字符串，空则不落 assistant 行，如流式异常中断场景）
     * @param citationsJson    助手消息引用 JSON（assistant 落库用，user 消息引用为空）
     * @param modelName        本轮实际使用的对话模型名
     * @param responseTimeMs   本轮回答耗时（毫秒）
     */
    @Transactional
    public void saveRound(ChatSession session, String userContent, String assistantContent,
                          String citationsJson, String modelName, Long responseTimeMs) {
        long now = System.currentTimeMillis();
        // ① 会话头 upsert：不存在则插入，存在则更新关键字段
        upsertSessionHead(session, modelName, userContent, now);

        // ② 消息双写（user + assistant，同一条 INSERT 保证原子性）
        List<ChatMessageEntity> messages = new ArrayList<>(2);
        messages.add(buildMessageEntity(session, ChatMessage.ROLE_USER, userContent,
                null, modelName, null, now));
        if (assistantContent != null && !assistantContent.isBlank()) {
            messages.add(buildMessageEntity(session, ChatMessage.ROLE_ASSISTANT, assistantContent,
                    citationsJson, modelName, responseTimeMs, now));
        }
        try {
            messageMapper.batchInsert(messages);
            log.info("本轮消息已写入 MySQL sessionId={} 条数={} model={} elapsedMs={}",
                    session.getSessionId(), messages.size(), modelName, responseTimeMs);
        } catch (Exception e) {
            log.error("本轮消息 MySQL 写入失败 sessionId={}", session.getSessionId(), e);
        }

        // ③ 同步写回 Redis（实时上下文缓存，会话对象已由调用方 appendMessage 携带本轮消息）
        //    即使 Redis 短暂不可用也不影响 MySQL 主链路，降级处理。
        try {
            sessionStore.save(session, chatProperties.getSessionTtlSeconds());
        } catch (Exception e) {
            log.warn("本轮会话 Redis 同步失败 sessionId={}（不影响 MySQL 主链路）", session.getSessionId(), e);
        }
    }

    /**
     * 确保会话头在 MySQL 存在并更新（供每轮持久化后调用，Redis 侧由调用方统一保存）。
     * <p>
     * 会话行不存在（如早期创建失败/兜底重建场景）则插入；存在则更新 last_access_time、
     * 会话首轮补 title、model_name 等。
     * </p>
     */
    private void upsertSessionHead(ChatSession session, String modelName, String userContent, long now) {
        try {
            ChatSessionEntity existing = sessionMapper.findBySessionId(session.getSessionId());
            if (existing == null) {
                // 会话头尚未落库，补插一行
                ChatSessionEntity entity = toSessionEntity(session);
                entity.setModelName(modelName);
                entity.setTitle(buildTitle(userContent));
                entity.setLastAccessTime(new Date(now));
                sessionMapper.insert(entity);
                return;
            }
            // 会话头已存在，更新（title 首轮为空则补，last_access_time 续期，model_name 记录）
            ChatSessionEntity update = ChatSessionEntity.builder()
                    .sessionId(session.getSessionId())
                    .title(existing.getTitle() == null || existing.getTitle().isBlank()
                            ? buildTitle(userContent) : existing.getTitle())
                    .modelName(modelName != null ? modelName : existing.getModelName())
                    .status(STATUS_ACTIVE)
                    .lastAccessTime(new Date(now))
                    .build();
            sessionMapper.updateByIdempotent(update);
        } catch (Exception e) {
            log.error("会话头 MySQL upsert 失败 sessionId={}", session.getSessionId(), e);
        }
    }

    /**
     * 构建消息落库实体（deleted=0，审计 create_time 由调用方统一填充，模拟"自动审计填充"语义）。
     */
    private ChatMessageEntity buildMessageEntity(ChatSession session, String role, String content,
                                                 String citationsJson, String modelName,
                                                 Long responseTimeMs, long now) {
        return ChatMessageEntity.builder()
                .messageId(UUID.randomUUID().toString())
                .sessionId(session.getSessionId())
                .tenantId(session.getTenantId())
                .userId(session.getUserId())
                .kbId(session.getKbId())
                .role(role)
                .content(content)
                .modelName(modelName)
                .messageType(null)
                .citations(citationsJson)
                .responseTime(responseTimeMs)
                .tokenCount(null)
                .createTime(new Date(now))
                .deleted(NOT_DELETED)
                .build();
    }

    /**
     * 将会话 DTO 组装为落库会话实体（deleted=0、status=活跃、审计时间填充）。
     */
    private ChatSessionEntity toSessionEntity(ChatSession session) {
        Date now = new Date();
        return ChatSessionEntity.builder()
                .sessionId(session.getSessionId())
                .tenantId(session.getTenantId())
                .userId(session.getUserId())
                .kbId(session.getKbId())
                .modelName(null)
                .title(session.getTitle())
                .status(STATUS_ACTIVE)
                .lastAccessTime(new Date(session.getLastAccessTime() > 0
                        ? session.getLastAccessTime() : System.currentTimeMillis()))
                .createTime(new Date(session.getCreateTime() > 0
                        ? session.getCreateTime() : System.currentTimeMillis()))
                .deleted(NOT_DELETED)
                .build();
    }

    /**
     * 由首条用户消息截取会话标题（截断长度 {@link #TITLE_MAX_LEN}，空则返回 null）。
     */
    private String buildTitle(String firstUserContent) {
        if (firstUserContent == null || firstUserContent.isBlank()) {
            return null;
        }
        String trimmed = firstUserContent.trim();
        return trimmed.length() <= TITLE_MAX_LEN ? trimmed : trimmed.substring(0, TITLE_MAX_LEN);
    }

    /**
     * Redis 丢失时，从 MySQL 按 sessionId 重建会话上下文。
     *
     * @param sessionId 会话 ID
     * @param tenantId  请求租户 ID（用于校验归属）
     * @param userId    请求用户 ID（用于校验归属）
     * @return 重建后的会话；MySQL 中不存在或归属不符返回 null
     */
    private ChatSession rebuildFromDb(String sessionId, Long tenantId, Long userId) {
        try {
            ChatSessionEntity header = sessionMapper.findBySessionId(sessionId);
            if (header == null) {
                return null;
            }
            // 归属校验：租户 + 用户维度必须一致，防止越权访问他人会话
            if (!java.util.Objects.equals(header.getTenantId(), tenantId)
                    || !java.util.Objects.equals(header.getUserId(), userId)) {
                log.warn("会话归属校验失败，拒绝重建 sessionId={} requesterTenant={} requesterUser={}",
                        sessionId, tenantId, userId);
                return null;
            }
            // 组装会话头
            ChatSession.ChatSessionBuilder builder = ChatSession.builder()
                    .sessionId(header.getSessionId())
                    .tenantId(header.getTenantId())
                    .userId(header.getUserId())
                    .kbId(header.getKbId())
                    .title(header.getTitle())
                    .createTime(header.getCreateTime() != null
                            ? header.getCreateTime().getTime() : System.currentTimeMillis())
                    .lastAccessTime(header.getLastAccessTime() != null
                            ? header.getLastAccessTime().getTime() : System.currentTimeMillis())
                    .messages(new ArrayList<>());
            // 组装历史消息（按 create_time 升序）
            List<ChatMessageEntity> dbMessages = messageMapper.listBySessionId(sessionId);
            List<ChatMessage> chatMessages = new ArrayList<>(dbMessages.size());
            for (ChatMessageEntity m : dbMessages) {
                ChatMessage cm = ChatMessage.builder()
                        .role(m.getRole())
                        .content(m.getContent())
                        .timestamp(m.getCreateTime() != null ? m.getCreateTime().getTime() : 0L)
                        .build();
                chatMessages.add(cm);
            }
            return builder.messages(chatMessages).build();
        } catch (Exception e) {
            log.error("从 MySQL 重建会话失败 sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 查询指定用户/租户下的会话列表（来自 MySQL 权威数据，按最后访问时间倒序）。
     *
     * @param tenantId 租户 ID（null 时仅按用户过滤）
     * @param userId   用户 ID
     * @return 会话头实体列表（不含消息明细，供列表页展示）
     */
    public List<ChatSessionEntity> listSessions(Long tenantId, Long userId) {
        if (tenantId == null) {
            // 无租户维度时按用户过滤需单独处理，此处退化为按用户查询（通过 Mapper 补充按用户查询）
            return sessionMapper.listByUserOnly(userId);
        }
        return sessionMapper.listByTenantUser(tenantId, userId);
    }

    /**
     * 按会话 ID 查询完整历史消息（来自 MySQL 全量数据，citations 反序列化还原）。
     *
     * @param sessionId 会话 ID
     * @return 历史消息列表（按时间升序）；无数据返回空列表
     */
    public List<ChatHistoryMessage> getHistory(String sessionId) {
        List<ChatMessageEntity> rows = messageMapper.listBySessionId(sessionId);
        List<ChatHistoryMessage> result = new ArrayList<>(rows.size());
        for (ChatMessageEntity row : rows) {
            result.add(ChatHistoryMessage.builder()
                    .role(row.getRole())
                    .content(row.getContent())
                    .citations(deserializeCitations(row.getCitations()))
                    .timestamp(row.getCreateTime() != null ? row.getCreateTime().getTime() : 0L)
                    .modelName(row.getModelName())
                    .responseTime(row.getResponseTime())
                    .build());
        }
        return result;
    }

    /**
     * 按会话 ID 查询会话头（用于鉴权与校验存在性），Redis/MySQL 均可，未删除会话优先。
     *
     * @param sessionId 会话 ID
     * @return 会话实体；不存在返回 null
     */
    public ChatSessionEntity getSessionHeader(String sessionId) {
        try {
            return sessionMapper.findBySessionId(sessionId);
        } catch (Exception e) {
            log.error("查询会话头失败 sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 清空会话：MySQL 逻辑删除会话及其下全部消息（deleted=1）+ 清除 Redis 会话与索引。
     *
     * @param sessionId 会话 ID
     * @return 删除的会话消息总数；会话不存在返回 0
     */
    @Transactional
    public int clearSession(String sessionId) {
        int affected = 0;
        try {
            // ① MySQL 逻辑删除会话及其下消息
            affected = sessionMapper.logicDeleteBySessionId(sessionId);
            if (affected > 0) {
                messageMapper.logicDeleteBySessionId(sessionId);
            }
            log.info("会话已逻辑删除 sessionId={} 会话受影响={}", sessionId, affected);
        } catch (Exception e) {
            log.error("会话 MySQL 逻辑删除失败 sessionId={}", sessionId, e);
        }
        // ② 清除 Redis 会话与用户索引
        try {
            sessionStore.delete(sessionId);
        } catch (Exception e) {
            log.error("会话 Redis 清除失败 sessionId={}", sessionId, e);
        }
        return affected;
    }

    /**
     * 反序列化引用 JSON 列表；为空/失败时返回空列表。
     */
    private List<Citation> deserializeCitations(String citationsJson) {
        if (citationsJson == null || citationsJson.isBlank() || "[]".equals(citationsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(citationsJson, new TypeReference<List<Citation>>() {
            });
        } catch (Exception e) {
            log.warn("消息引用 JSON 反序列化失败", e);
            return List.of();
        }
    }
}
