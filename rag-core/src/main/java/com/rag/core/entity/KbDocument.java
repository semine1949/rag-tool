package com.rag.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 知识库文档登记实体（v2 重构，v3 分片策略下沉）
 * <p>v2 变更：分片策略从知识库下移到文档维度，支持同一知识库下不同文档采用不同切分策略。</p>
 * <p>v3 变更：分片策略从文档维度进一步下沉到分片维度（doc_chunk），
 * 本实体不再持有 chunk_strategy / chunk_size / chunk_overlap 字段，
 * 每个分片的分片策略与参数快照记录在 doc_chunk（chunk_mode + params_snapshot）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbDocument {
    /** 文档ID（主键，自增） */
    private Long docId;
    /** 所属知识库ID */
    private Long kbId;
    /** 所属租户ID */
    private Long tenantId;
    /** 文件名 */
    private String fileName;
    /** 文件类型（扩展名，如 pdf/docx/txt） */
    private String fileType;
    /** 文件大小（字节） */
    private Long fileSize;

    // ===== 文档处理状态机（v2 新增） =====
    /** 处理状态：PENDING/PARSING/PARSED/CHUNKING/CHUNKED/VECTORIZING/COMPLETED/FAILED */
    private String processStatus;

    // ===== 版本与元数据 =====
    /** 分片总数 */
    private Integer chunkCount;
    /** 当前生效版本号 */
    private String version;
    /** 上传者/所有者ID */
    private Long ownerId;
    /** Weaviate集合名 T{tenantId}_Kb{kbId} */
    private String collectionName;
    /** 上传时间 */
    private Date uploadTime;
    /** 创建时间 */
    private Date createTime;
    /** 最后更新时间 */
    private Date updateTime;
}
