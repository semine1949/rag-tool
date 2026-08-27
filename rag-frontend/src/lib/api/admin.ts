import type {
  CreateKbRequest,
  CreateTenantRequest,
  CreateUserRequest,
  KbPermission,
  KnowledgeBase,
  ModelOption,
  Role,
  Tenant,
  UserItem,
} from '@/lib/types';
import { request, USE_MOCK } from './client';
import {
  mapKb,
  mapRole,
  mapTenant,
  type BackendKb,
  type BackendRole,
  type BackendTenant,
} from './adapter';
import { mockServer } from './mock/server';

/**
 * 管理端接口
 * 真实后端端点（rag-bootstrap / AdminController）：
 *   POST/GET /api/admin/tenant、/api/admin/tenant/list、/api/admin/tenant/{id}/members
 *   POST /api/admin/user、GET /api/admin/role/list
 *   POST/GET /api/admin/kb、/api/admin/kb/list、/api/admin/kb/{id}/config
 *   GET/POST/DELETE /api/admin/kb/{id}/permissions
 *   POST /api/admin/kb/{id}/collection/{init|clear|drop}、/api/admin/kb/{id}/reprocess
 */
export const adminApi = {
  /* ============ 租户 ============ */

  async listTenants(): Promise<Tenant[]> {
    if (USE_MOCK) return mockServer.listTenants();
    const list = await request.get<BackendTenant[]>('/admin/tenant/list');
    return list.map(mapTenant);
  },

  async createTenant(payload: CreateTenantRequest): Promise<Tenant> {
    if (USE_MOCK) return mockServer.createTenant(payload);
    // 后端仅接收 tenantName + defaultEmbeddingModel，其余配额字段后端未建模
    const created = await request.post<BackendTenant>('/admin/tenant', {
      tenantName: payload.name,
      defaultEmbeddingModel: null,
    });
    return mapTenant(created);
  },

  /** 后端仅支持租户成员角色任命，无独立的启用/停用接口；此方法为占位兼容 */
  async updateTenantStatus(id: number, status: Tenant['status']): Promise<Tenant> {
    if (USE_MOCK) return mockServer.updateTenantStatus(id, status);
    throw new Error('后端未提供租户状态切换接口，请通过成员角色管理调整');
  },

  async deleteTenant(id: number): Promise<void> {
    if (USE_MOCK) return mockServer.deleteTenant(id);
    throw new Error('后端未提供租户删除接口');
  },

  /* ============ 用户 ============ */

  async listUsers(): Promise<UserItem[]> {
    if (USE_MOCK) return mockServer.listUsers();
    // 后端无独立的用户列表接口，角色列表亦无用户维度；暂返回空并提示
    throw new Error('后端未提供用户列表接口，请接入 auth/user 查询或调整实现');
  },

  async createUser(payload: CreateUserRequest): Promise<UserItem> {
    if (USE_MOCK) return mockServer.createUser(payload);
    const created = await request.post<{ userId: number; username: string }>('/admin/user', {
      username: payload.username,
      password: payload.password,
      nickname: payload.email,
    });
    return {
      id: created.userId,
      username: created.username,
      email: payload.email,
      tenantId: payload.tenantId,
      tenantName: '',
      roles: payload.roles,
      status: 'ACTIVE',
      createdAt: new Date().toISOString(),
    };
  },

  async updateUserStatus(id: number, status: UserItem['status']): Promise<UserItem> {
    if (USE_MOCK) return mockServer.updateUserStatus(id, status);
    throw new Error('后端未提供用户状态切换接口');
  },

  async deleteUser(id: number): Promise<void> {
    if (USE_MOCK) return mockServer.deleteUser(id);
    throw new Error('后端未提供用户删除接口');
  },

  /* ============ 角色 ============ */

  async listRoles(): Promise<Role[]> {
    if (USE_MOCK) return mockServer.listRoles();
    const list = await request.get<BackendRole[]>('/admin/role/list');
    return list.map(mapRole);
  },

  /* ============ 知识库 ============ */

  async listKbs(): Promise<KnowledgeBase[]> {
    if (USE_MOCK) return mockServer.listKbs();
    const list = await request.get<BackendKb[]>('/admin/kb/list');
    return list.map(mapKb);
  },

  async createKb(payload: CreateKbRequest): Promise<KnowledgeBase> {
    if (USE_MOCK) return mockServer.createKb(payload);
    // 后端需要 tenantId；/auth/me 未返回该字段，暂以 1（默认租户）构造
    // 后续后端在 /auth/me 中补充 tenantId 后可改为从用户信息读取
    const tenantId = 1;
    const created = await request.post<BackendKb>('/admin/kb', {
      tenantId,
      kbName: payload.name,
      description: payload.description,
      // 后端知识库仅存 embeddingModel，检索模式/分块在文档与查询维度
      embeddingModel: payload.embeddingModel,
    });
    return mapKb(created);
  },

  /** 后端 v2 配置仅支持更新 embedding_model */
  async updateKbConfig(id: number, payload: { embeddingModel: string }): Promise<KnowledgeBase> {
    if (USE_MOCK) {
      return mockServer.updateKbConfig(id, {
        retrievalMode: 'HYBRID',
        vectorWeight: 0.7,
        topK: 5,
        chunkSize: 800,
        chunkOverlap: 120,
        embeddingModel: payload.embeddingModel,
      } as never);
    }
    await request.put<void>(`/admin/kb/${id}/config`, { embeddingModel: payload.embeddingModel });
    const list = await this.listKbs();
    return list.find((k) => k.id === id) ?? list[0];
  },

  async deleteKb(id: number): Promise<void> {
    if (USE_MOCK) return mockServer.deleteKb(id);
    // 后端删除知识库需走 drop 集合 + 权限清理，无独立 DELETE 接口
    throw new Error('后端未提供知识库删除接口，请使用集合 drop 或调整实现');
  },

  /** 知识库成员权限：后端返回角色编码数组，非用户授权明细 */
  async kbPermissions(kbId: number): Promise<KbPermission[]> {
    if (USE_MOCK) return mockServer.kbPermissions(kbId);
    const roleCodes = await request.get<string[]>(`/admin/kb/${kbId}/permissions`);
    return roleCodes.map((code, i) => ({
      userId: -i,
      username: code,
      email: '',
      role: code as KbPermission['role'],
      grantedAt: '',
    }));
  },

  /** 向量集合运维：init / clear / drop / reprocess */
  async collectionAction(
    kbId: number,
    action: 'init' | 'clear' | 'drop' | 'reprocess',
  ): Promise<KnowledgeBase> {
    if (USE_MOCK) return mockServer.collectionAction(kbId, action);
    const url =
      action === 'reprocess'
        ? `/admin/kb/${kbId}/reprocess`
        : `/admin/kb/${kbId}/collection/${action}`;
    await request.post<void>(url);
    const list = await this.listKbs();
    return list.find((k) => k.id === kbId) ?? list[0];
  },

  /* ============ 文档运维 ============ */

  /** 删除文档：后端未提供单文档删除接口，抛错提示 */
  async deleteDoc(id: number): Promise<void> {
    if (USE_MOCK) return mockServer.deleteDoc(id);
    throw new Error('后端未提供单文档删除接口');
  },

  /** 重建索引：后端为知识库维度 reprocess，文档维度暂以知识库级重处理代理 */
  async reprocessDoc(id: number): Promise<{ id: number }> {
    if (USE_MOCK) return mockServer.reprocessDoc(id);
    throw new Error('后端未提供单文档重建接口，请使用知识库集合重建');
  },

  /* ============ 仪表盘 / 模型 ============ */

  /** 后端未提供 /admin/dashboard 接口 */
  async dashboard(): Promise<never> {
    if (USE_MOCK) return mockServer.dashboard() as never;
    throw new Error('后端未提供仪表盘统计接口 /api/admin/dashboard');
  },

  /** 后端未提供模型列表接口 */
  async models(): Promise<ModelOption[]> {
    if (USE_MOCK) return mockServer.models();
    // 返回后端对话默认模型占位，供聊天页选择
    return [
      { id: 'qwen-turbo', name: 'Qwen-Turbo（默认）', provider: 'dashscope', type: 'chat', available: true },
      { id: 'qwen-max', name: 'Qwen-Max', provider: 'dashscope', type: 'chat', available: true },
      {
        id: 'text-embedding-v3',
        name: 'text-embedding-v3',
        provider: 'dashscope',
        type: 'embedding',
        available: true,
        dimension: 1024,
      },
    ];
  },
};
