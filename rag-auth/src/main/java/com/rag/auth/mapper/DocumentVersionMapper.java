package com.rag.auth.mapper;

import com.rag.common.entity.DocumentVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档版本表 MyBatis Mapper（v2：documentId(String)→docId(Long)，列名 document_id→doc_id）
 */
@Mapper
public interface DocumentVersionMapper {

    List<DocumentVersion> findByDocumentId(@Param("docId") Long docId);

    DocumentVersion findCurrentVersion(@Param("docId") Long docId);

    DocumentVersion findByDocIdAndVersion(@Param("docId") Long docId,
                                          @Param("version") String version);

    DocumentVersion findLatestVersion(@Param("docId") Long docId);

    int insert(DocumentVersion version);

    int deactivateAll(@Param("docId") Long docId);

    int activeVersion(@Param("docId") Long docId,
                      @Param("version") String version);

    int deleteOlderThan(@Param("docId") Long docId,
                        @Param("keepCount") int keepCount);
}
