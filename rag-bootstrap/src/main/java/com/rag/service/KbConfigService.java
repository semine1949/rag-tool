package com.rag.service;

import com.rag.auth.mapper.KnowledgeBaseMapper;
import com.rag.common.entity.config.EmbeddingConfig;
import com.rag.common.entity.config.EmbeddingProperties;
import com.rag.common.entity.config.WeaviateCollectionConfig;
import com.rag.common.entity.KnowledgeBase;
import com.rag.common.enums.EmbeddingModelType;
import com.rag.config.factory.VectorStoreRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 知识库配置服务（v2 重构）
 * <p>分片策略由 {@link com.rag.common.chunker.ChunkStrategyFactory} 依据 chunkStrategy + SplitterConfig
 * 统一判定构造，知识库仅保留 embedding_model 相关配置。</p>
 */
@Service
public class KbConfigService {

    private static final Logger log = LoggerFactory.getLogger(KbConfigService.class);

    private final KnowledgeBaseMapper kbMapper;
    private final VectorStoreRegistry vectorStoreRegistry;
    private final EmbeddingProperties embeddingProperties;

    public KbConfigService(KnowledgeBaseMapper kbMapper,
                           VectorStoreRegistry vectorStoreRegistry,
                           EmbeddingProperties embeddingProperties) {
        this.kbMapper = kbMapper;
        this.vectorStoreRegistry = vectorStoreRegistry;
        this.embeddingProperties = embeddingProperties;
    }

    /**
     * 知识库加载结果（仅包含 KB / Embedding / Collection 配置，分片策略由分片工厂统一判定）
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
            throw new com.rag.common.exception.RagException("RAG_KB_NOT_FOUND", "知识库不存在: " + kbId);
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