-- 任务书 #90 C90-03 D90-03：招募与推广正交——closed 只停止新报名，已接受推广继续按有效期归因；
-- 显式结束推广（POST /api/tasks/{id}/end-promotion）或 promotion_ends_at 到点才停止新归因。

ALTER TABLE task ADD COLUMN IF NOT EXISTS promotion_ends_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_task_commerce_package
    ON task(commerce_package_id) WHERE commerce_package_id IS NOT NULL;

-- 占位唯一索引重建：结束的推广（promotion_ends_at 已落）不再占用「每套餐至多一个进行中推广」名额。
-- 部分索引谓词必须 immutable，不能用 now()；本系统 promotion_ends_at 只由 end-promotion 置为过去时刻
-- （=已结束），故 IS NULL 即「进行中」。V52 原索引先行删除再重建（迁移文件不改旧账）。
DROP INDEX IF EXISTS uniq_active_promotion_per_package;
CREATE UNIQUE INDEX IF NOT EXISTS uniq_active_promotion_per_package
    ON task(commerce_package_id)
    WHERE commerce_package_id IS NOT NULL
      AND status IN ('draft', 'pending_review', 'published')
      AND promotion_ends_at IS NULL;
