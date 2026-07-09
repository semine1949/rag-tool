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
 * OpenAI Embedding API实现
 */
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    // text-embedding-3-small 默认维度
    private static final int DEFAULT_VECTOR_DIM = 1536;

    @Override
    public List<float[]> batchEmbed(List<String> texts, EmbeddingConfig config) {
        String apiKey = config.getModelSource();
        String baseUrl = config.getBaseUrl();
        String modelName = config.getModelName();
        int maxLen = config.getMaxTextLen() != null ? config.getMaxTextLen() : 8191;

        // TODO: 填入OpenAI API Key，格式为 sk-xxx
        if (apiKey == null || apiKey.isBlank() || "sk-xxx".equals(apiKey)) {
            throw new RagException("RAG_EMBED_OPENAI", "OpenAI api-key未正确配置，请填入有效的API Key");
        }

        // 批量处理 - 一次请求发送多条文本
        List<float[]> results = new ArrayList<>();
        int batchSize = config.getBatchSize() != null ? config.getBatchSize() : 32;

        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);

            // 截断文本
            List<String> truncated = batch.stream()
                    .map(t -> t.length() > maxLen ? t.substring(0, maxLen) : t)
                    .toList();

            try {
                Map<String, Object> param = new HashMap<>();
                param.put("model", modelName != null ? modelName : "text-embedding-3-small");
                param.put("input", truncated.size() == 1 ? truncated.get(0) : truncated);

                RequestBody body = RequestBody.create(
                        JSONUtil.toJsonStr(param),
                        MediaType.parse("application/json"));
                Request request = new Request.Builder()
                        .url((baseUrl != null ? baseUrl : "https://api.openai.com/v1") + "/embeddings")
                        .header("Authorization", "Bearer " + apiKey)
                        .post(body)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        String errorBody = response.body() != null ? response.body().string() : "";
                        throw new RagException("RAG_EMBED_OPENAI",
                                "OpenAI调用失败，状态码: " + response.code() + ", body: " + errorBody);
                    }
                    var json = JSONUtil.parseObj(response.body().string());
                    var dataArray = json.getJSONArray("data");
                    for (int j = 0; j < dataArray.size(); j++) {
                        List<Double> embedding = dataArray.getJSONObject(j)
                                .getJSONArray("embedding").toList(Double.class);
                        float[] vec = new float[embedding.size()];
                        for (int k = 0; k < embedding.size(); k++) {
                            vec[k] = embedding.get(k).floatValue();
                        }
                        results.add(vec);
                    }
                }
            } catch (RagException e) {
                throw e;
            } catch (Exception e) {
                log.error("OpenAI Embedding调用失败: {}", e.getMessage());
                throw new RagException("RAG_EMBED_OPENAI", "OpenAI调用失败: " + e.getMessage(), e);
            }
        }
        return results;
    }

    @Override
    public float[] embed(String text, EmbeddingConfig config) {
        List<float[]> results = batchEmbed(List.of(text), config);
        return results.isEmpty() ? new float[0] : results.get(0);
    }

    @Override
    public int getVectorDim(EmbeddingConfig config) {
        if (config.getVectorDim() != null) return config.getVectorDim();
        String modelName = config.getModelName();
        if (modelName != null) {
            return switch (modelName) {
                case "text-embedding-3-large", "text-embedding-ada-002" -> 1536;
                case "text-embedding-3-small" -> 1536;
                default -> DEFAULT_VECTOR_DIM;
            };
        }
        return DEFAULT_VECTOR_DIM;
    }

    @Override
    public EmbeddingModelType getModelType() {
        return EmbeddingModelType.OPENAI;
    }
}
