# 任务书 #41：未支付订单 TTL 关单

> 生成日期：2026-08-17。交办对象：Qoder（独立开发代理，无会话上下文，本文自包含）。
> 仓库：当前 main。背景与缺口登记：`docs/草场开发进度与续接指南.md` 横向缺口 #41；推荐执行顺序第二批第 8 项。
> 完成后须回写进度指南（#41 标完成）并同步 CLAUDE.md。
>
> **定性（已核实，比登记时更重要）**：这不只是消费者端 UX 收尾，是**资源泄漏修复**——下单即扣库存（`CommerceRepository.reserveInventory`），但仓库层**不存在** `releaseInventory`，被遗弃的 `pending_payment` 订单永久占用包/时段库存，且 dispatcher 对失败支付无限重试（`CommerceDispatcher` 每 3s `attemptPayment`，失败仅 `recordError` 留在原状态）。本任务同时关掉这两个问题。

## 目标

1. 未支付订单超过 TTL（默认 15 分钟）自动关闭：`pending_payment → cancelled`（终态），停止支付重试
2. 关单时**对称释放**下单时占用的库存（包级 `commerce_package_inventory` / D-07 时段级 `commerce_package_inventory_slot`）
3. 消费者端如实展示：待支付订单显示支付截止时间，超时订单显示「已超时关闭」

**本期不做**（防扩散）：消费者主动取消未支付订单（另案 UX）；已支付超时退款路径的库存归还（见文末「相邻发现」，刻意不混入）；identity 通知模板（关单通知是独立小后续）；真实 PSP 的对账语义（sandbox 支付是同步的，竞态面见 D4）。

## 设计决策（已定死，勿另起方案）

| # | 决策 | 理由 |
|---|---|---|
| D1 | `consumer_order` 加 `payment_deadline timestamptz NULL`，**下单时写入** `now() + marketplace.commerce.payment-timeout-seconds`（默认 900） | 快照语义（镜像既有 `redeem_deadline` 存行上）：改配置不影响存量订单；dispatcher 的 claim SQL 直接用列 |
| D2 | 关单由 **`CommerceDispatcher` 既有调度循环**承接：`pending_payment` 分支先查 deadline，**过期→claim 关单，未过期→照旧 attemptPayment** | 不新增调度器；「到期不再尝试支付」由分支顺序保证 |
| D3 | claim 用**条件 UPDATE 守卫迁移**（`WHERE status='pending_payment' AND payment_deadline <= now()`），镜像同文件 `claimExpired`（paid→refund_pending）的写法；**库存释放与状态迁移同一事务** | 与支付成功路径的竞态由状态机单边胜出；原子性由事务保证 |
| D4 | 竞态两方向都安全：关单先赢 → 支付尝试发现非 pending_payment 跳过；支付先赢 → claim UPDATE 0 行、不释放库存。**测试双向都要锁** | 终态互斥是本任务的核心正确性 |
| D5 | 释放库存新增 `releaseInventory(versionId, slotId?)`：`remaining_stock + 1` 且 **`remaining_stock < total_stock` 守卫封顶**（防双重释放把库存刷爆） | 与 reserve 对称；守卫吸收任何上游重复调用 |
| D6 | 关单要能定位当初扣的是哪份库存：订单行必须携带 `package_version_id` 与 `inventory_slot_id` 引用——**Stage 0 核实列是否已存在**，缺则随本迁移补列（存量行无法回填 slot → NULL，接受；backfill 只针对 `pending_payment` 行的 deadline） | 释放必须精确对称，不能按当前套餐版本猜 |
| D7 | 订单响应（create/list/detail）回显 `paymentDeadline`；**无新公开端点、无新 edge 前缀** | 纯后台自治 + 读模型补字段 |
| D8 | 原因/审计：复用订单既有错误/补偿备注列（admin 订单表已在显示补偿错误）写 `payment_timeout`，不新造审计表 | Stage 0 确认具体列名 |
| D9 | commerce 若已有订单生命周期 outbox 事件（Stage 0 核查），关单同事务补发一条同族事件；**没有就不为它新建事件族** | 不为单一动作发明新事件体系 |

