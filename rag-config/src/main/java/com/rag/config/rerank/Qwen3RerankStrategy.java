package com.rag.config.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.entity.config.RerankConfig;
import com.rag.common.rerank.RerankStrategy;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Qwen3-Rerank 交叉编码器精排策略实现 —— 策略模式具体策略。
 * <p>
 * 基于硅基流动 OpenAI 兼容 Rerank API（{@code POST /v1/rerank}）实现，
 * 复用项目现有 OkHttp + Bearer Token 鉴权 + Jackson 序列化基础设施。
 * </p>
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li><b>远程调用</b>：独立构建 OkHttpClient，配置连接/读取超时</li>
 *   <li><b>分批处理</b>：自动按配置的 batchSize 分批调用，每批独立请求</li>
 *   <li><b>限流保护</b>：基于时间窗口的简单令牌桶（AtomicInteger + 秒级重置），超出阈值自动降级</li>
 *   <li><b>异常降级</b>：任何异常均返回原始候选列表，记录告警日志，不中断检索链路</li>
 *   <li><b>精排得分</b>：写入 {@code Document.metadata["relevanceScore"]}，原始融合得分保留在 score 字段</li>
 * </ul>
 *
 * <h3>扩展方式</h3>
 * <p>后续新增其他重排模型时，参照此类实现 {@link RerankStrategy} 接口即可，
 * 在 {@link RerankStrategyFactory} 中注册新的模型类型映射，无需修改本类。</p>
 *
 * @author rag-tool
 * @see RerankStrategy
 * @see RerankStrategyFactory
 * @since 1.0
 */
public class Qwen3RerankStrategy implements RerankStrategy {

    private static final Logger log = LoggerFactory.getLogger(Qwen3RerankStrategy.class);

    /** 硅基流动 Rerank API 端点路径 */
    private static final String RERANK_PATH = "/rerank";

    /** 限流窗口大小（毫秒），1 秒 = 1000ms */
    private static final long RATE_LIMIT_WINDOW_MS = 1000L;

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final int batchSize;
    private final int maxQps;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    // ==================== 限流组件 ====================

    /** 当前窗口内的调用计数 */
    private final AtomicInteger callCount = new AtomicInteger(0);
    /** 当前限流窗口的起始时间戳（毫秒） */
    private volatile long windowStartMs = System.currentTimeMillis();

    /**
     * 构造 Qwen3-Rerank 策略实例。
     *
     * @param config 重排配置（baseUrl / apiKey / modelName / batchSize / maxQps / 超时等）
     */
    public Qwen3RerankStrategy(RerankConfig config) {
        // 处理 baseUrl 末尾斜杠
        String rawUrl = config.getBaseUrl();
        this.baseUrl = rawUrl != null && rawUrl.endsWith("/")
                ? rawUrl.substring(0, rawUrl.length() - 1)
                : rawUrl;
        this.apiKey = config.getApiKey();
        this.modelName = config.getModelName() != null ? config.getModelName() : "Qwen/Qwen3-Reranker";
        this.batchSize = config.getBatchSize() != null && config.getBatchSize() > 0 ? config.getBatchSize() : 20;
        this.maxQps = config.getMaxQps() != null && config.getMaxQps() > 0 ? config.getMaxQps() : 5;
        this.objectMapper = new ObjectMapper();

        int connectTimeout = config.getConnectTimeoutSeconds() != null ? config.getConnectTimeoutSeconds() : 30;
        int readTimeout = config.getReadTimeoutSeconds() != null ? config.getReadTimeoutSeconds() : 60;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout, TimeUnit.SECONDS)
                .readTimeout(readTimeout, TimeUnit.SECONDS)
                .build();

