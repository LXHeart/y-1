package com.grassland.intelligence.ai.run;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import static com.grassland.intelligence.config.R2dbcBindings.nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AI Run 仓储（GL-P3-AI-001 Phase 3）。
 */
@Component
public class AiRunRepository {

    private static final String SELECT_COLS =
            "id::text, organization_id::text, account_id::text, capability, provider, model, run_type, "
            + "input_tokens, output_tokens, images_generated, video_seconds, "
            + "budget_cents, actual_cents, status, failure_reason, "
            + "started_at, completed_at, price_table_version, operation_id::text, refund_operation_id::text, "
            + "created_at, updated_at, platform_model_version, fallback_authorized, context_snapshot_id::text, "
            + "credits_cents_policy_version";

    private final DatabaseClient db;

    public AiRunRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建 Run 记录。 */
    public Mono<UUID> create(AiRun run) {
        return db.sql("""
                INSERT INTO ai_run(
                    organization_id, account_id, capability, provider, model, run_type,
                    budget_cents, operation_id, platform_model_version, fallback_authorized, context_snapshot_id,
                    credits_cents_policy_version
                ) VALUES (
                    :orgId, :accountId, :capability, :provider, :model, :runType,
                    :budgetCents, CAST(:operationId AS uuid), :platformModelVersion, :fallbackAuthorized,
                    CAST(:contextSnapshotId AS uuid), :creditsCentsPolicyVersion
                )
                RETURNING id::text
                """)
                .bind("orgId", nullable(run.organizationId(), String.class))
                .bind("accountId", run.accountId())
                .bind("capability", run.capability())
                .bind("provider", run.provider())
                .bind("model", nullable(run.model(), String.class))
                .bind("runType", run.runType())
                .bind("budgetCents", run.budgetCents())
                .bind("operationId", run.operationId().toString())
                .bind("platformModelVersion", nullable(run.platformModelVersion(), Integer.class))
                .bind("fallbackAuthorized", run.fallbackAuthorized())
                .bind("contextSnapshotId", nullable(
                        run.contextSnapshotId() == null ? null : run.contextSnapshotId().toString(), String.class))
                .bind("creditsCentsPolicyVersion", nullable(run.creditsCentsPolicyVersion(), String.class))
                .map((r, meta) -> r.get("id", String.class))
                .one()
                .map(UUID::fromString);
    }

    /** 标记完成（结算）—— 一并落用量计量（GL-P3-AI-001：原仅写 actual_cents，计量列恒空）。 */
    public Mono<Boolean> complete(UUID id, int actualCents, Integer inputTokens, Integer outputTokens,
                                  int imagesGenerated, int videoSeconds) {
        return db.sql("""
                UPDATE ai_run
                SET actual_cents = :actualCents,
                    input_tokens = :inputTokens,
                    output_tokens = :outputTokens,
                    images_generated = :imagesGenerated,
                    video_seconds = :videoSeconds,
                    status = 'completed',
                    completed_at = now(),
                    updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'running'
                RETURNING id::text
                """)
                .bind("id", id.toString())
                .bind("actualCents", actualCents)
                .bind("inputTokens", nullable(inputTokens, Integer.class))
                .bind("outputTokens", nullable(outputTokens, Integer.class))
                .bind("imagesGenerated", imagesGenerated)
                .bind("videoSeconds", videoSeconds)
                .map((r, meta) -> r.get("id", String.class))
                .one()
                .hasElement();
    }

    /** 标记失败。 */
    public Mono<Boolean> fail(UUID id, String reason) {
        return db.sql("""
                UPDATE ai_run
                SET status = 'failed',
                    failure_reason = :reason,
                    completed_at = now(),
                    updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'running'
                RETURNING id::text
                """)
                .bind("id", id.toString())
                .bind("reason", reason)
                .map((r, meta) -> r.get("id", String.class))
                .one()
                .hasElement();
    }

    /** 标记取消（用户主动 abort，不退预留）。 */
    public Mono<Boolean> cancel(UUID id) {
        return db.sql("""
                UPDATE ai_run
                SET status = 'cancelled',
                    failure_reason = 'user aborted',
                    completed_at = now(),
                    updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'running'
                RETURNING id::text
                """)
                .bind("id", id.toString())
                .map((r, meta) -> r.get("id", String.class))
                .one()
                .hasElement();
    }

    /** 设置退回操作 ID。 */
    public Mono<Boolean> setRefundOperation(UUID id, UUID refundOpId) {
        return db.sql("""
                UPDATE ai_run
                SET refund_operation_id = CAST(:refundOpId AS uuid),
                    updated_at = now()
                WHERE id = CAST(:id AS uuid)
                RETURNING id::text
                """)
                .bind("id", id.toString())
                .bind("refundOpId", refundOpId.toString())
                .map((r, meta) -> r.get("id", String.class))
                .one()
                .hasElement();
    }

    /** 按 operation ID 查询（用于幂等退款）。 */
    public Mono<AiRun> findByOperationId(UUID operationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM ai_run WHERE operation_id = CAST(:id AS uuid)")
                .bind("id", operationId.toString())
                .map(AiRunRepository::map)
                .one();
    }

    /** 按 ID 查询（GET /api/ai/runs/{id}）。 */
    public Mono<AiRun> findById(UUID id) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM ai_run WHERE id = CAST(:id AS uuid)")
                .bind("id", id.toString())
                .map(AiRunRepository::map)
                .one();
    }

    /** 按账号列最近 Run（GET /api/ai/runs）。 */
    public Flux<AiRun> findByAccount(String accountId, int limit) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM ai_run WHERE account_id = :accountId ORDER BY started_at DESC LIMIT :limit")
                .bind("accountId", accountId)
                .bind("limit", limit)
                .map(AiRunRepository::map)
                .all();
    }

    private static AiRun map(Row row, RowMetadata meta) {
        return new AiRun(
                uuidFromString(row.get("id", String.class)),
                row.get("organization_id", String.class),
                row.get("account_id", String.class),
                row.get("capability", String.class),
                row.get("provider", String.class),
                row.get("model", String.class),
                row.get("run_type", String.class),
                row.get("input_tokens", Integer.class),
                row.get("output_tokens", Integer.class),
                row.get("images_generated", Integer.class),
                row.get("video_seconds", Integer.class),
                row.get("budget_cents", Integer.class),
                row.get("actual_cents", Integer.class),
                row.get("status", String.class),
                row.get("failure_reason", String.class),
                toInstant(row.get("started_at", OffsetDateTime.class)),
                toInstant(row.get("completed_at", OffsetDateTime.class)),
                row.get("price_table_version", String.class),
                uuidFromString(row.get("operation_id", String.class)),
                uuidFromString(row.get("refund_operation_id", String.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)),
                row.get("platform_model_version", Integer.class),
                row.get("fallback_authorized", Boolean.class),
                uuidFromString(row.get("context_snapshot_id", String.class)),
                row.get("credits_cents_policy_version", String.class)
        );
    }

    private static UUID uuidFromString(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
