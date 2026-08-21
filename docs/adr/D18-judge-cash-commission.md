# ADR-D18：审判官现金佣金

| 状态 | 日期 | 决策编号 | 阻塞范围 | 依赖 |
|------|------|----------|----------|------|
| 已采纳 | 2026-08-21 | D-18（本 ADR；部分采纳 D-15 D1 的「现金与真实 PSP 同批再做」延后） | Trust、Finance、前端统计 | D-15（事件/幂等骨架）、D-01（真实外币化门禁） |

## 背景

PRD §7.2 写审判官激励「积分/佣金」。ADR-D15 落地了积分（per-vote 平坦，默认 20），并把现金延后
（D1）：理由是「平台内闭环下提不出去的应付负债」。但项目现状是**所有资金流都是 Sandbox 内部账本**
（赏金 capture、佣金补贴 SUBSIDY_EXPENSE 均已入推荐官钱包并走 sandbox 提现出口），现金佣金与它们
同语义——「提不出去」的风险边界是 D-01 真实 PSP，不是本功能。故按与 D-02 阶梯补贴相同的口径
（「不依赖 PSP 的内部费用化支出可先行」）落地内部闭环现金，真实外币化继续等 D-01 + 财务批准。

## 决策

1. **形态**：每票平坦现金 `trust.judge-reward.cash-cents-per-vote`（分；**默认 0=关闭**，与积分同
   哨兵模式）。发放对象/口径完全沿用 D-15 D2：该轮**实际投出**的票（含弃权）、逐审判官事件、
   早结论未投/超时未投/客服终审轮不发、重开轮各自计发。
2. **事件**：独立 eventType `JudgeVoteCommissionRewarded`（载荷 `{disputeId, round, judgeAccountId,
   amountCents}`），与 `JudgeVoteRewarded` 分离——既有积分载荷契约零变更，也避开跨版本 activity
   重试的 payload canonical-hash 冲突边缘。确定性 eventId 前缀 `JudgeVoteCommission:`。
3. **入账**：finance 既有 `JudgeRewardEventConsumer/Processor` 加分支（inbox 幂等复用）；同事务
   钱包 `credit` + `wallet_ledger(judge_commission)` + journal 双录
   `Dr JUDGE_COMMISSION_EXPENSE（新费用科目，与 SUBSIDY_EXPENSE 分列）/ Cr WALLET:{judge}`；
   operationId `judge-commission:{disputeId}:{round}:{judgeId}`（journal 唯一索引第二道幂等）。
4. **统计与通知**：月度收入统计白名单 + 前端钱包流水 label/统计列加 `judge_commission`；identity
   通知新 case（WALLET 类「审判现金佣金已到账」，收件人=judgeAccountId）。
5. **运维边界**：真实外币化（可提现真钱）仍受 **D-01 门禁 + 财务批准**；生产 overlay 透传
   `TRUST_JUDGE_COMMISSION_CENTS_PER_VOTE`（默认 0）——启用前须财务定口径。顺带修复调查发现的
   运维缺口：`FINANCE_JUDGE_REWARD_CONSUMER_ENABLED` 此前在 dev/production compose 均未注入
   （ADR-D15 声称已开实为缺），本轮补齐（dev 默认 true，prod 显式透传默认 true）。

## 不做

- 方向加权/多数方加成（D-15 D3 同理由：防从众偏差与合谋套利）。
- 日上限/月上限（与积分同口径，需要时经新 ADR）。
- 审判官佣金的商家侧可见性（与商家的争议结果通知分离，不混入）。
