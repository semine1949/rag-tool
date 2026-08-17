# RAG Vector Tool — 项目技术总结

> **生成时间**：2026-08-06
> **项目版本**：0.0.1-SNAPSHOT
> **License**：MIT

---

## 一、项目背景与定位

### 1.1 项目名称

**RAG Vector Tool**（`rag-vector-tool`）

### 1.2 业务目标

打造一套**面向生产环境的开箱即用 RAG（检索增强生成）知识库平台**。解决的问题：
- 企业内部海量文档（PDF/Word/Excel/PPT/图片等）难以结构化检索
- 缺少一站式的「上传→解析→分块→向量化→检索→问答」全链路工具
- 私有化部署场景下，数据安全与多租户权限隔离的刚性需求

### 1.3 目标用户

- **内部员工/运维团队**：私有化部署后，通过 REST API 上传文档、检索知识库
- **开发者/集成方**：通过 HTTP API 集成到现有业务系统（如客服工单、企业搜索）
- **知识库管理员**：管理租户、用户、角色权限、知识库配置

### 1.4 产品边界

| 维度 | 属于本平台 | 不属于本平台（外部依赖） |
|------|-----------|------------------------|
| 文档解析 | Tika 通用文本 + DeepSeek-OCR 多模态 + Excel 智能表格 | 本地 OCR 引擎（PaddleOCR/Tesseract）——不内嵌 |
| 向量存储 | Weaviate（自建适配器直连） | Chroma/Qdrant/Pinecone 等 —— 不支持 |
| Embedding | Qwen3-Embedding / BGE-M3 / OpenAI 兼容（硅基流动 / OpenAI 官方），Ollama 协议适配器已实现，配置即可启用本地模型 | — |
| 分块策略 | text-model（自然分隔符） + hierarchical-model（父子双层） | 语义分割、关键词提取 —— 不支持 |
| LLM 调用 | 暂无对外问答 API；底层 ChatModel/ChatClient 能力已就绪（AiModelFactory），支持 OPENAI/OLLAMA/DASHSCOPE 三协议 | 端到端对话产品 —— 未集成 |
| 重排 Rerank | Qwen3-Reranker（硅基流动 Rerank API），HYBRID 模式可启用，异常自动降级 | — |
| 混合检索 | 三模式：VECTOR_ONLY / BM25_ONLY / HYBRID（RRF + 加权求和两种融合） | — |
| 文件存储 | 本地文件系统（默认）或 MinIO（可选） | 其他对象存储 —— 不支持 |

---

## 二、整体架构与技术栈

### 2.1 模块架构

```
rag-vector-tool (父 POM)
├── rag-common     ← 抽象与工具层（零内部依赖）
├── rag-config     ← 装配与实现层（依赖 rag-common）
├── rag-auth       ← 认证与权限层（依赖 rag-config）
└── rag-bootstrap  ← 启动入口 + REST API（依赖以上全部）
```

**依赖链**：`rag-bootstrap → rag-auth → rag-config → rag-common`

### 2.2 后端技术栈

| 类别 | 技术 | 版本 | 用途 |
|------|------|------|------|
| 语言 | Java | 17 | — |
| 框架 | Spring Boot | 3.4.5 | 应用框架 |
| AI 框架 | Spring AI | 1.0.0 | Document/Embedding/VectorStore 抽象 |
| AI 模型 starter | spring-ai-starter-model-openai | 1.0.0 | OpenAI 协议模型官方 SDK |
| AI 模型 starter | spring-ai-starter-model-ollama | 1.0.0 | Ollama 协议模型官方 SDK |
| AI 模型 starter | spring-ai-alibaba-starter-dashscope | 1.0.0.2 | DashScope 协议适配 |
| 数据库 | MySQL | 8.0+ | 用户/权限/文档/版本持久化 |
| 向量数据库 | Weaviate | —（Docker 独立部署） | 向量存储与相似度检索 |
| ORM | MyBatis | 3.0.3 | 数据访问 |
| 文档解析 | Apache Tika + Apache POI | — | 通用文本提取、Excel 解析 |
| HTTP 客户端 | OkHttp | 4.12.0 | Embedding API / OCR API 调用 |
| JSON | Jackson | 2.15.3 | 序列化 |
| 工具 | Hutool | 5.8.25 | JSON 解析、工具方法 |
| 密码加密 | BCrypt | —（Spring Security） | 用户密码哈希 |
| 认证 | JWT（自研） | — | Token 签发与验证 |
| 文件存储 | MinIO（可选） | 8.5.7 | 对象存储（默认本地文件系统） |
| 构建 | Maven | 3.8+ | 依赖管理与编译 |

