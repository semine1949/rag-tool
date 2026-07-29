package com.rag.boot.controller;

import com.rag.auth.context.RequestContext;
import com.rag.auth.service.KbAccessService;
import com.rag.boot.service.FileProcessResult;
import com.rag.boot.service.RagToolService;
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
     * 单文件上传入库（仅需 file + kbId）
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam("kbId") Long kbId) {
        try {
            Long userId = requireAuth();
            kbAccessService.checkUploadPermission(userId, kbId);
            FileProcessResult result = ragToolService.processFile(file, kbId, null);
            return ResponseEntity.ok(Map.of("code", 200, "data", result));
        } catch (Exception e) {
            log.error("文件上传处理失败", e);
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
            CompletableFuture<List<FileProcessResult>> future =
                    ragToolService.batchProcessFiles(files, kbId);
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
