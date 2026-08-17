# Issue #26 任务满员自动关闭 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 最后一个名额「接受成功」的同一事务内把任务从 `published` 原子迁移到 `closed`，满员任务退出大厅，自动关闭有通知与操作文案，手动 close 幂等兼容。

**Architecture:** 在接受落定点（非资金型 `apps.accept` / 资金型 saga `activateEngagement` / revise 提交）的既有 R2DBC 事务内执行一条无版本守卫的条件 UPDATE（accepted 计数 ≥ max_slots 才迁移），同事务追加 `TaskClosed` outbox 事件（`closeReason=slots_full`）；feed 与自动接受扫描谓词排除满员；V42 回填历史数据；identity 通知模板只在 `slots_full` 时通知任务归属人。

**Tech Stack:** Java 25 / Spring Boot WebFlux + R2DBC DatabaseClient（guarded UPDATE + RETURNING，无 JPA）、Flyway、Testcontainers PG（`MarketplaceItSupport`）、Vue3 + Vitest。

**Spec:** `docs/superpowers/specs/2026-08-18-issue-26-task-full-auto-close-design.md`（决策 D1–D13，实现时随时对照）

## Global Constraints

- JDK 25：`JAVA_HOME=/opt/homebrew/opt/openjdk@25`，gradle 在 `platform-java/` 下用 `./gradlew`
- 迁移一律幂等（DDL 用 `IF NOT EXISTS`；本计划 V42 是纯 DML，天然重放安全）
- 错误统一走 `MarketplaceException(status, 中文message)`；响应信封由既有 handler 处理
- Reactor：副作用调用在 `switchIfEmpty`/`orElse` 参数位置必须 `Mono.defer` 包裹（eager-assembly 陷阱）
- 每个 Task 结束单独 commit，中文 conventional commits：`feat(marketplace): #26 …` / `feat(identity): #26 …` / `feat(frontend): #26 …` / `docs: #26 …`
- 行号是 2026-08-18 快照，漂移按符号搜；**动手前先读锚点文件**
- 全程在 worktree 分支 `feat/issue-26-task-full-autoclose` 上，不直接改 main

---

### Task 1: closeIfFull 仓储方法 + V42 回填 + 非资金接受链路接入

**Files:**
- Create: `platform-java/services/marketplace-service/src/main/resources/db/migration/V42__task_full_autoclose_backfill.sql`
- Create: `platform-java/services/marketplace-service/src/main/java/com/grassland/marketplace/taskcatalog/TaskFullAutoCloser.java`
- Modify: `platform-java/services/marketplace-service/src/main/java/com/grassland/marketplace/taskcatalog/TaskRepository.java`（`close()` 方法附近）
- Modify: `platform-java/services/marketplace-service/src/main/java/com/grassland/marketplace/taskcatalog/ApplicationController.java`（`claimAcceptance` 非资金分支，:241-272）
- Test: `platform-java/services/marketplace-service/src/test/java/com/grassland/marketplace/taskcatalog/TaskAutoCloseIT.java`（新建）

**Interfaces:**
- Produces: `TaskRepository.closeIfFull(String taskId): Mono<Task>`（0 行 = 未满/无上限/已非 published，返回 empty，绝不报错）
- Produces: `TaskFullAutoCloser.closeIfFull(String taskId): Mono<Task>`（调用方事务内执行；关闭成功时同事务 append `TaskClosed` 事件，payload 含 `closeReason=slots_full` 与 `taskOwnerId`/`ownerAccountId`；未关闭返回 empty）
- Produces: `TaskFullAutoCloser.closedPayload(Task task, String closeReason): Map<String,Object>`（静态，Task 3 手动 close 复用）

- [ ] **Step 1: 读锚点**——`TaskRepository.java`（`close`/`transition`/`SELECT_COLS`/`map`、`findFeed`）、`ApplicationController.claimAcceptance` 全文、`TaskController.taskClosedEnvelope`/`taskEventPayload`（:728-787）、`OutboxRepository` append 签名、`ApplicationControllerIT`（造数 helper `apply`/`publishTask`/`accept`、`concurrentAcceptsCannotOversellSingleSlot` :444-470）

