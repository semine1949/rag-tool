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
 *     chat-model: deepseek-v4-flash
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
    private String chatModel = "deepseek-v4-flash";

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

    /** RAG 场景系统提示词模板（未配置时使用内置默认模板，内置模板包含引用标注、禁止编造等强制约束） */
    private String ragSystemPrompt;

    /** 普通对话系统提示词（未配置时不传系统提示词，模型使用自身默认行为） */
    private String chatSystemPrompt;

    /** 相似度阈值（0.0~1.0），低于此值的召回片段过滤，默认值见 application.yml */
    private Double similarityThreshold;

    /** 默认系统提示词（知识库级自定义提示词可覆盖） */
    private String defaultSystemPrompt;

    /**
     * 检索前置分类拦截全局开关。
     * <p>仅当 {@code kbId != null} 的知识库问答/混合问答场景生效：
     * 开启后在检索执行前对用户问题进行通用二分类，判定为通用常识、闲聊、实时信息类问题时
     * 跳过检索、直接走纯对话模式回答；关闭时完全回退原检索流程，零副作用。</p>
     */
    private boolean preQueryFilterEnabled;

    /** 分类用模型名（为空时回退 {@link #chatModel}，全程复用现有对话模型） */
    private String preQueryFilterModel;

    /** 分类指令模板（含 {@code {query}} 占位符，替换为用户问题；未配置时使用内置极简二分类指令） */
    private String preQueryFilterPrompt;

    /** 被拦截的通用回答专属提示词（方案B：未配置时回退普通对话提示词 resolve(false,null)）。强调实时性准确性、控制 Token 成本 */
    private String preQueryFilterAnswerPrompt;

    /** 被拦截的通用回答前置标注（回答开头强制追加，标识未参考知识库内容） */
    private String preQueryFilterTag = "该回答为通用知识，未参考本知识库内容";

    /** 分类调用超时兜底（毫秒），超过视为分类失败并放行 RAG 链路 */
    private long preQueryFilterTimeoutMs = 5000;
}