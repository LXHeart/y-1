-- KYB 审核不变量：一份证据不能冒充多类必需证件；同一目标只能有一个开放审核请求。
CREATE UNIQUE INDEX uq_merchant_attachment_org_document_media
    ON merchant_attachment(organization_id, media_reference_id)
    WHERE attachment_type IN ('business_license', 'legal_person_id_front', 'legal_person_id_back');

ALTER TABLE kyb_verification_request
    ALTER COLUMN target_id SET NOT NULL,
    ADD CONSTRAINT ck_kyb_verification_type
        CHECK (verification_type IN ('merchant_profile', 'store_profile', 'withdrawal_account')),
    ADD CONSTRAINT ck_kyb_request_status
        CHECK (status IN ('pending', 'under_review', 'approved', 'rejected'));

CREATE UNIQUE INDEX uq_kyb_request_open_target
    ON kyb_verification_request(verification_type, target_id)
    WHERE status IN ('pending', 'under_review');
