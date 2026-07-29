package com.rag.core.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 OkHttp 的 OpenAI 兼容 Embedding 模型实现。
 * <p>
 * 框架自带的 {@code spring-ai-model-openai} 连接器在本项目的 Maven 镜像中不可用，
 * 故此处直接实现 Spring AI 的 {@link EmbeddingModel} 接口（OpenAI / 通义 / BGE-M3 均走 OpenAI 兼容协议）。
 * 返回的向量维度由构造参数指定，与知识库所选模型一致。
 */
public class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;

    public OpenAiCompatibleEmbeddingModel(String baseUrl, String apiKey, String model, int dimensions) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<float[]> vectors = embed(request.getInstructions());
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
        return embed(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "embeddings" : baseUrl + "/embeddings";
            RequestBody body = RequestBody.create(
                    objectMapper.writeValueAsString(new EmbeddingRequestPayload(model, texts)),
                    MediaType.parse("application/json"));
            Request.Builder rb = new Request.Builder().url(url).post(body);
            if (apiKey != null && !apiKey.isBlank()) {
                rb.addHeader("Authorization", "Bearer " + apiKey);
            }
            try (Response resp = httpClient.newCall(rb.build()).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    throw new IllegalStateException("Embedding 调用失败: HTTP " + resp.code());
                }
                EmbeddingResponsePayload parsed =
                        objectMapper.readValue(resp.body().string(), EmbeddingResponsePayload.class);
                if (parsed.data == null || parsed.data.isEmpty()) {
                    throw new IllegalStateException("Embedding 返回为空");
                }
                parsed.data.sort(Comparator.comparingInt(d -> d.index));
                List<float[]> result = new ArrayList<>(parsed.data.size());
                for (EmbeddingResponsePayload.EmbeddingData d : parsed.data) {
                    result.add(toFloatArray(d.embedding));
                }
                return result;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Embedding 调用异常: " + e.getMessage(), e);
        }
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private float[] toFloatArray(List<Double> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i).floatValue();
        }
        return arr;
    }

    // ==================== 请求/响应 DTO ====================

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbeddingRequestPayload {
        public String model;
        public List<String> input;

        EmbeddingRequestPayload(String model, List<String> input) {
            this.model = model;
            this.input = input;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbeddingResponsePayload {
        public List<EmbeddingData> data;

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class EmbeddingData {
            public int index;
            public List<Double> embedding;
        }
    }
}
