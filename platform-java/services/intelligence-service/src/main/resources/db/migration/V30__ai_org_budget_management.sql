-- 任务书 #37：组织 AI 预算管理入口。
-- 预算配置会被多个组织管理员并发编辑，补乐观锁版本；存量行从 v1 开始。
ALTER TABLE IF EXISTS ai_model_budget
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 1;
