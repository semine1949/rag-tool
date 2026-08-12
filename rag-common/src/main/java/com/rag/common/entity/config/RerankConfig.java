package com.rag.common.entity.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 重排配置 POJO，封装重排模型调用所需的所有参数。
 * <p>
 * 配置优先级：请求参数 > 知识库配置 > 全局默认配置（application.yml）。
 * 所有字段均可空，null 时由上层按优先级逐级回退至全局默认值。
 * </p>
 * <p>
 * 设计模式说明：此 POJO 属于策略模式的上下文载体，承载策略实例化所需的配置参数。
 * 与具体重排策略解耦，后续新增重排模型只需扩展此配置中的模型类型即可。
 * </p>
 *
 * @see com.rag.common.rerank.RerankStrategy
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RerankConfig {

    /** 重排开关：true=启用精排，false/null=跳过重排走原始排序 */
    @Builder.Default
    private Boolean enabled = false;

    /**
     * 重排模型类型标识，用于工厂匹配具体策略实现。
     * <p>首期固定 {@code "qwen3-reranker"}，后续扩展其他模型时新增标识即可。</p>
     */
    @Builder.Default
    private String modelType = "qwen3-reranker";

    /**
     * 模型名称（API 调用时使用的模型 ID）。
     * <p>默认值：{@code Qwen/Qwen3-Reranker}（硅基流动模型名）</p>
     */
    @Builder.Default
    private String modelName = "Qwen/Qwen3-Reranker";

    /**
     * API Base URL，用于重排接口调用。
     * <p>默认值：{@code https://api.siliconflow.cn/v1}（硅基流动端点）</p>
     */
    @Builder.Default
    private String baseUrl = "https://api.siliconflow.cn/v1";

    /**
     * API Key（Bearer Token 鉴权）。
     * <p>复用硅基流动 apiKey，与 Embedding 模型共用凭证。</p>
     */
    private String apiKey;

    // ==================== 候选池放大参数 ====================

    /**
     * 候选池放大倍数。
     * <p>启用重排时，召回阶段取 {@code topK * candidateMultiplier} 条候选送入精排，
     * 精排后再截取最终 topK。默认 3 倍。</p>
     */
    @Builder.Default
    private Integer candidateMultiplier = 3;

    // ==================== 分批与超时参数 ====================

    /**
     * 单次 Rerank API 请求最大文档数。
     * <p>超过此数量时自动分批调用。默认 20 条/批。</p>
     */
    @Builder.Default
    private Integer batchSize = 20;

    /**
     * 连接超时时间（秒），默认 30 秒。
     */
    @Builder.Default
    private Integer connectTimeoutSeconds = 30;

    /**
     * 读取超时时间（秒），默认 60 秒。
     */
    @Builder.Default
    private Integer readTimeoutSeconds = 60;

    // ==================== 限流参数 ====================

    /**
     * 每秒最大调用次数（限流保护），默认 5 次/秒。
     * <p>超出阈值时自动降级为原始排序，不中断检索请求。</p>
     */
    @Builder.Default
    private Integer maxQps = 5;

    // ==================== 工厂方法 ====================

    /**
     * 创建默认关闭的重排配置。
     * <p>用于未配置重排的知识库，确保重排节点完全跳过。</p>
     */
    public static RerankConfig disabled() {
        return RerankConfig.builder().enabled(false).build();
    }

    /**
     * 创建 Qwen3-Reranker 默认重排配置。
     * <p>启用重排，候选放大 3 倍，其余参数使用内置默认值。</p>
     *
     * @param apiKey API Key（必填，用于 Bearer Token 鉴权）
     */
    public static RerankConfig qwen3Default(String apiKey) {
        return RerankConfig.builder()
                .enabled(true)
                .modelType("qwen3-reranker")
                .modelName("Qwen/Qwen3-Reranker")
                .baseUrl("https://api.siliconflow.cn/v1")
                .apiKey(apiKey)
                .candidateMultiplier(3)
                .batchSize(20)
                .connectTimeoutSeconds(30)
                .readTimeoutSeconds(60)
                .maxQps(5)
                .build();
    }
}
