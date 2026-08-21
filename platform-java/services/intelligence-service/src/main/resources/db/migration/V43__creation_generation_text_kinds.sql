-- 任务书 #44 登记扩展：文本创作流接 lineage——kind 值域从视频改编/图片三类扩展四类文本产出。
-- 既有三值语义不变；约束重建为七值（DO 块保证重放幂等）。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'creation_generation_kind_check'
                 AND conrelid = 'creation_generation'::regclass) THEN
        ALTER TABLE creation_generation DROP CONSTRAINT creation_generation_kind_check;
    END IF;
    ALTER TABLE creation_generation ADD CONSTRAINT creation_generation_kind_check
        CHECK (kind IN ('video_adaptation', 'asset_image', 'scene_image',
                        'article', 'moments_copy', 'comedy_script', 'assistant_guide'));
END $$;
