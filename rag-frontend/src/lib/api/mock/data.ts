import type {
  ActivityItem,
  DashboardData,
  DocumentItem,
  KbPermission,
  KnowledgeBase,
  ModelOption,
  Role,
  Tenant,
  UserItem,
} from '@/lib/types';

/**
 * Mock 数据源（内存态，支持增删改，刷新页面后复位）
 * 真实后端接入后由 VITE_USE_MOCK=false 关闭
 */

/** 相对当前时间生成 ISO 字符串，days 为前多少天 */
function daysAgo(days: number, hour = 10, minute = 0): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  d.setHours(hour, minute, 0, 0);
  return d.toISOString();
}

function minutesAgo(min: number): string {
  return new Date(Date.now() - min * 60000).toISOString();
}

/* ================= 模型 ================= */

export const mockModels: ModelOption[] = [
  { id: 'qwen-max', name: 'Qwen-Max', provider: 'dashscope', type: 'chat', available: true },
  { id: 'qwen-plus', name: 'Qwen-Plus', provider: 'dashscope', type: 'chat', available: true },
  { id: 'qwen-turbo', name: 'Qwen-Turbo', provider: 'dashscope', type: 'chat', available: true },
  { id: 'llama3.1:8b', name: 'Llama 3.1 8B', provider: 'ollama', type: 'chat', available: true },
  { id: 'qwen2.5:14b', name: 'Qwen2.5 14B', provider: 'ollama', type: 'chat', available: false },
  {
    id: 'text-embedding-v3',
    name: 'text-embedding-v3',
    provider: 'dashscope',
    type: 'embedding',
    available: true,
    dimension: 1024,
  },
  {
    id: 'text-embedding-v2',
    name: 'text-embedding-v2',
    provider: 'dashscope',
    type: 'embedding',
    available: true,
    dimension: 1536,
  },
  {
    id: 'bge-m3',
    name: 'bge-m3 (Ollama)',
    provider: 'ollama',
    type: 'embedding',
    available: true,
    dimension: 1024,
  },
  {
    id: 'nomic-embed-text',
    name: 'nomic-embed-text',
    provider: 'ollama',
    type: 'embedding',
    available: true,
    dimension: 768,
  },
];

/* ================= 租户 ================= */

export const mockTenants: Tenant[] = [
  {
    id: 1,
    code: 'acme',
    name: 'Acme 智能科技',
    status: 'ACTIVE',
    userCount: 42,
    kbCount: 8,
    docCount: 1284,
    quotaMb: 51200,
    usedMb: 18432,
    createdAt: daysAgo(180),
    ownerEmail: 'admin@acme.com',
  },
  {
    id: 2,
    code: 'globex',
    name: 'Globex 金融研究院',
    status: 'ACTIVE',
    userCount: 26,
    kbCount: 5,
    docCount: 763,
    quotaMb: 30720,
    usedMb: 9216,
    createdAt: daysAgo(120),
    ownerEmail: 'it@globex.cn',
  },
  {
    id: 3,
    code: 'initech',
    name: 'Initech 制造集团',
    status: 'SUSPENDED',
    userCount: 11,
    kbCount: 2,
    docCount: 158,
    quotaMb: 10240,
    usedMb: 8704,
    createdAt: daysAgo(76),
    ownerEmail: 'ops@initech.com',
  },
  {
    id: 4,
    code: 'umbrella',
    name: 'Umbrella 医药研发',
    status: 'PENDING',
    userCount: 4,
    kbCount: 1,
    docCount: 23,
    quotaMb: 10240,
    usedMb: 512,
    createdAt: daysAgo(9),
    ownerEmail: 'lab@umbrella.io',
  },
];

/* ================= 角色 ================= */

export const mockRoles: Role[] = [
  {
    id: 0,
    code: 'SUPER_ADMIN',
    name: '平台超级用户',
    description: '全平台最高权限，不属于任何租户，可创建/删除所有租户、管理全平台用户角色',
    level: 0,
    userCount: 1,
  },
  {
    id: 1,
    code: 'TENANT_ADMIN',
    name: '租户管理员',
    description: '租户内最高权限，可管理用户、角色、全部知识库与配置',
    level: 1,
    userCount: 3,
  },
  {
    id: 2,
    code: 'KB_ADMIN',
    name: '知识库管理员',
    description: '管理被授权的知识库，含配置、成员授权与集合运维',
    level: 2,
    userCount: 9,
  },
  {
    id: 3,
    code: 'CONTRIBUTOR',
    name: '内容贡献者',
    description: '可上传、重建与删除自己提交的文档，可发起问答',
    level: 3,
    userCount: 21,
  },
  {
    id: 4,
    code: 'VIEWER',
    name: '只读访客',
    description: '仅可浏览文档与发起问答，无任何写操作权限',
    level: 4,
    userCount: 9,
  },
];

