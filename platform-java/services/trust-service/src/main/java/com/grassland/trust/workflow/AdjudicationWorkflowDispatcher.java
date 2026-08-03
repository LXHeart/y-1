package com.grassland.trust.workflow;

import com.grassland.trust.dispute.DeferredDisputeRequest;
import com.grassland.trust.dispute.DeferredDisputeRequestRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** F5 promoted standard 案的 durable Temporal 启动派发器。 */
@Component
@ConditionalOnProperty(prefix = "trust.adjudication.dispatcher", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AdjudicationWorkflowDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AdjudicationWorkflowDispatcher.class);

    private final DeferredDisputeRequestRepository requests;
    private final AdjudicationWorkflowStarter starter;
    private final int batchSize;

    public AdjudicationWorkflowDispatcher(
            DeferredDisputeRequestRepository requests,
            AdjudicationWorkflowStarter starter,
            @org.springframework.beans.factory.annotation.Value(
                    "${trust.adjudication.dispatcher.batch-size:32}") int batchSize) {
        this.requests = requests;
        this.starter = starter;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${trust.adjudication.dispatcher.poll-ms:2000}")
    public void dispatch() {
        Mono.fromRunnable(this::dispatchBatch).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    void dispatchBatch() {
        List<DeferredDisputeRequest> rows = requests.findAdjudicationDispatchable(batchSize).collectList().block();
        if (rows == null) {
            return;
        }
        for (DeferredDisputeRequest request : rows) {
            try {
                starter.start(request.promotedDisputeId())
                        .then(Mono.defer(() -> requests.markAdjudicationWorkflowStarted(
                                request.id(), request.promotedDisputeId())))
                        .block();
            } catch (RuntimeException failure) {
                log.warn("deferred adjudication dispatch failed request={} dispute={}",
                        request.id(), request.promotedDisputeId(), failure);
            }
        }
    }
}
