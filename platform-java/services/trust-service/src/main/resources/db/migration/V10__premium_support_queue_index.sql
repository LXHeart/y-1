-- 客服队列按 premium first、同优先级 oldest first；id 是稳定 keyset 尾键，避免同时间戳漏项/重复。
-- Flyway sidecar disables the migration transaction because PostgreSQL requires CONCURRENTLY
-- to run outside transaction blocks. Keep this file index-only.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_dispute_support_queue
    ON dispute_case (support_priority DESC, created_at, id)
    WHERE status <> 'final';
