package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.taskcatalog.AcceptanceCommand;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Shared starter for the request path and the durable acceptance dispatcher. */
@Component
public class AcceptanceWorkflowStarter {

    private final WorkflowClient workflowClient;

    public AcceptanceWorkflowStarter(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    public Mono<String> start(AcceptanceCommand command) {
        AcceptanceInput input = new AcceptanceInput(
                command.applicationId(), command.taskId(), command.merchantAccountId(),
                command.organizationId(), command.amountCents(), command.actorAccountId(), command.id());
        return Mono.fromCallable(() -> {
            ApplicationReservationWorkflow stub = workflowClient.newWorkflowStub(
                    ApplicationReservationWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(command.workflowId())
                            .setTaskQueue(ApplicationReservationWorkflowImpl.TASK_QUEUE)
                            .setWorkflowIdReusePolicy(
                                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                            .build());
            WorkflowClient.start(stub::run, input);
            return command.workflowId();
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(WorkflowExecutionAlreadyStarted.class,
                alreadyStarted -> Mono.just(command.workflowId()));
    }
}
