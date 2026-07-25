package com.grassland.finance.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * finance outbox → Kafka 发布器（草场 Epic 6 Slice 6B）。复刻 identity/marketplace OutboxPublisher。
 * 轮询未发布（published_at IS NULL）行发 topic（默认 grassland.finance.events），成功后 markPublished。
 * Kafka 未配置时跳过。at-least-once；消费方须幂等。
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repo;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final boolean enabled;

    public OutboxPublisher(OutboxRepository repo,
                           @Autowired(required = false) KafkaTemplate<String, String> kafka,
                           @Value("${finance.outbox.topic:grassland.finance.events}") String topic,
                           @Value("${finance.outbox.enabled:true}") boolean enabled) {
        this.repo = repo;
        this.kafka = kafka;
        this.topic = topic;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${finance.outbox.poll-interval-ms:2000}")
    public void publishPending() {
        if (!enabled || kafka == null) {
            return;
        }
        repo.findUnpublished(50)
                .flatMap(row -> Mono.fromCallable(() -> {
                            kafka.send(topic, row.aggregateId(), buildEnvelope(row));
                            return row;
                        })
                        .doOnSuccess(r -> repo.markPublished(r.id()).subscribe())
                        .doOnError(e -> log.error("publish failed for event {}", row.eventId(), e))
                        .onErrorResume(e -> Mono.empty()))
                .subscribe();
    }

    private String buildEnvelope(OutboxRepository.OutboxRow row) {
        return "{\"eventId\":\"" + row.eventId() + "\",\"eventType\":\"" + row.eventType()
                + "\",\"aggregateType\":\"" + row.aggregateType() + "\",\"aggregateId\":\"" + row.aggregateId()
                + "\",\"payload\":" + row.payloadJson() + "}";
    }
}
