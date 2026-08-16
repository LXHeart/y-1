-- D-07: one consumer split may credit multiple recommender wallets.
CREATE TABLE consumer_payment_split_allocation (
    id uuid PRIMARY KEY,
    order_ref text NOT NULL REFERENCES consumer_payment(order_ref),
    split_operation_id varchar(128) NOT NULL,
    recommender_account_id uuid NOT NULL,
    amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (order_ref, split_operation_id, recommender_account_id)
);
CREATE INDEX idx_consumer_split_allocation_order
    ON consumer_payment_split_allocation(order_ref, split_operation_id);
