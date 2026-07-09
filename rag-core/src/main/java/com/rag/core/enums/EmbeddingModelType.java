package com.rag.core.enums;

/**
 * Embedding模型类型枚举
 */
public enum EmbeddingModelType {
    /** 本地ONNX模型 */
    ONNX,
    /** OpenAI API */
    OPENAI,
    /** Ollama本地 */
    OLLAMA,
    /** 智谱 */
    ZHIPU,
    /** 通义千问 */
    TONGYI,
    /** MiniMax */
    MINIMAX
}
