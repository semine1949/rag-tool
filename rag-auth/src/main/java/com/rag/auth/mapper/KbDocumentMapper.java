package com.rag.auth.mapper;

import com.rag.core.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库文档登记表 MyBatis Mapper
 */
@Mapper
public interface KbDocumentMapper {

    KbDocument findById(@Param("docId") Long docId);

    KbDocument findByFileId(@Param("fileId") String fileId);

    List<KbDocument> findByKbId(@Param("kbId") Long kbId);

    int insert(KbDocument doc);

    int deleteByFileId(@Param("fileId") String fileId);

    int updateChunkCount(@Param("fileId") String fileId, @Param("chunkCount") Integer chunkCount);
}
