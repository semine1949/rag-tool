package com.rag.core.api;

import com.rag.core.config.EmbeddingConfig;
import com.rag.core.enums.EmbeddingModelType;

import java.util.List;

/**
 * Embedding向量生成顶层接口
 */
public interface EmbeddingClient {

    /**
     * 根据文本列表批量生成向量
     * @param texts 文本列表
     * @param config 模型配置
     * @return 向量列表，每个元素对应一个输入文本
     */
    List<float[]> batchEmbed(List<String> texts, EmbeddingConfig config);

    /**
     * 单条文本生成向量
     * @param text 文本
     * @param config 模型配置
     * @return 向量
     */
    float[] embed(String text, EmbeddingConfig config);

    /**
     * 获取当前模型向量维度
     * @param config 模型配置
     * @return 向量维度
     */
    int getVectorDim(EmbeddingConfig config);

    /**
     * 返回支持的模型类型
     * @return 模型类型
     */
    EmbeddingModelType getModelType();
}