### 2.3 RAG 完整链路（当前实现状态）

```
文档上传 → 解析 → 分块(chunk) → 向量化 → 向量入库 → 检索(向量/BM25/混合) → 重排(可选) → [LLM调用缺失]
```

| 步骤 | 当前实现 | 自研/第三方 | 状态 |
|------|---------|------------|------|
| 1. 文档上传 | MultipartFile → 临时文件 | Spring Boot 原生 | ✅ 已实现 |
| 2. 文档解析 | Tika（文本）+ DeepSeek-OCR（图片）+ ExcelParser（表格） | 第三方 + 自研 | ✅ 已实现 |
| 3. 分块 | SizeTextSplitter（text-model）+ ParentChildTextSplitter（hierarchical-model） | **自研** | ✅ 已实现 |
| 4. 向量化 | AiModelFactory → 官方 OpenAiEmbeddingModel（Qwen3/BGE-M3/OpenAI 兼容） | 自研工厂 + 官方 SDK | ✅ 已实现 |
| 5. 向量入库 | WeaviateVectorStoreAdapter → Weaviate | 自研适配 | ✅ 已实现 |
| 6. 原文持久化 | doc_chunk 表（MySQL） | MyBatis | ✅ 已实现 |
| 7. 检索 | 三模式（VECTOR_ONLY/BM25_ONLY/HYBRID）+ 多知识库联合检索 + 白名单过滤，topK 默认 5 | Weaviate + 自研 | ✅ 已实现 |
| 8. 重排 Rerank | Qwen3-Reranker 精排（候选池放大→精排→topK 截断，仅 HYBRID 模式，异常自动降级） | 自研（OpenAiRerankModel） | ✅ 已实现 |
| 9. Prompt 组装 | 未实现（multi-search 预留回答生成扩展点） | — | ❌ |
| 10. LLM 调用 | 底层 ChatModel/ChatClient 已就绪（AiModelFactory），未配置 CHAT 模型、无对外 API | Spring AI + 自研工厂 | ⚠️ 能力就绪，API 缺失 |
| 11. 结果输出 | 检索返回 `List<FileProcessResult>`（含 snippet + score） | 自研 | ✅ 已实现 |

### 2.4 部署形态

- **部署方式**：单服务 JAR 包启动（`java -jar` 或 `spring-boot:run`），非 K8s/容器化
- **多租户**：**已开启** —— 租户-用户-角色三级模型，Weaviate 集合名 `T{tenantId}_Kb{kbId}` 物理隔离
- 权限体系：JWT Token 认证 + RBAC（4 级角色），登录锁定（5 次失败锁 30 分钟）
- 向量库装配：VectorStoreConfig 支持按 provider 创建 Weaviate/Milvus 向量库（当前使用 Weaviate）

### 2.5 AI 模型统一管理框架（AiModelFactory）

以工厂 + 适配器 + 策略三模式统一管理全部 AI 模型调用。

