package com.rag.service;

import lombok.Builder;
import lombok.Data;

/**
 * 多知识库检索结果条目。
 * <p>字段与向量库元数据字段（docId / chunkId / kbId 等）一一对应，沿用项目已有命名约定。</p>
 */
@Data
@Builder
public class MultiSearchResult {

    /** 切片唯一标识（UUID） */
    private String chunkId;

    /** 文档归属 ID（用于白名单过滤、去重） */
    private String docId;

    /** 知识库归属 ID */
    private String kbId;

    /** 文件名 */
    private String fileName;

    /** 切片文本片段 */
    private String snippet;

    /** 相似度 / 精排得分 */
    private Double score;

    /** 分块模式（text_model / hierarchical_model） */
    private String chunkMode;

    /** 切片类型（parent / child / flat） */
    private String chunkType;

    /** 父块 ID（父子增强用） */
    private String parentChunkId;

    /** 文件路径（溯源） */
    private String sourcePath;
}
