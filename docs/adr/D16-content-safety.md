# ADR-D16：内容安全检查与 content_safety capability

| 状态 | 日期 | 决策编号 | 阻塞范围 | 依赖 |
|------|------|----------|----------|------|
| 已采纳 | 2026-08-17 | D16（PRD §4.7 规范检查 / §4.14 内容安全与版权 / §4.10 capability） | Intelligence、edge、前端 | 控制面 capability 机器、AiExecutionService 单环 |

## 背景

规范检查现状只有字数/标题/结构提示（`platform-format-rules.json` 契约），无敏感词库、无内容安全模型调用；
控制面 capability 设计 8 种实际接入 4 种（`content_safety` 仅存在于迁移注释与前端标签映射）。本决策把
敏感词/违规承诺/夸大宣传（广告法极限词）/导流表达的**自动检测**接入创作链路，`content_safety` 真实落地。

## 决策

### D1 两层检测架构：确定性永远跑，LLM 深检可配置

- **L1 确定性层**（词库子串 + 正则）：无模型依赖、毫秒级、**每次生成必跑**——不可降级的底线。
- **L2 LLM 深检**（content_safety capability 经控制面路由）：上下文级违规判定（虚假宣传语境、夸大暗示、
  侵权风险提示）。控制面未配置 content_safety 模型 → 只跑 L1，findings 标 `deepCheck:false`，
  生成流程零影响（增强项而非闸门，见 D6）。
- seeder 缺省**不**种 content_safety（生产默认 L1-only）；env 显式提供模型配置时才 seed，运营经
  admin CRUD 配置即开 L2。

### D2 词库 = 版本化配置文件（镜像 platform-format-rules 机制）

- `contracts/content-safety-lexicon.json`：`version`（如 `lexicon-v1`）、六类目录、词表、正则模式、例外表。
- Java 构建消费（processResources 进 classpath）+ **版本随创作上下文快照冻结**——规则更新不能改变
  历史生成记录的检查结论（§4.7）。
- admin 在线 CRUD 词库后置（登记）；文件式版本化是 v1 真相源，运营改词 = 提 PR 发版本。

### D3 初版类目与严重度

| category | severity | 起步内容 |
|---|---|---|
| `politics` / `porn` / `illegal` | **high** | 违法类底线词（运营持续扩充；代码交付结构 + 起步集） |
| `absolute_claims`（广告法极限词） | medium | 最/第一/顶级/唯一/绝对/100%/国家级… |
| `false_promises`（违规承诺） | medium | 保本/稳赚/包治/无效退款/永久有效… |
| `diversion`（导流联系） | low | 微信/VX/加我 + 账号正则 |
| `platform_unwanted`（平台不推荐表达） | low | 少量起步，后续按平台 overlay |

- 例外表吸收误报（如「**第一**时间」「**最**新」）——词库内容是运营资产，代码职责是结构、加载、匹配、呈现。
- 中文子串匹配 + 拉丁词边界（`\b`）；精确率取舍：L1 容忍少量误报由例外表消化，上下文判断交给 L2。

### D4 content_safety 只走平台模型，不开放 BYOK

BYOK 白名单维持 `text|image|image_generation|video_generation` 不加 content_safety——用户自带「安全检查
模型」可接空转假模型骗过检查；安全检查完整性优先。个人 BYOK 查 content_safety 永远 miss →
回落控制面，未配置即 denied（= L1-only），路由层天然满足本约束。

### D5 LLM 深检经 AiExecutionService 单一执行环，0 积分

- 深检 = 执行环的一次 run：`capability=content_safety`、**feature=null**（免费执行分支）、
  ai_run 留痕可审计、沿用预算/并发/退款机器——不开第二条执行旁路（单环纪律）。
- **实现载体修正（对任务书的微调，语义不变）**：任务书设想「价目表加 0 价条目、扣减闭环照走扣 0」；
  实测 finance credits 模型为「单次恒扣 1 + 配额倍率 bps（1000-100000）」，**不存在按 feature 的 0 价
  机制**；本库既有的免费执行形态即 `feature=null`（`AiExecutionService` 只对 `provider.isPlatform()
  && feature != null` 发起 consume）。故「平台资助 0 积分」落地为 feature=null 免费分支——
  用户零扣减、run 留痕两项验收语义完全一致，且不为单一 feature 改 finance 扣减语义。
- 深检时机：**长文本内联**（阈值 ≥200 字符且已配置模型；文章正文等，生成后随最终帧返回，延迟被生成耗时
  主导）；**短文本（标题等）仅 L1**。

### D6 门槛姿态 = advisory，不硬阻断

- findings 是「警告 + 类别建议」，用户可编辑后复查（PRD：AI 输出是建议、允许人工编辑、不自动发布）。
- 任务提交侧（履约凭证带 high 发现是否拦截）登记为后续独立决策，本任务不动 marketplace。
- 高风险行业 overlay（依赖 #24 门店品类进上下文）登记为后续钩子。

### D7 双入口：生成流内联自动检 + 独立手动复查端点

- 生成完成 → L1（必）+ L2（长文本且已配置）→ 结果带 `safety` 块。
- `POST /api/content-safety/check`（需登录）：`{text, platform?}` → findings——用户**编辑后**的复查入口。
- 请求体 ≤50KB；platform 可选（overlay 预留，v1 不改变结果）。findings 形态：

```json
"safety": { "findings": [ { "category": "absolute_claims", "severity": "medium",
  "match": "最好吃", "index": 12, "advice": "广告法极限词，建议改为具体描述" } ],
  "lexiconVersion": "lexicon-v1", "deepCheck": true }
```

### D8 v1 接入五条文本流

文章标题+正文、朋友圈文案、喜剧脚本、视频脚本、图片评价文案。各自 result 侧统一带 `safety` 块
（SSE 判别帧约定不变；chunk 流在 [DONE] 前追加独立 safety 帧，非流式 JSON 在 data 内嵌——
新增字段不破坏既有消费器）。创作助手优化输出、视频改编、多模态图片输入登记后续。

### D9 词库服务端独占

不下发前端（单一真相源 + 避免整表被顺手抓走）；前端只渲染 findings（含词库版本标注）。

## 影响

- **Intelligence**：`contracts/content-safety-lexicon.json` + `contentsafety/` 包（Lexicon 加载 / Checker L1 /
  AiChecker L2 / Service 编排 / Controller 手动端点）；创作上下文快照冻结 lexiconVersion；五条流出入口。
- **edge**：`/api/content-safety` → intelligence + flag `EDGE_ROUTE_CONTENT_SAFETY_INTELLIGENCE`。
- **前端**：共享 `SafetyFindingsPanel`（severity 排序、类别 chip、advice、词库版本、重新检查）接入五条流结果区。
- **控制面**：seeder 可选种 content_safety（env 提供时）；admin CRUD 既有端点即可管理。

## 不在范围

- 词库在线 CRUD（后置）；履约提交侧拦截（独立决策）；行业 overlay；多模态输入检查；
  创作助手优化输出与视频改编接入（后续）。
