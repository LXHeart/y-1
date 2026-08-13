package com.grassland.intelligence.videoproduction;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

@DisplayName("Video billing reconciliation admin API")
class VideoBillingReconciliationControllerIT extends IntelligenceItSupport {
    private static final String ADMIN = "71717171-7171-7171-7171-717171717171";
    private static final String USER = "72727272-7272-7272-7272-727272727272";

    @MockitoBean
    FinanceCreditOperationClient finance;

    @BeforeEach
    void clean() {
        reset(finance);
        db.sql("DELETE FROM video_generation_job").then().block();
        db.sql("DELETE FROM ai_credit_compensation").then().block();
        db.sql("DELETE FROM ai_run").then().block();
    }

    @Test
    @DisplayName("requires platform admin")
    void requiresAdmin() {
        client().get().uri("/api/admin/ai/video-reconciliation")
                .exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/admin/ai/video-reconciliation")
                .header("X-Grassland-Identity", sign(USER, "merchant"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("classifies consistent, inconsistent, and pending billing states without leaking URLs")
    void reconcilesVideoBillingEvidence() {
        UUID consistentRun = insertRun("completed", 6, 3);
        insertJob(consistentRun, "succeeded", 6, 3, "/api/media/" + UUID.randomUUID());

        UUID mismatchedRun = insertRun("completed", 7, 3);
        insertJob(mismatchedRun, "succeeded", 6, 3,
                "https://provider.example/private-video.mp4");

        UUID pendingRun = insertRun("failed", null, null);
        insertJob(pendingRun, "failed", null, null, null);
        insertCompensation(pendingRun, "pending");
        when(finance.query(anyList())).thenAnswer(invocation -> {
            Map<UUID, FinanceCreditOperationClient.Operation> operations = new LinkedHashMap<>();
            for (UUID operationId : invocation.<java.util.List<UUID>>getArgument(0)) {
                operations.put(operationId, operation(operationId, "consumed"));
            }
            return Mono.just(operations);
        });

        client().get().uri("/api/admin/ai/video-reconciliation?limit=9999")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isOk().expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("\"total\":3")
                            .contains("\"consistent\":1")
                            .contains("\"pending\":1")
                            .contains("\"inconsistent\":1")
                            .contains("archived_media_reference_missing")
                            .contains("job_run_cost_mismatch")
                            .contains("credit_compensation_pending")
                            .contains("\"financeAuthorityState\":\"consumed\"")
                            .contains("\"monetaryConversionState\":\"policy_missing\"")
                            .doesNotContain("resultUrl")
                            .doesNotContain("provider.example")
                            .doesNotContain("secret script");
                });
    }

    @Test
    @DisplayName("missing Finance fence is inconsistent and Finance outage remains explicit pending")
    void distinguishesMissingFinanceAuthorityFromOutage() {
        UUID run = insertRun("completed", 6, 3);
        insertJob(run, "succeeded", 6, 3, "/api/media/" + UUID.randomUUID());
        when(finance.query(anyList())).thenReturn(Mono.just(Map.of()));

        client().get().uri("/api/admin/ai/video-reconciliation")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isOk().expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("finance_credit_operation_missing")
                        .contains("\"inconsistent\":1"));

        when(finance.query(anyList())).thenReturn(Mono.error(new IllegalStateException("finance down")));
        client().get().uri("/api/admin/ai/video-reconciliation")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isOk().expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("finance_authority_unavailable")
                        .contains("\"financeAuthorityState\":\"unavailable\"")
                        .contains("\"pending\":1")
                        .doesNotContain("finance down"));
    }

    private static FinanceCreditOperationClient.Operation operation(UUID operationId, String state) {
        return new FinanceCreditOperationClient.Operation(
                operationId, USER, "video_production_video", state,
                "paid", 42L, UUID.randomUUID().toString(),
                "compensated".equals(state) ? UUID.randomUUID().toString() : null);
    }

    private UUID insertRun(String status, Integer actualCents, Integer seconds) {
        UUID id = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        db.sql("""
                INSERT INTO ai_run(id, account_id, capability, provider, model, run_type,
                    budget_cents, actual_cents, video_seconds, status, operation_id,
                    completed_at, price_table_version)
                VALUES (CAST(:id AS uuid), :account, 'video_generation', 'minimax', 'video-01',
                    'async', 10, :actual, :seconds, :status, CAST(:operation AS uuid),
                    CASE WHEN :status='running' THEN NULL ELSE now() END, 'prod-v1')
                """)
                .bind("id", id.toString()).bind("account", USER)
                .bind("actual", nullable(actualCents, Integer.class))
                .bind("seconds", nullable(seconds, Integer.class))
                .bind("status", status).bind("operation", operation.toString())
                .then().block();
        return id;
    }

    private void insertJob(
            UUID runId, String status, Integer actualCost, Integer actualSeconds,
            String resultReference) {
        db.sql("""
                INSERT INTO video_generation_job(
                    account_id, idempotency_key, run_id, provider, model, status, progress,
                    input_payload, result_url, requested_duration_seconds, actual_duration_seconds,
                    aspect_ratio, pricing_version, unit_price_cents, estimated_cost_cents,
                    actual_cost_cents, platform_model_version, completed_at)
                VALUES (:account, :key, CAST(:run AS uuid), 'minimax', 'video-01', :status, 100,
                    '{"script":"secret script"}'::jsonb, :reference, 5, :actualSeconds,
                    '9:16', 'prod-v1', 2, 10, :actualCost, 1,
                    CASE WHEN :status IN ('succeeded','failed','cancelled') THEN now() ELSE NULL END)
                """)
                .bind("account", USER).bind("key", UUID.randomUUID().toString())
                .bind("run", runId.toString()).bind("status", status)
                .bind("reference", nullable(resultReference, String.class))
                .bind("actualSeconds", nullable(actualSeconds, Integer.class))
                .bind("actualCost", nullable(actualCost, Integer.class))
                .then().block();
    }

    private void insertCompensation(UUID runId, String status) {
        UUID operation = db.sql("SELECT operation_id::text AS id FROM ai_run WHERE id=CAST(:id AS uuid)")
                .bind("id", runId.toString())
                .map(row -> UUID.fromString(row.get("id", String.class))).one().block();
        db.sql("""
                INSERT INTO ai_credit_compensation(
                    run_id, actual_run_id, consume_operation_id, account_id, feature,
                    reason, status, standalone)
                VALUES (CAST(:run AS uuid), CAST(:run AS uuid), CAST(:operation AS uuid),
                    :account, 'video_production_video', 'provider failed', :status, false)
                """)
                .bind("run", runId.toString()).bind("operation", operation.toString())
                .bind("account", USER).bind("status", status).then().block();
    }
}
