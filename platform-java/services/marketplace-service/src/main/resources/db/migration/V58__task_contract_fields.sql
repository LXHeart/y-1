-- 任务书 #96 C96-04（§7 V58）：发布合同字段——发布前审稿 / 交付期限天数 / 取消条款模板。
-- 全部 IF NOT EXISTS（迁移重放红线）。

ALTER TABLE task ADD COLUMN IF NOT EXISTS review_required boolean NOT NULL DEFAULT false;
ALTER TABLE task ADD COLUMN IF NOT EXISTS delivery_deadline_days integer;
ALTER TABLE task ADD COLUMN IF NOT EXISTS cancel_policy jsonb;

-- 草稿送审（§6 /submissions/draft）复用 engagement_submission 存储（content_url 允许空串=无公开链接，
-- TC96-015）；submission_kind 区分草稿与发布凭证，默认 published 兼容存量行（V5 行为零迁移改写）。
ALTER TABLE engagement_submission ADD COLUMN IF NOT EXISTS submission_kind varchar(32) NOT NULL DEFAULT 'published';

CREATE INDEX IF NOT EXISTS idx_submission_draft_review_scan
    ON engagement_submission(created_at)
    WHERE submission_kind = 'draft' AND status = 'submitted';
