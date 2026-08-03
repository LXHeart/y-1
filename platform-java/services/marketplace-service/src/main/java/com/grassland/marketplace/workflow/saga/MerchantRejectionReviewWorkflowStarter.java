package com.grassland.marketplace.workflow.saga;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** D-03 商家拒绝客服 SLA workflow 启动器；确定性 workflowId + AlreadyStarted 幂等。 */
@Component
public class MerchantRejectionReviewWorkflowStarter {

    private final WorkflowClient workflowClient;

    public MerchantRejectionReviewWorkflowStarter(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    public Mono<String> start(String applicationId, String disputeId, String organizationId, long csSlaSeconds) {
        String workflowId = "rejection-review-" + applicationId;
        MerchantRejectionReviewInput input = new MerchantRejectionReviewInput(
                applicationId, disputeId, organizationId, Math.max(0, csSlaSeconds));
        return Mono.fromCallable(() -> {
            MerchantRejectionReviewWorkflow stub = workflowClient.newWorkflowStub(
                    MerchantRejectionReviewWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(workflowId)
                            .setTaskQueue(ApplicationReservationWorkflowImpl.TASK_QUEUE)
                            .setWorkflowIdReusePolicy(
                                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                            .build());
            WorkflowClient.start(stub::run, input);
            return workflowId;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(WorkflowExecutionAlreadyStarted.class, already -> Mono.just(workflowId));
    }
}
