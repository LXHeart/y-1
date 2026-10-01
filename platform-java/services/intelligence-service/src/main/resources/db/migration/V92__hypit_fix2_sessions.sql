-- V92__hypit_fix2_sessions.sql — 107-fix-2 C107F2-19（§7.1 D-11）。
-- Hypit Studio/Preview 持久会话表：owner/project/kind/run/revision/readOnly 绑定、
-- 绝对 TTL、撤销时间线与一次性票据 nonce 槽（char(64) sha256hex）。
-- 只加表/索引，无回填、无破坏性 DDL；旧应用可忽略（§7.3）。
CREATE TABLE IF NOT EXISTS hypit_session (
    id text PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES hypit_project(id),
    account_id text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('studio','preview')),
    run_file text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    read_only boolean NOT NULL,
    state text NOT NULL CHECK (state IN ('starting','active','failed','closed','expired','revoked')),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    ticket_nonce_hash char(64),
    ticket_expires_at timestamptz,
    ticket_redeemed_at timestamptz,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_hypit_session_owner_project
    ON hypit_session(account_id, project_id, state);
CREATE INDEX IF NOT EXISTS idx_hypit_session_expiry
    ON hypit_session(state, expires_at);
