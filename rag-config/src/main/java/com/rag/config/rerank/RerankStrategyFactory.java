package com.rag.config.rerank;

import com.rag.common.entity.config.RerankConfig;
import com.rag.common.rerank.RerankStrategy;
import com.rag.common.client.OpenAiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重排策略工厂 —— 工厂模式。
 * <p>
 * 根据 {@link RerankConfig} 中的模型类型标识，实例化并返回对应的 {@link RerankStrategy} 策略实现。
 * 所有策略实现统一委托给 {@link OpenAiClient#rerank}，限流与分批能力已内置在客户端中。
 * 支持策略实例缓存复用（{@link ConcurrentHashMap}），避免重复创建对象。
 * </p>
 *
 * <h3>设计模式说明</h3>
 * <ul>
 *   <li><b>工厂模式</b>：封装策略实例化逻辑，调用方仅需传入配置，无需关心具体策略的构造细节。</li>
 *   <li><b>缓存复用</b>：按模型类型缓存策略实例，同一模型类型多次请求共享同一实例（线程安全）。</li>
 *   <li><b>安全降级</b>：未匹配到模型类型时返回 {@code null}，调用方据此跳过重排节点。</li>
 * </ul>
 *
 * <h3>扩展方式</h3>
 * <p>新增重排模型时，在 {@link #build(RerankConfig)} 的 switch 中增加新的 case 分支即可，
 * 调用方无需修改任何代码。</p>
 *
 * @author rag-tool
 * @see RerankStrategy
 * @see OpenAiClient
 * @since 1.0
 */
public class RerankStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(RerankStrategyFactory.class);

    /** 统一 OpenAI 兼容客户端 */
    private final OpenAiClient openAiClient;
    /** 策略实例缓存，key=模型类型标识 */
    private final Map<String, RerankStrategy> cache = new ConcurrentHashMap<>();

    public RerankStrategyFactory(OpenAiClient openAiClient) {
        this.openAiClient = openAiClient;
    }

    /**
     * 根据重排配置获取对应的策略实例。
     * <p>
     * 配置关闭或模型类型为空时直接返回 {@code null}（表示跳过重排）。
     * 策略实例按模型类型缓存，同一模型类型多次请求共享同一实例。
     * </p>
     *
     * @param config 重排配置
     * @return 对应的策略实例；配置关闭或未匹配到模型时返回 {@code null}
     */
    public RerankStrategy getStrategy(RerankConfig config) {
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            log.debug("重排未启用，跳过策略实例化");
            return null;
        }

        String modelType = config.getModelType();
        if (modelType == null || modelType.isBlank()) {
            log.debug("重排模型类型为空，跳过策略实例化");
            return null;
        }

        return cache.computeIfAbsent(modelType, key -> build(config));
    }

    /**
     * 构建具体策略实例（适配器模式：委托给 OpenAiClient.rerank()）。
     */
    private RerankStrategy build(RerankConfig config) {
        String modelType = config.getModelType();
        log.info("构建重排策略: modelType={}, modelName={}", modelType, config.getModelName());

        return switch (modelType.toLowerCase()) {
            case "qwen3-reranker" -> new OpenAiRerankAdapter(openAiClient, config);
            // 后续扩展示例：
            // case "cohere-rerank" -> new OpenAiRerankAdapter(openAiClient, config);
            default -> {
                log.warn("未知的重排模型类型: {}，将跳过重排", modelType);
                yield null;
            }
        };
    }

    /**
     * 手动清理缓存（用于配置变更后强制重建策略实例）。
     *
     * @param modelType 要清理的模型类型，为 null 时清空全部缓存
     */
    public void evictCache(String modelType) {
        if (modelType == null) {
            cache.clear();
            log.info("重排策略缓存已全部清空");
        } else {
            RerankStrategy removed = cache.remove(modelType);
            log.info("重排策略缓存已清理: modelType={}, existed={}", modelType, removed != null);
        }
    }

    // ==================== 内部适配器 ====================

    /**
     * 基于 OpenAiClient 的 RerankStrategy 适配器。
     * <p>将策略接口调用委托给 {@link OpenAiClient#rerank}，限流和分批已内置。</p>
     */
    private static class OpenAiRerankAdapter implements RerankStrategy {

        private final OpenAiClient client;
        private final RerankConfig config;

        OpenAiRerankAdapter(OpenAiClient client, RerankConfig config) {
            this.client = client;
            this.config = config;
        }

        @Override
        public List<Document> rerank(String query, List<Document> candidates) {
            int batchSize = config.getBatchSize() != null ? config.getBatchSize() : 20;
            int maxQps = config.getMaxQps() != null ? config.getMaxQps() : 5;
            return client.rerank(config.getBaseUrl(), config.getApiKey(), config.getModelName(),
                    query, candidates, batchSize, maxQps);
        }
    }
}
