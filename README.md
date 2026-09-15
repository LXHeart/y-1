# 草场（Grassland）

草场是面向商家、推荐官与消费者的种草推广平台，围绕推广任务、内容创作和到店消费组织业务：

- **任务合作**：商家发布推广任务，推荐官报名、接受合作、提交履约凭证，双方完成核验、确认、争议处理与结算。
- **AI 创作**：从主题、热点、参考素材或已接受任务开始，制作图文、图片、视频和脚本，保存项目并交付内容。
- **到店消费**：消费者通过推广链接或商城购买套餐，完成支付、到店核销、退款与评价。

当前资金链路采用 **Sandbox 语义**。真实支付、存管、合规主体和外部对账仍受 [D01](docs/adr/D01-psp-escrow-compliance.md) 与生产门禁约束；本地流程可运行不代表已具备真实资金上线条件。

项目导航：[文档总索引](docs/文档总索引.md) · [项目速览](docs/架构/项目速览.md) · [技术架构](docs/架构/项目架构详解.md) · [目录地图](docs/架构/目录结构.md) · [开发进度](docs/草场开发进度与续接指南.md) · [任务书索引](docs/任务书/README.md)

## 三个应用入口

三个应用共用前端工程和后端服务，由 Vite 多入口构建、Nginx 按独立 origin 提供页面。下表是默认 Compose 端口，路径均相对于对应应用。

