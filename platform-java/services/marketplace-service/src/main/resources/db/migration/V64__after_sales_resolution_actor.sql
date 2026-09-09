-- Audit-fix 03 (R09): retain the account that issued an after-sales decision.
-- Nullable for existing disputes; new decisions write the authenticated operator.
ALTER TABLE consumer_order_after_sales_dispute
    ADD COLUMN IF NOT EXISTS resolution_actor_account_id uuid;

