# 全功能营销工具库

本地优先的短视频提取、内容创作与营销工具平台。

## 功能概览

| 功能 | 说明 |
|------|------|
| AI 内容创作中心 | 平台优先一级入口：选平台（九平台）→ 定内容形式 → 选创作来源（独立/任务/门店/热点/参考素材）；任务模式在进入创作前冻结任务版本、平台规则、所选素材和 AI 配置元数据 |
| 视频参考提取 | 视频制作的可选参考输入：抖音/Bilibili 视频解析、预览、下载、音频提取、AI 分析，结果可带入视频制作脚本 |
| 图片评价文案 | 点评探店定位：上传图片 → 选择平台（淘宝/大众点评）→ 生成探店点评文案 → 一键复制 / 导出飞书文档，支持多版本对比 |
| 爆款文章创作 | 主题 → 平台（微信公众号/知乎/小红书/抖音图集短文案）→ 标题 → 大纲 → 正文 → 按段落配图，SSE 流式输出 |
| 脱口秀/风格化脚本 | 六种抽象风格模板（不模仿特定在世创作者），SSE 流式输出 |
| 图片生成 | 素材生成定位：独立图片生成，支持参考图上传和 @mention |
| 视频内容改编 | 将视频分析结果转为分镜脚本，支持自定义指令和图片上传 |
| 视频制作 | 上传素材 / 粘贴参考视频链接 / 从热点选主题 → AI 脚本生成 → 异步视频生成（Sandbox 已可用，Seedance/MiniMax 真实渠道待联调） |
| 账号系统 | 邮箱注册（图形验证码 + 邮箱验证码，可选初始身份）、登录、按用户隔离设置 |
| 积分系统 | 功能使用扣积分，管理员可调整 |
| 创作灵感 | 多平台热点（抖音/微博/知乎），支持 60s API 和 ALAPI 两种数据源，作为创作选题与灵感来源 |

## 技术栈

- 前端：Vue 3.5 + Vite + TypeScript + vue-router + Pinia
- 后端：Java 25 + Spring Boot 4 + Spring Cloud Gateway
- 数据库：PostgreSQL（Java Flyway + database-bootstrap）
- 媒体处理：Java Playwright + ffmpeg
- 前端测试：Vitest + Playwright | 后端测试：JUnit 5

Express/TypeScript 后端已移除；所有后端 HTTP、数据库迁移、媒体和 AI 业务均运行在 Java 25 服务中。Node 仍是前端和测试工具链的必要依赖：Vite/Vitest/前端 Playwright/E2E seed、coverage 和 secret scan 均继续使用 Node。Intelligence 使用的 Java Playwright 仍按上游机制在服务容器内启动 Node driver，但不存在 JavaScript 后端应用、Node HTTP 服务或 Node 领域 Worker。

## 快速开始

### 1. 安装依赖

```bash
npm install
npx playwright install
```

### 2. 配置环境变量

```bash
cp .env.example .env
cp .env.docker.example .env.docker
```

必填项：

```dotenv
CORS_ORIGIN=http://localhost:5173
DOUYIN_PROXY_TOKEN_SECRET=replace-with-at-least-32-characters-secret
BILIBILI_PROXY_TOKEN_SECRET=replace-with-at-least-32-characters-secret
FFMPEG_PATH=ffmpeg
LOG_LEVEL=info
```

> 平台模型端点、密钥与模型名不在 env 里配（任务书 #58 起）：启动后到治理台
> 「平台管理 → AI 模型」新增凭据（协议方言：`openai-completions` / `openai-responses` /
> `anthropic-messages` / `google-generative-ai`）并绑定模型。生产校验脚本会**反向封禁**
> `QWEN_BASE_URL` 等旧变量，写进 overlay 会直接 fail。

如需启用账号系统，还需配置：

