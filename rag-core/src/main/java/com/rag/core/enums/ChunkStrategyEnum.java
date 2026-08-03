package com.rag.core.enums;

/**
 * 分片策略枚举
 */
public enum ChunkStrategyEnum {
    /** 固定长度分片 */
    FIXED_SIZE,
    /** 语义边界分片 */
    SEMANTIC,
    /** 表格独立分片 */
    TABLE,
    /** 代码函数分片 */
    CODE_FUNCTION,
    /** 标题层级分片 */
    TITLE_HIERARCHY,
    /** 父子分片 */
    PARENT_CHILD,
    /** 通用文本分块策略（text_model），对应 SizeTextSplitter */
    TEXT_MODEL,
    /** 层级父子分块策略（hierarchical_model），对应 ParentChildTextSplitter */
    HIERARCHICAL_MODEL
}
