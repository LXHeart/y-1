-- 门店资料纳入统一 KYB 审核流。
ALTER TABLE store_profile
    ADD COLUMN submitted_at timestamptz,
    ADD COLUMN reviewed_at timestamptz,
    ADD COLUMN reviewer_account_id uuid,
    ADD COLUMN review_note text;

-- V16 的 active 仅表示资料已填写，历史数据没有审核事实，迁移为可提交草稿。
UPDATE store_profile SET status = 'draft' WHERE status = 'active';
ALTER TABLE store_profile ALTER COLUMN status SET DEFAULT 'draft';

ALTER TABLE store_profile
    ADD CONSTRAINT chk_store_profile_kyb_status
    CHECK (status IN ('draft', 'pending', 'under_review', 'approved', 'rejected', 'inactive'));
