package com.rag.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 知识库实体（扁平配置列，不存 JSON）
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
    /** 分片策略：FIXED_SIZE/SEMANTIC/TABLE/CODE_FUNCTION/TITLE_HIERARCHY/PARENT_CHILD */
    private String chunkStrategy;
    /** 分片大小（字符数） */
    private Integer chunkSize;
    /** 分片重叠窗口（字符数） */
    private Integer chunkOverlap;
    /** Embedding模型名（如 text-embedding-3-small / Qwen/Qwen3-Embedding-0.6B） */
    private String embeddingModel;
    private Integer status;
    private Date createTime;
}
