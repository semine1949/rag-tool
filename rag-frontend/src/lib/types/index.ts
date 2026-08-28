/**
 * 全局领域模型类型定义
 * 与后端 rag-common / rag-auth 模块的 DTO 严格对齐
 */

/** 后端统一响应包装体 */
export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
  timestamp?: number;
}

/** 分页结果 */
export interface PageResult<T> {
  list: T[];
  total: number;
  pageNum: number;
  pageSize: number;
}

/* ============ RBAC 角色 ============ */

/**
 * 角色编码，与后端 sys_role 一致。
 * 两级权限隔离：
 *   - SUPER_ADMIN（平台超级用户，不属于任何租户，全租户可见）
 *   - 租户内角色 TENANT_ADMIN > KB_ADMIN > CONTRIBUTOR > VIEWER
 */
export type RoleCode = 'SUPER_ADMIN' | 'TENANT_ADMIN' | 'KB_ADMIN' | 'CONTRIBUTOR' | 'VIEWER';

/** 超级用户的"全租户"占位租户ID（与后端约定一致） */
export const SUPER_ADMIN_TENANT_ID = 0;

/** 角色元信息 */
export interface Role {
  id: number;
  code: RoleCode;
  name: string;
  description: string;
  /** 角色层级：1 最高 */
  level: number;
  userCount: number;
}

/** 细粒度权限点 */
export type PermissionKey =
  | 'tenant:manage'
  | 'user:manage'
  | 'role:assign'
  | 'kb:create'
  | 'kb:config'
  | 'kb:delete'
  | 'kb:permission'
  | 'doc:upload'
  | 'doc:delete'
  | 'doc:reprocess'
  | 'chat:query'
  | 'doc:view';

/* ============ 认证 ============ */

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
  email: string;
  tenantCode: string;
}

/** 登录成功返回的令牌与用户信息 */
export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  /** accessToken 过期秒数，默认 7200（2h） */
  expiresIn: number;
  tokenType: string;
}

export interface LoginResponse extends AuthTokens {
  user: CurrentUser;
}

/** 当前登录用户 */
export interface CurrentUser {
  id: number;
  username: string;
  /** 展示名，后端昵称 */
  nickname?: string;
  email: string;
  tenantId: number;
  tenantCode: string;
  tenantName: string;
  roles: RoleCode[];
  status: UserStatus;
  createdAt: string;
  lastLoginAt?: string;
}

export interface RefreshRequest {
  refreshToken: string;
}

/** 后端 /auth/me 返回的原始结构（数据在 data 字段内） */
export interface BackendMe {
  userId: number;
  username: string;
  nickname?: string;
  roleCode?: RoleCode | null;
  roleName?: string | null;
  tenantId?: number;
}

/* ============ 租户 / 用户 ============ */

export type TenantStatus = 'ACTIVE' | 'SUSPENDED' | 'PENDING';
export type UserStatus = 'ACTIVE' | 'LOCKED' | 'DISABLED';

export interface Tenant {
  id: number;
  code: string;
  name: string;
  status: TenantStatus;
  userCount: number;
  kbCount: number;
  docCount: number;
  /** 存储配额上限（MB） */
  quotaMb: number;
  usedMb: number;
  createdAt: string;
  ownerEmail: string;
}

export interface CreateTenantRequest {
  code: string;
  name: string;
  quotaMb: number;
  ownerEmail: string;
}

export interface UserItem {
  id: number;
  username: string;
  email: string;
  tenantId: number;
  tenantName: string;
  roles: RoleCode[];
  status: UserStatus;
  createdAt: string;
  lastLoginAt?: string;
}

export interface CreateUserRequest {
  username: string;
  email: string;
  password: string;
  tenantId: number;
  roles: RoleCode[];
}

/* ============ 知识库 ============ */

/** 检索模式，与后端 RetrievalMode 一致 */
export type RetrievalMode = 'VECTOR_ONLY' | 'BM25_ONLY' | 'HYBRID';

/** 分块策略 */
export type ChunkStrategy = 'text-model' | 'hierarchical-model';

/** 向量集合状态 */
export type CollectionStatus = 'READY' | 'UNINITIALIZED' | 'ERROR' | 'PROCESSING';

export interface KnowledgeBase {
  id: number;
  name: string;
  description: string;
  tenantId: number;
  /** Milvus 集合名 */
  collectionName: string;
  embeddingModel: string;
  retrievalMode: RetrievalMode;
  chunkStrategy: ChunkStrategy;
  chunkSize: number;
  chunkOverlap: number;
  /** 混合检索时向量权重 0~1 */
  vectorWeight: number;
  topK: number;
  docCount: number;
  chunkCount: number;
  collectionStatus: CollectionStatus;
  createdAt: string;
  updatedAt: string;
  owner: string;
}

