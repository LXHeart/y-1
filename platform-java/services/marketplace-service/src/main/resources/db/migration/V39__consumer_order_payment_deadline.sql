-- 任务书 #41：未支付订单 TTL 关单。
-- payment_deadline 在下单时快照写入（created_at + payment-timeout-seconds，默认 900s），
-- 之后改配置不影响存量订单（镜像 redeem_deadline 的存行语义）。
-- NULL 语义：终态历史行与无法判定过期时间的行，dispatcher 视 NULL 为不过期。
ALTER TABLE consumer_order ADD COLUMN IF NOT EXISTS payment_deadline timestamptz;

-- 存量 pending_payment 行回填（清 Sandbox 积压的占用泄漏）；终态历史行不回填（NULL = 永不过期）。
UPDATE consumer_order SET payment_deadline = created_at + interval '900 seconds'
 WHERE status = 'pending_payment' AND payment_deadline IS NULL;

-- dispatcher 关单 claim 的扫描索引（镜像 idx_consumer_order_expiry 的部分索引模式）。
CREATE INDEX IF NOT EXISTS idx_consumer_order_payment_expiry
    ON consumer_order(payment_deadline)
    WHERE status = 'pending_payment';
