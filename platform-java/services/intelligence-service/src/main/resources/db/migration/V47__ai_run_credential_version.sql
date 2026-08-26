-- 任务书 #47 S3（D7）：ai_run 冻结平台凭据版本。
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v47）。
--
-- 为什么需要：厂商 key 额度耗尽或被封时，症状是一批 Run 突然失败，第一个要回答的问题是
-- 「这批用的哪把 key、是不是从轮换那一刻起」。platform_model_version（V8）只冻结模型配置版本，
-- 凭据轮换不改变它 —— 没有本列就只能靠时间戳猜。
--
-- 可空：BYOK run 与无凭据的平台 run（env bootstrap 兜底）都没有凭据版本；存量 Run 亦为 NULL，
-- 不 backfill —— 它们的凭据版本无从考证，编一个值比留空更糟。
--
-- 注：本列原计划占 V48（任务书写作时把 V47 预留给 base_url 收口的破坏性迁移）。实测 Flyway
-- out-of-order 未配置即默认 false，先用掉 V48 会让后补的 V47 永久无法应用，故本迁移占 V47，
-- 破坏性收口顺延到 V48。编号本身不承载语义，「破坏性迁移单独发布」的约定不变。

ALTER TABLE ai_run ADD COLUMN IF NOT EXISTS credential_version bigint;

COMMENT ON COLUMN ai_run.credential_version IS
    '平台凭据版本快照（platform_provider_credential.version）；BYOK/env 兜底 run 为 NULL';
