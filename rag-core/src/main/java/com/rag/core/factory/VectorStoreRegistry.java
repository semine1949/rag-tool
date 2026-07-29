package com.rag.core.factory;

import com.rag.core.vectorstore.WeaviateVectorStoreAdapter;
import io.weaviate.client.Config;
import io.weaviate.client.WeaviateClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多租户 VectorStore 注册表。
 * <p>
 * 按 {@code collectionName(=T{tenantId}_Kb{kbId})} 物理隔离，懒创建并缓存 {@link VectorStore} 实例。
 * 每个集合绑定对应知识库的 {@link EmbeddingModel}，保证向量维度一致。
 * <p>
 * 框架自带的 {@code spring-ai-vector-store-weaviate} 连接器在本项目 Maven 镜像中不可用，
 * 故此处基于 {@link WeaviateVectorStoreAdapter}（{@code io.weaviate:client}）实现。
 */
public class VectorStoreRegistry {

    private final WeaviateClient weaviateClient;
    private final Map<String, VectorStore> weaviateStores = new ConcurrentHashMap<>();

    public VectorStoreRegistry(String weaviateUrl, String weaviateToken) {
        this.weaviateClient = buildClient(weaviateUrl, weaviateToken);
    }

    public VectorStore getWeaviateStore(String collectionName, EmbeddingModel embeddingModel, int vectorDim) {
        return weaviateStores.computeIfAbsent(collectionName,
                name -> new WeaviateVectorStoreAdapter(weaviateClient, embeddingModel, name, vectorDim));
    }

    public void dropCollection(String collectionName) {
        weaviateClient.schema().classDeleter().withClassName(collectionName).run();
        weaviateStores.remove(collectionName);
    }

    public void clearCollection(String collectionName) {
        dropCollection(collectionName);
    }

    private static WeaviateClient buildClient(String url, String token) {
        String scheme = url.startsWith("https") ? "https" : "http";
        String hostPort = url.replaceFirst("^https?://", "").replaceAll("/+$", "");
        Map<String, String> headers = new HashMap<>();
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        return new WeaviateClient(new Config(scheme, hostPort, headers));
    }
}
