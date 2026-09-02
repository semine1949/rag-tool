package com.rag.common.enums;

/**
 * 对话（LLM）调用场景标识枚举。
 * <p>
 * 用于"同一模型在不同场景下使用不同温度"的场景化温度控制：不同业务场景对输出
 * 确定性要求不同，应使用不同采样温度。本枚举标识各调用场景，与模型配置中的
 * {@code scene-temperatures} 温度表配合实现按场景差异化温度。
 * </p>
 * <p>
 * 温度本身不固化在本枚举内，而是由各模型的 {@code scene-temperatures} 场景温度表
 * （在 YAML 配置中维护）决定，未命中场景时回退模型级默认 {@code temperature}。
 * 温度通过请求级 {@code ChatOptions}（随 {@code Prompt} 携带）覆盖模型默认温度，
 * 不污染模型单例缓存中的 {@code defaultOptions}，保证问答主链路温度不受影响。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
public enum ChatScene {

    /** 问题分类 / 检索前置二分类等确定性任务 */
    CLASSIFY,

    /** 查询改写（多轮上下文补全 + 复杂查询优化） */
    REWRITE,

    /** 知识库（RAG）问答：基于检索上下文做严谨回答 */
    RAG_QA,

    /** 通用问答 / 闲聊互动（无检索上下文） */
    CHAT_QA
}
