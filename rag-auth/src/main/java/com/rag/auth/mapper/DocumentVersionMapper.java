package com.rag.auth.mapper;

import com.rag.core.entity.DocumentVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档版本表 MyBatis Mapper
 */
@Mapper
public interface DocumentVersionMapper {

    List<DocumentVersion> findByDocumentId(@Param("documentId") String documentId);

    DocumentVersion findCurrentVersion(@Param("documentId") String documentId);

    DocumentVersion findByDocIdAndVersion(@Param("documentId") String documentId,
                                          @Param("version") String version);

    DocumentVersion findLatestVersion(@Param("documentId") String documentId);

    int insert(DocumentVersion version);

    int deactivateAll(@Param("documentId") String documentId);

    int activeVersion(@Param("documentId") String documentId,
                      @Param("version") String version);

    int deleteOlderThan(@Param("documentId") String documentId,
                        @Param("keepCount") int keepCount);
}
