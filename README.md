# 全功能营销工具库

本地优先的短视频提取、内容创作与营销工具平台。

## 功能概览

| 功能 | 说明 |
|------|------|
| AI 内容创作中心 | 平台优先一级入口：选平台（九平台）→ 定内容形式 → 选创作来源（独立/任务/门店/热点/参考素材），导航到对应创作视图 |
| 视频参考提取 | 视频制作的可选参考输入：抖音/Bilibili 视频解析、预览、下载、音频提取、AI 分析，结果可带入视频制作脚本 |
| 图片评价文案 | 点评探店定位：上传图片 → 选择平台（淘宝/大众点评）→ 生成探店点评文案 → 一键复制 / 导出飞书文档，支持多版本对比 |
| 爆款文章创作 | 主题 → 平台（微信公众号/知乎/小红书/抖音图集短文案）→ 标题 → 大纲 → 正文 → 按段落配图，SSE 流式输出 |
| 脱口秀/风格化脚本 | 六种抽象风格模板（不模仿特定在世创作者），SSE 流式输出 |
| 图片生成 | 素材生成定位：独立图片生成，支持参考图上传和 @mention |
| 视频内容改编 | 将视频分析结果转为分镜脚本，支持自定义指令和图片上传 |
| 视频制作 | 上传素材 / 粘贴参考视频链接 / 从热点选主题 → AI 脚本生成 → 视频生成 |
| 账号系统 | 邮箱注册（图形验证码 + 邮箱验证码，可选初始身份）、登录、按用户隔离设置 |
| 积分系统 | 功能使用扣积分，管理员可调整 |
| 创作灵感 | 多平台热点（抖音/微博/知乎），支持 60s API 和 ALAPI 两种数据源，作为创作选题与灵感来源 |

## 技术栈

- 前端：Vue 3 + Vite + TypeScript
- 后端：Express 5 + TypeScript
- 数据库：PostgreSQL（session、用户数据、积分、热点缓存）
- 页面抓取：Playwright | HTML 提取：Cheerio | 音频处理：ffmpeg
- 输入校验：Zod | 日志：Pino | 测试：Vitest

## 快速开始

### 1. 安装依赖

```bash
npm install
npx playwright install
```

### 2. 配置环境变量

```bash
cp .env.example .env
```

必填项：

```dotenv
PORT=3000
CORS_ORIGIN=http://localhost:5173
DOUYIN_PROXY_TOKEN_SECRET=replace-with-at-least-32-characters-secret
BILIBILI_PROXY_TOKEN_SECRET=replace-with-at-least-32-characters-secret
FFMPEG_PATH=ffmpeg
LOG_LEVEL=info
```

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

### 3. 初始化数据库

```bash
npx tsx server/src/scripts/run-migrations.ts
```

> 不配置数据库时，应用仍可正常运行，但不会有账号系统、积分和热点缓存功能。

### 4. 启动开发环境

```bash
npm run dev
```

- 前端：`http://localhost:5173`
- 后端：`http://localhost:3000`

### 5. 生产构建

```bash
npm run build
npm run start
```

生产模式下后端托管前端构建产物 `dist/`。

## 使用流程

1. **主路径：AI 内容创作中心**（默认首页）：选平台 → 选内容形式 → 选创作来源（独立创作/从热点/参考素材等）→ 开始创作，自动带入对应创作视图
2. **文章创作**：输入主题 → 选平台（含抖音图集短文案模式）→ 选标题 → 编辑大纲 → 生成正文 → 按段落配图
3. **视频制作**：上传素材 / 粘贴抖音或 B 站参考视频链接 / 从热点选主题 → AI 生成脚本 → 生成视频
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

## 管理员

- 管理员账号通过数据库 migration 创建
- 登录后可看到「管理」标签页：查看用户列表、调整积分

## 视频分析配置

支持两种提供者：**Coze 工作流** 和 **Qwen（OpenAI 兼容接口）**。

配置优先级：
1. 服务端持久化设置（按用户隔离，通过「设置」弹窗配置）
2. 环境变量默认值（`COZE_ANALYSIS_*` / `QWEN_ANALYSIS_*`）

