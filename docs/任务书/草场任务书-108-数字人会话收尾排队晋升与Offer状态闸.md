# 开发任务书：数字人会话收尾、排队晋升与 Offer 状态闸

> 模板版本：3.0.0 ｜ 本次按模板裁剪填写
> 任务编号：108 ｜ 任务书版本：1.0.0 ｜ 创建日期：2026-09-27
> 规划模型/负责人：ZCode（主程规划） ｜ 目标仓库：y-1（仓库根） ｜ 当前分支：main（仅记录）
> 代码基线：`98dc80cd`（含 2026-09-27 数字人 Fake 档六 commit 与体积门禁修复 commit）；工作区无未提交源码改动（仅 test-artifacts/ 与 scripts/local/ 本地产物）｜ 本次事实核验日期：2026-09-27
> 规格状态：READY_FOR_IMPLEMENTATION ｜ 实施状态：NOT_STARTED
> 目标执行者：能力较弱的编码模型 ｜ 任务卡总数：5 ｜ 起始卡：C-01
> 执行顺序：C-01 → C-02 → C-03 → C-04 → C-05
> 执行模式：AUTO_CHAIN
> 交付终点：本地实现 + 模块门禁 + 本地 docker 栈集成验收（浏览器实测）；不默认包含上线
> 决策依据：用户 2026-09-27 会话中提出的三条缺陷（reaper 不收尾 / 排队无晋升 / offer 无状态闸）；#105C 冻结的队列与状态机语义；其余为授权规划者裁量的常规实现选择（D-01～D-06）

---

## 1. 产品需求、目标与范围

### 1.1 一句话目标

数字人会话的容量名额在会话废弃后自动释放、排队会话在名额空出后自动接通、未派发会话的连接请求被服务端以明确错误拒绝——用户连续使用数字人不再因残留会话卡死。

### 1.2 背景与价值

2026-09-27 本地全链实测发现：用户关闭页面（未点「结束会话」）后，reaper 把会话推进到 `ending` 就停住，没有任何组件把它收敛为 `ended`（设计上的收尾方 runtime→Java 异步回执链属于尚未接线的执行环）；`ending` 既占用全局容量计数、又被 `uq_dh_session_owner_active` 部分唯一索引视为活跃，导致：下一个会话被建成 `queued`（排队）、排队会话没有晋升 worker 必然在 60 秒后被回收、前端对排队会话照常发起 WebRTC offer 打穿到 runtime 得到 404，页面显示费解的「runtime 上游拒绝」。三条缺陷同源于「会话生命周期的容量收尾链路缺失」。

### 1.3 范围内（明确交付）

| 需求编号 | 用户/触发场景 | 必须交付的可观察行为 | 交付优先级 | 负责卡号 | 对应验收编号 |
|---|---|---|---|---|---|
| REQ-001 | 用户关闭数字人会话页面/心跳失联后，再次进入数字人并点「开始」 | 被回收的旧会话在宽限期后自动收敛为 `ended`（本人可新建、全局容量释放），新会话正常派发连接，不再出现「runtime 上游拒绝」 | 必须 | C-02 | AC-001/AC-002 |
| REQ-002 | 全局名额被其他会话占用时，用户点「查看费用并开始」 | 新会话以 `queued` 建立并显示「排队中」；名额释放后 ≤10 秒内自动晋升派发并接通画面，全程无需用户手动重试 | 必须 | C-03/C-04 | AC-003/AC-004 |
| REQ-003 | 会话处于排队/准备中/终态时，连接层收到 offer 请求（前端异常路径或恶意直调） | 服务端返回 409 `dh_session_queued`（排队/准备中）或 409 `dh_state_conflict`（终态），不转发 runtime | 必须 | C-01 | AC-005 |
| REQ-004 | 排队会话晋升瞬间，前端页面正在展示 | 前端通过 SSE `session.state` 事件（服务端晋升时写入 dh_event）或轮询兜底感知状态变化并自动发起媒体连接 | 必须 | C-03/C-04 | AC-004 |

### 1.4 范围外（明确不做，遇到也不处理）

- 不实现执行环（LLM 文字回复、字幕、音频律动、runtime→Java 事件回执链）——属后续任务书。
- 不改 runtime Python 侧任何代码（`routes_internal.py` 对未知会话 end 命令返回 404 的现状即被本任务利用）。
- 不改 `maxSessionsGlobal`/`maxQueuedGlobal` 配置语义、价表、计费。
- 不给 `media-ready`/`playback-reset`/`interrupt` 增加状态闸（media-ready 已按快照幂等回读；其余维持现状）。
- 不改部署形态（runtime 本机裸跑/TURN 注入等均为本机事实，与代码无关）。
- 不补 reaper 终态转移的 SSE 事件推送（废弃会话的页面通常已关闭；在页面仍开着的场景维持现状）。

### 1.5 不许顺手修

- `DigitalHumanSessionReaper.CLAIM_SQL` 不含 `preparing` 态（历史缺口：全局 preparing 悬挂无回收）——本任务 C-03 的 preparing 是受控瞬态（失败即回 queued），全局问题列入汇报不修。
- `uq_dh_session_owner_active` 把 `ending` 算活跃（V88 冻结语义）——不改索引。
- 治理台 maxSessionsGlobal 编辑界面、`Limits.maxQueuedGlobal` 未接线 `QUEUE_MAX` 常量——不动。

### 1.6 用户、入口与已知限制

- 用户/调用方：商家/消费者个人账号（数字人为个人能力，K05 无组织上下文）。
- 使用前置条件：数字人目录开启且有 approved 后端组合；已建角色；预检通过。
- 已知且允许保留的限制：排队等待期画面区显示「等待媒体…」+ 状态徽标「排队中」，不额外做排队动画；晋升依赖 5s 轮询周期，名额释放到接通最多有约 10 秒延迟。
- 验收例外：无。

### 1.7 用户场景与业务闭环

| 场景/关联需求 | 用户及动机 | 触发与前置条件 | 主流程 | 最终结果及去向 | 中断后的恢复入口 |
|---|---|---|---|---|---|
| SC-01 / REQ-001 | 用户演示完直接关标签页（不点结束） | 一个连接中的会话存在 | 关页 → 心跳停 → reaper 30s 心跳超时标 `ending`（既有）→ 宽限 15s 后 finalize：runtime.end 尽力而为 + CAS `ending→ended` | 名额（本人+全局）释放；用户下次进入可直接开会话 | 若 finalize 期间用户回来：页面心跳恢复则 reaper 不再认领；已 `ended` 则深链显示终态面板 |
| SC-02 / REQ-002/004 | 第二个用户在名额被占时开始会话 | 全局 active ≥ maxSessionsGlobal | 点开始 → 会话以 `queued` 建立 → 页面显示「排队中」、不发起 offer → 占用者会话结束 → promotion worker 认领 → `queued→preparing→connecting` + 写 `session.state` 事件 → SSE/轮询到达 → 前端 connectMedia → answer/media-ready → 画面接通 | 排队用户无需任何操作得到完整会话 | 排队期间点「结束会话」→ 直接 ending（既有路径）→ finalize |
| SC-03 / REQ-003 | 异常客户端对排队会话直调 offer | 会话 state=queued | POST offer → 409 `dh_session_queued`，runtime 零调用 | 错误透出到媒体错误区，不产生 runtime 侧垃圾请求 | 正常路径由 C-04 保证排队期不发起 offer |

- 核心术语：会话状态机 `preparing→queued→connecting→ready⇄listening/responding/paused/reconnecting→ending→ended/failed`（V88 CHECK + `DigitalHumanRecords.SessionState`，K04）；「占全局槽」= 非 queued 且非终态（K13.4 `occupiesSlot()`）。
- 需求来源：用户 2026-09-27 逐条提出（本文开头三条），实测证据链见 §2.6。

### 1.8 产品成功标准

| 成功标准 | 观察对象与判定方法 | 目标/阈值及依据 | 对应 AC |
|---|---|---|---|
| 废弃会话自愈 | 浏览器关页后 DB 轮询 `dh_session.state` | ≤ reaper 周期+宽限+一次轮询（约 25s）内变 `ended` | AC-001 |
| 连续演示不卡死 | SC-01 后立刻按 SC-02 开始新会话 | 新会话 `connecting` 且画面出帧 | AC-002 |
| 排队自动晋升 | 排队会话在占用者结束后 | ≤10s（promotion 轮询 5s×2 内）进入 `connecting` 且前端自动连接 | AC-003/004 |
| offer 明确拒绝 | 排队会话 POST offer | 409 + `dh_session_queued`，fake runtime 零调用 | AC-005 |

### 1.9 未决问题与决策权限

无。三条需求用户已明确；D-01～D-06 为授权范围内规划选择，均给出唯一结论。

---

## 2. 仓库上下文

### 2.1 目标端（勾选）

| 勾 | 端 | HTML 入口 | 相关目录 |
|---|---|---|---|
| [x] | AI 创作中心（数字人工作台挂载于此端） | `ai.html` | `src/ai/`、`src/views/digital-human/` |
| [ ] | 用户端 | — | — |
| [ ] | 治理台 | — | — |
| [ ] | 共享组件 | — | — |
| [x] | 后端 | — | `platform-java/services/intelligence-service/.../digitalhuman/` |
| [ ] | 构建/脚本 | — | — |
| [x] | 文档/契约 | — | `docs/任务书/README.md`（索引） |

本次无视觉/UI 样式变更（「排队中」徽标与状态文案既有），§8 只保留行为规格。

### 2.2 设计规范路由

改任何 UI 前必读根 `DESIGN.md`（本任务仅行为改动、无样式/布局改动；若实施中发现需要样式变更，必须停下按 §13 修订）。治理台不涉及。

### 2.3 入口位置

