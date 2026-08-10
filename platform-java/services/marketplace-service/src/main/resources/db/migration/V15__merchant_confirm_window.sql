-- D-03 商家确认窗口（core slice）：推荐官提交履约后，给商家一个确认窗口（概念 3 天）。
-- 到期商家未操作 → 自动确认结算（default-approve，保护推荐官，HLD §18 资金不悬置）。
--
-- merchant_confirm_deadline_at：提交时设 = now + 窗口（controller 读 marketplace.confirmation.window-seconds 传入）。
-- null = 未进窗口。供 GET .../confirmation 轮询与 UI 倒计时；真正到期由 Temporal
-- ConfirmationWindowWorkflow 的 Timer 驱动，此列是估算展示值，不作判定依据（同审判窗口 deadline）。
--
-- auto_confirmed_at：仅 D-03 窗口到期路径写入；商家手动确认只写 confirmed_at。Temporal activity 重试据此区分：
-- auto_confirmed_at 非空 = 本 workflow 已取得自动确认权，可继续幂等 capture；仅 confirmed_at 非空 = 商家先确认，本 workflow abort。
--
-- 用 IF NOT EXISTS：生产顺序迁移（V1→V15）列不存在时正常新增，也允许修复环境在重放前已补列。
ALTER TABLE task_application
    ADD COLUMN IF NOT EXISTS merchant_confirm_deadline_at timestamptz,
    ADD COLUMN IF NOT EXISTS auto_confirmed_at timestamptz;

-- DB 提交与 Temporal start 不是同一事务。submission 级启动标记供 ConfirmationWindowDispatcher 扫描补启：
-- 只有 workflow start 成功或 AlreadyStarted 才写；进程在 DB commit 后、Temporal start 前崩溃时，下轮扫描不会丢窗口。
ALTER TABLE engagement_submission
    ADD COLUMN IF NOT EXISTS confirmation_workflow_started_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_submission_confirmation_dispatch
    ON engagement_submission(created_at)
    WHERE status = 'submitted' AND confirmation_workflow_started_at IS NULL;
