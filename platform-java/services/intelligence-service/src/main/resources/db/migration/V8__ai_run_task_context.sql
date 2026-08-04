-- GL-P3-AI-001：ai_run 任务上下文快照列（TaskContext，HLD §6.2「模型必须保存使用时的版本快照」）。
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v8）。
--
-- Run 起始冻结当时解析出的平台模型版本 + 是否经授权回退，配合既有 price_table_version，
-- 使每条 Run 可复现、计费口径冻结（D-11：Run 记 priceTableVersion；§6.2：模型版本快照）。
-- 表新且未切流，无 backfill。

ALTER TABLE ai_run ADD COLUMN platform_model_version int;
ALTER TABLE ai_run ADD COLUMN fallback_authorized boolean NOT NULL DEFAULT false;
