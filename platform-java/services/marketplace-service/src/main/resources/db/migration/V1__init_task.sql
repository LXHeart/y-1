-- 草场 marketplace-service 首个 schema（Epic 4 Slice 4A）。
-- 独立 Flyway 历史：marketplace 用 spring.flyway.table=marketplace_flyway_schema（identity 用默认 flyway_schema_history）。
-- 与 identity 共用 neon 集群的 public schema，表名不冲突（task/outbox vs organization/session 等）。

-- 推广任务（task-catalog MVP）。
CREATE TABLE task (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,        -- 发布者 = 断言 caller（merchant）
    organization_id uuid NOT NULL,         -- 所属 org（发布者声明；跨服务归属校验留 4B+）
    title text NOT NULL,
    description text,
    status varchar(32) NOT NULL DEFAULT 'published',  -- draft/published/closed（MVP 创建即 published）
    content_form varchar(32),              -- video/image/article（HLD 内容形式）
    platform varchar(32),                  -- douyin/xiaohongshu/...（HLD 平台）
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_task_org ON task(organization_id);
CREATE INDEX idx_task_owner ON task(owner_account_id);
CREATE INDEX idx_task_status ON task(status);

-- outbox（复刻 identity 2A 精简版：本 slice 仅写表，Kafka 发布器留 4B）。
-- 表名 marketplace_outbox 避免与 identity 的 outbox（同 neon public schema）冲突。
CREATE TABLE marketplace_outbox (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id text NOT NULL UNIQUE,
    event_type text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    payload json NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
