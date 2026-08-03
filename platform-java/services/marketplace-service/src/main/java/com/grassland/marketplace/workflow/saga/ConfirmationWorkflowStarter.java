package com.grassland.marketplace.workflow.saga;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * D-03 确认窗口 workflow 启动器。Controller 首次启动与 {@link ConfirmationWindowDispatcher} 补启共用，
 * 确保 workflowId / task queue / reuse policy 不漂移。
 *
 * <p>{@code workflowId = confirm-<submissionId>}：退回重交会有新 submission 与新窗口；旧 Timer 绑定旧 submission，
 * 到期重验非 submitted 后 abort。重复启动 / 多副本 dispatcher 由确定性 ID +
 * {@code ALLOW_DUPLICATE_FAILED_ONLY} + {@link WorkflowExecutionAlreadyStarted} 幂等收敛。
 */
@Component
public class ConfirmationWorkflowStarter {

    private final WorkflowClient workflowClient;

    public ConfirmationWorkflowStarter(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    public Mono<String> start(String applicationId, String submissionId,
                              String organizationId, long windowSeconds) {
        String workflowId = workflowId(submissionId);
        ConfirmationInput input = new ConfirmationInput(
                applicationId, submissionId, organizationId, Math.max(0, windowSeconds));
        return Mono.fromCallable(() -> {
            ConfirmationWindowWorkflow stub = workflowClient.newWorkflowStub(
                    ConfirmationWindowWorkflow.class,
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
        .onErrorResume(WorkflowExecutionAlreadyStarted.class, alreadyStarted -> Mono.just(workflowId));
    }

    static String workflowId(String submissionId) {
        return "confirm-" + submissionId;
    }
}
