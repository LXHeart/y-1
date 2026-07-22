# 草场旧 API 兼容契约矩阵

> 状态：Epic 0 契约冻结基线（草案）  
> 用途：为 `edge-bff` 透明代理和后续路由绞杀提供 Wire-level 兼容依据  
> 行为基线：现有 Express Controller 与 Vitest/Supertest 测试，**不是**生产数据快照

## 1. 目的

Java `edge-bff` 必须在迁移期完整保留现有 Vue 前端依赖的 `/api/**` 行为。本矩阵把现有路由按“响应模式”分类，标注：

- Method / Path
- 是否需要认证
- 请求 Body 类型
- 响应模式
- 关键 Status / Header
- 是否可安全重放
- 当前权威测试位置

涉及生产 Session JSON、密码 Hash、API Key、Token 或签名媒体 URL 的格式属于 Epic 0 生产事实核对项，**不在本文件猜测或复制**。

## 2. 响应模式总览

| 模式 | 说明 | BFF 处理约束 |
|---|---|---|
| JSON | `{success:true,data}` / `{success:false,error:string}` | 状态码、字段名和中文错误原样保留 |
| SVG | `/api/auth/captcha` 返回原始 `image/svg+xml` | 不包装成 JSON，保留 Content-Type |
| SSE-POST | POST + fetch 流式 | 保留 `text/event-stream`、`data: JSON\n\n`、`[DONE]`、`X-Accel-Buffering: no`，零聚合，可取消 |
| Multipart | 图片上传字段 `images` | 原始 Boundary/字节透传，不解析、不重建、不二次限制大小 |
| Binary/Range | 视频/音频代理与下载 | 保留 `Range`/`If-Range`、`200/206/416`、`Content-Range`/`Content-Length`/`Content-Disposition` |
| Cookie | 登录态、验证码、Session | 多值 `Set-Cookie`、`y1.sid`、HttpOnly、SameSite=Lax 原样保留 |
| RateLimit | 限流 Header | 保留 `RateLimit-Limit/Remaining/Reset` |

## 3. 路由族矩阵（按 `server/src/routes/**`）

| 路由族 | Method | Path | 认证 | Body | 响应模式 | 关键约束 | 可重放 |
|---|---|---|---|---|---|---|---|
| 认证-验证码 | GET | `/api/auth/captcha` | 否 | 无 | SVG | 原始 `image/svg+xml`，Session 中存验证码 | 否 |
| 认证-发码 | POST | `/api/auth/send-code` | 否 | JSON | JSON | 需图形验证码 | 否 |
| 认证-注册 | POST | `/api/auth/register` | 否 | JSON | JSON | 201 注册成功 | 否 |
| 认证-登录 | POST | `/api/auth/login` | 否 | JSON | JSON/`Set-Cookie` | 失败限流（IP/账号-IP） | 否 |
| 认证-当前用户 | GET | `/api/auth/me` | 是 | 无 | JSON | 401 未登录 | 是 | ✅ 已迁移 identity-service（Epic 2 Slice 2A），BFF RouteManifest 路由，可单路由回滚（`EDGE_ROUTE_AUTH_ME_IDENTITY=false`）|
| 认证-登出 | POST | `/api/auth/logout` | 是 | 无 | JSON | 清除 Cookie | 否 |
| 首页热点 | GET | `/api/homepage/hot-items` | 否 | 无 | JSON | 30/min 限流 | 是 |
| 抖音-提取 | POST | `/api/douyin/extract-video` | 否 | JSON | JSON | 返回签名媒体 URL | 否 |
| 抖音-分析 | POST | `/api/douyin/analyze-video` | 部分 | JSON | JSON | 需登录扣积分 | 否 |
| 抖音-媒体 | GET | `/api/douyin/proxy/:token` | 否 | 无 | Binary/Range | 200/206/416，签名 Token | 是 |
| 抖音-下载 | GET | `/api/douyin/download/:token` | 否 | 无 | Binary | `Content-Disposition` | 是 |
| 抖音-音频 | GET | `/api/douyin/audio/:token` | 否 | 无 | Binary | FFmpeg 产物 | 是 |
| 抖音-热点 | GET | `/api/douyin/hot-items` | 否 | 无 | JSON | 30/min 限流 | 是 |
| 抖音-Session | GET/POST | `/api/douyin/session*` | 否 | JSON | JSON | 扫码登录增强 fallback | 否 |
| Bilibili-提取 | POST | `/api/bilibili/extract-video` | 否 | JSON | JSON | 进程/DASH | 否 |
| Bilibili-媒体 | GET | `/api/bilibili/proxy/:token` | 否 | 无 | Binary/Range | 200/206/416 | 是 |
| Bilibili-下载 | GET | `/api/bilibili/download/:token` | 否 | 无 | Binary | `Content-Disposition` | 是 |
| 图片评价-分析 | POST | `/api/image-analysis/analyze` | 部分 | Multipart `images` | SSE-POST | 最多 6 张/30MB | 否 |
| 图片评价-步骤 | POST | `/api/image-analysis/step/*` | 部分 | Multipart/JSON | SSE-POST/JSON | 草稿/优化/精修 | 否 |
| 图片评价-风格 | GET/PUT | `/api/image-analysis/style-preferences` | 是 | JSON | JSON | 风格记忆 | 否 |
| 图片评价-导出 | POST | `/api/image-analysis/export-feishu` | 是 | JSON | JSON | 飞书导出 | 否 |
| 文章-标题 | POST | `/api/article-generation/titles` | 部分 | JSON | JSON | 仅 Qwen | 否 |
| 文章-大纲 | POST | `/api/article-generation/outline` | 部分 | JSON | SSE-POST | 流式 | 否 |
| 文章-正文 | POST | `/api/article-generation/content` | 部分 | JSON | SSE-POST | 流式 | 否 |
| 文章-配图 | POST | `/api/article-generation/image-*` | 部分 | JSON/Multipart | JSON | 搜索/生成 | 否 |
| 文章-生成图 | GET | `/api/article-generation/generated-images/:id` | 否 | 无 | Binary | 公开可访问 | 是 |
| 视频改编 | POST | `/api/video-recreation/*` | 是 | JSON/Multipart | JSON | 4 张/5MB | 否 |
| 脱口秀 | POST | `/api/comedy-generation/generate-script` | 是 | JSON | SSE-POST | 流式，`enable_thinking:false` | 否 |
| 设置 | GET/PUT | `/api/settings/analysis*`、`/homepage` | 是 | JSON | JSON | 密钥留空=保留 | 否 |
| 设置-模型 | POST | `/api/settings/analysis/models`、`verify-model` | 是 | JSON | JSON | 模型验证 | 否 |
| 健康 | GET | `/health` | 否 | 无 | JSON | `{success:true}` | 是 |

