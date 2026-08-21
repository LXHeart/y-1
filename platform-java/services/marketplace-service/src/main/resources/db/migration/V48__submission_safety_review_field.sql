-- 草场 marketplace V48：履约提交硬门槛（ADR-D16 D6 登记项落地）。
-- 复核队列从「每提交一行（仅评论）」泛化为「每提交×字段一行」：评论(commentText)与备注(note)的
-- 词库 advisory 命中各自留痕，运营复核时能看到命中的是哪个字段、看到对应原文。
-- 既有行经 DEFAULT 回填 field='comment'，语义不变（V47 之前只有评论会落行）。
ALTER TABLE comment_safety_review
    ADD COLUMN IF NOT EXISTS field varchar(16) NOT NULL DEFAULT 'comment'
    CHECK (field IN ('comment', 'note'));

-- submission_id 单列唯一 → (submission_id, field) 复合唯一（同一提交的两个字段各留一行）。
ALTER TABLE comment_safety_review
    DROP CONSTRAINT IF EXISTS comment_safety_review_submission_id_key;

CREATE UNIQUE INDEX IF NOT EXISTS uq_comment_safety_review_submission_field
    ON comment_safety_review (submission_id, field);
