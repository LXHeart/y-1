CREATE TABLE speech_transcription (
    id uuid PRIMARY KEY,
    media_reference_id uuid NOT NULL,
    owner_account_id text NOT NULL,
    organization_id text,
    requested_language varchar(16) NOT NULL,
    detected_language varchar(16),
    duration_ms bigint NOT NULL CHECK (duration_ms >= 0),
    status varchar(16) NOT NULL CHECK (status IN ('processing','completed','failed')),
    transcript_text text,
    provider varchar(64),
    model varchar(128),
    platform_model_version integer,
    ai_run_id uuid,
    failure_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CHECK (status <> 'completed' OR (transcript_text IS NOT NULL AND provider IS NOT NULL AND model IS NOT NULL AND ai_run_id IS NOT NULL AND completed_at IS NOT NULL)),
    CHECK (status <> 'failed' OR failure_code IS NOT NULL)
);

CREATE INDEX idx_speech_transcription_owner ON speech_transcription(owner_account_id, created_at DESC);
CREATE INDEX idx_speech_transcription_media ON speech_transcription(media_reference_id, created_at DESC);
