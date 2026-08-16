-- D-07: immutable order allocation snapshot for multi-recommender settlement.
CREATE TABLE consumer_order_attribution_allocation (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES consumer_order(id),
    recommender_account_id uuid NOT NULL,
    share_bps int NOT NULL CHECK (share_bps > 0 AND share_bps <= 10000),
    amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    source varchar(32) NOT NULL DEFAULT 'manual',
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (order_id, recommender_account_id)
);

ALTER TABLE consumer_order_after_sales_dispute
    ADD COLUMN resolution varchar(24),
    ADD COLUMN resolution_amount_cents bigint,
    ADD COLUMN resolution_reason text,
    ADD COLUMN refund_operation_id varchar(128);
