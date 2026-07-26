package com.grassland.trust.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 用 {@link TestWorkflowEnvironment}（replay 引擎）验证 {@link DisputeAdjudicationWorkflowImpl} 编排
 * （草场 Epic 6 Slice 6C Phase C）：多轮投票 / 平票重开 / 超 maxRounds 升级客服 / 上诉等客服终审 / 无上诉 finalize。
 * 纯单元测试，不启动 Spring 上下文；Timer 在 test env 自动推进不实等。
 */
class DisputeAdjudicationWorkflowReplayTest {

    private TestWorkflowEnvironment env;
    private FakeActivity activity;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(DisputeAdjudicationWorkflowImpl.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(DisputeAdjudicationWorkflowImpl.class);
        activity = new FakeActivity();
        worker.registerActivitiesImplementations(activity);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void decidesOnMajorityThenFinalizesNoAppeal() {
        activity.tallyByRound.put(1, TallyResult.decided(5, 2, 0, 7, "for_merchant"));
        activity.hasAppealOrEscalation = false;

        run(input(2));

        assertThat(activity.rounds).containsExactly(1);
        assertThat(activity.recordedDecision).isEqualTo("for_merchant");
        assertThat(activity.escalated).isFalse();
        assertThat(activity.appliedPanelDecision).isTrue();  // 无上诉 → 面板判决 finalize
        assertThat(activity.published).isTrue();
    }

    @Test
    void reopensOnTieThenDecidesRoundTwo() {
        activity.tallyByRound.put(1, TallyResult.undecided(3, 3, 1, 7));
        activity.tallyByRound.put(2, TallyResult.decided(2, 5, 0, 7, "for_recommender"));
        activity.hasAppealOrEscalation = false;

        run(input(2));

        assertThat(activity.rounds).containsExactly(1, 2);
        assertThat(activity.recordedDecision).isEqualTo("for_recommender");
        assertThat(activity.appliedPanelDecision).isTrue();
    }

    @Test
    void escalatesWhenNoDecisionAfterMaxRounds() {
        activity.tallyByRound.put(1, TallyResult.undecided(3, 3, 1, 7));
        activity.tallyByRound.put(2, TallyResult.undecided(2, 2, 3, 7));
        activity.hasAppealOrEscalation = true;   // escalated → 等客服
        activity.finalAfterPolls = 2;             // 客服终审后 isFinal 变 true

        run(input(2));

        assertThat(activity.rounds).containsExactly(1, 2);
        assertThat(activity.escalated).isTrue();
        assertThat(activity.recordedDecision).isNull();
        assertThat(activity.appliedPanelDecision).isFalse();  // 走客服路径，不 applyPanelDecision
        assertThat(activity.published).isTrue();
    }

    @Test
    void awaitsCustomerServiceOnAppeal() {
        activity.tallyByRound.put(1, TallyResult.decided(5, 2, 0, 7, "for_merchant"));
        activity.hasAppealOrEscalation = true;   // 判决后上诉 → 等客服
        activity.finalAfterPolls = 3;

        run(input(2));

        assertThat(activity.recordedDecision).isEqualTo("for_merchant");
        assertThat(activity.appliedPanelDecision).isFalse();  // 上诉 → 客服 finalize，不 applyPanelDecision
        assertThat(activity.published).isTrue();
    }

    private void run(AdjudicationInput in) {
        WorkflowClient client = env.getWorkflowClient();
        DisputeAdjudicationWorkflow stub = client.newWorkflowStub(
                DisputeAdjudicationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(DisputeAdjudicationWorkflowImpl.TASK_QUEUE)
                        .build());
        stub.run(in);
    }

    private AdjudicationInput input(int maxRounds) {
        return new AdjudicationInput("11111111-1111-1111-1111-111111111111",
                0L, 0L, maxRounds, 60L, 1L);
    }

    /** 可控 fake activity——测试按分支配置返回值并记录调用。 */
    static final class FakeActivity implements AdjudicationActivity {
        final Map<Integer, TallyResult> tallyByRound = new HashMap<>();
        final List<Integer> rounds = new ArrayList<>();
        String recordedDecision;
        boolean escalated;
        boolean appliedPanelDecision;
        boolean heldReleased;
        boolean published;
        boolean hasAppealOrEscalation;
        int finalAfterPolls = Integer.MAX_VALUE;  // 默认 isFinal 恒 false
        int isFinalCalls;

        @Override
        public void assignPanel(String disputeId, int round) {
            rounds.add(round);
        }

        @Override
        public TallyResult tallyVotes(String disputeId, int round) {
            return tallyByRound.getOrDefault(round, TallyResult.undecided(0, 0, 0, 0));
        }

        @Override
        public void recordDecision(String disputeId, String winner) {
            recordedDecision = winner;
        }

        @Override
        public void escalate(String disputeId) {
            escalated = true;
        }

        @Override
        public boolean isFinal(String disputeId) {
            isFinalCalls++;
            return isFinalCalls > finalAfterPolls;
        }

        @Override
        public boolean hasAppealOrEscalation(String disputeId) {
            return hasAppealOrEscalation;
        }

        @Override
        public void applyPanelDecision(String disputeId) {
            appliedPanelDecision = true;
        }

        @Override
        public void releaseHoldAndApplyDecision(String disputeId) {
            heldReleased = true;
        }

        @Override
        public void publishFinalStatus(String disputeId) {
            published = true;
        }
    }
}
