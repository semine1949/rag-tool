package com.rag.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 知识库角色权限实体（哪些角色可访问该知识库）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbRolePermission {
    private Long id;
    private Long kbId;
    private Long roleId;
    private Date createTime;
}
