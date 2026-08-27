-- 任务书 #49 D4/D6：子账号登录名旁表。app_users.email 是共享表（database-bootstrap V1）上的
-- NOT NULL UNIQUE 列，identity 迁移不可 ALTER（#48 D14 雷区）——登录名存自有旁表，
-- email 列以 {用户名}@sub.grassland.invalid 占位满足约束，成员绑定邮箱后 UPDATE 为真值。
-- 登录标识双查：先按本表 username 命中，miss 再按 app_users.email（存量邮箱账号零变化）。
CREATE TABLE IF NOT EXISTS account_username (
    account_id uuid PRIMARY KEY,
    username text NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now()
);