/* ================= 用户 ================= */

export const mockUsers: UserItem[] = [
  {
    id: 1,
    username: 'admin',
    email: 'admin@acme.com',
    // 平台超级用户：tenantId=0 表示全租户，不属于任何租户实体
    tenantId: 0,
    tenantName: '',
    roles: ['SUPER_ADMIN'],
    status: 'ACTIVE',
    createdAt: daysAgo(180),
    lastLoginAt: minutesAgo(6),
  },
  {
    id: 2,
    username: 'li.wei',
    email: 'li.wei@acme.com',
    tenantId: 1,
    tenantName: 'Acme 智能科技',
    roles: ['KB_ADMIN'],
    status: 'ACTIVE',
    createdAt: daysAgo(150),
    lastLoginAt: minutesAgo(52),
  },
  {
    id: 3,
    username: 'zhang.min',
    email: 'zhang.min@acme.com',
    tenantId: 1,
    tenantName: 'Acme 智能科技',
    roles: ['CONTRIBUTOR'],
    status: 'ACTIVE',
    createdAt: daysAgo(96),
    lastLoginAt: minutesAgo(180),
  },
  {
    id: 4,
    username: 'chen.hao',
    email: 'chen.hao@acme.com',
    tenantId: 1,
    tenantName: 'Acme 智能科技',
    roles: ['VIEWER'],
    status: 'ACTIVE',
    createdAt: daysAgo(64),
    lastLoginAt: daysAgo(2, 15, 20),
  },
  {
    id: 5,
    username: 'wang.fang',
    email: 'wang.fang@globex.cn',
    tenantId: 2,
    tenantName: 'Globex 金融研究院',
    // 数据模型为一用户一租户一角色，mock 保持一致（单一角色）
    roles: ['KB_ADMIN'],
    status: 'ACTIVE',
    createdAt: daysAgo(58),
    lastLoginAt: minutesAgo(320),
  },
  {
    id: 6,
    username: 'liu.yang',
    email: 'liu.yang@globex.cn',
    tenantId: 2,
    tenantName: 'Globex 金融研究院',
    roles: ['VIEWER'],
    status: 'LOCKED',
    createdAt: daysAgo(41),
    lastLoginAt: daysAgo(12, 9, 0),
  },
  {
    id: 7,
    username: 'sun.qi',
    email: 'sun.qi@initech.com',
    tenantId: 3,
    tenantName: 'Initech 制造集团',
    roles: ['CONTRIBUTOR'],
    status: 'DISABLED',
    createdAt: daysAgo(30),
  },
];

/* ================= 知识库 ================= */

