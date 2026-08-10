-- Persist the authoritative media ownership snapshot in the consuming domain.
ALTER TABLE engagement_submission_attachment
    ADD COLUMN media_domain_type varchar(64),
    ADD COLUMN media_domain_id text,
    ADD COLUMN media_checksum varchar(64),
    ADD COLUMN media_status_snapshot varchar(16),
    ADD COLUMN media_metadata_version integer NOT NULL DEFAULT 1;

UPDATE engagement_submission_attachment attachment
SET media_domain_type = 'application',
    media_domain_id = submission.application_id::text,
    media_status_snapshot = 'active'
FROM engagement_submission submission
WHERE submission.id = attachment.submission_id;

ALTER TABLE engagement_submission_attachment ALTER COLUMN media_domain_type SET NOT NULL;
ALTER TABLE engagement_submission_attachment ALTER COLUMN media_domain_id SET NOT NULL;
ALTER TABLE engagement_submission_attachment ALTER COLUMN media_status_snapshot SET NOT NULL;

ALTER TABLE engagement_submission_attachment
    ADD CONSTRAINT ck_submission_attachment_domain CHECK (media_domain_type = 'application'),
    ADD CONSTRAINT ck_submission_attachment_status CHECK (media_status_snapshot = 'active'),
    ADD CONSTRAINT ck_submission_attachment_checksum
        CHECK (media_checksum IS NULL OR media_checksum ~ '^[0-9a-f]{64}$');

CREATE INDEX idx_submission_attachment_media_domain
    ON engagement_submission_attachment(media_domain_type, media_domain_id);
