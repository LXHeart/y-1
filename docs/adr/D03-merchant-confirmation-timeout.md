# ADR-D03：商家确认超时、拒绝、失联与恶意拖延规则

| 状态 | 日期 | 决策编号 | 阻塞范围 | 依赖 |
|------|------|----------|----------|------|
| 已采纳 | 2026-08-02 草案 / 2026-08-02 已采纳 | D-03（HLD §19、§10.3、§18） | Marketplace、Trust、Finance、Temporal | D-01、D-02 |

## 决策记录（已采纳，2026-08-02）

决策者逐条拍板——**采纳推荐方案 D + 全部默认值**：

1. **确认窗口默认 3 个自然日**（按任务类可配）。
2. **窗口到期无操作 → 自动确认结算**（default-approve：保护推荐官、资金不悬置）。商家不同意须主动在窗口内拒绝/提争议。
3. **拒绝冲突客服 SLA 3 个工作日**，客服超时未裁定 → 按系统核实结果结算（避免裁定侧再悬置）。【采纳推荐默认】
4. **补证次数上限 2 次**，超出强制进入确认窗口按规则 1 处理。
5. **cancel 后未提交凭证的 engagement：首期无补偿、全额返还商家**（商家主动 cancel 视违约，记 trust 信誉；首期不对推荐官补偿，换取实现简单——接受对已接单推荐官不公）。已核实通过的履约仍照常结算给推荐官（推荐官无过错）。
6. **窗口通知渠道：邮件 + 站内**（SMS/Push 归 `GL-P4-NOTIFY-001`）。【采纳推荐默认】

**解锁**：`GL-P1-TASK-001` 的 close/cancel 后 pending 报名与进行中履约挂账**正式决策化**——close 不动既有 engagement（商家已接受=已承诺）；cancel 视商家违约（已核实通过履约照结、未提交凭证首期无补偿全额返商家、记信誉）。Marketplace/Finance/Trust 的确认/超时/裁定状态机 + Temporal 确认窗口 Workflow（HLD §9.3）可进入 LLD。

**仍属实现层（后续 backlog，非本决策）**：Temporal 确认窗口定时器、拒绝端点转客服、补证计数、cancel 违约计数进信誉、通知通道接线。

