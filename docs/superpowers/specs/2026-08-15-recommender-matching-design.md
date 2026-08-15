# 推荐官自动匹配 / 智能排序（确定性 v1）设计

- 日期：2026-08-15
- 状态：已批准（产品方向由用户确认；本文冻结 v1 技术口径）
- 范围：Web 商家工作台 + 推荐官通知中心/任务大厅
- 对应缺口：PRD §6、`docs/草场开发进度与续接指南.md` 路线图第 13 项

## 1. 目标与非目标

**目标**：商家选中已发布任务后，查看全站候选推荐官的稳定排序；每条结果展示总分、六维分数、计算证据和推荐理由；商家可以发邀请，推荐官从通知中心直达该任务并按正常报名规则报名；邀请与报名状态可审计。

**非目标**：
- 不使用 LLM、Embedding 或外部画像做匹配。
- 不新增 marketplace → identity/trust/intelligence 同步调用，不跨库读取用户资料。
- 不做自动接受、自动报名、批量群发、付费置顶或人工改分。
- 不把推荐结果永久缓存为当前真相；只有发出邀请时冻结该次评分证据。

## 2. 数据边界与候选池

所有评分事实只来自 marketplace 已有表：

| 事实 | 来源 |
|---|---|
| 任务目标平台、最低等级 | `task` |
| 全站报名历史、接受/完成、最后活动 | `task_application` |
| 首次交付响应速度 | `engagement_submission` |
| 平均评分 | `engagement_rating` |
| 当前有效等级 | 现有 `ReputationService`（同库事实 + 当前声誉策略 + Lv5 准入） |

基础候选池为 `task_application` 中出现过的全部唯一 `recommender_account_id`。对指定任务生成列表时再排除：

1. 已报名当前任务的人（无须再邀请）。
2. 当前有效等级低于任务 `min_recommender_level` 的人（邀请后也无法报名）。
3. 任务 owner 本人（防止自邀）。

报名历史本身即足以计算 `ReputationStats.empty()` 之外的活动事实；无评分或无响应样本保留在候选池中，对应维度记 0 分并显式标为“暂无样本”，不填中性分。

## 3. v1 评分公式

总分为整数 0-100，`scoringVersion = "deterministic-v1"`。各维只用下表规则，任何实现不得加入隐藏加权：

| 维度 | 满分 | 证据与分段 |
|---|---:|---|
| 平台契合度 | 30 | 统计该推荐官在同平台上形成履约的报名数（状态曾进入 accepted/refunded）：0→0，1→15，2→22，≥3→30；任务平台为空时为 0 |
| 等级 | 15 | 当前有效等级：Lv1→0，Lv2→4，Lv3→8，Lv4→12，Lv5→15 |
| 完成率 | 20 | `round(completionRate × 20)`；沿用声誉口径，从分母排除商家取消 |
| 平均评分 | 15 | 有样本时 `round(averageScore / 5 × 15)`；无样本 0 |
| 响应速度 | 10 | 平均首次交付响应：≤24h→10，≤48h→8，≤72h→6，≤7d→3，>7d→1；无样本 0 |
| 近期活跃 | 10 | 距 `lastActiveAt`：≤7d→10，≤30d→8，≤90d→5，≤180d→2，>180d/无样本→0 |

计算时间统一使用服务端注入的 `Clock`，响应返回 `computedAt`。并列时依次按：总分降序、平台分降序、完成率分降序、`accountId` 升序。这样同一事实快照、策略版本和计算时间必定得到相同结果。

每个维度返回：`key`、中文 `label`、`score`、`maxScore`、结构化 `evidence`。推荐理由是纯规则生成，取有分维度中得分率最高的前三项；全维 0 时返回“有全站报名历史，可继续观察履约表现”。理由不是新的评分输入。

## 4. API 契约

| 端点 | 权限 | 语义 |
|---|---|---|
| `GET /api/tasks/{taskId}/recommendations?limit=50` | 当前任务 manager | 返回稳定排序、公式版本、计算时间、六维明细；`limit` 1-100 |
| `POST /api/tasks/{taskId}/recommendations/{accountId}/invite` | 当前任务 manager | 复算资格与分数，冻结邀请快照，同事务写 outbox；重复邀请幂等返回既有记录 |

推荐响应不暴露 identity 私有资料，候选标识只返回 marketplace 已知的 `accountId` 与声誉/评分事实。前端可复用已有公开推荐官画像接口做辅助展示，但它不参与排序，也不影响评分端点可用性。

## 5. 邀请与审计模型（marketplace V30）

```sql
task_recommender_invitation(
  id, task_id, recommender_account_id, invited_by_account_id,
  scoring_version, score_snapshot jsonb,
  created_at, applied_at,
  UNIQUE(task_id, recommender_account_id)
)
```

- `score_snapshot` 冻结总分、六维得分/证据、声誉策略版本和 `computedAt`；邀请后事实变化不改历史。
- 同一任务/推荐官只能有一条邀请。重复 POST 返回同一 id，且不重复写 outbox/通知。
- 新邀请与 `TaskRecommenderInvited` outbox 在同一 R2DBC 事务。
- 推荐官成功创建报名时，在同一事务把匹配邀请的 `applied_at` 设为当前时间；无邀请的普通报名不受影响。
- 邀请不绕过任务状态、报名截止、名额、最低等级和一人一报规则。通知只是入口，报名仍走既有 `POST /applications`。

## 6. 通知中心直达

`TaskRecommenderInvited` payload 至少包含 `taskId`、`recommenderAccountId`、`taskOwnerId`、`invitationId`。identity 只从 payload 取收件人，不反查 marketplace；模板分类为 `engagement`，`linkPath=/me/task-invitations`，通知 payload 保留 `taskId`/`invitationId`。

前端解析该专用落点后：

1. 打开草场工作台并切到推荐官视角。
2. 通过既有 `GET /api/tasks/{id}` 读取目标任务并选中。
3. 滚动到任务大厅，突出目标任务；用户点击“报名”后仍执行原报名端点。

任务已关闭、截止或等级变化导致不可报名时，详情仍按既有可见性契约处理，报名端点返回真实原因；前端不伪造“邀请保证可报名”。

## 7. 安全、性能与可观测性

- 两个新端点都必须经 `TaskResourceAuthorization.requireManager`，门店任务继续实时向 identity 校验 manager；这是既有授权调用，不是新增数据依赖。
- 候选账号一次 SQL 取回，声誉用 `ReputationService.snapshots` 批量聚合；禁止按候选 N+1 查询。
- v1 `limit≤100`，先取全量候选并评分后截断，避免数据库分页发生在排序前导致错误 Top N。
- 不记录自由文本画像、邮箱、手机号或 LLM prompt；日志只记录 task/invitation/account 标识和公式版本。
- outbox 继续沿用既有至少一次投递 + identity inbox 幂等。

## 8. 验收标准

- 固定事实下六维分数、总分、排序和理由可由测试逐项复算。
- 无评分/响应样本与真正低分可区分（`sampleCount=0`/`value=null`）。
- 非任务 manager 无法读推荐列表或发邀请。
- 重复邀请不产生第二条邀请或第二个 outbox 事件。
- 点击邀请通知能进入指定任务，且报名后 `application` 创建、邀请 `applied_at` 回填。
- marketplace、identity、前端定向/全量测试以及 typecheck/build 通过。
