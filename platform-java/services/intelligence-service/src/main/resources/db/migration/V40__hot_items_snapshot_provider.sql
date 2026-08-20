-- 草场 intelligence V40：热点快照 provider 维度（缺口清偿之八遗留：alapi 历史归档）。
-- alapi 路径（扁平 items、per-token 进程内缓存）此前不经 DB 缓存，归档钩子永不触发。
-- 归档统一为 groups 形态（alapi 按站点分组：platform=站点 id、label=站点名），加 provider 列
-- 区分两源——否则 alapi 与 60s 的同 platform 组（douyin/weibo 两边都有）会在「今天/本周」
-- 聚合里跨源混并。存量行按 '60s' 回填（V38 上线以来只有 60s 在归档）。
ALTER TABLE hot_items_snapshot ADD COLUMN IF NOT EXISTS provider varchar(16) NOT NULL DEFAULT '60s';

CREATE INDEX IF NOT EXISTS idx_hot_items_snapshot_provider_fetched
    ON hot_items_snapshot (provider, fetched_at DESC);
