-- D-03 F6：contest 与手动/自动确认共用 task_application 本地门闩。
-- requested_at 是 durable intent：一旦落库，Timer/confirm 的 guarded update 均不得再取得确认权；
-- trust/Temporal 出站失败由派发器按该状态恢复，不在数据库事务内跨服务调用。
ALTER TABLE task_application
    ADD COLUMN IF NOT EXISTS contest_requested_at timestamptz,
    ADD COLUMN IF NOT EXISTS rejection_workflow_started_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_task_application_contest_dispatch
    ON task_application(contest_requested_at)
    WHERE contest_requested_at IS NOT NULL
      AND (merchant_rejected_at IS NULL OR rejection_workflow_started_at IS NULL);
