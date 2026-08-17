-- 草场 finance V19：审判官激励（任务书 #31 / ADR-D15）。
--
-- credits_transaction.type CHECK 扩展新值 judge_reward（V6 加列先例）：发放走既有 CreditsService.award
-- 幂等闭环（operation_id = judge-reward:{disputeId}:{round}:{judgeAccountId} 唯一索引吸收重放），
-- type 区分「审判奖励」与注册赠送/运营调账，对账口径不同。
--
-- finance 首个 Kafka 业务消费者的 inbox 幂等表（镜像 identity identity_inbox / marketplace inbox 形态）：
-- (consumer_name, event_id) 唯一 + 内容 SHA-256 校验（同 ID 异内容 → 契约错误进 DLT，不静默覆盖）。

-- V6 的内联匿名 CHECK 系统名为 credits_transaction_type_check（PG <table>_<column>_check 命名）。
-- 置换为新值集（含 judge_reward）；两段式避免锁表。重放安全（IF EXISTS + DROP 再 ADD）。
ALTER TABLE credits_transaction DROP CONSTRAINT IF EXISTS credits_transaction_type_check;
ALTER TABLE credits_transaction DROP CONSTRAINT IF EXISTS ck_credits_transaction_type;
ALTER TABLE credits_transaction ADD CONSTRAINT credits_transaction_type_check
    CHECK (type IN ('purchase', 'reward', 'consume', 'refund', 'judge_reward')) NOT VALID;
ALTER TABLE credits_transaction VALIDATE CONSTRAINT credits_transaction_type_check;

CREATE TABLE finance_inbox (
    consumer_name text NOT NULL,
    event_id text NOT NULL,
    event_type text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    payload_sha256 text NOT NULL,
    source_topic text NOT NULL,
    source_partition int NOT NULL,
    source_offset bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);

CREATE INDEX idx_finance_inbox_offset ON finance_inbox(consumer_name, source_partition, source_offset);