export const mockKbs: KnowledgeBase[] = [
  {
    id: 1,
    name: '产品技术文档库',
    description: '产品白皮书、架构设计、API 手册与版本发布说明的统一检索入口',
    tenantId: 1,
    collectionName: 'kb_acme_product_doc',
    embeddingModel: 'text-embedding-v3',
    retrievalMode: 'HYBRID',
    chunkStrategy: 'hierarchical-model',
    chunkSize: 800,
    chunkOverlap: 120,
    vectorWeight: 0.7,
    topK: 5,
    docCount: 386,
    chunkCount: 12480,
    collectionStatus: 'READY',
    createdAt: daysAgo(150),
    updatedAt: minutesAgo(35),
    owner: 'li.wei',
  },
  {
    id: 2,
    name: '客服知识问答库',
    description: '高频问题、话术模板与服务流程规范，支撑智能客服自动应答',
    tenantId: 1,
    collectionName: 'kb_acme_service_qa',
    embeddingModel: 'text-embedding-v3',
    retrievalMode: 'VECTOR_ONLY',
    chunkStrategy: 'text-model',
    chunkSize: 500,
    chunkOverlap: 80,
    vectorWeight: 1,
    topK: 4,
    docCount: 512,
    chunkCount: 8964,
    collectionStatus: 'READY',
    createdAt: daysAgo(132),
    updatedAt: minutesAgo(120),
    owner: 'zhang.min',
  },
  {
    id: 3,
    name: '法务合同条款库',
    description: '标准合同模板、条款库与合规审查要点，命中需精确匹配术语',
    tenantId: 1,
    collectionName: 'kb_acme_legal',
    embeddingModel: 'text-embedding-v2',
    retrievalMode: 'BM25_ONLY',
    chunkStrategy: 'text-model',
    chunkSize: 1000,
    chunkOverlap: 150,
    vectorWeight: 0,
    topK: 6,
    docCount: 174,
    chunkCount: 5320,
    collectionStatus: 'READY',
    createdAt: daysAgo(110),
    updatedAt: daysAgo(3, 17, 40),
    owner: 'admin',
  },
  {
    id: 4,
    name: '研发内部 Wiki',
    description: '工程规范、故障复盘、运维手册与技术分享沉淀',
    tenantId: 1,
    collectionName: 'kb_acme_dev_wiki',
    embeddingModel: 'bge-m3',
    retrievalMode: 'HYBRID',
    chunkStrategy: 'hierarchical-model',
    chunkSize: 700,
    chunkOverlap: 100,
    vectorWeight: 0.6,
    topK: 5,
    docCount: 143,
    chunkCount: 4210,
    collectionStatus: 'PROCESSING',
    createdAt: daysAgo(88),
    updatedAt: minutesAgo(12),
    owner: 'li.wei',
  },
  {
    id: 5,
    name: '市场竞品情报库',
    description: '竞品分析报告、行业研报与市场动态周报',
    tenantId: 1,
    collectionName: 'kb_acme_market',
    embeddingModel: 'text-embedding-v3',
    retrievalMode: 'HYBRID',
    chunkStrategy: 'text-model',
    chunkSize: 600,
    chunkOverlap: 90,
    vectorWeight: 0.75,
    topK: 5,
    docCount: 69,
    chunkCount: 2180,
    collectionStatus: 'READY',
    createdAt: daysAgo(52),
    updatedAt: daysAgo(1, 11, 5),
    owner: 'zhang.min',
  },
  {
    id: 6,
    name: '培训课件归档库',
    description: '新员工培训、产品认证课件与考核题库（集合尚未初始化）',
    tenantId: 1,
    collectionName: 'kb_acme_training',
    embeddingModel: 'nomic-embed-text',
    retrievalMode: 'VECTOR_ONLY',
    chunkStrategy: 'text-model',
    chunkSize: 500,
    chunkOverlap: 60,
    vectorWeight: 1,
    topK: 3,
    docCount: 0,
    chunkCount: 0,
    collectionStatus: 'UNINITIALIZED',
    createdAt: daysAgo(14),
    updatedAt: daysAgo(14),
    owner: 'admin',
  },
];

/* ================= 知识库成员权限 ================= */

export const mockKbPermissions: Record<number, KbPermission[]> = {
  1: [
    {
      userId: 2,
      username: 'li.wei',
      email: 'li.wei@acme.com',
      role: 'KB_ADMIN',
      grantedAt: daysAgo(150),
    },
    {
      userId: 3,
      username: 'zhang.min',
      email: 'zhang.min@acme.com',
      role: 'CONTRIBUTOR',
      grantedAt: daysAgo(96),
    },
    {
      userId: 4,
      username: 'chen.hao',
      email: 'chen.hao@acme.com',
      role: 'VIEWER',
      grantedAt: daysAgo(64),
    },
  ],
};

/* ================= 文档 ================= */

const docSeeds: Array<[string, string, number, number, number, number, DocumentItem['status']]> = [
  ['产品架构设计说明书_v3.2.pdf', 'pdf', 4820, 1, 186, 3, 'INDEXED'],
  ['RAG检索链路调优实践.md', 'md', 96, 4, 42, 1, 'INDEXED'],
  ['客户服务标准话术手册.docx', 'docx', 1360, 2, 128, 2, 'INDEXED'],
  ['2026年度合规审查清单.xlsx', 'xlsx', 620, 3, 64, 1, 'INDEXED'],
  ['多租户隔离方案评审记录.pdf', 'pdf', 2140, 4, 0, 1, 'PROCESSING'],
  ['竞品季度对比分析报告.pdf', 'pdf', 3580, 5, 152, 1, 'INDEXED'],
  ['Milvus集合运维手册.md', 'md', 148, 4, 58, 2, 'INDEXED'],
  ['服务等级协议SLA模板.docx', 'docx', 780, 3, 36, 1, 'INDEXED'],
  ['嵌入模型选型对比.txt', 'txt', 42, 1, 18, 1, 'INDEXED'],
  ['损坏的扫描件归档.pdf', 'pdf', 8960, 2, 0, 1, 'FAILED'],
  ['API接口变更日志.md', 'md', 88, 1, 26, 5, 'INDEXED'],
  ['行业监管政策汇编.pdf', 'pdf', 6240, 3, 214, 1, 'INDEXED'],
];

