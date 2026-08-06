-- GL-P2-ADMIN-004 凭证人工复核：人工改判记录表。
--
-- 设计：不改动 engagement_verification（自动核验聚合态，仍只由 runVerificationChecks 写）。
-- 本表承载人工改判记录——运营对 inconclusive 项做 passed/failed 人工判定。
-- 读端（confirm 闸门 / 结算阻断 / 运营队列）改为"有 override 则 override 优先，否则读自动结论"。
--
-- 一份交付物（submission_id）至多一条生效 override（UNIQUE）；驳回后可重判（upsert 覆盖）。
-- 附审计字段（reviewer / note / created_at），不改既有 engagement_verification 的任何列。

CREATE TABLE verification_override (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    submission_id   uuid NOT NULL REFERENCES engagement_submission(id) ON DELETE CASCADE,
    status          varchar(32) NOT NULL CHECK (status IN ('passed', 'failed')),
                    -- 人工改判结论：passed=人工确认通过 / failed=人工判定不通过（不含 inconclusive——
                    -- inconclusive 是自动核验的中间态，人工复核的目的是给出确定结论）
    reviewer_account_id uuid NOT NULL,
    review_note     text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- 一份交付物至多一条 override（重判用 upsert 覆盖）
CREATE UNIQUE INDEX uq_verification_override_submission ON verification_override(submission_id);
CREATE INDEX idx_verification_override_submission ON verification_override(submission_id);
