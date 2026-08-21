package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 预算阈值告警扫描器真实库 IT：跃迁发一条 outbox 事件 + 告警行；幂等扫描不重发。 */
@DisplayName("BudgetAlertScanner (真实库)")
class BudgetAlertScannerIT extends IntelligenceItSupport {

    private static final String ORG = "org-alert-it-" + UUID.randomUUID();

    @Autowired
    BudgetAlertScanner scanner;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM intelligence_outbox").then().block();
        db.sql("DELETE FROM ai_budget_alert").then().block();
        db.sql("DELETE FROM ai_model_budget WHERE organization_id = :org").bind("org", ORG).then().block();
    }

    private void insertBudget(long dailyTokensLimit, long currentDailyTokens) {
        db.sql("""
                INSERT INTO ai_model_budget(
                    organization_id, capability, provider, max_tokens_daily,
                    current_daily_tokens, current_daily_cents, current_monthly_tokens, current_monthly_cents,
                    last_reset_date, version, enabled)
                VALUES (:org, '*', '*', :limit,
                    :current, 0, 0, 0, CURRENT_DATE, 1, true)
                """)
                .bind("org", ORG)
                .bind("limit", dailyTokensLimit)
                .bind("current", currentDailyTokens)
                .then().block();
    }

    private long outboxCount(String level) {
        return db.sql("SELECT COUNT(*) FROM intelligence_outbox"
                        + " WHERE event_type = 'AiOrgBudgetThresholdCrossed'"
                        + " AND payload ->> 'level' = :level")
                .bind("level", level)
                .map(row -> row.get(0, Long.class)).one().block();
    }

    private String alertLevel() {
        return db.sql("SELECT level FROM ai_budget_alert"
                        + " WHERE organization_id = :org AND rule_key = 'daily_tokens'")
                .bind("org", ORG)
                .map(row -> row.get(0, String.class)).one().block();
    }

    @Test
    @DisplayName("达 80% 发 warning 事件与告警行；重复扫描不重发；越限后升级发 exceeded")
    void transitionLifecycle() {
        insertBudget(100, 80);
        scanner.scanOnce().then().block();

        assertThat(alertLevel()).isEqualTo("warning");
        assertThat(outboxCount("warning")).isEqualTo(1);
        String warningEventId = db.sql("SELECT event_id FROM intelligence_outbox"
                        + " WHERE event_type = 'AiOrgBudgetThresholdCrossed' AND payload ->> 'level' = 'warning'")
                .map(row -> row.get(0, String.class)).one().block();
        assertThat(warningEventId).isEqualTo(UUID.nameUUIDFromBytes(
                ("AiBudgetAlert:" + ORG + ":daily_tokens:" + LocalDate.now() + ":warning")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());

        // 幂等扫描：同等级不重发
        scanner.scanOnce().then().block();
        assertThat(outboxCount("warning")).isEqualTo(1);

        // 用量越限 → 升级 exceeded 发一条，warning 告警行被覆盖
        db.sql("UPDATE ai_model_budget SET current_daily_tokens = 105 WHERE organization_id = :org")
                .bind("org", ORG).then().block();
        scanner.scanOnce().then().block();

        assertThat(alertLevel()).isEqualTo("exceeded");
        assertThat(outboxCount("exceeded")).isEqualTo(1);
        assertThat(outboxCount("warning")).isEqualTo(1);  // 历史事件不消失
    }

    @Test
    @DisplayName("低于阈值与未配置上限的维度零事件")
    void belowThresholdEmitsNothing() {
        insertBudget(100, 79);
        scanner.scanOnce().then().block();
        assertThat(outboxCount("warning")).isZero();
        assertThat(outboxCount("exceeded")).isZero();

        db.sql("DELETE FROM ai_model_budget WHERE organization_id = :org").bind("org", ORG).then().block();
        insertBudget(0, 0);
        // max_tokens_daily=0 视为无上限哨兵（limit<=0 跳过）
        scanner.scanOnce().then().block();
        assertThat(outboxCount("warning")).isZero();
    }
}