```dotenv
DATABASE_URL=postgresql://user:password@localhost:5432/dbname
SESSION_SECRET=at-least-32-characters-session-secret
SMTP_HOST=smtp.example.com
SMTP_PORT=465
SMTP_USER=your@email.com
SMTP_PASS=your-smtp-password
SMTP_FROM="Your App <noreply@example.com>"
```

### 3. 启动 Java 后端

```bash
cd platform-java
source ../scripts/lib/java-runtime.sh
ensure_java_runtime 25
cd ..
docker compose --env-file .env.docker up -d --build
```

`database-bootstrap` 负责空库基础表，五个 Java 领域服务各自执行 Flyway migration。平台 AI 统一由 Intelligence 按治理台控制面（`platform_provider_credential` + `platform_model_config`）解析 provider/model；控制面无可用行时对应能力 fail-closed，不会用占位模型顶上。

### 4. 启动前端开发环境

```bash
npm run dev:client
```

- 前端：`http://localhost:5173`
- API：Vite 代理到本机 Edge `http://localhost:8081`

### 5. 前端生产构建

```bash
npm run build:client
```

生产运行使用 Compose 的 Nginx frontend 与 Java 25 后端。Express 后端已从仓库和部署图移除；Intelligence 容器中的 Node 仅是 Java Playwright 的浏览器 driver，不承载后端 HTTP 或领域逻辑。

## 使用流程

1. **主路径：AI 内容创作中心**（默认首页）：选平台 → 选内容形式 → 选创作来源（独立创作/从热点/参考素材等）→ 开始创作，自动带入对应创作视图；已接受任务会先创建不可变上下文快照，首次成功创建为准
2. **文章创作**：输入主题 → 选平台（含抖音图集短文案模式）→ 选标题 → 编辑大纲 → 生成正文 → 按段落配图
3. **视频制作**：上传素材 / 粘贴抖音或 B 站参考视频链接 / 从热点选主题 → AI 生成脚本 → 创建异步视频任务并轮询结果（Sandbox 可用）
4. **脱口秀/风格化脚本**：输入主题 → 选六种风格模板之一 → AI 实时生成脚本
5. **点评探店文案**：上传图片 → 选择平台 → 生成文案 → 复制/导出飞书
6. **图片生成**：输入描述（可上传参考图）→ 生成素材图片
7. 兼容入口收纳在「更多工具」下拉中（创作灵感/视频参考提取等）；需要调整分析配置时，点击「设置」（需先登录）

## 积分系统

- 每次使用视频分析、图片评价、文章生成、脱口秀生成扣除 1 积分
- 新注册用户自动获得 3 积分（可通过 `FREE_CREDITS_ON_REGISTER` 环境变量调整）
- 管理员可通过管理页面调整任意用户的积分
- 所有积分变动均有记录：余额与流水在同一事务内写入，不会出现半记账
- 上游（AI 服务）调用失败会自动退回已扣积分；重试携带同一 operation id，不会重复扣分

## 管理员与后台角色

- 后台权限以 Identity 服务的 `backend_role` 表为权威；`platform_admin` 是后台角色超集
- 仓库当前没有 `npm run admin:create` 或生产管理员 bootstrap CLI；Flyway `V26__backend_role.sql` 只把已有 `app_users.role=admin` 账号回填为 `platform_admin`，不会创建新账号
- 已有平台管理员可通过 `PUT /api/admin/users/{id}/roles` 授予或撤销后台角色；首个平台管理员必须由部署运维在受控数据库环境完成引导并留存审计
- `npm run e2e:seed` / `npm run e2e:seed:auth` 只用于隔离测试数据，禁止用于生产管理员初始化
- 登录后可看到「管理」入口，按角色处理用户积分、审核、财务、风险、经营分析、AI 模型和统一审计等模块

## 视频分析配置

平台默认路径由治理台「平台管理 → AI 模型」控制面解析（`platform_provider_credential` +
`platform_model_config`），与具体厂商无关；**env 里没有 provider 选择位**。

配置优先级：
1. 用户级 BYOK（`/api/ai/keys`；存量 `user_settings` 的 `features.video` 仍被识别，`provider=coze`
   走独立协议不可迁移）
