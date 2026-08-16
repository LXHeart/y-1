-- 门店粒度邀请（体验小项收尾：按门店粒度邀请）。
-- organization_invitation 增加可空 store_id：NULL = 组织级邀请（既有语义不变），
-- 非 NULL = 门店邀请——接受时落 store_membership（staff/manager）而非组织成员。
-- role 列沿用 varchar(32)（建表时无 CHECK 约束），门店邀请存 staff/manager。
-- 待接受唯一索引 (organization_id, email) WHERE pending 不变：同一 org 对同一邮箱
-- 同时只允许一封待接受邀请，无论组织级还是门店级——避免给被邀请人制造两份并列邀请的歧义。
ALTER TABLE organization_invitation ADD COLUMN IF NOT EXISTS store_id uuid;

CREATE INDEX IF NOT EXISTS idx_org_invitation_store
    ON organization_invitation(store_id)
    WHERE store_id IS NOT NULL;
