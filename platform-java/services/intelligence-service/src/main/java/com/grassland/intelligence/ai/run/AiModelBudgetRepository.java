package com.grassland.intelligence.ai.run;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * AI 模型预算仓储（GL-P3-AI-001 Phase 3）。
 */
@Component
public class AiModelBudgetRepository {

    private static final String SELECT_COLS =
            "id::text, organization_id::text, capability, provider, "
            + "max_tokens_per_run, max_tokens_daily, max_tokens_monthly, "
            + "max_cents_per_run, max_cents_daily, max_cents_monthly, "
            + "current_daily_tokens, current_daily_cents, current_monthly_tokens, current_monthly_cents, "
            + "last_reset_date, enabled, created_at, updated_at";

    private final DatabaseClient db;

    public AiModelBudgetRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建预算配置。 */
    public Mono<UUID> create(AiModelBudget budget) {
        return db.sql("""
                INSERT INTO ai_model_budget(
                    organization_id, capability, provider,
                    max_tokens_per_run, max_tokens_daily, max_tokens_monthly,
                    max_cents_per_run, max_cents_daily, max_cents_monthly,
                    current_daily_tokens, current_daily_cents,
                    current_monthly_tokens, current_monthly_cents,
                    last_reset_date, enabled
                ) VALUES (
                    :orgId, :capability, :provider,
                    :maxTokensRun, :maxTokensDaily, :maxTokensMonthly,
                    :maxCentsRun, :maxCentsDaily, :maxCentsMonthly,
                    0, 0, 0, 0,
                    CURRENT_DATE, true
                )
                RETURNING id::text
                """)
                .bind("orgId", Parameter.fromOrEmpty(budget.organizationId(), String.class))
                .bind("capability", budget.capability())
                .bind("provider", budget.provider())
                .bind("maxTokensRun", Parameter.fromOrEmpty(budget.maxTokensPerRun(), Integer.class))
                .bind("maxTokensDaily", Parameter.fromOrEmpty(budget.maxTokensDaily(), Long.class))
                .bind("maxTokensMonthly", Parameter.fromOrEmpty(budget.maxTokensMonthly(), Long.class))
                .bind("maxCentsRun", Parameter.fromOrEmpty(budget.maxCentsPerRun(), Integer.class))
                .bind("maxCentsDaily", Parameter.fromOrEmpty(budget.maxCentsDaily(), Long.class))
                .bind("maxCentsMonthly", Parameter.fromOrEmpty(budget.maxCentsMonthly(), Long.class))
                .map((r, meta) -> r.get("id", String.class))
                .one()
                .map(UUID::fromString);
    }

    /** 按组织+能力+provider 查询预算配置。 */
    public Mono<AiModelBudget> findByOrganizationAndCapability(String organizationId, String capability) {
        // 普通字符串拼接：text block 会吃掉行尾空格，SELECT/列/FROM 会粘连成坏 SQL。
        return db.sql("SELECT " + SELECT_COLS
                + " FROM ai_model_budget"
                + " WHERE organization_id = :orgId"
                + " AND capability = :capability"
                + " AND enabled = true"
                + " ORDER BY created_at DESC"
                + " LIMIT 1")
                .bind("orgId", organizationId)
                .bind("capability", capability)
                .map(AiModelBudgetRepository::map)
                .one();
    }

    /** 累加用量（含自动重置）。 */
    public Mono<Boolean> accumulate(UUID id, long addedTokens, long addedCents) {
        return db.sql("""
                UPDATE ai_model_budget
                SET current_daily_tokens = current_daily_tokens + :addedTokens,
                    current_daily_cents = current_daily_cents + :addedCents,
                    current_monthly_tokens = current_monthly_tokens + :addedTokens,
                    current_monthly_cents = current_monthly_cents + :addedCents,
                    updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND last_reset_date = CURRENT_DATE
                RETURNING id::text
                """)
                .bind("id", id.toString())
                .bind("addedTokens", addedTokens)
                .bind("addedCents", addedCents)
                .map((r, meta) -> r.get("id", String.class))
                .one()
                .hasElement();
    }

    /** 重置日统计。 */
    public Mono<Boolean> resetDaily(UUID id) {
        return db.sql("""
                UPDATE ai_model_budget
                SET current_daily_tokens = 0,
                    current_daily_cents = 0,
                    last_reset_date = CURRENT_DATE,
                    updated_at = now()
                WHERE id = CAST(:id AS uuid)
                RETURNING id::text
                """)
                .bind("id", id.toString())
                .map((r, meta) -> r.get("id", String.class))
                .one()
                .hasElement();
    }

    /** 重置月统计。 */
    public Mono<Boolean> resetMonthly(UUID id) {
        return db.sql("""
                UPDATE ai_model_budget
                SET current_monthly_tokens = 0,
                    current_monthly_cents = 0,
                    updated_at = now()
                WHERE id = CAST(:id AS uuid)
                RETURNING id::text
                """)
                .bind("id", id.toString())
                .map((r, meta) -> r.get("id", String.class))
                .one()
                .hasElement();
    }

    private static AiModelBudget map(Row row, RowMetadata meta) {
        return new AiModelBudget(
                uuidFromString(row.get("id", String.class)),
                row.get("organization_id", String.class),
                row.get("capability", String.class),
                row.get("provider", String.class),
                row.get("max_tokens_per_run", Integer.class),
                row.get("max_tokens_daily", Long.class),
                row.get("max_tokens_monthly", Long.class),
                row.get("max_cents_per_run", Integer.class),
                row.get("max_cents_daily", Long.class),
                row.get("max_cents_monthly", Long.class),
                row.get("current_daily_tokens", Long.class),
                row.get("current_daily_cents", Long.class),
                row.get("current_monthly_tokens", Long.class),
                row.get("current_monthly_cents", Long.class),
                row.get("last_reset_date", LocalDate.class),
                row.get("enabled", Boolean.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static UUID uuidFromString(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
