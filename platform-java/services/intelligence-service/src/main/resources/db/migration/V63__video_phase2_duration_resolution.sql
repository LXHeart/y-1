-- 任务书 #65（2026-09-01）：视频制作二期——时长/分辨率/锚定图/重合成序号。
--
-- 拍板（PRD §4.4 二期）：
--   * targetDurationSeconds 放宽 15-180（一期 15-60）；
--   * 镜头数上限 30（一期 10）——video_shot.seq 的 CHECK 同步放宽；
--   * 新增 resolution（1080x1920 竖版缺省 / 1920x1080 B 站横版缺省）；
--   * AI 补图（卡2）：video_shot.anchor_media_id / anchor_source；
--   * 成片后重合成（卡6）：video_production_task.recompose_seq。
--
-- 硬约束（沿用 V60）：全部 DDL 幂等（IF NOT EXISTS / DROP CONSTRAINT IF EXISTS + ADD 成对），
-- 重放进空库、存量库与 PlatformModelConcurrencyMigrationTest 的合成 schema 均安全。
-- anchor_media_id 用 **uuid** 而非任务书草稿里的 bigint：media_reference.id 全库是 uuid
-- （V4），bigint 无法与媒体行对齐——按任务书前言「与 #64 落地代码有出入以代码为准」修正。
-- 段缓存文件存对象存储 segments/{taskId}/{shotId}.mp4，不建表。

-- ── 时长上限放宽：15-60 → 15-180（步进 5 不变）────────────────────────────────
-- 列内联 CHECK 的 Postgres 默认名 = <table>_<column>_check（V60 建表时生成，无显式名）。
ALTER TABLE video_storyboard DROP CONSTRAINT IF EXISTS video_storyboard_target_duration_seconds_check;
ALTER TABLE video_storyboard ADD CONSTRAINT video_storyboard_target_duration_seconds_check
    CHECK (target_duration_seconds BETWEEN 15 AND 180 AND target_duration_seconds % 5 = 0);

-- ── 分辨率（卡1）：缺省竖版；B 站任务由 API 层落横版────────────────────────────
ALTER TABLE video_storyboard ADD COLUMN IF NOT EXISTS resolution varchar(16) NOT NULL DEFAULT '1080x1920';
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'video_storyboard_resolution_check'
                 AND conrelid = 'video_storyboard'::regclass) THEN
        ALTER TABLE video_storyboard DROP CONSTRAINT video_storyboard_resolution_check;
    END IF;
    ALTER TABLE video_storyboard ADD CONSTRAINT video_storyboard_resolution_check
        CHECK (resolution IN ('1080x1920', '1920x1080'));
END $$;

-- ── 镜头数上限放宽：seq 1-10 → 1-30（镜头数 3-30 由 API/提示词层保证下界）──────
ALTER TABLE video_shot DROP CONSTRAINT IF EXISTS video_shot_seq_check;
ALTER TABLE video_shot ADD CONSTRAINT video_shot_seq_check CHECK (seq BETWEEN 1 AND 30);

-- ── AI 补图（卡2）：锚定图媒体句柄与来源标记────────────────────────────────────
-- anchor_source：'user'（用户上传锚定图/无锚定图）/ 'ai'（平台资助 AI 生成的首帧图）。
ALTER TABLE video_shot ADD COLUMN IF NOT EXISTS anchor_media_id uuid;
ALTER TABLE video_shot ADD COLUMN IF NOT EXISTS anchor_source varchar(8) NOT NULL DEFAULT 'user';
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'video_shot_anchor_source_check'
                 AND conrelid = 'video_shot'::regclass) THEN
        ALTER TABLE video_shot DROP CONSTRAINT video_shot_anchor_source_check;
    END IF;
    ALTER TABLE video_shot ADD CONSTRAINT video_shot_anchor_source_check
        CHECK (anchor_source IN ('user', 'ai'));
END $$;

-- ── 成片后单镜重抽（卡6）：重合成序号，operationId={taskId}:recompose:{n} 的 n ────
ALTER TABLE video_production_task ADD COLUMN IF NOT EXISTS recompose_seq int NOT NULL DEFAULT 0;

COMMENT ON COLUMN video_storyboard.resolution IS
    '#65 分辨率：1080x1920（缺省竖版）/ 1920x1080（B 站横版）；provider ratio 与合成 normalize 均由它派生';
COMMENT ON COLUMN video_shot.anchor_media_id IS '#65 AI 补图媒体句柄（uuid 对齐 media_reference）；take/合成首帧优先取它';
COMMENT ON COLUMN video_shot.anchor_source IS '#65 锚定图来源：user=用户上传（或无锚定图），ai=平台资助生成';
COMMENT ON COLUMN video_production_task.recompose_seq IS '#65 重合成序号（单调递增），重结算 operationId={taskId}:recompose:{n}';
