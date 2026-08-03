-- 草场身份域：商家附件（KYB 材料）。GL-P3-MERCHANT-001。
-- 引用 intelligence.media_reference，跨服务无 FK，快照 mime_type/size_bytes。
CREATE TABLE merchant_attachment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,                 -- 所属商家
    attachment_type varchar(32) NOT NULL,          -- business_license / legal_person_id_front / legal_person_id_back / store_photo / other
    media_reference_id uuid NOT NULL,              -- 引用 intelligence.media_reference
    mime_type text,                                -- 快照：media 删除后仍可展示类型
    size_bytes bigint,                             -- 快照：字节大小
    ocr_result jsonb,                              -- OCR 识别结果（后续 OCR 集成时使用）
    uploaded_at timestamptz NOT NULL DEFAULT now(),
    uploaded_by_account_id uuid NOT NULL          -- 上传者
);

CREATE INDEX idx_merchant_attachment_org ON merchant_attachment(organization_id, attachment_type);
CREATE UNIQUE INDEX uq_merchant_attachment_org_type ON merchant_attachment(organization_id, attachment_type)
    WHERE attachment_type IN ('business_license', 'legal_person_id_front', 'legal_person_id_back');
-- 证件类附件每种只能有一个，其他类型不限制
