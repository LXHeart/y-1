package com.grassland.identity.kyb;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(prefix = "identity.kyb.document-analysis", name = "enabled", havingValue = "true")
public class KybDocumentAnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(KybDocumentAnalysisWorker.class);

    private final KybDocumentAnalysisJobRepository jobs;
    private final MerchantAttachmentRepository attachments;
    private final MerchantProfileRepository profiles;
    private final KybMediaClient media;
    private final KybDocumentVerifier verifier;
    private final KybDocumentAnalysisProperties properties;
    private final TransactionalOperator transactions;
    private final AtomicBoolean polling = new AtomicBoolean();

    public KybDocumentAnalysisWorker(
            KybDocumentAnalysisJobRepository jobs,
            MerchantAttachmentRepository attachments,
            MerchantProfileRepository profiles,
            KybMediaClient media,
            KybDocumentVerifier verifier,
            KybDocumentAnalysisProperties properties,
            TransactionalOperator transactions) {
        this.jobs = jobs;
        this.attachments = attachments;
        this.profiles = profiles;
        this.media = media;
        this.verifier = verifier;
        this.properties = properties;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${identity.kyb.document-analysis.poll-interval-ms:2000}")
    public void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        UUID token = UUID.randomUUID();
        jobs.claimBatch(properties.batchSize(), token, properties.claimLease())
                .flatMap(this::process, properties.maxConcurrency())
                .then()
                .doOnError(error -> log.error("KYB document analysis poll failed", error))
                .doFinally(signal -> polling.set(false))
                .subscribe();
    }

    Mono<Void> process(KybDocumentAnalysisJobRepository.Job job) {
        return attachments.findById(job.attachmentId())
                .flatMap(attachment -> profiles.findById(attachment.organizationId())
                        .flatMap(profile -> media.analyzeDocument(
                                        attachment.mediaReferenceId(), attachment.organizationId(),
                                        attachment.attachmentType())
                                .map(result -> verifier.verify(result, profile)))
                        .flatMap(result -> transactions.transactional(jobs.complete(job, result)))
                        .flatMap(updated -> updated ? Mono.<Void>empty()
                                : Mono.error(new IllegalStateException("KYB analysis claim was lost"))))
                .switchIfEmpty(Mono.<Void>error(new IllegalStateException("KYB analysis target is missing")))
                .onErrorResume(error -> retry(job, error));
    }

    private Mono<Void> retry(KybDocumentAnalysisJobRepository.Job job, Throwable error) {
        boolean dead = job.attemptCount() >= properties.maxAttempts();
        Duration delay = backoff(job.attemptCount());
        String code = error.getClass().getSimpleName();
        log.warn("KYB document analysis failed: attachmentId={}, attempt={}, dead={}, code={}",
                job.attachmentId(), job.attemptCount(), dead, code);
        return transactions.transactional(jobs.retry(job, delay, code, dead)).then();
    }

    private Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        long millis;
        try {
            millis = Math.multiplyExact(properties.initialBackoff().toMillis(), multiplier);
        } catch (ArithmeticException error) {
            millis = properties.maxBackoff().toMillis();
        }
        return Duration.ofMillis(Math.min(millis, properties.maxBackoff().toMillis()));
    }
}
