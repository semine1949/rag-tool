import type {
  ChatRequest,
  ChatResponse,
  Citation,
  CreateKbRequest,
  CreateTenantRequest,
  CreateUserRequest,
  CurrentUser,
  DashboardData,
  DocumentItem,
  KbConfigRequest,
  KbPermission,
  KnowledgeBase,
  LoginRequest,
  LoginResponse,
  ModelOption,
  MultiSearchRequest,
  RegisterRequest,
  Role,
  SearchHit,
  SearchRequest,
  StreamEvent,
  Tenant,
  UploadOptions,
  UserItem,
} from '@/lib/types';
import { ApiError } from '../client';
import { ACCESS_TOKEN_TTL_SEC, REFRESH_TOKEN_TTL_SEC } from '../tokenStore';
import {
  mockActivities,
  mockDashboard,
  mockDefaultAnswer,
  mockDocs,
  mockKbPermissions,
  mockKbs,
  mockModels,
  mockAnswers,
  mockRoles,
  mockTenants,
  mockUsers,
} from './data';

/**
 * Mock 服务端：在内存中模拟后端行为
 * 所有方法均带网络延时，便于观察 loading 态
 */

/** 模拟网络延时 */
function delay<T>(data: T, ms = 420): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(data), ms));
}

/** 内存态数据副本，允许增删改 */
const db = {
  tenants: [...mockTenants],
  users: [...mockUsers],
  kbs: [...mockKbs],
  docs: [...mockDocs],
  roles: [...mockRoles],
  kbPermissions: { ...mockKbPermissions } as Record<number, KbPermission[]>,
  activities: [...mockActivities],
};

/** 自增主键 */
let seq = 1000;
const nextId = () => ++seq;

/** 登录失败计数，用于演示账号锁定 */
const loginFailures = new Map<string, number>();
/** 允许的最大失败次数，超过则锁定 */
const MAX_LOGIN_FAILURES = 5;

/** 生成假 JWT（仅结构相似，无签名意义） */
function fakeJwt(payload: Record<string, unknown>, ttlSec: number): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = btoa(
    JSON.stringify({ ...payload, iat: Date.now() / 1000, exp: Date.now() / 1000 + ttlSec }),
  );
  return `${header}.${body}.mock-signature`;
}

/** 默认演示账号：与真实后端启动时创建的 admin 账号保持一致 */
const DEMO_ACCOUNTS: Record<string, { password: string; userId: number }> = {
  admin: { password: 'admin123', userId: 1 },
  'li.wei': { password: 'admin123', userId: 2 },
  'zhang.min': { password: 'admin123', userId: 3 },
  'chen.hao': { password: 'admin123', userId: 4 },
};

function toCurrentUser(u: UserItem): CurrentUser {
  const tenant = db.tenants.find((t) => t.id === u.tenantId);
  return {
    id: u.id,
    username: u.username,
    email: u.email,
    tenantId: u.tenantId,
    tenantCode: tenant?.code ?? 'acme',
    tenantName: u.tenantName,
    roles: u.roles,
    status: u.status,
    createdAt: u.createdAt,
    lastLoginAt: new Date().toISOString(),
  };
}

function issueTokens(user: CurrentUser): LoginResponse {
  return {
    accessToken: fakeJwt({ sub: user.username, uid: user.id, tid: user.tenantId }, ACCESS_TOKEN_TTL_SEC),
    refreshToken: fakeJwt({ sub: user.username, typ: 'refresh' }, REFRESH_TOKEN_TTL_SEC),
    expiresIn: ACCESS_TOKEN_TTL_SEC,
    tokenType: 'Bearer',
    user,
  };
}

