# ADR-D11：AI 用量单位、预留/退回、平台模型和 BYOK 计费边界

| 状态 | 日期 | 决策编号 | 阻塞范围 | 依赖 |
|------|------|----------|----------|------|
| 草案 | 2026-08-02 | D-11（HLD §19、§12.3） | Intelligence、Finance | D-01（仅 AI 真实收费时） |

## 背景

HLD §12.3 定义平台 AI 能力管理：按能力（文本/视觉/图片/视频理解/视频生成/语音/内容安全/检索）配置平台主备模型；普通用户默认用平台能力、无需配 Key；用户/商家组织可选 **BYOK**（自带 Key），按能力选平台或自有模型；BYOK 模型不支持某能力时回退平台模型**必须明确授权，不能静默扣平台额度**。HLD §19 D-11 要求定「AI 用量单位、预留/退回、平台模型和 BYOK 计费边界」。

现状（`GL-P2-FIN-001`）：AI **仍主要走 legacy credits**——每次调用扣一个抽象积分，无真实 usage account、无 Run 计量、无 BYOK 计费边界、无 model budget。本决策定计量单位、预留/退款、平台模型与 BYOK 的计费边界，使 AI 从「抽象积分」升级为「可计量、可计费、可预算」的真实用量体系（`GL-P3-AI-001` 控制面的前置）。

## 当前代码现状

- intelligence-service 有 `AiCapabilityAdapter` 抽象、`GeneratedImageStore`（S3）、`media_reference`；能力含文本（Qwen/OpenAI-compat）、视觉、图片生成、视频生成（stub，`VIDEO_GENERATION_IMPLEMENTED` 常量硬关，GL-P0-BILL-001）。
- **计费**：legacy credits——`requireCredit` 在调用前扣积分，失败 `charge.refund()`（GL-P0-BILL-002 的 `CreditCharge` 句柄），用户 abort 不退。积分是抽象单位，不映射真实成本。
- **BYOK**：设置里有服务端持久化的 provider 凭据（per-org），但**无 Envelope Encryption**（明文或简单加密）、无 BYOK 与平台模型的运行时分发与计费区分。
- **无 usage account**：无 Run 记录、无 token/张/秒计量、无 model budget。

## 方案与取舍

### 方案 A：维持抽象积分，不做用量计量（现状）

每次调用扣固定积分，不区分能力真实成本。

- ✅ 零改动。
- ❌ 积分不反映真实成本（视频生成 vs 文本天差地别），平台要么亏损补贴要么定价偏离；无法做 model budget；BYOK 与平台模型计费混淆。**不可持续**。

### 方案 B：按能力真实计量（token/张/秒/次），平台模型收 credits、BYOK 不收 AI 费（推荐）

- **计量**：按能力**按实量**计量——文本=token、图片=张、视频=秒、视觉/语音=次；**不做跨能力的人为归一**，每能力独立计价。
- **usage account**：每个 AI Run 写一条用量记录（account/org、能力、模型、用量、成本、状态），作为真实用量账本；credits 仍作为预付单位，但每条用量映射真实成本（credits = 预付，usage = 真实计量，二者经价目表换算）。
- **平台模型**：平台承担 provider 成本，向用户收 credits（或真实收款，依赖 D-01）；定价含平台 margin。
- **BYOK**：用户自带 Key（Envelope Encryption，`GL-P3-AI-001`）；**平台不收 AI token 费**（用户直付 provider）；可选收小额「控制面费」（首期推荐**不收**，激励 BYOK）。
- **BYOK 回退**：BYOK 模型不支持某能力时回退平台模型，**必须用户/组织策略明确授权**（HLD §12.3），否则该能力直接失败，不静默扣平台额度。

  - ✅ 计量反映真实成本，model budget 可行，BYOK 与平台模型边界清晰。
  - ✅ 符合 HLD §12.3 与 §2.3 显式版本化（价目表/模型配置版本化）。
  - ❌ 需建 usage account + Run 计量 + 价目表 + BYOK 密钥管理（Envelope Encryption），是 `GL-P3-AI-001` 的主体工作。

### 方案 C：统一「AI 币」归一单位

把所有能力折算成统一「AI 币」（如 1 币 = 1k token = 0.1 图）。

- ❌ 折算率难定且随模型变；掩盖真实成本结构；provider 计价维度（输入/输出 token、分辨率、时长）无法简单归一。**否决**。

## 推荐

**采纳方案 B**。细则：

### 1. 计量单位（按能力，按实量）

| 能力 | 计量单位 | 说明 |
|------|----------|------|
| 文本（生成/分析） | token（输入+输出分开计） | SSE 流式按实际产出 token 计 |
| 图片生成 | 张 | 按分辨率/质量档分价 |
| 视频生成 | 秒 | 按分辨率/时长分价 |
| 视觉理解 | 次（含输入 token） | 视为带图输入的 token |
| 语音 | 秒 / 字符 | 按能力定 |
| 内容安全 / 检索 | 次 | |

