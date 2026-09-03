package com.rag.config.adapter;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.rag.common.adapter.ModelAdapter;
import com.rag.common.enums.ModelCategory;
import com.rag.common.enums.ProtocolType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * DashScope 协议模型适配器（阿里云通义千问 / 百炼平台）。
 * <p>
 * 通过 spring-ai-alibaba 体系官方 SDK 构建
 * {@link DashScopeChatModel} / {@link DashScopeEmbeddingModel}。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */

public class DashScopeModelAdapter implements ModelAdapter {

    @Override
    public ProtocolType getProtocolType() {
        return ProtocolType.DASHSCOPE;
    }

    @Override
    public ModelCategory getModelCategory() {
        return null;
    }

    @Override
    public ChatModel createChatModel(String modelName, String baseUrl, String apiKey,
                                     String completionsPath, Double temperature, Integer maxTokens) {
        // 构建 DashScopeApi
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        // 构建对话选项（DashScopeChatOptions 此版本 setMaxTokens() 为无参缺陷方法，改用官方 builder 链式 withXxx）
        // 温度读取 YAML 配置（temperature 为 null 时不设，交由服务端默认）
        // 最大 Token 读取 YAML 配置（maxTokens 非 null 时才设置，否则交由服务端默认）
        DashScopeChatOptions.DashscopeChatOptionsBuilder optionsBuilder = DashScopeChatOptions.builder()
                .withModel(modelName);
        if (temperature != null) {
            optionsBuilder.withTemperature(temperature);
        }
        if (maxTokens != null) {
            optionsBuilder.withMaxToken(maxTokens);
        }
        DashScopeChatOptions chatOptions = optionsBuilder.build();

        // 构建对话模型
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(chatOptions)
                .build();
    }

    /**
     * 构造仅含温度的请求级 DashScope Options。
     *
     * @param temperature 场景解析后的采样温度
     * @return DashScopeChatOptions（仅 temperature，请求级覆盖 defaultOptions）
     */
    @Override
    public ChatOptions createSceneChatOptions(Double temperature) {
        DashScopeChatOptions options = new DashScopeChatOptions();
        options.setTemperature(temperature);
        return options;
    }

    @Override
    public EmbeddingModel createEmbeddingModel(String modelName, String baseUrl, String apiKey, String completionsPath) {
        // 构建 DashScopeApi
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        // 构建嵌入模型（DashScopeEmbeddingModel 仅需 DashScopeApi）
        return new DashScopeEmbeddingModel(dashScopeApi);
    }

    @Override
    public boolean supports(ModelCategory category, ProtocolType protocol) {
        // DashScope 适配器仅判断协议，忽略类别
        return protocol == ProtocolType.DASHSCOPE;
    }
}
