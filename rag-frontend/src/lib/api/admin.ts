import type {
  CreateKbRequest,
  CreateTenantRequest,
  CreateUserRequest,
  DashboardData,
  DocumentItem,
  KbConfigRequest,
  KbPermission,
  KnowledgeBase,
  ModelOption,
  Role,
  Tenant,
  UserItem,
} from '@/lib/types';
import { request, USE_MOCK } from './client';
import { mockServer } from './mock/server';

/**
 * 管理端接口
 * 后端端点：/api/admin/tenant*、/api/admin/user、/api/admin/role/list、/api/admin/kb*
 */
export const adminApi = {
  /* ============ 租户 ============ */

  listTenants(): Promise<Tenant[]> {
    if (USE_MOCK) return mockServer.listTenants();
    return request.get<Tenant[]>('/admin/tenant/list');
  },

  createTenant(payload: CreateTenantRequest): Promise<Tenant> {
    if (USE_MOCK) return mockServer.createTenant(payload);
    return request.post<Tenant>('/admin/tenant', payload);
  },

  updateTenantStatus(id: number, status: Tenant['status']): Promise<Tenant> {
    if (USE_MOCK) return mockServer.updateTenantStatus(id, status);
    return request.put<Tenant>(`/admin/tenant/${id}/status`, { status });
  },

  deleteTenant(id: number): Promise<void> {
    if (USE_MOCK) return mockServer.deleteTenant(id);
    return request.del<void>(`/admin/tenant/${id}`);
  },

  /* ============ 用户 ============ */

  listUsers(): Promise<UserItem[]> {
    if (USE_MOCK) return mockServer.listUsers();
    return request.get<UserItem[]>('/admin/user');
  },

  createUser(payload: CreateUserRequest): Promise<UserItem> {
    if (USE_MOCK) return mockServer.createUser(payload);
    return request.post<UserItem>('/admin/user', payload);
  },

  updateUserStatus(id: number, status: UserItem['status']): Promise<UserItem> {
    if (USE_MOCK) return mockServer.updateUserStatus(id, status);
    return request.put<UserItem>(`/admin/user/${id}/status`, { status });
  },

  deleteUser(id: number): Promise<void> {
    if (USE_MOCK) return mockServer.deleteUser(id);
    return request.del<void>(`/admin/user/${id}`);
  },

  /* ============ 角色 ============ */

  listRoles(): Promise<Role[]> {
    if (USE_MOCK) return mockServer.listRoles();
    return request.get<Role[]>('/admin/role/list');
  },

  /* ============ 知识库 ============ */

  listKbs(): Promise<KnowledgeBase[]> {
    if (USE_MOCK) return mockServer.listKbs();
    return request.get<KnowledgeBase[]>('/admin/kb/list');
  },

  createKb(payload: CreateKbRequest): Promise<KnowledgeBase> {
    if (USE_MOCK) return mockServer.createKb(payload);
    return request.post<KnowledgeBase>('/admin/kb', payload);
  },

  /** 更新检索模式、权重、Top-K 与分块参数 */
  updateKbConfig(id: number, payload: KbConfigRequest): Promise<KnowledgeBase> {
    if (USE_MOCK) return mockServer.updateKbConfig(id, payload);
    return request.put<KnowledgeBase>(`/admin/kb/${id}/config`, payload);
  },

  deleteKb(id: number): Promise<void> {
    if (USE_MOCK) return mockServer.deleteKb(id);
    return request.del<void>(`/admin/kb/${id}`);
  },

  /** 知识库成员授权列表 */
  kbPermissions(kbId: number): Promise<KbPermission[]> {
    if (USE_MOCK) return mockServer.kbPermissions(kbId);
    return request.get<KbPermission[]>(`/admin/kb/${kbId}/permissions`);
  },

  /** 向量集合运维：初始化 / 清空 / 删除 / 重建 */
  collectionAction(
    kbId: number,
    action: 'init' | 'clear' | 'drop' | 'reprocess',
  ): Promise<KnowledgeBase> {
    if (USE_MOCK) return mockServer.collectionAction(kbId, action);
    return request.post<KnowledgeBase>(`/admin/kb/${kbId}/collection/${action}`);
  },

  /* ============ 文档运维 ============ */

  deleteDoc(id: number): Promise<void> {
    if (USE_MOCK) return mockServer.deleteDoc(id);
    return request.del<void>(`/admin/kb/document/${id}`);
  },

  reprocessDoc(id: number): Promise<DocumentItem> {
    if (USE_MOCK) return mockServer.reprocessDoc(id);
    return request.post<DocumentItem>(`/admin/kb/document/${id}/reprocess`);
  },

  /* ============ 仪表盘 / 模型 ============ */

  dashboard(): Promise<DashboardData> {
    if (USE_MOCK) return mockServer.dashboard();
    return request.get<DashboardData>('/admin/dashboard');
  },

  models(): Promise<ModelOption[]> {
    if (USE_MOCK) return mockServer.models();
    return request.get<ModelOption[]>('/admin/kb/models');
  },
};
