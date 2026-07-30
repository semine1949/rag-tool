package com.rag.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档分片实体（v2 新增）
 * <p>作为业务库与向量数据库之间的元数据映射桥梁，持久化全部分片原文。</p>
 * <p>核心职责：
 *   1. 持久化分片原文，支撑内容检索、溯源与上下文补全
 *   2. 记录分片在文档内的顺序与层级关系（parent_chunk_id）
 *   3. 承载向量数据库对象 ID（vector_id），实现业务ID→向量ID的双向映射
 *   4. 记录分片的父子类型（chunk_type），区分层级分片与扁平分片
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocChunk {
    /** 分片ID（主键） */
    private Long chunkId;
    /** 所属文档ID（关联 kb_document.doc_id） */
    private Long docId;
    /** 所属租户ID */
    private Long tenantId;
    /** 所属知识库ID */
    private Long kbId;
    /** 关联版本ID（doc_version.id），可为空以支持未版本化场景 */
    private Long versionId;
    /** 分片序号（从0开始，记录分片在文档中的顺序） */
    private Integer chunkIndex;
    /** 父子分片类型：parent=父分片, child=子分片, flat=扁平分片 */
    private String chunkType;
    /** 父分片ID（自引用，用于 PARENT_CHILD 分片策略的层级关系） */
    private Long parentChunkId;
    /** 分片原文（完整持久化，不依赖向量数据库存储原文） */
    private String content;
    /** 向量数据库中的对象 ID（如 Weaviate object ID），用于业务与向量间的双向映射 */
    private String vectorId;
    /** 创建时间 */
    private Date createTime;
    /** 最后更新时间 */
    private Date updateTime;
}