2. 治理台平台凭据 + 平台模型配置（无可用行则 fail-closed，不静默降级）

> 旧的 `COZE_ANALYSIS_*` / `QWEN_ANALYSIS_*` / `VIDEO_ANALYSIS_API_*` 环境变量已删除（任务书 #59）：
> Java 侧零读取方，设了不生效。
>
> 浏览器不会直接请求第三方分析服务，所有请求由后端代理。

## 首页热点

支持两种数据源，可在「设置」弹窗中切换：

1. **60s API**（默认）：聚合抖音/微博/知乎热点，通过 Tab 栏切换
2. **ALAPI**：需要配置 ALAPI Token，展示抖音/微博/微信/小红书热点

热点数据缓存在数据库中（60s 缓存 2 小时，ALAPI 缓存 5 分钟）。

## Docker Compose 部署

适用于服务器部署。公网 API 统一经 Nginx → Edge BFF；Edge 只代理 RouteManifest 中启用的 Java 路由，未登记路径、method 不匹配或关闭的路由均返回 404。

> 当前 Compose 的 Intelligence 服务包含 Java Playwright 浏览器登录增强 / browser fallback；其 Node driver 是浏览器自动化实现依赖，不是 Node 后端服务。

### 1. 准备环境变量

```bash
cp .env.docker.example .env.docker
```

编辑 `.env.docker`，配置 `FRONTEND_ORIGIN`、`PUBLIC_BACKEND_ORIGIN`、`CORS_ORIGIN`、`DATABASE_URL` 等；首次启动还必须填写 `MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`。Compose 会在任一必填项缺失时 fail-fast。平台模型凭据不走 env，启动后在治理台「平台管理 → AI 模型」配置。root 凭据仅由一次性 `minio-init` 建桶和创建受限 service account；`intelligence-service` 只接收 `MINIO_ACCESS_KEY/MINIO_SECRET_KEY`，且关闭运行时建桶权限。

### 2. 构建并启动

```bash
docker compose --env-file .env.docker build
docker compose --env-file .env.docker up -d
```

默认公网映射只有前端 `8080 -> 80` 和对象存储上传代理 `9002`。Edge `8081` 只绑定 `127.0.0.1`，用于本机诊断和 Vite 开发；默认栈不包含 Express backend。

### 3. 验证

```bash
docker compose --env-file .env.docker config --quiet
curl http://<host>:<FRONTEND_PORT>/health
curl -i http://<host>:<FRONTEND_PORT>/api/auth/captcha
```

### 4. Edge BFF 切流与回退

`edge-bff` 是唯一 API 入口，保留 Cookie、SSE、Multipart、Range 和既有公开 wire 契约。对象存储 presigned PUT 仍按设计直传 `9002`，不经过 BFF。

- 单路由停用：设置对应 `EDGE_ROUTE_*` 为 `false` 并 recreate `edge-bff`；该路由返回 404，不回退 Express。
- `API_UPSTREAM` 必须保持 `edge-bff:8080`；JBE-04 后不再支持整入口回退到 backend。
- TLS 在上游 LB/ingress 终止时设置 `PUBLIC_FORWARDED_PROTO=https` 和实际的 `TRUSTED_PROXY_CIDR`；Nginx 会覆盖协议头并从可信代理链重建客户端 IP。
- 不配置自动 upstream failover，避免非幂等 POST 被两个上游重复执行。

```bash
# 本地 Java 构建（需要 JDK 25；可用 brew install openjdk@25）
cd platform-java
./gradlew clean build

# 默认栈已包含 Edge 与五个 Java 领域服务
docker compose --env-file .env.docker up -d --build
curl -i http://localhost:8081/health
curl -i http://localhost:8081/api/auth/captcha

# RouteManifest 变更后重建 Edge；关闭的路由会 fail-closed 404
docker compose --env-file .env.docker up -d --no-deps --force-recreate edge-bff
```

