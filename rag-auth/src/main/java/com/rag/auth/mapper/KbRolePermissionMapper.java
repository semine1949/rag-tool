package com.rag.auth.mapper;

import com.rag.core.entity.KbRolePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识库角色权限表 MyBatis Mapper
 */
@Mapper
public interface KbRolePermissionMapper {

    List<KbRolePermission> findByKbId(@Param("kbId") Long kbId);

    KbRolePermission findByKbAndRole(@Param("kbId") Long kbId, @Param("roleId") Long roleId);

    int insert(KbRolePermission permission);

    int delete(@Param("id") Long id);

    int deleteByKbAndRole(@Param("kbId") Long kbId, @Param("roleId") Long roleId);
}
