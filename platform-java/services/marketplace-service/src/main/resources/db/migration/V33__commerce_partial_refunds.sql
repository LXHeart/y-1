-- D-07: retain cumulative refund state on the immutable order aggregate.
ALTER TABLE consumer_order
    ADD COLUMN refunded_amount_cents bigint NOT NULL DEFAULT 0
        CHECK (refunded_amount_cents >= 0 AND refunded_amount_cents <= price_cents),
    ADD COLUMN refund_requested_amount_cents bigint
        CHECK (refund_requested_amount_cents IS NULL OR refund_requested_amount_cents > 0),
    ADD COLUMN refund_reason text;

ALTER TABLE consumer_order
    DROP CONSTRAINT IF EXISTS consumer_order_status_check;
ALTER TABLE consumer_order
    ADD CONSTRAINT consumer_order_status_check
        CHECK (status IN ('pending_payment', 'paid', 'redeeming', 'redeemed',
            'refund_pending', 'partially_refunded', 'refunded', 'payment_failed', 'cancelled'));
