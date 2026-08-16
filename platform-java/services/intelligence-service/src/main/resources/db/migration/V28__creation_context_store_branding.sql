-- 任务书 #24 Stage 4：门店品牌上下文（storeBranding）冻结进创作上下文快照。
-- 快照表 append/immutable：既有 trg_creation_context_immutable UPDATE 触发器继续生效，
-- 新列仅参与 INSERT；历史行回填空对象（无门店品牌约束）。
ALTER TABLE creation_context_snapshot
    ADD COLUMN store_branding_snapshot jsonb NOT NULL DEFAULT '{}';
