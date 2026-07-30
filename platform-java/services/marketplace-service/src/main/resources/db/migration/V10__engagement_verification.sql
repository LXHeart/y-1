-- 草场 marketplace V10：履约核验（Verification v1）。
--
-- 背景：V5 落了交付物本身（content_url 发布链接 + 说明 + submitted/accepted/rejected），
-- V9 落了附件媒体证据，但「商家核验」一直无记录——confirm 是凭空点的
--（ApplicationController.confirm 注释自承「此前 confirm 是凭空点的」）。本迁移为每份交付物落一条
-- 自动核验记录：商家触发的自动核验（链接可达性 + AI 视觉）产出 tri-state 聚合态
--（passed/failed/inconclusive）+ 各项明细（checks jsonb）。
--
-- 数据库纪律：
--   * keyed on submission_id（履约级，resubmit 安全），同库真 FK（与 V9 attachment 一致）。
--   * 不动 SubmissionStatus（uq_submission_pending WHERE status='submitted' 仍 load-bearing）。
--   * 不加 ApplicationStatus 新态（confirmed_at 仍是履约确认的唯一标记）。
--   * 商家手动决策不在此表——confirm 即手动通过、submissions/.../reject 即手动退回，复用既有流。
--   * UNIQUE(submission_id) → 一份交付物一份核验记录，商家重跑原地 upsert（ON CONFLICT DO UPDATE）。

CREATE TABLE engagement_verification (
    id uuid PRIMARY KEY,
    submission_id uuid NOT NULL REFERENCES engagement_submission(id),  -- 同库真 FK
    status varchar(32) NOT NULL,                 -- passed / failed / inconclusive（自动核验聚合态）
    checks jsonb NOT NULL DEFAULT '[]'::jsonb,   -- [{type, status, detail, checked_at}] 各项明细
    last_checked_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 一份交付物一份核验记录；商家触发核验 / 重跑均原地 upsert（ON CONFLICT DO UPDATE）。
CREATE UNIQUE INDEX uq_verification_submission
    ON engagement_verification(submission_id);

-- 按 submission 取核验态（listSubmissions 批量、confirm/capture 闸门按 app→accepted submission 取）。
CREATE INDEX idx_verification_submission
    ON engagement_verification(submission_id);
