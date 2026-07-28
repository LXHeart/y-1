package com.grassland.marketplace.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class OutboxPublisher {

    static final String KAFKA_SEND_ERROR = "KAFKA_SEND_ERROR";
    static final String OUTBOX_ACK_ERROR = "OUTBOX_ACK_ERROR";
    static final String SERIALIZATION_ERROR = "SERIALIZATION_ERROR";

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final MarketplaceOutboxProperties properties;
    private final OutboxPublisherMetrics metrics;
    private final ObjectMapper mapper;
    private final AtomicBoolean isPublishing = new AtomicBoolean();

    public OutboxPublisher(
            OutboxRepository repository,
            KafkaTemplate<String, String> kafka,
            MarketplaceOutboxProperties properties,
            OutboxPublisherMetrics metrics,
            ObjectMapper mapper) {
        this.repository = repository;
        this.kafka = kafka;
        this.properties = properties;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${marketplace.outbox.poll-interval-ms:2000}")
    public void publishPending() {
        if (!properties.enabled() || !isPublishing.compareAndSet(false, true)) {
            return;
        }
        publishBatch()
                .doFinally(ignored -> isPublishing.set(false))
                .subscribe(
                        ignored -> {},
                        error -> log.error("marketplace outbox batch failed", error));
    }

    Mono<Void> publishBatch() {
        if (!properties.enabled()) {
            return Mono.empty();
        }
        String claimToken = UUID.randomUUID().toString();
        return repository
                .claimBatch(claimToken, properties.batchSize(), properties.claimDuration())
                .doOnNext(ignored -> metrics.claimed())
                .flatMap(this::publishOne, properties.maxConcurrency())
                .then();
    }

    private Mono<Void> publishOne(OutboxRepository.OutboxRow row) {
        long startedAt = System.nanoTime();
        return Mono.fromCallable(() -> kafka.send(
                        properties.topic(), row.aggregateId(), buildEnvelope(row)))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(Mono::fromFuture)
                .timeout(properties.ackTimeout())
                .flatMap(ignored -> repository
                        .markPublished(row.id(), row.claimToken())
                        .onErrorMap(OutboxAckException::new))
                .doOnNext(updated -> {
                    if (updated) {
                        metrics.published(elapsedSince(startedAt));
                    } else {
                        metrics.staleClaim();
                    }
                })
                .then()
                .onErrorResume(error -> markFailed(row, error, startedAt));
    }

    private Mono<Void> markFailed(OutboxRepository.OutboxRow row, Throwable error, long startedAt) {
        String errorCode = errorCode(error);
        Duration retryBackoff = retryBackoff(row.attemptCount());
        log.warn(
                "marketplace outbox publish failed: eventId={}, errorCode={}, attempt={}, retryIn={}",
                row.eventId(),
                errorCode,
                row.attemptCount() + 1,
                retryBackoff,
                error);
        return repository
                .markFailed(row.id(), row.claimToken(), errorCode, retryBackoff)
                .doOnNext(updated -> {
                    if (updated) {
                        metrics.failed(errorCode, elapsedSince(startedAt));
                    } else {
                        metrics.staleClaim();
                    }
                })
                .then();
    }

    private Duration retryBackoff(int completedAttempts) {
        Duration backoff = properties.initialBackoff();
        for (int attempt = 0;
                attempt < completedAttempts && backoff.compareTo(properties.maxBackoff()) < 0;
                attempt++) {
            Duration doubled = backoff.multipliedBy(2);
            backoff = doubled.compareTo(properties.maxBackoff()) > 0
                    ? properties.maxBackoff()
                    : doubled;
        }
        return backoff;
    }

    private String buildEnvelope(OutboxRepository.OutboxRow row) {
        try {
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("eventId", row.eventId());
            envelope.put("eventType", row.eventType());
            envelope.put("aggregateType", row.aggregateType());
            envelope.put("aggregateId", row.aggregateId());
            JsonNode payload = mapper.readTree(row.payloadJson());
            envelope.set("payload", payload);
            return mapper.writeValueAsString(envelope);
        } catch (Exception error) {
            throw new OutboxSerializationException(error);
        }
    }

    private static String errorCode(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof OutboxSerializationException) {
                return SERIALIZATION_ERROR;
            }
            if (current instanceof OutboxAckException) {
                return OUTBOX_ACK_ERROR;
            }
            current = current.getCause();
        }
        return KAFKA_SEND_ERROR;
    }

    private static Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    private static final class OutboxSerializationException extends RuntimeException {
        private OutboxSerializationException(Throwable cause) {
            super(cause);
        }
    }

    private static final class OutboxAckException extends RuntimeException {
        private OutboxAckException(Throwable cause) {
            super(cause);
        }
    }
}
