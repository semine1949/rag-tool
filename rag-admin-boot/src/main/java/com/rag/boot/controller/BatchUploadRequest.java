package com.rag.boot.controller;

import com.rag.core.config.ChunkConfig;
import com.rag.core.config.EmbeddingConfig;
import com.rag.core.config.WeaviateCollectionConfig;
import lombok.Data;

/**
 * 批量上传请求体
 */
@Data
public class BatchUploadRequest {
    private ChunkConfig chunkConfig;
    private EmbeddingConfig embeddingConfig;
    private WeaviateCollectionConfig collectionConfig;
}
