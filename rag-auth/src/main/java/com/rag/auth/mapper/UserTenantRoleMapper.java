package com.rag.auth.mapper;

import com.rag.core.entity.UserTenantRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户-租户-角色关联表 MyBatis Mapper
 */
@Mapper
public interface UserTenantRoleMapper {

    UserTenantRole findByUserAndTenant(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    List<UserTenantRole> findByUserId(@Param("userId") Long userId);

    List<UserTenantRole> findByTenantId(@Param("tenantId") Long tenantId);

    int insert(UserTenantRole record);

    int updateRole(@Param("id") Long id, @Param("roleId") Long roleId);

    int delete(@Param("id") Long id);

    int deleteByUserAndTenant(@Param("userId") Long userId, @Param("tenantId") Long tenantId);
}
