package com.rag.common.enums;

/**
 * 检索模式枚举，定义三种检索召回路径。
 * <ul>
 *   <li>{@link #VECTOR_ONLY} —— 纯向量检索（默认模式，兼容原有逻辑）</li>
 *   <li>{@link #BM25_ONLY}  —— 纯 BM25 关键词检索</li>
 *   <li>{@link #HYBRID}     —— 混合多路召回（向量 + BM25，经 RRF 或加权求和融合）</li>
 * </ul>
 */
public enum SearchMode {
    /** 纯向量检索（默认值，100% 兼容原有调用） */
    VECTOR_ONLY,
    /** 纯 BM25 关键词检索 */
    BM25_ONLY,
    /** 混合多路召回：向量 + BM25 双路召回后融合排序 */
    HYBRID
}