**三模式架构**：
- 工厂模式：AiModelFactory / AiModelFactoryImpl 统一负责模型创建与缓存（5 个 ConcurrentHashMap：chat / embedding / embeddingWithUsername / chatClient / rerank，computeIfAbsent 惰性创建），业务代码禁止直接 new 模型
- 适配器模式：ModelAdapter 接口屏蔽协议差异，三个实现类 OpenAiModelAdapter / OllamaModelAdapter / DashScopeModelAdapter 各自用官方 SDK 构建模型
- 策略模式：supports(category, protocol) 实现协议路由，工厂遍历 List<ModelAdapter> 找到首个匹配适配器

**工厂能力清单**（AiModelFactory 接口 11 个方法）：

| 方法 | 说明 |
|------|------|
| getChatModels() / getChatModel(name) | 对话模型（CHAT 类别） |
| getEmbeddingModel(name) / getEmbeddingModelWithUsername(name, username) | 嵌入模型（后者仅 OPENAI 协议支持 X-Username 头） |
| getOcrModel(name) / getAsrModel(name) | OCR / ASR（以 ChatModel 实现） |
| getChatClient(name) | Spring AI ChatClient 封装 |
| getRerankModel(name) | 重排模型（不走适配器，工厂直接按 OPENAI 协议创建 OpenAiRerankModel） |
| existsModel / getModelConfig / getModelNamesByCategory | 配置查询 |

**配置体系**：模型统一声明于 spring.ai.platform.models（application.public.yml，经 spring.config.import 引入），由 AiModelProperties 绑定。每个模型含 category（CHAT/EMBEDDING/OCR/RERANK/ASR/WORKFLOW/AGENT/RAG）+ protocol（OPENAI/OLLAMA/DASHSCOPE）+ baseUrl/apiKey/modelName + 生成参数（temperature/maxTokens/topP/streamEnabled 等）+ extensions 扩展参数。修改 YAML 的 protocol 字段即可切换协议，无需改代码。

**统一 HTTP 客户端**：OpenAiClient（rag-common/client）封装 embed / rerank / ocr 三类 OpenAI 兼容端点调用，共享 OkHttp 连接池与 Bearer 鉴权；Rerank 内置令牌桶限流 + 分批处理。

---

## 三、文档处理模块细节

### 3.1 支持文件格式与解析策略

| 文件类型 | 扩展名 | 解析器 | 策略说明 |
|---------|--------|--------|---------|
| 纯文本 | txt, md, markdown, html, htm | `TikaDocumentReader` | 直接文本提取 |
| Office 文档 | pdf, doc, docx, ppt, pptx | `TikaOcrMixedParser` | Tika 文本提取 + 内嵌图片 OCR |
| 图片 | jpg, jpeg, png, bmp | `DeepSeekOcrParser` | 纯多模态 OCR |
| Excel | xls, xlsx | `ExcelParser` | 智能表格解析（≤20行→Markdown表格，>20行→键值对） |

**OCR 策略**：
- 已启用 OCR：通过 OpenAiClient.ocr() 调用硅基流动的 deepseek-ai/DeepSeek-OCR 远程模型（多模态 /chat/completions 端点，独立超时控制）
- PDF/Word/PPT 中内嵌图片：由 `ImageCapturingExtractor` 截获，逐张 OCR
- 纯扫描件兜底：若 Tika + 内嵌图片 OCR 均无产出，回退为全量文档 OCR

### 3.2 Chunk 分块策略

| 策略 | 类 | 模式标识 | 默认参数 | 算法描述 |
|------|-----|---------|---------|---------|
| **text-model** | `SizeTextSplitter` | `text_model` | delimiter=`\n`, maxTokens=1024, chunkOverlap=50 | 自然分隔符优先（按 `\n` 切分，片段拼接），超长片段硬截断（含重叠） |
| **hierarchical-model** | `ParentChildTextSplitter` | `hierarchical_model` | parentSeparator=`\n\n\n`, parentMaxTokens=2048, childSeparator=`\n\n`, childMaxTokens=1024, parentMode=paragraph | 文本清洗→语义段落分割→标题行检测→短段落合并→父块生成→子块细分，父子关联 |

