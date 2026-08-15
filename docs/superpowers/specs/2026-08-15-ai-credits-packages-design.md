# AI 用量套餐体系（平台内闭环 v1）设计

- 日期：2026-08-15
- 状态：已批准（用户当轮拍板计费口径=积分包；Sandbox 支付即时生效）
- 范围决策：平台内付费闭环，暂不接第三方 PSP（GL-P4 后置）；Web 端
- 对应 backlog：GL-P2-FIN-001 平台内部分重定义

## 1. 目标与非目标

**目标**：用户可自助购买积分包（SKU：¥X → N 积分，1 积分 = 1 次 AI 调用，与现有扣减口径一致）；Sandbox 支付即时生效（镜像消费者端 commerce）；admin 管理 SKU（版本化调价）与订单可见；全程双录账本 + 幂等 + 对账事实。

**非目标（明确后置）**：
- 按 actual usage 的差额金额结算（等真实价目与财务批准；B4 credits↔cents policy 机制已留口）
- 组织配额与成本上限（企业客户，PRD §4.11）
- 用户自助购买退款（v1 用 admin 调账兜底）
- 发票、真实 PSP（D-01）

## 2. 数据模型（finance V14）

```sql
credits_package          -- 运营可变壳：id, name, description, status(draft/active/retired), current_version_id
credits_package_version  -- 不可变快照：id, package_id, version, price_cents, credits_amount, note, created_at
                         --   UNIQUE(package_id, version)；调价 = 新版本行 + current 指针切换
credits_purchase_order   -- account_id, package_id, package_version_id, price_cents, credits_amount(下单冻结),
                         --   status(created/paid/failed), provider, provider_ref, operation_id UNIQUE, paid_at
```

积分入账复用 `credits_account`/`credits_transaction`，`type='purchase'`（V6 CHECK 已预留，零迁移）。

## 3. 购买编排（finance 新包 aicredits/）

`CreditsPurchaseService.purchase(command)`，单 R2DBC 事务（镜像 ConsumerPaymentService）：
1. 订单落库；`operation_id` 唯一幂等，冲突 → 回放字段匹配校验后返回既有单
2. `PaymentProviderAdapter.charge`（SandboxPaymentProviderAdapter 即时成功；真实 PSP = 换 adapter 配置）
3. `providerOperations.register`（对账事实）
4. 双录账本：借 `EXTERNAL` / 贷 `AI_CREDIT_REVENUE`（LedgerAccount.Type 新枚举）
5. 积分入账：balance+N、total_earned+N、`credits_transaction(type='purchase')`，幂等键 `purchase:<orderId>`
6. `markPaid` + outbox `AiCreditsPurchased`

生产边界：生产 overlay 强制 `FINANCE_PSP_MODE≠sandbox`，真实 adapter 缺失时启动 fail-fast → 生产购买入口天然关闭，无需额外开关。

## 4. API 与路由

| 端点 | 方法 | 权限 | 路由 |
|---|---|---|---|
| `/api/credits/packages` | GET | 登录 | 骑现有 `/api/credits` 前缀（EDGE_ROUTE_CREDITS_FINANCE），零新路由 |
| `/api/credits/purchase-orders` | GET/POST | 登录（本人） | 同上 |
| `/api/admin/credits-packages` | GET/POST/PUT | requireRole(FINANCE, PLATFORM_ADMIN) | 新精确前缀 → finance |
| `/api/admin/credits-purchase-orders` | GET | 同上 | 新精确前缀 → finance（只读） |

命名避开 `/api/admin/ai`（intelligence 前缀），无碰撞。

## 5. 前端

- 顶栏积分徽标点击 → 「积分与套餐」弹窗：余额 + active SKU 卡片 + 购买记录
- 购买流：选卡片 → 确认（价格/面值/v1 不可退提示）→ 下单 → sandbox 即时成功 → 余额与流水刷新
- AdminView 新「积分套餐」tab：SKU 列表 + 新建/调价（出新 version）/上下架 + 订单监控
- 新 `useCreditsPackages` composable；复用 `useCredits`

## 6. 切分（每片独立 commit + 验证）

| Slice | 内容 | 验收 |
|---|---|---|
| A | finance SKU 域：V14 三表 + admin CRUD + edge admin 路由 | IT：版本化语义、角色门禁、路由门禁 |
| B | 购买编排：service + 用户端点 + 账本科目 + outbox | IT：幂等重放不双入账、账本借贷平衡、purchase 流水 |
| C | 前端：积分弹窗购买流 + admin 套餐 tab | vitest + 真浏览器实测（购买→余额↑→admin 订单可见） |
| D | 对账收尾：admin 只读三方核对端点（订单×积分流水×账本）+ 文档 | IT + 进度文档 |

## 7. 关键既有锚点

- `ConsumerPaymentService.pay`：事务编排范式（insertPayment 幂等 → ledger → providerOperations → outbox）
- `LedgerAccount.Type`：新增 AI_CREDIT_REVENUE；借 EXTERNAL/贷收入的分录模式参考 `postConsumerPayment`
- `credits.award`：入账原子性范式（purchase 流水走独立方法带 type）
- `SandboxPaymentProviderAdapter`：`@ConditionalOnProperty(finance.psp.mode=sandbox, matchIfMissing=true)`
- marketplace `package_version`：不可变版本化范式
- finance Flyway 当前最新 V13 → 本特性从 V14 起
