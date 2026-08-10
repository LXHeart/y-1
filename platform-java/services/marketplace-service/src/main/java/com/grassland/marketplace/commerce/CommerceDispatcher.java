package com.grassland.marketplace.commerce;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Repairs cross-service gaps and triggers automatic refunds for expired unredeemed orders. */
@Component
@ConditionalOnProperty(name = "marketplace.commerce.dispatcher-enabled", havingValue = "true", matchIfMissing = true)
public class CommerceDispatcher {

    private final CommerceService service;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean();

    public CommerceDispatcher(
            CommerceService service,
            @Value("${marketplace.commerce.dispatcher-batch-size:32}") int batchSize) {
        this.service = service;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${marketplace.commerce.dispatcher-poll-ms:3000}")
    public void dispatch() {
        if (!running.compareAndSet(false, true)) return;
        service.claimExpired(batchSize)
                .flatMap(order -> service.attemptRefund(order, "automatic_expiry"), 4)
                .thenMany(service.pendingDispatch(batchSize))
                .flatMap(order -> switch (order.status()) {
                    case "pending_payment" -> service.attemptPayment(order);
                    case "refund_pending" -> service.attemptRefund(order, "dispatcher_retry");
                    case "redeeming" -> service.attemptSplit(order);
                    default -> Flux.<CommerceModels.Order>empty().next();
                }, 4)
                .doFinally(ignored -> running.set(false))
                .subscribe(ignored -> {}, error -> running.set(false));
    }
}
