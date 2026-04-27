# Douyin Extractor

一个本地优先的抖音/Bilibili 视频提取工具。

当前项目能力需要按运行方式区分：
- **本地 Node 环境**：支持基础解析、视频预览、下载、音频提取，以及抖音登录增强 / browser fallback
- **Docker Compose 部署**：当前只承诺基础解析、视频预览、下载、音频提取、视频分析，不承诺 Playwright 登录增强 / browser fallback

当前已实现：
- 粘贴抖音分享文本或链接
- 解析可播放视频
- 页面内预览视频
- 下载 mp4 视频
- 提取音频附件
- 手动触发视频分析
- 通过"分析设置"弹窗选择 Coze / Qwen，并把配置持久化到服务端
- 匿名解析失败时，在本地 Node 环境下可启用扫码登录增强
- 图片评价文案：上传图片，选择平台（淘宝/大众点评），生成评价并一键复制
- 图片评价生成过程支持步骤时间线展示：生成中可看到每一步耗时，生成完成后仍可通过“查看生成步骤”回看本次流程
- 图片评价支持导出到飞书文档：后端会创建 docx 文档、上传图片并写入评价内容
- 爆款文章创作：输入主题 → 选平台（微信公众号/知乎/小红书）→ AI 生成标题 → 选标题生成大纲 → 编辑大纲生成正文，大纲和正文实时流式输出
- 账号注册/登录，模型、密钥和首页热点配置按账号隔离保存
- 多平台热点聚合（抖音、微博、知乎），也可切换为 ALAPI 数据源

## 技术栈

- 前端：Vue 3 + Vite + TypeScript
- 后端：Express 5 + TypeScript
- 数据库：PostgreSQL（session、用户数据、热点缓存）
- 页面抓取：Playwright
- HTML/数据提取：Cheerio
- 输入与环境变量校验：Zod
- 日志：Pino
- 音频处理：ffmpeg
- 测试：Vitest

## 运行前置条件

本地需要先准备：
- Node.js
- npm
- PostgreSQL（可选，用于账号系统和热点缓存）
- `ffmpeg` 可执行文件
- Playwright 浏览器依赖

首次安装依赖后，建议执行：

```bash
npm install
npx playwright install
```

如果 `ffmpeg` 不在系统 PATH 中，可通过环境变量 `FFMPEG_PATH` 指向实际路径。

## 快速开始

### 1. 安装依赖

```bash
npm install
npx playwright install
```

### 2. 配置环境变量

可直接复制示例文件：

```bash
cp .env.example .env
```

在项目根目录创建 `.env`，至少包含：

```dotenv
PORT=3000
CORS_ORIGIN=http://localhost:5173
DOUYIN_PROXY_TOKEN_SECRET=replace-with-at-least-32-characters-secret
BILIBILI_PROXY_TOKEN_SECRET=replace-with-at-least-32-characters-secret
FFMPEG_PATH=ffmpeg
LOG_LEVEL=info
```

`DOUYIN_PROXY_TOKEN_SECRET` 和 `BILIBILI_PROXY_TOKEN_SECRET` 为必填项，且长度至少 32 个字符。

如需启用账号系统（注册/登录/按用户保存设置），还需配置：

```dotenv
DATABASE_URL=postgresql://user:password@localhost:5432/dbname
SESSION_SECRET=at-least-32-characters-session-secret
```

以及用于注册验证码的 SMTP 配置：

```dotenv
SMTP_HOST=smtp.example.com
SMTP_PORT=465
SMTP_USER=your@email.com
SMTP_PASS=your-smtp-password
SMTP_FROM="Your App <noreply@example.com>"
```

### 3. 初始化数据库

配置好 `DATABASE_URL` 后，执行 SQL migration 创建表结构：

```bash
psql -d your_db -f server/sql/001_init.sql
```

然后创建管理员账号：

```bash
psql -d your_db -f server/sql/002_create_admin.sql
```

> 不配置数据库时，应用仍可正常运行，但不会有账号系统和热点缓存功能。

