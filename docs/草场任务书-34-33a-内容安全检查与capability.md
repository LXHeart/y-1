# 任务书 #34+#33a：内容安全检查 + content_safety capability（合并立项）

> 生成日期：2026-08-17。交办对象：Qoder（独立开发代理，无会话上下文，本文自包含）。
> 仓库：当前 main。背景与缺口登记：`docs/草场开发进度与续接指南.md` 横向缺口 #34、#33（capability 部分）；推荐执行顺序第四批第 13 项。
> 完成后须回写进度指南（#34 标完成、#33 更新为剩 voice/retrieval 两项）并同步 CLAUDE.md。
>
> **本任务分两个 Phase**：Phase A 把「规则决策包」誊写为 ADR（`docs/adr/DXX-content-safety.md`，编号先 `ls docs/adr/` 确认）；Phase B 按 ADR 实现。重大偏离须停下回报。

## 目标（PRD §4.7 规范检查 / §4.14 内容安全与版权 / §4.10 capability 七种之一）

- 敏感词、违规承诺、夸大宣传（广告法极限词）、导流表达的**自动检测**，接入创作生成链路与手动复查
- `content_safety` capability 从「控制面标签位」变为真实接入（#33 三缺一的第一个）
- 检测结果以结构化 findings 呈现给用户（advisory），支撑「商业发布前确认」的信任基础

**现状**：规范检查=字数/标题/结构提示（`contracts/platform-format-rules.json` + 前端 `useArticleFormatRule.ts:35-50`，后端 `PlatformCreationRuleCatalog.java:21-33` 加载进快照）；无敏感词库、无内容安全模型调用；控制面 capability 设计 8 种实际接入 4 种。

## 规则决策包（Phase A 誊写为 ADR）

### D1 两层检测架构：确定性永远跑，LLM 深检可配置

- **L1 确定性层**（词库子串 + 正则）：无模型依赖、毫秒级、**每次生成必跑**——这是不可降级的底线
- **L2 LLM 深检**（content_safety capability 经控制面路由）：上下文级违规判定（虚假宣传语境、夸大暗示、侵权风险提示）
- 控制面未配置 content_safety 模型 → 只跑 L1，findings 标 `deepCheck:false`，生成流程零影响（增强项而非闸门，见 D6）

### D2 词库 = 版本化配置文件（镜像 platform-format-rules 机制）

- 新建 `contracts/content-safety-lexicon.json`：`version`（如 `lexicon-v1`）、六类目录、词表、正则模式、例外表
- **Java 构建消费 + 版本冻结**：词库版本随创作上下文快照落档（对齐 §4.7「规则更新不能改变历史任务和历史生成记录」）
- admin 在线 CRUD 词库**后置**（登记）；文件式版本化是 v1 真相源，运营改词=提 PR 发版本

### D3 初版类目与严重度

| category | severity | 起步内容 |
|---|---|---|
| `politics` / `porn` / `illegal` | **high** | 违法类底线词（运营必须持续扩充，代码交付结构+起步集） |
| `absolute_claims`（广告法极限词） | medium | 最/第一/顶级/唯一/绝对/100%/国家级…（公开极限词表） |
| `false_promises`（违规承诺） | medium | 保本/稳赚/包治/无效退款/永久有效… |
| `diversion`（导流联系） | low | 微信/VX/加我 + 账号正则 |
| `platform_unwanted`（平台不推荐表达） | low | 少量起步，后续按平台 overlay |

- 例外表吸收误报（如「**第一**时间」「**最**新」）——**词库内容是运营资产**，代码职责是结构、加载、匹配、呈现
- 中文子串匹配 + 拉丁词边界；精确率取舍记录进 ADR，L2 补上下文判断

### D4 content_safety **只走平台模型，不开放 BYOK**

BYOK 白名单维持 `text|image|image_generation|video_generation` 不加 content_safety——用户自带「安全检查模型」可接空转假模型骗过检查，安全检查完整性优先（ADR 核心论证）。

### D5 LLM 深检经 AiExecutionService 单一执行环，0 积分

