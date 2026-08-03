-- D-03 slice 2：商家拒绝「系统核实通过」的履约 → 转客服裁定。
-- confirmed_at 同时设：表示系统核实事实已成立，使争议终局 reconciliation 可按裁决落钱；
-- merchant_rejected_at / rejection_reason 单独记录商家异议，避免 UI 将其误报为普通确认。
ALTER TABLE task_application
    ADD COLUMN IF NOT EXISTS merchant_rejected_at timestamptz,
    ADD COLUMN IF NOT EXISTS rejection_reason text,
    ADD COLUMN IF NOT EXISTS merchant_rejection_dispute_id uuid;