### 4. 启动开发环境

```bash
npm run dev
```

默认行为：
- 前端开发服务器：Vite 默认端口 `5173`
- 后端服务：`http://localhost:3000`

### 5. 生产构建与启动

```bash
npm run build
npm run start
```

生产模式下，后端会托管前端构建产物 `dist/`。

## 视频分析配置说明

当前视频分析支持两种提供者：

1. **Coze 工作流**
2. **Qwen（OpenAI 兼容接口）**

配置来源按优先级分为：

1. **服务端持久化设置（按用户隔离）**
   - 登录后点击"设置"按钮
   - 点击后可选择 Coze / Qwen，并保存各自的地址、密钥、模型
   - 配置按用户隔离保存在数据库中
   - 浏览器不会直接请求第三方分析服务
2. **环境变量默认值**
   - Coze 可使用 `COZE_ANALYSIS_*` 或兼容旧变量 `VIDEO_ANALYSIS_*`
   - Qwen 可使用 `QWEN_ANALYSIS_*`
   - 超时统一使用 `VIDEO_ANALYSIS_API_TIMEOUT_MS`

补充说明：
- 设置弹窗中如果密钥输入框留空，表示保留服务端已保存的密钥；如果只输入空格后保存，会显式清空已保存密钥
- 设置保存时会校验分析服务地址，必须是有效的 `http(s)://` 地址
- 未登录用户点击"设置"会弹出登录框，提示先登录
- `proxyVideoUrl` 仅允许本站相对路径或与 `PUBLIC_BACKEND_ORIGIN` 同源的绝对地址，其他来源会被拒绝

## 首页热点说明

首页热点支持两种数据源，可在"设置"弹窗中切换：

1. **60s API**（默认）：聚合展示抖音、微博、知乎三个平台的热点，每个平台 20 条，通过 Tab 栏切换
2. **ALAPI**：需要配置 ALAPI Token，展示抖音、微博、微信、小红书的热点

热点数据会缓存在数据库中（60s 缓存 2 小时，ALAPI 缓存 5 分钟），减少对上游 API 的请求。

## 账号系统说明

注册流程：
1. 切换到"注册"标签
2. 填写邮箱、显示名称
3. 填写图形验证码（点击图片可刷新）
4. 点击"获取验证码"发送邮箱验证码
5. 填写 6 位邮箱验证码和密码
6. 提交注册，成功后自动登录

登录后：
- 分析设置（模型、密钥）按账号隔离保存
- 首页热点配置按账号隔离保存
- 页面顶部显示已登录用户名

## Docker Compose 部署

适用于服务器部署，当前推荐方案为：
- 前后端拆开容器
- 前端容器提供静态页面
- 前端通过 Nginx 反向代理 `/api` 到后端容器
- 后端容器提供 API、视频代理、下载、音频提取、视频分析能力

当前这个 Compose 方案**不包含** Playwright 浏览器登录增强 / browser fallback，仅适用于基础解析链路。

### 1. 准备部署环境变量

```bash
cp .env.docker.example .env.docker
```

#### 域名部署示例

```dotenv
FRONTEND_ORIGIN=https://your-frontend-domain.com
PUBLIC_BACKEND_ORIGIN=https://your-backend-domain.com
CORS_ORIGIN=https://your-frontend-domain.com
DOUYIN_PROXY_TOKEN_SECRET=replace-with-at-least-32-characters
BILIBILI_PROXY_TOKEN_SECRET=replace-with-at-least-32-characters
DATABASE_URL=postgresql://user:password@db:5432/dbname
SESSION_SECRET=at-least-32-characters-session-secret
VIDEO_ANALYSIS_API_TOKEN=your-token
QWEN_ANALYSIS_API_KEY=your-qwen-key
```

#### IP + 自定义端口示例

