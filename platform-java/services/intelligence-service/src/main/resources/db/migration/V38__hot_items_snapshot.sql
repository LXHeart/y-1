-- 草场 intelligence V38：热点历史快照（缺口清偿之八，#35 D5 / PRD §4.3 时间范围筛选）。
-- 60s 刷新每次把分类后的 groups 落一行快照（append-only），「今天/本周」按时间窗聚合去重。
-- TTL 2h 决定快照自然密度（≤12 行/天）；保留窗口由仓储按 retention 清理（默认 14 天）。
CREATE TABLE IF NOT EXISTS hot_items_snapshot (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    fetched_at timestamptz NOT NULL DEFAULT now(),
    groups jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_hot_items_snapshot_fetched_at ON hot_items_snapshot (fetched_at DESC);