> 不做跨能力归一；价目表（每能力每模型的价格）**版本化**（HLD §2.3），变更发新版本，存量 Run 按其时点价目表结算。

### 2. 预留 / 退回（沿用 GL-P0-BILL-002 退款范式）

- **Run 开始前**：按「预算上限」预留 credits（如文本按 max_tokens 预估、视频按申请时长）。`requireCredit` 返回 `CreditCharge` 句柄。
- **Run 成功**：按**实际用量**结算（预留 - 实际 = 退回多余预留）。
- **provider 失败**：`charge.refund()` 全额退预留（GL-P0-BILL-002；refund 键 `refund:<operationId>` 幂等）。
- **用户主动 abort**：**不退**（内容已流出，GL-P0-BILL-002 既有口径）。
- **超预算硬停**：Run 执行中用量逼近预算上限 → 中断（model budget 硬停，`GL-P3-AI-001`）。

### 3. 平台模型 vs BYOK 计费边界

| 场景 | AI token 费 | 平台控制面费 | 说明 |
|------|-------------|--------------|------|
| 平台模型 | 收 credits（含 margin） | 已含 | 平台承担 provider 成本 |
| BYOK | **不收**（用户直付 provider） | 首期**不收** | 仅计量入 usage account 供 quota/budget |
| BYOK 回退平台模型（经授权） | 回退部分收 credits | — | 必须策略授权，按平台模型口径收 |

### 4. BYOK 密钥与回退（安全 + 计费）

- BYOK Key 经 **Envelope Encryption**（DEK 加密 Key，KEK 在 KMS/Secret Manager，`GL-P3-AI-001` 前置）；数据库只存密文 + Key Version + 掩码提示（HLD §11.2）。
- 普通成员**可用不可见**完整 Key（HLD §12.3）。
- BYOK → 平台模型回退：组织策略显式配置「允许回退」；未配置则该能力失败，**不静默扣平台额度**（HLD §12.3 硬规则）。

### 5. usage account 与价目表

- 新增 AI usage account（intelligence 或 finance 归属——**推荐 finance 归属资金侧，intelligence 归属用量事实**，跨服务经事件，符合 HLD §6.2 事实单写）：intelligence 写 Run 用量事实，finance 记 credits 扣减/退回账本。
- 价目表版本化，Run 记录其结算时的 `priceTableVersion`，配置不篡改历史（HLD §2.3）。

## 待你拍板

1. **BYOK 平台控制面费**：首期推荐**不收**（激励 BYOK、降低计费复杂度）。若要收，需定费率（按 Run 次数？按用量？）。
2. **credits 是否长期保留 vs 全转真实收款**：首期推荐 credits 作为预付单位 + usage 真实计量并存；真实收款（Payment 充值 credits）依赖 D-01。是否首期即上「真实付款充 credits」？
3. **model budget 默认值**：每 Run / 每 org 的 token/张/秒预算上限默认值（⚙️ 需运营/成本定）。
4. **usage account 归属**：推荐 intelligence 写用量事实、finance 记账本；是否同意此拆分？
5. **价目表定价**：各能力各模型的具体价格（含平台 margin）——需成本核算 + 产品定价，本 ADR 只定口径。
6. **视频生成**当前硬关（`VIDEO_GENERATION_IMPLEMENTED`）；接入时（`GL-P3-VIDEO-001`）按秒计费，是否纳入本 ADR 口径（推荐纳入，统一计量）。

## 影响

- **解锁**：`GL-P3-AI-001` 控制面（Run/usage/model budget/BYOK）可按本口径进 LLD；`GL-P2-FIN-001` 真实 AI 收费可基于 usage account + credits 充值设计。
- **约束**：intelligence 每次 AI Run 写用量记录 + 预留/结算/退回；finance credits 扣减从「调用即扣」升级为「预留→实际结算/退回」；BYOK 需 Envelope Encryption；价目表/模型配置版本化。
- **波及**：现有 6 个扣分点（GL-P0-BILL-002）的 `requireCredit` 句柄需从「固定扣」改为「按预算预留 + 实际结算」；`AiCapabilityAdapter` 需返回用量（token/张/秒）供计量；BYOK 回退策略需组织配置 UI。
- **依赖 D-01**：仅当「真实付款充 credits」进首期时依赖 D-01 存管；纯 credits 预付不依赖。
- **联动 D-10**：BYOK/平台模型若走境外 AI，触发 PIPL 跨境评估（D-10 §5）。

## 不在范围

- AI 控制面的具体实现（Run 调度、并发、健康检查、DNS pinning）——`GL-P3-AI-001` LLD。
- 视频生成供应商接入——`GL-P3-VIDEO-001`。
- legacy credits 的迁移与退场节奏（渐进，`GL-P2-FIN-001` 承接）。
- AI 内容安全/合规审核策略（属能力治理，非计费）。
