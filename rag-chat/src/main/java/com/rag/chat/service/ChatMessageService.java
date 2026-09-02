package com.rag.chat.service;

import com.rag.auth.context.RequestContext;
import com.rag.chat.assembler.PromptTemplateResolver;
import com.rag.chat.classifier.GenericQueryClassifier;
import com.rag.chat.config.ChatProperties;
import com.rag.chat.generator.ChatGenerator;
import com.rag.chat.safety.SafetyChecker;
import com.rag.chat.service.ChatContextService.PreparedContext;
import com.rag.common.chat.ChatAnswer;
import com.rag.common.chat.ChatMessage;
import com.rag.common.chat.ChatSession;
import com.rag.common.chat.ChatStreamEvent;
import com.rag.common.chat.Citation;
import com.rag.common.chat.SessionStore;
import com.rag.common.entity.KbChatConfig;
import com.rag.common.entity.config.SearchConfig;
import com.rag.common.exception.RagException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 消息收发编排服务。
 * <p>
 * Chat 链路顶层编排入口，协调 {@link ChatSessionService}（会话生命周期）、
 * {@link ChatContextService}（上下文准备）与 {@link ChatGenerator}（回答生成），
 * 完成完整的问答流程并持久化会话。
 * 支持同步与流式两种响应模式，任一节点异常均降级不中断整体请求。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Service