export interface CreateKbRequest {
  name: string;
  description: string;
  embeddingModel: string;
  retrievalMode: RetrievalMode;
  chunkStrategy: ChunkStrategy;
  chunkSize: number;
  chunkOverlap: number;
}

export interface KbConfigRequest {
  retrievalMode: RetrievalMode;
  vectorWeight: number;
  topK: number;
  chunkSize: number;
  chunkOverlap: number;
}

/** 知识库成员权限 */
export interface KbPermission {
  userId: number;
  username: string;
  email: string;
  role: RoleCode;
  grantedAt: string;
}

/* ============ 文档 ============ */

export type DocStatus = 'INDEXED' | 'PROCESSING' | 'FAILED' | 'PENDING';

export interface DocumentItem {
  id: number;
  fileName: string;
  /** 文件类型：pdf/docx/md/txt/xlsx 等 */
  fileType: string;
  fileSizeKb: number;
  kbId: number;
  kbName: string;
  chunkCount: number;
  version: number;
  status: DocStatus;
  chunkStrategy: ChunkStrategy;
  uploadedBy: string;
  uploadedAt: string;
  /** 失败原因 */
  errorMsg?: string;
}

/** 上传参数 */
export interface UploadOptions {
  kbId: number;
  chunkStrategy: ChunkStrategy;
  chunkSize: number;
  chunkOverlap: number;
}

/** 上传任务的前端状态 */
export interface UploadTask {
  id: string;
  fileName: string;
  fileSizeKb: number;
  progress: number;
  status: 'uploading' | 'success' | 'error';
  errorMsg?: string;
}

/* ============ 问答 / 检索 ============ */

/** 引用溯源 */
export interface Citation {
  index: number;
  docId: number;
  docName: string;
  kbName: string;
  chunkIndex: number;
  /** 相似度得分 0~1 */
  score: number;
  snippet: string;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  citations?: Citation[];
  /** 是否正在流式输出 */
  streaming?: boolean;
  error?: string;
  createdAt: string;
  model?: string;
}

export interface ChatRequest {
  question: string;
  kbIds: number[];
  model: string;
  retrievalMode: RetrievalMode;
  topK: number;
  temperature: number;
  /** 是否携带历史上下文 */
  withHistory: boolean;
  /** 多轮会话 ID（后端续接上下文用，可选） */
  sessionId?: string;
}

export interface ChatResponse {
  answer: string;
  citations: Citation[];
  model: string;
  tokenUsage?: number;
  costMs?: number;
  /** 多轮会话 ID（真实后端返回） */
  sessionId?: string;
}

/** SSE 流式事件 */
export type StreamEvent =
  | { type: 'citations'; citations: Citation[] }
  | { type: 'content'; content: string }
  | { type: 'done' }
  | { type: 'error'; message: string };

export interface SearchRequest {
  query: string;
  kbId: number;
  topK: number;
  retrievalMode: RetrievalMode;
}

export interface MultiSearchRequest {
  query: string;
  kbIds: number[];
  topK: number;
  retrievalMode: RetrievalMode;
}

export interface SearchHit {
  docId: number;
  docName: string;
  kbId: number;
  kbName: string;
  chunkIndex: number;
  score: number;
  content: string;
}

/* ============ 模型 ============ */

export interface ModelOption {
  id: string;
  name: string;
  provider: 'dashscope' | 'ollama';
  type: 'chat' | 'embedding';
  /** 是否可用 */
  available: boolean;
  dimension?: number;
}

/* ============ 仪表盘 ============ */

export interface DashboardMetric {
  key: string;
  label: string;
  value: string;
  /** 环比变化，正数为增长 */
  delta: number;
  hint: string;
}

/** 折线图数据点 */
export interface TrendPoint {
  label: string;
  value: number;
}

/** 系统健康度 */
export interface HealthItem {
  name: string;
  score: number;
  status: 'ok' | 'warn' | 'danger';
}

/** 活动流条目 */
export interface ActivityItem {
  id: number;
  actor: string;
  action: string;
  target: string;
  time: string;
  type: 'upload' | 'query' | 'kb' | 'user' | 'error';
}

/** 文档类型分布 */
export interface DocTypeSlice {
  type: string;
  count: number;
  color: string;
}

export interface DashboardData {
  metrics: DashboardMetric[];
  qaTrend: { current: TrendPoint[]; previous: TrendPoint[] };
  health: { overall: number; items: HealthItem[] };
  activities: ActivityItem[];
  docTypes: DocTypeSlice[];
}
