package com.rag.common.enums;

/**
 * 模型类别枚举，用于区分 AI 模型的业务用途。
 * <p>
 * 工厂按类别提供不同的获取方法（如 {@code getChatModel}、{@code getEmbeddingModel}），
 * 同一类别下可存在多个不同协议的模型实例。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
public enum ModelCategory {

    /** 对话模型（文本生成 / 多轮对话） */
    CHAT,

    /** 嵌入模型（文本向量化） */
    EMBEDDING,

    /** OCR 图像识别模型 */
    OCR,

    /** 重排序模型（精排） */
    RERANK,

    /** 语音识别模型（ASR） */
    ASR,

    /** 工作流模型 */
    WORKFLOW,

    /** 智能体模型 */
    AGENT,

    /** RAG 模型 */
    RAG
}
