package com.rag.config.model;

import com.rag.common.client.OpenAiClient;
import com.rag.config.properties.AiModelProperties;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 协议重排序模型实现。
 * <p>
 * 复用 {@link OpenAiClient} 的 HTTP 能力完成 {@code /rerank} 端点调用，
 * 将 Spring AI 无关的精排结果转换为 {@link RerankResult} 列表。
 * Spring AI 无标准 RerankModel 抽象，故本实现不走协议适配器，由工厂直接创建。
 * </p>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li>HTTP 调用、限流、分批均由 {@link OpenAiClient#rerank} 内置处理。</li>
 *   <li>本类负责将返回的文档（含 {@code relevanceScore} 元数据）映射为索引+得分结果。</li>
 * </ul>
 *
 * @author rag-tool
 * @since 1.0
 */
public class OpenAiRerankModel implements RerankModel {

    /** 统一 OpenAI 兼容客户端（复用 HTTP 能力） */
    private final OpenAiClient openAiClient;

    /** 模型配置（baseUrl / apiKey / modelName / batchSize / maxQps） */
    private final AiModelProperties.ModelConfig config;

    /**
     * 构造器。
     *
     * @param openAiClient 统一 OpenAI 兼容客户端
     * @param config       模型配置
     */
    public OpenAiRerankModel(OpenAiClient openAiClient, AiModelProperties.ModelConfig config) {
        this.openAiClient = openAiClient;
        this.config = config;
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        // 将文本列表转为 Document 列表（供 OpenAiClient.rerank 处理）
        List<Document> candidates = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("originalIndex", i);
            candidates.add(new Document(String.valueOf(i), documents.get(i), meta));
        }

        // 复用 OpenAiClient 的 HTTP 能力（内置限流 + 分批）
        int batchSize = config.getExtensions() != null && config.getExtensions().containsKey("batchSize")
                ? ((Number) config.getExtensions().get("batchSize")).intValue() : 20;
        int maxQps = config.getExtensions() != null && config.getExtensions().containsKey("maxQps")
                ? ((Number) config.getExtensions().get("maxQps")).intValue() : 5;

        List<Document> reranked = openAiClient.rerank(
                config.getBaseUrl(), config.getApiKey(), config.getModelName(),
                query, candidates, batchSize, maxQps);

        // 转换为 RerankResult（索引 + 得分）
        List<RerankResult> results = new ArrayList<>(reranked.size());
        for (Document doc : reranked) {
            int index = ((Number) doc.getMetadata().get("originalIndex")).intValue();
            double score = 0.0;
            Object scoreObj = doc.getMetadata().get("relevanceScore");
            if (scoreObj instanceof Number number) {
                score = number.doubleValue();
            }
            results.add(new RerankResult(index, score));
        }

        // 按得分降序排列
        results.sort(Comparator.comparingDouble(RerankResult::score).reversed());

        // 截取 topN
        if (topN > 0 && results.size() > topN) {
            return results.subList(0, topN);
        }
        return results;
    }
}
