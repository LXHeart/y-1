package com.grassland.identity.notification;

import com.grassland.identity.event.EventContractException;
import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 通知消费的 Kafka 可靠性配置。草场 Slice 12 Stage 2。
 *
 * <p>镜像 marketplace {@code KafkaConsumerReliabilityConfig}，但<b>不重复定义事务 bean</b>——
 * identity 已有 {@code identityReactiveTransactionManager} / {@code identityTransactionalOperator}
 * （{@code IdentityServiceApplication}），processor 直接注入后者。
 *
 * <p>关键策略（与 marketplace 一致）：
 * <ul>
 *   <li>{@link EventContractException} 标记不可重试——坏数据直接进 DLT，不阻塞分区。</li>
 *   <li>{@link FixedBackOff}(500ms, 2)：瞬时故障重试 2 次后进 DLT。</li>
 *   <li>{@code AckMode.MANUAL} + {@code asyncAcks}：事务提交后才 ack，未提交前 offset 不前移。</li>
 * </ul>
 */
@Configuration
public class NotificationKafkaReliabilityConfig {

    @Bean
    FixedBackOff notificationRetryBackOff() {
        return new FixedBackOff(500L, 2L);
    }

    @Bean
    DeadLetterPublishingRecoverer notificationDeadLetterRecoverer(KafkaTemplate<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template,
                (record, error) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        recoverer.setAppendOriginalHeaders(true);
        recoverer.setStripPreviousExceptionHeaders(false);
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        return recoverer;
    }

    @Bean
    DefaultErrorHandler notificationErrorHandler(
            DeadLetterPublishingRecoverer recoverer, FixedBackOff backOff) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(EventContractException.class);
        errorHandler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(
                    org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
                    Exception error, int deliveryAttempt) {
                // 预留：如需重试计数可在此埋点。
            }

            @Override
            public void recovered(
                    org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Exception error) {
                // 预留：恢复计数。
            }

            @Override
            public void recoveryFailed(
                    org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
                    Exception original, Exception failure) {
                // 预留：DLT 失败计数。
            }
        });
        return errorHandler;
    }

    @Bean(name = "notificationKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<Object, Object> notificationKafkaListenerContainerFactory(
            org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setAsyncAcks(true);
        factory.getContainerProperties().setDeliveryAttemptHeader(true);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}
