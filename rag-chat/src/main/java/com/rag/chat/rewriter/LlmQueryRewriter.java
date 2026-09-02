package com.rag.chat.rewriter;

import com.rag.common.chat.ChatMessage;
import com.rag.common.chat.QueryRewriter;
import com.rag.common.enums.ChatScene;
import com.rag.chat.config.ChatProperties;
import com.rag.config.factory.AiModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link QueryRewriter} 的 LLM 实现。
 * <p>
 * 结合会话历史调用对话模型进行查询改写，消除代词歧义、补全省略信息，
 * 提升下游检索精准度。遵循"查询改写仅携带最近 3 轮历史以控制 Token 消耗"约定。
 * 异常时降级返回原始查询，保障主链路不中断。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Component
public class LlmQueryRewriter implements QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryRewriter.class);

    /** 查询改写系统提示词 */
    private static final String REWRITE_SYSTEM_PROMPT =
            "你是查询改写助手。根据对话历史，将用户最新提问改写为独立、完整的检索查询。" +
            "要求：\n" +
            "1. 消除代词歧义，补全省略的主语/宾语。\n" +
            "2. 仅输出改写后的查询文本，不附加任何解释或前缀。\n" +
            "3. 若最新提问本身已完整，原样输出。";

    /** 携带最近历史轮次（一轮 = 一问一答 = 2 条消息） */
    private static final int HISTORY_ROUNDS = 3;

    /** AI 模型工厂 */
    private final AiModelFactory aiModelFactory;

    /** 问答全局配置 */
    private final ChatProperties chatProperties;

    /**
     * 构造函数注入。
     *
     * @param aiModelFactory AI 模型工厂
     * @param chatProperties 问答全局配置
     */
    public LlmQueryRewriter(AiModelFactory aiModelFactory, ChatProperties chatProperties) {
        this.aiModelFactory = aiModelFactory;
        this.chatProperties = chatProperties;
    }

    @Override
    public String rewrite(String query, List<ChatMessage> history) {
        // 无历史或历史过短，直接返回原始查询
        if (history == null || history.isEmpty() || history.size() < 2) {
            return query;
        }

        try {
            // 解析改写模型名：优先使用 rewriteModel，为空回退 chatModel
            String rewriteModel = chatProperties.getRewriteModel();
            if (rewriteModel == null || rewriteModel.isBlank()) {
                rewriteModel = chatProperties.getChatModel();
            }

            ChatModel chatModel = aiModelFactory.getChatModel(rewriteModel);

            // 构造消息列表：系统提示 + 最近 3 轮历史 + 当前提问
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(REWRITE_SYSTEM_PROMPT));

            // 截取最近 HISTORY_ROUNDS 轮（每轮 2 条消息）
            int startIdx = Math.max(0, history.size() - HISTORY_ROUNDS * 2);
            for (int i = startIdx; i < history.size(); i++) {
                ChatMessage msg = history.get(i);
                if (ChatMessage.ROLE_USER.equals(msg.getRole())) {
                    messages.add(new UserMessage(msg.getContent()));
                }
                // ASSISTANT 消息作为上下文，由 Prompt 自动按角色组装
            }
            // 当前提问作为最新用户消息
            messages.add(new UserMessage(query));

            // 改写属确定性任务：使用独立 REWRITE 场景温度（默认 0），请求级覆盖，保证改写结果稳定可复现
            ChatOptions sceneOptions = aiModelFactory.resolveSceneChatOptions(rewriteModel, ChatScene.REWRITE);
            Prompt prompt = sceneOptions == null
                    ? new Prompt(messages)
                    : new Prompt(messages, sceneOptions);
            String rewritten = chatModel.call(prompt).getResult().getOutput().getText();

            // 兜底：改写结果为空或过长（可能是模型输出了多余解释），降级原始查询
            if (rewritten == null || rewritten.isBlank()) {
                return query;
            }
            if (rewritten.length() > query.length() * 5) {
                log.warn("改写结果异常过长，降级为原始查询 original={} rewritten={}", query, rewritten);
                return query;
            }
            return rewritten.trim();
        } catch (Exception e) {
            log.warn("查询改写失败，降级为原始查询 query={}", query, e);
            return query;
        }
    }
}