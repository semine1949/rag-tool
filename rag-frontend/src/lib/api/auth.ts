import type {
  CurrentUser,
  LoginRequest,
  LoginResponse,
  RegisterRequest,
} from '@/lib/types';
import { request, USE_MOCK } from './client';
import { mockServer } from './mock/server';

/**
 * 认证相关接口
 * 后端端点：POST /api/auth/login|register|refresh，GET /api/auth/user/{id}
 */
export const authApi = {
  /** 登录，返回令牌与用户信息 */
  login(payload: LoginRequest): Promise<LoginResponse> {
    if (USE_MOCK) return mockServer.login(payload);
    // skipAuth：登录请求无需携带旧令牌
    return request.post<LoginResponse>('/auth/login', payload, {
      // @ts-expect-error 自定义扩展字段，供请求拦截器识别
      skipAuth: true,
    });
  },

  /** 注册新账号 */
  register(payload: RegisterRequest): Promise<LoginResponse> {
    if (USE_MOCK) return mockServer.register(payload);
    return request.post<LoginResponse>('/auth/register', payload, {
      // @ts-expect-error 自定义扩展字段
      skipAuth: true,
    });
  },

  /** 使用 refreshToken 换取新令牌 */
  refresh(refreshToken: string): Promise<LoginResponse> {
    if (USE_MOCK) return mockServer.refresh();
    return request.post<LoginResponse>(
      '/auth/refresh',
      { refreshToken },
      {
        // @ts-expect-error 自定义扩展字段
        skipAuth: true,
      },
    );
  },

  /** 查询指定用户详情 */
  getUser(id: number): Promise<CurrentUser> {
    if (USE_MOCK) return mockServer.getUser(id);
    return request.get<CurrentUser>(`/auth/user/${id}`);
  },
};
