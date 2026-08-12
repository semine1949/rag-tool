package com.rag.controller;

import com.rag.auth.context.RequestContext;
import com.rag.auth.service.KbAccessService;
import com.rag.service.FileProcessResult;
import com.rag.service.RagToolService;
import com.rag.common.chunker.SplitterConfig;
import com.rag.common.entity.config.SearchConfig;
import com.rag.common.enums.SearchMode;
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
     * 单文件上传入库（默认兜底接口）。
     * <p>file + kbId，可选 chunkStrategy（text-model / hierarchical-model）。未指定或为 null 时
     * 回退 text-model，并使用默认参数分块。</p>
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam("kbId") Long kbId,
            @RequestParam(value = "chunkStrategy", required = false) String chunkStrategy) {
        try {
            Long userId = requireAuth();
            kbAccessService.checkUploadPermission(userId, kbId);
            // 默认兜底：config 传 null，工厂按 chunkStrategy 采用默认参数构造 splitter
            FileProcessResult result = ragToolService.processFile(file, kbId, null, chunkStrategy, null);
            return ResponseEntity.ok(Map.of("code", 200, "data", result));
        } catch (Exception e) {
            log.error("文件上传处理失败", e);
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    /**
     * text-model 上传文件落库 API（支持细粒度传参）。
     * <p>上传文件 + kbId，按 text-model 策略分块落库（解析→分块→向量化→入库）。
     * 通过 {@link SplitterConfig} 按用户传入的 delimiter / maxTokens / chunkOverlap 构造参数，
     * 未传入的字段采用默认值。</p>
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
            // 按用户传参构造 SplitterConfig（未传入的字段为 null，由 splitter 采用默认值）
            SplitterConfig config = new SplitterConfig();
            config.setDelimiter(delimiter);
            config.setMaxTokens(maxTokens);
            config.setChunkOverlap(chunkOverlap);
            FileProcessResult result = ragToolService.processFile(
                    file, kbId, null, "TEXT_MODEL", config);
            return ResponseEntity.ok(Map.of("code", 200, "data", result));
        } catch (Exception e) {
            log.error("text-model 上传落库失败", e);
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    /**
     * hierarchical-model 上传文件落库 API（支持细粒度传参）。
     * <p>上传文件 + kbId，按 hierarchical-model 策略分块落库（解析→父块+子块→向量化→入库）。
     * 通过 {@link SplitterConfig} 按用户传入的 parentSeparator / parentMaxTokens / childSeparator /
     * childMaxTokens / parentMode 构造参数，未传入的字段采用默认值。</p>
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
            // 按用户传参构造 SplitterConfig（未传入的字段为 null，由 splitter 采用默认值）
            SplitterConfig config = new SplitterConfig();
            config.setParentSeparator(parentSeparator);
            config.setParentMaxTokens(parentMaxTokens);
            config.setChildSeparator(childSeparator);
            config.setChildMaxTokens(childMaxTokens);
            config.setParentMode(parentMode);
            FileProcessResult result = ragToolService.processFile(
                    file, kbId, null, "HIERARCHICAL_MODEL", config);
            return ResponseEntity.ok(Map.of("code", 200, "data", result));
        } catch (Exception e) {
            log.error("hierarchical-model 上传落库失败", e);
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    /**
     * 批量文件上传（files + kbId，可指定分片策略与参数）。
     * <p>未指定 chunkStrategy 时由 Service 回退 text-model 默认参数；参数未传入时由 splitter 采用默认值。</p>
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
            // 依据 request 中用户传参构造 SplitterConfig（未传入字段为 null，由 splitter 采用默认值）
            String chunkStrategy = request != null ? request.getChunkStrategy() : null;
            SplitterConfig config = buildSplitterConfig(request);
            CompletableFuture<List<FileProcessResult>> future =
                    ragToolService.batchProcessFiles(files, kbId, chunkStrategy, config);
            return ResponseEntity.ok(Map.of("code", 200, "msg", "批量任务已提交"));
        } catch (Exception e) {
            log.error("批量上传处理失败", e);
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    /**
     * 依据 {@link BatchUploadRequest} 中用户传参构造 {@link SplitterConfig}。
     * request 为 null 时返回 null（Service 采用默认参数）。
     */
    private SplitterConfig buildSplitterConfig(BatchUploadRequest request) {
        if (request == null) {
            return null;
        }
        SplitterConfig config = new SplitterConfig();
        config.setDelimiter(request.getDelimiter());
        config.setMaxTokens(request.getMaxTokens());
        config.setChunkOverlap(request.getChunkOverlap());
        config.setParentSeparator(request.getParentSeparator());
        config.setParentMaxTokens(request.getParentMaxTokens());
        config.setChildSeparator(request.getChildSeparator());
        config.setChildMaxTokens(request.getChildMaxTokens());
        config.setParentMode(request.getParentMode());
        return config;
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
     * 多模式检索（兼容原有向量检索，扩展支持 BM25 / 混合召回 / 重排精排）。
     * <p>
     * 所有新增参数均为可选，不传时默认纯向量模式（100% 兼容原有行为）。
     * 支持三种检索模式：
     * <ul>
     *   <li>{@code searchMode=VECTOR_ONLY} —— 纯向量检索（默认）</li>
     *   <li>{@code searchMode=BM25_ONLY}  —— 纯 BM25 关键词检索</li>
     *   <li>{@code searchMode=HYBRID}     —— 混合多路召回（向量 + BM25 融合），可叠加重排精排</li>
     * </ul>
     * 融合参数：RRF 模式时 rrfK 默认 60；加权求和模式时 vectorWeight + bm25Weight 控制权重。
     * 重排参数：rerank=true 启用 Qwen3-Rerank 精排（仅 HYBRID 模式生效），
     * rerankMultiplier 控制候选池放大倍数（默认 3）。
     * </p>
     */
    @PostMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam("kbId") Long kbId,
            @RequestParam("query") String query,
            @RequestParam(value = "topK", defaultValue = "5") int topK,
            // ===== 检索模式（可选，默认 VECTOR_ONLY 兼容原有行为） =====
            @RequestParam(value = "searchMode", required = false) String searchMode,
            // ===== RRF 融合参数 =====
            @RequestParam(value = "rrfK", required = false) Integer rrfK,
            // ===== 加权求和参数 =====
            @RequestParam(value = "vectorWeight", required = false) Double vectorWeight,
            @RequestParam(value = "bm25Weight", required = false) Double bm25Weight,
            // ===== 重排参数（可选，默认关闭，仅 HYBRID 模式生效） =====
            @RequestParam(value = "rerank", required = false) Boolean rerank,
            @RequestParam(value = "rerankMultiplier", required = false) Integer rerankMultiplier) {
        try {
            Long userId = requireAuth();
            kbAccessService.checkViewPermission(userId, kbId);

            // 构建 SearchConfig（仅非空参数才设置，保持默认值）
            SearchConfig.SearchConfigBuilder configBuilder = SearchConfig.builder();

            if (searchMode != null && !searchMode.isBlank()) {
                try {
                    configBuilder.searchMode(SearchMode.valueOf(searchMode.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    log.warn("未知的 searchMode: {}, 回退为 VECTOR_ONLY", searchMode);
                    configBuilder.searchMode(SearchMode.VECTOR_ONLY);
                }
            }

            if (rrfK != null && rrfK > 0) {
                configBuilder.rrfK(rrfK);
            }
            if (vectorWeight != null) {
                configBuilder.vectorWeight(vectorWeight);
            }
            if (bm25Weight != null) {
                configBuilder.bm25Weight(bm25Weight);
            }
            // ===== 重排参数（可选，默认关闭） =====
            if (rerank != null) {
                configBuilder.rerankEnabled(rerank);
            }
            if (rerankMultiplier != null && rerankMultiplier > 0) {
                configBuilder.rerankCandidateMultiplier(rerankMultiplier);
            }

            SearchConfig config = configBuilder.build();
            List<FileProcessResult> results = ragToolService.search(query, kbId, topK, config);
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
            throw new com.rag.common.exception.RagException("AUTH_REQUIRED", "请先登录或提供 API-Key");
        }
        return userId;
    }
}
