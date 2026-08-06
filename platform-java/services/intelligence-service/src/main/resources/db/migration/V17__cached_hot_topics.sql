-- GL: 首页热点 60s 缓存表（镜像 legacy server/sql/006_cached_hot_topics.sql）。
-- 60s API 2h TTL 缓存 + 过期降级。intelligence Flyway 独立建（与 legacy 迁移隔离）。

CREATE TABLE IF NOT EXISTS intelligence_cached_hot_topics (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider    text NOT NULL DEFAULT '60s',
    items       jsonb NOT NULL,
    fetched_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_intelligence_cached_hot_provider ON intelligence_cached_hot_topics(provider, fetched_at DESC);