- 页面入口：`src/views/digital-human/DigitalHumanWorkbench.vue`（会话开始/接管编排：`handleStartConfirm`/`handleTakeover`）。
- 会话域 composable：`src/views/digital-human/composables/useDigitalHumanSession.ts`（create/end/heartbeat/visibility）。
- 媒体域 composable：`src/views/digital-human/composables/useDigitalHumanMedia.ts`（offer/answer/media-ready/ICE）。
- 事件域 composable：`src/views/digital-human/composables/useDigitalHumanEvents.ts`（SSE 订阅+重连）。
- 前端 API 层：`src/composables/useDigitalHumanApi.ts`（`createSession`/`getSession`/`getEvents` 等）。
- Java 路由链：Edge `/api/digital-human/**` → `DigitalHumanConnectionController.webrtcOffer`（API16 offer 中继）→ `DigitalHumanRuntimeClient` → runtime internal 面；会话创建 `DigitalHumanSessionController` → `DigitalHumanSessionService.create`；回收 `DigitalHumanSessionReaper`（@Scheduled 5s）。
- 数据：`dh_session`（V88，state CHECK 见 `V88__digital_human_core.sql:98`）、`dh_event`（event_type 无枚举约束，`:154`）。
- 测试入口：`DigitalHumanOfferIT`/`DigitalHumanSessionIT`/`DigitalHumanReaperTest`/`DigitalHumanWorkerSchedulingIT`（均 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/`）。

### 2.4 相关现有文件

| 文件 | 相关符号 | 当前职责 | 本次关系 |
|---|---|---|---|
| `platform-java/.../digitalhuman/DigitalHumanConnectionController.java` | `webrtcOffer` | offer 形状+租约校验后直转 runtime | 必须修改（C-01 状态闸） |
| `platform-java/.../digitalhuman/DigitalHumanSessionReaper.java` | `scanExpired`/`runScheduled` | 认领失效会话单向 CAS 到 ending/failed/reconnecting | 必须修改（C-02 finalize pass） |
| `platform-java/.../digitalhuman/DigitalHumanSessionService.java` | `create`（派发段 95–118 行）/`casState`/`end` | 会话建立+runtime 派发+结束 | 必须修改（C-03 提取 `dispatchRuntime`） |
| `platform-java/.../digitalhuman/DigitalHumanRuntimeClient.java` | `createSession(SessionBinding)`/`end(String,String)` facade | runtime 控制面客户端 | 只读参考 |
| `platform-java/.../digitalhuman/DigitalHumanEventService.java` | `append(sessionId,eventId,eventType,leaseEpoch,payload)` | dh_event 持久写入（seq 分配、eventId 幂等、旧 epoch 丢弃） | 只读参考（C-03 新增调用方） |
| `platform-java/.../digitalhuman/DigitalHumanSessionPromotionWorker.java` | — | 不存在 | 新建（C-03） |
| `src/views/digital-human/DigitalHumanWorkbench.vue` | `handleStartConfirm`/`handleTakeover`/`onEvent` | 开始/接管后无条件 connectMedia | 必须修改（C-04） |
| `src/views/digital-human/composables/useDigitalHumanSession.ts` | `start` | 建会话+心跳 | 必须修改（C-04 排队轮询兜底） |
| `platform-realtime/digital-human/src/grassland_dh/routes_internal.py` | `session_commands` | 未知会话 404 `dh_not_found` | 只读参考（不改） |

### 2.5 当前行为（2026-09-27 核验）

1. **创建**：`create` 事务内 `allocateAndInsert` 锁 `dh_catalog` 单例行 → 统计 `active = state<>'queued' AND state NOT IN ('ended','failed')`、`queued` 计数 → `active < maxSessionsGlobal`（catalog JSON，默认 1）→ 插入 `preparing`；否则 `queued`（`QUEUE_MAX=10` 满 → 429 `dh_capacity_full`）。事务后仅当「createdNow 且 state=preparing」才派发 runtime（avatar 绑定 → `runtime.createSession` → CAS `preparing→connecting`；失败 `markInitFailed`+503）。**queued 会话无任何派发路径**。
2. **回收**：`DigitalHumanSessionReaper` 每 5s 扫描（queued 60s/connecting 90s/心跳 30~60s/idle 120s/expires 超时）→ connecting 超时→`failed`；ready 族且 gate active→`reconnecting`；其余→`ending`。**单向 CAS 后不再收尾：不通知 runtime、不落 `ended`**。用户主动 `end()` 则同步完成 `runtime.end`（吞错）→ CAS `ending→ended`。
3. **offer**：`webrtcOffer` = parseStrict 形状校验 → `grants.assertSessionLease` → **直接** `runtime.webrtcOffer`。无会话状态校验；queued/终态会话的 offer 打到 runtime 得 404，被 `upstream()` 映射为透传错误（用户看到「runtime 上游拒绝」）。
4. **前端**：`handleStartConfirm` → `start()`（建会话+心跳）→ 无条件 `connectMedia`+`connectEvents`。SSE 事件仅来自 `dh_event` 表（今天只有 runtime→Java `reportEvent` 写入，而该链路未接线 → 表常空）；状态徽标对 queued 已有「排队中」文案（`DigitalHumanStage.STATE_LABELS`）。
5. **索引**：`uq_dh_session_owner_active WHERE state NOT IN ('ended','failed')`——`ending` 阻塞同主人新建。

### 2.6 当前问题（实测证据链，2026-09-27）

- 现象：用户 21:44 点开始 → 页面「runtime 上游拒绝」；DB 会话 `d2ba8073` 建为 queued → 60s 后 reaper 标 ending 卡死；同时段废弃会话 `71e07b1d` 卡 ending 自 13:37 占用唯一全局名额。
- 问题三条（即本任务三 REQ）：reaper 只到 ending 不收尾；queued 无晋升；offer 无状态闸。
- 影响：任何「关页不点结束」的会话都会卡死名额，演示一次即复现；排队会话必死。
- 根因：已确认（§2.5 1–3 行）；设计上的收尾方（runtime→Java 回执）属未接线的执行环，本任务在 Java 侧补齐同步收尾，不依赖该环。

### 2.7 基线与来源核验

| 项目 | 已核实内容/证据 |
|---|---|
| 指令与设计 | 根 `AGENTS.md`（UI 规则/组件分层/目录归位）；`docs/任务书/任务书模板.md` v3.0.0 全文 |
| 架构与业务决策 | `#105C`（K01/K04 状态机、K13.4 占槽语义、队列上限 10/满 429）、`#105fix-1 C105X-01/03`（reaper 调度驱动、offer 真实中继+connectReady）；V88 迁移 CHECK/索引 |
| 工作区 | `git rev-parse HEAD` = `98dc80cd`；`git status` 无未提交源码改动；`node scripts/check-file-size.mjs` 门禁通过（Workbench.vue 798 行） |
| 测试基线 | 2026-09-27 本会话：runtime pytest 143 passed、前端数字人 106/106、vue-tsc 0 错、Java DH 全类 IT 通过（证据在对话记录；本任务书发布前未重跑，执行者开工先跑 V-00 基线） |
| 复用检查 | `EventService.append`（幂等写事件）、`casState`（CAS 模式）、`SessionIT.FakeRuntimeConfig`（fake transport 注入范式）、`WorkerSchedulingIT` 的 `@TestPropertySource` 调度测试范式 |

### 2.8 事实、决策与示例的区分

本文 `FACT`＝§2.5 所列（均有源码/实测锚点）；`DECISION`＝D-01～D-06 与 §5 规则；无 `EXAMPLE` 填充。

### 2.9 影响面与兼容面

| 影响面 | 是否受影响 | 具体对象 | 兼容要求 | 验证方式 |
|---|---:|---|---|---|
| 页面/路由 | 否 | — | — | — |
| 公共 HTTP 契约 | 是 | API16 offer 新增 409 分支 | 既有 200/422/409(dh_lease_stale 等) 不变；新码 `dh_session_queued` 为追加 | OfferIT |
| 数据库/缓存 | 是（无迁移） | `dh_event` 新增 `session.state` 生产者 | event_type 无枚举约束；eventId UUID 幂等 | PromotionIT |
| 权限/身份 | 否 | owner/lease 校验顺序不变（闸在租约断言之后） | 越权仍 404/403 | OfferIT 回归 |
| 计费/积分/资金 | 否 | 不动经济链 | — | — |
| 部署/配置 | 是（新增可配项） | `digital-human.reaper.finalize-grace-seconds`、`digital-human.promotion.poll-interval-ms`、`digital-human.promotion.enabled` | 默认值开箱即用，无需部署侧变更 | 默认值单测 |
| 文档/状态 | 是 | `docs/任务书/README.md` 索引行 | 登记新任务书 | docs:links |

---

## 3. 技术决策（契约冻结，局部实现按明确边界裁量）

| 决策项 | 结论 |
|---|---|
| 语言/框架 | Java 25 + Spring Boot 4（intelligence-service，WebFlux/R2DBC）；前端 Vue 3 + Vitest |
| 新增依赖 | 无（MUST NOT 新增任何包） |
| 文件布局 | 全部改动落在既有 `digitalhuman` 包与 `src/views/digital-human/`；新文件仅 `DigitalHumanSessionPromotionWorker.java` 与其测试 |
| 错误处理策略 | 沿用 `IntelligenceException(status, code, message)` 透传模式；worker 内错误吞掉记 WARN/DEBUG（照 reaper 既有模式） |
| 配置变更 | 见 §2.9；三个新属性都有默认值，`application.yml` 不强制登记（`@Value` 默认值即文档） |
| 兼容/发布 | 无迁移、无发布顺序约束；前后端可独立部署（前端旧版遇到新后端：排队会话会走到 offer 409，行为优于现状） |

### 决策记录

#### D-01：reaper 内建 finalize pass（REQ-001）

- 决策：`DigitalHumanSessionReaper` 在每次扫描内新增收尾阶段——对 `state='ending' AND state_entered_at < now - 宽限` 的行执行 `runtime.end(sessionId,"reaper_finalize")`（错误吞掉）→ `UPDATE dh_session SET state='ended', ended_at=COALESCE(ended_at,now()), state_entered_at=now(), version=version+1, updated_at=now() WHERE id=… AND state='ending'`（无 owner 过滤；CAS 保证与用户 end() 幂等互斥）。宽限期默认 15s（`digital-human.reaper.finalize-grace-seconds:15`），宽限目的：避开用户主动 end() 的同步收尾窗口，不与其竞态。
- 原因：收尾依赖的 runtime→Java 回执链未接线且属执行环范围；同步收尾是唯一不越范围的闭环。
- 放弃方案：①等执行环接线（超范围）；②独立 cleanup worker（收尾语义与 reaper 强耦合，拆开引入跨 worker 排序问题）。
- 决策依据/权限：授权规划者（用户需求 #1 的直接实现）。
- 约束级别：冻结的可观察契约（ending→ended 时序与幂等）；内部 SQL 写法可等价调整。

#### D-02：新建 promotion worker + 提取共享派发（REQ-002）

- 决策：新建 `DigitalHumanSessionPromotionWorker`（`@Scheduled(fixedDelayString="${digital-human.promotion.poll-interval-ms:5000}")`，`digital-human.promotion.enabled:true` 开关+running CAS，照 reaper 范式）。每轮：事务内锁 `dh_catalog` 单例行（与 `allocateAndInsert` 同一串行化点）→ 复查 active<max → 认领最老 `queued`（`FOR UPDATE SKIP LOCKED`）→ CAS `queued→preparing`；事务外调 `DigitalHumanSessionService.dispatchRuntime(row)`（从 `create` 提取：avatar 绑定装配 → `runtime.createSession` → CAS `preparing→connecting`）。**派发失败（任何错误）→ CAS 回 `preparing→queued`（刷新 state_entered_at）+ WARN，下轮重试**；寿命由既有 `expires_at` 与 queued 60s 超时兜底。
- 原因：queued→connecting 的晋升是 #105C 状态机既有语义但从未实现；复用 create 的派发段避免两套 wire。
- 放弃方案：①容量满直接 429 砍掉排队（改变 105C 已冻结的排队语义）；②晋升失败标 failed（runtime 短暂不可用会误杀排队会话，回退重试更稳）。
- 约束级别：冻结（认领串行化点、失败回退语义）；提取手法可裁量。

