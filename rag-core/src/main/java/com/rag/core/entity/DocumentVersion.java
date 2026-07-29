package com.rag.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档版本实体（Multi-Tenant v2）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVersion {
    /** 主键ID */
    private Long id;
    /** 文档唯一标识 */
    private String documentId;
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
