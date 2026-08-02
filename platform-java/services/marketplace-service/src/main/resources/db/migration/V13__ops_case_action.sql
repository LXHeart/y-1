-- GL-P1-OPS-001 Stage 2：受限处置动作 + DLT 登记。
--
-- ops_case_action 是动作的幂等台账：operation_id 唯一索引保证「同一操作至多执行一次」
-- （沿用 credits bridge / GL-P0-BILL-002 的口径）。行先落 pending 再调下游，下游结果回填 —— 反过来
-- （先调用后记账）会在进程崩溃时留下「钱动了但没有记录」的窗口。
CREATE TABLE ops_case_action (
    id uuid PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES ops_case(id) ON DELETE CASCADE,
    operation_id text NOT NULL,
    action varchar(32) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'pending',
    requested_by uuid NOT NULL,
    outcome text,
    error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT uq_ops_case_action_operation UNIQUE (operation_id),
    CONSTRAINT ck_ops_case_action_status CHECK (status IN ('pending', 'succeeded', 'failed')),
    CONSTRAINT ck_ops_case_action_kind CHECK (
        action IN ('retry_reconciliation', 'release_funds', 'dlt_replay', 'dlt_discard'))
);

CREATE INDEX idx_ops_case_action_case ON ops_case_action (case_id, created_at);

-- DLT 消息登记。此前 DLT 只有投递没有落地：消息躺在 topic 里，没人知道有多少、也没法重投。
-- (topic, partition, "offset") 唯一 —— Kafka 位点天然幂等，消费者重启重读不会登记两次。
-- offset 是保留字，列名加引号。
CREATE TABLE ops_dlt_message (
    id uuid PRIMARY KEY,
    topic text NOT NULL,
    partition integer NOT NULL,
    "offset" bigint NOT NULL,
    original_topic text NOT NULL,
    message_key text,
    payload text NOT NULL,
    error_summary text,
    status varchar(16) NOT NULL DEFAULT 'pending',
    replayed_at timestamptz,
    discarded_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_ops_dlt_position UNIQUE (topic, partition, "offset"),
    CONSTRAINT ck_ops_dlt_status CHECK (status IN ('pending', 'replayed', 'discarded'))
);

CREATE INDEX idx_ops_dlt_pending ON ops_dlt_message (created_at) WHERE status = 'pending';
