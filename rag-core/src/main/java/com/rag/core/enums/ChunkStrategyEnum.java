package com.rag.core.enums;

/**
 * 分片策略枚举（精简版，仅保留两种支持的策略）。
 */
public enum ChunkStrategyEnum {
    /** 通用文本分块策略（text_model），对应 SizeTextSplitter */
    TEXT_MODEL,
    /** 层级父子分块策略（hierarchical_model），对应 ParentChildTextSplitter */
    HIERARCHICAL_MODEL
}
