-- 草场 trust-service Slice 6C 审判（adjudication）：扩 dispute_case + 审判官/面板/投票/上诉表。
-- 独立 Flyway 历史表 trust_flyway_schema（续 V1 受理 / V2 outbox published）。

-- ① 扩 dispute_case：审判轮次 / 聚合版本 / 上诉状态 / 终局裁决 / 脱敏证据句柄。
ALTER TABLE dispute_case
    ADD COLUMN round int NOT NULL DEFAULT 0,
    ADD COLUMN version bigint NOT NULL DEFAULT 1,
    ADD COLUMN appeal_state varchar(32) NOT NULL DEFAULT 'none',     -- none/pending/filed/withdrawn
    ADD COLUMN final_decision varchar(32),                           -- 终局裁决（含客服覆盖）
    ADD COLUMN final_decided_by uuid,                                -- 终局裁决者（客服 account_id）
    ADD COLUMN evidence_ref text;                                    -- 脱敏证据句柄（D-10 占位）

-- ② 重建 partial unique：从「至多一个 open」放宽为「至多一个未终局（status <> 'final'）」。
--    审判中间态（voting/decided/appealed）也占用该 engagement 的唯一活跃争议槽，持续阻塞结算。
DROP INDEX IF EXISTS uniq_dispute_open_per_engagement;
CREATE UNIQUE INDEX uniq_dispute_active_per_engagement
    ON dispute_case(engagement_ref) WHERE status <> 'final';

-- ③ 审判官池（HLD 3.1「符合条件的推荐官」）。eligibility_tier 预留声誉准入（声誉模块未建，本轮用配置阈值占位）。
CREATE TABLE judge (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL UNIQUE,         -- 推荐官账号（与 identity 账号同空间）
    organization_id uuid,                    -- 归属组织（同组织冲突排除用；可空=平台级审判官）
    eligibility_tier int NOT NULL DEFAULT 1, -- 资格等级（>= trust.adjudication.judge-eligibility-tier 可被抽）
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_judge_active_tier ON judge(active, eligibility_tier);

-- 审判官-组织利益冲突（排除与争议组织有冲突的审判官）。
CREATE TABLE judge_conflict (
    judge_id uuid NOT NULL REFERENCES judge(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL,
    PRIMARY KEY (judge_id, organization_id)
);

-- ④ 面板分配（每轮 panel-size 官）。UNIQUE(dispute_id,round,judge) 保证分配幂等。
CREATE TABLE dispute_panel_assignment (
    id bigserial PRIMARY KEY,
    dispute_id uuid NOT NULL,
    round int NOT NULL,
    judge_account_id uuid NOT NULL,
    assigned_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(dispute_id, round, judge_account_id)
);
CREATE INDEX idx_panel_dispute_round ON dispute_panel_assignment(dispute_id, round);

-- ⑤ 投票（每官每轮一票，幂等）。vote: for_merchant / for_recommender / abstain。
CREATE TABLE dispute_vote (
    id bigserial PRIMARY KEY,
    dispute_id uuid NOT NULL,
    round int NOT NULL,
    judge_account_id uuid NOT NULL,
    vote varchar(16) NOT NULL,
    rationale text,
    voted_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(dispute_id, round, judge_account_id)
);
CREATE INDEX idx_vote_dispute_round ON dispute_vote(dispute_id, round);

-- ⑥ 上诉（每争议至多一条；HLD 10.5 上诉 → 客服终审队列）。
CREATE TABLE dispute_appeal (
    dispute_id uuid PRIMARY KEY,
    appealed_by uuid NOT NULL,
    appealed_at timestamptz NOT NULL DEFAULT now(),
    status varchar(32) NOT NULL DEFAULT 'filed',  -- filed / withdrawn / decided
    final_decision varchar(32),                   -- 客服终审裁决
    final_decided_by uuid,
    decided_at timestamptz
);
