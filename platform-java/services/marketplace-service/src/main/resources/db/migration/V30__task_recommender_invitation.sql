-- 确定性推荐官匹配 v1：邀请审计快照与报名闭环。
CREATE TABLE task_recommender_invitation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id uuid NOT NULL REFERENCES task(id),
    recommender_account_id uuid NOT NULL,
    invited_by_account_id uuid NOT NULL,
    scoring_version varchar(40) NOT NULL,
    score_snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    applied_at timestamptz,
    CONSTRAINT uq_task_recommender_invitation UNIQUE(task_id, recommender_account_id),
    CONSTRAINT ck_task_recommender_score_snapshot CHECK (jsonb_typeof(score_snapshot) = 'object')
);

CREATE INDEX idx_task_recommender_invitation_candidate
    ON task_recommender_invitation(recommender_account_id, created_at DESC);
CREATE INDEX idx_task_recommender_invitation_task
    ON task_recommender_invitation(task_id, created_at DESC);