**实现进度（2026-08-03）**：规则 1–4、规则 5 的首期 cancel 口径与规则 6 已落地；本节记录最终实现语义，历史分段测试数量见续接指南与提交历史。
- `ConfirmationWindowWorkflow` + `ConfirmationActivity` 复用共享 `SettlementExecution`（gate + capture + hold）；窗口绑定 submission，退回重交会启动新窗口，旧 Timer 到期重验后退出。`ConfirmationWindowDispatcher` 以固定 workflow ID 补齐 DB commit→Temporal start 间隙。
- **F6 本地 contest 门闩**：marketplace V17 在 `task_application` 持久化 `contest_requested_at` 与客服 SLA workflow 启动标记。contest 先用 guarded `UPDATE ... RETURNING` 提交本地 intent，`confirm` / `autoConfirm` 同时要求 intent 为空；三路争抢同一 application 行，PostgreSQL 行锁与谓词复查只允许一个赢家。contest 输给已确认路径时明确 409；contest 先赢时 Timer 返回 held，且 `SettlementExecution` 在唯一钱侧入口再次回读本地门闩，绝不调用 finance capture。trust 的开放争议查询仍保留，但只作纵深防御，不承担跨服务竞态正确性。
- **F6 durable recovery**：本地 intent 提交后才在事务外幂等创建 trust `merchant_rejection`，再完成 submission/application、ops case 与确定性 outbox，最后以固定 ID 启动客服 SLA；`MerchantContestDispatcher` 扫描未完成 intent 并恢复 trust/local/Temporal 各阶段。远端或 Temporal 暂时失败不会自动终局客服案，也不会重复事件、处置单或 workflow。
- **F5 延后异议**：保留 `uniq_dispute_active_per_engagement`。活跃 `merchant_rejection` 期间，经过 marketplace 当事方授权的推荐官普通异议会持久化为 deferred request（逐字保留 reason），首次返回 202、重试返回 200；pending 响应不暴露客服案 ID。客服人工终审或 SLA auto-finalize 在同一 trust 事务中先终局旧案、再创建唯一 `standard` successor、标记 request promoted，并追加旧案 `DisputeFinalized` 与新案 `DisputeOpened`。
- **F5 审判与结算接续**：promoted request 持久化固定 `adjudicate-<successorId>` 启动意图，dispatcher 自动启动既有七官 `DisputeAdjudicationWorkflow`；successor opener 可读取自己的案卷。旧客服案 final 事件携带 `settlementDeferred=true` / `successorDisputeId`，marketplace 仍记 inbox 但不创建 reconciliation；只有 successor 的最终 `DisputeFinalized` 进入既有 D-06 reconciliation，避免旧裁决越过新争议落钱。
- **跨服务断言**：trust 调 marketplace 参与方授权使用 `purpose=service`、`audience=grassland-marketplace`；marketplace 的 verify keyring 必须同时包含 edge 用户键与 trust→marketplace 服务键。配置只引用环境变量，不在仓库记录密钥值。
- 其余规则：补证上限默认 2；cancel 仅对 `accepted + 尚无 submission` 的 engagement 全额 release；`ConfirmationWindowEntered` / `ConfirmationWindowExpiring` / `AutoSettledOnTimeout` 走邮件+站内通知。
- **验证边界（2026-08-03）**：marketplace/trust 全量测试与 `bootJar` 使用显式 JDK 25 通过；根工程 62 个测试文件、659 项测试、typecheck、build 通过。容器以 bootJar 后生成的新 JAR 重建；真实 Temporal + Kafka 闭环覆盖 F6 Timer-first 409 与 contest-first 保持 reserved、F5 人工终审及 300 秒 SLA 两条 deferred→successor→voting 路径、旧案 inboxed 但 reconciliation suppressed、successor final 正常 reconciled。真实浏览器走 Vite `:5173` → edge-bff `:8081`，中间等待只观察 DOM。上述均为本地测试/容器验证，未做生产真实流量验证。
- **收尾完成（2026-08-16）**：trust 消费 merchant cancel 事件并按累计次数生成幂等风险信号；运营处置单可按 sourceRef 直达客服裁定；客服 SLA 使用上海时区的可配置业务日日历（法定假日/调休工作日由部署配置，秒数仅作测试或运维覆盖）。

## 背景

PRD §8 与 §9 把**商家确认**设为结算的必要步骤：推荐官提交凭证 → 系统按指标核实 → 全部达标标记「系统核实通过」→ 通知商家确认 → 商家确认后进入结算（佣金 T+2）。但 PRD **没有定义**：商家收到确认通知后**不操作**怎么办、**拒绝**（系统核实通过却拒绝）怎么办、**反复要求补证拖延**怎么办、**长期失联**怎么办。HLD §10.3 把这些列为 `TBD`；§18 把「商家确认超时规则缺失」列为风险——「履约和资金长期悬置」，要求「在 Marketplace/Finance LLD 前完成产品决策」。

同时，`GL-P1-TASK-001` Stage 1 明确把 **close/cancel 后已存在 pending 报名与进行中履约的处置**挂账到本决策（close/cancel/deadline 当时只门控「新报名」apply，不动 accept/confirm/结算）。本决策要一并定这些边界，解除 TASK-001 的挂账。

## 当前代码现状

- marketplace 有 engagement 状态机（pending → accepted → 提交凭证 → 核实 → 待确认 → 结算），但**无确认窗口、无超时定时器、无自动确认、无拒绝升级路径**。商家确认是纯手动、永不超时的端点。
- `settlement_reconciliation`（V8）按 engagement 对账，无时间驱动触发。
- 争议侧（trust）有争议窗口（结果公布后 48h，PRD §争议）与 7 人审判，但**「商家拒绝已核实通过的履约」不自动进争议**。
- close 后：`ApplicationController.apply` 对 closed/cancelled 任务 409（断新报名），但**已 accept 的 engagement 后续 accept/confirm/submit/结算路径完全不受 close 影响**（TASK-001 锁的现状）。
- 即：所有「时间驱动」的确认/超时/升级**都不存在**，资金可无限期悬置。

