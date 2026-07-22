-- 草场身份域：门店、组织成员关系、三级商家准入权限。Epic 2 Slice 2F。
-- 对应 HLD：6.3 MERCHANT_ORGANIZATION ||--o{ STORE；5.2 merchant-organization（成员关系和权限委派）；1.3 事实 7（三级商家准入）。
-- 跨实体引用沿用 V1 约定：逻辑引用，不加强制 FK（应用层校验）。

-- 门店（商家主体下辖多门店）
CREATE TABLE store (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    name varchar(255) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_store_organization ON store(organization_id);

-- 组织成员关系 + 角色（owner / admin / member）。UNIQUE 保证一个账号在同一 org 只有一条成员关系。
CREATE TABLE organization_membership (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    account_id uuid NOT NULL,
    role varchar(32) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, account_id)
);

CREATE INDEX idx_membership_org ON organization_membership(organization_id);
CREATE INDEX idx_membership_account ON organization_membership(account_id);

-- 三级商家准入权限（draft / basic_publish / finance_transaction），org 级 capability，默认草稿。
ALTER TABLE organization ADD COLUMN permission_tier varchar(32) NOT NULL DEFAULT 'draft';

-- 回填：存量 org 的 owner 纳入成员表（greenfield，行数极少；让鉴权统一走成员表，owner 也出现在成员列表）。
INSERT INTO organization_membership (id, organization_id, account_id, role)
SELECT gen_random_uuid(), id, owner_account_id, 'owner' FROM organization;
