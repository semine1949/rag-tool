package com.rag.embedding.impl;

import cn.hutool.json.JSONUtil;
import com.rag.core.api.EmbeddingClient;
import com.rag.core.config.EmbeddingConfig;
import com.rag.core.enums.EmbeddingModelType;
import com.rag.core.exception.RagException;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Ollama本地Embedding实现
 */
public class OllamaEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingClient.class);
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    // 默认向量维度（m3e-base模型为768）
    private static final int DEFAULT_VECTOR_DIM = 768;

    @Override
    public List<float[]> batchEmbed(List<String> texts, EmbeddingConfig config) {
        List<float[]> results = new ArrayList<>();
        for (String text : texts) {
            float[] vec = embed(text, config);
            results.add(vec);
        }
        return results;
    }

    @Override
    public float[] embed(String text, EmbeddingConfig config) {
        String baseUrl = config.getBaseUrl();
        String modelName = config.getModelName();

        // TODO: 确认Ollama服务地址，默认 http://localhost:11434
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new RagException("RAG_EMBED_OLLAMA", "Ollama base-url未配置，请在EmbeddingConfig中设置");
        }

        try {
            Map<String, Object> param = new HashMap<>();
            param.put("model", modelName != null ? modelName : "m3e");
            param.put("prompt", text);

            RequestBody body = RequestBody.create(
                    JSONUtil.toJsonStr(param),
                    MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(baseUrl + "/api/embeddings")
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new RagException("RAG_EMBED_OLLAMA",
                            "Ollama调用失败，状态码: " + response.code());
                }
                List<Double> embedding = JSONUtil.parseObj(response.body().string())
                        .getJSONArray("embedding")
                        .toList(Double.class);
                float[] vec = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vec[i] = embedding.get(i).floatValue();
                }
                return vec;
            }
        } catch (RagException e) {
            throw e;
        } catch (Exception e) {
            log.error("Ollama Embedding调用失败: {}", e.getMessage());
            throw new RagException("RAG_EMBED_OLLAMA", "Ollama调用失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int getVectorDim(EmbeddingConfig config) {
        return config.getVectorDim() != null ? config.getVectorDim() : DEFAULT_VECTOR_DIM;
    }

    @Override
    public EmbeddingModelType getModelType() {
        return EmbeddingModelType.OLLAMA;
    }
}
