package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditSettlement;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SuppressWarnings("unchecked")
class CreditUsageSettlementDispatcherTest {

    private CreditUsageSettlementRepository repository;
    private CreditsClient credits;
    private OutboxRepository outbox;
    private TransactionalOperator transactions;

    @BeforeEach
    void setUp() {
        repository = mock(CreditUsageSettlementRepository.class);
        credits = mock(CreditsClient.class);
        outbox = mock(OutboxRepository.class);
        transactions = mock(TransactionalOperator.class);
        when(transactions.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void successfulSettlementCompletesIntentAndEmitsUsageAdjusted() {
        var claim = claim(1);
        var settlement = settlement(claim, -2);
        when(repository.claimBatch(eq(10), any(UUID.class), eq(Duration.ofMinutes(1))))
                .thenReturn(Flux.just(claim));
        when(credits.settleUsage(any(CreditCharge.class), eq(100L), eq("money-v1")))
                .thenReturn(Mono.just(settlement));
        when(repository.markCompleted(claim.id(), claim.claimToken(), settlement))
                .thenReturn(Mono.just(true));
        when(outbox.append(any())).thenReturn(Mono.empty());

        dispatcher(10).dispatchBatch().block();

        ArgumentCaptor<CreditCharge> reservation = ArgumentCaptor.forClass(CreditCharge.class);
        verify(credits).settleUsage(reservation.capture(), eq(100L), eq("money-v1"));
        assertThat(reservation.getValue().operationId()).isEqualTo(claim.consumeOperationId().toString());
        ArgumentCaptor<EventEnvelope> event = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outbox).append(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("UsageAdjusted");
        assertThat(event.getValue().payload()).containsEntry("adjustmentCredits", -2);
    }

    @Test
    void insufficientTopUpRemainsRetryableUntilAttemptLimit() {
        var claim = claim(1);
        when(repository.claimBatch(eq(10), any(UUID.class), eq(Duration.ofMinutes(1))))
                .thenReturn(Flux.just(claim));
        when(credits.settleUsage(any(), eq(100L), eq("money-v1")))
                .thenReturn(Mono.error(new IntelligenceException(402, "insufficient credits")));
        when(repository.markFailed(
                eq(claim.id()), eq(claim.claimToken()), eq(Duration.ofSeconds(2)),
                eq("IntelligenceException:402"))).thenReturn(Mono.just(true));

        dispatcher(3).dispatchBatch().block();

        verify(repository).markFailed(
                claim.id(), claim.claimToken(), Duration.ofSeconds(2), "IntelligenceException:402");
        verify(repository, never()).markTerminalFailed(any(), any(), any());
        verify(outbox, never()).append(any());
    }

    @Test
    void policyConflictIsTerminalAndDoesNotEmitEvent() {
        var claim = claim(1);
        when(repository.claimBatch(eq(10), any(UUID.class), eq(Duration.ofMinutes(1))))
                .thenReturn(Flux.just(claim));
        when(credits.settleUsage(any(), eq(100L), eq("money-v1")))
                .thenReturn(Mono.error(new IntelligenceException(409, "policy mismatch")));
        when(repository.markTerminalFailed(
                claim.id(), claim.claimToken(), "IntelligenceException:409"))
                .thenReturn(Mono.just(true));

        dispatcher(10).dispatchBatch().block();

        verify(repository).markTerminalFailed(
                claim.id(), claim.claimToken(), "IntelligenceException:409");
        verify(repository, never()).markFailed(any(), any(), any(), any());
        verify(outbox, never()).append(any());
    }

    @Test
    void transientFailureBecomesTerminalAtAttemptLimit() {
        var claim = claim(3);
        when(repository.claimBatch(eq(10), any(UUID.class), eq(Duration.ofMinutes(1))))
                .thenReturn(Flux.just(claim));
        when(credits.settleUsage(any(), eq(100L), eq("money-v1")))
                .thenReturn(Mono.error(new IntelligenceException(502, "finance unavailable")));
        when(repository.markTerminalFailed(
                claim.id(), claim.claimToken(), "IntelligenceException:502"))
                .thenReturn(Mono.just(true));

        dispatcher(3).dispatchBatch().block();

        verify(repository).markTerminalFailed(
                claim.id(), claim.claimToken(), "IntelligenceException:502");
        verify(repository, never()).markFailed(any(), any(), any(), any());
    }

    private CreditUsageSettlementDispatcher dispatcher(int maxAttempts) {
        return new CreditUsageSettlementDispatcher(
                repository, credits, outbox, transactions, true, 10, maxAttempts,
                Duration.ofMinutes(1), Duration.ofSeconds(2), Duration.ofMinutes(5));
    }

    private static CreditUsageSettlementRepository.SettlementClaim claim(int attempt) {
        return new CreditUsageSettlementRepository.SettlementClaim(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "44444444-4444-4444-4444-444444444444", "ai_run_text",
                "money-v1", 100, UUID.randomUUID(), attempt);
    }

    private static CreditSettlement settlement(
            CreditUsageSettlementRepository.SettlementClaim claim, int adjustment) {
        return new CreditSettlement(
                claim.accountId(), CreditFeature.AI_RUN_TEXT,
                claim.consumeOperationId().toString(), CreditCharge.Source.PAID,
                claim.policyVersion(), 300, 3, claim.actualCents(), 1, adjustment, false);
    }
}
