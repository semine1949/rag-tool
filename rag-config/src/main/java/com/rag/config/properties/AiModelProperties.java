package com.rag.config.properties;

import com.rag.common.enums.ModelCategory;
import com.rag.common.enums.ProtocolType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 模型统一配置属性类。
 * <p>
 * 绑定 YAML 中的 {@code spring.ai.platform} 前缀，以 {@code Map<String, ModelConfig>}
 * 结构统一声明模型清单。key 为逻辑模型名（如 {@code qwen-turbo}），value 为该模型的
 * 类别、协议、凭证与生成参数。通过修改 YAML 的 {@code protocol} 字段即可动态切换协议，
 * 无需改动任何代码。
 * </p>
 *
 * <h3>示例配置</h3>
 * <pre>{@code
 * spring:
 *   ai:
 *     platform:
 *       models:
 *         qwen-turbo:
 *           category: CHAT
 *           protocol: OPENAI
 *           base-url: ${QWEN_BASE_URL}
 *           api-key: ${QWEN_API_KEY}
 *           model-name: qwen-turbo
 * }</pre>
 *
 * @author rag-tool
 * @since 1.0
 */
@Data
@ConfigurationProperties(prefix = "spring.ai.platform")
public class AiModelProperties {

    /** 模型清单，key=逻辑模型名，value=模型配置 */
    private Map<String, ModelConfig> models = new HashMap<>();

    /**
     * 单个模型的配置。
     * <p>涵盖协议路由（category + protocol）、连接凭证（baseUrl + apiKey）、
     * 模型标识（modelName）以及生成参数（temperature / maxTokens 等）。</p>
     */
    @Data
    public static class ModelConfig {

        /** 模型类别（对话 / 嵌入 / OCR / 重排 / ASR / 工作流 / 智能体 / RAG） */
        private ModelCategory category;

        /** 协议类型（OPENAI / OLLAMA / DASHSCOPE） */
        private ProtocolType protocol;

        /** API 基础 URL */
        private String baseUrl;

        /** API 密钥（Bearer Token 鉴权） */
        private String apiKey;

        /** 自定义 Completions PATH（为空时使用协议默认路径） */
        private String completionsPath;

        /** API 调用时使用的模型名（模型 ID） */
        private String modelName;

        /** 采样温度，默认 0.7 */
        private Double temperature = 0.7;

        /** 最大生成 Token 数 */
        private Integer maxTokens;

        /** 核采样（top_p），默认 0.9 */
        private Double topP = 0.9;

        /** 采样候选数（top_k） */
        private Integer topK;

        /** 上下文窗口大小（Ollama 的 num_ctx） */
        private Integer numCtx;

        /** 是否启用流式输出，默认 false */
        private Boolean streamEnabled = false;

        /** 是否启用联网搜索，默认 false */
        private Boolean enableSearch = false;

        /** 是否启用深度思考，默认 false */
        private Boolean enableThinking = false;

        /** 扩展参数（协议特有 / 未来扩展的键值对） */
        private Map<String, Object> extensions = new HashMap<>();
    }
}
