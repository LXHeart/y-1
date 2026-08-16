-- D-07: attribution amendments are append-only and auditable.
CREATE TABLE consumer_order_attribution (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES consumer_order(id),
    recommender_account_id uuid,
    recommender_share_bps int NOT NULL CHECK (recommender_share_bps BETWEEN 0 AND 10000),
    source varchar(32) NOT NULL DEFAULT 'manual',
    reason text,
    actor_account_id uuid NOT NULL,
    effective_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_consumer_order_attribution_order
    ON consumer_order_attribution(order_id, effective_at DESC);
