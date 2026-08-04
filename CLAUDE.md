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
- 积分扣减使用原子操作 `UPDATE ... WHERE balance >= 1 RETURNING`，且余额 UPDATE 与流水 INSERT 必须在同一 `withDbTransaction` 内（GL-P0-CRED-001）
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
- 草场 credits bridge 挂在 `/internal/credits/{consume,refund}`，**不在 `/api` 树下**（nginx 只反代 `/api/`，并对 `/internal/` 与旧 `/api/internal/` 显式 404）；`INTERNAL_API_KEY` 未配置时 fail-closed 503，带任一 `X-Forwarded-*`/`Forwarded` 头一律 404。Java 侧路径在 intelligence `application.yml` 里，改路径要两边一起改
- `requireCredit()` 返回 `CreditCharge` 句柄，上游失败时调 `charge.refund(note)` 写 `refund` 流水（GL-P0-BILL-002）。退款键是 `refund:<operationId>`，靠 `operation_id` 唯一索引保证一次扣减至多一次退款；`refund()` 失败只记日志不抛，避免掩盖原始错误。用户主动 abort 不退款（内容已流出）
- 会话 cookie 属性由 `SESSION_COOKIE_SECURE`（`auto|always|never`）/`SESSION_COOKIE_SAME_SITE` 决定，**backend 与 identity-service 必须同值**：两端写同一张 `session` 表，express-session 的 rolling 续期按库里 `sess.cookie` 重发 Set-Cookie，Java 侧写错会抹掉 Secure。Java 侧唯一真相源是 `SessionCookiePolicy`，Express 侧是 `resolveSessionCookieSecure()`。`auto` 需要 `TRUST_PROXY=1`；`always` 在 HTTP 入口上会让 express-session **整个不发 cookie**（登录静默失效），Java 侧则照发 —— 混合部署统一用 `auto`（GL-P0-AUTH-001）
- 安全响应头在 `lib/security-headers.ts` 与 `nginx.conf` 各有一份（直连 backend 的部署没有 nginx）。nginx 侧用 `proxy_hide_header` 去掉上游同名头再发唯一一份；**不要在 `/api/` location 内写 `add_header`** —— 那会丢弃 server 级整组 `add_header`。HSTS 是双条件：`SECURITY_HSTS_ENABLED=1` **且** 本次请求确实是 HTTPS。有意不加 CSP（当前前端含内联样式，需单独一项做 report-only → 强制）
- 状态变更请求（POST/PUT/PATCH/DELETE）经 `lib/csrf.ts` 校验 Origin/Referer，挂在 `/api` 之前（跨站请求应在建会话与解析 body 前被拒）。无 Origin 也无 Referer 的请求放行（非浏览器客户端），**有 Origin 就必须匹配**。同源判断刻意读 `X-Forwarded-Proto` 而非 `req.secure` —— 漏配 `TRUST_PROXY=1` 时 `req.secure` 为假，会把 HTTPS 同源请求算成跨站并全量 403。`/internal/credits` 不挂此检查（服务间通道，由 `INTERNAL_API_KEY` + `rejectForwardedRequest` 把关）
- 事务邮件走 identity 的 `mail_outbox`（第五份 outbox，GL-P1-NOTIFY-001）：`NotificationEventProcessor.emit` 在站内通知插入的**同一事务**内 `mail_outbox.append`，保证「通知落库 ⇔ 邮件入队」原子。`MailOutboxPublisher` 轮询 SMTP 发送，失败 5 次后 `status=dead`（区别于领域 outbox 的无限重试）。`MailTemplates` **委托 `NotificationTemplates` 拿文案** + PERMISSION 过滤（高价值子集：邀请/履约/争议/资金）。**验证码保持同步直发**（`SendCodeController`→`SmtpMailSender`，用户在等，不经 outbox）。邀请事件 `MembershipInvited`/`Revoked` 邮件收件人 = `payload.email`（未注册邮箱也能发），其余 = accountId→`app_users.email`
- KYB 敏感字段（法人身份证号、收款账号）唯一写入通道是 identity 的 `KybFieldCrypto`，接 `platform-crypto` 信封加密（KEK=`CRYPTO_KEK_BASE64`，与 intelligence BYOK 同一变量）。**未配 KEK 时相关请求 503，不得退化为存明文**；读取侧只回末 4 位掩码（`legalPersonIdNumberMasked`/`accountNumberMasked`），完整明文不出响应体也不进 outbox payload（D-10）。审核入队唯一通道是 `KybSubmissionService`——`submit` 必须在同一事务内完成「状态变更 + `kyb_verification_request` 入队 + outbox」，漏掉入队会让 admin 队列恒空、审核不可达（GL-P3-MERCHANT-001）
- identity-service **没有全局 `SecurityWebFilterChain`**，授权是逐 controller 约定：平台 admin 端点用 `CurrentAccountResolver.requireAdmin`，组织内端点用 `OrgAuthorization.requireRole`。新增端点漏掉这一行就是完全无鉴权（`/api/admin/kyb-requests` 曾如此）。仓储层的跨租户守卫是第二道闸：改删组织资源的 SQL 必须带 `organization_id` 条件，只按 id 定位等于放开跨租户写
- 移动端 token 认证由**请求头 `X-Device-Info` 单点切换**（GL-P3-IDENTITY-001）：带该头 → `LoginController` 走 token 分支，签 access/refresh token 且**不建 session 行、不发 Set-Cookie**；不带 → Web cookie 路径逐字节不变。`IDENTITY_ACCESS_TOKEN_SECRET` 未配时移动端 503（fail-closed），Web 不受影响。refresh token 只以 SHA-256 落 `refresh_token` 表，**v1 刻意不轮换**（刷新只 touch `last_used_at` + 重签 access token）；access token 的 `session_token` claim = refresh_token 行 id，`identity_session` 与设备撤销都按它定位。⚠️ **edge-bff 侧验签（`AccessTokenFilter`）尚未实现**，Bearer 经 BFF 换不到内部断言，端到端未通。Argon2 hash/rehash 必须 `subscribeOn(Schedulers.boundedElastic())` —— 64MB/3 轮的重操作留在 Netty 事件循环上会拖垮整个服务
- AI 控制面（GL-P3-AI-001，intelligence `ai/controlplane/` + `ai/run/`）：平台模型配置（`platform_model_config`，主备 + 版本化，V1 雏形已在 V7 重建）admin CRUD 在 `/api/admin/ai/models`，**全部经 `IntelligenceCallerResolver.requireAdmin`**（断言 `role=admin`；intelligence 之前**没有任何 admin 门闩**，`Caller` 连 `role` 都不带——这是本轮新加的）。`ByokRoutingService` 平台分支查控制面；**BYOK→平台回退须 `allowFallback` 显式授权**（HLD §12.3 硬规则），否则返回 `DENIED(fallback_not_authorized)` 不静默扣平台额度。`AiExecutionService` 是执行闭环唯一编排：平台 run 先 `credits.consume` 且用 `charge.operationId()` 作 run `operation_id` 保退款幂等、BYOK run **不扣分**（D-11）并解密 key（明文只活在 `ExecutionContext`，不入日志/响应/outbox，无 KEK→503）；失败提交后 best-effort `credits.refund`（不在 DB 事务内做 HTTP），`AiRunCompleted/Failed` 经 `intelligenceTransactionalOperator` 同事务 append outbox。`TaskContext`（`ai_run` 的 `price_table_version`/`platform_model_version`/`fallback_authorized`，V8）在 Run 起始冻结。`/api/ai`（keys + runs）+ `/api/admin/ai` 经 edge 路由到 intelligence（`EDGE_ROUTE_AI_INTELLIGENCE`/`EDGE_ROUTE_ADMIN_AI_INTELLIGENCE`，精确前缀不抢 legacy `/api/admin/users`）；此前 `/api/ai/keys` 落 legacy 隐性 404、本轮才首次可达。真实 provider 调用经 `TextCompletionClient`（`ProviderUrlGuard` SSRF 第一道闸；pinned-DNS 未接是已知缺口）
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
