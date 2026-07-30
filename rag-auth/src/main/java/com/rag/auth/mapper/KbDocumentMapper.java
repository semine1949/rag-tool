package com.rag.auth.mapper;

import com.rag.core.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库文档登记表 MyBatis Mapper（v2 重构）
 */
@Mapper
public interface KbDocumentMapper {

    KbDocument findById(@Param("docId") Long docId);

    List<KbDocument> findByKbId(@Param("kbId") Long kbId);

    int insert(KbDocument doc);

    int deleteByDocId(@Param("docId") Long docId);

    int updateChunkCount(@Param("docId") Long docId, @Param("chunkCount") Integer chunkCount);

    /** v2 新增：更新处理状态 */
    int updateProcessStatus(@Param("docId") Long docId, @Param("processStatus") String processStatus);

    /** v2 新增：更新文档维度的分片策略配置 */
    int updateChunkConfig(@Param("docId") Long docId,
                          @Param("chunkStrategy") String chunkStrategy,
                          @Param("chunkSize") Integer chunkSize,
                          @Param("chunkOverlap") Integer chunkOverlap);
}
