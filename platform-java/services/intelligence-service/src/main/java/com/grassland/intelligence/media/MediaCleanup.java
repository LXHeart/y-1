package com.grassland.intelligence.media;

import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.intelligence.event.OutboxRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * media_reference TTL / pending 孤儿清理（草场 Slice 8 第二步）。
 *
 * <p>先 claim deleting，再删最终/临时两把 key，成功后写 deleted_at 审计；任何对象删除失败都保留 deleting，
 * stale deleting 会在下轮重新 claim 并重试。文章生成图同时有 {@code S3GeneratedImageStore.cleanupExpired}
 * 按对象 lastModified 巡检：两者故意幂等重叠——store 巡检清理无元数据的历史/孤儿对象，本类清理有
 * media_reference 的资产并写删除审计。
 */
@Component
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class MediaCleanup {

    private static final Logger log = LoggerFactory.getLogger(MediaCleanup.class);

    private final MediaReferenceRepository mediaRefs;
    private final ObjectStorageAdapter storage;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final Duration pendingGrace;
    private final AtomicBoolean running = new AtomicBoolean();

    public MediaCleanup(
            MediaReferenceRepository mediaRefs,
            ObjectStorageAdapter storage,
            OutboxRepository outbox,
            TransactionalOperator transactions,
            @Value("${media.pending-grace-seconds:3600}") long pendingGraceSeconds,
            @Value("${media.upload-url-ttl-seconds:900}") long uploadUrlTtlSeconds) {
        this.mediaRefs = mediaRefs;
        this.storage = storage;
        this.outbox = outbox;
        this.transactions = transactions;
        this.pendingGrace = Duration.ofSeconds(Math.max(
                Math.max(pendingGraceSeconds, 1L), Math.max(uploadUrlTtlSeconds, 1L) + 60L));
    }

    @Scheduled(fixedDelayString = "${media.cleanup-interval-ms:300000}")
    public void cleanupExpired() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        cleanup().doFinally(signal -> running.set(false)).subscribe(
                ignored -> {},
                error -> log.warn("media cleanup round failed: failureStage=cleanup_round, "
                        + "exceptionType={}, errorCategory=cleanup_round_failed", exceptionType(error)));
    }

    Mono<Void> cleanup() {
        return mediaRefs.findCleanupCandidates(pendingGrace)
                .concatMap(this::claimDeleteAndAudit)
                .then(cleanOrphanedTemporaryObjects());
    }

    private Mono<Void> claimDeleteAndAudit(MediaReference candidate) {
        return mediaRefs.claimCleanup(candidate.id())
                .flatMap(this::deleteAndAudit)
                .onErrorResume(error -> {
                    log.warn("media cleanup failed: mediaId={}, failureStage=candidate_cleanup, "
                            + "exceptionType={}, errorCategory=cleanup_failed",
                            candidate.id(), exceptionType(error));
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> deleteAndAudit(MediaReference ref) {
        // 失败（释放/对象删除/complete 任一）向上抛，由 claimDeleteAndAudit 的 onErrorResume 捕获：
        // 行留 deleting，下轮 cleanup 重试整个释放+删除（quota_released 标志保证释放幂等）。
        return mediaRefs.releaseQuota(ref.id())
                .then(deleteObject(ref.objectKey()))
                .then(deleteObjectIfPresent(ref.uploadKey()))
                .then(Mono.defer(() -> transactions.transactional(mediaRefs.completeDelete(ref.id())
                        .flatMap(completed -> completed
                                ? outbox.append(MediaLifecycleEvents.deleted(ref, "expired_or_abandoned"))
                                        .thenReturn(true)
                                : Mono.just(false)))))
                .flatMap(completed -> completed
                        ? Mono.<Void>empty()
                        : Mono.error(new IllegalStateException("media delete claim was lost")));
    }

    private Mono<Void> cleanOrphanedTemporaryObjects() {
        Instant cutoff = Instant.now().minus(pendingGrace);
        return Mono.fromCallable(() -> storage.listObjects("media-pending/"))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(reactor.core.publisher.Flux::fromIterable)
                .filter(object -> object.lastModified() != null && object.lastModified().isBefore(cutoff))
                .concatMap(object -> deleteObject(object.key())
                        .onErrorResume(error -> {
                            log.warn("media temporary object cleanup failed: "
                                    + "failureStage=orphan_storage_delete, exceptionType={}, "
                                    + "objectKeyHash={}, errorCategory=storage_delete_failed",
                                    exceptionType(error), objectKeyHash(object.key()));
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> deleteObject(String key) {
        return Mono.fromRunnable(() -> storage.deleteObject(key))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private Mono<Void> deleteObjectIfPresent(String key) {
        return key == null ? Mono.empty() : deleteObject(key);
    }

    private static String exceptionType(Throwable error) {
        String simpleName = error == null ? null : error.getClass().getSimpleName();
        return simpleName == null || simpleName.isBlank() ? "Unknown" : simpleName;
    }

    private static String objectKeyHash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
