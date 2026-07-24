-- 草场 marketplace-service 第三个 schema（Epic 4 Slice 4F：资金型任务赏金 + 接受预留中间态）。
-- 纯增量，安全跑在已部署 V2 的 neon（marketplace_flyway_schema now at v3）。仍与 identity 共用 neon public schema。

-- 任务赏金（分；可空）。NULL/0 = 非资金型任务（accept 走 4B 直连，不经资金预留 Saga）；
-- >0 = 资金型任务（accept 经 AcceptApplicationReservationWorkflow → finance ReserveFunds）。不可 NOT NULL 无默认，
-- 否则 populated task 表迁移失败（同 V2 max_slots 模式）。
ALTER TABLE task ADD COLUMN bounty_cents bigint;

-- 注：task_application.status 是 varchar(32)，reserving 中间态无需 ALTER，仅枚举 + repository 泛化支持。
