-- 任务书 #69 卡E：凭据连通性探测结果（只存最近一次，无历史表——探测不缓存，每次点击实打）。
-- status 值域：ok | unauthorized | unreachable | error。
ALTER TABLE platform_provider_credential ADD COLUMN IF NOT EXISTS last_probe_at timestamptz;
ALTER TABLE platform_provider_credential ADD COLUMN IF NOT EXISTS last_probe_status varchar(16);
ALTER TABLE platform_provider_credential ADD COLUMN IF NOT EXISTS last_probe_latency_ms integer;
ALTER TABLE platform_provider_credential ADD COLUMN IF NOT EXISTS last_probe_error varchar(512);
