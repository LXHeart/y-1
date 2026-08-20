-- 门店媒体审核人工复核队列（缺口清偿之五遗留）：review 状态的人工承接。
-- 自动审核行 reviewed_* 恒 NULL；人工裁决（approve→pass / reject→blocked）就地覆盖 status
-- 并留痕裁决人/时间/备注（findings/model/run_id 保留自动审核证据，moderated_at 刷新为裁决时刻）。
ALTER TABLE store_media_moderation ADD COLUMN IF NOT EXISTS reviewed_by varchar(64);
ALTER TABLE store_media_moderation ADD COLUMN IF NOT EXISTS reviewed_at timestamptz;
ALTER TABLE store_media_moderation ADD COLUMN IF NOT EXISTS review_note varchar(500);
