package com.rag.auth.mapper;

import com.rag.core.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库表 MyBatis Mapper（扁平配置列）
 */
@Mapper
public interface KnowledgeBaseMapper {

    KnowledgeBase findById(@Param("kbId") Long kbId);

    List<KnowledgeBase> findByTenantId(@Param("tenantId") Long tenantId);

    int insert(KnowledgeBase kb);

    int update(KnowledgeBase kb);

    int updateStatus(@Param("kbId") Long kbId, @Param("status") Integer status);

    int updateConfig(@Param("kbId") Long kbId,
                     @Param("chunkStrategy") String chunkStrategy,
                     @Param("chunkSize") Integer chunkSize,
                     @Param("chunkOverlap") Integer chunkOverlap,
                     @Param("embeddingModel") String embeddingModel);
}
