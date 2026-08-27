package com.rag.controller;

import com.rag.auth.context.RequestContext;
import com.rag.auth.mapper.*;
import com.rag.common.api.AuthService;
import com.rag.common.entity.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 认证鉴权接口（需求6）
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserTenantRoleMapper userTenantRoleMapper;
    private final RoleMapper roleMapper;

    public AuthController(AuthService authService,
                          UserTenantRoleMapper userTenantRoleMapper,
                          RoleMapper roleMapper) {
        this.authService = authService;
        this.userTenantRoleMapper = userTenantRoleMapper;
        this.roleMapper = roleMapper;
    }

    /**
     * 用户名密码登录
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        try {
            String token = authService.login(body.get("username"), body.get("password"));
            return ResponseEntity.ok(Map.of("code", 200, "accessToken", token, "msg", "登录成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("code", 401, "msg", e.getMessage()));
        }
    }

    /**
     * 刷新Token
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        try {
            String newToken = authService.refreshToken(body.get("refreshToken"));
            return ResponseEntity.ok(Map.of("code", 200, "accessToken", newToken));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "msg", e.getMessage()));
        }
    }

    /**
     * 注册用户
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AuthUser user) {
        try {
            AuthUser created = authService.createUser(user);
            return ResponseEntity.ok(Map.of("code", 200, "userId", created.getUserId(), "msg", "注册成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", e.getMessage()));
        }
    }

    /**
     * 获取当前用户信息（需Token）
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<?> userInfo(@PathVariable Long userId) {
        AuthUser user = authService.getUserById(userId);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("code", 200, "data", user));
    }

    /**
     * 获取当前登录用户信息（含角色），从 JWT 上下文推导
     */
    @GetMapping("/me")
    public ResponseEntity<?> currentUser() {
        Long userId = RequestContext.currentUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("code", 401, "msg", "未登录"));
        }
        AuthUser user = authService.getUserById(userId);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        // 查询用户租户角色，取最高角色
        String roleCode = null;
        String roleName = null;
        List<UserTenantRole> utrs = userTenantRoleMapper.findByUserId(userId);
        if (!utrs.isEmpty()) {
            // 按角色层级取最高：TENANT_ADMIN > KB_ADMIN > CONTRIBUTOR > VIEWER
            Set<Long> roleIds = utrs.stream().map(UserTenantRole::getRoleId).collect(Collectors.toSet());
            List<Role> roles = roleMapper.findAll().stream()
                    .filter(r -> roleIds.contains(r.getRoleId()))
                    .toList();
            // 按优先级排序取最高
            List<String> priority = List.of("TENANT_ADMIN", "KB_ADMIN", "CONTRIBUTOR", "VIEWER");
            Role highest = roles.stream()
                    .min(Comparator.comparingInt(r -> priority.indexOf(r.getRoleCode())))
                    .orElse(null);
            if (highest != null) {
                roleCode = highest.getRoleCode();
                roleName = highest.getRoleName();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", user.getUserId());
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        result.put("roleCode", roleCode);
        result.put("roleName", roleName);
        return ResponseEntity.ok(Map.of("code", 200, "data", result));
    }
}
