-- TTS 上游提交成功后的中间态（TtsWorker 回写 submitted）此前被 CHECK 拒收——
-- sandbox 直落 succeeded 从未触发；2026-09-03 真实 MiniMax TTS 首次提交成功即暴露
-- （DataIntegrityViolationException 循环）。对齐 video_shot_take 的状态机：其 CHECK
-- 与派发索引均含 submitted，audio 表建表时漏了；仓储认领查询本就含三态，纯 DDL 缺口。

ALTER TABLE video_shot_audio DROP CONSTRAINT IF EXISTS video_shot_audio_status_check;
ALTER TABLE video_shot_audio
    ADD CONSTRAINT video_shot_audio_status_check
    CHECK (status IN ('queued', 'submitted', 'processing', 'succeeded', 'failed', 'skipped'));

DROP INDEX IF EXISTS idx_video_shot_audio_dispatch;
CREATE INDEX idx_video_shot_audio_dispatch
    ON video_shot_audio(next_attempt_at, created_at)
    WHERE status IN ('queued', 'submitted', 'processing');
