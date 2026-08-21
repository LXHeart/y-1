-- 组织级 BYOK 启用（ADR-D17）：组织密钥唯一约束 + 回退策略表 + Run 来源审计。
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v41）。

-- V13 收紧唯一索引时只处理了个人作用域；组织行若存在历史重复，先确定性停用，
-- 再建组织维唯一索引（同 V13 手法，避免迁移在脏数据上失败）。
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY organization_id, capability
               ORDER BY updated_at DESC, created_at DESC, id DESC
           ) AS row_number
    FROM ai_provider_key
    WHERE enabled = true
      AND organization_id IS NOT NULL
)
UPDATE ai_provider_key AS key
SET enabled = false,
    updated_at = now()
FROM ranked
WHERE key.id = ranked.id
  AND ranked.row_number > 1;

-- 一个组织 + 一种能力最多一把有效组织密钥（镜像个人作用域约束）。
CREATE UNIQUE INDEX IF NOT EXISTS idx_ai_provider_key_org_active_capability
    ON ai_provider_key(organization_id, capability)
    WHERE enabled = true AND organization_id IS NOT NULL;

-- Run 审计：组织密钥命中时记录组织 ID（个人 BYOK / 平台为 NULL）。
ALTER TABLE ai_run ADD COLUMN IF NOT EXISTS byok_organization_id text;

-- 组织回退策略（D-11：BYOK → 平台回退须组织策略显式授权）。
-- 无行 = 默认不允许回退；version 乐观锁与 ai_model_budget 同款。
CREATE TABLE IF NOT EXISTS ai_org_byok_policy (
    organization_id         text PRIMARY KEY,
    allow_platform_fallback boolean NOT NULL DEFAULT false,
    version                 bigint NOT NULL DEFAULT 1,
    updated_by_account_id   text NOT NULL,
    updated_at              timestamptz NOT NULL DEFAULT now()
);
