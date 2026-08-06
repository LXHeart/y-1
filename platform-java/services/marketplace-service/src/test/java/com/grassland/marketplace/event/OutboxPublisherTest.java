package com.grassland.marketplace.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OutboxPublisherTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MarketplaceOutboxProperties properties = new MarketplaceOutboxProperties(
            true,
            "grassland.marketplace.events",
            10,
            4,
            Duration.ofSeconds(10),
            Duration.ofMinutes(5),
            Duration.ofSeconds(1),
            Duration.ofSeconds(30));
    private final OutboxPublisher publisher = new OutboxPublisher(
            repository, kafka, properties, new OutboxPublisherMetrics(meterRegistry), new ObjectMapper());

    @Test
    void publishBatchMarksPublishedOnlyAfterKafkaAcknowledges() {
        CompletableFuture<SendResult<String, String>> sendFuture = new CompletableFuture<>();
        stubClaim(0);
        when(kafka.send(eq("grassland.marketplace.events"), eq("app-42"), any(String.class)))
                .thenReturn(sendFuture);
        when(repository.markPublished(anyString(), anyString())).thenReturn(Mono.just(true));

        // 先 complete sendFuture（Kafka ack），再同步等待 publishBatch 完成后验证 markPublished
        sendFuture.complete(new SendResult<>(null, mock(RecordMetadata.class)));
        StepVerifier.create(publisher.publishBatch()).verifyComplete();

        verify(repository).markPublished(eq("7a979ae8-e0bb-49f3-b612-554974dd0f6b"), anyString());
        assertThat(meterRegistry.counter("marketplace.outbox.published").count()).isEqualTo(1.0);
    }

    @Test
    void publishBatchReleasesClaimWithLowCardinalityErrorAndExponentialBackoff() {
        stubClaim(3);
        when(kafka.send(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        when(repository.markFailed(anyString(), anyString(), eq(OutboxPublisher.KAFKA_SEND_ERROR),
                        eq(Duration.ofSeconds(8))))
                .thenReturn(Mono.just(true));

        StepVerifier.create(publisher.publishBatch()).verifyComplete();

        verify(repository).markFailed(
                eq("7a979ae8-e0bb-49f3-b612-554974dd0f6b"),
                anyString(),
                eq(OutboxPublisher.KAFKA_SEND_ERROR),
                eq(Duration.ofSeconds(8)));
        verify(repository, never()).markPublished(any(), any());
        assertThat(meterRegistry.counter(
                        "marketplace.outbox.failed", "error_code", OutboxPublisher.KAFKA_SEND_ERROR)
                .count()).isEqualTo(1.0);
    }

    @Test
    void publishBatchCapsBackoffAtConfiguredMaximum() {
        stubClaim(30);
        when(kafka.send(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("still unavailable")));
        when(repository.markFailed(any(), any(), any(), eq(Duration.ofSeconds(30))))
                .thenReturn(Mono.just(true));

        StepVerifier.create(publisher.publishBatch()).verifyComplete();

        verify(repository).markFailed(
                any(), any(), eq(OutboxPublisher.KAFKA_SEND_ERROR), eq(Duration.ofSeconds(30)));
    }

    private void stubClaim(int attemptCount) {
        when(repository.claimBatch(anyString(), eq(10), eq(Duration.ofMinutes(5))))
                .thenAnswer(invocation -> Flux.just(row(attemptCount, invocation.getArgument(0))));
    }

    private OutboxRepository.OutboxRow row(int attemptCount, String claimToken) {
        return new OutboxRepository.OutboxRow(
                "7a979ae8-e0bb-49f3-b612-554974dd0f6b",
                "event-1",
                "EngagementSettled",
                "TaskApplication",
                "app-42",
                "{\"applicationId\":\"app-42\"}",
                attemptCount,
                claimToken,
                Instant.now());
    }
}
