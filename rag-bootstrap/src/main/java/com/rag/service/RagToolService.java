package com.rag.service;

import com.rag.auth.context.RequestContext;
import com.rag.auth.mapper.DocChunkMapper;
import com.rag.auth.mapper.KbDocumentMapper;
import com.rag.auth.service.KbConfigService;
import com.rag.common.chunker.ChunkStrategyFactory;
import com.rag.common.chunker.ParentChildTextSplitter;
import com.rag.common.chunker.SizeTextSplitter;
import com.rag.common.chunker.SplitterConfig;
import com.rag.common.api.DocumentVersionService;
import com.rag.common.entity.config.EmbeddingConfig;
import com.rag.common.entity.config.RerankConfig;
import com.rag.common.entity.config.SearchConfig;
import com.rag.common.entity.config.WeaviateCollectionConfig;
import com.rag.common.entity.DocChunk;
import com.rag.common.entity.DocumentVersion;
import com.rag.common.entity.KbDocument;
import com.rag.common.enums.ProcessStatusEnum;
import com.rag.common.enums.SearchMode;
import com.rag.common.exception.RagException;
import com.rag.common.rerank.RerankStrategy;
import com.rag.config.factory.EmbeddingModelFactory;
import com.rag.config.factory.VectorStoreRegistry;
import com.rag.config.rerank.RerankStrategyFactory;
import com.rag.config.vectorstore.WeaviateVectorStoreAdapter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.common.parser.DocumentParseFactory;
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
 * RAG 核心流水线服务
 * <p>
 * 核心流程：
 *   1. 解析文件 → 插入文档记录(PENDING)
 *   2. 通过 {@link ChunkStrategyFactory} 依据 chunkStrategy + SplitterConfig 构造 splitter 分块
 *   3. 推进全流程处理状态机（PENDING→...→COMPLETED/FAILED）
 *   4. 分片原文持久化到 doc_chunk 表，向量化入库
 *   5. 版本表关联使用 doc_id(Long)
 * <p>
 * 流程：解析 → 插入文档记录(PENDING) → 分块 → 版本记录 → 向量化入库 → doc_chunk持久化 → COMPLETED
 */
@Service
public class RagToolService {

    private static final Logger log = LoggerFactory.getLogger(RagToolService.class);

    private final DocumentParseFactory parseFactory;
    /** 封装式分片策略工厂：依据 chunkStrategy + SplitterConfig 构造 splitter */
    private final ChunkStrategyFactory chunkStrategyFactory;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final VectorStoreRegistry vectorStoreRegistry;
    private final DocumentVersionService versionService;
    private final KbConfigService kbConfigService;
    private final KbDocumentMapper kbDocumentMapper;
    private final DocChunkMapper docChunkMapper;
    private final Executor ragTaskExecutor;
    /** JSON 序列化器（生成 params_snapshot 参数快照） */
    private final ObjectMapper objectMapper;
    /** 重排策略工厂（可插拔，null 时禁用重排） */
    private final RerankStrategyFactory rerankStrategyFactory;

    public RagToolService(DocumentParseFactory parseFactory,
                          ChunkStrategyFactory chunkStrategyFactory,
                          EmbeddingModelFactory embeddingModelFactory,
                          VectorStoreRegistry vectorStoreRegistry,
                          DocumentVersionService versionService,
                          KbConfigService kbConfigService,
                          KbDocumentMapper kbDocumentMapper,
                          DocChunkMapper docChunkMapper,
                          Executor ragTaskExecutor,
                          ObjectMapper objectMapper,
                          RerankStrategyFactory rerankStrategyFactory) {
        this.parseFactory = parseFactory;
        this.chunkStrategyFactory = chunkStrategyFactory;
        this.embeddingModelFactory = embeddingModelFactory;
        this.vectorStoreRegistry = vectorStoreRegistry;
        this.versionService = versionService;
        this.kbConfigService = kbConfigService;
        this.kbDocumentMapper = kbDocumentMapper;
        this.docChunkMapper = docChunkMapper;
        this.ragTaskExecutor = ragTaskExecutor;
        this.objectMapper = objectMapper;
        this.rerankStrategyFactory = rerankStrategyFactory;
    }

    // ==================== 单文件处理 ====================