        log.info("Qwen3RerankStrategy 初始化完成: model={}, baseUrl={}, batchSize={}, maxQps={}",
                modelName, baseUrl, batchSize, maxQps);
    }

    /**
     * 对候选文档列表执行重排序。
     * <p>
     * 核心流程：
     * <ol>
     *   <li>空值校验：query 或 candidates 为空时跳过重排</li>
     *   <li>限流检查：超出 QPS 阈值时降级返回原始排序</li>
     *   <li>分批调用：超过 batchSize 时自动分批，每批独立请求</li>
     *   <li>得分归一化：多批次结果按原始得分排序合并</li>
     *   <li>异常兜底：任何异常均降级为原始候选列表</li>
     * </ol>
     * </p>
     *
     * @param query      用户查询语句
     * @param candidates 候选文档列表
     * @return 重排序后的文档列表（按精排得分降序）
     */
    @Override
    public List<Document> rerank(String query, List<Document> candidates) {
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
            // 单条候选无需重排
            candidates.get(0).getMetadata().put("relevanceScore", 1.0);
            return candidates;
        }

        // ===== 限流检查 =====
        if (!tryAcquire()) {
            log.warn("重排限流触发（QPS={}），降级为原始排序结果", maxQps);
            return candidates;
        }

        try {
            // ===== 分批处理 =====
            if (candidates.size() <= batchSize) {
                // 单批直接调用
                return doRerank(query, candidates);
            }

            // 多批分批调用
            log.debug("候选文档 {} 条超过批次大小 {}，分批处理", candidates.size(), batchSize);
            List<List<Document>> batches = partition(candidates, batchSize);
            List<Document> allResults = new ArrayList<>();

            for (int i = 0; i < batches.size(); i++) {
                List<Document> batch = batches.get(i);
                // 每批之间再次检查限流
                if (!tryAcquire()) {
                    log.warn("分批处理中限流触发（批次 {}/{}），剩余批次降级", i + 1, batches.size());
                    // 剩余批次保持原始排序
                    for (int j = i; j < batches.size(); j++) {
                        allResults.addAll(batches.get(j));
                    }
                    break;
                }
                try {
                    List<Document> reranked = doRerank(query, batch);
                    allResults.addAll(reranked);
                } catch (Exception e) {
                    log.warn("批次 {} 重排失败，保留原始排序: {}", i + 1, e.getMessage());
                    allResults.addAll(batch);
                }
            }

            // 多批次结果按精排得分全局排序
            allResults.sort(Comparator.comparingDouble(this::getRelevanceScore).reversed());
            return allResults;

        } catch (Exception e) {
            log.error("重排调用异常，降级为原始排序: {}", e.getMessage(), e);
            return candidates;
        }
    }

    // ==================== 核心 API 调用 ====================

    /**
     * 单批次 Rerank API 调用。
     * <p>
     * 构造 JSON 请求体 → POST 到硅基流动 Rerank 端点 → 解析返回的精排得分 →
     * 写入 Document 元数据 → 按 relevanceScore 降序排列返回。
     * </p>
     *
     * @param query      查询语句
     * @param candidates 候选文档列表（不超过 batchSize 条）
     * @return 重排序后的文档列表
     */
    private List<Document> doRerank(String query, List<Document> candidates) throws Exception {
        // 提取文档文本列表
        List<String> texts = new ArrayList<>(candidates.size());
        for (Document doc : candidates) {
            String text = doc.getText();
            texts.add(text != null ? text : "");
        }

        // 构造请求
        String url = baseUrl + RERANK_PATH;
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", modelName);
        requestBody.put("query", query);
        requestBody.put("documents", texts);

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));

        Request.Builder rb = new Request.Builder().url(url).post(body);
        if (apiKey != null && !apiKey.isBlank()) {
            rb.addHeader("Authorization", "Bearer " + apiKey);
        }

        // 执行请求
        try (Response resp = httpClient.newCall(rb.build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IllegalStateException("Rerank API 返回非成功状态: HTTP " + resp.code());
            }
            String respBody = resp.body().string();
            RerankResponsePayload payload = objectMapper.readValue(respBody, RerankResponsePayload.class);

            if (payload.results == null || payload.results.isEmpty()) {
                throw new IllegalStateException("Rerank API 返回空结果");
            }

            // 将精排得分写入对应 Document 的元数据
            for (RerankResultItem item : payload.results) {
                if (item.index >= 0 && item.index < candidates.size()) {
                    Document doc = candidates.get(item.index);
                    doc.getMetadata().put("relevanceScore", item.relevanceScore);
                }
            }

            // 按 relevanceScore 降序排列
            List<Document> sorted = new ArrayList<>(candidates);
            sorted.sort(Comparator.comparingDouble(this::getRelevanceScore).reversed());

            log.debug("Rerank 完成: {} 条候选 → {} 条精排结果", candidates.size(), sorted.size());
            return sorted;
        }
    }

    // ==================== 限流实现 ====================

    /**
     * 简单令牌桶限流：尝试获取一次调用许可。
     * <p>
     * 以 1 秒为滑动窗口，窗口内调用次数超过 maxQps 时拒绝。
     * 进入新窗口时自动重置计数器。
     * </p>
     *
     * @return true=允许调用，false=触发限流
     */
    private boolean tryAcquire() {
        long now = System.currentTimeMillis();
        // 检查是否需要进入新窗口
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
     * 将列表按指定大小分区。
     *
     * @param list 原始列表
     * @param size 每批大小
     * @return 分区后的列表集合
     */
    private List<List<Document>> partition(List<Document> list, int size) {
        List<List<Document>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    /**
     * 获取文档的精排相关性得分，无精排得分时返回原始 score 作为兜底。
     *
     * @param doc 文档对象
     * @return 精排得分或原始得分
     */
    private double getRelevanceScore(Document doc) {
        Object score = doc.getMetadata().get("relevanceScore");
        if (score instanceof Number) {
            return ((Number) score).doubleValue();
        }
        // 兜底：使用原始融合得分
        return doc.getScore() != null ? doc.getScore() : 0.0;
    }

    // ==================== 请求/响应 DTO ====================

    /**
     * Rerank API 响应体。
     * <p>硅基流动 Rerank 接口返回格式：{@code {"results":[{"index":0,"relevance_score":0.98},...]}}</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RerankResponsePayload {
        public List<RerankResultItem> results;
    }

    /**
     * 单条重排结果项。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RerankResultItem {
        /** 文档在原始候选列表中的索引 */
        public int index;
        /** 相关性得分（越高越相关） */
        public double relevanceScore;
    }
}