**hierarchical-model 分块流程**：
1. 文本清洗（统一换行符，压缩连续空行）
2. 语义段落分割（双换行切分 + 标题行检测，中文编号 `第X章/一、/1.` 等）
3. 短段落合并（相邻段落累加不超 parentMaxTokens）
4. 父块生成（段落拼入，超长在句子边界 `。！？.!?;\n` 截断）
5. 子块细分（每个父块按 childSeparator 切分，超长句子边界截断）

**文档清洗**：仅 hierarchical-model 做换行符统一和空行压缩，不做摘要和关键词提取。

**元数据提取**：每个分块富化 `fileId / docId / documentVersion / ownerId / tenantId / kbId / chunkIndex`。

### 3.3 向量化模型

| 模型类型 | 枚举 | 模型名称 | 端点 | 向量维度 |
|---------|------|---------|------|---------|
| 通义 Qwen3 | `TONGYI` | `Qwen/Qwen3-Embedding-0.6B` | 硅基流动 API | 1024 |
| BGE-M3 | `BGE_M3` | `BAAI/bge-m3` | 硅基流动 API | 1024 |
| OpenAI 兼容 | `OPENAI` | `text-embedding-3-small` | OpenAI API | 1536 |

- 部署方式：当前配置均为远程 API 调用；Ollama 协议适配器已实现（OllamaModelAdapter），配置即可启用本地模型
- 协议：统一走 OpenAI 兼容 /embeddings 端点，由 AiModelFactory.getEmbeddingModel() 创建官方 OpenAiEmbeddingModel（spring-ai-starter-model-openai）
- 缓存：按逻辑模型名缓存实例（AiModelFactoryImpl 内 ConcurrentHashMap，惰性创建）
- 配置与映射：模型统一声明于 spring.ai.platform.models（application.public.yml）；知识库 EmbeddingModelType 经 RagToolService.resolveEmbeddingModel() 映射为逻辑名（BGE_M3→bge-m3 / TONGYI→tongyi / OPENAI→openai）

---

## 四、检索与重排模块

### 4.1 检索策略

| 维度 | 当前实现 |
|------|---------|
| 检索模式 | 三模式：VECTOR_ONLY（默认）/ BM25_ONLY / HYBRID |
| 混合融合 | RRF（默认，rrfK=60）与加权求和（vectorWeight + bm25Weight）两种 |
| topK 默认值 | 5（可自定义，≤0 时回退为 5） |
| 相似度计算 | Weaviate cosine 距离 → certainty（1-distance） |
| 多库检索 | 支持多知识库联合检索 + 白名单过滤 |

**单库检索流程**（RagToolService.search）：
1. 加载知识库配置（Embedding 模型 + Weaviate 集合）
2. 经 AiModelFactory 获取对应 EmbeddingModel
3. 通过 WeaviateVectorStoreAdapter.searchByMode(query, topK, filter, searchConfig) 执行多模式检索
4. 若启用重排（仅 HYBRID 模式）：候选池放大（topK × rerankMultiplier，默认 3）→ Qwen3-Reranker 精排 → topK 截断；异常自动降级原始排序
5. 返回 FileProcessResult 列表（含 snippet、score、fileName、documentVersion 等）

**多库检索流程**（RagToolService.multiSearch，严格顺序）：
1. 参数校验（kbIds/query 非空）+ Controller 层逐库校验 READ 权限（任一失败阻断整个请求）
2. 召回量分层计算：每库召回 = topK × expandFactor（默认 2）；白名单场景再 × 3 补偿过滤损耗
3. 多库串行粗排合并：逐 kbId loadConfigs → getWeaviateStore → searchByMode，空结果跳过，合并为统一候选池
4. 白名单过滤（粗排之后、去重之前）：HashSet O(1) 查找，仅保留 docId 命中白名单的切片
5. 跨库去重：三级 key 优先级 docId+chunkId → recordId → docId+textHash，保留高分
6. 全局精排：合并去重后统一重排（跨库全局最优），失败降级原始相似度降序
7. 父子增强（可选）：enableParentEnhancement=true 时，子块从候选池内按 chunkId==parentChunkId 反查父块替换内容

