package com.rag.config.vectorstore;

import com.rag.common.entity.config.SearchConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 多路召回融合策略工具类。
 * <p>
 * 支持两种融合算法：
 * <ul>
 *   <li><b>RRF（倒数排名融合，Reciprocal Rank Fusion）</b>：默认方案，
 *       公式 score = Σ 1/(k + rank)，消除向量得分与 BM25 得分的量级差异。</li>
 *   <li><b>加权求和融合（Weighted Sum）</b>：得分归一化后按权重相加。</li>
 * </ul>
 * </p>
 */
public final class SearchFusion {

    private static final Logger log = LoggerFactory.getLogger(SearchFusion.class);

    /** 默认 RRF 常数 k */
    private static final int DEFAULT_RRF_K = 60;

    /** 默认向量权重 */
    private static final double DEFAULT_VECTOR_WEIGHT = 0.5;

    /** 默认 BM25 权重 */
    private static final double DEFAULT_BM25_WEIGHT = 0.5;

    private SearchFusion() {
        // 工具类禁止实例化
    }

    /**
     * 执行多路召回融合，入口方法。
     * <p>
     * 两路结果各自取 topK * 2 条候选，按 chunkId / textHash 去重后融合排序，
     * 最终截取 topK 条返回。
     * </p>
     *
     * @param vectorResults 向量检索结果列表
     * @param bm25Results   BM25 检索结果列表
     * @param topK          最终返回条数
     * @param config        融合配置（指定 fusionMode、rrfK、权重等）
     * @return 融合排序后的最终结果，最多 topK 条
     */
    public static List<Document> fuse(List<Document> vectorResults,
                                      List<Document> bm25Results,
                                      int topK,
                                      SearchConfig config) {
        if (config == null) {
            config = SearchConfig.hybrid();
        }

        // 去重 key 提取函数：优先 chunkId → textHash → text 内容
        String fusionMode = config.getFusionMode() != null ? config.getFusionMode().toLowerCase() : "rrf";

        // 两路各取 topK * 2 候选
        int candidateLimit = topK * 2;
        List<Document> vecCandidates = limitResults(vectorResults, candidateLimit);
        List<Document> bm25Candidates = limitResults(bm25Results, candidateLimit);

        if (vecCandidates.isEmpty() && bm25Candidates.isEmpty()) {
            return List.of();
        }

        if ("weighted".equals(fusionMode)) {
            return weightedSumFusion(vecCandidates, bm25Candidates, topK, config);
        }

        // 默认 RRF 融合
        return rrfFusion(vecCandidates, bm25Candidates, topK, config.getRrfK());
    }

    // ==================== RRF 融合 ====================

    /**
     * RRF（倒数排名融合）算法。
     * <p>
     * 公式：score(doc) = Σ 1/(k + rank_i)，其中 rank_i 为文档在第 i 路检索中的排名（从 1 开始）。
     * 同一文档在两路中出现时，取最高融合分。
     * </p>
     *
     * @param vectorResults 向量检索候选
     * @param bm25Results   BM25 检索候选
     * @param topK          最终返回条数
     * @param rrfK          RRF 常数 k
     * @return 融合后结果
     */
    private static List<Document> rrfFusion(List<Document> vectorResults,
                                            List<Document> bm25Results,
                                            int topK,
                                            int rrfK) {
        int k = rrfK > 0 ? rrfK : DEFAULT_RRF_K;
        // 使用 LinkedHashMap 保持插入顺序，同时记录最高分
        Map<String, FusionCandidate> candidateMap = new LinkedHashMap<>();

        // 向量检索排名计算
        for (int i = 0; i < vectorResults.size(); i++) {
            String key = extractKey(vectorResults.get(i));
            double rrfScore = 1.0 / (k + (i + 1));
            candidateMap.merge(key,
                    new FusionCandidate(vectorResults.get(i), rrfScore),
                    (existing, incoming) -> {
                        if (incoming.rrfScore > existing.rrfScore) {
                            existing.doc = incoming.doc;
                            existing.rrfScore = incoming.rrfScore;
                        }
                        return existing;
                    });
        }

        // BM25 检索排名计算
        for (int i = 0; i < bm25Results.size(); i++) {
            String key = extractKey(bm25Results.get(i));
            double rrfScore = 1.0 / (k + (i + 1));
            candidateMap.merge(key,
                    new FusionCandidate(bm25Results.get(i), rrfScore),
                    (existing, incoming) -> {
                        // 累加 RRF 得分
                        existing.rrfScore += incoming.rrfScore;
                        return existing;
                    });
        }

        // 按 RRF 得分降序排列，截取 topK
        return candidateMap.values().stream()
                .sorted(Comparator.comparingDouble(FusionCandidate::getRrfScore).reversed())
                .limit(topK)
                .peek(c -> c.doc.getMetadata().put("rrfScore", c.rrfScore))
                .map(FusionCandidate::getDoc)
                .collect(Collectors.toList());
    }