- 深检 = `AiExecutionService` 的一次 run：`capability=content_safety`、`feature=CONTENT_SAFETY`、**定价 0（平台资助，不向用户扣分）**
- 收益：不开第二条执行旁路（本库用血换来的单环纪律）、ai_run 留痕可审计、沿用退款/TaskContext 机器
- 积分侧 0 价 feature 走既有价目表版本化（新增 0 价条目），**不是硬编码跳过扣费**——扣减闭环照走、扣 0
- 深检时机：**长文本内联**（文章正文等，生成后随最终帧返回，延迟可接受——本就被生成耗时主导）；**短文本（标题等）仅 L1**

### D6 门槛姿态 = advisory，不硬阻断

- findings 是「警告 + 类别建议」，用户可编辑后复查（PRD：AI 输出是建议、允许人工编辑、不自动发布）
- 任务提交侧（履约凭证带 high 发现是否拦截）**登记为后续独立决策**，本任务不动 marketplace
- 高风险行业 overlay（依赖 #24 门店品类进上下文）登记为后续钩子

### D7 双入口：生成流内联自动检 + 独立手动复查端点

- 生成完成 → L1（必）+ L2（长文本且已配置）→ 结果帧带 `safety` 块
- `POST /api/content-safety/check`（需登录）：`{text, platform?}` → findings——用户**编辑后**的复查入口（前端「重新检查」）
- findings 形态：

```json
"safety": { "findings": [ { "category": "absolute_claims", "severity": "medium",
  "match": "最好吃", "index": 12, "advice": "广告法极限词，建议改为具体描述" } ],
  "lexiconVersion": "lexicon-v1", "deepCheck": true }
```

### D8 v1 接入五条文本流

文章标题+正文、朋友圈文案、喜剧脚本、视频脚本、图片评价文案。创作助手优化输出、视频改编、多模态图片输入登记后续。各自 result 帧统一加 `safety` 块（SSE 判别帧约定不变，新增字段不破坏既有消费器）。

### D9 词库服务端独占

不下发前端（单一真相源 + 避免整表被顺手抓走）；前端只渲染 findings。

## 现状锚点（动手前先读；行号为 2026-08-16 快照，漂移按符号搜）

- `contracts/platform-format-rules.json` + `intelligence-service/.../creationcontext/PlatformCreationRuleCatalog.java:21-33` — 版本化规则契约加载与快照冻结的**完整先例**（D2 镜像对象，含 Java 构建消费方式）
- `intelligence-service/.../ai/`：控制面（`platform_model_config`、`PlatformModelConfigSeeder.java:47`、`ByokRoutingService`）、执行环（`AiExecutionService`、`ModelBudgetService`）、`TextCompletionClient`/`AiCapabilityAdapter`
- BYOK 白名单：`ai/byok/CreateAiProviderKeyRequest.java:11`
- 积分价目：finance credits 价目版本化机制（`VideoGenerationProperties`/价目表先例，grep feature 价目定义处）
- 五条流的 SSE 出口：`articlegeneration/`、`moments/`、`comedy/`（CLAUDE.md 列有路径）、`videoproduction/VideoScript*`、`imageanalysis/`
- 前端规范检查先例：`src/views/article/composables/useArticleFormatRule.ts:35-50`（findings 面板与它并列）
- edge：RouteManifest + `EDGE_ROUTE_*` 约定（新公网前缀 `/api/content-safety/**`）

## Phase B 实施阶段

### Stage B1 — intelligence：词库 + 确定性层 + 手动端点

- `contracts/content-safety-lexicon.json`（D3 起步集）+ `contentsafety/` 新包：`ContentSafetyLexicon`（加载/版本）、`ContentSafetyChecker`（L1：子串+正则+例外，输出 findings 含 index/advice/severity）、`ContentSafetyService`（编排 + 词库版本随快照冻结）
- `POST /api/content-safety/check`（登录；请求体 ≤50KB；platform 可选做 overlay 预留）
- **测试**：各类目命中/例外不误报/拉丁边界/index 正确/空文本零 findings/大文本性能（≤10k 字符毫秒级）/端点鉴权与体积上限

