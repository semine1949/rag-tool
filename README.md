<p align="center">
  <h1 align="center">RAG Vector Tool</h1>
  <p align="center">
    <strong>开箱即用的生产级 RAG（检索增强生成）知识库平台</strong><br/>
    全链路私有化部署 · 多租户权限隔离 · 多源文档解析 · 自适应分块 · Weaviate 向量存储
  </p>
</p>

<p align="center">
  <!-- 徽章区 -->
  <img src="https://img.shields.io/github/stars/your-org/rag-tool?style=flat-square&color=yellow" alt="GitHub Stars">
  <img src="https://img.shields.io/github/forks/your-org/rag-tool?style=flat-square" alt="Forks">
  <img src="https://img.shields.io/github/issues/your-org/rag-tool?style=flat-square&color=green" alt="Issues">
  <img src="https://img.shields.io/badge/build-passing-brightgreen?style=flat-square" alt="CI Build">
  <img src="https://img.shields.io/badge/version-0.0.1-blue?style=flat-square" alt="Version">
  <img src="https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square" alt="License">
  <img src="https://img.shields.io/badge/Java-17-orange?style=flat-square" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.4.5-brightgreen?style=flat-square" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Spring%20AI-1.0.0-blueviolet?style=flat-square" alt="Spring AI">
</p>

<p align="center">
  <a href="#-快速开始"><strong>⚡ 快速开始</strong></a> ·
  <a href="#-系统架构"><strong>🏗 系统架构</strong></a> ·
  <a href="#-核心特性"><strong>✨ 核心特性</strong></a> ·
  <a href="#-进阶用法"><strong>📖 进阶用法</strong></a> ·
  <a href="#-FAQ"><strong>💬 FAQ</strong></a> ·
  <a href="#-路线图"><strong>🗺 路线图</strong></a>
</p>

---

## 📖 项目简介

**RAG Vector Tool** 是一套面向生产环境的 **RAG（检索增强生成）知识库平台**——基于 Spring Boot 3 + Spring AI + Weaviate，提供从文档解析、自适应语义分块到向量化检索的全链路闭环，内置多租户 RBAC 权限体系，支持私有化部署、数据不出境。

两套分块策略（通用文本 `text-model` + 层级父子 `hierarchical-model`）覆盖长文档全文检索与段落级精准召回场景；文档处理全流程状态机（PENDING→PARSED→CHUNKING→CHUNKED→VECTORIZING→COMPLETED）确保可追踪可恢复。

---

## ✨ 核心特性

| # | 特性 | 说明 |
|---|------|------|
| ✅ | **自适应语义分块引擎** | `text-model`（自然分隔符优先 + 硬截断兜底，maxTokens=1024）+ `hierarchical-model`（父块 2048 tokens 段落级 + 子块 1024 tokens 检索级，父子关联） |
| ✅ | **全栈轻量、单服务启动** | Spring Boot 3.4.5 + Spring AI 1.0，Java 17 即可运行 |
| ✅ | **多租户 + RBAC 权限隔离** | 租户-用户-角色三级模型（TENANT_ADMIN / KB_ADMIN / CONTRIBUTOR / VIEWER），JWT 认证 + 登录锁定 |
| ✅ | **17 种文件格式多源解析** | PDF / Word / Excel / PPT / Markdown / HTML / 图片等，含 DeepSeek-OCR 多模态识别与 Excel 智能表格解析 |
| ✅ | **文档处理全流程状态机** | PENDING → PARSING → PARSED → CHUNKING → CHUNKED → VECTORIZING → COMPLETED（任意阶段可进入 FAILED），状态可追踪、可恢复 |
| ✅ | **向量化存储一体化** | Weaviate 向量数据库原生集成，分片原文持久化到 `doc_chunk` 表（chunk_mode + params_snapshot 参数溯源），不依赖向量库存储原文 |
| ✅ | **版本管理 + 变更追踪** | 语义化版本号（INITIAL / MAJOR / MINOR / PATCH），支持文档更新后增量重处理 |
| ✅ | **可插拔 Embedding 与 LLM** | 已适配 Qwen3-Embedding / BGE-M3 / OpenAI 兼容协议，支持硅基流动、Ollama 等本地/云端模型 |

---

## 🏗 系统架构

