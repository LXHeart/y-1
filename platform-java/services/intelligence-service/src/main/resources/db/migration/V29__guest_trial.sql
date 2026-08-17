-- 草场 intelligence V29：游客有限体验轻量表（任务书 #36 / ADR-D14）。
--
-- 刻意轻量且不进 finance/ai_run：游客试用是平台赞助的营销动作，不建虚拟账号、不写 credits 流水、
-- 不产生对账噪音（R4 商业化边界）；审计只存 SHA-256 截断 IP 哈希，无任何个人数据（R8）。

CREATE TABLE guest_trial_quota (
    gtid uuid NOT NULL,                        -- 匿名身份（httpOnly 随机 cookie 的随机 UUID）
    capability varchar(32) NOT NULL,           -- 白名单能力名（article-titles / content-score / image-review）
    day date NOT NULL,                         -- 日界（北京时间）
    used int NOT NULL DEFAULT 0,
    PRIMARY KEY (gtid, capability, day)
);

CREATE TABLE guest_trial_run (
    id uuid PRIMARY KEY,
    gtid uuid NOT NULL,
    capability varchar(32) NOT NULL,
    ip_hash varchar(16) NOT NULL,              -- SHA-256 截断哈希；不存原始 IP/UA（R8）
    outcome varchar(32) NOT NULL,              -- success / quota_exhausted / rate_limited / provider_error
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_guest_trial_run_created ON guest_trial_run(created_at DESC);
