-- 草场 identity-service：按邮箱邀请组织成员。
--
-- 背景：组织成员只能按 account_id（UUID）添加，实际不可用——邀请人不可能知道对方的 UUID。
-- 直接补一个「按邮箱查 accountId」的端点会把 app_users 变成账号枚举探针（输入邮箱即可判定该邮箱是否注册），
-- 故改为**邀请流**：邀请人只写邮箱，系统不回答该邮箱是否有账号；被邀请人登录后自行看到并接受。
--
-- 关键不变式：
-- - 接受时校验「当前登录账号的邮箱 == 邀请邮箱」，故 id 泄露也无法被他人冒领；
-- - 同一 org 同一邮箱同时只能有一封待接受邀请（partial unique）；改邀请角色需先撤销再发。
-- - email 归一化为小写存储（app_users.email 同约定），比较时两侧都归一化。

CREATE TABLE organization_invitation (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    email text NOT NULL,                                  -- 归一化小写；无 FK（被邀请人可能尚未注册）
    role varchar(32) NOT NULL,                            -- admin / member（owner 不可经邀请授予）
    status varchar(32) NOT NULL DEFAULT 'pending',        -- pending / accepted / revoked / declined
    invited_by_account_id uuid NOT NULL,
    accepted_by_account_id uuid,                          -- 接受者；与 email 对应的账号
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 一个 org 对同一邮箱同时只有一封待接受邀请；终态（accepted/revoked/declined）不占位，可重新邀请。
CREATE UNIQUE INDEX uq_org_invitation_pending
    ON organization_invitation(organization_id, email)
    WHERE status = 'pending';

-- 被邀请人视角查询（GET /api/me/invitations）：按邮箱找待接受邀请。
CREATE INDEX idx_org_invitation_email
    ON organization_invitation(email)
    WHERE status = 'pending';

-- 组织侧列表。
CREATE INDEX idx_org_invitation_org ON organization_invitation(organization_id);
