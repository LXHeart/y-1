-- 移动端 refresh token（GL-P3-IDENTITY-001，docs/移动端刷新token认证方案设计.md）。
-- DB 只存 SHA-256(token)（小写 hex）；明文 token 仅出现在登录/刷新往返中。
-- 撤销 = revoked_at 置位（软删）；硬删由 RefreshTokenCleanup 按 retention 执行。
--
-- account_id 不加 FK：Flyway 在 DataSourceConfig bean 初始化时执行，早于测试基座手建 legacy
-- app_users（生产也要求 legacy 表先建）；对齐 identity_session（V5）无 FK 惯例。
-- token_hash 的 UNIQUE 约束自带唯一索引，不再单建 hash 索引（设计文档 v0.1 的冗余索引已去掉）。
CREATE TABLE refresh_token (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL,
    token_hash text NOT NULL UNIQUE,
    device_fingerprint text,
    device_name text,
    last_used_at timestamptz,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    metadata jsonb
);

CREATE INDEX idx_refresh_token_account ON refresh_token(account_id);
CREATE INDEX idx_refresh_token_expires ON refresh_token(expires_at) WHERE revoked_at IS NULL;
