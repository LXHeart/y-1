# AI 用量套餐体系（平台内闭环 v1）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用户自助购买积分包（¥X → N 积分），Sandbox 支付即时生效，admin 版本化管理 SKU，全程双录账本 + 幂等。

**Architecture:** finance 新增 `aicredits/` 包：三张表（壳/不可变版本/订单）+ 镜像 `ConsumerPaymentService` 的单事务购买编排（订单幂等 → provider charge → provider operation → 账本过账 → 积分入账 → outbox）。用户端点骑现有 `/api/credits` 前缀路由零新路由；admin 新精确前缀路由。

**Tech Stack:** Java 25 / Spring Boot 4 WebFlux+R2DBC / PostgreSQL Flyway / Vue 3 + Pinia-less composables / vitest + WebTestClient IT

**Spec:** `docs/superpowers/specs/2026-08-15-ai-credits-packages-design.md`

## Global Constraints

- JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4/libexec/openjdk.jdk/Contents/Home（JDK 25 必需）
- 领域写 + outbox append 必须同一 R2DBC 事务（Slice 7C 范式：`transactions.transactional(work)`）
- `operation_id` 全局唯一幂等；积分入账键 `purchase:<orderId>`；账本 journal 键 `ai-credit-purchase:<orderId>`
- 不建跨服务 FK（database-per-service）
- Java 编译零 warning（-Xlint 门禁）
- 每任务：TDD 红→绿，全绿后 commit；commit 只 commit 不 push
- 前端请求带 `credentials: 'include'`，错误信封 `{success:false,error}`

---

### Task 1: finance SKU 域（V14 三表 + 仓储 + admin CRUD + edge 路由）

**Files:**
- Create: `platform-java/services/finance-service/src/main/resources/db/migration/V14__credits_packages.sql`
- Create: `platform-java/services/finance-service/src/main/java/com/grassland/finance/aicredits/CreditsPackageRepository.java`
- Create: `platform-java/services/finance-service/src/main/java/com/grassland/finance/aicredits/CreditsPackageAdminController.java`
- Test: `platform-java/services/finance-service/src/test/java/com/grassland/finance/aicredits/CreditsPackageAdminControllerIT.java`
- Modify: `platform-java/services/edge-bff/src/main/resources/application.yml`（admin 两条路由）
- Modify: `platform-java/services/edge-bff/src/test/java/com/grassland/edge/proxy/JavaRouteManifestGateTest.java`
- Modify: `docker-compose.yml`（flag 透传）

**Interfaces（Produces）:**
- `CreditsPackageRepository.listActive()` → `Flux<PackageView>`（view = package 字段 + current version 的 price_cents/credits_amount/version）
- `CreditsPackageRepository.findById(String packageId)` → `Mono<PackageView>`
- `CreditsPackageRepository.create(String name, String description, long priceCents, int creditsAmount, String note)` → `Mono<PackageView>`（package + v1 version + current 指针，单事务）
- `CreditsPackageRepository.newVersion(String packageId, long priceCents, int creditsAmount, String note)` → `Mono<PackageView>`（仅 draft/active 可调价；version+1；事务内切 current 指针）
- `CreditsPackageRepository.setStatus(String packageId, String status)` → `Mono<PackageView>`（draft→active、active→retired、retired→active 允许；非法迁移 FinanceException 409）
- `PackageView` record：`String id, String name, String description, String status, long version, long priceCents, int creditsAmount, String note`

**Steps:**

- [ ] **1.1 写 V14 迁移（RED 前置）**

```sql
-- V14__credits_packages.sql
CREATE TABLE credits_package (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name               text NOT NULL,
    description        text NOT NULL DEFAULT '',
    status             text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','active','retired')),
    current_version_id uuid,                          -- 循环引用 version 表，无 FK（建行后回填）
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE credits_package_version (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    package_id     uuid NOT NULL,
    version        bigint NOT NULL CHECK (version >= 1),
    price_cents    bigint NOT NULL CHECK (price_cents > 0),
    credits_amount integer NOT NULL CHECK (credits_amount > 0),
    note           text NOT NULL DEFAULT '',
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (package_id, version)
);
CREATE INDEX idx_credits_package_version_pkg ON credits_package_version(package_id, version DESC);

CREATE TABLE credits_purchase_order (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          uuid NOT NULL,
    package_id          uuid NOT NULL,
    package_version_id  uuid NOT NULL,
    price_cents         bigint NOT NULL,
    credits_amount      integer NOT NULL,
    status              text NOT NULL DEFAULT 'created' CHECK (status IN ('created','paid','failed')),
    provider            text NOT NULL,
    provider_ref        text NOT NULL,
    operation_id        text NOT NULL UNIQUE,
    paid_at             timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_credits_purchase_order_account ON credits_purchase_order(account_id, created_at DESC);
```

