package com.rag.auth.context;

/**
 * 请求上下文（线程级别），存储当前请求的用户身份。
 * 注意：租户上下文在请求时由 kbId 派生，JWT/API-Key 仅携带 userId/username。
 */
public class RequestContext {

    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<>();

    private Long userId;
    private String username;

    public static void set(RequestContext ctx) {
        HOLDER.set(ctx);
    }

    public static RequestContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Long currentUserId() {
        RequestContext ctx = get();
        return ctx != null ? ctx.userId : null;
    }

    public static String currentUsername() {
        RequestContext ctx = get();
        return ctx != null ? ctx.username : null;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
}
