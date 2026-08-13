ALTER TABLE video_generation_job
    ADD COLUMN context_snapshot_id uuid REFERENCES creation_context_snapshot(id) ON DELETE RESTRICT,
    ADD COLUMN provider_config_fingerprint varchar(64);

CREATE INDEX idx_video_generation_job_context_snapshot
    ON video_generation_job(context_snapshot_id)
    WHERE context_snapshot_id IS NOT NULL;