## 方案与取舍

### 方案 A：商家确认是硬门槛，永不超时（现状）

维持现状，商家不确认就永不结算，资金永久托管。

- ✅ 实现零成本（已是现状）。
- ❌ **资金长期悬置**，推荐官已达标却拿不到钱，严重损害推荐官侧体验与平台信任——HLD §18 明确点名此风险不可接受。

### 方案 B：核实通过后 N 天无操作 → 自动确认结算（default-approve）

系统客观核实通过即视为达标事实；给商家 N 天「无异议窗口」，窗口内不操作则默认无异议，自动进入结算。商家若不同意须**主动**在窗口内拒绝/提争议。

- ✅ 保护推荐官：客观核实通过 + 商家不作为 → 放款，资金不悬置。
- ✅ 把「确认」从「商家放行」重新定义为「无异议窗口」，与争议窗口（48h）语义一致。
- ❌ 商家可能合理地想拒但错过窗口 → 自动放款造成商家损失。缓解：通知强提醒 + 窗口够长 + 拒绝进入裁定而非直接否。

### 方案 C：核实通过后 N 天无操作 → 转客服人工裁定（default-to-support）

超时不当自动放款，而是进客服队列，由客服调查后裁定。

- ✅ 不自动放款，给商家「合理遗漏」留余地。
- ❌ 客服负担重、资金仍悬置到客服处理、SLA 不可控；冷启动期客服能力不足时是瓶颈。

### 方案 D（推荐）：B 为主 + 拒绝/冲突升级到 C 的裁定通道

核实通过后进入「确认窗口」（推荐 3 天），窗口内：商家**确认** → 结算；商家**拒绝**（核实通过却拒）→ 因「客观达标」与「商家拒绝」冲突，**不直接返还商家**，进入**客服/争议裁定**通道；窗口**到期无操作** → 自动确认结算（保护推荐官）。恶意拖延用「补证次数上限」收敛。

- ✅ 兼顾：正常路径自动结算不悬置，冲突路径有人工兜底。
- ✅ 「核实通过」作为客观事实兜底默认值，符合 HLD §2.3「AI/核实只提供事实，资金裁决需可人工介入」。
- ❌ 需建确认窗口定时器 + 客服裁定工作流（Temporal），是 D-03 的实现成本。

## 推荐

**采纳方案 D**，并定 close/cancel 挂账边界。具体规则（阈值标 ⚙️ 供调）：

### 1. 确认窗口与超时

- 系统核实通过 → engagement 进入**待商家确认**，起**确认窗口**（⚙️ 默认 **3 个自然日**，可按任务类配置）。
- 窗口内商家操作：
  - **确认** → 进入结算（佣金 T+2，PRD §8.4）。
  - **拒绝**（须填拒绝理由）→ 见下条「拒绝冲突」。
  - **不操作** → 窗口到期**自动确认结算**（default-approve）。
- 强提醒：窗口进入、临到期（剩余 24h）、到期自动结算各发一次通知（复用 `GL-P1-NOTIFY-001` 事务邮件/通知通道）。

### 2. 拒绝冲突（系统核实通过，商家拒绝）

- 因「客观核实达标」与「商家拒绝」冲突，**不直接返还商家**，转为**客服裁定**（trust 客服终审队列，复用 §10.5 客服终审路径）。
- 客服 ⚙️ **3 个工作日**内裁定：维持结算 / 改判（退款商家或返推荐官）。客服超时未裁定 → 默认按系统核实结果结算（避免在裁定侧再悬置）。
- 商家滥用拒绝（反复拒同一达标履约）→ 风控标记，见下条。

### 3. 恶意拖延收敛

- **补证次数上限**：推荐官提交凭证后，商家「要求补证」⚙️ 上限 **2 次**；超出后不再允许补证，强制进入确认窗口按规则 1 处理。
- 商家**反复拒绝**被客服多次改判维持结算 → 风控降权（影响其后续任务的自动确认窗口可缩短或强制客服复核），记 trust 信誉。

