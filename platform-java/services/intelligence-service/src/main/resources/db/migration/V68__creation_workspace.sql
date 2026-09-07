-- 任务书 #92 C-02（D-01：复用 creation_draft 作唯一工作区存储，不建第二套项目表）。
-- 三个 jsonb 列：工作区恢复态 + 结果资产关联 + 运行关联。
--   workspace_json    ≤64KB 结构化恢复态（capability/runState/sourceLabel/currentStep/inputs），
--                     由服务端结构闸校验（敏感键拒绝，见 CreationWorkspace）；
--   result_asset_ids  生成结果落素材服务返回的资源 ID（去重 ≤20，只存 ID 不存 URL/二进制，D-04）；
--   run_ids           运行记录 ID（去重 ≤20）。
-- 只加列与默认值，不改既有约束/正文；旧行按默认值读取（{} / [] / []）。
-- ADD COLUMN IF NOT EXISTS：与全仓迁移重放测试约定一致，重放安全。
ALTER TABLE creation_draft
    ADD COLUMN IF NOT EXISTS workspace_json jsonb NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS result_asset_ids jsonb NOT NULL DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS run_ids jsonb NOT NULL DEFAULT '[]';