export const mockServer = {
  /* ================= 认证 ================= */

  async login(payload: LoginRequest): Promise<LoginResponse> {
    await delay(null, 620);
    const account = DEMO_ACCOUNTS[payload.username];
    const failures = loginFailures.get(payload.username) ?? 0;

    // 超过失败次数上限，直接锁定
    if (failures >= MAX_LOGIN_FAILURES) {
      throw new ApiError('账号已被锁定，请 30 分钟后重试或联系租户管理员解锁', 423);
    }

    if (!account || account.password !== payload.password) {
      const next = failures + 1;
      loginFailures.set(payload.username, next);
      const remain = MAX_LOGIN_FAILURES - next;
      if (remain <= 0) {
        throw new ApiError('账号已被锁定，请 30 分钟后重试或联系租户管理员解锁', 423);
      }
      throw new ApiError(`用户名或密码错误，还可尝试 ${remain} 次后账号将被锁定`, 401);
    }

    const user = db.users.find((u) => u.id === account.userId);
    if (!user) throw new ApiError('用户不存在', 404);
    if (user.status === 'LOCKED') throw new ApiError('账号已被锁定，请联系管理员', 423);
    if (user.status === 'DISABLED') throw new ApiError('账号已被禁用', 403);

    loginFailures.delete(payload.username);
    return issueTokens(toCurrentUser(user));
  },

  async register(payload: RegisterRequest): Promise<LoginResponse> {
    await delay(null, 700);
    if (db.users.some((u) => u.username === payload.username)) {
      throw new ApiError('用户名已存在，请更换后重试', 409);
    }
    const tenant = db.tenants.find((t) => t.code === payload.tenantCode) ?? db.tenants[0];
    const created: UserItem = {
      id: nextId(),
      username: payload.username,
      email: payload.email,
      tenantId: tenant.id,
      tenantName: tenant.name,
      // 新注册用户默认最低权限
      roles: ['VIEWER'],
      status: 'ACTIVE',
      createdAt: new Date().toISOString(),
    };
    db.users.unshift(created);
    DEMO_ACCOUNTS[payload.username] = { password: payload.password, userId: created.id };
    return issueTokens(toCurrentUser(created));
  },

  async refresh(): Promise<LoginResponse> {
    await delay(null, 260);
    const user = db.users[0];
    return issueTokens(toCurrentUser(user));
  },

  async getUser(id: number): Promise<CurrentUser> {
    const user = db.users.find((u) => u.id === id);
    if (!user) throw new ApiError('用户不存在', 404);
    return delay(toCurrentUser(user), 200);
  },

  /* ================= 仪表盘 ================= */

  async dashboard(): Promise<DashboardData> {
    return delay({ ...mockDashboard, activities: db.activities.slice(0, 7) }, 480);
  },

  /* ================= 模型 ================= */

  async models(): Promise<ModelOption[]> {
    return delay(mockModels, 180);
  },

  /* ================= 知识库 ================= */

  async listKbs(): Promise<KnowledgeBase[]> {
    return delay([...db.kbs], 400);
  },

  async createKb(payload: CreateKbRequest): Promise<KnowledgeBase> {
    await delay(null, 600);
    if (db.kbs.some((k) => k.name === payload.name)) {
      throw new ApiError('同名知识库已存在', 409);
    }
    const kb: KnowledgeBase = {
      id: nextId(),
      name: payload.name,
      description: payload.description,
      tenantId: 1,
      // 集合名由租户与知识库名派生
      collectionName: `kb_acme_${payload.name.replace(/\s+/g, '_').toLowerCase()}`,
      embeddingModel: payload.embeddingModel,
      retrievalMode: payload.retrievalMode,
      chunkStrategy: payload.chunkStrategy,
      chunkSize: payload.chunkSize,
      chunkOverlap: payload.chunkOverlap,
      vectorWeight: payload.retrievalMode === 'BM25_ONLY' ? 0 : payload.retrievalMode === 'VECTOR_ONLY' ? 1 : 0.7,
      topK: 5,
      docCount: 0,
      chunkCount: 0,
      collectionStatus: 'UNINITIALIZED',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      owner: 'admin',
    };
    db.kbs.unshift(kb);
    return kb;
  },

  async updateKbConfig(id: number, payload: KbConfigRequest): Promise<KnowledgeBase> {
    await delay(null, 500);
    const kb = db.kbs.find((k) => k.id === id);
    if (!kb) throw new ApiError('知识库不存在', 404);
    Object.assign(kb, payload, { updatedAt: new Date().toISOString() });
    return kb;
  },

  async deleteKb(id: number): Promise<void> {
    await delay(null, 460);
    const idx = db.kbs.findIndex((k) => k.id === id);
    if (idx < 0) throw new ApiError('知识库不存在', 404);
    db.kbs.splice(idx, 1);
    db.docs = db.docs.filter((d) => d.kbId !== id);
  },

  async kbPermissions(kbId: number): Promise<KbPermission[]> {
    return delay(db.kbPermissions[kbId] ?? [], 320);
  },

  /** 集合运维：init / clear / drop / reprocess */
  async collectionAction(
    kbId: number,
    action: 'init' | 'clear' | 'drop' | 'reprocess',
  ): Promise<KnowledgeBase> {
    await delay(null, 800);
    const kb = db.kbs.find((k) => k.id === kbId);
    if (!kb) throw new ApiError('知识库不存在', 404);

    switch (action) {
      case 'init':
        kb.collectionStatus = 'READY';
        break;
      case 'clear':
        // 清空向量数据但保留集合结构
        kb.chunkCount = 0;
        kb.docCount = 0;
        kb.collectionStatus = 'READY';
        db.docs = db.docs.filter((d) => d.kbId !== kbId);
        break;
      case 'drop':
        kb.collectionStatus = 'UNINITIALIZED';
        kb.chunkCount = 0;
        kb.docCount = 0;
        db.docs = db.docs.filter((d) => d.kbId !== kbId);
        break;
      case 'reprocess':
        kb.collectionStatus = 'PROCESSING';
        // 模拟异步重建完成
        setTimeout(() => {
          kb.collectionStatus = 'READY';
        }, 3000);
        break;
    }
    kb.updatedAt = new Date().toISOString();
    return kb;
  },

  /* ================= 文档 ================= */

  async listDocs(kbId?: number): Promise<DocumentItem[]> {
    const list = kbId ? db.docs.filter((d) => d.kbId === kbId) : db.docs;
    return delay([...list], 420);
  },

  async upload(file: File, options: UploadOptions): Promise<DocumentItem> {
    await delay(null, 900);
    const kb = db.kbs.find((k) => k.id === options.kbId);
    if (!kb) throw new ApiError('目标知识库不存在', 404);

    const ext = file.name.split('.').pop()?.toLowerCase() ?? 'txt';
    const doc: DocumentItem = {
      id: nextId(),
      fileName: file.name,
      fileType: ext,
      fileSizeKb: Math.max(1, Math.round(file.size / 1024)),
      kbId: kb.id,
      kbName: kb.name,
      chunkCount: 0,
      version: 1,
      status: 'PROCESSING',
      chunkStrategy: options.chunkStrategy,
      uploadedBy: 'admin',
      uploadedAt: new Date().toISOString(),
    };
    db.docs.unshift(doc);

    // 模拟后台异步索引完成
    setTimeout(() => {
      doc.status = 'INDEXED';
      doc.chunkCount = Math.max(1, Math.round(doc.fileSizeKb / (options.chunkSize / 20)));
      kb.docCount += 1;
      kb.chunkCount += doc.chunkCount;
    }, 2600);

    db.activities.unshift({
      id: nextId(),
      actor: 'admin',
      action: '上传文档',
      target: file.name,
      time: new Date().toISOString(),
      type: 'upload',
    });

    return doc;
  },

  async deleteDoc(id: number): Promise<void> {
    await delay(null, 380);
    const idx = db.docs.findIndex((d) => d.id === id);
    if (idx < 0) throw new ApiError('文档不存在', 404);
    db.docs.splice(idx, 1);
  },

  async reprocessDoc(id: number): Promise<DocumentItem> {
    await delay(null, 520);
    const doc = db.docs.find((d) => d.id === id);
    if (!doc) throw new ApiError('文档不存在', 404);
    doc.status = 'PROCESSING';
    doc.version += 1;
    doc.errorMsg = undefined;
    setTimeout(() => {
      doc.status = 'INDEXED';
      doc.chunkCount = doc.chunkCount || Math.max(1, Math.round(doc.fileSizeKb / 30));
    }, 2600);
    return doc;
  },

  /* ================= 检索 ================= */

  /** 依据关键词从 mock 答案库挑选回答 */
  pickAnswer(question: string): string {
    const q = question.toLowerCase();
    const hit = mockAnswers.find((a) => a.keywords.some((k) => q.includes(k.toLowerCase())));
    return hit?.answer ?? mockDefaultAnswer;
  },

  /** 根据选中的知识库构造引用溯源 */
  buildCitations(kbIds: number[]): Citation[] {
    const pool = db.docs.filter(
      (d) => d.status === 'INDEXED' && (kbIds.length === 0 || kbIds.includes(d.kbId)),
    );
    return pool.slice(0, 3).map((d, i) => ({
      index: i + 1,
      docId: d.id,
      docName: d.fileName,
      kbName: d.kbName,
      chunkIndex: 12 + i * 7,
      score: Number((0.93 - i * 0.06).toFixed(3)),
      snippet:
        '……检索命中的原文片段，包含与问题高度相关的定义、参数说明与操作步骤，可点击查看完整分块内容……',
    }));
  },

  async chat(payload: ChatRequest): Promise<ChatResponse> {
    await delay(null, 900);
    return {
      answer: this.pickAnswer(payload.question),
      citations: this.buildCitations(payload.kbIds),
      model: payload.model,
      tokenUsage: 1280,
      costMs: 1840,
    };
  },

  /**
   * 模拟 SSE 流式输出：先推 citations，再逐字推 content，最后 done
   * 返回取消函数
   */
  chatStream(
    payload: ChatRequest,
    onEvent: (e: StreamEvent) => void,
  ): () => void {
    let cancelled = false;
    const timers: number[] = [];

    const run = async () => {
      // 阶段一：先返回引用溯源
      await new Promise((r) => {
        timers.push(window.setTimeout(r, 560));
      });
      if (cancelled) return;
      onEvent({ type: 'citations', citations: this.buildCitations(payload.kbIds) });

      // 阶段二：按片段推送正文
      const answer = this.pickAnswer(payload.question);
      const chunks = answer.match(/[\s\S]{1,3}/g) ?? [];
      for (const chunk of chunks) {
        if (cancelled) return;
        await new Promise((r) => {
          timers.push(window.setTimeout(r, 16));
        });
        onEvent({ type: 'content', content: chunk });
      }

      if (cancelled) return;
      onEvent({ type: 'done' });
    };

    void run();

    return () => {
      cancelled = true;
      timers.forEach((t) => window.clearTimeout(t));
    };
  },

  async search(payload: SearchRequest): Promise<SearchHit[]> {
    await delay(null, 520);
    return db.docs
      .filter((d) => d.kbId === payload.kbId && d.status === 'INDEXED')
      .slice(0, payload.topK)
      .map((d, i) => ({
        docId: d.id,
        docName: d.fileName,
        kbId: d.kbId,
        kbName: d.kbName,
        chunkIndex: i * 5 + 3,
        score: Number((0.95 - i * 0.05).toFixed(3)),
        content: `命中「${payload.query}」的分块内容片段 ${i + 1}……`,
      }));
  },

  async multiSearch(payload: MultiSearchRequest): Promise<SearchHit[]> {
    await delay(null, 620);
    return db.docs
      .filter((d) => payload.kbIds.includes(d.kbId) && d.status === 'INDEXED')
      .slice(0, payload.topK)
      .map((d, i) => ({
        docId: d.id,
        docName: d.fileName,
        kbId: d.kbId,
        kbName: d.kbName,
        chunkIndex: i * 4 + 2,
        score: Number((0.94 - i * 0.04).toFixed(3)),
        content: `跨库命中「${payload.query}」的分块内容片段 ${i + 1}……`,
      }));
  },

  /* ================= 租户 ================= */

  async listTenants(): Promise<Tenant[]> {
    return delay([...db.tenants], 420);
  },

  async createTenant(payload: CreateTenantRequest): Promise<Tenant> {
    await delay(null, 640);
    if (db.tenants.some((t) => t.code === payload.code)) {
      throw new ApiError('租户编码已存在', 409);
    }
    const tenant: Tenant = {
      id: nextId(),
      code: payload.code,
      name: payload.name,
      status: 'PENDING',
      userCount: 0,
      kbCount: 0,
      docCount: 0,
      quotaMb: payload.quotaMb,
      usedMb: 0,
      createdAt: new Date().toISOString(),
      ownerEmail: payload.ownerEmail,
    };
    db.tenants.unshift(tenant);
    return tenant;
  },

  async updateTenantStatus(id: number, status: Tenant['status']): Promise<Tenant> {
    await delay(null, 380);
    const tenant = db.tenants.find((t) => t.id === id);
    if (!tenant) throw new ApiError('租户不存在', 404);
    tenant.status = status;
    return tenant;
  },

  async deleteTenant(id: number): Promise<void> {
    await delay(null, 420);
    const idx = db.tenants.findIndex((t) => t.id === id);
    if (idx < 0) throw new ApiError('租户不存在', 404);
    db.tenants.splice(idx, 1);
  },

  /* ================= 用户 / 角色 ================= */

  async listUsers(): Promise<UserItem[]> {
    return delay([...db.users], 420);
  },

  async createUser(payload: CreateUserRequest): Promise<UserItem> {
    await delay(null, 620);
    if (db.users.some((u) => u.username === payload.username)) {
      throw new ApiError('用户名已存在', 409);
    }
    const tenant = db.tenants.find((t) => t.id === payload.tenantId) ?? db.tenants[0];
    const user: UserItem = {
      id: nextId(),
      username: payload.username,
      email: payload.email,
      tenantId: tenant.id,
      tenantName: tenant.name,
      roles: payload.roles,
      status: 'ACTIVE',
      createdAt: new Date().toISOString(),
    };
    db.users.unshift(user);
    return user;
  },

  async updateUserStatus(id: number, status: UserItem['status']): Promise<UserItem> {
    await delay(null, 360);
    const user = db.users.find((u) => u.id === id);
    if (!user) throw new ApiError('用户不存在', 404);
    user.status = status;
    return user;
  },

  async deleteUser(id: number): Promise<void> {
    await delay(null, 380);
    const idx = db.users.findIndex((u) => u.id === id);
    if (idx < 0) throw new ApiError('用户不存在', 404);
    db.users.splice(idx, 1);
  },

  async listRoles(): Promise<Role[]> {
    return delay([...db.roles], 300);
  },
};
