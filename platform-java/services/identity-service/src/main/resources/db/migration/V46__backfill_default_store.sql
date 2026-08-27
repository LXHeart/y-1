-- 任务书 #50 D3：存量零门店主体自动补建默认门店（名 = 主体名，用户拍板④）。
-- 补建后这些主体自动进入单店模式（推导制：门店数 ≤1）。
-- 幂等：NOT EXISTS 守卫，重放零行。
INSERT INTO store (id, organization_id, name, status)
SELECT gen_random_uuid(), o.id, o.name, 'active'
FROM organization o
WHERE NOT EXISTS (SELECT 1 FROM store s WHERE s.organization_id = o.id);
