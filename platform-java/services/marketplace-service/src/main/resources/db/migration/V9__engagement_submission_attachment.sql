-- 草场 marketplace V9：履约交付物附件（Slice 11 Stage 2）。
--
-- 背景：V5 落了交付物本身（content_url 发布链接 + 说明 + 核验状态），但「截图/数据/视频」等媒体证据
-- 无处落脚。Slice 8 已在 intelligence-service 建好 media_reference 鉴权三步上传 + 双 key 生命周期，
-- 本迁移让 engagement_submission 成为它的第一个消费者：推荐官上传的 media_reference 挂接到交付物上，
-- 供商家核验时查看/下载。这是 greenfield 消费，非迁移。
--
-- 数据库纪律（与 V5 recommender_account_id 一致）：media_reference_id 是**无 FK 的 uuid**——
-- media_reference 属于 intelligence 库，跨服务跨库无法真 FK。挂接时把 mime_type/size_bytes 快照一份，
-- 这样即便 media 日后被删除（intelligence active→deleted→下载 404），marketplace 仍能展示「曾是图片/xx KB」
-- 的残留元信息（下载按钮置灰）。下载时经 IntelligenceMediaClient 中转取短时 presigned URL，
-- 不在 marketplace 侧做级联删除。

CREATE TABLE engagement_submission_attachment (
    id uuid PRIMARY KEY,
    submission_id uuid NOT NULL REFERENCES engagement_submission(id),  -- 同库真 FK
    media_reference_id uuid NOT NULL,          -- 跨服务引用 intelligence.media_reference，无 FK
    mime_type text,                             -- 挂接时快照（media 删除后仍可展示类型）
    size_bytes bigint,                          -- 挂接时快照
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 一份交付物的同一附件只能挂接一次（防重复挂接；提交时去重已在请求层做，这是 DB 兜底）。
CREATE UNIQUE INDEX uq_submission_attachment
    ON engagement_submission_attachment(submission_id, media_reference_id);

-- 商家查看交付物列表时按 submission 批量取附件。
CREATE INDEX idx_attachment_submission
    ON engagement_submission_attachment(submission_id, created_at);
