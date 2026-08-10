package com.grassland.identity.permission;

import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;

/** Marks SLA breaches durably and emits one event/audit entry per request. Never auto-grants access. */
@Component
@ConditionalOnProperty(prefix = "identity.permission.sla-monitor", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class PermissionSlaMonitor {

    private static final Logger log = LoggerFactory.getLogger(PermissionSlaMonitor.class);

    private final MerchantPermissionRequestRepository requests;
    private final PermissionRequestAuditRepository audits;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final AtomicBoolean polling = new AtomicBoolean();

    public PermissionSlaMonitor(MerchantPermissionRequestRepository requests,
                                PermissionRequestAuditRepository audits,
                                OutboxRepository outbox,
                                TransactionalOperator transactions) {
        this.requests = requests;
        this.audits = audits;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${identity.permission.sla-monitor.poll-interval-ms:60000}")
    public void poll() {
        if (!polling.compareAndSet(false, true)) return;
        processBatch(100)
                .doOnError(error -> log.error("permission SLA monitor failed", error))
                .doFinally(signal -> polling.set(false))
                .subscribe();
    }

    Flux<Void> processBatch(int limit) {
        return transactions.transactional(requests.markOverdue(limit)
                .concatMap(req -> audits.append(req.id(), req.organizationId(), null, "system",
                                "sla_breached", req.status(), req.status(), null)
                        .then(outbox.append(new EventEnvelope(UUID.randomUUID().toString(),
                                "PermissionReviewSlaBreached", "MerchantPermissionRequest", req.id(),
                                1, Instant.now(), null, Map.of("organizationId", req.organizationId(),
                                        "requestId", req.id(), "requestedTier", req.requestedTier())))).then()));
    }
}
