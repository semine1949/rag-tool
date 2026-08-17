package com.rag.common.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话会话实体。
 * <p>
 * 承载一次完整多轮问答会话的上下文：会话标识、所属租户/用户、关联知识库、
 * 对话历史消息列表、生命周期时间戳。会话数据按租户 + 用户维度隔离，
 * 由 {@code SessionStore} 持久化（默认 Redis 实现），并按 TTL 自动过期清理。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {

    /** 会话唯一标识（UUID） */
    private String sessionId;

    /** 租户 ID（多租户隔离） */
    private Long tenantId;

    /** 用户 ID（用户级隔离） */
    private Long userId;

    /** 关联知识库 ID（可空，表示临时问答不绑定知识库） */
    private Long kbId;

    /** 会话标题（用于列表展示，可由首条用户消息截取） */
    private String title;

    /** 对话历史消息列表，按时间顺序追加 */
    @Builder.Default
    private List<ChatMessage> messages = new ArrayList<>();

    /** 创建时间（毫秒） */
    private long createTime;

    /** 最后访问时间（毫秒），用于 TTL 续期与过期判定 */
    private long lastAccessTime;

    /**
     * 追加一条消息并刷新最后访问时间。
     *
     * @param message 消息实例
     */
    public void appendMessage(ChatMessage message) {
        this.messages.add(message);
        this.lastAccessTime = System.currentTimeMillis();
    }
}