### 5. CI 与公共入口 E2E

本地可以用与 GitHub Actions 相同的隔离 Compose 流程验证公共 Nginx → Edge BFF → Java 服务入口：

```bash
# 需要 Docker、Node 20+、Chromium，以及用于 Gradle toolchain 的 JDK 25
npx playwright install chromium
npm run e2e:ci
```

`scripts/ci-e2e.sh` 会创建独立的 Compose project，生成临时密钥和 E2E 账号，先运行 Java `database-bootstrap`，再构建并启动完整 Java 后端，最后执行 Playwright；流程不启动 Express backend。前端开发、构建、Vitest、E2E seed 和 Playwright 继续使用 Node。macOS 默认 Java 8/21 时会自动查找 Homebrew JDK 25；也可以显式设置 `JAVA_HOME`。E2E 使用不可调用的占位 Qwen 地址，仅用于通过 Intelligence 的启动配置校验，不会发起模型请求。

失败时脚本会保存 `test-artifacts/compose.log`、`test-artifacts/compose-ps.txt` 和 Playwright 报告；无论成功或失败都会清理隔离 Compose project 及卷。GitHub Actions 的 `node`、`java`、`e2e` 三个 job 会分别执行已跟踪文件密钥扫描、类型检查、全源覆盖率测试与构建；全量 Gradle 测试与 jar artifact；公共入口浏览器测试。

Node 前端覆盖率门槛由 `docs/status.yaml` 和 `vitest.config.ts` 共同锁定：statements/lines 68%、branches 74%、functions 53%；CI 对 Git diff 中变更的可执行行执行 80% 门禁并上传 HTML 报告。`npm run docs:status` 会阻止两处门槛漂移。总体覆盖率仍未达到 80%，不能把 CI 绿色解释为全仓覆盖率目标已完成。

兼容约束和契约矩阵见 [`docs/草场旧API兼容契约矩阵.md`](docs/草场旧API兼容契约矩阵.md)。可观测性组件按需使用 `--profile observability` 启动。

## 环境变量