> 完整路由清单以 `server/src/app.ts` 挂载顺序和 `server/src/routes/**` 为准；后续路由迁移时在本矩阵追加切换状态和回滚开关。

## 4. 必须保留的响应行为

### 4.1 通用 Envelope

```json
{ "success": true, "data": {} }
```

```json
{ "success": false, "error": "中文错误信息" }
```

### 4.2 Cookie

- 名称：`y1.sid`
- 属性：HttpOnly、SameSite=Lax、生产 Secure、滚动 7 天
- 多值 `Set-Cookie` 必须保持独立 Header，不能合并

### 4.3 CAPTCHA

- `GET /api/auth/captcha`
- `Content-Type: image/svg+xml`
- 原始 SVG，不 JSON 包装

### 4.4 SSE

- `Content-Type: text/event-stream`
- `Cache-Control: no-cache`
- `X-Accel-Buffering: no`
- 帧：`data: <JSON>\n\n`，结束：`data: [DONE]\n\n`
- 取消传播：客户端断开必须取消上游请求

### 4.5 Multipart

- 字段名：`images`
- 当前限制：最多 6 张、单文件上限按现有 schema
- BFF 不解析、不重建、不二次限制

### 4.6 媒体 Range

- 转发：`Range`、`If-Range`
- 保留状态：`200`、`206`、`416`
- 保留 Header：`Content-Range`、`Accept-Ranges`、`Content-Length`、`Content-Type`、`ETag`、`Last-Modified`、`Content-Disposition`

### 4.7 限流 Header

- `RateLimit-Limit`
- `RateLimit-Remaining`
- `RateLimit-Reset`

## 5. 待核实的生产事实（Epic 0）

以下在 BFF 切换正式流量前必须以真实环境为准核对，不在本文件固化：

- 生产密码 Hash 实际格式（bcrypt/scrypt 编码、salt、参数）
- `connect-pg-simple` Session JSON 真实结构与签名
- `y1.sid` Cookie 签名与属性
- 抖音/Bilibili 签名媒体 Token 编码与 TTL
- 视频 Range 实际边界行为

## 6. Hop-by-hop Header 处理

BFF 在双向剥离以下 Header，避免代理语义污染：

- `Connection` 及其点名的所有 Header
- `Keep-Alive`
- `Proxy-Authenticate` / `Proxy-Authorization`
- `TE` / `Trailer`
- `Transfer-Encoding`
- `Upgrade`
- 客户端 `Host`（由 BFF 使用固定上游 Host）

客户端不能通过 `Host`、`Forwarded`、`X-Forwarded-*` 或任意 Header 改变固定上游地址。

## 7. 合成 Wire Fixture

`platform-java/contracts/legacy-wire-fixtures/` 存放合成测试数据：

- 只使用虚构账号、Cookie、Token、URL、媒体内容
- 禁止复制生产 Session、密码 Hash、API Key、支付数据或真实签名媒体 URL
- 覆盖：JSON 成功/错误、多值 `Set-Cookie`、SVG、Multipart 原始字节、SSE 增量与取消、Range `206/416`、下载 Header、Hop-by-hop 清理
- `edge-bff` 的 `LegacyExpressProxyContractTest` 使用内嵌 Reactor Netty 上游验证上述 Wire 行为
