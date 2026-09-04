-- 任务书 #75：套餐推广任务化（2026-09-04 五拍板）。
-- D1 任务-套餐关联：task.commerce_package_id + 每套餐同时至多一个进行中推广任务（部分唯一索引，
--    进行中 = 非终态集合 draft/pending_review/published；closed/cancelled 释放占用）。
-- D2 佣金二形态：commerce_package_version.recommender_fixed_cents（比例 XOR 固定额，服务层判；DDL 只挡负数）。
-- D3 核销 48h 冷静期：consumer_order.split_eligible_at（核销时快照写入）+ split_completed_at（分账完成标记，
--    解耦后 redeemed 不再蕴含已分账）。

ALTER TABLE task ADD COLUMN IF NOT EXISTS commerce_package_id uuid;

CREATE UNIQUE INDEX IF NOT EXISTS uniq_active_promotion_per_package
    ON task(commerce_package_id)
    WHERE commerce_package_id IS NOT NULL
      AND status IN ('draft', 'pending_review', 'published');

ALTER TABLE commerce_package_version
    ADD COLUMN IF NOT EXISTS recommender_fixed_cents int;

-- 幂等重放：约束名已存在时 ADD CONSTRAINT 会失败，用 DO 块守卫（表/列上面已 IF NOT EXISTS）。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_commerce_version_fixed_cents_nonneg') THEN
        ALTER TABLE commerce_package_version
            ADD CONSTRAINT chk_commerce_version_fixed_cents_nonneg
            CHECK (recommender_fixed_cents IS NULL OR recommender_fixed_cents >= 0);
    END IF;
END $$;

ALTER TABLE consumer_order ADD COLUMN IF NOT EXISTS split_eligible_at timestamptz;
ALTER TABLE consumer_order ADD COLUMN IF NOT EXISTS split_completed_at timestamptz;

-- 冷静期到期扫描：未完成分账的已核销订单（到期过滤 split_eligible_at <= now() 由查询条件承担，
-- 时间变值不可索引；redeemed+未完成集合本身很小）。旧 redeeming 在途单沿用既有 idx_consumer_order_dispatch。
CREATE INDEX IF NOT EXISTS idx_consumer_order_split_due
    ON consumer_order(updated_at)
    WHERE status = 'redeemed' AND split_completed_at IS NULL;
