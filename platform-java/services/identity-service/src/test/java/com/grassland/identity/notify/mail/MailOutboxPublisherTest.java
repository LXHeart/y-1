package com.grassland.identity.notify.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.identity.notify.SmtpMailSender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class MailOutboxPublisherTest {

    @Mock private MailOutboxRepository repository;
    @Mock private SmtpMailSender mailSender;

    private SimpleMeterRegistry meterRegistry;
    private MailOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        publisher = new MailOutboxPublisher(repository, mailSender, meterRegistry, properties(true));
        lenient().when(mailSender.isConfigured()).thenReturn(true);
        lenient().when(repository.pendingCount()).thenReturn(Mono.just(0L));
        lenient().when(repository.deadCount()).thenReturn(Mono.just(0L));
    }

    @Test
    void committedDeadTransitionIncrementsCounterAndRefreshesCurrentGauge() throws Exception {
        MailOutboxRepository.MailOutboxRow row = row();
        when(repository.claimBatch(eq(10), any(UUID.class), eq(Duration.ofSeconds(60))))
                .thenReturn(Flux.just(row));
        doThrow(new IllegalStateException("smtp unavailable: secret detail"))
                .when(mailSender).send(row.recipient(), row.subject(), row.body());
        when(repository.markDead(row.id(), row.claimToken(), "IllegalStateException"))
                .thenReturn(Mono.just(true));
        when(repository.deadCount()).thenReturn(Mono.just(1L));

        publisher.publishPending();

        verify(repository, timeout(1_000)).markDead(row.id(), row.claimToken(), "IllegalStateException");
        await(() -> counter("grassland.mail.outbox.dead") == 1.0
                && gauge("grassland.mail.outbox.dead.current") == 1.0);
        assertThat(counter("grassland.mail.outbox.failures")).isEqualTo(1.0);
        assertThat(counter("grassland.mail.outbox.mark.failures")).isZero();
    }

    @Test
    void markDeadClaimMissDoesNotCountCommittedDeadTransition() throws Exception {
        MailOutboxRepository.MailOutboxRow row = row();
        when(repository.claimBatch(eq(10), any(UUID.class), eq(Duration.ofSeconds(60))))
                .thenReturn(Flux.just(row));
        doThrow(new IllegalStateException("smtp unavailable"))
                .when(mailSender).send(row.recipient(), row.subject(), row.body());
        when(repository.markDead(row.id(), row.claimToken(), "IllegalStateException"))
                .thenReturn(Mono.just(false));

        publisher.publishPending();

        verify(repository, timeout(1_000)).markDead(row.id(), row.claimToken(), "IllegalStateException");
        await(() -> counter("grassland.mail.outbox.mark.failures") == 1.0);
        assertThat(counter("grassland.mail.outbox.dead")).isZero();
    }

    @Test
    void markDeadErrorDoesNotCountCommittedDeadTransition() throws Exception {
        MailOutboxRepository.MailOutboxRow row = row();
        when(repository.claimBatch(eq(10), any(UUID.class), eq(Duration.ofSeconds(60))))
                .thenReturn(Flux.just(row));
        doThrow(new IllegalStateException("smtp unavailable"))
                .when(mailSender).send(row.recipient(), row.subject(), row.body());
        when(repository.markDead(row.id(), row.claimToken(), "IllegalStateException"))
                .thenReturn(Mono.error(new IllegalStateException("database unavailable")));

        publisher.publishPending();

        verify(repository, timeout(1_000)).markDead(row.id(), row.claimToken(), "IllegalStateException");
        await(() -> counter("grassland.mail.outbox.mark.failures") == 1.0);
        assertThat(counter("grassland.mail.outbox.dead")).isZero();
    }

    @Test
    void disabledPublisherSkipsDeliveryButStillRefreshesBacklogGauges() throws Exception {
        meterRegistry.close();
        meterRegistry = new SimpleMeterRegistry();
        publisher = new MailOutboxPublisher(repository, mailSender, meterRegistry, properties(false));
        when(repository.pendingCount()).thenReturn(Mono.just(3L));
        when(repository.deadCount()).thenReturn(Mono.just(2L));

        publisher.publishPending();

        await(() -> gauge("grassland.mail.outbox.pending") == 3.0
                && gauge("grassland.mail.outbox.dead.current") == 2.0);
        verify(repository, never()).claimBatch(anyInt(), any(UUID.class), any(Duration.class));
    }

    @Test
    void unconfiguredSmtpSkipsDeliveryButStillRefreshesBacklogGauges() throws Exception {
        when(mailSender.isConfigured()).thenReturn(false);
        when(repository.pendingCount()).thenReturn(Mono.just(4L));
        when(repository.deadCount()).thenReturn(Mono.just(1L));

        publisher.publishPending();

        await(() -> gauge("grassland.mail.outbox.pending") == 4.0
                && gauge("grassland.mail.outbox.dead.current") == 1.0);
        verify(repository, never()).claimBatch(anyInt(), any(UUID.class), any(Duration.class));
    }

    private static MailOutboxProperties properties(boolean enabled) {
        return new MailOutboxProperties(enabled, 2_000, 10, 1, 60_000, 1, 1_000, 30_000, 5_000);
    }

    private static MailOutboxRepository.MailOutboxRow row() {
        UUID claimToken = UUID.randomUUID();
        return new MailOutboxRepository.MailOutboxRow(
                "d7440f7c-7d40-4e77-9f25-6cc9c58b7581",
                "evt-1",
                "to@test",
                "subject",
                "body",
                claimToken,
                1);
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
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