#### D-03：晋升写 `session.state` 事件（REQ-004 服务端半）

- 决策：promotion worker 在 CAS `preparing→connecting` 成功后调 `EventService.append(sessionId, UUID.randomUUID(), "session.state", 行leaseEpoch, Map.of("state","connecting"))`；append 幂等/eventId 去重/旧 epoch 丢弃均由既有实现承担。reaper 的 ending/ended 转移**不**写事件（§1.4）。
- 原因：前端 SSE 订阅已存在且 `applySessionEvent` 已处理 `session.state`；dh_event 是 SSE 唯一数据源，不写事件前端永不知晓晋升。
- 放弃方案：前端纯轮询（SSE 已在排队期建立，事件路径延迟最低）。
- 约束级别：冻结（事件类型与 payload 形状）；轮询兜底见 D-05。

#### D-04：offer 状态闸（REQ-003）

- 决策：`webrtcOffer` 在 `assertSessionLease` 之后、转发 runtime 之前加载会话状态（复用 `sessions.get` 或等价单行查询）：`connecting/ready/listening/responding/reconnecting` → 放行（现状）；`queued/preparing` → 409 `dh_session_queued`「会话正在排队等待空闲名额，请稍候。」；`ending/ended/failed` → 409 `dh_state_conflict`「会话状态已变化。」。
- 原因：lease 断言先保证越权/失联语义不变；闸只拦「会话未在 runtime 侧存在」与「已终态」两类。
- 放弃方案：复用 `dh_state_conflict` 一个码（排队是可等待状态，与终态混淆会让前端无法区分可重试）。
- 约束级别：冻结（状态集合、错误码、顺序）；查询实现可裁量。

#### D-05：前端排队等待（REQ-002/004 前端半）

- 决策：采用「派发连接器注入」把接通编排移进会话域 composable，视图净零增行（`DigitalHumanWorkbench.vue` 现距 800 硬顶仅 2 行余量）。`useDigitalHumanSession` 新增两个成员：①`setDispatchConnector(fn: (session: Session) => Promise<void>)`——视图在装配期注册 `(s) => connectMedia(s)`（媒体 composable 与会话 composable 互不依赖，装配顺序不受影响）；②内部 `waitForDispatch()` 轮询兜底。`start()`/`resume()` 语义变为：建会话/接管成功后，`state !== 'queued'` → 立即调连接器（与现状「create 后 connectMedia」等序）；`state === 'queued'` → 启动 `waitForDispatch`（3s 轮询 `api.getSession`，把 state/leaseEpoch/mediaEpoch/pausedUntil/expiresAt 合并进 `session.value`，同 `applySessionEvent` 字段集），当 state 离开 queued 变为非终态时调连接器恰一次；终态/`isCurrent` 失效/连续约 70s 停止。Workbench 的 `onEvent`（SSE `session.state` → `applySessionEvent` 更新 `session.value.state`）与轮询双通道都会触发「离开 queued」判定。视图改动＝删两处 `await connectMedia(...)`、加 `setDispatchConnector` 注册两行，净约 0 行；`connectEvents` 照旧在视图无条件调用（排队期 SSE 即时感知晋升）。
- 原因：SSE 依赖 dh_event 且事件链今天无其他生产者，轮询兜底保证断连场景也能接通；连接器注入让排队编排留在域 composable（R-LAYER），同时守住体积门禁。
- 放弃方案：①只靠 SSE（断连错过唯一晋升事件即永不接通）；②Workbench 内加状态 watcher（+8~10 行，视图将超 800 硬顶）。
- 约束级别：冻结（触发条件/字段集/停止条件/恰一次语义/视图净零增行）；连接器注册的实现形式可等价调整。

#### D-06：卡顺序与写入隔离

- 决策：C-01（offer 闸）→ C-02（reaper finalize）→ C-03（promotion+事件+提取）→ C-04（前端）→ C-05（集成+文档）。C-02 与 C-03 无共享文件（reaper 不改、SessionService 只在 C-03 提取）；C-03/C-04 均触碰 `session` 状态语义，先后端后前端保证前端可对着新后端行为开发；C-05 责任卡收口。
- 约束级别：冻结（执行顺序）；理由＝容量释放先于晋升才有端到端意义。

### 3.1 端到端接线与职责

| 链路段 | 当前入口/符号 | 本次改动或复用 | 上游输入 → 下游交付 | 负责卡 | 接通证据 |
|---|---|---|---|---|---|
| offer 闸 | `DigitalHumanConnectionController.webrtcOffer` | 状态查询+409 分支 | 请求 → 状态判定 → runtime 转发或 409 | C-01 | TC-C01-001/002 |
| 废弃收尾 | `DigitalHumanSessionReaper.runScheduled` | finalize pass（runtime.end+CAS ended） | ending 行（过宽限）→ ended 行 | C-02 | TC-C02-001/002 |
| 排队晋升 | `DigitalHumanSessionPromotionWorker`（新） | 认领+`dispatchRuntime`（自 SessionService 提取） | queued 行 → runtime createSession + connecting 行 + dh_event | C-03 | TC-C03-001/002/003 |
| 晋升感知（服务端） | `DigitalHumanEventService.append` | 新调用方（worker） | connecting 转移 → `session.state` 事件 → SSE | C-03 | TC-C03-004 |
| 晋升感知（前端） | `useDigitalHumanSession.setDispatchConnector`（新）+视图注册 | queued 不连媒体；状态离开 queued 由 composable 触发连接器恰一次 | session.state 变化 → 画面接通 | C-04 | TC-C04-001/002 |
| 集成闭环 | docker 栈 + 浏览器探针 | C-05 责任卡 | SC-01/02/03 全链 | C-05 | V-04 |

---

## 4. 目标行为

### 4.1 用户流程（SC-02 主流程，差异处标注）

1. 用户进入数字人工作台，选中角色点「查看费用并开始」→ 确认。
2. 系统预检通过；创建时全局名额被占 → 会话以 `queued` 返回（既有）。
3. 前端展示会话视图：状态徽标「排队中」，画面区「等待媒体…」，**不发起 offer**（新）。
4. 占用者会话结束（用户点结束或被 finalize）→ promotion worker ≤5s 认领 → `queued→preparing→connecting` + 写 `session.state` 事件（新）。
5. 前端经 SSE/轮询感知 → `connectMedia`：offer（此时状态 connecting，闸放行）→ answer → media-ready → 画面出帧（新）。
6. 失败时：runtime 派发异常 → 会话回 queued 重试（服务端 WARN）；60s 排队超时 → ending → finalize → ended（页面显示终态面板，既有语义）。

### 4.2 行为变化表

| 场景 | 当前行为 | 目标行为 |
|---|---|---|
| 关页废弃会话 | 卡 ending 永久占名额 | ≤约 25s 收敛 ended，名额释放 |
| 名额被占时开始 | queued 排队 60s 后死亡 | 排队等待，名额空出 ≤10s 自动接通 |
| queued 会话收到 offer | 转发 runtime → 404「runtime 上游拒绝」 | 409 `dh_session_queued`，runtime 零调用 |
| 终态会话收到 offer | 转发 runtime → 404 | 409 `dh_state_conflict` |
| connecting/ready 会话 offer（含重连） | 正常中继 | 不变（回归保护） |
| 用户主动结束 | 同步 ending→ended | 不变（宽限期避开竞态） |
| 排队期间点「结束会话」 | ending | 不变，随后被 finalize 收尾 |
| 排队期间刷新页面 | 深链/接管，resume 后照常（offer 打穿） | 接管后仍 queued → 同 SC-02 等待逻辑 |

### 4.3 状态定义（业务对象状态权威源：`dh_session.state`，前端 `session.value.state` 为投影）

| 状态 | 进入条件 | 可执行操作 | 展示内容 | 离开条件 |
|---|---|---|---|---|
| `queued`（本次赋予完整语义） | 创建时 active≥max | end（既有 allowedActions） | 徽标「排队中」+画面区「等待媒体…」 | 晋升→connecting；超时→ending；用户 end→ending |
| `preparing` | 建立瞬间/promotion 认领（瞬态） | end | 同 connecting 前置 | 派发成功→connecting；派发失败→回 queued（仅 promotion 路径） |
| `ending` | 用户 end/reaper 各超时 | 无 | 终态过渡 | finalize→ended |
| 其余状态 | 既有 | 既有 | 既有 | 既有 |

UI 请求状态：排队等待期 `starting=false`（创建已成功）、`mediaConnecting=false`（未发起）、SSE `status` 正常子集——不新增 UI 状态枚举。异步作业状态＝会话状态本身（权威在 DB）。

### 4.4 状态迁移规则（新增/变更部分）

```text
queued -> preparing：promotion worker 认领（catalog 行锁下容量复查通过；唯一入口）
preparing -> connecting：dispatchRuntime 成功（同时写 session.state 事件）
preparing -> queued：dispatchRuntime 失败回退（仅 promotion 路径；create 路径失败仍 -> failed，现状不变）
ending -> ended：用户 end() 同步收尾（既有）或 reaper finalize（新，宽限 15s 后）
非法迁移拒绝：CAS WHERE state=<expected> 不匹配即无操作（幂等），不报错给调用方
并发：两 worker/用户 end 同时推进 ending→ended -> 恰一次生效（CAS），后到者无操作
刷新/重开：queued 会话接管后进入同一等待逻辑（takeover 路径与 start 同构）
```

---

## 5. 业务规则

### 5.1 输入规则

本任务无新用户输入字段。offer 请求体字段（`type/mediaEpoch/leaseEpoch/sdp/requestId`）不变（§6）。

### 5.2 校验规则

