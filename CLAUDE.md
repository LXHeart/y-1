# CLAUDE.md

## Repository overview

全功能营销工具库 — 本地优先的短视频提取、内容创作与营销工具平台。

技术栈：Vue 3 + Vite 前端，Java 25 + Spring Boot 4 后端，PostgreSQL 数据库。Node 继续用于前端构建、Vitest、E2E seed 和 Playwright。

> 草场长期目标架构见 `docs/草场Java微服务技术架构与渐进迁移方案.md` 与 `docs/草场系统技术总体设计（HLD-v0.1）.md`。当前后端运行面由 edge、identity、marketplace、finance、trust、intelligence 六个 Java 服务承载。Bilibili/Douyin 媒体链路（含 FFmpeg 与 Playwright）也由 intelligence 承载。生产默认入口为 Nginx → `edge-bff`；RouteManifest 未命中、method 不匹配或 flag=false 均 fail-closed 404。仓库不再包含 Express/TypeScript 后端；Node 主工具链用于前端与 E2E，Intelligence 中仅保留 Java Playwright 上游要求的 Node driver，不承载 HTTP 或领域逻辑。

## Java 平台（草场 Epic 0/1）

- 工程：`platform-java/`（Gradle Kotlin DSL 多模块，版本目录 `gradle/libs.versions.toml`）
- 模块：`services/edge-bff`、`services/identity-service`、`services/marketplace-service`、`services/finance-service`、`services/trust-service`、`services/intelligence-service`
- 工具链：JDK 25（`brew install openjdk@25`）；通过 `./gradlew` 构建，不依赖系统 Gradle
- `edge-bff` 是固定上游透明代理，零聚合透传 SSE / Multipart / Range，剥离 hop-by-hop header；契约矩阵见 `docs/草场旧API兼容契约矩阵.md`
- 默认 `docker compose up -d` 启动 Edge 与五个 Java 领域服务；JBE-04 后 RouteManifest 未命中、method 不匹配或 flag=false 都在 Edge fail-closed 404，不再回退 Express；`API_UPSTREAM` 必须保持 `edge-bff:8080`。TLS 在 LB/ingress 终止时必须设 `PUBLIC_FORWARDED_PROTO=https` 和实际的 `TRUSTED_PROXY_CIDR`
- 保持既有 public API wire 契约；后端能力进入 Java 服务，媒体处理继续使用 Java 侧 Playwright/ffmpeg worker 边界，不在 WebFlux 事件循环执行阻塞任务

核心功能模块：
- **AI 内容创作中心**：平台优先一级入口（九平台 × 内容形式 × 创作来源），解析为具体工作流并 handoff 到对应创作视图；已接受任务先冻结任务版本、平台规则、所选素材和 AI 配置元数据
- **视频参考提取**：视频制作的可选参考输入（兼容入口保留）：抖音/Bilibili 视频解析、预览、下载、音频提取、AI 分析，结果可带入视频制作脚本
- **图片评价文案**：点评探店定位：上传图片 → 选择平台（淘宝/大众点评）→ 生成探店点评文案 → 一键复制 / 导出飞书，支持本次会话多版本对比
- **爆款文章创作**：主题 → 平台（微信公众号/知乎/小红书/抖音图集短文案）→ 标题 → 大纲 → 正文 → 按段落配图，SSE 流式输出，附只读平台规范提示
- **脱口秀/风格化脚本**：六种抽象风格模板（不模仿特定在世创作者），SSE 流式输出
- **朋友圈创作**：图片+文字（专用轻量流程：主题/四风格/素材图 → 精简文案+九宫格顺序建议+每图配文，一次多模态 SSE）与视频+文字（路由到视频制作，脚本 prompt 注入朋友圈熟人分享适配）
- **内容安全检查**：任务书 #34+#33a / ADR-D16。版本化服务端词库 L1 每次生成必跑；长文本可经控制面 `content_safety` capability 做平台资助的 L2 语境深检，未配置或失败降级为 L1。五条文本创作流返回结构化 safety findings，前端可编辑后手动复查，提醒为 advisory、不自动发布或硬阻断
- **图片生成**：素材生成定位：独立图片生成，支持参考图上传和 @mention
- **视频内容改编**：将视频分析结果转为分镜脚本，支持用户自定义指令和图片上传
- **视频制作**：上传素材图片 / 粘贴参考视频链接（内嵌抖音/B站参考提取）/ 从热点选主题 + 店铺信息 → AI 脚本生成（SSE 流式）→ 异步视频生成（Sandbox 已可用，Seedance/MiniMax 真实渠道待联调）
- **账号系统**：邮箱注册（图形验证码 + 邮箱验证码，注册事务内创建商家/推荐官初始身份档案）、登录、按用户隔离设置
- **积分系统**：功能使用扣积分，管理员可调整
- **创作灵感**（原首页/热点聚合）：多平台热点（抖音/微博/知乎），支持 60s API 和 ALAPI 两种数据源；版本化 taxonomy 在缓存刷新时确定性标注行业/城市/内容类型，前端可组合筛选，源级有效期默认隐藏过期趋势

