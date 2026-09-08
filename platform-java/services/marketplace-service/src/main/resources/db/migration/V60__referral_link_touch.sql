-- 草场 marketplace V60：不透明推广链接（rlid）与触达事实（任务书 #98 / D98-01、D98-02）。
--
-- 链接由服务端发放：id 为不可枚举随机串（非账号 ID、非 uuid），携带发放时的归因口径版本快照；
-- status 三态：active（可归因）/ ended（本人终止或推广结束联动）/ expired（超过 expires_at）。
-- 过期为读时判定（status='active' 且 expires_at <= now() 即视为 expired），无需后台扫描落状态；
-- ended 由本人终止端点写入（ended_reason='manual'），推广结束不回写存量行——解析时按
-- 「链接任务是否仍是该套餐进行中推广」判失效（D98-01 联动语义），原因可解释。
-- 发放幂等：先查现行 active 链接再插入；并发双插产生两条 active 均有效（last-touch 天然兼容），
-- 不做唯一约束。

CREATE TABLE IF NOT EXISTS referral_link(
  id varchar(32) PRIMARY KEY,
  recommender_account_id uuid NOT NULL,
  task_id uuid NOT NULL,
  policy_version varchar(32) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'active',
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  ended_at timestamptz,
  ended_reason varchar(32),
  CONSTRAINT ck_referral_link_status CHECK (status IN ('active', 'ended', 'expired'))
);

CREATE INDEX IF NOT EXISTS idx_referral_link_owner ON referral_link(recommender_account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_referral_link_task ON referral_link(task_id);

-- 触达事实（D98-02）：消费者经 rlid 进入购买页即落行；未登录触达 consumer_account_id 为 NULL。
-- 重复触达各记一行（窗口判定取 touched_at 最新行），不做唯一约束。
CREATE TABLE IF NOT EXISTS referral_touch(
  id uuid PRIMARY KEY,
  referral_link_id varchar(32) NOT NULL,
  consumer_account_id uuid,
  touched_at timestamptz NOT NULL DEFAULT now(),
  context varchar(64) NOT NULL DEFAULT 'landing',
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_referral_touch_consumer ON referral_touch(consumer_account_id, touched_at DESC);
CREATE INDEX IF NOT EXISTS idx_referral_touch_link ON referral_touch(referral_link_id, touched_at DESC);

-- 归因事实行（V34 append-only 审计表）补 rlid 语义：下单经 rlid 归因时记录链接与触达时间，
-- 供解释读模型与审计；存量行两列为 NULL（D98-07：历史订单归因不回填）。
ALTER TABLE consumer_order_attribution ADD COLUMN IF NOT EXISTS referral_link_id varchar(32);
ALTER TABLE consumer_order_attribution ADD COLUMN IF NOT EXISTS touched_at timestamptz;
