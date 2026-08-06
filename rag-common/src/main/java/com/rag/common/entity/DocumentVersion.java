package com.rag.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档版本实体（v2 修复关联一致性：documentId(String) → docId(Long)）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVersion {
    /** 主键ID */
    private Long id;
    /** 文档ID（关联 kb_document.doc_id，v2 改为 BIGINT） */
    private Long docId;
    /** 所属租户ID */
    private Long tenantId;
    /** 所属知识库ID */
    private Long kbId;
    /** 语义化版本号（如v1.0.0） */
    private String version;
    /** 本版本分片总数 */
    private Integer chunkCount;
    /** 操作人ID */
    private Long operatorId;
    /** 变更类型：INITIAL, MAJOR, MINOR, PATCH */
    private String changeType;
    /** 变更说明 */
    private String changeRemark;
    /** 关联集合名称 */
    private String collectionName;
    /** 是否当前生效版本 */
    private Boolean currentActive;
    /** 创建时间 */
    private Date createTime;
}
