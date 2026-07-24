-- 草场 marketplace-service 第二个 schema（Epic 4 Slice 4B：报名 + 名额）。
-- 纯增量，安全跑在已部署 V1 的 neon（marketplace_flyway_schema now at v2）。
-- 仍与 identity 共用 neon public schema，表名 task_application 不冲突。

-- 任务名额上限（可空，NULL=不限名额）。不可 NOT NULL 无默认，否则 populated task 表迁移失败。
ALTER TABLE task ADD COLUMN max_slots int;

-- 推荐官报名（application 聚合，HLD 5.3）。
CREATE TABLE task_application (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES task(id),          -- 同库（marketplace 自有）→ 真 FK；级联随任务删除
    recommender_account_id uuid NOT NULL,               -- 跨服务 account ref（identity 域），database-per-service 不加 FK
    status varchar(32) NOT NULL DEFAULT 'pending',      -- pending/accepted/rejected/withdrawn
    note text,                                           -- 报名附言
    reviewed_by_account_id uuid,                        -- accept/reject 的操作商家（caller），withdraw 时为 null
    decided_at timestamptz,                             -- accept/reject 时间
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- 一人一任务一报名；withdrawn 为终态，刻意阻断重报（MVP；将来需重报改 partial unique index）
    UNIQUE(task_id, recommender_account_id)
);
CREATE INDEX idx_task_application_task ON task_application(task_id);
