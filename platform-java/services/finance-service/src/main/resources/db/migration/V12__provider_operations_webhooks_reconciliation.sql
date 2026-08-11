-- D-01 Sandbox provider boundary.
-- These tables record the provider-facing contract without contacting a real PSP.
-- A real adapter can reuse the same operation_id/provider_ref values when it is selected.

CREATE TABLE finance_provider_operation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(32) NOT NULL,
    operation_id varchar(160) NOT NULL UNIQUE,
    operation_type varchar(32) NOT NULL
        CHECK (operation_type IN ('payment', 'refund', 'split', 'payout', 'deposit', 'escrow')),
    reference text NOT NULL,
    amount_cents bigint NOT NULL CHECK (amount_cents >= 0),
    currency varchar(8) NOT NULL DEFAULT 'CNY',
    provider_ref text NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'succeeded'
        CHECK (status IN ('requested', 'processing', 'succeeded', 'failed', 'reconciled')),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

CREATE UNIQUE INDEX uq_finance_provider_operation_provider_ref
    ON finance_provider_operation(provider, provider_ref);
CREATE INDEX idx_finance_provider_operation_status
    ON finance_provider_operation(status, created_at DESC);

CREATE TABLE finance_provider_webhook_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id varchar(160) NOT NULL,
    provider varchar(32) NOT NULL,
    event_type varchar(64) NOT NULL,
    provider_ref text,
    operation_id varchar(160),
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(24) NOT NULL DEFAULT 'received'
        CHECK (status IN ('received', 'processed', 'ignored', 'failed')),
    error_message text,
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    UNIQUE(provider, event_id)
);

CREATE INDEX idx_finance_provider_webhook_operation
    ON finance_provider_webhook_event(operation_id, received_at DESC);

CREATE TABLE finance_provider_reconciliation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider varchar(32) NOT NULL,
    statement_ref varchar(160) NOT NULL,
    provider_ref text NOT NULL,
    operation_id varchar(160),
    operation_type varchar(32),
    amount_cents bigint NOT NULL CHECK (amount_cents >= 0),
    currency varchar(8) NOT NULL DEFAULT 'CNY',
    status varchar(24) NOT NULL
        CHECK (status IN ('matched', 'mismatch', 'unmatched')),
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(provider, statement_ref, provider_ref)
);

CREATE INDEX idx_finance_provider_reconciliation_status
    ON finance_provider_reconciliation(status, created_at DESC);
