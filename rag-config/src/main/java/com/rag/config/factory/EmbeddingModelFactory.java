package com.rag.config.factory;

import com.rag.common.entity.config.EmbeddingConfig;
import com.rag.common.entity.config.EmbeddingProperties;
import com.rag.common.enums.EmbeddingModelType;
import com.rag.common.client.OpenAiClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding 模型工厂。
 * <p>
 * 统一委托给 {@link OpenAiClient#embed(String, String, String, List)} 执行向量化，
 * 返回实现 Spring AI {@link EmbeddingModel} 接口的适配器。按模型类型缓存实例。
 */
public class EmbeddingModelFactory {

    private final EmbeddingProperties embeddingProperties;
    /** 统一 OpenAI 兼容客户端 */
    private final OpenAiClient openAiClient;
    /** 模型实例缓存，key=模型类型枚举 */
    private final Map<EmbeddingModelType, EmbeddingModel> cache = new ConcurrentHashMap<>();

    public EmbeddingModelFactory(EmbeddingProperties embeddingProperties, OpenAiClient openAiClient) {
        this.embeddingProperties = embeddingProperties;
        this.openAiClient = openAiClient;
    }

    /**
     * 获取或创建 Embedding 模型实例。
     *
     * @param config Embedding 配置（含模型类型、模型名称等）
     * @return Spring AI EmbeddingModel 适配器
     */
    public EmbeddingModel getModel(EmbeddingConfig config) {
        return cache.computeIfAbsent(config.getModelType(), this::build);
    }

    /**
     * 构建适配器：从 EmbeddingProperties 获取凭证信息，委托给 OpenAiClient.embed()。
     */
    private EmbeddingModel build(EmbeddingModelType type) {
        EmbeddingProperties.ModelProps props = switch (type) {
            case BGE_M3 -> embeddingProperties.getBgeM3();
            case OPENAI -> embeddingProperties.getOpenai();
            case TONGYI -> embeddingProperties.getTongyi();
            default -> embeddingProperties.getTongyi();
        };
        int dim = props.getDim() != null ? props.getDim() : 1024;
        // 返回适配器，委托给 OpenAiClient
        return new OpenAiEmbeddingAdapter(openAiClient, props.getBaseUrl(), props.getApiKey(), props.getModel(), dim);
    }

    /**
     * 基于 OpenAiClient 的 EmbeddingModel 适配器。
     * <p>将 Spring AI 接口调用委托给 {@link OpenAiClient#embed}。</p>
     */
    private static class OpenAiEmbeddingAdapter implements EmbeddingModel {

        private final OpenAiClient client;
        private final String baseUrl;
        private final String apiKey;
        private final String model;
        private final int dimensions;

        OpenAiEmbeddingAdapter(OpenAiClient client, String baseUrl, String apiKey, String model, int dimensions) {
            this.client = client;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
            this.dimensions = dimensions;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<float[]> vectors = client.embed(baseUrl, apiKey, model, request.getInstructions());
            List<Embedding> embeddings = new ArrayList<>(vectors.size());
            for (int i = 0; i < vectors.size(); i++) {
                embeddings.add(new Embedding(vectors.get(i), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public float[] embed(String text) {
            return client.embed(baseUrl, apiKey, model, List.of(text)).get(0);
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return client.embed(baseUrl, apiKey, model, texts);
        }

        @Override
        public int dimensions() {
            return dimensions;
        }
    }
}
