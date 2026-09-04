package com.rag.controller;

import com.rag.auth.context.RequestContext;
import com.rag.chat.service.ChatMessageService;
import com.rag.chat.service.ChatSessionService;
import com.rag.common.chat.ChatAnswer;
import com.rag.common.chat.ChatHistoryMessage;
import com.rag.common.chat.ChatStreamEvent;
import com.rag.common.entity.ChatSessionEntity;
import com.rag.common.exception.RagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 问答（Chat）Controller。
 * <p>
 * 提供同步与流式两种问答接口，支持四种对话场景：
 * <ul>
 *   <li>普通对话：仅传 query + sessionId</li>
 *   <li>知识库问答：传 kbId + query</li>
 *   <li>文档问答：传 files + query</li>
 *   <li>知识库+文档混合问答：传 kbId + files + query</li>
 * </ul>
 * sessionId 为空时自动创建新会话；非空时续接已有会话（多轮对话）。
 * </p>
 * <p>
 * v5 新增历史会话管理端点：会话列表（GET /sessions）、单会话历史查询
 * （GET /sessions/{sessionId}/messages，引用反序列化还原可回放）、清空会话
 * （DELETE /sessions/{sessionId}，逻辑删除 + 清 Redis）。均按当前登录用户鉴权与隔离。
 * </p>
 *
 * @author rag-tool
 * @since 5.0
 */
@RestController
@RequestMapping("/api/rag/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatMessageService chatMessageService;
    private final ChatSessionService chatSessionService;

    public ChatController(ChatMessageService chatMessageService,
                          ChatSessionService chatSessionService) {
        this.chatMessageService = chatMessageService;
        this.chatSessionService = chatSessionService;
    }

    /**
     * 同步问答接口。
     * <p>接收 JSON 请求体，返回完整回答与引用列表。</p>
     *
     * @param request 问答请求（含 kbId / query / sessionId / model / stream）
     * @param files   临时上传文件（可选）
     * @return 问答结果
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatAnswer chat(@RequestPart("request") ChatRequest request,
                           @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        log.info("同步问答请求 kbId={} sessionId={} stream={} searchMode={} topK={}",
                request.getKbId(), request.getSessionId(), request.getStream(),
                request.getSearchMode(), request.getTopK());
        return chatMessageService.chat(
                request.getQuery(), request.getKbId(), request.getSessionId(),
                files, request.getModel(),
                request.toSearchConfig(), request.getTopK());
    }

    /**
     * 流式问答接口（SSE）。
     * <p>返回 text/event-stream，逐段推送内容与引用。</p>
     *
     * @param request 问答请求
     * @param files   临时上传文件（可选）
     * @return SSE 事件流
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE,
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Flux<ChatStreamEvent> chatStream(@RequestPart("request") ChatRequest request,
                                            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        log.info("流式问答请求 kbId={} sessionId={} searchMode={} topK={}",
                request.getKbId(), request.getSessionId(),
                request.getSearchMode(), request.getTopK());
        return chatMessageService.chatStream(
                request.getQuery(), request.getKbId(), request.getSessionId(),
                files, request.getModel(),
                request.toSearchConfig(), request.getTopK());
    }

    /**
     * 查询当前登录用户的历史会话列表（v5 新增）。
     * <p>来自 MySQL 权威数据（全量持久化），按最后访问时间倒序；当前上下文仅携带 userId，
     * 故按 userId 维度过滤（多租户场景仍可在会话记录中保留 tenant_id 隔离）。</p>
     *
     * @return 会话头实体列表
     */
    @GetMapping("/sessions")
    public List<ChatSessionEntity> listSessions() {
        Long userId = requireAuth();
        log.info("查询会话列表 userId={}", userId);
        // 无知识库上下文时按用户维度列出该用户全部未删除会话
        return chatSessionService.listSessions(null, userId);
    }

    /**
     * 查询单会话的完整历史消息（v5 新增）。
     * <p>来自 MySQL 全量消息（含引用 JSON 反序列化还原），按时间升序，供前端完整回放。
     * 鉴权：仅会话所属用户可查看。</p>
     *
     * @param sessionId 会话 ID
     * @return 历史消息列表
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatHistoryMessage> listMessages(@PathVariable("sessionId") String sessionId) {
        Long userId = requireAuth();
        // 归属校验：确保会话属于当前登录用户
        ChatSessionEntity header = chatSessionService.getSessionHeader(sessionId);
        if (header == null || !Objects.equals(header.getUserId(), userId)) {
            throw new RagException("SESSION_NOT_FOUND", "会话不存在或无权访问");
        }
        log.info("查询会话历史 sessionId={} userId={}", sessionId, userId);
        return chatSessionService.getHistory(sessionId);
    }

    /**
     * 清空会话（v5 新增）。
     * <p>MySQL 逻辑删除该会话及其全部消息（deleted=1）+ 清除 Redis 会话与索引。
     * 鉴权：仅会话所属用户可删除。</p>
     *
     * @param sessionId 会话 ID
     * @return 操作结果（含是否删除成功）
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, Object> clearSession(@PathVariable("sessionId") String sessionId) {
        Long userId = requireAuth();
        ChatSessionEntity header = chatSessionService.getSessionHeader(sessionId);
        if (header == null || !Objects.equals(header.getUserId(), userId)) {
            throw new RagException("SESSION_NOT_FOUND", "会话不存在或无权访问");
        }
        int affected = chatSessionService.clearSession(sessionId);
        log.info("清空会话 sessionId={} userId={} 影响会话数={}", sessionId, userId, affected);
        return Map.of("code", 200, "msg", affected > 0 ? "会话已清空" : "会话不存在", "affected", affected);
    }

    /**
     * 解析当前登录用户（未登录抛鉴权异常）。
     *
     * @return 用户 ID
     */
    private Long requireAuth() {
        Long userId = RequestContext.currentUserId();
        if (userId == null) {
            throw new RagException("AUTH_REQUIRED", "请先登录或提供 API-Key");
        }
        return userId;
    }
}
