-- 用户举报/投诉工单（PRD §11.8 后台客服「处理投诉、争议和申诉」——唯一 PRD 提及而未立项的横向能力）。
-- 用户侧通用举报入口：对任务/交付物/内容/订单/用户等平台对象提交投诉；客服在处置台承接。
-- 状态机：open（待受理）→ processing（受理调查中）→ resolved（办结，附结论）/ dismissed（不成立，附理由）；
-- 可重判（handle 幂等 UPDATE，结论可修正，处置留痕走 handler/note/handled_at）。
CREATE TABLE IF NOT EXISTS user_complaint (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_account_id varchar(64) NOT NULL,
    target_type         varchar(32) NOT NULL
                        CHECK (target_type IN ('task', 'submission', 'content', 'order', 'user', 'other')),
    target_id           varchar(128),
    reason              varchar(32) NOT NULL
                        CHECK (reason IN ('spam', 'fraud', 'inappropriate_content', 'rights_infringement', 'other')),
    description         varchar(500) NOT NULL,
    status              varchar(16) NOT NULL DEFAULT 'open'
                        CHECK (status IN ('open', 'processing', 'resolved', 'dismissed')),
    handler_account_id  varchar(64),
    resolution_note     varchar(500),
    handled_at          timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- 客服处置队列：按状态 + 时间倒序。
CREATE INDEX IF NOT EXISTS idx_user_complaint_status
    ON user_complaint (status, created_at DESC);

-- 用户侧「我的投诉」：按举报人 + 时间倒序。
CREATE INDEX IF NOT EXISTS idx_user_complaint_reporter
    ON user_complaint (reporter_account_id, created_at DESC);

-- 防重复刷单：同一举报人对同一对象同一原因的未办结投诉只允许一条（应用层据此 409）。
CREATE INDEX IF NOT EXISTS idx_user_complaint_open_dedupe
    ON user_complaint (reporter_account_id, target_type, target_id, reason)
    WHERE status IN ('open', 'processing');
