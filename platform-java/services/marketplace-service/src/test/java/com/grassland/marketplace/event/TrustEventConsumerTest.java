package com.grassland.marketplace.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TrustEventConsumerTest {

    private final TrustEventProcessor processor = mock(TrustEventProcessor.class);
    private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TrustEventConsumer consumer = new TrustEventConsumer(
            processor, new TrustEventConsumerMetrics(meterRegistry));

    @Test
    void acknowledgesOnlyAfterReactiveProcessingCompletes() {
        ConsumerRecord<String, String> record = record();
        reactor.core.publisher.Sinks.One<TrustEventProcessingResult> result = reactor.core.publisher.Sinks.one();
        when(processor.process(record)).thenReturn(result.asMono());

        consumer.onEvent(record, acknowledgment).subscribe();

        verify(acknowledgment, never()).acknowledge();

        result.tryEmitValue(TrustEventProcessingResult.PROCESSED);

        verify(acknowledgment).acknowledge();
        assertThat(meterRegistry.counter(
                        "marketplace.trust.consumer.records", "outcome", "processed")
                .count()).isEqualTo(1.0);
    }

    @Test
    void processingFailurePropagatesWithoutAcknowledging() {
        ConsumerRecord<String, String> record = record();
        when(processor.process(record)).thenReturn(Mono.error(new IllegalStateException("database unavailable")));

        StepVerifier.create(consumer.onEvent(record, acknowledgment))
                .expectErrorMessage("database unavailable")
                .verify();

        verify(acknowledgment, never()).acknowledge();
        assertThat(meterRegistry.counter(
                        "marketplace.trust.consumer.records", "outcome", "failed")
                .count()).isEqualTo(1.0);
    }

    @Test
    void duplicateAndIgnoredResultsHaveDistinctMetrics() {
        ConsumerRecord<String, String> duplicate = record();
        ConsumerRecord<String, String> ignored = new ConsumerRecord<>(
                "grassland.trust.events", 0, 18L, "d-2", "{}");
        when(processor.process(duplicate)).thenReturn(Mono.just(TrustEventProcessingResult.DUPLICATE));
        when(processor.process(ignored)).thenReturn(Mono.just(TrustEventProcessingResult.IGNORED));

        StepVerifier.create(consumer.onEvent(duplicate, acknowledgment)).verifyComplete();
        StepVerifier.create(consumer.onEvent(ignored, acknowledgment)).verifyComplete();

        assertThat(meterRegistry.counter(
                        "marketplace.trust.consumer.records", "outcome", "duplicate")
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                        "marketplace.trust.consumer.records", "outcome", "ignored")
                .count()).isEqualTo(1.0);
    }

    private ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>("grassland.trust.events", 0, 17L, "d-1", "{}");
    }
}
