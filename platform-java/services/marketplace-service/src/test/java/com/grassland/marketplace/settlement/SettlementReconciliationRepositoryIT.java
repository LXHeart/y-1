package com.grassland.marketplace.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** {@link SettlementReconciliationRepository} 状态机与查询（Slice 7B）。 */
class SettlementReconciliationRepositoryIT extends MarketplaceItSupport {

    private static final String APP = "app-repo-it";
    private static final String DECISION = "for_recommender";
    private static final String WORKFLOW = "settlement-reconcile-dispute-repo-it";

    @Test
    void enqueueIsIdempotentBySourceEventAndRejectsDuplicateDispute() {
        assertThat(enqueue("src-1", "dispute-repo-it")).isTrue();
        assertThat(enqueue("src-1", "dispute-repo-it")).isFalse();  // 同 source_event_id → noop

        SettlementReconciliation row = repos().findBySourceEventId("src-1").block();
        assertThat(row).isNotNull();
        assertThat(row.status()).isEqualTo("pending");
        assertThat(row.applicationId()).isEqualTo(APP);
        assertThat(row.workflowId()).isEqualTo(WORKFLOW);
    }

    @Test
    void markStartedAndTerminalTransitionsAreGuarded() {
        enqueue("src-2", "dispute-2");
        assertThat(repos().markStarted("src-2", 1, Duration.ofSeconds(30)).block()).isTrue();
        SettlementReconciliation started = repos().findBySourceEventId("src-2").block();
        assertThat(started.status()).isEqualTo("started");
        assertThat(started.dispatchAttempt()).isEqualTo(1);

        assertThat(repos().markReconciled("src-2").block()).isTrue();
        assertThat(repos().findBySourceEventId("src-2").block().status()).isEqualTo("reconciled");
        assertThat(repos().markReconciled("src-2").block()).isFalse();  // 终态不可再迁移
    }

    @Test
    void markBlockedRecordsReason() {
        enqueue("src-3", "dispute-3");
        repos().markStarted("src-3", 1, Duration.ofSeconds(30)).block();
        assertThat(repos().markBlocked("src-3", "finance_missing").block()).isTrue();
        SettlementReconciliation blocked = repos().findBySourceEventId("src-3").block();
        assertThat(blocked.status()).isEqualTo("blocked");
        assertThat(blocked.reason()).isEqualTo("finance_missing");
    }

    @Test
    void findDispatchableReturnsPendingAndStaleStarted() {
        enqueue("src-pending", "dispute-p");
        enqueue("src-stale", "dispute-s");
        repos().markStarted("src-stale", 1, Duration.ofSeconds(30)).block();
        // started 行 next_dispatch_at 在未来 → 暂不可派发
        assertThat(repos().findDispatchable(10).map(SettlementReconciliation::sourceEventId).collectList().block())
                .contains("src-pending").doesNotContain("src-stale");

        // 把 next_dispatch_at 拨到过去 → 可重派发
        db.sql("UPDATE settlement_reconciliation SET next_dispatch_at = now() - interval '1 second'"
                + " WHERE source_event_id = 'src-stale'").then().block();
        assertThat(repos().findDispatchable(10).map(SettlementReconciliation::sourceEventId).collectList().block())
                .contains("src-pending", "src-stale");
    }

    @Test
    void findLatestForApplicationIsChronological() {
        enqueue("src-old", "dispute-old");
        // 强制 src-old 早于 src-new
        db.sql("UPDATE settlement_reconciliation SET created_at = now() - interval '1 hour'"
                + " WHERE source_event_id = 'src-old'").then().block();
        enqueue("src-new", "dispute-new");

        SettlementReconciliation latest = repos().findLatestForApplication(APP).block();
        assertThat(latest.sourceEventId()).isEqualTo("src-new");
    }

    @Test
    void enqueueSurfacesUniqueDisputeViolation() {
        enqueue("src-a", "dispute-shared");
        StepVerifier.create(enqueueMono("src-b", "dispute-shared"))  // 不同 source，同 dispute → 唯一冲突
                .verifyError();
    }

    private Boolean enqueue(String sourceEventId, String disputeId) {
        return enqueueMono(sourceEventId, disputeId).block();
    }

    private reactor.core.publisher.Mono<Boolean> enqueueMono(String sourceEventId, String disputeId) {
        return repos().enqueue(sourceEventId, disputeId, APP, null, DECISION, WORKFLOW);
    }

    private SettlementReconciliationRepository repos() {
        return new SettlementReconciliationRepository(db);
    }
}
