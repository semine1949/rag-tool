package com.rag.boot.service;

import com.rag.chunker.ChunkerFactory;
import com.rag.core.api.VectorStore;
import com.rag.core.config.ChunkConfig;
import com.rag.core.config.EmbeddingConfig;
import com.rag.core.config.WeaviateCollectionConfig;
import com.rag.core.entity.*;
import com.rag.core.enums.EmbeddingModelType;
import com.rag.core.exception.RagException;
import com.rag.embedding.EmbeddingFactory;
import com.rag.parser.DocumentParseFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * RAG工具核心服务 - 串联整个处理链路
 */
@Service
public class RagToolService {

    private static final Logger log = LoggerFactory.getLogger(RagToolService.class);

    private final DocumentParseFactory parseFactory;
    private final ChunkerFactory chunkerFactory;
    private final EmbeddingFactory embeddingFactory;
    private final VectorStore vectorStore;
    private final Executor taskExecutor;

    // TODO: 配置Weaviate集合名称，默认为 "Document"
    private static final String DEFAULT_COLLECTION = "Document";

    // TODO: 配置默认Embedding模型类型
    private static final EmbeddingModelType DEFAULT_MODEL_TYPE = EmbeddingModelType.OLLAMA;

    // TODO: 配置模型名称，对应实际使用的模型
    private static final String DEFAULT_MODEL_NAME = "m3e";

    // TODO: 配置Ollama/OpenAI等Embedding API 的 Base URL
    private static final String DEFAULT_EMBEDDING_BASE_URL = "http://localhost:11434";

    // TODO: 配置API Key（OpenAI/Tongyi等在线API需要）
    private static final String DEFAULT_API_KEY = "sk-xxx";

    public RagToolService(DocumentParseFactory parseFactory,
                          ChunkerFactory chunkerFactory,
                          EmbeddingFactory embeddingFactory,
                          VectorStore vectorStore,
                          @Qualifier("ragTaskExecutor") Executor taskExecutor) {
        this.parseFactory = parseFactory;
        this.chunkerFactory = chunkerFactory;
        this.embeddingFactory = embeddingFactory;
        this.vectorStore = vectorStore;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 单文件完整处理链路：解析 → 分片 → 向量化 → 入库
     */
    public FileProcessResult processFile(MultipartFile file, ChunkConfig chunkConfig,
                                         EmbeddingConfig embeddingConfig,
                                         WeaviateCollectionConfig collectionConfig) {
        File tempFile = null;
        try {
            // 1. 创建临时文件
            tempFile = File.createTempFile("rag_", "_" + file.getOriginalFilename());
            file.transferTo(tempFile);

            // 2. 文档解析
            log.info("开始解析文件: {}", file.getOriginalFilename());
            DocumentParseResult parseResult = parseFactory.parse(tempFile);
            if (parseResult.getFullText() == null || parseResult.getFullText().isBlank()) {
                throw new RagException("RAG_SVC_001", "未提取到有效文本: " + file.getOriginalFilename());
            }

            // 3. 文本分片
            log.info("开始分片: {}, 策略={}", file.getOriginalFilename(), chunkConfig.getEnableStrategies());
            List<Chunk> chunks = chunkerFactory.chunk(parseResult, chunkConfig);

            // 4. Embedding向量化
            log.info("开始向量化: {}, chunk数={}", file.getOriginalFilename(), chunks.size());
            EmbeddingConfig embedCfg = embeddingConfig != null ? embeddingConfig : buildDefaultEmbeddingConfig();
            embeddingFactory.switchModel(embedCfg);
            List<String> chunkTexts = chunks.stream().map(Chunk::getText).toList();
            List<float[]> vectors = embeddingFactory.batchEmbed(chunkTexts, embedCfg);

            // 5. 构建VectorRecord
            if (vectors.size() != chunks.size()) {
                throw new RagException("RAG_SVC_002", "向量数量与分片数量不一致");
            }

            List<VectorRecord> records = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Chunk chunk = chunks.get(i);
                float[] vec = vectors.get(i);
                List<Float> vecList = new ArrayList<>();
                for (float v : vec) vecList.add(v);

                records.add(VectorRecord.builder()
                        .recordId(chunk.getChunkId())
                        .text(chunk.getText())
                        .vector(vecList)
                        .fileName(chunk.getFileName())
                        .fileType(chunk.getFileType())
                        .chunkType(chunk.getChunkType() != null ? chunk.getChunkType().name() : null)
                        .parentChunkId(chunk.getParentId())
                        .pageNo(chunk.getPageNo())
                        .tableFlag(chunk.getTableMeta() != null && !chunk.getTableMeta().isEmpty())
                        .codeFlag(chunk.getCodeMeta() != null && !chunk.getCodeMeta().isEmpty())
                        .sourcePath(parseResult.getMeta().getSourcePath())
                        .fileId(parseResult.getMeta().getFileId())
                        .textHash(chunk.getTextHash())
                        .extraMeta(chunk.getExtraMeta())
                        .build());
            }

            // 6. 入库Weaviate
            WeaviateCollectionConfig collCfg = collectionConfig != null ? collectionConfig :
                    buildDefaultCollectionConfig(embeddingFactory.getVectorDim(embedCfg));

            // 初始化集合
            vectorStore.initCollection(collCfg);

            // 增量更新：先删除该文件的旧分片
            if (parseResult.getMeta().getFileId() != null) {
                vectorStore.deleteByFileId(parseResult.getMeta().getFileId(), collCfg.getClassName());
            }

            // 批量写入
            int inserted = vectorStore.batchInsert(records, collCfg);

            return FileProcessResult.builder()
                    .fileId(parseResult.getMeta().getFileId())
                    .fileName(file.getOriginalFilename())
                    .chunkCount(chunks.size())
                    .insertedCount(inserted)
                    .success(true)
                    .build();

        } catch (Exception e) {
            log.error("文件处理失败: {}", file.getOriginalFilename(), e);
            return FileProcessResult.builder()
                    .fileName(file.getOriginalFilename())
                    .success(false)
                    .error(e.getMessage())
                    .build();
        } finally {
            if (tempFile != null) tempFile.delete();
        }
    }