### Stage B2 — capability 接线 + 0 积分深检

- 控制面 seed `content_safety` capability（模型缺省未配置即 D1 降级路径）；路由消费对齐既有 capability 分发
- 价目表加 `CONTENT_SAFETY` 0 价条目（版本化）；`AiExecutionService` run 带 feature 正常走扣减（扣 0）
- `ContentSafetyAiChecker`：rubric prompt（类目清单+语境判定+结构化 JSON 输出），结果折叠进 findings（来源标 `deep`）
- **测试**：未配置→降级且生成不受影响；配置→深检 run 落 ai_run、用户余额零变化、结果折叠；坏 JSON 输出降级为「深检不可用」不炸生成流

### Stage B3 — 五条流内联接入

- 各流生成完成处调 `ContentSafetyService.check`（长文本带深检、短文本 L1），最终 result 帧加 `safety` 块；SSE 帧约定与既有消费器兼容（新增字段）
- **测试**：五条流各自「生成→帧含 safety 块」断言（mock checker）；深检失败不影响生成主结果；既有流 IT 零回归

### Stage B4 — edge + 前端

- RouteManifest 注册 `/api/content-safety/**` → intelligence + flag（默认 true）
- 前端：共享 `SafetyFindingsPanel`（severity 排序、类别 chip、advice、词库版本标注、「重新检查」按钮调手动端点替换当前文本）；接入五条流的结果区（与 `useArticleFormatRule` 的字数提示并列展示）
- **门禁**：`npm run test && npm run typecheck && npm run build` + vitest（面板渲染/复查流/空 findings）

### Stage B5 — 收尾

1. 全量门禁：`./gradlew :services:intelligence-service:test` + 前端三件套
2. 回写进度指南：#34 标完成、#33 行更新（剩 voice/retrieval）、第四批第 13 项划掉
3. CLAUDE.md：API 表补 `/api/content-safety/*`、§4.7 检查能力描述更新

## 验收标准

1. 生成含「最好吃」「包治」「加微信 xxx」的文章 → result 帧的 findings 分别命中 absolute_claims/false_promises/diversion，带位置与建议；「第一时间」不误报
2. 控制面未配置 content_safety → `deepCheck:false`，五条流生成零影响；配置后深检运行、ai_run 留痕、**用户积分零扣减**
3. 用户编辑文本后点「重新检查」→ 手动端点返回最新 findings
4. 词库版本出现在 findings 与创作上下文快照；改词库版本不影响历史快照
5. BYOK 请求 capability 含 content_safety → 400（白名单不变）
6. edge flag 关闭 → `/api/content-safety/*` 404
7. 既有五条流与 AiExecutionService 测试零回归

## 代码库陷阱清单（必读）

- Java 构建：JDK 25（`JAVA_HOME=/opt/homebrew/opt/openjdk@25`），入口 `platform-java/` 下 `./gradlew`
- **AiExecutionService 是执行闭环唯一编排**——深检不得旁路自建 HTTP 调用；0 积分走价目表 0 价条目而非硬编码跳过
- **SSE 帧里 boolean/number 必须原生下发**（`"false"` 字符串是 truthy）；流已 200 后失败只能 `{error}` 帧——深检失败**降级标注**而非 error 帧（生成主结果已成功）
- **intelligence 没有全局 ObjectMapper bean**——持服务本地实例；LLM 结构化输出解析要容错（坏 JSON → 深检不可用）
- LLM 调用阻塞 IO → boundedElastic；词库子串扫描纯内存可在事件循环，但正则集大时谨慎（基准测试锁 ≤10k 字符毫秒级）
- 新公网前缀不进 RouteManifest 就是 404（fail-closed）
- Jackson 3：请求 record 可选数值字段必须包装类型；`is` 前缀派生态加 `@JsonIgnore`
- Reactor：`switchIfEmpty` 副作用包 `Mono.defer`
- 提交按语义拆分（建议：ADR+词库 / 确定性层+端点 / capability+0 价深检 / 五条流接入 / edge+前端 / docs 各一个 commit），中文 commit message、`feat(scope):` / `test(scope):` / `docs:` 风格
