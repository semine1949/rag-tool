package com.rag.boot.service;

import com.rag.auth.context.RequestContext;
import com.rag.auth.mapper.KbDocumentMapper;
import com.rag.auth.service.KbConfigService;
import com.rag.chunker.ChunkerFactory;
import com.rag.core.api.DocumentVersionService;
import com.rag.core.config.ChunkConfig;
import com.rag.core.config.EmbeddingConfig;
import com.rag.core.config.WeaviateCollectionConfig;
import com.rag.core.entity.KbDocument;
import com.rag.core.entity.DocumentVersion;
import com.rag.core.exception.RagException;
import com.rag.core.factory.EmbeddingModelFactory;
import com.rag.core.factory.VectorStoreRegistry;
import com.rag.parser.DocumentParseFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * RAG 核心流水线服务（Spring AI 重构版）
 * <p>
 * 解析 → 分片 → 元数据富化 → 入库（VectorStore.add 内部完成向量化）→ 检索。
 * 全程以 {@link Document} 为统一载体，向量化由 {@link VectorStore} 绑定的 {@link EmbeddingModel} 完成。
 */
@Service
public class RagToolService {

    private static final Logger log = LoggerFactory.getLogger(RagToolService.class);

    private final DocumentParseFactory parseFactory;
    private final ChunkerFactory chunkerFactory;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final VectorStoreRegistry vectorStoreRegistry;
    private final DocumentVersionService versionService;
    private final KbConfigService kbConfigService;
    private final KbDocumentMapper kbDocumentMapper;
    private final Executor ragTaskExecutor;

    public RagToolService(DocumentParseFactory parseFactory,
                          ChunkerFactory chunkerFactory,
                          EmbeddingModelFactory embeddingModelFactory,
                          VectorStoreRegistry vectorStoreRegistry,
                          DocumentVersionService versionService,
                          KbConfigService kbConfigService,
                          KbDocumentMapper kbDocumentMapper,
                          Executor ragTaskExecutor) {
        this.parseFactory = parseFactory;
        this.chunkerFactory = chunkerFactory;
        this.embeddingModelFactory = embeddingModelFactory;
        this.vectorStoreRegistry = vectorStoreRegistry;
        this.versionService = versionService;
        this.kbConfigService = kbConfigService;
        this.kbDocumentMapper = kbDocumentMapper;
        this.ragTaskExecutor = ragTaskExecutor;
    }

    // ==================== 单文件处理 ====================

