-- 草场 marketplace V47：评论人工复核队列（缺口清偿之九遗留清偿）。
-- 评论提交时词库 low/medium 命中（advisory，不拦截）此前两端都无留痕——本表承接：
-- 提交链路把 advisory 明细快照落一行 open，运营在处置台复核（confirmed=无问题 / violation=违规）。
-- 设计对齐 verification_override（V18）：不改自动词库检查真相，人工结论独立成表；
-- violation 经交付物列表 commentFlagged 透出给商家（平台标记，接不接受仍是商家的 confirm/reject）。
CREATE TABLE IF NOT EXISTS comment_safety_review (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id   uuid NOT NULL UNIQUE REFERENCES engagement_submission(id) ON DELETE CASCADE,
    comment_text    varchar(500) NOT NULL,
    findings        jsonb NOT NULL DEFAULT '[]'::jsonb,   -- 词库 advisory 明细快照（category/severity/advice）
    lexicon_version varchar(64),
    status          varchar(16) NOT NULL DEFAULT 'open'
                    CHECK (status IN ('open', 'confirmed', 'violation')),
    reviewer_account_id varchar(64),
    review_note     varchar(500),
    reviewed_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_comment_safety_review_status
    ON comment_safety_review (status, created_at DESC);
