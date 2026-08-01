-- 草场 identity V14：事务邮件 delivery outbox（GL-P1-NOTIFY-001）。
--
-- 背景：站内通知（V11）已覆盖「主动刷新才看见」的缺口，但高价值动作（被邀请、报名被接受、凭证被退回、
-- 争议、结算、资金到账）没有邮件通道——用户不在线就错过。现有 SmtpMailSender 只能同步直发验证码/邀请，
-- 邀请邮件还是 best-effort 吞错（失败仅 log，无重试/持久化）。
--
-- 本表是「第五份 outbox」：复用 marketplace/trust/finance/identity 四份领域 outbox 的
-- 「append-in-tx → claim → 外部 send → markSent/markFailure+退避」骨架，区别仅：
--  ① 外部 send 是 SMTP 而非 Kafka；
--  ② 领域 outbox 失败无限重试，邮件坏地址不能如此——加 status='dead' 死信封顶（默认 5 次）。
--
-- 关键不变式：
-- - source_event_id + recipient 唯一：一个事件给同一收件人只入队一封（幂等，吸收 at-least-once 事件重投）。
--   非部分索引（同 V11 uq_notification_event_account）：Postgres UNIQUE 允许多个 NULL，
--   source_event_id IS NULL 的非事件邮件仍可重复；同时让 ON CONFLICT 能直接命中。
-- - 内容存渲染好的字符串（subject/body）：emit 时一次性渲染入队，publisher 职责单一（只发）。
-- - 同事务写入：mail_outbox.append 与 notification.insertIfAbsent 在 NotificationEventProcessor 同一
--   R2DBC 事务内，保证「通知落库 ⇔ 邮件入队」原子。
CREATE TABLE mail_outbox (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_event_id text,                           -- 溯源 + 幂等；NULL = 非事件派生（预留）
    recipient text NOT NULL,                        -- 收件人邮箱（邀请可发给未注册邮箱）
    subject text NOT NULL,
    body text NOT NULL,
    category varchar(32),                           -- invitation/engagement/dispute/wallet（便于按类别开关）
    status varchar(16) NOT NULL DEFAULT 'pending'   -- pending / sent / dead
        CHECK (status IN ('pending', 'sent', 'dead')),
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claimed_until timestamptz,
    claim_token uuid,
    last_error_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz
);

-- 一个事件对同一收件人只入队一封邮件。
CREATE UNIQUE INDEX uq_mail_outbox_event_recipient
    ON mail_outbox(source_event_id, recipient);

-- publisher 派发扫描：仅 pending 行，按 next_attempt_at/claim 状态/创建序。
CREATE INDEX idx_mail_outbox_dispatch
    ON mail_outbox(next_attempt_at, claimed_until, created_at)
    WHERE status = 'pending';
