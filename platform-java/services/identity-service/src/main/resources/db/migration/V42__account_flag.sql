-- 任务书 #48：子账号首登强制改密标记（D3/D14）。
--
-- 放旁表而非 app_users 加列：app_users 是 database-bootstrap 管的共享表，且在
-- identity 的 IT 环境里由 @BeforeAll 手工建表（晚于本服务 Flyway），历史约束是
-- 本服务迁移不得引用这些表（UserLookup/SQL 运行时读可以，DDL 不行）。
-- Verifier（DatabaseSchemaVerifier.verifyTable）只检查期望列存在性、不拒绝额外列，
-- 但测试基建顺序决定了 DDL 必须落自有表。
CREATE TABLE IF NOT EXISTS account_flag (
    account_id           uuid PRIMARY KEY,
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
