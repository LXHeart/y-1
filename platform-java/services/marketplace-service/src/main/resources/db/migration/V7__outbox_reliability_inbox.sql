-- Slice 7A: marketplace event delivery reliability.

ALTER TABLE marketplace_outbox
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN claim_token uuid,
    ADD COLUMN claimed_until timestamptz,
    ADD COLUMN last_error_code varchar(64);

CREATE INDEX idx_marketplace_outbox_ready
    ON marketplace_outbox (next_attempt_at, created_at)
    WHERE published_at IS NULL;

CREATE INDEX idx_marketplace_outbox_claim
    ON marketplace_outbox (claimed_until)
    WHERE published_at IS NULL AND claimed_until IS NOT NULL;

CREATE TABLE marketplace_inbox (
    consumer_name varchar(128) NOT NULL,
    event_id text NOT NULL,
    event_type text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    payload_sha256 char(64) NOT NULL,
    source_topic text NOT NULL,
    source_partition integer NOT NULL,
    source_offset bigint NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id),
    UNIQUE (consumer_name, source_topic, source_partition, source_offset)
);
