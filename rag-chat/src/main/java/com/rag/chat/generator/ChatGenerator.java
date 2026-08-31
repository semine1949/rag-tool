package com.rag.chat.generator;

import com.rag.common.chat.Citation;
import com.rag.common.chat.ChatStreamEvent;
import com.rag.chat.config.ChatProperties;
import com.rag.config.factory.AiModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 LLM 的回答生成器。
 * <p>
 * 整合召回上下文与系统提示词，调用对话模型生成回答。支持同步与流式两种输出模式：
 * <ul>
 *   <li>同步：返回完整回答文本与引用列表</li>
 *   <li>流式：先推送引用事件，再逐字推送内容片段，最后推送结束事件</li>
 * </ul>
 * 异常时降级返回召回片段摘要，保障基础可用性。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Component
public class ChatGenerator {

    private static final Logger log = LoggerFactory.getLogger(ChatGenerator.class);

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
    public ChatGenerator(AiModelFactory aiModelFactory, ChatProperties chatProperties) {
        this.aiModelFactory = aiModelFactory;
        this.chatProperties = chatProperties;
    }

    /**
     * 同步生成回答。
     *
     * @param query           用户提问
     * @param contextText     召回上下文文本（含编号 [1]..[n]）
     * @param citations       引用列表
     * @param systemPrompt    系统提示词
     * @param modelName       对话模型名（为空使用全局默认）
     * @return 生成的回答文本
     */
    public String generate(String query, String contextText, List<Citation> citations,
                           String systemPrompt, String modelName) {
        try {
            ChatModel chatModel = aiModelFactory.getChatModel(resolveModelName(modelName));
            List<Message> messages = buildMessages(query, contextText, systemPrompt);
            Prompt prompt = new Prompt(messages);
            String answer = chatModel.call(prompt).getResult().getOutput().getText();
            return answer == null ? "" : answer;
        } catch (Exception e) {
            log.error("同步生成回答失败，降级返回召回片段摘要 query={}", query, e);
            return buildFallbackAnswer(citations);
        }
    }

    /**
     * 流式生成回答。
     * <p>事件顺序：citations → content（多次）→ done。</p>
     *
     * @param query        用户提问
     * @param contextText  召回上下文文本
     * @param citationsJson 引用列表的 JSON 字符串
     * @param systemPrompt 系统提示词
     * @param modelName    对话模型名（为空使用全局默认）
     * @return 事件流
     */
    public Flux<ChatStreamEvent> generateStream(String query, String contextText,
                                                String citationsJson, String systemPrompt,
                                                String modelName) {
        StringBuilder fullAnswer = new StringBuilder();
        try {
            ChatModel chatModel = aiModelFactory.getChatModel(resolveModelName(modelName));
            List<Message> messages = buildMessages(query, contextText, systemPrompt);
            Prompt prompt = new Prompt(messages);

            // 先推送引用事件
            Flux<ChatStreamEvent> citationsEvent = Flux.just(
                    ChatStreamEvent.citations(citationsJson));

            // 流式推送内容片段
            // 注意：不能用 map + 返回 null，Reactor 的 map 禁止 mapper 返回 null，
            // 一旦返回 null 会抛出 "The mapper returned a null value" NPE，且无法被 filter 拦截。
            // 因此改为 flatMap + Mono.empty()：null/空 chunk 直接静默跳过，不触发 NPE。
            Flux<ChatStreamEvent> contentEvents = chatModel.stream(prompt)
                    .flatMap(response -> {
                        String chunk = response.getResult().getOutput().getText();
                        if (chunk != null && !chunk.isEmpty()) {
                            fullAnswer.append(chunk);
                            return Mono.just(ChatStreamEvent.content(chunk));
                        }
                        return Mono.empty();
                    });

            // 推送结束事件
            Flux<ChatStreamEvent> doneEvent = Flux.defer(() ->
                    Flux.just(ChatStreamEvent.done(fullAnswer.toString())));

            return Flux.concat(citationsEvent, contentEvents, doneEvent)
                    .onErrorResume(e -> {
                        log.error("流式生成失败，降级返回错误事件 query={}", query, e);
                        return Flux.just(ChatStreamEvent.error("生成失败，请稍后重试"));
                    });
        } catch (Exception e) {
            log.error("流式生成初始化失败 query={}", query, e);
            return Flux.just(ChatStreamEvent.error("生成服务暂不可用"));
        }
    }

    /**
     * 构造模型调用消息列表：系统提示 + 上下文 + 用户提问。
     *
     * @param query        用户提问
     * @param contextText  召回上下文文本
     * @param systemPrompt 系统提示词
     * @return 消息列表
     */
    private List<Message> buildMessages(String query, String contextText, String systemPrompt) {
        List<Message> messages = new ArrayList<>();
        // 系统提示词：优先使用传入值，回退全局默认
        String prompt = (systemPrompt != null && !systemPrompt.isBlank())
                ? systemPrompt : chatProperties.getDefaultSystemPrompt();
        if (prompt != null && !prompt.isBlank()) {
            messages.add(new SystemMessage(prompt));
        }
        // 上下文作为用户消息的一部分（与提问合并）
        String userMessage = contextText == null || contextText.isBlank()
                ? query
                : "参考资料：\n" + contextText + "\n\n用户问题：" + query;
        messages.add(new UserMessage(userMessage));
        return messages;
    }

    /**
     * 解析模型名：传入为空时回退全局默认。
     */
    private String resolveModelName(String modelName) {
        return (modelName == null || modelName.isBlank())
                ? chatProperties.getChatModel() : modelName;
    }

    /**
     * 降级回答：LLM 调用失败时返回召回片段摘要。
     *
     * @param citations 引用列表
     * @return 降级回答文本
     */
    private String buildFallbackAnswer(List<Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return "根据现有资料无法回答。";
        }
        StringBuilder sb = new StringBuilder("根据现有资料无法生成完整回答，以下为相关参考片段：\n\n");
        for (Citation c : citations) {
            sb.append("[").append(c.getIndex()).append("] ")
                    .append(c.getSnippet() != null ? c.getSnippet() : "")
                    .append("\n\n");
        }
        return sb.toString();
    }
}