> 浏览器不会直接请求第三方分析服务，所有请求由后端代理。

## 首页热点

支持两种数据源，可在「设置」弹窗中切换：

1. **60s API**（默认）：聚合抖音/微博/知乎热点，通过 Tab 栏切换
2. **ALAPI**：需要配置 ALAPI Token，展示抖音/微博/微信/小红书热点

热点数据缓存在数据库中（60s 缓存 2 小时，ALAPI 缓存 5 分钟）。

## Docker Compose 部署

适用于服务器部署。公网 API 统一经 Nginx → Edge BFF；Edge 将已迁移路由送到 Java 领域服务，其余透明转发 Express legacy/worker。

> 当前 Compose 方案不包含 Playwright 浏览器登录增强 / browser fallback。

### 1. 准备环境变量

```bash
cp .env.docker.example .env.docker
```

编辑 `.env.docker`，配置 `FRONTEND_ORIGIN`、`PUBLIC_BACKEND_ORIGIN`、`CORS_ORIGIN`、`DATABASE_URL` 等；首次启动还必须填写 `MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY` 四个对象存储凭据，Compose 会在缺失时 fail-fast。

### 2. 构建并启动

```bash
docker compose --env-file .env.docker build
docker compose --env-file .env.docker up -d
```

默认公网映射只有前端 `8080 -> 80` 和对象存储上传代理 `9002`。Express `3000` 与 Edge `8081` 只绑定 `127.0.0.1`，用于本机诊断和 Vite 开发。

### 3. 验证

```bash
docker compose --env-file .env.docker config --quiet
curl http://<host>:<FRONTEND_PORT>/health
curl -i http://<host>:<FRONTEND_PORT>/api/auth/captcha
```

### 4. Edge BFF 切流与回退

`edge-bff` 是默认 API 入口，保留 Cookie、SSE、Multipart、Range 和 legacy wire 契约。对象存储 presigned PUT 仍按设计直传 `9002`，不经过 BFF。

- 单路由回退：设置对应 `EDGE_ROUTE_*` 为 `false`，recreate `edge-bff`。
- 整入口应急回退：设置 `API_UPSTREAM=backend:3000`，用 `--no-deps` 只 recreate `frontend`。
- TLS 在上游 LB/ingress 终止时设置 `PUBLIC_FORWARDED_PROTO=https` 和实际的 `TRUSTED_PROXY_CIDR`；Nginx 会覆盖协议头并从可信代理链重建客户端 IP。
- 不配置自动 upstream failover，避免非幂等 POST 被两个上游重复执行。
- 整入口回退时 Java 独占路径会降级为 legacy 404，不是功能等价回滚。

```bash
# 本地 Java 构建（需要 JDK 25；可用 brew install openjdk@25）
cd platform-java
./gradlew clean build

# 默认栈已包含 Edge 与五个 Java 领域服务
docker compose --env-file .env.docker up -d --build
curl -i http://localhost:8081/health
curl -i http://localhost:8081/api/auth/captcha

# 整入口应急回退（显式操作，恢复时改回 edge-bff:8080）
API_UPSTREAM=backend:3000 docker compose --env-file .env.docker up -d --no-deps --force-recreate frontend
```

### 5. CI 与公共入口 E2E

本地可以用与 GitHub Actions 相同的隔离 Compose 流程验证公共 Nginx → Edge BFF → Java 服务入口：

```bash
# 需要 Docker、Node 20+、Chromium，以及用于 Gradle toolchain 的 JDK 25
npx playwright install chromium
npm run e2e:ci
```

`scripts/ci-e2e.sh` 会创建独立的 Compose project，生成临时密钥和 E2E 账号，先运行 legacy migration，再构建六个 Java `bootJar`、启动完整栈并执行 Playwright。macOS 默认 Java 8/21 时会自动查找 Homebrew JDK 25；也可以显式设置 `JAVA_HOME`。E2E 使用不可调用的占位 Qwen 地址，仅用于通过 Intelligence 的启动配置校验，不会发起模型请求。

