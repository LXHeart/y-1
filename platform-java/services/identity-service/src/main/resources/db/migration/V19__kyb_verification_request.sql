-- 草场身份域：KYB 审核申请（统一工作流）。GL-P3-MERCHANT-001。
CREATE TABLE kyb_verification_request (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    requester_account_id uuid NOT NULL,
    verification_type varchar(32) NOT NULL,        -- merchant_profile / store_profile / withdrawal_account
    target_id uuid,                                -- 审核对象 ID
    materials jsonb,                               -- 提交材料引用（merchant_attachment_id 列表）
    status varchar(32) NOT NULL DEFAULT 'pending', -- pending/under_review/approved/rejected
    reviewer_account_id uuid,
    review_note text,
    review_deadline timestamptz,                   -- SLA 截止时间
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_kyb_request_org ON kyb_verification_request(organization_id);
CREATE INDEX idx_kyb_request_status ON kyb_verification_request(status);
CREATE INDEX idx_kyb_request_type_target ON kyb_verification_request(verification_type, target_id);