- [ ] **Step 2: 写失败测试** `TaskAutoCloseIT`（继承 `MarketplaceItSupport`，照 `ApplicationControllerIT` 的断言/造数风格）：

```java
/** #26 满员自动关闭：名额 accept 落定即原子关闭（D1–D3）。 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class TaskAutoCloseIT extends MarketplaceItSupport {

    // 场景 1：maxSlots=1，唯一报名接受成功 → 任务 closed + outbox TaskClosed(slots_full) 
    @Test
    void singleAcceptReachingCapClosesTaskAtomically() {
        // publishTask(merchant, org, /*maxSlots*/1) → apply → accept
        // 断言: 任务状态查询返回 closed；
        // 断言: marketplace_outbox 存在 event_type='TaskClosed' 且 payload 含 closeReason=slots_full、taskOwnerId
        // （marketplace.outbox.enabled=false，直接查 outbox 表，照 OutboxAtomicityIT/TaskControllerIT 的查表断言）
    }

    // 场景 2：maxSlots=2，接受 1 个 → 仍 published
    @Test
    void acceptBelowCapKeepsTaskPublished() { ... }

    // 场景 3：maxSlots=null → 永不关闭
    @Test
    void unlimitedTaskNeverAutoCloses() { ... }

    // 场景 4：两并发接受抢最后一个名额，只成功一个，且成功者触发关闭
    @Test
    void concurrentFinalSlotAcceptClosesTaskExactlyOnce() {
        // 照 ApplicationControllerIT.concurrentAcceptsCannotOversellSingleSlot 的双线程 latch 模式，
        // maxSlots=2 先接受 1 个，再并发接受 2 个（第 3、4 份报名抢最后 1 个名额）
        // 断言: 恰一个 200、一个 409 名额已满；任务 closed；outbox TaskClosed(slots_full) 恰 1 条
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cd platform-java && JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :services:marketplace-service:test --tests "com.grassland.marketplace.taskcatalog.TaskAutoCloseIT"`
Expected: FAIL（任务仍 published / 事件不存在）

- [ ] **Step 4: 实现**

`V42__task_full_autoclose_backfill.sql`：

```sql
-- #26 满员自动关闭：回填历史满员任务。口径 = accepted 计数 >= max_slots（设计 D1/D8）。
-- 纯 DML（无 DDL），重放安全；不伪造 outbox 事件（V11/V14 先例）。
UPDATE task
SET status = 'closed', version = version + 1, updated_at = now()
WHERE status = 'published'
  AND max_slots IS NOT NULL
  AND (SELECT count(*) FROM task_application a
       WHERE a.task_id = task.id AND a.status = 'accepted') >= task.max_slots;
```

`TaskRepository` 新增（紧挨 `close()`）：

```java
/** #26 满员自动关闭（D3）：accepted 计数达到 max_slots 时 published->closed。无版本守卫（系统迁移不参与用户乐观锁）；0 行 = 无操作。 */
public Mono<Task> closeIfFull(String taskId) {
    return db.sql("""
            UPDATE task SET status = 'closed', version = version + 1, updated_at = now()
            WHERE id = CAST(:id AS uuid) AND status = 'published' AND max_slots IS NOT NULL
              AND (SELECT count(*) FROM task_application a
                   WHERE a.task_id = task.id AND a.status = 'accepted') >= task.max_slots
            RETURNING %s
            """.formatted(SELECT_COLS))
            .bind("id", taskId)
            .map(TaskRepository::map).one();
}
```

`TaskFullAutoCloser`（新组件；envelope 构造照 `TaskController.taskClosedEnvelope` 的实际 API，payload 键对齐 `taskEventPayload` 并新增 `closeReason`/`taskOwnerId`）：

