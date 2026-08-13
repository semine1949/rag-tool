package com.rag.service;

import com.rag.auth.context.RequestContext;
import com.rag.auth.mapper.DocChunkMapper;
import com.rag.controller.MultiSearchRequest;
import com.rag.auth.mapper.KbDocumentMapper;
import com.rag.auth.service.KbConfigService;
import com.rag.common.chunker.ChunkStrategyFactory;
import com.rag.common.chunker.ParentChildTextSplitter;
import com.rag.common.chunker.SizeTextSplitter;
import com.rag.common.chunker.SplitterConfig;
import com.rag.common.api.DocumentVersionService;
import com.rag.common.entity.config.EmbeddingConfig;
import com.rag.common.entity.config.SearchConfig;
import com.rag.common.entity.config.WeaviateCollectionConfig;
import com.rag.common.entity.DocChunk;
import com.rag.common.entity.DocumentVersion;
import com.rag.common.entity.KbDocument;
import com.rag.common.enums.ProcessStatusEnum;
import com.rag.common.enums.SearchMode;
import com.rag.common.exception.RagException;
import com.rag.config.factory.AiModelFactory;
import com.rag.config.factory.VectorStoreRegistry;
import com.rag.config.model.RerankModel;
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
    /** AI 模型工厂：统一创建/缓存 Embedding 与 Rerank 模型 */
    private final AiModelFactory aiModelFactory;
    private final VectorStoreRegistry vectorStoreRegistry;
    private final DocumentVersionService versionService;
    private final KbConfigService kbConfigService;
    private final KbDocumentMapper kbDocumentMapper;
    private final DocChunkMapper docChunkMapper;
    private final Executor ragTaskExecutor;
    /** JSON 序列化器（生成 params_snapshot 参数快照） */
    private final ObjectMapper objectMapper;

    public RagToolService(DocumentParseFactory parseFactory,
                          ChunkStrategyFactory chunkStrategyFactory,
                          AiModelFactory aiModelFactory,
                          VectorStoreRegistry vectorStoreRegistry,
                          DocumentVersionService versionService,
                          KbConfigService kbConfigService,
                          KbDocumentMapper kbDocumentMapper,
                          DocChunkMapper docChunkMapper,
                          Executor ragTaskExecutor,
                          ObjectMapper objectMapper) {
        this.parseFactory = parseFactory;
        this.chunkStrategyFactory = chunkStrategyFactory;
        this.aiModelFactory = aiModelFactory;
        this.vectorStoreRegistry = vectorStoreRegistry;
        this.versionService = versionService;
        this.kbConfigService = kbConfigService;
        this.kbDocumentMapper = kbDocumentMapper;
        this.docChunkMapper = docChunkMapper;
        this.ragTaskExecutor = ragTaskExecutor;
        this.objectMapper = objectMapper;
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
                            colConfig.getClassName(), resolveEmbeddingModel(embConfig),
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
                    collectionConfig.getClassName(), resolveEmbeddingModel(embeddingConfig),
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

        EmbeddingModel model = resolveEmbeddingModel(embeddingConfig);
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
        if (rerankEnabled && !documents.isEmpty()) {
            try {
                // 通过 AiModelFactory 获取重排模型（统一由工厂创建 + 缓存）
                RerankModel rerankModel = resolveRerankModel();
                if (rerankModel != null) {
                    List<Document> rerankedDocs = rerank(query, documents, rerankModel);
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

    // ==================== 多知识库检索 + 白名单过滤 ====================

    /**
     * 多知识库检索 + 白名单过滤。
     * <p>
     * 实现「物理隔离 + 逻辑联合 + 应用层后过滤」方案，严格按以下顺序执行：
     * <ol>
     *   <li>参数校验（kbIds 非空、query 非空）</li>
     *   <li>召回量分层计算：普通场景每库 topK × expandFactor，白名单场景再 × 3</li>
     *   <li>多知识库串行粗排 + 合并：遍历每个 kbId 召回候选，空则跳过，合并为统一候选池</li>
     *   <li>白名单过滤（应用层后过滤，严格位于粗排之后、去重之前）</li>
     *   <li>跨库去重：优先级 docId+chunkId → chunkId → docId+textHash</li>
     *   <li>全局精排（跨库统一排序，失败降级为原始相似度排序）</li>
     *   <li>父子增强（子块替换为父块完整内容）</li>
     * </ol>
     * <p>关键字提炼与回答生成暂不实现，留作后续扩展点。</p>
     *
     * @param request 多库检索请求
     * @return 全局最优切片列表（数量 ≤ topK）
     */
    public List<MultiSearchResult> multiSearch(MultiSearchRequest request) {
        if (request.getKbIds() == null || request.getKbIds().isEmpty()) {
            throw new RagException("RAG_KB_EMPTY", "知识库 ID 列表不能为空");
        }
        // 关键字提炼（步骤2）：当前直接回退原提问，TODO 后续可接入轻量模型提炼关键词
        String query = request.getQuery();
        if (query == null || query.isBlank()) {
            throw new RagException("RAG_QUERY_EMPTY", "检索内容不能为空");
        }

        int topK = request.getTopK() != null && request.getTopK() > 0 ? request.getTopK() : 5;
        int expandFactor = request.getExpandFactor() != null && request.getExpandFactor() > 0
                ? request.getExpandFactor() : 2;

        // ===== 步骤3：召回量分层计算 =====
        // 普通场景每库召回量 = topK × expandFactor；白名单场景放大 3 倍补偿过滤损耗
        boolean hasWhitelist = request.getWhitelist() != null && !request.getWhitelist().isEmpty();
        int recallTopK = topK * expandFactor * (hasWhitelist ? 3 : 1);
        log.info("多库检索开始, kbIds={}, query={}, topK={}, expandFactor={}, whitelist={}, recallTopK={}",
                request.getKbIds(), query, topK, expandFactor, hasWhitelist, recallTopK);

        // 构建统一检索配置（所有知识库复用同一 query 与 searchMode，保证跨库分数可比较）
        SearchConfig searchConfig = buildSearchConfig(request);

        // ===== 步骤4：多知识库串行粗排 + 合并 =====
        List<Document> merged = new ArrayList<>();
        for (Long kbId : request.getKbIds()) {
            try {
                List<Document> candidates = recallFromKb(kbId, query, recallTopK, searchConfig);
                if (candidates == null || candidates.isEmpty()) {
                    log.info("知识库 {} 检索无结果，跳过", kbId);
                    continue;
                }
                merged.addAll(candidates);
            } catch (Exception e) {
                // 单个知识库检索失败不阻断整体，仅跳过（容错）
                log.warn("知识库 {} 检索失败，跳过: {}", kbId, e.getMessage());
            }
        }
        log.info("多库粗排合并完成, 候选池 {} 条", merged.size());

        // 合并后为空直接返回空结果，不进入后续步骤
        if (merged.isEmpty()) {
            return List.of();
        }

        // ===== 步骤5：白名单过滤（粗排之后、去重之前） =====
        if (hasWhitelist) {
            merged = filterByWhitelist(merged, request.getWhitelist());
            log.info("白名单过滤完成, 剩余 {} 条", merged.size());
        }

        // 白名单过滤后为空：交由精排/降级排序处理（下方去重后为空同样直接返回空）
        if (merged.isEmpty()) {
            return List.of();
        }

        // ===== 步骤6：跨库去重 =====
        merged = deduplicate(merged);
        log.info("跨库去重完成, 剩余 {} 条", merged.size());

        // ===== 步骤7：全局精排（跨库统一排序，保证全局最优） =====
        merged = rerankIfEnabled(merged, query, request);

        // ===== 步骤8：父子增强 =====
        if (Boolean.TRUE.equals(request.getEnableParentEnhancement())) {
            merged = applyParentEnhancement(merged);
        }

        // ===== 最终 topK 截断 =====
        if (merged.size() > topK) {
            merged = merged.subList(0, topK);
        }

        return merged.stream().map(this::toResult).collect(Collectors.toList());
    }

    /**
     * 依据请求构建统一 {@link SearchConfig}（所有知识库复用同一检索配置）。
     * <p>searchMode 解析失败回退 VECTOR_ONLY；rerank 参数透传。</p>
     */
    private SearchConfig buildSearchConfig(MultiSearchRequest request) {
        SearchConfig.SearchConfigBuilder builder = SearchConfig.builder();
        String mode = request.getSearchMode();
        if (mode != null && !mode.isBlank()) {
            try {
                builder.searchMode(SearchMode.valueOf(mode.toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("未知 searchMode: {}, 回退 VECTOR_ONLY", mode);
                builder.searchMode(SearchMode.VECTOR_ONLY);
            }
        }
        // 多库场景下重排由 multiSearch 统一执行，此处无需在单库内部触发
        builder.rerankEnabled(false);
        return builder.build();
    }

    /**
     * 对单个知识库执行召回，返回候选文档列表。
     * <p>复用 {@link KbConfigService#loadConfigs} 加载配置，按 collectionName 从注册表获取缓存 store。</p>
     */
    private List<Document> recallFromKb(Long kbId, String query, int recallTopK, SearchConfig searchConfig) {
        KbConfigService.KbLoadedConfig loaded = kbConfigService.loadConfigs(kbId);
        EmbeddingConfig embeddingConfig = loaded.embeddingConfig();
        WeaviateCollectionConfig collectionConfig = loaded.collectionConfig();

        EmbeddingModel model = resolveEmbeddingModel(embeddingConfig);
        WeaviateVectorStoreAdapter store = (WeaviateVectorStoreAdapter) vectorStoreRegistry.getWeaviateStore(
                collectionConfig.getClassName(), model, collectionConfig.getVectorDim());

        return store.searchByMode(query, recallTopK, null, searchConfig);
    }

    /**
     * 白名单过滤：白名单转为 {@link HashSet} 常数级查找，仅保留「文档归属(docId)」命中白名单的切片。
     * <p>严格位于粗排之后、去重之前。</p>
     */
    private List<Document> filterByWhitelist(List<Document> candidates, List<String> whitelist) {
        Set<String> allowed = new HashSet<>(whitelist);
        List<Document> filtered = new ArrayList<>();
        for (Document doc : candidates) {
            Object docId = doc.getMetadata().get("docId");
            String docIdStr = docId == null ? null : String.valueOf(docId);
            if (docIdStr != null && allowed.contains(docIdStr)) {
                filtered.add(doc);
            }
        }
        return filtered;
    }

    /**
     * 跨库去重：三级 key 优先级
     * <ol>
     *   <li>文档归属 + 切片标识（docId + chunkId，最精确）</li>
     *   <li>切片自带的唯一 ID（Document.getId()，即 recordId）</li>
     *   <li>文档归属 + 文本哈希（docId + textHash，兜底）</li>
     * </ol>
     * 保留高分，保持首次出现顺序。
     */
    private List<Document> deduplicate(List<Document> candidates) {
        Map<String, Document> seen = new LinkedHashMap<>();
        for (Document doc : candidates) {
            String key = dedupeKey(doc);
            if (key == null) {
                // 无法构造有效去重键时按切片唯一 ID 兜底，仍无则按文本哈希
                key = "id:" + doc.getId();
            }
            // 已存在则保留得分更高者
            Document existing = seen.get(key);
            if (existing == null || scoreOf(doc) > scoreOf(existing)) {
                seen.put(key, doc);
            }
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * 构造去重键，按优先级返回第一个可用的组合键。
     */
    private String dedupeKey(Document doc) {
        String docId = strMeta(doc, "docId");
        String chunkId = strMeta(doc, "chunkId");
        // 优先级1：文档归属 + 切片标识
        if (docId != null && chunkId != null) {
            return docId + "|" + chunkId;
        }
        // 优先级2：切片自带的唯一 ID（recordId / Document.getId()）
        String recordId = strMeta(doc, "recordId");
        if (recordId != null) {
            return "rid:" + recordId;
        }
        // 优先级3：文档归属 + 文本哈希
        String textHash = strMeta(doc, "textHash");
        if (docId != null && textHash != null) {
            return docId + "|" + textHash;
        }
        return null;
    }

    /**
     * 全局精排：合并去重后的候选统一精排，保证全局最优。
     * <p>精排失败时降级为原始相似度分数降序排序，服务不中断。</p>
     */
    private List<Document> rerankIfEnabled(List<Document> candidates, String query, MultiSearchRequest request) {
        if (!Boolean.TRUE.equals(request.getRerank())) {
            return sortByScore(candidates);
        }
        try {
            RerankModel rerankModel = resolveRerankModel();
            if (rerankModel != null) {
                List<Document> reranked = rerank(query, candidates, rerankModel);
                if (reranked != null && !reranked.isEmpty()) {
                    log.info("多库全局精排完成, {} 条", reranked.size());
                    return reranked;
                }
            }
        } catch (Exception e) {
            log.warn("多库全局精排异常，降级为原始相似度排序: {}", e.getMessage());
        }
        return sortByScore(candidates);
    }

    /**
     * 按原始相似度得分降序排序（精排降级 / 未启用精排时的默认排序）。
     */
    private List<Document> sortByScore(List<Document> candidates) {
        List<Document> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(this::scoreOf).reversed());
        return sorted;
    }

    /**
     * 父子增强：将命中的子块替换为其父块完整内容。
     * <p>优先从当前候选池按 chunkId == parentChunkId 匹配父块；未命中则保留原子块。</p>
     */
    private List<Document> applyParentEnhancement(List<Document> candidates) {
        // 构建 chunkId -> Document 索引，用于反查父块
        Map<String, Document> byChunkId = new HashMap<>();
        for (Document doc : candidates) {
            String chunkId = strMeta(doc, "chunkId");
            if (chunkId != null) {
                byChunkId.put(chunkId, doc);
            }
        }

        List<Document> enhanced = new ArrayList<>(candidates.size());
        for (Document doc : candidates) {
            String chunkType = strMeta(doc, "chunkType");
            String parentChunkId = strMeta(doc, "parentChunkId");
            // 仅对子块（child）执行增强，父块/扁平分片保持原样
            if ("child".equals(chunkType) && parentChunkId != null) {
                Document parent = byChunkId.get(parentChunkId);
                if (parent != null && parent.getText() != null && !parent.getText().isBlank()) {
                    // 用父块完整内容替换子块文本，但保留子块自身的元数据（docId/kbId/chunkId 等）
                    Document replaced = new Document(doc.getId(), parent.getText(), doc.getMetadata());
                    replaced.getMetadata().put("parentEnhanced", true);
                    enhanced.add(replaced);
                    continue;
                }
                // 候选池内未命中父块：TODO 后续可扩展通过 doc_chunk 表反查父块原文
            }
            enhanced.add(doc);
        }
        return enhanced;
    }

    /**
     * 将检索得到的 {@link Document} 映射为 {@link MultiSearchResult}。
     */
    private MultiSearchResult toResult(Document doc) {
        Double score = doc.getScore();
        // 精排得分回填在 relevanceScore 中，优先取用
        Object rel = doc.getMetadata().get("relevanceScore");
        if (rel instanceof Number n) {
            score = n.doubleValue();
        }
        return MultiSearchResult.builder()
                .chunkId(strMeta(doc, "chunkId"))
                .docId(strMeta(doc, "docId"))
                .kbId(strMeta(doc, "kbId"))
                .fileName(strMeta(doc, "fileName"))
                .snippet(doc.getText())
                .score(score)
                .chunkMode(strMeta(doc, "chunkMode"))
                .chunkType(strMeta(doc, "chunkType"))
                .parentChunkId(strMeta(doc, "parentChunkId"))
                .sourcePath(strMeta(doc, "sourcePath"))
                .build();
    }

    /**
     * 从文档元数据中安全读取字符串字段（兼容数值类型，统一转 String）。
     */
    private String strMeta(Document doc, String key) {
        Object v = doc.getMetadata().get(key);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 获取文档得分（缺省视为 0）。
     */
    private double scoreOf(Document doc) {
        Double score = doc.getScore();
        Object rel = doc.getMetadata().get("relevanceScore");
        if (rel instanceof Number n) {
            return n.doubleValue();
        }
        return score == null ? 0.0 : score;
    }

    /**
     * 解析重排模型：从 {@link AiModelFactory} 配置中查找第一个 RERANK 类别模型。
     *
     * @return 重排模型；未配置时返回 {@code null}
     */
    private RerankModel resolveRerankModel() {
        List<String> rerankNames = aiModelFactory.getModelNamesByCategory(
                com.rag.common.enums.ModelCategory.RERANK);
        if (rerankNames.isEmpty()) {
            log.warn("未配置 RERANK 类别模型，跳过重排");
            return null;
        }
        String modelName = rerankNames.get(0);
        return aiModelFactory.getRerankModel(modelName);
    }

    /**
     * 调用重排模型执行精排，并转换为 Document 列表。
     * <p>重排结果按索引回填到原始文档列表，按精排得分降序返回。</p>
     *
     * @param query      用户查询
     * @param documents  候选文档列表
     * @param rerankModel 重排模型
     * @return 精排后的文档列表
     */
    private List<Document> rerank(String query, List<Document> documents, RerankModel rerankModel) {
        List<String> texts = documents.stream().map(Document::getText).toList();
        List<RerankModel.RerankResult> results = rerankModel.rerank(query, texts, documents.size());
        if (results == null || results.isEmpty()) {
            return documents;
        }
        // 按精排结果顺序重建文档列表，并将精排得分写入 metadata
        List<Document> reranked = new ArrayList<>(results.size());
        for (RerankModel.RerankResult r : results) {
            if (r.index() >= 0 && r.index() < documents.size()) {
                Document doc = documents.get(r.index());
                doc.getMetadata().put("relevanceScore", r.score());
                reranked.add(doc);
            }
        }
        return reranked;
    }

    // ==================== 文档列表 ====================

    public List<KbDocument> listDocuments(Long kbId) {
        return kbDocumentMapper.findByKbId(kbId);
    }

    // ==================== 状态机辅助方法 ====================

    /**
     * 桥接：根据 EmbeddingConfig 解析 EmbeddingModel。
     * <p>将知识库维度的 embedding 模型类型映射到 {@link AiModelFactory} 配置中的逻辑模型名，
     * 再由工厂统一创建（惰性 + 缓存）。</p>
     *
     * @param embeddingConfig Embedding 配置（含模型类型）
     * @return EmbeddingModel 实例
     */
    private EmbeddingModel resolveEmbeddingModel(EmbeddingConfig embeddingConfig) {
        // 将 EmbeddingModelType 映射为 spring.ai.platform.models 中的逻辑模型名
        String logicalName = switch (embeddingConfig.getModelType()) {
            case BGE_M3 -> "bge-m3";
            case TONGYI -> "tongyi";
            case OPENAI -> "openai";
        };
        if (!aiModelFactory.existsModel(logicalName)) {
            // 逻辑名未配置时，回退尝试直接按 API 模型名查找
            String apiModelName = embeddingConfig.getModelName();
            if (apiModelName != null && aiModelFactory.existsModel(apiModelName)) {
                logicalName = apiModelName;
            }
        }
        return aiModelFactory.getEmbeddingModel(logicalName);
    }

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
