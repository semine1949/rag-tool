import axios, {
  AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from 'axios';
import type { ApiResult, AuthTokens } from '@/lib/types';
import { tokenStore } from './tokenStore';

/** API 基础路径，默认走 Vite 代理 */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api';

/** 统一业务异常 */
export class ApiError extends Error {
  code: number;
  constructor(message: string, code = -1) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
  }
}

/** 未授权回调，由 AuthProvider 注册，用于跳转登录页 */
let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(fn: () => void): void {
  onUnauthorized = fn;
}

/** 标记请求是否已重试过，避免刷新令牌后无限循环 */
interface RetriableConfig extends InternalAxiosRequestConfig {
  _retried?: boolean;
  /** 该请求是否跳过 Authorization 注入（如登录、刷新） */
  skipAuth?: boolean;
}

export const http: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
});

/* ================= 令牌刷新：并发去重 ================= */

/** 正在进行的刷新请求，保证多个 401 只触发一次刷新 */
let refreshPromise: Promise<string> | null = null;

/** 调用刷新接口换取新的 accessToken */
async function doRefresh(): Promise<string> {
  const refreshToken = tokenStore.getRefreshToken();
  if (!refreshToken) throw new ApiError('刷新令牌不存在', 401);

  // 使用裸 axios，避免再次进入拦截器造成递归
  const resp = await axios.post<ApiResult<AuthTokens>>(
    `${API_BASE_URL}/auth/refresh`,
    { refreshToken },
    { headers: { 'Content-Type': 'application/json' }, timeout: 15000 },
  );

  const body = resp.data;
  if (body.code !== 0 && body.code !== 200) {
    throw new ApiError(body.message || '刷新令牌失败', body.code);
  }
  tokenStore.setTokens(body.data);
  return body.data.accessToken;
}

/** 获取刷新后的令牌（并发共享同一个 Promise） */
function refreshAccessToken(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = doRefresh().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

/* ================= 请求拦截：注入 JWT ================= */

http.interceptors.request.use(async (config) => {
  const cfg = config as RetriableConfig;
  if (cfg.skipAuth) return cfg;

  // 若 accessToken 已过期而 refreshToken 仍在，先静默刷新再发请求
  if (tokenStore.getAccessToken() && tokenStore.isAccessExpired() && tokenStore.getRefreshToken()) {
    try {
      await refreshAccessToken();
    } catch {
      tokenStore.clear();
      onUnauthorized?.();
    }
  }

  const token = tokenStore.getAccessToken();
  if (token) {
    cfg.headers.Authorization = `Bearer ${token}`;
  }
  return cfg;
});

/* ================= 响应拦截：解包 + 401 重试 ================= */

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiResult<unknown>>) => {
    const config = error.config as RetriableConfig | undefined;
    const status = error.response?.status;

    // 401：尝试刷新一次并重放原请求
    if (status === 401 && config && !config._retried && !config.skipAuth) {
      config._retried = true;
      try {
        const token = await refreshAccessToken();
        config.headers.Authorization = `Bearer ${token}`;
        return http.request(config);
      } catch {
        tokenStore.clear();
        onUnauthorized?.();
        return Promise.reject(new ApiError('登录状态已失效，请重新登录', 401));
      }
    }

    if (status === 403) {
      return Promise.reject(new ApiError('权限不足，无法执行该操作', 403));
    }

    const respData = error.response?.data as
      | { msg?: string; message?: string }
      | undefined;
    const msg =
      respData?.msg ||
      respData?.message ||
      (error.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : error.message) ||
      '网络异常';
    return Promise.reject(new ApiError(msg, status ?? -1));
  },
);

/* ================= 统一请求方法 ================= */

/**
 * 解包后端响应，失败抛出 ApiError。
 * 兼容两种契约：
 *  - 标准包装：{ code, msg, data }
 *  - 平铺结构：{ code:200, accessToken, userId, ... }（无 data 字段时返回整个 body 供调用方取字段）
 * 后端错误消息使用 msg 字段，HTTP 非 2xx 时后端也在 body 中携带 code 与 msg。
 */
function unwrap<T>(body: ApiResult<T> | Record<string, unknown>): T {
  if (body == null) throw new ApiError('响应体为空');
  // 兼容 code=0 / code=200 两种成功约定
  const code = (body as { code?: number }).code;
  if (code !== undefined && code !== 0 && code !== 200) {
    const msg = (body as { msg?: string }).msg || (body as { message?: string }).message || '请求失败';
    throw new ApiError(msg, code);
  }
  // 有 data 字段取 data；无 data 字段（如登录/刷新/注册）返回整个 body，调用方按需取字段
  if ('data' in body) {
    return (body as ApiResult<T>).data;
  }
  return body as unknown as T;
}

export const request = {
  async get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    const resp = await http.get<ApiResult<T>>(url, config);
    return unwrap(resp.data);
  },

  async post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    const resp = await http.post<ApiResult<T>>(url, data, config);
    return unwrap(resp.data);
  },

  async put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    const resp = await http.put<ApiResult<T>>(url, data, config);
    return unwrap(resp.data);
  },

  async del<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    const resp = await http.delete<ApiResult<T>>(url, config);
    return unwrap(resp.data);
  },

  /** 表单上传（multipart/form-data），支持进度回调 */
  async upload<T>(
    url: string,
    form: FormData,
    onProgress?: (percent: number) => void,
  ): Promise<T> {
    const resp = await http.post<ApiResult<T>>(url, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000,
      onUploadProgress: (e) => {
        if (onProgress && e.total) {
          onProgress(Math.round((e.loaded / e.total) * 100));
        }
      },
    });
    return unwrap(resp.data);
  },
};

/** 供 SSE 等原生 fetch 场景取用的请求头 */
export function authHeaders(): Record<string, string> {
  const token = tokenStore.getAccessToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}
