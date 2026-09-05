package com.rag.common.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE 流式输出事件结构。
 * <p>
 * 标准化事件类型，供前端按事件类型分阶段渲染：
 * <ul>
 *   <li>{@code content} —— 内容增量片段（逐字/逐段返回）</li>
 *   <li>{@code citations} —— 引用元数据（在内容之前推送）</li>
 *   <li>{@code done} —— 流式结束标志（携带完整回答与元信息）</li>
 *   <li>{@code error} —— 错误事件（携带错误信息）</li>
 * </ul>
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamEvent {

    /** 内容增量事件 */
    public static final String TYPE_CONTENT = "content";

    /** 引用元数据事件 */
    public static final String TYPE_CITATIONS = "citations";

    /** 流式结束事件 */
    public static final String TYPE_DONE = "done";

    /** 错误事件 */
    public static final String TYPE_ERROR = "error";

    /** 会话标识事件（v5 新增：流式开始时推送当前 sessionId，供前端持久化续聊/恢复历史） */
    public static final String TYPE_SESSION = "session";

    /** 事件类型 */
    private String type;

    /** 事件数据（content 时为文本片段，citations 时为 JSON 数组，done 时为完整回答，error 时为错误信息） */
    private String data;

    /**
     * 构造内容增量事件。
     *
     * @param content 内容片段
     * @return 事件实例
     */
    public static ChatStreamEvent content(String content) {
        return ChatStreamEvent.builder().type(TYPE_CONTENT).data(content).build();
    }

    /**
     * 构造引用元数据事件。
     *
     * @param citationsJson 引用列表的 JSON 字符串
     * @return 事件实例
     */
    public static ChatStreamEvent citations(String citationsJson) {
        return ChatStreamEvent.builder().type(TYPE_CITATIONS).data(citationsJson).build();
    }

    /**
     * 构造流式结束事件。
     *
     * @param fullAnswer 完整回答文本
     * @return 事件实例
     */
    public static ChatStreamEvent done(String fullAnswer) {
        return ChatStreamEvent.builder().type(TYPE_DONE).data(fullAnswer).build();
    }

    /**
     * 构造会话标识事件（v5 新增）。
     * <p>
     * 在流式回答起始处推送当前会话 ID。对于服务端新建的会话（客户端未携带 sessionId 续接时），
     * 前端依赖本事件得知本次会话的 ID，从而持久化到 localStorage，实现刷新/重登后恢复历史与续聊。
     * </p>
     *
     * @param sessionId 会话 ID
     * @return 事件实例
     */
    public static ChatStreamEvent session(String sessionId) {
        return ChatStreamEvent.builder().type(TYPE_SESSION).data(sessionId).build();
    }

    /**
     * 构造错误事件。
     *
     * @param errorMessage 错误信息
     * @return 事件实例
     */
    public static ChatStreamEvent error(String errorMessage) {
        return ChatStreamEvent.builder().type(TYPE_ERROR).data(errorMessage).build();
    }
}
