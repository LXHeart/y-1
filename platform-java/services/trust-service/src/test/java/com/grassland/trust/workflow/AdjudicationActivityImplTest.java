package com.grassland.trust.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.grassland.trust.adjudication.AdjudicationProperties;
import com.grassland.trust.dispute.DisputeCase;
import com.grassland.trust.dispute.DisputeCaseRepository;
import com.grassland.trust.event.OutboxRepository;
import com.grassland.trust.judge.Judge;
import com.grassland.trust.judge.JudgeEligibilityService;
import com.grassland.trust.judge.JudgeRepository;
import com.grassland.trust.judge.VoteTally;
import com.grassland.trust.security.TrustException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link AdjudicationActivityImpl} 单元测试（草场 Epic 6 Slice 6C Phase C，Mockito）。
 * 覆盖 tallyVotes 映射、recordDecision 幂等、assignPanel 幂等短路、escalate/isFinal。
 *
 * <p>Slice 7C-2：activity 把「领域写 + outbox append」包进 {@code transactions.transactional(...)}。
 * 单测里把 {@link TransactionalOperator} 桩成<b>直通</b>（原样返回被包的 Mono），故断言不变；
 * 真实回滚由 {@code ActivityOutboxAtomicityIT}（testcontainers + spy outbox）证明。
 */
@SuppressWarnings("unchecked")
class AdjudicationActivityImplTest {

