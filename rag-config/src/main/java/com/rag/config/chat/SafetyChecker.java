package com.rag.config.chat;

import com.rag.config.properties.ChatProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 输入输出内容安全校验器。
 * <p>
 * 对用户输入与模型输出进行基础安全检查，包含提示词注入防护与敏感词过滤。
 * 校验开关由 {@link ChatProperties#isSafetyCheckEnabled()} 控制，关闭时直接放行。
 * 本实现为轻量规则校验，后续可扩展为基于模型的内容安全审核。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Component
public class SafetyChecker {

    /** 提示词注入特征词（出现即视为可疑，需拦截或告警） */
    private static final List<String> INJECTION_PATTERNS = Arrays.asList(
            "ignore previous instructions",
            "disregard the above",
            "forget your instructions",
            "忽略上述指令",
            "无视以上内容",
            "你现在的角色是",
            "system prompt:",
            "reveal your prompt"
    );

    /** 敏感词列表（示例，生产环境应从配置或外部词库加载） */
    private static final List<String> SENSITIVE_WORDS = Arrays.asList(
            // 占位：实际敏感词由配置或合规词库提供
    );

    /** 问答全局配置 */
    private final ChatProperties chatProperties;

    /**
     * 构造函数注入。
     *
     * @param chatProperties 问答全局配置
     */
    public SafetyChecker(ChatProperties chatProperties) {
        this.chatProperties = chatProperties;
    }

    /**
     * 校验用户输入是否安全。
     *
     * @param input 用户输入文本
     * @return {@code true} 表示通过校验；{@code false} 表示存在风险
     */
    public boolean checkInput(String input) {
        if (!chatProperties.isSafetyCheckEnabled()) {
            return true;
        }
        if (input == null || input.isBlank()) {
            return true;
        }
        String lower = input.toLowerCase();
        // 提示词注入检测
        for (String pattern : INJECTION_PATTERNS) {
            if (lower.contains(pattern.toLowerCase())) {
                return false;
            }
        }
        // 敏感词检测
        for (String word : SENSITIVE_WORDS) {
            if (input.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验模型输出是否安全。
     *
     * @param output 模型输出文本
     * @return {@code true} 表示通过校验；{@code false} 表示存在风险
     */
    public boolean checkOutput(String output) {
        if (!chatProperties.isSafetyCheckEnabled()) {
            return true;
        }
        if (output == null || output.isBlank()) {
            return true;
        }
        // 输出仅做敏感词检测，不检测提示词注入（模型输出本身不应包含注入指令）
        for (String word : SENSITIVE_WORDS) {
            if (output.contains(word)) {
                return false;
            }
        }
        return true;
    }
}