## Commands

```bash
npm run dev              # 前端 Vite（port 5173）
npm run dev:client       # 仅前端（Vite, port 5173）
npm run build            # 前端类型检查 + Vite 构建
npm run test             # 运行测试
npm run typecheck        # TypeScript 类型检查
npm run e2e              # Playwright E2E
```

DATABASE_URL 由运行时环境、`.env` 或 Secret Manager 提供；文档和提交中不得记录数据库连接值、密码或其他凭据。

## Architecture

### Frontend (`src/`)

- `App.vue` — 顶层 shell：标签导航（AI 内容创作中心为默认一级入口，更多工具下拉收纳兼容入口）、认证、设置、积分、管理
- `components/` — 各功能模块的页面组件
- `composables/` — 请求与状态逻辑（`useAuth`, `useDouyinParse`, `useArticleCreation`, `useCredits` 等）
- `types/` — 前端类型定义

### Backend (`platform-java/`)

- `services/edge-bff` — Java Edge 路由、鉴权与 fail-closed 门禁
- `services/identity-service` — 账号、会话、组织与权限
- `services/marketplace-service` — 任务撮合、履约、运营处置与消费套餐订单（未支付订单 TTL 关单并释放库存）。任务类型四选一：图文/视频/文章/点赞互动（content_form 受控值集 + `TaskRequirements.interaction` 配置块，ADR-D13；互动核验 = 既有 link/platform/evidence 分支 + interaction_screenshot 多模态截图判定，复用 `/api/verification/analyze` mode=interaction）
- `services/finance-service` — 钱包、积分、订单与资金对账；霸王餐押金走反向资金流（`/internal/freebie/**`，推荐官钱包预付托管 → 达标 FREEBIE_REFUND 全额退推荐官 / 未达标 FREEBIE_COMPENSATE 补偿商家，ADR-D12；既有 `reservations/{ref}/reconcile` 对无商家预留的行自动回落 freebie 对账）；审判官投票奖励经 trust→Kafka→finance 消费者入账（`JudgeVoteRewarded` → `awardJudgeReward` type=judge_reward，inbox+operation_id 双幂等，ADR-D15；finance 首个 Kafka 业务消费者）
- `services/trust-service` — 争议、审判与客服终审
- `services/intelligence-service` — AI、媒体、创作上下文与素材
- `services/database-bootstrap` — 共享身份/会话基础表 bootstrap

### Key architectural patterns

- 视频预览/下载走后端代理，不暴露上游地址给前端
- 分析提供者和凭据由服务端持久化设置决定，浏览器不能直接调用第三方服务
- SSE 流式输出用于文章大纲/正文生成和脱口秀生成（`fetch` + `response.body.getReader()`，非 EventSource）
- 文章配图按段落拆分，用户可跳过单个段落
- 积分扣减使用原子操作 `UPDATE ... WHERE balance >= 1 RETURNING`，且余额 UPDATE 与流水 INSERT 必须在同一事务内（GL-P0-CRED-001）。**积分存储与扣减/退款逻辑已迁入 finance-service**（`credits_account`/`credits_transaction` 表 + `CreditsService` 幂等闭环，事务由 `TransactionalOperator` 保证）；legacy `credit.service.ts` 现为薄 HTTP 代理，`withDbTransaction` 已不再用于积分
- `queryDb<T>()` 返回 `QueryResult<T>` — 始终用 `.rows` 访问数据

## API routes

