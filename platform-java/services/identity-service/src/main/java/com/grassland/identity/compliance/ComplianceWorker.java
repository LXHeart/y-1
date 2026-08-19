package com.grassland.identity.compliance;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ComplianceWorker {

    private static final Logger log = LoggerFactory.getLogger(ComplianceWorker.class);

    private final ComplianceRepository repository;
    private final ComplianceService service;
    private final ComplianceProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();

    public ComplianceWorker(
            ComplianceRepository repository, ComplianceService service, ComplianceProperties properties) {
        this.repository = repository;
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${identity.compliance.poll-interval-ms:5000}")
    public void runScheduled() {
        if (!properties.enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        runOnce()
                .doOnError(error -> log.error("Personal data compliance worker failed", error))
                .onErrorResume(error -> Mono.empty())
                .doFinally(signal -> running.set(false))
                .subscribe();
    }

    Mono<Void> runOnce() {
        UUID exportClaim = UUID.randomUUID();
        UUID closureClaim = UUID.randomUUID();
        return repository.expireExports()
                .thenMany(repository.claimExports(
                        properties.batchSize(), exportClaim, properties.claimLease(), properties.maxAttempts()))
                .flatMap(service::generateExport, properties.maxConcurrency())
                .thenMany(repository.claimDueClosures(
                        properties.batchSize(), closureClaim, properties.claimLease(), properties.maxAttempts()))
                .flatMap(service::eraseAccount, properties.maxConcurrency())
                .then();
    }
}
