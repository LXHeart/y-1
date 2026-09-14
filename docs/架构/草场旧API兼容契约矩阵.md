# 草场旧 API 兼容契约矩阵（Java 实现基线）

> 校准日期：2026-09-15。范围：既有 `/api/**` 的 HTTP 兼容行为、当前路由来源与验证入口。
> Express 已退役；本文件不再依赖 `server/src/routes/**`、Supertest 或已移除的 `platform-java/contracts/legacy-wire-fixtures/`。新增接口和显式退役以当前 Java Controller、Edge 配置及对应任务书为准。

## 1. 权威来源与使用方式

| 内容 | 当前来源 |
|---|---|
| 路由、方法、前缀/精确匹配、启停与上游 | [Edge application.yml](../../platform-java/services/edge-bff/src/main/resources/application.yml)、[UpstreamResolver](../../platform-java/services/edge-bff/src/main/java/com/grassland/edge/proxy/UpstreamResolver.java) |
| 请求/响应与资源权限 | 对应 Java Controller、DTO、调用的领域服务与集成测试 |
| 流式代理与 Header 处理 | [RoutingProxyHandler](../../platform-java/services/edge-bff/src/main/java/com/grassland/edge/proxy/RoutingProxyHandler.java)、[ProxyHeaderPolicy](../../platform-java/services/edge-bff/src/main/java/com/grassland/edge/proxy/ProxyHeaderPolicy.java) |
| 公网入口限制与缓冲 | [nginx.conf](../../nginx.conf) |
| 认证 Token、刷新与设备撤销 | [移动端认证方案](移动端刷新token认证方案设计.md) |

下表是代表路由，不是路由注册表。带前缀的条目不能证明全部子路径或 HTTP 方法可用；必须同时匹配已启用的 Edge 规则与下游 Controller。

`EDGE_ROUTE_*` 关闭或无匹配时 fail-closed 404。路由停用不再自动回切 Express；发布回退按[运行手册](../运维/生产发布与灾备运行手册.md)处理。

## 2. HTTP 兼容行为

| 模式 | 保留行为 | 实现边界 |
|---|---|---|
| JSON | 既有 `{success:true,data}`、`{success:false,error}`、状态码、字段名与中文错误 | 不把所有新接口强行包装成同一旧 Envelope；Edge 认证失败也可能是空 Body 401 |
| URL 与 Query | 原始编码路径、重复参数、空参数与游标编码 | 避免 `%3A` 被二次编码为 `%253A`，由代理测试保护 |
| SVG | CAPTCHA 的原始 `image/svg+xml` | 不包装成 JSON |
| Cookie | `y1.sid`；多值 `Set-Cookie` 保持独立 Header | 属性、有效期和滚动行为由 Identity 会话配置决定；移动登录不发 Web 登录 Cookie |
| POST SSE | `text/event-stream`、`data: <JSON>\n\n`、原接口的 `[DONE]`、`X-Accel-Buffering: no` | 原样流式传输并传播取消；事件型 SSE 以自身协议为准，不能强加 `[DONE]` |
| Multipart | 字段、Boundary 与原始字节，例如图片字段 `images` | Edge 不重建 Body；Nginx 与业务端仍可执行请求/单文件限制，不能说整个链路“无限制” |
| Binary/Range | `Range`、`If-Range`，`200/206/416` 与下载 Header | 保留 `Content-Range`、`Content-Length`、`Content-Disposition`、`Accept-Ranges`、类型与缓存验证头 |
| RateLimit | `RateLimit-Limit`、`RateLimit-Remaining`、`RateLimit-Reset` | 一般保留下游值；Edge 已施加共享路由族额度时，保留 Edge 的同名限流头 |

代理不完整聚合 SSE/二进制 Body，不跟随上游重定向，也不自动重试非幂等写请求。具体文件数量/大小、Provider 能力和费用限制归对应业务契约，不在此复制成一份长期不变的数值表。

## 3. 代表路由矩阵

