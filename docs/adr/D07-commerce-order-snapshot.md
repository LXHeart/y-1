# ADR-D07：商品/套餐、定价、库存、有效期和订单快照归属

| 状态 | 日期 | 决策编号 | 阻塞范围 | 依赖 |
|------|------|----------|----------|------|
| 已采纳 | 2026-08-02 草案 / 2026-08-02 已采纳 | D-07（HLD §19、§10.4） | Marketplace/Commerce | D-01、D-02 |

## 决策记录（已采纳，2026-08-02）

采纳推荐方案 A（商品/套餐建 marketplace、版本化快照、订单创建时冻结）+ 全部默认值：

1. **商品/套餐编辑：编辑出新版本**（镜像 `task` 草稿→发布，`package_version` 不可变快照）。
2. **库存：按套餐总库存**——原子扣减/回补（`UPDATE ... WHERE stock >= 1 RETURNING`，镜像积分扣减 GL-P0-CRED-001）；门店分时段库存延后。
3. **有效期：固定截止日 + 购买后 N 天两者都支持**（套餐配置）。
4. **过期未核销自动退款进首期**——按 `redeemDeadline` 扫描并触发退款消费者（PRD §8.3「未核销全额退」）；实现采用数据库状态 + 补偿 dispatcher，不为每笔订单创建 Temporal workflow。
5. **推广归因：固定单一归因**——一个订单归一个推荐官，分账按订单快照 splitPlan 给该推荐官；多推荐官/换绑归因延后。
6. **平台抽成：按任务/套餐配置**——抽成随 splitPlan 冻结进订单快照，商家发布时定，灵活；具体 5%–10% 数值由产品后续填。

**服务归属**（HLD §6.2 事实单写）：商品/套餐 catalog + 版本快照 + 消费者订单 + 订单快照 + 核销码签发 → **marketplace**；支付意图/支付/退款/对账/分账 → **finance**（经 D-01 存管通道）。订单快照冻结字段：`orderId/consumerAccountId/orgId/storeId/taskId/packageVersionId/priceCents/splitPlan/policyVersion/redeemCode/status/时间戳/redeemDeadline`——**价格与分账比例创建时快照，配置不篡改历史**（HLD §2.3）。

**当时解锁结果**：到店核销链路（商品→订单→支付→核销→分账）得以进入 LLD；`GL-P2-FIN-002` 可按订单快照 splitPlan 设计 Payment/Refund/分账（**生产资金通道仍依赖 D-01 真实 PSP 落地**）。

**实现状态（2026-08-11）**：Marketplace V26、Finance V11、`/api/v2` Web UI、Sandbox 支付/退款/核销/分账和过期补偿 dispatcher 已完成。仍延后：真实消费者支付（依赖 D-01）、多门店时段库存、多推荐官/换绑归因、部分退款和已核销售后争议。

## 背景

到店核销佣金分成（PRD 模式三）引入消费者：消费者扫推荐官专属二维码 → 在平台查看门店与套餐 → 创建订单并支付 → 获核销码 → 到店核销 → 分账（佣金给推荐官 + 余款给商家）。ADR 起草时，HLD §10.4 曾把「套餐/商品、库存、有效期和价格快照模型」列为 `DECISION REQUIRED`，并给出包含 Temporal `SplitAfterRedemption` 的概念时序；当前 Sandbox 实现已改为数据库状态 + durable dispatcher。

本决策当时确定了：**商品/套餐如何建模与版本化、订单快照冻结哪些字段、库存与有效期的处置规则、订单/支付/核销/分账的服务归属**，并解锁了后续 LLD 与当前 Sandbox 实现。

## 当前代码现状（2026-08-11）

- marketplace 已有 `commerce_package`、不可变 `commerce_package_version`、版本库存、`consumer_order`、核销码哈希和一单一评价；订单创建时冻结价格、归因和三方分账金额。
- finance 已有 `consumer_payment`、`consumer_payment_refund`、`consumer_payment_split`、`CONSUMER_ESCROW` 与对应 Journal/Posting 双录；当前 provider 是 Sandbox。
- Web 已提供推广落地页、下单、订单列表、退款、评价，以及商家套餐管理、摄像头/输码核销和后台订单监控。真实 PSP、Webhook、存管/合规和生产对账仍受 D-01 约束。

## 方案与取舍

### 方案 A：商品/套餐作为版本化实体建在 marketplace，订单冻结快照（推荐）

镜像 `task_version`（V11）的不可变快照模式：

