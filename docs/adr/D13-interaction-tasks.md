# ADR-D13：点赞互动任务类型（content_form 受控化 + 互动核验）

| 状态 | 日期 | 决策编号 | 阻塞范围 | 依赖 |
|------|------|----------|----------|------|
| 已采纳 | 2026-08-17 | D13（PRD §2.2 任务类型 / §9 核实维度第 3 行） | Marketplace、Intelligence、前端 | Verification v2、#22 正交资金字段 |

## 背景

任务类型三选一（图文种草 / 视频种草 / 点赞互动）只落了图文与视频的弱约定：`content_form` 是自由字符串
（marketplace V1，前端纯文本框），「点赞互动」任务形态及其截图凭证、互动核验完全未实现。

**核心建模判断**：点赞互动不是新任务大类，是 `content_form` 的**第四个受控值 `interaction`**——它决定凭证形态
与核验分支，而发布/报名/接受/确认/结算/争议链路与图文视频完全同构，全部复用。

## 决策

### R1 content_form 受控化

- 规范值集：`image | video | article | interaction`（null = 未指定，沿用现状）。
- 写入口（create/update/revise）契约层校验值集；DB 迁移先规范化存量（lower/trim，同义词归并：
  `视频`→video、`图文|图片`→image、`文章`→article，未知值置 NULL），再 `CHECK NOT VALID` + `VALIDATE`
  （镜像 V21 两段式，避免锁表）。
- 存量未知值置 NULL 而非拒绝：大厅可见性不受影响（feed 只在显式筛选时按值过滤）。

### R2 互动任务的任务侧配置

`TaskRequirements` 加可空结构块 `interaction`（镜像 `commissionLadder` 的嵌套 record 模式，自动随
task_version / task_context 快照机器冻结，marketplace 不加新列）：

```json
"interaction": { "targetUrl": "https://...", "actionType": "like" }
```

- `actionType` 受控值：`like | favorite | follow`。**评论不做**——评论带 UGC 内容安全与 moderation 负担，
  登记后置。
- 交叉校验：`contentForm=interaction ⇔ requirements.interaction 非空`（单边违反 400），挂 create/update/revise 三入口。
- `targetUrl` 复用核验引擎既有 `LinkUrlGuard`（http(s)、无凭据、SSRF/私网拒绝），不新写一套。
- v1 单目标单次动作；「N 次互动/多目标/有效期跟踪」登记为后续（截图证据语义会翻倍，不首期背）。

### R3 凭证形态（提交侧）

复用既有 submission 形状，语义重定义 + 一个新字段：

| 字段 | 互动任务语义 |
|---|---|
| `contentUrl`（既有，必填） | **被互动的目标帖子/账号链接**（不再是要发布的作品链接） |
| `platformHandle`（新增，可空） | 推荐官在该平台的账号标识；`contentForm=interaction` 时**必填**（提交契约 400，≤64 字符） |
| `mediaIds`（既有，上限 6） | **动作截图**（展示其账号已点赞/收藏/关注的界面截图） |
| `note` | 自由备注 |

截图 ≥1 张是**核验检查**（`evidence_completeness`）而非提交时硬拒：截图缺失走核验 failed/inconclusive
→ 商家在确认窗口决策/退回，与既有核验语义一致（不引入提交时快照状态的新分支）。

### R4 核验维度——与现有四类检查的关系（核心）

**不新建检查体系**。互动任务的检查表：

| 检查键 | 互动任务下的语义 | 变化 |
|---|---|---|
| `link_reachability` | 目标链接可达 | **复用零改动** |
| `platform_identity` | 目标链接域名 vs 任务平台一致 | **复用零改动**（输入取 contentUrl，天然成立） |
| `evidence_completeness` | `platformHandle` 非空 + 截图 ≥1 | **规则分支扩展**（图文/视频规则不变） |
| `ai_visual` | 原作品视觉检查 | 互动任务**跳过** |
| `interaction_screenshot`（**新检查键**） | 多模态模型识别截图：① 截图内容与目标帖子匹配；② 动作状态可见（已赞/已藏/已关注标记）；③ 截图账号与 `platformHandle` 一致 | **新增**：复用 ai_visual 的 intelligence 多模态调用通道（`/api/verification/analyze` 加 `mode=interaction` 分支），换互动专用 prompt 与上下文（targetUrl/actionType/handle），结构化 pass/fail/inconclusive |

- **不确定即人工**：模型不可用/低置信 → `inconclusive` → 既有 VERIFICATION 待判定队列 + 人工 override
  （GL-P2-ADMIN-004 链路原样吸收，与 PRD §9「各平台采集方式待确认」的谨慎一致）。
- runs append-only、每次核验冻结输入、人工改判不改自动结果——全部沿用既有不变式。
- 结算折叠到 `engagement_verification` 的规则不变（最差结果胜出）。

### R5 明确不做

- 评论类互动（UGC moderation，后置）；多目标/次数/时长跟踪；平台官方 API 数据源（P1 真实指标项）；
  互动反作弊（同账号跨任务养号风控——登记风险，归风控域后续校准）。
- 资金模式无特例：互动任务照常配 bounty/阶梯佣金/霸王餐押金（与 ADR-D12 正交）。

### R6 前端行为

- 发布表单：内容形式从自由文本输入改为**下拉**（图文/视频/文章/点赞互动）；选「点赞互动」时展示
  目标链接 + 动作类型两个字段（必填），其余要求字段照旧。
- 推荐官提交面板：互动任务显示「平台账号标识」输入 + 截图上传提示文案。
- 任务大厅：卡片显示「点赞互动」徽标；feed 的 contentForm 筛选对 `interaction` 值天然生效（后端零改动）。
- **「围绕任务创作」入口对互动任务隐藏**（AI 创作不适用于无内容交付的任务；按 contentForm 分支）。

### R7 通知与结算：零新增

`DeliverableSubmitted`/`VerificationChecked`/`VerificationOverridden` 及结算通知全部复用（#28 模式已在）。

## 影响

- **Marketplace**：V41 迁移（content_form 规范化 + CHECK + `engagement_submission.platform_handle` 列）；
  `TaskRequirements.interaction` 嵌套 record + 三入口交叉校验；`CreateSubmissionRequest.platformHandle` +
  互动必填分支；核验引擎 `evidence_completeness` 分支 + `interaction_screenshot` 检查 + `ai_visual` 跳过。
- **Intelligence**：`/api/verification/analyze` 请求加可选 `mode/actionType/platformHandle/targetUrl`，
  默认 `visual` 行为零改动；互动专用 prompt。
- **前端**：见 R6。

## 不在范围

- 平台官方互动数据源（P1）；反作弊风控；评论类互动；多目标/次数任务。
