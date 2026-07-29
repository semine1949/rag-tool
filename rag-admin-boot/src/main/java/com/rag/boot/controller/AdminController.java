package com.rag.boot.controller;

import com.rag.auth.context.RequestContext;
import com.rag.auth.mapper.*;
import com.rag.auth.service.AuthServiceImpl;
import com.rag.auth.service.KbAccessService;
import com.rag.auth.service.KbConfigService;
import com.rag.core.config.EmbeddingConfig;
import com.rag.core.entity.*;
import com.rag.core.exception.RagException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理员接口（新数据库设计）
 * 权限由 KbAccessService 基于 user_tenant_role + kb_role_permission 判定。
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
        log.info("租户创建成功: tenantId={}, tenantName={}", tenant.getTenantId(), tenantName);
        return ResponseEntity.ok(Map.of("code", 200, "data", tenant));
    }

    @GetMapping("/tenant/list")
    public ResponseEntity<?> listTenants() {
        requireAuth();
        List<Tenant> tenants = new ArrayList<>();
        // 简易全量查询：通过角色关联反查所有租户成本较高，这里直接返回已关联租户集合
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
     * 任命用户在指定租户的角色（覆盖式）
     */
    @PostMapping("/tenant/{tenantId}/members")
    public ResponseEntity<?> assignTenantRole(@PathVariable Long tenantId,
                                              @RequestBody Map<String, Object> body) {
        Long opUserId = requireAuth();
        requireTenantAdminOfTenant(opUserId, tenantId);

        Long userId = objToLong(body.get("userId"));
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
        return ResponseEntity.ok(Map.of("code", 200, "msg", "租户角色已更新"));
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
        return ResponseEntity.ok(Map.of("code", 200,
                "data", Map.of("userId", user.getUserId(), "username", username)));
    }

    @GetMapping("/role/list")
    public ResponseEntity<?> listRoles() {
        requireAuth();
        return ResponseEntity.ok(Map.of("code", 200, "data", roleMapper.findAll()));
    }

    // ==================== 知识库管理 ====================

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
        String chunkStrategy = (String) body.get("chunkStrategy");
        Integer chunkSize = objToInt(body.get("chunkSize"));
        Integer chunkOverlap = objToInt(body.get("chunkOverlap"));
        String embeddingModel = (String) body.get("embeddingModel");
        if (embeddingModel == null || embeddingModel.isBlank()) {
            Tenant tenant = tenantMapper.findById(tenantId);
            embeddingModel = tenant != null ? tenant.getDefaultEmbeddingModel() : null;
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "必须提供 embeddingModel 或租户默认模型"));
        }

        KnowledgeBase kb = kbConfigService.createKnowledgeBase(
                tenantId, kbName, description, chunkStrategy, chunkSize, chunkOverlap, embeddingModel);
        return ResponseEntity.ok(Map.of("code", 200, "data", kb));
    }

    @GetMapping("/kb/list")
    public ResponseEntity<?> listKnowledgeBases(@RequestParam Long tenantId) {
        Long userId = requireAuth();
        requireTenantAdminOfTenant(userId, tenantId);
        return ResponseEntity.ok(Map.of("code", 200, "data", kbMapper.findByTenantId(tenantId)));
    }

    @GetMapping("/kb/{kbId}/config")
    public ResponseEntity<?> getKbConfig(@PathVariable Long kbId) {
        Long userId = requireAuth();
        kbAccessService.checkAdminPermission(userId, kbId);
        return ResponseEntity.ok(Map.of("code", 200, "data", buildConfigView(kbId)));
    }

    @PutMapping("/kb/{kbId}/config")
    public ResponseEntity<?> updateKbConfig(@PathVariable Long kbId, @RequestBody Map<String, Object> body) {
        Long userId = requireAuth();
        kbAccessService.checkAdminPermission(userId, kbId);
        String chunkStrategy = (String) body.get("chunkStrategy");
        Integer chunkSize = objToInt(body.get("chunkSize"));
        Integer chunkOverlap = objToInt(body.get("chunkOverlap"));
        String embeddingModel = (String) body.get("embeddingModel");
        kbConfigService.updateKbConfig(kbId, chunkStrategy, chunkSize, chunkOverlap, embeddingModel);
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

    private Map<String, Object> buildConfigView(Long kbId) {
        KnowledgeBase kb = kbConfigService.getKb(kbId);
        EmbeddingConfig emb = kbConfigService.loadEmbeddingConfig(kbId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kbId", kb.getKbId());
        view.put("tenantId", kb.getTenantId());
        view.put("kbName", kb.getKbName());
        view.put("description", kb.getDescription());
        view.put("chunkStrategy", kb.getChunkStrategy());
        view.put("chunkSize", kb.getChunkSize());
        view.put("chunkOverlap", kb.getChunkOverlap());
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

    private Integer objToInt(Object val) {
        if (val instanceof Number num) return num.intValue();
        if (val instanceof String str && !str.isBlank()) return Integer.parseInt(str);
        return null;
    }
}
