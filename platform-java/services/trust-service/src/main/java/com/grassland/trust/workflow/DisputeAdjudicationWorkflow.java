package com.grassland.trust.workflow;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 争议审判 workflow（草场 Epic 6 Slice 6C Phase C / HLD §5.5、§9.3、§10.5 + 任务书 #74 卡 B/C）。
 *
 * <p>编排（HLD 9.2 确定性铁律——workflow 主体只用 Timer/currentTimeMillis，DB/RPC 全在 activity 内）：
 * <pre>
 * [卡 B 质证段] evidencePhase=true 时：await(双方质证完毕 signal concludeEvidence 或 质证窗到点)
 * for round in startRound..startRound+maxRounds-1:
 *   assignPanel(round) → await(投票窗到点 或 concludeEarly signal) → tallyVotes(round)
 *   if 多数决: recordDecision → break
 *   else 平票/不足 → 下一轮重开
 * if 未决: escalate（→ 客服终审）
 * else: Workflow.sleep(appealWindow)
 * if 未终局:
 *   if 有 appeal/escalation: 轮询 isFinal 等客服终审
 *   else: applyPanelDecision（面板判决 finalize）
 * publishFinalStatus（发 DisputeFinalized + 卡 G 判例入库）
 * </pre>
 * 触发：court 通道开争议即启（卡 B，质证段先行）；手动 {@code POST /disputes/{id}/adjudicate}
 * 保留作自愈/重试入口（幂等）；发回重审（卡 F）终止旧 run 后同 workflowId 重启（startRound=新轮次）。
 */
@WorkflowInterface
public interface DisputeAdjudicationWorkflow {

    @WorkflowMethod
    void run(AdjudicationInput input);

    /**
     * 任务书 #74 卡 B：双方质证完毕（或任一方到点后的开庭触发）提前结束质证等待。
     * 送达晚于质证段结束（历史已推进）时为 no-op；送达已结束 workflow 时由信令侧吞异常。
     */
    @SignalMethod
    void concludeEvidence();

    /**
     * 任务书 #74 卡 C（D2 抢先 4/7 达票）：castVote 同事务翻 decided 后发此信号，让 workflow 跳过
     * 剩余投票窗睡眠提前进入收尾；满窗 tally 是兜底双保险（信号丢失不影响正确性）。
     */
    @SignalMethod
    void concludeEarly();
}
