-- 组织 AI 预算阈值告警（任务书 #37 登记项）：等级跃迁状态表。
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v42）。
--
-- 每个 (组织, 规则维度, 窗口) 一行，记录当前告警等级（warning/exceeded）；
-- 扫描器只在等级跃迁（无→warning→exceeded）时发事件，换窗（period_key 变化）
-- 自然重置。配合发布侧确定性 eventId 与 identity inbox/notification 双幂等，
-- 同一窗口同一等级对组织管理员最多通知一次。
CREATE TABLE IF NOT EXISTS ai_budget_alert (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id text NOT NULL,
    rule_key        varchar(32) NOT NULL,   -- daily_tokens/daily_cents/monthly_tokens/monthly_cents
    period_key      varchar(16) NOT NULL,   -- 日窗 '2026-08-21' / 月窗 '2026-08'
    level           varchar(16) NOT NULL,   -- warning/exceeded
    observed_value  bigint NOT NULL,
    limit_value     bigint NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, rule_key, period_key)
);

CREATE INDEX IF NOT EXISTS idx_ai_budget_alert_org
    ON ai_budget_alert(organization_id, updated_at DESC);
