-- 草场身份域：身份档案 + 账号级活动身份 + 门店粒度成员。Epic 2 Slice 2G。
-- HLD 5.2 identity-profile / store-membership；1.3 事实 1/2（统一账号、活动身份）。
-- 跨实体引用沿用 V1/V2 约定：逻辑引用，不加强制 FK（应用层校验）。

-- 身份档案：账号开通的商家/推荐官身份（一个账号每种类型至多一条；消费者无需开通）
CREATE TABLE identity_profile (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    identity_type varchar(32) NOT NULL,        -- merchant / recommender
    organization_id uuid,                       -- 商家身份关联 org（推荐官为空）
    status varchar(32) NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, identity_type)
);

CREATE INDEX idx_identity_profile_account ON identity_profile(account_id);

-- 账号级活动身份（HLD 1.3 事实 2：同一时间仅一个；NULL = 消费者默认场景）
-- 注：per-session/多设备并发规则属 HLD D-08 延期；账号级为地基。
CREATE TABLE account_active_identity (
    account_id uuid PRIMARY KEY,
    active_identity_type varchar(32),           -- merchant / recommender / NULL(消费者)
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 门店粒度成员（HLD store-membership：门店范围成员和资源授权）
CREATE TABLE store_membership (
    id uuid PRIMARY KEY,
    store_id uuid NOT NULL,
    account_id uuid NOT NULL,
    role varchar(32) NOT NULL DEFAULT 'staff',  -- manager / staff
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (store_id, account_id)
);

CREATE INDEX idx_store_membership_store ON store_membership(store_id);
CREATE INDEX idx_store_membership_account ON store_membership(account_id);
