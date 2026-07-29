package com.rag.auth.mapper;

import com.rag.core.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 租户表 MyBatis Mapper
 */
@Mapper
public interface TenantMapper {

    Tenant findById(@Param("tenantId") Long tenantId);

    Tenant findByDefault(@Param("tenantName") String tenantName);

    int insert(Tenant tenant);

    int updateStatus(@Param("tenantId") Long tenantId, @Param("status") Integer status);

    int updateDefaultEmbeddingModel(@Param("tenantId") Long tenantId,
                                    @Param("defaultEmbeddingModel") String defaultEmbeddingModel);
}
