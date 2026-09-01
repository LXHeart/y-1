-- 任务书 #64 卡5：video_shot_audio 补 TTS 异步执行所需的两组句柄列。
--
-- 1) provider_task_id：MiniMax T2A 是异步任务（submit→taskId→poll），任务号必须跨 worker
--    领单周期持久化（与 video_generation_job.provider_task_id 同构）。
-- 2) run_id / budget_id / budget_reservation_date / reserved_cents：TTS 走 feature=null 免费分支
--    （平台资助 0 积分），但 prepareExecution 仍会做预算预留并落 ai_run。跨周期轮询 + 重启恢复
--    都要求 ExecutionContext 可从行重建（video worker 同款），否则重启后 ai_run 悬空 running、
--    预算预留泄漏。计费红线不变：本表只存句柄，扣退一律经 AiExecutionService。
--
-- 幂等铁律（任务书 #64 卡1 约束 1）：全部 ADD COLUMN IF NOT EXISTS。
-- run_id 的 FK 到 ai_run 允许（#49 合成 schema 手工建了 ai_run）；不建 media_id FK（约束 2）。

ALTER TABLE video_shot_audio ADD COLUMN IF NOT EXISTS provider_task_id varchar(128);
ALTER TABLE video_shot_audio ADD COLUMN IF NOT EXISTS run_id uuid REFERENCES ai_run(id) ON DELETE RESTRICT;
ALTER TABLE video_shot_audio ADD COLUMN IF NOT EXISTS budget_id uuid;
ALTER TABLE video_shot_audio ADD COLUMN IF NOT EXISTS budget_reservation_date date;
ALTER TABLE video_shot_audio ADD COLUMN IF NOT EXISTS reserved_cents int;

COMMENT ON COLUMN video_shot_audio.provider_task_id IS 'TTS provider 异步任务号（MiniMax T2A）；sandbox 为空';
COMMENT ON COLUMN video_shot_audio.run_id IS '免费执行环 ai_run 句柄（feature=null 平台资助）；只存句柄不动账本';
