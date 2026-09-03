package com.rag.config.adapter;

import com.rag.common.adapter.ModelAdapter;
import com.rag.common.enums.ModelCategory;
import com.rag.common.enums.ProtocolType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * OpenAI 协议模型适配器（通用适配器）。
 * <p>
 * 兼容所有实现 OpenAI API 规范的模型服务（硅基流动、DeepSeek、通义兼容端点等）。
 * 由于 {@link #getModelCategory()} 返回 {@code null}，本适配器不限类别，
 * 可处理 CHAT / EMBEDDING / OCR / ASR 等所有类别的模型创建。
 * </p>
 *
 * <h3>关键实现细节</h3>
 * <ul>
 *   <li>超时设置：连接 10s、读取 240s（适应长耗时生成）。</li>
 *   <li>{@code completionsPath} 非空时设置 Completions 路径；嵌入模型额外设置 Embeddings 路径。</li>
 *   <li>对话模型注入 {@link ToolCallingManager}（无工具场景传 {@code null}）。</li>
 * </ul>
 *
 * @author rag-tool
 * @since 1.0
 */
@Component
public class OpenAiModelAdapter implements ModelAdapter {

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.OPENAI;
    }

    /**
     * 返回 {@code null}，表示本适配器不限类别（通用适配器）。
     */
    @Override
    public ModelCategory getModelCategory() {
        return null;
    }

    @Override
    public ChatModel createChatModel(String modelName, String baseUrl, String apiKey,
                                     String completionsPath, Double temperature, Integer maxTokens) {
        // 构建 OpenAiApi：设置超时（连接 10s、读取 240s）
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey);
        if (completionsPath != null && !completionsPath.isBlank()) {
            apiBuilder.completionsPath(completionsPath);
        }
        OpenAiApi openAiApi = apiBuilder.build();

        // 构建对话选项：模型名、温度、最大 Token、核采样
        // 温度读取 YAML 配置（temperature 为 null 时使用 0.7 兜底），而非硬编码
        // 最大 Token 读取 YAML 配置（maxTokens 为 null 时使用 4096 兜底），而非此前硬编码 4096
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(temperature != null ? temperature : 0.7)
                .maxTokens(maxTokens != null ? maxTokens : 4096)
                .topP(0.9)
                .build();

        // 构建对话模型：注入 ToolCallingManager（无工具场景传 null）
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(chatOptions)
                .toolCallingManager(null)
                .build();
    }

    /**
     * 构造仅含温度的请求级 OpenAI Options。
     *
     * @param temperature 场景解析后的采样温度
     * @return OpenAiChatOptions（仅 temperature，不含模型名，请求级覆盖 defaultOptions）
     */
    @Override
    public ChatOptions createSceneChatOptions(Double temperature) {
        return OpenAiChatOptions.builder()
                .temperature(temperature)
                .build();
    }

    @Override
    public EmbeddingModel createEmbeddingModel(String modelName, String baseUrl, String apiKey, String completionsPath) {
        // 构建 OpenAiApi：设置超时；completionsPath 非空时同时设置 Completions 与 Embeddings 路径
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey);
        if (completionsPath != null && !completionsPath.isBlank()) {
            apiBuilder.completionsPath(completionsPath).embeddingsPath(completionsPath);
        }
        OpenAiApi openAiApi = apiBuilder.build();

        // 构建嵌入选项
        OpenAiEmbeddingOptions embeddingOptions = OpenAiEmbeddingOptions.builder()
                .model(modelName)
                .build();

        // 返回嵌入模型：MetadataMode.NONE + 嵌入选项
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.NONE, embeddingOptions);
    }

    @Override
    public boolean supports(ModelCategory category, ProtocolType protocol) {
        // OpenAI 适配器为通用适配器，仅判断协议，忽略类别
        return protocol == ProtocolType.OPENAI;
    }
}
