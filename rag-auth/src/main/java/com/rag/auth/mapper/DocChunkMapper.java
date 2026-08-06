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
     * 按分片ID查询
     */
    DocChunk findById(@Param("chunkId") Long chunkId);

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
