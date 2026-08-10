-- D-05 准入系统收口：自动核验建议、领取/并发版本、SLA 违约和审计。
ALTER TABLE merchant_permission_request
    ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS review_started_at timestamptz,
    ADD COLUMN IF NOT EXISTS sla_breached_at timestamptz,
    ADD COLUMN IF NOT EXISTS auto_review_status varchar(24) NOT NULL DEFAULT 'not_run',
    ADD COLUMN IF NOT EXISTS auto_review_result jsonb,
    ADD COLUMN IF NOT EXISTS review_mode varchar(24) NOT NULL DEFAULT 'manual',
    ADD COLUMN IF NOT EXISTS risk_level varchar(16) NOT NULL DEFAULT 'standard',
    ADD COLUMN IF NOT EXISTS attachment_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS decision_at timestamptz,
    ADD COLUMN IF NOT EXISTS appeal_count integer NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_permission_auto_review_status'
                   AND conrelid = 'merchant_permission_request'::regclass) THEN
        ALTER TABLE merchant_permission_request ADD CONSTRAINT ck_permission_auto_review_status
            CHECK (auto_review_status IN ('not_run', 'pending', 'passed', 'failed', 'needs_review'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_permission_review_mode'
                   AND conrelid = 'merchant_permission_request'::regclass) THEN
        ALTER TABLE merchant_permission_request ADD CONSTRAINT ck_permission_review_mode
            CHECK (review_mode IN ('manual', 'auto_recommendation'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_permission_risk_level'
                   AND conrelid = 'merchant_permission_request'::regclass) THEN
        ALTER TABLE merchant_permission_request ADD CONSTRAINT ck_permission_risk_level
            CHECK (risk_level IN ('standard', 'elevated', 'high'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_permission_appeal_count'
                   AND conrelid = 'merchant_permission_request'::regclass) THEN
        ALTER TABLE merchant_permission_request ADD CONSTRAINT ck_permission_appeal_count
            CHECK (appeal_count >= 0 AND appeal_count <= 3);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_permission_request_status'
                   AND conrelid = 'merchant_permission_request'::regclass) THEN
        ALTER TABLE merchant_permission_request ADD CONSTRAINT ck_permission_request_status
            CHECK (status IN ('pending', 'under_review', 'approved', 'rejected'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_permission_requested_tier'
                   AND conrelid = 'merchant_permission_request'::regclass) THEN
        ALTER TABLE merchant_permission_request ADD CONSTRAINT ck_permission_requested_tier
            CHECK (requested_tier IN ('basic_publish', 'finance_transaction'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_permission_attachment_ids_array'
                   AND conrelid = 'merchant_permission_request'::regclass) THEN
        ALTER TABLE merchant_permission_request ADD CONSTRAINT ck_permission_attachment_ids_array
            CHECK (jsonb_typeof(attachment_ids) = 'array');
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_permission_request_queue
    ON merchant_permission_request(status, review_deadline, created_at)
    WHERE status IN ('pending', 'under_review');

CREATE TABLE IF NOT EXISTS merchant_permission_request_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    actor_account_id uuid,
    actor_kind varchar(24) NOT NULL,
    action varchar(48) NOT NULL,
    from_status varchar(32),
    to_status varchar(32),
    details jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 历史版本没有开放申请唯一约束。迁移时若已存在并发重复件，优先保留已领取件，其次保留最早件；
-- 其余件以 system 审计方式关闭，避免 CREATE UNIQUE INDEX 直接中断上线。
WITH ranked AS (
    SELECT id, organization_id, status AS old_status,
           row_number() OVER (
               PARTITION BY organization_id, requested_tier
               ORDER BY CASE WHEN status = 'under_review' THEN 0 ELSE 1 END, created_at, id
           ) AS rn,
           first_value(id) OVER (
               PARTITION BY organization_id, requested_tier
               ORDER BY CASE WHEN status = 'under_review' THEN 0 ELSE 1 END, created_at, id
           ) AS kept_id
    FROM merchant_permission_request
    WHERE status IN ('pending', 'under_review')
), closed AS (
    UPDATE merchant_permission_request request
       SET status = 'rejected',
           review_note = COALESCE(request.review_note, '历史重复开放申请已由迁移关闭'),
           decision_at = COALESCE(request.decision_at, now()),
           version = request.version + 1,
           updated_at = now()
      FROM ranked
     WHERE request.id = ranked.id AND ranked.rn > 1
    RETURNING request.id, request.organization_id, ranked.old_status, ranked.kept_id
)
INSERT INTO merchant_permission_request_audit(
    request_id, organization_id, actor_kind, action, from_status, to_status, details)
SELECT id, organization_id, 'system', 'migration_duplicate_closed', old_status, 'rejected',
       jsonb_build_object('keptRequestId', kept_id::text)
FROM closed;

CREATE UNIQUE INDEX IF NOT EXISTS uq_permission_request_open_tier
    ON merchant_permission_request(organization_id, requested_tier)
    WHERE status IN ('pending', 'under_review');

CREATE UNIQUE INDEX IF NOT EXISTS uq_merchant_attachment_org_regulated_type
    ON merchant_attachment(organization_id, attachment_type)
    WHERE attachment_type IN ('industry_license', 'financial_qualification');

CREATE UNIQUE INDEX IF NOT EXISTS uq_merchant_attachment_org_all_document_media
    ON merchant_attachment(organization_id, media_reference_id)
    WHERE attachment_type IN ('business_license', 'legal_person_id_front', 'legal_person_id_back',
                              'industry_license', 'financial_qualification');

CREATE INDEX IF NOT EXISTS idx_permission_request_audit_request
    ON merchant_permission_request_audit(request_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_permission_request_sla
    ON merchant_permission_request(review_deadline)
    WHERE status IN ('pending', 'under_review') AND sla_breached_at IS NULL;
