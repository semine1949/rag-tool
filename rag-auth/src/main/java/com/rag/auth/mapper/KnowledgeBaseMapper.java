package com.rag.auth.mapper;

import com.rag.core.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库表 MyBatis Mapper（v2：移除 chunk 策略列，保留 embedding_model）
 */
@Mapper
public interface KnowledgeBaseMapper {

    KnowledgeBase findById(@Param("kbId") Long kbId);

    List<KnowledgeBase> findByTenantId(@Param("tenantId") Long tenantId);

    int insert(KnowledgeBase kb);

    int update(KnowledgeBase kb);

    int updateStatus(@Param("kbId") Long kbId, @Param("status") Integer status);

    /** v2：仅更新 embedding_model，chunk 策略已下沉至文档维度 */
    int updateEmbeddingModel(@Param("kbId") Long kbId, @Param("embeddingModel") String embeddingModel);
}
