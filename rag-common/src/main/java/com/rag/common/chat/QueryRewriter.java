package com.rag.common.chat;

import java.util.List;

/**
 * 查询改写抽象接口。
 * <p>
 * 在多轮对话场景下，结合历史上下文对用户当前提问进行改写，
 * 消除代词歧义、补全省略信息，提升下游检索的精准度。
 * 抽象下沉至 {@code rag-common}，具体实现（如基于 LLM 的改写）置于 {@code rag-config}。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
public interface QueryRewriter {

    /**
     * 结合会话历史对原始查询进行改写。
     * <p>实现方应保证异常时降级返回原始查询，避免阻断主链路。</p>
     *
     * @param query   用户当前提问
     * @param history 会话历史消息（最近 N 轮，由调用方控制数量）
     * @return 改写后的查询；异常或无需改写时返回原始查询
     */
    String rewrite(String query, List<ChatMessage> history);
}
