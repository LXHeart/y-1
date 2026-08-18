package com.grassland.intelligence.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ContentAssetEmbeddingRepositoryIT extends IntelligenceItSupport {

    @Autowired
    private ContentAssetEmbeddingRepository repository;

    @BeforeEach
    void clear() {
        db.sql("DELETE FROM content_asset_embedding").then().block();
    }

    @Test
    void enqueue_isIdempotentWhilePendingOrProcessing() {
        UUID asset = UUID.randomUUID();
        assertThat(repository.enqueue(asset, 1, "hash-a").block()).isTrue();
        assertThat(repository.enqueue(asset, 1, "hash-a").block()).isFalse();
        repository.claimBatch(1, UUID.randomUUID(), Duration.ofMinutes(1)).single().block();
        assertThat(repository.enqueue(asset, 1, "hash-a").block()).isFalse();
        assertThat(db.sql("SELECT COUNT(*)::int AS count FROM content_asset_embedding WHERE asset_id = :assetId")
                .bind("assetId", asset).map(row -> row.get("count", Integer.class)).one().block()).isEqualTo(1);
    }

    @Test
    void claimBatch_usesTokenAndIncrementsAttemptsOnceUnderConcurrentClaimers() throws Exception {
        UUID asset = UUID.randomUUID();
        repository.enqueue(asset, 1, "hash-a").block();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return repository.claimBatch(1, UUID.randomUUID(), Duration.ofMinutes(1)).collectList().block(); });
            var second = pool.submit(() -> { start.await(); return repository.claimBatch(1, UUID.randomUUID(), Duration.ofMinutes(1)).collectList().block(); });
            start.countDown();
            var a = first.get();
            var b = second.get();
            assertThat(a.size() + b.size()).isEqualTo(1);
            ContentAssetEmbedding claimed = a.isEmpty() ? b.getFirst() : a.getFirst();
            assertThat(claimed.attemptCount()).isEqualTo(1);
            assertThat(claimed.claimToken()).isNotNull();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void claimBatch_rejectsNonPositiveLimitsLeasesAndMaxAttempts() {
        assertClaimRejected(0, Duration.ofMinutes(1), 1);
        assertClaimRejected(-1, Duration.ofMinutes(1), 1);
        assertClaimRejected(1, Duration.ZERO, 1);
        assertClaimRejected(1, Duration.ofMillis(-1), 1);
        assertClaimRejected(1, Duration.ofMinutes(1), 0);
        assertClaimRejected(1, Duration.ofMinutes(1), -1);
    }

    @Test
    void markReady_persistsVectorAndRoutingSnapshot_andFindExcludesStaleRows() {
        UUID asset = UUID.randomUUID();
        repository.enqueue(asset, 1, "hash-a").block();
        UUID token = UUID.randomUUID();
        ContentAssetEmbedding claimed = repository.claimBatch(1, token, Duration.ofMinutes(1)).single().block();
        ProviderResolution provider = ProviderResolution.platform(UUID.randomUUID(), "qwen", "http://qwen", "embed-v1", 7, 4);
        assertThat(repository.markReady(claimed.id(), token, provider, "algo-1", vector(), UUID.randomUUID()).block()).isTrue();
        assertThat(repository.findReadyForAssets(List.of(asset)).single().block())
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo("ready");
                    assertThat(row.embedding()).hasSize(256);
                    assertThat(row.provider()).isEqualTo("qwen");
                    assertThat(row.model()).isEqualTo("embed-v1");
                    assertThat(row.modelVersionKey()).isEqualTo("platform:7");
                });
        db.sql("UPDATE content_asset_embedding SET status='stale', updated_at = now() - interval '2 days' WHERE id = :id")
                .bind("id", claimed.id()).then().block();
        assertThat(repository.findReadyForAssets(List.of(asset)).collectList().block()).isEmpty();
    }

    @Test
    void failedRows_retryAfterExpiredLease_butMaxAttemptsCannotClaim() {
        UUID asset = UUID.randomUUID();
        repository.enqueue(asset, 1, "hash-a").block();
        UUID token = UUID.randomUUID();
        ContentAssetEmbedding claimed = repository.claimBatch(1, token, Duration.ofSeconds(1)).single().block();
        db.sql("UPDATE content_asset_embedding SET claimed_until = now() - interval '1 second' WHERE id = :id")
                .bind("id", claimed.id()).then().block();
        assertThat(repository.claimBatch(1, UUID.randomUUID(), Duration.ofMinutes(1)).collectList().block()).hasSize(1);

        db.sql("UPDATE content_asset_embedding SET status='failed', failure_code='retry', claim_token=NULL, claimed_until=NULL, attempt_count=5, next_attempt_at=now() - interval '1 second' WHERE id = :id")
                .bind("id", claimed.id()).then().block();
        assertThat(repository.claimBatch(1, UUID.randomUUID(), Duration.ofMinutes(1), 5).collectList().block()).isEmpty();
    }

    @Test
    void markFailed_requiresCurrentClaimAndSchedulesRetryWithoutPersistingUnstableCodes() {
        UUID asset = UUID.randomUUID();
        repository.enqueue(asset, 1, "hash-a").block();
        UUID token = UUID.randomUUID();
        ContentAssetEmbedding claimed = repository.claimBatch(1, token, Duration.ofMinutes(1)).single().block();

        for (String invalid : List.of(" ", "provider timeout", "provider: denied", "a".repeat(65))) {
            assertThatThrownBy(() -> repository.markFailed(claimed.id(), token, invalid, Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(repository.markFailed(claimed.id(), UUID.randomUUID(), "retry", Duration.ofMinutes(1)).block()).isFalse();
        assertThat(repository.markFailed(claimed.id(), token, "retry", Duration.ofMinutes(1)).block()).isTrue();
        assertThat(db.sql("""
                SELECT failure_code, claim_token::text AS claim_token, claimed_until,
                       next_attempt_at > now() AS retry_scheduled
                FROM content_asset_embedding WHERE id = :id
                """).bind("id", claimed.id())
                .map(row -> new FailedState(
                        row.get("failure_code", String.class), row.get("claim_token", String.class),
                        row.get("claimed_until", java.time.OffsetDateTime.class), row.get("retry_scheduled", Boolean.class)))
                .one().block())
                .satisfies(state -> {
                    assertThat(state.failureCode()).isEqualTo("retry");
                    assertThat(state.claimToken()).isNull();
                    assertThat(state.claimedUntil()).isNull();
                    assertThat(state.retryScheduled()).isTrue();
                });
    }

    @Test
    void markStale_requiresCurrentClaimAndRemovesWorkFromReadyAndClaimQueries() {
        UUID asset = UUID.randomUUID();
        repository.enqueue(asset, 1, "hash-a").block();
        UUID token = UUID.randomUUID();
        ContentAssetEmbedding claimed = repository.claimBatch(1, token, Duration.ofMinutes(1)).single().block();

        assertThat(repository.markStale(claimed.id(), UUID.randomUUID()).block()).isFalse();
        assertThat(repository.markStale(claimed.id(), token).block()).isTrue();
        assertThat(repository.findReadyForAssets(List.of(asset)).collectList().block()).isEmpty();
        assertThat(repository.claimBatch(1, UUID.randomUUID(), Duration.ofMinutes(1)).collectList().block()).isEmpty();
    }

    private static List<Double> vector() {
        return java.util.stream.IntStream.range(0, 256).mapToDouble(i -> i + 0.25d).boxed().toList();
    }

    private void assertClaimRejected(int limit, Duration lease, int maxAttempts) {
        assertThatThrownBy(() -> repository.claimBatch(limit, UUID.randomUUID(), lease, maxAttempts)
                .collectList().block()).isInstanceOf(IllegalArgumentException.class);
    }

    private record FailedState(
            String failureCode, String claimToken, java.time.OffsetDateTime claimedUntil, Boolean retryScheduled) {}
}
