-- 任务书 #74 卡 G：脱敏判例库。表内无 org/account/金额列——脱敏由构造保证，不靠查询过滤。
CREATE TABLE IF NOT EXISTS precedent_case (
    id uuid PRIMARY KEY,
    dispute_id uuid NOT NULL UNIQUE,
    task_type varchar(32),                 -- v1 无事实源，预留
    task_platform varchar(32),
    dispute_kind varchar(32),
    focus varchar(200),                    -- kind + platform + reason 前 80 字
    claims_summary text,                   -- 原告 claim / 被告 answer caption 摘要（各截 200 字）
    decision varchar(32),
    final_via varchar(16),                 -- panel / cs / retrial
    vote_summary jsonb,                    -- 各轮 {forMerchant, forRecommender, abstain, matchedPlatformCount}
    rationale_digest jsonb,                -- 终局轮每票 rationale 截 100 字数组（不含审判官账号）
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_precedent_created ON precedent_case(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_precedent_platform ON precedent_case(task_platform);
