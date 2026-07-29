package com.rag.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 角色实体（RBAC）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Role {
    /** 角色ID */
    private Long roleId;
    /** 角色编码 */
    private String roleCode;
    /** 角色名称 */
    private String roleName;
    /** 角色描述 */
    private String description;
    /** 状态：1=启用, 0=禁用 */
    private Integer status;
    /** 创建时间 */
    private Date createTime;
}
