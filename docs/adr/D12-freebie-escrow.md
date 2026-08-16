# ADR-D12：霸王餐反向资金流（推荐官押金托管）

| 状态 | 日期 | 决策编号 | 阻塞范围 | 依赖 |
|------|------|----------|----------|------|
| 已采纳 | 2026-08-17 | D12（PRD §2.2 模式一 / §8.2） | Finance、Marketplace、Identity、前端 | D01（账本）、D02（资金组合）、2026-08-15 平台内闭环拍板 |

## 背景

PRD §2.2 模式一「霸王餐」：推荐官报名被接受时**预付押金进平台托管**（资金方向与既有商家出资 escrow 相反）；达标（核实 + 商家确认）→ **全额返还推荐官**（§8.2 明文，无平台费）；未达标/超时终局 → **资金释放给商家**（作为补偿）。本质：推荐官以「完成任务」换免费体验，商家让利 = 餐费本身。

**关键推导（支撑数据模型）**：霸王餐与佣金模式的资金方向矩阵**完全同构**——成功 → 钱到推荐官侧（佣金 capture / 押金 refund），失败 → 钱到商家侧（佣金 release / 押金 compensate）。差异只在语义标签与费用处理（佣金 capture 抽平台费、押金 refund 全额）。因此任务用**正交字段**建模（`task.freebie_deposit_cents` 与 `bounty_cents` 并列），未来组合模式天然可加；但 v1 强制单模式 XOR（D1）。

## 决策

### D1 范围：v1 单模式 XOR，组合后置

`freebie_deposit_cents` 与 `bounty_cents` 同时 >0 → 400。组合模式（同任务佣金+押金）为后续 backlog：同构方向矩阵已论证其可行性，但 Saga 需双预留步、结算需双腿拆分，不值得首期背。ADR 明记此边界。

### D2 账务：独立 journal 类型，不复用既有语义

新增 journal_type：`FREEBIE_RESERVE`（推荐官钱包 → 托管）、`FREEBIE_REFUND`（托管 → 推荐官钱包，全额无费）、`FREEBIE_COMPENSATE`（托管 → 商家 org 账户）。不复用 RESERVE/RELEASE/CAPTURE——它们承载「商家出资」方向语义（CAPTURE=打款推荐官并抽费），混用会毁掉对账可读性。posting 账户：`WALLET:{accountId}` / `RESERVE` 池（account_owner=推荐官 accountId，account_ref=engagementRef）/ `ESCROW:{orgId}`；钱包流水新 entry_type：`freebie_reserve`（负）/ `freebie_refund`（正）。

### D3 预付资金源 = 推荐官钱包余额；v1 不做钱包充值

平台内闭环拍板（2026-08-15：不接第三方支付）下，推荐官钱包余额的唯一来源是任务收益沉淀。**风险登记**：冷启动用户接不了霸王餐任务——充值通道与真实 PSP 同批做（届时本来就绕不开）。余额不足 = accept 失败（D6 矩阵之外的 Saga 补偿回 pending），DB 层 `balance_cents >= 0` CHECK 兜底防透支。

### D4 无平台费

§8.2 明文「全额返还」；商家让利即餐费。fee_cents 恒 0。

### D5 发布门槛 = 资金交易权限 tier

霸王餐涉及托管与商家收款（补偿），按 PRD §2.1「涉及预付、托管…的任务」走与 bounty 任务相同的 funding 权限闸门（`FINANCE_TRANSACTION` tier）与发布校验链（挂 `enforceLadderBudget` 同一位置附近）；押金同样受单笔上限约束。

### D6 失败归因矩阵（核心，逐格实现+测试）

| 终局事件 | 资金去向 | 依据 |
|---|---|---|
| 核实/确认成功（Settlement 成功路径） | **退推荐官**（全额） | §8.2 |
| 核实未达标且争议终局判商家（DisputeFinalized for_merchant） | **补偿商家** | 未达标 |
| 争议终局判推荐官（for_recommender） | **退推荐官** | 达标方胜诉 |
| 履约超时未提交凭证且任务终局（closed/completed） | **补偿商家** | §8.2 超时 |
| **商家取消任务**（D-03 cancel 流程） | **退推荐官** | ⚠️ 与既有 `EngagementRefundedOnCancel`（退**商家**）方向相反——商家取消不是推荐官的失败。cancel 处理器必须按资金来源分支 |
| 推荐官主动放弃/撤回（accepted 后如有该路径） | 退推荐官 | 未消费体验 |

### D7 押金快照 pinning（镜像 bounty）

任务修订改 deposit 只影响新报名；已接受履约按 accept 时冻结值结算。镜像 V14（bounty 快照）+ V27（task_context 触发器）既有模式，把 `freebie_deposit_cents` 纳入快照列。

### D8 事件与通知

outbox 新事件：`FreebieReserved` / `FreebieRefunded` / `FreebieCompensated`。identity 通知：Reserved/Refunded → 收件人 `recommenderAccountId`（WALLET 类）；Compensated → 双方（`taskOwnerId` + `recommenderAccountId`，ENGAGEMENT 类）。payload 不泄露账号。

### D9 结算唯一钱侧入口分支

`SettlementExecution`（及其本地门闩）内按资金来源分支调 freebie 生命周期，**不新增第二个钱侧入口**；`settlement_reconciliation` 对 freebie 行同样落账（finance 侧 reconcile 按 funding source 分支）。

## 方案与取舍

- **正交字段 vs 独立任务表**：独立表会让发布/报名/接受/结算/争议全链路复制一遍，成本不可接受；正交字段 + XOR 约束以一个 CHECK 级校验换取全链路复用。采纳后者。
- **独立 journal 类型 vs 复用 RESERVE/CAPTURE**：复用会令「钱从谁口袋出」不可读——RESERVE 的 Dr ESCROW 在 freebie 语义下根本不成立（钱不在商家账上）。采纳独立类型，posting 账户复用（RESERVE 池 owner 语义扩展为「出资方」）。
- **押金行复用 funds_reservation vs 独立 freebie_escrow 行**：funds_reservation 的 org/payee/佣金补贴列与 freebie 语义不匹配（出资方是推荐官不是 org），复用需大量可空列与分支。finance 侧以 journal operationId（`freebie-reserve:{engagementRef}` 等）+ 状态表 `freebie_escrow` 独立建模，生命周期守卫与既有镜像。

## 影响

- **Finance**：`FreebieEscrowLifecycleService` + 内部端点 `/internal/freebie/{reserve,refund,compensate,reconcile}`（服务断言，marketplace 专属）；`WalletEntryType` 扩展；reconcile 派生余额 SUM 覆盖 freebie 腿。
- **Marketplace**：V40 迁移（task/task_version 加 `freebie_deposit_cents` + V27 触发器重建纳入快照）；task_application 加 `freebie_deposit_cents` 快照列；accept Saga 按 funding source 分支（freebie → 预扣押金）；`SettlementExecution`/cancel/`SettlementReconciliationActivityImpl` 按 D6 矩阵分支；发布 XOR + funding tier 闸门。
- **Identity**：通知模板三 case（D8）。
- **前端**：发布表单押金输入（元↔cents）+ XOR 交互；大厅徽标「需预付 ¥X · 达标全额返还」；钱包流水 label。

## 不在范围

- 组合模式（同任务佣金+押金）——D1 后置。
- 钱包充值通道 / PSP——与真实支付同批（D3 风险登记）。
- 推荐官主动撤回已接受履约（当前产品无此路径；若未来加入，按 D6 矩阵行「退推荐官」实现）。