失败时脚本会保存 `test-artifacts/compose.log`、`test-artifacts/compose-ps.txt` 和 Playwright 报告；无论成功或失败都会清理隔离 Compose project 及卷。GitHub Actions 的 `node`、`java`、`e2e` 三个 job 会分别执行已跟踪文件密钥扫描、类型检查、全源覆盖率测试与构建；全量 Gradle 测试与 jar artifact；公共入口浏览器测试。

Node 全源覆盖率本次实测约为 statements/lines 47.7%、branches 75.1%、functions 68.9%；CI 保留 statements/lines 46%、branches 74%、functions 66% 的全局 ratchet，并对 Git diff 中变更的可执行行执行 80% 门禁、上传 HTML 报告。总体覆盖率仍未达到 80%，不能把 CI 绿色解释为全仓覆盖率目标已完成。

兼容约束和契约矩阵见 [`docs/草场旧API兼容契约矩阵.md`](docs/草场旧API兼容契约矩阵.md)。可观测性组件按需使用 `--profile observability` 启动。

## 环境变量

完整列表见 `server/src/lib/env.ts`。常用变量：

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `PORT` | `3000` | 后端监听端口 |
| `CORS_ORIGIN` | `http://localhost:5173,http://localhost:5174` | 允许的前端来源（逗号分隔） |
| `DATABASE_URL` | 空 | PostgreSQL 连接字符串 |
| `SESSION_SECRET` | 空 | Session 签名密钥（启用账号时必填，≥32 字符）|
| `DOUYIN_PROXY_TOKEN_SECRET` | 必填 | 抖音代理 token 签名密钥（≥32 字符）|
| `BILIBILI_PROXY_TOKEN_SECRET` | 必填 | B站代理 token 签名密钥（≥32 字符）|
| `FFMPEG_PATH` | `ffmpeg` | ffmpeg 可执行路径 |
| `PUBLIC_BACKEND_ORIGIN` | 空 | 后端公网地址（第三方回源访问用） |
| `QWEN_ANALYSIS_BASE_URL` | 空 | Qwen 接口地址 |
| `QWEN_ANALYSIS_API_KEY` | 空 | Qwen API Key |
| `QWEN_ANALYSIS_MODEL` | 空 | Qwen 模型名（默认 qwen3.5-flash 在服务层配置） |
| `COZE_ANALYSIS_BASE_URL` | 空 | Coze 工作流地址 |
| `COZE_ANALYSIS_API_TOKEN` | 空 | Coze API Token |
| `IMAGE_GENERATION_BASE_URL` | 空 | AI 图片生成接口地址 |
| `IMAGE_GENERATION_API_KEY` | 空 | AI 图片生成 API Key |
| `IMAGE_GENERATION_MODEL` | 空 | AI 图片生成模型名 |
| `ALAPI_BASE_URL` | `https://v3.alapi.cn` | ALAPI 地址（热点数据源） |
| `TRUST_PROXY` | 空 | Express 信任代理（Docker 部署设 `1`） |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASS` / `SMTP_FROM` | 空 | SMTP 配置（启用注册时必填）|
| `FREE_CREDITS_ON_REGISTER` | `3` | 新用户注册赠送积分 |
| `LOG_LEVEL` | `info` | 日志级别（fatal/error/warn/info/debug/trace/silent） |

## 项目结构

```text
.
├── src/                     # Vue 前端
│   ├── components/          # 页面组件
│   ├── composables/         # 请求与状态逻辑
│   └── types/               # 类型定义
├── server/src/              # Express 后端
│   ├── controllers/         # HTTP handler
│   ├── routes/              # 路由定义
│   ├── services/            # 业务逻辑
│   ├── services/providers/  # AI 分析提供者（Coze、Qwen）
│   ├── lib/                 # 基础能力
│   └── schemas/             # 请求验证 schema
├── server/sql/              # 数据库 migration
├── package.json
├── vite.config.ts
└── vitest.config.ts
```

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
- 密码使用 `scrypt` 哈希存储
- 积分扣减使用原子操作
- 管理员接口需 admin 角色认证
- 所有 API 带基础限流
