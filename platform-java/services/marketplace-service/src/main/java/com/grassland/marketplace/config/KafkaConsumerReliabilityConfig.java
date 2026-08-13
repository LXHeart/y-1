package com.grassland.marketplace.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.event.EventContractException;
import com.grassland.marketplace.event.TrustEventConsumerMetrics;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerReliabilityConfig {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper marketplaceEventObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean(TransactionalOperator.class)
    TransactionalOperator marketplaceTransactionalOperator(
            ReactiveTransactionManager transactionManager) {
        return TransactionalOperator.create(transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean(ReactiveTransactionManager.class)
    ReactiveTransactionManager marketplaceReactiveTransactionManager(
            ConnectionFactory connectionFactory) {
        return new R2dbcTransactionManager(connectionFactory);
    }

    @Bean
    FixedBackOff marketplaceTrustRetryBackOff() {
        return new FixedBackOff(500L, 2L);
    }

    @Bean
    DeadLetterPublishingRecoverer marketplaceTrustDeadLetterRecoverer(
            KafkaTemplate<Object, Object> template) {
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
    DefaultErrorHandler marketplaceTrustErrorHandler(
            ConsumerRecordRecoverer recoverer,
            FixedBackOff backOff,
            TrustEventConsumerMetrics metrics) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(EventContractException.class);
        errorHandler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception error, int deliveryAttempt) {
                if (deliveryAttempt > 1) {
                    metrics.retry();
                }
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> record, Exception error) {
                metrics.recovered();
            }

            @Override
            public void recoveryFailed(
                    ConsumerRecord<?, ?> record, Exception original, Exception failure) {
                metrics.recoveryFailed();
            }
        });
        return errorHandler;
    }

    @Bean(name = "marketplaceTrustKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<Object, Object>
            marketplaceTrustKafkaListenerContainerFactory(
                    ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
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
