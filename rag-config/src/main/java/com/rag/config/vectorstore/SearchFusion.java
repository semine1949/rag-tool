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
     * 两路结果各自取 topK * 2 条候选，汇总后先执行两轮阶梯去重预处理：
     * <ol>
     *   <li>第一轮：docId + chunkId 实例级精准去重，合并同分片得分</li>
     *   <li>第二轮：textHash 内容级冗余去重，保留最高分结果</li>
     * </ol>
     * 去重后进入融合排序，最终截取 topK 条返回。
     * </p>
     * <p>
     * 注意：仅 HYBRID 混合模式触发去重逻辑，单路检索（纯向量/纯BM25）完全不进入此方法。
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

        String fusionMode = config.getFusionMode() != null ? config.getFusionMode().toLowerCase() : "rrf";

        // 两路各取 topK * 2 候选
        int candidateLimit = topK * 2;
        List<Document> vecCandidates = limitResults(vectorResults, candidateLimit);
        List<Document> bm25Candidates = limitResults(bm25Results, candidateLimit);

        if (vecCandidates.isEmpty() && bm25Candidates.isEmpty()) {
            return List.of();
        }

        // === 两轮阶梯去重预处理：汇总两路候选，构建带排名信息的 RankedDocument 列表 ===
        int rrfK = config.getRrfK() > 0 ? config.getRrfK() : DEFAULT_RRF_K;
        List<RankedDocument> allCandidates = new ArrayList<>();

        // 向量路候选：记录原始排名（1-based）和来源
        for (int i = 0; i < vecCandidates.size(); i++) {
            Document doc = vecCandidates.get(i);
            double score = "weighted".equals(fusionMode)
                    ? computeWeightedScore(doc, config.getVectorWeight() != null ? config.getVectorWeight() : DEFAULT_VECTOR_WEIGHT)
                    : 1.0 / (rrfK + (i + 1)); // RRF 预计算排名分
            allCandidates.add(new RankedDocument(doc, score, i + 1, "vector"));
        }

        // BM25 路候选：记录原始排名（1-based）和来源
        for (int i = 0; i < bm25Candidates.size(); i++) {
            Document doc = bm25Candidates.get(i);
            double score = "weighted".equals(fusionMode)
                    ? computeWeightedScore(doc, config.getBm25Weight() != null ? config.getBm25Weight() : DEFAULT_BM25_WEIGHT)
                    : 1.0 / (rrfK + (i + 1)); // RRF 预计算排名分
            allCandidates.add(new RankedDocument(doc, score, i + 1, "bm25"));
        }

        // 执行两轮阶梯去重
        List<Document> dedupedCandidates = deduplicateCandidates(allCandidates, fusionMode, rrfK);
        log.debug("融合去重预处理：{} 条候选 → {} 条去重后", allCandidates.size(), dedupedCandidates.size());

        if (dedupedCandidates.isEmpty()) {
            return List.of();
        }

        // === 去重后进入融合排序阶段 ===
        // 注意：加权求和模式下需要先对去重后的候选做归一化（因为得分来自不同来源）
        if ("weighted".equals(fusionMode)) {
            normalizeScores(dedupedCandidates);
        }

        if ("weighted".equals(fusionMode)) {
            return weightedSumFusionFromDeduped(dedupedCandidates, topK, config);
        }

        // 默认 RRF 融合：使用去重后的候选 + 已预计算的 RRF 得分
        return rrfFusionFromDeduped(dedupedCandidates, topK, rrfK);
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

    /**
     * 计算单个文档的加权得分（使用 rawScore 或 normalizedScore）。
     * <p>
     * 优先使用归一化后的得分（normalizedScore），若不存在则使用原始得分（rawScore）。
     * 用于在两轮去重前预计算各候选的加权分。
     * </p>
     *
     * @param doc    文档对象
     * @param weight 该路的权重
     * @return 加权后的得分
     */
    private static double computeWeightedScore(Document doc, double weight) {
        Map<String, Object> meta = doc.getMetadata();
        // 优先使用 normalizedScore，其次使用原始 score
        Double score = null;
        if (meta != null) {
            Object normalized = meta.get("normalizedScore");
            if (normalized instanceof Number) {
                score = ((Number) normalized).doubleValue();
            }
        }
        if (score == null) {
            score = doc.getScore() != null ? doc.getScore() : 0.0;
        }
        return score * weight;
    }

    /**
     * RRF 融合：使用已去重、已预计算 RRF 得分的候选列表。
     * <p>
     * 此方法接收两轮去重后的 Document 列表。每个 Document 的 score 字段
     * 已在 fuse() 入口预计算为 RRF 排名分（1/(k+rank)）。
     * 去重阶段可能已对同 docId+chunkId 做了得分累加，此处直接排序截取即可。
     * </p>
     * <p>
     * 保留内层 extractKey 去重作为兜底防护，防止极端情况下仍有残留重复。
     * </p>
     *
     * @param dedupedCandidates 两轮去重后的候选文档列表
     * @param topK              最终返回条数
     * @param rrfK              RRF 常数 k
     * @return 融合排序后的最终结果
     */
    private static List<Document> rrfFusionFromDeduped(List<Document> dedupedCandidates,
                                                        int topK,
                                                        int rrfK) {
        int k = rrfK > 0 ? rrfK : DEFAULT_RRF_K;
        Map<String, FusionCandidate> candidateMap = new LinkedHashMap<>();

        for (Document doc : dedupedCandidates) {
            String key = extractKey(doc);
            // 优先从 score 字段获取已预计算的 RRF 分（去重阶段已计算），否则重新计算
            double rrfScore;
            if (doc.getScore() != null) {
                rrfScore = doc.getScore();
            } else {
                rrfScore = 1.0 / (k + 1); // 兜底
            }
            candidateMap.merge(key,
                    new FusionCandidate(doc, rrfScore),
                    (existing, incoming) -> {
                        // 兜底：累加 RRF 得分
                        existing.rrfScore += incoming.rrfScore;
                        return existing;
                    });
        }

        return candidateMap.values().stream()
                .sorted(Comparator.comparingDouble(FusionCandidate::getRrfScore).reversed())
                .limit(topK)
                .peek(c -> c.doc.getMetadata().put("rrfScore", c.rrfScore))
                .map(FusionCandidate::getDoc)
                .collect(Collectors.toList());
    }

    /**
     * 加权求和融合：使用已去重、已归一化的候选列表。
     * <p>
     * 此方法接收两轮去重后的 Document 列表。在 fuse() 入口中已对去重后的
     * 候选做了 Min-Max 归一化，此处直接按配置权重加权求和即可。
     * </p>
     * <p>
     * 保留内层 extractKey 去重作为兜底防护。
     * </p>
     *
     * @param dedupedCandidates 两轮去重后、已归一化的候选文档列表
     * @param topK              最终返回条数
     * @param config            融合配置
     * @return 融合排序后的最终结果
     */
    private static List<Document> weightedSumFusionFromDeduped(List<Document> dedupedCandidates,
                                                                int topK,
                                                                SearchConfig config) {
        double vw = config.getVectorWeight() != null ? config.getVectorWeight() : DEFAULT_VECTOR_WEIGHT;
        double bw = config.getBm25Weight() != null ? config.getBm25Weight() : DEFAULT_BM25_WEIGHT;

        Map<String, FusionCandidate> candidateMap = new LinkedHashMap<>();

        for (Document doc : dedupedCandidates) {
            String key = extractKey(doc);
            double normalizedScore = 1.0; // 默认
            Map<String, Object> meta = doc.getMetadata();
            if (meta != null) {
                Object ns = meta.get("normalizedScore");
                if (ns instanceof Number) {
                    normalizedScore = ((Number) ns).doubleValue();
                }
            }
            // 根据来源确定权重（如果无法区分，使用平均权重）
            double weight = (vw + bw) / 2.0;
            double score = normalizedScore * weight;
            candidateMap.merge(key,
                    new FusionCandidate(doc, score),
                    (existing, incoming) -> {
                        // 兜底：取最高加权分
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

    // ==================== 两轮阶梯去重 ====================

    /**
     * 两轮阶梯去重预处理：在两路候选汇总完成后、正式融合得分计算前执行。
     * <p>
     * 第一轮：按 docId + chunkId 做实例级精准去重，同一条分片被两路同时召回时合并得分。
     * 第二轮：按 textHash 做内容级冗余去重，消除不同分片但文本内容完全一致的冗余。
     * </p>
     *
     * @param candidates 汇总后的候选文档列表（含排名信息）
     * @param fusionMode 融合模式（"rrf" 或 "weighted"）
     * @param rrfK       RRF 常数 k（仅 RRF 模式使用）
     * @return 两轮去重后的候选文档列表
     */
    private static List<Document> deduplicateCandidates(List<RankedDocument> candidates,
                                                         String fusionMode,
                                                         int rrfK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // 第一轮：docId + chunkId 实例级精准去重
        List<RankedDocument> afterRound1 = deduplicateByDocChunkId(candidates, fusionMode, rrfK);
        log.debug("第一轮去重完成：{} 条 → {} 条", candidates.size(), afterRound1.size());

        // 第二轮：textHash 内容指纹去重
        List<RankedDocument> afterRound2 = deduplicateByTextHash(afterRound1);
        log.debug("第二轮去重完成：{} 条 → {} 条", afterRound1.size(), afterRound2.size());

        // 转换回 Document 列表
        return afterRound2.stream()
                .map(RankedDocument::doc)
                .collect(Collectors.toList());
    }

    /**
     * 第一轮去重：docId + chunkId 实例级精准去重。
     * <p>
     * 定位：处理同一条分片实例同时被向量检索和 BM25 检索命中（完全同一条数据的重复召回）。
     * 主键格式：{@code {docId}_{chunkId}}
     * </p>
     * <p>
     * 得分合并规则：
     * <ul>
     *   <li>RRF 融合模式：同主键的两条结果，RRF 排名分累加</li>
     *   <li>加权求和融合模式：同主键的两条结果，取更高的加权得分</li>
     * </ul>
     * </p>
     * <p>
     * 兜底：若候选结果缺失 docId 或 chunkId，不执行本轮去重，直接流入下一轮。
     * </p>
     *
     * @param candidates 原始候选列表
     * @param fusionMode 融合模式
     * @param rrfK       RRF 常数 k
     * @return 第一轮去重后的候选列表
     */
    private static List<RankedDocument> deduplicateByDocChunkId(List<RankedDocument> candidates,
                                                                  String fusionMode,
                                                                  int rrfK) {
        // 使用 LinkedHashMap 保持插入顺序
        Map<String, RankedDocument> dedupMap = new LinkedHashMap<>();

        for (RankedDocument rd : candidates) {
            Document doc = rd.doc();
            Map<String, Object> meta = doc.getMetadata();
            String key = buildDocChunkKey(meta);

            // 缺失 docId 或 chunkId 时，跳过本轮去重，直接保留（使用原始排名作为唯一键区分）
            if (key == null) {
                String fallbackKey = extractFallbackKey(doc);
                dedupMap.put(fallbackKey, rd);
                continue;
            }

            // 同主键合并逻辑
            if (dedupMap.containsKey(key)) {
                RankedDocument existing = dedupMap.get(key);
                if ("weighted".equals(fusionMode)) {
                    // 加权求和模式：取更高得分
                    if (rd.score() > existing.score()) {
                        dedupMap.put(key, rd);
                    }
                } else {
                    // RRF 模式：得分累加
                    double accumulatedScore = existing.score() + rd.score();
                    // 保留排名更靠前的文档
                    RankedDocument merged = existing.originalRank() <= rd.originalRank()
                            ? existing : rd;
                    dedupMap.put(key, new RankedDocument(merged.doc(), accumulatedScore,
                            merged.originalRank(), merged.source()));
                }
            } else {
                dedupMap.put(key, rd);
            }
        }

        return new ArrayList<>(dedupMap.values());
    }

    /**
     * 第二轮去重：textHash 内容指纹去重。
     * <p>
     * 定位：消除不同 docId/chunkId 但文本内容完全一致的冗余分片
     * （如重复上传文档、不同文档包含完全相同段落）。
     * </p>
     * <p>
     * 得分处理规则：无论 RRF 模式还是加权求和模式，均保留该哈希下融合得分最高的一条结果。
     * 若得分完全相同，保留原始召回排名更靠前的条目。
     * </p>
     * <p>
     * 兜底：若候选结果缺失 textHash，跳过本轮内容级去重，直接保留原结果。
     * </p>
     *
     * @param candidates 第一轮去重后的候选列表
     * @return 第二轮去重后的候选列表
     */
    private static List<RankedDocument> deduplicateByTextHash(List<RankedDocument> candidates) {
        // 使用 LinkedHashMap 保持插入顺序
        Map<String, RankedDocument> dedupMap = new LinkedHashMap<>();

        for (RankedDocument rd : candidates) {
            Document doc = rd.doc();
            Map<String, Object> meta = doc.getMetadata();
            String textHash = getStringMeta(meta, "textHash");

            // 缺失 textHash 时跳过本轮去重
            if (textHash == null || textHash.isBlank()) {
                String fallbackKey = extractFallbackKey(doc);
                dedupMap.put(fallbackKey, rd);
                continue;
            }

            String key = "hash:" + textHash;

            // 同哈希冲突时保留最高分
            if (dedupMap.containsKey(key)) {
                RankedDocument existing = dedupMap.get(key);
                // 得分相同时，保留原始排名更靠前的条目
                if (rd.score() > existing.score()
                        || (Math.abs(rd.score() - existing.score()) < 1e-10
                        && rd.originalRank() < existing.originalRank())) {
                    dedupMap.put(key, rd);
                }
            } else {
                dedupMap.put(key, rd);
            }
        }

        return new ArrayList<>(dedupMap.values());
    }

    /**
     * 构建 docId + chunkId 联合主键。
     *
     * @param meta 文档元数据
     * @return 联合主键字符串，格式为 {@code {docId}_{chunkId}}；若缺少字段则返回 null
     */
    private static String buildDocChunkKey(Map<String, Object> meta) {
        if (meta == null) {
            return null;
        }
        String docId = getStringMeta(meta, "docId");
        String chunkId = getStringMeta(meta, "chunkId");
        if (docId != null && !docId.isBlank() && chunkId != null && !chunkId.isBlank()) {
            return docId + "_" + chunkId;
        }
        return null;
    }

    /**
     * 兜底 key 提取：当 docId、chunkId、textHash 均缺失时的降级策略。
     * <p>
     * 优先级：Document id → 文本内容前 64 字符。
     * 兜底场景仅做去重保留单条，不做得分合并。
     * </p>
     *
     * @param doc 文档对象
     * @return 兜底去重 key
     */
    private static String extractFallbackKey(Document doc) {
        if (doc == null) {
            return "";
        }
        // 优先使用 Spring AI Document 原生 id
        String id = doc.getId();
        if (id != null && !id.isBlank()) {
            return "fb_id:" + id;
        }
        // 最终兜底：文本内容前 64 字符
        String text = doc.getText();
        if (text != null && !text.isBlank()) {
            return "fb_txt:" + text.substring(0, Math.min(64, text.length()));
        }
        return "";
    }

    /**
     * 安全获取元数据中的字符串值。
     */
    private static String getStringMeta(Map<String, Object> meta, String key) {
        if (meta == null) {
            return null;
        }
        Object value = meta.get(key);
        return value != null ? String.valueOf(value) : null;
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

    /**
     * 带排名信息的候选文档记录。
     * <p>
     * 用于两轮阶梯去重过程中携带原始召回排名、来源路径和当前得分，
     * 支持去重冲突时的优选决策。
     * </p>
     *
     * @param doc          文档对象
     * @param score        当前得分（RRF 模式为 RRF 分，加权求和模式为加权分）
     * @param originalRank 原始召回排名（1-based），用于同分时优选
     * @param source       召回来源标识（"vector" / "bm25"）
     */
    private record RankedDocument(Document doc, double score, int originalRank, String source) {
    }
}
