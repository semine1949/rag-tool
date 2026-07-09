package com.rag.boot.controller;

import com.rag.boot.service.FileProcessResult;
import com.rag.boot.service.RagToolService;
import com.rag.core.config.ChunkConfig;
import com.rag.core.config.EmbeddingConfig;
import com.rag.core.config.WeaviateCollectionConfig;
import com.rag.core.enums.ChunkStrategyEnum;
import com.rag.core.enums.EmbeddingModelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * RAG工具对外REST API
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);
    private final RagToolService ragToolService;

    public RagController(RagToolService ragToolService) {
        this.ragToolService = ragToolService;
    }

    // ==================== 文件上传解析接口 ====================

    /**
     * 单文件上传并入库
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestBody(required = false) ChunkConfig chunkConfig,
            @RequestBody(required = false) EmbeddingConfig embeddingConfig,
            @RequestBody(required = false) WeaviateCollectionConfig collectionConfig) {
        try {
            FileProcessResult result = ragToolService.processFile(
                    file, chunkConfig, embeddingConfig, collectionConfig);
            return ResponseEntity.ok(Map.of("code", 200, "data", result));
        } catch (Exception e) {
            log.error("文件上传处理失败", e);
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    /**
     * 批量文件上传并异步入库
     */
    @PostMapping("/upload/batch")
    public ResponseEntity<?> uploadBatch(
            @RequestParam("files") List<MultipartFile> files,
            @RequestBody(required = false) BatchUploadRequest request) {
        try {
            ChunkConfig chunkConfig = request != null ? request.getChunkConfig() : null;
            EmbeddingConfig embeddingConfig = request != null ? request.getEmbeddingConfig() : null;
            WeaviateCollectionConfig collectionConfig = request != null ? request.getCollectionConfig() : null;

            CompletableFuture<List<FileProcessResult>> future =
                    ragToolService.batchProcessFiles(files, chunkConfig, embeddingConfig, collectionConfig);
            return ResponseEntity.ok(Map.of("code", 200, "msg", "批量任务已提交，等待处理完成"));
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
            List<FileProcessResult> results = ragToolService.processDirectory(
                    request.getDirPath(),
                    request.getChunkConfig(),
                    request.getEmbeddingConfig(),
                    request.getCollectionConfig());
            return ResponseEntity.ok(Map.of("code", 200, "data", results));
        } catch (Exception e) {
            log.error("文件夹处理失败", e);
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    // ==================== 分片策略配置接口 ====================

    /**
     * 获取所有分片策略
     */
    @GetMapping("/chunk/strategies")
    public ResponseEntity<?> getStrategies() {
        List<Map<String, String>> strategies = Arrays.stream(ChunkStrategyEnum.values())
                .map(s -> Map.of("name", s.name(), "description", getStrategyDescription(s)))
                .toList();
        return ResponseEntity.ok(Map.of("code", 200, "data", strategies));
    }

    // ==================== 模型切换接口 ====================

    /**
     * 获取可用模型列表
     */
    @GetMapping("/embedding/models")
    public ResponseEntity<?> getModels() {
        List<Map<String, String>> models = Arrays.stream(EmbeddingModelType.values())
                .map(m -> Map.of("name", m.name(), "label", getModelLabel(m)))
                .toList();
        return ResponseEntity.ok(Map.of("code", 200, "data", models));
    }

    // ==================== 集合管理接口 ====================

    /**
     * 初始化集合
     */
    @PostMapping("/collection/init")
    public ResponseEntity<?> initCollection(@RequestBody WeaviateCollectionConfig config) {
        try {
            ragToolService.initCollection(config);
            return ResponseEntity.ok(Map.of("code", 200, "msg", "集合初始化成功: " + config.getClassName()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    /**
     * 清空集合
     */
    @DeleteMapping("/collection/{className}/clear")
    public ResponseEntity<?> clearCollection(@PathVariable String className) {
        try {
            ragToolService.clearCollection(className);
            return ResponseEntity.ok(Map.of("code", 200, "msg", "集合已清空: " + className));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    /**
     * 删除集合
     */
    @DeleteMapping("/collection/{className}")
    public ResponseEntity<?> dropCollection(@PathVariable String className) {
        try {
            ragToolService.dropCollection(className);
            return ResponseEntity.ok(Map.of("code", 200, "msg", "集合已删除: " + className));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("code", 500, "msg", e.getMessage()));
        }
    }

    // ==================== 辅助方法 ====================

    private String getStrategyDescription(ChunkStrategyEnum strategy) {
        return switch (strategy) {
            case FIXED_SIZE -> "固定长度切割，支持重叠窗口";
            case SEMANTIC -> "语义边界切割（OpenNLP分句+相似度判定）";
            case TABLE -> "表格独立分片，附带行列元数据";
            case CODE_FUNCTION -> "代码按函数/方法边界切割";
            case TITLE_HIERARCHY -> "按标题层级递归切割";
            case PARENT_CHILD -> "父子分片，父块大段摘要+子块细分";
        };
    }

    private String getModelLabel(EmbeddingModelType type) {
        return switch (type) {
            case ONNX -> "本地ONNX（BGE/M3E/SBERT）";
            case OPENAI -> "OpenAI Embedding API";
            case OLLAMA -> "Ollama本地部署";
            case ZHIPU -> "智谱Embedding API";
            case TONGYI -> "通义千问Embedding API";
            case MINIMAX -> "MiniMax Embedding API";
        };
    }
}
