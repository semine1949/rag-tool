package com.rag.boot.controller;

import com.rag.auth.context.RequestContext;
import com.rag.auth.service.KbAccessService;
import com.rag.boot.service.FileProcessResult;
import com.rag.boot.service.RagToolService;
import com.rag.chunker.ParentChildTextSplitter;
import com.rag.chunker.SizeTextSplitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * RAG 对外 REST API（Multi-Tenant v2 —— 简化版）
 * 用户只需传 file + kbId，所有配置由系统按知识库预设自动完成
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);
    private final RagToolService ragToolService;
    private final KbAccessService kbAccessService;

    public RagController(RagToolService ragToolService, KbAccessService kbAccessService) {
        this.ragToolService = ragToolService;
        this.kbAccessService = kbAccessService;
    }

    // ==================== 文件上传 ====================

    /**
     * 单文件上传入库（file + kbId，可选文档级分片策略，未指定默认 FIXED_SIZE）
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam("kbId") Long kbId,
            @RequestParam(value = "chunkStrategy", required = false) String chunkStrategy,
            @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
            @RequestParam(value = "chunkOverlap", required = false) Integer chunkOverlap) {
        try {
            Long userId = requireAuth();
            kbAccessService.checkUploadPermission(userId, kbId);
            FileProcessResult result = ragToolService.processFile(file, kbId, null, chunkStrategy, chunkSize, chunkOverlap);
            return ResponseEntity.ok(Map.of("code", 200, "data", result));
        } catch (Exception e) {
            log.error("文件上传处理失败", e);
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    /**
     * text_model 上传文件落库 API。
     * <p>上传文件 + kbId，按 {@link SizeTextSplitter} 参数分块后落库（解析→分块→向量化→入库）。
     * splitter 参数（delimiter / maxTokens / chunkOverlap）作为接口参数传入，未传入时使用默认值。</p>
     */
    @PostMapping(value = "/upload/text-model", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadTextModel(
            @RequestPart("file") MultipartFile file,
            @RequestParam("kbId") Long kbId,
            @RequestParam(value = "delimiter", required = false) String delimiter,
            @RequestParam(value = "maxTokens", required = false) Integer maxTokens,
            @RequestParam(value = "chunkOverlap", required = false) Integer chunkOverlap) {
        try {
            Long userId = requireAuth();
            kbAccessService.checkUploadPermission(userId, kbId);
            // 未传入参数时采用默认值构造带参 splitter
            String sep = delimiter != null ? delimiter : "\n";
            int maxTk = maxTokens != null ? maxTokens : 1024;
            int overlap = chunkOverlap != null ? chunkOverlap : 50;
            SizeTextSplitter splitter = new SizeTextSplitter(sep, maxTk, overlap);
            FileProcessResult result = ragToolService.processFileWithSplitter(
                    file, kbId, null, "TEXT_MODEL", splitter);
            return ResponseEntity.ok(Map.of("code", 200, "data", result));
        } catch (Exception e) {
            log.error("text-model 上传落库失败", e);
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    /**
     * hierarchical_model 上传文件落库 API。
     * <p>上传文件 + kbId，按 {@link ParentChildTextSplitter} 参数分块后落库（解析→父块+子块→向量化→入库）。
     * splitter 参数（parentSeparator / parentMaxTokens / childSeparator / childMaxTokens / parentMode）
     * 作为接口参数传入，未传入时使用默认值。</p>
     */
    @PostMapping(value = "/upload/hierarchical-model", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadHierarchicalModel(
            @RequestPart("file") MultipartFile file,
            @RequestParam("kbId") Long kbId,
            @RequestParam(value = "parentSeparator", required = false) String parentSeparator,
            @RequestParam(value = "parentMaxTokens", required = false) Integer parentMaxTokens,
            @RequestParam(value = "childSeparator", required = false) String childSeparator,
            @RequestParam(value = "childMaxTokens", required = false) Integer childMaxTokens,
            @RequestParam(value = "parentMode", required = false) String parentMode) {
        try {
            Long userId = requireAuth();
            kbAccessService.checkUploadPermission(userId, kbId);
            // 未传入参数时采用默认值构造带参 splitter
            String pSep = parentSeparator != null ? parentSeparator : "\n\n\n";
            int pMaxTk = parentMaxTokens != null ? parentMaxTokens : 2048;
            String cSep = childSeparator != null ? childSeparator : "\n\n";
            int cMaxTk = childMaxTokens != null ? childMaxTokens : 1024;
            String pMode = parentMode != null ? parentMode : "paragraph";
            ParentChildTextSplitter splitter = new ParentChildTextSplitter(
                    pSep, pMaxTk, cSep, cMaxTk, pMode);
            FileProcessResult result = ragToolService.processFileWithSplitter(
                    file, kbId, null, "HIERARCHICAL_MODEL", splitter);
            return ResponseEntity.ok(Map.of("code", 200, "data", result));
        } catch (Exception e) {
            log.error("hierarchical-model 上传落库失败", e);
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    /**
     * 批量文件上传（仅需 files + kbId）
     */
    @PostMapping(value = "/upload/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadBatch(
            @RequestPart("files") List<MultipartFile> files,
            @RequestPart(value = "request", required = false) BatchUploadRequest request) {
        try {
            Long userId = requireAuth();
            Long kbId = request != null ? request.getKbId() : null;
            if (kbId == null) {
                return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "缺少 kbId"));
            }
            kbAccessService.checkUploadPermission(userId, kbId);
            // 透传文档级分片策略（request 可空，未指定则默认 FIXED_SIZE）
            String chunkStrategy = request != null ? request.getChunkStrategy() : null;
            Integer chunkSize = request != null ? request.getChunkSize() : null;
            Integer chunkOverlap = request != null ? request.getChunkOverlap() : null;
            CompletableFuture<List<FileProcessResult>> future =
                    ragToolService.batchProcessFiles(files, kbId, chunkStrategy, chunkSize, chunkOverlap);
            return ResponseEntity.ok(Map.of("code", 200, "msg", "批量任务已提交"));
        } catch (Exception e) {
            log.error("批量上传处理失败", e);
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    /**
     * 本地文件夹批量入库
     */
    @PostMapping("/process/directory")
    public ResponseEntity<?> processDirectory(@RequestBody DirectoryProcessRequest request) {
        try {
            Long userId = requireAuth();
            kbAccessService.checkUploadPermission(userId, request.getKbId());
            List<FileProcessResult> results = ragToolService.processDirectory(
                    request.getDirPath(), request.getKbId());
            return ResponseEntity.ok(Map.of("code", 200, "data", results));
        } catch (Exception e) {
            log.error("文件夹处理失败", e);
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    // ==================== 检索与查看 ====================

    /**
     * 向量检索
     */
    @PostMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam("kbId") Long kbId,
            @RequestParam("query") String query,
            @RequestParam(value = "topK", defaultValue = "5") int topK) {
        try {
            Long userId = requireAuth();
            kbAccessService.checkViewPermission(userId, kbId);
            List<FileProcessResult> results = ragToolService.search(query, kbId, topK);
            return ResponseEntity.ok(Map.of("code", 200, "data", results));
        } catch (Exception e) {
            log.error("检索失败", e);
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    /**
     * 查看知识库文档列表
     */
    @GetMapping("/documents")
    public ResponseEntity<?> listDocuments(@RequestParam("kbId") Long kbId) {
        try {
            Long userId = requireAuth();
            kbAccessService.checkViewPermission(userId, kbId);
            return ResponseEntity.ok(Map.of("code", 200, "data", ragToolService.listDocuments(kbId)));
        } catch (Exception e) {
            log.error("文档列表查询失败", e);
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    // ==================== 辅助方法 ====================

    private Long requireAuth() {
        Long userId = RequestContext.currentUserId();
        if (userId == null) {
            throw new com.rag.core.exception.RagException("AUTH_REQUIRED", "请先登录或提供 API-Key");
        }
        return userId;
    }
}
