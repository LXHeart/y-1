package com.grassland.trust.workflow;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.trust.dispute.DeferredDisputeRequest;
import com.grassland.trust.dispute.DeferredDisputeRequestRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** {@link AdjudicationWorkflowDispatcher} durable intent 扫描、幂等标记与逐行失败隔离。 */
class AdjudicationWorkflowDispatcherTest {

    private DeferredDisputeRequestRepository requests;
    private AdjudicationWorkflowStarter starter;
    private AdjudicationWorkflowDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        requests = org.mockito.Mockito.mock(DeferredDisputeRequestRepository.class);
        starter = org.mockito.Mockito.mock(AdjudicationWorkflowStarter.class);
        dispatcher = new AdjudicationWorkflowDispatcher(requests, starter, 32);
    }

    @Test
    void emptyBatchDoesNothing() {
        when(requests.findAdjudicationDispatchable(32)).thenReturn(Flux.empty());

        dispatcher.dispatchBatch();

        verify(starter, never()).start(anyString());
        verify(requests, never()).markAdjudicationWorkflowStarted(anyString(), anyString());
    }

    @Test
    void startsPromotedDisputeThenMarksDurableIntent() {
        DeferredDisputeRequest request = request("request-1", "dispute-1");
        when(requests.findAdjudicationDispatchable(32)).thenReturn(Flux.just(request));
        when(starter.start("dispute-1")).thenReturn(Mono.just("adjudicate-dispute-1"));
        when(requests.markAdjudicationWorkflowStarted("request-1", "dispute-1"))
                .thenReturn(Mono.just(true));

        dispatcher.dispatchBatch();

        var order = org.mockito.Mockito.inOrder(starter, requests);
        order.verify(starter).start("dispute-1");
        order.verify(requests).markAdjudicationWorkflowStarted("request-1", "dispute-1");
    }

    @Test
    void temporalFailureLeavesIntentPendingAndDoesNotBlockNextRow() {
        DeferredDisputeRequest first = request("request-1", "dispute-1");
        DeferredDisputeRequest second = request("request-2", "dispute-2");
        when(requests.findAdjudicationDispatchable(32)).thenReturn(Flux.just(first, second));
        when(starter.start("dispute-1")).thenReturn(Mono.error(new IllegalStateException("temporal down")));
        when(starter.start("dispute-2")).thenReturn(Mono.just("adjudicate-dispute-2"));
        when(requests.markAdjudicationWorkflowStarted("request-2", "dispute-2"))
                .thenReturn(Mono.just(true));

        dispatcher.dispatchBatch();

        verify(requests, never()).markAdjudicationWorkflowStarted("request-1", "dispute-1");
        verify(starter).start("dispute-2");
        verify(requests).markAdjudicationWorkflowStarted("request-2", "dispute-2");
    }

    @Test
    void failedStartedMarkerIsRetriedThroughFixedWorkflowId() {
        DeferredDisputeRequest request = request("request-1", "dispute-1");
        when(requests.findAdjudicationDispatchable(32)).thenReturn(Flux.just(request));
        when(starter.start("dispute-1")).thenReturn(Mono.just("adjudicate-dispute-1"));
        when(requests.markAdjudicationWorkflowStarted("request-1", "dispute-1"))
                .thenReturn(Mono.error(new IllegalStateException("database down")))
                .thenReturn(Mono.just(true));

        dispatcher.dispatchBatch();
        dispatcher.dispatchBatch();

        verify(starter, org.mockito.Mockito.times(2)).start("dispute-1");
        verify(requests, org.mockito.Mockito.times(2))
                .markAdjudicationWorkflowStarted("request-1", "dispute-1");
    }

    private DeferredDisputeRequest request(String id, String disputeId) {
        Instant now = Instant.now();
        return new DeferredDisputeRequest(
                id, "source-" + id, "engagement-" + id, "organization-" + id,
                "recommender-" + id, "逐字理由", "promoted", disputeId,
                "adjudicate-" + disputeId, null, now, now);
    }
}
