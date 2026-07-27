-- 草场 marketplace V5：履约交付物（推荐官提交凭证 → 商家核验）。
--
-- 背景（撮合闭环缺的一环）：此前 accept 之后商家直接就能点「确认履约」，
-- 推荐官交什么、平台核什么**没有任何模型**——确认是凭空点的。PRD 第九章「自动核实引擎」
-- 的第一步就是「推荐官提交凭证（链接、截图、数据截图）」，没有这张表就无处落脚。
--
-- 本迁移只落**交付物本身**（发布链接 + 说明 + 商家核验状态）。截图/数据附件留待接入
-- 已有的对象存储三步上传后再加，不预先造字段。

CREATE TABLE engagement_submission (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES task_application(id),  -- 同库真 FK
    recommender_account_id uuid NOT NULL,        -- 冗余：提交人自查（跨服务引用 app_users，无 FK）
    content_url text NOT NULL,                   -- 发布链接（核实的主证据）
    note text,                                   -- 推荐官补充说明
    status varchar(32) NOT NULL DEFAULT 'submitted',  -- submitted / accepted / rejected
    review_note text,                            -- 商家退回原因
    reviewed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 一个履约同时只能有一份待核验的交付物（防重复提交刷屏）；
-- 被退回（rejected）后不占位，推荐官可以改好再交，形成「提交→退回→重交」的正常循环。
CREATE UNIQUE INDEX uq_submission_pending
    ON engagement_submission(application_id)
    WHERE status = 'submitted';

CREATE INDEX idx_submission_application ON engagement_submission(application_id, created_at DESC);
