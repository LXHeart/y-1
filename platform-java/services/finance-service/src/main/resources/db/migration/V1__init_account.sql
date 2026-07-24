-- 草场 finance-service 首个 schema（Epic 4 Slice 4D：账户/余额地基）。
-- 独立 Flyway 历史：finance 用 spring.flyway.table=finance_flyway_schema（identity 用 flyway_schema_history、marketplace 用 marketplace_flyway_schema）。
-- 三服务共用 neon 集群 public schema，表名不冲突（finance_account/finance_outbox）。

-- 资金账户（ledger 根实体，HLD 5.4「保存金融余额」）。一 org 一账户。
CREATE TABLE finance_account (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL UNIQUE,        -- 一 org 一账户；跨服务 ref（identity 域），database-per-service 不加 FK
    balance_cents bigint NOT NULL DEFAULT 0 CHECK (balance_cents >= 0),  -- 非负不变式（Saga 预留/扣减将依赖）
    currency varchar(8) NOT NULL DEFAULT 'CNY',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- outbox（复刻 marketplace_outbox 精简版：本 slice 仅写表，Kafka 发布器留后续）。
CREATE TABLE finance_outbox (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id text NOT NULL UNIQUE,
    event_type text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    payload json NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
