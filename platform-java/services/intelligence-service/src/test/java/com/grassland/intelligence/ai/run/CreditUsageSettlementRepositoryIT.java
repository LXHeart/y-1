package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditSettlement;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class CreditUsageSettlementRepositoryIT extends IntelligenceItSupport {

    private CreditUsageSettlementRepository repository;

    @BeforeEach
    void clean() {
        repository = new CreditUsageSettlementRepository(db);
        db.sql("DELETE FROM ai_credit_usage_settlement").then().block();
        db.sql("DELETE FROM ai_credit_compensation").then().block();
        db.sql("DELETE FROM ai_run").then().block();
    }

    @Test
    void replicasClaimOnePendingIntentOnlyOnce() {
        UUID runId = insertRun();
        repository.enqueue(
                runId, UUID.randomUUID(), UUID.randomUUID().toString(),
                "ai_run_text", "money-v1", 125).block();
        CreditUsageSettlementRepository other = new CreditUsageSettlementRepository(db);

        var claims = Mono.zip(
                        repository.claimBatch(10, UUID.randomUUID(), Duration.ofMinutes(1)).collectList(),
                        other.claimBatch(10, UUID.randomUUID(), Duration.ofMinutes(1)).collectList())
                .block();

        assertThat(claims).isNotNull();
        assertThat(claims.getT1().size() + claims.getT2().size()).isEqualTo(1);
    }

    @Test
    void expiredLeaseCanBeReclaimedAndStaleOwnerCannotComplete() {
        UUID runId = insertRun();
        UUID operationId = UUID.randomUUID();
        String accountId = UUID.randomUUID().toString();
        repository.enqueue(runId, operationId, accountId, "ai_run_text", "money-v1", 125).block();

        UUID firstToken = UUID.randomUUID();
        var first = repository.claimBatch(1, firstToken, Duration.ofMinutes(1)).single().block();
        db.sql("UPDATE ai_credit_usage_settlement SET claimed_until = now() - interval '1 second'")
                .then().block();
        UUID secondToken = UUID.randomUUID();
        var second = repository.claimBatch(1, secondToken, Duration.ofMinutes(1)).single().block();
        CreditSettlement result = settlement(accountId, operationId);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(repository.markCompleted(first.id(), firstToken, result).block()).isFalse();
        assertThat(repository.markCompleted(second.id(), secondToken, result).block()).isTrue();
    }

    @Test
    void completionPersistsFinanceAdjustmentAndCannotBeClaimedAgain() {
        UUID runId = insertRun();
        UUID operationId = UUID.randomUUID();
        String accountId = UUID.randomUUID().toString();
        repository.enqueue(runId, operationId, accountId, "ai_run_text", "money-v1", 100).block();
        UUID token = UUID.randomUUID();
        var claim = repository.claimBatch(1, token, Duration.ofMinutes(1)).single().block();

        assertThat(repository.markCompleted(claim.id(), token, settlement(accountId, operationId)).block())
                .isTrue();
        assertThat(repository.claimBatch(1, UUID.randomUUID(), Duration.ofMinutes(1)).collectList().block())
                .isEmpty();
        var persisted = db.sql("""
                        SELECT status, charge_source, reserved_cents, reserved_credits,
                               actual_cents, actual_credits, adjustment_credits,
                               completed_at IS NOT NULL AS completed
                        FROM ai_credit_usage_settlement
                        WHERE run_id = CAST(:runId AS uuid)
                        """)
                .bind("runId", runId.toString())
                .map((row, metadata) -> java.util.List.of(
                        row.get("status", String.class), row.get("charge_source", String.class),
                        row.get("reserved_cents", Long.class), row.get("reserved_credits", Integer.class),
                        row.get("actual_cents", Integer.class), row.get("actual_credits", Integer.class),
                        row.get("adjustment_credits", Integer.class), row.get("completed", Boolean.class)))
                .one().block();
        assertThat(persisted).containsExactly("completed", "paid", 300L, 3, 100, 1, -2, true);
    }

    private CreditSettlement settlement(String accountId, UUID operationId) {
        return new CreditSettlement(
                accountId, CreditFeature.AI_RUN_TEXT, operationId.toString(),
                CreditCharge.Source.PAID, "money-v1", 300, 3, 100, 1, -2, false);
    }

    private UUID insertRun() {
        UUID runId = UUID.randomUUID();
        db.sql("""
                INSERT INTO ai_run(id, account_id, capability, provider, model, run_type,
                                   budget_cents, operation_id, credits_cents_policy_version)
                VALUES (CAST(:id AS uuid), :account, 'text', 'qwen', 'qwen-plus', 'sync', 300,
                        CAST(:operationId AS uuid), 'money-v1')
                """)
                .bind("id", runId.toString())
                .bind("account", UUID.randomUUID().toString())
                .bind("operationId", UUID.randomUUID().toString())
                .then().block();
        return runId;
    }
}