运行部署变量以 `.env.docker.example` 和各 Java 服务的 `application.yml` 为准。常用变量：

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `CORS_ORIGIN` | `http://localhost:5173,http://localhost:5174` | 允许的前端来源（逗号分隔） |
| `DATABASE_URL` | 空 | PostgreSQL 连接字符串 |
| `SESSION_SECRET` | 空 | Session 签名密钥（启用账号时必填，≥32 字符）|
| `DOUYIN_PROXY_TOKEN_SECRET` | 必填 | 抖音代理 token 签名密钥（≥32 字符）|
| `BILIBILI_PROXY_TOKEN_SECRET` | 必填 | B站代理 token 签名密钥（≥32 字符）|
| `FFMPEG_PATH` | `ffmpeg` | ffmpeg 可执行路径 |
| `PUBLIC_BACKEND_ORIGIN` | 空 | 后端公网地址（第三方回源访问用） |
| `IMAGE_GENERATION_PRICING_VERSION` | `image-config-v1` | 图片生成价目版本，任务快照冻结 |
| `IMAGE_GENERATION_UNIT_PRICE_CENTS` | `80` | 每张图片的预算/成本分值 |
| `AI_VERIFICATION_ENABLED` | `true` | AI 视觉核验开关；`false` → `/api/verification/analyze` 返回 400，marketplace 把 `ai_visual` 降为 inconclusive |
| `AI_STORE_MEDIA_MODERATION_ENABLED` | `true` | 媒体自动审核开关；`false` → 不落 `store_media_moderation` 行（未审降级），运营台「门店媒体」复核队列恒空 |
| `ALAPI_BASE_URL` | `https://v3.alapi.cn` | ALAPI 地址（热点数据源） |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASS` / `SMTP_FROM` | 空 | SMTP 配置（启用注册时必填）|
| `FREE_CREDITS_ON_REGISTER` | `3` | 新用户注册赠送积分 |
| `LOG_LEVEL` | `info` | 日志级别（fatal/error/warn/info/debug/trace/silent） |

## 项目结构

```text
.
├── src/                          # Vue 前端
│   ├── router/                   # vue-router 路由配置
│   ├── stores/                   # Pinia 全局状态
│   ├── layouts/                  # 布局组件（DefaultLayout + NavigationBar）
│   ├── views/                    # 页面级组件（每个对应一个路由）
│   │   ├── ai-center/            # AI 内容创作中心
│   │   ├── image/                # 图片评价
│   │   ├── article/              # 文章创作
│   │   ├── video/                # 视频分析
│   │   ├── image-gen/            # 图片生成
│   │   ├── video-production/     # 视频制作
│   │   ├── comedy/               # 脱口秀脚本
│   │   ├── commerce/             # 消费者商城
│   │   ├── grassland/            # 草场工作台
│   │   ├── ops/                  # 运营处置台
│   │   ├── admin/                # 管理后台
│   │   └── home/                 # 首页
│   ├── components/
│   │   ├── ui/                   # 基础 UI 组件（AppButton, AppModal, AppTabs 等）
│   │   ├── shared/               # 业务共享组件（LoginModal, LoadingState 等）
│   │   └── *.vue                 # 跨页面共享组件
│   ├── composables/              # 请求与状态逻辑（按功能域拆分）
│   ├── config/                   # 平台配置
│   ├── types/                    # 类型定义
│   │   └── grassland/            # 草场类型（按功能域拆分：task, recommender, dispute 等）
│   └── lib/                      # 工具库
├── platform-java/                # Java 25 后端多模块工程
├── package.json
├── vite.config.ts
└── vitest.config.ts
```

## 路由表

| 路径 | 名称 | 页面 | 说明 |
|------|------|------|------|
| `/` | — | → `/ai-center` | 默认重定向到 AI 创作中心 |
| `/ai-center` | ai-center | AiCreationCenter | AI 内容创作中心（九平台一级入口） |
| `/home` | home | HomeView | 首页 |
| `/video` | video | VideoAnalysisView | 视频参考提取 |
| `/image` | image | ImageAnalysisView | 图片评价文案 |
| `/article` | article | ArticleCreationView | 爆款文章创作 |
| `/image-gen` | image-gen | ImageGenerationView | 图片生成 |
| `/comedy` | comedy | ComedyWritingView | 脱口秀/风格化脚本 |
| `/video-production` | video-production | VideoProductionView | 视频内容改编 |
| `/commerce` | commerce | ConsumerCommerceView | 消费者商城 |
| `/grassland` | grassland | GrasslandWorkbench | 草场工作台（需登录） |
| `/ops` | ops | OpsConsole | 运营处置台（客服角色） |
| `/admin` | admin | AdminView | 管理后台（管理员角色） |

## 常见问题

### 为什么分析需要 `PUBLIC_BACKEND_ORIGIN`？

第三方分析服务需要回源访问后端生成的代理地址。本地开发时分析服务可能无法访问 `localhost`；服务器部署时应配置为第三方可访问的公网地址。

### 为什么设置需要登录？

分析设置和首页热点配置按账号隔离保存，不同用户有各自独立的模型和密钥配置。

### 为什么超过 10 分钟不给分析？

视频过长会导致分析耗时过长、分段过多、结果可读性下降。最佳体验：30 秒到 2 分钟。

## 已实现的安全约束

- 视频代理走后端签名 URL，不暴露上游地址
- 只允许受信任的视频 host 进入代理链路
- 注册需图形验证码 + 邮箱验证码双重验证
- 新密码使用 `Argon2id` 哈希存储；历史 `bcrypt` 哈希在登录成功后自动升级
- 积分扣减使用原子操作
- 后台接口由 Java 服务按 `backend_role` 做服务端 RBAC 校验，`platform_admin` 具有超集权限
- 所有 API 带基础限流
