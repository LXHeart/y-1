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
- 匿名解析失败时，在本地 Node 环境下可启用扫码登录增强

## 技术栈

- 前端：Vue 3 + Vite + TypeScript
- 后端：Express 5 + TypeScript
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
FFMPEG_PATH=ffmpeg
LOG_LEVEL=info
```

`DOUYIN_PROXY_TOKEN_SECRET` 为必填项，且长度至少 32 个字符。

### 3. 启动开发环境

```bash
npm run dev
```

默认行为：
- 前端开发服务器：Vite 默认端口 `5173`
- 后端服务：`http://localhost:3000`

### 4. 生产构建与启动

```bash
npm run build
npm run start
```

生产模式下，后端会托管前端构建产物 `dist/`。

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
VIDEO_ANALYSIS_API_TOKEN=your-token
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
VIDEO_ANALYSIS_API_TOKEN=your-token
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
- 点击“分析视频”，确认第三方分析服务使用的是 `${PUBLIC_BACKEND_ORIGIN}/api/douyin/proxy/:token`

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
- `VIDEO_ANALYSIS_API_TOKEN` 是否已填写真实值
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
| `DOUYIN_FETCH_TIMEOUT_MS` | `15000` | 抖音页面/文本抓取超时 |
| `DOUYIN_LOGIN_TIMEOUT_MS` | `180000` | 扫码登录整体超时 |
| `DOUYIN_LOGIN_URL` | `https://www.douyin.com/` | 登录增强入口地址 |
| `DOUYIN_STORAGE_STATE_PATH` | `server/.data/douyin-storage-state.json` | 本地持久化登录态文件 |
| `DOUYIN_MEDIA_PROCESS_TIMEOUT_MS` | `45000` | 音频提取超时 |
| `DOUYIN_MEDIA_TEMP_DIR` | `server/.data/douyin-audio` | 音频临时目录 |
| `DOUYIN_USER_AGENT` | 内置桌面 UA | 抖音请求默认 UA |
| `DOUYIN_COOKIE_USER_AGENT` | 空 | 可选，单独覆盖 cookie/login 相关 UA |
| `DOUYIN_PROXY_TOKEN_SECRET` | 无默认值 | 必填，且至少 32 字符，用于代理/下载 token 签名 |
| `PUBLIC_BACKEND_ORIGIN` | 空 | 第三方分析服务访问后端代理地址时使用的公网 backend origin |
| `VIDEO_ANALYSIS_API_BASE_URL` | `https://g3xqktww2r.coze.site/run` | 视频分析上游完整 endpoint |
| `VIDEO_ANALYSIS_API_PATH` | 空 | 兼容保留字段，当前不参与主要拼接逻辑 |
| `VIDEO_ANALYSIS_API_TOKEN` | 空 | 视频分析上游 Bearer Token |
| `VIDEO_ANALYSIS_API_TIMEOUT_MS` | `60000` | 视频分析请求超时 |
| `FFMPEG_PATH` | `ffmpeg` | ffmpeg 可执行路径 |
| `LOG_LEVEL` | `info` | Pino 日志级别 |
| `TRUST_PROXY` | 未设置 | 部署在反向代理后时可设为 `1` |

## 使用流程

1. 把抖音 App 复制出来的整段分享文本或链接粘贴到输入框。
2. 点击“提取视频”。
3. 成功后可直接：
   - 页面内预览视频
   - 下载视频
   - 提取音频
   - 在新标签页预览代理流
4. 如果匿名链路命中校验页或失败，再展开“登录增强”面板扫码，并在登录完成后重新提取。

## 接口一览

当前 README 的接口示例仍以 Douyin 为主，因为这部分说明主要覆盖本次联调和部署验证所需的核心链路。Bilibili 相关运行时变量与容器化能力已经接入，但如需对外文档化完整 Bilibili 路由与行为，可后续再单独补一节，避免和当前 Douyin 验证主线混在一起。

### 健康检查

- `GET /health`

### 抖音相关接口

- `POST /api/douyin/extract-video`
- `GET /api/douyin/proxy/:token`
- `GET /api/douyin/download/:token`
- `GET /api/douyin/audio/:token`
- `GET /api/douyin/session`
- `POST /api/douyin/session/start`
- `GET /api/douyin/session/poll`
- `POST /api/douyin/session/logout`

### 示例：提取视频

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

字段说明：
- `proxyVideoUrl`：用于页面内预览的视频代理地址
- `downloadVideoUrl`：用于下载 mp4 的后端地址
- `downloadAudioUrl`：用于触发音频提取并下载音频文件
- `usedSession`：`true` 表示本次解析走了本地持久化登录态增强
- `fetchStage`：表示最终可播放地址的来源，可能为：
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

## 项目结构

```text
.
├── src/                     # Vue 前端
├── server/src/              # Express 后端源码
├── test/                    # 测试初始化与辅助文件
├── package.json
├── vite.config.ts
└── vitest.config.ts
```

关键目录：
- `src/components/`：页面组件
- `src/composables/`：前端请求与状态逻辑
- `server/src/controllers/`：HTTP handler
- `server/src/services/`：Douyin 提取、登录、代理、音频处理
- `server/src/lib/`：浏览器抓取、HTTP、日志、环境变量等基础能力

## 已实现的边界与约束

- 视频预览和下载走后端代理，不直接把上游视频地址暴露给前端。
- 后端只允许受信任的视频 host 进入代理/下载链路。
- 登录增强只保存在后端本地文件中，不回传到前端。
- `extract-video`、`proxy`、`download`、`audio`、`session` 接口都带有基础限流。
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
