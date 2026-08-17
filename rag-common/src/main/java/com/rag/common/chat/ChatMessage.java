package com.rag.common.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单条对话消息结构。
 * <p>
 * 用于会话历史存储与模型调用入参，记录消息角色（USER/ASSISTANT/SYSTEM）、内容与时间戳。
 * 通过静态工厂方法 {@link #user(String)} / {@link #assistant(String)} / {@link #system(String)}
 * 快速构造标准消息，避免业务层重复手工填充时间戳。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /** 用户消息角色 */
    public static final String ROLE_USER = "USER";

    /** 助手消息角色 */
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    /** 系统消息角色 */
    public static final String ROLE_SYSTEM = "SYSTEM";

    /** 消息角色：USER / ASSISTANT / SYSTEM */
    private String role;

    /** 消息文本内容 */
    private String content;

    /** 消息时间戳（毫秒），用于按序展示与过期清理 */
    private long timestamp;

    /**
     * 构造用户消息，自动填充当前时间戳。
     *
     * @param content 消息内容
     * @return 用户消息实例
     */
    public static ChatMessage user(String content) {
        return ChatMessage.builder()
                .role(ROLE_USER)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 构造助手消息，自动填充当前时间戳。
     *
     * @param content 消息内容
     * @return 助手消息实例
     */
    public static ChatMessage assistant(String content) {
        return ChatMessage.builder()
                .role(ROLE_ASSISTANT)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 构造系统消息，自动填充当前时间戳。
     *
     * @param content 消息内容
     * @return 系统消息实例
     */
    public static ChatMessage system(String content) {
        return ChatMessage.builder()
                .role(ROLE_SYSTEM)
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
