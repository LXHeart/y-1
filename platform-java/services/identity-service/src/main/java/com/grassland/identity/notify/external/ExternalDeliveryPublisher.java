package com.grassland.identity.notify.external;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(prefix = "identity.external-delivery", name = "enabled", havingValue = "true")
public class ExternalDeliveryPublisher {
    private static final Logger log = LoggerFactory.getLogger(ExternalDeliveryPublisher.class);
    private final ExternalDeliveryRepository repository;
    private final ExternalDeliveryGateway gateway;
    private final ExternalDeliveryProperties properties;
    private final AtomicBoolean polling = new AtomicBoolean();

    public ExternalDeliveryPublisher(
            ExternalDeliveryRepository repository,
            ExternalDeliveryGateway gateway,
            ExternalDeliveryProperties properties) {
        this.repository = repository;
        this.gateway = gateway;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${identity.external-delivery.poll-interval-ms:2000}")
    public void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        repository.claimBatch(properties.batchSize(), UUID.randomUUID(), properties.claimLease())
                .flatMap(this::deliver, properties.maxConcurrency()).then()
                .doOnError(error -> log.error("external notification delivery poll failed", error))
                .doFinally(signal -> polling.set(false)).subscribe();
    }

    Mono<Void> deliver(ExternalDeliveryRepository.Row row) {
        return gateway.send(row).timeout(properties.sendTimeout())
                .then(Mono.defer(() -> repository.markSent(row))).then()
                .onErrorResume(error -> {
                    boolean dead = row.attemptCount() >= properties.maxAttempts();
                    Duration delay = backoff(row.attemptCount());
                    log.warn("external notification delivery failed: channel={}, attempt={}, dead={}",
                            row.channel(), row.attemptCount(), dead);
                    return repository.markFailure(row, dead, delay, error.getClass().getSimpleName()).then();
                });
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
