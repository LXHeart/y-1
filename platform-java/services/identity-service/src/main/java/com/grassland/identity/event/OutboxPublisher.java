package com.grassland.identity.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int MAX_ERROR_CODE_LENGTH = 64;

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final OutboxProperties properties;
    private final AtomicBoolean isPublishing = new AtomicBoolean();
    private final AtomicLong pendingGauge = new AtomicLong();
    private final AtomicLong oldestPendingAgeGauge = new AtomicLong();
    private final Counter attemptsCounter;
    private final Counter successCounter;
    private final Counter failuresCounter;
    private final Counter markFailuresCounter;
    private final Counter overlapCounter;
    private final Timer publishDuration;

    @Autowired
    public OutboxPublisher(
            OutboxRepository repository,
            @Autowired(required = false) KafkaTemplate<String, String> kafka,
            MeterRegistry meterRegistry,
            OutboxProperties properties) {
        this(repository, kafka, new ObjectMapper().findAndRegisterModules(), meterRegistry, properties);
    }

    OutboxPublisher(
            OutboxRepository repository,
            KafkaTemplate<String, String> kafka,
            ObjectMapper mapper,
            MeterRegistry meterRegistry,
            OutboxProperties properties) {
        this.repository = repository;
        this.kafka = kafka;
        this.mapper = mapper.copy().findAndRegisterModules();
        this.properties = properties;
        attemptsCounter = Counter.builder("grassland.outbox.publish.attempts").register(meterRegistry);
        successCounter = Counter.builder("grassland.outbox.publish.success").register(meterRegistry);
        failuresCounter = Counter.builder("grassland.outbox.publish.failures").register(meterRegistry);
        markFailuresCounter = Counter.builder("grassland.outbox.mark.failures").register(meterRegistry);
        overlapCounter = Counter.builder("grassland.outbox.poll.overlap").register(meterRegistry);
        publishDuration = Timer.builder("grassland.outbox.publish.duration").register(meterRegistry);
        Gauge.builder("grassland.outbox.pending", pendingGauge, AtomicLong::get).register(meterRegistry);
        Gauge.builder("grassland.outbox.oldest.pending.age", oldestPendingAgeGauge, AtomicLong::get)
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${identity.outbox.poll-interval-ms:2000}")
    public void publishPending() {
        if (!properties.enabled() || kafka == null) {
            return;
        }
        if (!isPublishing.compareAndSet(false, true)) {
            overlapCounter.increment();
            return;
        }

        UUID claimToken = UUID.randomUUID();
        repository.claimBatch(properties.batchSize(), claimToken, properties.claimLease())
                .flatMap(this::publishClaimed, properties.maxConcurrency())
                .then()
                .doOnError(error -> log.error("Failed to process identity outbox batch", error))
                .onErrorResume(error -> Mono.empty())
                .then(refreshBacklogMetrics())
                .doFinally(signal -> isPublishing.set(false))
                .subscribe();
    }

    private Mono<Void> publishClaimed(OutboxRepository.OutboxRow row) {
        long startedAt = System.nanoTime();
        attemptsCounter.increment();
        return Mono.fromCallable(() -> buildEnvelope(row))
                .flatMap(message -> Mono.fromCallable(
                                () -> kafka.send(properties.topic(), row.aggregateId(), message))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(Mono::fromFuture)
                        .timeout(properties.ackTimeout()))
                .then(markPublished(row))
                .onErrorResume(error -> markFailure(row, error))
                .doFinally(signal -> publishDuration.record(
                        System.nanoTime() - startedAt, TimeUnit.NANOSECONDS));
    }

    private Mono<Void> markPublished(OutboxRepository.OutboxRow row) {
        return Mono.defer(() -> repository.markPublished(row.id(), row.claimToken()))
                .flatMap(updated -> {
                    if (updated) {
                        successCounter.increment();
                    } else {
                        markFailuresCounter.increment();
                    }
                    return Mono.<Void>empty();
                })
                .onErrorResume(error -> {
                    markFailuresCounter.increment();
                    log.warn("Failed to mark identity outbox event published: eventId={}", row.eventId());
                    return Mono.<Void>empty();
                });
    }

    private Mono<Void> markFailure(OutboxRepository.OutboxRow row, Throwable error) {
        failuresCounter.increment();
        String errorCode = errorCode(error);
        Duration retryDelay = Duration.ofMillis(backoffMillis(row.attemptCount()));
        return Mono.defer(() -> repository.markFailure(
                        row.id(), row.claimToken(), retryDelay, errorCode))
                .flatMap(updated -> {
                    if (!updated) {
                        markFailuresCounter.increment();
                    }
                    return Mono.<Void>empty();
                })
                .onErrorResume(markError -> {
                    markFailuresCounter.increment();
                    log.warn("Failed to mark identity outbox retry: eventId={}, errorCode={}",
                            row.eventId(), errorCode);
                    return Mono.<Void>empty();
                });
    }

    private Mono<Void> refreshBacklogMetrics() {
        return Mono.zip(
                        repository.pendingCount().defaultIfEmpty(0L),
                        repository.oldestPendingAgeSeconds().defaultIfEmpty(0L))
                .doOnNext(backlog -> {
                    pendingGauge.set(backlog.getT1());
                    oldestPendingAgeGauge.set(backlog.getT2());
                })
                .doOnError(error -> log.warn("Failed to refresh identity outbox metrics"))
                .onErrorResume(error -> Mono.empty())
                .then();
    }

    private String buildEnvelope(OutboxRepository.OutboxRow row) throws Exception {
        JsonNode payload = mapper.readTree(row.payloadJson());
        return mapper.writeValueAsString(new KafkaEventEnvelope(
                row.eventId(), row.eventType(), row.aggregateType(), row.aggregateId(), payload));
    }

    private long backoffMillis(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 62));
        long multiplier = 1L << exponent;
        long initial = properties.initialBackoffMs();
        long maximum = properties.maxBackoffMs();
        if (multiplier > maximum / initial) {
            return maximum;
        }
        return Math.min(initial * multiplier, maximum);
    }

    private static String errorCode(Throwable error) {
        String code = Exceptions.unwrap(error).getClass().getSimpleName();
        return code.length() <= MAX_ERROR_CODE_LENGTH
                ? code
                : code.substring(0, MAX_ERROR_CODE_LENGTH);
    }

    private record KafkaEventEnvelope(
            String eventId,
            String eventType,
            String aggregateType,
            String aggregateId,
            JsonNode payload) {}
}