| 模块 | 路由前缀 | 说明 |
|------|----------|------|
| 认证 | `/api/auth/*` | login, register, captcha, send-code, logout, me |
| 首页 | `/api/homepage/hot-items` | 匿名热点数据；支持 `industry/city/contentType/includeExpired` 筛选，响应含 taxonomy、标签与有效期 |
| 抖音 | `/api/douyin/*` | extract, analyze, analysis-media, proxy, download, audio, session(GET/POST), session/start, session/poll, session/logout, hot-items |
| Bilibili | `/api/bilibili/*` | extract, analyze, analysis-media, proxy, download |
| 图片评价 | `/api/image-analysis/*` | analyze (SSE), export-feishu, save-style-memory, style-preferences (GET/PUT), style-preferences/optimize, step/draft, step/optimize, step/style-refine |
| 文章生成 | `/api/article-generation/*` | titles, outline (SSE), content (SSE), image-recommendations, search-images, generate-image |
| 脱口秀 | `/api/comedy-generation/*` | generate-script (SSE) |
| 朋友圈 | `/api/moments-generation/*` | generate (SSE 判别帧 progress/result/error)：一次多模态调用产出精简文案+九宫格顺序建议+每图配文；扣 moments_generation 积分，失败退款；任务模式绑定 moments+image-text 快照（intelligence-service，PRD §4.4）|
| 视频制作 | `/api/video-production/*` | `capabilities`、`generate-video`、`jobs` 查询/取消已由 intelligence 提供异步 Sandbox 闭环；Seedance/MiniMax 真实渠道待联调 |
| 视频改编 | `/api/video-recreation/*` | adapt-content, generate-asset-image, generate-all-asset-images, generate-scene-image, generate-all-scene-images |
| 创作助手 | `/api/creation-assistant/*` | score (SSE), suggest (SSE), guide (SSE), task-coverage (SSE), topic-from-hot (SSE)（intelligence-service，PRD §4.9）|
| 创作草稿 | `/api/creation-drafts/*` | 列表/新建/读取/保存（乐观锁 PUT，冲突 409）/删除（intelligence-service，§4.9.7）|
| 任务创作上下文 | `/api/creation-contexts/*` | 创建/读取不可变快照（intelligence-service，PRD §4.12）；Marketplace 内部端点只向 intelligence 返回 accepted 报名的权威任务快照（含 storeBranding 门店品牌块，任务书 #24） |
| 门店公开资料 | `/api/stores/{storeId}/public-profile` | identity 只读白名单（GET，任务书 #24）：未登录也放行（resolveOptional），只回 PRD §2.1 公开字段（不含 KYB 审核列/org 内部字段），门店/组织非 active → 404；edge flag `EDGE_ROUTE_STORES_PUBLIC_IDENTITY`。内部批量端点 `/internal/identity/stores/public-profiles` 仅 marketplace 服务断言可调，不进 edge |
| 推荐官匹配 | `/api/tasks/{id}/recommendations*` | marketplace 从报名/任务/履约/评分/声誉事实确定性计算六维排序；商家邀请经 outbox 进入通知中心，推荐官仍走原报名规则 |
| 报名批量处理 | `/api/tasks/{id}/applications/batch-accept`、`/api/tasks/{id}/applications/batch-reject` | 任务书 #27：商家批量接受/拒绝报名（1–50 条，逐项独立，允许部分成功）；同任务可设 `autoAcceptMinLevel` 等级门槛，dispatcher 定时扫描自动通过达标报名 |
| 推荐官收入统计 | `/api/finance/wallets/me/statistics?from=&to=` | 任务书 #29+#30：`wallet_ledger` 权威表按月（北京时间 `date_trunc`）+ 按 engagement 聚合，含毛/抽成/净；跨度≤12 月，self-scoped；前端按任务标题经 my-applications join |
| 商家月度账单 | `/api/finance/organizations/{orgId}/monthly-bill?month=` | 任务书 #29+#30：journal/posting 双录按 `journal_type` 聚合 + FEE 腿单列，flow=ESCROW 腿净额；org-scoped 自查跨 org 404，月切北京时间 |
| 游客试用 | `/api/guest-trial/{capability}`、`/api/guest-trial/quota` | 任务书 #36 / ADR-D14：未登录匿名放行（gtid httpOnly cookie + IP 双层限流 + 每能力 3 次/天，成功才计次）；不进 finance credits/ai_run，审计只存 IP 截断哈希；edge flag `EDGE_ROUTE_GUEST_TRIAL_INTELLIGENCE` 可整体关闭 |
| 内容安全复查 | `/api/content-safety/check` | 登录用户对编辑后的文本重新检查；返回版本化 findings。Edge flag `EDGE_ROUTE_CONTENT_SAFETY_INTELLIGENCE` 关闭时 fail-closed 404；词库不下发前端 |
| 推荐官我的报名 | `/api/tasks/my-applications?status=&cursor=&limit=` | 任务书 #29+#30：跨任务 keyset 分页列当前推荐官报名，join task 标题/状态/赏金 + settledAt；复用 `/api/tasks/**` 前缀不新增公网路由 |
| 积分 | `/api/credits/*` | balance, history, packages（active 积分包）, purchase-orders（购买/记录，Sandbox 支付即时生效）|
| 管理 | `/api/admin/*` | users, adjust-credits（需 admin 角色）; credits-packages + credits-purchase-orders（含 reconciliation 三方对账，需 FINANCE 角色）|
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
- 创作助手 SSE 帧是**判别联合**（`type` 取 score/overall/ask/brief/gap/covered/topic），`useCreationAssistant` 的帧消费器交给回调的是整帧对象而非 `content` 字符串。帧里的 boolean/number **必须原生下发**——`{"covered":"false"}` 在 JS 里是 truthy，判断会反；前端另按 `=== true` 兜一道。流已 200 开头后无法改状态码，失败一律走 `{error}` 帧
- 草稿自动保存是**整行覆盖 + 乐观锁**：未改字段要按当前值回填，本地 `version` 只能用服务端回传值覆盖（自增猜测会让后续每次保存都 409）。409 进冲突态后**停止自动重试**（重试一直撞同一版本），由用户选重载或覆盖
- `npx tsx script.ts` 用于 DB 脚本（不要用 `-e` 内联模式）
- 注册流程：图形验证码 → 邮箱验证码 → 注册
- `provider-url.ts` 维护 `TRUSTED_PUBLIC_API_SUFFIXES` 白名单，匹配时跳过 SSRF 私有 IP 检查
- 管理员路由需要 `requireAuthenticatedUser` + `requireAdmin` 双重中间件
- 视频制作图片用 base64 在 JSON body 中传输（≤9 张）；Java WebFlux 请求体上限必须覆盖该公开契约
- 视频生成以 intelligence 的 `VideoGenerationProperties` 为能力真相源：`mode=sandbox` 默认可用，`seedance`/`minimax` 需配置真实渠道。任务创建冻结 provider/model/价目版本，前端轮询 `/api/video-production/jobs/{id}`；真实回调执行 HMAC 验签、时间窗与 inbox 重放保护，结果归档为 `VIDEO_ASSET` 后只返回内部媒体引用，实际 provider 时长参与结算，归档失败和超时进入可重试/补偿状态。route flag 关闭时 fail-closed 404，不存在 Node 回切路径
- 积分域已 Java 原生化（GL-P3-AI-001/JBE-07）：finance-service 承载 `/internal/credits/**` 命令 + `/api/credits/{balance,history}` 读端（edge 路由 `EDGE_ROUTE_CREDITS_FINANCE`）。Identity 与 Intelligence 直连 finance 时只使用按 issuer/audience 分离的服务断言；通用 `INTERNAL_API_KEY`/`X-Internal-Key` 已删除。内部路径仍拒绝任一 `X-Forwarded-*`/`Forwarded` 请求并返回 404，且**不在 `/api` 树下**
- `CreditsClient` 返回 `CreditCharge` 句柄，上游失败时调 `charge.refund(note)` 写 `refund` 流水（GL-P0-BILL-002）。退款键是 `refund:<consumeId>`，由 intelligence `FinanceCreditsClient` 在调用前派生；finance 按 `operation_id` 原样存储，partial unique index 保证一次扣减至多一次退款。退款失败只记日志不掩盖原始上游错误；用户主动 abort 不退款（内容已流出）
- 任务创作上下文由 intelligence 通过服务断言读取 Marketplace 权威数据，禁止跨库直读或信任前端 task JSON。幂等键为 account/application/taskVersion/platform/contentForm，首次成功创建后不再重读任务、素材或当前 AI 配置；快照无更新端点且数据库拒绝 UPDATE。BYOK 只冻结 configId/provider/model/keyVersion/maskedHint 等元数据，禁止保存明文密钥、密文或 provider baseUrl。控制面 `/api/ai/runs` 可绑定 `contextSnapshotId`；其它 Java 生成入口是否消费快照按各自契约显式实现，不存在 Node fallback
- JBE-02 后 `app_users`、`session`、`user_settings`、`email_verification_codes` 由 Java `database-bootstrap` 管理；前端/E2E 的 Node 工具链继续保留。会话 cookie 属性由 `SESSION_COOKIE_SECURE`（`auto|always|never`）/`SESSION_COOKIE_SAME_SITE` 决定，Java 侧唯一真相源是 `SessionCookiePolicy`
- 公网安全响应头由 `nginx.conf` 统一输出；Nginx 先用 `proxy_hide_header` 去掉 Java 上游同名头再发唯一一份。**不要在 `/api/` location 内写 `add_header`**，否则会丢弃 server 级整组 header。HSTS 只读部署参数 `PUBLIC_FORWARDED_PROTO`，不信任客户端同名头；`TRUSTED_PROXY_CIDR` 仅填实际 LB/ingress 网段，real-ip 还原后再重建 XFF 链。有意不加 CSP（当前前端含内联样式，需单独做 report-only → 强制）
- 事务邮件走 identity 的 `mail_outbox`（第五份 outbox，GL-P1-NOTIFY-001）：`NotificationEventProcessor.emit` 在站内通知插入的**同一事务**内 `mail_outbox.append`，保证「通知落库 ⇔ 邮件入队」原子。`MailOutboxPublisher` 轮询 SMTP 发送，失败 5 次后 `status=dead`（区别于领域 outbox 的无限重试）。`MailTemplates` **委托 `NotificationTemplates` 拿文案** + PERMISSION 过滤（高价值子集：邀请/履约/争议/资金）。**验证码保持同步直发**（`SendCodeController`→`SmtpMailSender`，用户在等，不经 outbox）。邀请事件 `MembershipInvited`/`Revoked` 邮件收件人 = `payload.email`（未注册邮箱也能发），其余 = accountId→`app_users.email`
- KYB 敏感字段（法人身份证号、收款账号）唯一写入通道是 identity 的 `KybFieldCrypto`，接 `platform-crypto` 信封加密（KEK=`CRYPTO_KEK_BASE64`，与 intelligence BYOK 同一变量）。**未配 KEK 时相关请求 503，不得退化为存明文**；读取侧只回末 4 位掩码（`legalPersonIdNumberMasked`/`accountNumberMasked`），完整明文不出响应体也不进 outbox payload（D-10）。审核入队唯一通道是 `KybSubmissionService`——`submit` 必须在同一事务内完成「状态变更 + `kyb_verification_request` 入队 + outbox」，漏掉入队会让 admin 队列恒空、审核不可达（GL-P3-MERCHANT-001）
- identity-service **没有全局 `SecurityWebFilterChain`**，授权是逐 controller 约定：平台 admin 端点用 `CurrentAccountResolver.requireAdmin`，组织内端点用 `OrgAuthorization.requireRole`。新增端点漏掉这一行就是完全无鉴权（`/api/admin/kyb-requests` 曾如此）。仓储层的跨租户守卫是第二道闸：改删组织资源的 SQL 必须带 `organization_id` 条件，只按 id 定位等于放开跨租户写
- 移动端 token 认证由**请求头 `X-Device-Info` 单点切换**（GL-P3-IDENTITY-001）：带该头 → `LoginController` 走 token 分支，签 access/refresh token 且**不建 session 行、不发 Set-Cookie**；不带 → Web cookie 路径逐字节不变。`IDENTITY_ACCESS_TOKEN_SECRET` 未配时移动端 503（fail-closed），Web 不受影响。refresh token 只以 SHA-256 落 `refresh_token` 表，**v1 刻意不轮换**（刷新只 touch `last_used_at` + 重签 access token）；access token 的 `session_token` claim = refresh_token 行 id，`identity_session` 与设备撤销都按它定位。edge-bff 的 `AccessTokenFilter` 在公网边界验签并复查 refresh-token 撤销状态，随后由 `InternalAssertionFilter` 换发目标服务断言；原始 access token 不向 Java 上游扩散，非法/撤销/非 Bearer 凭据直接 401，refresh/revoke 例外透传。Argon2 hash/rehash 必须 `subscribeOn(Schedulers.boundedElastic())` —— 64MB/3 轮的重操作留在 Netty 事件循环上会拖垮整个服务
- AI 控制面（GL-P3-AI-001，intelligence `ai/controlplane/` + `ai/run/`）：平台模型配置（`platform_model_config`，主备 + 版本化，V1 雏形已在 V7 重建）admin CRUD 在 `/api/admin/ai/models`，**全部经 `IntelligenceCallerResolver.requireAdmin`**（断言 `role=admin`；intelligence 之前**没有任何 admin 门闩**，`Caller` 连 `role` 都不带——这是本轮新加的）。`ByokRoutingService` 平台分支查控制面；**BYOK→平台回退须 `allowFallback` 显式授权**（HLD §12.3 硬规则），否则返回 `DENIED(fallback_not_authorized)` 不静默扣平台额度。`AiExecutionService` 是执行闭环唯一编排：平台 run 先 `credits.consume` 且用 `charge.operationId()` 作 run `operation_id` 保退款幂等、BYOK run **不扣分**（D-11）并解密 key（明文只活在 `ExecutionContext`，不入日志/响应/outbox，无 KEK→503）；失败提交后 best-effort `credits.refund`（不在 DB 事务内做 HTTP），`AiRunCompleted/Failed` 经 `intelligenceTransactionalOperator` 同事务 append outbox。`TaskContext`（`ai_run` 的 `price_table_version`/`platform_model_version`/`fallback_authorized`，V8）在 Run 起始冻结。`/api/ai`（keys + runs）+ `/api/admin/ai` 经 edge 路由到 intelligence（`EDGE_ROUTE_AI_INTELLIGENCE`/`EDGE_ROUTE_ADMIN_AI_INTELLIGENCE`，精确前缀不抢 legacy `/api/admin/users`）；此前 `/api/ai/keys` 落 legacy 隐性 404、本轮才首次可达。真实 provider 调用经 `TextCompletionClient`；BYOK 地址保存/执行时校验全部公网 DNS，执行连接使用固定地址 resolver 保留原 hostname/SNI 并关闭 DNS rebinding TOCTOU 窗口，平台地址由 `PlatformProviderPolicy` 限制受信 origin
- 内容安全（ADR-D16）保持两层语义：L1 `ContentSafetyChecker` 是不可降级底线；L2 必须以 `capability=content_safety` 进入 `AiExecutionService`，不得旁路自建模型调用。该 capability 不开放 BYOK，使用 `feature=null` 的既有免费执行分支实现平台资助零积分（原因见 ADR-D16 D5），但仍落 ai_run、预算和并发机器。流尾 safety 帧失败只能降级 `deepCheck:false`，不能覆盖已成功的生成结果；词库版本随创作上下文快照落档，完整词库只留服务端
- 热点 taxonomy（任务书 #35）是服务端运营资产：`contracts/hot-topic-taxonomy.json` 的行业键必须与 identity `Industry.dbValue` 全集一致；分类只在 60s/ALAPI 缓存刷新时执行，同一缓存窗口标签稳定。缓存 TTL 决定何时拉上游，`homepage.hot-items.validity-hours.*` 决定趋势是否默认展示，两者不得混用；请求期只做维度内 OR、跨维度 AND 过滤
- 环境变量定义在 `.env.example`、`.env.docker.example` 及各 Java 服务 `application.yml`，是运行时配置的来源
- API 表描述逻辑契约；实际 upstream 由 edge-bff RouteManifest 与对应 flag 决定，未启用的路由返回 404

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
- 热点：`HomepageHotServiceTest`, `HomepageControllerTest`, `HotTopicClassifierTest`, `useHomepageHotItems.test.ts`, `HotTopicPicker.test.ts`
- 设置：`settings.controller.test.ts`, `analysis-settings.service.test.ts`

## Working conventions

- 优先小范围、聚焦的修改
- 复用已有的 resolver/session/proxy/audio/analysis 服务，不要重复逻辑
- 不要削弱 trusted-host 检查、代理 URL 验证、挑战检测或 session fallback 语义
- 公开分析端点不能接受浏览器传入的提供者地址或凭据
- 保持 README 和 CLAUDE.md 与实际代码一致
