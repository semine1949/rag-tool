import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { authApi, setUnauthorizedHandler, tokenStore } from '@/lib/api';
import { hasPermission } from '@/lib/rbac';
import type {
  CurrentUser,
  LoginRequest,
  PermissionKey,
  RegisterRequest,
} from '@/lib/types';

interface AuthContextValue {
  user: CurrentUser | null;
  /** 初始化中（读取本地令牌） */
  initializing: boolean;
  login: (payload: LoginRequest) => Promise<void>;
  register: (payload: RegisterRequest) => Promise<void>;
  logout: () => void;
  /** 权限判定 */
  can: (key: PermissionKey) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * 认证状态提供者
 * 负责令牌持久化、用户信息恢复与权限判定
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [initializing, setInitializing] = useState(true);

  // 启动时从本地恢复登录态
  useEffect(() => {
    const cached = tokenStore.getUser();
    const token = tokenStore.getAccessToken();
    if (cached && token) {
      setUser(cached);
    }
    setInitializing(false);
  }, []);

  const logout = useCallback(() => {
    tokenStore.clear();
    setUser(null);
  }, []);

  // 注册 401 兜底回调：令牌彻底失效时清空状态
  useEffect(() => {
    setUnauthorizedHandler(() => {
      tokenStore.clear();
      setUser(null);
    });
  }, []);

  const login = useCallback(async (payload: LoginRequest) => {
    const resp = await authApi.login(payload);
    // 登录成功先落令牌，再拉取当前用户信息
    tokenStore.setTokens(resp);
    let user = resp.user;
    try {
      user = await authApi.me();
    } catch {
      // /me 失败不阻断登录，使用登录响应中的占位用户
      user = resp.user;
    }
    tokenStore.setUser(user);
    setUser(user);
  }, []);

  const register = useCallback(async (payload: RegisterRequest) => {
    const resp = await authApi.register(payload);
    tokenStore.setTokens(resp);
    let user = resp.user;
    try {
      user = await authApi.me();
    } catch {
      user = resp.user;
    }
    tokenStore.setUser(user);
    setUser(user);
  }, []);

  const can = useCallback(
    (key: PermissionKey) => hasPermission(user?.roles, key),
    [user],
  );

  const value = useMemo<AuthContextValue>(
    () => ({ user, initializing, login, register, logout, can }),
    [user, initializing, login, register, logout, can],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/** 读取认证上下文 */
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用');
  return ctx;
}
