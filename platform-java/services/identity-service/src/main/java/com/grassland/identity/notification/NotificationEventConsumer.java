package com.grassland.identity.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 消费 identity 自己的 outbox topic（{@code grassland.identity.events}）派生站内通知。
 *
 * <p>默认<b>不启用</b>（本地/测试无 Kafka 时跳过，避免连接噪声）；compose 置
 * {@code IDENTITY_NOTIFICATION_CONSUMER_ENABLED=true}。镜像 marketplace {@code TrustEventConsumer}。
 *
 * <p>{@code @KafkaListener} 的 ack 为手动（{@code AckMode.MANUAL} + {@code asyncAcks}）——
 * 只在 {@code processor.process} 成功（事务提交）后才 {@code acknowledge}，事务未提交前 offset 不前移。
 */
@Component
@ConditionalOnProperty(name = "identity.notification-consumer.enabled", havingValue = "true")
public class NotificationEventConsumer {

    private final NotificationEventProcessor processor;
    private final NotificationConsumerMetrics metrics;

    public NotificationEventConsumer(NotificationEventProcessor processor, NotificationConsumerMetrics metrics) {
        this.processor = processor;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = "${identity.notification-consumer.topic:grassland.identity.events}",
            groupId = "${identity.notification-consumer.group-id:identity-notification-consumer}",
            autoStartup = "${identity.notification-consumer.auto-startup:true}",
            containerFactory = "notificationKafkaListenerContainerFactory")
    public Mono<Void> onEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        return processor
                .process(record)
                .doOnNext(metrics::record)
                .then(Mono.<Void>fromRunnable(acknowledgment::acknowledge))
                .doOnError(ignored -> metrics.failed());
    }
}
