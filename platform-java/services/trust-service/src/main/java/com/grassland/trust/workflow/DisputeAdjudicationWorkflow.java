package com.grassland.trust.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * 争议审判 workflow（草场 Epic 6 Slice 6C Phase C / HLD §5.5、§9.3、§10.5）。
 *
 * <p>编排（HLD 9.2 确定性铁律——workflow 主体只用 Timer/currentTimeMillis，DB/RPC 全在 activity 内）：
 * <pre>
 * for round in 1..maxRounds:
 *   assignPanel(round) → Workflow.sleep(voteWindow) → tallyVotes(round)
 *   if 多数决: recordDecision → break
 *   else 平票/不足 → 下一轮重开
 * if 未决: escalate（→ 客服终审）
 * else: Workflow.sleep(appealWindow)
 * if 未终局:
 *   if 有 appeal/escalation: 轮询 isFinal 等客服终审
 *   else: applyPanelDecision（面板判决 finalize）
 * publishFinalStatus（发 DisputeFinalized）
 * </pre>
 * 触发：商家/推荐官 {@code POST /disputes/{id}/adjudicate}（controller 同步分配 round-1 面板 + 启本 workflow）。
 */
@WorkflowInterface
public interface DisputeAdjudicationWorkflow {

    @WorkflowMethod
    void run(AdjudicationInput input);
}