| 规则编号 | 校验条件与先后顺序 | 校验位置 | 失败结果/文案 | 不允许发生的副作用 | TC |
|---|---|---|---|---|---|
| RULE-001 | offer：形状（既有 422）→ owner/lease（既有 403/404/409）→ **状态闸**：`queued/preparing` 拒、终态/ending 拒、其余放行 | `DigitalHumanConnectionController.webrtcOffer`（服务端权威） | 409 `dh_session_queued`「会话正在排队等待空闲名额，请稍候。」/ 409 `dh_state_conflict`「会话状态已变化。」 | 状态不符时 runtime 不得收到任何请求 | TC-C01-001/002/003 |
| RULE-002 | finalize：仅 `state='ending' AND state_entered_at < now()-宽限(默认15s)` 的行可收尾；每轮批量≤100（照 reaper limit） | reaper 扫描后置阶段 | 不满足则不动 | 未过宽限的 ending 行不得被碰（避开用户 end() 竞态） | TC-C02-002 |
| RULE-003 | promotion：`dh_catalog` 单例行 `FOR UPDATE` 下 `active<maxSessionsGlobal` 且存在 queued 才认领；每轮至多认领 `max-active` 个（实现可每轮 1 个，语义上不许超发） | promotion worker 事务内 | 不满足则本轮空转 | 不得绕过 catalog 行锁直接认领（会与 create 并发超发） | TC-C03-001/002 |
| RULE-004 | 前端：`session.state==='queued'` 期间 MUST NOT 调用 `connectMedia`/发起 offer | Workbench + useDigitalHumanSession | —（结构性不发起） | 排队期不得出现 offer 请求 | TC-C04-001 |

### 5.3 业务判断规则

```text
RULE-005 offer 状态闸：
IF state IN (connecting, ready, listening, responding, reconnecting) THEN 转发 runtime（现状）
ELSE IF state IN (queued, preparing) THEN 409 dh_session_queued
ELSE（ending/ended/failed）THEN 409 dh_state_conflict

RULE-006 finalize：
IF ending 行且过宽限 THEN runtime.end（吞错）→ CAS ending→ended（含 ended_at）
ELSE 跳过

RULE-007 promotion 派发失败：
IF dispatchRuntime 抛错 THEN CAS preparing→queued（刷新 state_entered_at）+ WARN，本轮结束
（不 markInitFailed、不重置 lease/mediaEpoch；寿命由 expires_at/queued 60s 超时兜底）

RULE-008 前端接通触发：
IF session.state 从 queued 变为非 queued 非终态 THEN connectMedia(session)（一次，防重入）
IF 轮询/SSE 得到终态 THEN 走既有 sessionTerminal 面板
```

### 5.4 权限与业务不变量

| 主体/角色 | 资源关系 | 允许操作 | 禁止操作及拒绝结果 | 测试编号 |
|---|---|---|---|---|
| 会话 owner | 本人会话 | offer/end/接管（既有） | 跨账号 offer → 404/403（既有，闸不得改变该顺序结果） | TC-C01-003 |
| 系统worker（reaper/promotion） | 全局会话 | 状态单向推进+收尾+派发 | 不得动账务/租约 epoch/mediaEpoch；不得复活终态 | TC-C02-001/003 |
| 匿名/未登录 | — | — | 401（既有） | 既有回归 |

不变量：①`ended/failed` 永不回退（所有新 CAS 带 `WHERE state=…`）；②同一会话 `runtime.createSession` 派发至多成功一次（promotion CAS queued→preparing 保证唯一持有者；runtime 幂等 receipt 兜底）；③`dh_event` 每个晋升恰好一条 `session.state/connecting`（eventId 随机、失败重试产生的新事件为追加行，SSE/前端按状态幂等合并，无副作用）；④容量计数口径不变（active = 非 queued 非终态）。

校验失败副作用：offer 被闸拒绝时——runtime 零请求、dh_event 零写入、会话行零变更。

---

## 6. 接口契约

### 类型与调用签名

```java
// 新增（C-03）：DigitalHumanSessionService 内提取，create 与 promotion 共用
// 完整语义＝现 create 的「提交后派发」段（DigitalHumanSessionService.java 95–118 行）：
//   SELECT avatar_id/avatar_revision FROM dh_profile_revision（缺行回落 null/0，不阻塞）
//   → 构造 SessionBinding → runtime.createSession(binding)
//   → 成功：casState(preparing→connecting)；返回推进后行
//   → 失败：抛原始错误（调用方决定回退策略；create 路径维持 markInitFailed+translate 现状）
public Mono<DigitalHumanSessionService.SessionRowView> dispatchRuntime(SessionRowView row)
// 注：SessionRowView 为包内 record（同文件既有），无需公开到文件外

// 新增（C-02）：DigitalHumanSessionReaper 私有阶段，无公开新签名；
// 构造器新增 @Value("${digital-human.reaper.finalize-grace-seconds:15}") int graceSeconds

// 新增（C-03）：DigitalHumanSessionPromotionWorker
// 构造器注入 DatabaseClient/TransactionalOperator/DigitalHumanSessionService/
//   DigitalHumanRuntimeClient（如 dispatchRuntime 吸收了 runtime 调用则不需要）/
//   DigitalHumanEventService；@Value("${digital-human.promotion.enabled:true}") boolean enabled
```

```ts
// 新增（C-04）：useDigitalHumanSession 返回项追加（签名节选，类型引用同文件既有 Session）
setDispatchConnector: (fn: (session: Session) => Promise<void>) => void
// 内部行为（D-05）：start/resume 成功后 state!=='queued' → 立即 fn(session)（等序替代视图原 connectMedia 调用）；
// state==='queued' → 内部 waitForDispatch()（3s 轮询合并状态；离开 queued→非终态触发 fn 恰一次；
// 终态/isCurrent 失效/约 70s 停止）；未注册 fn 时只更新状态不接通
```

### 6.1 API-001：offer 中继（既有端点，新增 409 分支）

- 请求方法/路径/登录/权限/幂等/载体/Edge 登记/身份传递/超时：全部不变（现状见 `DigitalHumanConnectionController.webrtcOffer` 与 `DigitalHumanOfferIT`）。
- 唯一变更＝错误契约（§6.4）；成功响应不变。

### 6.2–6.3 请求参数 / 成功响应

不变（复用既有 `OfferRequest` record 与 answer 信封）；本任务不新增请求字段。

### 6.4 错误契约（新增行）

| HTTP 状态 | 实际错误字段与值 | 触发条件 | 用户文案 | 调用方动作/可重试性 |
|---|---|---|---|---|
| 409 | `error.code="dh_session_queued"` | 会话 state ∈ {queued, preparing} | 会话正在排队等待空闲名额，请稍候。 | 可等待后重试（正常流程由 C-04 结构性不触发） |
| 409 | `error.code="dh_state_conflict"` | 会话 state ∈ {ending, ended, failed} | 会话状态已变化。 | 不可重试（刷新会话态） |

既有 422/404/409(dh_lease_stale)/503(dh_runtime_unavailable) 契约原样保留（错误信封格式照 `IntelligenceException` 现有透传，勿新造结构）。

### 6.5–6.7 错误处理原则 / 不变量 / 流式契约

- 前端 offer 失败展示沿用 `useDigitalHumanMedia` 的 `errorCode='offer_rejected' + errorMessage=服务端文案`，不新增映射。
- 不变量：闸拒绝时 runtime 调用数恒 0（fake transport 计数断言）；新错误码仅 `dh_session_queued` 一个，MUST NOT 再发明其他码。
- SSE/上传/异步：SSE 既有订阅不动；`session.state` 事件 payload=`{"state":"connecting"}`（leaseEpoch 取 append 时行值；mediaEpoch 不带，applySessionEvent 保持现值）。

---

## 7. 数据模型与迁移

- 无新表/新列/新迁移（`dh_event.event_type` 无枚举约束，`session.state` 直接可写；ended_at 列已存在）。
- 事务边界：promotion 认领事务（catalog 锁+容量复查+CAS queued→preparing）与派发（事务外网络调用）分离，照 create 既有模式；finalize 的 runtime.end 在 CAS 之外吞错。
- 并发兜底：catalog 单例行 FOR UPDATE（create 与 promotion 共用串行化点）；CAS 状态条件；runtime 命令幂等 receipt。
- 数据生命周期：ended 会话的既有清理链（历史/注销/清理 worker）不受影响；本任务不新增保留/清理义务。

---

## 8. UI 实现规格（仅 C-04）

- 页面归属：AI 创作端 `DigitalHumanWorkbench.vue`；设计规范＝根 `DESIGN.md`（本次零样式/零布局/零 token 变更，排队中文案与徽标既有）。
- 组件行为：`DigitalHumanStage` 排队期展示「等待媒体…」覆盖层 + 状态行「会话状态：排队中」——既有渲染，仅需保证不因未调用 connectMedia 而出现错误态（`connecting=false, errorCode=null`）。
- 交互细节：排队期「结束会话」按钮可点（既有）；输入框禁用态由 `session-state` prop 既有逻辑决定（queued 不在可发言集合）——不新增交互。
- 截图自查：N/A：无视觉变更；行为正确性由 TC-C04 单测与 C-05 浏览器集成证据（截图证明排队态+接通态）覆盖。

---

## 9. 全局约束（每张卡适用）

### 9.1 文件白名单 / 黑名单

| 标识 | 精确路径 | 权限 | 本次操作 | 允许修改的符号/段落 | 原因与完成标准 | 所属卡 |
|---|---|---|---|---|---|---|
| W01 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanConnectionController.java` | 写入 | 修改 | `webrtcOffer`（状态闸）及其私有辅助 | 409 分支+闸拒绝零 runtime 调用 | C-01 |
| W02 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanOfferIT.java` | 写入 | 修改 | 新增 @Test 用例 | TC-C01-001/002/003/004 | C-01 |
| W03 | `platform-java/.../digitalhuman/DigitalHumanSessionReaper.java` | 写入 | 修改 | finalize 阶段+graceSeconds 配置+类 javadoc | ending 过宽限→ended；宽限内不动 | C-02 |
| W04 | `platform-java/.../digitalhuman/DigitalHumanReaperTest.java` | 写入 | 修改 | 新增 @Test；必要时加 `@TestConfiguration` fake runtime（照 SessionIT 范式） | TC-C02-001/002/003 | C-02 |
| W05 | `platform-java/.../digitalhuman/DigitalHumanSessionService.java` | 写入 | 修改 | 提取 `dispatchRuntime`；`create` 改调用它（行为零变化） | create 路径回归全绿 | C-03 |
| W06 | `platform-java/.../digitalhuman/DigitalHumanSessionPromotionWorker.java` | 写入 | 新建 | 全文件 | RULE-003/007 全部满足；被 Spring 组件扫描装配 | C-03 |
| W07 | `platform-java/.../digitalhuman/DigitalHumanPromotionIT.java` | 写入 | 新建 | 全文件（IT，含调度驱动用例照 WorkerSchedulingIT 范式） | TC-C03-001/002/003/004 | C-03 |
| W08 | `src/views/digital-human/DigitalHumanWorkbench.vue` | 写入 | 修改 | `handleStartConfirm`/`handleTakeover`（queued 不连媒体）+新增 state watcher | RULE-004/RULE-008 | C-04 |
| W09 | `src/views/digital-human/composables/useDigitalHumanSession.ts` | 写入 | 修改 | 新增 `waitForDispatch`（轮询兜底）+返回项 | 兜底轮询字段集/停止条件符合 D-05 | C-04 |
| W10 | `src/views/digital-human/composables/useDigitalHumanSession.test.ts` | 写入 | 修改 | 新增用例 | TC-C04-001/002/003 | C-04 |
| W11 | `docs/任务书/README.md` | 写入 | 修改 | 索引表新增 #108 行 | docs:links 通过 | C-05 |
| W12 | `docs/任务书/草场任务书-108-数字人会话收尾排队晋升与Offer状态闸.md` | 写入 | 修改 | 仅实施状态列/版本记录（收口时） | 状态如实 | C-05 |
| R-只读 | `DigitalHumanEventService.java`、`DigitalHumanRuntimeClient.java`、`DigitalHumanRecords.java`、`V88__digital_human_core.sql`、`routes_internal.py`、`useDigitalHumanMedia.ts`、`useDigitalHumanEvents.ts`、`useDigitalHumanApi.ts`、`DigitalHumanSessionIT.java`、`DigitalHumanWorkerSchedulingIT.java` | 只读参考 | — | — | 复用/核验依据 | 各卡 |
| 生成物 | `test-artifacts/task-108/`（截图/日志/探针输出）；`scripts/local/dh-108-integration.mjs`（临时探针，不入库——scripts/local 被 Git 忽略） | 生成 | 新建 | — | 集成证据；不混入源码提交 | C-05 |

