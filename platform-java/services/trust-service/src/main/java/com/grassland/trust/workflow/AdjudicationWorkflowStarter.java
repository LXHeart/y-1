package com.grassland.trust.workflow;

import com.grassland.trust.adjudication.AdjudicationProperties;
import com.grassland.trust.dispute.DisputeCase;
import com.grassland.trust.dispute.DisputeCaseRepository;
import com.grassland.trust.dispute.DisputeCaseStatus;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 七官审判 workflow 共享启动器：HTTP 手动启动 / court 通道开争议自动启动（任务书 #74 卡 B）/
 * cs_direct SLA workflow（卡 A）/ 发回重审重启（卡 F）共用。
 *
 * <p>入参按争议当前状态折算（workflow 内不读 env/DB）：受理期（open|evidence）court 新案 evidencePhase=true；
 * 发回重审重启 evidencePhase=false 且 startRound=新轮次（老 run 已 terminate，卡 F）。
 */
@Component
public class AdjudicationWorkflowStarter {

    private static final Logger log = LoggerFactory.getLogger(AdjudicationWorkflowStarter.class);

    private final WorkflowClient workflowClient;
    private final AdjudicationProperties props;
    private final DisputeCaseRepository disputes;

    public AdjudicationWorkflowStarter(WorkflowClient workflowClient, AdjudicationProperties props,
                                       DisputeCaseRepository disputes) {
        this.workflowClient = workflowClient;
        this.props = props;
        this.disputes = disputes;
    }

    /** 主审判 workflow：按争议当前态折算质证段与起始轮。 */
    public Mono<String> start(String disputeId) {
        return disputes.findById(disputeId).flatMap(d -> startWithState(d));
    }

    private Mono<String> startWithState(DisputeCase d) {
        String workflowId = "adjudicate-" + d.id();
        boolean pending = DisputeCaseStatus.isEvidencePending(d.status());
        // 卡 B：court 通道受理期新启 run 带质证段；cs_direct/merchant_rejection/已开庭补启不带。
        boolean evidencePhase = pending && "court".equals(d.effectiveChannel());
        long evidenceWindow = evidencePhase ? Math.max(0, props.evidenceWindowSecondsEffective()) : 0;
        int startRound = Math.max(1, d.round());
        if (pending) {
            startRound = 1; // 受理期首启从第 1 轮开始
        }
        AdjudicationInput input = new AdjudicationInput(
                d.id(), props.voteWindowSecondsEffective(), props.appealWindowSecondsEffective(),
                props.maxRounds(), Math.max(0, props.csAwaitHours()) * 3600L,
                Math.max(1, props.csPollSeconds()), evidencePhase, evidenceWindow, startRound);
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

    /** 卡 A：cs_direct SLA workflow（固定 id cs-direct-sla-{disputeId}；重复启动幂等吞并）。 */
    public Mono<String> startCsSla(String disputeId, long slaSeconds) {
        if (slaSeconds <= 0) {
            return Mono.empty(); // SLA 禁用哨兵（测试）：不启 workflow，不落 cs_due_at
        }
        String workflowId = "cs-direct-sla-" + disputeId;
        CsDirectSlaInput input = new CsDirectSlaInput(disputeId, slaSeconds);
        return Mono.fromCallable(() -> {
            CsDirectSlaWorkflow stub = workflowClient.newWorkflowStub(
                    CsDirectSlaWorkflow.class,
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

    /**
     * 卡 F：发回重审重启。上一 run 多停在「等客服终审」轮询段（retrial 只对 appealed 案开放），
     * 必须先 terminate（固定 workflowId 在 ALLOW_DUPLICATE_FAILED_ONLY 下 completed run 不允许重启）；
     * terminate 不存在/已结束的 run 的异常吞掉。新 run evidencePhase=false、startRound=新轮次。
     */
    public Mono<String> restartForRetrial(String disputeId) {
        return disputes.findById(disputeId).flatMap(d -> Mono
                .<Void>fromCallable(() -> {
                    try {
                        WorkflowStub untyped = workflowClient.newUntypedWorkflowStub("adjudicate-" + disputeId);
                        untyped.terminate("cs-retrial");
                    } catch (Exception e) {
                        log.debug("retrial terminate skipped disputeId={} reason={}", disputeId, e.getMessage());
                    }
                    return null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> Mono.empty())
                .then(startWithState(d)));
    }
}