export const mockDocs: DocumentItem[] = docSeeds.map((s, i) => {
  const [fileName, fileType, fileSizeKb, kbId, chunkCount, version, status] = s;
  const kb = mockKbs.find((k) => k.id === kbId)!;
  return {
    id: i + 1,
    fileName,
    fileType,
    fileSizeKb,
    kbId,
    kbName: kb.name,
    chunkCount,
    version,
    status,
    chunkStrategy: kb.chunkStrategy,
    uploadedBy: ['li.wei', 'zhang.min', 'admin', 'wang.fang'][i % 4],
    uploadedAt: daysAgo(i * 2 + 1, 9 + (i % 8), (i * 7) % 60),
    errorMsg: status === 'FAILED' ? 'PDF 解析失败：文件已加密或内容为纯扫描图像' : undefined,
  };
});

/* ================= 活动流 ================= */

export const mockActivities: ActivityItem[] = [
  {
    id: 1,
    actor: 'zhang.min',
    action: '上传文档',
    target: '产品架构设计说明书_v3.2.pdf',
    time: minutesAgo(4),
    type: 'upload',
  },
  {
    id: 2,
    actor: 'chen.hao',
    action: '发起问答',
    target: '如何配置混合检索权重？',
    time: minutesAgo(11),
    type: 'query',
  },
  {
    id: 3,
    actor: 'li.wei',
    action: '更新知识库配置',
    target: '产品技术文档库 · HYBRID 0.7',
    time: minutesAgo(35),
    type: 'kb',
  },
  {
    id: 4,
    actor: 'system',
    action: '文档解析失败',
    target: '损坏的扫描件归档.pdf',
    time: minutesAgo(58),
    type: 'error',
  },
  {
    id: 5,
    actor: 'admin',
    action: '新增用户',
    target: 'wang.fang · KB_ADMIN',
    time: minutesAgo(96),
    type: 'user',
  },
  {
    id: 6,
    actor: 'wang.fang',
    action: '重建索引',
    target: '研发内部 Wiki',
    time: minutesAgo(142),
    type: 'kb',
  },
  {
    id: 7,
    actor: 'zhang.min',
    action: '批量上传',
    target: '市场竞品情报库 · 12 个文件',
    time: minutesAgo(210),
    type: 'upload',
  },
];

/* ================= 仪表盘 ================= */

export const mockDashboard: DashboardData = {
  metrics: [
    { key: 'kb', label: '知识库总数', value: '6', delta: 20, hint: '本月新增 1 个' },
    { key: 'doc', label: '文档总量', value: '1,284', delta: 12.4, hint: '较上周 +141' },
    { key: 'chunk', label: '向量分块数', value: '33.1K', delta: 8.7, hint: '平均 26 块/文档' },
    { key: 'qa', label: '今日问答量', value: '2,847', delta: -3.2, hint: '命中率 94.6%' },
  ],
  qaTrend: {
    // 近 12 个时段的问答量（本期）
    current: [
      { label: '00:00', value: 120 },
      { label: '02:00', value: 86 },
      { label: '04:00', value: 64 },
      { label: '06:00', value: 132 },
      { label: '08:00', value: 386 },
      { label: '10:00', value: 512 },
      { label: '12:00', value: 348 },
      { label: '14:00', value: 486 },
      { label: '16:00', value: 542 },
      { label: '18:00', value: 398 },
      { label: '20:00', value: 264 },
      { label: '22:00', value: 168 },
    ],
    previous: [
      { label: '00:00', value: 104 },
      { label: '02:00', value: 72 },
      { label: '04:00', value: 58 },
      { label: '06:00', value: 118 },
      { label: '08:00', value: 342 },
      { label: '10:00', value: 468 },
      { label: '12:00', value: 372 },
      { label: '14:00', value: 428 },
      { label: '16:00', value: 476 },
      { label: '18:00', value: 356 },
      { label: '20:00', value: 232 },
      { label: '22:00', value: 148 },
    ],
  },
  health: {
    overall: 94,
    items: [
      { name: 'Milvus 向量库', score: 98, status: 'ok' },
      { name: 'Embedding 服务', score: 96, status: 'ok' },
      { name: 'LLM 网关', score: 91, status: 'ok' },
      { name: '文档解析队列', score: 78, status: 'warn' },
      { name: 'MySQL 主库', score: 99, status: 'ok' },
    ],
  },
  activities: mockActivities,
  docTypes: [
    { type: 'PDF', count: 586, color: '#22d3ee' },
    { type: 'DOCX', count: 312, color: '#a855f7' },
    { type: 'Markdown', count: 214, color: '#3b82f6' },
    { type: 'XLSX', count: 108, color: '#34d399' },
    { type: 'TXT', count: 64, color: '#fbbf24' },
  ],
};