    private final DisputeCaseRepository disputes = mock(DisputeCaseRepository.class);
    private final JudgeRepository judges = mock(JudgeRepository.class);
    private final JudgeEligibilityService judgeEligibility = mock(JudgeEligibilityService.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final FinanceDecisionClient finance = mock(FinanceDecisionClient.class);
    private final TransactionalOperator transactions = mock(TransactionalOperator.class);
    private final AdjudicationActivityImpl activity = new AdjudicationActivityImpl(
            disputes, judges, judgeEligibility, outbox,
            new AdjudicationProperties(7, 24, 2, 48, 1, 48, 1, 168, 168, 60, 0, 0, 0, 0, 0),  // 秒级覆盖与发奖积分=0（关闭），用小时值
            finance, transactions);

    @BeforeEach
    void passThrough() {
        // 直通：transactional(mono) 原样返回被包的 Mono（本类无 MockitoExtension，未用到的桩不会报错）。
        when(transactions.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void tallyVotesAtomicallyRecordsMajorityDecision() {
        DisputeCase voting = dispute("d1", "voting");
        DisputeCase decided = dispute("d1", "decided");
        when(disputes.findByIdForUpdate("d1")).thenReturn(Mono.just(voting));
        when(judges.tallyVotes("d1", 1)).thenReturn(Mono.just(new VoteTally(5, 2, 0, 7)));
        when(disputes.recordDecision("d1", "for_merchant")).thenReturn(Mono.just(decided));
        when(outbox.append(any())).thenReturn(Mono.empty());

        assertThat(activity.tallyVotes("d1", 1)).isEqualTo(
                TallyResult.decided(5, 2, 0, 7, "for_merchant"));
        verify(disputes).recordDecision("d1", "for_merchant");
        verify(outbox).append(any());
    }

    @Test
    void tallyVotesAtomicallyAdvancesAnUndecidedRound() {
        DisputeCase voting = dispute("d1", "voting");
        when(disputes.findByIdForUpdate("d1")).thenReturn(Mono.just(voting));
        when(judges.tallyVotes("d1", 1)).thenReturn(Mono.just(new VoteTally(3, 3, 1, 7)));
        when(disputes.reopen("d1", 2)).thenReturn(Mono.just(voting));

        TallyResult tie = activity.tallyVotes("d1", 1);
        assertThat(tie.decided()).isFalse();
        assertThat(tie.winner()).isNull();
        verify(disputes).reopen("d1", 2);
    }

    @Test
    void tallyVotesAtomicallyEscalatesAnUndecidedFinalRound() {
        DisputeCase voting = dispute("d1", "voting");
        when(disputes.findByIdForUpdate("d1")).thenReturn(Mono.just(voting));
        when(judges.tallyVotes("d1", 1)).thenReturn(Mono.just(new VoteTally(3, 3, 1, 7)));
        when(disputes.markEscalated("d1")).thenReturn(Mono.just(voting));
        when(outbox.append(any())).thenReturn(Mono.empty());

        TallyResult tie = activity.closeVotingRound("d1", 1, true);

        assertThat(tie.decided()).isFalse();
        verify(disputes).markEscalated("d1");
        verify(outbox).append(any());
        verify(disputes, never()).reopen(anyString(), anyInt());
    }

    @Test
    void recordDecisionWritesOutboxWhenVoting() {
        DisputeCase decided = dispute("d1", "voting");
        when(disputes.recordDecision("d1", "for_merchant")).thenReturn(Mono.just(decided));
        when(outbox.append(any())).thenReturn(Mono.empty());

        activity.recordDecision("d1", "for_merchant");

        verify(disputes).recordDecision("d1", "for_merchant");
        verify(outbox).append(any());
    }

    @Test
    void recordDecisionIdempotentWhenNotVoting() {
        when(disputes.recordDecision(eq("d1"), anyString())).thenReturn(Mono.empty());  // 非 voting → empty

        activity.recordDecision("d1", "for_merchant");

        verify(outbox, never()).append(any());  // 幂等：不重发事件
    }

    @Test
    void assignPanelSkipsWhenPanelAlreadyAssigned() {
        when(disputes.findById("d1")).thenReturn(Mono.just(dispute("d1", "voting")));
        when(judges.countPanel("d1", 1)).thenReturn(Mono.just(7));  // 面板已存在

        activity.assignPanel("d1", 1);

        verify(judges, never()).drawEligiblePool(anyInt(), anyString(), anyInt());  // 不重新抽签
        verify(judges, never()).assignPanel(anyString(), anyInt(), any());
        verify(outbox, never()).append(any());
    }

    @Test
    void assignPanelRejectsPartialConditionalInsert() {
        DisputeCase open = dispute("d1", "open");
        DisputeCase voting = dispute("d1", "voting");
        List<Judge> pool = java.util.stream.IntStream.range(0, 7)
                .mapToObj(ignored -> judge()).toList();
        when(disputes.findById("d1")).thenReturn(Mono.just(open), Mono.just(voting));
        when(judges.countPanel("d1", 1)).thenReturn(Mono.just(0));
        when(judges.findPanelAccountIds("d1", 1)).thenReturn(Flux.empty());
        when(judges.lockPanel("d1", 1)).thenReturn(Mono.empty());
        when(judgeEligibility.drawVerifiedPool(eq(1), anyString(), eq(7), anySet()))
                .thenReturn(Mono.just(pool));
        when(judgeEligibility.validateNoOrganizationConflicts(any(), anyString())).thenReturn(Mono.empty());
        when(disputes.startAdjudication("d1", 1)).thenReturn(Mono.just(voting));
        when(judges.assignPanel(eq("d1"), eq(1), any())).thenReturn(Mono.just(6));
        when(outbox.append(any())).thenReturn(Mono.empty());

        assertThatThrownBy(() -> activity.assignPanel("d1", 1))
                .isInstanceOf(TrustException.class);
        verify(outbox, never()).append(any());
    }

    @Test
    void assignPanelSkipsWhenConcurrentRequestCompletesPanelUnderLock() {
        DisputeCase open = dispute("d1", "open");
        List<Judge> pool = java.util.stream.IntStream.range(0, 7)
                .mapToObj(ignored -> judge()).toList();
        List<String> completedAccounts = pool.stream().map(Judge::accountId).toList();
        when(disputes.findById("d1")).thenReturn(Mono.just(open));
        when(judges.countPanel("d1", 1)).thenReturn(Mono.just(0));
        when(judges.findPanelAccountIds("d1", 1))
                .thenReturn(Flux.empty(), Flux.fromIterable(completedAccounts));
        when(judges.lockPanel("d1", 1)).thenReturn(Mono.empty());
        when(judgeEligibility.drawVerifiedPool(eq(1), anyString(), eq(7), anySet()))
                .thenReturn(Mono.just(pool));
        when(judgeEligibility.validateNoOrganizationConflicts(any(), anyString())).thenReturn(Mono.empty());

        activity.assignPanel("d1", 1);

        verify(disputes, never()).startAdjudication(anyString(), anyInt());
        verify(judges, never()).assignPanel(anyString(), anyInt(), any());
        verify(outbox, never()).append(any());
    }

    @Test
    void assignPanelRevalidatesIdentityMembershipsImmediatelyBeforeWrite() {
        DisputeCase open = dispute("d1", "open");
        List<Judge> pool = java.util.stream.IntStream.range(0, 7)
                .mapToObj(ignored -> judge()).toList();
        when(disputes.findById("d1")).thenReturn(Mono.just(open));
        when(judges.countPanel("d1", 1)).thenReturn(Mono.just(0));
        when(judges.findPanelAccountIds("d1", 1)).thenReturn(Flux.empty());
        when(judgeEligibility.drawVerifiedPool(eq(1), eq(open.organizationId()), eq(7), anySet()))
                .thenReturn(Mono.just(pool));
        List<String> poolAccounts = pool.stream().map(Judge::accountId).toList();
        when(judgeEligibility.validateNoOrganizationConflicts(poolAccounts, open.organizationId()))
                .thenReturn(Mono.error(new TrustException(503, "身份服务暂时不可用")));

        assertThatThrownBy(() -> activity.assignPanel("d1", 1))
                .isInstanceOf(TrustException.class)
                .hasMessage("身份服务暂时不可用");
        verify(disputes, never()).startAdjudication(anyString(), anyInt());
        verify(judges, never()).assignPanel(anyString(), anyInt(), any());
        verify(transactions, never()).transactional(any(Mono.class));
    }

    @Test
    void isFinalAndEscalate() {
        when(disputes.findById("d1")).thenReturn(Mono.just(dispute("d1", "final")));
        assertThat(activity.isFinal("d1")).isTrue();

        when(disputes.markEscalated("d2")).thenReturn(Mono.just(dispute("d2", "voting")));
        when(outbox.append(any())).thenReturn(Mono.empty());
        activity.escalate("d2");
        verify(disputes).markEscalated("d2");
        verify(outbox).append(any());
    }

    // ---------- releaseHoldAndApplyDecision 矩阵（Phase D / D-06）----------

    @Test
    void merchantFavorReleasesWhenReserved() {
        when(disputes.findById("d1")).thenReturn(Mono.just(finalDispute("d1", "for_merchant")));
        when(finance.releaseIfReserved("org-1", "eng-d1")).thenReturn(Mono.just(true));
        activity.releaseHoldAndApplyDecision("d1");
        verify(finance).releaseIfReserved("org-1", "eng-d1");
        verify(finance, never()).reverseIfCaptured(anyString(), anyString());
    }

    @Test
    void merchantFavorReversesWhenNotReserved() {
        when(disputes.findById("d1")).thenReturn(Mono.just(finalDispute("d1", "for_merchant")));
        when(finance.releaseIfReserved("org-1", "eng-d1")).thenReturn(Mono.just(false));  // 非 reserved
        when(finance.reverseIfCaptured("org-1", "eng-d1")).thenReturn(Mono.just(true));
        activity.releaseHoldAndApplyDecision("d1");
        verify(finance).reverseIfCaptured("org-1", "eng-d1");
    }

    @Test
    void recommenderFavorCaptures() {
        when(disputes.findById("d1")).thenReturn(Mono.just(finalDispute("d1", "for_recommender")));
        when(finance.captureIfReserved("org-1", "eng-d1")).thenReturn(Mono.just(true));
        activity.releaseHoldAndApplyDecision("d1");
        verify(finance).captureIfReserved("org-1", "eng-d1");
        verify(finance, never()).releaseIfReserved(anyString(), anyString());
    }

    @Test
    void holdReleaseSkipsNonFinal() {
        when(disputes.findById("d1")).thenReturn(Mono.just(dispute("d1", "voting")));  // 非 final
        activity.releaseHoldAndApplyDecision("d1");
        verifyNoInteractions(finance);
    }

    private DisputeCase finalDispute(String id, String decision) {
        return new DisputeCase(id, "eng-" + id, "org-1", UUID.randomUUID().toString(), "merchant",
                "final", "未履约", decision, null, null, null, 1, 2L, "none", decision, null, null, "standard");
    }


    // ---------- 任务书 #31 / ADR-D15：审判官投票奖励事件 ----------

    @Test
    void tallyVotesEmitsPerVoterRewardsWithDeterministicEventIds() {
        // credits=20 开发奖：多数票终局 → 已投 3 名审判官各发一条 JudgeVoteRewarded（同事务链内），
        // 事件与 DisputeDecided 都经 outbox（回滚即都不发）。
        AdjudicationActivityImpl rewarding = new AdjudicationActivityImpl(
                disputes, judges, judgeEligibility, outbox,
                new AdjudicationProperties(7, 24, 2, 48, 1, 48, 1, 168, 168, 60, 0, 0, 0, 0, 20),
                finance, transactions);
        DisputeCase voting = dispute("d1", "voting");
        DisputeCase decided = dispute("d1", "decided");
        when(disputes.findByIdForUpdate("d1")).thenReturn(Mono.just(voting));
        when(judges.tallyVotes("d1", 1)).thenReturn(Mono.just(new VoteTally(5, 2, 0, 7)));
        when(disputes.recordDecision("d1", "for_merchant")).thenReturn(Mono.just(decided));
        when(judges.findVoterAccountIds("d1", 1)).thenReturn(Flux.just("j-1", "j-2", "j-3"));
        when(outbox.append(any())).thenReturn(Mono.empty());

        assertThat(rewarding.tallyVotes("d1", 1)).isEqualTo(
                TallyResult.decided(5, 2, 0, 7, "for_merchant"));

        java.util.List<com.grassland.trust.event.EventEnvelope> appended = new java.util.ArrayList<>();
        when(outbox.append(any())).thenAnswer(inv -> {
            appended.add(inv.getArgument(0));
            return Mono.empty();
        });
        rewarding.tallyVotes("d1", 1);
        long rewards = appended.stream()
                .filter(e -> "JudgeVoteRewarded".equals(e.eventType())).count();
        assertThat(rewards).isEqualTo(3);
        assertThat(appended.stream().anyMatch(e -> "DisputeDecided".equals(e.eventType()))).isTrue();
        com.grassland.trust.event.EventEnvelope reward = appended.stream()
                .filter(e -> "JudgeVoteRewarded".equals(e.eventType())).findFirst().orElseThrow();
        assertThat(reward.payload().get("credits")).isEqualTo(20);
        assertThat(reward.payload().get("round")).isEqualTo(1);
        assertThat(reward.payload().get("judgeAccountId")).isIn("j-1", "j-2", "j-3");
        assertThat(reward.payload().get("disputeId")).isEqualTo("d1");
    }

    @Test
    void rewardsDisabledWhenCreditsZeroAndUnvotedRoundsGetNoEvents() {
        // credits=0（默认关闭）：不发任何奖励事件；未投票轮（voters 空）也零事件。
        DisputeCase voting = dispute("d1", "voting");
        when(disputes.findByIdForUpdate("d1")).thenReturn(Mono.just(voting));
        when(judges.tallyVotes("d1", 1)).thenReturn(Mono.just(new VoteTally(5, 2, 0, 7)));
        when(disputes.recordDecision("d1", "for_merchant")).thenReturn(Mono.just(dispute("d1", "decided")));
        when(outbox.append(any())).thenReturn(Mono.empty());

        activity.tallyVotes("d1", 1);
        verify(outbox, org.mockito.Mockito.times(1)).append(any());  // 只有 DisputeDecided
    }

    @Test
    void recordDecisionEmitsRewardsForCurrentRoundVoters() {
        // recordDecision（投票窗到期终局路径）同口径：该轮实际投票者获奖。
        AdjudicationActivityImpl rewarding = new AdjudicationActivityImpl(
                disputes, judges, judgeEligibility, outbox,
                new AdjudicationProperties(7, 24, 2, 48, 1, 48, 1, 168, 168, 60, 0, 0, 0, 0, 20),
                finance, transactions);
        DisputeCase decided = dispute("d1", "decided");
        when(disputes.recordDecision("d1", "for_recommender")).thenReturn(Mono.just(decided));
        when(judges.findVoterAccountIds("d1", decided.round())).thenReturn(Flux.just("j-9"));
        when(outbox.append(any())).thenReturn(Mono.empty());

        rewarding.recordDecision("d1", "for_recommender");

        verify(outbox, org.mockito.Mockito.times(2)).append(any());  // 1 奖励 + 1 DisputeDecided
    }

    private DisputeCase dispute(String id, String status) {
        return new DisputeCase(id, "eng-" + id, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "merchant", status, "未履约", null, null, null, null, 1, 1L, "none", null, null, null, "standard");
    }

    private Judge judge() {
        return new Judge(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, 5,
                true, true, 1L, Instant.now(), UUID.randomUUID().toString(), Instant.now());
    }
}
