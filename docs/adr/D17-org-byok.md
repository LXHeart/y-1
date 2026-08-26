# ADR-D17：组织级 BYOK 启用

| 状态 | 日期 | 决策编号 | 阻塞范围 | 依赖 |
|------|------|----------|----------|------|
| 已采纳 | 2026-08-21 | D-17（本 ADR；任务书 #37 D6 登记的独立决策） | Intelligence BYOK 路由/管理、组织管理 UI | D-11（计费边界）、HLD §12.3 |

## 背景

任务书 #37（组织 AI 预算管理）立项时明确把「组织级 BYOK 开启」登记为独立决策（D6）：`ai_provider_key.organization_id` 列自 V5 起就为组织维度预留，但 `ByokRoutingService` 在「组织角色与管理策略具备权威校验前保持关闭」。前置条件此后陆续具备：`IdentityOrgAuthorizationClient`（member/admin/owner 三档权威校验，identity 白名单已含 intelligence）、任务书 #37 的组织预算管理模式（端点形状/鉴权/乐观锁/前端面板全套先例）。

## 决策

1. **路由优先级：个人 BYOK > 组织 BYOK > 平台模型。** 个人密钥命中即用（既有语义零变更，含锁定测试）；组织密钥是成员无个人密钥时的兜底；两级都未命中才进入平台回退判定。
2. **管理权：组织 admin/owner**（identity 权威校验，镜像 `AiOrgBudgetController`；组织不存在/权限不足统一 404 隐藏存在性）。端点 `/api/ai/organizations/{orgId}/keys`（CRUD/轮换/软删）与 `/api/ai/organizations/{orgId}/byok-policy`（GET/PUT），KEK fail-closed 与个人版同款。**普通成员「可用不可见」**（D-11/HLD §12.3）：无成员读取端点，仅运行时路由使用。
3. **平台回退策略（D-11 硬规则的落地口径）**：组织**未配置**任何有效组织密钥时，回退沿用调用方显式 `allowFallback`（与组织级开启前完全一致，零破坏面）；组织配置了有效组织密钥后，成员的平台回退须「组织策略开关（`ai_org_byok_policy.allow_platform_fallback`，默认不允许/无行即关）**且**调用方 `allowFallback`」双满足，否则 DENIED(`fallback_not_authorized`)，不静默扣平台额度。
4. **审计**：`ai_run.byok_organization_id` 记录组织密钥命中的 Run（个人 BYOK/平台为 null），TaskContext 透出；组织密钥的 `modelVersionKey` 用 `byok-org:` 前缀与个人区分。
5. **计费/预算**：沿用 D-11——组织密钥 Run 恒 0 cents、不 consume/refund 平台积分；token 计量入组织预算（查键为组织密钥的 provider 名，现机制天然支持）。
6. **冻结配置重解析**（PRD §4.12）：快照引用组织密钥时，重跑者必须仍是该组织成员（identity member 校验），否则与配置漂移同口径 409 fail-closed。

## 取舍记录

- **个人优先 vs 组织强制**：组织强制（禁个人密钥）会破坏已上线的个人 BYOK 语义，且 HLD §12.3「用户或商家组织**可以**选择 BYOK」两者并列——取个人优先。
- **策略开关 vs 沿用调用方 allowFallback**：D-11 原文「BYOK → 平台模型回退须组织策略显式授权，未配置则该能力失败」明确要求组织策略维度；仅影响新采用组织密钥的组织（零存量破坏），故按原文落地双闸，而非只靠调用方参数。
- **策略存储**：独立小表 `ai_org_byok_policy`（org PK + bool + version 乐观锁），不挂在预算行上——预算行可不配置而策略仍需存在。

## 不做（后续可另行立项）

- 成员端组织密钥只读列表（「可用不可见」首期从严，不给成员任何读取面）。
- 组织强制禁用成员个人 BYOK 的策略开关。
- 同一组织同能力多把 active 密钥（唯一索引限定一把，轮换/停用后再建）。

## 修订记录

### 2026-08-25 · 任务书 #47 S4（D9 + D16）

**决策 1「个人优先」被取代为按活动身份分叉。** 原文「个人 BYOK > 组织 BYOK > 平台模型」的单一优先级链不再成立：

- **merchant 活动身份** → `组织 BYOK > 平台`，**跳过个人密钥**（不是降级排序）。商家侧由组织统一配置模型；个人密钥数据保留，只是不参与该视角的路由。
- **recommender / 消费者** → `个人 BYOK > 平台`，与本 ADR 采纳时的行为逐字节一致。

分叉依据是 edge 的既有不变量：`SessionIdentityResolver:75-80` 保证只有 merchant 活动身份才携带 org/tier（其注释明说破坏它就破坏 HLD 7.4「活动身份 ↔ 组织上下文」）。因此每个 session 的链路是单一确定的，「同时看两层」的歧义不存在。原「取舍记录」担心的「组织强制会破坏已上线的个人 BYOK 语义」在分叉方案下不成立——推荐官侧完全不变。

**决策 3 的默认值由 `false` 翻为 `true`**（V48 迁移的 `SET DEFAULT true` + 代码侧 `defaultIfEmpty(true)`，两者必须同批上线）。原默认与任务书 #47 D15「组织配了该 capability 的 key 就用、没配就走平台」在「组织配了 text key、没配 image key」时直接冲突：org admin 配完 text 之后，图片能力会对全组织成员突然 DENIED，而他完全不会预期到这件事。

保留的部分：**已显式设过 `false` 的组织仍严格拒绝**（那是明示选择，V48 不 UPDATE 任何存量行）；D-11「不静默扣平台额度」的双闸语义不变——严格模式下调用方 `allowFallback=true` 也照样拒。

`GET /api/ai/organizations/{orgId}/byok-policy` 未配置时的响应默认同步翻为 `true`，否则 admin 面板会显示「不允许」而运行时实际允许；`configured=false` / `version=0` 不变，前端据此仍能区分「未配置」与「显式设为 true」。
