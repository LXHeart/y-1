-- GL-P2-FIN-002 / ADR-D01：不可变双录账本（Journal / Posting）—— 资金流转的内部真相源 + 审计账。
--
-- 背景：finance 此前是 Sandbox 单行余额简化模型（finance_account / recommender_wallet 的条件
-- UPDATE 直接改余额行）。HLD §6.4 要求「每个 Journal ≥ 2 Posting、借贷合计为零、Finalized Journal
-- 不可改、错误经 Reversal Journal、余额可由 Posting 重建」。本迁移在**不触动既有余额行**的前提下
-- 追加 journal + posting 两张 append-only 表（Approach B：余额行保留为投影+并发守卫，账本是真相源）。
--
-- operation_id 部分唯一索引复用 credit-bridge（server/sql/009）幂等模式：历史/无键行不受约束。
-- OPENING 回填存量余额（finance_account + recommender_wallet），使投影自迁移起可由 Posting 重建。

-- 账本头：一次资金移动 = 一条 journal（不可变，只追加）。
CREATE TABLE journal (
    id uuid PRIMARY KEY,
    journal_type varchar(32) NOT NULL,          -- DEPOSIT/RESERVE/RELEASE/CAPTURE/REVERSE/WITHDRAW/OPENING
    operation_id text,                          -- 幂等键（Saga 重试安全）；null = 一次性用户动作（credit/withdraw）
    currency varchar(8) NOT NULL DEFAULT 'CNY',
    organization_id uuid,                       -- 冗余便于按组织审计；wallet 类可能为 null
    engagement_ref text,                        -- 跨服务引用 marketplace application/engagement（无 FK）
    memo text,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 幂等安全网（同 outbox event_id 现状：上游状态守卫已是幂等源，此索引防「同一 operation_id 重复记账」）。
-- 部分索引：operation_id IS NULL（credit/withdraw 等一次性动作）不参与唯一性。
CREATE UNIQUE INDEX idx_journal_operation
    ON journal(operation_id)
    WHERE operation_id IS NOT NULL;

CREATE INDEX idx_journal_org ON journal(organization_id, created_at DESC);
CREATE INDEX idx_journal_engagement ON journal(engagement_ref) WHERE engagement_ref IS NOT NULL;

-- 账本明细：每条 journal ≥ 2 posting，借贷合计为零（HLD §6.4）。
-- 复合键记账（account_type + account_owner + account_ref），不建独立 accounts 表——余额由 SUM 派生。
--   ESCROW:{orgId}       平台对商家的托管负债（投影 = finance_account.balance_cents）
--   RESERVE:{orgId}:{ref} 已 earmark 待结算的预留池（派生，无行）
--   WALLET:{accountId}   推荐官可提现余额（投影 = recommender_wallet.balance_cents）
--   FEE                  平台抽成收入（派生）
--   EXTERNAL:{channel}   PSP/存管对手方（channel=sandbox 为 stub；真实 PSP 时此腿接 adapter）
-- 负债/收入类账户：credit 增、debit 减；余额 = SUM(credit) - SUM(debit)。
CREATE TABLE posting (
    id uuid PRIMARY KEY,
    journal_id uuid NOT NULL REFERENCES journal(id),
    account_type varchar(16) NOT NULL,
    account_owner text,                         -- orgId / accountId / channel；FEE 为 null
    account_ref text,                           -- RESERVE 用 engagementRef；其余多为 null
    direction varchar(6) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_posting_journal ON posting(journal_id);
CREATE INDEX idx_posting_account ON posting(account_type, account_owner);

-- OPENING 回填：为每个有余额的存量账户种一条不可变 journal，使投影可由 Posting 重建（HLD §6.4）。
-- 借外部腿（EXTERNAL:sandbox）对贷存量余额，金额=迁移时余额。operation_id 确定性唯一，便于审计追溯。
-- 历史 reservation 状态不回填（funds_reservation 表即其权威）；余额行已是 net-of-reservation 的可用额。

-- finance_account → ESCROW:{orgId}
INSERT INTO journal (id, journal_type, operation_id, currency, organization_id, memo, created_at)
SELECT gen_random_uuid(), 'OPENING', 'opening:escrow:' || organization_id::text, 'CNY', organization_id,
       'OPENING escrow balance', now()
  FROM finance_account
 WHERE balance_cents > 0;

INSERT INTO posting (id, journal_id, account_type, account_owner, direction, amount_cents, created_at)
SELECT gen_random_uuid(), j.id, 'EXTERNAL', 'sandbox', 'DEBIT', fa.balance_cents, now()
  FROM finance_account fa
  JOIN journal j ON j.operation_id = 'opening:escrow:' || fa.organization_id::text
 WHERE fa.balance_cents > 0;

INSERT INTO posting (id, journal_id, account_type, account_owner, direction, amount_cents, created_at)
SELECT gen_random_uuid(), j.id, 'ESCROW', fa.organization_id::text, 'CREDIT', fa.balance_cents, now()
  FROM finance_account fa
  JOIN journal j ON j.operation_id = 'opening:escrow:' || fa.organization_id::text
 WHERE fa.balance_cents > 0;

-- recommender_wallet → WALLET:{accountId}
INSERT INTO journal (id, journal_type, operation_id, currency, memo, created_at)
SELECT gen_random_uuid(), 'OPENING', 'opening:wallet:' || account_id::text, 'CNY',
       'OPENING wallet balance', now()
  FROM recommender_wallet
 WHERE balance_cents > 0;

INSERT INTO posting (id, journal_id, account_type, account_owner, direction, amount_cents, created_at)
SELECT gen_random_uuid(), j.id, 'EXTERNAL', 'sandbox', 'DEBIT', w.balance_cents, now()
  FROM recommender_wallet w
  JOIN journal j ON j.operation_id = 'opening:wallet:' || w.account_id::text
 WHERE w.balance_cents > 0;

INSERT INTO posting (id, journal_id, account_type, account_owner, direction, amount_cents, created_at)
SELECT gen_random_uuid(), j.id, 'WALLET', w.account_id::text, 'CREDIT', w.balance_cents, now()
  FROM recommender_wallet w
  JOIN journal j ON j.operation_id = 'opening:wallet:' || w.account_id::text
 WHERE w.balance_cents > 0;
