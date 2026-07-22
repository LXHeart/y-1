-- 草场身份域：商家主体（Organization）。Epic 2 Slice 2E 地基。
-- owner_account_id 逻辑引用 app_users.id，不加强制 FK（HLD「不代表跨库外键」，同库亦用应用层校验）。
CREATE TABLE organization (
    id uuid PRIMARY KEY,
    owner_account_id uuid NOT NULL,
    name varchar(255) NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'active',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_organization_owner ON organization(owner_account_id);