- **商品/套餐（package）**：归属 marketplace，可编辑、版本化（`package_version` 快照表，同 `task_version` 套路）。字段：所属门店/org、关联任务（归因）、标题、内容、价格、库存上限、有效期窗口（上架/下架、核销截止）、分账比例（推荐官佣金 / 商家余款 / 平台抽成）。
- **订单（consumer_order）**：归属 marketplace，**创建时冻结快照**——含 `packageVersionId`、`priceCents`（快照）、`splitPlan`（快照：分账比例）、`policyVersion`（D-02 的 Finance Product Policy 版本）、`taskId`（归因）、核销码、状态、时间戳。
- 后续对商品/任务/Policy 的编辑**不改变已下订的订单**（HLD §2.3 配置不篡改历史）。

  - ✅ 与 `task_version` 一致的版本化/快照范式，复用既有模式，不引入新服务。
  - ✅ 订单与核销/分账在同一一致性边界（marketplace），订单→核销→分账的强一致不需跨服务事务。
  - ✅ 价格/分账快照冻结，避免商家改价/改比例影响存量订单。

### 方案 B：商品/订单建独立 commerce-service

把 commerce 拆成第七个服务。

- ❌ HLD §2.2「不为首期将每个实体拆成独立服务」；commerce 与 task/engagement/settlement 强相关，拆开反而引入跨服务一致性负担。**否决**。

### 方案 C：不建商品/套餐目录，订单即兴（价格硬编码在二维码）

每个推广二维码绑定一个固定价格，无目录、无库存、无有效期。

- ❌ 无法支撑库存管控、有效期、改价、多套餐、平台抽成配置；PRD §10.4 的套餐/库存/有效期模型无载体。**否决**。

## 推荐

**采纳方案 A**。归属与快照细则：

### 1. 服务归属（HLD §6.2 事实单写）

| 事实 | 权威服务 |
|------|----------|
| 商品/套餐 catalog + 版本快照 | marketplace |
| 消费者订单 + 订单快照 + 核销码签发 | marketplace |
| 支付意图 / 支付 / 退款 / 对账 | finance（经 D-01 存管通道） |
| 分账（核销后） | finance（Marketplace durable dispatcher 调用幂等 split API，按订单快照 splitPlan） |

### 2. 订单快照冻结字段（创建订单时写入，不可变）

`orderId`、`consumerAccountId`、`orgId`/`storeId`、`taskId`（推广归因）、`packageVersionId`、`priceCents`、`splitPlan`（推荐官佣金 / 商家余款 / 平台抽成，按 D-02 Policy）、`policyVersion`、`redeemCode`、`status`、`createdAt`/`paidAt`/`redeemedAt`/`refundedAt`、`redeemDeadline`。

> 关键：**价格与分账比例在订单创建时快照**。商家之后改价、改分账比例、改 Policy，都不影响已下订订单的分账——分账严格按订单快照执行。

### 3. 库存规则

- 下订（支付成功）→ **原子扣减**库存（`UPDATE ... WHERE stock >= 1 RETURNING`，同积分扣减的原子模式，GL-P0-CRED-001）。
- 取消/退款 → 库存回补（幂等，按 orderId）。
- 库存 0 → 下订 409「售罄」。

### 4. 有效期规则

- 套餐有 `redeemDeadline`（核销截止）；订单继承快照。
- **过期未核销 → 自动退款消费者**（PRD §8.3「未核销/退款 全额退消费者」）：dispatcher 按 `redeemDeadline` claim 未核销订单并触发幂等退款；因尚未分账，直接从消费者托管原路冲回。
- 核销码核销时校验未过期，过期 → 拒绝核销。

### 5. 推广归因

- 订单带 `taskId` + 推荐官归因。**首期一一归因**（一个订单归一个推荐官）；PRD §10.4「推广归因是否可换绑或多推荐官归因」列为未决——**多推荐官/换绑归因延后**，首期固定单一归因，分账按订单快照 splitPlan 给该推荐官。

## 已拍板口径

1. 已上架套餐允许编辑，但每次编辑生成新的不可变版本。
2. 首期采用套餐版本总库存；门店分时段库存延后。
3. 固定截止日与购买后 N 天同时支持；两者同时配置时取更早时间。
4. 过期未核销自动退款进入首期，由 dispatcher 周期扫描。
5. 首期固定单一推荐官归因，不支持换绑或多推荐官。
6. 平台抽成按套餐配置并冻结进订单快照；具体费率由产品配置。

## 影响

- **已落地**：到店核销 Sandbox 链路（套餐→订单→支付→核销→分账/退款）及 `/api/v2` Web 消费者端。
- **约束**：marketplace 的套餐版本与订单快照不可变，分账按快照；库存原子扣减/幂等回补；dispatcher 修复 Marketplace→Finance 跨服务间隙。
- **后续波及**：真实 PSP PaymentIntent/Webhook/退款/分账与对账仍依赖 D-01；D-02 的核销类分账计划已由订单 splitPlan 快照实例化。
- **依赖 D-01/D-02**：支付与分账的资金通道（D-01）、分账计划口径（D-02）。

## 不在范围

- 消费者账号/认证（统一账号，消费者是默认场景，PRD §1.3）。
- 退款争议（消费者退款后的二次争议，归 D-06 + `GL-P2-TRUST-001`）。
- 多门店时段库存、换绑/多推荐官归因（延后）。
- 平台抽成的财务报表（`GL-P4-ANALYTICS-001`）。
