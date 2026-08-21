-- ASR 句级时间戳（任务书 #41 尾条）：provider verbose_json 的 segments[] 落库（JSONB）。
-- 「Provider payloads are deliberately not retained」的旧设计仅对全文成立——分段是字幕对齐的
-- 结构化产出而非原始载荷；sandbox/不支持分段的 provider 为 NULL，消费方回落启发式分轴。
ALTER TABLE speech_transcription ADD COLUMN IF NOT EXISTS segments_json JSONB;
