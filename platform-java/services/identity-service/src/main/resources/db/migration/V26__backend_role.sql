-- GL-P2-ADMIN-001 后台角色 RBAC 地基：多值后台角色关联表。
--
-- PRD §11.8 列了 8 种后台角色（审判官走 recommender + judge 池，正交于本表，故不列）。
-- 与 app_users.role（单值 text，保留作业务标识/兜底）正交：一个账号可同时持有多个后台角色。
-- identity 是 account 权威，edge-bff 读本表组装逗号分隔的 role claim 签入断言（TTL 60s）。
--
-- backfill：现有 app_users.role IN ('admin','customer_service') 的行迁入本表
-- （admin → platform_admin 超集；customer_service → customer_service）。app_users.role 字段不动（向后兼容）。

CREATE TABLE backend_role (
    account_id  uuid NOT NULL,                          -- 引用 app_users.id，无 FK（identity house style）
    role        varchar(32) NOT NULL,                   -- BackendRole dbValue（snake_case）
    granted_at  timestamptz NOT NULL DEFAULT now(),
    granted_by  uuid,                                   -- 授予者 account_id（审计用，可 null）
    PRIMARY KEY (account_id, role)
);

CREATE INDEX idx_backend_role_account ON backend_role(account_id);

-- backfill 现有 admin → platform_admin
INSERT INTO backend_role(account_id, role)
SELECT id, 'platform_admin' FROM app_users WHERE lower(role) = 'admin'
ON CONFLICT DO NOTHING;

-- backfill 现有 customer_service（保持原 dbValue，对齐 BackendRole.CUSTOMER_SERVICE）
INSERT INTO backend_role(account_id, role)
SELECT id, 'customer_service' FROM app_users WHERE lower(role) = 'customer_service'
ON CONFLICT DO NOTHING;
