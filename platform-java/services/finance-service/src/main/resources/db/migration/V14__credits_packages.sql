-- 草场 finance-service V14：AI 用量套餐体系（平台内闭环 v1）——积分包 SKU 三表。
--
-- 设计（docs/superpowers/specs/2026-08-15-ai-credits-packages-design.md）：
--   credits_package          —— 运营可变壳（名称/描述/状态 + current 指针）。
--   credits_package_version  —— 不可变价格快照：调价 = 新版本行 + 指针切换（「配置不篡改历史」，
--                                镜像 marketplace package_version 范式）；订单永远引用具体 version。
--   credits_purchase_order   —— 购买订单：下单时冻结 price_cents/credits_amount；
--                                operation_id 全局唯一（购买幂等键）；积分入账复用
--                                credits_transaction(type='purchase')（V6 CHECK 已预留，零迁移）。
--
-- account_id 跨服务引用 app_users.id，不建 FK（database-per-service 约定）。

CREATE TABLE credits_package (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name               text NOT NULL,
    description        text NOT NULL DEFAULT '',
    status             text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'active', 'retired')),
    current_version_id uuid,                          -- 循环引用 version 表，建行后回填，不建 FK
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_credits_package_status ON credits_package(status);

CREATE TABLE credits_package_version (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    package_id     uuid NOT NULL,
    version        bigint NOT NULL CHECK (version >= 1),
    price_cents    bigint NOT NULL CHECK (price_cents > 0),
    credits_amount integer NOT NULL CHECK (credits_amount > 0),
    note           text NOT NULL DEFAULT '',
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (package_id, version)
);

CREATE INDEX idx_credits_package_version_pkg ON credits_package_version(package_id, version DESC);

CREATE TABLE credits_purchase_order (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id         uuid NOT NULL,                 -- 跨服务引用 app_users.id，无 FK
    package_id         uuid NOT NULL,
    package_version_id uuid NOT NULL,
    price_cents        bigint NOT NULL,               -- 下单时冻结（镜像 version 快照）
    credits_amount     integer NOT NULL,              -- 下单时冻结
    status             text NOT NULL DEFAULT 'created' CHECK (status IN ('created', 'paid', 'failed')),
    provider           text NOT NULL,
    provider_ref       text NOT NULL,
    operation_id       text NOT NULL UNIQUE,          -- 购买幂等键
    paid_at            timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_credits_purchase_order_account ON credits_purchase_order(account_id, created_at DESC);
