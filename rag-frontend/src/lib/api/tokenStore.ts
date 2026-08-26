import type { AuthTokens, CurrentUser } from '@/lib/types';

/**
 * 令牌与用户信息的本地持久化
 * accessToken 有效期 2h，refreshToken 7d，过期时间一并落库以便主动刷新
 */

const ACCESS_KEY = 'nebula.accessToken';
const REFRESH_KEY = 'nebula.refreshToken';
const EXPIRE_KEY = 'nebula.expireAt';
const USER_KEY = 'nebula.user';

/** accessToken 默认有效期：2 小时 */
export const ACCESS_TOKEN_TTL_SEC = 2 * 60 * 60;
/** refreshToken 默认有效期：7 天 */
export const REFRESH_TOKEN_TTL_SEC = 7 * 24 * 60 * 60;

/** 提前 60s 视为过期，避免边界请求失败 */
const EXPIRE_SKEW_MS = 60 * 1000;

export const tokenStore = {
  getAccessToken(): string | null {
    return localStorage.getItem(ACCESS_KEY);
  },

  getRefreshToken(): string | null {
    return localStorage.getItem(REFRESH_KEY);
  },

  /** accessToken 是否即将/已经过期 */
  isAccessExpired(): boolean {
    const raw = localStorage.getItem(EXPIRE_KEY);
    if (!raw) return true;
    return Date.now() + EXPIRE_SKEW_MS >= Number(raw);
  },

  /** 写入令牌，并按 expiresIn 计算绝对过期时间 */
  setTokens(tokens: AuthTokens): void {
    localStorage.setItem(ACCESS_KEY, tokens.accessToken);
    localStorage.setItem(REFRESH_KEY, tokens.refreshToken);
    const ttl = (tokens.expiresIn || ACCESS_TOKEN_TTL_SEC) * 1000;
    localStorage.setItem(EXPIRE_KEY, String(Date.now() + ttl));
  },

  getUser(): CurrentUser | null {
    const raw = localStorage.getItem(USER_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as CurrentUser;
    } catch {
      return null;
    }
  },

  setUser(user: CurrentUser): void {
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  },

  /** 清空全部认证状态（登出/刷新失败） */
  clear(): void {
    [ACCESS_KEY, REFRESH_KEY, EXPIRE_KEY, USER_KEY].forEach((k) =>
      localStorage.removeItem(k),
    );
  },
};
