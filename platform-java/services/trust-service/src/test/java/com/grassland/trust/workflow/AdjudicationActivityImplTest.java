package com.grassland.trust.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.trust.adjudication.AdjudicationProperties;
import com.grassland.trust.dispute.DisputeCase;
import com.grassland.trust.dispute.DisputeCaseRepository;
import com.grassland.trust.event.OutboxRepository;
import com.grassland.trust.judge.JudgeRepository;
import com.grassland.trust.judge.VoteTally;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * {@link AdjudicationActivityImpl} 单元测试（草场 Epic 6 Slice 6C Phase C，Mockito）。
 * 覆盖 tallyVotes 映射、recordDecision 幂等、assignPanel 幂等短路、escalate/isFinal。
 */
class AdjudicationActivityImplTest {

    private final DisputeCaseRepository disputes = mock(DisputeCaseRepository.class);
    private final JudgeRepository judges = mock(JudgeRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final AdjudicationActivityImpl activity = new AdjudicationActivityImpl(
            disputes, judges, outbox, new AdjudicationProperties(7, 24, 2, 48, 1, 168, 60));

    @Test
    void tallyVotesMapsMajorityAndTie() {
        when(judges.tallyVotes("d1", 1)).thenReturn(Mono.just(new VoteTally(5, 2, 0, 7)));
        assertThat(activity.tallyVotes("d1", 1)).isEqualTo(
                TallyResult.decided(5, 2, 0, 7, "for_merchant"));

        when(judges.tallyVotes("d1", 2)).thenReturn(Mono.just(new VoteTally(3, 3, 1, 7)));
        TallyResult tie = activity.tallyVotes("d1", 2);
        assertThat(tie.decided()).isFalse();
        assertThat(tie.winner()).isNull();
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
    void isFinalAndEscalate() {
        when(disputes.findById("d1")).thenReturn(Mono.just(dispute("d1", "final")));
        assertThat(activity.isFinal("d1")).isTrue();

        when(disputes.markEscalated("d2")).thenReturn(Mono.just(dispute("d2", "voting")));
        when(outbox.append(any())).thenReturn(Mono.empty());
        activity.escalate("d2");
        verify(disputes).markEscalated("d2");
        verify(outbox).append(any());
    }

    private DisputeCase dispute(String id, String status) {
        return new DisputeCase(id, "eng-" + id, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "merchant", status, "未履约", null, null, null, null, 1, 1L, "none", null, null, null);
    }
}
