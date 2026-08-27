-- 任务书 #49 D3：存量清理——商家主体成员账号单一模型一次性收敛。
--
-- 规则（不可逆迁移，生产执行前必须备份确认）：
-- 1. 组织成员关系：删除全部「非主体 owner」的行（owner = role='owner' 或账号即
--    owner_account_id；两者取并集保守保留）。这些行来自已下线的邀请/挂靠通路。
-- 2. 门店成员关系：仅清「其组织层关系被清的账号」在本主体的门店行——纯门店经理
--    （无组织层挂靠，KYB 自建）天然不受影响。
-- 3. 身份档案：identity_profile 是快照表（非按 membership 实时解析），被清账号指向
--    本主体的 merchant 身份行一并删除，否则身份选择页出现幽灵主体；其各会话的活动身份
--    （V5 起按 session 存于 identity_session）若正停在 merchant 态则重置回消费者（NULL）。
-- 4. 待接受邀请全部作废（status='cancelled'，留痕不物理删）。
-- 5. 被清账号各落一条 SYSTEM 站内信：成员身份已被主体移除、平台账号本身保留。
--
-- 被清账号的平台账号（app_users 行）不动：那是本人自己注册的账号，不是主体资产。
-- 幂等：所有修改以「非 owner 关系存在」为前提，重放零行。
WITH removed_org AS (
    DELETE FROM organization_membership m
    USING organization o
    WHERE m.organization_id = o.id
      AND m.role <> 'owner'
      AND m.account_id <> o.owner_account_id
    RETURNING m.organization_id, m.account_id
), removed_store AS (
    DELETE FROM store_membership sm
    USING removed_org r, store s
    WHERE sm.account_id = r.account_id
      AND sm.store_id = s.id
      AND s.organization_id = r.organization_id
    RETURNING sm.account_id
), revoked_identities AS (
    DELETE FROM identity_profile ip
    USING removed_org r
    WHERE ip.account_id = r.account_id
      AND ip.identity_type = 'merchant'
      AND ip.organization_id = r.organization_id
    RETURNING ip.account_id
), reset_active AS (
    UPDATE identity_session sess
    SET active_identity_type = NULL
    FROM revoked_identities ri
    WHERE sess.account_id = ri.account_id
      AND sess.active_identity_type = 'merchant'
    RETURNING sess.account_id
), cancelled_invitations AS (
    UPDATE organization_invitation
    SET status = 'cancelled'
    WHERE status = 'pending'
    RETURNING id
)
INSERT INTO notification (account_id, category, event_type, title, body, link_path, payload)
SELECT DISTINCT r.account_id, 'system', 'LegacyMembershipRevoked',
       '你的商家主体成员身份已被移除',
       '平台已将商家成员体系切换为主体直建账号模式，你此前通过邀请/添加加入的成员身份已被移除。'
       || '你的平台账号本身保留，可正常登录使用；如需继续为该商家工作，请联系商家管理员创建成员账号。',
       '/me/organizations',
       jsonb_build_object('organizationId', r.organization_id)
FROM removed_org r;