    // ==================== 加权求和融合 ====================

    /**
     * 加权求和融合算法。
     * <p>
     * 两路得分各自 Min-Max 归一化到 [0,1] 区间，再按配置权重加权相加。
     * 去重策略：同一文档出现于两路时，按最高加权得分保留。
     * </p>
     */
    private static List<Document> weightedSumFusion(List<Document> vectorResults,
                                                     List<Document> bm25Results,
                                                     int topK,
                                                     SearchConfig config) {
        double vw = config.getVectorWeight() != null ? config.getVectorWeight() : DEFAULT_VECTOR_WEIGHT;
        double bw = config.getBm25Weight() != null ? config.getBm25Weight() : DEFAULT_BM25_WEIGHT;

        // 归一化
        normalizeScores(vectorResults);
        normalizeScores(bm25Results);

        Map<String, FusionCandidate> candidateMap = new LinkedHashMap<>();

        // 向量路加权
        for (Document doc : vectorResults) {
            String key = extractKey(doc);
            double score = doc.getScore() != null ? doc.getScore() * vw : 0;
            candidateMap.put(key, new FusionCandidate(doc, score));
        }

        // BM25 路加权，同 key 时取最高加权分
        for (Document doc : bm25Results) {
            String key = extractKey(doc);
            double score = doc.getScore() != null ? doc.getScore() * bw : 0;
            candidateMap.merge(key,
                    new FusionCandidate(doc, score),
                    (existing, incoming) -> {
                        if (incoming.rrfScore > existing.rrfScore) {
                            existing.doc = incoming.doc;
                            existing.rrfScore = incoming.rrfScore;
                        }
                        return existing;
                    });
        }

        return candidateMap.values().stream()
                .sorted(Comparator.comparingDouble(FusionCandidate::getRrfScore).reversed())
                .limit(topK)
                .peek(c -> c.doc.getMetadata().put("weightedScore", c.rrfScore))
                .map(FusionCandidate::getDoc)
                .collect(Collectors.toList());
    }

    // ==================== 辅助方法 ====================

    /**
     * 截取候选列表前 limit 条。
     */
    private static List<Document> limitResults(List<Document> results, int limit) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.size() <= limit ? results : results.subList(0, limit);
    }

    /**
     * 提取文档去重 key，优先级：textHash → chunkId → id → text 内容前 64 字符。
     */
    private static String extractKey(Document doc) {
        if (doc == null) {
            return "";
        }
        Map<String, Object> meta = doc.getMetadata();
        if (meta != null) {
            Object textHash = meta.get("textHash");
            if (textHash != null && !String.valueOf(textHash).isBlank()) {
                return "hash:" + textHash;
            }
            Object chunkId = meta.get("chunkId");
            if (chunkId != null && !String.valueOf(chunkId).isBlank()) {
                return "cid:" + chunkId;
            }
        }
        String id = doc.getId();
        if (id != null && !id.isBlank()) {
            return "id:" + id;
        }
        String text = doc.getText();
        if (text != null && !text.isBlank()) {
            return "txt:" + text.substring(0, Math.min(64, text.length()));
        }
        return "";
    }

    /**
     * Min-Max 归一化：将文档得分归一化到 [0, 1] 区间。
     * <p>若所有得分相同则全部置为 1.0。</p>
     */
    private static void normalizeScores(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        DoubleSummaryStatistics stats = docs.stream()
                .filter(d -> d.getScore() != null)
                .mapToDouble(Document::getScore)
                .summaryStatistics();
        double min = stats.getMin();
        double max = stats.getMax();
        double range = max - min;

        if (range <= 1e-10) {
            // 所有得分相同，统一置为 1.0
            docs.forEach(d -> {
                if (d.getScore() != null) {
                    d.getMetadata().put("normalizedScore", 1.0);
                }
            });
        } else {
            docs.forEach(d -> {
                if (d.getScore() != null) {
                    double normalized = (d.getScore() - min) / range;
                    d.getMetadata().put("normalizedScore", normalized);
                }
            });
        }
    }

    // ==================== 内部数据类 ====================

    /**
     * 融合候选对象，存储文档与融合得分。
     */
    private static class FusionCandidate {
        Document doc;
        double rrfScore;

        FusionCandidate(Document doc, double rrfScore) {
            this.doc = doc;
            this.rrfScore = rrfScore;
        }

        Document getDoc() { return doc; }
        double getRrfScore() { return rrfScore; }
    }
}
