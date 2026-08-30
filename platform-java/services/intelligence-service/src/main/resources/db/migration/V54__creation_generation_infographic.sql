-- 任务书 #55：信息图生成流（LLM 直写 SVG）——kind 值域扩展 infographic。
-- 既有八值语义不变；约束重建为九值（DO 块保证重放幂等）。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'creation_generation_kind_check'
                 AND conrelid = 'creation_generation'::regclass) THEN
        ALTER TABLE creation_generation DROP CONSTRAINT creation_generation_kind_check;
    END IF;
    ALTER TABLE creation_generation ADD CONSTRAINT creation_generation_kind_check
        CHECK (kind IN ('video_adaptation', 'asset_image', 'scene_image',
                        'article', 'moments_copy', 'comedy_script', 'assistant_guide',
                        'card_series', 'infographic'));
END $$;
