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
