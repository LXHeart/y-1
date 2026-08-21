-- 任务书 #44 登记扩展：组织级审计视图的读取路径。
-- organization_id 由生成链路写入（独立创作可空），组织视图按 (organization_id, created_at) 游标扫描。
CREATE INDEX IF NOT EXISTS idx_creation_generation_org
    ON creation_generation(organization_id, created_at DESC, id DESC)
    WHERE organization_id IS NOT NULL;
