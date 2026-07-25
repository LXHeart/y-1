-- 草场 marketplace-service 第四个 schema（Epic 5 Slice 5A：engagement 结算确认）。
-- 纯增量，安全跑在已部署 V3 的 neon（marketplace_flyway_schema now at v4）。仍与 identity 共用 neon public schema。

-- 商家确认履约时间（ConfirmEngagement，HLD 10.3）。null = 未确认；非空 = 已确认，授权 SettlementWindowWorkflow 结算 capture。
-- 复用 task_application 作 engagement 载体（4F 决策④：engagement_ref = application.id），独立 engagement 聚合留后续 slice。
ALTER TABLE task_application ADD COLUMN confirmed_at timestamptz;
