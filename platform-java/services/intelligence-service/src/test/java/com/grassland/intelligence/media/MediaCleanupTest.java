package com.grassland.intelligence.media;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.intelligence.event.OutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** MediaCleanup 的 claim、双 key 删除、审计顺序与失败重试语义。 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class MediaCleanupTest {

    @Mock
    private MediaReferenceRepository mediaRefs;

    @Mock
    private ObjectStorageAdapter storage;
    @Mock private OutboxRepository outbox;
    @Mock private TransactionalOperator transactions;

    private MediaCleanup cleanup;

    @BeforeEach
    void setUp() {
        cleanup = new MediaCleanup(mediaRefs, storage, outbox, transactions, 3600, 900);
        lenient().when(transactions.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(outbox.append(any())).thenReturn(Mono.empty());
        lenient().when(storage.listObjects("media-pending/")).thenReturn(List.of());
        lenient().when(mediaRefs.releaseQuota(any())).thenReturn(Mono.empty());
    }

    @Test
    void cleanupClaimsDeletesBothKeysThenCompletesAudit() {
        MediaReference ref = expired();
        MediaReference claimed = withStatus(ref, MediaStatus.DELETING);
        when(mediaRefs.findCleanupCandidates(Duration.ofHours(1))).thenReturn(Flux.just(ref));
        when(mediaRefs.claimCleanup(ref.id())).thenReturn(Mono.just(claimed));
        when(mediaRefs.completeDelete(ref.id())).thenReturn(Mono.just(true));

        StepVerifier.create(cleanup.cleanup()).verifyComplete();

        verify(storage).deleteObject(ref.objectKey());
        verify(storage).deleteObject(ref.uploadKey());
        verify(mediaRefs).completeDelete(ref.id());
    }

    @Test
    void cleanupKeepsDeletingWhenObjectDeletionFailsSoNextRoundCanRetry() {
        MediaReference ref = expired();
        MediaReference claimed = withStatus(ref, MediaStatus.DELETING);
        when(mediaRefs.findCleanupCandidates(Duration.ofHours(1))).thenReturn(Flux.just(ref));
        when(mediaRefs.claimCleanup(ref.id())).thenReturn(Mono.just(claimed));
        doThrow(new RuntimeException("storage unavailable"))
                .when(storage).deleteObject(ref.objectKey());

        StepVerifier.create(cleanup.cleanup()).verifyComplete();

        verify(mediaRefs, never()).completeDelete(ref.id());
    }

    @Test
    void cleanupRetriesPreviouslyDeletingCandidate() {
        MediaReference ref = withStatus(expired(), MediaStatus.DELETING);
        when(mediaRefs.findCleanupCandidates(Duration.ofHours(1))).thenReturn(Flux.just(ref));
        when(mediaRefs.claimCleanup(ref.id())).thenReturn(Mono.just(ref));
        when(mediaRefs.completeDelete(ref.id())).thenReturn(Mono.just(true));

        StepVerifier.create(cleanup.cleanup()).verifyComplete();

        verify(mediaRefs).claimCleanup(ref.id());
        verify(mediaRefs).completeDelete(ref.id());
    }

    private static MediaReference expired() {
        UUID id = UUID.randomUUID();
        return new MediaReference(
                id, "acct", null, MediaPurpose.USER_UPLOAD.db(), null, null,
                "media/user_upload/" + id, "media-pending/" + id,
                "image/png", 1L, "checksum", "upload",
                MediaStatus.ACTIVE, Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600), null);
    }

    private static MediaReference withStatus(MediaReference ref, MediaStatus status) {
        return new MediaReference(
                ref.id(), ref.ownerAccountId(), ref.organizationId(), ref.purpose(),
                ref.domainType(), ref.domainId(), ref.objectKey(), ref.uploadKey(),
                ref.mimeType(), ref.sizeBytes(), ref.checksum(), ref.source(), status,
                ref.createdAt(), ref.expiresAt(), ref.deletedAt());
    }
}
