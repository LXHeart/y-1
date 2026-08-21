package com.grassland.intelligence.ai.run;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 预算阈值告警状态仓储（任务书 #37 登记项）。
 *
 * <p>每 (组织, 规则, 窗口) 一行；只在等级跃迁时写入并配合确定性 eventId 发事件，
 * 同窗同等级不重复通知。等级只升不降（管理员中途调高上限造成的回落不翻状态，防抖动）。
 */
@Component
public class AiBudgetAlertRepository {

    /** 告警等级；序即严重度。 */
    public enum Level {
        WARNING, EXCEEDED;

        public String dbValue() {
            return name().toLowerCase();
        }
    }

    public record AiBudgetAlert(
            UUID id,
            String organizationId,
            String ruleKey,
            String periodKey,
            Level level,
            long observedValue,
            long limitValue) {
    }

    private final DatabaseClient db;

    public AiBudgetAlertRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 当前窗口既有告警行（无 = 尚未告警）。 */
    public Mono<AiBudgetAlert> find(String organizationId, String ruleKey, String periodKey) {
        return db.sql("""
                SELECT id::text, organization_id, rule_key, period_key, level, observed_value, limit_value
                FROM ai_budget_alert
                WHERE organization_id = :org AND rule_key = :rule AND period_key = :period
                """)
                .bind("org", organizationId)
                .bind("rule", ruleKey)
                .bind("period", periodKey)
                .map(AiBudgetAlertRepository::map)
                .one();
    }

    /** 跃迁时写入/升级（ON CONFLICT 覆盖 level 与观测值）。 */
    public Mono<AiBudgetAlert> upsert(
            String organizationId, String ruleKey, String periodKey, Level level,
            long observedValue, long limitValue) {
        return db.sql("""
                INSERT INTO ai_budget_alert(
                    organization_id, rule_key, period_key, level, observed_value, limit_value)
                VALUES (:org, :rule, :period, :level, :observed, :limitValue)
                ON CONFLICT (organization_id, rule_key, period_key) DO UPDATE SET
                    level = EXCLUDED.level,
                    observed_value = EXCLUDED.observed_value,
                    limit_value = EXCLUDED.limit_value,
                    updated_at = now()
                RETURNING id::text, organization_id, rule_key, period_key, level, observed_value, limit_value
                """)
                .bind("org", organizationId)
                .bind("rule", ruleKey)
                .bind("period", periodKey)
                .bind("level", level.dbValue())
                .bind("observed", observedValue)
                .bind("limitValue", limitValue)
                .map(AiBudgetAlertRepository::map)
                .one();
    }

    /** 供测试与排障列出组织近窗口告警。 */
    public Flux<AiBudgetAlert> listByOrganization(String organizationId) {
        return db.sql("""
                SELECT id::text, organization_id, rule_key, period_key, level, observed_value, limit_value
                FROM ai_budget_alert
                WHERE organization_id = :org
                ORDER BY updated_at DESC
                """)
                .bind("org", organizationId)
                .map(AiBudgetAlertRepository::map)
                .all();
    }

    private static AiBudgetAlert map(Row row, RowMetadata meta) {
        return new AiBudgetAlert(
                UUID.fromString(row.get("id", String.class)),
                row.get("organization_id", String.class),
                row.get("rule_key", String.class),
                row.get("period_key", String.class),
                Level.valueOf(row.get("level", String.class).toUpperCase()),
                row.get("observed_value", Long.class),
                row.get("limit_value", Long.class));
    }
}
