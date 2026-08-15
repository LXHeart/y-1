-- Review policy decisions remain append-only in task_review; this migration versions
-- verification overrides so every correction has a distinct outbox event.
ALTER TABLE verification_override
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 1;

ALTER TABLE verification_override
    DROP CONSTRAINT IF EXISTS ck_verification_override_version;

ALTER TABLE verification_override
    ADD CONSTRAINT ck_verification_override_version CHECK (version > 0);
