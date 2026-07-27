package com.grassland.intelligence.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * intelligence outbox → Kafka 发布器（复刻 marketplace 的 OutboxPublisher）。
 * {@code @Scheduled} 轮询未发布行 → 发 topic → markPublished。Kafka 未配置时 KafkaTemplate=null → 跳过。
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
                           @Value("${intelligence.outbox.topic:grassland.intelligence.events}") String topic,
                           @Value("${intelligence.outbox.enabled:true}") boolean enabled) {
        this.repo = repo;
        this.kafka = kafka;
        this.topic = topic;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${intelligence.outbox.poll-interval-ms:2000}")
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
