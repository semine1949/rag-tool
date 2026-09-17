# RAG Vector Tool（NebulaKB）— 项目技术总结

> **生成时间**：2026-09-04
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
| LLM 调用 | 已支持对外问答 API（同步 + 流式 SSE），底层 ChatModel/ChatClient 通过 AiModelFactory 调用 OPENAI/OLLAMA/DASHSCOPE 三协议 | 端到端对话产品 —— 未集成 |
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
├── rag-auth       ← 认证与权限层（依赖 rag-common、rag-config）
├── rag-chat       ← 会话编排层（依赖 rag-common、rag-config、rag-auth）
└── rag-bootstrap  ← 启动入口 + REST API（依赖以上全部）
```

**依赖链**：`rag-bootstrap → rag-chat → rag-config → rag-common`，`rag-bootstrap` 和 `rag-chat` 均依赖 `rag-auth`

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
| 缓存 | Redis | 6.0+ | 会话上下文与用户索引（问答多轮对话） |
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
文档上传 → 解析 → 分块(chunk) → 向量化 → 向量入库 → 检索(向量/BM25/混合) → 重排(可选) → 查询改写 → 相似度过滤 → 上下文组装 → RAG Prompt约束 → LLM生成 → 流式输出
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
| 9. 查询改写 | LlmQueryRewriter 基于会话历史进行指代消解与省略补全（改写失败自动降级为原始查询） | 自研 | ✅ 已实现 |
| 10. 相似度过滤 | SimilarityFilter 按 score 过滤低相关片段（默认阈值 0.5），过滤后为空触发无答案降级 | 自研 | ✅ 已实现 |
| 11. 上下文组装 | ContextAssembler 按召回顺序编号 [1]..[n]，Token 预算耗尽时按相似度优先级截断 | 自研 | ✅ 已实现 |
| 12. RAG Prompt 约束 | PromptTemplateResolver 根据场景（RAG/普通对话）自动加载系统提示词模板，内置引用标注、禁止编造等强制约束 | 自研 | ✅ 已实现 |
| 13. LLM 生成 | ChatGenerator 调用对话模型同步/流式生成回答，支持引用溯源，异常降级返回召回片段 | Spring AI + 自研 | ✅ 已实现 |
| 14. 流式输出 | SSE 流式推送（citations → content → done/error），done 后自动持久化 user + assistant 消息 | 自研 | ✅ 已实现 |

### 2.4 部署形态

- **部署方式**：单服务 JAR 包启动（`java -jar` 或 `spring-boot:run`），非 K8s/容器化
- **多租户**：**已开启** —— 租户-用户-角色三级模型，Weaviate 集合名 `T{tenantId}_Kb{kbId}` 物理隔离
- **两级权限隔离**：平台超级用户 SUPER_ADMIN（`tenant_id=0`，全租户最高权限）+ 租户级 RBAC（TENANT_ADMIN / KB_ADMIN / CONTRIBUTOR / VIEWER），登录锁定（5 次失败锁 30 分钟）
- **Web 管理控制台**：`rag-frontend`（React 18 + TypeScript 5.7 + Vite + Tailwind 3.4 + 自研基础 UI 组件），对接真实后端，可切换 Mock 模式独立开发
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

**配置体系**：模型统一声明于 spring.ai.platform.models（application.public.yml，经 spring.config.import 引入），由 AiModelProperties 绑定。每个模型含 category（CHAT/EMBEDDING/OCR/RERANK/ASR/WORKFLOW/AGENT/RAG）+ protocol（OPENAI/OLLAMA/DASHSCOPE）+ baseUrl/apiKey/modelName + 生成参数（temperature/maxTokens/topP/streamEnabled 等）+ extensions 扩展参数（Embedding 的向量维度 dim、OCR 的 timeout-ms / max-tokens / page-max-tokens 等均存于此）。修改 YAML 的 protocol 字段即可切换协议，无需改代码。模型相关配置全部集中在本文件，application.yml 仅保留环境与运行参数，严禁双写（public.yml 经 spring.config.import 被导入，优先级高于 application.yml）。

**统一 HTTP 客户端**：OpenAiClient（rag-common/client）封装 embed / rerank / ocr 三类 OpenAI 兼容端点调用，共享 OkHttp 连接池与 Bearer 鉴权；Rerank 内置令牌桶限流 + 分批处理。

**场景化温度控制（同模型不同场景不同温度）**：
- `ModelAdapter.createChatModel` 已参数化 `temperature` 与 `maxTokens`（非硬编码），新增抽象方法 `createSceneChatOptions(Double temperature)` 构造"仅含温度"的请求级场景 Options。
- `AiModelFactory` 新增 `resolveSceneTemperature(model, scene)` 与 `resolveSceneChatOptions(model, scene)`：解析优先级 `scene-temperatures.get(scene)` > 模型级 `temperature` > null。
- `ChatScene` 枚举（rag-common/enums）标识调用场景：`CLASSIFY`（问题分类/二分类）/ `REWRITE`（查询改写）/ `RAG_QA`（知识库问答）/ `CHAT_QA`（通用问答）；温度不固化在枚举内，由 YAML 场景温度表决定。
- 温度通过**请求级 `ChatOptions`（随 `Prompt` 携带）**覆盖模型 `defaultOptions`，不污染单例缓存中的模型默认温度；请求级 Options 与 defaultOptions 合并时请求级优先，向后兼容。
- 配置收敛为两层（均在模型 YAML 段）：`scene-temperatures`（按场景差异化，最优先）+ `temperature`（模型级默认兜底）。

**生成参数独立贯通**：`maxTokens` 作为独立参数由接口 → 工厂 → 三适配器贯通，取代 OpenAi 硬编码 4096 / Ollama / DashScope 默认值；`OpenAiModelAdapter` 在 null 时兜底 4096。

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
- 配置与映射：模型统一声明于 spring.ai.platform.models（application.public.yml），向量维度取对应模型段的 extensions.dim；知识库 EmbeddingModelType 经 ChatContextService.resolveEmbeddingModel() 映射为逻辑名（BGE_M3→bge-m3 / TONGYI→tongyi / OPENAI→openai），KbConfigService / AdminController.resolveEmbeddingDim() 按同一逻辑名精确查表取凭证与 dim

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

**单库检索流程**（RagQueryService.search）：
1. 加载知识库配置（Embedding 模型 + Weaviate 集合）
2. 经 AiModelFactory 获取对应 EmbeddingModel
3. 通过 WeaviateVectorStoreAdapter.searchByMode(query, topK, filter, searchConfig) 执行多模式检索
4. 若启用重排（仅 HYBRID 模式）：候选池放大（topK × rerankMultiplier，默认 3）→ Qwen3-Reranker 精排 → topK 截断；异常自动降级原始排序
5. 返回 FileProcessResult 列表（含 snippet、score、fileName、documentVersion 等）

**多库检索流程**（RagQueryService.multiSearch，严格顺序）：
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
| 相似度阈值 | SimilarityFilter 按 score 过滤（默认 0.5，可配置），过滤后为空触发无答案降级 |
| 去重 | 入库时按 textHash 去重（existsByTextHash）；检索期跨库去重（三级 key：docId+chunkId → recordId → docId+textHash） |
| Embedding 请求节流 | WeaviateVectorStoreAdapter 对逐分片 embedding 请求做节流（默认 150ms 间隔），避免密集请求触发硅基流动 Connection reset/限流 |

**引用溯源相关度**：`WeaviateVectorStoreAdapter.parseSearchResult` 将 score 同时写入 Document.metadata（除 builder.score 外），使上层 `getDoubleMeta` 能读回真实相似度；前端 `Citation` 支持展示文档名 + chunkId（#短标识）+ 相似度百分比。

---

## 五、Chat 问答模块

### 5.1 模块职责

Chat 模块承载**对话交互核心能力**，遵循三层架构设计：

| 层 | 模块 | 职责 |
|----|------|------|
| 接入层 | rag-bootstrap（ChatController） | REST API 入口，同步/流式问答端点 |
| 会话层 | rag-chat | 会话生命周期管理、消息收发编排、上下文组织 |
| 能力层 | rag-config（AiModelFactory） | 对话模型调用、模型统一管理 |

### 5.2 核心组件

| 组件 | 包路径 | 职责 |
|------|--------|------|
| ChatSessionService | `rag-chat/service/` | 会话创建、查询、续期，按租户+用户隔离 |
| ChatContextService | `rag-chat/service/` | 查询改写→检索召回→上下文组装全流程编排 |
| ChatMessageService | `rag-chat/service/` | 顶层编排，协调会话+上下文+生成+持久化 |
| ChatGenerator | `rag-chat/generator/` | 对话模型调用，支持同步与流式生成 |
| ContextAssembler | `rag-chat/assembler/` | 召回片段按编号 [1]..[n] 组装，Token 预算截断 |
| PromptTemplateResolver | `rag-chat/assembler/` | 根据场景（RAG/普通对话）自动选择系统提示词模板，内置引用标注、禁止编造等强制约束 |
| LlmQueryRewriter | `rag-chat/rewriter/` | 基于会话历史的查询改写，携带最近 3 轮 |
| RedisSessionStore | `rag-chat/store/` | 会话 Redis 存储，TTL 自动续期 |
| SafetyChecker | `rag-chat/safety/` | 输入输出安全校验，提示词注入防护 |
| TempDocumentService | `rag-chat/document/` | 临时文档解析、向量化、内存召回 |
| SimilarityFilter | `rag-config/retrieval/` | 相似度阈值过滤，统一覆盖单库/多库/临时文档三类场景 |
| GenericQueryClassifier | `rag-chat/classifier/` | 检索前置通用问题二分类拦截（判定闲聊/常识/实时信息类 → 跳过 RAG 走纯对话） |
| ChatProperties | `rag-chat/config/` | 全局 Chat 配置（开关、模型、Token 预算、TTL、阈值、提示词模板、preQueryFilter 拦截配置） |

### 5.3 四种对话场景

| 场景 | 数据源 | 说明 |
|------|--------|------|
| 普通对话 | 会话历史 | 基于历史上下文的多轮问答 |
| 知识库问答 | 向量知识库 | 检索增强生成（RAG） |
| 文档问答 | 临时上传文件 | 文件解析后作为上下文 |
| 混合问答 | 知识库 + 临时文档 | 两者共同作为上下文 |

### 5.4 问答路由流程

```
ChatController（chat / chatStream 读取 request.toSearchConfig() 透传检索模式 searchMode/topK 等）
  → ChatMessageService.chat() / .chatStream()
    → ChatSessionService.getOrCreateSession()
    → GenericQueryClassifier.shouldIntercept()  ← 检索前置通用问题二分类（kbId!=null 且开拦截开关时）
        ├─ 命中通用 → 专属提示词纯对话回答（空引用 + 前置标注，跳过检索），仅响应不检索
        └─ 未命中/失败 → 自动放行进入 RAG 链路
    → ChatContextService.prepareContext()
        → SafetyChecker.checkInput()          ← 输入安全校验
        → LlmQueryRewriter.rewrite()          ← 查询改写（ChatScene.REWRITE）
        → RagQueryService.searchDocuments()   ← 知识库检索（按 searchConfig 选择 VECTOR/BM25/HYBRID）
        → TempDocumentService.recallFromTemp() ← 临时文档召回
        → SimilarityFilter.filter()           ← 相似度阈值过滤
        → ContextAssembler.assemble()         ← 上下文组装
    → PromptTemplateResolver.resolve()        ← 场景感知 Prompt 选择
    → ChatGenerator.generate() / .generateStream()  ← 按场景（RAG_QA/CHAT_QA）携带请求级温度 Options
    → SafetyChecker.checkOutput()             ← 输出安全校验
    → SessionStore.save()                     ← 会话持久化（user + assistant，流式在 done 后持久化完整回答）