    /**
     * 批量异步处理多个文件
     */
    @Async("ragTaskExecutor")
    public CompletableFuture<List<FileProcessResult>> batchProcessFiles(
            List<MultipartFile> files, ChunkConfig chunkConfig,
            EmbeddingConfig embeddingConfig, WeaviateCollectionConfig collectionConfig) {
        List<FileProcessResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(processFile(file, chunkConfig, embeddingConfig, collectionConfig));
        }
        return CompletableFuture.completedFuture(results);
    }

    /**
     * 处理本地文件夹
     */
    public List<FileProcessResult> processDirectory(String dirPath, ChunkConfig chunkConfig,
                                                    EmbeddingConfig embeddingConfig,
                                                    WeaviateCollectionConfig collectionConfig) {
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new RagException("RAG_SVC_003", "目录不存在: " + dirPath);
        }

        List<FileProcessResult> results = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    try {
                        byte[] content = Files.readAllBytes(file.toPath());
                        MultipartFile mf = new InMemoryMultipartFile(
                                file.getName(), file.getName(),
                                detectContentType(file.getName()), content);
                        results.add(processFile(mf, chunkConfig, embeddingConfig, collectionConfig));
                    } catch (IOException e) {
                        log.error("读取文件失败: {}", file.getName(), e);
                    }
                }
            }
        }
        return results;
    }

    /**
     * 集合管理：初始化
     */
    public void initCollection(WeaviateCollectionConfig config) {
        vectorStore.initCollection(config);
    }

    /**
     * 集合管理：清空
     */
    public void clearCollection(String className) {
        vectorStore.clearCollection(className);
    }

    /**
     * 集合管理：删除
     */
    public void dropCollection(String className) {
        vectorStore.dropCollection(className);
    }

    // ============ 默认配置 ============

    private EmbeddingConfig buildDefaultEmbeddingConfig() {
        return EmbeddingConfig.builder()
                .modelType(DEFAULT_MODEL_TYPE)
                .modelName(DEFAULT_MODEL_NAME)
                .baseUrl(DEFAULT_EMBEDDING_BASE_URL)
                .modelSource(DEFAULT_API_KEY)
                .batchSize(16)
                .maxTextLen(512)
                .build();
    }

    private WeaviateCollectionConfig buildDefaultCollectionConfig(int vectorDim) {
        return WeaviateCollectionConfig.builder()
                .className(DEFAULT_COLLECTION)
                .vectorDim(vectorDim)
                .distanceMetric("cosine")
                .dedupStrategy("skip")
                .batchSize(500)
                .build();
    }

    private String detectContentType(String fileName) {
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "txt", "md" -> "text/plain";
            case "html", "htm" -> "text/html";
            default -> "application/octet-stream";
        };
    }

    /**
     * 内存MultipartFile实现
     */
    private static class InMemoryMultipartFile implements MultipartFile {
        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] content;

        InMemoryMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.content = content;
        }

        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content; }
        @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(content); }
        @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), content); }
    }
}
