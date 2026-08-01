# CLAUDE.md

## Repository overview

全功能营销工具库 — 本地优先的短视频提取、内容创作与营销工具平台。

技术栈：Vue 3 + Vite 前端，Express + TypeScript 后端，PostgreSQL 数据库。

> 草场长期目标架构见 `docs/草场Java微服务技术架构与渐进迁移方案.md` 与 `docs/草场系统技术总体设计（HLD-v0.1）.md`。当前是 Java 草场领域与 Express legacy/worker 并存的混合架构：identity、marketplace、finance、trust、intelligence 已有 Java 服务；Express 仍承载部分 legacy API、FFmpeg/Playwright worker 与迁移 fallback。生产默认 Nginx 仍直连 `backend:3000`，`edge-bff` 的 RouteManifest/feature flag 是启动期的逐路由切流控制。当前未完成开发与生产门禁见 `docs/草场开发进度与续接指南.md` 当前 backlog 及 memory `grassland-prioritized-backlog.md`。

## Java 平台（草场 Epic 0/1）

- 工程：`platform-java/`（Gradle Kotlin DSL 多模块，版本目录 `gradle/libs.versions.toml`）
- 模块：`services/edge-bff`、`services/identity-service`、`services/marketplace-service`、`services/finance-service`、`services/trust-service`、`services/intelligence-service`
- 工具链：JDK 25（`brew install openjdk@25`）；通过 `./gradlew` 构建，不依赖系统 Gradle
- `edge-bff` 是固定上游透明代理，零聚合透传 SSE / Multipart / Range，剥离 hop-by-hop header；契约矩阵见 `docs/草场旧API兼容契约矩阵.md`
- 可选 Compose Profile：`docker compose --profile java-edge up edge-bff`；默认生产入口仍由 Nginx 直连 `backend:3000`，RouteManifest flag 是启动期配置，回滚通常需要重启或替换 edge 实例
- 保持既有 public API/Express 行为与兼容契约；新草场领域直接进入 Java 服务，必要时允许聚焦的 bridge、routing、worker 兼容修改，不把 FFmpeg/Playwright 塞入 WebFlux 请求线程

核心功能模块：
- **视频提取分析**：抖音/Bilibili 视频解析、预览、下载、音频提取、AI 视频分析
- **图片评价文案**：上传图片 → 选择平台（淘宝/大众点评）→ 生成评价 → 一键复制 / 导出飞书
- **爆款文章创作**：主题 → 平台（微信公众号/知乎/小红书）→ 标题 → 大纲 → 正文 → 按段落配图，SSE 流式输出
- **脱口秀生成**：仿李继刚风格，SSE 流式输出
- **图片生成**：独立图片生成，支持参考图上传和 @mention
- **视频内容改编**：将视频分析结果转为分镜脚本，支持用户自定义指令和图片上传
- **视频制作**：上传素材图片 + 店铺信息 → AI 脚本生成（SSE 流式）→ 视频生成（Seedance API，待对接）
- **账号系统**：邮箱注册（图形验证码 + 邮箱验证码）、登录、按用户隔离设置
- **积分系统**：功能使用扣积分，管理员可调整
- **热点聚合**：多平台热点（抖音/微博/知乎），支持 60s API 和 ALAPI 两种数据源

## Commands

```bash
npm run dev              # 前后端同时启动（concurrently）
npm run dev:client       # 仅前端（Vite, port 5173）
npm run dev:server       # 仅后端（Express, port 3000）
npm run build            # 前后端一起构建
npm run start            # 生产模式启动
npm run test             # 运行测试
npm run typecheck        # TypeScript 类型检查
npx tsx server/src/scripts/run-migrations.ts  # 执行数据库 migration
```

DATABASE_URL 由运行时环境、`.env` 或 Secret Manager 提供；文档和提交中不得记录数据库连接值、密码或其他凭据。

## Architecture

### Frontend (`src/`)

- `App.vue` — 顶层 shell：标签导航（首页/视频提取分析/图片评价文案/爆款文章/脱口秀）、认证、设置、积分、管理
- `components/` — 各功能模块的页面组件
- `composables/` — 请求与状态逻辑（`useAuth`, `useDouyinParse`, `useArticleCreation`, `useCredits` 等）
- `types/` — 前端类型定义

### Backend (`server/src/`)

- `app.ts` — Express 应用工厂，CORS、限流、session、错误处理
- `routes/` — 路由定义，按功能模块拆分
- `controllers/` — HTTP handler
- `services/` — 业务逻辑（视频提取、分析、文章生成、积分、管理等）
- `services/providers/` — AI 分析提供者抽象（Coze 工作流、Qwen/OpenAI 兼容）
- `lib/` — 基础能力（认证、数据库、密码、邮件、限流、环境变量等）
- `schemas/` — 请求验证 schema（Zod）
- `sql/` — 数据库 migration 文件

### Key architectural patterns