```dotenv
FRONTEND_ORIGIN=http://64.83.36.35:18080
PUBLIC_BACKEND_ORIGIN=http://64.83.36.35:13000
FRONTEND_PORT=18080
BACKEND_PORT=13000
CORS_ORIGIN=http://64.83.36.35:18080
DOUYIN_PROXY_TOKEN_SECRET=replace-with-at-least-32-characters
BILIBILI_PROXY_TOKEN_SECRET=replace-with-at-least-32-characters
DATABASE_URL=postgresql://user:password@db:5432/dbname
SESSION_SECRET=at-least-32-characters-session-secret
VIDEO_ANALYSIS_API_TOKEN=your-token
QWEN_ANALYSIS_API_KEY=your-qwen-key
```

说明：
- `FRONTEND_ORIGIN` 是浏览器实际访问前端页面的地址
- `PUBLIC_BACKEND_ORIGIN` 必须是第三方分析服务可访问的后端公网地址
- `CORS_ORIGIN` 应填写前端公网地址
- `FRONTEND_PORT` / `BACKEND_PORT` 是宿主机暴露端口
- `PORT` 是后端容器内部监听端口，通常保持 `3000`
- 后端容器内 `ffmpeg` 已通过镜像安装，通常保持 `FFMPEG_PATH=ffmpeg` 即可

### 2. 构建并启动

```bash
docker compose --env-file .env.docker build
docker compose --env-file .env.docker up -d
```

默认映射：
- 前端：`8080 -> 80`
- 后端：`3000 -> 3000`

如需修改，可在 `.env.docker` 中调整：
- `FRONTEND_PORT`
- `BACKEND_PORT`

### 3. 验证服务

检查 Compose 配置：

```bash
docker compose --env-file .env.docker config
```

检查后端健康状态时，请把 `<BACKEND_PORT>` 替换成 `.env.docker` 里的实际数值。

例如，如果你配置的是：

```dotenv
BACKEND_PORT=13000
```

那么应执行：

```bash
curl http://64.83.36.35:13000/health
```

前端访问地址同理。如果你配置的是：

```dotenv
FRONTEND_PORT=18080
```

那么浏览器访问：

```text
http://64.83.36.35:18080
```

查看日志：

```bash
docker compose --env-file .env.docker logs -f frontend
docker compose --env-file .env.docker logs -f backend
```

### 4. 验证功能

部署后建议依次验证：
- 提取抖音视频
- 页面内预览
- 下载视频
- 提取音频
- 点击"分析视频"，确认第三方分析服务可以通过 `${PUBLIC_BACKEND_ORIGIN}` 回源访问后端代理地址；其作用见下方 FAQ
- 打开"分析设置"，切换 Coze / Qwen，保存后刷新页面，确认服务端持久化配置会被重新加载

### 5. 数据持久化

Compose 已为后端挂载 `backend_data` volume，对应容器内目录：

```text
/app/server/.data
```

用于保存：
- 媒体临时目录
- 未来可复用的数据目录约定

### 6. 当前限制

本轮 Docker Compose 部署**不承诺支持**：
- 抖音扫码登录增强
- Playwright 浏览器兜底解析

说明：
- `.env.docker.example` 里仍然保留了 `DOUYIN_LOGIN_*` 等字段，主要是为了和现有配置结构保持兼容
- 但当前 Compose 镜像不以这些登录增强 / browser fallback 能力作为可用能力对外承诺

如果后续服务器部署必须保留这些能力，需要单独做包含 Playwright/Chromium 依赖的 heavier backend 镜像。

### 7. 最小排障清单

如果 Compose 部署启动后仍不可用，优先检查：
- Docker daemon 是否已启动
- 服务器安全组 / 防火墙是否已放行 `FRONTEND_PORT` 和 `BACKEND_PORT`
- `DOUYIN_PROXY_TOKEN_SECRET` / `BILIBILI_PROXY_TOKEN_SECRET` 是否至少 32 位
- Coze / Qwen 对应的默认密钥是否已填写（`VIDEO_ANALYSIS_API_TOKEN`、`COZE_ANALYSIS_API_TOKEN`、`QWEN_ANALYSIS_API_KEY`）
- `PUBLIC_BACKEND_ORIGIN` 是否与第三方分析服务真实可访问的后端地址一致
- 前端请求 `/api/...` 是否已通过 `nginx.conf` 正常转发到 `backend:3000`