    /**
     * 处理单个上传文件：解析 → 分片 → 向量化入库。
     */
    public FileProcessResult processFile(MultipartFile file, Long kbId, String changeType) {
        if (file == null || file.isEmpty()) {
            throw new RagException("RAG_FILE_EMPTY", "上传文件为空");
        }
        KbConfigService.KbLoadedConfig loaded = kbConfigService.loadConfigs(kbId);
        Long tenantId = loaded.kb().getTenantId();

        String originalFilename = file.getOriginalFilename();
        Path tempPath = null;
        try {
            tempPath = Files.createTempFile("rag-upload-", "-" + originalFilename);
            file.transferTo(tempPath.toFile());
            File tempFile = tempPath.toFile();

            String contentType = detectContentType(originalFilename);
            log.info("开始处理文件: {}, 类型: {}, 知识库: {}, 租户: {}",
                    originalFilename, contentType, kbId, tenantId);

            List<Document> docs = parseFactory.parse(tempFile);
            String fullText = docs.get(0).getText();
            if (fullText == null || fullText.isBlank()) {
                throw new RagException("RAG_EMPTY_TEXT", "解析后文本为空: " + originalFilename);
            }

            ChunkConfig chunkConfig = loaded.chunkConfig();
            List<Document> chunks = chunkerFactory.chunk(docs, chunkConfig);

            String documentId = (String) docs.get(0).getMetadata().get("fileId");
            String fileName = (String) docs.get(0).getMetadata().get("fileName");

            // 版本计算
            DocumentVersion currentVersion = versionService.getCurrentVersion(documentId);
            String effectiveChangeType = (changeType != null && !changeType.isBlank())
                    ? changeType.toUpperCase() : (currentVersion == null ? "INITIAL" : "MINOR");
            String newVersion = versionService.generateNextVersion(documentId, effectiveChangeType);
            Long userId = RequestContext.currentUserId();

            // 富化每个切片元数据
            for (Document chunk : chunks) {
                chunk.getMetadata().put("fileId", documentId);
                chunk.getMetadata().put("documentVersion", newVersion);
                chunk.getMetadata().put("ownerId", userId);
                chunk.getMetadata().put("tenantId", tenantId);
                chunk.getMetadata().put("kbId", kbId);
            }

            // 1. 版本记录
            versionService.createVersion(DocumentVersion.builder()
                    .documentId(documentId)
                    .tenantId(tenantId)
                    .kbId(kbId)
                    .version(newVersion)
                    .chunkCount(chunks.size())
                    .operatorId(userId)
                    .changeType(effectiveChangeType)
                    .collectionName(loaded.collectionConfig().getClassName())
                    .currentActive(true)
                    .build());

            // 2. 知识库文档记录
            kbDocumentMapper.deleteByFileId(documentId);
            kbDocumentMapper.insert(KbDocument.builder()
                    .kbId(kbId)
                    .tenantId(tenantId)
                    .fileName(fileName)
                    .fileType(contentType)
                    .chunkCount(chunks.size())
                    .version(newVersion)
                    .ownerId(userId)
                    .collectionName(loaded.collectionConfig().getClassName())
                    .build());

            // 3. 入库（VectorStore.add 内部完成向量化）
            EmbeddingConfig embeddingConfig = loaded.embeddingConfig();
            WeaviateCollectionConfig collectionConfig = loaded.collectionConfig();
            VectorStore store = vectorStoreRegistry.getWeaviateStore(
                    collectionConfig.getClassName(), embeddingModelFactory.getModel(embeddingConfig),
                    collectionConfig.getVectorDim());

            // 删除旧切片（按 fileId），集合不存在时忽略
            if (documentId != null) {
                try {
                    store.delete(new Filter.Expression(Filter.ExpressionType.EQ,
                            new Filter.Key("fileId"), new Filter.Value(documentId)));
                } catch (Exception e) {
                    log.warn("删除旧切片失败（可忽略，可能集合尚未创建）: {}", e.getMessage());
                }
            }
            store.add(chunks);

            log.info("文件入库完成: {}, 切片数: {}", fileName, chunks.size());
            return FileProcessResult.builder()
                    .fileId(documentId)
                    .fileName(fileName)
                    .chunkCount(chunks.size())
                    .insertedCount(chunks.size())
                    .success(true)
                    .build();

        } catch (IOException e) {
            throw new RagException("RAG_IO", "文件处理IO异常: " + e.getMessage(), e);
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ==================== 批量处理 ====================

    public CompletableFuture<List<FileProcessResult>> batchProcessFiles(List<MultipartFile> files, Long kbId) {
        return CompletableFuture.supplyAsync(() -> {
            List<FileProcessResult> results = new ArrayList<>();
            for (MultipartFile file : files) {
                try {
                    results.add(processFile(file, kbId, null));
                } catch (Exception e) {
                    log.error("批量处理文件失败: {}", e.getMessage());
                    results.add(FileProcessResult.builder().success(false)
                            .message(e.getMessage()).build());
                }
            }
            return results;
        }, ragTaskExecutor);
    }

    // ==================== 目录处理 ====================

    public List<FileProcessResult> processDirectory(String directoryPath, Long kbId) {
        File dir = new File(directoryPath);
        if (!dir.isDirectory()) {
            throw new RagException("RAG_DIR", "目录不存在: " + directoryPath);
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return List.of();
        }
        List<FileProcessResult> results = new ArrayList<>();
        for (File f : files) {
            if (f.isFile()) {
                try {
                    MultipartFile mp = new InMemoryMultipartFile(f.getName(), Files.readAllBytes(f.toPath()), "application/octet-stream");
                    results.add(processFile(mp, kbId, null));
                } catch (IOException e) {
                    results.add(FileProcessResult.builder().success(false).message(e.getMessage()).build());
                }
            }
        }
        return results;
    }

    // ==================== 检索 ====================

    public List<FileProcessResult> search(String query, Long kbId, int topK) {
        if (query == null || query.isBlank()) {
            throw new RagException("RAG_QUERY_EMPTY", "检索内容不能为空");
        }
        KbConfigService.KbLoadedConfig loaded = kbConfigService.loadConfigs(kbId);
        EmbeddingConfig embeddingConfig = loaded.embeddingConfig();
        WeaviateCollectionConfig collectionConfig = loaded.collectionConfig();

        EmbeddingModel model = embeddingModelFactory.getModel(embeddingConfig);
        VectorStore store = vectorStoreRegistry.getWeaviateStore(
                collectionConfig.getClassName(), model, collectionConfig.getVectorDim());

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK <= 0 ? 5 : topK)
                .build();

        List<Document> documents = store.similaritySearch(request);
        log.info("检索完成，命中 {} 条", documents.size());

        return documents.stream().map(d -> FileProcessResult.builder()
                .fileId((String) d.getMetadata().get("fileId"))
                .fileName((String) d.getMetadata().get("fileName"))
                .snippet(d.getText())
                .score(d.getScore())
                .success(true)
                .build()).collect(Collectors.toList());
    }

    // ==================== 文档列表 ====================

    public List<KbDocument> listDocuments(Long kbId) {
        return kbDocumentMapper.findByKbId(kbId);
    }

    // ==================== 工具方法 ====================

    private String detectContentType(String filename) {
        if (filename == null) {
            return "unknown";
        }
        int idx = filename.lastIndexOf('.');
        return idx > 0 ? filename.substring(idx + 1).toLowerCase() : "unknown";
    }

    /**
     * 内存 MultipartFile 实现（用于目录导入）。
     */
    private static class InMemoryMultipartFile implements MultipartFile {
        private final String name;
        private final byte[] content;
        private final String contentType;

        InMemoryMultipartFile(String name, byte[] content, String contentType) {
            this.name = name;
            this.content = content;
            this.contentType = contentType;
        }

        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content; }
        @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(content); }
        @Override public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), content);
        }
    }
}
