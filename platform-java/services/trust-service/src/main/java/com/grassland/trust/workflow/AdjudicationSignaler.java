package com.grassland.trust.workflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 审判 workflow 信号器（任务书 #74 卡 B 质证完毕提前开庭 / 卡 C 抢先达票提前收尾）。
 *
 * <p>按固定 workflowId 取既有 run 的 stub 发信号。信号失败（workflow 已结束/不存在/Temporal 不可用）
 * 一律吞掉仅 WARN——满窗 tally 与窗口到点开庭是兜底双保险，信号只是加速器，不构成正确性依赖。
 * Reactor 侧 boundedElastic 包裹（WorkflowClient 是同步阻塞 API，禁在 event-loop 调）。
 */
@Component
public class AdjudicationSignaler {

    private static final Logger log = LoggerFactory.getLogger(AdjudicationSignaler.class);

    private final WorkflowClient workflowClient;

    public AdjudicationSignaler(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    /** 卡 B：双方质证完毕 → 提前开庭。 */
    public Mono<Void> concludeEvidence(String disputeId) {
        return signal(disputeId, "concludeEvidence", stub -> {
            DisputeAdjudicationWorkflow wf = workflowClient.newWorkflowStub(
                    DisputeAdjudicationWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId("adjudicate-" + disputeId)
                            .setTaskQueue(DisputeAdjudicationWorkflowImpl.TASK_QUEUE)
                            .setWorkflowIdReusePolicy(
                                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                            .build());
            wf.concludeEvidence();
            return null;
        });
    }

    /** 卡 C（D2）：抢先 4/7 达票已翻 decided → workflow 跳过剩余投票窗。 */
    public Mono<Void> concludeEarly(String disputeId) {
        return signal(disputeId, "concludeEarly", stub -> {
            DisputeAdjudicationWorkflow wf = workflowClient.newWorkflowStub(
                    DisputeAdjudicationWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId("adjudicate-" + disputeId)
                            .setTaskQueue(DisputeAdjudicationWorkflowImpl.TASK_QUEUE)
                            .setWorkflowIdReusePolicy(
                                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                            .build());
            wf.concludeEarly();
            return null;
        });
    }

    private Mono<Void> signal(String disputeId, String name, java.util.function.Function<Void, Void> call) {
        return Mono.<Void>fromRunnable(() -> {
            try {
                call.apply(null);
            } catch (Exception e) {
                // 信号到已完成/不存在 workflow 的投递异常一律吞掉（任务书卡 C：满窗兜底双保险）。
                log.info("adjudication signal {} not delivered disputeId={} reason={}", name, disputeId,
                        e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(e -> {
            log.warn("adjudication signal {} failed disputeId={}", name, disputeId, e);
            return Mono.empty();
        }).then();
    }
}
