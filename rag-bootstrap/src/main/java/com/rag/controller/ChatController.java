package com.rag.controller;

import com.rag.chat.service.ChatMessageService;
import com.rag.common.chat.ChatAnswer;
import com.rag.common.chat.ChatStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;

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
 *
 * @author rag-tool
 * @since 1.0
 */
@RestController
@RequestMapping("/api/rag/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatMessageService chatMessageService;

    public ChatController(ChatMessageService chatMessageService) {
        this.chatMessageService = chatMessageService;
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
        log.info("同步问答请求 kbId={} sessionId={} stream={}", request.getKbId(), request.getSessionId(), request.getStream());
        return chatMessageService.chat(
                request.getQuery(), request.getKbId(), request.getSessionId(),
                files, request.getModel());
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
        log.info("流式问答请求 kbId={} sessionId={}", request.getKbId(), request.getSessionId());
        return chatMessageService.chatStream(
                request.getQuery(), request.getKbId(), request.getSessionId(),
                files, request.getModel());
    }
}