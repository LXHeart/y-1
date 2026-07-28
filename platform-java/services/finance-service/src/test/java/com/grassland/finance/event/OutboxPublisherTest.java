package com.grassland.finance.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final String TOPIC = "grassland.finance.events";

    @Mock
    private OutboxRepository repository;

    @Mock
    private KafkaTemplate<String, String> kafka;

    private SimpleMeterRegistry meterRegistry;
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        OutboxProperties properties = new OutboxProperties(
                TOPIC, true, 2_000, 10, 1, 5_000, 60_000, 100, 250);
        publisher = new OutboxPublisher(
                repository,
                kafka,
                new ObjectMapper().findAndRegisterModules(),
                meterRegistry,
                properties);
        when(repository.pendingCount()).thenReturn(Mono.just(1L));
        when(repository.oldestPendingAgeSeconds()).thenReturn(Mono.just(12L));
    }

    @Test
    void publishPending_waitsForAck_preservesFiveFieldEnvelope_andGuardsOverlap() throws Exception {
        OutboxRepository.OutboxRow row = row("1", UUID.randomUUID(), 1);
        CompletableFuture<SendResult<String, String>> ack = new CompletableFuture<>();
        when(repository.claimBatch(eq(10), any(UUID.class), eq(Duration.ofSeconds(60))))
                .thenReturn(Flux.just(row), Flux.empty());
        when(kafka.send(eq(TOPIC), eq(row.aggregateId()), anyString())).thenReturn(ack);
        when(repository.markPublished(row.id(), row.claimToken())).thenReturn(Mono.just(true));

        publisher.publishPending();
        publisher.publishPending();

        verify(repository, timeout(1_000).times(1))
                .claimBatch(eq(10), any(UUID.class), eq(Duration.ofSeconds(60)));
        verify(repository, never()).markPublished(anyString(), any(UUID.class));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(kafka, timeout(1_000)).send(eq(TOPIC), eq(row.aggregateId()), message.capture());
        var json = new ObjectMapper().readTree(message.getValue());
        assertThat(json.size()).isEqualTo(5);
        assertThat(json.path("eventId").asText()).isEqualTo(row.eventId());
        assertThat(json.path("eventType").asText()).isEqualTo(row.eventType());
        assertThat(json.path("aggregateType").asText()).isEqualTo(row.aggregateType());
        assertThat(json.path("aggregateId").asText()).isEqualTo(row.aggregateId());
        assertThat(json.path("payload").path("text").asText()).isEqualTo("quote \" and 中文");
        assertThat(json.has("aggregateVersion")).isFalse();
        assertThat(json.has("occurredAt")).isFalse();
        assertThat(json.has("correlationId")).isFalse();

        ack.complete(null);

        verify(repository, timeout(1_000)).markPublished(row.id(), row.claimToken());
        await(() -> meterRegistry.get("grassland.outbox.publish.success").counter().count() == 1.0);
        assertThat(meterRegistry.get("grassland.outbox.publish.attempts").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("grassland.outbox.poll.overlap").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("grassland.outbox.publish.duration").timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("grassland.outbox.pending").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("grassland.outbox.oldest.pending.age").gauge().value()).isEqualTo(12.0);

        publisher.publishPending();
        verify(repository, timeout(1_000).times(2))
                .claimBatch(eq(10), any(UUID.class), eq(Duration.ofSeconds(60)));
    }

    @Test
    void publishPending_usesConfiguredMaxConcurrency() {
        OutboxRepository.OutboxRow first = row("1", UUID.randomUUID(), 1);
        OutboxRepository.OutboxRow second = row("2", UUID.randomUUID(), 1);
        CompletableFuture<SendResult<String, String>> firstAck = new CompletableFuture<>();
        CompletableFuture<SendResult<String, String>> secondAck = new CompletableFuture<>();
        when(repository.claimBatch(eq(10), any(UUID.class), eq(Duration.ofSeconds(60))))
                .thenReturn(Flux.just(first, second));
        when(kafka.send(eq(TOPIC), eq(first.aggregateId()), anyString())).thenReturn(firstAck);
        when(kafka.send(eq(TOPIC), eq(second.aggregateId()), anyString())).thenReturn(secondAck);
        when(repository.markPublished(first.id(), first.claimToken())).thenReturn(Mono.just(true));

        publisher.publishPending();

        verify(kafka, timeout(1_000)).send(eq(TOPIC), eq(first.aggregateId()), anyString());
        verify(kafka, never()).send(eq(TOPIC), eq(second.aggregateId()), anyString());
        firstAck.complete(null);
        verify(kafka, timeout(1_000)).send(eq(TOPIC), eq(second.aggregateId()), anyString());
    }

    @Test
    void publishPending_schedulesBoundedExponentialRetry_withErrorCodeOnly() throws Exception {
        OutboxRepository.OutboxRow row = row("1", UUID.randomUUID(), 2);
        CompletableFuture<SendResult<String, String>> nack = new CompletableFuture<>();
        nack.completeExceptionally(new IllegalStateException("broker unavailable: secret detail"));
        when(repository.claimBatch(eq(10), any(UUID.class), eq(Duration.ofSeconds(60))))
                .thenReturn(Flux.just(row));
        when(kafka.send(eq(TOPIC), eq(row.aggregateId()), anyString())).thenReturn(nack);
        when(repository.markFailure(
                row.id(), row.claimToken(), Duration.ofMillis(200), "IllegalStateException"))
                .thenReturn(Mono.just(true));

        publisher.publishPending();

        verify(repository, timeout(1_000)).markFailure(
                row.id(), row.claimToken(), Duration.ofMillis(200), "IllegalStateException");
        verify(repository, never()).markPublished(anyString(), any(UUID.class));
        await(() -> meterRegistry.get("grassland.outbox.publish.failures").counter().count() == 1.0);
        assertThat(meterRegistry.get("grassland.outbox.publish.duration").timer().count()).isEqualTo(1L);
    }

    @Test
    void publishPending_capsRetryDelay_withoutDeadLettering_andCountsMarkMiss() throws Exception {
        OutboxRepository.OutboxRow row = row("1", UUID.randomUUID(), 40);
        CompletableFuture<SendResult<String, String>> nack = new CompletableFuture<>();
        nack.completeExceptionally(new IllegalArgumentException("bad payload"));
        when(repository.claimBatch(eq(10), any(UUID.class), eq(Duration.ofSeconds(60))))
                .thenReturn(Flux.just(row));
        when(kafka.send(eq(TOPIC), eq(row.aggregateId()), anyString())).thenReturn(nack);
        when(repository.markFailure(
                row.id(), row.claimToken(), Duration.ofMillis(250), "IllegalArgumentException"))
                .thenReturn(Mono.just(false));

        publisher.publishPending();

        verify(repository, timeout(1_000)).markFailure(
                row.id(), row.claimToken(), Duration.ofMillis(250), "IllegalArgumentException");
        await(() -> meterRegistry.get("grassland.outbox.mark.failures").counter().count() == 1.0);
        assertThat(meterRegistry.get("grassland.outbox.publish.failures").counter().count()).isEqualTo(1.0);
    }

    private static OutboxRepository.OutboxRow row(String suffix, UUID claimToken, int attemptCount) {
        return new OutboxRepository.OutboxRow(
                "d7440f7c-7d40-4e77-9f25-6cc9c58b758" + suffix,
                "evt-" + suffix,
                "IdentityChanged",
                "Identity",
                "aggregate-" + suffix,
                "{\"text\":\"quote \\\" and 中文\"}",
                claimToken,
                attemptCount);
    }

    private static void await(Check check) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!check.satisfied() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(check.satisfied()).isTrue();
    }

    @FunctionalInterface
    private interface Check {
        boolean satisfied();
    }
}
