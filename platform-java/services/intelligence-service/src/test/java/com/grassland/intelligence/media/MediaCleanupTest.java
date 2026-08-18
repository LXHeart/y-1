package com.grassland.intelligence.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.StoredObject;
import com.grassland.intelligence.event.OutboxRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.slf4j.LoggerFactory;
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
        String sensitiveMessage = "upstream body at /private/storage/path";
        doThrow(new RuntimeException(sensitiveMessage))
                .when(storage).deleteObject(ref.objectKey());

        ListAppender<ILoggingEvent> appender = attachLogger();
        try {
            StepVerifier.create(cleanup.cleanup()).verifyComplete();
        } finally {
            detachLogger(appender);
        }

        verify(mediaRefs, never()).completeDelete(ref.id());
        ILoggingEvent warning = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("media cleanup failed"))
                .findFirst()
                .orElseThrow();
        assertThat(warning.getFormattedMessage())
                .contains(ref.id().toString(), "failureStage=candidate_cleanup",
                        "exceptionType=RuntimeException")
                .doesNotContain(ref.objectKey(), ref.uploadKey(), sensitiveMessage);
        assertThat(warning.getThrowableProxy()).isNull();
    }

    @Test
    void orphanCleanupFailureLogDoesNotExposeObjectKeyOrExceptionDetails() {
        String orphanKey = "media-pending/orphan-secret-key";
        String sensitiveMessage = "raw upstream body at /private/storage/path";
        when(mediaRefs.findCleanupCandidates(Duration.ofHours(1))).thenReturn(Flux.empty());
        when(storage.listObjects("media-pending/")).thenReturn(List.of(new StoredObject(
                orphanKey, 1L, null, null, Instant.now().minusSeconds(7200))));
        doThrow(new RuntimeException(sensitiveMessage)).when(storage).deleteObject(orphanKey);

        ListAppender<ILoggingEvent> appender = attachLogger();
        try {
            StepVerifier.create(cleanup.cleanup()).verifyComplete();
        } finally {
            detachLogger(appender);
        }

        ILoggingEvent warning = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("temporary object cleanup failed"))
                .findFirst()
                .orElseThrow();
        String expectedHash = sha256Prefix(orphanKey);
        assertThat(warning.getFormattedMessage())
                .contains("failureStage=orphan_storage_delete", "exceptionType=RuntimeException",
                        "objectKeyHash=" + expectedHash)
                .doesNotContain(orphanKey, sensitiveMessage);
        assertThat(warning.getThrowableProxy()).isNull();
    }

    @Test
    void cleanupRoundFailureLogDoesNotExposeExceptionDetails() {
        String sensitiveMessage = "cleanup response body at /private/storage/path";
        when(mediaRefs.findCleanupCandidates(Duration.ofHours(1)))
                .thenReturn(Flux.error(new IllegalStateException(sensitiveMessage)));

        ListAppender<ILoggingEvent> appender = attachLogger();
        try {
            cleanup.cleanupExpired();
        } finally {
            detachLogger(appender);
        }

        ILoggingEvent warning = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("media cleanup round failed"))
                .findFirst()
                .orElseThrow();
        assertThat(warning.getFormattedMessage())
                .contains("failureStage=cleanup_round", "exceptionType=IllegalStateException")
                .doesNotContain(sensitiveMessage);
        assertThat(warning.getThrowableProxy()).isNull();
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

    private static ListAppender<ILoggingEvent> attachLogger() {
        Logger logger = (Logger) LoggerFactory.getLogger(MediaCleanup.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachLogger(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(MediaCleanup.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static String sha256Prefix(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
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
