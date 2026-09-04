package com.rag.common.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 会话历史消息 DTO（v5 新增）。
 * <p>
 * 用于「按会话 ID 查询历史」接口的响应体：将 MySQL {@code chat_message} 全量落库记录
 * 还原为可供前端回放的结构。与 {@link ChatMessage}（用于大模型上下文、Redis 内存态）
 * 区分：本结构额外携带引用溯源列表 {@link #citations}（从数据库 JSON 列反序列化还原），
 * 保证引用信息在重启/刷新后可完整回放。
 * </p>
 *
 * @author rag-tool
 * @since 5.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatHistoryMessage {

    /** 消息角色：USER / ASSISTANT / SYSTEM */
    private String role;

    /** 消息文本内容 */
    private String content;

    /** 引用溯源列表（assistant 消息携带，从 JSON 列还原；USER 消息为空列表） */
    @Builder.Default
    private List<Citation> citations = List.of();

    /** 消息时间戳（毫秒，数据库 create_time 换算） */
    private long timestamp;

    /** 生成该消息使用的对话模型名 */
    private String modelName;

    /** 本次回答耗时（毫秒，可空） */
    private Long responseTime;
}
