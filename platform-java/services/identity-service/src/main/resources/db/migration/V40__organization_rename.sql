-- 草场 identity-service：商家主体更名审核流（2026-08-23 产品规则）。
--
-- 规则（PRD §2.1 补充）：
-- 1. 一个账号只能创建一个商家主体（POST /api/organizations 契约层 409 拦截，见 OrganizationController）；
-- 2. 主体名称变更须提交申请、经平台审核通过后才生效；
-- 3. 更名有 30 天冷却期：自上次变更（创建或上次更名生效）起 30 天内不可再次申请。
--
-- 表只落申请与审核轨迹；名称生效即 UPDATE organization.name（历史凭本表追溯）。
-- 风格随 V8：不建 FK（organization_id 由服务端写入并校验存在）。

CREATE TABLE IF NOT EXISTS organization_rename_request (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    requested_by_account_id uuid NOT NULL,
    current_name text NOT NULL,
    requested_name text NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'pending',      -- pending / approved / rejected
    requested_at timestamptz NOT NULL DEFAULT now(),
    reviewed_at timestamptz,
    reviewed_by_account_id uuid,
    review_note text
);

-- 同一主体同时只能有一份待审申请（重复提交走契约层 409，索引兜底并发）
CREATE UNIQUE INDEX IF NOT EXISTS uq_org_rename_pending
    ON organization_rename_request(organization_id)
    WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS idx_org_rename_by_org_time
    ON organization_rename_request(organization_id, requested_at DESC);
