# 全功能营销工具库

本地优先的短视频提取、内容创作与营销工具平台。

## 功能概览

| 功能 | 说明 |
|------|------|
| 视频提取分析 | 抖音/Bilibili 视频解析、预览、下载、音频提取、AI 视频分析 |
| 图片评价文案 | 上传图片 → 选择平台（淘宝/大众点评）→ 生成评价 → 一键复制 / 导出飞书文档 |
| 爆款文章创作 | 主题 → 平台（微信/知乎/小红书）→ 标题 → 大纲 → 正文 → 按段落配图，SSE 流式输出 |
| 脱口秀生成 | 仿李继刚风格，SSE 流式输出 |
| 图片生成 | 独立图片生成，支持参考图上传和 @mention |
| 视频内容改编 | 将视频分析结果转为分镜脚本，支持自定义指令和图片上传 |
| 账号系统 | 邮箱注册（图形验证码 + 邮箱验证码）、登录、按用户隔离设置 |
| 积分系统 | 功能使用扣积分，管理员可调整 |
| 热点聚合 | 多平台热点（抖音/微博/知乎），支持 60s API 和 ALAPI 两种数据源 |

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

1. 在页面顶部切换功能标签
2. **视频提取**：粘贴抖音/B站分享文本或链接 → 提取 → 预览/下载/音频/分析
3. **图片评价**：上传图片 → 选择平台 → 生成评价 → 复制/导出飞书
4. **爆款文章**：输入主题 → 选平台 → 选标题 → 编辑大纲 → 生成正文 → 按段落配图
5. **脱口秀**：输入主题 → AI 实时生成脚本
6. **图片生成**：输入描述（可上传参考图）→ 生成图片
7. 需要调整分析配置时，点击「设置」（需先登录）

## 积分系统

- 每次使用视频分析、图片评价、文章生成、脱口秀生成扣除 1 积分
- 新注册用户自动获得 3 积分（可通过 `FREE_CREDITS_ON_REGISTER` 环境变量调整）
- 管理员可通过管理页面调整任意用户的积分
- 所有积分变动均有记录

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

适用于服务器部署。前后端拆分容器，前端通过 Nginx 反向代理 `/api` 到后端。

> 当前 Compose 方案不包含 Playwright 浏览器登录增强 / browser fallback。

### 1. 准备环境变量

```bash
cp .env.docker.example .env.docker
```

编辑 `.env.docker`，配置 `FRONTEND_ORIGIN`、`PUBLIC_BACKEND_ORIGIN`、`CORS_ORIGIN`、`DATABASE_URL` 等。

### 2. 构建并启动

```bash
docker compose --env-file .env.docker build
docker compose --env-file .env.docker up -d
```

默认映射：前端 `8080 -> 80`，后端 `3000 -> 3000`。可通过 `FRONTEND_PORT` / `BACKEND_PORT` 调整。

### 3. 验证

```bash
docker compose --env-file .env.docker config   # 检查配置
curl http://<host>:<BACKEND_PORT>/health        # 检查后端健康
```

### 4. 可选：Java Edge BFF 评估（Epic 0/1）

草场后端正向 Java 微服务渐进迁移。本仓库新增独立的 `platform-java/` 工程，并附带一个**可选**的 `edge-bff`（Spring Cloud Gateway WebFlux）透明代理。

- 默认流量**不变**：Nginx 仍直连 `backend:3000`。
- `edge-bff` 仅在使用 `--profile java-edge` 时启动，默认映射 `EDGE_BFF_PORT=8081`。
- 启用后可通过 `http://localhost:8081/api/**` 和 `http://localhost:8081/health` 并行验证，不影响现有入口。

```bash
# 本地 Java 构建（需要 JDK 25；可用 brew install openjdk@25）
cd platform-java
./gradlew clean build

# Compose 可选 BFF 评估（仍保留 backend 与 Nginx 直连）
EDGE_BFF_PORT=8081 docker compose --env-file .env.docker --profile java-edge up -d --build backend edge-bff
curl -i http://localhost:8081/health
curl -i http://localhost:8081/api/auth/captcha
```

兼容约束和契约矩阵见 [`docs/草场旧API兼容契约矩阵.md`](docs/草场旧API兼容契约矩阵.md)。回滚方式：停用 `java-edge` Profile，流量自动回到 Nginx → Express。

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
