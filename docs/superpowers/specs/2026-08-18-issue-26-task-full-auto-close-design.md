# Issue #26 任务满员自动关闭 设计

- 日期：2026-08-18
- 状态：已实现（2026-08-18；计划见 `docs/superpowers/plans/2026-08-18-issue-26-task-full-auto-close.md`，测试证据见进度指南横向缺口 #26 行）
- 范围：marketplace-service（核心）+ identity-service 通知模板 + 前端操作文案
- 登记：进度指南横向缺口 #26（PRD §2.2「报名人数满自动截止」）；推荐执行顺序第一批第 3 项
- 取代关系：worktree `feat/issues-26-32` 中未提交的 `TaskCapacityDispatcher` 轮询方案作废（理由见 D2），仅作设计参考，不合并不沿用

## 目标

最后一个名额「接受成功」的同一事务内，把任务从 `published` 原子迁移到 `closed`；满员任务不再出现在大厅；新报名得到明确的「任务已关闭」错误；自动关闭发出通知与操作结果文案；与手动 close 幂等兼容。

## 口径决策（已定死，勿另起方案）

| # | 决策 | 理由 |
|---|---|---|
| D1 | 满员口径 = **accepted 报名数 ≥ maxSlots**（不是 claim 占用数，也不是 PRD 原文的「报名人数」） | 用户 2026-08-18 拍板：资金预留与结算都按 accepted 计数；「报名人数」口径需先改产品参数，本版不采纳。claim 占用数含 `reserving`，预留失败会释放，按它关会在预留失败时误关 |
| D2 | 关闭时机 = **接受落定的同一事务内**（非资金型：`apps.accept` 之后；资金型：saga `activateEngagement` reserving→accepted 之后）。**不做轮询 dispatcher** | 需求原文「最后一个名额接受成功后，原子地把任务从 published 改为 closed」。旧 dispatcher 方案有 ≤60s 窗口、按 occupied 判定会在预留期间误关、不发事件、feed 未修，四点均不满足 |
| D3 | 判定实现 = 单条条件 UPDATE（见「关闭语义」），**无版本守卫**，0 行更新 = 无操作（不是错误） | 系统触发的状态迁移不参与用户乐观锁；并发下只有一个事务能把 `published` 改走，天然幂等 |
| D4 | 资金型任务：claim→`reserving` 阶段**不**判定关闭；`activateEngagement` 事务内判定关闭；补偿路径（预留失败回退 pending + `counters.release`）绝不关闭 | 「预留失败不能误关」 |
| D5 | 手动 close 幂等化：任务已是 `closed` 时 `POST /api/tasks/{id}/close` 返回 **200 + 当前任务体**（不重复发事件）；仍为 `published` 但 version 不匹配 → 维持 409「任务已变更，请刷新后重试」 | 「手动 close 与自动 close 幂等兼容」；操作者重试幂等。`cancelled` 等其他状态维持现状 409 |
| D6 | feed 修复：`findFeed` 谓词增加 `NOT EXISTS (counter.occupied_slots >= max_slots)`；`max_slots IS NULL` 恒展示 | 与 apply 端「名额已满」同口径（apply 判的就是 counter）；关闭后任务被 `status='published'` 谓词排除，counter 谓词兜底资金型 `reserving` 瞬态窗口与任何漏网路径。可报名的任务才出现在大厅 |
| D7 | `findAutoAcceptEnabled` 扫描谓词同步排除满员（同 D6 条件） | 满员任务不再被自动通过每轮扫描空转出 `slots_full` |
| D8 | 历史数据：V42 迁移回填——`status='published' AND max_slots IS NOT NULL AND accepted 计数 ≥ max_slots` 的任务直接置 `closed`（version+1）；**不伪造 outbox 事件** | 迁移回填先例（V11/V14）不伪造事件；纯 DML 无 DDL，重放安全 |
| D9 | 新报名错误文案拆分：`closed` → 409「任务已关闭，无法报名」；`cancelled` → 409「任务已取消，无法报名」（原统一「任务当前不可报名」） | 「新报名返回明确的『任务已关闭』错误」 |
| D10 | 关闭后已存在 pending 报名**保持可处理**（accept 链路只校验 application 状态，现状即如此）；满员任务的 pending 自然只能拒绝/撤回（accept 会 409 名额已满） | 用户给定两个选项中取不破坏数据的一侧；不做批量自动拒绝 |
| D11 | 通知：identity `NotificationTemplates` 新增 `TaskClosed` → 仅 `closeReason=slots_full` 时产出通知，收件人 = 任务归属人（resolver 读 payload `taskOwnerId`/`ownerAccountId`，照 `TaskReviewRejected` 先例）；手动 close（操作者=商家本人）不通知 | 代码库惯例「操作者不通知自己刚做的动作」；需求「明确任务已满自动关闭」 |
| D12 | 操作结果：`GET .../reservation` 预留结局响应与 batch-accept 每项结果带 `taskClosed` 布尔（前端单条/资金型接受最终都收敛到 reservation 结局轮询，一处覆盖全路径）；前端接受成功文案在 `taskClosed=true` 时追加「任务名额已满，已自动关闭」 | 需求「通知**或**操作结果文案」——两者都做，成本极小；单条 accept 响应体不加该字段（资金型 202 时关闭尚未发生，加了也是 false，徒增误导） |
| D13 | `TaskClosed` 事件 payload 新增 `closeReason`（`slots_full` / `manual`）与 `taskOwnerId`；revise（含下调 maxSlots）提交成功后同样触发一次 closeIfFull | 下调名额低于已接受数时任务应收口为 closed；payload 补 `taskOwnerId` 供通知解析 |

