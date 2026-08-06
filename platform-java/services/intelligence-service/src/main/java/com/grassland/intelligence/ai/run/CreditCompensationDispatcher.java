package com.grassland.intelligence.ai.run;

import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/** Multi-replica-safe dispatcher for durable AI credit compensation intents. */
@Component
public class CreditCompensationDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(CreditCompensationDispatcher.class);
    private static final int MAX_ERROR_CODE_LENGTH = 64;

    private final CreditCompensationRepository repository;
    private final CreditsClient credits;
    private final boolean enabled;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration claimLease;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final AtomicBoolean dispatching = new AtomicBoolean();

    public CreditCompensationDispatcher(
            CreditCompensationRepository repository,
            CreditsClient credits,
            @Value("${ai.credit-compensation.enabled:true}") boolean enabled,
            @Value("${ai.credit-compensation.batch-size:20}") int batchSize,
            @Value("${ai.credit-compensation.max-attempts:10}") int maxAttempts,
            @Value("${ai.credit-compensation.claim-lease:PT1M}") Duration claimLease,
            @Value("${ai.credit-compensation.initial-backoff:PT2S}") Duration initialBackoff,
            @Value("${ai.credit-compensation.max-backoff:PT5M}") Duration maxBackoff) {
        this.repository = repository;
        this.credits = credits;
        this.enabled = enabled;
        this.batchSize = Math.max(batchSize, 1);
        this.maxAttempts = Math.max(maxAttempts, 1);
        this.claimLease = claimLease;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

    @Scheduled(fixedDelayString = "${ai.credit-compensation.poll-interval-ms:2000}")
    public void dispatchPending() {
        if (!enabled || !dispatching.compareAndSet(false, true)) {
            return;
        }
        UUID claimToken = UUID.randomUUID();
        repository.claimBatch(batchSize, claimToken, claimLease)
                .flatMap(this::process, batchSize)
                .then()
                .doOnError(error -> logger.error("AI credit compensation batch failed", error))
                .onErrorResume(error -> Mono.empty())
                .doFinally(signal -> dispatching.set(false))
                .subscribe();
    }

    /** Fast-path after the surrounding failure transaction commits; scheduled polling remains the reliability source. */
    public Mono<Void> dispatchRun(UUID runId) {
        UUID claimToken = UUID.randomUUID();
        return repository.claimRun(runId, claimToken, claimLease)
                .flatMap(this::process)
                .then();
    }

    private Mono<Void> process(CreditCompensationRepository.CompensationClaim claim) {
        CreditFeature feature;
        try {
            feature = CreditFeature.fromKey(claim.feature());
        } catch (IllegalArgumentException error) {
            return markTerminalFailed(claim, error);
        }
        CreditCharge charge = new CreditCharge(
                claim.accountId(), feature, claim.consumeOperationId().toString());
        return credits.compensate(charge, claim.reason())
                .then(Mono.defer(() -> repository.markCompleted(claim.id(), claim.claimToken())))
                .flatMap(updated -> {
                    if (!updated) {
                        logger.debug("Credit compensation claim expired before completion: {}", claim.id());
                    }
                    return Mono.<Void>empty();
                })
                .onErrorResume(error -> isTerminal(claim, error)
                        ? markTerminalFailed(claim, error)
                        : markFailed(claim, error));
    }

    private Mono<Void> markFailed(
            CreditCompensationRepository.CompensationClaim claim, Throwable error) {
        Duration delay = backoff(claim.attemptCount());
        return repository.markFailed(claim.id(), claim.claimToken(), delay, errorCode(error))
                .doOnNext(updated -> {
                    if (!updated) {
                        logger.debug("Credit compensation failure ignored for expired claim: {}", claim.id());
                    }
                })
                .then();
    }

    private Mono<Void> markTerminalFailed(
            CreditCompensationRepository.CompensationClaim claim, Throwable error) {
        String code = errorCode(error);
        return repository.markTerminalFailed(claim.id(), claim.claimToken(), code)
                .doOnNext(updated -> {
                    if (updated) {
                        logger.error(
                                "AI credit compensation entered terminal failure: id={} runId={} "
                                        + "attemptCount={} errorCode={}",
                                claim.id(), claim.runId(), claim.attemptCount(), code);
                    } else {
                        logger.debug("Terminal failure ignored for expired compensation claim: {}", claim.id());
                    }
                })
                .then();
    }

    private boolean isTerminal(
            CreditCompensationRepository.CompensationClaim claim, Throwable error) {
        if (claim.attemptCount() >= maxAttempts) {
            return true;
        }
        Throwable unwrapped = Exceptions.unwrap(error);
        if (!(unwrapped instanceof IntelligenceException intelligenceError)) {
            return false;
        }
        return intelligenceError.status() == 400
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
        String code = unwrapped instanceof IntelligenceException intelligenceError
                ? "IntelligenceException:" + intelligenceError.status()
                : unwrapped.getClass().getSimpleName();
        return code.length() <= MAX_ERROR_CODE_LENGTH
                ? code
                : code.substring(0, MAX_ERROR_CODE_LENGTH);
    }
}
