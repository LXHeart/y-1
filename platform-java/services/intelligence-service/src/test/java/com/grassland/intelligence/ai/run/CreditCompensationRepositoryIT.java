package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class CreditCompensationRepositoryIT extends IntelligenceItSupport {

    private CreditCompensationRepository repository;

    @BeforeEach
    void clean() {
        repository = new CreditCompensationRepository(db);
        db.sql("DELETE FROM ai_credit_compensation").then().block();
        db.sql("DELETE FROM ai_run").then().block();
    }

    @Test
    void replicasClaimOnePendingIntentOnlyOnce() {
        UUID runId = insertRun();
        repository.enqueue(runId, UUID.randomUUID(), UUID.randomUUID().toString(), "ai_run_text", "failed")
                .block();
        CreditCompensationRepository other = new CreditCompensationRepository(db);

        var claims = Mono.zip(
                        repository.claimBatch(10, UUID.randomUUID(), Duration.ofMinutes(1)).collectList(),
                        other.claimBatch(10, UUID.randomUUID(), Duration.ofMinutes(1)).collectList())
                .block();

        assertThat(claims).isNotNull();
        assertThat(claims.getT1().size() + claims.getT2().size()).isEqualTo(1);
    }

    @Test
    void unknownConsumeWithoutAiRunCanBePersistedAndClaimed() {
        UUID operationId = UUID.randomUUID();
        repository.enqueueUnknownConsume(
                operationId, UUID.randomUUID().toString(), "creation_assistant", "unknown response")
                .block();

        var claims = repository.claimBatch(
                        10, UUID.randomUUID(), Duration.ofMinutes(1))
                .collectList().block();

        assertThat(claims).singleElement().satisfies(claim -> {
            assertThat(claim.runId()).isNull();
            assertThat(claim.consumeOperationId()).isEqualTo(operationId);
        });
    }

    @Test
    void standaloneIntentKeepsLegacyWorkerRunIdReadableDuringRollingUpgrade() {
        UUID operationId = UUID.randomUUID();
        repository.enqueueUnknownConsume(
                operationId, UUID.randomUUID().toString(), "creation_assistant", "unknown response")
                .block();

        String legacyRunId = db.sql("""
                        SELECT run_id::text
                        FROM ai_credit_compensation
                        WHERE consume_operation_id = CAST(:operationId AS uuid)
                        """)
                .bind("operationId", operationId.toString())
                .map(row -> row.get("run_id", String.class)).one().block();

        assertThat(UUID.fromString(legacyRunId)).isEqualTo(operationId);
    }

    @Test
    void aiRunAttachesToExistingUnknownConsumeWithoutDuplicatingIntent() {
        UUID operationId = UUID.randomUUID();
        String accountId = UUID.randomUUID().toString();
        repository.enqueueUnknownConsume(
                operationId, accountId, "ai_run_text", "unknown response").block();
        UUID runId = insertRun(operationId);

        repository.enqueue(runId, operationId, accountId, "ai_run_text", "run failed").block();

        var persisted = db.sql("""
                        SELECT count(*) AS total, max(run_id::text) AS run_id
                        FROM ai_credit_compensation
                        WHERE consume_operation_id = CAST(:operationId AS uuid)
                        """)
                .bind("operationId", operationId.toString())
                .map((row, metadata) -> java.util.List.of(
                        row.get("total", Long.class), row.get("run_id", String.class)))
                .one().block();
        assertThat(persisted).containsExactly(1L, runId.toString());
    }

    @Test
    void staleClaimCannotCompleteReclaimedIntent() {
        UUID runId = insertRun();
        repository.enqueue(runId, UUID.randomUUID(), UUID.randomUUID().toString(), "ai_run_text", "failed")
                .block();
        UUID firstToken = UUID.randomUUID();
        var first = repository.claimRun(runId, firstToken, Duration.ofMinutes(1)).block();
        db.sql("UPDATE ai_credit_compensation SET claimed_until = now() - interval '1 second'")
                .then().block();
        UUID secondToken = UUID.randomUUID();
        var second = repository.claimRun(runId, secondToken, Duration.ofMinutes(1)).block();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(repository.markCompleted(first.id(), firstToken).block()).isFalse();
        assertThat(repository.markCompleted(second.id(), secondToken).block()).isTrue();
    }

    @Test
    void terminalFailureCannotBeClaimedAgain() {
        UUID runId = insertRun();
        repository.enqueue(runId, UUID.randomUUID(), UUID.randomUUID().toString(), "ai_run_text", "failed")
                .block();
        UUID claimToken = UUID.randomUUID();
        var claim = repository.claimRun(runId, claimToken, Duration.ofMinutes(1)).block();

        assertThat(claim).isNotNull();
        assertThat(repository.markTerminalFailed(claim.id(), claimToken, "IntelligenceException:409").block())
                .isTrue();
        assertThat(repository.claimRun(runId, UUID.randomUUID(), Duration.ofMinutes(1)).block())
                .isNull();
        var state = db.sql("SELECT status, failed_at IS NOT NULL AS has_failed_at "
                        + "FROM ai_credit_compensation WHERE run_id=CAST(:runId AS uuid)")
                .bind("runId", runId.toString())
                .map((row, meta) -> java.util.List.of(
                        row.get("status", String.class), row.get("has_failed_at", Boolean.class)))
                .one().block();
        assertThat(state).containsExactly("failed", true);
    }

    private UUID insertRun() {
        return insertRun(UUID.randomUUID());
    }

    private UUID insertRun(UUID operationId) {
        UUID runId = UUID.randomUUID();
        db.sql("""
                INSERT INTO ai_run(id, account_id, capability, provider, model, run_type,
                                   budget_cents, operation_id)
                VALUES (CAST(:id AS uuid), :account, 'text', 'qwen', 'qwen-plus', 'sync', 1,
                        CAST(:operationId AS uuid))
                """)
                .bind("id", runId.toString())
                .bind("account", UUID.randomUUID().toString())
                .bind("operationId", operationId.toString())
                .then().block();
        return runId;
    }
}
