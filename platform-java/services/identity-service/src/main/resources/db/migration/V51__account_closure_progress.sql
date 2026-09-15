-- 任务书 #103 C103-08（D103-06 / §7.2）：注销准备/域回执步骤表。
-- preparing 意图先持久化，远端 prepare/erase 后按步骤恢复；网络未知重试原请求（同
-- closureRequestId 幂等），不靠租约到期自行解冻已注销账号。
CREATE TABLE IF NOT EXISTS account_closure_step (
    closure_request_id uuid NOT NULL,
    domain varchar(32) NOT NULL,
    step varchar(64) NOT NULL,
    state varchar(32) NOT NULL DEFAULT 'pending'
        CHECK (state IN ('pending', 'processing', 'succeeded', 'retry_wait', 'needs_review')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz,
    claim_token uuid,
    claimed_until timestamptz,
    receipt_json jsonb,
    last_error_code varchar(128),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (closure_request_id, domain, step)
);

CREATE INDEX IF NOT EXISTS idx_account_closure_step_due
    ON account_closure_step(state, next_attempt_at);

-- §7.2：「已有 closure 增 preparing 合法态」——扩展 V37 的状态域（幂等重建约束）。
ALTER TABLE account_closure_request DROP CONSTRAINT IF EXISTS account_closure_request_status_check;
ALTER TABLE account_closure_request ADD CONSTRAINT account_closure_request_status_check
    CHECK (status IN ('preparing', 'blocked', 'retention', 'erasing', 'completed', 'cancelled', 'failed'));
