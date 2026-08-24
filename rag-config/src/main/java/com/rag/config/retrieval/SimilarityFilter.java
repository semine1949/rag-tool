package com.rag.config.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 相似度阈值过滤器。
 * <p>
 * 在检索召回后、上下文组装前，对召回片段执行相似度阈值过滤，
 * 仅保留 score ≥ threshold 的片段，过滤低相关/无相关内容，
 * 避免无效内容干扰生成、挤占上下文 Token 窗口。
 * </p>
 * <p>
 * 统一覆盖单库检索、多库联合检索、临时文档召回三类场景。
 * 过滤后为空时由调用方触发无答案降级逻辑。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Component
public class SimilarityFilter {

    private static final Logger log = LoggerFactory.getLogger(SimilarityFilter.class);

    /** 元数据 Key：文档相似度得分 */
    private static final String SCORE_KEY = "score";

    /**
     * 过滤低于阈值的文档片段。
     *
     * @param documents 原始召回文档列表
     * @param threshold 相似度阈值（0.0~1.0），仅保留 score ≥ threshold 的片段
     * @return 过滤后的文档列表；输入为空或 threshold 非法时返回原列表
     */
    public List<Document> filter(List<Document> documents, double threshold) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }
        if (threshold <= 0.0 || threshold > 1.0) {
            log.debug("相似度阈值 [{}] 不在有效范围 (0.0, 1.0]，跳过过滤", threshold);
            return documents;
        }

        int before = documents.size();
        List<Document> filtered = documents.stream()
                .filter(doc -> {
                    Object scoreObj = doc.getMetadata().get(SCORE_KEY);
                    if (scoreObj == null) {
                        // 无得分元数据，保留（兼容旧数据）
                        return true;
                    }
                    double score;
                    if (scoreObj instanceof Number) {
                        score = ((Number) scoreObj).doubleValue();
                    } else {
                        try {
                            score = Double.parseDouble(scoreObj.toString());
                        } catch (NumberFormatException e) {
                            return true;
                        }
                    }
                    return score >= threshold;
                })
                .collect(Collectors.toList());

        int after = filtered.size();
        int removed = before - after;
        if (removed > 0) {
            log.info("相似度阈值过滤 [{}]：{} 条 → {} 条（移除 {} 条低分片段）",
                    threshold, before, after, removed);
        }
        return filtered;
    }
}