# 移动端刷新 Token 认证方案与当前契约

> 任务：GL-P3-IDENTITY-001。文档校准：2026-09-15，v0.3（修正文档，不改变协议版本）。
> 状态：服务端已实现；原生 APP/小程序的安全存储、端侧 E2E、生产监控和密钥轮换演练另行验收。

## 一、适用范围

Web 使用 `y1.sid` Cookie，Session 保存在 PostgreSQL `session` 表；移动端可选择 Access Token + Refresh Token，二者共用 Identity 账号、活动身份和资源授权。这是自有 HMAC 协议，不是 OAuth/OIDC，也不是 JWT。

跨用户端与 AI 应用的一次性免登属于另一套协议，见[架构详解](项目架构详解.md#12-前端与创作工作区)，不能用这里的 Refresh Token 代替。

## 二、认证链路

```text
移动端登录（非空 X-Device-Info）
  → edge-bff → identity-service
  → refresh_token 行 + Access Token（不新建 Web Session，不发登录 Cookie）

普通 API 请求（Authorization: Bearer <access_token>）
  → Edge 验签及时间检查
  → 复查 refresh_token 未撤销/未过期，重新读取账号/角色/活动身份
  → 移除原始 Authorization，签发目标服务内部断言
  → 领域服务执行资源授权
```

`AccessTokenFilter` 仅对精确的 `POST /api/auth/refresh`、`POST /api/auth/revoke` 生命周期路由保留原始凭据。其他已登记 Java 路由出现无效、撤销或非 Bearer 授权时返回 401，不回退 Cookie。内部断言的 Redis 防重放不表示同一 Access Token 只能使用一次。

## 三、协议与接口

### 3.1 Token 模型与默认值

| 项目 | 当前实现 |
|---|---|
| Access Token | HMAC-SHA256 签名，默认有效 900 秒，时间容差默认 5 秒；Token 本身不落库 |
| Refresh Token | 128 字节安全随机数，经 base64url 无 padding 编码；默认有效 30 天 |
| 服务端保存 | `refresh_token` 只保存 Refresh Token 的 SHA-256 小写 hex，不保存明文 |
| 刷新策略 | 不轮换 Refresh Token，不延长其到期时间；更新 `last_used_at` 并签发新的 Access Token |
| 设备上限 | 每账号默认最多 10 个活跃 Refresh Token，超限撤销最旧项 |
| 活动身份 | `identity_session.session_token` 使用 Refresh Token 行 ID，按移动登录会话隔离 |

TTL 与设备上限可配置，不把早期“15–60 分钟/30–90 天”的建议范围当作运行默认值。

### 3.2 数据模型

数据库定义以 [V20__refresh_token.sql](../../platform-java/services/identity-service/src/main/resources/db/migration/V20__refresh_token.sql) 为准，不在文档中复制一份可能漂移的建表 SQL。

主要字段为 `id`、`account_id`、`token_hash`、`device_fingerprint`、`device_name`、`last_used_at`、`expires_at`、`revoked_at`、`created_at`、`metadata`。撤销先标记 `revoked_at`，历史行由可配置清理任务删除；业务代码见 [RefreshTokenService](../../platform-java/services/identity-service/src/main/java/com/grassland/identity/mobile/RefreshTokenService.java)。

### 3.3 Access Token 格式

格式为 `<payloadB64url>.<macB64url>`，两个部分均无 padding。当前 Payload 示例：

```json
{
  "account_id": "account-uuid",
  "email": "user@example.com",
  "role": "user",
  "device_id": "user-agent-hash",
  "session_token": "refresh-token-row-uuid",
  "kid": "access-token-v1",
  "iat": 1789430400,
  "exp": 1789431300
}
```

`session_token` 是数据库行 ID，不是 Refresh Token 明文。Payload 刻意不携带 `active_identity_type`，避免会话状态调整后失真；Edge 从数据库获取当前活动身份与后台角色。客户端应将 Token 视作不透明凭据，不能把解码出的角色当作授权结果。当前产品的身份初始化与重新登录规则见 [HLD §10.1](草场系统技术总体设计（HLD-v0.1）.md#101-注册登录与活动身份)，Token 机制本身不构成自助切换身份的入口。

字段与编码以 [AccessToken](../../platform-java/platform-identity-assertion/src/main/java/com/grassland/identity/assertion/token/AccessToken.java) 和 [AccessTokenCodec](../../platform-java/platform-identity-assertion/src/main/java/com/grassland/identity/assertion/token/AccessTokenCodec.java) 为准。Refresh Token 只是随机字符串，不使用此 Payload/签名结构。

### 3.4 登录、刷新与撤销

| 方法与路径 | 请求 | 成功响应要点 | 失败边界 |
|---|---|---|---|
| `POST /api/auth/login` | 既有账号/密码 Body；非空 `X-Device-Info` 选择移动模式 | `data.user` 与 `data.tokens`；移动模式不发 `Set-Cookie` | 既有登录校验/限流；移动密钥未配置时 503 |
| `POST /api/auth/refresh` | Bearer Refresh Token，或 JSON `refresh_token`；两者都有时优先 Bearer | `data.access_token`、`data.expires_in`；不返回新 Refresh Token | 无效/过期/撤销 401，限流 429，未启用 503 |
| `POST /api/auth/revoke` | JSON `refresh_token`、可选 `all_devices` | `data.revoked` 表示本次撤销数量 | 无效/过期/撤销 401，未启用 503 |

登录返回的 Token 部分如下，`data.user` 沿用现有登录响应，此处省略：

```json
{
  "success": true,
  "data": {
    "tokens": {
      "access_token": "<payloadB64url>.<macB64url>",
      "refresh_token": "<opaque-random-token>",
      "expires_in": 900
    }
  }
}
```

刷新响应：

```json
{
  "success": true,
  "data": {
    "access_token": "<new-payloadB64url>.<new-macB64url>",
    "expires_in": 900
  }
}
```

撤销必须使用 `POST` 和 JSON Body，不能沿用早期实施清单中的 `DELETE /api/auth/revoke`，也不能只给 Authorization 头：

```json
{
  "refresh_token": "<opaque-random-token>",
  "all_devices": false
}
```

`all_devices=true` 撤销该账号的全部活跃移动 Refresh Token，不等于撤销所有 Web Cookie 会话。各设备下次经过 Edge 时会因凭据行已撤销而被拒绝，无须等待 Access Token 自然过期。移动端退出应调用撤销端点；Web 退出仍走既有 `/api/auth/logout`。

实现分别见 [LoginController](../../platform-java/services/identity-service/src/main/java/com/grassland/identity/auth/LoginController.java)、[RefreshController](../../platform-java/services/identity-service/src/main/java/com/grassland/identity/mobile/RefreshController.java)、[RevokeController](../../platform-java/services/identity-service/src/main/java/com/grassland/identity/mobile/RevokeController.java)。

### 3.5 Edge 验证与实时授权

[AccessTokenIdentityResolver](../../platform-java/services/edge-bff/src/main/java/com/grassland/edge/internalassertion/AccessTokenIdentityResolver.java) 先验证签名与时间，再检查 Refresh Token 行存活和账号状态，读取 `identity_session`、`backend_role`、组织权限与首次改密标记。角色和活动身份变化不需要等待 Token 更新。

这依赖当前 Edge 的身份库只读查询；完整范围与隔离限制见[架构详解 §6.2](项目架构详解.md#62-edge-的身份库只读例外)。下游消费内部断言后仍负责资源级权限，不能把 Edge 鉴权成功当作跨组织授权。

### 3.6 设备管理

- 设备 ID 当前由 `SHA-256(User-Agent)` 的前 16 个 hex 字符派生；没有读取 `X-Device-Fingerprint`。相同 User-Agent 可得到相同指纹，不能把它当成硬件身份凭证。
- 设备名称优先 `X-Device-Name`，为空时回落 `X-Device-Label`。
- 每次移动登录对应一行 Refresh Token；列出和撤销时使用该行 `id`，不靠指纹区分登录实例。

| 方法与路径 | 行为 |
|---|---|
| `GET /api/me/devices` | 返回当前账号活跃移动设备，包含 `id`、名称、指纹、创建/使用/到期时间与 `current` |
| `DELETE /api/me/devices/{id}` | 撤销指定设备并清理对应活动身份；不存在 404，跨账号 403，成功返回 `success:true` |

设备接口允许已认证的 Cookie 或 Access Token 请求；Cookie 请求的 `current` 恒为 false。Web 会话列表是另一个 `/api/me/sessions` 接口。设备请求与字段见 [DeviceController](../../platform-java/services/identity-service/src/main/java/com/grassland/identity/mobile/DeviceController.java)，指纹规则见 [DeviceFingerprint](../../platform-java/services/identity-service/src/main/java/com/grassland/identity/identityprofile/DeviceFingerprint.java)。

## 四、配置、清理与审计

| 配置 | 默认值/关系 |
|---|---|
| `IDENTITY_ACCESS_TOKEN_SECRET` / `EDGE_ACCESS_TOKEN_SECRET` | Identity 签发与 Edge 验签使用匹配密钥；空值不启用移动认证 |
| `IDENTITY_ACCESS_TOKEN_KID` / `EDGE_ACCESS_TOKEN_KID` | 默认 `access-token-v1`，当前签发键与验签键标识保持一致 |
| `EDGE_ACCESS_TOKEN_PREVIOUS_KEYS` | 轮换期间保留的验签键列表，格式 `kid=secret,kid2=secret2` |
| `IDENTITY_ACCESS_TOKEN_TTL_SECONDS` | 900 |
| `IDENTITY_ACCESS_TOKEN_LEEWAY_SECONDS` / `EDGE_ACCESS_TOKEN_LEEWAY_SECONDS` | 5 |
| `IDENTITY_REFRESH_TOKEN_TTL_DAYS` | 30 |
| `IDENTITY_REFRESH_TOKEN_MAX_ACTIVE` | 10 |
| `IDENTITY_REFRESH_TOKEN_CLEANUP_ENABLED` | 服务默认 false；Compose 默认 true |
| `IDENTITY_REFRESH_TOKEN_CLEANUP_INTERVAL_MS` | 3600000 |
| `IDENTITY_REFRESH_TOKEN_CLEANUP_RETENTION_DAYS` | 7；清理超期的已撤销/过期历史行，不删除有效活跃行 |

配置来源为 [Identity application.yml](../../platform-java/services/identity-service/src/main/resources/application.yml)、[Edge application.yml](../../platform-java/services/edge-bff/src/main/resources/application.yml) 与 [Compose](../../docker-compose.yml)。密钥必须使用安全配置，不把示例值当成真实凭据。

[RefreshRateLimiter](../../platform-java/services/identity-service/src/main/java/com/grassland/identity/mobile/RefreshRateLimiter.java) 是进程内固定窗口：默认 60 秒、IP 上限 20、Token/IP 上限 10，成功刷新不消耗失败预算。多副本不会共享该计数，不能宣称已提供全局分布式刷新限流。

撤销与设备撤销分别记录 `token_revoke`、`device_revoke` 审计；不要把早期“登录/每次刷新均记独立审计”的计划视为现状。清理实现见 [RefreshTokenCleanup](../../platform-java/services/identity-service/src/main/java/com/grassland/identity/mobile/RefreshTokenCleanup.java)。

## 五、已有验证入口与剩余范围

| 范围 | 现有测试入口 |
|---|---|
| 移动登录、刷新、撤销 | [MobileAuthIT](../../platform-java/services/identity-service/src/test/java/com/grassland/identity/mobile/MobileAuthIT.java) |
| 未配置密钥、设备上限、清理 | [MobileSecretUnsetIT](../../platform-java/services/identity-service/src/test/java/com/grassland/identity/mobile/MobileSecretUnsetIT.java)、[RefreshTokenCapIT](../../platform-java/services/identity-service/src/test/java/com/grassland/identity/mobile/RefreshTokenCapIT.java)、[RefreshTokenCleanupIT](../../platform-java/services/identity-service/src/test/java/com/grassland/identity/mobile/RefreshTokenCleanupIT.java) |
| 设备范围与撤销 | [DeviceControllerIT](../../platform-java/services/identity-service/src/test/java/com/grassland/identity/mobile/DeviceControllerIT.java) |
| Edge 凭据消费与 Cookie 混用 | [AccessTokenFilterTest](../../platform-java/services/edge-bff/src/test/java/com/grassland/edge/internalassertion/AccessTokenFilterTest.java) |
| Token 格式和签名 | [AccessTokenSignerTest](../../platform-java/platform-identity-assertion/src/test/java/com/grassland/identity/assertion/token/AccessTokenSignerTest.java) |

这是已存在的测试定位，不是本次文档整理重新执行的测试报告。执行前置条件见[测试说明](../../tests/README.md)。

剩余范围：APP/小程序安全存储、退出与多设备撤销 E2E；生产异常刷新、撤销失败与 401 比例监控；Secret Manager 中的密钥轮换及回退演练。OAuth/OIDC、Refresh Token Family 轮换是后续设计选项，当前协议并未实现这些能力。
