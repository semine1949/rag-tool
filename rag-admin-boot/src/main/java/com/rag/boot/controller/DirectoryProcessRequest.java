package com.rag.boot.controller;

import com.rag.core.config.ChunkConfig;
import com.rag.core.config.EmbeddingConfig;
import com.rag.core.config.WeaviateCollectionConfig;
import lombok.Data;

/**
 * 文件夹处理请求体
 */
@Data
public class DirectoryProcessRequest {
    private String dirPath;
    private ChunkConfig chunkConfig;
    private EmbeddingConfig embeddingConfig;
    private WeaviateCollectionConfig collectionConfig;
}
