package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Starts the same settlement timer for manual and automatic confirmation paths. */
@Component
public class SettlementWorkflowStarter {

    private final WorkflowClient workflowClient;
    private final long daySeconds;
    private final long disputeWindowSeconds;

    public SettlementWorkflowStarter(
            WorkflowClient workflowClient,
            @Value("${marketplace.settlement.day-seconds:86400}") long daySeconds,
            @Value("${marketplace.settlement.dispute-window-seconds:172800}") long disputeWindowSeconds) {
        this.workflowClient = workflowClient;
        if (daySeconds <= 0) {
            throw new IllegalArgumentException("marketplace.settlement.day-seconds must be positive");
        }
        this.daySeconds = daySeconds;
        // C11/D06：争议窗口下限，负配视作 0（关闭）。结算 Timer = max(T+N, 下限)。
        this.disputeWindowSeconds = Math.max(0, disputeWindowSeconds);
    }

    public Mono<String> start(Task task, TaskApplication application) {
        return start(task.id(), task.organizationId(), application);
    }

    public Mono<String> start(String taskId, String organizationId, TaskApplication application) {
        String workflowId = "settle-" + application.id();
        SettlementInput input = new SettlementInput(
                application.id(), taskId, application.reviewedByAccountId(), organizationId,
                application.bountyCents(),
                SettlementWindowPolicy.windowSeconds(application, daySeconds, disputeWindowSeconds));
        return Mono.fromCallable(() -> {
                    SettlementWindowWorkflow stub = workflowClient.newWorkflowStub(
                            SettlementWindowWorkflow.class,
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
                .onErrorResume(WorkflowExecutionAlreadyStarted.class, ignored -> Mono.just(workflowId));
    }
}
