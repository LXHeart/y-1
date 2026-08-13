package com.grassland.intelligence.ai.run;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@SuppressWarnings("unchecked")
class CreditCompensationDispatcherTest {

    @Test
    void retryUsesStableConsumeOperationAndStopsAfterSuccess() {
        CreditCompensationRepository repository = mock(CreditCompensationRepository.class);
        CreditsClient credits = mock(CreditsClient.class);
        UUID runId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID firstToken = UUID.randomUUID();
        UUID secondToken = UUID.randomUUID();
        var first = claim(claimId, runId, operationId, firstToken, 1);
        var second = claim(claimId, runId, operationId, secondToken, 2);
        when(repository.claimRun(eq(runId), any(UUID.class), any(Duration.class)))
                .thenReturn(Mono.just(first), Mono.just(second), Mono.empty());
        CreditCharge expected = new CreditCharge(
                first.accountId(), CreditFeature.AI_RUN_TEXT, operationId.toString());
        when(credits.compensate(expected, first.reason()))
                .thenReturn(Mono.error(new IllegalStateException("finance unavailable")), Mono.empty());
        when(repository.markFailed(eq(claimId), eq(firstToken), any(Duration.class), any(String.class)))
                .thenReturn(Mono.just(true));
        when(repository.markCompleted(claimId, secondToken)).thenReturn(Mono.just(true));
        CreditCompensationDispatcher dispatcher = new CreditCompensationDispatcher(
                repository, credits, true, 10, 10, Duration.ofMinutes(1),
                Duration.ofMillis(1), Duration.ofSeconds(1));

        dispatcher.dispatchRun(runId).block();
        dispatcher.dispatchRun(runId).block();
        dispatcher.dispatchRun(runId).block();

        verify(credits, times(2)).compensate(expected, first.reason());
        verify(repository).markCompleted(claimId, secondToken);
    }

    @Test
    void permanentFinanceErrorMovesIntentToTerminalFailure() {
        CreditCompensationRepository repository = mock(CreditCompensationRepository.class);
        CreditsClient credits = mock(CreditsClient.class);
        UUID runId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        var claim = claim(claimId, runId, UUID.randomUUID(), claimToken, 1);
        when(repository.claimRun(eq(runId), any(UUID.class), any(Duration.class)))
                .thenReturn(Mono.just(claim));
        when(credits.compensate(any(CreditCharge.class), eq(claim.reason())))
                .thenReturn(Mono.error(new IntelligenceException(409, "operation scope conflict")));
        when(repository.markTerminalFailed(claimId, claimToken, "IntelligenceException:409"))
                .thenReturn(Mono.just(true));
        CreditCompensationDispatcher dispatcher = new CreditCompensationDispatcher(
                repository, credits, true, 10, 10, Duration.ofMinutes(1),
                Duration.ofMillis(1), Duration.ofSeconds(1));

        dispatcher.dispatchRun(runId).block();

        verify(repository).markTerminalFailed(claimId, claimToken, "IntelligenceException:409");
        verify(repository, never()).markFailed(eq(claimId), eq(claimToken), any(), any());
    }

    @Test
    void unknownFeatureMovesIntentToTerminalFailureWithoutCallingFinance() {
        CreditCompensationRepository repository = mock(CreditCompensationRepository.class);
        CreditsClient credits = mock(CreditsClient.class);
        UUID runId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        var claim = new CreditCompensationRepository.CompensationClaim(
                claimId, runId, UUID.randomUUID(), UUID.randomUUID().toString(),
                "unknown_feature", "AI run failed", claimToken, 1);
        when(repository.claimRun(eq(runId), any(UUID.class), any(Duration.class)))
                .thenReturn(Mono.just(claim));
        when(repository.markTerminalFailed(claimId, claimToken, "IllegalArgumentException"))
                .thenReturn(Mono.just(true));
        CreditCompensationDispatcher dispatcher = new CreditCompensationDispatcher(
                repository, credits, true, 10, 10, Duration.ofMinutes(1),
                Duration.ofMillis(1), Duration.ofSeconds(1));

        dispatcher.dispatchRun(runId).block();

        verify(repository).markTerminalFailed(claimId, claimToken, "IllegalArgumentException");
        verify(credits, never()).compensate(any(), any());
    }

    @Test
    void transientFailureStopsRetryingAtConfiguredAttemptLimit() {
        CreditCompensationRepository repository = mock(CreditCompensationRepository.class);
        CreditsClient credits = mock(CreditsClient.class);
        UUID runId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        var claim = claim(claimId, runId, UUID.randomUUID(), claimToken, 3);
        when(repository.claimRun(eq(runId), any(UUID.class), any(Duration.class)))
                .thenReturn(Mono.just(claim));
        when(credits.compensate(any(CreditCharge.class), eq(claim.reason())))
                .thenReturn(Mono.error(new IntelligenceException(502, "finance unavailable")));
        when(repository.markTerminalFailed(claimId, claimToken, "IntelligenceException:502"))
                .thenReturn(Mono.just(true));
        CreditCompensationDispatcher dispatcher = new CreditCompensationDispatcher(
                repository, credits, true, 10, 3, Duration.ofMinutes(1),
                Duration.ofMillis(1), Duration.ofSeconds(1));

        dispatcher.dispatchRun(runId).block();

        verify(repository).markTerminalFailed(claimId, claimToken, "IntelligenceException:502");
        verify(repository, never()).markFailed(eq(claimId), eq(claimToken), any(), any());
    }

    @Test
    void rollingUpgradeNotFoundRemainsRetryable() {
        CreditCompensationRepository repository = mock(CreditCompensationRepository.class);
        CreditsClient credits = mock(CreditsClient.class);
        UUID runId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        var claim = claim(claimId, runId, UUID.randomUUID(), claimToken, 1);
        when(repository.claimRun(eq(runId), any(UUID.class), any(Duration.class)))
                .thenReturn(Mono.just(claim));
        when(credits.compensate(any(CreditCharge.class), eq(claim.reason())))
                .thenReturn(Mono.error(new IntelligenceException(404, "route not deployed")));
        when(repository.markFailed(eq(claimId), eq(claimToken), any(Duration.class),
                eq("IntelligenceException:404"))).thenReturn(Mono.just(true));
        CreditCompensationDispatcher dispatcher = new CreditCompensationDispatcher(
                repository, credits, true, 10, 10, Duration.ofMinutes(1),
                Duration.ofMillis(1), Duration.ofSeconds(1));

        dispatcher.dispatchRun(runId).block();

        verify(repository).markFailed(eq(claimId), eq(claimToken), any(Duration.class),
                eq("IntelligenceException:404"));
        verify(repository, never()).markTerminalFailed(any(), any(), any());
    }

    private static CreditCompensationRepository.CompensationClaim claim(
            UUID id, UUID runId, UUID operationId, UUID token, int attempt) {
        return new CreditCompensationRepository.CompensationClaim(
                id, runId, operationId,
                "44444444-4444-4444-4444-444444444444",
                "ai_run_text", "AI run failed", token, attempt);
    }
}
