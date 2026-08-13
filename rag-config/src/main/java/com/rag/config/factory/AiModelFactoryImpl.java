package com.rag.config.factory;

import com.rag.common.adapter.ModelAdapter;
import com.rag.common.client.OpenAiClient;
import com.rag.common.enums.ModelCategory;
import com.rag.common.enums.ProtocolType;
import com.rag.config.model.OpenAiRerankModel;
import com.rag.config.model.RerankModel;
import com.rag.config.properties.AiModelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 模型工厂实现类 —— 工厂模式 + 适配器模式 + 策略模式。
 * <p>
 * 统一负责所有 AI 模型实例的创建与缓存，核心职责：
 * <ol>
 *   <li><b>缓存</b>：5 个 {@link ConcurrentHashMap} 按模型名缓存实例，避免重复创建模型和网络连接。</li>
 *   <li><b>协议路由</b>：{@link #findAdapter(AiModelProperties.ModelConfig)} 遍历 {@code List<ModelAdapter>}，
 *       通过 {@code supports(category, protocol)} 策略方法找到首个匹配的适配器。</li>
 *   <li><b>惰性创建</b>：所有 {@code getXxxModel} 方法使用 {@code computeIfAbsent} 惰性创建。</li>
 * </ol>
 * </p>
 *
 * <h3>特殊处理</h3>
 * <ul>
 *   <li>重排序模型不走适配器，直接按 OPENAI 协议创建 {@link OpenAiRerankModel}。</li>
 *   <li>带用户名的嵌入模型仅 OPENAI 协议支持（X-Username 头），其他协议降级。</li>
 * </ul>
 *
 * @author rag-tool
 * @since 1.0
 */
@Component
public class AiModelFactoryImpl implements AiModelFactory {

    private static final Logger log = LoggerFactory.getLogger(AiModelFactoryImpl.class);

    // ==================== 缓存字段 ====================

    /** 对话模型缓存，key=模型名 */
    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();

    /** 嵌入模型缓存，key=模型名 */
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();

    /** 带用户名嵌入模型缓存，key=模型名@用户名 */
    private final Map<String, EmbeddingModel> embeddingModelWithUsernameCache = new ConcurrentHashMap<>();

    /** ChatClient 缓存，key=模型名 */
    private final Map<String, ChatClient> chatClientCache = new ConcurrentHashMap<>();

    /** 重排序模型缓存，key=模型名 */
    private final Map<String, RerankModel> rerankModelCache = new ConcurrentHashMap<>();

    // ==================== 依赖注入 ====================

    /** 模型配置属性 */
    private final AiModelProperties aiModelProperties;

    /** 所有协议适配器（Spring 自动注入 List<ModelAdapter>） */
    private final List<ModelAdapter> modelAdapters;

    /** 统一 OpenAI 兼容客户端（供 Rerank 复用 HTTP 能力） */
    private final OpenAiClient openAiClient;

    /**
     * 构造注入。
     *
     * @param aiModelProperties 模型配置属性（绑定 spring.ai.platform）
     * @param modelAdapters     所有协议适配器（Spring 自动注入）
     * @param openAiClient      统一 OpenAI 兼容客户端（复用 Rerank HTTP 能力）
     */
    public AiModelFactoryImpl(AiModelProperties aiModelProperties,
                              List<ModelAdapter> modelAdapters,
                              OpenAiClient openAiClient) {
        this.aiModelProperties = aiModelProperties;
        this.modelAdapters = modelAdapters;
        this.openAiClient = openAiClient;
    }

    // ==================== 对话模型 ====================

    @Override
    public List<ChatModel> getChatModels() {
        List<String> names = getModelNamesByCategory(ModelCategory.CHAT);
        List<ChatModel> models = new ArrayList<>(names.size());
        for (String name : names) {
            models.add(getChatModel(name));
        }
        return models;
    }

    @Override
    public ChatModel getChatModel(String modelName) {
        return chatModelCache.computeIfAbsent(modelName, this::createChatModel);
    }

    // ==================== 嵌入模型 ====================

    @Override
    public EmbeddingModel getEmbeddingModel(String modelName) {
        return embeddingModelCache.computeIfAbsent(modelName, this::createEmbeddingModel);
    }

    @Override
    public EmbeddingModel getEmbeddingModelWithUsername(String modelName, String username) {
        // username 为空时退化为标准嵌入模型
        if (username == null || username.isBlank()) {
            return getEmbeddingModel(modelName);
        }
        String cacheKey = modelName + "@" + username;
        return embeddingModelWithUsernameCache.computeIfAbsent(cacheKey, key -> {
            AiModelProperties.ModelConfig config = getModelConfig(modelName);
            if (config == null) {
                throw new IllegalArgumentException("未找到嵌入模型配置: " + modelName);
            }
            // 仅 OPENAI 协议支持 X-Username 头，其他协议降级并告警
            if (config.getProtocol() != ProtocolType.OPENAI) {
                log.warn("协议 {} 不支持带用户名嵌入模型，退化为标准嵌入模型: {}", config.getProtocol(), modelName);
                return getEmbeddingModel(modelName);
            }
            // OPENAI 协议：通过 OpenAiApi.builder().headers() 注入 X-Username 头
            return createEmbeddingModelWithUsername(config, username);
        });
    }

    // ==================== OCR / ASR 模型（以 ChatModel 实现） ====================

    @Override
    public ChatModel getOcrModel(String modelName) {
        // OCR 以多模态 ChatModel 实现，与普通对话模型共用缓存
        return getChatModel(modelName);
    }

    @Override
    public ChatModel getAsrModel(String modelName) {
        // ASR 以语音对话 ChatModel 实现，与普通对话模型共用缓存
        return getChatModel(modelName);
    }

    // ==================== ChatClient ====================

    @Override
    public ChatClient getChatClient(String modelName) {
        return chatClientCache.computeIfAbsent(modelName, name -> {
            ChatModel chatModel = getChatModel(name);
            return ChatClient.builder(chatModel).build();
        });
    }

    // ==================== 重排序模型 ====================

    @Override
    public RerankModel getRerankModel(String modelName) {
        return rerankModelCache.computeIfAbsent(modelName, this::createRerankModel);
    }

    // ==================== 查询方法 ====================

    @Override
    public boolean existsModel(String modelName) {
        return modelName != null && aiModelProperties.getModels().containsKey(modelName);
    }

    @Override
    public AiModelProperties.ModelConfig getModelConfig(String modelName) {
        return aiModelProperties.getModels().get(modelName);
    }

    @Override
    public List<String> getModelNamesByCategory(ModelCategory category) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, AiModelProperties.ModelConfig> entry : aiModelProperties.getModels().entrySet()) {
            if (entry.getValue().getCategory() == category) {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    // ==================== 私有创建方法 ====================

    /**
     * 创建对话模型：通过 findAdapter 找到适配器后委托创建。
     *
     * @param modelName 逻辑模型名
     * @return 对话模型实例
     */
    private ChatModel createChatModel(String modelName) {
        AiModelProperties.ModelConfig config = getModelConfig(modelName);
        if (config == null) {
            throw new IllegalArgumentException("未找到对话模型配置: " + modelName);
        }
        ModelAdapter adapter = findAdapter(config);
        return adapter.createChatModel(config.getModelName(), config.getBaseUrl(),
                config.getApiKey(), config.getCompletionsPath());
    }

    /**
     * 创建嵌入模型：通过 findAdapter 找到适配器后委托创建。
     *
     * @param modelName 逻辑模型名
     * @return 嵌入模型实例
     */
    private EmbeddingModel createEmbeddingModel(String modelName) {
        AiModelProperties.ModelConfig config = getModelConfig(modelName);
        if (config == null) {
            throw new IllegalArgumentException("未找到嵌入模型配置: " + modelName);
        }
        ModelAdapter adapter = findAdapter(config);
        return adapter.createEmbeddingModel(config.getModelName(), config.getBaseUrl(),
                config.getApiKey(), config.getCompletionsPath());
    }

    /**
     * 创建带用户名头的嵌入模型（仅 OPENAI 协议）。
     * <p>通过 {@link OpenAiApi.Builder#headers(MultiValueMap)} 注入 {@code X-Username} 头，
     * 用于多租户场景下区分不同用户的向量化请求。</p>
     *
     * @param config   模型配置
     * @param username 用户名
     * @return 带 X-Username 头的嵌入模型
     */
    private EmbeddingModel createEmbeddingModelWithUsername(AiModelProperties.ModelConfig config, String username) {
        // 构造 X-Username 请求头
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.add("X-Username", username);

        // 构建 OpenAiApi：注入自定义头；completionsPath 非空时同时设置 Completions 与 Embeddings 路径
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .headers(headers);
        if (config.getCompletionsPath() != null && !config.getCompletionsPath().isBlank()) {
            apiBuilder.completionsPath(config.getCompletionsPath()).embeddingsPath(config.getCompletionsPath());
        }
        OpenAiApi openAiApi = apiBuilder.build();

        // 构建嵌入选项
        OpenAiEmbeddingOptions embeddingOptions = OpenAiEmbeddingOptions.builder()
                .model(config.getModelName())
                .build();

        // 返回带用户名头的嵌入模型
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.NONE, embeddingOptions);
    }

    /**
     * 创建重排序模型：不走适配器，直接按 OPENAI 协议创建 {@link OpenAiRerankModel}。
     * <p>复用 {@link OpenAiClient} 的 HTTP 能力完成 /rerank 调用。</p>
     *
     * @param modelName 逻辑模型名
     * @return 重排序模型实例
     */
    private RerankModel createRerankModel(String modelName) {
        AiModelProperties.ModelConfig config = getModelConfig(modelName);
        if (config == null) {
            throw new IllegalArgumentException("未找到重排序模型配置: " + modelName);
        }
        return new OpenAiRerankModel(openAiClient, config);
    }

    /**
     * 协议路由：遍历适配器列表，找到首个 {@code supports(category, protocol)} 返回 true 的适配器。
     *
     * @param config 模型配置（含 category 与 protocol）
     * @return 匹配的适配器
     * @throws IllegalStateException 未找到匹配适配器时抛出
     */
    private ModelAdapter findAdapter(AiModelProperties.ModelConfig config) {
        for (ModelAdapter adapter : modelAdapters) {
            if (adapter.supports(config.getCategory(), config.getProtocol())) {
                return adapter;
            }
        }
        throw new IllegalStateException(
                "未找到匹配的模型适配器: category=" + config.getCategory()
                        + ", protocol=" + config.getProtocol());
    }
}
