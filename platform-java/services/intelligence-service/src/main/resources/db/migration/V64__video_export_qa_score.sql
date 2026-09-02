-- 任务书 #66：B 线导出 lineage kind + D 线 take 评分列 + C 线 storyboard 分组列。
-- 任务书记作 V63（编写时最高+1）；实际 V63 已被 #65 时长/分辨率占用，顺延为 V64。
-- 全部幂等（ADD COLUMN IF NOT EXISTS / DO 块重建约束），Flyway 重放测试可安全双跑。

-- B1：导出登记 lineage（kind 值域追加 video_export）。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'creation_generation_kind_check'
                 AND conrelid = 'creation_generation'::regclass) THEN
        ALTER TABLE creation_generation DROP CONSTRAINT creation_generation_kind_check;
    END IF;
    ALTER TABLE creation_generation ADD CONSTRAINT creation_generation_kind_check
        CHECK (kind IN ('video_adaptation', 'asset_image', 'scene_image',
                        'article', 'moments_copy', 'comedy_script', 'assistant_guide',
                        'card_series', 'infographic',
                        'video_storyboard', 'video_master', 'video_export'));
END $$;

-- D1：候选质检评分（advisory，NULL=未评）。
ALTER TABLE video_shot_take ADD COLUMN IF NOT EXISTS score numeric NULL;
ALTER TABLE video_shot_take ADD COLUMN IF NOT EXISTS score_labels jsonb NULL;

-- C3：分镜分组与版本分支快照（{branches:[...]}）。
ALTER TABLE video_storyboard ADD COLUMN IF NOT EXISTS grouping jsonb NULL;
