package com.rag.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 问答（Chat）全局配置属性类。
 * <p>
 * 绑定 YAML 中 {@code rag.chat} 前缀的配置段，作为问答链路的默认参数来源。
 * 配置优先级：请求参数 > 知识库配置（{@code KbChatConfig}）> 此处全局默认。
 * 关闭 {@link #enabled} 时，所有问答接口返回禁用提示，对现有纯检索接口零影响。
 * </p>
 *
 * <h3>配置示例</h3>
 * <pre>{@code
 * rag:
 *   chat:
 *     enabled: true
 *     chat-model: qwen-turbo
 *     rewrite-enabled: true
 *     context-window-tokens: 6000
 *     max-history-rounds: 10
 *     session-ttl-seconds: 3600
 * }</pre>
 *
 * @author rag-tool
 * @since 1.0
 */
@Data
@ConfigurationProperties(prefix = "rag.chat")
public class ChatProperties {

    /** 问答能力全局总开关（关闭时所有问答接口返回禁用提示，对纯检索接口零影响） */
    private boolean enabled = true;

    /** 默认对话模型（spring.ai.platform.models 中的逻辑模型名，须为 CHAT 类别） */
    private String chatModel = "qwen-turbo";

    /** 查询改写开关（多轮上下文补全 + 复杂查询优化，失败自动降级为原始查询） */
    private boolean rewriteEnabled = true;

    /** 查询改写模型（为空时复用 {@link #chatModel}） */
    private String rewriteModel;

    /** 上下文窗口 Token 预算（召回片段按相似度优先级截断） */
    private int contextWindowTokens = 6000;

    /** 多轮对话历史最大保留轮次（一轮 = 一问一答） */
    private int maxHistoryRounds = 10;

    /** 历史上下文 Token 总量上限（与轮次双重裁剪） */
    private int historyMaxTokens = 3000;

    /** 会话过期时间（秒），超时未访问自动清理 */
    private long sessionTtlSeconds = 3600;

    /** 临时上传文档生命周期（秒），随会话清理或超时自动清理 */
    private long tempDocTtlSeconds = 1800;

    /** 临时文档最大数量（单次问答请求） */
    private int tempDocMaxCount = 5;

    /** 输入输出内容安全校验开关 */
    private boolean safetyCheckEnabled = true;

    /** 默认系统提示词（知识库级自定义提示词可覆盖） */
    private String defaultSystemPrompt;
}