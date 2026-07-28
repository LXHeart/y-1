package com.grassland.marketplace.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.event.EventContractException;
import com.grassland.marketplace.event.TrustEventConsumerMetrics;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

class KafkaConsumerReliabilityConfigTest {

    private final KafkaConsumerReliabilityConfig config = new KafkaConsumerReliabilityConfig();

    @Test
    void listenerFactoryUsesManualAsyncAcknowledgments() {
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer =
                mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);
        DefaultErrorHandler errorHandler = mock(DefaultErrorHandler.class);

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                config.marketplaceTrustKafkaListenerContainerFactory(
                        configurer, consumerFactory, errorHandler);

        verify(configurer).configure(factory, consumerFactory);
        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL);
        assertThat(factory.getContainerProperties().isAsyncAcks()).isTrue();
        assertThat(factory.getContainerProperties().isDeliveryAttemptHeader()).isTrue();
    }

    @Test
    void retryBackoffIsFiveHundredMillisecondsForTwoRetries() {
        FixedBackOff backOff = config.marketplaceTrustRetryBackOff();
        BackOffExecution execution = backOff.start();

        assertThat(execution.nextBackOff()).isEqualTo(500L);
        assertThat(execution.nextBackOff()).isEqualTo(500L);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void contractErrorsAreRecoveredImmediatelyWithoutRetry() {
        org.springframework.kafka.listener.ConsumerRecordRecoverer recoverer = mock(
                org.springframework.kafka.listener.ConsumerRecordRecoverer.class);
        TrustEventConsumerMetrics metrics = mock(TrustEventConsumerMetrics.class);
        DefaultErrorHandler handler = config.marketplaceTrustErrorHandler(
                recoverer, config.marketplaceTrustRetryBackOff(), metrics);
        ConsumerRecord<String, String> record = record();

        boolean handled = handler.handleOne(
                new EventContractException("bad event"), record, mock(org.apache.kafka.clients.consumer.Consumer.class),
                mock(org.springframework.kafka.listener.MessageListenerContainer.class));

        assertThat(handled).isTrue();
        verify(recoverer).accept(
                org.mockito.ArgumentMatchers.eq(record),
                org.mockito.ArgumentMatchers.argThat(error -> error instanceof EventContractException));
    }

    @Test
    void deadLetterPublishingPreservesSourceHeadersAndOriginMetadata() {
        KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        DeadLetterPublishingRecoverer recoverer = config.marketplaceTrustDeadLetterRecoverer(template);
        ConsumerRecord<String, String> record = record();
        record.headers().add("trace-id", "trace-123".getBytes(StandardCharsets.UTF_8));

        recoverer.accept(record, null, new EventContractException("bad event"));

        ArgumentCaptor<ProducerRecord<Object, Object>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("grassland.trust.events.DLT");
        assertThat(sent.getValue().partition()).isEqualTo(1);
        assertThat(new String(sent.getValue().headers().lastHeader("trace-id").value(), StandardCharsets.UTF_8))
                .isEqualTo("trace-123");
        assertThat(new String(
                        sent.getValue().headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                        StandardCharsets.UTF_8))
                .isEqualTo("grassland.trust.events");
    }

    @Test
    void deadLetterPublishingFailsWhenKafkaRejectsTheDltRecord() {
        KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("DLT unavailable")));
        DeadLetterPublishingRecoverer recoverer = config.marketplaceTrustDeadLetterRecoverer(template);

        assertThatThrownBy(() -> recoverer.accept(record(), null, new IllegalStateException("processing failed")))
                .hasRootCauseMessage("DLT unavailable");
    }

    private ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>("grassland.trust.events", 1, 23L, "d-1", "{}");
    }
}
