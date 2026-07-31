-- 草场 identity V11：站内通知中心（Slice 12 Stage 1）。
--
-- 背景：撮合链路上每一步（被邀请、报名被接受、凭证被退回、结算、争议）都只在对方**主动刷新页面**时才被发现。
-- 关键动作没有落地通道，用户得靠轮询 UI 猜进度。故先把「站内通知」这个收件箱落成一等聚合。
--
-- 归属：通知的收件人是**账号**，收件箱是账号的个人视图（与 /api/me/* 同族），故落 identity。
-- 领域事实仍归各自服务（marketplace 的履约、trust 的争议）——这里只存**面向人的投影**，不是第二份真相。
--
-- 关键不变式：
-- - account_id 无 FK：跨库引用 app_users（database-per-service，沿用 V9 recommender_profile 约定）。
-- - source_event_id + account_id 唯一：一个领域事件对同一收件人只产生一条通知。
--   这是幂等的**第二道**闸门（第一道是 V12 identity_inbox 的 consumer_name+event_id）——
--   inbox 保证同一消费者不重复处理，本索引额外保证「换消费者名/重放/手工补投」也不产生重复收件。
-- - link_path 只存**站内相对路径**，不存绝对 URL：通知是可被外部事件驱动写入的内容，
--   若允许绝对 URL，写入方就能让点击通知的用户跳到任意站外地址（开放重定向）。

CREATE TABLE notification (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL,                       -- 收件人；无 FK（跨库引用 app_users）
    category varchar(32) NOT NULL,                  -- invitation / permission / engagement / dispute / wallet / system
    event_type text NOT NULL,                       -- 溯源用的领域事件类型（MembershipInvited …）
    title text NOT NULL,
    body text,
    link_path text,                                 -- 站内跳转相对路径（如 /me/invitations）；禁绝对 URL
    source_event_id text,                           -- 溯源 + 幂等；NULL = 非事件派生（预留手工/系统通知）
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,     -- 前端渲染所需的少量结构化字段（orgId/taskId…）
    read_at timestamptz,                            -- NULL = 未读
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 一个事件对一个收件人只有一条通知。
-- 非部分索引：Postgres 的 UNIQUE 允许多个 NULL（NULL 互不相等），故 source_event_id IS NULL 的系统通知
-- 仍可重复插入；同时让 ON CONFLICT 能直接命中本索引（部分索引需带匹配 WHERE 才可被 ON CONFLICT 引用）。
CREATE UNIQUE INDEX uq_notification_event_account
    ON notification(source_event_id, account_id);

-- 列表主查询：我的收件箱按时间倒序（keyset 分页按 created_at 游标）。
CREATE INDEX idx_notification_account_created
    ON notification(account_id, created_at DESC);

-- 未读数与「只看未读」：未读通常远少于总量，用 partial index 而不是全表条件扫描。
CREATE INDEX idx_notification_unread
    ON notification(account_id)
    WHERE read_at IS NULL;
