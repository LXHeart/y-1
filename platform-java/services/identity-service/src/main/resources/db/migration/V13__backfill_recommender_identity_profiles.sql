-- 修复历史数据：V9 recommender_profile 曾允许独立创建，导致已有推荐官画像却没有可激活的身份。
-- 仅回填迁移执行时已存在的画像账号；不创建 session/audit/outbox，不自动改变任何会话的活动身份。
-- 无 app_users FK：沿用 identity_profile / recommender_profile 的逻辑引用约定，兼容 legacy 表尚未就绪的 Flyway 场景。
INSERT INTO identity_profile (id, account_id, identity_type, organization_id, status)
SELECT gen_random_uuid(), profile.account_id, 'recommender', NULL, 'active'
FROM recommender_profile AS profile
ON CONFLICT (account_id, identity_type) DO NOTHING;
