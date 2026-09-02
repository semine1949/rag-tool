package com.rag.chat.classifier;

import com.rag.chat.config.ChatProperties;
import com.rag.config.factory.AiModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * 通用问题二分类拦截器。
 * <p>
 * 在知识库检索执行前对用户问题进行极简二分类，判断其是否属于
 * "通用常识、闲聊互动、实时信息"类问题（与知识库内容无关）。
 * 若判定为通用问题，则跳过检索直接走纯对话模式回答，从而避免无效向量检索、
 * 降低算力消耗、提升首字响应速度。
 * </p>
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>零新增模型</b>：复用 {@link AiModelFactory} 管理的现有对话模型做极简二分类，不新建连接。</li>
 *   <li><b>首字判定</b>：仅取模型返回首字符，明确为"是"才判定为通用问题，否则一律视为非通用（放行进 RAG）。</li>
 *   <li><b>异常兜底</b>：分类调用异常、超时、结果为空或不可识别时，统一返回 {@code false}（放行 RAG）
 *       并记录 WARN 告警日志，绝不因分类失败中断整体请求。</li>
 *   <li><b>低 Token 成本</b>：分类 Prompt 为极简二分类指令，模型仅需输出"是/否"单字，
 *       相对检索 + 向量调用显著降低算力消耗。</li>
 * </ul>
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Component
public class GenericQueryClassifier {

    private static final Logger log = LoggerFactory.getLogger(GenericQueryClassifier.class);

    /**
     * 内置默认分类指令模板。
     * <p>要求模型仅回答"是"或"否"，避免冗余输出，降低 Token 消耗。{@code {query}} 为占位符。</p>
     */
    private static final String DEFAULT_CLASSIFY_PROMPT =
            "请判断以下问题是否属于通用常识、闲聊互动或实时信息类问题（与特定知识库内容无关）。" +
            "如果属于，请仅回答：是；否则请仅回答：否。\n问题：{query}";

    /** AI 模型工厂（惰性创建 + 缓存，复用现有对话模型） */
    private final AiModelFactory aiModelFactory;

    /** 问答全局配置（分类开关、模型、Prompt、标注、超时等） */
    private final ChatProperties chatProperties;

    /**
     * 构造函数注入。
     *
     * @param aiModelFactory AI 模型工厂
     * @param chatProperties 问答全局配置
     */
    public GenericQueryClassifier(AiModelFactory aiModelFactory, ChatProperties chatProperties) {
        this.aiModelFactory = aiModelFactory;
        this.chatProperties = chatProperties;
    }

    /**
     * 对用户问题做通用二分类判断。
     * <p>
     * 复用 {@code preQueryFilterModel}（为空回退全局 {@code chatModel}）调用极简二分类 Prompt，
     * 仅取模型返回首字符，明确为"是"才返回 {@code true}。分类异常/超时/空结果统一捕获，
     * 返回 {@code false}（放行 RAG 链路）并记录 WARN 告警日志。
     * </p>
     *
     * @param query 用户提问（非空）
     * @return {@code true} 表示通用问题（拦截走纯对话）；{@code false} 表示非通用（放行进 RAG）
     */
    public boolean classify(String query) {
        long start = System.currentTimeMillis();
        long timeoutMs = chatProperties.getPreQueryFilterTimeoutMs();
        try {
            ChatModel chatModel = aiModelFactory.getChatModel(resolveModelName());
            String prompt = buildPrompt(query);
            String response = chatModel.call(new Prompt(new UserMessage(prompt)))
                    .getResult().getOutput().getText();
            boolean isGeneric = isYesFirstChar(response);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[PRE-QUERY-FILTER] 分类完成 query={}, result={}, elapsedMs={}",
                    query, isGeneric, elapsed);
            return isGeneric;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[PRE-QUERY-FILTER] 分类调用异常，放行 RAG 链路 query={}, elapsedMs={}, err={}",
                    query, elapsed, e.getMessage());
            return false;
        }
    }

    /**
     * 构造分类指令文本：将 {@code {query}} 占位符替换为真实问题。
     *
     * @param query 用户提问
     * @return 分类指令文本
     */
    private String buildPrompt(String query) {
        String template = chatProperties.getPreQueryFilterPrompt();
        if (template == null || template.isBlank()) {
            template = DEFAULT_CLASSIFY_PROMPT;
        }
        return template.replace("{query}", query == null ? "" : query);
    }

    /**
     * 判定模型返回首字符是否为明确"是"。
     * <p>仅取首字符并忽略空白，匹配"是""Y""Yes"（含大小写）；其他一律视为非通用。</p>
     *
     * @param response 模型返回文本
     * @return {@code true} 表示明确"是"
     */
    private boolean isYesFirstChar(String response) {
        if (response == null) {
            return false;
        }
        String trimmed = response.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        char first = trimmed.charAt(0);
        return first == '是' || first == 'Y' || first == 'y';
    }

    /**
     * 解析分类模型名：优先使用 {@code preQueryFilterModel}，为空回退全局 {@code chatModel}。
     *
     * @return 分类模型名（非空）
     */
    private String resolveModelName() {
        String model = chatProperties.getPreQueryFilterModel();
        return (model == null || model.isBlank())
                ? chatProperties.getChatModel() : model;
    }

}
