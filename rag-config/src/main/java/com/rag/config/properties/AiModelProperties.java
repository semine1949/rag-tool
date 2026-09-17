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

        /** 采样温度，默认 0.7（作为"未显式场景温度"时的模型级默认兜底） */
        private Double temperature = 0.7;

        /**
         * 场景化温度表：按 {@link com.rag.common.enums.ChatScene} 场景为同一模型配置不同温度。
         * <p>key 为场景标识（如 {@code classify} / {@code rewrite} / {@code rag-qa} / {@code chat-qa}，
         * 忽略大小写与下划线/连字符），value 为该场景下的采样温度。
         * 请求级覆盖模型 {@code defaultOptions} 温度，不污染缓存实例；某场景未配置时回退 {@link #temperature}。</p>
         * <pre>{@code
         * qwen-turbo:
         *   temperature: 0.7
         *   scene-temperatures:
         *     classify: 0.0
         *     rewrite: 0.0
         *     rag-qa: 0.2
         *     chat-qa: 0.6
         * }</pre>
         */
        private Map<String, Double> sceneTemperatures = new HashMap<>();

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

        /**
         * 从 {@link #extensions} 中安全读取字符串型扩展参数。
         * <p>YAML 中的数字/布尔值经绑定后可能为 String / Number / Boolean，
         * 此处统一按字符串返回，避免调用方各自做类型判断。</p>
         *
         * @param key          扩展参数键（如 {@code dim} / {@code timeout-ms}）
         * @param defaultValue 键不存在或值为空时返回的默认值
         * @return 字符串值，缺失时返回 defaultValue
         */
        public String getExtensionString(String key, String defaultValue) {
            Object v = extensions == null ? null : extensions.get(key);
            if (v == null) {
                return defaultValue;
            }
            String s = String.valueOf(v);
            return s.isBlank() ? defaultValue : s;
        }

        /**
         * 从 {@link #extensions} 中安全读取整型扩展参数。
         * <p>支持 Number 与数字字符串两种形态；解析失败或键缺失时返回默认值，
         * 不会抛出异常（配置错误仅降级，不影响主链路）。</p>
         *
         * @param key          扩展参数键（如 {@code dim} / {@code max-tokens}）
         * @param defaultValue 键不存在或解析失败时返回的默认值
         * @return 整型值，缺失/非法时返回 defaultValue
         */
        public Integer getExtensionInt(String key, Integer defaultValue) {
            Object v = extensions == null ? null : extensions.get(key);
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v instanceof String s && !s.isBlank()) {
                try {
                    return Integer.valueOf(s.trim());
                } catch (NumberFormatException ignored) {
                    // 配置值非法：降级为默认值，交由上层日志观测
                    return defaultValue;
                }
            }
            return defaultValue;
        }

        /**
         * 从 {@link #extensions} 中安全读取长整型扩展参数（用于 timeout-ms 等毫秒值）。
         *
         * @param key          扩展参数键
         * @param defaultValue 键不存在或解析失败时返回的默认值
         * @return 长整型值，缺失/非法时返回 defaultValue
         */
        public Long getExtensionLong(String key, Long defaultValue) {
            Object v = extensions == null ? null : extensions.get(key);
            if (v instanceof Number n) {
                return n.longValue();
            }
            if (v instanceof String s && !s.isBlank()) {
                try {
                    return Long.valueOf(s.trim());
                } catch (NumberFormatException ignored) {
                    return defaultValue;
                }
            }
            return defaultValue;
        }
    }
}
