package com.grassland.trust.workflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * 审判 workflow 的活动集（草场 Epic 6 Slice 6C Phase C / HLD §5.5、§9.3）。每个活动幂等 + 执行前重验状态，
 * RPC/DB 仅在 activity 内（HLD 9.2 确定性铁律：workflow 主体只用 Timer/currentTimeMillis）。
 *
 * <ul>
 *   <li>{@code assignPanel} — 抽 panel-size 无冲突审判官 + 置状态（round 1: open→voting；round&gt;1: reopen）+ 写面板 + 发事件。</li>
 *   <li>{@code tallyVotes} — 读 dispute_vote 计票 → {@link TallyResult}（含是否过多数决）。</li>
 *   <li>{@code recordDecision} — voting→decided（面板多数判决）+ 发 DisputeDecided。</li>
 *   <li>{@code escalate} — 超 maxRounds 无判决 → 标 escalated（待客服终审）+ 发 AdjudicationEscalated。</li>
 *   <li>{@code isFinal} / {@code hasAppealOrEscalation} — workflow 决策分支用查询。</li>
 *   <li>{@code applyPanelDecision} — 无上诉时用面板判决 finalize。</li>
 *   <li>{@code publishFinalStatus} — 发 DisputeFinalized（释放 settlement hold 信号）。</li>
 * </ul>
 */
@ActivityInterface
public interface AdjudicationActivity {

    @ActivityMethod
    void assignPanel(String disputeId, int round);

    @ActivityMethod
    TallyResult tallyVotes(String disputeId, int round);

    @ActivityMethod
    void recordDecision(String disputeId, String winner);

    @ActivityMethod
    void escalate(String disputeId);

    @ActivityMethod
    boolean isFinal(String disputeId);

    @ActivityMethod
    boolean hasAppealOrEscalation(String disputeId);

    @ActivityMethod
    void applyPanelDecision(String disputeId);

    /** 按终局判决×reservation 状态矩阵分派钱侧（Phase D / D-06）：merchant_favor+reserved→release /
     *  merchant_favor+captured→reverse / recommender_favor+reserved→capture / 其余→幂等 noop。 */
    @ActivityMethod
    void releaseHoldAndApplyDecision(String disputeId);

    @ActivityMethod
    void publishFinalStatus(String disputeId);
}
