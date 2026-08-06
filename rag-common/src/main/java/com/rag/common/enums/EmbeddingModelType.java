package com.rag.common.enums;

/**
 * Embedding模型类型枚举
 */
public enum EmbeddingModelType {
    /** BGE-M3（OpenAI兼容远程端点） */
    BGE_M3,
    /** 通义千问 */
    TONGYI,
    /** OpenAI 兼容端点（text-embedding-3-small 等） */
    OPENAI
}
