package com.rag.config.adapter;

import com.rag.common.adapter.ModelAdapter;
import com.rag.common.enums.ModelCategory;
import com.rag.common.enums.ProtocolType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.stereotype.Component;

/**
 * Ollama 协议模型适配器（本地模型部署）。
 * <p>
 * 面向本地化部署的大模型服务，无 API Key 鉴权（本地协议）。
 * 通过官方 {@code spring-ai-ollama} SDK 构建 {@link OllamaChatModel} / {@link OllamaEmbeddingModel}。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */

public class OllamaModelAdapter implements ModelAdapter {

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.OLLAMA;
    }

    @Override
    public ModelCategory getModelCategory() {
        return null;
    }

    @Override
    public ChatModel createChatModel(String modelName, String baseUrl, String apiKey,
                                     String completionsPath, Double temperature) {
        // 构建 OllamaApi：本地协议，仅需 baseUrl
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .build();

        // 构建对话选项：模型名 + 温度（配置了 temperature 时写入，否则交由 Ollama 默认）
        OllamaOptions.Builder optionsBuilder = OllamaOptions.builder()
                .model(modelName);
        if (temperature != null) {
            optionsBuilder.temperature(temperature);
        }
        OllamaOptions options = optionsBuilder.build();

        // 构建对话模型
        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(options)
                .build();
    }

    /**
     * 构造仅含温度的请求级 Ollama Options。
     *
     * @param temperature 场景解析后的采样温度
     * @return OllamaOptions（仅 temperature，请求级覆盖 defaultOptions）
     */
    @Override
    public ChatOptions createSceneChatOptions(Double temperature) {
        return OllamaOptions.builder()
                .temperature(temperature)
                .build();
    }

    @Override
    public EmbeddingModel createEmbeddingModel(String modelName, String baseUrl, String apiKey, String completionsPath) {
        // 构建 OllamaApi
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .build();

        // 构建嵌入选项
        OllamaOptions options = OllamaOptions.builder()
                .model(modelName)
                .build();

        // 构建嵌入模型
        return OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(options)
                .build();
    }

    @Override
    public boolean supports(ModelCategory category, ProtocolType protocol) {
        // Ollama 适配器仅判断协议，忽略类别
        return protocol == ProtocolType.OLLAMA;
    }
}
