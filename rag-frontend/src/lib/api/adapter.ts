import type {
  Citation,
  DocStatus,
  KnowledgeBase,
  Role,
  RoleCode,
  Tenant,
  UserItem,
} from '@/lib/types';

/** 后端用户列表实体：{userId, username, nickname, status, createTime, tenantId, tenantName, roleCodes[]} */
export interface BackendUser {
  userId: number;
  username: string;
  nickname?: string | null;
  status: number;
  createTime?: string;
  tenantId?: number | null;
  tenantName?: string | null;
  roleCodes?: string[] | null;
}

/**
 * 后端实体 -> 前端类型 映射层
 * 后端字段命名与前端类型存在差异（如 tenantId vs id、status 数字 vs 枚举），在此统一转换，
 * 使页面组件只依赖前端稳定类型，隔离后端结构变化。
 */

/** 后端租户实体：{tenantId, tenantName, defaultEmbeddingModel, status(1/0), createTime} */
export interface BackendTenant {
  tenantId: number;
  tenantName: string;
  defaultEmbeddingModel?: string | null;
  status: number;
  createTime?: string;
}

/** 后端知识库实体：{kbId, tenantId, kbName, description, embeddingModel, status, createTime} */
export interface BackendKb {
  kbId: number;
  tenantId: number;
  kbName: string;
  description?: string | null;
  embeddingModel?: string | null;
  status: number;
  createTime?: string;
}

/** 后端角色实体：{roleId, roleCode, roleName, description, status, createTime} */
export interface BackendRole {
  roleId: number;
  roleCode: string;
  roleName: string;
  description?: string | null;
  status: number;
  createTime?: string;
}

/** 后端文件处理/检索结果 */
export interface BackendFileResult {
  fileId?: string;
  fileName?: string;
  chunkCount?: number;
  insertedCount?: number;
  success?: boolean;
  message?: string;
  snippet?: string;
  score?: number;
  documentVersion?: string;
}

/** 后端引用：{index, docId, kbId, fileName, chunkId, snippet, score} */
export interface BackendCitation {
  index: number;
  docId?: number | null;
  kbId?: number | null;
  fileName?: string;
  chunkId?: string;
  snippet?: string;
  score?: number;
}

/** 后端同步问答响应：{answer, citations, sessionId, model, elapsedMs}（平铺，非包裹 data） */
export interface BackendChatAnswer {
  answer: string;
  citations?: BackendCitation[];
  sessionId?: string;
  model?: string;
  elapsedMs?: number;
}

/** 后端 SSE 事件：{type, data}，data 为字符串 */
export interface BackendStreamEvent {
  type: 'content' | 'citations' | 'done' | 'error';
  data?: string;
}

/* ============ 状态数字 -> 前端枚举 ============ */

export function toTenantStatus(n: number): Tenant['status'] {
  if (n === 0) return 'SUSPENDED';
  if (n === 1) return 'ACTIVE';
  return 'PENDING';
}

export function toUserStatus(n: number): UserItem['status'] {
  if (n === 0) return 'DISABLED';
  if (n === -1) return 'LOCKED';
  return 'ACTIVE';
}

export function toDocStatus(success?: boolean): 'INDEXED' | 'FAILED' {
  return success === false ? 'FAILED' : 'INDEXED';
}

/** 后端文档登记实体（KbDocument）字段，对应 GET /rag/documents 返回的 List<KbDocument> */
export interface BackendKbDocument {
  docId?: number;
  kbId?: number;
  tenantId?: number;
  fileName?: string;
  fileType?: string;
  fileSize?: number;
  /** 处理状态：PENDING/PARSING/PARSED/CHUNKING/CHUNKED/VECTORIZING/COMPLETED/FAILED */
  processStatus?: string;
  chunkCount?: number;
  version?: string;
  ownerId?: number;
  collectionName?: string;
  uploadTime?: string;
  createTime?: string;
}

