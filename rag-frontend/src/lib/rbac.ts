import type { PermissionKey, RetrievalMode, RoleCode } from '@/lib/types';

/**
 * 四级 RBAC 权限矩阵（前端判定，最终以后端校验为准）
 * 层级：TENANT_ADMIN > KB_ADMIN > CONTRIBUTOR > VIEWER
 */

/** 角色层级，数字越小权限越高 */
export const ROLE_LEVEL: Record<RoleCode, number> = {
  TENANT_ADMIN: 1,
  KB_ADMIN: 2,
  CONTRIBUTOR: 3,
  VIEWER: 4,
};

/** 角色中文名 */
export const ROLE_LABEL: Record<RoleCode, string> = {
  TENANT_ADMIN: '租户管理员',
  KB_ADMIN: '知识库管理员',
  CONTRIBUTOR: '内容贡献者',
  VIEWER: '只读访客',
};

/** 权限点中文名与说明 */
export const PERMISSION_META: Record<PermissionKey, { label: string; desc: string }> = {
  'tenant:manage': { label: '租户管理', desc: '创建/停用租户、调整配额' },
  'user:manage': { label: '用户管理', desc: '新增、禁用、删除租户内用户' },
  'role:assign': { label: '角色分配', desc: '为用户授予或回收角色' },
  'kb:create': { label: '新建知识库', desc: '创建知识库并初始化向量集合' },
  'kb:config': { label: '知识库配置', desc: '修改检索模式、权重与分块参数' },
  'kb:delete': { label: '删除知识库', desc: '删除知识库及其向量集合' },
  'kb:permission': { label: '知识库授权', desc: '管理知识库成员与其角色' },
  'doc:upload': { label: '上传文档', desc: '上传文件并触发索引构建' },
  'doc:delete': { label: '删除文档', desc: '删除文档及对应向量分块' },
  'doc:reprocess': { label: '重建索引', desc: '按最新分块策略重新处理文档' },
  'doc:view': { label: '查看文档', desc: '浏览文档列表与分块详情' },
  'chat:query': { label: '智能问答', desc: '发起检索增强问答' },
};

/** 权限矩阵：角色 -> 拥有的权限点 */
export const ROLE_PERMISSIONS: Record<RoleCode, PermissionKey[]> = {
  TENANT_ADMIN: [
    'tenant:manage',
    'user:manage',
    'role:assign',
    'kb:create',
    'kb:config',
    'kb:delete',
    'kb:permission',
    'doc:upload',
    'doc:delete',
    'doc:reprocess',
    'doc:view',
    'chat:query',
  ],
  KB_ADMIN: [
    'kb:create',
    'kb:config',
    'kb:permission',
    'doc:upload',
    'doc:delete',
    'doc:reprocess',
    'doc:view',
    'chat:query',
  ],
  CONTRIBUTOR: ['doc:upload', 'doc:reprocess', 'doc:view', 'chat:query'],
  VIEWER: ['doc:view', 'chat:query'],
};

/** 权限矩阵展示顺序 */
export const PERMISSION_ORDER: PermissionKey[] = [
  'tenant:manage',
  'user:manage',
  'role:assign',
  'kb:create',
  'kb:config',
  'kb:delete',
  'kb:permission',
  'doc:upload',
  'doc:delete',
  'doc:reprocess',
  'doc:view',
  'chat:query',
];

export const ALL_ROLES: RoleCode[] = ['TENANT_ADMIN', 'KB_ADMIN', 'CONTRIBUTOR', 'VIEWER'];

/** 判断角色集合是否拥有指定权限 */
export function hasPermission(roles: RoleCode[] | undefined, key: PermissionKey): boolean {
  if (!roles || roles.length === 0) return false;
  return roles.some((r) => ROLE_PERMISSIONS[r]?.includes(key));
}

/** 取角色集合中的最高层级角色 */
export function highestRole(roles: RoleCode[] | undefined): RoleCode | null {
  if (!roles || roles.length === 0) return null;
  return [...roles].sort((a, b) => ROLE_LEVEL[a] - ROLE_LEVEL[b])[0];
}

/** 检索模式的展示元信息 */
export const RETRIEVAL_MODE_META: Record<
  RetrievalMode,
  { label: string; desc: string; tone: 'accent' | 'accent2' | 'accent3' }
> = {
  VECTOR_ONLY: {
    label: '纯向量检索',
    desc: '仅使用 Embedding 语义相似度召回，擅长同义改写与模糊表达',
    tone: 'accent',
  },
  BM25_ONLY: {
    label: '纯关键词检索',
    desc: '仅使用 BM25 词频匹配，擅长术语、编号与专有名词精确命中',
    tone: 'accent3',
  },
  HYBRID: {
    label: '混合检索',
    desc: '向量与 BM25 加权融合后重排序，兼顾语义泛化与关键词精度',
    tone: 'accent2',
  },
};

/** 分块策略展示元信息 */
export const CHUNK_STRATEGY_META = {
  'text-model': {
    label: '扁平分块 (text-model)',
    desc: '按固定长度切分并保留重叠窗口，索引速度快，适合短文档与 FAQ',
  },
  'hierarchical-model': {
    label: '层级分块 (hierarchical-model)',
    desc: '按标题层级构建父子结构，命中子块可回溯父块上下文，适合长篇规范文档',
  },
} as const;