| 应用 | 入口与代码 | 本地访问地址 | 主要用途 |
|---|---|---|---|
| 用户端 | `index.html` · `src/router/`、`src/layouts/` | [127.0.0.1:8080](http://127.0.0.1:8080) | 草场主页、商家与推荐官工作台、消费者商城、任务创作 |
| AI 创作端 | `ai.html` · `src/ai/` | [127.0.0.1:8084](http://127.0.0.1:8084) | 自由创作、最近项目、素材库、模型与预算管理 |
| 治理台 | `ops.html` · `src/ops/` | [127.0.0.1:8083](http://127.0.0.1:8083) | 审核、客服、财务、风控、内容与 AI 管理 |

用户端与 AI 创作端之间支持基于一次性 token 的免登跳转。治理台权限由服务端 `backend_role` 校验，独立入口本身不替代授权。

## 功能概览

### 推广、履约与消费

| 模块 | 当前能力 |
|---|---|
| 账号与身份 | 邮箱验证码注册、登录与会话、商家/推荐官身份切换、成员账号首次改密、通知中心 |
| 商家与门店 | 主体、品牌、门店资料与媒体库、KYB 认证、成员子账号与门店分配、权限申请 |
| 推广任务与合作 | 任务发布与审核、报名处理、接受合作、任务上下文冻结、履约凭证、核验与确认 |
| 消费者商城 | 套餐、推广链接与归因、订单支付、超时关单、核销、退款、评价 |
| 资金与经营 | 托管、结算、钱包、账单、积分、对账和经营分析；资金操作使用 Sandbox |
| 争议与治理 | 举报投诉、争议举证、审判官裁决、上诉与客服终审、判例、风险调查和统一审计 |

### AI 内容创作

| 模块 | 当前能力 |
|---|---|
| 创作入口与项目 | 按平台、内容形式和来源进入工作流；最近项目、自动保存与继续编辑、草稿版本比较、运行记录 |
| 图文与文案 | 公众号文章、知乎文章/回答、小红书与抖音图文、朋友圈文案、图片点评、风格化脚本；支持流式生成、内容检查与修复 |
| 图片与素材 | 图片生成与编辑、参考图、系列图卡、信息图、封面与文章配图、公共/个人/门店素材管理 |
| 视频制作 | 参考视频解析、分析与改编，脚本和分镜制作、镜头候选、配音/BGM、异步生成与合成 |
| 分镜画布 | 镜头、素材与备注节点，画布保存与恢复、AI 方案预览和应用、候选选择、任务恢复与交付 |
| AI 与治理 | 平台模型与个人/组织 BYOK、个人/组织预算、免费额度与积分、创作审计、语音转写 |
| 创作灵感 | 多平台热点、筛选与主题带入；支持 60s API 和 ALAPI 数据源 |

独立 AI 应用支持从主题、热点或参考素材自由创作；用户端 `/creation` 承接已接受任务的创作。任务版本、平台规则、所选素材和 AI 配置会冻结为上下文快照，后续生成绑定该快照。

**新增图文工作台（#101）已实现并通过本地复核，默认关闭。** 它在文章工作流中提供原稿导入、文本修改建议、视觉计划、图卡/封面制作、文章排版与文件交付，以及公众号连接和草稿同步。新工作流覆盖小红书、抖音、公众号、知乎的图文项目；真实模型、真实公众号与完整部署栈的验收状态见 [#101 规格与验收记录](docs/任务书/草场任务书-101-AI创作工作流与图文交付升级.md)。开关配置见下文。

分镜画布的修复与验收记录见 [#102](docs/任务书/草场任务书-102-创作画布复盘修复与真实闭环验收.md)。视频、图像、语音等真实渠道是否可用，取决于部署配置、模型能力、凭据与外部验收。

## 技术栈与架构

| 层次 | 技术 |
|---|---|
| 前端 | Vue 3.5、TypeScript、Vite 7、Vue Router 4、Pinia 4 |
| 样式与字体 | 全局设计 token、明暗双主题、Space Grotesk + Inter 自托管字体 |
| 后端 | Java 25、Spring Boot 4.1、Spring Cloud Gateway、WebFlux/R2DBC |
| 数据与异步任务 | PostgreSQL、Flyway、Redis、Kafka、Temporal |
| 媒体与对象存储 | MinIO/S3、Java Playwright、Chromium、FFmpeg |
| 测试与质量 | Vitest、Playwright、JUnit、Testcontainers、JaCoCo、ESLint、Spotless |
| 可观测性 | Prometheus、Alertmanager、Grafana、Loki、Tempo、OpenTelemetry |

API 请求路径为 **浏览器 → Nginx → Edge BFF → Java 领域服务**。本地 Vite 开发时，`/api` 直接代理到 Edge BFF。

| 服务 | 职责 |
|---|---|
| `edge-bff` | 唯一 API 入口，路由清单、会话/移动 token、内部身份断言、CSRF 与协议转发 |
| `identity-service` | 账号、会话、身份、组织、门店、成员、KYB、通知与后台角色 |
| `marketplace-service` | 推广任务、合作履约、消费订单、声誉、归因与经营分析 |
| `finance-service` | 双录账本、托管、支付/退款/分账 Sandbox、钱包、积分与对账 |
| `trust-service` | 争议、证据、裁决、上诉、客服终审与判例 |
| `intelligence-service` | 模型控制面、BYOK、AI 运行、创作项目、内容安全、图文与视频处理 |

`database-bootstrap` 初始化空库基础表，五个领域服务各自执行 Flyway 迁移；生产发布另有 `release-migrator` 按顺序执行迁移。共享加密、存储、身份断言、消息、数据库与财务能力位于 `platform-java/platform-*/`。

Express/TypeScript 后端已移除。Node 用于前端、测试与辅助脚本；Intelligence 容器中的 Node 是 Java Playwright 的浏览器 driver。

## 快速开始

以下步骤用于本地开发与 Sandbox 联调，命令默认从仓库根目录执行。

### 1. 准备运行环境

- Node.js 22.13+ 或 24 LTS、npm。
- JDK 25；仓库提供 Gradle Wrapper，无需另装 Gradle。
- Docker 与 Docker Compose v2。
- 本机执行 Java 媒体测试或视频 E2E 时，还需 FFmpeg/ffprobe。容器运行所需的 Chromium 与 FFmpeg 已写入 Intelligence 镜像。

```bash
npm ci

# 首次配置；已有文件时保留现有配置，按模板补齐变量
cp .env.example .env
cp .env.docker.example .env.docker
```

`.env` 供本地工具使用。下文 Compose 命令显式读取 `.env.docker`，修改 `.env` 不会自动覆盖它。

### 2. 配置本地服务

将 `.env.docker` 中的部署占位地址改为本地值：

```dotenv
FRONTEND_ORIGIN=http://127.0.0.1:8080
PUBLIC_BACKEND_ORIGIN=http://127.0.0.1:8080
FRONTEND_PORT=8080
OPS_FRONTEND_PORT=8083
AI_FRONTEND_PORT=8084
GRASSLAND_ORIGIN=http://127.0.0.1:8080
AI_APP_ORIGIN=http://127.0.0.1:8084
CORS_ORIGIN=http://127.0.0.1:8080,http://127.0.0.1:8083,http://127.0.0.1:8084,http://127.0.0.1:5173,http://127.0.0.1:5174
PUBLIC_FORWARDED_PROTO=http
SESSION_COOKIE_SECURE=never

# Compose 容器访问默认 PostgreSQL
DATABASE_URL=postgresql://grassland:grassland@postgres-local:5432/grassland

# 浏览器通过 Nginx 访问对象存储
MINIO_PUBLIC_BASE_URL=http://127.0.0.1:9002
MINIO_ROOT_USER=grassland-root
MINIO_ACCESS_KEY=example-media-access-key
```

补齐以下凭据，并替换模板中的 `replace-with-...` 占位值：

| 配置 | 用途与要求 |
|---|---|
| `SESSION_SECRET` | 会话密钥，至少 32 字符 |
| `DOUYIN_PROXY_TOKEN_SECRET`、`BILIBILI_PROXY_TOKEN_SECRET` | 媒体代理签名密钥，各至少 32 字符 |
| `IDENTITY_ASSERTION_KEY_*` | 按模板逐对配置内部服务断言密钥；保留各自的 `*_KID`，不要设置全局 `IDENTITY_ASSERTION_ISSUER` |
| `MARKETPLACE_COMMERCE_REDEEM_CODE_SECRET`、`TRUST_EVIDENCE_PSEUDONYM_SECRET` | 核销码与证据匿名化密钥，建议各使用 32 字符以上随机值 |
| `MINIO_ROOT_PASSWORD`、`MINIO_SECRET_KEY` | 分别用于 MinIO 初始化与应用运行账号，使用独立随机值 |
| `CRYPTO_KEK_BASE64` | 32 字节密钥的 Base64；平台模型凭据、BYOK 与 KYB 敏感字段加密需要 |

可用 `openssl rand -hex 32` 为每项生成独立随机密钥；`CRYPTO_KEK_BASE64` 使用 `openssl rand -base64 32`。MinIO 应用 access key 必须为 **3–20 字符**，且与 root 用户名区分。

当前 Compose 使用一次性 `minio-init` 创建桶和受限 service account，Intelligence 只接收应用凭据并关闭运行时建桶。环境模板中“应用凭据须与 root 一致”的旧注释已不适用，以 [Compose](docker-compose.yml) 和 [初始化脚本](deploy/storage/bootstrap-minio-service-account.sh) 为准。

需要邮箱注册/验证码时，配置 `SMTP_HOST`、`SMTP_PORT`、`SMTP_USER`、`SMTP_PASS`、`SMTP_FROM`。需要移动端 token 登录时，再配置 `IDENTITY_ACCESS_TOKEN_SECRET`；Web 会话不依赖该项。

### 3. 打包 Java 并启动服务

**Java Dockerfile 复制宿主机已经生成的 JAR，因此必须先运行 `bootJar`。**

```bash
source scripts/lib/java-runtime.sh
ensure_java_runtime 25
./platform-java/gradlew -p platform-java bootJar

docker compose --env-file .env.docker config --quiet
docker compose --env-file .env.docker up -d --wait postgres-local
docker compose --env-file .env.docker up -d --build
docker compose --env-file .env.docker ps -a
```

等待常驻服务健康；`database-bootstrap` 与 `minio-init` 是一次性任务，正常完成后退出。

```bash
curl -f http://127.0.0.1:8080/health
curl -i http://127.0.0.1:8080/api/auth/captcha
```

浏览器使用上方三个应用地址。Edge 本机诊断地址为 `http://127.0.0.1:8081`，对象存储上传代理为 `http://127.0.0.1:9002`。宿主机脚本/数据库客户端连接默认库时使用 `postgresql://grassland:grassland@127.0.0.1:55432/grassland`，不要使用仅在容器网络内解析的 `postgres-local`。

### 4. 前端热更新开发

后端服务运行后，按需在独立终端启动：

```bash
# 用户端
npm run dev:client -- --host 127.0.0.1 --port 5173 --strictPort

# 治理台：ops mode 将 /、/admin、/ops 映射到 ops.html
npm run dev:ops -- --host 127.0.0.1 --port 5174 --strictPort
```

Vite 默认把 `/api` 代理到 `http://localhost:8081`。自定义地址时，在启动命令前传入 `VITE_API_TARGET`，例如：

```bash
VITE_API_TARGET=http://127.0.0.1:8081 npm run dev:client
```

AI 入口可在 Vite 下访问 `/ai.html`；其 history 路由刷新与跨应用免登应通过独立 Nginx origin 联调。使用 Vite 用户端测试免登时，将 `GRASSLAND_ORIGIN` 改为实际用户端 origin，并重新创建 `frontend` 与 `identity-service` 以应用配置。访问地址中的 `localhost` 与 `127.0.0.1` 应保持一致。

### 5. 构建前端

```bash
npm run build:client
```

产物写入 `dist/`，包含 `index.html`、`ai.html`、`ops.html`。Compose 的 `frontend` 镜像负责构建并以 Nginx 提供三个入口。

## AI 模型、积分与功能开关

### 模型与密钥

平台模型在治理台 **内容与 AI → AI 模型** 中管理，包括受信端点、供应商凭据、模型与价目。当前协议适配包括 `openai-completions`、`openai-responses`、`anthropic-messages`、`google-generative-ai`；视频渠道另按对应能力配置。

个人自由创作在 AI 应用的 **AI 与治理** 中选择平台模型或自有密钥（BYOK）；组织预算、组织密钥与创作审计按主体管理。凭据由后端加密保存和调用，浏览器不直接携带密钥请求模型供应商。

平台模型端点、API key 与模型名不通过旧 `QWEN_*`、`COZE_ANALYSIS_*`、`VIDEO_ANALYSIS_API_*` 等环境变量配置。旧 `user_settings.features.*` 运行链路和旧模型列表/验证接口已退役，当前配置以模型控制面与 BYOK 接口为准。

缺少可用配置时，对应能力会拒绝执行。本地 `AI_PROVIDER_ALLOW_SANDBOX=true` 仅允许已实现的 Sandbox 能力；生产 overlay 会关闭它。

### 积分与预算

计费取决于能力、模型来源、价目和免费额度，不能统一理解为“每次生成扣 1 积分”。Finance 提供权威余额、流水与补偿，AI 运行使用稳定 operation id 保证扣费和退款幂等；上游失败按链路补偿，用户主动中止不等同于上游失败。

个人与组织可设置 AI 预算，平台可管理积分套餐、价目与积分调整。预算超限会阻止执行。免费额度基线由 `AI_FREE_QUOTA_BASE_DAILY`、`AI_FREE_QUOTA_ZONE_ID` 配置，运营可覆盖。

### 图文工作台与公众号同步

以下六个开关在 Compose 和环境模板中均默认 `false`：

| 开关 | 作用 |
|---|---|
| `EDGE_ROUTE_CREATION_STUDIO_INTELLIGENCE` | 开放新增图文工作台 API |
| `CREATION_STUDIO_WRITES_ENABLED` | 允许工作台业务写入 |
| `CREATION_VISUAL_WORKER_ENABLED` | 启用图卡/配图制作 worker |
| `EDGE_ROUTE_CREATION_WECHAT_INTELLIGENCE` | 开放公众号连接与草稿同步 API |
| `CREATION_WECHAT_WRITES_ENABLED` | 允许公众号渠道业务写入 |
| `CREATION_WECHAT_WORKER_ENABLED` | 启用公众号同步 worker |

启用前按 [#101 §7.6](docs/任务书/草场任务书-101-AI创作工作流与图文交付升级.md) 核对开放顺序、模型能力和公众号权限。回退顺序为：关闭业务写入 → 排空或核实在途任务 → 关闭 worker → 关闭 Edge 路由；保留历史记录、媒体和审计。

## 管理员与后台角色

治理台按职责组织为审核队列、用户与主体、交易与财务、内容与 AI、风控与审计五组。后台角色包括 `platform_admin`、`content_reviewer`、`customer_service`、`risk`、`finance`、`merchant_reviewer`；`platform_admin` 拥有后台角色超集权限，具体页签与操作仍以服务端授权为准。

仓库没有 `npm run admin:create` 或生产管理员 bootstrap CLI。首个平台管理员需要部署运维在受控数据库环境中引导并留存审计；迁移只会将已有 `app_users.role=admin` 账号回填到 `backend_role`，不会创建首个管理员。之后可由平台管理员通过 `PUT /api/admin/users/{id}/roles` 授予或撤销角色。

`npm run e2e:seed`、`npm run e2e:seed:auth` 只用于隔离测试环境，不能用作生产管理员初始化。

## 常用命令与验证

| 命令 | 用途 |
|---|---|
| `npm run dev` / `npm run dev:client` | 用户端 Vite 开发服务 |
| `npm run dev:ops` | 治理台 Vite 开发服务 |
| `npm run typecheck` | Vue/TypeScript 类型检查 |
| `npm run lint` | ESLint 与 Vue 文件体积门禁 |
| `npm test` | 前端与仓库级 Vitest 测试 |
| `npm run test:coverage` | 全源覆盖率 |
| `npm run coverage:changed` | 基于覆盖率报告检查变更可执行行 |
| `npm run docs:status` | 文档状态与覆盖率门槛一致性 |
| `npm run docs:links` | 本地 Markdown 链接与索引可达性 |
| `npm run security:secrets` | 已跟踪文件密钥扫描 |
| `npm run build` / `npm run build:client` | 类型检查与三入口构建 |
| `npm run e2e` | 对已准备的环境运行 Playwright |
| `npm run e2e:ci` | 创建隔离 Compose 环境并执行公共入口 E2E |

Java 完整验证需要 JDK 25、可用的 Docker/Testcontainers 和本机 FFmpeg：

```bash
source scripts/lib/java-runtime.sh
ensure_java_runtime 25
./platform-java/gradlew -p platform-java spotlessCheck test jacocoTestCoverageVerification bootJar
```

完整浏览器测试使用 Chromium、Firefox、WebKit：

```bash
npx playwright install --with-deps chromium firefox webkit
npm run e2e:ci
```

`e2e:ci` 负责生成测试密钥与账号、初始化数据库、打包并启动 Java 服务，经 Nginx → Edge 运行浏览器用例，退出时清理隔离栈及卷。测试模型配置通过控制面准备，不能作为真实供应商验收证据。诊断产物保存在 `test-artifacts/` 与 `playwright-report/`。

CI 包含前端、Java、公共入口 E2E，以及镜像漏洞扫描/SBOM 四类 job。前端覆盖率门槛由 [status.yaml](docs/status.yaml) 和 [vitest.config.ts](vitest.config.ts) 共同约束，变更可执行行门槛为 80%；Java 各模块门槛见 [Gradle 配置](platform-java/build.gradle.kts)。更多范围与前置条件见 [测试说明](tests/README.md) 和 [脚本说明](scripts/README.md)。

## Docker Compose 部署

[基础 Compose](docker-compose.yml) 提供本地 PostgreSQL、Redis、单节点 Kafka、Temporal dev server、MinIO 和应用服务。生产通过 [生产 overlay](docker-compose.production.yml) 配置外部 Kafka/Temporal、安全凭据、资源限制与可观测性，发布步骤以 [生产发布与灾备运行手册](docs/运维/生产发布与灾备运行手册.md) 为准。

- 前端默认发布 `8080`（用户端）、`8083`（治理台）、`8084`（AI 端）和 `9002`（对象存储代理）；Edge `8081` 仅绑定本机回环地址。
- `API_UPSTREAM` 保持为 `edge-bff:8080`。未登记、方法不匹配或关闭的 `EDGE_ROUTE_*` 返回 404；路由停用不回退到其他后端。
- Edge 保留 Cookie、SSE、Multipart 和 Range 等公开协议；对象存储 presigned 上传通过 `9002`，不经过 Edge。
- 修改应用域名/端口时，同步检查 `AI_APP_ORIGIN`、`GRASSLAND_ORIGIN`、`CORS_ORIGIN`、`PUBLIC_BACKEND_ORIGIN` 与 `MINIO_PUBLIC_BASE_URL`。
- TLS 在上游 LB/ingress 终止时，设置 `PUBLIC_FORWARDED_PROTO=https`、实际 `TRUSTED_PROXY_CIDR` 及相应 Cookie 策略。
- 可观测性组件通过 `--profile observability` 按需启用；附加 OTLP Collector 配置在 [docker-compose.observability.yml](docker-compose.observability.yml)。

生产配置检查入口：

```bash
scripts/validate-production-compose.sh --env-file /path/to/production.env
scripts/production-release.sh --env-file /path/to/production.env preflight
```

环境变量完整定义见 [.env.docker.example](.env.docker.example)、[.env.example](.env.example) 和各 Java 服务的 `application.yml`；实际注入与默认值以 Compose 和运行代码为准。

## 项目结构

```text
.
├── index.html / ai.html / ops.html  # 三个应用入口
├── src/
│   ├── router/、layouts/           # 用户端路由与应用壳
│   ├── ai/                        # AI 应用入口、路由与布局
│   ├── ops/                       # 治理台入口、admin 页签与 ops-console
│   ├── views/                     # 业务页面与就近组件/composable
│   ├── components/                # 共享组件与面板
│   ├── composables/、stores/       # 业务流与状态
│   ├── config/、types/、lib/       # 配置、类型与基础能力
│   └── style.css                  # 全局样式与双主题 token
├── platform-java/
│   ├── services/                  # Edge、五个领域服务与迁移任务
│   ├── platform-*/                # Java 共享能力
│   └── deploy/                    # 可观测性与迁移校验资源
├── contracts/                     # 平台规则、创作模板等共享 JSON 契约
├── deploy/                        # 安全契约与存储初始化
├── tests/                         # 部署、质量、安全与 E2E 测试
├── scripts/                       # CI、发布、校验与验收入口
├── docs/                          # 产品、架构、任务书、运维与测试资料
└── test-artifacts/                # 本地产物（Git 忽略）
```

前端单测与 `src/` 源码就近存放，Java 测试在各模块的 `src/test/`。手工验收脚本放 `scripts/acceptance/`，本机临时脚本放 `scripts/local/`。完整地图与迁移对照见 [目录结构](docs/架构/目录结构.md)。

## 主要路由

| 应用 | 路径 | 用途 |
|---|---|---|
| 用户端 | `/`、`/grassland`、`/commerce` | 主页、任务工作台、消费者商城 |
| 用户端 | `/creation` | 任务锁定创作面 |
| 用户端 | `/me/disputes`、`/me/disputes/:id` | 我的争议与详情 |
| 用户端 | `/first-password` | 成员账号首次修改密码 |
| 用户端 | `/docs/user-agreement`、`/docs/privacy-policy` | 公开协议与隐私政策占位页 |
| 用户端 | `/ai-center`、`/image-gen` | 兼容旧链接，跳转独立 AI 应用 |
| 用户端 | `/precedents`、`/complaints` | 打开工作台个人设置中的对应分节 |
| AI 创作端 | `/` | 自由创作与项目工作区 |
| 用户端 / AI 创作端 | `/article`、`/moments`、`/image`、`/comedy` | 图文、朋友圈、图片点评、风格化脚本 |
| 用户端 / AI 创作端 | `/video`、`/video-production`、`/video-canvas` | 参考视频分析、视频制作、分镜画布 |
| 治理台 | `/admin`、`/ops` | 管理后台、运营处置 |

完整定义见 [用户端路由](src/router/index.ts)、[AI 路由](src/ai/router.ts) 和 [治理台路由](src/ops/router.ts)。

## 开发约定与文档

修改前端 UI 前，先阅读 [AGENTS.md](AGENTS.md) 及适用设计规范：[用户端与 AI 创作端](DESIGN.md)、[治理台](src/ops/DESIGN.md)。复用现有组件与全局 token，保持明暗双主题，并完成桌面/移动端、键盘及加载/空/错误状态检查。

五个大视图继续按 URL 状态 composable、业务 composable、子组件分层；Vue 文件体积门禁由 `npm run lint` 执行。新增文档、脚本和截图按 [目录归位规约](docs/架构/目录结构.md) 存放。

当前开放项以 [开发进度与续接指南第四节](docs/草场开发进度与续接指南.md#四当前未完成开发与生产门禁按优先级) 和 [机器可读状态](docs/status.yaml) 为准；`status.yaml` 仅覆盖其声明的任务编号范围。后续批次查看 [任务书与交付报告索引](docs/任务书/README.md)，产品规则和操作从[产品文档索引](docs/产品/README.md)选择 [PRD](docs/产品/草场产品需求文档.md) 或[使用说明](docs/产品/草场使用说明.md)。
