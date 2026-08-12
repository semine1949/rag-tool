package com.rag.common.rerank;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 重排策略抽象契约 —— 策略模式核心接口。
 * <p>
 * 定义重排行为的输入输出标准，与具体重排模型解耦。
 * 所有重排策略实现（Qwen3-Reranker、后续模型）均需实现此接口。
 * </p>
 *
 * <h3>设计模式说明</h3>
 * <ul>
 *   <li><b>策略模式</b>：此接口为策略抽象，具体实现为独立策略类，
 *       检索主流程仅依赖此接口，不依赖任何具体重排实现。</li>
 *   <li><b>扩展方式</b>：新增重排模型时，只需新建实现类 + 在工厂注册模型类型映射，
 *       无需修改检索主流程和现有策略。</li>
 * </ul>
 *
 * <h3>输入输出约定</h3>
 * <ul>
 *   <li>输入：用户查询语句 + 候选文档列表（多路召回融合后的结果）</li>
 *   <li>输出：重排序后的文档列表，精排相关性得分写入 {@code Document.metadata["relevanceScore"]}</li>
 *   <li>异常语义：实现层应自行处理异常，返回原始候选列表作为降级，不向上抛出</li>
 * </ul>
 *
 * @author rag-tool
 * @since 1.0
 */
@FunctionalInterface
public interface RerankStrategy {

    /**
     * 对候选文档列表执行重排序。
     * <p>
     * 实现层职责：
     * <ol>
     *   <li>调用远程 Rerank API 获取精排得分</li>
     *   <li>将精排得分写入 {@code doc.metadata["relevanceScore"]}</li>
     *   <li>按精排得分降序排列结果</li>
     *   <li>异常时返回原始候选列表（降级），记录告警日志</li>
     * </ol>
     * </p>
     *
     * @param query      用户查询语句（不可为空）
     * @param candidates 候选文档列表（多路召回融合后的结果）
     * @return 重排序后的文档列表（按精排得分降序），异常降级时返回原始候选列表
     */
    List<Document> rerank(String query, List<Document> candidates);
}
