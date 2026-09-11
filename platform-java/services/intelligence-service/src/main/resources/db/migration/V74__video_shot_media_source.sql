-- 任务书 #100（C100-11 / API-10 / §7.1 V74 / §6.5）：每镜制作来源表。
--
-- 语义：shot_id 主键 = 一镜一行来源；缺行默认 generated（旧分镜兼容）；
-- own-media 行的 media_id 指向 media_reference（服务层校验归属/状态/实测时长，不建 FK，
-- 媒体裸 UUID 惯例一致）；trim/audio_mode 的合法性由服务层按媒体类型校验（表只存值）。
--
-- 硬约束（沿用 V71～V73 口径）：DDL 全幂等（IF NOT EXISTS / DO 块重建），重放进空库、
-- 存量库与 PlatformModelConcurrencyMigrationTest 合成 schema 均安全。

CREATE TABLE IF NOT EXISTS video_shot_media_source (
    shot_id uuid PRIMARY KEY,
    storyboard_id uuid NOT NULL,
    source_kind varchar(16) NOT NULL,
    media_id uuid,
    trim_start_ms bigint,
    trim_end_ms bigint,
    audio_mode varchar(16) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT video_shot_media_source_kind_check
        CHECK (source_kind IN ('generated', 'own-media')),
    CONSTRAINT video_shot_media_source_audio_check
        CHECK (audio_mode IN ('source', 'narration', 'mute'))
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                     WHERE indexname = 'video_shot_media_source_storyboard_shot'
                       AND tablename = 'video_shot_media_source') THEN
        CREATE INDEX video_shot_media_source_storyboard_shot
            ON video_shot_media_source (storyboard_id, shot_id);
    END IF;
END $$;

COMMENT ON TABLE video_shot_media_source IS
    '#100 C100-11 每镜制作来源：缺行=generated；own-media 合法性由服务层按 §6.5 校验';
