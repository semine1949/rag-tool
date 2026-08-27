package com.rag.controller;

import com.rag.auth.context.RequestContext;
import com.rag.auth.mapper.*;
import com.rag.auth.service.AuthServiceImpl;
import com.rag.auth.service.KbAccessService;
import com.rag.auth.service.KbConfigService;
import com.rag.common.entity.config.EmbeddingConfig;
import com.rag.common.entity.*;
import com.rag.common.exception.RagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理员接口（v2 重构：分片策略已从知识库下移到文档维度）
 * <p>权限由 KbAccessService 基于 user_tenant_role + kb_role_permission 判定。</p>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final RoleMapper roleMapper;
    private final UserTenantRoleMapper userTenantRoleMapper;
    private final KbRolePermissionMapper kbRolePermissionMapper;
    private final KbAccessService kbAccessService;
    private final KbConfigService kbConfigService;
    private final AuthServiceImpl authService;

    public AdminController(TenantMapper tenantMapper,
                           UserMapper userMapper,
                           KnowledgeBaseMapper kbMapper,
                           RoleMapper roleMapper,
                           UserTenantRoleMapper userTenantRoleMapper,
                           KbRolePermissionMapper kbRolePermissionMapper,
                           KbAccessService kbAccessService,
                           KbConfigService kbConfigService,
                           AuthServiceImpl authService) {
        this.tenantMapper = tenantMapper;
        this.userMapper = userMapper;
        this.kbMapper = kbMapper;
        this.roleMapper = roleMapper;
        this.userTenantRoleMapper = userTenantRoleMapper;
        this.kbRolePermissionMapper = kbRolePermissionMapper;
        this.kbAccessService = kbAccessService;
        this.kbConfigService = kbConfigService;
        this.authService = authService;
    }

    // ==================== 租户管理 ====================

    @PostMapping("/tenant")
    public ResponseEntity<?> createTenant(@RequestBody Map<String, String> body) {
        Long opUserId = requireAuth();
        requireTenantAdminAnywhere(opUserId);
        String tenantName = body.get("tenantName");
        if (tenantName == null || tenantName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "租户名称不能为空"));
        }
        Tenant tenant = Tenant.builder()
                .tenantName(tenantName)
                .defaultEmbeddingModel(body.get("defaultEmbeddingModel"))
                .status(1)
                .createTime(new Date())
                .build();
        tenantMapper.insert(tenant);
        // 内置初始化规则：创建租户后，自动将操作人授予该租户 TENANT_ADMIN，避免权限真空（谁创建、谁负责）
        Role taRole = roleMapper.findByCode(TENANT_ADMIN);
        if (taRole != null) {
            UserTenantRole existing = userTenantRoleMapper.findByUserAndTenant(opUserId, tenant.getTenantId());
            if (existing == null) {
                userTenantRoleMapper.insert(UserTenantRole.builder()
                        .userId(opUserId).tenantId(tenant.getTenantId()).roleId(taRole.getRoleId())
                        .createTime(new Date()).build());
            } else {
                userTenantRoleMapper.updateRole(existing.getId(), taRole.getRoleId());
            }
        }
        log.info("租户创建成功并初始化管理员: tenantId={}, tenantName={}, adminUserId={}",
                tenant.getTenantId(), tenantName, opUserId);
        return ResponseEntity.ok(Map.of("code", 200, "data", tenant));
    }

    @GetMapping("/tenant/list")
    public ResponseEntity<?> listTenants() {
        requireAuth();
        List<Tenant> tenants = new ArrayList<>();
        Long userId = RequestContext.currentUserId();
        List<UserTenantRole> utrs = userTenantRoleMapper.findByUserId(userId);
        Set<Long> tenantIds = utrs.stream().map(UserTenantRole::getTenantId).collect(Collectors.toSet());
        for (Long tid : tenantIds) {
            Tenant t = tenantMapper.findById(tid);
            if (t != null) tenants.add(t);
        }
        return ResponseEntity.ok(Map.of("code", 200, "data", tenants));
    }

    /**
     * 租户管理员指派运维接口。
     * action 取值：
     *   - GRANT  ：授予/升级指定用户在该租户的指定角色（一个租户允许多名管理员，授予非替换）
     *   - REVOKE ：撤销指定用户在该租户的角色记录（移除管理员身份）
     */
    @PostMapping("/tenant/{tenantId}/members")
    public ResponseEntity<?> assignTenantRole(@PathVariable Long tenantId,
                                              @RequestBody Map<String, Object> body) {
        Long opUserId = requireAuth();
        requireTenantAdminOfTenant(opUserId, tenantId);

        Long userId = objToLong(body.get("userId"));
        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "userId 必填"));
        }
        String action = body.getOrDefault("action", "GRANT").toString().toUpperCase();

        // 撤销：删除该用户在此租户的角色绑定
        if ("REVOKE".equals(action)) {
            if (userId.equals(opUserId)) {
                return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "不能撤销自己的租户管理员身份"));
            }
            userTenantRoleMapper.deleteByUserAndTenant(userId, tenantId);
            log.info("已撤销用户租户角色: userId={}, tenantId={}", userId, tenantId);
            return ResponseEntity.ok(Map.of("code", 200, "msg", "租户角色已撤销"));
        }

        // 授予：授予/升级指定角色
        String roleCode = (String) body.get("roleCode");
        validateRoleCode(roleCode);
        Role role = roleMapper.findByCode(roleCode);
        if (role == null) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "角色不存在: " + roleCode));
        }
        UserTenantRole existing = userTenantRoleMapper.findByUserAndTenant(userId, tenantId);
        if (existing != null) {
            userTenantRoleMapper.updateRole(existing.getId(), role.getRoleId());
        } else {
            userTenantRoleMapper.insert(UserTenantRole.builder()
                    .userId(userId).tenantId(tenantId).roleId(role.getRoleId())
                    .createTime(new Date()).build());
        }
        log.info("已授予用户租户角色: userId={}, tenantId={}, roleCode={}", userId, tenantId, roleCode);
        return ResponseEntity.ok(Map.of("code", 200, "msg", "租户角色已授予"));
    }

    // ==================== 用户管理 ====================

    @PostMapping("/user")
    public ResponseEntity<?> createUser(@RequestBody Map<String, Object> body) {
        Long opUserId = requireAuth();
        requireTenantAdminAnywhere(opUserId);
        String username = (String) body.get("username");
        String password = (String) body.getOrDefault("password", "rag123456");
        String nickname = (String) body.get("nickname");
        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "用户名不能为空"));
        }
        AuthUser user = AuthUser.builder()
                .username(username)
                .nickname(nickname)
                .passwordHash(authService.encodePassword(password))
                .status(1)
                .build();
        user = authService.createUser(user);

        // 联动：将用户绑定到所选租户并授予所选角色（写入 user_tenant_role）
        Long tenantId = objToLong(body.get("tenantId"));
        Object roleCodesObj = body.get("roleCodes");
        String boundRoleCode = null;
        if (tenantId != null && roleCodesObj instanceof List<?> roleList && !roleList.isEmpty()) {
            // 数据模型为一用户一租户一角色（user_tenant_role 按 user+tenant 唯一），取第一个所选角色绑定
            String roleCode = roleList.get(0) == null ? null : roleList.get(0).toString();
            if (roleCode != null && !roleCode.isBlank()) {
                validateRoleCode(roleCode);
                Role role = roleMapper.findByCode(roleCode);
                if (role != null) {
                    UserTenantRole existing = userTenantRoleMapper.findByUserAndTenant(user.getUserId(), tenantId);
                    if (existing != null) {
                        userTenantRoleMapper.updateRole(existing.getId(), role.getRoleId());
                    } else {
                        userTenantRoleMapper.insert(UserTenantRole.builder()
                                .userId(user.getUserId()).tenantId(tenantId).roleId(role.getRoleId())
                                .createTime(new Date()).build());
                    }
                    boundRoleCode = roleCode;
                }
            }
        }
        log.info("创建用户并绑定租户角色: userId={}, username={}, tenantId={}, roleCode={}",
                user.getUserId(), username, tenantId, boundRoleCode);
        return ResponseEntity.ok(Map.of("code", 200,
                "data", Map.of("userId", user.getUserId(), "username", username,
                        "tenantId", tenantId, "roleCode", boundRoleCode)));
    }

    /**
     * 用户列表（管理端）
     * 返回全部用户及其所属租户与角色，供管理页展示与创建用户时选择租户。
     * data: [{ userId, username, nickname, status, createTime, tenantId, tenantName, roleCodes: [] }]
     */
    @GetMapping("/user")
    public ResponseEntity<?> listUsers() {
        Long opUserId = requireAuth();
        requireTenantAdminAnywhere(opUserId);

        List<AuthUser> users = userMapper.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AuthUser u : users) {
            // 该用户的全部租户角色绑定
            List<UserTenantRole> utrs = userTenantRoleMapper.findByUserId(u.getUserId());
            // 取第一个绑定租户作为默认所属（一个用户通常属于一个主租户）
            Long tenantId = utrs.isEmpty() ? null : utrs.get(0).getTenantId();
            String tenantName = null;
            if (tenantId != null) {
                Tenant t = tenantMapper.findById(tenantId);
                tenantName = t != null ? t.getTenantName() : null;
            }
            // 收集角色编码
            List<String> roleCodes = new ArrayList<>();
            if (!utrs.isEmpty()) {
                List<Long> roleIds = utrs.stream().map(UserTenantRole::getRoleId).collect(Collectors.toList());
                List<Role> roles = roleMapper.findByIds(roleIds);
                for (Role r : roles) {
                    if (r != null) roleCodes.add(r.getRoleCode());
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", u.getUserId());
            item.put("username", u.getUsername());
            item.put("nickname", u.getNickname());
            item.put("status", u.getStatus());
            item.put("createTime", u.getCreateTime());
            item.put("tenantId", tenantId);
            item.put("tenantName", tenantName);
            item.put("roleCodes", roleCodes);
            result.add(item);
        }
        return ResponseEntity.ok(Map.of("code", 200, "data", result));
    }

    @GetMapping("/role/list")
    public ResponseEntity<?> listRoles() {
        requireAuth();
        return ResponseEntity.ok(Map.of("code", 200, "data", roleMapper.findAll()));
    }

    // ==================== 知识库管理（v2：chunk 策略已移至文档维度） ====================

    /**
     * 创建知识库（v2：仅需 embedding_model，chunk 策略由文档维度独立配置）
     */
    @PostMapping("/kb")
    public ResponseEntity<?> createKnowledgeBase(@RequestBody Map<String, Object> body) {
        Long userId = requireAuth();
        Long tenantId = objToLong(body.get("tenantId"));
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "tenantId 必填"));
        }
        requireTenantAdminOfTenant(userId, tenantId);

        String kbName = (String) body.get("kbName");
        String description = (String) body.get("description");
        String embeddingModel = (String) body.get("embeddingModel");
        if (embeddingModel == null || embeddingModel.isBlank()) {
            Tenant tenant = tenantMapper.findById(tenantId);
            embeddingModel = tenant != null ? tenant.getDefaultEmbeddingModel() : null;
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "必须提供 embeddingModel 或租户默认模型"));
        }

        KnowledgeBase kb = kbConfigService.createKnowledgeBase(
                tenantId, kbName, description, embeddingModel);
        return ResponseEntity.ok(Map.of("code", 200, "data", kb));
    }

    @GetMapping("/kb/list")
    public ResponseEntity<?> listKnowledgeBases() {
        Long userId = requireAuth();
        // 从当前用户租户归属中推导 tenantId
        List<UserTenantRole> utrs = userTenantRoleMapper.findByUserId(userId);
        if (utrs.isEmpty()) {
            return ResponseEntity.ok(Map.of("code", 200, "data", Collections.emptyList()));
        }
        Long tenantId = utrs.get(0).getTenantId();
        return ResponseEntity.ok(Map.of("code", 200, "data", kbMapper.findByTenantId(tenantId)));
    }

    @GetMapping("/kb/{kbId}/config")
    public ResponseEntity<?> getKbConfig(@PathVariable Long kbId) {
        Long userId = requireAuth();
        kbAccessService.checkAdminPermission(userId, kbId);
        return ResponseEntity.ok(Map.of("code", 200, "data", buildConfigView(kbId)));
    }

    /**
     * 更新知识库配置（v2：仅支持更新 embedding_model）
     */
    @PutMapping("/kb/{kbId}/config")
    public ResponseEntity<?> updateKbConfig(@PathVariable Long kbId, @RequestBody Map<String, Object> body) {
        Long userId = requireAuth();
        kbAccessService.checkAdminPermission(userId, kbId);
        String embeddingModel = (String) body.get("embeddingModel");
        if (embeddingModel != null && !embeddingModel.isBlank()) {
            kbConfigService.updateKbEmbeddingModel(kbId, embeddingModel);
        }
        return ResponseEntity.ok(Map.of("code", 200, "msg", "配置更新成功"));
    }

    // ==================== 知识库角色权限管理 ====================

    @GetMapping("/kb/{kbId}/permissions")
    public ResponseEntity<?> listKbPermissions(@PathVariable Long kbId) {
        Long userId = requireAuth();
        kbAccessService.checkViewPermission(userId, kbId);
        List<String> roleCodes = kbRolePermissionMapper.findByKbId(kbId).stream()
                .map(p -> {
                    Role r = roleMapper.findById(p.getRoleId());
                    return r != null ? r.getRoleCode() : null;
                })
                .filter(Objects::nonNull)
                .toList();
        return ResponseEntity.ok(Map.of("code", 200, "data", roleCodes));
    }

    @PostMapping("/kb/{kbId}/permissions")
    public ResponseEntity<?> addKbPermission(@PathVariable Long kbId, @RequestBody Map<String, Object> body) {
        Long userId = requireAuth();
        kbAccessService.checkAdminPermission(userId, kbId);
        String roleCode = (String) body.get("roleCode");
        validateRoleCode(roleCode);
        Role role = roleMapper.findByCode(roleCode);
        if (role == null) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "角色不存在: " + roleCode));
        }
        if (kbRolePermissionMapper.findByKbAndRole(kbId, role.getRoleId()) == null) {
            kbRolePermissionMapper.insert(KbRolePermission.builder()
                    .kbId(kbId).roleId(role.getRoleId()).createTime(new Date()).build());
        }
        return ResponseEntity.ok(Map.of("code", 200, "msg", "已添加知识库角色权限"));
    }

    @DeleteMapping("/kb/{kbId}/permissions/{roleCode}")
    public ResponseEntity<?> removeKbPermission(@PathVariable Long kbId, @PathVariable String roleCode) {
        Long userId = requireAuth();
        kbAccessService.checkAdminPermission(userId, kbId);
        Role role = roleMapper.findByCode(roleCode);
        if (role != null) {
            kbRolePermissionMapper.deleteByKbAndRole(kbId, role.getRoleId());
        }
        return ResponseEntity.ok(Map.of("code", 200, "msg", "已移除知识库角色权限"));
    }

    // ==================== 集合管理 ====================

    @PostMapping("/kb/{kbId}/collection/init")
    public ResponseEntity<?> initCollection(@PathVariable Long kbId) {
        Long userId = requireAuth();
        kbAccessService.checkAdminPermission(userId, kbId);
        kbConfigService.initKbCollection(kbId);
        return ResponseEntity.ok(Map.of("code", 200, "msg", "集合已初始化"));
    }

    @PostMapping("/kb/{kbId}/collection/clear")
    public ResponseEntity<?> clearCollection(@PathVariable Long kbId) {
        Long userId = requireAuth();
        kbAccessService.checkAdminPermission(userId, kbId);
        kbConfigService.clearKbCollection(kbId);
        return ResponseEntity.ok(Map.of("code", 200, "msg", "集合已清空"));
    }

    @PostMapping("/kb/{kbId}/collection/drop")
    public ResponseEntity<?> dropCollection(@PathVariable Long kbId) {
        Long userId = requireAuth();
        kbAccessService.checkAdminPermission(userId, kbId);
        kbConfigService.dropKbCollection(kbId);
        return ResponseEntity.ok(Map.of("code", 200, "msg", "集合已删除"));
    }

    @PostMapping("/kb/{kbId}/reprocess")
    public ResponseEntity<?> reprocess(@PathVariable Long kbId) {
        Long userId = requireAuth();
        kbAccessService.checkAdminPermission(userId, kbId);
        kbConfigService.reprocessKnowledgeBase(kbId);
        return ResponseEntity.ok(Map.of("code", 200, "msg", "已触发重处理"));
    }

    // ==================== 辅助方法 ====================

    /** v2：构建知识库配置视图（不含 chunk 策略字段，chunk 策略已移至文档维度） */
    private Map<String, Object> buildConfigView(Long kbId) {
        KnowledgeBase kb = kbConfigService.getKb(kbId);
        EmbeddingConfig emb = kbConfigService.loadEmbeddingConfig(kbId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kbId", kb.getKbId());
        view.put("tenantId", kb.getTenantId());
        view.put("kbName", kb.getKbName());
        view.put("description", kb.getDescription());
        view.put("embeddingModel", kb.getEmbeddingModel());
        view.put("collectionName", KbConfigService.deriveCollectionName(kb.getTenantId(), kb.getKbId()));
        view.put("vectorDim", emb.getVectorDim());
        return view;
    }

    private static final String TENANT_ADMIN = AuthServiceImpl.TENANT_ROLE_ADMIN;

    private Long requireAuth() {
        Long userId = RequestContext.currentUserId();
        if (userId == null) {
            throw new RagException("AUTH_REQUIRED", "请先登录或提供 API-Key");
        }
        return userId;
    }

    private void requireTenantAdminAnywhere(Long userId) {
        if (!kbAccessService.hasTenantAdminRole(userId)) {
            throw new RagException("PERM_DENIED", "仅租户管理员可执行此操作");
        }
    }

    private void requireTenantAdminOfTenant(Long userId, Long tenantId) {
        String role = kbAccessService.resolveTenantRole(userId, tenantId);
        if (!TENANT_ADMIN.equals(role)) {
            throw new RagException("PERM_DENIED", "仅租户管理员可执行此操作");
        }
    }

    private void validateRoleCode(String roleCode) {
        if (roleCode == null) {
            throw new RagException("PERM_DENIED", "roleCode 必填");
        }
        if (!List.of(TENANT_ADMIN, "KB_ADMIN", "CONTRIBUTOR", "VIEWER").contains(roleCode)) {
            throw new RagException("PERM_DENIED", "roleCode 取值: TENANT_ADMIN/KB_ADMIN/CONTRIBUTOR/VIEWER");
        }
    }

    private Long objToLong(Object val) {
        if (val instanceof Number num) return num.longValue();
        if (val instanceof String str && !str.isBlank()) return Long.parseLong(str);
        return null;
    }

}
