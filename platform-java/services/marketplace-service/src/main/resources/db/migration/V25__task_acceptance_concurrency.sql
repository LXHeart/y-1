-- Task acceptance concurrency control and durable Saga dispatch intent.

CREATE TABLE task_acceptance_counter (
    task_id uuid PRIMARY KEY REFERENCES task(id) ON DELETE CASCADE,
    occupied_slots int NOT NULL DEFAULT 0 CHECK (occupied_slots >= 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO task_acceptance_counter(task_id, occupied_slots)
SELECT t.id,
       COUNT(a.id) FILTER (WHERE a.status IN ('reserving', 'accepted'))::int
FROM task t
LEFT JOIN task_application a ON a.task_id = t.id
GROUP BY t.id;

CREATE TABLE task_acceptance_command (
    id uuid PRIMARY KEY,
    actor_account_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    task_id uuid NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    application_id uuid NOT NULL REFERENCES task_application(id) ON DELETE CASCADE,
    workflow_id varchar(160),
    merchant_account_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    amount_cents bigint NOT NULL CHECK (amount_cents >= 0),
    status varchar(32) NOT NULL CHECK (
        status IN ('pending_dispatch', 'started', 'accepted', 'compensated', 'aborted')
    ),
    failure_reason varchar(128),
    workflow_started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(actor_account_id, idempotency_key),
    CHECK ((amount_cents > 0 AND workflow_id IS NOT NULL)
        OR (amount_cents = 0 AND workflow_id IS NULL))
);

-- At most one in-flight accept attempt may own an application's slot. Terminal
-- commands remain as the immutable idempotency/audit ledger and permit a new
-- request after compensation.
CREATE UNIQUE INDEX uq_task_acceptance_command_active_application
    ON task_acceptance_command(application_id)
    WHERE status IN ('pending_dispatch', 'started');

CREATE INDEX idx_task_acceptance_command_dispatch
    ON task_acceptance_command(created_at, id)
    WHERE status = 'pending_dispatch';

CREATE INDEX idx_task_acceptance_command_application
    ON task_acceptance_command(application_id, created_at DESC);