- [ ] **1.2 写失败 IT（RED）**：`CreditsPackageAdminControllerIT extends FinanceItSupport`
  - `adminCreatesPackageWithVersionOne`：POST `/api/admin/credits-packages` `{name, priceCents, creditsAmount}` + FINANCE 角色断言 → 200，data 含 `status:"draft", version:1`
  - `priceChangeCreatesNewVersionAndSwitchesCurrent`：POST 建包 → PUT `/api/admin/credits-packages/{id}` `{priceCents, creditsAmount}` → version 2、新价格
  - `statusTransitionsEnforced`：retired→draft 409；draft→active 200
  - `nonFinanceRoleRejected`：普通用户断言 → 403（镜像 `LedgerAdminControllerIT` 的 signWithRole 用法）
- [ ] **1.3 跑 IT 确认失败**（路由 404 / 类不存在）
- [ ] **1.4 实现仓储 + 控制器（GREEN）**：控制器 `requireRole(BackendRole.FINANCE, BackendRole.PLATFORM_ADMIN)`，`{success,data}` 信封，POST/PUT 校验 name 1-50、priceCents 1-10_000_00、creditsAmount 1-100000
- [ ] **1.5 跑 IT 绿** + `:services:finance-service:test :bootJar` 零 warning
- [ ] **1.6 Edge 路由（先加门禁断言 RED → 加路由 GREEN）**：manifest 加 `/api/admin/credits-packages` 与 `/api/admin/credits-purchase-orders` 前缀 → finance + `EDGE_ROUTE_ADMIN_CREDITS_PACKAGES_FINANCE`/`EDGE_ROUTE_ADMIN_CREDITS_PURCHASE_ORDERS_FINANCE` 默认 true；compose 透传；gate test 加两条代表路由
- [ ] **1.7 Commit**：`feat(finance): 积分包 SKU 域与 admin 管理（AI 套餐 v1 Slice A）`

---

### Task 2: 购买编排 + 用户端点（Slice B）

**Files:**
- Create: `platform-java/services/finance-service/src/main/java/com/grassland/finance/aicredits/CreditsPurchaseService.java`
- Create: `platform-java/services/finance-service/src/main/java/com/grassland/finance/aicredits/CreditsPurchaseController.java`
- Create: `platform-java/services/finance-service/src/main/java/com/grassland/finance/aicredits/CreditsPurchaseOrderRepository.java`
- Modify: `platform-java/services/finance-service/src/main/java/com/grassland/finance/ledger/LedgerAccount.java`（Type 加 `AI_CREDIT_REVENUE` + `aiCreditRevenue()` 工厂，owner="ai-credits"）
- Modify: `platform-java/services/finance-service/src/main/java/com/grassland/finance/ledger/JournalEntry.java`（Type 加 `AI_CREDIT_PURCHASE`）
- Modify: `platform-java/services/finance-service/src/main/java/com/grassland/finance/ledger/LedgerService.java`（加 `postAiCreditPurchase(String orderId, long amount)`：借 external(psp.channel()) / 贷 aiCreditRevenue，journal 键 `ai-credit-purchase:<orderId>`，镜像 `postConsumerPayment`）
- Modify: `platform-java/services/finance-service/src/main/java/com/grassland/finance/credits/CreditsService.java`（加 `purchaseCredit(String accountId, int amount, String note, String operationId)`：镜像 `award` 但 type=`"purchase"`，deltaEarned=+amount）
- Test: `platform-java/services/finance-service/src/test/java/com/grassland/finance/aicredits/CreditsPurchaseControllerIT.java`

