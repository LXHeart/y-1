package com.grassland.trust.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * 争议审判 workflow 实现（草场 Epic 6 Slice 6C Phase C + 任务书 #74 卡 B/C 小法庭结构）。task-queue
 * {@code trust-adjudication}（application.yml 显式 worker）。
 *
 * <p>实现类<b>不加</b> {@code @Component}（同 marketplace SettlementWindowWorkflowImpl）——Temporal Worker 在 workflow
 * 线程每执行 new 一个实例，{@code @WorkflowImpl} 仅作 auto-discovery marker；若成 Spring 单例会在非 workflow 线程
 * 实例化，致字段初始化里的 {@code Workflow.newActivityStub} 抛 "non workflow thread"。
 *
 * <p>任务书 #74：卡 B 把「手动开庭+固定等待窗」改为 workflow 前置<b>质证段</b>（concludeEvidence 信号提前醒）；
 * 卡 C 把裸 sleep(voteWindow) 改为 {@code await(窗口到点 || concludeEarly)}——抢先 4/7 达票即时收尾，
 * 满窗 tally 兜底（信号丢失不影响正确性）。
 */
@WorkflowImpl(taskQueues = DisputeAdjudicationWorkflowImpl.TASK_QUEUE)
public class DisputeAdjudicationWorkflowImpl implements DisputeAdjudicationWorkflow {

    public static final String TASK_QUEUE = "trust-adjudication";

    private final AdjudicationActivity activity = Workflow.newActivityStub(
            AdjudicationActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofMillis(500))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    private boolean evidenceConcluded;
    private boolean concludedEarly;

    @Override
    public void concludeEvidence() {
        this.evidenceConcluded = true;
    }

    @Override
    public void concludeEarly() {
        this.concludedEarly = true;
    }

    @Override
    public void run(AdjudicationInput input) {
        awaitEvidencePhase(input);
        boolean decided = runVotingLoop(input);
        if (!decided) {
            activity.escalate(input.disputeId());
        } else {
            Workflow.sleep(Duration.ofSeconds(Math.max(0, input.appealWindowSeconds())));
        }
        awaitResolution(input);
        activity.releaseHoldAndApplyDecision(input.disputeId());  // 终局判决 → 钱侧分派（D-06 矩阵）
        activity.publishFinalStatus(input.disputeId());
    }

    /**
     * 任务书 #74 卡 B：举证质证段。evidenceWindowSeconds=0（禁用哨兵/测试）或 evidencePhase=false
     * （retrial 重启等已开庭 run）直接跳过；否则等「双方质证完毕 signal」或窗口到点——
     * 真正的状态迁移（evidence→voting）在 assignPanel activity 内（guarded UPDATE 幂等）。
     * Temporal 确定性：必须 newTimer 挂真实 Timer（await 里的 currentTimeMillis 条件不会自醒），
     * signal 提前醒 = await 谓词；test env 时间快进依赖真实 Timer。
     */
    private void awaitEvidencePhase(AdjudicationInput input) {
        if (!input.evidencePhase() || input.evidenceWindowSeconds() <= 0) {
            return;
        }
        Promise<Void> window = Workflow.newTimer(Duration.ofSeconds(input.evidenceWindowSeconds()));
        Workflow.await(() -> evidenceConcluded || window.isCompleted());
    }

    /** 多轮投票循环：每轮 assignPanel→await(投票窗 Timer 或抢先达票 signal)→tally；多数决则 recordDecision 并返回 true。 */
    private boolean runVotingLoop(AdjudicationInput input) {
        int lastRound = input.startRound() + Math.max(1, input.maxRounds()) - 1;
        for (int round = input.startRound(); round <= lastRound; round++) {
            activity.assignPanel(input.disputeId(), round);
            // 卡 C（D2 抢先达票）：真实 Timer + concludeEarly 信号双路唤醒；满窗 tally 兜底。
            Promise<Void> window = Workflow.newTimer(Duration.ofSeconds(Math.max(0, input.voteWindowSeconds())));
            Workflow.await(() -> concludedEarly || window.isCompleted());
            TallyResult tally = activity.tallyVotes(input.disputeId(), round);
            if (tally.decided()) {
                activity.recordDecision(input.disputeId(), tally.winner());
                return true;
            }
            // 平票/不足 -> 下一轮（tallyVotes 已原子推进状态，assignPanel(round+1) 完成抽签）
            concludedEarly = false;
        }
        return false;  // 超 maxRounds 无判决
    }

    /** 未终局时：有 appeal/escalation → 轮询等客服终审；否则用面板判决 finalize。 */
    private void awaitResolution(AdjudicationInput input) {
        if (activity.isFinal(input.disputeId())) {
            return;
        }
        if (activity.hasAppealOrEscalation(input.disputeId())) {
            long deadline = Workflow.currentTimeMillis() + Math.max(0, input.csAwaitSeconds()) * 1000L;
            long step = Math.max(1, input.csPollSeconds());
            while (Workflow.currentTimeMillis() < deadline && !activity.isFinal(input.disputeId())) {
                Workflow.sleep(Duration.ofSeconds(step));
            }
        } else {
            activity.applyPanelDecision(input.disputeId());
        }
    }
}