```java
/** #26 满员自动关闭：在接受落定事务内判定并关闭，同事务发 TaskClosed 事件（D2/D11/D13）。 */
@Component
public class TaskFullAutoCloser {

    private final TaskRepository tasks;
    private final OutboxRepository outbox;

    public TaskFullAutoCloser(TaskRepository tasks, OutboxRepository outbox) {
        this.tasks = tasks;
        this.outbox = outbox;
    }

    /** 只在调用方既有事务内使用：未满/无上限/已非 published -> empty；关闭成功 -> 返回关闭后任务（事件已追加）。 */
    public Mono<Task> closeIfFull(String taskId) {
        return tasks.closeIfFull(taskId)
                .flatMap(closed -> outbox.append(TaskController.taskClosedEnvelope(closed, "slots_full"))
                        .thenReturn(closed));
    }
}
```

（若 `taskClosedEnvelope` 是 TaskController 私有——把 envelope/payload 构造抽成 `TaskFullAutoCloser.closedPayload(Task, String closeReason)` 静态方法 + 包内可见的 envelope 工厂，TaskController 手动路径改调它，保证两条路径 payload 键完全一致。）

`claimAcceptance` 非资金分支（`apps.accept(...)` 成功、`ApplicationAccepted` 事件追加之后、事务收尾之前）接入：

```java
.flatMap(app -> taskFullAutoCloser.closeIfFull(task.id()).thenReturn(app))
```

- [ ] **Step 5: 跑测试确认通过**

Run: 同 Step 3，另跑 `--tests "com.grassland.marketplace.taskcatalog.ApplicationControllerIT"`（回归）
Expected: PASS 全绿

- [ ] **Step 6: Commit**

```bash
git add platform-java/services/marketplace-service
git commit -m "feat(marketplace): #26 满员自动关闭核心——closeIfFull 与非资金接受链路接入"
```

---

### Task 2: 资金型 Saga 激活路径接入（预留失败不关）

**Files:**
- Modify: `platform-java/services/marketplace-service/src/main/java/com/grassland/marketplace/workflow/saga/ApplicationReservationActivityImpl.java`（`activateEngagement` :137-160）
- Test: `TaskAutoCloseIT`（追加场景）

**Interfaces:**
- Consumes: Task 1 的 `TaskFullAutoCloser.closeIfFull`

- [ ] **Step 1: 读锚点**——`ApplicationReservationActivityImpl` 全文（`activateEngagement` 的事务边界与 outbox 追加方式）、`ApplicationControllerIT.monetaryAcceptStartsSagaAndActivates`（:362-381，`@MockitoSpyBean FinanceEscrowClient` stub 方式）、`monetaryAcceptInsufficientFundsCompensates`（:384-401）

- [ ] **Step 2: 写失败测试**（追加到 `TaskAutoCloseIT`）：

```java
    // 场景 5：资金型 maxSlots=1，预留成功 → 激活后任务 closed（D4）
    @Test
    void monetaryAcceptReserveSuccessThenActivationClosesTask() {
        // publishTaskBounty(maxSlots=1) → apply → accept(202) → 轮询/等待 saga 完成
        // （照 monetaryAcceptStartsSagaAndActivates 的 stub + Awaitility/轮询到 accepted）
        // 断言: 报名 accepted；任务 closed；outbox TaskClosed(slots_full) 1 条
    }

    // 场景 6：资金型预留失败（余额不足）→ 名额释放、任务保持 published（不误关，D4）
    @Test
    void monetaryAcceptReserveFailureKeepsTaskPublished() {
        // stub reserve 返回 409 余额不足 → 等待补偿完成
        // 断言: 报名回 pending；occupied_slots=0；任务仍 published；outbox 无 TaskClosed
    }
```

- [ ] **Step 3: 跑测试确认失败**（场景 5 FAIL：任务仍 published；场景 6 可能已 PASS——它是防回归护栏，PASS 也保留）

- [ ] **Step 4: 实现**——`activateEngagement` 的 `apps.activate(...)`（reserving→accepted）成功、`ApplicationAccepted` 事件追加之后（同事务）加：

```java
.flatMap(activated -> taskFullAutoCloser.closeIfFull(taskId).thenReturn(activated))
```

（`compensateAcceptance` 路径**不加**任何关闭逻辑——预留失败必须回退名额且不关任务。）

- [ ] **Step 5: 跑测试确认通过**（同 Task 1 Step 5 命令集）