## 现状锚点（2026-08-18 快照，行号漂移按符号搜）

- `platform-java/services/marketplace-service/src/main/java/com/grassland/marketplace/taskcatalog/`
  - `TaskStatus.java:14-19`——`draft/pending_review/published/closed/cancelled`，`published→closed` 唯一合法路径
  - `TaskRepository.java:226-228` `close()` / `:504-513` `transition()`——guarded UPDATE + RETURNING；`:378-409` `findFeed()`（本设计要改）；`:441-448` `findAutoAcceptEnabled()`
  - `TaskAcceptanceCounterRepository.java:18-38` `claim()`——`occupied_slots` 条件自增，`reserving+accepted` 均占位；防超卖已存在
  - `ApplicationController.java:241-272` `claimAcceptance()`——单条/批量/自动接受共享内核；`:204-207` `isMonetary` 分流；`:150-191` `apply()` 状态校验
  - `workflow/saga/ApplicationReservationActivityImpl.java:101-134` `reserveFunds`、`:137-160` `activateEngagement`（reserving→accepted + outbox 同事务）、`:162-196` `compensateAcceptance`
  - `TaskController.java:171-182` 手动 `close()`；`:689-694` `loadManageableTask` 状态门；`:728-732` `taskClosedEnvelope`、`:774-787` `taskEventPayload`
- `platform-java/services/identity-service/src/main/java/com/grassland/identity/notification/`
  - `NotificationTemplates.java:65-196` `externalTemplate`（无 `TaskClosed` 分支）
  - `NotificationRecipientResolver.java:72-116` `externalRecipients`（收件人只读 payload accountId）
- 前端：任务管理区接受报名的调用点（实现时以 `acceptApplication`/`batch-accept` 全局搜索定位）
- 迁移目录 `marketplace-service/src/main/resources/db/migration/`，最新 `V41`，本功能用 **V42**

## 关闭语义（核心 SQL）

`TaskRepository.closeIfFull(taskId)`：

```sql
UPDATE task SET status = 'closed', version = version + 1, updated_at = now()
WHERE id = :id AND status = 'published' AND max_slots IS NOT NULL
  AND (SELECT count(*) FROM task_application a
       WHERE a.task_id = :id AND a.status = 'accepted') >= task.max_slots
RETURNING <SELECT_COLS>
```

