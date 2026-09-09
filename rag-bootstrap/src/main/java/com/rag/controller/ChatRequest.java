package com.rag.controller;

import com.rag.common.entity.config.SearchConfig;
import com.rag.common.enums.SearchMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 问答（Chat）请求 DTO。
 * <p>
 * 支持三种问答模式：
 * <ul>
 *   <li>仅知识库问答：传 kbId + query</li>
 *   <li>仅上传文件问答：传 files + query</li>
 *   <li>知识库 + 上传文件混合问答：传 kbId + files + query</li>
 * </ul>
 * sessionId 为空时自动创建新会话；非空时续接已有会话（多轮对话）。
 * </p>
 * <p>
 * 检索参数（可选，用于知识库问答时的召回控制）：searchMode 指定检索模式
 * （VECTOR_ONLY / BM25_ONLY / HYBRID），topK 控制召回条数，其余为混合/重排参数。
 * 未传或未知时由 {@link ChatContextService} 回退默认值。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /** 知识库 ID（可选，基于知识库问答时传入） */
    private Long kbId;

    /** 用户提问（必填） */
    private String query;

    /** 会话 ID（可选，多轮对话续接时传入；为空则新建会话） */
    private String sessionId;

    /** 对话模型名（可选，为空时回退全局默认模型，默认为 deepseek-v4-flash */
    private String model;

    /** 是否启用流式输出（默认 false，走同步接口） */
    @Builder.Default
    private Boolean stream = false;

    // ==================== 检索参数（可选） ====================

    /** 检索模式：VECTOR_ONLY / BM25_ONLY / HYBRID（null 时回退 VECTOR_ONLY） */
    private String searchMode;

    /** 检索召回条数（null 时 Chat 场景默认 10，由 ContextAssembler 按 Token 预算截断） */
    private Integer topK;

    /** RRF 融合常数 k（HYBRID 且 fusionMode=rrf 时生效） */
    private Integer rrfK;

    /** 向量检索权重（HYBRID 且 fusionMode=weighted 时生效，范围 0~1） */
    private Double vectorWeight;

    /** BM25 检索权重（HYBRID 且 fusionMode=weighted 时生效） */
    private Double bm25Weight;

    /** 融合模式：rrf（默认）或 weighted */
    private String fusionMode;

    /** 重排开关（仅 HYBRID 模式生效） */
    private Boolean rerank;

    /** 重排候选池放大倍数 */
    private Integer rerankMultiplier;

    /**
     * 将本请求中的检索参数组装为 {@link SearchConfig}。
     * <p>searchMode 非法或为 null 时回退 VECTOR_ONLY；其余字段 null 时保持默认。</p>
     */
    public SearchConfig toSearchConfig() {
        SearchConfig.SearchConfigBuilder builder = SearchConfig.builder();
        SearchMode mode = SearchMode.VECTOR_ONLY;
        if (searchMode != null && !searchMode.isBlank()) {
            try {
                mode = SearchMode.valueOf(searchMode.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                mode = SearchMode.VECTOR_ONLY;
            }
        }
        builder.searchMode(mode);
        if (rrfK != null && rrfK > 0) {
            builder.rrfK(rrfK);
        }
        if (vectorWeight != null) {
            builder.vectorWeight(vectorWeight);
        }
        if (bm25Weight != null) {
            builder.bm25Weight(bm25Weight);
        }
        if (fusionMode != null && !fusionMode.isBlank()) {
            builder.fusionMode(fusionMode);
        }
        if (rerank != null) {
            builder.rerankEnabled(rerank);
        }
        if (rerankMultiplier != null && rerankMultiplier > 0) {
            builder.rerankCandidateMultiplier(rerankMultiplier);
        }
        return builder.build();
    }
}