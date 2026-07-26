package com.grassland.trust.workflow;

import com.grassland.trust.adjudication.AdjudicationProperties;
import com.grassland.trust.dispute.DisputeCase;
import com.grassland.trust.dispute.DisputeCaseRepository;
import com.grassland.trust.event.EventEnvelope;
import com.grassland.trust.event.OutboxRepository;
import com.grassland.trust.judge.Judge;
import com.grassland.trust.judge.JudgeRepository;
import com.grassland.trust.judge.VoteTally;
import com.grassland.trust.security.TrustException;
import io.temporal.spring.boot.ActivityImpl;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 审判 workflow 活动实现（草场 Epic 6 Slice 6C Phase C / HLD §5.5、§9.3）。
 *
 * <p>每个活动幂等 + 执行前重验状态（终态短路、面板已存在短路、判决已记短路）。DB 写入用 guarded-UPDATE；
 * outbox 用确定性 type-3 {@code eventId}（{@code eventType:disputeId:round}）保 activity 重试 exactly-once。
 * {@code assignPanel} 抽签失败（无可用审判官）抛 {@link TrustException}(503) → Temporal 重试。
 *
 * <p>worker = {@code trust-adjudication}（application.yml 显式定义）。
 */
@Component
@ActivityImpl(workers = DisputeAdjudicationWorkflowImpl.TASK_QUEUE)
public class AdjudicationActivityImpl implements AdjudicationActivity {

    private final DisputeCaseRepository disputes;
    private final JudgeRepository judges;
    private final OutboxRepository outbox;
    private final AdjudicationProperties props;

    public AdjudicationActivityImpl(DisputeCaseRepository disputes, JudgeRepository judges,
                                    OutboxRepository outbox, AdjudicationProperties props) {
        this.disputes = disputes;
        this.judges = judges;
        this.outbox = outbox;
        this.props = props;
    }

    @Override
    public void assignPanel(String disputeId, int round) {
        DisputeCase d = disputes.findById(disputeId).block();
        if (d == null || "final".equals(d.status())) {
            return;
        }
        if (judges.countPanel(disputeId, round).block() > 0) {
            return;  // 幂等：该轮面板已分配
        }
        if (round == 1) {
            if ("open".equals(d.status())) {
                disputes.startAdjudication(disputeId, 1).block();  // open→voting
            }
        } else if (d.round() < round) {
            disputes.reopen(disputeId, round).block();  // voting→voting 下一轮
        }
        d = disputes.findById(disputeId).block();  // reload 取 fresh version/round
        List<Judge> pool = judges.drawEligiblePool(props.judgeEligibilityTier(), d.organizationId(), props.panelSize())
                .collectList().block();
        if (pool == null || pool.isEmpty()) {
            throw new TrustException(503, "无可用的合格审判官");
        }
        judges.assignPanel(disputeId, round, pool.stream().map(Judge::accountId).toList()).block();
        outbox.append(envelope(round == 1 ? "DisputeAssigned" : "AdjudicationReopened", d, round, pool.size())).block();
    }

    @Override
    public TallyResult tallyVotes(String disputeId, int round) {
        VoteTally t = judges.tallyVotes(disputeId, round).block();
        if (t == null) {
            return TallyResult.undecided(0, 0, 0, 0);
        }
        if (t.hasMajorityForMerchant()) {
            return TallyResult.decided(t.forMerchant(), t.forRecommender(), t.abstain(), t.panelSize(), "for_merchant");
        }
        if (t.hasMajorityForRecommender()) {
            return TallyResult.decided(t.forMerchant(), t.forRecommender(), t.abstain(), t.panelSize(), "for_recommender");
        }
        return TallyResult.undecided(t.forMerchant(), t.forRecommender(), t.abstain(), t.panelSize());
    }

    @Override
    public void recordDecision(String disputeId, String winner) {
        DisputeCase updated = disputes.recordDecision(disputeId, winner).block();  // voting→decided
        if (updated == null) {
            return;  // 非 voting（已判决/终局）→ 幂等跳过
        }
        outbox.append(envelope("DisputeDecided", updated, updated.round(), null)).block();
    }

    @Override
    public void escalate(String disputeId) {
        DisputeCase updated = disputes.markEscalated(disputeId).block();  // appeal_state=escalated（保持 voting）
        if (updated == null) {
            return;  // 非 voting → 幂等跳过
        }
        outbox.append(envelope("AdjudicationEscalated", updated, updated.round(), null)).block();
    }

    @Override
    public boolean isFinal(String disputeId) {
        DisputeCase d = disputes.findById(disputeId).block();
        return d != null && "final".equals(d.status());
    }

    @Override
    public boolean hasAppealOrEscalation(String disputeId) {
        DisputeCase d = disputes.findById(disputeId).block();
        if (d == null) {
            return false;
        }
        boolean appealFiled = Boolean.TRUE.equals(disputes.hasAppeal(disputeId).block());
        String as = d.appealState();
        return appealFiled || "filed".equals(as) || "escalated".equals(as);
    }

    @Override
    public void applyPanelDecision(String disputeId) {
        DisputeCase d = disputes.findById(disputeId).block();
        if (d == null || "final".equals(d.status())) {
            return;
        }
        // 用面板判决（decision 列）finalize（decided→final）；decidedBy=null（非客服终审）。
        disputes.finalize(disputeId, d.decision() != null ? d.decision() : "no_decision", null).block();
    }

    @Override
    public void publishFinalStatus(String disputeId) {
        DisputeCase d = disputes.findById(disputeId).block();
        if (d == null) {
            return;
        }
        outbox.append(envelope("DisputeFinalized", d, d.round(), null)).block();
    }

    private EventEnvelope envelope(String eventType, DisputeCase d, int round, Integer panelSize) {
        String eventId = UUID.nameUUIDFromBytes(
                (eventType + ":" + d.id() + ":" + round).getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("disputeId", d.id());
        payload.put("engagementRef", d.engagementRef());
        payload.put("organizationId", d.organizationId());
        payload.put("round", round);
        if (d.decision() != null) {
            payload.put("decision", d.decision());
        }
        if (d.finalDecision() != null) {
            payload.put("finalDecision", d.finalDecision());
        }
        if (panelSize != null) {
            payload.put("panelSize", panelSize);
        }
        return new EventEnvelope(eventId, eventType, "DisputeCase",
                d.id(), d.version(), Instant.now(), null, payload);
    }
}