- 视频预览/下载走后端代理，不暴露上游地址给前端
- 分析提供者和凭据由服务端持久化设置决定，浏览器不能直接调用第三方服务
- SSE 流式输出用于文章大纲/正文生成和脱口秀生成（`fetch` + `response.body.getReader()`，非 EventSource）
- 文章配图按段落拆分，用户可跳过单个段落
- 积分扣减使用原子操作 `UPDATE ... WHERE balance >= 1 RETURNING`
- `queryDb<T>()` 返回 `QueryResult<T>` — 始终用 `.rows` 访问数据

## API routes

| 模块 | 路由前缀 | 说明 |
|------|----------|------|
| 认证 | `/api/auth/*` | login, register, captcha, send-code, logout, me |
| 首页 | `/api/homepage/hot-items` | 热点数据 |
| 抖音 | `/api/douyin/*` | extract, analyze, analysis-media, proxy, download, audio, session(GET/POST), session/start, session/poll, session/logout, hot-items |
| Bilibili | `/api/bilibili/*` | extract, analyze, analysis-media, proxy, download |
| 图片评价 | `/api/image-analysis/*` | analyze (SSE), export-feishu, save-style-memory, style-preferences (GET/PUT), style-preferences/optimize, step/draft, step/optimize, step/style-refine |
| 文章生成 | `/api/article-generation/*` | titles, outline (SSE), content (SSE), image-recommendations, search-images, generate-image |
| 脱口秀 | `/api/comedy-generation/*` | generate-script (SSE) |
| 视频制作 | `/api/video-production/*` | `capabilities`（GET）、`generate-script`（SSE）可用；`generate-video` 当前是 Seedance stub，已 gate 为 501 且不扣积分 |
| 视频改编 | `/api/video-recreation/*` | adapt-content, generate-asset-image, generate-all-asset-images, generate-scene-image, generate-all-scene-images |
| 积分 | `/api/credits/*` | balance, history |
| 管理 | `/api/admin/*` | users, adjust-credits（需 admin 角色）|
| 设置 | `/api/settings/*` | analysis (GET/PUT), analysis/models, analysis/verify-model, homepage (GET/PUT)（需登录）|
| 健康 | `/health` | 健康检查 |

## Non-obvious conventions

- Preview/download URL 是后端签名代理端点，不是上游原始地址
- 设置弹窗中密钥留空 = 保留已保存的密钥；输入空格后保存 = 清空密钥
- `video-analysis.service.ts` 按设置分发到 Coze 或 Qwen，平台服务与提供者无关
- `proxyVideoUrl` 必须是本站相对路径或与 `PUBLIC_BACKEND_ORIGIN` 同源
- 抖音登录增强仅作为 fallback，不是默认提取路径
- 文章生成仅支持 Qwen；Coze 是工作流引擎，不支持自由文本对话
- 脱口秀生成使用 `enable_thinking: false` 防止推理内容混入输出
- `npx tsx script.ts` 用于 DB 脚本（不要用 `-e` 内联模式）
- 注册流程：图形验证码 → 邮箱验证码 → 注册
- `provider-url.ts` 维护 `TRUSTED_PUBLIC_API_SUFFIXES` 白名单，匹配时跳过 SSRF 私有 IP 检查
- 管理员路由需要 `requireAuthenticatedUser` + `requireAdmin` 双重中间件
- 视频制作图片用 base64 在 JSON body 中传输（≤9 张），`express.json` limit 已调至 10MB
- `video-production.service.ts` 的 `isVideoGenerationAvailable()` 是「视频生成是否可用」的唯一真相源（`VIDEO_GENERATION_IMPLEMENTED` 常量硬关，无 env 开关）。**不可用能力不得先扣 `video_production_video` 积分**：controller 的 gate 必须留在 `requireCredit` 之前，`generate-video` 返回 501。前端经 `GET /api/video-production/capabilities` fail-closed 禁用入口。接入 Seedance 时改常量一处即全链生效
- 环境变量定义在 `server/src/lib/env.ts`，是运行时配置的唯一来源
- API 表描述逻辑契约；实际 upstream 由 edge-bff RouteManifest 与对应 flag 决定，未启用的 Java 路由回落 legacy

## Testing

```bash
npm run test && npm run typecheck && npm run build
```

Priority tests by area:
- 提取：`douyin-resolve.service.test.ts`, `douyin.controller.test.ts`, `douyin-proxy.service.test.ts`
- 音频：`douyin-audio.service.test.ts`
- 分析：`video-analysis.service.test.ts`, `bilibili-video-analysis.service.test.ts`
- 文章：`article-generation-dispatch.service.test.ts`
- 图片评价：`image-analysis.controller.test.ts`
- 认证：`auth.controller.test.ts`, `auth.test.ts`
- 热点：`hot-topics-60s.service.test.ts`, `homepage-hot.service.test.ts`
- 设置：`settings.controller.test.ts`, `analysis-settings.service.test.ts`

## Working conventions

- 优先小范围、聚焦的修改
- 复用已有的 resolver/session/proxy/audio/analysis 服务，不要重复逻辑
- 不要削弱 trusted-host 检查、代理 URL 验证、挑战检测或 session fallback 语义
- 公开分析端点不能接受浏览器传入的提供者地址或凭据
- 保持 README 和 CLAUDE.md 与实际代码一致
