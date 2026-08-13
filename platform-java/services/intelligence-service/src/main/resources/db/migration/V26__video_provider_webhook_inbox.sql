CREATE TABLE video_provider_webhook_inbox (
    provider varchar(64) NOT NULL,
    event_id varchar(256) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    PRIMARY KEY (provider, event_id)
);
