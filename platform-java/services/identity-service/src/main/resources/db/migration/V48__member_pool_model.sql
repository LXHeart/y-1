-- 任务书 #52：成员池与门店分配——存量迁移（纯 DML，幂等可重放）
-- 模型变更：所有子账号一律入主体池（organization_membership.role=member），
-- 门店身份降为池上的分配层（store_membership）。

-- ① 补池行：仅有门店挂靠、无组织关系的账号（#48~#51 期间建的 manager/staff 子账号）
--    一律补 organization_membership(member)。SELECT DISTINCT 防同账号挂多店时重复插行；
--    WHERE NOT EXISTS 防迁移重放重复。
INSERT INTO organization_membership(id, organization_id, account_id, role)
SELECT gen_random_uuid(), t.organization_id, t.account_id, 'member'
FROM (
    SELECT DISTINCT s.organization_id, sm.account_id
    FROM store_membership sm
    JOIN store s ON s.id = sm.store_id
) t
WHERE NOT EXISTS (
    SELECT 1 FROM organization_membership om
    WHERE om.organization_id = t.organization_id AND om.account_id = t.account_id
);

-- ② 审核流退役（#52 决策 A）：存量待审子账号一次性置 active——审核端点已删，
--    留在 pending_review 会变成永不可登录的死账号。限定占位邮箱域，不碰注册用户。
UPDATE app_users
SET status = 'active', updated_at = NOW()
WHERE status = 'pending_review' AND email LIKE '%@sub.grassland.invalid';

-- 存量 rejected 账号不动（终态语义保留）；member_review_required 列保留不删（回滚友好，
-- 读写方法已随审核流退役删除）。多店长门店不强制收敛——一店一店长闸只拦新操作
--（#52 决策 B/F），靠移除/调度自然收敛。
