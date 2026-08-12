# 移动端刷新 Token 认证方案设计

> **任务**: GL-P3-IDENTITY-001
> **状态**: 已实施（生产端侧集成与监控待部署）
> **版本**: v0.2

## 一、背景

当前草场平台的认证架构为 Web 设计，使用 Cookie-based Session：
- Web 用户通过 `edge-bff` → `identity-service` 登录
- Session 存储在 PostgreSQL `session` 表
- Cookie 签名兼容 Node.js（HMAC-SHA256 base64）

移动端（APP/小程序）需要不同的认证机制：
- 不支持 Cookie（或支持但不推荐）
- 需要长期有效的 token（避免频繁登录）
- 需要安全的 token 刷新机制

## 二、设计目标

1. **安全性**：Refresh Token 不应被滥用，需有撤销机制
2. **兼容性**：与现有 Session 架构共存，不影响 Web 用户
3. **可扩展**：支持未来多设备管理、设备指纹等
4. **简单性**：避免引入复杂的外部 OAuth provider 依赖

## 三、方案设计

### 3.1 Token 模型

| Token 类型 | 有效期 | 存储 | 用途 |
|------------|--------|------|------|
| Access Token | 15-60 分钟 | 内存（不落库） | API 认证 |
| Refresh Token | 30-90 天 | 数据库 | 刷新 Access Token |

### 3.2 数据模型

```sql
-- 扩展 identity-service 的 Flyway 迁移
CREATE TABLE refresh_token (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    token_hash text NOT NULL UNIQUE,  -- SHA-256(token)
    device_fingerprint text,           -- 设备指纹（可选）
    device_name text,                  -- 用户可读的设备名称，如"我的 iPhone"
    last_used_at timestamptz,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    metadata jsonb                      -- 扩展字段
);

CREATE INDEX idx_refresh_token_account ON refresh_token(account_id);
CREATE INDEX idx_refresh_token_hash ON refresh_token(token_hash);
CREATE INDEX idx_refresh_token_expires ON refresh_token(expires_at) WHERE revoked_at IS NULL;
```

### 3.3 Token 格式

Access Token 和 Refresh Token 使用 **紧凑的 HMAC-SHA256 token**（非 JWT）：
- 格式：`<payload>.<mac>`
- 与现有 Cookie 签名机制一致
- 避免引入新依赖

#### Access Token Payload (JSON, base64url):
```json
{
  "account_id": "uuid",
  "email": "user@example.com",
  "active_identity_type": "merchant|recommender|null",
  "role": "user|admin",
  "device_id": "hash",
  "iat": 1234567890,
  "exp": 1234567990
}
```

#### Refresh Token:
- 128 字节随机数（SecureRandom）
- 不包含用户信息（避免泄露时暴露更多）
- 数据库存储 SHA-256(token)

### 3.4 API 设计

#### 1. 登录（已有，返回 Refresh Token）

**POST** `/api/auth/login`

请求体（不变）：
```json
{
  "email": "user@example.com",
  "password": "password"
}
```

响应（扩展）：
```json
{
  "success": true,
  "data": {
    "user": { ... },
    "tokens": {
      "access_token": "<payload>.<mac>",
      "refresh_token": "128字节随机数",
      "expires_in": 900  // 15分钟（秒）
    }
  }
}
```

**行为变化**：
- 当请求头 `X-Device-Info` 存在时（移动端标识），返回 `tokens` 字段
- Web 端（Cookie 模式）不返回 `tokens`，继续使用 Set-Cookie

#### 2. 刷新 Token

**POST** `/api/auth/refresh`

请求头：
```
Authorization: Bearer <refresh_token>
```

或请求体：
```json
{
  "refresh_token": "..."
}
```

响应：
```json
{
  "success": true,
  "data": {
    "access_token": "<new_payload>.<mac>",
    "expires_in": 900
  }
}
```

**行为**：
- 验证 Refresh Token（查库、未撤销、未过期）
- 更新 `last_used_at`
- 重新颁发 Access Token
- Refresh Token 不轮换（简化设计，可选后续增强）

错误：
- 401：无效/过期/已撤销的 Refresh Token
- 429：刷新过于频繁（rate limit）

#### 3. 撤销 Token（可选）

**POST** `/api/auth/revoke`

