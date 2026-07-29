package com.rag.core.factory;

import com.rag.core.config.EmbeddingConfig;
import com.rag.core.config.EmbeddingProperties;
import com.rag.core.embedding.OpenAiCompatibleEmbeddingModel;
import com.rag.core.enums.EmbeddingModelType;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding 模型工厂。
 * <p>
 * Spring AI 自带的 OpenAI 连接器在本项目 Maven 镜像中不可用，故直接基于 OpenAI 兼容协议
 * 构建 {@link EmbeddingModel}（OpenAI / 通义 / BGE-M3 均走 OpenAI 兼容端点）。按模型类型缓存实例。
 */
public class EmbeddingModelFactory {

    private final EmbeddingProperties embeddingProperties;
    private final Map<EmbeddingModelType, EmbeddingModel> cache = new ConcurrentHashMap<>();

    public EmbeddingModelFactory(EmbeddingProperties embeddingProperties) {
        this.embeddingProperties = embeddingProperties;
    }

    public EmbeddingModel getModel(EmbeddingConfig config) {
        return cache.computeIfAbsent(config.getModelType(), this::build);
    }

    private EmbeddingModel build(EmbeddingModelType type) {
        EmbeddingProperties.ModelProps props = switch (type) {
            case BGE_M3 -> embeddingProperties.getBgeM3();
            case OPENAI -> embeddingProperties.getOpenai();
            case TONGYI -> embeddingProperties.getTongyi();
            default -> embeddingProperties.getTongyi();
        };
        int dim = props.getDim() != null ? props.getDim() : 1024;
        return new OpenAiCompatibleEmbeddingModel(props.getBaseUrl(), props.getApiKey(), props.getModel(), dim);
    }
}
