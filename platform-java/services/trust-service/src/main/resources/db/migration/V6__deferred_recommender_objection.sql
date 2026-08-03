-- D-03 F5：merchant_rejection 活跃期间，持久记录推荐官异议；客服终局后原子提升为 standard 案。
CREATE TABLE deferred_dispute_request (
    id uuid PRIMARY KEY,
    source_dispute_id uuid NOT NULL REFERENCES dispute_case(id),
    engagement_ref varchar(255) NOT NULL,
    organization_id uuid NOT NULL,
    recommender_account_id uuid NOT NULL,
    reason text,
    status varchar(32) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'promoted')),
    promoted_dispute_id uuid REFERENCES dispute_case(id),
    adjudication_workflow_id varchar(255),
    adjudication_workflow_started_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uniq_deferred_request_source_recommender
        UNIQUE (source_dispute_id, recommender_account_id),
    CONSTRAINT deferred_request_promotion_consistent CHECK (
        (status = 'pending' AND promoted_dispute_id IS NULL)
        OR (status = 'promoted' AND promoted_dispute_id IS NOT NULL AND adjudication_workflow_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uniq_deferred_request_promoted_dispute
    ON deferred_dispute_request(promoted_dispute_id)
    WHERE promoted_dispute_id IS NOT NULL;

CREATE INDEX idx_deferred_request_adjudication_dispatch
    ON deferred_dispute_request(updated_at)
    WHERE status = 'promoted' AND adjudication_workflow_started_at IS NULL;
