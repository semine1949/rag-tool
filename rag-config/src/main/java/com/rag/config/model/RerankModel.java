package com.rag.config.model;

import java.util.List;

/**
 * 重排序模型接口。
 * <p>
 * 定义精排行为的统一契约，屏蔽不同重排服务（Qwen3-Reranker、Cohere Rerank 等）的差异。
 * 与 Spring AI 的 {@code ChatModel}/{@code EmbeddingModel} 不同，Spring AI 无标准
 * RerankModel 抽象，故此处自行定义，并由工厂直接创建（不走协议适配器）。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
public interface RerankModel {

    /**
     * 对候选文档执行重排序。
     *
     * @param query     用户查询语句
     * @param documents 候选文档文本列表
     * @param topN      返回前 N 条（<=0 表示返回全部）
     * @return 按精排得分降序排列的结果列表（含原始索引与得分）
     */
    List<RerankResult> rerank(String query, List<String> documents, int topN);

    /**
     * 单条重排序结果。
     *
     * @param index 原始文档在输入列表中的索引
     * @param score 精排相关性得分
     */
    record RerankResult(int index, double score) {
    }
}
