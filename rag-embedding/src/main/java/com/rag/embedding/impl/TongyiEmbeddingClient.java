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
 * 通义千问 Embedding API 实现
 */
public class TongyiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(TongyiEmbeddingClient.class);
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    // text-embedding-v1/v2 默认1536维
    private static final int DEFAULT_VECTOR_DIM = 1536;

    @Override
    public List<float[]> batchEmbed(List<String> texts, EmbeddingConfig config) {
        // TODO: 填入通义千问API Key，格式为 sk-xxx
        String apiKey = config.getModelSource();
        if (apiKey == null || apiKey.isBlank() || "sk-xxx".equals(apiKey)) {
            throw new RagException("RAG_EMBED_TONGYI", "通义千问 api-key未正确配置，请填入有效的API Key");
        }

        List<float[]> results = new ArrayList<>();
        int batchSize = config.getBatchSize() != null ? config.getBatchSize() : 16;

        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);

            try {
                Map<String, Object> param = new HashMap<>();
                param.put("model", config.getModelName() != null ? config.getModelName() : "text-embedding-v2");
                Map<String, Object> input = new HashMap<>();
                input.put("texts", batch);
                param.put("input", input);

                Map<String, Object> params = new HashMap<>();
                params.put("text_type", "document");

                RequestBody body = RequestBody.create(
                        JSONUtil.toJsonStr(param),
                        MediaType.parse("application/json"));
                Request request = new Request.Builder()
                        .url("https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .post(body)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new RagException("RAG_EMBED_TONGYI",
                                "通义千问调用失败，状态码: " + response.code());
                    }
                    var json = JSONUtil.parseObj(response.body().string());
                    var output = json.getJSONObject("output");
                    var embeddings = output.getJSONArray("embeddings");
                    for (int j = 0; j < embeddings.size(); j++) {
                        List<Double> emb = embeddings.getJSONObject(j)
                                .getJSONArray("embedding").toList(Double.class);
                        float[] vec = new float[emb.size()];
                        for (int k = 0; k < emb.size(); k++) {
                            vec[k] = emb.get(k).floatValue();
                        }
                        results.add(vec);
                    }
                }
            } catch (RagException e) {
                throw e;
            } catch (Exception e) {
                log.error("通义千问 Embedding调用失败: {}", e.getMessage());
                throw new RagException("RAG_EMBED_TONGYI", "通义千问调用失败: " + e.getMessage(), e);
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
        return config.getVectorDim() != null ? config.getVectorDim() : DEFAULT_VECTOR_DIM;
    }

    @Override
    public EmbeddingModelType getModelType() {
        return EmbeddingModelType.TONGYI;
    }
}