黑名单：`platform-realtime/**`（本任务不改 runtime）、`deploy/**`、`platform-java/**/db/migration/**`（无迁移）、`src/style.css`/`DESIGN.md`（无样式变更）、其余未列文件一律禁改。任务前已有改动：无（基线干净）。

### 9.2 项目铁律速查（命中项）

- **R-JAVA**：WebFlux 事件循环禁阻塞——finalize/promotion 的网络调用走既有响应式 client（禁止 `.block()`）；worker 照 reaper 的 `running CAS + subscribe()` 范式。→ C-02/C-03。
- **R-DATA**：无新迁移；CAS/唯一约束即并发兜底；不得为测试改已执行迁移。→ 全部。
- **R-AI/R-计费**：不触碰执行环/经济链（promotion 派发不含任何计费调用，与 create 派发段同构）。→ C-03。
- **R-QUALITY**：Java 测试沿用模块 `src/test`；`WebTestClient` 带 30s responseTimeout 惯例；Gradle 不得以 UP-TO-DATE 冒充实跑（针对性 --tests 或 --rerun-tasks 说明）；前端 Vitest 沿用既有文件位；任务书索引 W11。→ 全部。
- **R-LAYER**：`DigitalHumanWorkbench.vue` 现 798 行（800 硬顶，无豁免），C-04 净增 MUST ≤ 0 行（D-05 连接器注入设计已保证）；排队等待域逻辑一律进 `useDigitalHumanSession`。→ C-04。
- **R-LIFECYCLE**：会话/事件资源属主与清理链既有，本任务只加状态推进生产者，无新登记项（`resource-lifecycle.registry.json` 不需更新——无新资源种类）。→ N/A（理由如左）。
- **R-DIR/R-SAFE/R-UI/R-ENTRY**：产物归 `test-artifacts/task-108/`；临时脚本 `scripts/local/`；凭据不入证据；无样式/入口变更。→ C-04/C-05。

### 9.3 验证环境事实

| 项目 | 本任务的精确值/检查方式 |
|---|---|
| 仓库根/命令 shell | `/Users/LXH/claude/y-1`；zsh（Java 命令在 bash 会话 source helper） |
| Node/npm | 仓库既有（执行 `node --version` 记录即可；依赖已按 lock 安装） |
| Java/Gradle | `cd platform-java && source ../scripts/lib/java-runtime.sh && ensure_java_runtime 25`；`./gradlew --version` 确认 JDK 25 toolchain |
| Docker/依赖服务 | 本地 docker 栈已在运行（y-1 项目：postgres/edge/intelligence/frontend 等；dh-runtime 为本机裸跑进程 9443）；IT 用 Testcontainers 自备 PG/Redis |
| 入口地址 | 前端 `http://127.0.0.1:8084`（AI 端）；e2e 账号 `e2e-merchant@test.local`（仅集成卡使用，凭据不写入任何文件） |
| 隔离与归属 | Java IT＝Testcontainers 隔离库；集成卡＝本地演示栈（`docker compose --env-file .env.docker -f docker-compose.yml -f deploy/digital-human/compose.production.yml`，**必须双文件**，单文件会 recreate intelligence 丢 overlay env） |
| 产物目录 | `test-artifacts/task-108/` |

### 9.4 安全、性能与兼容规格

| 类别 | 项目 | 唯一要求/阈值 | 验证用例 |
|---|---|---|---|
| 安全 | offer 闸不改变越权语义 | 闸在 owner/lease 断言之后；跨账号结果与现状一致 | TC-C01-003 |
| 隐私 | 证据脱敏 | 截图/日志不含凭据/token | C-05 证据审阅 |
| 性能 | worker 批量上限 | finalize ≤100/轮（照 reaper limit）；promotion 认领 ≤(max-active) 个/轮且实现上每轮 1 个即可 | 代码审查+TC-C03-002 |
| 异步资源 | 定时器/轮询释放 | 前端轮询在终态/generation 变更/离开 queued 即停（无泄漏 interval）；worker 有 running CAS 防重入 | TC-C04-003 |
| 兼容 | 旧前端+新后端 | 排队会话旧前端会收到 offer 409（优于现状 404）；新前端+旧后端：轮询兜底不依赖新接口 | — |

### 9.5 仓库约束适用矩阵

| 约束 | 适用卡号 | 落实动作 |
|---|---|---|
| R-UI | N/A | 无样式/视觉变更（§8） |
| R-ENTRY | N/A | 无入口/路由/部署契约变更 |
| R-JAVA | C-02/C-03 | 响应式 worker 范式、无阻塞 |
| R-DATA | 全部 | CAS/无迁移/幂等 |
| R-AI | N/A | 不触碰执行环与计费（promotion 派发段与 create 同构） |
| R-QUALITY | 全部 | §12.3 命令；索引 W11 |
| R-LAYER | C-04 | 视图纯装配，域逻辑进 composable |
| R-LIFECYCLE | N/A | 无新资源种类（理由 §9.2） |
| R-DIR | 全部 | 产物/脚本归位 |
| R-SAFE | 全部 | 基线保留/脱敏/不放宽断言 |

---

## 10. 开发计划与任务总表

| 卡 | 标题 | 端 | 对应需求 | 主要写入文件 | 依赖及交付物 | 验收编号 | 执行状态 |
|---|---|---|---|---|---|---|---|
| C-01 | offer 状态闸（409 dh_session_queued / dh_state_conflict） | Java | REQ-003 | W01/W02 | 无 | AC-005；V-01 | NOT_STARTED |
| C-02 | reaper finalize 收尾（ending→ended+runtime.end 尽力而为） | Java | REQ-001 | W03/W04 | 无 | AC-001；V-01 | NOT_STARTED |
| C-03 | promotion worker+dispatchRuntime 提取+晋升事件 | Java | REQ-002/004 | W05/W06/W07 | C-02（容量释放语义先行；代码无依赖） | AC-003；V-01 | NOT_STARTED |
| C-04 | 前端排队等待（不连媒体+状态 watcher+轮询兜底） | 前端 | REQ-002/004 | W08/W09/W10 | C-03 后端行为（对着新语义开发） | AC-004；V-02/V-03 | NOT_STARTED |
| C-05 | 集成验收+文档收口（责任卡） | 全栈 | 全部 | W11/W12+生成物 | C-01～C-04 全 VERIFIED | AC-001~005；V-04/V-05 | NOT_STARTED |

阶段：M1＝C-01～C-03（服务端自洽：闸+收尾+晋升，IT 可全证）；M2＝C-04（前端接通）；M3＝C-05（浏览器全链+索引）。无循环依赖；C-02/C-03 无共享文件（W03/W04 与 W05/W06/W07 不相交），C-03 内 W05 只做提取不改行为。

### 10.4 高风险与验证前置

| 风险编号 | 假设/链路 | 影响 | 等级 | 最早验证 | 不成立时的处理 |
|---|---|---|---|---|---|
| RISK-01 | finalize 的 runtime.end 在 runtime 进程不存在时吞错后 CAS 仍收敛 | REQ-001 | 中（网络失败路径） | TC-C02-003（fake end 抛错→仍 ended） | 已按吞错设计，无需降级 |
| RISK-02 | promotion 与 create 并发超发容量 | REQ-002 | 高（名额超卖） | TC-C03-002（并发 1 active+2 queued+max=1 恰晋升 0/1） | 若复现→认领事务必须与 create 同锁点（已定 D-02），仍失败则 BLOCKED |
| RISK-03 | 前端错过唯一晋升事件（SSE 断连） | REQ-004 | 中 | TC-C04-002（仅轮询路径到达也接通） | 已设轮询兜底 |
| RISK-04 | `dispatchRuntime` 提取改变 create 既有行为 | 回归 | 中 | V-01 中 DigitalHumanSessionIT 全类回归 | 退回 C-03 修提取，不放宽断言 |

---

## 11. 任务卡

### 卡 C-01：offer 状态闸

**执行包**：任务书版本 1.0.0；对应需求 REQ-003；执行者=目标编码模型；负责人=执行者（卡级 VERIFIED 自判）。

**类型与完成边界**：实现；409 分支上线（TC 全绿+既有中继用例回归）；接线完整（闸生效于真实端点）。

**背景**：排队/终态会话的 offer 现在打穿到 runtime 变 404「runtime 上游拒绝」，用户费解（§2.6）。

**输入与前置交付物**：无前置卡。开工先跑 V-00 基线（§12.3）。

**输出与移交**：W01/W02 变更；后置卡 C-04 依赖其错误码 `dh_session_queued`。

**必读清单**：§4.2/§5.2 RULE-001/§5.3 RULE-005/§6.4；`DigitalHumanConnectionController.webrtcOffer`（86–105 行区域）；`DigitalHumanOfferIT`（fake transport 注入与计数断言范式）。

**改动文件**：W01（`webrtcOffer`：在 `grants.assertSessionLease` 之后插入状态判定；可加私有辅助 `assertOfferableState`）；W02（新增用例）。

**开始前检查**：①V-00 基线绿（或记录已知失败）；②核对 `webrtcOffer` 现行顺序＝形状→租约→转发；③`DigitalHumanSessionIT.FakeRuntimeConfig` 计数范式已知。

**源码定位**：

```text
platform-java/.../digitalhuman/DigitalHumanConnectionController.java
  webrtcOffer(...)：`.then(grants.assertSessionLease(actor, id, request.leaseEpoch()))
  .then(runtime.webrtcOffer(...))` 两行之间插入状态闸
状态来源：sessions.get(actor, id)（返回 SessionSnapshot，data.state）或等价单行查询（可裁量）
```

