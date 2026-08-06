-- 当前 BYOK 控制面只允许个人作用域，且同一账号/能力最多一个 active provider。
-- 先确定性停用历史重复行，再收紧唯一约束，避免迁移在已有脏数据上失败。
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY owner_account_id, capability
               ORDER BY updated_at DESC, created_at DESC, id DESC
           ) AS row_number
    FROM ai_provider_key
    WHERE enabled = true
      AND organization_id IS NULL
)
UPDATE ai_provider_key AS key
SET enabled = false,
    updated_at = now()
FROM ranked
WHERE key.id = ranked.id
  AND ranked.row_number > 1;

DROP INDEX IF EXISTS idx_ai_provider_key_unique;

CREATE UNIQUE INDEX idx_ai_provider_key_personal_active_capability
    ON ai_provider_key(owner_account_id, capability)
    WHERE enabled = true AND organization_id IS NULL;