**检索配置**（SearchConfig，字段均可空，优先级：请求参数 > 知识库配置 > 全局默认）：

| 参数 | 默认 | 说明 |
|------|------|------|
| searchMode | VECTOR_ONLY | 检索模式 |
| rrfK | 60 | RRF 融合常数 |
| vectorWeight / bm25Weight | null | 加权求和模式权重 |
| fusionMode | rrf | 融合模式（rrf / weighted） |
| rerankEnabled | false | 重排开关（仅 HYBRID 生效） |
| rerankCandidateMultiplier | 3 | 重排候选池放大倍数 |

### 4.2 Rerank（重排）

- 模型：Qwen/Qwen3-Reranker（硅基流动 POST /v1/rerank），配置声明于 spring.ai.platform.models（category: RERANK）
- 实现：RerankModel 接口 + OpenAiRerankModel（复用 OpenAiClient.rerank() HTTP 能力，不走适配器），经 AiModelFactory.getRerankModel() 获取
- 触发条件：单库检索仅 HYBRID 模式且 rerank=true 触发；多库检索由请求 rerank=true 全局触发
- 内置保护：令牌桶限流（max-qps=5）+ 分批处理（batch-size=20）+ 异常自动降级原始排序
- 候选池放大：启用重排时召回量 = topK × rerankMultiplier（默认 3），精排后截取 topK

### 4.3 召回过滤逻辑

| 过滤维度 | 当前状态 |
|---------|---------|
| 元数据过滤 | 支持 Filter.Expression（如按 fileId 过滤删除旧记录） |
| 权限过滤 | 检索入口逐库校验 READ 权限（multi-search 任一库无权限则阻断整个请求） |
| 白名单过滤 | multi-search 支持按 docId 白名单后过滤（粗排之后、去重之前） |
| 相似度阈值 | 无阈值过滤，Weaviate 返回所有结果 |
| 去重 | 入库时按 textHash 去重（existsByTextHash）；检索期跨库去重（三级 key：docId+chunkId → recordId → docId+textHash） |

---

## 五、LLM 调用模块

### 5.1 LLM 模型

当前项目不包含对外 LLM 问答 API。RagToolService.search() / multiSearch() 仅返回检索结果（snippet + score），不组装 Prompt 也不调用 LLM 生成回答。

底层对话能力已就绪：
- AiModelFactory.getChatModel(modelName) / getChatClient(modelName) 可获取对话模型（支持 OPENAI / OLLAMA / DASHSCOPE 三协议）
- ModelCategory 枚举已预留 CHAT / WORKFLOW / AGENT / RAG 类别
- 当前 application.public.yml 未声明任何 CHAT 类别模型，需在配置中新增后方可使用
- multi-search 流程中已预留两个扩展点：关键字提炼与回答生成

### 5.2 Prompt 体系

**无**。项目中不存在系统提示词模板、用户查询模板、上下文拼接模板。

### 5.3 输出处理

**无**。检索直接返回原始片段文本 + 元数据，不做引用溯源、引用片段展示、幻觉抑制。

---

## 六、多租户、权限、知识库管理

### 6.1 知识库隔离

| 隔离维度 | 机制 |
|---------|------|
| 物理隔离 | Weaviate 集合名：`T{tenantId}_Kb{kbId}`（每个知识库独立集合） |
| 逻辑隔离 | MySQL 中 `knowledge_base.tenant_id` + 权限校验 |
| 数据访问 | 所有 API 操作前校验用户对该 kbId 的权限 |

