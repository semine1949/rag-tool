package com.rag.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 用户-租户-角色关联实体（用户在某一租户内的单一身份）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserTenantRole {
    private Long id;
    private Long userId;
    private Long tenantId;
    private Long roleId;
    private Date createTime;
}
