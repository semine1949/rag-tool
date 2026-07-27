package com.rag.boot.service;

import com.rag.auth.mapper.KbDocumentMapper;
import com.rag.auth.service.KbConfigService;
import com.rag.chunker.ChunkerFactory;
import com.rag.core.api.DocumentVersionService;
import com.rag.core.api.VectorStore;
import com.rag.embedding.EmbeddingFactory;
import com.rag.parser.DocumentParseFactory;
import com.rag.core.config.ChunkConfig;
import com.rag.core.config.EmbeddingConfig;
import com.rag.core.config.WeaviateCollectionConfig;
import com.rag.core.entity.*;
import com.rag.core.enums.EmbeddingModelType;
import com.rag.core.exception.RagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * RAG工具核心服务 —— Multi-Tenant v2
 * 按 kbId 从 DB 加载预设配置，自动完成 解析→分片→向量化→入库
 */
@Service
public class RagToolService {

    private static final Logger log = LoggerFactory.getLogger(RagToolService.class);

    private final DocumentParseFactory parseFactory;
    private final ChunkerFactory chunkerFactory;
    private final EmbeddingFactory embeddingFactory;
    private final VectorStore vectorStore;
    private final DocumentVersionService versionService;
    private final Executor taskExecutor;
    private final KbConfigService kbConfigService;
    private final KbDocumentMapper kbDocumentMapper;

    public RagToolService(DocumentParseFactory parseFactory,
                          ChunkerFactory chunkerFactory,
                          EmbeddingFactory embeddingFactory,
                          VectorStore vectorStore,
                          DocumentVersionService versionService,
                          @Qualifier("ragTaskExecutor") Executor taskExecutor,
                          KbConfigService kbConfigService,
                          KbDocumentMapper kbDocumentMapper) {
        this.parseFactory = parseFactory;
        this.chunkerFactory = chunkerFactory;
        this.embeddingFactory = embeddingFactory;
        this.vectorStore = vectorStore;
        this.versionService = versionService;
        this.taskExecutor = taskExecutor;
        this.kbConfigService = kbConfigService;
        this.kbDocumentMapper = kbDocumentMapper;
    }

    /**
     * 单文件完整处理链路（Multi-Tenant v2）
     */
    public FileProcessResult processFile(MultipartFile file, Long kbId, Long userId) {
        // 从知识库加载预设配置
        KbConfigService.KbLoadedConfig loaded = kbConfigService.loadAllConfigs(kbId);
        ChunkConfig chunkConfig = loaded.chunkConfig();
        EmbeddingConfig embeddingConfig = loaded.embeddingConfig();
        WeaviateCollectionConfig collectionConfig = loaded.collectionConfig();

        File tempFile = null;
        try {
            // 1. 文件落盘
            Path dir = Paths.get("./data/uploads").toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String safeName = sanitizeFileName(file.getOriginalFilename());
            String storedName = UUID.randomUUID().toString().substring(0, 8) + "_" + safeName;
            tempFile = dir.resolve(storedName).toFile();
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            // 2. 文档解析
            log.info("开始解析文件: kbId={}, fileName={}", kbId, file.getOriginalFilename());
            DocumentParseResult parseResult = parseFactory.parse(tempFile);
            if (parseResult.getFullText() == null || parseResult.getFullText().isBlank()) {
                throw new RagException("RAG_SVC_001", "未提取到有效文本: " + file.getOriginalFilename());
            }

            // 3. 文本分片
            log.info("开始分片: kbId={}, fileName={}, strategies={}", kbId, file.getOriginalFilename(), chunkConfig.getEnableStrategies());
            List<Chunk> chunks = chunkerFactory.chunk(parseResult, chunkConfig);

            // 4. 版本号生成与权限绑定
            String documentId = parseResult.getMeta().getFileId();
            String collectionClassName = collectionConfig.getClassName();
            Long tenantId = loaded.kb().getTenantId();

            DocumentVersion currentVersion = versionService.getCurrentVersion(documentId);
            String changeType = currentVersion == null ? "INITIAL" : "MINOR";
            String newVersion = versionService.generateNextVersion(documentId, changeType);

            for (Chunk chunk : chunks) {
                chunk.setDocumentId(documentId);
                chunk.setDocumentVersion(newVersion);
                chunk.setOwnerId(userId);
            }

            DocumentVersion versionDoc = DocumentVersion.builder()
                    .documentId(documentId)
                    .tenantId(tenantId)
                    .kbId(kbId)
                    .version(newVersion)
                    .chunkCount(chunks.size())
                    .operatorId(userId)
                    .changeType(changeType)
                    .changeRemark("文件上传入库")
                    .collectionName(collectionClassName)
                    .currentActive(true)
                    .build();
            versionService.createVersion(versionDoc);

            // 登记文档到 kb_document 表
            KbDocument kbDoc = KbDocument.builder()
                    .kbId(kbId)
                    .tenantId(tenantId)
                    .fileName(file.getOriginalFilename())
                    .fileType(detectContentType(file.getOriginalFilename()))
                    .chunkCount(chunks.size())
                    .version(newVersion)
                    .ownerId(userId)
                    .collectionName(collectionClassName)
                    .uploadTime(new Date())
                    .build();
            kbDocumentMapper.insert(kbDoc);

            log.info("版本号生成: documentId={}, version={}, changeType={}, chunks={}",
                    documentId, newVersion, changeType, chunks.size());

            // 5. Embedding向量化
            log.info("开始向量化: kbId={}, fileName={}, chunks={}", kbId, file.getOriginalFilename(), chunks.size());
            embeddingFactory.switchModel(embeddingConfig);
            List<String> chunkTexts = chunks.stream().map(Chunk::getText).toList();
            List<float[]> vectors = embeddingFactory.batchEmbed(chunkTexts, embeddingConfig);

            if (vectors.size() != chunks.size()) {
                throw new RagException("RAG_SVC_002", "向量数量与分片数量不一致");
            }

            // 6. 构建VectorRecord（写入 tenantId / kbId 元数据）
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
                        .documentId(chunk.getDocumentId())
                        .documentVersion(chunk.getDocumentVersion())
                        .ownerId(chunk.getOwnerId())
                        .tenantId(tenantId)
                        .kbId(kbId)
                        .extraMeta(chunk.getExtraMeta())
                        .build());
            }

