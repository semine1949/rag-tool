package com.rag.core.entity;

import com.rag.core.enums.ChunkStrategyEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 分片实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Chunk {
    /** 分片ID */
    private String chunkId;
    /** 分片文本内容 */
    private String text;
    /** 父分片ID（父子分片策略时使用） */
    private String parentId;
    /** 产生此分片的策略类型 */
    private ChunkStrategyEnum chunkType;
    /** 分片文本哈希（用于去重） */
    private String textHash;
    /** 源文件元数据 */
    private String fileName;
    private String fileType;
    private String fileId;
    /** 页码（PDF等有页码的文件） */
    private Integer pageNo;
    /** 表格元数据（表格分片时携带） */
    private Map<String, Object> tableMeta;
    /** 代码元数据（代码分片时携带） */
    private Map<String, Object> codeMeta;
    /** 标题层级信息 */
    private Map<String, Object> titleMeta;
    // ==================== 版本与权限字段（需求6-7扩展） ====================
    /** 文档唯一标识 */
    private String documentId;
    /** 文档版本号（语义化版本，如v1.0.0） */
    private String documentVersion;
    /** 创建人/所有者ID */
    private Long ownerId;
    /** 可访问角色ID列表 */
    private List<Long> roleIds;
    /** 权限标签列表 */
    private List<String> permissionTags;
    /** 扩展属性 */
    private Map<String, Object> extraMeta;
}
