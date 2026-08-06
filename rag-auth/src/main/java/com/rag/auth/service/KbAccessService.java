package com.rag.auth.service;

import com.rag.auth.context.RequestContext;
import com.rag.auth.mapper.KbRolePermissionMapper;
import com.rag.auth.mapper.KnowledgeBaseMapper;
import com.rag.auth.mapper.RoleMapper;
import com.rag.auth.mapper.UserTenantRoleMapper;
import com.rag.common.entity.KbRolePermission;
import com.rag.common.entity.KnowledgeBase;
import com.rag.common.entity.Role;
import com.rag.common.entity.UserTenantRole;
import com.rag.common.exception.RagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 知识库访问权限服务
 * 权限模型：用户在某一租户内的单一角色（user_tenant_role）∩ 知识库允许的角色（kb_role_permission）
 * - TENANT_ADMIN：本租户全部 KB 的完全控制（不受 kb_role_permission 限制）
 * - KB_ADMIN：仅 kb_role_permission 含 KB_ADMIN 的 KB 可配置/管理成员
 * - CONTRIBUTOR：仅含 CONTRIBUTOR 的 KB 可上传/查看
 * - VIEWER：仅含 VIEWER 的 KB 可查看/检索
 */
public class KbAccessService {

    private static final Logger log = LoggerFactory.getLogger(KbAccessService.class);

    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    public static final String KB_ADMIN = "KB_ADMIN";
    public static final String CONTRIBUTOR = "CONTRIBUTOR";
    public static final String VIEWER = "VIEWER";
    public static final String NONE = "NONE";

    private final KnowledgeBaseMapper kbMapper;
    private final UserTenantRoleMapper userTenantRoleMapper;
    private final KbRolePermissionMapper kbRolePermissionMapper;
    private final RoleMapper roleMapper;

    public KbAccessService(KnowledgeBaseMapper kbMapper,
                           UserTenantRoleMapper userTenantRoleMapper,
                           KbRolePermissionMapper kbRolePermissionMapper,
                           RoleMapper roleMapper) {
        this.kbMapper = kbMapper;
        this.userTenantRoleMapper = userTenantRoleMapper;
        this.kbRolePermissionMapper = kbRolePermissionMapper;
        this.roleMapper = roleMapper;
    }

    /**
     * 解析用户在某知识库的最终角色（按优先级：TENANT_ADMIN > KB_ADMIN > CONTRIBUTOR > VIEWER > NONE）
     */
    public String resolveKbRole(Long userId, Long kbId) {
        if (userId == null || kbId == null) return NONE;

        KnowledgeBase kb = kbMapper.findById(kbId);
        if (kb == null) return NONE;

        UserTenantRole utr = userTenantRoleMapper.findByUserAndTenant(userId, kb.getTenantId());
        if (utr == null) return NONE;

        Role role = roleMapper.findById(utr.getRoleId());
        if (role == null) return NONE;
        String userRoleCode = role.getRoleCode();

        // 租户管理员对本租户所有 KB 拥有完全控制
        if (TENANT_ADMIN.equals(userRoleCode)) {
            return TENANT_ADMIN;
        }

        // 其余角色需知识库显式授予
        List<KbRolePermission> perms = kbRolePermissionMapper.findByKbId(kbId);
        for (KbRolePermission perm : perms) {
            Role permRole = roleMapper.findById(perm.getRoleId());
            if (permRole != null && userRoleCode.equals(permRole.getRoleCode())) {
                return userRoleCode;
            }
        }
        return NONE;
    }

    public String resolveKbRoleFromContext(Long kbId) {
        Long userId = RequestContext.currentUserId();
        if (userId == null) return NONE;
        return resolveKbRole(userId, kbId);
    }

    /**
     * 解析用户在指定租户的角色编码（可能为 null）
     */
    public String resolveTenantRole(Long userId, Long tenantId) {
        if (userId == null || tenantId == null) return null;
        UserTenantRole utr = userTenantRoleMapper.findByUserAndTenant(userId, tenantId);
        if (utr == null) return null;
        Role role = roleMapper.findById(utr.getRoleId());
        return role != null ? role.getRoleCode() : null;
    }

    /**
     * 判断用户是否为任意租户的租户管理员（用于租户/用户创建等操作）
     */
    public boolean hasTenantAdminRole(Long userId) {
        if (userId == null) return false;
        List<UserTenantRole> list = userTenantRoleMapper.findByUserId(userId);
        for (UserTenantRole utr : list) {
            Role role = roleMapper.findById(utr.getRoleId());
            if (role != null && TENANT_ADMIN.equals(role.getRoleCode())) {
                return true;
            }
        }
        return false;
    }

    public void checkUploadPermission(Long userId, Long kbId) {
        String role = resolveKbRole(userId, kbId);
        if (!KB_ADMIN.equals(role) && !CONTRIBUTOR.equals(role) && !TENANT_ADMIN.equals(role)) {
            throw new RagException("PERM_DENIED",
                    String.format("用户 %d 无权向知识库 %d 上传文件（当前角色: %s）", userId, kbId, role));
        }
    }

    public void checkAdminPermission(Long userId, Long kbId) {
        String role = resolveKbRole(userId, kbId);
        if (!KB_ADMIN.equals(role) && !TENANT_ADMIN.equals(role)) {
            throw new RagException("PERM_DENIED",
                    String.format("用户 %d 无权管理知识库 %d 配置（当前角色: %s）", userId, kbId, role));
        }
    }

    public void checkViewPermission(Long userId, Long kbId) {
        String role = resolveKbRole(userId, kbId);
        if (NONE.equals(role)) {
            throw new RagException("PERM_DENIED",
                    String.format("用户 %d 无权访问知识库 %d", userId, kbId));
        }
    }

    public void checkMemberManagePermission(Long userId, Long kbId) {
        checkAdminPermission(userId, kbId);
    }
}