## 常用命令

```bash
npm run dev
npm run dev:client
npm run dev:server
npm run build
npm run start
npm run test
npm run typecheck
npm run preview
```

## 环境变量

以下环境变量由 `server/src/lib/env.ts` 定义。

| 变量名 | 默认值 | 说明 |
| --- | --- | --- |
| `NODE_ENV` | `development` | 运行环境 |
| `PORT` | `3000` | 后端监听端口 |
| `CORS_ORIGIN` | `http://localhost:5173` | 允许的前端来源 |
| `DATABASE_URL` | 空 | PostgreSQL 连接字符串，启用账号系统和热点缓存 |
| `SESSION_SECRET` | 空 | Session 签名密钥，启用账号系统时必填，至少 32 字符 |
| `SESSION_COOKIE_NAME` | `y1.sid` | Session cookie 名称 |
| `SESSION_COOKIE_MAX_AGE_MS` | 空 | Session cookie 过期时间（毫秒） |
| `DOUYIN_FETCH_TIMEOUT_MS` | `15000` | 抖音页面/文本抓取超时 |
| `DOUYIN_HOT_API_BASE_URL` | `https://60s.viki.moe/v2/douyin` | 60s 热点 API 基地址 |
| `DOUYIN_HOT_API_TIMEOUT_MS` | `8000` | 60s 热点 API 请求超时 |
| `DOUYIN_LOGIN_TIMEOUT_MS` | `180000` | 扫码登录整体超时 |
| `DOUYIN_LOGIN_URL` | `https://www.douyin.com/` | 登录增强入口地址 |
| `DOUYIN_STORAGE_STATE_PATH` | `server/.data/douyin-storage-state.json` | 本地持久化登录态文件 |
| `DOUYIN_MEDIA_PROCESS_TIMEOUT_MS` | `45000` | 音频提取超时 |
| `DOUYIN_MEDIA_TEMP_DIR` | `server/.data/douyin-audio` | 音频临时目录 |
| `DOUYIN_USER_AGENT` | 内置桌面 UA | 抖音请求默认 UA |
| `DOUYIN_COOKIE_USER_AGENT` | 空 | 可选，单独覆盖 cookie/login 相关 UA |
| `DOUYIN_PROXY_TOKEN_SECRET` | 无默认值 | 必填，且至少 32 字符，用于代理/下载 token 签名 |
| `BILIBILI_FETCH_TIMEOUT_MS` | `15000` | B 站页面抓取超时 |
| `BILIBILI_USER_AGENT` | 内置桌面 UA | B 站请求默认 UA |
| `BILIBILI_PROXY_TOKEN_SECRET` | 无默认值 | 必填，且至少 32 字符，用于 B 站代理/下载 token 签名 |
| `PUBLIC_BACKEND_ORIGIN` | 空 | 第三方分析服务访问后端代理地址时使用的公网 backend origin |
| `VIDEO_ANALYSIS_API_BASE_URL` | `https://g3xqktww2r.coze.site/run` | 兼容旧配置的 Coze 默认 endpoint |
| `VIDEO_ANALYSIS_API_PATH` | 空 | 兼容保留字段 |
| `VIDEO_ANALYSIS_API_TOKEN` | 空 | 兼容旧配置的 Coze 默认 Token |
| `COZE_ANALYSIS_BASE_URL` | 空 | Coze 默认 endpoint，优先于兼容旧变量 |
| `COZE_ANALYSIS_API_TOKEN` | 空 | Coze 默认 Token，优先于兼容旧变量 |
| `QWEN_ANALYSIS_BASE_URL` | 空 | Qwen OpenAI 兼容接口基础地址 |
| `QWEN_ANALYSIS_API_KEY` | 空 | Qwen 默认 API Key |
| `QWEN_ANALYSIS_MODEL` | 空 | Qwen 默认模型名，未填写时回退为 `qwen3.5-flash` |
| `ANALYSIS_SETTINGS_PATH` | 空 | 可选，分析设置文件路径（不配置时使用数据库） |
| `ANALYSIS_SETTINGS_ALLOW_REMOTE_WRITE` | 未设置 | 设为 `1` 后允许非本机客户端访问 `/api/settings/analysis` |
| `VIDEO_ANALYSIS_API_TIMEOUT_MS` | `180000` | 视频分析请求超时 |
| `FFMPEG_PATH` | `ffmpeg` | ffmpeg 可执行路径 |
| `LOG_LEVEL` | `info` | Pino 日志级别 |
| `TRUST_PROXY` | 未设置 | 部署在反向代理后时可设为 `1` |
| `ALAPI_BASE_URL` | `https://v3.alapi.cn` | ALAPI 基地址 |
| `ALAPI_TIMEOUT_MS` | `8000` | ALAPI 请求超时 |
| `SMTP_HOST` | 空 | SMTP 服务器地址，启用注册功能时必填 |
| `SMTP_PORT` | `465` | SMTP 服务器端口 |
| `SMTP_USER` | 空 | SMTP 用户名 |
| `SMTP_PASS` | 空 | SMTP 密码 |
| `SMTP_FROM` | 空 | 发件人地址 |

