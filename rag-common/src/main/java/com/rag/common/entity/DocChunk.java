package com.rag.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档分片实体（v2 新增，v3 分片策略下沉，v4 分片 ID 语义统一）
 * <p>作为业务库与向量数据库之间的元数据映射桥梁，持久化全部分片原文。</p>
 * <p>核心职责：
 *   1. 持久化分片原文，支撑内容检索、溯源与上下文补全
 *   2. 记录分片在文档内的顺序与层级关系（parent_chunk_id）
 *   3. 承载向量数据库对象 ID（vector_id），实现业务ID→向量ID的双向映射
 *   4. 记录分片的父子类型（chunk_type），区分层级分片与扁平分片
 *   5. [v3 新增] 记录分片策略（chunk_mode）与参数快照（params_snapshot），
 *      分片策略从文档维度下沉到分片维度，支持分片级溯源与策略复盘
 *   6. [v4] doc_chunk_id 与 chunk_id 分离：
 *      doc_chunk_id=表自增主键（MySQL 行身份）；chunk_id=业务分片 ID(UUID)，
 *      与向量元数据/检索/前端/父子关联全链路一致，消除命名歧义
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocChunk {
    /** 分片表主键（自增，MySQL 行身份；不承担业务键） */
    private Long docChunkId;
    /** 业务分片ID（UUID，与向量元数据 chunkId 一致），父块/子块/扁平块每行唯一 */
    private String chunkId;
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
    /** 父分片业务ID（存父块的 chunk_id(UUID)，用于层级策略的父子关联；parent/flat 为 null） */
    private String parentChunkId;
    /** 分片原文（完整持久化，不依赖向量数据库存储原文） */
    private String content;
    /** 向量数据库中的对象 ID（如 Weaviate object ID），用于业务与向量间的双向映射 */
    private String vectorId;
    /** [v3 新增] 本分片采用的分片策略名（如 FIXED_SIZE / TEXT_MODEL / HIERARCHICAL_MODEL） */
    private String chunkMode;
    /** [v3 新增] 分片参数快照（JSON），记录该分片实际生效的分块参数 */
    private String paramsSnapshot;
    /** 创建时间 */
    private Date createTime;
    /** 最后更新时间 */
    private Date updateTime;
}