## 现状锚点（动手前先读；行号为 2026-08-17 快照，漂移按符号搜）

- `marketplace-service/src/main/resources/db/migration/V26__commerce_orders.sql` — `consumer_order` 建表：`cancelled`/`payment_failed` 状态在 CHECK 里有定义但**无任何写入路径**；只有 `redeem_deadline`，无支付截止；`commerce_package_inventory`（:41-44，`remaining_stock <= total_stock` 不变式）；D-07 时段库存表在后续迁移（`ls db/migration` 找 slot 表所在版本）
- `marketplace-service/.../commerce/CommerceRepository.java`
  - `:203` / `:214` `reserveInventory(versionId[, slotId])` — **下单即扣**，全仓库无 release 对应物（本任务补）
  - `:343-358` `claimExpired` — paid→refund_pending 的条件 UPDATE 先例（D3 镜像对象）
  - `:516-521` `pendingDispatch` — dispatcher 捞单查询
- `marketplace-service/.../commerce/CommerceDispatcher.java:26-40` — `@Scheduled(fixedDelayString = "${marketplace.commerce.dispatcher-poll-ms:3000}")`，现有 case：`pending_payment → attemptPayment`、过期 paid → refund、`redeeming → attemptSplit`
- `marketplace-service/.../commerce/CommerceService.java` — `:170` 附近下单（`repository.reserveInventory(detail.version().id(), command.inventorySlotId())` 后建单）；`:383-392` `attemptPayment` 失败 `recordError` 留原状态
- 前端：`src/views/commerce/ConsumerCommerceView.vue:194-195`「支付正在后台重试」；`:308-309` `cancelled` 标签映射（现为死代码，本任务激活）；`src/composables/useCommerce.ts`

## Stage 0 — 定型核查（半天）

1. `consumer_order` 是否已存 `package_version_id` / `inventory_slot_id`（D6）；错误/备注列名（D8）
2. `attemptPayment` 成功把订单置 paid 的 UPDATE 是否状态守卫（`WHERE status='pending_payment'`）——D4 竞态前提
3. commerce 现有 outbox 事件族（grep commerce 包 outbox append）——D9 取舍
4. 前端订单类型定义里 status 联合类型位置

## Stage 1 — 数据模型 + 库存释放原语

**Migration V38**（`ls` marketplace db/migration 确认下一版本号）：

```sql
ALTER TABLE consumer_order ADD COLUMN payment_deadline timestamptz;
-- 存量 pending_payment 行回填 created_at + 900s（清 Sandbox 积压；终态历史行不回填，NULL 语义见下）
UPDATE consumer_order SET payment_deadline = created_at + interval '900 seconds'
 WHERE status = 'pending_payment' AND payment_deadline IS NULL;
-- 若 Stage 0 发现缺 inventory_slot_id：同迁移补列（不回填，存量接受为无法释放）
```

- 配置：`marketplace.commerce.payment-timeout-seconds:900`（application.yml + `.env.docker.example` 占位说明）
- 下单路径写入 `payment_deadline = now() + ttl`
- **`CommerceRepository.releaseInventory(String versionId)` 与 `(String versionId, String slotId)`**：与 reserve 完全对称的 UPDATE，`SET remaining_stock = remaining_stock + 1 ... WHERE ... AND remaining_stock < total_stock RETURNING remaining_stock`（D5 封顶守卫）
- NULL deadline 防御语义：dispatcher 视 NULL 为不过期（终态历史行天然不受影响），代码注释写明

## Stage 2 — dispatcher 关单分支

- `CommerceRepository` 加 `claimPaymentExpired(Instant now)`：条件 UPDATE `pending_payment → cancelled`（`WHERE payment_deadline <= :now`，同 UPDATE 写原因 `payment_timeout` 到 D8 确定的列）RETURNING 订单行（含 versionId/slotId）
- `CommerceService.cancelExpired(order)`：**同一事务**内 claim 成功 → `releaseInventory(versionId, slotId)`（带 slot 释放 slot，无 slot 释放包级）
- `CommerceDispatcher` 的 `pending_payment` case 改为：deadline 已过 → cancel 流程；未过 → 既有 attemptPayment。捞单 SQL 若按状态捞，确保过期单仍会被捞出（它们仍是 pending_payment，天然覆盖）
- 幂等：claim 0 行即跳过（并发副本/双轮重复安全）；release 的封顶守卫兜底