## 使用流程

1. 在页面顶部切换功能标签：首页 / 视频提取分析 / 图片评价文案 / 爆款文章。
2. **首页**：查看多平台热点聚合（抖音、微博、知乎），可切换平台 Tab，也可在设置中切换为 ALAPI 数据源。
3. **视频提取**：粘贴抖音或 B 站的分享文本或链接，点击"提取视频"。成功后可直接预览、下载；抖音额外支持提取音频。
4. **图片评价**：上传图片后选择平台（淘宝/大众点评），点击"生成评价"，获得评价文案，可一键复制到目标平台。生成过程中会显示步骤时间线和每一步耗时；生成完成后，可通过“查看生成步骤”继续回看本次流程。
5. **图片评价导出飞书**：生成成功后，登录用户可将结果导出到飞书文档。后端会先创建 docx 文档，再为每张图片创建图片块、上传素材并回填图片 token，最后写入评价正文与标签。
6. **爆款文章**：输入主题 → 选择目标平台（微信公众号/知乎/小红书）→ 选择 AI 生成的标题 → 编辑大纲 → 生成完整正文，大纲和正文实时流式显示。仅支持 Qwen 提供者，需先在"分析设置"中配置 Qwen 地址和密钥。
7. 如需调整分析引擎或凭据，点击"设置"保存配置（需先登录）。
8. 如果匿名链路失败：
   - 抖音：展开"登录增强"面板扫码，并在登录完成后重新提取
   - B 站：优先检查分享链接是否有效，以及下方 FAQ 中 `PUBLIC_BACKEND_ORIGIN` 的配置要求是否满足

## 接口一览

### 健康检查

- `GET /health`

### 认证接口

- `POST /api/auth/login` — 邮箱密码登录
- `POST /api/auth/register` — 注册（需邮箱验证码）
- `GET /api/auth/captcha` — 获取 SVG 图形验证码
- `POST /api/auth/send-code` — 发送邮箱验证码（需先通过图形验证码）
- `POST /api/auth/logout` — 退出登录
- `GET /api/auth/me` — 获取当前登录用户信息

### 首页接口

- `GET /api/homepage/hot-items` — 获取热点数据（60s 或 ALAPI）

### 抖音相关接口

- `POST /api/douyin/extract-video`
- `POST /api/douyin/analyze-video`
- `GET /api/douyin/analysis-media/:id`
- `GET /api/douyin/proxy/:token`
- `GET /api/douyin/download/:token`
- `GET /api/douyin/audio/:token`
- `GET /api/douyin/session`
- `POST /api/douyin/session/start`
- `GET /api/douyin/session/poll`
- `POST /api/douyin/session/logout`

### B 站相关接口