### 6.2 用户权限模型

**三级模型**：租户（Tenant）→ 用户（User）→ 角色（Role）

**4 级角色**：

| 角色 | 编码 | 权限范围 |
|------|------|---------|
| 租户管理员 | `TENANT_ADMIN` | 本租户所有 KB 完全控制（不受 kb_role_permission 限制） |
| 知识库管理员 | `KB_ADMIN` | 仅 kb_role_permission 含该角色的 KB 可配置/管理成员 |
| 编辑者 | `CONTRIBUTOR` | 仅含该角色的 KB 可上传/查看 |
| 查看者 | `VIEWER` | 仅含该角色的 KB 可查看/检索 |

**权限判定逻辑**（`KbAccessService.resolveKbRole`）：
1. 查询 `user_tenant_role` 获取用户在租户中的角色
2. 若为 `TENANT_ADMIN` → 直接拥有该租户全部 KB 权限
3. 否则查 `kb_role_permission` 表，看该 KB 是否授予了用户角色
4. 命中则返回角色，未命中返回 `NONE`（无权限）

**认证机制**：
- JWT Token（自研 `JwtTokenProvider`）：accessToken 2h + refreshToken 7d
- 登录锁定：连续 5 次失败 → 锁 30 分钟
- JWT 拦截器：除 `/api/auth/**` 外全部路径需 Token

### 6.3 知识库操作

| 操作 | 接口 | 权限要求 |
|------|------|---------|
| 创建知识库 | `POST /api/admin/kb` | 租户管理员 |
| 查看知识库列表 | `GET /api/admin/kb/list` | 租户管理员 |
| 查看知识库配置 | `GET /api/admin/kb/{kbId}/config` | KB 管理员+ |
| 更新 Embedding 模型 | `PUT /api/admin/kb/{kbId}/config` | KB 管理员+ |
| 初始化集合 | `POST /api/admin/kb/{kbId}/collection/init` | KB 管理员+ |
| 清空集合 | `POST /api/admin/kb/{kbId}/collection/clear` | KB 管理员+ |
| 删除集合 | `POST /api/admin/kb/{kbId}/collection/drop` | KB 管理员+ |
| 重处理 | `POST /api/admin/kb/{kbId}/reprocess` | KB 管理员+ |
| 上传文档 | `POST /api/rag/upload` | CONTRIBUTOR+ |
| 批量上传 | `POST /api/rag/upload/batch` | CONTRIBUTOR+ |
| 检索 | `POST /api/rag/search` | VIEWER+ |
| 多库检索 | `POST /api/rag/multi-search` | 每个 kbId 均需 VIEWER+，任一失败阻断整个请求 |
| 文档列表 | `GET /api/rag/documents` | VIEWER+ |

**版本管理**：
- 语义化版本号：`INITIAL` / `MAJOR` / `MINOR` / `PATCH`
- `document_version` 表记录每次入库的版本快照（docId + version + chunkCount + operatorId）
- 同一文件重复上传时，先按 `fileId` 清理 Weaviate 旧向量再入库新数据
- 配置：`keep-versions=10`, `auto-clean-enabled=false`

---

## 七、接口与交互

### 7.1 对外暴露接口

**认证接口**（无需 Token）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 用户名密码登录，返回 accessToken |
| POST | `/api/auth/refresh` | 刷新 Token |
| POST | `/api/auth/register` | 注册用户 |
| GET | `/api/auth/user/{userId}` | 查看用户信息 |

