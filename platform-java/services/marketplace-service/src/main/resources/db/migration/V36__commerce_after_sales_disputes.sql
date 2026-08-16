-- D-07: post-redemption after-sales disputes are first-class order facts.
ALTER TABLE consumer_order
    DROP CONSTRAINT IF EXISTS consumer_order_status_check;
ALTER TABLE consumer_order
    ADD CONSTRAINT consumer_order_status_check
        CHECK (status IN ('pending_payment', 'paid', 'redeeming', 'redeemed',
            'refund_pending', 'partially_refunded', 'refunded', 'after_sales_disputed',
            'payment_failed', 'cancelled'));

CREATE TABLE consumer_order_after_sales_dispute (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE REFERENCES consumer_order(id),
    consumer_account_id uuid NOT NULL,
    reason text NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'resolved', 'rejected')),
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz
);
