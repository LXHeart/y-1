package com.grassland.intelligence.videoproduction;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Read-only projection used by the production video billing reconciliation endpoint. */
@Component
public class VideoBillingReconciliationRepository {
    private final DatabaseClient db;

    public VideoBillingReconciliationRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<RowData> latest(int limit) {
        return db.sql("""
                SELECT job.id::text AS job_id, job.run_id::text AS run_id, job.account_id,
                       job.provider, job.model, job.status AS job_status,
                       job.pricing_version, job.unit_price_cents,
                       job.estimated_cost_cents, job.actual_cost_cents,
                       job.requested_duration_seconds, job.actual_duration_seconds,
                       job.result_url,
                       run.status AS run_status, run.operation_id::text AS operation_id,
                       run.actual_cents AS run_actual_cents, run.video_seconds AS run_video_seconds,
                       compensation.status AS compensation_status
                FROM video_generation_job job
                LEFT JOIN ai_run run ON run.id = job.run_id
                LEFT JOIN ai_credit_compensation compensation
                       ON compensation.actual_run_id = run.id AND compensation.standalone = false
                ORDER BY job.created_at DESC
                LIMIT :limit
                """)
                .bind("limit", limit)
                .map(VideoBillingReconciliationRepository::map)
                .all();
    }

    private static RowData map(Row row, RowMetadata metadata) {
        return new RowData(
                uuid(row.get("job_id", String.class)),
                uuid(row.get("run_id", String.class)),
                row.get("account_id", String.class),
                row.get("provider", String.class),
                row.get("model", String.class),
                row.get("job_status", String.class),
                row.get("pricing_version", String.class),
                row.get("unit_price_cents", Integer.class),
                row.get("estimated_cost_cents", Integer.class),
                row.get("actual_cost_cents", Integer.class),
                row.get("requested_duration_seconds", Integer.class),
                row.get("actual_duration_seconds", Integer.class),
                row.get("result_url", String.class),
                row.get("run_status", String.class),
                uuid(row.get("operation_id", String.class)),
                row.get("run_actual_cents", Integer.class),
                row.get("run_video_seconds", Integer.class),
                row.get("compensation_status", String.class));
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    public record RowData(
            UUID jobId, UUID runId, String accountId, String provider, String model, String jobStatus,
            String pricingVersion, int unitPriceCents, int estimatedCostCents,
            Integer actualCostCents, int requestedDurationSeconds,
            Integer actualDurationSeconds, String resultReference,
            String runStatus, UUID operationId, Integer runActualCents,
            Integer runVideoSeconds, String compensationStatus) {}
}
