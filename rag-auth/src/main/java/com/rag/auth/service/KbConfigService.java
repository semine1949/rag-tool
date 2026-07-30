package com.rag.auth.service;

import com.rag.auth.mapper.KnowledgeBaseMapper;
import com.rag.core.config.ChunkConfig;
import com.rag.core.config.EmbeddingConfig;
import com.rag.core.config.EmbeddingProperties;
import com.rag.core.config.WeaviateCollectionConfig;
import com.rag.core.entity.KbDocument;
import com.rag.core.entity.KnowledgeBase;
import com.rag.core.enums.ChunkStrategyEnum;
import com.rag.core.enums.EmbeddingModelType;
import com.rag.core.factory.VectorStoreRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Set;

/**
 * 知识库配置服务（v2 重构）
 * <p>分片策略已从知识库下移到文档维度，知识库仅保留 embedding_model。</p>
 * <p>新增 buildChunkConfig(KbDocument) 从文档维度构建分片配置。</p>
 */
@Service
public class KbConfigService {

    private static final Logger log = LoggerFactory.getLogger(KbConfigService.class);

    private final KnowledgeBaseMapper kbMapper;
    private final VectorStoreRegistry vectorStoreRegistry;
    private final EmbeddingProperties embeddingProperties;

    @Value("${rag.chunk.default.fixed-size:500}")
    private int defaultFixedSize;
    @Value("${rag.chunk.default.slide-overlap:50}")
    private int defaultSlideOverlap;
    @Value("${rag.chunk.default.semantic-threshold:0.7}")
    private double defaultSemanticThreshold;
    @Value("${rag.chunk.default.split-table:true}")
    private boolean defaultSplitTable;
    @Value("${rag.chunk.default.split-code:true}")
    private boolean defaultSplitCode;
    @Value("${rag.chunk.default.max-title-level:3}")
    private int defaultMaxTitleLevel;
    @Value("${rag.chunk.default.parent-chunk-len:1000}")
    private int defaultParentChunkLen;
    @Value("${rag.chunk.default.child-chunk-len:200}")
    private int defaultChildChunkLen;

    public KbConfigService(KnowledgeBaseMapper kbMapper,
                           VectorStoreRegistry vectorStoreRegistry,
                           EmbeddingProperties embeddingProperties) {
        this.kbMapper = kbMapper;
        this.vectorStoreRegistry = vectorStoreRegistry;
        this.embeddingProperties = embeddingProperties;
    }

    /**
     * 知识库加载结果（v2：ChunkConfig 改为从文档维度构建，此处仅包含 KB/Embedding/Collection 配置）
     */
    public record KbLoadedConfig(
            KnowledgeBase kb,
            EmbeddingConfig embeddingConfig,
            WeaviateCollectionConfig collectionConfig) {
    }

    public KbLoadedConfig loadConfigs(Long kbId) {
        KnowledgeBase kb = getKb(kbId);
        return new KbLoadedConfig(kb, loadEmbeddingConfig(kbId), loadCollectionConfig(kbId));
    }

    public KnowledgeBase getKb(Long kbId) {
        KnowledgeBase kb = kbMapper.findById(kbId);
        if (kb == null) {
            throw new com.rag.core.exception.RagException("RAG_KB_NOT_FOUND", "知识库不存在: " + kbId);
        }
        return kb;
    }

    // ===== v2：创建知识库仅需 embedding_model，chunk 策略由文档维度配置 =====

    public KnowledgeBase createKnowledgeBase(Long tenantId, String kbName, String description,
                                             String embeddingModel) {
        KnowledgeBase kb = KnowledgeBase.builder()
                .tenantId(tenantId)
                .kbName(kbName)
                .description(description)
                .embeddingModel(embeddingModel)
                .status(1)
                .createTime(new Date())
                .build();
        kbMapper.insert(kb);
        initKbCollection(kb.getKbId());
        log.info("创建知识库成功, kbId={}, name={}", kb.getKbId(), kb.getKbName());
        return kb;
    }

    // ===== v2：更新知识库仅更新 embedding_model =====

    public void updateKbEmbeddingModel(Long kbId, String embeddingModel) {
        kbMapper.updateEmbeddingModel(kbId, embeddingModel);
        log.info("更新知识库 Embedding 模型成功, kbId={}", kbId);
    }

    // ==================== v2 新增：从文档维度构建 ChunkConfig ====================

