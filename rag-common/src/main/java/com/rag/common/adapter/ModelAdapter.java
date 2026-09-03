package com.rag.common.adapter;

import com.rag.common.enums.ModelCategory;
import com.rag.common.enums.ProtocolType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * 模型适配器接口 —— 适配器模式核心契约。
 * <p>
 * 屏蔽不同协议（OpenAI / Ollama / DashScope）在模型创建上的差异，
 * 将统一的模型创建请求适配为各协议官方 SDK 的构建逻辑。
 * 工厂通过遍历所有适配器并调用 {@link #supports(ModelCategory, ProtocolType)}
 * 完成协议路由（策略模式）。
 * </p>
 *
 * <h3>设计约定</h3>
 * <ul>
 *   <li>{@link #getModelCategory()} 返回 {@code null} 表示不限类别（通用适配器）。</li>
 *   <li>适配器实现类通过 {@code @Component} 注册，由 Spring 自动注入 {@code List<ModelAdapter>}。</li>
 *   <li>模型实例由工厂统一缓存，适配器只负责"如何创建"，不负责"是否缓存"。</li>
 * </ul>
 *
 * @author rag-tool
 * @since 1.0
 */
public interface ModelAdapter {

    /**
     * 返回本适配器支持的协议类型。
     *
     * @return 协议类型枚举（如 {@link ProtocolType#OPENAI}）
     */
    ProtocolType getProtocolType();

    /**
     * 返回本适配器支持的模型类别。
     * <p>返回 {@code null} 表示不限类别，可处理所有类别（通用适配器）。</p>
     *
     * @return 支持的模型类别；{@code null} 表示不限类别
     */
    ModelCategory getModelCategory();

    /**
     * 创建对话模型（ChatModel）。
     *
     * @param modelName       模型名称（API 调用时使用的模型 ID）
     * @param baseUrl         API 基础 URL
     * @param apiKey          API 密钥
     * @param completionsPath 自定义 Completions PATH（可为空）
     * @param temperature     采样温度（模型级默认温度，来自 YAML 配置；为 null 时适配器使用自身默认）
     * @param maxTokens       最大生成 Token 数（模型级默认，来自 YAML 配置；为 null 时适配器使用自身默认）
     * @return 创建好的对话模型实例
     */
    ChatModel createChatModel(String modelName, String baseUrl, String apiKey,
                              String completionsPath, Double temperature, Integer maxTokens);

    /**
     * 构造"仅覆盖温度"的请求级场景 Options。
     * <p>
     * 场景化温度通过请求级 {@link ChatOptions} 随 {@code Prompt} 携带，
     * 与模型 {@code defaultOptions} 合并时请求级优先，从而在不污染模型单例缓存的前提下
     * 让同一模型在不同场景使用不同温度。各协议适配器返回对应协议的 Options 实现。
     * 请求级 Options 仅携带温度，不含模型名，避免与已解析的模型名冲突。
     * </p>
     *
     * @param temperature 场景解析后的采样温度（非空）
     * @return 对应协议的、仅含 temperature 的 ChatOptions
     */
    ChatOptions createSceneChatOptions(Double temperature);

    /**
     * 创建嵌入模型（EmbeddingModel）。
     *
     * @param modelName       模型名称（API 调用时使用的模型 ID）
     * @param baseUrl         API 基础 URL
     * @param apiKey          API 密钥
     * @param completionsPath 自定义 Completions PATH（可为空）
     * @return 创建好的嵌入模型实例
     */
    EmbeddingModel createEmbeddingModel(String modelName, String baseUrl, String apiKey, String completionsPath);

    /**
     * 判断本适配器是否支持指定的模型类别与协议组合。
     * <p>策略模式的路由判定方法：工厂遍历适配器列表，命中首个返回 {@code true} 的适配器。</p>
     *
     * @param category 模型类别
     * @param protocol 协议类型
     * @return {@code true} 表示本适配器支持该组合，{@code false} 表示不支持
     */
    boolean supports(ModelCategory category, ProtocolType protocol);
}
