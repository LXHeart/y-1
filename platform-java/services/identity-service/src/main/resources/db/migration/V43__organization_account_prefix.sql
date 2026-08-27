-- 任务书 #49 D5：商家主体成员账号前缀。子账号登录名 = 前缀-登录名（如 caoyuan-zhangsan），
-- 前缀全局唯一（跨主体不撞名）；存量主体回填 org + id 前 8 位 hex（id 唯一故前缀唯一），
-- 管理员可在主体设置里改成自定义值（仅字母数字，改后只影响新建账号）。
ALTER TABLE organization ADD COLUMN IF NOT EXISTS account_prefix text;

UPDATE organization
SET account_prefix = 'org' || left(replace(id::text, '-', ''), 8)
WHERE account_prefix IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_organization_account_prefix ON organization (account_prefix);

ALTER TABLE organization ALTER COLUMN account_prefix SET NOT NULL;
