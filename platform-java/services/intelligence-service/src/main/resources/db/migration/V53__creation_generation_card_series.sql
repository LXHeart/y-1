-- 任务书 #54：系列 AI 图卡生成流——kind 值域扩展 card_series。
-- 既有七值语义不变；约束重建为八值（DO 块保证重放幂等）。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'creation_generation_kind_check'
                 AND conrelid = 'creation_generation'::regclass) THEN
        ALTER TABLE creation_generation DROP CONSTRAINT creation_generation_kind_check;
    END IF;
    ALTER TABLE creation_generation ADD CONSTRAINT creation_generation_kind_check
        CHECK (kind IN ('video_adaptation', 'asset_image', 'scene_image',
                        'article', 'moments_copy', 'comedy_script', 'assistant_guide',
                        'card_series'));
END $$;