| 路由族 | Method / Path | 服务 | Body / 响应 | 必查约束 |
|---|---|---|---|---|
| 验证码 | `GET /api/auth/captcha` | Identity | SVG / Cookie | 创建或更新验证码状态，不作为普通无副作用 GET 重放 |
| 发码、注册、登录、退出 | `POST /api/auth/send-code`、`register`、`login`、`logout` | Identity | JSON / Cookie | 密码校验、验证码、登录限流和 Session；移动登录见认证方案 |
| 当前用户与设备 | `GET /api/auth/me`、`GET /api/me/devices`；`DELETE /api/me/devices/{id}` | Identity | JSON | 当前账号范围、实时角色、设备撤销；设备指纹不是硬件身份凭证 |
| 移动刷新/撤销 | `POST /api/auth/refresh`、`POST /api/auth/revoke` | Identity | Refresh Token / JSON | 方法级精确登记；刷新支持 Bearer，撤销要求 JSON Body |
| 跨应用免登 | `POST /api/auth/cross-app-tokens`、`POST /api/auth/cross-app-tokens/exchange` | Identity | JSON / 目标会话 | 一次性 Token、目标应用绑定和来源校验，不可重放核销 |
| 首页热点 | `GET /api/homepage/hot-items`、`GET /api/douyin/hot-items` | Intelligence | JSON | 聚合与限流；用户级旧热点设置已退役 |
| 视频提取与分析 | `POST /api/douyin/extract-video`、`analyze-video`；Bilibili 对应路径 | Intelligence | JSON | 签名媒体引用、调用资格、实际用量与取消 |
| 媒体代理/下载 | `GET /api/douyin/proxy/{token}`、`download/{token}`、`audio/{token}`；Bilibili 的 `proxy`、`download` | Intelligence | Binary/Range | 签名、TTL、Range、下载头；保留原 URL 编码 |
| 通用媒体 | `/api/media/**` | Intelligence | JSON / 上传 / 媒体读取 | 三步上传、对象归属与确认；方法以 Controller 为准 |
| 图片评价 | `POST /api/image-analysis/analyze`、`/step/draft`、`/step/optimize`、`/step/style-refine` | Intelligence | Multipart/JSON → SSE/JSON | 具体入参、上传限制与失败退款按端点核对 |
| 图片偏好与导出 | `GET/PUT /api/image-analysis/style-preferences`；`POST /api/image-analysis/export-feishu` | Intelligence | JSON | 用户范围、风格配置和飞书凭据 |
| 文章生成 | `POST /api/article-generation/titles`、`outline`、`content` | Intelligence | JSON → JSON/SSE | 平台模型/BYOK 能力路由，不再限定旧方案的单一 Qwen 模型 |
| 文章配图 | `POST /api/article-generation/image-recommendations`、`search-images`、`generate-image`；`GET /api/article-generation/generated-images/{id}` | Intelligence | JSON/Multipart / 媒体 | 生成、读取、归属与配额契约；不得把动态媒体 URL 当永久公开地址 |
| 朋友圈与脚本 | `POST /api/moments-generation/generate`、`POST /api/comedy-generation/generate-script` | Intelligence | JSON/Multipart → SSE | 保留既有帧和结束语义，遵守统一 AI 调用与积分策略 |
| 视频改编/生产 | `/api/video-recreation/**`、`/api/video-production/**` | Intelligence | JSON/Multipart / JSON/SSE/导出 | 任务幂等、候选选择、运行恢复、合成与导出版本 |
| 飞书设置 | `GET/PUT /api/settings/analysis` | Intelligence | JSON | 当前仅保留飞书导出设置；密钥的掩码、留空和清空含义见下文 |
| 语音转写 | `POST /api/speech/transcriptions`、`GET /api/speech/transcriptions/{id}` | Intelligence | JSON | Java 新接口，无旧 Express 契约；`speech_audio` 上传、owner 范围与 Sandbox/真实 Provider 区分 |
| 图文工作台与公众号草稿 | `/api/creation-studio/**`、`/api/creation-channels/wechat/**` | Intelligence | 版本化 JSON / 导出 | #101 新接口；Edge 默认关闭，还需服务侧写入开关与真实渠道验收 |
| 任务、消费、资金与争议 | `/api/tasks/**`、`/api/applications/**`、`/api/v2/**`、`/api/finance/**`、`/api/credits/**`、`/api/trust/**` | 各领域服务 | 按 Java 契约 | 不属于旧营销工具的 Express 契约；归属见 Edge 配置，业务规则见 HLD/ADR |

以上用同族短路径表示的单元格沿用该行首个完整前缀。完整映射以代码为准，不依据本表构造未经登记的路径。

## 4. 已退役或改变含义的旧契约