/** 后端处理状态 → 前端文档状态 */
export function toDocStatusFromProcess(processStatus?: string): DocStatus {
  if (!processStatus) return 'INDEXED';
  const p = processStatus.toUpperCase();
  if (p === 'FAILED') return 'FAILED';
  if (p === 'COMPLETED') return 'INDEXED';
  // 其余（PENDING/PARSING/PARSED/CHUNKING/CHUNKED/VECTORIZING）视为处理中
  return 'PROCESSING';
}

/* ============ 实体映射 ============ */

export function mapTenant(t: BackendTenant): Tenant {
  return {
    id: t.tenantId,
    // 后端无租户编码字段，用名称前缀兜底
    code: `t${t.tenantId}`,
    name: t.tenantName,
    status: toTenantStatus(t.status),
    // 后端未返回配额与统计，使用占位值
    userCount: 0,
    kbCount: 0,
    docCount: 0,
    quotaMb: 0,
    usedMb: 0,
    createdAt: t.createTime ?? '',
    ownerEmail: '',
  };
}

export function mapKb(kb: BackendKb): KnowledgeBase {
  return {
    id: kb.kbId,
    name: kb.kbName,
    description: kb.description ?? '',
    tenantId: kb.tenantId,
    // 集合名由后端派生，前端展示用占位
    collectionName: `kb_${kb.tenantId}_${kb.kbId}`,
    embeddingModel: kb.embeddingModel ?? 'text-embedding-v3',
    // 后端 v2 已将检索模式/分块策略下沉到文档/查询维度，知识库默认混合检索
    retrievalMode: 'HYBRID',
    chunkStrategy: 'text-model',
    chunkSize: 800,
    chunkOverlap: 120,
    vectorWeight: 0.7,
    topK: 5,
    docCount: 0,
    chunkCount: 0,
    // 后端未返回集合状态；真实后端默认视为可用，便于聊天页直接选择
    collectionStatus: 'READY',
    createdAt: kb.createTime ?? '',
    updatedAt: kb.createTime ?? '',
    owner: '',
  };
}

export function mapUser(u: BackendUser): UserItem {
  return {
    id: u.userId,
    username: u.username,
    email: u.nickname ?? '',
    tenantId: u.tenantId ?? 0,
    tenantName: u.tenantName ?? '',
    roles: (u.roleCodes ?? []).filter((c): c is RoleCode =>
      ['SUPER_ADMIN', 'TENANT_ADMIN', 'KB_ADMIN', 'CONTRIBUTOR', 'VIEWER'].includes(c),
    ),
    status: toUserStatus(u.status),
    createdAt: u.createTime ?? '',
  };
}

export function mapRole(r: BackendRole): Role {
  return {
    id: r.roleId,
    code: r.roleCode as RoleCode,
    name: r.roleName,
    description: r.description ?? '',
    level: roleLevel(r.roleCode),
    userCount: 0,
  };
}

/** 按角色编码计算层级（越小越高） */
function roleLevel(code: string): number {
  switch (code) {
    case 'SUPER_ADMIN':
      return 0;
    case 'TENANT_ADMIN':
      return 1;
    case 'KB_ADMIN':
      return 2;
    case 'CONTRIBUTOR':
      return 3;
    case 'VIEWER':
      return 4;
    default:
      return 9;
  }
}

export function mapCitation(c: BackendCitation): Citation {
  return {
    index: c.index,
    docId: c.docId ?? 0,
    docName: c.fileName ?? '',
    kbName: '',
    chunkIndex: 0,
    chunkId: c.chunkId ?? '',
    score: c.score ?? 0,
    snippet: c.snippet ?? '',
  };
}

/** 解析后端 SSE 事件中的 citations 数据（JSON 数组字符串） */
export function parseCitationsData(raw?: string): Citation[] {
  if (!raw) return [];
  try {
    const arr = JSON.parse(raw) as BackendCitation[];
    return Array.isArray(arr) ? arr.map(mapCitation) : [];
  } catch {
    return [];
  }
}
