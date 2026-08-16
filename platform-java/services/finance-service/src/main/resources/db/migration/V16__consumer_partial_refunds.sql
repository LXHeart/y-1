-- D-07: a payment may be refunded in multiple idempotent operations.
ALTER TABLE consumer_payment
    ADD COLUMN refunded_amount_cents bigint NOT NULL DEFAULT 0
        CHECK (refunded_amount_cents >= 0 AND refunded_amount_cents <= amount_cents);

ALTER TABLE consumer_payment
    DROP CONSTRAINT IF EXISTS consumer_payment_status_check;
ALTER TABLE consumer_payment
    ADD CONSTRAINT consumer_payment_status_check
        CHECK (status IN ('succeeded', 'partially_refunded', 'refunded'));

ALTER TABLE consumer_payment_refund
    DROP CONSTRAINT IF EXISTS consumer_payment_refund_order_ref_key;

CREATE INDEX idx_consumer_payment_refund_order
    ON consumer_payment_refund(order_ref, created_at DESC);
