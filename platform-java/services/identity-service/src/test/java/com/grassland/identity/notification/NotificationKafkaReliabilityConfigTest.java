package com.grassland.identity.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

@SuppressWarnings("unchecked")
class NotificationKafkaReliabilityConfigTest {

    @Test
    void listenerFactoryUsesManualAcknowledgmentsAndObservation() {
        NotificationKafkaReliabilityConfig config = new NotificationKafkaReliabilityConfig();
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer =
                mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);
        DefaultErrorHandler errorHandler = mock(DefaultErrorHandler.class);

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                config.notificationKafkaListenerContainerFactory(
                        configurer, consumerFactory, errorHandler);

        verify(configurer).configure(factory, consumerFactory);
        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL);
        assertThat(factory.getContainerProperties().isAsyncAcks()).isTrue();
        assertThat(factory.getContainerProperties().isDeliveryAttemptHeader()).isTrue();
        assertThat(factory.getContainerProperties().isObservationEnabled()).isTrue();
    }
}
