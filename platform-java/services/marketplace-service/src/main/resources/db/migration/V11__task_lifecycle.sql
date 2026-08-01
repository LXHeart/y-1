-- 草场 marketplace V11：任务生命周期（GL-P1-TASK-001 Stage 1）。
--
-- 背景：V1 落的是「创建即 published、单版本、可变行」的 task；无 draft/版本/deadline/关闭/取消，
-- 也没有发布时的不可变要求快照。Stage 1 补齐生命周期后端契约：
--   * version 做乐观锁计数器（每次状态迁移 +1；draft 编辑也 +1）；
--   * application_deadline 仅建模 PRD「指定时间」截止（apply 时判，不动既有 accept/confirm/结算）；
--   * published_at 标记进入 published 的时刻，发布配额/月度统计改按它计（draft 创建不占发布额度）；
--   * cancelled_at 标记取消；
--   * task_version 不可变快照表：publish 时落一行当前要求快照（HLD §5.3「不可变任务版本」）。
--
-- 数据库纪律（镜像 V2/V3 增量模式）：
--   * 纯增量列 + 新表，安全跑在已部署 V10 的库（marketplace_flyway_schema now at v11）。
--   * backfill 既有 published/closed 行：published_at 取 created_at（它们创建即发布），
--     并为每条写一行 version=1 快照（published_by 取 owner）。
--   * 不触 marketplace_outbox（迁移绝不伪造事件；既有发布事件早该已发，不补发）。
--   * close/cancel 只改 status + 时间戳 + version，apply 侧的门控在 ApplicationController。

ALTER TABLE task
    ADD COLUMN version int NOT NULL DEFAULT 1,
    ADD COLUMN application_deadline timestamptz,
    ADD COLUMN published_at timestamptz,
    ADD COLUMN cancelled_at timestamptz;

-- 既有 published/closed 行的发布时刻取创建时刻（创建即发布模型下二者同值）。
UPDATE task SET published_at = created_at
 WHERE published_at IS NULL AND status IN ('published', 'closed');

CREATE TABLE task_version (
    task_id uuid NOT NULL REFERENCES task(id),
    version int NOT NULL,
    title text NOT NULL,
    description text,
    content_form varchar(32),
    platform varchar(32),
    max_slots int,
    bounty_cents bigint,
    application_deadline timestamptz,
    requirements jsonb NOT NULL DEFAULT '{}'::jsonb,
    published_at timestamptz NOT NULL DEFAULT now(),
    published_by uuid,
    PRIMARY KEY (task_id, version)
);

-- 为既有 published/closed 任务补 version=1 不可变快照（published_by 取 owner）。
-- requirements 留空对象：历史任务无结构化要求录入，不强造。
INSERT INTO task_version (task_id, version, title, description, content_form, platform,
                          max_slots, bounty_cents, application_deadline, published_at, published_by)
SELECT id, 1, title, description, content_form, platform, max_slots, bounty_cents, NULL,
       COALESCE(published_at, created_at), owner_account_id
  FROM task
 WHERE status IN ('published', 'closed');

-- 月度发布额度按 published_at 计；给个轻量索引避免全表扫。
CREATE INDEX idx_task_published_at ON task(published_at);