/* ================= 问答假答案 ================= */

/** 流式问答的模拟答案库，按关键词粗匹配 */
export const mockAnswers: Array<{ keywords: string[]; answer: string }> = [
  {
    keywords: ['混合检索', 'hybrid', '权重'],
    answer:
      '混合检索（HYBRID）会同时执行向量语义召回与 BM25 关键词召回，再通过加权融合对候选分块重排序。\n\n**权重配置建议**\n\n1. `vectorWeight` 取值范围 0~1，BM25 权重为 `1 - vectorWeight`。\n2. 语义泛化类问答（如客服场景）建议 0.7~0.8，语义为主。\n3. 术语精确匹配类场景（如法务条款、编号检索）建议 0.4~0.5，让关键词发挥作用。\n4. 调整后需要重新评估 Top-K，通常混合模式下 Top-K 设为 5~8 效果最佳。\n\n在「知识库管理 → 配置」中修改 `retrievalMode` 与 `vectorWeight` 后立即生效，无需重建集合。',
  },
  {
    keywords: ['分块', 'chunk', '策略'],
    answer:
      '平台提供两种分块策略：\n\n**text-model（扁平分块）**\n按固定长度切分，配合重叠窗口保持上下文连续，适合 FAQ、话术、短文档，索引速度快。\n\n**hierarchical-model（层级分块）**\n先按标题层级构建父子结构，再对叶子节点切分，检索命中子块时可回溯父块补全上下文，适合长篇技术文档与规范手册。\n\n**参数建议**：`chunkSize` 500~1000 字符，`chunkOverlap` 取 chunkSize 的 10%~20%。修改分块策略后需要对已有文档执行「重建索引」才会生效。',
  },
  {
    keywords: ['权限', '角色', 'rbac', '隔离'],
    answer:
      '平台采用四级 RBAC 与三级数据隔离：\n\n**角色层级**\n- `TENANT_ADMIN`：租户内最高权限，管理用户、角色与全部知识库\n- `KB_ADMIN`：管理被授权知识库的配置、成员与集合运维\n- `CONTRIBUTOR`：上传/重建/删除文档，可发起问答\n- `VIEWER`：仅浏览与问答\n\n**隔离层级**\n租户（Tenant）→ 知识库（KnowledgeBase）→ 向量集合（Collection）。每个租户的数据通过 `tenantId` 强制过滤，向量集合按知识库独立命名，跨租户不可见。\n\n权限判定顺序为：租户校验 → 角色校验 → 知识库级授权校验，三者全部通过才放行。',
  },
  {
    keywords: ['上传', '文档', '失败'],
    answer:
      '文档上传后会经历「解析 → 分块 → 向量化 → 写入集合」四个阶段，状态字段依次为 `PENDING` → `PROCESSING` → `INDEXED`。\n\n**常见失败原因**\n\n1. PDF 为纯扫描图像且未启用 OCR，解析结果为空\n2. 文件被加密或密码保护\n3. 单文件超过配额上限\n4. Embedding 服务限流导致向量化超时\n\n失败的文档会标记为 `FAILED` 并记录原因，可在文档管理页对其执行「重新处理」，系统会以最新的分块策略重跑整条链路。',
  },
];

/** 默认兜底答案 */
export const mockDefaultAnswer =
  '根据检索到的知识库内容，可以总结如下：\n\n本平台是一套面向企业的多租户 RAG 知识库系统，核心能力包括**多知识库联合检索**、**三种检索模式（向量 / BM25 / 混合）**、**两种分块策略**以及**四级 RBAC 权限体系**。\n\n检索链路会先对问题做向量化，在选中的知识库集合内召回相关分块，经重排序后拼装为上下文交给大模型生成答案，同时返回引用溯源，便于核对原文出处。\n\n如需更精确的回答，建议缩小知识库选择范围或提高 Top-K 数量。';