**本卡目标行为**：RULE-001/005；闸拒绝时 fake runtime transport 的 offer 调用数=0。

**函数级要求**：`webrtcOffer` — 输入不变；输出新增两个 409 分支（§6.4）；副作用：拒绝路径零 runtime 请求、零 DB 写入。MUST：状态查询失败（404）沿用既有语义；MUST NOT：改变 422/lease 断言顺序与结果；MUST NOT 发明新错误码。

**做法**：

| 步骤 | 文件/符号 | 动作 | 完成检查点 | 失败时处理 |
|---|---|---|---|---|
| 1 | W01 `webrtcOffer` | 插入状态闸+私有辅助 | 代码审查符合 RULE-005 | 编译错自查 |
| 2 | W02 | TC-C01-001/002/003/004 用例 | V-01 中 OfferIT 全绿 | 修实现不改断言 |
| 3 | — | 跑 SessionIT 回归（offer 中继路径共享） | 退出码 0 | BLOCKED 若既有用例语义冲突 |

**边界与异常场景**：

| 场景 | 触发 | 预期 | TC |
|---|---|---|---|
| C-01/E01 | queued 会话 offer | 409 dh_session_queued；runtime offer 计数 0 | TC-C01-001 |
| C-01/E02 | ended 会话 offer | 409 dh_state_conflict；runtime 计数 0 | TC-C01-002 |
| C-01/E03 | 非 owner offer（回归） | 既有 404/403，闸不先于租约断言生效 | TC-C01-003 |
| C-01/E04 | connecting 会话 offer（回归） | 200 answer，行为与现状逐字段一致 | TC-C01-004 |

**本卡禁止**：改 media-ready/playback-reset；改错误信封结构；动 W01 之外文件。

**验收**：命令验收 V-01（含 OfferIT+SessionIT）；AC-005：Given 排队会话，When POST offer，Then 409+`dh_session_queued` 且 runtime 调用数 0、会话行零变更。UI 验收 N/A（无 UI）。

---

### 卡 C-02：reaper finalize 收尾

**执行包**：版本 1.0.0；REQ-001；执行者同上。

**类型与完成边界**：实现；ending 过宽限自动收敛 ended（含 runtime 不可达路径）；不改变 reaper 既有认领语义。

**背景**：reaper 单向推进到 ending 后无人收尾（§2.5-2），废弃会话永久占名额。

**输入与前置交付物**：无。

**输出与移交**：W03/W04；C-03 的容量释放语义依赖本卡（运行时先后，代码无依赖）。

**必读清单**：§4.4 迁移规则、§5.2 RULE-002、D-01；`DigitalHumanSessionReaper` 全文（177 行）；`DigitalHumanReaperTest.seedSession` 范式；`DigitalHumanSessionIT.FakeRuntimeConfig`。

**改动文件**：W03（finalize 阶段+`graceSeconds` 构造参数/`@Value`+javadoc 补一句收尾语义）；W04（新用例；如需断言 runtime.end 被调用，加 `@TestConfiguration` 计数 fake——照 SessionIT 的 `FakeRuntimeConfig`，注意 reaper 构造器因此需注入 `DigitalHumanRuntimeClient`）。

**源码定位**：

```text
DigitalHumanSessionReaper.scanExpired(...)：认领 CAS 之后追加 finalize 阶段（同轮或独立方法均可）
认领 SQL 模式参照 CLAIM_SQL（SKIP LOCKED/limit 100）
runtime.end facade：DigitalHumanRuntimeClient.end(String sessionId, String reasonCode)
```

**本卡目标行为**：RULE-002/006；宽限默认 15s 可配。

**函数级要求**：finalize 阶段 — 输入=无（自查 ending 行）；副作用=`runtime.end`（吞一切错误）+CAS UPDATE（§D-01 SQL）；不变=未过宽限行零触碰、终态永不回退、不动 cleanup_pending/error_code/账务。MUST：UPDATE 带 `AND state='ending'` 与 `state_entered_at < :cutoff`；MUST NOT：对 failed 行做任何事。

**做法**：

| 步骤 | 文件/符号 | 动作 | 检查点 | 失败处理 |
|---|---|---|---|---|
| 1 | W03 | finalize 查询+runtime.end+CAS | 单轮内先认领后收尾（顺序无硬约束，宽限已避竞态） | 编译/风格自查 |
| 2 | W04 | TC-C02-001/002/003 | ReaperTest+V-01 绿 | 修实现 |
| 3 | W03 javadoc | 类注释补「收尾：ending 过宽限→ended（runtime.end 尽力而为）」 | 审查 | — |

**边界与异常场景**：

| 场景 | 触发 | 预期 | TC |
|---|---|---|---|
| C-02/E01 | ending 且 state_entered_at 早于 now-15s | ended+ended_at 置位；runtime.end 恰一次 | TC-C02-001 |
| C-02/E02 | ending 但未过宽限 | 行零变更（用户 end() 竞态保护） | TC-C02-002 |
| C-02/E03 | fake runtime.end 抛 503 | 仍收敛 ended（吞错） | TC-C02-003 |

**本卡禁止**：写 dh_event；改 CLAIM_SQL 状态集合；加 SSE 推送。

**验收**：V-01（ReaperTest）；AC-001：Given 废弃 ending 会话过宽限，When reaper 扫描，Then state=ended、同主人可新建、全局 active 计数减一。AC-002 由 C-05 集成复证。

---

### 卡 C-03：promotion worker + 派发提取 + 晋升事件

**执行包**：版本 1.0.0；REQ-002/004（服务端半）；执行者同上。

**类型与完成边界**：实现；queued 会话在容量空出后被认领派发为 connecting 并产生 `session.state` 事件；create 路径行为零变化（提取等价）。

**背景**：queued 无晋升 worker（§2.5-1），排队 60s 必死。

**输入与前置交付物**：C-02 VERIFIED（容量释放语义；无代码依赖）。

**输出与移交**：W05/W06/W07；C-04 前端依赖事件与状态语义。

**必读清单**：D-02/D-03、RULE-003/007、§4.4；`DigitalHumanSessionService.create`（95–118 行派发段+`SessionBinding` 装配+`casState`）；`DigitalHumanEventService.append`（幂等/旧 epoch 丢弃语义）；`DigitalHumanWorkerSchedulingIT`（`@TestPropertySource` 调度测试范式）。

**改动文件**：W05（提取 `dispatchRuntime(SessionRowView)`；`create` 派发段改调用；失败路径在 create 侧维持 `markInitFailed+translate` 原样——即回退策略由调用方持有）；W06 新建（认领事务+派发+事件+失败回退+`@Scheduled`/enabled/running CAS）；W07 新建 IT。

**源码定位**：

```text
DigitalHumanSessionService.create：`.flatMap(runtimeBinding -> runtime.createSession(...))` 起至
  `.map(updated -> new CreateResult(...)).onErrorResume(...)` 段＝提取目标
SessionBinding 装配含 dh_profile_revision 查询（defaultIfEmpty null/0）——随段迁移
promotion 认领：SELECT singleton/config FOR UPDATE（照 allocateAndInsert）→ active 计数 →
  SELECT oldest queued FOR UPDATE SKIP LOCKED → CAS queued→preparing RETURNING COLUMNS
```

**本卡目标行为**：RULE-003/006/007；晋升成功恰产生一条 `session.state`（payload `{"state":"connecting"}`）事件。

**函数级要求**：`dispatchRuntime(row)` — 输入=`SessionRowView`（含 profileId/profileRevision/lease/mediaEpoch/expires 等 COLUMNS 字段）；输出=成功推进后的行（Mono）；错误=原始透传（不吞）；副作用=runtime createSession+两段 CAS+（仅 promotion 调用方）事件写入放段外。不变=create 路径外部可观察行为逐项一致（V-01 SessionIT 回归）。MUST：promotion 认领在 catalog 行锁事务内复查容量；MUST NOT：派发失败标 failed 或动 lease/mediaEpoch/账务；MUST NOT 绕锁直取 queued。

**做法**：

| 步骤 | 文件/符号 | 动作 | 检查点 | 失败处理 |
|---|---|---|---|---|
| 1 | W05 | 提取+create 改调用 | SessionIT 全类回归绿（V-01） | 提取不等价→修到绿，禁改断言 |
| 2 | W06 | 新建 worker（认领/派发/事件/回退/调度） | 组件被扫描装配（IT 证） | 风格照 reaper |
| 3 | W07 | TC-C03-001/002/003/004 | V-01 绿 | 修实现 |

**边界与异常场景**：

| 场景 | 触发 | 预期 | TC |
|---|---|---|---|
| C-03/E01 | active=0,max=1,1 条 queued | 晋升 connecting+runtime createSession 恰一次+事件恰一条 | TC-C03-001 |
| C-03/E02 | active=1,max=1,2 条 queued | 本轮零认领；占用者 ended 后每轮至多晋升一个 | TC-C03-002 |
| C-03/E03 | fake createSession 抛 503 一次后恢复 | 首轮回退 queued（state_entered_at 刷新），次轮成功 | TC-C03-003 |
| C-03/E04 | 晋升后 SSE 通道查询（events after=0） | 能读到 `session.state/connecting` 一条 | TC-C03-004 |

**本卡禁止**：改 reaper；改 offer 闸（W01）；给 create 加新行为；写非 `session.state` 类型事件。

**验收**：V-01（PromotionIT+SessionIT+WorkerSchedulingIT 回归）；AC-003：Given 占用者结束（C-02 收尾），When 等待 ≤10s，Then queued 会话变 connecting 且 dh_event 有 `session.state` 事件、runtime createSession 恰一次。

---

### 卡 C-04：前端排队等待（派发连接器注入）

**执行包**：版本 1.0.0；REQ-002/004（前端半）；执行者同上。

**类型与完成边界**：实现；排队期结构性不发 offer；晋升感知双通道（SSE 既有+轮询兜底）自动接通；单测覆盖；视图净增 ≤0 行（体积门禁）。

**背景**：前端对 queued 会话照常 connectMedia → 打穿 404（§2.5-4）。

**输入与前置交付物**：C-03 VERIFIED（事件与状态语义）。

**输出与移交**：W08/W09/W10；C-05 集成消费。

**必读清单**：D-05（连接器注入全文）、RULE-004/008、§4.1/4.3；`DigitalHumanWorkbench.vue` 的 composable 装配区（424–470 行区域：`useDigitalHumanMedia(api, account)` 与 `useDigitalHumanSession(...)` 互不依赖）与 `handleStartConfirm`/`handleTakeover`；`useDigitalHumanSession.ts` 的 generation/ticket/isCurrent 机制、`start`/`resume` 与 `dispose`；`useDigitalHumanSession.test.ts` 既有用例形态（fake api 注入范式）；根 `DESIGN.md`（确认零样式变更前提下动工）。

