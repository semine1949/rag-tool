package com.rag.controller;

import com.rag.common.api.AuthService;
import com.rag.common.entity.AuthUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证鉴权接口（需求6）
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
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
}