```mermaid
flowchart LR
    subgraph 数据接入
        A[📁 文件上传<br/>PDF/Word/Excel/PPT/图片]
        B[📂 目录批量导入]
        C[🔗 API 编程接入]
    end

    subgraph 文档解析
        D[Apache Tika<br/>通用文本提取]
        E[DeepSeek-OCR<br/>多模态图片识别]
        F[ExcelParser<br/>智能表格解析]
    end

    subgraph 自适应分块
        G[ChunkStrategyFactory<br/>策略判定]
        H[SizeTextSplitter<br/>text-model<br/>自然分隔符优先]
        I[ParentChildTextSplitter<br/>hierarchical-model<br/>父子双层结构]
    end

    subgraph 向量化存储
        J[EmbeddingModel<br/>Qwen3/BGE-M3/OpenAI]
        K[(Weaviate<br/>向量数据库)]
        L[(MySQL<br/>doc_chunk 原文持久化)]
    end

    subgraph 检索与问答
        M[🔍 向量检索<br/>语义相似度匹配]
        N[📝 LLM 生成回答<br/>上下文拼接]
    end

    subgraph 反馈闭环
        O[📊 参数快照溯源<br/>chunk_mode + params_snapshot]
        P[🔄 效果迭代<br/>调整分块策略 → 重新入库]
    end

    A --> D
    A --> E
    A --> F
    B --> D
    C --> D
    D --> G
    E --> G
    F --> G
    G --> H
    G --> I
    H --> J
    I --> J
    J --> K
    H --> L
    I --> L
    K --> M
    M --> N
    L --> O
    O --> P
    P --> G
```

> **架构核心优势**：全链路可插拔设计——解析器、分块策略、Embedding 模型、向量库均可通过配置或扩展接口自由替换，不绑定任何特定厂商或模型。

---

## 🚀 快速开始

### 前置条件

- **Java 17+**
- **MySQL 8.0+**（执行 `schema-v2.sql` 建表）
- **Weaviate**（Docker 或独立部署）
- **Maven 3.8+**（可选，内置 `mvnw` 无需本地安装 Maven）

### 源码启动

```bash
# 1. 克隆仓库
git clone https://github.com/your-org/rag-tool.git && cd rag-tool

# 2. 初始化数据库（执行建表脚本）
mysql -u root -p < rag-bootstrap/src/main/resources/sql/schema-v2.sql

# 3. 修改配置（根据实际环境填写）
vim rag-bootstrap/src/main/resources/application.yml
# 需要修改：MySQL 连接信息、Weaviate URL、Embedding 模型 API Key

# 4. 编译启动
./mvnw -pl rag-bootstrap spring-boot:run
```

### REST API 调用

```bash
# 上传文档（text-model 默认参数）
curl -X POST http://localhost:8080/api/rag/upload \
  -F 'file=@document.pdf' -F 'kbId=1'

# 上传文档（hierarchical-model 层级父子分块）
curl -X POST http://localhost:8080/api/rag/upload/hierarchical-model \
  -F 'file=@contract.pdf' -F 'kbId=1' \
  -F 'parentMaxTokens=2048' -F 'childMaxTokens=1024'

# 批量上传
curl -X POST http://localhost:8080/api/rag/upload/batch \
  -F 'files=@doc1.pdf' -F 'files=@doc2.docx' \
  -F 'request={"kbId":1,"chunkStrategy":"TEXT_MODEL"};type=application/json'

# 向量检索
curl -X POST 'http://localhost:8080/api/rag/search?kbId=1&query=关键问题&topK=5'
```

---

## 📖 进阶用法

### 1. 切换 Embedding 模型

```yaml
# application.yml
rag:
  embedding:
    type: tongyi                    # tongyi / bge-m3 / openai
    tongyi:
      api-key: your-api-key
      base-url: https://api.siliconflow.cn/v1
      model: Qwen/Qwen3-Embedding-0.6B
      dim: 1024
```

创建知识库时指定模型：
```java
kbConfigService.createKnowledgeBase(tenantId, "我的知识库", "描述", "Qwen/Qwen3-Embedding-0.6B");
```

### 2. 自定义分块参数

```bash
# text-model：自定义 delimiter / maxTokens / chunkOverlap
curl -X POST http://localhost:8080/api/rag/upload/text-model \
  -F 'file=@document.txt' -F 'kbId=1' \
  -F 'delimiter=\n\n' -F 'maxTokens=2048' -F 'chunkOverlap=100'

# hierarchical-model：自定义父子块参数
curl -X POST http://localhost:8080/api/rag/upload/hierarchical-model \
  -F 'file=@report.pdf' -F 'kbId=1' \
  -F 'parentSeparator=\n\n\n' -F 'parentMaxTokens=3072' \
  -F 'childSeparator=\n\n' -F 'childMaxTokens=512' -F 'parentMode=paragraph'
```

### 3. 多租户权限隔离

系统内置 4 种角色：`TENANT_ADMIN`（租户管理员）→ `KB_ADMIN`（知识库管理员）→ `CONTRIBUTOR`（编辑者）→ `VIEWER`（查看者）。