- `POST /api/bilibili/extract-video`
- `POST /api/bilibili/analyze-video`
- `GET /api/bilibili/analysis-media/:id`
- `GET /api/bilibili/proxy/:token`
- `GET /api/bilibili/download/:token`

### 图片评价接口

- `POST /api/image-analysis/analyze` — 上传图片并生成评价文案（SSE）
  - 请求体包含 `platform` 字段：`taobao`（淘宝）或 `dianping`（大众点评），影响文案风格
  - 请求体包含 `reviewLength` 字段：目标字数（15-300）
  - 请求体包含可选 `feelings` 字段：用户补充感受
  - 响应为流式事件，前端会按步骤展示进度，并在事件中合并 `startedAt`、`completedAt`、`durationMs` 等计时信息
- `POST /api/image-analysis/export-feishu` — 将当前图片评价导出到飞书 docx 文档
  - 需要先登录，并在“分析设置”中保存飞书 App ID / App Secret
  - 如已在设置中保存 `folderToken`，导出时会自动把文档创建到对应文件夹
  - 图片导出采用“创建图片块 → 上传素材 → replace_image”三步流程

### 爆款文章创作接口

- `POST /api/article-generation/titles` — 根据主题生成标题选项（JSON 响应）
- `POST /api/article-generation/outline` — 根据主题和标题流式生成大纲（SSE）
- `POST /api/article-generation/content` — 根据主题、标题、大纲流式生成正文（SSE）

三个接口都接受 `platform` 字段：`wechat`（微信公众号，默认）、`zhihu`（知乎）、`xiaohongshu`（小红书），影响标题风格、大纲结构和正文语气。

仅支持 Qwen 提供者。大纲和正文使用 SSE（Server-Sent Events）实时流式输出，前端通过 `fetch` + `ReadableStream` 消费。

### 设置接口

- `GET /api/settings/analysis` — 获取分析设置
- `PUT /api/settings/analysis` — 保存分析设置
- `POST /api/settings/analysis/models` — 获取可用模型列表
- `POST /api/settings/analysis/verify-model` — 验证模型可用性

需要登录后才能访问。

### 分析接口请求体

抖音与 B 站分析接口都接受同样的请求体结构：

```json
{
  "proxyVideoUrl": "/api/douyin/proxy/<token>"
}
```

字段说明：
- `proxyVideoUrl` 必填
- 分析提供者、服务地址、密钥、模型统一由服务端持久化设置或环境变量决定，浏览器侧请求不能覆盖

### 示例：提取视频

#### 抖音

请求：

```bash
curl -X POST http://localhost:3000/api/douyin/extract-video \
  -H 'Content-Type: application/json' \
  -d '{"input":"7.54 复制打开抖音，看看【示例】 https://v.douyin.com/xxxx/"}'
```

响应示例：

```json
{
  "success": true,
  "data": {
    "sourceUrl": "https://v.douyin.com/xxxx/",
    "platform": "douyin",
    "videoId": "1234567890",
    "author": "示例作者",
    "title": "示例标题",
    "coverUrl": "https://p3-sign.douyinpic.com/...",
    "proxyVideoUrl": "/api/douyin/proxy/<token>",
    "downloadVideoUrl": "/api/douyin/download/<token>",
    "downloadAudioUrl": "/api/douyin/audio/<token>",
    "usedSession": false,
    "fetchStage": "browser_network"
  }
}
```

#### B 站

请求：

```bash
curl -X POST http://localhost:3000/api/bilibili/extract-video \
  -H 'Content-Type: application/json' \
  -d '{"input":"https://www.bilibili.com/video/BV1test"}'
```

响应示例：

```json
{
  "success": true,
  "data": {
    "sourceUrl": "https://www.bilibili.com/video/BV1test",
    "platform": "bilibili",
    "videoId": "BV1test",
    "author": "示例作者",
    "title": "示例标题",
    "coverUrl": "https://i0.hdslb.com/...",
    "durationSeconds": 125,
    "proxyVideoUrl": "/api/bilibili/proxy/<token>",
    "downloadVideoUrl": "/api/bilibili/download/<token>",
    "playbackMode": "dash"
  }
}
```

