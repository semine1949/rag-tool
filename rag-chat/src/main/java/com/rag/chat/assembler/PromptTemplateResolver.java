package com.rag.chat.assembler;

import com.rag.chat.config.ChatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 系统提示词模板解析器。
 * <p>
 * 根据对话场景（RAG 知识库问答 / 普通对话）自动选择对应的系统提示词模板。
 * 支持两级配置体系：全局默认模板（{@link ChatProperties}）+ 知识库级自定义模板（{@code KbChatConfig}）。
 * 配置优先级：知识库级 > 全局配置 > 内置默认模板。
 * </p>
 *
 * <h3>RAG 场景检测</h3>
 * 当 {@code isRag = true} 时加载 RAG 约束模板，强制 LLM 遵循：
 * <ul>
 *   <li>严格基于上下文片段作答，禁止编造</li>
 *   <li>按召回片段编号 [1]..[n] 标注引用</li>
 *   <li>无匹配信息时明确告知无法回答</li>
 * </ul>
 * 普通对话场景加载通用对话模板，不附加 RAG 约束。
 *
 * @author rag-tool
 * @since 1.0
 */
@Component
public class PromptTemplateResolver {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateResolver.class);

    /**
     * 内置默认 RAG 系统提示词模板。
     * <p>当全局配置与知识库配置均未指定时使用此默认值。</p>
     */
    private static final String DEFAULT_RAG_SYSTEM_PROMPT =
            "你是一个知识库问答助手。严格遵循以下规则回答用户问题：\n\n" +
            "1. 必须严格基于以下参考资料片段作答，禁止编造、推测或引入参考资料外的信息。\n" +
            "2. 回答时，对于引用的内容，在对应的句子末尾标注引用编号，如 [1]、[2][3]。\n" +
            "3. 如果参考资料不足以回答用户问题，请明确告知\"根据现有资料无法回答该问题\"，不得强行编造。\n" +
            "4. 回答应简洁、准确、结构化，优先使用列表或分点表述。\n" +
            "5. 参考资料按相关度排序，编号 [1]..[n] 为优先级顺序。";

    /** 问答全局配置 */
    private final ChatProperties chatProperties;

    public PromptTemplateResolver(ChatProperties chatProperties) {
        this.chatProperties = chatProperties;
    }

    /**
     * 解析系统提示词。
     *
     * @param isRag        是否为 RAG 知识库问答场景（有 kbId 或上传文件时）
     * @param kbSystemPrompt 知识库级自定义提示词（可为 null）
     * @return 最终系统提示词；RAG 场景一定返回非空，普通对话可能返回 null
     */
    public String resolve(boolean isRag, String kbSystemPrompt) {
        if (isRag) {
            // 优先级：知识库级 > 全局配置 > 内置默认
            if (kbSystemPrompt != null && !kbSystemPrompt.isBlank()) {
                log.debug("使用知识库级 RAG 提示词");
                return kbSystemPrompt;
            }
            String global = chatProperties.getRagSystemPrompt();
            if (global != null && !global.isBlank()) {
                log.debug("使用全局 RAG 提示词");
                return global;
            }
            log.debug("使用内置默认 RAG 提示词");
            return DEFAULT_RAG_SYSTEM_PROMPT;
        }

        // 普通对话：全局配置 > 无（不传系统提示词）
        String chatPrompt = chatProperties.getChatSystemPrompt();
        if (chatPrompt != null && !chatPrompt.isBlank()) {
            log.debug("使用全局对话提示词");
            return chatPrompt;
        }
        return null;
    }
}