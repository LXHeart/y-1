-- GL-P2-ADMIN-002 推荐官平台认证审核流（可选，自助开通不受阻）。
--
-- 设计：recommender 身份仍是自助开通（POST /api/me/identities 即生效，IdentityOpened 不变）。
-- 本表承载一个**可选的平台认证**审核流：推荐官提交材料 → 内容审核员审核 → approved 获得认证徽标。
-- 未认证推荐官仍可报名任务，认证是加分项（商家筛选时可见、未来可挂等级权益）。
--
-- 克隆 kyb_verification_request 范式（V19）：status 状态机 pending→approved/rejected，
-- 同 account 只允许一个 open 态申请（防重复堆队，UNIQUE 部分索引）。

CREATE TABLE recommender_verification_request (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id            uuid NOT NULL,                        -- 跨服务引用 app_users.id，无 FK（identity house style）
    materials             jsonb,                                -- 提交材料（社交账号/作品链接等，自由 JSON）
    status                varchar(32) NOT NULL DEFAULT 'pending'
                              CHECK (status IN ('pending', 'approved', 'rejected')),
    reviewer_account_id   uuid,
    review_note           text,
    review_deadline       timestamptz,                           -- SLA 截止
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_recommender_verification_account ON recommender_verification_request(account_id);
CREATE INDEX idx_recommender_verification_status ON recommender_verification_request(status);
-- 同 account 只允许一个 open 态申请（pending 不重复堆队；approved/rejected 不约束）
CREATE UNIQUE INDEX uq_recommender_verification_open
    ON recommender_verification_request(account_id)
    WHERE status = 'pending';
