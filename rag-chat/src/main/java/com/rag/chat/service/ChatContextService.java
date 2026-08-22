package com.rag.chat.service;

import com.rag.chat.config.ChatProperties;
import com.rag.chat.assembler.ContextAssembler;
import com.rag.chat.document.TempDocumentService;
import com.rag.chat.safety.SafetyChecker;
import com.rag.common.chat.ChatMessage;
import com.rag.common.chat.ChatSession;
import com.rag.common.chat.Citation;
import com.rag.common.chat.QueryRewriter;
import com.rag.common.entity.config.EmbeddingConfig;
import com.rag.common.entity.config.SearchConfig;
import com.rag.common.entity.config.WeaviateCollectionConfig;
import com.rag.common.enums.SearchMode;
import com.rag.config.factory.AiModelFactory;
import com.rag.config.factory.VectorStoreRegistry;
import com.rag.config.vectorstore.WeaviateVectorStoreAdapter;
import com.rag.auth.service.KbConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文组织服务。
 * <p>
 * 负责 Chat 链路中"查询改写 → 检索召回 → 上下文组装"这一核心阶段的编排。
 * 整合知识库检索与临时文档召回，组装为模型可用的上下文文本与引用元数据。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Service
public class ChatContextService {

    private static final Logger log = LoggerFactory.getLogger(ChatContextService.class);

    private final QueryRewriter queryRewriter;
    private final SafetyChecker safetyChecker;
    private final ContextAssembler contextAssembler;
    private final TempDocumentService tempDocumentService;
    private final KbConfigService kbConfigService;
    private final AiModelFactory aiModelFactory;
    private final VectorStoreRegistry vectorStoreRegistry;
    private final ChatProperties chatProperties;

    public ChatContextService(QueryRewriter queryRewriter,
                              SafetyChecker safetyChecker,
                              ContextAssembler contextAssembler,
                              TempDocumentService tempDocumentService,
                              KbConfigService kbConfigService,
                              AiModelFactory aiModelFactory,
                              VectorStoreRegistry vectorStoreRegistry,
                              ChatProperties chatProperties) {
        this.queryRewriter = queryRewriter;
        this.safetyChecker = safetyChecker;
        this.contextAssembler = contextAssembler;
        this.tempDocumentService = tempDocumentService;
        this.kbConfigService = kbConfigService;
        this.aiModelFactory = aiModelFactory;
        this.vectorStoreRegistry = vectorStoreRegistry;
        this.chatProperties = chatProperties;
    }

    /**
     * 准备上下文：查询改写 → 检索召回（知识库+临时文档）→ 上下文组装。
     *
     * @param query   用户提问
     * @param kbId    知识库 ID（可选）
     * @param session 当前会话
     * @param files   临时上传文件（可选）
     * @return 准备好的上下文（含改写查询、全部文档、组装结果）
     */
    public PreparedContext prepareContext(String query, Long kbId,
                                          ChatSession session, List<MultipartFile> files) {
        List<ChatMessage> history = session.getMessages();

        // ① 输入安全校验
        if (!safetyChecker.checkInput(query)) {
            throw new com.rag.common.exception.RagException("CHAT_UNSAFE",
                    "输入内容包含不安全信息，请修改后重试");
        }

        // ② 查询改写
        String rewrittenQuery = query;
        if (chatProperties.isRewriteEnabled() && history != null && !history.isEmpty()) {
            rewrittenQuery = queryRewriter.rewrite(query, history);
            log.debug("查询改写: {} → {}", query, rewrittenQuery);
        }

        // ③ 检索召回
        List<Document> allDocs = new ArrayList<>();

        // 3a. 知识库召回
        if (kbId != null) {
            List<Document> kbDocs = searchDocuments(rewrittenQuery, kbId);
            if (kbDocs != null) {
                allDocs.addAll(kbDocs);
            }
        }

        // 3b. 临时文档召回
        if (files != null && !files.isEmpty()) {
            String embeddingModelName = resolveEmbeddingModelName(kbId);
            for (MultipartFile file : files) {
                try {
                    Path tempPath = Files.createTempFile("rag-chat-",
                            "-" + file.getOriginalFilename());
                    file.transferTo(tempPath.toFile());
                    tempDocumentService.processTempDocument(
                            session.getSessionId(), tempPath.toFile(), embeddingModelName);
                    Files.deleteIfExists(tempPath);
                } catch (Exception e) {
                    log.warn("临时文档处理失败，跳过: {}", file.getOriginalFilename(), e);
                }
            }
            List<Document> tempDocs = tempDocumentService.recallFromTemp(
                    session.getSessionId(), rewrittenQuery, embeddingModelName, 5);
            if (tempDocs != null) {
                allDocs.addAll(tempDocs);
            }
        }

        // ④ 上下文组装
        ContextAssembler.AssembledContext assembled = contextAssembler.assemble(
                allDocs, chatProperties.getContextWindowTokens());

        return new PreparedContext(rewrittenQuery, allDocs, assembled);
    }

    /**
     * 上下文准备结果。
     */
    public record PreparedContext(
            String rewrittenQuery,
            List<Document> allDocs,
            ContextAssembler.AssembledContext assembledContext) {
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从知识库检索并返回原始 {@link Document} 列表。
     */
    private List<Document> searchDocuments(String query, Long kbId) {
        KbConfigService.KbLoadedConfig loaded = kbConfigService.loadConfigs(kbId);
        EmbeddingConfig embeddingConfig = loaded.embeddingConfig();
        WeaviateCollectionConfig collectionConfig = loaded.collectionConfig();

        EmbeddingModel model = resolveEmbeddingModel(embeddingConfig);
        WeaviateVectorStoreAdapter store = (WeaviateVectorStoreAdapter) vectorStoreRegistry.getWeaviateStore(
                collectionConfig.getClassName(), model, collectionConfig.getVectorDim());

        SearchConfig searchConfig = SearchConfig.builder()
                .searchMode(SearchMode.VECTOR_ONLY)
                .build();
        int topK = 10; // Chat 场景召回 10 条候选，由 ContextAssembler 按 Token 预算截断

        return store.searchByMode(query, topK, null, searchConfig);
    }

    /**
     * 解析向量化模型名：从知识库配置获取，回退默认 bge-m3。
     */
    private String resolveEmbeddingModelName(Long kbId) {
        if (kbId != null) {
            try {
                KbConfigService.KbLoadedConfig loaded = kbConfigService.loadConfigs(kbId);
                return switch (loaded.embeddingConfig().getModelType()) {
                    case BGE_M3 -> "bge-m3";
                    case TONGYI -> "tongyi";
                    case OPENAI -> "openai";
                };
            } catch (Exception e) {
                log.warn("获取 embedding 模型名失败，回退默认 bge-m3", e);
            }
        }
        return "bge-m3";
    }

    /**
     * 桥接：根据 EmbeddingConfig 解析 EmbeddingModel。
     */
    private EmbeddingModel resolveEmbeddingModel(EmbeddingConfig embeddingConfig) {
        String logicalName = switch (embeddingConfig.getModelType()) {
            case BGE_M3 -> "bge-m3";
            case TONGYI -> "tongyi";
            case OPENAI -> "openai";
        };
        if (!aiModelFactory.existsModel(logicalName)) {
            String apiModelName = embeddingConfig.getModelName();
            if (apiModelName != null && aiModelFactory.existsModel(apiModelName)) {
                logicalName = apiModelName;
            }
        }
        return aiModelFactory.getEmbeddingModel(logicalName);
    }
}