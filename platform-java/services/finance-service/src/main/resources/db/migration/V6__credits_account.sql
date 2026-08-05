-- 草场 finance-service V6：用户积分账户 + 流水（GL-P3-AI-001 下属切片）。
--
-- 背景（积分 Java 原生化）：legacy Express 的 user_credits / credit_transactions（server/sql/008、009）
-- 一直是积分的唯一真相源——intelligence 的 CreditsClient 经 /internal/credits bridge 回调 legacy 直写这两张表，
-- /api/credits 读端也住 legacy。本迁移把积分存储与扣减/退款逻辑迁入 finance：V6 建表、V7 平迁存量。
-- 切换后 finance 成为单一真相源；legacy 两表冻结保留作回滚快照（Phase B 才删）。
--
-- 表结构逐字复刻 legacy schema（列名改 account_id 以贴合 finance house style，见 recommender_wallet）：
--   credits_account      —— 账号级余额（balance/total_earned/total_spent），镜像 user_credits。
--   credits_transaction  —— append-only 流水（amount 带符号、type 枚举、operation_id 幂等键），镜像 credit_transactions。
--
-- account_id 跨服务引用 app_users.id，**不建 FK**（database-per-service 约定，与 recommender_wallet.account_id 同口径）。
-- operation_id 部分唯一索引复刻 server/sql/009:12-14：同一 operation_id 全局只落一行（consume 与 refund 键不同，
-- 退款键为 refund:<consumeId>，由 CreditsService 派生），保证「一次扣减至多一次退款」+「重试不双扣」（GL-P0-CRED-001）。

CREATE TABLE credits_account (
    account_id   uuid PRIMARY KEY,                                      -- 跨服务引用 app_users.id，无 FK（database-per-service）
    balance      integer NOT NULL DEFAULT 0 CHECK (balance >= 0),       -- 非负：扣减用条件 UPDATE 保证，不靠 CHECK 兜底
    total_earned integer NOT NULL DEFAULT 0,
    total_spent  integer NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE credits_transaction (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    uuid NOT NULL,                                        -- 跨服务引用 app_users.id，无 FK
    amount        integer NOT NULL,                                     -- 带符号：consume 为负、reward/refund 为正
    balance_after integer NOT NULL,                                     -- 该笔落地后的余额快照（对账/UI 用）
    type          text NOT NULL CHECK (type IN ('purchase', 'reward', 'consume', 'refund')),
    feature       text,                                                 -- article_generation / video_analysis / admin_adjust ...
    note          text,
    operation_id  text,                                                 -- 幂等键；null = 历史无键行（不参与唯一性）
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_credits_txn_account ON credits_transaction(account_id, created_at DESC);

-- 幂等键唯一索引（复刻 server/sql/009:12-14 + V5 ledger 同模式）：部分索引排除 NULL operation_id 历史行。
CREATE UNIQUE INDEX idx_credits_txn_operation
    ON credits_transaction(operation_id)
    WHERE operation_id IS NOT NULL;
