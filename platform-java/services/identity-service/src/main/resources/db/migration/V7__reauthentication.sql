-- 草场 identity-service：MFA 重认证（解 trust 客服终审 403 阻塞）。
--
-- 背景：trust 的 final-decision 要求断言 reauthenticatedAt 在 5 分钟内（HLD §11.2 客服覆盖判决须重新认证），
-- 但登录链路不产生该字段——edge-bff 签发断言时硬编码 null，导致客服终审恒 403。
--
-- 方案：重认证时间戳按 session 存（与活动身份同粒度，一个设备重认证不影响另一设备），
-- edge-bff 直读 identity_session 时一并取出，签进断言的 reauthenticatedAt / authStrength。

ALTER TABLE identity_session
    ADD COLUMN reauthenticated_at timestamptz,                          -- 最近一次重认证时刻；NULL=从未重认证
    ADD COLUMN auth_strength varchar(16) NOT NULL DEFAULT 'level1';     -- level1=普通登录 / level2=已重认证

-- 按重认证时刻查询（下游校验近期性），仅索引已重认证的行。
CREATE INDEX idx_identity_session_reauth
    ON identity_session(reauthenticated_at)
    WHERE reauthenticated_at IS NOT NULL;
