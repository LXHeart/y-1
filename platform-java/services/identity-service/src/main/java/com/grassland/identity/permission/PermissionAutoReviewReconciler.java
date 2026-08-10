package com.grassland.identity.permission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import com.grassland.identity.organization.PermissionTier;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** Refreshes permission recommendations after asynchronous KYB OCR finishes. */
@Component
@ConditionalOnProperty(prefix = "identity.permission.auto-review", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PermissionAutoReviewReconciler {

    private static final Logger log = LoggerFactory.getLogger(PermissionAutoReviewReconciler.class);

    private final MerchantPermissionRequestRepository requests;
    private final PermissionAutomaticReviewer reviewer;
    private final PermissionRequestAuditRepository audits;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean polling = new AtomicBoolean();

    public PermissionAutoReviewReconciler(MerchantPermissionRequestRepository requests,
                                          PermissionAutomaticReviewer reviewer,
                                          PermissionRequestAuditRepository audits,
                                          OutboxRepository outbox,
                                          TransactionalOperator transactions) {
        this.requests = requests;
        this.reviewer = reviewer;
        this.audits = audits;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${identity.permission.auto-review.poll-interval-ms:5000}")
    public void poll() {
        if (!polling.compareAndSet(false, true)) return;
        processBatch(100).then()
                .doOnError(error -> log.error("permission auto review reconciliation failed", error))
                .doFinally(signal -> polling.set(false))
                .subscribe();
    }

    reactor.core.publisher.Flux<MerchantPermissionRequest> processBatch(int limit) {
        return requests.findAwaitingAutomaticReview(limit).concatMap(this::reconcile);
    }

    private Mono<MerchantPermissionRequest> reconcile(MerchantPermissionRequest request) {
        PermissionTier tier = PermissionTier.fromDb(request.requestedTier());
        Industry industry = Industry.fromDb(request.industry());
        return reviewer.evaluate(request.organizationId(), tier, industry, parseIds(request.attachmentIds()))
                .flatMap(auto -> unchanged(request, auto)
                        ? Mono.empty()
                        : transactions.transactional(requests.updateAutomaticReview(
                                        request.id(), request.version(), auto)
                                .flatMap(updated -> audits.append(updated.id(), updated.organizationId(), null, "system",
                                                "automatic_review_updated", request.status(), updated.status(), auto.resultJson())
                                        .then(outbox.append(new EventEnvelope(UUID.randomUUID().toString(),
                                                "PermissionAutoReviewUpdated", "MerchantPermissionRequest",
                                                updated.id(), 1, Instant.now(), null,
                                                Map.of("organizationId", updated.organizationId(),
                                                        "status", updated.autoReviewStatus()))))
                                        .thenReturn(updated))));
    }

    private static boolean unchanged(MerchantPermissionRequest request, PermissionAutoReview auto) {
        return java.util.Objects.equals(auto.status(), request.autoReviewStatus())
                && java.util.Objects.equals(auto.mode(), request.reviewMode())
                && java.util.Objects.equals(auto.riskLevel(), request.riskLevel())
                && java.util.Objects.equals(auto.resultJson(), request.autoReviewResult());
    }

    private List<String> parseIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception error) {
            throw new IllegalStateException("permission attachment_ids is invalid", error);
        }
    }
}
