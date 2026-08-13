package com.rag.config.store;

import com.rag.common.enums.ModelCategory;
import com.rag.config.factory.AiModelFactory;
import com.rag.config.factory.VectorStoreRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 向量库配置类。
 * <p>
 * 根据 {@code provider} 配置创建对应的 {@link VectorStore} 实例：
 * <ul>
 *   <li>{@code weaviate}：复用项目自研的 {@code WeaviateVectorStoreAdapter}（基于 io.weaviate:client）。</li>
 *   <li>{@code milvus}：预留扩展位，当前项目未引入 Milvus 连接器，抛出明确异常提示。</li>
 * </ul>
 * 嵌入模型通过 {@link #resolveEmbeddingModel()} 从配置中查找第一个 EMBEDDING 类别模型，
 * 由 {@link AiModelFactory} 统一创建（工厂模式）。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Configuration
public class VectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    /** 向量库类型：weaviate / milvus */
    @Value("${rag.vectorstore.provider:weaviate}")
    private String provider;

    /** 集合名称 */
    @Value("${rag.weaviate.collection:Document_V1}")
    private String collectionName;

    /** 向量维度（默认 1024，与 bge-m3 一致） */
    @Value("${rag.vectorstore.dimension:1024}")
    private int vectorDimension;

    /** AI 模型工厂（统一创建嵌入模型） */
    private final AiModelFactory aiModelFactory;

    /** 向量库注册表（多租户按 collection 创建 Weaviate VectorStore） */
    private final VectorStoreRegistry vectorStoreRegistry;

    /**
     * 构造注入。
     *
     * @param aiModelFactory      AI 模型工厂
     * @param vectorStoreRegistry 向量库注册表
     */
    public VectorStoreConfig(AiModelFactory aiModelFactory, VectorStoreRegistry vectorStoreRegistry) {
        this.aiModelFactory = aiModelFactory;
        this.vectorStoreRegistry = vectorStoreRegistry;
    }

    /**
     * 创建向量库实例。
     * <p>根据 provider 配置分发到对应的 VectorStore 实现。</p>
     *
     * @return VectorStore 实例
     * @throws IllegalStateException 不支持的 provider 或缺少嵌入模型时抛出
     */
    @Bean
    public VectorStore vectorStore() {
        EmbeddingModel embeddingModel = resolveEmbeddingModel();
        if (embeddingModel == null) {
            throw new IllegalStateException("未配置 EMBEDDING 类别模型，无法创建向量库");
        }

        String p = provider == null ? "weaviate" : provider.toLowerCase();
        log.info("创建向量库: provider={}, collection={}", p, collectionName);

        return switch (p) {
            case "weaviate" -> createWeaviateStore(embeddingModel);
            case "milvus" -> {
                log.error("Milvus 向量库连接器尚未引入，暂不支持");
                throw new IllegalStateException(
                        "Milvus 向量库暂未实现，请使用 weaviate provider 或引入 Milvus 连接器");
            }
            default -> throw new IllegalStateException("不支持的向量库 provider: " + provider);
        };
    }

    /**
     * 从配置中解析第一个 EMBEDDING 类别模型，并交由工厂创建。
     *
     * @return 嵌入模型实例；未配置时返回 {@code null}
     */
    private EmbeddingModel resolveEmbeddingModel() {
        List<String> embeddingNames = aiModelFactory.getModelNamesByCategory(ModelCategory.EMBEDDING);
        if (embeddingNames.isEmpty()) {
            log.warn("配置中未找到 EMBEDDING 类别模型");
            return null;
        }
        String modelName = embeddingNames.get(0);
        log.info("向量库使用嵌入模型: {}", modelName);
        return aiModelFactory.getEmbeddingModel(modelName);
    }

    /**
     * 创建 Weaviate VectorStore（复用项目自研适配器）。
     * <p>通过 {@link VectorStoreRegistry} 按 collection 名 + 嵌入模型创建，
     * 保持与现有多租户物理隔离架构一致。</p>
     *
     * @param embeddingModel 嵌入模型
     * @return Weaviate VectorStore
     */
    private VectorStore createWeaviateStore(EmbeddingModel embeddingModel) {
        // 通过注册表按 collection 名创建（懒创建 + 缓存，多租户隔离）
        return vectorStoreRegistry.getWeaviateStore(collectionName, embeddingModel, vectorDimension);
    }
}
