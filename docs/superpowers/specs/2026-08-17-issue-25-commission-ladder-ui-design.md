# #25 阶梯佣金前端闭环设计

**日期**：2026-08-17  
**状态**：已确认，待实施  
**范围**：前端任务配置、任务展示、履约确认和请求契约；复用现有 Marketplace/Finance 后端能力

## 目标

让商家不再依赖直接调用 API 即可创建、保存、发布和修订阶梯佣金任务，并在确认履约时申报实际指标、预览对应佣金，形成可实际结算的 Web Sandbox 闭环。

## 现状与约束

- Marketplace 的 `CommissionLadder` 已支持版本化单指标策略、1-20 个固定金额档位、按最高已达档位结算、最高档赏金预留和任务快照冻结。
- 商家手动确认阶梯任务时，`POST /api/tasks/{taskId}/applications/{applicationId}/confirm` 必须携带非负整数 `confirmedMetricValue`；固定佣金任务可继续使用无请求体确认。
- 当前前端 `TaskRequirements` 没有 `commissionLadder`，任务表单不能配置阶梯，确认请求也不带指标值。
- 旧 `feat/issue-25-commission-ladder-ui` worktree 基于落后主线 49 个提交的代码，只覆盖发布配置；实现时仅参考其纯转换思路，不直接套用补丁。
- #22 当前规定赏金与霸王餐押金互斥；阶梯佣金属于赏金模式，不能与霸王餐押金同时启用。

## 数据模型

前端公共类型增加：

```ts
interface CommissionLadderTier {
  threshold: number
  payoutCents: number
}

interface CommissionLadder {
  policyVersion: string
  metricKey: string
  tiers: CommissionLadderTier[]
}
```

`TaskRequirements` 增加可空的 `commissionLadder`。表单使用独立的元表示：

```ts
interface CommissionLadderFormData {
  enabled: boolean
  policyVersion: string
  metricKey: string
  tiers: Array<{ threshold: number; payoutYuan: number }>
}
```

`policyVersion` 是内部快照字段：新建默认 `ladder-v1`，界面不展示；编辑草稿或修订任务时保留后端返回的原值。

## 任务配置交互

`MerchantTaskForm` 在赏金输入附近增加“阶梯佣金”开关。开关开启后显示：

- 指标标识输入，例如 `douyin.play_count`；在没有权威平台指标字典前保持自由输入，但提供格式说明。
- 档位列表，每行包含非负整数阈值和人民币佣金金额。
- 添加档位和删除档位操作；至少保留一档，最多 20 档。
- “达到最高档只发该档固定佣金、不累加”和“最高档由任务赏金足额预留”的业务说明。

霸王餐押金大于零时禁用阶梯开关，阶梯开关启用时禁用霸王餐押金输入。两种模式之间切换时，用户必须先主动关闭或清空当前模式；前端不能静默丢失已填档位。

## 表单转换与校验

抽出无 UI 依赖的 `commission-ladder.ts`，负责：

- 默认表单值。
- 元转分、分转元。
- 阈值排序和 payload 构造。
- 从任务快照回填可编辑表单。
- 根据实际指标计算预计佣金。
- 返回单一、可展示的校验错误。

提交审核、保存草稿和保存修订共用同一校验入口：

1. 策略启用时指标标识不能为空，长度不超过 128，并匹配后端允许的字符集。
2. 档位数为 1-20。
3. 阈值必须是非负安全整数且不能重复。
4. 金额必须是有限、非负、最多两位小数的值，换算后为安全整数分。
5. 金额随阈值升高不能下降。
6. 任务赏金必须大于零，且最高档佣金不能超过赏金。
7. 霸王餐押金启用时拒绝提交阶梯策略。

禁用阶梯时 payload 不包含 `commissionLadder`；启用时 payload 按阈值升序发送。创建、编辑草稿、提交草稿和修订已发布任务都复用这一路径。

## 任务展示

商家任务列表和推荐官任务大厅对配置了阶梯的任务显示“阶梯佣金”标识，并展示：

- 指标标识。
- 最低档至最高档佣金范围。
- 展开后的“阈值 → 固定佣金”档位明细。

现有赏金金额继续表示最高可预留金额；展示文案避免让用户误认为每个档位会累加。

## 履约确认

商家报名列表按 application id 保存待申报指标。选中任务含 `commissionLadder` 时：

- 在“确认履约”操作旁显示实际指标输入，标签包含该策略的 `metricKey`。
- 输入要求为非负整数。
- 使用冻结的任务档位实时显示“预计结算 ¥X”；低于首档显示 ¥0。
- 未填、负数、非整数或超出安全整数范围时禁用确认并给出明确提示。
- 确认时调用 `confirmEngagement(taskId, applicationId, confirmedMetricValue)`，发送 JSON 请求体。

固定佣金任务不渲染指标输入，仍调用现有无请求体请求，保持后端兼容。确认成功后清理该 application 的临时输入；失败时保留输入以便修正重试。

## 错误处理

- 所有本地校验失败使用现有 `setNotice`，不发请求。
- 后端 400/409 继续由 `useGrasslandMarketplace` 的统一错误路径展示。
- 编辑旧任务遇到后端返回的未知 `policyVersion` 时原样保留，不擅自升级策略版本。
- 后端若返回非法或空档位，前端不允许提交覆盖，并显示“阶梯佣金配置异常，请重新配置”。

## 测试

采用测试先行，覆盖：

1. 纯函数：排序、元分转换、回填、预计佣金、重复阈值、金额递减、赏金不足、霸王餐冲突和安全整数边界。
2. `MerchantTaskForm`：开关、动态档位、最多 20 档、内部版本不展示、资金模式互斥。
3. `GrasslandWorkbench`：创建/草稿/修订 payload、编辑回填、重置、列表展示、指标输入和预计佣金。
4. `useGrasslandMarketplace`：阶梯确认发送 JSON；固定佣金确认不发送请求体；错误路径不回写成功状态。
5. 回归：#22 霸王餐 XOR、#23 互动任务表单、#27 批量操作与现有确认轮询保持不变。

实现后运行相关 Vitest，再运行 `npm run test`、`npm run typecheck` 和 `npm run build`。本功能不修改 Java 代码；后端既有 CommissionLadder/confirm 集成测试作为契约基线。

## 明确不做

- 不新增后端指标字典、平台指标 provider 或真实数据自动回填。
- 不允许商家编辑策略版本。
- 不改变最高档预留、快照冻结、结算 hold 或 Finance 账本语义。
- 不实现霸王餐押金与阶梯佣金组合。
- 不直接合并或覆盖旧 worktree。

## 完成标准

商家可在 Web 中创建并发布阶梯任务，推荐官可看懂档位规则，商家确认履约时必须申报实际指标并看到预计佣金，前端发出的创建、修订和确认请求与现有后端契约一致，固定佣金和霸王餐任务无行为回归。
