package com.rag.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 向量记录（入库用, Multi-Tenant v2）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorRecord {
    /** 记录ID */
    private String recordId;
    /** 文本内容 */
    private String text;
    /** 向量数据 */
    private List<Float> vector;
    /** 源文件名 */
    private String fileName;
    /** 文件类型 */
    private String fileType;
    /** 分片策略类型 */
    private String chunkType;
    /** 父分片ID */
    private String parentChunkId;
    /** 页码 */
    private Integer pageNo;
    /** 是否表格分片 */
    private Boolean tableFlag;
    /** 是否代码分片 */
    private Boolean codeFlag;
    /** 源文件路径 */
    private String sourcePath;
    /** 文件唯一ID（去重用） */
    private String fileId;
    /** 文本哈希（去重用） */
    private String textHash;
    // ==================== 版本字段 ====================
    /** 文档唯一标识 */
    private String documentId;
    /** 文档版本号（语义化版本） */
    private String documentVersion;
    /** 创建人/所有者ID */
    private Long ownerId;
    /** 扩展元数据 */
    private Map<String, Object> extraMeta;
    // ==================== 多租户字段 ====================
    /** 所属租户ID（审计） */
    private Long tenantId;
    /** 所属知识库ID（审计） */
    private Long kbId;
}
