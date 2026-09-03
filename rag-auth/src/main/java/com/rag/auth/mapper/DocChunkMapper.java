package com.rag.auth.mapper;

import com.rag.common.entity.DocChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档分片表 MyBatis Mapper（v2 新增）
 */
@Mapper
public interface DocChunkMapper {

    /**
     * 按业务分片ID（chunk_id, UUID）查询单个分片。
     * <p>父子关联按 chunk_id 对齐：子块 parent_chunk_id = 父块 chunk_id，
     * 可用于按子块反查父块/按业务键溯源。</p>
     */
    DocChunk findByChunkId(@Param("chunkId") String chunkId);

    /**
     * 按父分片业务ID（parent_chunk_id）查询其全部子分片。
     */
    List<DocChunk> findByParentChunkId(@Param("parentChunkId") String parentChunkId);

    /**
     * 按文档ID查询全部分片（按 chunk_index 排序）
     */
    List<DocChunk> findByDocId(@Param("docId") Long docId);

    /**
     * 按文档ID和版本ID查询分片列表
     */
    List<DocChunk> findByDocIdAndVersion(@Param("docId") Long docId, @Param("versionId") Long versionId);

    /**
     * 批量插入分片
     */
    int batchInsert(@Param("list") List<DocChunk> chunks);

    /**
     * 插入单个分片
     */
    int insert(DocChunk chunk);

    /**
     * 按文档ID删除全部分片
     */
    int deleteByDocId(@Param("docId") Long docId);

    /**
     * 统计某文档的分片总数
     */
    int countByDocId(@Param("docId") Long docId);
}
