import type {
  BackendMe,
  CurrentUser,
  LoginRequest,
  LoginResponse,
  RegisterRequest,
} from '@/lib/types';
import { request } from './client';

/**
 * 认证相关接口
 *
 * 后端契约（rag-auth / AuthController）：
 *   POST /api/auth/login    -> { code:200, accessToken, msg }  （后端未返回 refreshToken，由前端占位）
 *   POST /api/auth/register -> { code:200, userId, msg }
 *   POST /api/auth/refresh  -> { code:200, accessToken }
 *   GET  /api/auth/me       -> { code:200, data:{ userId, username, nickname, roleCode, roleName } }
 *   GET  /api/auth/user/{id}-> { code:200, data:{...} }
 */
export const authApi = {
  /**
   * 登录：后端仅返回 accessToken，user 信息需再调 /auth/me 获取。
   * 前端在 AuthContext 中组装完整 LoginResponse（accessToken + 占位 refreshToken + user）。
   */
  async login(payload: LoginRequest): Promise<LoginResponse> {
    const body = await request.post<{ accessToken: string }>('/auth/login', payload, {
      // @ts-expect-error 自定义扩展字段，供请求拦截器识别
      skipAuth: true,
    });
    // 后端当前不返回 refreshToken；refreshToken 置空，触发自动续期时后端若返回新 token 再回填。
    return {
      accessToken: body.accessToken,
      refreshToken: '',
      expiresIn: 2 * 60 * 60, // accessToken 有效期 2h，与后端保持一致
      tokenType: 'Bearer',
      // 占位用户，后续 /auth/me 会覆盖；仅保证结构完整
      user: {
        id: 0,
        username: payload.username,
        email: '',
        tenantId: 0,
        tenantCode: '',
        tenantName: '',
        roles: [],
        status: 'ACTIVE',
        createdAt: '',
      },
    };
  },

  /** 注册：后端返回 userId，成功后仍需单独登录 */
  async register(payload: RegisterRequest): Promise<LoginResponse> {
    await request.post<{ userId: number }>('/auth/register', payload, {
      // @ts-expect-error 自定义扩展字段
      skipAuth: true,
    });
    // 注册成功后自动登录一次，复用登录流程拿令牌与用户信息
    return this.login({ username: payload.username, password: payload.password });
  },

  /** 使用 refreshToken 换取新令牌 */
  async refresh(refreshToken: string): Promise<LoginResponse> {
    const body = await request.post<{ accessToken: string }>(
      '/auth/refresh',
      { refreshToken },
      {
        // @ts-expect-error 自定义扩展字段
        skipAuth: true,
      },
    );
    return {
      accessToken: body.accessToken,
      refreshToken: '', // 后端刷新仅返回 accessToken，不轮换 refreshToken
      expiresIn: 2 * 60 * 60,
      tokenType: 'Bearer',
      user: null as unknown as CurrentUser, // 刷新不携带用户信息，调用方不依赖此字段
    };
  },

  /**
   * 当前登录用户信息
   * 后端 /auth/me -> { userId, username, nickname, roleCode, roleName }
   * 映射为前端 CurrentUser（id / username / roles 等）
   */
  async me(): Promise<CurrentUser> {
    const raw = await request.get<BackendMe>('/auth/me');
    return {
      id: raw.userId,
      username: raw.username,
      email: raw.nickname || raw.username,
      nickname: raw.nickname,
      tenantId: raw.tenantId ?? 0,
      tenantCode: '',
      tenantName: '',
      // 后端单个 roleCode -> 前端角色集合
      roles: (raw.roleCode ? [raw.roleCode] : []) as CurrentUser['roles'],
      status: 'ACTIVE',
      createdAt: '',
    };
  },

  /** 按 id 查询用户详情 */
  getUser(id: number): Promise<CurrentUser> {
    return request.get<CurrentUser>(`/auth/user/${id}`);
  },
};
