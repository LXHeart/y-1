-- 草场 marketplace V8：结算对账请求表（Slice 7B）。
--
-- 背景：此前 TrustEventProcessor 收到 DisputeFinalized 就直接派生 EngagementSettled，
-- 但「争议终局」≠「finance 已完成 release/capture/reverse」。客服终审路径甚至会在
-- workflow 动钱之前就发出 DisputeFinalized。于是在钱还没到位（或被人工阻断）时
-- 结算态就翻成了 settled。
--
-- 本表把对账请求**持久化**：消费 DisputeFinalized 时在同一 Inbox 事务里落一行 pending，
-- 由 SettlementReconciliationDispatcher 确定性地启动 SettlementReconciliationWorkflow，
-- workflow 读 trust/finance 权威状态、幂等补执行缺失的钱动作，确认后才写 EngagementSettled。
-- Kafka ACK 只依赖 Inbox + 本行提交；Temporal 宕机或进程崩溃都不再丢业务事件。
--
-- source_event_id（trust 事件 id）作 PK：Kafka at-least-once 重投天然去重。
-- dispute_id 唯一：同一争议只对账一次（appeal/再开产生新 dispute_id → 新行）。
-- application_id = engagement_ref = task_application.id（跨表逻辑引用，无 FK）。
-- status: pending(待派发) / started(已派发) / reconciled(已结算) / blocked(人工/冲突)。
CREATE TABLE settlement_reconciliation (
    source_event_id text PRIMARY KEY,
    dispute_id text NOT NULL,
    application_id text NOT NULL,
    organization_id text,
    final_decision text NOT NULL,
    workflow_id text NOT NULL,
    status text NOT NULL DEFAULT 'pending',
    reason text,
    dispatch_attempt int NOT NULL DEFAULT 0,
    next_dispatch_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_settlement_reconciliation_dispute ON settlement_reconciliation(dispute_id);
CREATE INDEX idx_settlement_reconciliation_dispatch ON settlement_reconciliation(status, next_dispatch_at);
CREATE INDEX idx_settlement_reconciliation_application ON settlement_reconciliation(application_id, created_at DESC);
