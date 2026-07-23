-- 草场身份域：D-05 完整规则（HLD 41/1075）。Epic 2 Slice 2L。
-- 行业分类 + 材料结构化的支撑列、审核 SLA 截止时间、申诉引用链。

-- organization 加行业分类（D-05「行业」）；存量 org 为 NULL=未指定。
ALTER TABLE organization ADD COLUMN industry varchar(64);

-- 权限申请补全：SLA 截止时间、申诉引用原 rejected 申请、提交时行业快照。
ALTER TABLE merchant_permission_request
    ADD COLUMN review_deadline     timestamptz,   -- 提交时算 = created_at + SLA（默认 72h）
    ADD COLUMN original_request_id uuid,          -- 申诉引用的原 rejected 申请 id
    ADD COLUMN appeal_note         text,          -- 申诉说明
    ADD COLUMN industry            varchar(64);   -- 提交时 organization.industry 快照

-- 回填存量 pending 申请的 review_deadline（greenfield 少量；72h SLA）。
UPDATE merchant_permission_request
    SET review_deadline = created_at + interval '72 hours'
    WHERE review_deadline IS NULL AND status = 'pending';