**改动文件**：W08（`handleStartConfirm`/`handleTakeover` 删两处 `await connectMedia(...)`；装配区注册连接器两行）；W09（`setDispatchConnector`+`waitForDispatch`+start/resume 分支+返回项）；W10（用例）。

**源码定位**：

```text
src/views/digital-human/DigitalHumanWorkbench.vue
  装配区：`const { … } = useDigitalHumanMedia(api, account)`（447 行附近，解构含 connectMedia）
          `const { start, resume, end, … } = useDigitalHumanSession(api, account, {…})`
  handleStartConfirm：`await connectMedia(created)` 行删除（由 start 内部连接器接管）
  handleTakeover：`await connectMedia(current)` 行删除
src/views/digital-human/composables/useDigitalHumanSession.ts
  start()/resume() 尾部接 D-05 分支；新增 setDispatchConnector 与私有 waitForDispatch
  （轮询 interval 照本文件 heartbeat 的 generation/isCurrent 范式）
```

**本卡目标行为**：RULE-004/008；刷新/接管进入的 queued 会话走同一等待逻辑；连接器恰一次调用。

**函数级要求**：

`useDigitalHumanSession.ts` - `setDispatchConnector(fn)` 与 `start`/`resume` 扩展

- 完整签名：`setDispatchConnector: (fn: (session: Session) => Promise<void>) => void`；`start(preflight, saveTranscript): Promise<Session | null>`、`resume(takeover: boolean): Promise<void>` 签名不变。
- 输入：连接器=视图注册的媒体接通动作；未注册时行为=只更新 session 状态不接通（等价现状中「视图没调 connectMedia」的退化，不报错）。
- 输出：start 仍返回 created；排队路径在连接器触发前即返回（不 await 轮询完成）。
- 副作用：轮询只读 `api.getSession`+合并 `session.value` 字段（state/leaseEpoch/mediaEpoch/pausedUntil/expiresAt）；连接器恰一次（离开 queued→非终态时机）。
- 不变条件：排队期零 offer（连接器不触发即无 offer）；generation/ticket 失效时轮询即停且不触发连接器；非 queued 路径连接器在 `start`/`resume` 完成前调用（与现状 connectMedia→connectEvents 顺序一致）。
- 清理与失败：`dispose()`/`end()`/终态停止 interval；单次轮询失败静默重试不置 error。
- MUST：连接器触发恰一次（重复 SSE+轮询同时到达不双连，幂等守卫）；MUST NOT：在 composable import 媒体层（依赖注入方向=视图→composable）；MUST NOT 视图净增行>0。

- MUST 满足：1. RULE-004/008 全文 2. `npm run lint`（含体积门禁）通过
- MUST NOT：1. 样式/布局/新组件 2. 改 `useDigitalHumanMedia`（其 connect 幂等已够）3. 把等待逻辑留在视图

**做法**：

| 步骤 | 文件/符号 | 动作 | 完成检查点 | 失败时处理 |
|---|---|---|---|---|
| 1 | W09 | `setDispatchConnector`+`waitForDispatch`+start/resume 分支 | TC-C04-002/003 单测绿 | 修实现 |
| 2 | W08 | 删两处直连、注册连接器 | `wc -l` ≤798±2 且 `npm run lint` 绿；TC-C04-001 | 若超行数：按 D-05 收缩视图，不得加豁免 |
| 3 | W10 | 用例补齐 | V-02 绿 | — |

**边界与异常场景**：

| 场景 | 触发 | 预期 | TC |
|---|---|---|---|
| C-04/E01 | created.state==='queued' | 全程零 offer 请求（api 层 spy 断言）；徽标排队中；连接器未触发 | TC-C04-001 |
| C-04/E02 | SSE 与轮询先后都到达 connecting | 连接器恰一次（幂等守卫断言） | TC-C04-002 |
| C-04/E03 | 会话终态/组件卸载/generation 失效 | 轮询停止且连接器不触发（fake timers 断言无残留） | TC-C04-003 |
| C-04/E04 | 未注册连接器（回归容错） | start/resume 正常返回，session 状态照常更新，无异常 | TC-C04-004 |

**本卡禁止**：样式改动；新增组件；动 `useDigitalHumanMedia`/`useDigitalHumanEvents`；把逻辑写进模板。

**验收**：V-02/V-03；AC-004：Given 页面排队中，When 后端晋升（事件或轮询先到），Then 自动发起 offer 且 ≤10s 内 `videoWidth>0`（后半句由 C-05 实测，本卡以单测证触发链恰一次）。UI 截图 N/A（§8）。

---

### 卡 C-05：集成验收与文档收口（责任卡）

**执行包**：版本 1.0.0；全部 REQ；执行者同上；集成负责人=本卡执行者。

**类型与完成边界**：集成+文档；本地 docker 栈浏览器全链三场景实测+索引登记；此前卡全部 VERIFIED 为前置。

**输入与前置交付物**：C-01～C-04 VERIFIED 及其证据。

**输出与移交**：W11/W12+`test-artifacts/task-108/` 证据+`scripts/local/dh-108-integration.mjs`（临时，不入库）。

**必读清单**：§1.7 三场景、§12.3 V-04/V-05；`scripts/local/dh-end-active.mjs`（清场范式，本地已有；不可依赖则自写同构探针）；compose 双文件铁律（§9.3）。

**做法**：

| 步骤 | 动作 | 检查点 |
|---|---|---|
| 1 | `./gradlew :services:intelligence-service:check`（V-01 全量）+ 前端 `npm run test -- src/views/digital-human` + `npm run typecheck` | 退出码 0 |
| 2 | 重建 intelligence 容器（双文件 compose `up -d --build intelligence`）+确认本机 runtime 进程存活 | 容器 healthy、`9443` 探活 |
| 3 | 写临时探针脚本（Playwright，照 dh-end-active 的登录链）跑三场景：SC-01 废弃自愈（会话 A 开→关页→≤40s DB ended→新会话 B 直接 connecting 且出帧）；SC-02 排队晋升（A 占用→B 开始→断言 B 排队徽标+零 offer→结束 A→≤15s B 出帧）；SC-03 排队期直调 offer→409 文案断言 | 每场景证据：截图+DB 查询输出+时间戳日志入 `test-artifacts/task-108/` |
| 4 | W11 索引行+W12 实施状态收口；`npm run docs:links`、`npm run docs:status` | 退出码 0 |

**边界**：探针只动 e2e 账号数据；用后清场（结束活跃会话）；不动 dhrepro 容器。

**本卡禁止**：为让集成通过改断言/裁场景；把临时脚本提交入库。

**验收**：AC-001~005 逐条复证（§12.5）；证据含排队态与接通态截图（证明目标页面与状态，非 body 非空）。

---

## 12. 测试、验证命令与集成验收

### 12.1 需求追踪与验收覆盖

| 需求/不变量 | 实现卡 | 条款 | 边界 | AC | TC | 命令/步骤 | 证据 |
|---|---|---|---|---|---|---|---|
| REQ-001 | C-02 | RULE-002/006 | C-02/E01–E03 | AC-001/002 | TC-C02-001/002/003 | V-01；V-04 场景 1 | test-artifacts/task-108/ |
| REQ-002 | C-03/C-04 | RULE-003/004/007/008 | C-03/E01–E03、C-04/E01/E02 | AC-003/004 | TC-C03-001~003、TC-C04-001/002 | V-01/V-02；V-04 场景 2 | 同上 |
| REQ-003 | C-01 | RULE-001/005 | C-01/E01–E04 | AC-005 | TC-C01-001~004 | V-01；V-04 场景 3 | 同上 |
| REQ-004 | C-03/C-04 | D-03/D-05 | C-03/E04、C-04/E02 | AC-004 | TC-C03-004、TC-C04-002 | V-01/V-02 | 同上 |

### 12.2 测试用例（逐用例）

#### TC-C01-001：排队会话 offer 被 409 拒且零 runtime 调用

| 项目 | 内容 |
|---|---|
| 对应条款 | REQ-003/RULE-001/005/AC-005 |
| 风险/类别 | 高；负向 |
| 测试层级 | Controller IT |
| 实现位置 | `DigitalHumanOfferIT` 新增用例（照类内既有 fake transport 计数） |
| 前置数据 | e2e 合成账号；直接 INSERT `dh_session` state='queued'（照 ReaperTest.seedSession 列集）+合法 leaseEpoch |
| 输入 | POST `/api/digital-human/sessions/{id}/webrtc/offer`，合法形状+正确 leaseEpoch |
| 依赖模拟 | fake runtime transport（类内既有）；断言其 offer 计数=0 |
| 操作步骤 | 1) 建行 2) 发 offer 3) 断言 |
| 预期展示/响应 | 409；`error.code="dh_session_queued"`；文案含「排队」 |
| 预期副作用 | runtime offer 计数 0；dh_session 行 state/version 零变化 |
| 最终状态 | 会话仍 queued |
| 清理 | 类内既有自清 |
| 执行与证据 | V-01 |
| 防止假阳性 | 断言 fake 计数（若闸失效转发 runtime，fake 返回 answer 会 200 → 用例必败）；再断言响应码≠404（排除「404 旧路径」假通过） |

#### TC-C01-002：终态会话 offer 409 dh_state_conflict

同 TC-C01-001 结构，state='ended'；预期 409 `dh_state_conflict`；防假阳性：断言错误码非 `dh_runtime_unavailable`/非 404（证明没走到 runtime）。

#### TC-C01-003：跨账号 offer 越权语义不变（回归）

owner B 的 connecting 会话，账号 A 发 offer → 既有 403/404（照类内既有越权用例结果），错误码与现状一致；证明闸未前置于鉴权。

#### TC-C01-004：connecting 会话 offer 中继不变（回归）

既有 `tc105x_03_02_relayAnswerThenConnectReadyIdempotent` 原样保留并随 V-01 执行；不复制实现，仅登记为回归。

#### TC-C02-001：ending 过宽限自动收敛 ended 且 runtime.end 恰一次

| 项目 | 内容 |
|---|---|
| 对应条款 | REQ-001/RULE-002/AC-001 |
| 风险/类别 | 高；正向 |
| 测试层级 | IT（ReaperTest，Testcontainers 真 PG） |
| 实现位置 | `DigitalHumanReaperTest` 新增（类内需补 fake runtime `@TestConfiguration` 计数 end 调用） |
| 前置数据 | seedSession('ending')，`state_entered_at=now-60s` |
| 输入 | 直调 `scanExpired(now, 100)`（类内既有直调范式） |
| 依赖模拟 | fake runtime transport：end 返回成功 RuntimeState；计数 |
| 预期副作用 | state='ended'、ended_at 非空、runtime.end 计数=1 |
| 防假阳性 | 未过宽限对照行（TC-C02-002）零变更——若 finalize 误提前，对照行 ended 会令本用例失败 |

