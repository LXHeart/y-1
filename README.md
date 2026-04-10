# Douyin Extractor

一个本地优先的抖音视频提取工具。

当前已实现：
- 粘贴抖音分享文本或链接
- 解析可播放视频
- 页面内预览视频
- 下载 mp4 视频
- 提取音频附件
- 匿名解析失败时，启用扫码登录增强

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