```

**检索模式全链路透传**：前端 `rag.ts` 透传 `searchMode/topK` → `ChatRequest.toSearchConfig()`（非法回退 VECTOR_ONLY）→ `ChatContextService.prepareContext(searchConfig, topK)`，不再硬编码 VECTOR_ONLY；未传参数时内部回退 VECTOR_ONLY，兼容旧客户端。

### 5.5 配置体系

| 配置项 | 来源 | 优先级 |
|--------|------|--------|
| 全局默认 | ChatProperties（`rag.chat.*`） | 最低 |
| 知识库级 | KbChatConfig（`kb_chat_config` 表） | 中等 |
| 请求参数 | ChatRequest（`model` 字段） | 最高 |

| 参数 | 默认值 | 说明 |
|------|--------|------|
| enabled | true | 问答能力全局开关 |
| chatModel | qwen-turbo | 默认对话模型 |
| rewriteEnabled | true | 查询改写开关 |
| contextWindowTokens | 6000 | 上下文 Token 预算 |
| maxHistoryRounds | 10 | 最大保留历史轮次 |
| sessionTtlSeconds | 3600 | 会话过期时间 |
| tempDocTtlSeconds | 1800 | 临时文档生命周期 |
| safetyCheckEnabled | true | 安全校验开关 |
| similarityThreshold | 0.5 | 相似度阈值（过滤低分片段） |
| ragSystemPrompt | (内置默认) | RAG 场景系统提示词模板 |
| chatSystemPrompt | (无) | 普通对话系统提示词 |
| preQueryFilterEnabled | false | 检索前置通用问题二分类拦截全局开关（kbId!=null 的知识库/混合问答触发） |
| preQueryFilterModel | (空→chatModel) | 拦截分类所用模型 |
| preQueryFilterPrompt / answerPrompt | (内置/专属) | 分类指令（含 `{query}`）与拦截回答专属提示词（强调实时信息以官方为准） |
| preQueryFilterTag | 通用知识标注 | 拦截回答前置标注文案 |
| preQueryFilterTimeoutMs | 5000 | 分类调用超时（超时自动放行 RAG） |

> 注：`pre-query-filter` 系列在 YAML 中采用**平铺 kebab-case 字段**（`pre-query-filter-enabled` 等），与 ChatProperties 平铺字段对应；嵌套段结构无法绑定到平铺字段（绑定根因修复记录见 work-log）。

### 5.6 异常降级规则

| 异常环节 | 降级策略 |
|---------|---------|
| 查询改写失败 | 自动使用原始查询 |
| 检索服务异常 | 降级为纯对话模式 |
| 相似度过滤后无结果 | 返回"根据现有资料无法回答该问题"，不调用 LLM |
| LLM 调用失败/超时 | 返回召回片段列表 |
| Redis 不可用 | 回退 MySQL 读取会话历史 |
| 文档解析失败 | 提示用户，不中断会话 |
| 流式生成中断 | 不写入不完整消息，保留上一轮会话状态 |

### 5.7 引用溯源

- 召回片段按相似度排序编号 [1]..[n]，与 ContextAssembler 组装编号一一对应
- 引用元数据包含：docId、kbId、fileName、chunkId、snippet、score
- 同步接口返回 `List<Citation>` 与答案并列
- 流式接口先推送 citations 事件，再逐段推送 content，最后推送 done 事件

---

## 六、多租户、权限、知识库管理

### 6.1 知识库隔离

| 隔离维度 | 机制 |
|---------|------|
| 物理隔离 | Weaviate 集合名：`T{tenantId}_Kb{kbId}`（每个知识库独立集合） |
| 逻辑隔离 | MySQL 中 `knowledge_base.tenant_id` + 权限校验 |
| 数据访问 | 所有 API 操作前校验用户对该 kbId 的权限 |

### 6.2 用户权限模型（两级权限隔离）

**两级权限体系**：平台超级用户层（SUPER_ADMIN）+ 租户 RBAC 层。

**角色清单**（`roleLevel`：SUPER_ADMIN=0 > TENANT_ADMIN=1 > KB_ADMIN=2 > CONTRIBUTOR=3 > VIEWER=4）：

| 角色 | 编码 | 权限范围 |
|------|------|---------|
| 平台超级用户 | `SUPER_ADMIN` | 全局最高权限，不属于任何租户实体（`user_tenant_role` 以 `tenant_id=0` 表示"全租户"），可创建/删除所有租户、管理全平台用户角色分配、查看所有租户配置与数据；是唯一允许创建租户的角色 |
| 租户管理员 | `TENANT_ADMIN` | 本租户所有 KB 完全控制（不受 kb_role_permission 限制），数据范围隔离（仅本租户数据），不可见/不可任命高于自身权限的角色 |
| 知识库管理员 | `KB_ADMIN` | 仅 kb_role_permission 含该角色的 KB 可配置/管理成员 |
| 编辑者 | `CONTRIBUTOR` | 仅含该角色的 KB 可上传/查看 |
| 查看者 | `VIEWER` | 仅含该角色的 KB 可查看/检索 |

**权限判定逻辑**（`KbAccessService.resolveKbRole`）：
1. 短路放行：`isSuperAdmin(userId)`（查 `tenant_id=0` 绑定）→ 拥有全平台权限
2. 查询 `user_tenant_role` 获取用户在租户中的角色
3. 若为 `TENANT_ADMIN` → 直接拥有该租户全部 KB 权限
4. 否则查 `kb_role_permission` 表，看该 KB 是否授予了用户角色
5. 命中则返回角色，未命中返回 `NONE`（无权限）

**设计约定**：
- 超级用户身份记录在 `user_tenant_role`（`tenant_id=0 + SUPER_ADMIN`）；`admin` 默认账号初始化为超级用户，不绑定具体租户
- `/auth/me` 对超级用户返回 `tenantId=0`，前端以 `tenantId==0` 判断超级用户视图（不额外加布尔字段）
- 数据模型为一用户一租户一角色（`user_tenant_role` 按 user+tenant 唯一约束）；创建用户由 `AdminController.createUser` 联动写入租户角色绑定

**认证机制**：
- JWT Token（自研 `JwtTokenProvider`）：accessToken 2h + refreshToken 7d
- 登录锁定：连续 5 次失败 → 锁 30 分钟
- JWT 拦截器：除 `/api/auth/**` 外全部路径需 Token
- Spring Security 无状态（SecurityConfig + JwtAuthenticationFilter），与 JwtAuthInterceptor 双重保障

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
| 单文档删除 | `DELETE /api/rag/documents/{docId}` | 上传者本人或 KB_ADMIN / TENANT_ADMIN（物理删除三表业务数据 + 按 vector_id 精确删向量） |

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
| DELETE | `/api/rag/documents/{docId}` | 单文档删除（物理删除三表 + 精确删向量） |

**问答接口**（需 Token）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/rag/chat` | 同步问答（multipart/form-data，支持 kbId + query + files + sessionId + model + searchMode/topK 等检索参数） |
| POST | `/api/rag/chat/stream` | 流式问答 SSE（text/event-stream，事件类型：citations / content / done / error，done 携带完整回答持久化） |

**管理接口**（需 Token + 平台超级用户 / 租户管理员 / KB 管理员，两级权限隔离）：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/admin/tenant` | 创建租户（**仅 SUPER_ADMIN**，super admin 不再自动授予 TENANT_ADMIN） |
| GET | `/api/admin/tenant/list` | 列出当前用户的租户（SUPER_ADMIN 全量；TENANT_ADMIN 仅本租户） |
| POST | `/api/admin/tenant/{tenantId}/members` | 任命租户角色（含 GRANT/REVOKE） |
| POST | `/api/admin/user` | 创建用户（联动写入 user_tenant_role） |
| GET | `/api/admin/user` | 用户列表（返回 userId/username/tenantId/tenantName/roleCodes） |
| GET | `/api/admin/role/list` | 查看角色列表（TENANT_ADMIN 视角过滤掉 SUPER_ADMIN） |
| POST | `/api/admin/kb` | 创建知识库（super admin 兜底取首个可用租户） |
| GET | `/api/admin/kb/list` | 知识库列表（SUPER_ADMIN 全量；TENANT_ADMIN 仅本租户） |
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

### 7.3 Web 前端控制台（rag-frontend）

- **技术栈**：React 18.3 + TypeScript 5.7 + Vite 5.4 + Tailwind CSS 3.4 + React Router 6 + Axios；**无第三方组件库**，基础 UI（Button/Card/Modal/Table/Toast/Select/Tabs 等）与图表（Line/Donut/Ring）全部按深色玻璃拟态设计系统自研。
- **页面**：登录/注册、概览仪表盘、智能问答（流式 + 引用溯源 + 多轮会话）、知识库管理、文档管理（上传/删除/重建）、租户管理、用户管理、角色权限（含 SUPER_ADMIN 两级权限视图）。
- **设计系统**：背景 `#070b15`、玻璃卡片（backdrop-blur）、accent 主渐变 `linear-gradient(135deg,#22d3ee,#a855f7)`。
- **Mock 模式**：内置完整 Mock 数据层（`src/lib/api/mock`），`VITE_USE_MOCK` 开关可独立运行；默认对接真实后端（Vite 代理 `/api` → localhost:8080），含 `adapter.ts` 后端实体→前端类型统一映射层。
- **对接状态**：认证/列表/上传/检索/问答/权限管理均与真实后端端到端打通；JWT 自动注入与 401 自动刷新、SSE 手动分帧解析。

---

## 八、现存约束、问题、待优化点

### 8.1 当前项目限制

| 类别 | 约束/问题 | 影响 |
|------|----------|------|
| 待验证 | 父子增强 MySQL 跨表反查 | v4 已统一 doc_chunk.chunk_id 为 UUID 业务键，与向量 metadata chunkId 一致，理论上可通过 `findByChunkId` 跨表反查父块；跨表增强逻辑仍待落地 |
| 已完成 | rag.embedding（vectorDim）与 rag.deepseek-ocr 旧配置迁移 | 已迁移至 spring.ai.platform.models + extensions（dim / max-tokens / page-max-tokens），消费方改读 AiModelProperties |
| 待迁移 | DashScope 适配器依赖 spring-ai-alibaba（非官方 starter） | 版本已与 Spring AI 1.0.0 对齐，需关注后续兼容性 |
| 性能 | 单服务部署，无分布式向量检索 | 大规模知识库场景性能受限 |
| 性能 | Embedding/OCR 均同步调用 | 大文件上传可能超时（OCR 超时 120s） |
| 性能 | 多库检索串行遍历 | 知识库多时延迟线性增长 |
| 稳定性 | DeepSeek-OCR 依赖硅基流动付费 API | 402 错误（账户余额不足）会导致 OCR 失败 |
| 稳定性 | 模型实例缓存无过期策略 | 模型配置变更后需重启 |
| 稳定性 | 临时文档处理为同步阻塞 | 大文件会拖慢首字延迟（TTFT） |
| 稳定性 | Embedding 限流 | 硅基流动 /embeddings 密集请求会 Connection reset；已内置 150ms 节流（以时间换稳定） |
| 数据模型 | 一用户一租户一角色 | user_tenant_role 按 user+tenant 唯一约束，前端角色选择为单选 |

**近期遗留事项**（详见 work-log）：
- 已实现的 `doc_chunk` 命名语义统一（主键 `doc_chunk_id` 与业务键 `chunk_id` 分离，parent_chunk_id 存父块 UUID）：**存量库需执行 schema-v2.sql 内附 ALTER 迁移**，重启后端后新入库分片才写入 chunk_id/chunk_type/parent_chunk_id；已入库旧 hierarchical 数据 parent_chunk_id 仍为空，需重建文档。
- 场景化温度、preQueryFilter、maxTokens 等均为后端代码 + 配置改动，需**重启后端**生效；preQueryFilter 全局开关默认 false。
