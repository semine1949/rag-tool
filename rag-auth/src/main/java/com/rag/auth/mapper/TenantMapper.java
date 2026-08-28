package com.rag.auth.mapper;

import com.rag.common.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 租户表 MyBatis Mapper
 */
@Mapper
public interface TenantMapper {

    /** 全量租户列表（供超级用户全局视图使用） */
    List<Tenant> findAll();

    Tenant findById(@Param("tenantId") Long tenantId);

    Tenant findByDefault(@Param("tenantName") String tenantName);

    int insert(Tenant tenant);

    int updateStatus(@Param("tenantId") Long tenantId, @Param("status") Integer status);

    int updateDefaultEmbeddingModel(@Param("tenantId") Long tenantId,
                                    @Param("defaultEmbeddingModel") String defaultEmbeddingModel);
}
