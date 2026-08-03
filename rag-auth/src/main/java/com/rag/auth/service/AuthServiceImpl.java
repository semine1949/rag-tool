package com.rag.auth.service;

import com.rag.auth.jwt.JwtTokenProvider;
import com.rag.auth.mapper.RoleMapper;
import com.rag.auth.mapper.TenantMapper;
import com.rag.auth.mapper.UserMapper;
import com.rag.auth.mapper.UserTenantRoleMapper;
import com.rag.core.api.AuthService;
import com.rag.core.entity.AuthUser;
import com.rag.core.entity.Role;
import com.rag.core.entity.Tenant;
import com.rag.core.entity.UserTenantRole;
import com.rag.core.exception.AuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证服务 MySQL 持久化实现
 * 用户为全局主体，租户归属通过 user_tenant_role 关联表承载。
 * refreshToken 保留内存态（会话缓存，非持久化范畴）。
 */
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    public static final String TENANT_ROLE_ADMIN = "TENANT_ADMIN";

    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final UserTenantRoleMapper userTenantRoleMapper;
    private final RoleMapper roleMapper;

    /** refreshToken -> userId（会话缓存） */
    private final ConcurrentHashMap<String, Long> refreshTokenStore = new ConcurrentHashMap<>();

    private static final int MAX_LOGIN_FAIL = 5;
    private static final long LOCK_DURATION_MS = 30 * 60 * 1000L;

    public AuthServiceImpl(JwtTokenProvider jwtTokenProvider,
                           PasswordEncoder passwordEncoder,
                           UserMapper userMapper,
                           TenantMapper tenantMapper,
                           UserTenantRoleMapper userTenantRoleMapper,
                           RoleMapper roleMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
        this.userTenantRoleMapper = userTenantRoleMapper;
        this.roleMapper = roleMapper;
        initDefaultAdmin();
    }

    private void initDefaultAdmin() {
        // 1. 确保默认租户存在
        Tenant tenant = tenantMapper.findById(1L);
        if (tenant == null) {
            tenant = Tenant.builder()
                    .tenantName("默认租户")
                    .defaultEmbeddingModel("text-embedding-3-small")
                    .status(1)
                    .createTime(new Date())
                    .build();
            tenantMapper.insert(tenant);
            log.info("默认租户已创建: tenantId={}", tenant.getTenantId());
        }

        // 2. 确保管理员用户存在（全局主体，无 tenant_id）
        AuthUser admin = userMapper.findByUsername("admin");
        if (admin == null) {
            admin = AuthUser.builder()
                    .username("admin")
                    .nickname("管理员")
                    .passwordHash(passwordEncoder.encode("admin123"))
                    .status(1)
                    .loginFailCount(0)
                    .createTime(new Date())
                    .build();
            userMapper.insert(admin);
            log.info("默认管理员已创建: admin / admin123, userId={}", admin.getUserId());
        }

        // 3. 确保 TENANT_ADMIN 角色存在并赋予管理员
        Role tenantAdminRole = roleMapper.findByCode(TENANT_ROLE_ADMIN);
        if (tenantAdminRole == null) {
            log.warn("角色字典缺少 TENANT_ADMIN，请检查 schema-v2.sql 种子数据");
            return;
        }
        UserTenantRole existing = userTenantRoleMapper.findByUserAndTenant(admin.getUserId(), tenant.getTenantId());
        if (existing == null) {
            UserTenantRole utr = UserTenantRole.builder()
                    .userId(admin.getUserId())
                    .tenantId(tenant.getTenantId())
                    .roleId(tenantAdminRole.getRoleId())
                    .createTime(new Date())
                    .build();
            userTenantRoleMapper.insert(utr);
            log.info("已为管理员赋予 TENANT_ADMIN: userId={}, tenantId={}",
                    admin.getUserId(), tenant.getTenantId());
        }
    }

    @Override
    public String login(String username, String password) {
        AuthUser user = userMapper.findByUsername(username);
        if (user == null) {
            throw new AuthException("AUTH_001", "用户名或密码错误");
        }

        if (user.getStatus() == -1) {
            if (user.getLastLoginTime() != null) {
                long lockedSince = user.getLastLoginTime().getTime();
                if (System.currentTimeMillis() - lockedSince > LOCK_DURATION_MS) {
                    user.setStatus(1);
                    user.setLoginFailCount(0);
                    userMapper.updateLoginFailCount(user.getUserId(), 0, user.getLastLoginTime());
                } else {
                    throw new AuthException("AUTH_003", "账号已被锁定，请30分钟后重试");
                }
            }
        }
        if (user.getStatus() == 0) {
            throw new AuthException("AUTH_002", "账号已被禁用");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            recordLoginFailure(user.getUserId());
            throw new AuthException("AUTH_001", "用户名或密码错误");
        }

        resetLoginFailure(user.getUserId());
        Date now = new Date();
        userMapper.updateLastLoginTime(user.getUserId(), now);

        String accessToken = jwtTokenProvider.generateAccessToken(user.getUserId(), username);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getUserId());
        refreshTokenStore.put(refreshToken, user.getUserId());

        log.info("用户登录成功: userId={}, username={}", user.getUserId(), username);
        return accessToken;
    }

    @Override
    public String refreshToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new AuthException("AUTH_004", "RefreshToken无效或已过期");
        }
        Long userId = refreshTokenStore.get(refreshToken);
        if (userId == null) {
            throw new AuthException("AUTH_004", "RefreshToken不存在");
        }
        AuthUser user = userMapper.findById(userId);
        if (user == null || user.getStatus() != 1) {
            throw new AuthException("AUTH_005", "用户状态异常");
        }
        return jwtTokenProvider.generateAccessToken(userId, user.getUsername());
    }

    @Override
    public Long validateToken(String token) {
        if (!jwtTokenProvider.validateToken(token)) {
            return null;
        }
        return jwtTokenProvider.getUserIdFromToken(token);
    }

    @Override
    public boolean validateAccessToken(String token) {
        return jwtTokenProvider.validateToken(token);
    }

    @Override
    public AuthUser getUserById(Long userId) {
        return userMapper.findById(userId);
    }

    @Override
    public AuthUser createUser(AuthUser user) {
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            throw new AuthException("AUTH_006", "用户名不能为空");
        }
        if (userMapper.findByUsername(user.getUsername()) != null) {
            throw new AuthException("AUTH_006", "用户名已存在: " + user.getUsername());
        }
        user.setCreateTime(new Date());
        if (user.getStatus() == null) user.setStatus(1);
        if (user.getLoginFailCount() == null) user.setLoginFailCount(0);
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode("rag123456"));
        }
        userMapper.insert(user);
        log.info("用户创建成功: userId={}, username={}", user.getUserId(), user.getUsername());
        return user;
    }

    @Override
    public void updateUser(AuthUser user) {
        userMapper.update(user);
    }

    @Override
    public void disableUser(Long userId) {
        userMapper.updateStatus(userId, 0);
    }

    @Override
    public void recordLoginFailure(Long userId) {
        AuthUser user = userMapper.findById(userId);
        if (user == null) return;
        int count = (user.getLoginFailCount() == null ? 0 : user.getLoginFailCount()) + 1;
        Date now = new Date();
        if (count >= MAX_LOGIN_FAIL) {
            userMapper.updateStatus(userId, -1);
            log.warn("账号已锁定: userId={}, 失败次数={}", userId, count);
        }
        userMapper.updateLoginFailCount(userId, count, now);
    }

    @Override
    public void resetLoginFailure(Long userId) {
        userMapper.updateLoginFailCount(userId, 0, null);
    }

    /**
     * 对明文密码进行 BCrypt 编码（供管理员接口创建用户使用）
     */
    public String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
}
