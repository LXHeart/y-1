-- 草场身份域：商家权限审核工作流（D-05 地基）。Epic 2 Slice 2H。
-- 商家提交升级申请 → 平台 admin 审核 → 批准则接 organization.permission_tier。
CREATE TABLE merchant_permission_request (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    requester_account_id uuid NOT NULL,
    requested_tier varchar(32) NOT NULL,          -- basic_publish / finance_transaction
    materials json,                                -- 提交材料（自由 JSON；完整 schema 随 D-05 后续定）
    status varchar(32) NOT NULL DEFAULT 'pending', -- pending / under_review / approved / rejected
    reviewer_account_id uuid,
    review_note text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_perm_request_org ON merchant_permission_request(organization_id);
CREATE INDEX idx_perm_request_status ON merchant_permission_request(status);