**Interfaces:**
- Consumes: Task 1 的 `CreditsPackageRepository.listActive/findById`
- Produces: `POST /api/credits/purchase-orders` body `{packageId, operationId?}`（operationId 缺省服务端生成）→ `{success,data:{orderId,status:"paid",creditsAmount,balance}}`；`GET /api/credits/packages` → `{success,data:[PackageView...]}`（仅 active）；`GET /api/credits/purchase-orders` → 本人倒序列表
- `CreditsPurchaseOrderRepository.insert(...)` → 幂等（operation_id 冲突返回 empty → service 层回放校验）；`markPaid(id)`；`findByAccount(accountId, limit)`

**Steps:**

- [ ] **2.1 写失败 IT（RED）**：
  - `purchaseGrantsCreditsAndPostsLedger`：seed active 包 ¥9.9→10 → 登录断言 POST purchase → 200 paid；`credits_account.balance`=+10；`credits_transaction` 有 `type='purchase'` 行（operation `purchase:<orderId>`）；`journal` 有 `AI_CREDIT_PURCHASE` 且借贷合计为零、贷方科目 `AI_CREDIT_REVENUE`；`provider_operation` 有 payment 行；outbox 有 `AiCreditsPurchased`
  - `purchaseIsIdempotent`：同 operationId 重放 → 返回同一 orderId，balance/流水/journal 不翻倍
  - `draftOrRetiredPackageRejected`：draft 包购买 → 409
  - `anonymousRejected`：无断言 → 401
  - `packagesListOnlyActive`：draft+active 并存 → 仅 active
- [ ] **2.2 跑 IT 确认失败**
- [ ] **2.3 实现编排（GREEN）**：

```java
public Mono<PurchaseOutcome> purchase(PurchaseCommand command) {
    String operationId = command.operationId() == null ? UUID.randomUUID().toString() : command.operationId();
    Mono<PurchaseOutcome> work = packages.findById(command.packageId())
            .switchIfEmpty(Mono.error(new FinanceException(404, "积分包不存在")))
            .map(pkg -> {
                if (!"active".equals(pkg.status())) throw new FinanceException(409, "积分包不在售");
                return pkg;
            })
            .flatMap(pkg -> orders.insert(command.accountId(), pkg, provider.channel(),
                            provider.channel() + ":ai-credit:" + UUID.randomUUID(), operationId)
                    .flatMap(order -> provider.charge(order.id().toString(), order.priceCents())
                            .then(ledger.postAiCreditPurchase(order.id().toString(), order.priceCents()))
                            .then(credits.purchaseCredit(command.accountId(), order.creditsAmount(),
                                    "购买 " + packages.findName(pkg.id()), "purchase:" + order.id()))
                            .then(orders.markPaid(order.id()))
                            .then(outbox.append("AiCreditsPurchased", order.id(), Map.of(
                                    "orderId", order.id(), "accountId", command.accountId(),
                                    "packageId", pkg.id(), "priceCents", order.priceCents(),
                                    "creditsAmount", order.creditsAmount())))
                            .then(credits.balance(command.accountId())
                                    .map(b -> new PurchaseOutcome(order, "paid", b.balance())))))
                    .switchIfEmpty(Mono.defer(() -> orders.findByOperationId(operationId)
                            .map(existing -> new PurchaseOutcome(existing, "paid", null)))); // 幂等回放
    return transactions.transactional(work);
}
```

  注：outbox.append 的实际签名按 finance `event/OutboxRepository` 现有形参对齐（eventId 确定性派生）；`provider.charge` 按 `PaymentProviderAdapter` 现有方法名对齐（sandbox 即时 Mono 完成）。
- [ ] **2.4 用户控制器**：`callers.requireUser` → service；GET packages/purchase-orders；控制器薄、校验在 service/record
- [ ] **2.5 跑 IT 绿** + finance 全量 `test` + `bootJar` 零 warning
- [ ] **2.6 Commit**：`feat(finance): 积分包购买编排与用户端点（AI 套餐 v1 Slice B）`

---

### Task 3: 前端购买流 + admin 套餐台（Slice C）

