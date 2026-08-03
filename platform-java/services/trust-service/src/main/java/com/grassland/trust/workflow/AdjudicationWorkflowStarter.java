package com.grassland.trust.workflow;

import com.grassland.trust.adjudication.AdjudicationProperties;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 七官审判 workflow 共享启动器：HTTP 手动启动与 deferred dispatcher 共用固定 workflow id。 */
@Component
public class AdjudicationWorkflowStarter {

    private final WorkflowClient workflowClient;
    private final AdjudicationProperties props;

    public AdjudicationWorkflowStarter(WorkflowClient workflowClient, AdjudicationProperties props) {
        this.workflowClient = workflowClient;
        this.props = props;
    }

    public Mono<String> start(String disputeId) {
        String workflowId = "adjudicate-" + disputeId;
        AdjudicationInput input = new AdjudicationInput(
                disputeId, props.voteWindowSecondsEffective(), props.appealWindowSecondsEffective(),
                props.maxRounds(), Math.max(0, props.csAwaitHours()) * 3600L,
                Math.max(1, props.csPollSeconds()));
        return Mono.fromCallable(() -> {
            DisputeAdjudicationWorkflow stub = workflowClient.newWorkflowStub(
                    DisputeAdjudicationWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(workflowId)
                            .setTaskQueue(DisputeAdjudicationWorkflowImpl.TASK_QUEUE)
                            .setWorkflowIdReusePolicy(
                                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                            .build());
            WorkflowClient.start(stub::run, input);
            return workflowId;
        }).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(WorkflowExecutionAlreadyStarted.class, already -> Mono.just(workflowId));
    }
}