- 返回 0 行 = 任务无上限 / 未满 / 已不是 published（可能被并发手动 close 或 cancel 抢先）→ 一律静默无操作
- 调用点（全部在既有事务内，紧跟 accepted 落定）：
  1. `claimAcceptance` 非资金分支，`apps.accept(...)` 成功后
  2. `ApplicationReservationActivityImpl.activateEngagement`，`apps.activate(...)` 成功后（outbox `ApplicationAccepted` 同事务）
  3. `TaskController` revise 提交成功后（D13）
- 返回非空时，同事务追加 outbox `TaskClosed` 事件（`closeReason=slots_full`、payload 含 `taskOwnerId`）；手动 close 事件补 `closeReason=manual`
- 并发矩阵：
  - 两个并发 accept 抢最后一个名额：`claim()` 条件 UPDATE 保证只成功一个；成功者事务内 closeIfFull 生效，失败者 409「名额已满」，任务不误关
  - accept vs 手动 close：两者都带 `status='published'` 守卫，行锁串行化，后到者 0 行无操作；商家随后重试手动 close 得到 D5 幂等 200
  - accept vs cancel：`published` 守卫二者抢一，输者 0 行；cancel 对已 closed 任务维持现状 409
  - accept vs revise：closeIfFull 不校验 version，revise 提交后任务照常可被关闭；revise 自身仍受 version 乐观锁约束（竞态时 409 刷新重试，现状不变）

## 通知文案

- `TaskClosed` + `closeReason=slots_full` →（ENGAGEMENT 类）标题「任务名额已满已自动关闭」，正文「你的任务报名名额已满，系统已自动关闭报名」，跳转 `LINK_ENGAGEMENTS`；其他 `closeReason`（含 manual/缺失）→ `null` 不通知
- resolver：`case "TaskClosed" -> accountIds(payload, "taskOwnerId", "ownerAccountId")`

## 测试（对应需求验收清单）

1. 单报名达到上限：maxSlots=1，接受唯一报名 → 任务 closed、outbox 有 `TaskClosed`（slots_full、taskOwnerId）、响应 `taskClosed=true`（IT）
2. 两并发接受只成功一个：扩展现有 `concurrentAcceptsCannotOversellSingleSlot`，断言胜者触发关闭（IT）
3. 资金型预留成功后关闭：monetary accept + reserve stub 成功 → saga 激活后任务 closed（IT）；预留失败（余额不足）→ 回退 pending、名额释放、任务保持 published（不误关）（IT，扩展 `monetaryAcceptInsufficientFundsCompensates`）
4. batch / auto accept 达到上限：批量最后一项触发关闭且后续项 409 名额已满（IT）；自动接受到达上限后任务不再被扫描（IT/单测）
5. 满员任务不出现在大厅：feed 排除 occupied≥max 任务；closed 任务天然不出现；V42 回填后遗留满员任务不出现（IT + 迁移重放断言）
6. revise / cancel / manual close 竞态：手动 close 幂等 200（含自动关闭后再手动 close）；cancel 与 close 抢一；revise 下调名额触发关闭（IT）
7. `maxSlots=null` 永不关闭、`maxSlots=1` 边界（IT）
8. closed 任务新报名 → 409「任务已关闭，无法报名」（更新现有 `applyClosedTaskConflict`）；关闭后 pending 仍可拒绝/撤回、accept 满员 409（IT）
9. 通知模板与收件人解析（identity 单测：slots_full 通知归属人、manual 不通知）
10. 前端：`taskClosed=true` 时提示文案（组件测试）

## 明确不做

- 轮询 dispatcher 兜底（原子路径 + 回填 + feed 谓词已覆盖；旧半成品方案作废）
- 满员重开（reopen）能力
- pending 报名批量自动拒绝
- PRD「报名人数」口径（等产品参数变更再议）
- 大厅满员角标/灰显（直接不展示）

## 完成标准

- 上述测试全部落地并通过；门禁全绿：`./gradlew :services:marketplace-service:test :services:identity-service:test`（JDK 25）+ `npm test` + `npm run typecheck` + `npm run build` + `git diff --check`
- 无新增 API 端点（行为变更 + 响应字段增补），无需更新 CLAUDE.md 路由表
- 回写进度指南：#26 标完成、执行顺序第一批第 3 项划掉、附测试证据