#### TC-C02-002：宽限期内 ending 零触碰

seed 两行：`now-60s`（应收敛）与 `now-5s`（不应）。断言后者 state/version/state_entered_at 均不变；前者 ended。

#### TC-C02-003：runtime.end 抛错仍收敛（吞错路径）

fake end 返回 `Mono.error(503)`；断言仍 ended、无异常逃逸（scanExpired 正常完成）。

#### TC-C03-001：容量空出即晋升+事件恰一条

| 项目 | 内容 |
|---|---|
| 对应条款 | REQ-002/004/RULE-003/D-03/AC-003 |
| 风险/类别 | 高；正向+集成 |
| 测试层级 | IT（PromotionIT，Testcontainers 真 PG+fake runtime） |
| 前置数据 | catalog `maxSessionsGlobal=1`；0 活跃；1 条 queued（state_entered_at=now-1s，未超时） |
| 输入 | 调度驱动（`@TestPropertySource digital-human.promotion.poll-interval-ms=250`，照 WorkerSchedulingIT 范式）或直调 worker 方法（两种都可，断言同） |
| 预期副作用 | state='connecting'；fake createSession 计数=1；`dh_event` 恰一条 `event_type='session.state'` 且 payload state='connecting' |
| 防假阳性 | 断言 fake createSession 计数=1（≠0 防未派发、≠2 防重复派发）；断言事件恰一条（count） |

#### TC-C03-002：容量占满不认领、释放后逐个晋升

max=1；1 active+2 queued → 轮询数轮断言三行状态不变；将 active 行置 'ended' → ≤3 轮内恰一个 queued→connecting、另一个仍 queued。

#### TC-C03-003：派发失败回退 queued 后次轮成功

fake createSession 第一次 `Mono.error(503)` 之后成功；断言首轮后 state 回 queued（state_entered_at 刷新）、次轮 connecting；期间 lease_epoch/media_epoch/version 无人工重置语义异常（version 因 CAS 自然+2，断言状态即可）。

#### TC-C03-004：晋升事件可经 SSE 通道读出

用既有 events 查询（`DigitalHumanEventService.open` 或 HTTP events 端点 after=0）断言能读到该事件（证明前端订阅路径可感知）。

#### TC-C04-001：排队期结构性零 offer

| 项目 | 内容 |
|---|---|
| 对应条款 | RULE-004/AC-004 前半 |
| 风险/类别 | 高；负向 |
| 测试层级 | 前端组合单测（Vitest，happy-dom 按既有文件级声明） |
| 实现位置 | `useDigitalHumanSession.test.ts` |
| 前置数据 | fake api：createSession 返回 state='queued' 的 Session；注册 spy 连接器 |
| 操作步骤 | start() → 前进 fake timers（3s×n） |
| 预期 | 连接器未触发；轮询 getSession 被调用≥1；session.state 保持 queued |
| 防假阳性 | 连接器 spy 计数=0 断言（若实现误接通即失败）；再断言 offer 类 api 零调用 |

#### TC-C04-002：SSE 与轮询先后到达恰一次接通

fake api 轮询第 2 次返回 state='connecting'；同时用 `applySessionEvent`（或直接改 session 状态的既有入口）模拟 SSE 先到——断言连接器恰一次（幂等守卫）；SSE 断连场景（仅轮询到达）同样恰一次。

#### TC-C04-003：终态/卸载停止轮询且不触发连接器

fake api 返回 ended → 断言 interval 清理（fake timers 无残留回调；dispose() 后再前进时间零调用）；连接器计数=0。

#### TC-C04-004：未注册连接器容错（回归）

不调 `setDispatchConnector` 直接 start（非 queued 返回）→ 正常返回、session 更新、无异常。

### 12.3 本任务验证清单

| 验证编号 | 适用卡 | 工作目录与 shell | 精确命令 | 前置/副作用 | 必需性 | 通过标准 | 证据 |
|---|---|---|---|---|---|---|---|
| V-00 | 开工基线 | `platform-java/`（bash） | `source ../scripts/lib/java-runtime.sh && ensure_java_runtime 25 && ./gradlew :services:intelligence-service:test --tests 'com.grassland.intelligence.digitalhuman.DigitalHuman*IT' --tests '*DigitalHumanReaperTest'` | Testcontainers；首次较慢 | 必需 | 退出码 0；已知失败如实记录 | 对话记录 |
| V-01 | C-01/02/03 | `platform-java/`（bash） | 分卡子集（`--tests` 按卡取）：C-01=`DigitalHumanOfferIT`+`DigitalHumanSessionIT`；C-02 追加 `DigitalHumanReaperTest`；C-03 追加 `DigitalHumanPromotionIT`+`DigitalHumanWorkerSchedulingIT`（四类名全限 `com.grassland.intelligence.digitalhuman.` 前缀） | Testcontainers；首次较慢 | 必需 | 退出码 0；报告含本卡新增用例被执行（非零用例） | build 报告+对话 |
| V-02 | C-04 | 仓库根 | `npm run test -- src/views/digital-human/composables/useDigitalHumanSession.test.ts src/views/digital-human/components` | 无 | 必需 | 退出码 0；用例数含新增 | vitest 输出 |
| V-03 | C-04 | 仓库根 | `npm run typecheck && npm run lint` | 无（lint 含体积门禁） | 必需 | 退出码 0 | 输出 |
| V-04 | C-05 | 仓库根 | 手工/探针：双文件 compose `up -d --build intelligence` → `node scripts/local/dh-108-integration.mjs`（自写探针，登录链照 dh-end-active 范式） | 本地栈重建；仅 e2e 账号数据；结束会话清场 | 必需 | 三场景断言全过；截图+DB 输出入 `test-artifacts/task-108/` | 证据目录 |
| V-05 | C-05 | 仓库根 | `npm run docs:links && npm run docs:status` | 无 | 必需 | 退出码 0 | 输出 |

注意：Gradle 需真跑——若显示 UP-TO-DATE/FROM-CACHE 覆盖目标类，用 `--rerun-tasks` 重跑并在证据中说明；前端 `npm run test` 确认非零用例。

### 12.5 最终集成验收与完成定义

- 负责人/责任卡：C-05（执行者）。
- 前置：C-01～C-04 VERIFIED；V-01/02/03 全绿。
- 集成清单：V-04 三场景 + V-05。
- 串联流程：SC-01（废弃→自愈→新建出帧）→ SC-02（排队→自动晋升→出帧）→ SC-03（排队直调 offer 409）。出帧判定=`videoWidth>0`（照既有探针手法，并滚动到会话区后截图——本机已修自动滚动，若探针页面未含该修复以手动 scrollTo 兜底）。
- 保留行为：用户主动结束、重连（connecting/ready 会话 offer）、跨账号越权、心跳/租约、历史/注销清理链——既有 IT 全绿即证。
- 证据：`test-artifacts/task-108/`（每场景：截图+时间戳日志+DB 查询输出；截图证明排队徽标与接通画面）。
- 交付状态：全部必需项 PASS 才可整任务 VERIFIED；集成未跑=IMPLEMENTED。

### 12.6 发布与回滚

N/A：本地交付；无生产发布。配置新键均有默认值，回滚＝还原代码即可（无迁移）。

### 12.7 边界目录与适用性（全任务一次）

| 编号 | 场景 | 负责卡/TC 或 N/A |
|---|---|---|
| E01 | 空输入 | N/A：无新输入字段（offer 422 既有） |
| E02 | 超长输入 | N/A：同上 |
| E03 | 重复提交/连点 | TC-C03-002（容量并发）；前端 starting 既有防重 |
| E04 | 断网/连接中断 | TC-C02-003（runtime 不可达）；TC-C04-002（SSE 断连轮询兜底） |
| E05 | 服务端/第三方错误 | TC-C03-003（派发失败回退） |
| E06 | 未登录/会话过期 | 既有 401 回归（V-01 含） |
| E07 | 无权限/非法状态迁移 | TC-C01-002/003；CAS 非法迁移无操作（TC-C02-002） |
| E08 | 成功但无数据 | N/A：无列表类新端点 |
| E09 | 数据过期/版本冲突 | leaseEpoch 断言既有（V-01 回归） |
| E10 | 刷新/深链/离开恢复 | C-04 设计含 takeover 路径；TC-C04-002 轮询恢复；集成场景 2 覆盖 |
| E11 | 快速切换账号/对象 | N/A：会话为个人域+generation 机制既有（TC-C04-003 证清理） |
| E12 | 卸载/失活在途请求/计时器 | TC-C04-003 |
| E13 | 缺字段/null/非法枚举 | N/A：无新输入 |
| E14 | 数值边界 | N/A：宽限/批量常量有默认值（代码审查） |
| E15 | 多客户端并发/乱序 | TC-C02-001（CAS 恰一次）+TC-C03-002 |
| E16 | 超时/取消已提交+重试幂等 | operation 幂等既有（V-01 SessionIT）；promotion 失败重试不破坏幂等（runtime receipt） |
| E17 | 跨账号/组织 | TC-C01-003 |
| E18 | 在途撤权/注销/清理 | 既有 Erasure/Cleanup IT 随 V-00/V-01 回归；本任务无新资源 |
| E19 | 旧数据/旧客户端 | §9.4 兼容行（新码仅追加） |
| E20 | 部分成功/事件重放/重启 | append 幂等（eventId）+promotion 重试（TC-C03-003）；worker 重启后下轮自愈（无内存态） |
| E21 | 日期/时区/金额 | N/A：无金额；时间全用服务端 now()（IT 直调注入时钟范式既有） |
| E22 | 大列表/长文案/分页 | N/A |

---

## 13. 阻塞规则

照模板 §13.1–13.3 全文适用（不裁剪）。本任务特别提示：若 C-03 提取 `dispatchRuntime` 时发现派发段与 create 的 operation/事务耦合超出「纯段提取」（例如 markInitFailed 必须内联），允许把提取边界调整为「promotion 复用 binding 装配+runtime 调用+CAS 三步、create 保留原段」的等价方案，但 create 行为零变化与 promotion 语义不变是硬约束；仍不可行则 BLOCKED（B 编号自增）。

---

## 14. 执行结果汇报

照模板 §14 全文适用：每卡对话汇报（卡号/状态/命令/目录/退出码/证据/偏差），全部卡+V-04/V-05 后整任务汇总；不新建独立报告文件；中断前输出 §14.1 续作检查点。

---

## 版本记录

| 修订版本 | 日期 | 变化原因与决策人 | 受影响条款/卡 | 是否重做发布检查 |
|---|---|---|---|---|
| 1.0.0 | 2026-09-27 | 首版发布（ZCode 规划；三条用户需求→D-01~D-06；基线 98dc80cd；发布前按模板附 B 逐项自查通过） | 全文 | — |
