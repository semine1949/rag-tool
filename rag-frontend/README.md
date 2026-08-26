# NebulaKB 前端工程

企业级 RAG 知识库平台前端，基于 **React 18 + TypeScript + Vite + Tailwind CSS** 构建，采用深色玻璃拟态设计系统，内置完整 Mock 数据层，可独立运行开发。

## 快速开始

```bash
cd rag-frontend
npm install
npm run dev
```

默认运行在 http://localhost:5173/，登录页已预填演示账号 `tenant_admin / admin123`。

## 目录结构

```
rag-frontend/
├── src/
│   ├── components/
│   │   ├── ui/            # 基础 UI 组件（Button/Card/Modal/Badge/Table/Input/Select/Tabs/Toast 等）
│   │   ├── charts/        # 图表组件（SVG 折线图、环形图、环形分布图）
│   │   ├── icons/         # 内联 SVG 图标集
│   │   └── layout/        # Sidebar / Topbar / AppLayout / Background
│   ├── features/
│   │   ├── auth/          # 登录注册页、AuthContext、RequireAuth
│   │   ├── dashboard/     # 概览页指标卡、趋势图、健康度、活动流、文档类型分布
│   │   ├── chat/          # 智能问答、流式输出、引用溯源
│   │   ├── kbs/           # 知识库卡片网格、新建弹窗、集合运维
│   │   ├── docs/          # 文档表格、拖拽上传
│   │   ├── tenants/       # 租户表格、创建弹窗、三级隔离架构图
│   │   ├── users/         # 用户表格、角色标签、创建弹窗
│   │   └── roles/         # 角色卡片、权限矩阵、判定逻辑、认证机制
│   ├── lib/
│   │   ├── api/           # Axios client、JWT 拦截器、tokenStore、mock server
│   │   ├── types/         # 领域模型类型定义
│   │   ├── utils/         # className 合并、格式化工具
│   │   └── rbac.ts        # RBAC 权限矩阵与权限判定
│   ├── styles/
│   │   └── index.css      # Tailwind 导入 + 玻璃拟态全局样式
│   ├── main.tsx           # 应用入口
│   ├── router.tsx         # 8 页路由表
│   └── vite-env.d.ts      # Vite 环境变量类型
├── index.html
├── package.json
├── tailwind.config.js     # Design Token 映射
├── vite.config.ts         # Vite 配置 + 后端代理
└── .env.example           # 环境变量示例
```

## 设计系统 Design Token

| Token | 值 | 用途 |
|-------|-----|------|
| 背景 | `#070b15` | 页面主背景 |
| 玻璃卡片表面 | `rgba(18,26,46,0.62)` | Card / Modal 背景 |
| 背景模糊 | `blur(18px)` | 毛玻璃效果 |
| 边框 | `rgba(255,255,255,0.08)` | 组件边框 |
| 主文字 | `#e8eefb` | 标题正文 |
| 次要文字 | `#8a98b5` | 说明、提示 |
| 主色 accent | `#22d3ee` | 青 |
| accent-2 | `#a855f7` | 紫 |
| accent-3 | `#3b82f6` | 蓝 |
| 状态绿 | `#34d399` | 成功/健康 |
| 状态琥珀 | `#fbbf24` | 警告 |
| 状态红 | `#f87171` | 失败/危险 |
| 主渐变 | `linear-gradient(135deg, #22d3ee, #a855f7)` | 按钮、强调 |
| 圆角 | `16px` | 卡片圆角 |

视觉风格：深色 + 3 个 `blur(90px)` 背景光斑、玻璃拟态卡片、hover 上浮 + 青辉光。

## 路由与页面

| 路径 | 页面 | 默认所需权限 |
|------|------|--------------|
| `/login` | 登录/注册双卡片 | 无需登录 |
| `/dashboard` | 概览 | 登录 |
| `/chat` | 智能问答 | `chat:query` |
| `/kbs` | 知识库管理 | `doc:view` |
| `/docs` | 文档管理 | `doc:view` |
| `/tenants` | 租户管理 | `tenant:manage` |
| `/users` | 用户管理 | `user:manage` |
| `/roles` | 角色权限 | `role:assign` |

## API 层与 Mock 切换

所有 HTTP 请求都通过 `src/lib/api/client.ts` 中的统一 `http` 实例发出：

- 请求拦截器自动注入 `Authorization: Bearer <accessToken>`
- accessToken 过期前 60 秒自动静默刷新，并发请求共享同一刷新流程
- 401 响应会尝试刷新一次，失败则清空登录态并跳转登录页
- `src/lib/api/mock/server.ts` 提供完整内存 Mock 服务，支持登录失败锁定、文档异步索引、SSE 流式输出等

**切换真实后端：**

1. 复制 `.env.example` 为 `.env`：
   ```env
   VITE_API_BASE_URL=/api
   VITE_USE_MOCK=false
   VITE_PROXY_TARGET=http://localhost:8080
   ```
2. 保持 `VITE_USE_MOCK=false` 关闭 Mock，前端将直接请求后端接口。
3. `vite.config.ts` 中已将 `/api` 代理到 `VITE_PROXY_TARGET`（默认 Spring Boot 8080），开发时无需额外处理 CORS。
4. 后端需实现统一响应体（与 `ApiResult<T>` 一致）：
   ```json
   { "code": 0, "message": "ok", "data": { ... } }
   ```

### 已对接端点清单

- 认证：`POST /api/auth/login|register|refresh`、`GET /api/auth/user/{id}`
- 问答：`POST /api/rag/chat`、SSE `POST /api/rag/chat/stream`
- RAG：`POST /api/rag/upload`、`POST /api/rag/search`、`POST /api/rag/multi-search`、`GET /api/rag/documents`
- 管理：`/api/admin/tenant*`、`/api/admin/user`、`/api/admin/role/list`、`/api/admin/kb*`
- 模型：`GET /api/admin/kb/models`

SSE 流式端点约定事件类型：`citations`、`content`、`done`、`error`。

## 脚本说明

```bash
npm run dev       # 启动开发服务器
npm run build     # 类型检查 + 生产构建
npm run preview   # 预览生产构建产物
npm run typecheck # 仅 TypeScript 类型检查
```

## 注意事项

- 当前为前端工程，未配置前后端联调时默认启用 Mock 模式，便于演示全部交互。
- 真实后端接入后，请按 `ApiResult<T>` 规范返回数据，否则统一响应解包会抛错。
- 由于 Vite 代理仅作用于开发环境，生产环境部署时前端静态资源应与后端部署在同一域名，或在后端配置 CORS。