**Files:**
- Create: `src/composables/useCreditsPackages.ts` + `useCreditsPackages.test.ts`
- Create: `src/components/CreditsPackagesModal.vue` + `CreditsPackagesModal.test.ts`
- Modify: `src/layouts/DefaultLayout.vue`（积分徽标 button 化 → 打开弹窗；挂载弹窗）
- Create: `src/components/admin/CreditsPackagesAdminPanel.vue`（或按 AdminView 现有 tab 组件位置约定放置）+ 测试
- Modify: `src/views/admin/AdminView.vue`（新「积分套餐」tab）

**Interfaces:**
- Consumes: Task 2 的三个用户端点 + Task 1 的 admin 端点
- Produces: `useCreditsPackages()` → `{packages, orders, loading, purchasing, error, load, purchase, loadOrders}`；`purchase(packageId)` 成功后返回新余额供徽标刷新

**Steps:**

- [ ] **3.1 composable 失败测试（RED）**：契约（GET/POST 路径、body 形状、幂等 operationId 客户端生成、错误信封解析、成功后 balance 更新回调）——镜像 `useMomentsCreation.test.ts` 的 stubFetch 风格
- [ ] **3.2 实现 composable（GREEN）**
- [ ] **3.3 弹窗组件失败测试（RED→GREEN）**：渲染 active SKU 卡（名称/价格/面值）、购买确认（含「购买后不支持自助退款」文案）、购买成功余额刷新、购买记录列表、loading/error 态
- [ ] **3.4 DefaultLayout 接线**：徽标 `0 次` 从文本改 button → open modal；弹窗购买成功后调 `loadCreditBalance()`
- [ ] **3.5 admin 台（RED→GREEN）**：SKU 列表（状态/version/价格/面值）+ 新建表单 + 调价（PUT）+ 上下架 + 订单只读列表；仅 FINANCE/platform_admin 可见 tab（镜像 AdminView 现有角色过滤）
- [ ] **3.6 全量验证**：`npm run test && npm run typecheck && npm run build`
- [ ] **3.7 真浏览器实测**：重建 finance 镜像起栈 → e2e-merchant 登录 → admin 建 active 包 → 积分徽标 → 购买 → 余额 +N、流水/订单/账本三处 DB 核对 → admin 台可见订单
- [ ] **3.8 Commit**：`feat(frontend): 积分套餐购买流与 admin 套餐台（AI 套餐 v1 Slice C）`

---

### Task 4: 对账端点 + 文档收尾（Slice D）

**Files:**
- Create: `platform-java/services/finance-service/src/main/java/com/grassland/finance/aicredits/CreditsPurchaseReconciliationController.java`（或并入 admin controller）
- Test: 扩展 `CreditsPurchaseControllerIT` 或新 IT
- Modify: `docs/草场开发进度与续接指南.md`、`项目速览.md`、`CLAUDE.md`（路由表/功能清单）

**Steps:**

- [ ] **4.1 失败 IT（RED）**：`GET /api/admin/credits-purchase-orders/reconciliation`（FINANCE 角色）→ 逐单核对：订单 paid ⇔ `credits_transaction(purchase:<orderId>)` 存在 ⇔ journal `ai-credit-purchase:<orderId>` 存在且平衡；汇总 `{totalOrders, consistent, inconsistent:[{orderId, reasons[]}]}`；人为删一行流水后重跑 → 该单进 inconsistent
- [ ] **4.2 实现（GREEN）**：只读 SQL JOIN 三方；全量 finance test + bootJar
- [ ] **4.3 文档同步**：进度指南（Slice 23 记录 + 开放项速览 P2 AI 商业化行更新）、速览笔记 A 组移除「付费 SKU」缺口、CLAUDE.md
- [ ] **4.4 Commit**：`feat(finance): 积分购买三方对账端点（AI 套餐 v1 Slice D）` + `docs: AI 套餐 v1 进度同步`

---

## Self-Review 结论

- Spec 覆盖：§2 三表→Task1；§3 编排→Task2；§4 API/路由→Task1/2；§5 前端→Task3；§6 切分 A-D→Task1-4；对账→Task4 ✓
- 占位扫描：Task2 代码块中两处「按现有签名对齐」标注（outbox.append/provider.charge）为执行时对齐既有私有 API 的显式指令，非占位 ✓
- 类型一致性：PackageView 贯穿 Task1/2/3；operationId 键口径（订单原键 / `purchase:<orderId>` / `ai-credit-purchase:<orderId>`）全计划一致 ✓
