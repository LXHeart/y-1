package com.grassland.identity.kyb;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/** Reconciles identity's durable retention intent with intelligence's finite leases. */
@Component
public class KybMediaRetentionReconciler {

    private static final Logger log = LoggerFactory.getLogger(KybMediaRetentionReconciler.class);
    private static final int MAX_ERROR_CODE_LENGTH = 64;

    private final KybMediaRetentionCommandRepository repository;
    private final KybMediaClient mediaClient;
    private final KybMediaRetentionProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();

    public KybMediaRetentionReconciler(
            KybMediaRetentionCommandRepository repository,
            KybMediaClient mediaClient,
            KybMediaRetentionProperties properties) {
        this.repository = repository;
        this.mediaClient = mediaClient;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${identity.kyb.retention.poll-interval-ms:2000}")
    public void reconcilePending() {
        if (!properties.enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        reconcileOnce()
                .doOnError(error -> log.error("KYB media retention reconciliation failed", error))
                .onErrorResume(error -> Mono.empty())
                .doFinally(signal -> running.set(false))
                .subscribe();
    }

    Mono<Void> reconcileOnce() {
        UUID claimToken = UUID.randomUUID();
        return repository.expireSealed()
                .thenMany(repository.claimBatch(
                        properties.batchSize(), claimToken, properties.claimLease(), properties.renewAhead()))
                .flatMap(this::synchronize, properties.maxConcurrency())
                .then();
    }

    private Mono<Void> synchronize(KybMediaRetentionCommand command) {
        return remote(command)
                .flatMap(deadline -> repository.markSynced(
                        command.mediaReferenceId(), command.referenceId(), command.claimToken(), deadline))
                .then()
                .onErrorResume(error -> repository.markFailure(
                                command.mediaReferenceId(), command.referenceId(), command.claimToken(),
                                backoff(command.attemptCount()), errorCode(error))
                        .then());
    }

    private Mono<java.time.Instant> remote(KybMediaRetentionCommand command) {
        return switch (command.desiredState()) {
            case "live" -> mediaClient.acquireLease(
                            command.mediaReferenceId(), command.organizationId(), command.referenceId(),
                            command.referenceType(), properties.liveLeaseSeconds())
                    .map(KybMediaRetentionReceipt::leaseUntil);
            case "sealed" -> mediaClient.seal(
                            command.mediaReferenceId(), command.organizationId(), command.referenceId(),
                            command.referenceType(), command.retainUntil())
                    .map(KybMediaRetentionReceipt::retainedUntil);
            case "released" -> mediaClient.release(
                            command.mediaReferenceId(), command.organizationId(), command.referenceId())
                    .thenReturn(java.time.Instant.EPOCH);
            default -> Mono.error(new IllegalStateException("Unsupported retention state"));
        };
    }

    private Duration backoff(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 62));
        long multiplier = 1L << exponent;
        long initial = properties.initialBackoffMs();
        long maximum = properties.maxBackoffMs();
        return Duration.ofMillis(multiplier > maximum / initial
                ? maximum : Math.min(initial * multiplier, maximum));
    }

    private static String errorCode(Throwable error) {
        String code = Exceptions.unwrap(error).getClass().getSimpleName();
        return code.length() <= MAX_ERROR_CODE_LENGTH ? code : code.substring(0, MAX_ERROR_CODE_LENGTH);
    }
}