- [ ] **Step 6: Commit**：`git commit -m "feat(marketplace): #26 资金型接受激活后满员自动关闭"`

---

### Task 3: 手动 close 幂等 + apply 文案拆分 + revise 下调名额收口 + cancel 竞态

**Files:**
- Modify: `platform-java/services/marketplace-service/src/main/java/com/grassland/marketplace/taskcatalog/TaskController.java`（`close` :171-182、`apply` 校验、revise 路径、手动 close 的 envelope 加 `closeReason=manual`）
- Test: `TaskAutoCloseIT`（追加）+ `ApplicationControllerIT.applyClosedTaskConflict`（更新文案断言）+ `TaskControllerIT`（close 幂等）

**Interfaces:**
- Consumes: Task 1 的 `closeIfFull` / `closedPayload`

- [ ] **Step 1: 读锚点**——`TaskController.close`/`loadManageableTask`(:689-694)/revise 端点及其事务组装、`ApplicationController.apply`(:150-191)、`TaskControllerIT.closeAndCancelEmitEventsAndTransition`(:585-603)、`ApplicationControllerIT.applyClosedTaskConflict`(:153-163)

- [ ] **Step 2: 写失败测试**：

```java
    // 场景 7：自动关闭后再手动 close → 200 幂等返回当前任务体，不重复发事件（D5）
    @Test
    void manualCloseAfterAutoCloseIsIdempotent() {
        // maxSlots=1 接受满员自动关闭后，商家 POST close（带当前 version）
        // 断言: 200；outbox TaskClosed 仍只有 1 条
    }

    // 场景 8：published 状态下 version 不匹配的手动 close → 维持 409（现状不变）
    // 场景 9：cancelled 任务手动 close → 409（现状不变）
    // 场景 10：revise 下调 maxSlots 至已接受数之下 → 提交成功后任务 closed（D13）
    @Test
    void reviseLoweringCapBelowAcceptedClosesTask() {
        // maxSlots=3 已接受 2 → revise 改 maxSlots=2 → 任务 closed + TaskClosed(slots_full)
    }
    // 场景 11：apply 对 closed/cancelled 任务的错误文案（D9）——更新 ApplicationControllerIT.applyClosedTaskConflict
    //   断言 closed → 409「任务已关闭，无法报名」；新增 cancelled → 409「任务已取消，无法报名」
```

- [ ] **Step 3: 跑测试确认失败**

- [ ] **Step 4: 实现**

`close()` 重写（语义）：

```java
// D5：close 幂等化——已 closed 返回 200 当前任务体；published+版本守卫不变；其余状态维持 409。
public Mono<ResponseEntity<...>> close(...) {
    return loadManageableTask(id, caller, /*放开状态门*/)
        .flatMap(task -> switch (task.status()) {
            case CLOSED -> Mono.just(okWithBody(task));           // 幂等重试
            case PUBLISHED -> transactions.transactional(
                    tasks.close(id, body.expectedVersion())
                        .switchIfEmpty(Mono.error(conflict("任务已变更，请刷新后重试")))
                        .flatMap(closed -> outbox.append(closedEnvelope(closed, "manual")).thenReturn(closed)))
                .map(closed -> okWithBody(closed));
            default -> Mono.error(conflict("任务当前状态不允许该操作"));
        });
}
```

`apply()` 状态校验分支改（D9）：

```java
String message = switch (task.status()) {
    case CLOSED -> "任务已关闭，无法报名";
    case CANCELLED -> "任务已取消，无法报名";
    default -> "任务当前不可报名";
};
return fail(409, message);
```

revise 端点：任务更新成功的同一事务末尾追加 `taskFullAutoCloser.closeIfFull(task.id()).thenReturn(updated)`（D13）。

- [ ] **Step 5: 跑测试确认通过**（含 `TaskControllerIT` 全类回归）

- [ ] **Step 6: Commit**：`git commit -m "feat(marketplace): #26 手动关闭幂等与报名/修订收口语义"`

---

### Task 4: feed 与自动接受扫描排除满员 + V42 重放断言

