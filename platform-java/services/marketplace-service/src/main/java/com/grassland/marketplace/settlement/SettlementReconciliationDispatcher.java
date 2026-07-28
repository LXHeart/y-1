package com.grassland.marketplace.settlement;

import com.grassland.marketplace.workflow.saga.ApplicationReservationWorkflowImpl;
import com.grassland.marketplace.workflow.saga.SettlementReconciliationWorkflow;
import com.grassland.marketplace.workflow.saga.SettlementReconciliationWorkflow.ReconciliationInput;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 结算对账派发器（Slice 7B）。轮询 dispatchable 对账请求，确定性地启动 {@link SettlementReconciliationWorkflow}。
 *
 * <p>关键：把「Kafka ACK」与「Temporal 启动」解耦——消费侧只保证 Inbox + 对账行已提交（ACK 即安全）；
 * 进程在 DB 提交后、workflow 启动前崩溃也不会丢业务事件，本派发器下轮把 pending 行重新派发。
 * 确定性 workflow id（{@code settlement-reconcile-<disputeId>}）+ {@code ALLOW_DUPLICATE_FAILED_ONLY} +
 * {@code WorkflowExecutionAlreadyStarted} 视作成功，使重复派发 / 多实例都安全。
 *
 * <p>派发体跑在 boundedElastic：{@code WorkflowClient.start} 是阻塞 gRPC，不能占住 @Scheduled 单线程饿死 outbox 发布器。
 */
@Component
@ConditionalOnProperty(prefix = "marketplace.reconciliation", name = "dispatcher-enabled",
        havingValue = "true", matchIfMissing = true)
public class SettlementReconciliationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SettlementReconciliationDispatcher.class);

    private final SettlementReconciliationRepository reconciliations;
    private final WorkflowClient workflowClient;
    private final SettlementReconciliationProperties props;

    public SettlementReconciliationDispatcher(
            SettlementReconciliationRepository reconciliations,
            WorkflowClient workflowClient,
            SettlementReconciliationProperties props) {
        this.reconciliations = reconciliations;
        this.workflowClient = workflowClient;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${marketplace.reconciliation.poll-ms:2000}")
    public void dispatch() {
        Mono.fromRunnable(this::dispatchBatch)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    void dispatchBatch() {
        List<SettlementReconciliation> rows =
                reconciliations.findDispatchable(props.batchSize()).collectList().block();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (SettlementReconciliation row : rows) {
            dispatchOne(row);
        }
    }

    private void dispatchOne(SettlementReconciliation row) {
        ReconciliationInput input = new ReconciliationInput(
                row.sourceEventId(), row.disputeId(), row.applicationId(), row.finalDecision());
        int attempt = row.dispatchAttempt() + 1;
        Duration redispatch = Duration.ofSeconds(props.redispatchSeconds());
        try {
            SettlementReconciliationWorkflow stub = workflowClient.newWorkflowStub(
                    SettlementReconciliationWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(row.workflowId())
                            .setTaskQueue(ApplicationReservationWorkflowImpl.TASK_QUEUE)
                            .setWorkflowIdReusePolicy(
                                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                            .build());
            WorkflowClient.start(stub::reconcile, input);
            reconciliations.markStarted(row.sourceEventId(), attempt, redispatch).block();
        } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
            reconciliations.markStarted(row.sourceEventId(), attempt, redispatch).block();
        } catch (RuntimeException failure) {
            log.warn("settlement reconcile dispatch failed src={} attempt={}",
                    row.sourceEventId(), attempt, failure);
            reconciliations.markStartFailed(
                    row.sourceEventId(), attempt, Duration.ofSeconds(props.startFailureBackoffSeconds())).block();
        }
    }
}
