ALTER TABLE hypit_variant
    ADD COLUMN next_poll_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN observation_token uuid,
    ADD COLUMN observation_lease_until timestamptz,
    ADD COLUMN last_observed_at timestamptz,
    ADD COLUMN observation_failures integer NOT NULL DEFAULT 0,
    ADD COLUMN last_observation_error varchar(200);

CREATE INDEX idx_hypit_variant_observation_due ON hypit_variant(next_poll_at, id)
    WHERE state IN ('queued', 'running') AND build_id IS NOT NULL;
