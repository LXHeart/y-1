package com.grassland.trust.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * 争议审判 workflow 实现（草场 Epic 6 Slice 6C Phase C）。task-queue {@code trust-adjudication}（application.yml 显式 worker）。
 *
 * <p>实现类<b>不加</b> {@code @Component}（同 marketplace SettlementWindowWorkflowImpl）——Temporal Worker 在 workflow
 * 线程每执行 new 一个实例，{@code @WorkflowImpl} 仅作 auto-discovery marker；若成 Spring 单例会在非 workflow 线程
 * 实例化，致字段初始化里的 {@code Workflow.newActivityStub} 抛 "non workflow thread"。
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

    @Override
    public void run(AdjudicationInput input) {
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

    /** 多轮投票循环：每轮 assignPanel→sleep(voteWindow)→tally；多数决则 recordDecision 并返回 true。 */
    private boolean runVotingLoop(AdjudicationInput input) {
        for (int round = 1; round <= input.maxRounds(); round++) {
            activity.assignPanel(input.disputeId(), round);
            Workflow.sleep(Duration.ofSeconds(Math.max(0, input.voteWindowSeconds())));
            TallyResult tally = activity.tallyVotes(input.disputeId(), round);
            if (tally.decided()) {
                activity.recordDecision(input.disputeId(), tally.winner());
                return true;
            }
            // 平票/不足 → 下一轮（assignPanel(round+1) 内会 reopen）
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
