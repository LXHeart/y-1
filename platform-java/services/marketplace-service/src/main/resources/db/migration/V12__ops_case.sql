-- 草场 marketplace V12：运营处置台 case 登记 + 审计流水（GL-P1-OPS-001 Stage 1）。
--
-- 背景：阻断/暂缓已经发生，但运营没有可查、可处置、可审计的载体。
--   · settlement_reconciliation(status='blocked', reason) 已落库（V8），但它是**对账请求**表，
--     只覆盖 DisputeFinalized 触发的对账路径，且没有处置状态、没有审批、没有审计。
--   · SettlementActivityImpl 的 hold（reason=open_dispute / verification_failed）**只 outbox.append
--     一条 SettlementHeld 事件**，没有任何持久行 —— 运营无从知道当前有多少笔被暂缓。
-- 本表把「需要人工处置的事」统一登记为 case，与来源表解耦（source_kind + source_ref 指回去）。
--
-- 幂等：UNIQUE(source_kind, source_ref) —— 同一阻断/暂缓至多一张单。登记方用 ON CONFLICT DO NOTHING，
-- 故重试、Kafka 重投、Temporal activity 重跑都不会开出第二张单。
--
-- 状态机 open→in_review→(approved|rejected)→resolved：
--   open       登记完成，无人认领
--   in_review  已提审（submitted_by 填入），等另一个人审批
--   approved   审批通过，处置动作可执行（Stage 2 的重试/补偿在此态下才允许）
--   rejected   审批驳回，终态（不再处置；如需重来则新开 case）
--   resolved   处置执行完毕，终态
--
-- 双人审批在 **DB 层** 兜住，不只靠应用层判断：见 ck_ops_case_two_person。
CREATE TABLE ops_case (
    id uuid PRIMARY KEY,
    -- 来源分类。settlement_blocked → settlement_reconciliation.source_event_id；
    -- settlement_held → task_application.id（SettlementHeld 的 aggregateId）。
    -- Stage 2 加 dlt_message；Verification inconclusive 不建 case（按设计不阻断结算，属「待判定」查询）。
    source_kind varchar(48) NOT NULL,
    source_ref text NOT NULL,
    -- 冗余业务坐标，供队列筛选与详情展示，避免每行都回查来源表。
    organization_id text,
    application_id text,
    reason varchar(64) NOT NULL,               -- 来源给出的阻断原因（finance_blocked / open_dispute / verification_failed …）
    severity varchar(16) NOT NULL DEFAULT 'normal',  -- normal / high（资金类阻断置 high）
    status varchar(16) NOT NULL DEFAULT 'open',
    version bigint NOT NULL DEFAULT 1,         -- 乐观锁（同 dispute_case 口径）
    -- 双人审批：提审人与审批人必须是不同账号。
    submitted_by uuid,
    submitted_at timestamptz,
    submit_note text,
    approved_by uuid,
    approved_at timestamptz,
    approve_note text,
    resolved_at timestamptz,
    resolution varchar(32),                    -- Stage 2 处置结果（retried / compensated / discarded …）
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_ops_case_source UNIQUE (source_kind, source_ref),
    CONSTRAINT ck_ops_case_status CHECK (status IN ('open', 'in_review', 'approved', 'rejected', 'resolved')),
    CONSTRAINT ck_ops_case_severity CHECK (severity IN ('normal', 'high')),
    -- 双人审批硬约束：有审批人时必须已提审，且审批人 ≠ 提审人。
    -- 放在 DB 层是刻意的 —— 应用层判断会被新调用路径绕过（历史上 permissions/grant 自升就是这么漏的），
    -- 而资金处置的四眼原则一旦被绕过没有第二道防线。
    CONSTRAINT ck_ops_case_two_person CHECK (
        approved_by IS NULL
        OR (submitted_by IS NOT NULL AND approved_by <> submitted_by)
    ),
    -- 进入 in_review 及之后必须有提审人；审批终态必须有审批人。
    CONSTRAINT ck_ops_case_submitter_present CHECK (
        status = 'open' OR submitted_by IS NOT NULL
    ),
    CONSTRAINT ck_ops_case_approver_present CHECK (
        status NOT IN ('approved', 'rejected') OR approved_by IS NOT NULL
    )
);

-- 队列默认视图：未终态按最早优先（时效）。
CREATE INDEX idx_ops_case_queue ON ops_case (created_at)
    WHERE status IN ('open', 'in_review', 'approved');
CREATE INDEX idx_ops_case_org ON ops_case (organization_id, created_at DESC);
CREATE INDEX idx_ops_case_application ON ops_case (application_id, created_at DESC);

-- 审计流水：**只追加不改不删**（无 UPDATE/DELETE 路径，仓储层只提供 append + list）。
-- 记录前后态，使「谁在什么时候把单子从哪推到哪」可完整重建。
CREATE TABLE ops_case_audit (
    id bigserial PRIMARY KEY,
    case_id uuid NOT NULL REFERENCES ops_case(id) ON DELETE CASCADE,
    action varchar(32) NOT NULL,               -- registered / submitted / approved / rejected / resolved / action_executed
    actor_account_id uuid,                     -- 系统登记为 NULL（registered）
    actor_role varchar(32),                    -- 执行时的平台角色（customer_service / admin / system）
    from_status varchar(16),
    to_status varchar(16),
    note text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_ops_case_audit_case ON ops_case_audit (case_id, id);
