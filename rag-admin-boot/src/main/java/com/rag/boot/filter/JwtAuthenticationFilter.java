package com.rag.boot.filter;

import com.rag.auth.context.RequestContext;
import com.rag.auth.jwt.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器 (Multi-Tenant v2)
 * 从 Authorization: Bearer <token> 头中解析 JWT 并写入 RequestContext
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_HEADER = "Authorization";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(AUTH_HEADER);

        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();

        if (!jwtTokenProvider.validateToken(token)) {
            log.debug("JWT 校验失败或已过期");
            filterChain.doFilter(request, response);
            return;
        }

        // Write to RequestContext
        RequestContext ctx = new RequestContext();
        ctx.setUserId(jwtTokenProvider.getUserIdFromToken(token));
        ctx.setUsername(jwtTokenProvider.getUsernameFromToken(token));
        RequestContext.set(ctx);

        log.debug("JWT 认证成功: userId={}", ctx.getUserId());

        filterChain.doFilter(request, response);
    }
}
