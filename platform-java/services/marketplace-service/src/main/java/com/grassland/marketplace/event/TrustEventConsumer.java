package com.grassland.marketplace.event;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "marketplace.trust-consumer.enabled", havingValue = "true")
public class TrustEventConsumer {

    private final TrustEventProcessor processor;
    private final TrustEventConsumerMetrics metrics;

    public TrustEventConsumer(TrustEventProcessor processor, TrustEventConsumerMetrics metrics) {
        this.processor = processor;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = "${marketplace.trust-consumer.topic:grassland.trust.events}",
            groupId = "${marketplace.trust-consumer.group-id:marketplace-trust-consumer}",
            autoStartup = "${marketplace.trust-consumer.auto-startup:true}",
            containerFactory = "marketplaceTrustKafkaListenerContainerFactory")
    public Mono<Void> onEvent(
            ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        return processor
                .process(record)
                .doOnNext(metrics::record)
                .then(Mono.<Void>fromRunnable(acknowledgment::acknowledge))
                .doOnError(ignored -> metrics.failed());
    }
}
