package com.rag.common.entity.config;

import com.rag.common.enums.SearchMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检索配置实体，封装 BM25 检索参数、混合融合参数与重排参数。
 * <p>
 * 配置优先级：请求参数 > 知识库配置 > 全局默认配置（application.yml）。
 * 所有字段均为可空，null 时由上层按优先级逐级回退至全局默认值。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchConfig {

    /** 检索模式，默认 {@link SearchMode#VECTOR_ONLY} */
    @Builder.Default
    private SearchMode searchMode = SearchMode.VECTOR_ONLY;

    // ==================== RRF 融合参数 ====================

    /**
     * RRF 常数 k，默认 60。
     * <p>公式：score = Σ 1/(k + rank)，k 越大排名越平滑。</p>
     */
    @Builder.Default
    private int rrfK = 60;

    // ==================== 加权求和融合参数 ====================

    /** 向量检索权重（加权求和模式使用，范围 0.0~1.0） */
    private Double vectorWeight;

    /** BM25 检索权重（加权求和模式使用，范围 0.0~1.0，与 vectorWeight 之和应为 1.0） */
    private Double bm25Weight;

    // ==================== BM25 参数 ====================

    /**
     * BM25 k1 参数（Weaviate 服务端内部管理，此处预留为知识库级配置）。
     * <p>注意：Weaviate client 4.6.0 不暴露 k1/b 参数给客户端，此字段暂为预留。</p>
     */
    private Double bm25K1;

    /**
     * BM25 b 参数（Weaviate 服务端内部管理，此处预留为知识库级配置）。
     * <p>注意：Weaviate client 4.6.0 不暴露 k1/b 参数给客户端，此字段暂为预留。</p>
     */
    private Double bm25B;

    // ==================== 融合模式 ====================

    /**
     * 融合模式：rrf（默认，倒数排名融合）或 weighted（加权求和融合）。
     * 未知值回退为 rrf。
     */
    @Builder.Default
    private String fusionMode = "rrf";

    // ==================== 重排参数 ====================

    /**
     * 重排开关：true 启用精排，false/null 跳过重排（默认关闭，100% 兼容原有行为）。
     * <p>仅作用于 HYBRID 混合模式，纯向量/纯 BM25 单路检索不触发重排。</p>
     */
    @Builder.Default
    private Boolean rerankEnabled = false;

    /** 重排候选池放大倍数，启用重排时召回阶段放大候选数量。
     * <p>默认 3 倍，即取 topK * 3 条候选送入精排，精排后再截取最终 topK。</p>
     */
    @Builder.Default
    private Integer rerankCandidateMultiplier = 3;

    // ==================== 相似度阈值过滤 ====================

    /**
     * 相似度阈值（0.0~1.0），仅保留 score ≥ threshold 的召回片段。
     * <p>null 时不执行过滤，与原有行为完全兼容。</p>
     */
    private Double similarityThreshold;

    // ==================== 工厂方法 ====================

    /** 创建纯向量检索默认配置 */
    public static SearchConfig vectorOnly() {
        return SearchConfig.builder().searchMode(SearchMode.VECTOR_ONLY).build();
    }

    /** 创建纯 BM25 检索默认配置 */
    public static SearchConfig bm25Only() {
        return SearchConfig.builder().searchMode(SearchMode.BM25_ONLY).build();
    }

    /** 创建混合检索默认配置（RRF 融合，k=60） */
    public static SearchConfig hybrid() {
        return SearchConfig.builder().searchMode(SearchMode.HYBRID).rrfK(60).fusionMode("rrf").build();
    }
}
