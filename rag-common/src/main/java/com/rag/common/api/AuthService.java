package com.rag.common.api;

import com.rag.common.entity.AuthUser;

/**
 * 认证服务接口（需求6）
 */
public interface AuthService {

    /**
     * 用户名密码登录
     * @return JWT accessToken
     */
    String login(String username, String password);

    /**
     * 刷新Token
     * @param refreshToken 刷新令牌
     * @return 新的accessToken
     */
    String refreshToken(String refreshToken);

    /**
     * 校验Token并返回用户ID
     */
    Long validateToken(String token);

    /**
     * 校验AccessToken有效性
     */
    boolean validateAccessToken(String token);

    /**
     * 根据用户ID获取用户信息
     */
    AuthUser getUserById(Long userId);

    /**
     * 注册/创建用户
     */
    AuthUser createUser(AuthUser user);

    /**
     * 更新用户信息
     */
    void updateUser(AuthUser user);

    /**
     * 禁用用户
     */
    void disableUser(Long userId);

    /**
     * 记录登录失败（触发锁定逻辑）
     */
    void recordLoginFailure(Long userId);

    /**
     * 重置登录失败计数
     */
    void resetLoginFailure(Long userId);
}
