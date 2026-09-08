package com.grassland.marketplace.workflow.saga;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.time.Instant;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 交付看门狗 workflow 启动器（任务书 #96 C96-01）。派发器专用（accept 首启也走派发器补扫，
 * durable intent = {@code delivery_workflow_started_at IS NULL}）。
 *
 * <p>{@code workflowId = delivery-<applicationId>-<remedyEndEpochSecond>}：终结线 epoch 烧进 ID——
 * 延期批准后移截止 ⇒ 新 ID 新 workflow（旧实例到点被行级守卫 abort）；同截止重复派发 / 多副本
 * 由确定性 ID + {@code ALLOW_DUPLICATE_FAILED_ONLY} + {@link WorkflowExecutionAlreadyStarted} 幂等收敛。
 */
@Component
public class DeliveryDeadlineWorkflowStarter {

    private final WorkflowClient workflowClient;

    public DeliveryDeadlineWorkflowStarter(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    public Mono<String> start(String applicationId, String taskId, String organizationId,
            Instant remedyDeadlineAt, long remainingToDeadlineSeconds, long remainingToRemedyEndSeconds,
            long reminderLeadSeconds) {
        String workflowId = workflowId(applicationId, remedyDeadlineAt);
        DeliveryDeadlineInput input = new DeliveryDeadlineInput(applicationId, taskId, organizationId,
                remainingToDeadlineSeconds, remainingToRemedyEndSeconds, reminderLeadSeconds);
        return Mono.fromCallable(() -> {
            DeliveryDeadlineWorkflow stub = workflowClient.newWorkflowStub(
                    DeliveryDeadlineWorkflow.class,
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

    static String workflowId(String applicationId, Instant remedyDeadlineAt) {
        return "delivery-" + applicationId + "-" + remedyDeadlineAt.getEpochSecond();
    }
}
