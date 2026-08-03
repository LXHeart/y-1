-- 草场身份域：商家主体详细资料（KYB）。GL-P3-MERCHANT-001。
-- organization_id 引用 organization.id（逻辑引用，无 FK，database-per-service）。
CREATE TABLE merchant_profile (
    organization_id uuid PRIMARY KEY,
    legal_name varchar(255),                        -- 法定名称（工商注册名称）
    unified_social_credit_code varchar(64) UNIQUE,  -- 统一社会信用代码（唯一索引）
    business_type varchar(32),                       -- 企业类型（individual/llc/corp/等）
    legal_person_name varchar(64),                  -- 法人姓名
    legal_person_id_number varchar(32),              -- 法人身份证号（敏感信息，需加密）
    registered_capital_cents bigint,                 -- 注册资本（分）
    establishment_date date,                         -- 成立日期
    business_address jsonb,                          -- 经营地址 {province,city,district,address,longitude,latitude}
    contact_phone varchar(32),                       -- 联系电话
    contact_email varchar(255),                      -- 联系邮箱
    status varchar(32) NOT NULL DEFAULT 'draft',     -- draft/pending/under_review/approved/rejected
    submitted_at timestamptz,                        -- 提交审核时间
    reviewed_at timestamptz,                         -- 审核完成时间
    reviewer_account_id uuid,                        -- 审核人
    review_note text,                                -- 审核备注
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_merchant_profile_org ON merchant_profile(organization_id);
CREATE INDEX idx_merchant_profile_status ON merchant_profile(status);
CREATE INDEX idx_merchant_profile_uscc ON merchant_profile(unified_social_credit_code) WHERE unified_social_credit_code IS NOT NULL;