### 4. 失联（商家账号长期不活跃）

- 确认窗口与自动确认结算**不依赖商家在线**——通知发到邮箱/站内，窗口到期照常自动结算。故「失联」在结算路径上等价于「不操作」，已被规则 1 覆盖。
- 商家失联的**新任务**侧问题（无人接单/无人核销到店核销类）归运营处置（`GL-P1-OPS-001`）与 D-05 商家准入降权，不在本 ADR。

### 5. close / cancel 后 pending 报名与进行中履约（解除 TASK-001 挂账）

- **close（关闭报名）**：只断新报名（TASK-001 已定）。已 accept 的 engagement **不受影响**，继续走核实/确认/结算——商家已接受 = 已承诺，close 不是违约。✅ 这正是 TASK-001 锁的现状，本 ADR 确认其为正式决策。
- **cancel（取消整个任务）**：商家主动撤销。已 accept 的 engagement 视为**商家违约**：
  - 已核实通过的履约 → **照常结算给推荐官**（推荐官无过错）。
  - 已 accept 但未提交凭证的 engagement → ⚙️ **推荐**：返还商家预留，但按商家违约给推荐官一笔补偿（补偿口径待产品定：固定补偿 / 按任务赏金比例 / 无补偿）。**首期简化选项：无补偿、全额返还商家**（接受对推荐官不公，换取实现简单）——需产品确认。
  - cancel 触发 trust 记录商家违约次数，影响信誉与后续准入（D-05）。

## 待你拍板

1. **确认窗口默认时长**（推荐 3 天）与是否按任务类区分。
2. **超时默认动作**：本推荐为自动确认结算（default-approve）。若你们倾向「转客服不自动放款」（方案 C），需接受资金悬置到客服处理。
3. **拒绝冲突的客服 SLA**（推荐 3 工作日）与客服超时默认（推荐按核实结果结算）。
4. **补证次数上限**（推荐 2 次）。
5. **cancel 后未提交凭证 engagement 的补偿口径**：首期「无补偿全额返商家」是否可接受？还是必须有推荐官补偿？这是对推荐官公平性与实现成本的直接取舍。
6. **窗口通知渠道**：邮件 + 站内是否够，是否需要 SMS/Push（后者归 `GL-P4-NOTIFY-001`）。

## 影响

- **解锁**：`GL-P1-TASK-001` 的 close 后 pending 报名挂账解除（确认 close 不动既有 engagement 为正式决策；cancel 违约补偿口径待产品定后落 LLD）；Marketplace/Finance/Trust 的确认/超时/裁定状态机可进 LLD；Temporal 确认窗口 Workflow（HLD §9.3）可设计。
- **约束**：engagement 状态机加「待确认（带窗口到期时间）」态 + Temporal 定时器驱动自动结算；商家确认端点保留，新增拒绝端点（带理由）转客服；`settlement_reconciliation` 由「事件驱动」扩为「事件 + 时间驱动」。
- **波及**：trust 客服终审队列需承接「核实通过却拒绝」的裁定；通知通道（`GL-P1-NOTIFY-001`）加确认窗口三类通知；OPS 运营台（`GL-P1-OPS-001`）的「待判定」类来源可能新增「超时自动结算」审计项。
- **依赖 D-01/D-02**：结算/退款/补偿的实际资金动作走 D-01 的存管通道、按 D-02 的分账计划出账。

## 不在范围

- 争议期内资金 hold 与终局裁决（D-06）——本 ADR 的「拒绝冲突→客服裁定」是结算前的门，D-06 是结算后争议的资金处置。
- 推荐官侧失联/超时（推荐官提交凭证超时、推荐官主动撤回已由 TASK-001 撤回入口覆盖；推荐官失联的 engagement 由商家侧 cancel/超时收敛，不在本 ADR 重复）。
- 商家准入与违约降权细则（D-05）。
- 各平台核实信号与人工阈值（D-04）。
