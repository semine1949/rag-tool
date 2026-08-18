package com.rag.service;

import com.rag.auth.context.RequestContext;
import com.rag.auth.service.KbConfigService;
import com.rag.common.chat.ChatAnswer;
import com.rag.common.chat.ChatMessage;
import com.rag.common.chat.ChatSession;
import com.rag.common.chat.ChatStreamEvent;
import com.rag.common.chat.Citation;
import com.rag.common.chat.QueryRewriter;
import com.rag.common.chat.SessionStore;
import com.rag.common.entity.config.EmbeddingConfig;
import com.rag.common.entity.config.SearchConfig;
import com.rag.common.entity.config.WeaviateCollectionConfig;
import com.rag.common.enums.SearchMode;
import com.rag.common.exception.RagException;
import com.rag.config.chat.ChatGenerator;
import com.rag.config.chat.ContextAssembler;
import com.rag.config.chat.SafetyChecker;
import com.rag.config.chat.TempDocumentService;
import com.rag.config.factory.AiModelFactory;
import com.rag.config.factory.VectorStoreRegistry;
import com.rag.config.properties.ChatProperties;
import com.rag.config.vectorstore.WeaviateVectorStoreAdapter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 问答（Chat）编排服务。
 * <p>
 * 全链路流程：开关校验 → 会话管理 → 输入安全校验 → 查询改写 → 检索召回（知识库+临时文档）
 * → 上下文组装 → 回答生成 → 输出安全校验 → 会话持久化 → 返回。
 * 任一节点异常均降级不中断整体请求。
 * </p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 问答全局配置（开关、模型、Token 预算等默认参数） */
    private final ChatProperties chatProperties;
    /** 会话存储（Redis 实现，按租户+用户隔离） */
    private final SessionStore sessionStore;
    /** 查询改写（LLM 实现，多轮上下文补全） */
    private final QueryRewriter queryRewriter;
    /** 安全校验（提示词注入检测 + 敏感词过滤） */
    private final SafetyChecker safetyChecker;
    /** 上下文组装（召回片段 → 编号上下文 + 引用元数据） */
    private final ContextAssembler contextAssembler;
    /** 回答生成（同步/流式 LLM 调用） */
    private final ChatGenerator chatGenerator;
    /** 临时文档服务（解析→向量化→内存召回） */
    private final TempDocumentService tempDocumentService;
    /** 知识库配置服务（用于加载 embedding 配置、租户信息等） */
    private final KbConfigService kbConfigService;
    /** AI 模型工厂（用于创建 EmbeddingModel） */
    private final AiModelFactory aiModelFactory;
    /** 向量存储注册表（用于获取 Weaviate store） */
    private final VectorStoreRegistry vectorStoreRegistry;
    /** JSON 序列化器（用于引用序列化） */
    private final ObjectMapper objectMapper;

    public ChatService(ChatProperties chatProperties,
                       SessionStore sessionStore,
                       QueryRewriter queryRewriter,
                       SafetyChecker safetyChecker,
                       ContextAssembler contextAssembler,
                       ChatGenerator chatGenerator,
                       TempDocumentService tempDocumentService,
                       KbConfigService kbConfigService,
                       AiModelFactory aiModelFactory,
                       VectorStoreRegistry vectorStoreRegistry,
                       ObjectMapper objectMapper) {
        this.chatProperties = chatProperties;
        this.sessionStore = sessionStore;
        this.queryRewriter = queryRewriter;
        this.safetyChecker = safetyChecker;
        this.contextAssembler = contextAssembler;
        this.chatGenerator = chatGenerator;
        this.tempDocumentService = tempDocumentService;
        this.kbConfigService = kbConfigService;
        this.aiModelFactory = aiModelFactory;
        this.vectorStoreRegistry = vectorStoreRegistry;
        this.objectMapper = objectMapper;
    }

    // ==================== Chat 问答链路 ====================

    /**
     * 同步问答编排。
     * <p>
     * 全链路流程：开关校验 → 会话管理 → 输入安全校验 → 查询改写 → 检索召回（知识库+临时文档）
     * → 上下文组装 → 回答生成 → 输出安全校验 → 会话持久化 → 返回。
     * 任一节点异常均降级不中断整体请求。
     * </p>
     *
     * @param query      用户提问
     * @param kbId       知识库 ID（可选，null 时仅从临时文档召回）
     * @param sessionId  会话 ID（可选，null 时新建会话）
     * @param files      临时上传文件（可选）
     * @param modelName  对话模型名（可选，null 时回退全局默认）
     * @return 问答结果
     */
    public ChatAnswer chat(String query, Long kbId, String sessionId,
                           List<MultipartFile> files, String modelName) {
        long startTime = System.currentTimeMillis();
        Long userId = RequestContext.currentUserId();
        // 获取租户 ID：优先从知识库配置推导
        Long tenantId = resolveTenantId(kbId, userId);

        // ① 开关校验
        if (!chatProperties.isEnabled()) {
            throw new RagException("CHAT_DISABLED", "问答功能已关闭");
        }

        // ② 会话管理
        ChatSession session = getOrCreateSession(sessionId, tenantId, userId, kbId);
        List<ChatMessage> history = session.getMessages();

        // ③ 输入安全校验
        if (!safetyChecker.checkInput(query)) {
            throw new RagException("CHAT_UNSAFE", "输入内容包含不安全信息，请修改后重试");
        }

        // ④ 查询改写
        String rewrittenQuery = query;
        if (chatProperties.isRewriteEnabled() && history != null && !history.isEmpty()) {
            rewrittenQuery = queryRewriter.rewrite(query, history);
            log.debug("查询改写: {} → {}", query, rewrittenQuery);
        }

        // ⑤ 检索召回
        List<Document> allDocs = new ArrayList<>();

        // 5a. 知识库召回
        if (kbId != null) {
            List<Document> kbDocs = searchDocuments(rewrittenQuery, kbId);
            if (kbDocs != null) {
                allDocs.addAll(kbDocs);
            }
        }

        // 5b. 临时文档召回
        if (files != null && !files.isEmpty()) {
            String embeddingModelName = resolveEmbeddingModelName(kbId);
            for (MultipartFile file : files) {
                try {
                    Path tempPath = Files.createTempFile("rag-chat-", "-" + file.getOriginalFilename());
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

        // ⑥ 上下文组装
        ContextAssembler.AssembledContext assembled = contextAssembler.assemble(
                allDocs, chatProperties.getContextWindowTokens());
        String contextText = assembled.getContextText();
        List<Citation> citations = assembled.getCitations();

        // ⑦ 回答生成
        String answer = chatGenerator.generate(
                query, contextText, citations, null, modelName);

        // ⑧ 输出安全校验
        if (!safetyChecker.checkOutput(answer)) {
            answer = "回答内容包含不安全信息，已被拦截。";
        }

        // ⑨ 会话持久化
        session.appendMessage(ChatMessage.user(query));
        session.appendMessage(ChatMessage.assistant(answer));
        sessionStore.save(session, chatProperties.getSessionTtlSeconds());

        long elapsed = System.currentTimeMillis() - startTime;
        return ChatAnswer.builder()
                .answer(answer)
                .citations(citations)
                .sessionId(session.getSessionId())
                .model(modelName != null ? modelName : chatProperties.getChatModel())
                .elapsedMs(elapsed)
                .build();
    }

    /**
     * 流式问答编排。
     * <p>
     * 前 6 步与同步问答相同，第 7 步调用 {@link ChatGenerator#generateStream} 返回 SSE 事件流。
     * 事件顺序：citations → content（多次）→ done / error。
     * </p>
     *
     * @param query      用户提问
     * @param kbId       知识库 ID（可选）
     * @param sessionId  会话 ID（可选）
     * @param files      临时上传文件（可选）
     * @param modelName  对话模型名（可选）
     * @return SSE 事件流
     */
    public Flux<ChatStreamEvent> chatStream(String query, Long kbId, String sessionId,
                                            List<MultipartFile> files, String modelName) {
        Long userId = RequestContext.currentUserId();
        Long tenantId = resolveTenantId(kbId, userId);

        // ① 开关校验
        if (!chatProperties.isEnabled()) {
            return Flux.just(ChatStreamEvent.error("问答功能已关闭"));
        }

        // ② 会话管理
        ChatSession session = getOrCreateSession(sessionId, tenantId, userId, kbId);
        List<ChatMessage> history = session.getMessages();

        // ③ 输入安全校验
        if (!safetyChecker.checkInput(query)) {
            return Flux.just(ChatStreamEvent.error("输入内容包含不安全信息，请修改后重试"));
        }

        // ④ 查询改写
        String rewrittenQuery = query;
        if (chatProperties.isRewriteEnabled() && history != null && !history.isEmpty()) {
            rewrittenQuery = queryRewriter.rewrite(query, history);
        }

        // ⑤ 检索召回
        List<Document> allDocs = new ArrayList<>();
        if (kbId != null) {
            List<Document> kbDocs = searchDocuments(rewrittenQuery, kbId);
            if (kbDocs != null) {
                allDocs.addAll(kbDocs);
            }
        }
        // 临时文档召回（复用同步流程逻辑）
        if (files != null && !files.isEmpty()) {
            String embeddingModelName = resolveEmbeddingModelName(kbId);
            for (MultipartFile file : files) {
                try {
                    Path tempPath = Files.createTempFile("rag-chat-", "-" + file.getOriginalFilename());
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

        // ⑥ 上下文组装
        ContextAssembler.AssembledContext assembled = contextAssembler.assemble(
                allDocs, chatProperties.getContextWindowTokens());
        String contextText = assembled.getContextText();
        List<Citation> citations = assembled.getCitations();

        // ⑦ 流式生成（citations → content* → done）
        String citationsJson = serializeCitations(citations);
        final String finalQuery = query;
        final ChatSession finalSession = session;

        return chatGenerator.generateStream(query, contextText, citationsJson, null, modelName)
                .doOnComplete(() -> {
                    // 流式完成后持久化会话（异步，不阻塞 SSE 流）
                    finalSession.appendMessage(ChatMessage.user(finalQuery));
                    // 注意：完整回答由 ChatGenerator 的 done 事件回传，此处仅记录用户消息
                    sessionStore.save(finalSession, chatProperties.getSessionTtlSeconds());
                })
                .doOnError(e -> log.error("流式问答异常 sessionId={}", session.getSessionId(), e));
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从知识库检索并返回原始 {@link Document} 列表（供 Chat 链路内部使用）。
     * <p>使用简化的纯向量检索，不经过 FileProcessResult 转换，直接返回 Document 原始对象。</p>
     *
     * @param query 检索查询
     * @param kbId  知识库 ID
     * @return 检索到的文档列表
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
     * 获取或创建会话。
     *
     * @param sessionId 会话 ID（null 时新建）
     * @param tenantId  租户 ID
     * @param userId    用户 ID
     * @param kbId      知识库 ID
     * @return 会话实例
     */
    private ChatSession getOrCreateSession(String sessionId, Long tenantId, Long userId, Long kbId) {
        if (sessionId != null && !sessionId.isBlank()) {
            ChatSession existing = sessionStore.get(sessionId);
            if (existing != null) {
                return existing;
            }
            log.warn("会话不存在，创建新会话 sessionId={}", sessionId);
        }
        String newSessionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        ChatSession session = ChatSession.builder()
                .sessionId(newSessionId)
                .tenantId(tenantId)
                .userId(userId)
                .kbId(kbId)
                .title(null)
                .createTime(now)
                .lastAccessTime(now)
                .build();
        sessionStore.save(session, chatProperties.getSessionTtlSeconds());
        log.info("新会话创建 sessionId={} tenantId={} userId={}", newSessionId, tenantId, userId);
        return session;
    }

    /**
     * 解析租户 ID：优先从知识库配置推导，回退从 RequestContext 获取。
     */
    private Long resolveTenantId(Long kbId, Long userId) {
        if (kbId != null) {
            try {
                KbConfigService.KbLoadedConfig loaded = kbConfigService.loadConfigs(kbId);
                return loaded.kb().getTenantId();
            } catch (Exception e) {
                log.warn("从知识库获取 tenantId 失败，回退 userId={}", userId, e);
            }
        }
        return userId; // 兜底：userId 即 tenantId（当前简化多租户模型）
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
     * 序列化引用列表为 JSON 字符串（供 SSE 流式事件使用）。
     */
    private String serializeCitations(List<Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(citations);
        } catch (JsonProcessingException e) {
            log.warn("引用序列化失败", e);
            return "[]";
        }
    }

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
}