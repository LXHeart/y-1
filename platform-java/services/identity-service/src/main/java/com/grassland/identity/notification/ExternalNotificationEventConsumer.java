package com.grassland.identity.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 消费下游服务（marketplace / trust / finance）的 outbox topic 派生站内通知。草场 Slice 12 Stage 3。
 *
 * <p>三个 topic 共用同一个 {@link NotificationEventProcessor}——处理器与 topic 无关，
 * 只认 5 字段 wire envelope（eventId/eventType/aggregateType/aggregateId/payload），未知字段忽略。
 * 每个 topic 用<b>独立 group-id</b>，inbox 幂等键 {@code (consumer_name, event_id)} 因此互不干扰。
 *
 * <p>默认<b>不启用</b>（本地/测试无 Kafka 时跳过）；compose 置
 * {@code IDENTITY_NOTIFICATION_EXTERNAL_ENABLED=true}。ack 手动，事务提交后才前移 offset。
 *
 * <p>收件人解析<b>只用 payload 里已有的 accountId</b>，identity 不反查下游领域表——
 * 所需字段（taskOwnerId / openedByAccountId / payeeAccountId）由发射端补齐。
 */
@Component
@ConditionalOnProperty(name = "identity.notification-consumer.external.enabled", havingValue = "true")
public class ExternalNotificationEventConsumer {

    private final NotificationEventProcessor processor;
    private final NotificationConsumerMetrics metrics;

    public ExternalNotificationEventConsumer(
            NotificationEventProcessor processor, NotificationConsumerMetrics metrics) {
        this.processor = processor;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = "${identity.notification-consumer.external.marketplace-topic:grassland.marketplace.events}",
            groupId = "${identity.notification-consumer.external.marketplace-group-id:"
                    + "identity-notification-marketplace-consumer}",
            autoStartup = "${identity.notification-consumer.external.auto-startup:true}",
            containerFactory = "notificationKafkaListenerContainerFactory")
    public Mono<Void> onMarketplaceEvent(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment,
            @org.springframework.messaging.handler.annotation.Header(
                    name = org.springframework.kafka.support.KafkaHeaders.GROUP_ID,
                    required = false) String groupId) {
        return handle(record, acknowledgment, groupId, "identity-notification-marketplace-consumer");
    }

    @KafkaListener(
            topics = "${identity.notification-consumer.external.trust-topic:grassland.trust.events}",
            groupId = "${identity.notification-consumer.external.trust-group-id:"
                    + "identity-notification-trust-consumer}",
            autoStartup = "${identity.notification-consumer.external.auto-startup:true}",
            containerFactory = "notificationKafkaListenerContainerFactory")
    public Mono<Void> onTrustEvent(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment,
            @org.springframework.messaging.handler.annotation.Header(
                    name = org.springframework.kafka.support.KafkaHeaders.GROUP_ID,
                    required = false) String groupId) {
        return handle(record, acknowledgment, groupId, "identity-notification-trust-consumer");
    }

    @KafkaListener(
            topics = "${identity.notification-consumer.external.finance-topic:grassland.finance.events}",
            groupId = "${identity.notification-consumer.external.finance-group-id:"
                    + "identity-notification-finance-consumer}",
            autoStartup = "${identity.notification-consumer.external.auto-startup:true}",
            containerFactory = "notificationKafkaListenerContainerFactory")
    public Mono<Void> onFinanceEvent(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment,
            @org.springframework.messaging.handler.annotation.Header(
                    name = org.springframework.kafka.support.KafkaHeaders.GROUP_ID,
                    required = false) String groupId) {
        return handle(record, acknowledgment, groupId, "identity-notification-finance-consumer");
    }

    /** consumerName 取实际 group-id（配置可改），header 缺失时回落到该 topic 的默认名。 */
    private Mono<Void> handle(
            ConsumerRecord<String, String> record,
            Acknowledgment acknowledgment,
            String groupId,
            String fallbackConsumerName) {
        String consumerName = groupId == null || groupId.isBlank() ? fallbackConsumerName : groupId;
        return processor
                .process(record, consumerName)
                .doOnNext(metrics::record)
                .then(Mono.<Void>fromRunnable(acknowledgment::acknowledge))
                .doOnError(ignored -> metrics.failed());
    }
}
