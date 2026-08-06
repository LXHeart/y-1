-- Cluster-wide platform model concurrency leases.
-- Pre-created slots avoid COUNT-then-INSERT races across service replicas.

ALTER TABLE platform_model_config
    ADD CONSTRAINT chk_platform_model_max_concurrency
    CHECK (max_concurrency IS NULL OR max_concurrency BETWEEN 1 AND 1000)
    NOT VALID;

CREATE TABLE platform_model_concurrency_slot (
    config_id      uuid NOT NULL REFERENCES platform_model_config(id) ON DELETE RESTRICT,
    slot_no        int NOT NULL CHECK (slot_no > 0),
    lease_token    uuid,
    lease_until    timestamptz,
    acquired_at    timestamptz,
    PRIMARY KEY (config_id, slot_no),
    CONSTRAINT chk_platform_model_lease_state CHECK (
        (lease_token IS NULL AND lease_until IS NULL AND acquired_at IS NULL)
        OR
        (lease_token IS NOT NULL AND lease_until IS NOT NULL AND acquired_at IS NOT NULL)
    )
);

CREATE INDEX idx_platform_model_concurrency_expiry
    ON platform_model_concurrency_slot(config_id, lease_until);

INSERT INTO platform_model_concurrency_slot(config_id, slot_no)
SELECT config.id, slots.slot_no
FROM platform_model_config config
CROSS JOIN LATERAL generate_series(1, config.max_concurrency) AS slots(slot_no)
WHERE config.max_concurrency BETWEEN 1 AND 1000;
