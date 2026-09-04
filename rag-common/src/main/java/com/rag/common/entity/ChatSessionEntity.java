package com.rag.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 对话会话持久化实体（v5 新增，对应表 chat_session）。
 * <p>
 * 会话/消息双表持久化方案中的「会话」表实体。与 {@link com.rag.common.chat.ChatSession}
 * （Redis 实时上下文 DTO）相互区分：本实体为 MySQL 全量持久化层，承载会话头信息
 * （session_id / tenant_id / user_id / kb_id / model_name / status / title 等）。
 * </p>
 * <p>
 * 审计字段（create_time / update_time）由 DDL 默认值自动维护，逻辑删除标记 deleted
 * 由上层服务在写入时显式置 0、在清空时置 1，查询一律过滤 deleted=0。
 * </p>
 *
 * @author rag-tool
 * @since 5.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionEntity {

    /** 会话表物理自增主键（MySQL 行身份，不承担业务键） */
    private Long id;

    /** 会话业务 ID（UUID，对外暴露，多轮续接/回放依据） */
    private String sessionId;

    /** 租户 ID（多租户隔离维度） */
    private Long tenantId;

    /** 用户 ID（用户级隔离维度） */
    private Long userId;

    /** 关联知识库 ID（可空，表示临时问答未绑定知识库） */
    private Long kbId;

    /** 本会话使用的对话模型名 */
    private String modelName;

    /** 会话标题（列表展示，可由首条用户消息截取） */
    private String title;

    /** 状态：1=活跃, 0=已关闭 */
    private Integer status;

    /** 最后访问时间（续期/排序依据） */
    private Date lastAccessTime;

    /** 创建时间 */
    private Date createTime;

    /** 最后更新时间 */
    private Date updateTime;

    /** 逻辑删除标记：0=未删除, 1=已删除 */
    private Integer deleted;
}
