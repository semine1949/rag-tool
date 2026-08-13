package com.rag.common.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一的 OpenAI 兼容协议客户端，封装所有大模型 HTTP 调用。
 * <p>
 * 将项目中分散的 Embedding / Rerank / OCR 调用统一到此单一入口，
 * 共享 OkHttp 连接池、Jackson 序列化、Bearer Token 鉴权等基础设施，
 * 消除原有 {@code OpenAiCompatibleEmbeddingModel} / {@code Qwen3RerankStrategy} / {@code DeepSeekOcrClient} 的重复代码。
 * </p>
 *
 * <h3>方法概览</h3>
 * <ul>
 *   <li>{@link #embed(String, String, String, List)} — Embedding 向量化（批量）</li>
 *   <li>{@link #rerank(String, String, String, String, List, int, int)} — Rerank 重排序（内置限流+分批）</li>
 *   <li>{@link #ocr(String, String, String, byte[], String, long)} — OCR 识别（独立超时控制）</li>
 * </ul>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li><b>限流保护</b>：仅 Rerank 方法内置令牌桶限流，因为 Rerank 存在分批循环调用场景，QPS 压力天然高于 Embedding 和 OCR。</li>
 *   <li><b>异常处理</b>：所有方法均将异常包装为 {@link IllegalStateException} 向上抛出，由调用方决定降级策略。</li>
 *   <li><b>线程安全</b>：OkHttpClient 实例自身线程安全；限流计数器使用 {@link AtomicInteger} + volatile 保证可见性。</li>
 * </ul>
 *
 * @author rag-tool
 * @since 1.0
 */
public class OpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

    // ==================== 常量 ====================

    /** Embedding API 端点路径 */
    private static final String EMBED_PATH = "/embeddings";
    /** Rerank API 端点路径 */
    private static final String RERANK_PATH = "/rerank";
    /** Chat Completions API 端点路径 */
    private static final String CHAT_PATH = "/chat/completions";
    /** 限流窗口大小（毫秒） */
    private static final long RATE_LIMIT_WINDOW_MS = 1000L;

    // ==================== 共享基础设施 ====================

    /** 通用 OkHttpClient（连接/读取超时 30s/60s，适用于 Embedding 和 Rerank） */
    private final OkHttpClient httpClient;
    /** 通用 Jackson ObjectMapper */
    private final ObjectMapper objectMapper;

    public OpenAiClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // ==================== Embedding ====================

    /**
     * 批量文本向量化（OpenAI 兼容 /embeddings 端点）。
     *
     * @param baseUrl API Base URL（如 {@code https://api.siliconflow.cn/v1}）
     * @param apiKey  API Key（Bearer Token 鉴权）
     * @param model   模型名称（如 {@code BAAI/bge-m3}）
     * @param texts   待向量化的文本列表
     * @return 按输入顺序排列的向量数组列表，每个向量为 float[]
     * @throws IllegalStateException 调用失败时抛出
     */
    public List<float[]> embed(String baseUrl, String apiKey, String model, List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            String url = buildUrl(baseUrl, EMBED_PATH);
            String jsonBody = objectMapper.writeValueAsString(new EmbedRequest(model, texts));
            RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));

            Request.Builder rb = new Request.Builder().url(url).post(body);
            addAuth(rb, apiKey);

            try (Response resp = httpClient.newCall(rb.build()).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    throw new IllegalStateException("Embedding 调用失败: HTTP " + resp.code());
                }
                EmbedResponse parsed = objectMapper.readValue(resp.body().string(), EmbedResponse.class);
                if (parsed.data == null || parsed.data.isEmpty()) {
                    throw new IllegalStateException("Embedding 返回为空");
                }
                // 按 index 排序保证顺序
                parsed.data.sort(Comparator.comparingInt(d -> d.index));
                List<float[]> result = new ArrayList<>(parsed.data.size());
                for (EmbedResponse.EmbedData d : parsed.data) {
                    result.add(toFloatArray(d.embedding));
                }
                return result;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Embedding 网络异常: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Embedding 调用异常: " + e.getMessage(), e);
        }
    }

    // ==================== Rerank ====================

    /**
     * 对候选文档列表执行 Rerank 重排序（OpenAI 兼容 /rerank 端点）。
     * <p>
     * 内置限流保护（令牌桶）和分批处理能力：
     * 候选文档数超过 batchSize 时自动分批调用，批次间顺序执行；
     * 超出 QPS 阈值时降级返回原始候选列表。
     * </p>
     *
     * @param baseUrl    API Base URL
     * @param apiKey     API Key
     * @param model      模型名称
     * @param query      用户查询语句
     * @param candidates 候选文档列表
     * @param batchSize  单批最大文档数
     * @param maxQps     每秒最大调用次数（<=0 表示不限流）
     * @return 按精排得分降序排列的文档列表
     */
    public List<Document> rerank(String baseUrl, String apiKey, String model,
                                  String query, List<Document> candidates,
                                  int batchSize, int maxQps) {
        // ===== 空值兜底 =====
        if (query == null || query.isBlank()) {
            log.debug("重排跳过：查询语句为空");
            return candidates;
        }
        if (candidates == null || candidates.isEmpty()) {
            log.debug("重排跳过：候选文档为空");
            return candidates != null ? candidates : List.of();
        }
        if (candidates.size() == 1) {
            candidates.get(0).getMetadata().put("relevanceScore", 1.0);
            return candidates;
        }

        // ===== 限流检查 =====
        if (maxQps > 0 && !tryAcquire(maxQps)) {
            log.warn("重排限流触发（QPS={}），降级为原始排序结果", maxQps);
            return candidates;
        }

        int bs = batchSize > 0 ? batchSize : 20;

        try {
            if (candidates.size() <= bs) {
                return doRerank(baseUrl, apiKey, model, query, candidates);
            }

            // 多批分批调用
            log.debug("候选文档 {} 条超过批次大小 {}，分批处理", candidates.size(), bs);
            List<List<Document>> batches = partition(candidates, bs);
            List<Document> allResults = new ArrayList<>();

            for (int i = 0; i < batches.size(); i++) {
                List<Document> batch = batches.get(i);
                if (maxQps > 0 && !tryAcquire(maxQps)) {
                    log.warn("分批处理中限流触发（批次 {}/{}），剩余批次降级", i + 1, batches.size());
                    for (int j = i; j < batches.size(); j++) {
                        allResults.addAll(batches.get(j));
                    }
                    break;
                }
                try {
                    List<Document> reranked = doRerank(baseUrl, apiKey, model, query, batch);
                    allResults.addAll(reranked);
                } catch (Exception e) {
                    log.warn("批次 {} 重排失败，保留原始排序: {}", i + 1, e.getMessage());
                    allResults.addAll(batch);
                }
            }

            allResults.sort(Comparator.comparingDouble(this::getRelevanceScore).reversed());
            return allResults;

        } catch (Exception e) {
            log.error("重排调用异常，降级为原始排序: {}", e.getMessage(), e);
            return candidates;
        }
    }

    /**
     * 单批次 Rerank API 调用。
     */
    private List<Document> doRerank(String baseUrl, String apiKey, String model,
                                     String query, List<Document> candidates) throws Exception {
        List<String> texts = new ArrayList<>(candidates.size());
        for (Document doc : candidates) {
            String text = doc.getText();
            texts.add(text != null ? text : "");
        }

        String url = buildUrl(baseUrl, RERANK_PATH);
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("query", query);
        requestBody.put("documents", texts);

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));

        Request.Builder rb = new Request.Builder().url(url).post(body);
        addAuth(rb, apiKey);

        try (Response resp = httpClient.newCall(rb.build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IllegalStateException("Rerank API 返回非成功状态: HTTP " + resp.code());
            }
            RerankResponse payload = objectMapper.readValue(resp.body().string(), RerankResponse.class);

            if (payload.results == null || payload.results.isEmpty()) {
                throw new IllegalStateException("Rerank API 返回空结果");
            }

            for (RerankResultItem item : payload.results) {
                if (item.index >= 0 && item.index < candidates.size()) {
                    Document doc = candidates.get(item.index);
                    doc.getMetadata().put("relevanceScore", item.relevanceScore);
                }
            }

            List<Document> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator.comparingDouble(this::getRelevanceScore).reversed());

            log.debug("Rerank 完成: {} 条候选 → {} 条精排结果", candidates.size(), sorted.size());
            return sorted;
        }
    }

    // ==================== OCR ====================

    /**
     * OCR 识别（OpenAI 兼容 /chat/completions 多模态端点）。
     * <p>
     * 将图片/文档以 base64 data URL 传入多模态模型，由远程模型渲染并识别文本。
     * 使用独立超时控制的 OkHttpClient（OCR 模型响应可能较慢，默认 120s）。
     * </p>
     *
     * @param baseUrl   API Base URL
     * @param apiKey    API Key
     * @param model     模型名称（如 {@code deepseek-ai/DeepSeek-OCR}）
     * @param content   文件/图片二进制数据
     * @param mimeType  媒体类型（如 {@code image/png}、{@code application/pdf}）
     * @param timeoutMs 超时毫秒数（<=0 时使用默认 120000ms）
     * @return 识别出的完整文本
     * @throws IllegalStateException 调用失败时抛出
     */
    public String ocr(String baseUrl, String apiKey, String model,
                      byte[] content, String mimeType, long timeoutMs) {
        long timeout = timeoutMs > 0 ? timeoutMs : 120_000L;

        // OCR 使用独立 OkHttpClient，超时更长
        OkHttpClient ocrClient = new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();

        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(content);
        String prompt = "You are a precise OCR engine. Extract ALL text from the provided image or document, "
                + "preserving the reading order and layout as much as possible. "
                + "Output only the extracted text, without any commentary.";

        try {
            // 构造 messages 数组
            Map<String, Object> systemMsg = new LinkedHashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", "You are a professional OCR assistant.");

            List<Map<String, Object>> userContent = new ArrayList<>();

            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text", prompt);
            userContent.add(textPart);

            Map<String, Object> imageUrlObj = new LinkedHashMap<>();
            imageUrlObj.put("url", dataUrl);

            Map<String, Object> imagePart = new LinkedHashMap<>();
            imagePart.put("type", "image_url");
            imagePart.put("image_url", imageUrlObj);
            userContent.add(imagePart);

            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userContent);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(systemMsg);
            messages.add(userMsg);

            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("model", model);
            reqBody.put("messages", messages);
            reqBody.put("temperature", 0);
            reqBody.put("max_tokens", 4096);

            String url = buildUrl(baseUrl, CHAT_PATH);
            String jsonBody = objectMapper.writeValueAsString(reqBody);
            RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));

            Request.Builder rb = new Request.Builder().url(url).post(body);
            addAuth(rb, apiKey);

            try (Response resp = ocrClient.newCall(rb.build()).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    throw new IllegalStateException("OCR 调用失败: HTTP " + resp.code());
                }
                ChatResponse chatResp = objectMapper.readValue(resp.body().string(), ChatResponse.class);

                if (chatResp.error != null) {
                    throw new IllegalStateException("OCR 错误: " + chatResp.error.message);
                }
                if (chatResp.choices == null || chatResp.choices.isEmpty()) {
                    throw new IllegalStateException("OCR 返回空结果");
                }
                ChatChoice choice = chatResp.choices.get(0);
                if (choice.message == null) {
                    throw new IllegalStateException("OCR 返回消息为空");
                }
                String result = choice.message.content;
                return result != null ? result.trim() : "";
            }
        } catch (IOException e) {
            throw new IllegalStateException("OCR 网络异常: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("OCR 调用异常: " + e.getMessage(), e);
        }
    }

    // ==================== 限流实现（仅 Rerank 使用） ====================

    /** 当前窗口内的调用计数 */
    private final AtomicInteger callCount = new AtomicInteger(0);
    /** 当前限流窗口的起始时间戳（毫秒） */
    private volatile long windowStartMs = System.currentTimeMillis();

    /**
     * 简单令牌桶限流：尝试获取一次调用许可。
     * <p>
     * 以 1 秒为滑动窗口，窗口内调用次数超过 maxQps 时拒绝。
     * 进入新窗口时自动重置计数器。
     * </p>
     *
     * @param maxQps 每秒最大调用次数
     * @return true=允许调用，false=触发限流
     */
    private boolean tryAcquire(int maxQps) {
        long now = System.currentTimeMillis();
        if (now - windowStartMs > RATE_LIMIT_WINDOW_MS) {
            synchronized (this) {
                if (now - windowStartMs > RATE_LIMIT_WINDOW_MS) {
                    windowStartMs = now;
                    callCount.set(0);
                }
            }
        }
        int current = callCount.incrementAndGet();
        return current <= maxQps;
    }

    // ==================== 工具方法 ====================

    /**
     * 拼接完整 API URL。
     */
    private String buildUrl(String baseUrl, String path) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + path : baseUrl + path;
    }

    /**
     * 添加 Bearer Token 鉴权头。
     */
    private void addAuth(Request.Builder rb, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            rb.addHeader("Authorization", "Bearer " + apiKey);
        }
    }

    /**
     * 将 Double 列表转为 float[] 数组。
     */
    private float[] toFloatArray(List<Double> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i).floatValue();
        }
        return arr;
    }

    /**
     * 将列表按指定大小分区。
     */
    private List<List<Document>> partition(List<Document> list, int size) {
        List<List<Document>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    /**
     * 获取文档的精排相关性得分。
     */
    private double getRelevanceScore(Document doc) {
        Object score = doc.getMetadata().get("relevanceScore");
        if (score instanceof Number) {
            return ((Number) score).doubleValue();
        }
        return doc.getScore() != null ? doc.getScore() : 0.0;
    }

    // ==================== 请求/响应 DTO ====================

    /** Embedding 请求体 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbedRequest {
        public String model;
        public List<String> input;

        EmbedRequest(String model, List<String> input) {
            this.model = model;
            this.input = input;
        }
    }

    /** Embedding 响应体 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbedResponse {
        public List<EmbedData> data;

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class EmbedData {
            public int index;
            public List<Double> embedding;
        }
    }

    /** Rerank 响应体 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RerankResponse {
        public List<RerankResultItem> results;
    }

    /** 单条 Rerank 结果 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RerankResultItem {
        public int index;
        public double relevanceScore;
    }

    /** Chat Completions 响应体 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChatResponse {
        public List<ChatChoice> choices;
        public ErrorDetail error;

        @JsonIgnoreProperties(ignoreUnknown = true)
        static class ErrorDetail {
            public String message;
        }
    }

    /** Chat 选项 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChatChoice {
        public ChatMessage message;
    }

    /** Chat 消息 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChatMessage {
        public String content;
    }
}
