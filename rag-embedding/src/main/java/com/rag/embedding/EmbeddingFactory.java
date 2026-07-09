package com.rag.embedding;

import com.rag.core.api.EmbeddingClient;
import com.rag.core.config.EmbeddingConfig;
import com.rag.core.enums.EmbeddingModelType;
import com.rag.core.exception.RagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Embedding模型工厂 - 运行时动态切换模型
 */
public class EmbeddingFactory {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingFactory.class);

    private final Map<EmbeddingModelType, EmbeddingClient> clientMap;
    private EmbeddingModelType currentType;
    private EmbeddingConfig currentConfig;

    public EmbeddingFactory(Map<EmbeddingModelType, EmbeddingClient> clientMap) {
        this.clientMap = clientMap;
    }

    /**
     * 切换到指定模型类型
     */
    public void switchModel(EmbeddingConfig config) {
        this.currentType = config.getModelType();
        this.currentConfig = config;
        log.info("切换Embedding模型: type={}, model={}", config.getModelType(), config.getModelName());
    }

    /**
     * 使用当前配置的模型生成向量
     */
    public List<float[]> batchEmbed(List<String> texts) {
        if (currentType == null || currentConfig == null) {
            throw new RagException("RAG_EMBED_001", "未配置Embedding模型，请先调用switchModel");
        }
        return batchEmbed(texts, currentConfig);
    }

    /**
     * 使用指定配置生成向量
     */
    public List<float[]> batchEmbed(List<String> texts, EmbeddingConfig config) {
        EmbeddingClient client = clientMap.get(config.getModelType());
        if (client == null) {
            throw new RagException("RAG_EMBED_002", "不支持的Embedding模型类型: " + config.getModelType());
        }
        return client.batchEmbed(texts, config);
    }

    /**
     * 获取向量维度
     */
    public int getVectorDim(EmbeddingConfig config) {
        EmbeddingClient client = clientMap.get(config.getModelType());
        if (client == null) {
            throw new RagException("RAG_EMBED_002", "不支持的Embedding模型类型: " + config.getModelType());
        }
        return client.getVectorDim(config);
    }
}
