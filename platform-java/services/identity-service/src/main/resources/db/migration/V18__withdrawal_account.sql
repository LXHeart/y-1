-- 草场身份域：商家收款账户。GL-P3-MERCHANT-001。
CREATE TABLE withdrawal_account (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    account_type varchar(32) NOT NULL,             -- bank_card / alipay / wechat
    account_name varchar(255) NOT NULL,            -- 账户名
    account_number_encrypted text,                 -- 账号（加密存储）
    bank_name varchar(128),                        -- 开户行
    branch_name varchar(255),                      -- 开户支行
    is_default boolean NOT NULL DEFAULT false,     -- 是否默认账户
    status varchar(32) NOT NULL DEFAULT 'pending',  -- pending/under_review/approved/rejected
    submitted_at timestamptz,
    reviewed_at timestamptz,
    reviewer_account_id uuid,
    review_note text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_withdrawal_account_org ON withdrawal_account(organization_id);
CREATE INDEX idx_withdrawal_account_status ON withdrawal_account(status);
