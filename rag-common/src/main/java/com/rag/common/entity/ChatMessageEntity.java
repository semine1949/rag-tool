package com.rag.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 对话消息持久化实体（v5 新增，对应表 chat_message）。
 * <p>
 * 会话/消息双表持久化方案中的「消息」表实体。每条问答记录为一行，
 * 每轮问答统一写入 user + assistant 两条消息。与
 * {@link com.rag.common.chat.ChatMessage}（Redis 内存态 DTO）相互区分。
 * </p>
 * <p>
 * 为支撑 MySQL 全量可回放：role/content 完整落库，引用列表 {@link #citations}
 * 以 JSON 字符串随 assistant 消息存储；response_time / token_count 记录耗时与
 * token 用量。查询/回放按 session_id + create_time 升序，且一律过滤 deleted=0。
 * </p>
 *
 * @author rag-tool
 * @since 5.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageEntity {

    /** 消息表物理自增主键（MySQL 行身份） */
    private Long id;

    /** 消息业务 ID（UUID） */
    private String messageId;

    /** 所属会话 ID（FK → chat_session.session_id） */
    private String sessionId;

    /** 租户 ID（多租户隔离维度） */
    private Long tenantId;

    /** 用户 ID（用户级隔离维度） */
    private Long userId;

    /** 关联知识库 ID（可空） */
    private Long kbId;

    /** 消息角色：USER / ASSISTANT / SYSTEM */
    private String role;

    /** 消息文本内容 */
    private String content;

    /** 生成该消息使用的对话模型名 */
    private String modelName;

    /** 消息类型（预留，如 TEXT/RAG 等） */
    private String messageType;

    /** 引用溯源列表 JSON（assistant 消息携带，USER 消息为 NULL） */
    private String citations;

    /** 本次回答耗时（毫秒） */
    private Long responseTime;

    /** 本次回答 token 用量（估算，可空） */
    private Integer tokenCount;

    /** 消息时间（用于按序回放与排序） */
    private Date createTime;

    /** 逻辑删除标记：0=未删除, 1=已删除 */
    private Integer deleted;
}