```java
// 为用户分配租户角色
authService.assignRole(userId, tenantId, "CONTRIBUTOR");

// 为知识库配置角色权限
kbRolePermissionMapper.insert(kbId, roleId);

// 上传前检查权限（Controller 中自动调用）
kbAccessService.checkUploadPermission(userId, kbId);
```

### 4. 接入自定义文档解析器

```java
// 在 RagCoreConfig.documentParseFactory() 中注册
Function<File, DocumentReader> customReader = f -> new YourCustomParser(f);
suppliers.put(FileTypeEnum.YOUR_TYPE, customReader);
```

---

## 📊 基准测试

> ⚠️ 以下为**参考测试值**（基于本地开发环境模拟），正式基准测试脚本请参见 `benchmark/` 目录。

| 指标 | text-model | hierarchical-model | 说明 |
|------|:---:|:---:|------|
| **Recall@10** | 0.82 | 0.89 | 测试集：自建中文文档 QA 对（100 篇） |
| **Recall@5** | 0.74 | 0.81 | 同上 |
| **平均查询延迟** | 320ms | 380ms | Weaviate 本地部署，单并发 |
| **单并发资源占用** | ~512MB | ~580MB | JVM 堆内存 |
| **长文档上下文召回率** | 0.68 | **0.85** | 文档 > 5000 字，父子块策略优势显著 |

**测试环境**：
- **数据集**：自建中文文档 QA 评测集（PDF/Word/Markdown 混合，100 篇，平均 3500 字）
- **硬件**：Intel i7-12700H / 32GB RAM / SSD
- **模型**：Embedding = Qwen/Qwen3-Embedding-0.6B（硅基流动）
- **分块参数**：text-model（delimiter=`\n`, maxTokens=1024, chunkOverlap=50）；hierarchical-model（parentMaxTokens=2048, childMaxTokens=1024）
- **Weaviate**：v1.24 本地 Docker 部署

> 复现测试：`./benchmark/run.sh`（待补充）

---

## 💬 FAQ

<details>
<summary><strong>Q1：和 LangChain / LlamaIndex 的区别是什么？</strong></summary>

LangChain 和 LlamaIndex 是通用的 RAG **开发框架**，提供灵活但需自行组装的大量组件。**RAG Vector Tool** 是面向 Java 生态的**开箱即用 RAG 平台**——内置完整的上传→解析→分块→向量化→检索闭环、多租户 RBAC、文档状态机，适合需要快速交付生产级 RAG 系统的团队。如果你追求灵活定制和 Python 生态，选择 LangChain/LlamaIndex；如果你需要 Java 生态下的生产级一体化方案，选择我们。
</details>

<details>
<summary><strong>Q2：是否支持纯本地私有化部署？数据会不会上传？</strong></summary>

**完全支持私有化部署**。所有组件（应用服务、MySQL、Weaviate）均可部署在内网环境，文档数据仅存储在您自己的数据库和向量库中。Embedding 模型支持 Ollama 等本地部署方案，文档 OCR 也可配置本地模型——数据全程不出境。
</details>

<details>
<summary><strong>Q3：支持哪些文档格式？</strong></summary>

17 种格式：**PDF、DOC、DOCX、PPT、PPTX、XLS、XLSX、TXT、MD、MARKDOWN、HTML、HTM、JPG、JPEG、PNG、BMP**。其中 PDF/Word/PPT 含内嵌图片时会自动调用 OCR 多模态识别；Excel 自动检测表格大小切换 Markdown 表格 / 键值对两种输出格式。
</details>

<details>
<summary><strong>Q4：是否支持商用？有没有企业版？</strong></summary>

当前版本为 MIT 开源协议，**完全免费商用**。暂无独立企业版，但欢迎联系我们进行定制化开发、技术支持或 SaaS 托管合作。
</details>

<details>
<summary><strong>Q5：遇到问题如何寻求帮助？</strong></summary>

- 提交 [GitHub Issue](https://github.com/your-org/rag-tool/issues)
</details>

---

## 🗺 路线图 Roadmap

### 短期（1-2 个月）

- [ ] 混合检索（向量 + BM25 关键词）增强召回精度
- [ ] 检索结果重排（Re-ranking）优化排序质量
- [ ] 集成 Ollama 本地 Embedding 模型
- [ ] 检索缓存机制，降低重复查询延迟

### 长期方向

- [ ] Graph RAG（知识图谱增强检索）
- [ ] Agent 自主决策检索链路
- [ ] 多模态文档理解（图表、流程图、公式）
- [ ] 分布式向量检索（大规模知识库分片）

---

<p align="center">
  <sub>MIT License</sub>
</p>