**Files:**
- Modify: `TaskRepository.findFeed`(:378-409)、`findAutoAcceptEnabled`(:441-448)
- Test: `TaskAutoCloseIT`（追加）+ `TaskLifecycleMigrationTest`（扩展）+ `BatchApplicationControllerIT.findAutoAcceptEnabledOnlyScansEligibleTasks` 风格

**Interfaces:** 无新接口

- [ ] **Step 1: 读锚点**——两个查询的现有 SQL 与绑定方式、`TaskLifecycleMigrationTest` 的 schema 造形 + baseline 重放结构、`BatchApplicationControllerIT`(:196-221)

- [ ] **Step 2: 写失败测试**：

```java
    // 场景 12：occupied>=max（含 reserving 瞬态）的任务不出现在 feed（D6）
    @Test
    void feedExcludesFullTaskEvenWhilePublished() {
        // maxSlots=1，资金型 accept 后立刻（reserving 中）查 feed → 该任务不在列表
        // 满员自动关闭后（closed）自然也不在 —— 双断言
    }
    // 场景 13：maxSlots=null 任务恒在 feed
    // 场景 14：自动接受扫描不返回满员任务（D7）——照 findAutoAcceptEnabledOnlyScansEligibleTasks，
        //   造 accepted 计数=max 但 status 仍 published 的任务（SQL 直改绕过关闭路径），断言不被扫描
```

`TaskLifecycleMigrationTest` 追加：在 baseline schema 里预置「published + accepted 满员」行 → 重放 V11+ → 断言该行 status='closed' 且 version+1、无伪造 outbox 行。

- [ ] **Step 3: 跑测试确认失败**（feed 场景 FAIL）

- [ ] **Step 4: 实现**——两个查询各加同一谓词（对齐各自 SQL 的表别名写法）：

```sql
AND (task.max_slots IS NULL OR NOT EXISTS (
    SELECT 1 FROM task_acceptance_counter counter
    WHERE counter.task_id = task.id AND counter.occupied_slots >= task.max_slots))
```

- [ ] **Step 5: 跑测试确认通过**（含 `BatchApplicationControllerIT`、`TaskLifecycleMigrationTest` 回归）

- [ ] **Step 6: Commit**：`git commit -m "feat(marketplace): #26 大厅与自动通过扫描排除满员任务"`

---

### Task 5: identity 通知模板与收件人

**Files:**
- Modify: `platform-java/services/identity-service/src/main/java/com/grassland/identity/notification/NotificationTemplates.java`、`NotificationRecipientResolver.java`
- Test: 既有模板/resolver 测试文件（以 `grep -rn "TaskReviewRejected\|externalTemplate" platform-java/services/identity-service/src/test` 定位）

**Interfaces:** 无（纯消费 outbox 事件）

- [ ] **Step 1: 读锚点**——`NotificationTemplates.externalTemplate` switch、`NotificationRecipientResolver.externalRecipients`、对应测试文件结构与既有 `EngagementRefundedOnCancel`（payload 分支文案）测试写法

- [ ] **Step 2: 写失败测试**：`TaskClosed` + `closeReason=slots_full` → 产出 ENGAGEMENT 通知（标题「任务名额已满，已自动关闭」/正文「你的任务报名名额已满，系统已自动关闭报名」/link=LINK_ENGAGEMENTS/payload=taskPayload）；`closeReason=manual`/缺失 → template 为 null；resolver 对 `taskOwnerId`、`ownerAccountId` 两字段取收件人并去重

- [ ] **Step 3: 跑测试确认失败**

- [ ] **Step 4: 实现**——`externalTemplate` 加：

```java
// marketplace：#26 满员自动关闭只通知任务归属人；商家手动关闭是自身操作，不通知（D11）。
case "TaskClosed" -> {
    if (!"slots_full".equals(stringField(payload, "closeReason"))) {
        yield null;
    }
    yield new Template(NotificationCategory.ENGAGEMENT, "任务名额已满，已自动关闭",
            "你的任务报名名额已满，系统已自动关闭报名", LINK_ENGAGEMENTS, taskPayload(payload));
}
```

`externalRecipients` 加：

