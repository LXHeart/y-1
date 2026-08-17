# ADR-D15：审判官激励（陪审轮终局按票发积分）

| 状态 | 日期 | 决策编号 | 阻塞范围 | 依赖 |
|------|------|----------|----------|------|
| 已采纳 | 2026-08-17 | D15（PRD §7.2：审判官可获得平台奖励（积分/佣金）作为参与激励） | Trust、Finance、Identity | 既有 outbox/消费/幂等机器、平台内闭环拍板（2026-08-15） |

## 背景

陪审轮终局后，应对**该轮实际投票**的审判官自动发放激励。现状：trust judge 域仅报名/查询/退出；
outbox 争议事件无奖励族；finance 唯一 "reward" 路径是注册赠送 / admin 正向调整。

## 决策

### D1 奖励形式 = AI 积分；佣金后置

v1 发 AI 积分（走 finance credits 既有账户/流水/幂等机器）。**不做现金佣金**：向钱包入现金会制造平台内
闭环下提不出去的应付负债，与「付费平台内闭环、不接第三方支付」拍板冲突；佣金形式与真实 PSP 同批再做。
积分的副作用是正向的：驱动审判官使用 AI 创作工具。

### D2 触发与范围 = 陪审轮终局、按实际投票、逐人事件

- 触发点：**每轮审判终局**（多数票达成的 `recordDecision`，与 `DisputeDecided` 同事务逐 judge append
  `JudgeVoteRewarded`）。投票窗到期裁决的终局路径（若与多数票分离）同口径挂终局事务。
- 奖励对象：该轮**实际投出且计入裁决**的票（early conclusion 未投票者无奖励；超时弃权无奖励——
  PRD §7.2 弃权语义）。弃权票（abstain）算「实际投出」——弃权是积极参与的表现且不引入方向偏差。
- 打平重选 / 上诉后重开（`AdjudicationReopened`）：每轮各自计发（轮次号参与幂等键）。
- **客服终审轮不发**（非陪审，无投票事实）。

### D3 金额 = 平坦 per-vote 配置，不做方向加权

- `trust.judge-reward.credits-per-vote`（默认 20，运营可调）。
- **刻意不做**「与多数一致才多给」的质量加权：少数派判决可能是正当异议，加权会制造从众偏差与合谋套利面；
  平坦金额同时消除「为钱站队」的激励。质量加权登记为远期选项。
- v1 无日上限（分案随机 + 争议量天然限速；日上限登记为升级杠杆，配额机器将来可直接加）。

### D4 跨服务 = outbox 事件 → finance 异步消费，不占审判关键路径

trust 同事务发**逐审判官**事件（量小：≤7/轮，finance/identity 双消费方都好处理）：

```json
"JudgeVoteRewarded": { "disputeId": "...", "round": 2, "judgeAccountId": "...", "credits": 20 }
```

- finance 新增 Kafka 消费者（镜像 identity 通知中心 inbox 幂等模式：`consumer_name + event_id` inbox
  + 处理器），调用**本服务内** `CreditsService.award` 入账（type=reward）。
- **幂等键**：`operation_id = judge-reward:{disputeId}:{round}:{judgeAccountId}`——credits 流水既有
  operation 唯一索引吸收 Kafka at-least-once 重放；inbox 是第二道保险。
- **绝不在审判终局事务里同步调 finance**（finance 不可用会拖死裁决主链路；这正是 outbox 存在的理由）。

### D5 防刷

- 主闸复用既有：审判官 = Lv5 + 运营准入（trust V8 准入版本与只追加审计 + marketplace 权威声誉快照门），
  高弃权率可由运营用既有撤销工具处理——零新代码。
- 平坦金额（D3）消除方向性套利；投票窗约束刷量节奏；Kafka 重放由幂等键吸收。
- 合谋（审判官与当事方）在本方案下无金钱放大器（奖励与结果无关）。

### D6 记账与审计

- finance migration：`credits_transaction.type` CHECK 扩展新值 `judge_reward`（V6 先例；存量默认
  `reward`，运营查询按 type 区分）。发放走 `award` 的既有幂等闭环，type 落 `judge_reward`。
- **trust 侧不建奖励表**：outbox（决策痕迹）+ credits 流水（发放痕迹）双痕迹即审计面；
  operation_id 可双向对账。
- 消费失败走既有 DLT/重试语义，处理进度暴露 pending/lag 指标（对齐五个 outbox owner 的观测约定）。

### D7 通知（搭 #28 便车）

- identity `NotificationTemplates` 加 `JudgeVoteRewarded`：DISPUTE 类，收件人 = `judgeAccountId`
  （payload 直读），深链 `/me/disputes`，文案含积分数；payload 不泄露其他审判官。
- 邮件经 `MailTemplates` 委托自动覆盖（DISPUTE 在高价值子集）。

### D8 前端零新增（v1）

审判官在既有积分历史（`/api/credits/history`，类型 label 加「审判奖励」）+ 通知中心看到结果；审判台不改造。

## 影响

- **Trust**：`AdjudicationActivityImpl.recordDecision`（终局事务内）收集该轮 `dispute_vote` 的 judge
  账号，逐人 append `JudgeVoteRewarded`（确定性 event_id：`type:disputeId:round:judgeId`）；
  配置 `trust.judge-reward.credits-per-vote:20`；金额策略集中在 trust（finance 只执行）。
- **Finance**：首个 Kafka 业务消费者（inbox 表 V19 + processor，镜像 identity 形态；consumer group、
  DLT、lag 指标对齐观测约定）；`CreditsService.award` 扩展 judge_reward 类型 + operation_id 前缀；
  V6 CHECK 扩展。
- **Identity**：`JudgeVoteRewarded` 模板 case（D7）。
- **前端**：积分历史类型 label 加「审判奖励」（一处常量）。

## 不在范围

- 现金佣金（与真实 PSP 同批）；质量加权（远期）；日上限（升级杠杆）；审判台改造。
