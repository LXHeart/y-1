-- KYB 媒体留存引用。每个附件绑定和审核请求各持有一个 token；无引用的孤立媒体可清理。
CREATE TABLE media_kyb_retention (
    media_reference_id uuid NOT NULL,
    reference_id uuid NOT NULL,
    organization_id text NOT NULL,
    retained_at timestamptz NOT NULL DEFAULT now(),
    released_at timestamptz,
    PRIMARY KEY (media_reference_id, reference_id)
);

CREATE INDEX idx_media_kyb_retention_active ON media_kyb_retention(media_reference_id)
    WHERE released_at IS NULL;