**测试（本任务核心）**：
- IT：过期单 → cancelled + `remaining_stock` 回升（包级与 slot 级各一）；未过期单不动
- IT：**竞态双向**——支付成功先落 → claim 0 行、库存不释放；claim 先落 → 支付尝试跳过（断言无 finance 支付调用）
- IT：重复 claim 幂等；release 封顶（构造 remaining==total 时 release 不越界）
- 单测：deadline NULL 不关单；backfill 后存量 pending 单到点被关
- 既有 commerce IT 零回归（尤其 paid 过期退款、redeeming 分账路径）

## Stage 3 — 响应字段 + 前端

- 订单响应（create/list/detail 的 body 组装处）加 `paymentDeadline`（ISO；终态/历史 NULL 行原样 null）
- 前端：
  - `useCommerce` 订单类型加 `paymentDeadline?: string | null`、status 联合类型确认含 `cancelled`
  - `ConsumerCommerceView`：pending_payment 订单显示「请在 MM-DD HH:mm 前完成支付」（可选加分：倒计时）；cancelled 订单显示「已超时关闭」（激活 `:308-309` 死映射）；「支付正在后台重试」文案仅对未过期失败单显示
- vitest：状态→文案映射、deadline 渲染、过期判断
- **门禁**：`npm run test && npm run typecheck && npm run build`

## Stage 4 — 收尾

1. 全量门禁：`./gradlew :services:marketplace-service:test`（含 IT，JDK 25）+ 前端三件套
2. 回写进度指南：#41 标完成（附库存泄漏修复说明与测试证据）、推荐执行顺序第二批第 8 项划掉
3. CLAUDE.md：commerce 相关描述补一句「未支付订单 TTL 关单并释放库存」

---

## 验收标准

1. 下单后默认 15 分钟未支付 → 订单 `cancelled`，原因 `payment_timeout`；此后 dispatcher 不再对其尝试支付
2. 关单同事务释放库存：包级与时段级 `remaining_stock` 各自回升 1；并发/重复关单不超卖不重放（封顶守卫）
3. 到期边界竞态：支付成功与关单互斥，最终恰好一个终态，库存占用与终态一致（paid 保留 / cancelled 释放）
4. 配置改为 60s 后，新建订单按 60s 关单；改前创建的存量订单仍按其快照 deadline
5. 消费者端：待支付单显示截止时间；超时单显示「已超时关闭」；不再出现无限「支付正在后台重试」
6. 既有 commerce 测试零回归

## 相邻发现（刻意不混入本任务，回写文档时登记）

已支付订单超过核销期限走自动退款（`claimExpired` → refund），该路径**同样不归还库存**——是否应在退款时归还 `remaining_stock` 是独立的产品决策（涉及商家已让利与超卖权衡），单独立项，勿顺手改。

## 代码库陷阱清单（必读）

- Java 构建：JDK 25（`JAVA_HOME=/opt/homebrew/opt/openjdk@25`），入口 `platform-java/` 下 `./gradlew`
- Reactor：`switchIfEmpty` 副作用包 `Mono.defer`；库存释放与状态迁移必须同一 R2DBC 事务（`TransactionalOperator`）
- 条件 UPDATE 返回 0 行 = 竞态败者，**静默跳过不报错**（镜像 claimExpired 语义）
- Jackson 3：请求 record 可选数值字段必须包装类型；`is` 前缀无参方法会被序列化成属性（派生态加 `@JsonIgnore`）
- marketplace 无全局安全链：本任务**无新公开端点**，不涉及；但改动的 dispatcher/service 保持既有内部调用边界
- 金额/库存整数运算，无浮点；migration 版本号动手前 `ls` 确认
- 提交按语义拆分（建议：migration+release 原语 / dispatcher 关单+测试 / 前端 / docs 各一个 commit），中文 commit message、`feat(scope):` / `test(scope):` / `docs:` 风格
