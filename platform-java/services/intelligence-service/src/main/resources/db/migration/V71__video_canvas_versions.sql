-- 任务书 #100（C100-01）：创作画布四类版本的地基——分镜编辑版本 + 选片单调版本。
-- edit_version 为 C100-03 编辑流准备（本卡只落列与读取，不提前实现编辑闸）；
-- selection_version 服务选片：普通选择 JSONB 原子合并（COALESCE(selection,'{}') || patch）、
-- 推荐全量替换，每次成功写入 +1；客户端据此丢弃乱序旧响应。
--
-- 硬约束（沿用 V60/V63/V64）：全部 DDL 幂等（ADD COLUMN IF NOT EXISTS / DO 块重建约束），
-- 重放进空库、存量库与 PlatformModelConcurrencyMigrationTest 合成 schema 均安全——
-- video_storyboard / video_production_task 由 V60（>V13 baseline）创建，重放链上必然存在。

-- ── 分镜编辑版本：存量行从 1 开始（正整数）────────────────────────────────────
ALTER TABLE video_storyboard ADD COLUMN IF NOT EXISTS edit_version bigint NOT NULL DEFAULT 1;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'video_storyboard_edit_version_check'
                 AND conrelid = 'video_storyboard'::regclass) THEN
        ALTER TABLE video_storyboard DROP CONSTRAINT video_storyboard_edit_version_check;
    END IF;
    ALTER TABLE video_storyboard ADD CONSTRAINT video_storyboard_edit_version_check
        CHECK (edit_version > 0);
END $$;

-- ── 选片版本：存量行从 0 开始（0=尚无显式/落定选择写入）───────────────────────
ALTER TABLE video_production_task ADD COLUMN IF NOT EXISTS selection_version bigint NOT NULL DEFAULT 0;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'video_production_task_selection_version_check'
                 AND conrelid = 'video_production_task'::regclass) THEN
        ALTER TABLE video_production_task DROP CONSTRAINT video_production_task_selection_version_check;
    END IF;
    ALTER TABLE video_production_task ADD CONSTRAINT video_production_task_selection_version_check
        CHECK (selection_version >= 0);
END $$;

COMMENT ON COLUMN video_storyboard.edit_version IS
    '#100 分镜编辑版本：内容/集合写入口共享分镜行锁的 CAS 基线（编辑流 C100-03 接入）';
COMMENT ON COLUMN video_production_task.selection_version IS
    '#100 选片版本：选择写入单调递增，客户端只接受不小于已接收版本的选择数据';
