package com.grassland.identity.event;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepository repo;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final boolean enabled;

    public OutboxPublisher(OutboxRepository repo,
                           @Autowired(required = false) KafkaTemplate<String, String> kafka,
                           @Value("${identity.outbox.topic:grassland.identity.events}") String topic,
                           @Value("${identity.outbox.enabled:true}") boolean enabled) {
        this.repo = repo;
        this.kafka = kafka;
        this.topic = topic;
        this.enabled = enabled;
    }

    @PostConstruct
    public void init() {
        repo.ensureTable()
            .doOnSuccess(v -> log.info("Outbox table ready"))
            .doOnError(e -> log.error("Failed to ensure outbox table", e))
            .onErrorComplete()
            .subscribe();
    }

    @Scheduled(fixedDelayString = "${identity.outbox.poll-interval-ms:2000}")
    public void publishPending() {
        if (!enabled || kafka == null) return;
        repo.findUnpublished(50)
            .flatMap(row -> {
                String key = row.aggregateId();
                String envelope = buildEnvelope(row);
                return Mono.fromCallable(() -> {
                        kafka.send(topic, key, envelope);
                        return row;
                    })
                    .doOnSuccess(r -> repo.markPublished(r.id()).subscribe())
                    .doOnError(e -> log.error("Failed to publish event {}", row.eventId(), e))
                    .onErrorResume(e -> Mono.empty());
            })
            .subscribe();
    }

    private String buildEnvelope(OutboxRepository.OutboxRow row) {
        return "{\"eventId\":\"" + row.eventId() + "\","
            + "\"eventType\":\"" + row.eventType() + "\","
            + "\"aggregateType\":\"" + row.aggregateType() + "\","
            + "\"aggregateId\":\"" + row.aggregateId() + "\","
            + "\"payload\":" + row.payloadJson() + "}";
    }
}
