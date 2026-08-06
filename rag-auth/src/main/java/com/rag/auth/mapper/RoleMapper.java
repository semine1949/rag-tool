package com.rag.auth.mapper;

import com.rag.common.entity.Role;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色字典表 MyBatis Mapper
 */
@Mapper
public interface RoleMapper {

    Role findById(@Param("roleId") Long roleId);

    Role findByCode(@Param("roleCode") String roleCode);

    List<Role> findAll();

    List<Role> findByIds(@Param("roleIds") List<Long> roleIds);
}