字段说明：
- `proxyVideoUrl`：用于页面内预览和后续分析的视频代理地址
- `downloadVideoUrl`：用于下载 mp4 的后端地址
- `downloadAudioUrl`：仅抖音返回，用于触发音频提取并下载音频文件
- `playbackMode`：仅 B 站返回，`progressive` 表示单流直连，`dash` 表示音视频分离并由后端处理
- `usedSession`：仅抖音返回，`true` 表示本次解析走了本地持久化登录态增强
- `fetchStage`：仅抖音返回，表示最终可播放地址的来源，可能为：
  - `page_json`：直接来自页面 HTML / script 中的可用片段
  - `browser_json`：来自浏览器抓取到的页面 JSON 片段
  - `browser_network`：来自浏览器网络响应或媒体请求信号

失败时统一返回：

```json
{
  "success": false,
  "error": "错误信息"
}
```

### 示例：分析视频

#### 抖音

```bash
curl -X POST http://localhost:3000/api/douyin/analyze-video \
  -H 'Content-Type: application/json' \
  -d '{
    "proxyVideoUrl": "/api/douyin/proxy/<token>"
  }'
```

#### B 站

```bash
curl -X POST http://localhost:3000/api/bilibili/analyze-video \
  -H 'Content-Type: application/json' \
  -d '{
    "proxyVideoUrl": "/api/bilibili/proxy/<token>"
  }'
```

### 分析结果说明

- 短视频会直接走单次分析
- 较长视频会自动按最长 30 秒分段，再把多段结果合并
- 当前只支持分析 10 分钟以内的视频，30 秒到 2 分钟通常效果最好
- 返回结果中可能包含：
  - `runId`
  - `runIds`
  - `segmented`
  - `clipCount`
  - `videoScript`
  - `videoCaptions`
  - `charactersDescription`
  - `sceneDescription`
  - `propsDescription`
  - `voiceDescription`

### 示例：查询登录增强状态

```bash
curl http://localhost:3000/api/douyin/session
```

典型状态包括：
- `missing`：当前未建立本地登录态
- `launching`：后端正在准备登录页/二维码
- `qr_ready`：二维码已生成，等待扫码
- `waiting_for_confirm`：已扫码，等待手机确认
- `authenticated`：本地登录态可复用
- `expired`：旧登录态已失效
- `error`：登录增强流程异常

## 页面操作说明 / 常见问题

### 1. 为什么分析需要 `PUBLIC_BACKEND_ORIGIN`？

因为第三方分析服务需要回源访问本项目生成的 `proxyVideoUrl` 或 `analysis-media` 地址。
如果后端没有一个外部可访问的公网地址，第三方分析服务就拿不到视频内容。

简化理解：
- 浏览器可以访问你的页面
- 后端可以访问上游视频源
- 第三方分析服务还需要能访问你的后端代理地址

所以：
- 本地开发时，如果分析服务无法访问你的本机 `localhost`，分析可能失败
- 服务器部署时，应把 `PUBLIC_BACKEND_ORIGIN` 配成第三方分析服务可访问的真实公网 backend origin

上文 Docker 示例、功能验证和排障清单里提到的 `PUBLIC_BACKEND_ORIGIN`，都指这里这个用途。

### 2. 为什么页面里的分析配置会持久化到服务端？

这是当前设计要求。

页面里的"分析设置"会保存到服务端（配置了数据库时存入数据库，否则存入 `server/.data/analysis-settings.json`），后续分析请求直接复用这份配置，不需要在每次请求里重复携带提供者地址或密钥。

这样做是为了：
- 避免浏览器把第三方分析服务地址或凭据作为请求参数反复发送
- 让刷新页面后仍能保持当前分析提供者配置
- 统一由后端决定实际使用的分析提供者与凭据来源

### 3. 为什么超过 10 分钟不给分析？

当前分析链路会把较长视频自动拆成多个最长 30 秒的片段分别分析，再把多段结果合并。
但视频过长时会带来几个问题：
- 上游分析耗时明显变长
- 分段数量过多，整体成本更高
- 合并后的结果可读性通常会下降

