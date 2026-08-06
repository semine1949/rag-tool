package com.rag.common.enums;

/**
 * 文档全流程处理状态枚举（v2 新增）
 * <p>覆盖文档从上传、解析、分块、向量化到最终入库/失败的完整生命周期。</p>
 * <p>状态流转路径：
 *   PENDING → PARSING → PARSED → CHUNKING → CHUNKED → VECTORIZING → COMPLETED
 *                                                                    → FAILED（任意阶段可进入）
 * </p>
 */
public enum ProcessStatusEnum {
    /** 待处理（初始状态） */
    PENDING,
    /** 解析中 */
    PARSING,
    /** 解析完成 */
    PARSED,
    /** 分块中 */
    CHUNKING,
    /** 分块完成 */
    CHUNKED,
    /** 向量化中 */
    VECTORIZING,
    /** 处理完成 */
    COMPLETED,
    /** 处理失败 */
    FAILED;
}
