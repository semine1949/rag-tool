package com.rag.auth.service;

import com.rag.auth.mapper.KnowledgeBaseMapper;
import com.rag.core.config.ChunkConfig;
import com.rag.core.config.EmbeddingConfig;
import com.rag.core.config.EmbeddingProperties;
import com.rag.core.config.WeaviateCollectionConfig;
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
 * 知识库配置服务（Spring AI 重构版）
 * <p>负责知识库元信息、Embedding 配置、Weaviate 集合配置管理，以及集合的创建/清空/删除。</p>
 * <p>集合的物理创建交由 Spring AI {@code WeaviateVectorStore} 在首次 add 时懒创建；
 * 清空/删除通过 {@link VectorStoreRegistry} 操作底层 WeaviateClient。</p>
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

    public record KbLoadedConfig(
            KnowledgeBase kb,
            ChunkConfig chunkConfig,
            EmbeddingConfig embeddingConfig,
            WeaviateCollectionConfig collectionConfig) {
    }

    public KbLoadedConfig loadConfigs(Long kbId) {
        KnowledgeBase kb = getKb(kbId);
        return new KbLoadedConfig(kb, loadChunkConfig(), loadEmbeddingConfig(kbId), loadCollectionConfig(kbId));
    }

    public KnowledgeBase getKb(Long kbId) {
        KnowledgeBase kb = kbMapper.findById(kbId);
        if (kb == null) {
            throw new com.rag.core.exception.RagException("RAG_KB_NOT_FOUND", "知识库不存在: " + kbId);
        }
        return kb;
    }

    public KnowledgeBase createKnowledgeBase(Long tenantId, String kbName, String description,
                                             String chunkStrategy, Integer chunkSize,
                                             Integer chunkOverlap, String embeddingModel) {
        KnowledgeBase kb = KnowledgeBase.builder()
                .tenantId(tenantId)
                .kbName(kbName)
                .description(description)
                .chunkStrategy(chunkStrategy)
                .chunkSize(chunkSize)
                .chunkOverlap(chunkOverlap)
                .embeddingModel(embeddingModel)
                .status(1)
                .createTime(new Date())
                .build();
        kbMapper.insert(kb);
        initKbCollection(kb.getKbId());
        log.info("创建知识库成功, kbId={}, name={}", kb.getKbId(), kb.getKbName());
        return kb;
    }

    public void updateKbConfig(Long kbId, String chunkStrategy, Integer chunkSize,
                               Integer chunkOverlap, String embeddingModel) {
        kbMapper.updateConfig(kbId, chunkStrategy, chunkSize, chunkOverlap, embeddingModel);
        log.info("更新知识库配置成功, kbId={}", kbId);
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

    public ChunkConfig loadChunkConfig() {
        return ChunkConfig.builder()
                .enableStrategies(Set.of(
                        ChunkStrategyEnum.FIXED_SIZE,
                        ChunkStrategyEnum.SEMANTIC))
                .fixedChunkSize(defaultFixedSize)
                .slideOverlap(defaultSlideOverlap)
                .semanticThreshold(defaultSemanticThreshold)
                .splitTableSingleChunk(defaultSplitTable)
                .splitCodeByFunction(defaultSplitCode)
                .maxTitleLevel(defaultMaxTitleLevel)
                .parentChunkLen(defaultParentChunkLen)
                .childChunkLen(defaultChildChunkLen)
                .build();
    }

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
