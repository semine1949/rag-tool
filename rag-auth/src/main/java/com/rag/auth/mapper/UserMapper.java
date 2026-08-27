package com.rag.auth.mapper;

import com.rag.common.entity.AuthUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 用户表 MyBatis Mapper (Multi-Tenant v2)
 */
@Mapper
public interface UserMapper {

    AuthUser findById(@Param("userId") Long userId);

    AuthUser findByUsername(@Param("username") String username);

    /** 查询全部用户（不含密码哈希），供管理端用户列表使用 */
    List<AuthUser> findAll();

    Integer existsByUsername(@Param("username") String username);

    int insert(AuthUser user);

    int update(AuthUser user);

    int updateStatus(@Param("userId") Long userId, @Param("status") Integer status);

    int updateLoginFailCount(@Param("userId") Long userId,
                             @Param("loginFailCount") Integer loginFailCount,
                             @Param("lastLoginTime") Date lastLoginTime);

    int updateLastLoginTime(@Param("userId") Long userId,
                            @Param("lastLoginTime") Date lastLoginTime);
}
