package com.grassland.intelligence.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** media_reference 仓储 + V4 状态机集成测试。testcontainers postgres，Flyway V4 已建表。 */
class MediaReferenceRepositoryIT extends IntelligenceItSupport {

    @Autowired
    private MediaReferenceRepository repo;

    @Autowired
    private KybMediaRetentionRepository kybRetentions;

    @Autowired
    private TransactionalOperator transactions;

    @Test
    void insertPersistsBothKeysAndFindsById() {
        MediaReference ref = newMedia(MediaStatus.PENDING, Instant.now().plusSeconds(3600));

        StepVerifier.create(repo.insert(ref))
                .assertNext(saved -> {
                    assertThat(saved.id()).isEqualTo(ref.id());
                    assertThat(saved.status()).isEqualTo(MediaStatus.PENDING);
                    assertThat(saved.ownerAccountId()).isEqualTo(ref.ownerAccountId());
                    assertThat(saved.objectKey()).isEqualTo(ref.objectKey());
                    assertThat(saved.uploadKey()).isEqualTo(ref.uploadKey());
                    assertThat(saved.createdAt()).isNotNull();
                    assertThat(saved.expiresAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void finalizeStateMachineClaimsOnceAndCompletes() {
        MediaReference ref = newMedia(MediaStatus.PENDING, null);
        repo.insert(ref).block();

        StepVerifier.create(repo.claimFinalize(ref.id()))
                .assertNext(claimed -> {
                    assertThat(claimed.status()).isEqualTo(MediaStatus.FINALIZING);
                    assertThat(claimed.uploadKey()).isEqualTo(ref.uploadKey());
                    assertThat(claimed.objectKey()).isEqualTo(ref.objectKey());
                })
                .verifyComplete();
        StepVerifier.create(repo.claimFinalize(ref.id())).verifyComplete();

        StepVerifier.create(repo.completeFinalize(ref.id(), "image/png", 42L, "abc123"))
                .assertNext(active -> {
                    assertThat(active.status()).isEqualTo(MediaStatus.ACTIVE);
                    assertThat(active.objectKey()).isEqualTo(ref.objectKey());
                    assertThat(active.uploadKey()).isEqualTo(ref.uploadKey());
                    assertThat(active.sizeBytes()).isEqualTo(42L);
                    assertThat(active.checksum()).isEqualTo("abc123");
                })
                .verifyComplete();
    }

    @Test
    void releaseFinalizeAllowsRetry() {
        MediaReference ref = newMedia(MediaStatus.PENDING, null);
        repo.insert(ref).block();
        repo.claimFinalize(ref.id()).block();

        StepVerifier.create(repo.releaseFinalize(ref.id())).expectNext(true).verifyComplete();
        StepVerifier.create(repo.claimFinalize(ref.id()))
                .assertNext(claimed -> assertThat(claimed.status()).isEqualTo(MediaStatus.FINALIZING))
                .verifyComplete();
    }

    @Test
    void deleteStateMachineChecksOwnerAndRetainsAudit() {
        MediaReference ref = newMedia(MediaStatus.ACTIVE, null);
        repo.insert(ref).block();

        StepVerifier.create(repo.claimDelete(ref.id(), "other")).verifyComplete();
        StepVerifier.create(repo.claimDelete(ref.id(), ref.ownerAccountId()))
                .assertNext(claimed -> assertThat(claimed.status()).isEqualTo(MediaStatus.DELETING))
                .verifyComplete();
        StepVerifier.create(repo.completeDelete(ref.id())).expectNext(true).verifyComplete();

        StepVerifier.create(repo.findById(ref.id()))
                .assertNext(deleted -> {
                    assertThat(deleted.status()).isEqualTo(MediaStatus.DELETED);
                    assertThat(deleted.uploadKey()).isNull();
                    assertThat(deleted.deletedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void staleDeletingCanBeReclaimedForCleanupRetry() {
        MediaReference ref = newMedia(MediaStatus.ACTIVE, Instant.now().minusSeconds(3600));
        repo.insert(ref).block();
        repo.claimDelete(ref.id(), ref.ownerAccountId()).block();

        StepVerifier.create(repo.claimCleanup(ref.id()))
                .assertNext(claimed -> assertThat(claimed.status()).isEqualTo(MediaStatus.DELETING))
                .verifyComplete();
    }

    @Test
    void releaseQuotaIsAtomicUploadOnlyAndIdempotent() {
        String owner = "acct-" + UUID.randomUUID();
        MediaReference upload = newMedia(owner, MediaStatus.ACTIVE, 100L, null);
        repo.insertIfQuotaAllowed(upload, 10, 1_000_000L).block();
        MediaReference generated = newMedia(owner, MediaStatus.ACTIVE, 5_000L, null);
        MediaReference generatedRow = new MediaReference(
                generated.id(), generated.ownerAccountId(), generated.organizationId(),
                generated.purpose(), generated.domainType(), generated.domainId(),
                generated.objectKey(), generated.uploadKey(), generated.mimeType(),
                generated.sizeBytes(), generated.checksum(), "generated", MediaStatus.ACTIVE,
                generated.createdAt(), generated.expiresAt(), generated.deletedAt());
        repo.insert(generatedRow).block();
        assertThat(counterObjectCount(owner)).isEqualTo(1L);

        // generated 行从未预留配额，释放不应改变计数。
        repo.releaseQuota(generatedRow.id()).block();
        assertThat(counterObjectCount(owner)).isEqualTo(1L);

        // upload 行释放：计数递减到 0。
        repo.releaseQuota(upload.id()).block();
        assertThat(counterObjectCount(owner)).isEqualTo(0L);

        // 幂等：重复释放 upload 不再扣（quota_released 标志生效）。
        repo.releaseQuota(upload.id()).block();
        assertThat(counterObjectCount(owner)).isEqualTo(0L);
    }

    private Long counterObjectCount(String owner) {
        return db.sql("SELECT COALESCE(object_count,0)::bigint AS c FROM media_owner_quota WHERE owner_account_id=:owner")
                .bind("owner", owner).map(row -> row.get("c", Long.class)).one().block();
    }

    @Test
    void insertIfQuotaAllowedSerializesConcurrentInsertsToOneWinner() {
        String owner = "acct-" + UUID.randomUUID();
        java.util.List<MediaReference> pending = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> newMedia(owner, MediaStatus.PENDING, 1L, null))
                .toList();

        StepVerifier.create(reactor.core.publisher.Flux.fromIterable(pending)
                        .flatMap(ref -> repo.insertIfQuotaAllowed(ref, 1, 1_000_000L)
                                .map(ignored -> true)
                                .defaultIfEmpty(false))
                        .collectList())
                .assertNext(results -> {
                    assertThat(results).hasSize(6);
                    assertThat(results.stream().filter(success -> success).count()).isEqualTo(1L);
                })
                .verifyComplete();
    }

    @Test
    void usageCountsReservedBytesAndExcludesDeleting() {
        String owner = "acct-" + UUID.randomUUID();
        MediaReference pending = newMedia(owner, MediaStatus.PENDING, 10L, null);
        MediaReference active = newMedia(owner, MediaStatus.ACTIVE, 20L, null);
        repo.insert(pending).block();
        repo.insert(active).block();

        StepVerifier.create(repo.usageByOwner(owner))
                .assertNext(usage -> {
                    assertThat(usage.objectCount()).isEqualTo(2);
                    assertThat(usage.totalBytes()).isEqualTo(30);
                })
                .verifyComplete();

        repo.claimDelete(active.id(), owner).block();
        StepVerifier.create(repo.usageByOwner(owner))
                .assertNext(usage -> {
                    assertThat(usage.objectCount()).isEqualTo(1);
                    assertThat(usage.totalBytes()).isEqualTo(10);
                })
                .verifyComplete();
    }

    @Test
    void findCleanupCandidatesReturnsExpiredAndStaleRowsOnly() {
        MediaReference expired = newMedia(MediaStatus.ACTIVE, Instant.now().minusSeconds(3600));
        MediaReference fresh = newMedia(MediaStatus.ACTIVE, Instant.now().plusSeconds(3600));
        MediaReference retainedKyb = newKybMedia(MediaStatus.ACTIVE, Instant.now().plusSeconds(3600));
        repo.insert(expired).block();
        repo.insert(fresh).block();
        repo.insert(retainedKyb).block();
        kybRetentions.retain(retainedKyb.id(), retainedKyb.organizationId(), UUID.randomUUID()).block();

        StepVerifier.create(repo.findCleanupCandidates(Duration.ofHours(1)).map(MediaReference::id).collectList())
                .assertNext(ids -> {
                    assertThat(ids).contains(expired.id());
                    assertThat(ids).doesNotContain(fresh.id(), retainedKyb.id());
                })
                .verifyComplete();
        StepVerifier.create(repo.claimCleanup(retainedKyb.id())).verifyComplete();
        StepVerifier.create(repo.claimDelete(retainedKyb.id(), retainedKyb.ownerAccountId())).verifyComplete();
    }

    @Test
    void kybRetentionBlocksDeleteUntilAllReferencesAreReleased() {
        MediaReference unbound = newKybMedia(MediaStatus.ACTIVE, null);
        repo.insert(unbound).block();
        StepVerifier.create(repo.claimDelete(unbound.id(), unbound.ownerAccountId()))
                .assertNext(claimed -> assertThat(claimed.status()).isEqualTo(MediaStatus.DELETING))
                .verifyComplete();

        MediaReference retained = newKybMedia(MediaStatus.ACTIVE, null);
        repo.insert(retained).block();
        UUID requestId = UUID.randomUUID();
        assertThat(kybRetentions.retain(retained.id(), retained.organizationId(), requestId).block()).isTrue();
        StepVerifier.create(repo.claimDelete(retained.id(), retained.ownerAccountId())).verifyComplete();
        StepVerifier.create(repo.claimCleanup(retained.id())).verifyComplete();

        assertThat(kybRetentions.release(retained.id(), retained.organizationId(), requestId).block()).isTrue();
        StepVerifier.create(repo.claimDelete(retained.id(), retained.ownerAccountId()))
                .assertNext(claimed -> assertThat(claimed.status()).isEqualTo(MediaStatus.DELETING))
                .verifyComplete();
    }

    @Test
    void kybLeaseRenewalCannotShortenAndExpiredLeaseStopsBlockingDelete() {
        MediaReference retained = newKybMedia(MediaStatus.ACTIVE, null);
        repo.insert(retained).block();
        UUID referenceId = UUID.randomUUID();

        KybMediaRetentionRepository.Retention first = kybRetentions.upsertLease(
                retained.id(), retained.organizationId(), referenceId, "attachment", Duration.ofHours(2)).block();
        KybMediaRetentionRepository.Retention renewed = kybRetentions.upsertLease(
                retained.id(), retained.organizationId(), referenceId, "attachment", Duration.ofMinutes(5)).block();

        assertThat(first).isNotNull();
        assertThat(renewed).isNotNull();
        assertThat(renewed.leaseUntil()).isAfterOrEqualTo(first.leaseUntil());
        assertThat(kybRetentions.isRetained(retained.id()).block()).isTrue();

        db.sql("UPDATE media_kyb_retention SET lease_until=now()-interval '1 second' "
                        + "WHERE media_reference_id=CAST(:media AS uuid) AND reference_id=CAST(:reference AS uuid)")
                .bind("media", retained.id()).bind("reference", referenceId).then().block();

        assertThat(kybRetentions.isRetained(retained.id()).block()).isFalse();
        StepVerifier.create(repo.claimDelete(retained.id(), retained.ownerAccountId()))
                .assertNext(claimed -> assertThat(claimed.status()).isEqualTo(MediaStatus.DELETING))
                .verifyComplete();
    }

    @Test
    void sealedRetentionCannotBeShortenedOrReleasedBeforeItsDeadline() {
        MediaReference retained = newKybMedia(MediaStatus.ACTIVE, null);
        repo.insert(retained).block();
        UUID requestId = UUID.randomUUID();
        Instant retainUntil = Instant.now().plus(Duration.ofDays(30));

        KybMediaRetentionRepository.Retention sealed = kybRetentions.seal(
                retained.id(), retained.organizationId(), requestId, "review_request", retainUntil).block();
        KybMediaRetentionRepository.Retention shorter = kybRetentions.seal(
                retained.id(), retained.organizationId(), requestId, "review_request",
                Instant.now().plus(Duration.ofDays(1))).block();

        assertThat(sealed).isNotNull();
        assertThat(shorter).isNotNull();
        assertThat(shorter.retainedUntil()).isAfterOrEqualTo(sealed.retainedUntil());
        assertThat(kybRetentions.release(retained.id(), retained.organizationId(), requestId).block()).isFalse();
        assertThat(kybRetentions.isRetained(retained.id()).block()).isTrue();

        db.sql("UPDATE media_kyb_retention SET retained_until=now()-interval '1 second' "
                        + "WHERE media_reference_id=CAST(:media AS uuid) AND reference_id=CAST(:reference AS uuid)")
                .bind("media", retained.id()).bind("reference", requestId).then().block();

        assertThat(kybRetentions.release(retained.id(), retained.organizationId(), requestId).block()).isTrue();
        assertThat(kybRetentions.isRetained(retained.id()).block()).isFalse();
    }

    @Test
    void retentionCommittedWhileOwnerDeleteWaitsPreventsDeleteClaim() throws Exception {
        assertConcurrentRetentionWins(ref -> repo.claimDelete(ref.id(), ref.ownerAccountId()));
    }

    @Test
    void retentionCommittedWhileCleanupWaitsPreventsCleanupClaim() throws Exception {
        assertConcurrentRetentionWins(ref -> repo.claimCleanup(ref.id()));
    }

    private void assertConcurrentRetentionWins(Function<MediaReference, Mono<MediaReference>> claim) throws Exception {
        MediaReference retained = newKybMedia(MediaStatus.ACTIVE, null);
        repo.insert(retained).block();
        UUID referenceId = UUID.randomUUID();
        CountDownLatch mediaLocked = new CountDownLatch(1);
        Sinks.Empty<Void> allowRetentionInsert = Sinks.empty();

        CompletableFuture<Void> retention = transactions.transactional(
                        db.sql("SELECT id FROM media_reference WHERE id=CAST(:media AS uuid) FOR UPDATE")
                                .bind("media", retained.id()).map(row -> row.get(0)).one()
                                .doOnNext(ignored -> mediaLocked.countDown())
                                .then(allowRetentionInsert.asMono())
                                .then(kybRetentions.upsertLease(retained.id(), retained.organizationId(), referenceId,
                                        "review_request", Duration.ofDays(7)))
                                .then())
                .subscribeOn(Schedulers.boundedElastic()).toFuture();

        assertThat(mediaLocked.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MediaReference> deletion = claim.apply(retained)
                .subscribeOn(Schedulers.boundedElastic()).toFuture();
        Thread.sleep(150);
        assertThat(deletion).isNotDone();

        allowRetentionInsert.tryEmitEmpty();
        retention.get(5, TimeUnit.SECONDS);
        assertThat(deletion.get(5, TimeUnit.SECONDS)).isNull();
        assertThat(repo.findById(retained.id()).block().status()).isEqualTo(MediaStatus.ACTIVE);
        assertThat(kybRetentions.isRetained(retained.id()).block()).isTrue();
    }

    private static MediaReference newKybMedia(MediaStatus status, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        String organizationId = UUID.randomUUID().toString();
        return new MediaReference(
                id,
                "acct-" + UUID.randomUUID(),
                organizationId,
                MediaPurpose.MERCHANT_KYB.db(),
                MediaPurpose.MERCHANT_KYB.db(),
                organizationId,
                "media/merchant_kyb/" + id,
                "media-pending/" + id,
                "image/png",
                8L,
                null,
                "upload",
                status,
                null,
                expiresAt,
                null);
    }

    private static MediaReference newMedia(MediaStatus status, Instant expiresAt) {
        return newMedia("acct-" + UUID.randomUUID(), status, 1L, expiresAt);
    }

    private static MediaReference newMedia(
            String ownerAccountId, MediaStatus status, long sizeBytes, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        return new MediaReference(
                id,
                ownerAccountId,
                null,
                MediaPurpose.USER_UPLOAD.db(),
                null,
                null,
                "media/user_upload/" + id,
                "media-pending/" + id,
                "image/png",
                sizeBytes,
                null,
                "upload",
                status,
                null,
                expiresAt,
                null);
    }
}