    /**
     * 处理单个上传文件：解析 → 插入文档(PENDING) → 分块 → 版本记录 → 向量化入库 →
     * doc_chunk持久化 → COMPLETED。
     * <p>分块通过 {@link ChunkStrategyFactory#getSplitter} 依据 chunkStrategy 与 splitterConfig
     * 构造对应 splitter（text-model / hierarchical-model）执行。config 为 null 或字段为空时采用默认参数。</p>
     *
     * @param file           上传文件
     * @param kbId           知识库 ID
     * @param changeType     变更类型（可空）
     * @param chunkStrategy  分片策略名（如 TEXT_MODEL / text-model，可空，未知回退 text-model）
     * @param splitterConfig 分片参数载体（可空，null 时采用默认参数）
     * @return 落库结果
     */
    public FileProcessResult processFile(MultipartFile file, Long kbId, String changeType,
                                         String chunkStrategy, SplitterConfig splitterConfig) {
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
            // v3 变更：分片策略下沉到分片维度，kb_document 不再持久化分片策略字段
            KbDocument kbDoc = KbDocument.builder()
                    .kbId(kbId)
                    .tenantId(tenantId)
                    .fileName(fileName != null ? fileName : originalFilename)
                    .fileType(contentType)
                    .fileSize(file.getSize())
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

            // ===== 3. 依据分片策略 + 参数构造 splitter =====
            String effectiveStrategy = (chunkStrategy != null && !chunkStrategy.isBlank())
                    ? chunkStrategy : "TEXT_MODEL";
            TextSplitter splitter = chunkStrategyFactory.getSplitter(chunkStrategy, splitterConfig);
            log.info("分片策略: {}, 使用splitter: {}", effectiveStrategy,
                    splitter instanceof SizeTextSplitter ? "SizeTextSplitter(text-model)"
                            : "ParentChildTextSplitter(hierarchical-model)");

            // ===== 4. 推进状态 → CHUNKING =====
            updateStatus(docId, ProcessStatusEnum.CHUNKING);

            // ===== 5. 分块 =====
            List<Document> chunks = splitter.apply(docs);
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
            // 写入分片策略（chunk_mode，取自 splitter 的 CHUNK_MODE）与参数快照（params_snapshot，JSON）
            String chunkMode = splitter instanceof ParentChildTextSplitter
                    ? ParentChildTextSplitter.CHUNK_MODE : SizeTextSplitter.CHUNK_MODE;
            String paramsSnapshot = buildParamsSnapshot(splitter);
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
                        .chunkMode(chunkMode)
                        .paramsSnapshot(paramsSnapshot)
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
                                                                          String chunkStrategy, SplitterConfig splitterConfig) {
        return CompletableFuture.supplyAsync(() -> {
            List<FileProcessResult> results = new ArrayList<>();
            for (MultipartFile file : files) {
                try {
                    results.add(processFile(file, kbId, null, chunkStrategy, splitterConfig));
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
                    results.add(processFile(mp, kbId, null, null, null));
                } catch (IOException e) {
                    results.add(FileProcessResult.builder().success(false).message(e.getMessage()).build());
                }
            }
        }
        return results;
    }

    // ==================== 检索 ====================

    /**
     * 检索（兼容原有接口，默认纯向量模式）。
     * <p>保持 100% 向后兼容，行为与原有逻辑完全一致。</p>
     *
     * @param query 检索内容
     * @param kbId  知识库 ID
     * @param topK  返回条数
     * @return 检索结果
     */
    public List<FileProcessResult> search(String query, Long kbId, int topK) {
        return search(query, kbId, topK, null);
    }

    /**
     * 多模式检索（扩展接口），支持向量/BM25/混合三种模式。
     * <p>
     * searchConfig 为 null 时默认走纯向量模式，与原有逻辑完全一致。
     * 支持 BM25 参数、RRF 融合参数等可选配置。
     * </p>
     *
     * @param query        检索内容
     * @param kbId         知识库 ID
     * @param topK         返回条数
     * @param searchConfig 检索配置（可选，null 时默认 VECTOR_ONLY）
     * @return 检索结果
     */
    public List<FileProcessResult> search(String query, Long kbId, int topK, SearchConfig searchConfig) {
        if (query == null || query.isBlank()) {
            throw new RagException("RAG_QUERY_EMPTY", "检索内容不能为空");
        }
        // topK 兜底
        int effectiveTopK = topK <= 0 ? 5 : topK;

        KbConfigService.KbLoadedConfig loaded = kbConfigService.loadConfigs(kbId);
        EmbeddingConfig embeddingConfig = loaded.embeddingConfig();
        WeaviateCollectionConfig collectionConfig = loaded.collectionConfig();

        EmbeddingModel model = embeddingModelFactory.getModel(embeddingConfig);
        WeaviateVectorStoreAdapter store = (WeaviateVectorStoreAdapter) vectorStoreRegistry.getWeaviateStore(
                collectionConfig.getClassName(), model, collectionConfig.getVectorDim());

        // 默认配置：纯向量模式
        if (searchConfig == null) {
            searchConfig = SearchConfig.vectorOnly();
        }

        // ===== 重排候选池放大：启用重排时召回更多候选供精排挑选 =====
        int recallTopK = effectiveTopK;
        boolean rerankEnabled = Boolean.TRUE.equals(searchConfig.getRerankEnabled())
                && searchConfig.getSearchMode() == SearchMode.HYBRID;
        if (rerankEnabled) {
            int multiplier = searchConfig.getRerankCandidateMultiplier() != null
                    ? searchConfig.getRerankCandidateMultiplier() : 3;
            recallTopK = effectiveTopK * multiplier;
            log.debug("重排候选池放大: topK={} → recallTopK={} (x{})", effectiveTopK, recallTopK, multiplier);
        }

        // 通过适配器的统一多模式检索入口执行
        List<Document> documents = store.searchByMode(query, recallTopK, null, searchConfig);

        log.info("检索完成, mode={}, 命中 {} 条", searchConfig.getSearchMode(), documents.size());

        // ===== 重排节点：在融合结果后、最终 topK 截断前插入 =====
        if (rerankEnabled && !documents.isEmpty() && rerankStrategyFactory != null) {
            try {
                RerankConfig rerankConfig = buildRerankConfig(embeddingConfig);
                RerankStrategy strategy = rerankStrategyFactory.getStrategy(rerankConfig);
                if (strategy != null) {
                    List<Document> rerankedDocs = strategy.rerank(query, documents);
                    if (rerankedDocs != null && !rerankedDocs.isEmpty()) {
                        documents = rerankedDocs;
                        log.info("重排完成, 精排结果 {} 条", documents.size());
                    }
                }
            } catch (Exception e) {
                log.warn("重排异常降级，使用原始融合排序结果: {}", e.getMessage());
                // 降级：保持原始 documents 不变
            }
        }

        // 最终 topK 截断
        if (documents.size() > effectiveTopK) {
            documents = documents.subList(0, effectiveTopK);
        }

        return documents.stream().map(d -> FileProcessResult.builder()
                .fileId(String.valueOf(d.getMetadata().get("docId")))
                .fileName((String) d.getMetadata().get("fileName"))
                .snippet(d.getText())
                .score(d.getScore())
                .documentVersion((String) d.getMetadata().get("documentVersion"))
                .success(true)
                .build()).collect(Collectors.toList());
    }

    /**
     * 从 EmbeddingConfig 构建重排配置。
     * <p>重排复用 Embedding 模型的 apiKey 和 baseUrl（同一硅基流动端点），
     * 重排专用参数从 searchConfig 获取。</p>
     *
     * @param embeddingConfig Embedding 模型配置（提供 apiKey / baseUrl）
     * @return 重排配置
     */
    private RerankConfig buildRerankConfig(EmbeddingConfig embeddingConfig) {
        return RerankConfig.builder()
                .enabled(true)
                .modelType("qwen3-reranker")
                .baseUrl(embeddingConfig.getBaseUrl() != null
                        ? embeddingConfig.getBaseUrl() : "https://api.siliconflow.cn/v1")
                .apiKey(embeddingConfig.getModelSource())
                .build();
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

    /**
     * 构建分片参数快照（doc_chunk.params_snapshot 的 JSON 内容）。
     * <p>依据 splitter 实际类型提取生效的分块参数：
     * <ul>
     *   <li>{@link SizeTextSplitter}（text-model）：delimiter / maxTokens / chunkOverlap</li>
     *   <li>{@link ParentChildTextSplitter}（hierarchical-model）：父块与子块五参数</li>
     * </ul>
     * 序列化失败时返回 {@code null}（JSON 列允许 NULL）。</p>
     *
     * @param splitter 实际生效的 splitter 实例（text-model / hierarchical-model）
     * @return 参数快照 JSON 字符串，序列化失败时为 null
     */
    private String buildParamsSnapshot(TextSplitter splitter) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (splitter instanceof SizeTextSplitter s) {
            // text-model：通用文本分块参数
            params.put("delimiter", s.getDelimiter());
            params.put("maxTokens", s.getMaxTokens());
            params.put("chunkOverlap", s.getChunkOverlap());
        } else if (splitter instanceof ParentChildTextSplitter p) {
            // hierarchical-model：层级父子分块参数
            params.put("parentSeparator", p.getParentSeparator());
            params.put("parentMaxTokens", p.getParentMaxTokens());
            params.put("childSeparator", p.getChildSeparator());
            params.put("childMaxTokens", p.getChildMaxTokens());
            params.put("parentMode", p.getParentMode());
        }
        if (params.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            log.warn("分片参数快照序列化失败, error={}", e.getMessage());
            return null;
        }
    }

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