请求体：
```json
{
  "refresh_token": "...",
  "all_devices": false  // true=撤销该用户所有设备
}
```

响应：
```json
{
  "success": true
}
```

### 3.5 Access Token 验证

#### edge-bff 签发内部断言

当前 `InternalAssertionFilter` 从 Cookie session 签发 `X-Grassland-Identity`。

扩展：当请求头 `Authorization: Bearer <access_token>` 时：
1. 验证 Access Token 签名和过期
2. 解析 payload
3. 签发内部断言（`X-Grassland-Identity`）
4. 下游服务无需改动（继续消费断言）

#### 鉴权流程

```
移动端请求 → edge-bff
             ↓
          验证 Access Token
             ↓
          签发内部断言 → downstream services
```

### 3.6 设备管理

#### 设备指纹

- 头部 `X-Device-Fingerprint`：SHA-256(User-Agent + 设备标识符)
- 头部 `X-Device-Name`：用户可读的设备名（如"我的 iPhone 15"）
- 存储在 `refresh_token.device_fingerprint/device_name`

#### 列出设备

**GET** `/api/me/devices`

响应：
```json
{
  "success": true,
  "data": {
    "devices": [
      {
        "id": "...",
        "device_name": "我的 iPhone",
        "last_used_at": "2026-08-03T10:00:00Z",
        "expires_at": "2026-10-03T10:00:00Z"
      }
    ]
  }
}
```

#### 撤销设备

**DELETE** `/api/me/devices/{id}`

### 3.7 安全考虑

| 风险 | 缓解措施 |
|------|----------|
| Refresh Token 泄露 | 存储 SHA-256 而非明文；支持撤销 |
| 重放攻击 | Access Token 短期（15分钟）；审计日志 |
| 中间人攻击 | 强制 HTTPS；生产环境 edge-bff 配置 TLS |
| 设备被盗 | 用户可远程撤销设备 |
| 暴力破解 | Rate limit；token 足够长（128字节） |

## 四、实施计划

### Phase 1：数据模型 + 登录扩展
1. Flyway 迁移：`refresh_token` 表
2. `RefreshTokenRepository` + `RefreshTokenService`
3. `LoginController` 扩展：检测 `X-Device-Info` 时返回 tokens
4. `AccessTokenCodec`：编码/解码 Access Token

### Phase 2：刷新端点 + edge-bff 验证
1. ✅ `RefreshTokenController`：`/api/auth/refresh`
2. ✅ edge-bff `AccessTokenFilter`：在公网边界验证 Bearer token，复查撤销状态，并由 `InternalAssertionFilter` 签发目标服务断言
3. ✅ 安全边界：原始 access token 不向 Java/legacy 上游扩散；refresh/revoke 原样透传；非法、撤销或非 Bearer 凭据直接 401，不回退 Cookie
4. ✅ 测试：有效 token、撤销 token、Cookie 混用、legacy 路由和 refresh/revoke 透传均有回归覆盖

### Phase 3：设备管理 + 撤销
1. `DeviceManagementController`：列出/撤销设备
2. `DELETE /api/auth/revoke` 端点
3. 前端/移动端集成

### Phase 4：清理与监控
1. 清理过期 Refresh Token 的定时任务
2. 审计日志：登录/刷新/撤销事件
3. 监控：异常刷新频率检测

## 五、与现有架构的关系

| 组件 | Web (Cookie) | 移动 (Token) |
|------|--------------|--------------|
| 身份权威 | `identity-service` | `identity-service` |
| Session/Token 存储 | `session` 表 | `refresh_token` 表 |
| edge-bff 断言 | 从 Cookie 签发 | 从 Access Token 签发 |
| 下游服务 | 消费断言（无改动） | 消费断言（无改动） |

**关键**：下游服务（marketplace/finance/trust）无需改动，继续从 `X-Grassland-Identity` 获取用户信息。

## 六、生产侧剩余项

1. APP/小程序安全存储、退出登录与多设备撤销的端侧 E2E。
2. 异常刷新频率、撤销失败和 access-token 401 比例的生产告警。
3. 在真实 Secret Manager 中执行 access-token 双钥轮换与回退演练。

## 七、参考

- HLD v0.1 第 7.4 节：内部身份断言
- HLD v0.1 第 11.1 节：服务身份与鉴权
- OWASP OAuth 2.0 Security Best Current Practice
