package com.rag.boot.service;

import com.rag.auth.context.RequestContext;
import com.rag.auth.mapper.DocChunkMapper;
import com.rag.auth.mapper.KbDocumentMapper;
import com.rag.auth.service.KbConfigService;
import com.rag.chunker.ChunkerFactory;
import com.rag.core.api.DocumentVersionService;
import com.rag.core.config.ChunkConfig;
import com.rag.core.config.EmbeddingConfig;
import com.rag.core.config.WeaviateCollectionConfig;
import com.rag.core.entity.DocChunk;
import com.rag.core.entity.DocumentVersion;
import com.rag.core.entity.KbDocument;
import com.rag.core.enums.ProcessStatusEnum;
import com.rag.core.exception.RagException;
import com.rag.core.factory.EmbeddingModelFactory;
import com.rag.core.factory.VectorStoreRegistry;
import com.rag.parser.DocumentParseFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TextSplitter;
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
 * RAG 核心流水线服务（v2 重构版）
 * <p>
 * 核心变更：
 *   1. 分片策略从知识库维度下移到文档维度（ChunkConfig 从 KbDocument 构建）
 *   2. 新增全流程处理状态机（PENDING→...→COMPLETED/FAILED）
 *   3. 分片原文持久化到 doc_chunk 表
 *   4. 版本表关联使用 doc_id(Long) 替代 documentId(String)
 * <p>
 * 流程：解析 → 插入文档记录(PENDING) → 文档维度构建ChunkConfig → 分块 →
 *        版本记录 → 向量化入库 → doc_chunk持久化 → COMPLETED
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
    private final DocChunkMapper docChunkMapper;
    private final Executor ragTaskExecutor;

    public RagToolService(DocumentParseFactory parseFactory,
                          ChunkerFactory chunkerFactory,
                          EmbeddingModelFactory embeddingModelFactory,
                          VectorStoreRegistry vectorStoreRegistry,
                          DocumentVersionService versionService,
                          KbConfigService kbConfigService,
                          KbDocumentMapper kbDocumentMapper,
                          DocChunkMapper docChunkMapper,
                          Executor ragTaskExecutor) {
        this.parseFactory = parseFactory;
        this.chunkerFactory = chunkerFactory;
        this.embeddingModelFactory = embeddingModelFactory;
        this.vectorStoreRegistry = vectorStoreRegistry;
        this.versionService = versionService;
        this.kbConfigService = kbConfigService;
        this.kbDocumentMapper = kbDocumentMapper;
        this.docChunkMapper = docChunkMapper;
        this.ragTaskExecutor = ragTaskExecutor;
    }

    // ==================== 单文件处理（v2 重构） ====================

    /**
     * 处理单个上传文件：解析 → 插入文档(PENDING) → 构建文档级ChunkConfig → 分块 →
     * 版本记录 → 向量化入库 → doc_chunk持久化 → COMPLETED。
     * <p>分块使用 {@link #chunkerFactory} 按文档级策略编排执行。</p>
     */
    public FileProcessResult processFile(MultipartFile file, Long kbId, String changeType,
                                          String chunkStrategy, Integer chunkSize, Integer chunkOverlap) {
        return processFileInternal(file, kbId, changeType, chunkStrategy, chunkSize, chunkOverlap, null);
    }

    /**
     * 处理单个上传文件，但分块使用外部传入的带参 splitter（text-model / hierarchical-model）。
     * <p>复用 {@link #processFile} 的完整落库流程（解析、状态机、版本、向量化、doc_chunk），
     * 仅分块步骤替换为 {@code customSplitter.apply(docs)}，用于将 splitter 参数作为接口参数传入的场景。</p>
     *
     * @param file          上传文件
     * @param kbId          知识库 ID
     * @param changeType    变更类型（可空）
     * @param chunkStrategy 文档级分片策略名（如 TEXT_MODEL / HIERARCHICAL_MODEL），用于记录到 kb_document
     * @param customSplitter 带参 splitter 实例
     * @return 落库结果
     */
    public FileProcessResult processFileWithSplitter(MultipartFile file, Long kbId, String changeType,
                                                     String chunkStrategy, TextSplitter customSplitter) {
        return processFileInternal(file, kbId, changeType, chunkStrategy, null, null, customSplitter);
    }

    /**
     * 单文件处理内部实现：参数 {@code customSplitter} 为空时走 {@link ChunkerFactory} 编排分块，
     * 非空时直接使用外部传入的 splitter 分块。
     */
    private FileProcessResult processFileInternal(MultipartFile file, Long kbId, String changeType,
                                                  String chunkStrategy, Integer chunkSize, Integer chunkOverlap,
                                                  TextSplitter customSplitter) {
        if (file == null || file.isEmpty()) {
            throw new RagException("RAG_FILE_EMPTY", "上传文件为空");
        }
        KbConfigService.KbLoadedConfig loaded = kbConfigService.loadConfigs(kbId);
        Long tenantId = loaded.kb().getTenantId();
        Long userId = RequestContext.currentUserId();

        String originalFilename = file.getOriginalFilename();
        Path tempPath = null;
        Long docId = null;
        try {
            tempPath = Files.createTempFile("rag-upload-", "-" + originalFilename);
            file.transferTo(tempPath.toFile());
            File tempFile = tempPath.toFile();

            String contentType = detectContentType(originalFilename);
            log.info("开始处理文件: {}, 类型: {}, 知识库: {}, 租户: {}",
                    originalFilename, contentType, kbId, tenantId);

            // ===== 1. 解析文件 =====
            List<Document> docs = parseFactory.parse(tempFile);
            String fullText = docs.get(0).getText();
            if (fullText == null || fullText.isBlank()) {
                throw new RagException("RAG_EMPTY_TEXT", "解析后文本为空: " + originalFilename);
            }

            // 提取元数据（fileId 为 UUID，用于 Weaviate 过滤删除）
            String fileId = (String) docs.get(0).getMetadata().get("fileId");
            String fileName = (String) docs.get(0).getMetadata().get("fileName");

            // ===== 2. 插入文档记录（PENDING 状态，获取 doc_id） =====
            KbDocument kbDoc = KbDocument.builder()
                    .kbId(kbId)
                    .tenantId(tenantId)
                    .fileName(fileName != null ? fileName : originalFilename)
                    .fileType(contentType)
                    .fileSize(file.getSize())
                    .chunkStrategy(chunkStrategy)   // 文档级策略，由上传接口传入；未设置则 buildChunkConfig 回退 FIXED_SIZE
                    .chunkSize(chunkSize)
                    .chunkOverlap(chunkOverlap)
                    .processStatus(ProcessStatusEnum.PARSED.name())
                    .chunkCount(0)
                    .ownerId(userId)
                    .collectionName(loaded.collectionConfig().getClassName())
                    .uploadTime(new Date())
                    .createTime(new Date())
                    .build();
            kbDocumentMapper.insert(kbDoc);
            docId = kbDoc.getDocId();
            log.info("文档记录已创建, docId={}, fileId={}, fileName={}", docId, fileId, kbDoc.getFileName());

            // 删除旧记录（同一文件重复上传时清理）
            if (fileId != null) {
                // 按 Weaviate 中旧 fileId 清理向量
                try {
                    EmbeddingConfig embConfig = loaded.embeddingConfig();
                    WeaviateCollectionConfig colConfig = loaded.collectionConfig();
                    VectorStore store = vectorStoreRegistry.getWeaviateStore(
                            colConfig.getClassName(), embeddingModelFactory.getModel(embConfig),
                            colConfig.getVectorDim());
                    store.delete(new Filter.Expression(Filter.ExpressionType.EQ,
                            new Filter.Key("fileId"), new Filter.Value(fileId)));
                } catch (Exception e) {
                    log.warn("删除旧向量数据失败（可忽略）: {}", e.getMessage());
                }
            }

            // ===== 3. 从文档维度构建 ChunkConfig =====
            ChunkConfig chunkConfig = kbConfigService.buildChunkConfig(kbDoc);
            log.info("文档级分片策略: {}, size={}, overlap={}",
                    kbDoc.getChunkStrategy() != null ? kbDoc.getChunkStrategy() : "FIXED_SIZE(默认)",
                    chunkConfig.getFixedChunkSize(), chunkConfig.getSlideOverlap());

            // ===== 4. 推进状态 → CHUNKING =====
            updateStatus(docId, ProcessStatusEnum.CHUNKING);

            // ===== 5. 分块 =====
            List<Document> chunks;
            if (customSplitter != null) {
                // 使用外部传入的带参 splitter（text-model / hierarchical-model 上传接口）
                chunks = customSplitter.apply(docs);
            } else {
                // 默认走 ChunkerFactory 按文档级策略编排分块
                chunks = chunkerFactory.chunk(docs, chunkConfig);
            }
            int chunkCount = chunks.size();
            log.info("分块完成, docId={}, 分片数={}", docId, chunkCount);

            // ===== 6. 推进状态 → CHUNKED =====
            updateStatus(docId, ProcessStatusEnum.CHUNKED);

            // ===== 7. 计算版本 =====
            DocumentVersion currentVersion = versionService.getCurrentVersion(docId);
            String effectiveChangeType = (changeType != null && !changeType.isBlank())
                    ? changeType.toUpperCase() : (currentVersion == null ? "INITIAL" : "MINOR");
            String newVersion = versionService.generateNextVersion(docId, effectiveChangeType);

            // 创建版本记录
            DocumentVersion version = DocumentVersion.builder()
                    .docId(docId)
                    .tenantId(tenantId)
                    .kbId(kbId)
                    .version(newVersion)
                    .chunkCount(chunkCount)
                    .operatorId(userId)
                    .changeType(effectiveChangeType)
                    .collectionName(loaded.collectionConfig().getClassName())
                    .currentActive(true)
                    .build();
            versionService.createVersion(version);
            log.info("版本创建: docId={}, ver={}, chunks={}", docId, newVersion, chunkCount);

            // 获取版本记录ID（用于 doc_chunk 关联）
            DocumentVersion savedVersion = versionService.getCurrentVersion(docId);
            Long versionId = savedVersion != null ? savedVersion.getId() : null;

            // ===== 8. 富化每个分片元数据 =====
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                chunk.getMetadata().put("fileId", fileId);
                chunk.getMetadata().put("docId", docId);
                chunk.getMetadata().put("documentVersion", newVersion);
                chunk.getMetadata().put("ownerId", userId);
                chunk.getMetadata().put("tenantId", tenantId);
                chunk.getMetadata().put("kbId", kbId);
                chunk.getMetadata().put("chunkIndex", i);
            }

            // ===== 9. 推进状态 → VECTORIZING =====
            updateStatus(docId, ProcessStatusEnum.VECTORIZING);

            // ===== 10. 向量化入库（VectorStore.add 内部完成向量化） =====
            EmbeddingConfig embeddingConfig = loaded.embeddingConfig();
            WeaviateCollectionConfig collectionConfig = loaded.collectionConfig();
            VectorStore store = vectorStoreRegistry.getWeaviateStore(
                    collectionConfig.getClassName(), embeddingModelFactory.getModel(embeddingConfig),
                    collectionConfig.getVectorDim());

            store.add(chunks);
            log.info("向量化入库完成, docId={}, chunks={}", docId, chunkCount);

            // ===== 11. 分片原文持久化到 doc_chunk 表 =====
            List<DocChunk> docChunks = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                DocChunk dc = DocChunk.builder()
                        .docId(docId)
                        .tenantId(tenantId)
                        .kbId(kbId)
                        .versionId(versionId)
                        .chunkIndex(i)
                        .chunkType("flat")  // 默认扁平分片，PARENT_CHILD 策略时由 Chunker 覆写元数据
                        .parentChunkId(null)
                        .content(chunk.getText())
                        .vectorId((String) chunk.getMetadata().get("id")) // Weaviate 返回的 object ID
                        .createTime(new Date())
                        .build();
                docChunks.add(dc);
            }
            if (!docChunks.isEmpty()) {
                docChunkMapper.batchInsert(docChunks);
                log.info("分片原文已持久化到 doc_chunk 表, docId={}, count={}", docId, docChunks.size());
            }

            // ===== 12. 推进状态 → COMPLETED，更新分片数与版本 =====
            kbDocumentMapper.updateChunkCount(docId, chunkCount);
            updateStatus(docId, ProcessStatusEnum.COMPLETED);

            // 同步更新 kb_document 的 version 字段
            kbDoc.setVersion(newVersion);
            kbDoc.setChunkCount(chunkCount);
            kbDoc.setProcessStatus(ProcessStatusEnum.COMPLETED.name());

            log.info("文件处理完成: docId={}, fileName={}, 切片数={}", docId, fileName, chunkCount);
            return FileProcessResult.builder()
                    .fileId(String.valueOf(docId))
                    .fileName(fileName)
                    .chunkCount(chunkCount)
                    .insertedCount(chunkCount)
                    .success(true)
                    .build();

        } catch (IOException e) {
            markFailed(docId, e);
            throw new RagException("RAG_IO", "文件处理IO异常: " + e.getMessage(), e);
        } catch (RagException e) {
            markFailed(docId, e);
            throw e;
        } catch (Exception e) {
            markFailed(docId, e);
            throw new RagException("RAG_SYS", "文件处理异常: " + e.getMessage(), e);
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

    public CompletableFuture<List<FileProcessResult>> batchProcessFiles(List<MultipartFile> files, Long kbId,
                                                                          String chunkStrategy, Integer chunkSize, Integer chunkOverlap) {
        return CompletableFuture.supplyAsync(() -> {
            List<FileProcessResult> results = new ArrayList<>();
            for (MultipartFile file : files) {
                try {
                    results.add(processFile(file, kbId, null, chunkStrategy, chunkSize, chunkOverlap));
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
                    results.add(processFile(mp, kbId, null, null, null, null));
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
                .fileId(String.valueOf(d.getMetadata().get("docId")))
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

    // ==================== 状态机辅助方法 ====================

    private void updateStatus(Long docId, ProcessStatusEnum status) {
        if (docId == null) {
            return;
        }
        try {
            kbDocumentMapper.updateProcessStatus(docId, status.name());
            log.debug("文档状态推进: docId={}, status={}", docId, status);
        } catch (Exception e) {
            log.warn("更新文档状态失败: docId={}, status={}, error={}", docId, status, e.getMessage());
        }
    }

    private void markFailed(Long docId, Exception e) {
        if (docId == null) {
            return;
        }
        try {
            kbDocumentMapper.updateProcessStatus(docId, ProcessStatusEnum.FAILED.name());
            log.error("文档处理失败, docId={}, error={}", docId, e.getMessage());
        } catch (Exception ex) {
            log.warn("标记失败状态异常: docId={}, error={}", docId, ex.getMessage());
        }
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