public class ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageService.class);

    private final ChatSessionService sessionService;
    private final ChatContextService contextService;
    private final ChatGenerator chatGenerator;
    private final SafetyChecker safetyChecker;
    private final SessionStore sessionStore;
    private final ChatProperties chatProperties;
    private final PromptTemplateResolver promptResolver;
    private final GenericQueryClassifier queryClassifier;
    private final ObjectMapper objectMapper;

    public ChatMessageService(ChatSessionService sessionService,
                              ChatContextService contextService,
                              ChatGenerator chatGenerator,
                              SafetyChecker safetyChecker,
                              SessionStore sessionStore,
                              ChatProperties chatProperties,
                              PromptTemplateResolver promptResolver,
                              GenericQueryClassifier queryClassifier,
                              ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.contextService = contextService;
        this.chatGenerator = chatGenerator;
        this.safetyChecker = safetyChecker;
        this.sessionStore = sessionStore;
        this.chatProperties = chatProperties;
        this.promptResolver = promptResolver;
        this.queryClassifier = queryClassifier;
        this.objectMapper = objectMapper;
    }

    /**
     * 同步问答编排。
     * <p>
     * 全链路流程：开关校验 → 会话管理 → 上下文准备 → 回答生成 → 输出安全校验 → 会话持久化 → 返回。
     * 任一节点异常均降级不中断整体请求。
     * </p>
     *
     * @param query        用户提问
     * @param kbId         知识库 ID（可选，null 时仅从临时文档召回）
     * @param sessionId    会话 ID（可选，null 时新建会话）
     * @param files        临时上传文件（可选）
     * @param modelName    对话模型名（可选，null 时回退全局默认）
     * @param searchConfig 检索配置（可选，null 时知识库召回回退纯向量）
     * @param topK         知识库召回条数（可选，null 时默认 10）
     * @return 问答结果
     */
    public ChatAnswer chat(String query, Long kbId, String sessionId,
                           List<MultipartFile> files, String modelName,
                           SearchConfig searchConfig, Integer topK) {
        long startTime = System.currentTimeMillis();
        Long userId = RequestContext.currentUserId();
        Long tenantId = sessionService.resolveTenantId(kbId, userId);

        // ① 开关校验
        if (!chatProperties.isEnabled()) {
            throw new RagException("CHAT_DISABLED", "问答功能已关闭");
        }

        // ② 会话管理
        ChatSession session = sessionService.getOrCreateSession(sessionId, tenantId, userId, kbId);

        // ②.5 检索前置分类拦截（仅知识库问答/混合问答场景生效：preQueryFilterEnabled && kbId != null）
        // 判定为通用常识、闲聊、实时信息类问题时，跳过检索直接走纯对话模式回答。
        boolean isRag = kbId != null || (files != null && !files.isEmpty());
        if (shouldInterceptGenericQuery(kbId, query)) {
            return handleGenericQuerySync(query, session, modelName, startTime);
        }

        // ③ 上下文准备（安全校验 + 改写 + 召回 + 组装）
        PreparedContext prepared = contextService.prepareContext(query, kbId, session, files, searchConfig, topK);
        String contextText = prepared.assembledContext().getContextText();
        List<Citation> citations = prepared.assembledContext().getCitations();
        // 打印输入给大模型的资料库上下文（RAG 溯源用）
        logContext("chat", query, contextText, prepared.rewrittenQuery());

        // ③.5 解析系统提示词（RAG 场景加载约束模板，普通对话加载通用模板）
        String systemPrompt = promptResolver.resolve(isRag, null);

        // ④ 回答生成
        String answer;
        if (isRag && (contextText == null || contextText.isBlank())) {
            // RAG 场景下无可用上下文时，直接返回无答案，不调用 LLM
            answer = "根据现有资料无法回答该问题。";
        } else {
            answer = chatGenerator.generate(
                    query, contextText, citations, systemPrompt, modelName);
        }

        // ⑤ 输出安全校验
        if (!safetyChecker.checkOutput(answer)) {
            answer = "回答内容包含不安全信息，已被拦截。";
        }

        // ⑥ 会话持久化
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
     * 判断当前请求是否需要触发"检索前置分类拦截"。
     * <p>
     * 触发条件：全局开关 {@code preQueryFilterEnabled} 开启，且为知识库问答/混合问答场景（{@code kbId != null}）。
     * 普通对话（{@code kbId==null} 且无文件）与纯文档问答（{@code kbId==null} 有文件）不触发。
     * 触发前先执行一次输入安全校验（checkInput），满足"输入安全校验完成后"的时序要求。
     * </p>
     *
     * @param kbId  知识库 ID
     * @param query 用户提问
     * @return {@code true} 表示命中通用问题、需拦截走纯对话；{@code false} 表示放行原 RAG 链路
     */
    private boolean shouldInterceptGenericQuery(Long kbId, String query) {
        // 全局开关关闭或非知识库场景，不触发拦截
        if (!chatProperties.isPreQueryFilterEnabled() || kbId == null) {
            return false;
        }
        // 先验输入安全校验（prepareContext 内幂等二次校验无害）
        if (!safetyChecker.checkInput(query)) {
            log.warn("[PRE-QUERY-FILTER] 输入未通过安全校验，放行 RAG 链路 query={}", query);
            return false;
        }
        // 执行通用二分类：true 拦截走纯对话，false 放行进 RAG
        return queryClassifier.classify(query);
    }

    /**
     * 处理被拦截的通用问题（同步模式）。
     * <p>
     * 跳过知识库检索，复用通用对话提示词（方案B：优先 {@code preQueryFilterAnswerPrompt}，
     * 回退普通对话提示词）生成纯对话回答，并在回答开头强制前置统一标注。
     * 同样经过输出安全校验与会话持久化，引用列表为空。
     * </p>
     *
     * @param query     用户提问
     * @param kbId      知识库 ID（用于会话上下文）
     * @param session   当前会话
     * @param modelName 对话模型名
     * @param startTime 链路起始时间
     * @param isRag     是否 RAG 场景（用于提示词解析，拦截分支固定走普通对话分支）
     * @return 问答结果（含前置标注、空引用）
     */
    private ChatAnswer handleGenericQuerySync(String query, ChatSession session,
                                              String modelName, long startTime) {
        log.info("[PRE-QUERY-FILTER] 拦截通用问题，跳过知识库检索 query={}", query);
        // 空引用：未检索知识库，无引用来源
        List<Citation> citations = List.of();
        // 解析拦截回答专属提示词（方案B）：优先 preQueryFilterAnswerPrompt，回退普通对话提示词
        String systemPrompt = resolveInterceptPrompt();
        String rawAnswer = chatGenerator.generate(query, null, citations, systemPrompt, modelName);

        // 回答开头强制前置统一标注
        String tag = chatProperties.getPreQueryFilterTag();
        String answer = (tag != null && !tag.isBlank()) ? tag + "\n" + rawAnswer : rawAnswer;

        // 输出安全校验
        if (!safetyChecker.checkOutput(answer)) {
            answer = "回答内容包含不安全信息，已被拦截。";
        }

        // 会话持久化
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
     * 解析拦截回答专属提示词。
     * <p>方案B：优先使用 {@code preQueryFilterAnswerPrompt}（强调实时性准确性、控制 Token 成本），
     * 未配置时回退普通对话提示词 {@code promptResolver.resolve(false, null)}。</p>
     *
     * @return 拦截回答系统提示词（可能为 null，回退模型默认行为）
     */
    private String resolveInterceptPrompt() {
        String answerPrompt = chatProperties.getPreQueryFilterAnswerPrompt();
        if (answerPrompt != null && !answerPrompt.isBlank()) {
            return answerPrompt;
        }
        return promptResolver.resolve(false, null);
    }

    /**
     * 处理被拦截的通用问题（流式模式）。
     * <p>
     * 跳过知识库检索，复用通用对话提示词（方案B）生成纯对话回答。
     * 事件顺序：citations(空) → content(前置标注) → content(流式回答) → done。
     * 通过 {@code Flux.concatMap} 在首个内容片段前插入统一前置标注，保证"开头即标注"，
     * 与同步模式格式统一；done 事件携带含前置标注的完整回答，用于流式完成后会话持久化。
     * </p>
     *
     * @param query     用户提问
     * @param session   当前会话
     * @param modelName 对话模型名
     * @return SSE 事件流
     */
    private Flux<ChatStreamEvent> handleGenericQueryStream(String query, ChatSession session,
                                                           String modelName) {
        log.info("[PRE-QUERY-FILTER] 拦截通用问题，跳过知识库检索 query={}", query);
        String systemPrompt = resolveInterceptPrompt();
        String tag = chatProperties.getPreQueryFilterTag();
        String tagPrefix = (tag != null && !tag.isBlank()) ? tag + "\n" : "";
        // 标记前置标注是否已插入（仅首个 content 片段前插入一次）
        AtomicBoolean tagEmitted = new AtomicBoolean(false);
        final String finalQuery = query;
        final ChatSession finalSession = session;
        // 捕获 done 事件中的完整回答（含前置标注），用于流式完成后持久化助手消息
        AtomicReference<String> fullAnswer = new AtomicReference<>();

        return chatGenerator.generateStream(query, null, "[]", systemPrompt, modelName)
                .concatMap(event -> {
                    // 在首个内容片段前插入前置标注
                    if (ChatStreamEvent.TYPE_CONTENT.equals(event.getType())
                            && tagEmitted.compareAndSet(false, true)) {
                        return Flux.just(ChatStreamEvent.content(tagPrefix), event);
                    }
                    // done 事件携带含前置标注的完整回答，供持久化与前端收尾
                    if (ChatStreamEvent.TYPE_DONE.equals(event.getType())) {
                        return Flux.just(ChatStreamEvent.done(tagPrefix + event.getData()));
                    }
                    return Flux.just(event);
                })
                .doOnNext(event -> {
                    // 捕获 done 事件中的完整回答（已含前置标注）
                    if (ChatStreamEvent.TYPE_DONE.equals(event.getType())) {
                        fullAnswer.set(event.getData());
                    }
                })
                .doOnComplete(() -> {
                    // 持久化用户消息
                    finalSession.appendMessage(ChatMessage.user(finalQuery));
                    // 持久化助手消息（仅当流式完整结束时，异常中断不写入不完整消息）
                    String answer = fullAnswer.get();
                    if (answer != null && !answer.isBlank()) {
                        finalSession.appendMessage(ChatMessage.assistant(answer));
                    }
                    sessionStore.save(finalSession, chatProperties.getSessionTtlSeconds());
                })
                .doOnError(e -> {
                    // 流式异常时不写入不完整消息，保留上一轮完整会话状态
                    log.error("流式通用回答异常，会话状态未变更 sessionId={}", session.getSessionId(), e);
                });
    }

    /**
     * 流式问答编排。
     * <p>
     * 前 3 步与同步问答相同，第 4 步调用 {@link ChatGenerator#generateStream} 返回 SSE 事件流。
     * 事件顺序：citations → content（多次）→ done / error。
     * </p>
     *
     * @param query        用户提问
     * @param kbId         知识库 ID（可选）
     * @param sessionId    会话 ID（可选）
     * @param files        临时上传文件（可选）
     * @param modelName    对话模型名（可选）
     * @param searchConfig 检索配置（可选，null 时知识库召回回退纯向量）
     * @param topK         知识库召回条数（可选，null 时默认 10）
     * @return SSE 事件流
     */
    public Flux<ChatStreamEvent> chatStream(String query, Long kbId, String sessionId,
                                            List<MultipartFile> files, String modelName,
                                            SearchConfig searchConfig, Integer topK) {
        Long userId = RequestContext.currentUserId();
        Long tenantId = sessionService.resolveTenantId(kbId, userId);

        // ① 开关校验
        if (!chatProperties.isEnabled()) {
            return Flux.just(ChatStreamEvent.error("问答功能已关闭"));
        }

        // ② 会话管理
        ChatSession session = sessionService.getOrCreateSession(sessionId, tenantId, userId, kbId);

        // ②.5 检索前置分类拦截（仅知识库问答/混合问答场景生效：preQueryFilterEnabled && kbId != null）
        boolean isRag = kbId != null || (files != null && !files.isEmpty());
        if (shouldInterceptGenericQuery(kbId, query)) {
            return handleGenericQueryStream(query, session, modelName);
        }

        // ③ 上下文准备
        PreparedContext prepared;
        try {
            prepared = contextService.prepareContext(query, kbId, session, files, searchConfig, topK);
        } catch (RagException e) {
            return Flux.just(ChatStreamEvent.error(e.getMessage()));
        }

        String contextText = prepared.assembledContext().getContextText();
        List<Citation> citations = prepared.assembledContext().getCitations();
        // 打印输入给大模型的资料库上下文（RAG 溯源用）
        logContext("chatStream", query, contextText, prepared.rewrittenQuery());

        // ③.5 解析系统提示词（RAG 场景加载约束模板，普通对话加载通用模板）
        String systemPrompt = promptResolver.resolve(isRag, null);

        // ④ 流式生成（RAG 场景无可用上下文时，直接返回无答案，不调用 LLM）
        if (isRag && (contextText == null || contextText.isBlank())) {
            final ChatSession finalSession = session;
            finalSession.appendMessage(ChatMessage.user(query));
            finalSession.appendMessage(ChatMessage.assistant("根据现有资料无法回答该问题。"));
            sessionStore.save(finalSession, chatProperties.getSessionTtlSeconds());
            return Flux.concat(
                    Flux.just(ChatStreamEvent.citations("[]")),
                    Flux.just(ChatStreamEvent.content("根据现有资料无法回答该问题。")),
                    Flux.just(ChatStreamEvent.done("根据现有资料无法回答该问题。")));
        }

        String citationsJson = serializeCitations(citations);
        final String finalQuery = query;
        final ChatSession finalSession = session;
        // 捕获 done 事件中的完整回答，用于流式完成后持久化助手消息
        AtomicReference<String> fullAnswer = new AtomicReference<>();

        return chatGenerator.generateStream(query, contextText, citationsJson, systemPrompt, modelName)
                .doOnNext(event -> {
                    // 捕获 done 事件中的完整回答
                    if (ChatStreamEvent.TYPE_DONE.equals(event.getType())) {
                        fullAnswer.set(event.getData());
                    }
                })
                .doOnComplete(() -> {
                    // 持久化用户消息
                    finalSession.appendMessage(ChatMessage.user(finalQuery));
                    // 持久化助手消息（仅当流式完整结束时，异常中断不写入不完整消息）
                    String answer = fullAnswer.get();
                    if (answer != null && !answer.isBlank()) {
                        finalSession.appendMessage(ChatMessage.assistant(answer));
                    }
                    sessionStore.save(finalSession, chatProperties.getSessionTtlSeconds());
                })
                .doOnError(e -> {
                    // 流式异常时不写入不完整消息，保留上一轮完整会话状态
                    log.error("流式问答异常，会话状态未变更 sessionId={}", session.getSessionId(), e);
                });
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
     * 打印输入给大语言模型的资料库上下文（RAG 溯源排查用）。
     * <p>
     * 以多行日志输出：调用链路标识、改写后查询、召回片段数、完整 contextText。
     * 使用 info 级别，便于在生产环境直接检索定位。
     * </p>
     *
     * @param channel      调用链路标识（chat / chatStream）
     * @param query        用户原始提问
     * @param contextText  组装后输入给大模型的资料库内容（可能为空）
     * @param rewritten    改写后的查询（可能为空）
     */
    private void logContext(String channel, String query, String contextText, String rewritten) {
        if (contextText == null || contextText.isBlank()) {
            log.info("[RAG-CONTEXT][{}] 无可用的资料库上下文，query={}, rewritten={}", channel, query, rewritten);
            return;
        }
        log.info("[RAG-CONTEXT][{}] 输入给大模型的资料库内容开始 <<< query={}, rewritten={}, chars={}",
                channel, query, rewritten, contextText.length());
        // 逐行打印，避免单条日志过长被截断
        for (String line : contextText.split("\\R")) {
            log.info("[RAG-CONTEXT]    {}", line);
        }
        log.info("[RAG-CONTEXT][{}] 输入给大模型的资料库内容结束 >>>", channel);
    }
}