-- Risk investigation domain. Signals are idempotent facts; cases and audits are operational read models.
CREATE TABLE risk_signal (
    id uuid PRIMARY KEY,
    source_kind varchar(48) NOT NULL,
    source_ref varchar(160) NOT NULL,
    subject_kind varchar(32) NOT NULL CHECK (subject_kind IN ('account', 'organization', 'task', 'order', 'engagement')),
    subject_ref varchar(160) NOT NULL,
    organization_id uuid,
    rule_code varchar(64) NOT NULL,
    rule_version varchar(32) NOT NULL,
    score int NOT NULL CHECK (score BETWEEN 0 AND 100),
    severity varchar(16) NOT NULL CHECK (severity IN ('low', 'medium', 'high', 'critical')),
    status varchar(24) NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'acknowledged', 'resolved', 'dismissed')),
    evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_kind, source_ref, rule_code, rule_version)
);
CREATE INDEX idx_risk_signal_queue ON risk_signal(status, severity, occurred_at DESC);
CREATE INDEX idx_risk_signal_subject ON risk_signal(subject_kind, subject_ref, occurred_at DESC);
CREATE INDEX idx_risk_signal_org ON risk_signal(organization_id, occurred_at DESC);

CREATE TABLE risk_case (
    id uuid PRIMARY KEY,
    subject_kind varchar(32) NOT NULL CHECK (subject_kind IN ('account', 'organization', 'task', 'order', 'engagement')),
    subject_ref varchar(160) NOT NULL,
    organization_id uuid,
    status varchar(24) NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'in_review', 'resolved', 'dismissed')),
    severity varchar(16) NOT NULL CHECK (severity IN ('low', 'medium', 'high', 'critical')),
    score int NOT NULL CHECK (score BETWEEN 0 AND 100),
    reason varchar(500) NOT NULL,
    resolution_note varchar(1000),
    assigned_to uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz
);
CREATE UNIQUE INDEX uniq_active_risk_case_subject
    ON risk_case(subject_kind, subject_ref) WHERE status IN ('open', 'in_review');
CREATE INDEX idx_risk_case_queue ON risk_case(status, severity, created_at);
CREATE INDEX idx_risk_case_org ON risk_case(organization_id, created_at DESC);

CREATE TABLE risk_case_signal (
    case_id uuid NOT NULL REFERENCES risk_case(id),
    signal_id uuid NOT NULL UNIQUE REFERENCES risk_signal(id),
    attached_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (case_id, signal_id)
);

CREATE TABLE risk_case_audit (
    id bigserial PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES risk_case(id),
    action varchar(32) NOT NULL,
    actor_account_id uuid,
    actor_role varchar(64) NOT NULL,
    note varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_case_audit_case ON risk_case_audit(case_id, id);
