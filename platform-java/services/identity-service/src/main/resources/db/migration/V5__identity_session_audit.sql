-- 草场身份域：活动身份 per-session + 审计 + 多设备（HLD D-08 / 10.1「更新活动身份和审计」）。Epic 2 Slice 2I。
-- 取代 2G 的账号级 account_active_identity：活动身份改为按 session（设备/标签）隔离，多设备互不影响。
-- greenfield：account_active_identity 仅测试/dev 写过、无生产数据，直接 DROP；功能迁入 identity_session。

-- 按 session 隔离的活动身份（一个 sid 一行；多设备 = 多行）。active_identity_type 为 NULL = 该 session 为消费者。
-- 行在首次激活身份时懒创建；仅登录、未激活身份的 session 无行（默认消费者）。
CREATE TABLE identity_session (
    session_token text PRIMARY KEY,              -- cookie 里的 sid
    account_id uuid NOT NULL,
    active_identity_type varchar(32),            -- merchant / recommender / NULL(消费者)
    device_id varchar(64),                       -- 设备指纹（user-agent 哈希），多设备视图去重/标识
    device_label varchar(255),                   -- 客户端可选自报标签
    ip_address varchar(64),
    user_agent varchar(512),
    issued_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz                       -- 对齐 session cookie 生命周期，未来过期清理用
);

CREATE INDEX idx_identity_session_account ON identity_session(account_id);

-- 身份切换 append-only 审计（HLD 10.1「更新活动身份和审计」；风险矩阵「身份切换审计」）。
CREATE TABLE identity_audit_log (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    action varchar(32) NOT NULL,                 -- activate / deactivate / revoke_session
    from_identity_type varchar(32),
    to_identity_type varchar(32),
    session_token text,
    device_id varchar(64),
    ip_address varchar(64),
    user_agent varchar(512),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    detail json
);

CREATE INDEX idx_identity_audit_account_time ON identity_audit_log(account_id, occurred_at);

-- 删除账号级活动身份表（功能由 identity_session 取代，greenfield 无生产数据）。
DROP TABLE account_active_identity;
