package com.grassland.intelligence.ai.run;

import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditSettlement;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/** Multi-replica-safe delivery of completed AI usage to Finance. */
@Component
public class CreditUsageSettlementDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(CreditUsageSettlementDispatcher.class);
    private final CreditUsageSettlementRepository repository;
    private final CreditsClient credits;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final boolean enabled;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration claimLease;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final AtomicBoolean dispatching = new AtomicBoolean();

    public CreditUsageSettlementDispatcher(
            CreditUsageSettlementRepository repository,
            CreditsClient credits,
            OutboxRepository outbox,
            TransactionalOperator transactions,
            @Value("${ai.credit-usage-settlement.enabled:true}") boolean enabled,
            @Value("${ai.credit-usage-settlement.batch-size:20}") int batchSize,
            @Value("${ai.credit-usage-settlement.max-attempts:20}") int maxAttempts,
            @Value("${ai.credit-usage-settlement.claim-lease:PT1M}") Duration claimLease,
            @Value("${ai.credit-usage-settlement.initial-backoff:PT2S}") Duration initialBackoff,
            @Value("${ai.credit-usage-settlement.max-backoff:PT5M}") Duration maxBackoff) {
        this.repository = repository;
        this.credits = credits;
        this.outbox = outbox;
        this.transactions = transactions;
        this.enabled = enabled;
        this.batchSize = Math.max(batchSize, 1);
        this.maxAttempts = Math.max(maxAttempts, 1);
        this.claimLease = claimLease;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

    @Scheduled(fixedDelayString = "${ai.credit-usage-settlement.poll-interval-ms:2000}")
    public void dispatchPending() {
        if (!enabled || !dispatching.compareAndSet(false, true)) {
            return;
        }
        dispatchBatch()
                .doOnError(error -> logger.error("AI credit usage settlement batch failed", error))
                .onErrorResume(error -> Mono.empty())
                .doFinally(signal -> dispatching.set(false))
                .subscribe();
    }

    Mono<Void> dispatchBatch() {
        if (!enabled) {
            return Mono.empty();
        }
        UUID claimToken = UUID.randomUUID();
        return repository.claimBatch(batchSize, claimToken, claimLease)
                .flatMap(this::process, batchSize)
                .then();
    }

    private Mono<Void> process(CreditUsageSettlementRepository.SettlementClaim claim) {
        CreditFeature feature;
        try {
            feature = CreditFeature.fromKey(claim.feature());
        } catch (IllegalArgumentException error) {
            return markTerminalFailed(claim, error);
        }
        CreditCharge reservation = new CreditCharge(
                claim.accountId(), feature, claim.consumeOperationId().toString(),
                CreditCharge.Source.PAID, null, true, claim.policyVersion(), -1, -1);
        return credits.settleUsage(reservation, claim.actualCents(), claim.policyVersion())
                .flatMap(settlement -> complete(claim, settlement))
                .onErrorResume(error -> isTerminal(claim, error)
                        ? markTerminalFailed(claim, error)
                        : markFailed(claim, error));
    }

    private Mono<Void> complete(
            CreditUsageSettlementRepository.SettlementClaim claim,
            CreditSettlement settlement) {
        Mono<Void> chain = repository.markCompleted(claim.id(), claim.claimToken(), settlement)
                .flatMap(updated -> updated
                        ? outbox.append(usageAdjustedEvent(claim, settlement)).thenReturn(true)
                        : Mono.just(false))
                .then();
        return transactions.transactional(chain);
    }

    private Mono<Void> markFailed(
            CreditUsageSettlementRepository.SettlementClaim claim, Throwable error) {
        return repository.markFailed(
                        claim.id(), claim.claimToken(), backoff(claim.attemptCount()), errorCode(error))
                .then();
    }

    private Mono<Void> markTerminalFailed(
            CreditUsageSettlementRepository.SettlementClaim claim, Throwable error) {
        String code = errorCode(error);
        return repository.markTerminalFailed(claim.id(), claim.claimToken(), code)
                .doOnNext(updated -> {
                    if (updated) {
                        logger.error(
                                "AI credit usage settlement entered terminal failure: id={} runId={} "
                                        + "attemptCount={} errorCode={}",
                                claim.id(), claim.runId(), claim.attemptCount(), code);
                    }
                })
                .then();
    }

    private boolean isTerminal(
            CreditUsageSettlementRepository.SettlementClaim claim, Throwable error) {
        if (claim.attemptCount() >= maxAttempts) {
            return true;
        }
        Throwable unwrapped = Exceptions.unwrap(error);
        if (!(unwrapped instanceof IntelligenceException intelligenceError)) {
            return false;
        }
        return intelligenceError.status() == 400
                || intelligenceError.status() == 404
                || intelligenceError.status() == 409
                || intelligenceError.status() == 422;
    }

    private Duration backoff(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 30));
        long multiplier = 1L << exponent;
        long initialMillis = Math.max(initialBackoff.toMillis(), 1L);
        long maximumMillis = Math.max(maxBackoff.toMillis(), initialMillis);
        if (multiplier > maximumMillis / initialMillis) {
            return Duration.ofMillis(maximumMillis);
        }
        return Duration.ofMillis(Math.min(initialMillis * multiplier, maximumMillis));
    }

    private static String errorCode(Throwable error) {
        Throwable unwrapped = Exceptions.unwrap(error);
        return unwrapped instanceof IntelligenceException intelligenceError
                ? "IntelligenceException:" + intelligenceError.status()
                : unwrapped.getClass().getSimpleName();
    }

    private static EventEnvelope usageAdjustedEvent(
            CreditUsageSettlementRepository.SettlementClaim claim,
            CreditSettlement settlement) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", claim.runId().toString());
        payload.put("accountId", claim.accountId());
        payload.put("feature", claim.feature());
        payload.put("source", settlement.source().name().toLowerCase(java.util.Locale.ROOT));
        payload.put("creditsCentsPolicyVersion", settlement.creditsCentsPolicyVersion());
        payload.put("reservedCents", settlement.reservedCents());
        payload.put("reservedCredits", settlement.reservedCredits());
        payload.put("actualCents", settlement.actualCents());
        payload.put("actualCredits", settlement.actualCredits());
        payload.put("adjustmentCredits", settlement.adjustmentCredits());
        return new EventEnvelope(
                UUID.nameUUIDFromBytes(("UsageAdjusted:" + claim.runId()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString(),
                "UsageAdjusted", "ai_run", claim.runId().toString(), 1,
                Instant.now(), claim.consumeOperationId().toString(), payload);
    }
}