所以当前限制为：最长 10 分钟；最佳体验建议 30 秒到 2 分钟。

### 4. 抖音和 B 站的分析链路有什么区别？

两者对外使用方式基本一致：先提取视频，再点击"分析视频"；实际使用哪个分析提供者，由服务端持久化设置或环境变量决定。

后端处理细节不同：
- **抖音**：主要是单视频流代理，额外支持登录增强、音频提取
- **B 站**：既支持 progressive 单流，也支持 DASH 音视频分离资源；遇到 DASH 时，后端会先准备可分析媒体，再继续分析

共同点：
- 都支持最长 30 秒自动分段分析
- 都支持 10 分钟时长限制
- 都通过同一套服务端分析设置选择 Coze / Qwen

### 5. 为什么要登录才能修改设置？

分析设置（模型选择、API 密钥）和首页热点配置都改为按账号隔离保存。这样做是为了：
- 不同用户可以有各自独立的模型和密钥配置
- 密钥不会暴露给其他用户
- 支持多用户各自管理自己的分析链路

## 项目结构

```text
.
├── src/                     # Vue 前端
├── server/src/              # Express 后端源码
├── server/sql/              # 数据库 migration
├── package.json
├── vite.config.ts
└── vitest.config.ts
```

关键目录：
- `src/components/`：页面组件（首页、登录、视频解析、图片评价、爆款文章、设置弹窗）
- `src/composables/`：前端请求与状态逻辑，其中 `useImageAnalysis.ts` 负责图片评价 SSE 消费、步骤合并、导出飞书状态管理
- `src/types/`：前端类型定义
- `server/src/controllers/`：HTTP handler
- `server/src/routes/`：Auth / Homepage / Douyin / Bilibili / 图片分析 / 文章创作 / 设置路由定义
- `server/src/services/`：Auth / 用户 / 热点 / Douyin / Bilibili 提取/代理/分析/音频/文章创作处理
- `server/src/services/providers/`：AI 分析提供者抽象（Coze、Qwen），含文章生成的 SSE 流式支持；图片评价步骤事件也从这里发出
- `server/src/services/feishu-export.service.ts`：图片评价导出飞书文档，负责创建 docx、创建图片块、上传图片素材并回填 token
- `server/src/lib/`：认证、数据库、浏览器抓取、HTTP、日志、环境变量等基础能力

## 已实现的边界与约束

- 视频预览和下载走后端代理，不直接把上游视频地址暴露给前端。
- 后端只允许受信任的视频 host 进入代理/下载链路。
- 登录增强只保存在后端本地文件中，不回传到前端。
- `extract-video`、`proxy`、`download`、`audio`、`session`、`auth` 接口都带有基础限流。
- 注册时需要图形验证码 + 邮箱验证码双重验证。
- 密码使用 `scrypt` 哈希存储，不明文保存。
- 该项目当前更适合本地自用或受控环境，不是公开多租户 SaaS。

当前匿名解析链路的非显然行为：
- 短链 / 分享页输入仍可能经过 `desktop_http -> mobile_http -> browser` 的多阶段回退。
- direct `/video/:id?...` 输入在 `desktop_http` 未解析出可播放素材时，会跳过低价值 `mobile_http`，直接进入 `browser`。
- 这类 direct URL 优化不会关闭 browser 内部的 mobile retry；是否继续 mobile follow-up，仍由 browser 侧基于实际信号决定。
- 如果匿名链路命中 challenge / captcha，再由登录增强作为 fallback，而不是默认路径。

## 测试与校验

```bash
npm run test
npm run typecheck
npm run build
```

如果修改了解析链路，优先关注：
- `server/src/services/douyin-resolve.service.test.ts`
- `server/src/controllers/douyin.controller.test.ts`
- `server/src/services/douyin-audio.service.test.ts`
- `server/src/controllers/image-analysis.controller.test.ts`
- `server/src/services/article-generation-dispatch.service.test.ts`