**RAG 接口**（需 Token）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/rag/upload` | 单文件上传（默认 text-model） |
| POST | `/api/rag/upload/text-model` | text-model 上传（可传 delimiter/maxTokens/chunkOverlap） |
| POST | `/api/rag/upload/hierarchical-model` | hierarchical-model 上传（可传父子块参数） |
| POST | `/api/rag/upload/batch` | 批量上传（异步） |
| POST | `/api/rag/process/directory` | 本地文件夹入库 |
| POST | `/api/rag/search` | 多模式检索（kbId/query/topK 必填；可选 searchMode/rrfK/vectorWeight/bm25Weight/rerank/rerankMultiplier） |
| POST | `/api/rag/multi-search` | 多知识库联合检索 + 白名单过滤（kbIds/query/topK/whitelist/expandFactor/searchMode/rerank/rerankMultiplier/enableParentEnhancement） |
| GET | `/api/rag/documents?kbId=` | 查看知识库文档列表 |

**管理接口**（需 Token + 租户管理员/KB 管理员）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/admin/tenant` | 创建租户 |
| GET | `/api/admin/tenant/list` | 列出当前用户的租户 |
| POST | `/api/admin/tenant/{tenantId}/members` | 任命租户角色 |
| POST | `/api/admin/user` | 创建用户 |
| GET | `/api/admin/role/list` | 查看角色列表 |
| POST | `/api/admin/kb` | 创建知识库 |
| GET | `/api/admin/kb/list` | 知识库列表 |
| GET/PUT | `/api/admin/kb/{kbId}/config` | 查看/更新 KB 配置 |
| GET/POST/DELETE | `/api/admin/kb/{kbId}/permissions` | 管理 KB 角色权限 |
| POST | `/api/admin/kb/{kbId}/collection/init` | 初始化集合 |
| POST | `/api/admin/kb/{kbId}/collection/clear` | 清空集合 |
| POST | `/api/admin/kb/{kbId}/collection/drop` | 删除集合 |
| POST | `/api/admin/kb/{kbId}/reprocess` | 重处理知识库 |

**SDK**：无，仅提供 REST API。

### 7.2 输入输出约束

| 维度 | 约束 |
|------|------|
| 上传文件大小 | 单文件 ≤ 100MB，总请求 ≤ 500MB（Spring Multipart 配置） |
| 检索输入 | query 字符串，无长度硬限制 |
| topK | 默认 5，可自定义（≤0 时回退为 5） |
| 输出格式 | JSON：`{"code":200,"data":[...]}` |
| 检索结果字段（单库） | fileId, fileName, snippet（分片文本）, score（相似度）, documentVersion, success |
| 检索结果字段（多库） | chunkId, docId, kbId, fileName, snippet, score, chunkMode, chunkType, parentChunkId, sourcePath |

---

## 八、现存约束、问题、待优化点

### 8.1 当前项目限制

| 类别 | 约束/问题 | 影响 |
|------|----------|------|
| 功能缺失 | 无对外问答 API（底层 ChatModel/ChatClient 已就绪，但未配置 CHAT 模型） | 检索结果需调用方自行组装 Prompt 调 LLM；multi-search 的关键字提炼与回答生成为 TODO |
| 功能缺失 | 父子增强仅候选池内反查父块 | 向量库 parentChunkId（UUID）与 doc_chunk 表自增 ID 非同一体系，无法跨表反查父块原文 |
| 功能缺失 | 无相似度阈值过滤 | 低相关度结果不会被过滤 |
| 待迁移 | rag.embedding（vectorDim）与 rag.deepseek-ocr 旧配置保留 | 后续迁移到 spring.ai.platform.models.extensions 统一管理 |
| 待迁移 | DashScope 适配器依赖 spring-ai-alibaba（非官方 starter） | 版本已与 Spring AI 1.0.0 对齐，需关注后续兼容性 |
| 性能 | 单服务部署，无分布式向量检索 | 大规模知识库场景性能受限 |
| 性能 | Embedding/OCR 均同步调用 | 大文件上传可能超时（OCR 超时 120s） |
| 稳定性 | DeepSeek-OCR 依赖硅基流动付费 API | 402 错误（账户余额不足）会导致 OCR 失败 |
| 稳定性 | 模型实例缓存无过期策略 | 模型配置变更后需重启 |
