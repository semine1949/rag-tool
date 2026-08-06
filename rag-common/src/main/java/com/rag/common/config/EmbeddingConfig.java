package com.rag.common.config;

import com.rag.common.enums.EmbeddingModelType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Embedding模型配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingConfig {
    /** 模型类型 */
    private EmbeddingModelType modelType;
    /** 模型名称：text-embedding-v2, bge-m3 等 */
    private String modelName;
    /** 文本截断最大长度 */
    @Builder.Default
    private Integer maxTextLen = 512;
    /** API密钥 */
    private String modelSource;
    /** API Base URL */
    private String baseUrl;
    /** 向量输出维度 */
    private Integer vectorDim;
    /** 批量单次处理数量 */
    @Builder.Default
    private Integer batchSize = 32;
}
