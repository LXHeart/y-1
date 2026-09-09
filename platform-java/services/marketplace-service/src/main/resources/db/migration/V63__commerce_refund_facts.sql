-- Audit-fix 03 (R09/R13): append-only refund facts. The cumulative amount on
-- consumer_order cannot answer a time-window query, especially for partial refunds.
CREATE TABLE IF NOT EXISTS consumer_order_refund (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES consumer_order(id),
    operation_id varchar(128) NOT NULL UNIQUE,
    amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    source varchar(64) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_consumer_order_refund_occurred
    ON consumer_order_refund(occurred_at, id);
CREATE INDEX IF NOT EXISTS idx_consumer_order_refund_order
    ON consumer_order_refund(order_id, occurred_at DESC);