- `POST /api/settings/analysis/models`、`POST /api/settings/analysis/verify-model`：任务书 [#88](../任务书/草场任务书-88-旧分析设置模型链路退役.md) 已退役，返回 404。
- `GET/PUT /api/settings/homepage`：用户级热点设置已删除，热点配置在治理端统一管理。不要因 Edge 仍登记 `/api/settings` 前缀就认为该子路径存在。
- `GET/PUT /api/settings/analysis`：仍存在，但只维护飞书导出凭据；旧 `features` 模型配置不再输出，更新时忽略。
- 飞书密钥更新不能沿用“留空等于保留”的旧文字：缺省或掩码值表示保留，空字符串表示清空；具体以 [AnalysisSettingsService](../../platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/settings/AnalysisSettingsService.java) 与 Schema 校验为准。

模型接入使用平台模型/BYOK 控制面。正式退役的接口和历史含义变更，应同时修改调用方与契约测试，不能为兼容矩阵重新恢复旧后端。

## 5. Header 与信任边界

双向剥离 `Connection`、其点名 Header、`Keep-Alive`、`Proxy-Authenticate`、`Proxy-Authorization`、`TE`、`Trailer`、`Transfer-Encoding`、`Upgrade`；请求 `Host` 由固定上游替换。

上游只能来自服务端路由配置，客户端不能通过 Header 指定任意地址。`Forwarded/X-Forwarded-*` 的信任处理按 Nginx 与 Edge 边界过滤器执行；不能将 Hop-by-hop 清理误解为所有转发头都被同一函数删除。外部内部身份头必须剥离并重新签发。

## 6. 重试与生产事实核对

业务写入、生成、发验证码、一次性免登核销等请求不得由代理盲目重放。只有端点已定义稳定幂等键、请求体一致性和结果恢复时，调用方才能按该契约重试；GET 也不能只凭方法名推断无副作用。

发布时仍需核对实际 Cookie 属性、历史密码 Hash、媒体签名/TTL、Range 和供应商行为。测试仅使用合成账号、Cookie、Token 与媒体，不复制生产会话、密码 Hash、API Key 或真实签名 URL。当前密码验证器支持 bcrypt/Argon2id，不把迁移草案中的 scrypt 当作已实现能力。

## 7. 当前验证入口

| 范围 | 已存在的测试/实现 |
|---|---|
| JSON、状态码、Query、Cookie、Multipart、Range 与下载头 | [RoutingProxyContractTest](../../platform-java/services/edge-bff/src/test/java/com/grassland/edge/proxy/RoutingProxyContractTest.java) |
| Header 清理与 URL/响应处理 | [ProxyHeaderPolicyTest](../../platform-java/services/edge-bff/src/test/java/com/grassland/edge/proxy/ProxyHeaderPolicyTest.java)、[RoutingProxyHandlerTest](../../platform-java/services/edge-bff/src/test/java/com/grassland/edge/proxy/RoutingProxyHandlerTest.java) |
| 路由归属与 fail-closed | [JavaRouteManifestGateTest](../../platform-java/services/edge-bff/src/test/java/com/grassland/edge/proxy/JavaRouteManifestGateTest.java)、[RouteOwnershipContractTest](../../platform-java/services/edge-bff/src/test/java/com/grassland/edge/proxy/RouteOwnershipContractTest.java)、[EdgeFailClosedIT](../../platform-java/services/edge-bff/src/test/java/com/grassland/edge/proxy/EdgeFailClosedIT.java) |
| 认证、CSRF 与公网信任边界 | [AccessTokenFilterTest](../../platform-java/services/edge-bff/src/test/java/com/grassland/edge/internalassertion/AccessTokenFilterTest.java)、[EdgeCsrfOriginFilterTest](../../platform-java/services/edge-bff/src/test/java/com/grassland/edge/security/EdgeCsrfOriginFilterTest.java)、[PublicEdgeBoundaryFilterTest](../../platform-java/services/edge-bff/src/test/java/com/grassland/edge/security/PublicEdgeBoundaryFilterTest.java) |
| 具体生成帧、取消、计费和授权 | 对应领域服务的 `src/test/` 与[浏览器测试](../../tests/README.md)；不能仅靠代理夹具证明业务闭环 |

测试类名称描述当前入口，不代表本次文档整理已重新运行 Java 或浏览器测试。执行范围与前置条件统一查看[测试说明](../../tests/README.md)。
