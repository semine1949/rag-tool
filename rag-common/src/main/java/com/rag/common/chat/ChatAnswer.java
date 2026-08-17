package com.rag.common.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 同步问答响应体。
 * <p>
 * 封装一次问答请求的完整结果：回答文本、引用列表、会话 ID（用于多轮续接）。
 * 流式问答不使用此结构，改为通过 {@link ChatStreamEvent} 逐事件推送。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatAnswer {

    /** 生成的回答文本 */
    private String answer;

    /** 引用溯源列表（与回答中的 [n] 一一对应） */
    private List<Citation> citations;

    /** 会话 ID（用于多轮续接，首轮生成后返回） */
    private String sessionId;

    /** 本次问答使用的对话模型名 */
    private String model;

    /** 本次问答总耗时（毫秒） */
    private long elapsedMs;
}
