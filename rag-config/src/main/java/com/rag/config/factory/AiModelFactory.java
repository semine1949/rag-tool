package com.rag.config.factory;

import com.rag.common.enums.ChatScene;
import com.rag.common.enums.ModelCategory;
import com.rag.config.model.RerankModel;
import com.rag.config.properties.AiModelProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

/**
 * AI 模型工厂接口 —— 工厂模式核心契约。
 * <p>
 * 统一管理多种类型模型（对话、嵌入、OCR、ASR、重排序等）的创建与缓存，
 * 屏蔽不同协议（OpenAI / Ollama / DashScope）的差异，通过 YAML 配置动态切换协议和模型。
 * 所有模型实例均通过本工厂获取，业务代码禁止直接 {@code new} 模型对象。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
public interface AiModelFactory {

    /**
     * 获取所有已配置的对话模型。
     *
     * @return 对话模型列表（按配置顺序）
     */
    List<ChatModel> getChatModels();

    /**
     * 按名获取对话模型（惰性创建 + 缓存）。
     *
     * @param modelName 逻辑模型名（对应配置中的 key）
     * @return 对话模型实例
     */
    ChatModel getChatModel(String modelName);

    /**
     * 解析指定模型在指定场景下的采样温度。
     * <p>解析优先级：模型级场景温度表 {@code scene-temperatures} 中该场景的取值 &gt; 模型级
     * {@code temperature}（YAML {@code temperature}）。两层均未配置时返回 {@code null}。</p>
     *
     * @param modelName 逻辑模型名
     * @param scene     对话场景
     * @return 解析后的场景温度；模型/场景温度均未配置时为 {@code null}
     */
    Double resolveSceneTemperature(String modelName, ChatScene scene);

    /**
     * 获取指定模型在指定场景下的请求级 {@link ChatOptions}。
     * <p>仅含 temperature（由 {@link #resolveSceneTemperature} 解析），随 {@code Prompt} 携带以
     * 请求级覆盖模型 {@code defaultOptions} 温度，实现"同一模型不同场景不同温度"且不污染单例缓存。</p>
     *
     * @param modelName 逻辑模型名
     * @param scene     对话场景
     * @return 对应协议、仅含 temperature 的 ChatOptions；模型不存在或无场景温度可解析时返回 {@code null}
     */
    ChatOptions resolveSceneChatOptions(String modelName, ChatScene scene);

    /**
     * 按名获取嵌入模型（惰性创建 + 缓存）。
     *
     * @param modelName 逻辑模型名（对应配置中的 key）
     * @return 嵌入模型实例
     */
    EmbeddingModel getEmbeddingModel(String modelName);

    /**
     * 按名获取带自定义用户名头的嵌入模型。
     * <p>仅 OPENAI 协议支持通过 {@code X-Username} 头注入用户名；
     * 其他协议退化为标准嵌入模型并打印 WARN 日志。</p>
     *
     * @param modelName 逻辑模型名（对应配置中的 key）
     * @param username  自定义用户名（为空时退化为标准 {@link #getEmbeddingModel}）
     * @return 嵌入模型实例
     */
    EmbeddingModel getEmbeddingModelWithUsername(String modelName, String username);

    /**
     * 按名获取 OCR 模型（以 ChatModel 实现，走多模态对话端点）。
     *
     * @param modelName 逻辑模型名（对应配置中的 key）
     * @return OCR 模型实例（ChatModel）
     */
    ChatModel getOcrModel(String modelName);

    /**
     * 按名获取 ASR 模型（以 ChatModel 实现，走语音对话端点）。
     *
     * @param modelName 逻辑模型名（对应配置中的 key）
     * @return ASR 模型实例（ChatModel）
     */
    ChatModel getAsrModel(String modelName);

    /**
     * 按名获取 ChatClient（Spring AI 流式客户端封装）。
     *
     * @param modelName 逻辑模型名（对应配置中的 key）
     * @return ChatClient 实例
     */
    ChatClient getChatClient(String modelName);

    /**
     * 按名获取重排序模型。
     * <p>重排序模型不走适配器，由工厂直接按 OPENAI 协议创建 {@link RerankModel}。</p>
     *
     * @param modelName 逻辑模型名（对应配置中的 key）
     * @return 重排序模型实例
     */
    RerankModel getRerankModel(String modelName);

    /**
     * 判断指定模型名是否已在配置中声明。
     *
     * @param modelName 逻辑模型名
     * @return {@code true} 表示存在，{@code false} 表示不存在
     */
    boolean existsModel(String modelName);

    /**
     * 获取指定模型的配置。
     *
     * @param modelName 逻辑模型名
     * @return 模型配置；不存在时返回 {@code null}
     */
    AiModelProperties.ModelConfig getModelConfig(String modelName);

    /**
     * 按类别获取模型名列表。
     *
     * @param category 模型类别
     * @return 属于该类别的逻辑模型名列表
     */
    List<String> getModelNamesByCategory(ModelCategory category);
}
