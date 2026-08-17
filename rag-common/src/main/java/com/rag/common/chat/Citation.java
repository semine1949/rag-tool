package com.rag.common.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 引用溯源元数据。
 * <p>
 * 用于回答内容中标注的 [n] 引用编号，回链到原始召回片段，
 * 保障生成内容的可追溯性。引用关系严格对应召回片段，无来源的生成内容不得标注引用。
 * </p>
 *
 * @author rag-tool
 * @since 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Citation {

    /** 引用编号（与回答中的 [n] 一一对应，从 1 开始） */
    private int index;

    /** 来源文档 ID */
    private Long docId;

    /** 来源知识库 ID */
    private Long kbId;

    /** 来源文档名称 */
    private String fileName;

    /** 来源分片 ID */
    private String chunkId;

    /** 来源分片原文片段（截断展示用） */
    private String snippet;

    /** 召回相似度得分（0.0~1.0） */
    private Double score;
}
