-- GL-P2-ADMIN-003 任务内容审核（全审政策）。
--
-- 全审政策：所有任务发布后进入 pending_review 审态，内容审核员审核通过后才对推荐官可见（published）。
-- task 表的 status 不加 CHECK（保持向后兼容，靠应用层 TaskStatus 枚举守值域）。
-- 本迁移建 task_review 审计表（审核决定流水），与 ops_case_audit / verification_override 同口径。

CREATE TABLE task_review (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id         uuid NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    action          varchar(32) NOT NULL CHECK (action IN ('submitted', 'approved', 'rejected')),
    reviewer_account_id uuid,                            -- submitted 时为 null（系统/商家动作）
    review_note     text,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_task_review_task ON task_review(task_id, created_at);
CREATE INDEX idx_task_review_action ON task_review(action, created_at);
