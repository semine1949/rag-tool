package com.rag.core.config;

import com.rag.core.enums.EmbeddingModelType;
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
    /** 模型名称：bge-small, text-embedding-ada-002等 */
    private String modelName;
    /** 文本截断最大长度 */
    @Builder.Default
    private Integer maxTextLen = 512;
    /** API密钥 / 本地模型文件路径 */
    // TODO: 在线模型需填入API密钥，本地模型填入模型文件路径
    private String modelSource;
    /** API Base URL */
    // TODO: 填入各Embedding API的Base URL，例如OpenAI: https://api.openai.com/v1
    private String baseUrl;
    /** 向量输出维度 */
    private Integer vectorDim;
    /** 批量单次处理数量 */
    @Builder.Default
    private Integer batchSize = 32;
}
