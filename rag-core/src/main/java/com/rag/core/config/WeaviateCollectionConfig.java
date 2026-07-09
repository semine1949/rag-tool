package com.rag.core.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Weaviate集合/类配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeaviateCollectionConfig {
    /** 集合/类名称 */
    private String className;
    /** 向量维度 */
    private Integer vectorDim;
    /** 距离度量方式：cosine, dot, l2-squared */
    @Builder.Default
    private String distanceMetric = "cosine";
    /** 去重策略：skip（跳过重复）/ overwrite（覆盖更新） */
    @Builder.Default
    private String dedupStrategy = "skip";
    /** 批量写入大小 */
    @Builder.Default
    private Integer batchSize = 500;
}