            // 7. 入库Weaviate（物理隔离集合）
            vectorStore.initCollection(collectionConfig);

            // 增量更新：先删除该文件的旧分片
            if (parseResult.getMeta().getFileId() != null) {
                vectorStore.deleteByFileId(parseResult.getMeta().getFileId(), collectionConfig.getClassName());
            }

            int inserted = vectorStore.batchInsert(records, collectionConfig);

            return FileProcessResult.builder()
                    .fileId(parseResult.getMeta().getFileId())
                    .fileName(file.getOriginalFilename())
                    .chunkCount(chunks.size())
                    .insertedCount(inserted)
                    .success(true)
                    .build();

        } catch (Exception e) {
            log.error("文件处理失败: kbId={}, fileName={}", kbId, file.getOriginalFilename(), e);
            return FileProcessResult.builder()
                    .fileName(file.getOriginalFilename())
                    .success(false)
                    .error(e.getMessage())
                    .build();
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * 批量异步处理多个文件
     */
    @Async("ragTaskExecutor")
    public CompletableFuture<List<FileProcessResult>> batchProcessFiles(
            List<MultipartFile> files, Long kbId, Long userId) {
        List<FileProcessResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            results.add(processFile(file, kbId, userId));
        }
        return CompletableFuture.completedFuture(results);
    }

    /**
     * 处理本地文件夹
     */
    public List<FileProcessResult> processDirectory(String dirPath, Long kbId, Long userId) {
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
                        results.add(processFile(mf, kbId, userId));
                    } catch (IOException e) {
                        log.error("读取文件失败: {}", file.getName(), e);
                    }
                }
            }
        }
        return results;
    }

    /**
     * 向量检索
     */
    public List<FileProcessResult> search(Long kbId, Long userId, String query, int topK) {
        KbConfigService.KbLoadedConfig loaded = kbConfigService.loadAllConfigs(kbId);
        EmbeddingConfig embeddingConfig = loaded.embeddingConfig();
        WeaviateCollectionConfig collectionConfig = loaded.collectionConfig();

        // 向量化查询文本
        embeddingFactory.switchModel(embeddingConfig);
        List<float[]> vecs = embeddingFactory.batchEmbed(List.of(query), embeddingConfig);
        List<Float> vecList = new ArrayList<>();
        for (float v : vecs.get(0)) vecList.add(v);

        // 检索
        List<VectorRecord> results = vectorStore.search(vecList, topK, null, collectionConfig.getClassName());

        return results.stream()
                .map(r -> FileProcessResult.builder()
                        .fileId(r.getFileId())
                        .fileName(r.getFileName())
                        .success(true)
                        .build())
                .toList();
    }

    /**
     * 列出知识库文档
     */
    public List<KbDocument> listDocuments(Long kbId) {
        return kbDocumentMapper.findByKbId(kbId);
    }

    // ============ 工具方法 ============

    private String sanitizeFileName(String name) {
        if (name == null) return "unnamed";
        return name.replaceAll("[/\\\\]", "_")
                   .replaceAll("\\.{2,}", ".")
                   .replaceAll("\\s+", "_");
    }

    private String detectContentType(String fileName) {
        if (fileName == null) return "application/octet-stream";
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
