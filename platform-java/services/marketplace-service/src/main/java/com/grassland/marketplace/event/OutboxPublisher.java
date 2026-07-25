package com.grassland.marketplace.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * marketplace outbox → Kafka 发布器（草场 Epic 6 Slice 6B）。复刻 identity 的 OutboxPublisher。
 *
 * <p>{@code @Scheduled} 轮询未发布（published_at IS NULL）的 outbox 行，发到 topic（默认 grassland.marketplace.events），
 * 发送成功后 markPublished（published_at=now()）。失败仅记日志、下次重试（at-least-once；消费方须幂等）。
 * Kafka 未配置时 KafkaTemplate 为 null → 跳过（本地/测试可禁）。
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
                           @Value("${marketplace.outbox.topic:grassland.marketplace.events}") String topic,
                           @Value("${marketplace.outbox.enabled:true}") boolean enabled) {
        this.repo = repo;
        this.kafka = kafka;
        this.topic = topic;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${marketplace.outbox.poll-interval-ms:2000}")
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
