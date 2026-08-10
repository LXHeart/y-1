-- KYB document OCR and automatic verification. Analysis is advisory; human review remains authoritative.
ALTER TABLE merchant_attachment
    ADD COLUMN ocr_status varchar(24) NOT NULL DEFAULT 'not_applicable',
    ADD COLUMN ocr_provider varchar(64),
    ADD COLUMN ocr_model varchar(128),
    ADD COLUMN ocr_result_version integer,
    ADD COLUMN ocr_analyzed_at timestamptz,
    ADD COLUMN ocr_failure_code varchar(64),
    ADD CONSTRAINT ck_merchant_attachment_ocr_status
        CHECK (ocr_status IN ('not_applicable', 'pending', 'processing', 'passed', 'needs_review', 'failed'));

UPDATE merchant_attachment
SET ocr_status = 'pending'
WHERE attachment_type IN ('business_license', 'legal_person_id_front', 'legal_person_id_back')
  AND ocr_result IS NULL;

CREATE TABLE kyb_document_analysis_job (
    attachment_id uuid PRIMARY KEY REFERENCES merchant_attachment(id) ON DELETE CASCADE,
    status varchar(16) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'completed', 'dead')),
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claimed_until timestamptz,
    claim_token uuid,
    last_error_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

INSERT INTO kyb_document_analysis_job (attachment_id)
SELECT id FROM merchant_attachment
WHERE attachment_type IN ('business_license', 'legal_person_id_front', 'legal_person_id_back')
  AND ocr_result IS NULL
ON CONFLICT DO NOTHING;

CREATE INDEX idx_kyb_document_analysis_job_pending
    ON kyb_document_analysis_job(next_attempt_at, created_at)
    WHERE status = 'pending';

