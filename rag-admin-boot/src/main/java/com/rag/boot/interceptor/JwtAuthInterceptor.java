package com.rag.boot.interceptor;

import com.rag.auth.context.RequestContext;
import com.rag.auth.jwt.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * JWT认证拦截器（Multi-Tenant v2）
 * <p>
 * 拦截所有请求（排除 /api/auth/**），解析 JWT Bearer Token，
 * 将用户身份信息写入 RequestContext（线程级别）。
 * <p>
 * 认证失败返回 401。
 */
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthInterceptor.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_AUTHORIZATION = "Authorization";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthInterceptor(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String authHeader = request.getHeader(HEADER_AUTHORIZATION);

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            return handleJwt(token, response);
        }

        log.warn("请求缺少认证信息: {} {}", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"缺少认证信息\"}");
        return false;
    }

    private boolean handleJwt(String token, HttpServletResponse response) {
        try {
            if (!jwtTokenProvider.validateToken(token)) {
                writeUnauthorized(response, "Token无效或已过期");
                return false;
            }

            Long userId = jwtTokenProvider.getUserIdFromToken(token);
            String username = jwtTokenProvider.getUsernameFromToken(token);

            if (userId == null) {
                writeUnauthorized(response, "Token中缺少用户标识");
                return false;
            }

            RequestContext ctx = new RequestContext();
            ctx.setUserId(userId);
            ctx.setUsername(username);
            RequestContext.set(ctx);

            return true;
        } catch (Exception e) {
            log.error("JWT认证异常", e);
            writeUnauthorized(response, "认证异常: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        RequestContext.clear();
    }

    private void writeUnauthorized(HttpServletResponse response, String message) {
        try {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"" + message + "\"}");
        } catch (Exception ignored) {
            // ignore
        }
    }
}