    /**
     * 从文档维度的分片策略配置构建 {@link ChunkConfig}。
     * <p>优先使用文档自身配置，未指定时回退 application.yml 默认值。</p>
     *
     * @param doc 知识库文档（包含 chunkStrategy/chunkSize/chunkOverlap 字段）
     * @return 合并后的分片配置
     */
    public ChunkConfig buildChunkConfig(KbDocument doc) {
        ChunkStrategyEnum strategy = resolveChunkStrategy(doc.getChunkStrategy());
        int chunkSize = (doc.getChunkSize() != null && doc.getChunkSize() > 0)
                ? doc.getChunkSize() : defaultFixedSize;
        int chunkOverlap = (doc.getChunkOverlap() != null)
                ? doc.getChunkOverlap() : defaultSlideOverlap;

        return ChunkConfig.builder()
                .enableStrategies(Set.of(strategy))
                .fixedChunkSize(chunkSize)
                .slideOverlap(chunkOverlap)
                .semanticThreshold(defaultSemanticThreshold)
                .splitTableSingleChunk(defaultSplitTable)
                .splitCodeByFunction(defaultSplitCode)
                .maxTitleLevel(defaultMaxTitleLevel)
                .parentChunkLen(defaultParentChunkLen)
                .childChunkLen(defaultChildChunkLen)
                .build();
    }

    private ChunkStrategyEnum resolveChunkStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return ChunkStrategyEnum.FIXED_SIZE;
        }
        try {
            return ChunkStrategyEnum.valueOf(strategy.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知分片策略 '{}'，回退默认 FIXED_SIZE", strategy);
            return ChunkStrategyEnum.FIXED_SIZE;
        }
    }

    // ==================== 集合管理（Spring AI） ====================

    /**
     * 初始化集合：Spring AI WeaviateVectorStore 在首次 add 时懒创建集合，
     * 故此处仅做占位（集合随后在入库时自动建立）。
     */
    public void initKbCollection(Long kbId) {
        log.info("知识库集合将在首次入库时由 Spring AI 自动创建, kbId={}", kbId);
    }

    public void clearKbCollection(Long kbId) {
        String className = loadCollectionConfig(kbId).getClassName();
        vectorStoreRegistry.clearCollection(className);
        log.info("清空知识库集合成功, kbId={}, className={}", kbId, className);
    }

    public void dropKbCollection(Long kbId) {
        String className = loadCollectionConfig(kbId).getClassName();
        vectorStoreRegistry.dropCollection(className);
        log.info("删除知识库集合成功, kbId={}, className={}", kbId, className);
    }

    public void reprocessKnowledgeBase(Long kbId) {
        String className = loadCollectionConfig(kbId).getClassName();
        vectorStoreRegistry.clearCollection(className);
        initKbCollection(kbId);
        log.info("知识库重处理(清空集合)成功, kbId={}, className={}", kbId, className);
    }

    // ==================== 配置解析 ====================

    public EmbeddingConfig loadEmbeddingConfig(Long kbId) {
        KnowledgeBase kb = getKb(kbId);
        return resolveEmbeddingConfig(kb.getEmbeddingModel());
    }

    private EmbeddingConfig resolveEmbeddingConfig(String embeddingModel) {
        EmbeddingModelType modelType = resolveType(embeddingModel);
        EmbeddingProperties.ModelProps p = switch (modelType) {
            case BGE_M3 -> embeddingProperties.getBgeM3();
            case OPENAI -> embeddingProperties.getOpenai();
            case TONGYI -> embeddingProperties.getTongyi();
        };
        String modelName = (embeddingModel != null && !embeddingModel.isBlank())
                ? embeddingModel : p.getModel();
        return EmbeddingConfig.builder()
                .modelType(modelType)
                .modelName(modelName)
                .baseUrl(p.getBaseUrl())
                .modelSource(p.getApiKey())
                .vectorDim(p.getDim())
                .build();
    }

    private EmbeddingModelType resolveType(String embeddingModel) {
        if (embeddingModel == null || embeddingModel.isBlank()) {
            return EmbeddingModelType.TONGYI;
        }
        String s = embeddingModel.toUpperCase();
        if (s.contains("BGE")) {
            return EmbeddingModelType.BGE_M3;
        }
        if (s.contains("TEXT-EMBEDDING") || s.contains("OPENAI") || s.contains("ADA")) {
            return EmbeddingModelType.OPENAI;
        }
        return EmbeddingModelType.TONGYI;
    }

    public WeaviateCollectionConfig loadCollectionConfig(Long kbId) {
        KnowledgeBase kb = getKb(kbId);
        EmbeddingConfig emb = resolveEmbeddingConfig(kb.getEmbeddingModel());
        String className = deriveCollectionName(kb.getTenantId(), kb.getKbId());
        WeaviateCollectionConfig config = new WeaviateCollectionConfig();
        config.setClassName(className);
        config.setVectorDim(emb.getVectorDim());
        return config;
    }

    /**
     * 派生 Weaviate 集合名（多租户物理隔离）：T{tenantId}_Kb{kbId}
     */
    public static String deriveCollectionName(Long tenantId, Long kbId) {
        return "T" + tenantId + "_Kb" + kbId;
    }
}
