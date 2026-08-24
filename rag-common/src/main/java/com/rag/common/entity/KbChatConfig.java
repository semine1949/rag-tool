package com.rag.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 知识库级问答配置实体。
 * <p>
 * 持久化在 {@code kb_chat_config} 表中，按知识库维度定制问答行为。
 * 配置优先级：请求参数 > 知识库配置（本实体）> 全局默认配置（application.yml 中的 rag.chat）。
 * 所有字段均可空，null 时由上层按优先级回退至全局默认值。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbChatConfig {

    /** 主键 ID */
    private Long id;

    /** 知识库 ID */
    private Long kbId;

    /** 知识库级问答开关（null 时回退全局开关） */
    private Boolean chatEnabled;

    /** 知识库级对话模型名（null 时回退全局 chat-model） */
    private String chatModel;

    /** 知识库级查询改写开关（null 时回退全局 rewrite-enabled） */
    private Boolean rewriteEnabled;

    /** 知识库级查询改写模型（null 时回退全局 rewrite-model 或 chat-model） */
    private String rewriteModel;

    /** 知识库级上下文窗口 Token 预算（null 时回退全局 context-window-tokens） */
    private Integer contextWindowTokens;

    /** 知识库级自定义系统提示词（null 时回退全局 default-system-prompt） */
    private String customSystemPrompt;

    /** 知识库级 RAG 系统提示词（null 时回退全局 rag-system-prompt 或内置默认模板） */
    private String ragSystemPrompt;

    /** 知识库级相似度阈值（null 时回退全局 similarity-threshold 或不做过滤） */
    private Double similarityThreshold;

    /** 知识库级安全校验开关（null 时回退全局 safety-check-enabled） */
    private Boolean safetyCheckEnabled;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;
}
