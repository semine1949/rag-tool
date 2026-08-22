package com.rag.chat.service;

import com.rag.auth.context.RequestContext;
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
import com.rag.common.exception.RagException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;

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
    private final ObjectMapper objectMapper;

    public ChatMessageService(ChatSessionService sessionService,
                              ChatContextService contextService,
                              ChatGenerator chatGenerator,
                              SafetyChecker safetyChecker,
                              SessionStore sessionStore,
                              ChatProperties chatProperties,
                              ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.contextService = contextService;
        this.chatGenerator = chatGenerator;
        this.safetyChecker = safetyChecker;
        this.sessionStore = sessionStore;
        this.chatProperties = chatProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 同步问答编排。
     * <p>
     * 全链路流程：开关校验 → 会话管理 → 上下文准备 → 回答生成 → 输出安全校验 → 会话持久化 → 返回。
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
        Long tenantId = sessionService.resolveTenantId(kbId, userId);

        // ① 开关校验
        if (!chatProperties.isEnabled()) {
            throw new RagException("CHAT_DISABLED", "问答功能已关闭");
        }

        // ② 会话管理
        ChatSession session = sessionService.getOrCreateSession(sessionId, tenantId, userId, kbId);

        // ③ 上下文准备（安全校验 + 改写 + 召回 + 组装）
        PreparedContext prepared = contextService.prepareContext(query, kbId, session, files);
        String contextText = prepared.assembledContext().getContextText();
        List<Citation> citations = prepared.assembledContext().getCitations();

        // ④ 回答生成
        String answer = chatGenerator.generate(
                query, contextText, citations, null, modelName);

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
     * 流式问答编排。
     * <p>
     * 前 3 步与同步问答相同，第 4 步调用 {@link ChatGenerator#generateStream} 返回 SSE 事件流。
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
        Long tenantId = sessionService.resolveTenantId(kbId, userId);

        // ① 开关校验
        if (!chatProperties.isEnabled()) {
            return Flux.just(ChatStreamEvent.error("问答功能已关闭"));
        }

        // ② 会话管理
        ChatSession session = sessionService.getOrCreateSession(sessionId, tenantId, userId, kbId);

        // ③ 上下文准备
        PreparedContext prepared;
        try {
            prepared = contextService.prepareContext(query, kbId, session, files);
        } catch (RagException e) {
            return Flux.just(ChatStreamEvent.error(e.getMessage()));
        }

        String contextText = prepared.assembledContext().getContextText();
        List<Citation> citations = prepared.assembledContext().getCitations();

        // ④ 流式生成
        String citationsJson = serializeCitations(citations);
        final String finalQuery = query;
        final ChatSession finalSession = session;

        return chatGenerator.generateStream(query, contextText, citationsJson, null, modelName)
                .doOnComplete(() -> {
                    // 流式完成后持久化会话（异步，不阻塞 SSE 流）
                    finalSession.appendMessage(ChatMessage.user(finalQuery));
                    sessionStore.save(finalSession, chatProperties.getSessionTtlSeconds());
                })
                .doOnError(e -> log.error("流式问答异常 sessionId={}", session.getSessionId(), e));
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
}