package com.rag.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 认证用户实体
 * 用户为全局主体，不含 tenant_id；租户归属由 user_tenant_role 关联表承载。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthUser {
    /** 用户ID */
    private Long userId;
    /** 用户名（全局唯一） */
    private String username;
    /** 昵称 */
    private String nickname;
    /** 密码哈希（BCrypt） */
    private String passwordHash;
    /** 用户状态：1=正常, 0=禁用, -1=锁定 */
    private Integer status;
    /** 登录失败次数 */
    private Integer loginFailCount;
    /** 最后登录时间 */
    private Date lastLoginTime;
    /** 创建时间 */
    private Date createTime;
}
