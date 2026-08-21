-- 组织邀请二次提醒（任务书 #41 尾巴）：reminder_sent_at 是扫描器的幂等 claim 标记——
-- 条件 UPDATE 置位与 outbox 事件同事务，重复扫描天然免重放；NULL = 从未提醒。
ALTER TABLE organization_invitation ADD COLUMN IF NOT EXISTS reminder_sent_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_org_invitation_reminder
    ON organization_invitation (created_at)
    WHERE status = 'pending' AND reminder_sent_at IS NULL;
