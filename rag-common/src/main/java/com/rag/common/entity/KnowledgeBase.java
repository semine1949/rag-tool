package com.rag.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 知识库实体（v2：分片策略已下沉至文档维度，知识库仅保留 embedding_model）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBase {
    private Long kbId;
    private Long tenantId;
    private String kbName;
    private String description;
    /** Embedding模型名（如 text-embedding-3-small），同一知识库内统一 */
    private String embeddingModel;
    private Integer status;
    private Date createTime;
}