```java
// #26：满员自动关闭通知任务归属人（payload 直读，不反查任务域——照 TaskReviewRejected 先例）。
case "TaskClosed" -> accountIds(payload, "taskOwnerId", "ownerAccountId");
```

- [ ] **Step 5: 跑测试确认通过**：`./gradlew :services:identity-service:test`（JDK 25）

- [ ] **Step 6: Commit**：`git commit -m "feat(identity): #26 满员自动关闭站内通知"`

---

### Task 6: 前端 taskClosed 操作结果文案

**Files:**
- Modify: `src/types/grassland/task.ts`（`ReservationOutcome` :242 附近、批量结果 item 类型）、`src/views/grassland/GrasslandWorkbench.vue`（`accept` :823-843、`batchAccept` :889-919、`buildBatchSummary` :873-887）
- Modify: `platform-java/.../taskcatalog/ApplicationController.java`（`reservationOutcome` :413 附近响应加 `taskClosed`；批量 item 结果透传 `taskClosed`——`claimAcceptance` 返回结构带上关闭事实）
- Test: `src/composables/useGrasslandMarketplace.batch.test.ts`（扩展）

**Interfaces:**
- Produces: `GET /api/tasks/{id}/applications/{appId}/reservation` 响应新增 `taskClosed: boolean`；`POST .../batch-accept` 每项新增 `taskClosed: boolean`

- [ ] **Step 1: 读锚点**——`reservationOutcome` 的响应组装、`claimAcceptance`/batchAccept 的 item 结果 record、前端 `ReservationOutcome`/`BatchItemResult` 类型、batch composable 测试的 stub 模式

- [ ] **Step 2: 写失败测试**——batch 测试扩展：stub 的 batch-accept 响应 item 带 `"taskClosed": true` → 断言透传到结果对象；reservation 类型编译覆盖由 typecheck 兜底

- [ ] **Step 3: 跑测试确认失败**（`npx vitest run src/composables/useGrasslandMarketplace.batch.test.ts`）

- [ ] **Step 4: 实现**
  - 后端：`reservationOutcome` 组装时查任务状态 `tasks.findById(app.taskId())` → `payload.put("taskClosed", task.status()==CLOSED)`；`claimAcceptance` 返回值带 `taskClosed`（由 Task 1/2 接入点的 `closeIfFull` 结果透传），batch item 序列化包含该字段
  - 前端类型：`ReservationOutcome`/`BatchItemResult` 加 `taskClosed?: boolean`
  - `GrasslandWorkbench.accept`：`outcome.status === 'accepted'` 的 label 追加 `outcome.taskClosed ? '；任务名额已满，已自动关闭' : ''`；batch 轮询同款
  - `buildBatchSummary`：`if (results.some((r) => r.taskClosed)) parts.push('任务名额已满，已自动关闭')`

- [ ] **Step 5: 跑测试确认通过**：`npx vitest run src/composables/useGrasslandMarketplace.batch.test.ts && npm run typecheck`

- [ ] **Step 6: Commit**：`git commit -m "feat(frontend): #26 接受结果展示任务满员自动关闭"`

---

### Task 7: 文档回写与全量门禁

**Files:**
- Modify: `docs/草场开发进度与续接指南.md`（#26 行标完成 + 执行顺序第一批第 3 项划掉 + 附测试证据；漂移校准文档中 worktree 口径更新）
- Modify: `docs/superpowers/specs/2026-08-18-issue-26-task-full-auto-close-design.md`（状态行改「已实现」）

- [ ] **Step 1: 全量门禁**（在 worktree 根）

```bash
cd platform-java && JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :services:marketplace-service:test :services:identity-service:test
cd .. && npm test && npm run typecheck && npm run build && git diff --check
```

Expected: 全绿（Java 全部 IT 需 Docker daemon 在跑）

- [ ] **Step 2: 回写文档**（进度指南 #26 缺口行改「已完成」并附 TaskAutoCloseIT 等证据摘要；「当前开放项速览」与第一批执行顺序同步）

- [ ] **Step 3: Commit**：`git commit -m "docs: #26 状态回写——满员自动关闭落地"`
