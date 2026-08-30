# 草场任务书 #57：小红书图文·风格三选 + 创作 skill 库 + 图卡放大

> 来源：2026-08-30 用户规格四点——①生成标题前选一个标题套路；②生成正文前选体裁+文风，正文按体裁、文风生成；③每种体裁/文风/标题套路各对应一个 skill，存数据库、后台可看可改、生成前调用；④拆卡结果图加「放大」按钮。体裁 9 种 / 文风 7 种 / 标题套路 6 种，清单以本任务书附录种子契约为唯一真相源（用户 2026-08-30 原文归纳）。
> 状态：**已立项待实现**（交 Qoder）。
> 前置：无硬前置；与 #54 图卡流（已并线）正交，放大按钮落在其面板上。
> 规模定性：1 张新表 + 启动种子 + 4 处消息组装注入 + 2 条新路由 + 治理台 1 面板 + 用户端 3 组 chips + 图卡 lightbox 接线。种子内容已逐字定死（附录），无自由发挥空间。

## 决策表（2026-08-30 拍板）

| # | 决策 | 选择 |
|---|---|---|
| A | 范围与注入点 | 仅创作中心**小红书图文（非抖音模式）**：`titles` 注入标题套路、`content` 注入体裁+文风；`outline` 不注入；任务模式（taskMode=true）走同端点、同一注入逻辑。公众号/知乎/抖音图集不带新字段，行为逐字节不变 |
| B | 交互 | 三组选择器 = fieldset + radio chips（`.option-grid`/`.style-option` 既有范式，`CardSeriesPanel.vue:123-136`）；**必选、无默认值**：未选标题套路则「生成标题」禁用，未选体裁+文风则「生成正文」禁用；选项数据服务端下发（决策 E），前端不硬编码清单 |
| C | skill 存储 | 新表 `creation_style_skill`（V55，幂等 DDL）；**Java 启动种子**（表空才种，best-effort），种子来自 repo 根 `/contracts/creation-style-skills.json`（附录逐字拷贝），22 条 = 6 标题套路 + 9 体裁 + 7 文风；`UNIQUE(category, code)`。admin 改的是库行，种子不回写文件 |
| D | 注入格式 | **追加进同一条 system 消息文本，不新增 system 消息**（BYOK 任意端点对多条 system 兼容性不可假设）；注入段自带「与前文冲突以本段为准」优先级句（小红书 base prompt 有「风格多样化」「闺蜜口吻」两句，必须可覆盖）；体裁在前、文风在后 |
| E | 选项来源 | `GET /api/creation-style-skills?category=…` 下发 enabled 列表（code/name/description/sortOrder，**不含 promptContent**）；治理台改完、停用即随下次拉取生效（tier 来源服务端化教训） |
| F | 校验语义 | 生成时**直读库、不缓存**（admin 改完立即生效，省缓存失效解释成本）；code 未知或停用 → 400 明确文案「所选XX无效或已停用，请重新选择」；不带 code 的请求 = 现状，prompt 逐字节不变（回归红线） |
| G | 治理台 | AdminView **末尾**新页签「创作风格」+ `CreationSkillsAdminPanel.vue`（`src/ops/admin/components/`）；本轮仅 查看 / 编辑（描述+promptContent+启用停用）；乐观锁 version 冲突 409；**不分页**（≤22 行固定量配置，与 #53 的无界列表不同）；新增/删除/调序不做（边界） |
| H | 图卡放大 | 复用流内已有 `ArticleLightbox`（`ArticleCreationView.vue:335` 实例已在）；`CardSeriesPanel` 新增 `open-lightbox` emit，成功卡「缩略图点击 + 放大按钮」双入口；失败卡无放大 |
| I | lineage | `content` 的 `inputSummary` 增加 `styleSelection`（genre/style 的 code+name）；titles 现状不落 lineage，维持 |
| J | 状态生命周期 | 三选择挂在 `useArticleCreation` 状态上；「重新开始」**保留**三选择（与平台保留一致，连载创作少重复选）；换出小红书（含切抖音）则不展示、不携带；清空仅靠手动改选 |

## 模型与关键技术真相（动手前必读）

1. **现状 prompt 全部硬编码**：`article/ArticlePrompts.java`（TITLES `:42-88`、CONTENT `:127-165`），全库无任何 skill/template 表。本任务是第一个「后台可改 prompt 注入生成流」机制，**最贴近的完整样板是 `contentsafety/ContentSafetyLexicon.java`**（V34 建表 + classpath contracts JSON 启动种子 + admin CRUD + 生成流消费），照抄其骨架。
2. **注入点共 4 处消息组装**：titles 任务模式 `ArticleController.java:71-74` / 独立 `:79-83`；content 任务模式 `contentTaskStream :195-233` / 独立 `:132-134`。抽一个私有 helper 组装，免得四处漏。
3. **标题输出是严格 JSON 契约**：`{titles:[{title,hook}]}`（`parseTitles`）。注入段只约束标题写法，不得触碰输出格式要求；且小红书 TITLES base 有「风格多样化：疑问句、数字列表…」一句（`:78`），与「全候选遵循同一套路」天然冲突——注入模板必须带优先级句（决策 D）。
4. **小红书 CONTENT base 默认闺蜜口吻**（`:157`「像在跟闺蜜/好朋友分享」）——专业博主风/学术考据风等注入后必须能覆盖默认语气，同理靠优先级句。
5. **抖音模式的 platform 值就是 `'xiaohongshu'`**（`ArticleCreationView.vue:397-400`），`isDouyinMode` 只在视图层 ref。携带条件不能只看 platform 值——视图须把「是否携带」同步给 composable（决策 B/J 的 `styleSkillsActive`）。
6. **intelligence 没有 ObjectMapper bean**（注入即炸整个 Spring 上下文）——种子 JSON 解析在 Seeder/Service 内自持 Jackson 实例。
7. **IT 容器自跑 intelligence Flyway**（`IntelligenceItSupport.java:98`）——本表为 intelligence 自有表，V55 一处 DDL 即可，**不涉及**共享表「bootstrap V1 + IntelligenceItSupport 双处补 DDL」铁律；也不要动 database-bootstrap。
8. **AdminView 页签测试按数字下标点**（`AdminView.vue:63-64` 注释）——新页签必须加在**末尾**；edge 新 admin 路由必须**精确前缀**（`edge-bff/application.yml:143-148` 注释），且 `RouteOwnershipContractTest` 绑定真实 yml 逐路径断言，加路由必须同步加用例。
9. **contracts 打包**：repo 根 `/contracts/*.json` 经 `intelligence-service/build.gradle.kts:63-73` 的 copySpec 进 jar——新 JSON 忘记登记 = 线上种子文件不存在，启动种子空转。

## S1 · 后端（intelligence-service + edge-bff）

### S1.1 V55 迁移 `db/migration/V55__creation_style_skill.sql`

```sql
CREATE TABLE IF NOT EXISTS creation_style_skill (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    category      text NOT NULL CHECK (category IN ('TITLE_FORMULA', 'GENRE', 'STYLE')),
    code          text NOT NULL,
    name          text NOT NULL,
    description   text NOT NULL DEFAULT '',
    prompt_content text NOT NULL,
    enabled       boolean NOT NULL DEFAULT true,
    sort_order    int NOT NULL DEFAULT 0,
    version       int NOT NULL DEFAULT 0,
    updated_by    uuid,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT creation_style_skill_category_code_key UNIQUE (category, code)
);
```

### S1.2 新包 `creationstyle/`

- `CreationStyleSkillCategory`（enum：TITLE_FORMULA/GENRE/STYLE，`fromKey` 宽容解析）+ `CreationStyleSkill`（record 实体）。
- `CreationStyleSkillRepository`：`DatabaseClient` 裸 SQL + `R2dbcBindings` 惯例（照 `homepage/HomepageHotConfigRepository.java`）；`count()` / `listEnabled(category?)`（enabled=true，category 内按 sort_order）/ `listAll()` / `findById(id)` / `insertSeed(…)`（ON CONFLICT DO NOTHING）/ `updateRow(id, name, description, promptContent, enabled, expectedVersion, updatedBy)`——UPDATE 带 `AND version = :expected`，命中行数 0 由上层区分「不存在/版本冲突」→ 409。
- `CreationStyleSkillSeeder`：`@EventListener(ApplicationReadyEvent)`，表空才按附录 JSON 全量插入；解析用**自持** Jackson 实例（真相 6）；失败 best-effort 打 WARN 不阻断启动（照 `PlatformModelConfigSeeder` 姿态）。
- `CreationStyleSkillService`：`requireEnabled(category, code)` → Mono；未知/停用 → `IntelligenceException`（400，文案「所选{标题套路|体裁|文风}无效或已停用，请重新选择」）。**无缓存直读**（决策 F）。
- `CreationStyleSkillController`：
  - `GET /api/creation-style-skills?category=`（category 可省略=三组合并）：`callers.resolve` 宽放行（纯目录无敏感内容），响应 `{success:true,data:{skills:[{category,code,name,description,sortOrder}]}}`，**绝不含 promptContent**；
  - `GET /api/admin/creation-style-skills`：`callers.requireAdmin`，全量含停用含 promptContent；
  - `PUT /api/admin/creation-style-skills/{id}`：body record `UpdateRequest(String name, String description, String promptContent, Boolean enabled, Integer expectedVersion)`（**全必填整行提交，包装类型**——Jackson record 惯例）；校验 name 非空≤30、promptContent 非空≤2000、expectedVersion 非空；成功 `{success:true,data:{skill}}`；版本不符/无此行 → 409；`updated_by`=admin accountId。错误经既有 `@ExceptionHandler(IntelligenceException)` 信封。

### S1.3 生成流注入（`article/` 改造）

- `ArticlePrompts` 增加重载（保留原单参方法，字节不变）：

```
titlesSystem(Platform, SkillPrompt formula)
contentSystem(Platform, SkillPrompt genre, SkillPrompt style)
// SkillPrompt = record(String name, String promptContent)，由实体映射
```

注入模板（逐字）：

```
titles 追加段：
\n\n【标题套路：{name}】\n在满足上述输出格式要求的前提下，全部 5 个候选标题都必须遵循以下套路（与前文「风格多样化」的要求冲突时，以本段为准）：\n{promptContent}

content 追加段（体裁在前、文风在后，两段）：
\n\n【内容体裁：{name}】\n正文必须遵循以下体裁结构要求：\n{promptContent}
\n\n【文风口吻：{name}】\n全文语言风格必须遵循以下口吻要求（与前文默认语气冲突时，以本段为准）：\n{promptContent}
```

- `TitlesRequest` 加 `String titleFormula`（可空）；`ContentRequest` 加 `String genre, String style`（可空）。可空=不注入=现状。
- `ArticleController` 四处组装点（真相 2）：先 `requireEnabled` 解析（titleFormula→TITLE_FORMULA；genre→GENRE；style→STYLE），空值跳过；任一无效即 400、**不发起上游调用、不扣积分**（校验在 `frozenText.executeIndependent` 之前）。任务模式同样注入。
- lineage：`contentInput(body)`（`ArticleController.java:149` 调用处）的 inputSummary 增加 `styleSelection: {genre:{code,name}, style:{code,name}}`（未选时无此键）。

### S1.4 edge-bff 路由 + 契约测试

- `application.yml`：`/api/creation-style-skills` → intelligence（用户侧路由块，参照 `:244-271`）；`/api/admin/creation-style-skills` → intelligence（admin 块，精确前缀，参照 `:189-193`）。均带 `enabled` 开关变量，默认 true。
- `RouteOwnershipContractTest` 补两条断言。

### S1.5 后端测试（IT）

1. 启动种子：空表 → 22 条（6/9/7 分布、UNIQUE 不炸）；已有行 → 不重复种。
2. `GET /api/creation-style-skills`：仅 enabled、无 promptContent 键、category 过滤正确。
3. 注入断言（WireMock）：带 `titleFormula:"number"` 的 titles 请求，上游 system 文本含「【标题套路：数字型】」与种子 prompt 片段；带 genre+style 的 content 请求含两段且体裁段在前。**stub/校验 JSON 一律 python json.dumps 生成**。
4. 无效/停用 code → 400 且 error 文案明确、WireMock 零上游请求、积分零变动；不带新字段的请求 → 上游 system 文本与现状逐字节一致（回归）。
5. admin：非 admin 403；PUT 改 promptContent 后下一次生成即注入新文本（直读无缓存实证）；expectedVersion 不符 409。
6. `RouteOwnershipContractTest` 两条新路由绿。

## S2 · 用户端前端（`/article` 流）

### S2.1 `useArticleCreation.ts`

- 新增状态：`titleFormula` / `genre` / `style`（ref，string，''=未选）、`styleSkillOptions`（三组目录）、`styleSkillsLoading/Error`、`styleSkillsActive`（boolean，由视图同步）。
- `fetchStyleSkills()`：`GET /api/creation-style-skills`（一次全量，视图在小红书模式下调用；失败置 error 态供重试）。
- `fetchTitles` 请求体：`styleSkillsActive && titleFormula` 时加 `titleFormula`；`streamContent` 同理加 `genre, style`。**抖音模式 active=false 不携带**（真相 5）。
- `reset()` 保留三选择（决策 J）。

### S2.2 `ArticleCreationView.vue`

- topic 段（「生成标题」按钮 `:112-120` 上方）：`platform==='xiaohongshu' && !isDouyinMode` 时渲染「标题套路 *」chips 组；`disabled` 追加 `|| !titleFormula`。
- outline 段（「生成正文」按钮 `:215-230` 上方，注意该按钮在 outline 段内）：同条件渲染「内容体裁 *」「文风口吻 *」两组 chips；`disabled` 追加 `|| !genre || !style`。
- chips 写法照 `CardSeriesPanel.vue:123-136`（fieldset.form-field > legend + .option-grid > label.style-option 包 radio）；选中项组下方一行 `.field-note` 显示该 skill 的 description。
- 目录加载失败：chips 区内联错误 + 「重试」按钮（不阻塞其它步骤）。
- `watch([platform, isDouyinMode])` 同步 `styleSkillsActive` 并在小红书非抖音时懒拉目录（一次）。
- 样式只用既有 token 与 `.gl-field`/`.gl-zone` 全局层；明暗双主题成对自查（AGENTS.md 硬性规则）。

### S2.3 图卡放大（`CardSeriesPanel.vue`）

- 新增 `defineEmits<{ (e: 'open-lightbox', url: string): void }>()`。
- 成功卡：`.result-actions`（`:254-269`）在「下载」前加「放大」按钮（复用 `ArticleImageSlots.vue:87-92` 的放大 SVG，`data-test="card-series-zoom"`）；`<img>` 本身点击同触发。
- `ArticleCreationView.vue:324-328` 挂载处加 `@open-lightbox="openLightbox"`——复用 `:335` 已有 `ArticleLightbox`（Esc/遮罩关闭已内建），**不新建组件**。

### S2.4 前端测试（vitest）

1. 小红书模式：三组 chips 渲染、按钮门控（未选禁用→选后可用）；wechat/zhihu/抖音模式无 chips、请求体无新字段。
2. 请求体断言（mock fetch）：titles 带 titleFormula、content 带 genre+style。
3. 目录加载失败 → 错误提示 + 重试按钮存在。
4. CardSeriesPanel：成功卡有放大按钮且点击 emit `open-lightbox` 带 url；点缩略图同效；失败卡无放大。
5. 弹窗类用例 mount 带 `stubs: { teleport: true }`（happy-dom Teleport 坑）。

## S3 · 治理台

- `AdminView.vue` 三处挂载（页签按钮**末尾** / `activeSection` 联合类型 `:469-471` / `v-else-if` 面板块参照 `:320-323`），页签名「创作风格」。
- 新组件 `src/ops/admin/components/CreationSkillsAdminPanel.vue` + 同名 `.test.ts`：
  - 分类过滤 chips（全部/标题套路/内容体裁/文风口吻）+ 列表表格（列：分类、名称、code、描述、启用开关、更新时间、操作）；
  - 启用开关照 `AiProviderKeysPanel.vue:31-42` 的 switch 写法（change 即 PUT）；
  - 「编辑」弹窗用全局 modal 骨架（`src/style.css:380-527` 的 `.modal-overlay/.modal-card` + `.field-textarea`），编辑 description + promptContent（textarea rows≈10）+ enabled；保存整行 PUT；409 → 「已被他人修改，请刷新后重试」；
  - 不分页、不新增、不删除、不调序（决策 G）。
- 遵守 `src/ops/DESIGN.md`（Cal 系），只用 token；组件放 `src/ops/admin/components/`（治理台私有，不进 `src/components/`）。

## S4 · 门禁与实测

- 前端 vitest + typecheck + build；intelligence 全量 IT（与本地部署栈并存会挂死——先停栈或定向跑）。
- 本地栈浏览器实测（e2e-seed 账号，双主题截图留档）：
  1. 创作中心 → 小红书图文：不选套路「生成标题」禁用 → 选「数字型」→ 候选标题全部带数字；
  2. 选体裁「干货攻略型」+文风「专业博主风」→ 正文分步编号且语气克制（覆盖默认闺蜜口吻）；换文风「沙雕搞笑风」重生，口吻切换明显；
  3. 拆卡 → 成功卡点「放大」与点缩略图均开 lightbox，Esc 关闭；
  4. 治理台「创作风格」：改「数字型」prompt（加一句特征词）→ 回用户端重生标题立即带新特征；停用某体裁 → 用户端目录消失，已选该值生成报错提示明确；
  5. 公众号/知乎/抖音路径回归：无新选择器，全流程可用；
  6. 明暗双主题截图（用户端两步选择器 + 治理台面板）。

## 验收清单

1. 生成标题前可选标题套路（6 种，附录为准），未选不能生成；候选标题全部符合所选套路。
2. 生成正文前必选体裁（9）+ 文风（7）；正文结构随体裁、口吻随文风，能覆盖平台默认语气。
3. 22 条 skill 落库（V55+启动种子）；治理台「创作风格」可看可改可停用，改动即时生效（无缓存）；用户端目录由服务端下发且不含 prompt 文本。
4. 图卡成功卡可放大（按钮+点图双入口），Esc/遮罩关闭。
5. 旧客户端/其它平台不带新字段 → 全链行为与现状一致（注入零发生）；无效/停用 code → 400 明确文案、零上游调用、零扣费。
6. 门禁全绿；`RouteOwnershipContractTest` 含两条新路由；实测截图留档。

## 已知边界（本轮不做）

- `outline` 不注入（体裁是否应上移影响大纲结构，出效果后再议）。
- 公众号/知乎、抖音图集、朋友圈/短视频等其他流不接选择器（后端注入能力平台无关，后续按样推广即可）。
- skill 的新增/删除/拖拽调序（sort_order 落库但治理台不提供调序 UI）；skill 版本历史/变更审计（仅 updated_by/updated_at 记当下）。
- 任务模式不把 skill 冻结进 creation_context_snapshot（选择发生在生成时，与 topic 同级是生成时输入；lineage 已记 code+name 可追溯）。
- 图卡 lightbox 不做 series 内上一张/下一张（单卡预览够用；`ImageLightbox` 多图版是后续可选升级）。
- 治理台列表不分页（≤22 行固定量）。

## 实现红线（历史教训，逐条自查）

- DDL 一律幂等（CREATE TABLE IF NOT EXISTS / ADD COLUMN IF NOT EXISTS）；迁移号冲突顺延取空号；**不动 database-bootstrap**（本表非共享表）。
- `/contracts/creation-style-skills.json` 必须登记进 `build.gradle.kts` copySpec，否则 jar 内无种子文件。
- intelligence **不注入 ObjectMapper bean**——种子解析自持实例。
- 请求 record 可选字段一律可空 String/包装类型；派生态 is 前缀无参方法 `@JsonIgnore`。
- 校验失败（无效/停用 code）必须发生在任何上游调用与扣费之前。
- Reactor 副作用包 `Mono.defer`；`switchIfEmpty` 参数不急切求值。
- WireMock stub/断言 JSON 用 python json.dumps 生成，不手写 text block。
- 自建 WebTestClient 必带 30s responseTimeout。
- 新 admin 页签加 AdminView **末尾**（测试按下标点页签）；edge admin 路由精确前缀。
- 前端无 toast 系统：错误用内联 `.error`（role=alert）或按钮禁用表达，不引入新交互件。
- 改 UI 前读对应 DESIGN.md（用户端根 DESIGN.md / 治理台 src/ops/DESIGN.md）；颜色字号圆角只用 token；明暗双值成对。
- vitest 弹窗组件 mount 带 `stubs: { teleport: true }`。
- 全量 IT 与本地部署栈并存会挂死——定向跑或先停栈。

## 附录 · 种子契约全文（`/contracts/creation-style-skills.json`，逐字拷贝）

```json
{
  "version": 1,
  "skills": [
    {"category": "TITLE_FORMULA", "code": "number", "name": "数字型", "description": "数字量化收获，阅读门槛低", "sortOrder": 1,
     "promptContent": "全部候选标题都必须包含具体数字（如「3个」「7款」「100天」），数字尽量放在标题前半段；用数字量化收获或数量，降低阅读门槛；可用双数字制造对比（如「实测7款，只有2款值得买」）；各候选标题的数字取值要有区分度，不要重复同一个数字。"},
    {"category": "TITLE_FORMULA", "code": "suspense", "name": "悬念型", "description": "钩子留到正文揭晓", "sortOrder": 2,
     "promptContent": "全部候选标题都必须留悬念：抛出未说完的结果或反常事实，把关键结论留到正文揭晓；可用「没想到」「结果…」「最后一个绝了」等钩子表达；不要在标题里剧透答案；悬念必须能在正文真实兑现，禁止空洞标题党。"},
    {"category": "TITLE_FORMULA", "code": "contrast", "name": "反差型", "description": "对比冲突，差距越明显越好", "sortOrder": 3,
     "promptContent": "全部候选标题都必须内置对比冲突：两个参照物差距越明显越好（如「月薪3千 vs 3万的护肤区别」「以为…结果…」「别人…我…」）；反差必须真实存在于内容中，不得为对比捏造事实；各候选可采用不同的对比维度。"},
    {"category": "TITLE_FORMULA", "code": "identity", "name": "身份代入型", "description": "点名人群，读者对号入座", "sortOrder": 4,
     "promptContent": "全部候选标题都必须点名具体人群，让目标读者第一眼认出「这说的是我」；人群标签越具体越好（打工人、i人、租房党、新手妈妈、大学生等），可叠加场景限定（独居、通勤、开学季）；各候选可指向不同的人群切面。"},
    {"category": "TITLE_FORMULA", "code": "timeliness", "name": "时效型", "description": "时间窗口，适度紧迫感", "sortOrder": 5,
     "promptContent": "全部候选标题都必须强调时间窗口或新鲜度（如「2026最新」「现在知道还不晚」「再不看就没了」）；制造适度紧迫感，但不得虚构截止日期、政策或节点；各候选的时效切入角度要有变化。"},
    {"category": "TITLE_FORMULA", "code": "advice", "name": "听劝型", "description": "听劝口吻，激发评论参与", "sortOrder": 6,
     "promptContent": "全部候选标题都必须用听劝/求助口吻：以「听劝！」起头，或包含明确的求助点（如「听劝！第一次去成都怎么玩」「大家帮我看看这个offer」）；让读者产生「我要给建议」的参与冲动；各候选的求助点侧重不同。"},

    {"category": "GENRE", "code": "practical_guide", "name": "干货攻略型", "description": "分步保姆级教程，收藏率高", "sortOrder": 1,
     "promptContent": "正文按保姆级教程组织：开头一句话说清读者照做能获得什么；正文分点或分步编号推进，每一步写明具体做法（做什么、怎么做、注意什么）；多用可执行的动词，少用空泛形容词；结尾给一段可收藏的要点总结；整体语气笃定，像手把手带练。"},
    {"category": "GENRE", "code": "review", "name": "种草测评型", "description": "实测分维度，结论明确不骑墙", "sortOrder": 2,
     "promptContent": "正文按实测测评组织：先交代背景（为什么测、怎么测的）；分维度评价（如效果、价格、使用感，维度按主题选取）；结论明确、指出适用人群，优点缺点都要说，不骑墙；单品深评或多品横评均可，横评时逐品给一句最终判断。"},
    {"category": "GENRE", "code": "experience", "name": "经验分享型", "description": "第一人称复盘，真实踩坑", "sortOrder": 3,
     "promptContent": "正文按第一人称复盘组织：交代起点与背景；按时间线或事件推进，写清关键转折点和当时的选择；坦诚踩过的坑与付出的代价，细节要具体（时间、金额、场景）；结尾提炼2-3条可迁移的经验教训；真实细节优先于漂亮话。"},
    {"category": "GENRE", "code": "resonance", "name": "情绪共鸣型", "description": "说中读者心里有没说出口的话", "sortOrder": 4,
     "promptContent": "正文按情绪共鸣组织：从一个具体的生活小场景切入，细节越小越真越好；层层递进，说出读者心里有但没说出口的感受；可用一两句金句点题收尾；不解决问题、不说教，只表达「我懂你」。"},
    {"category": "GENRE", "code": "pitfall", "name": "避坑红黑榜", "description": "排雷警示，红黑对比给替代", "sortOrder": 5,
     "promptContent": "正文按警示导向组织：开门见山说清「坑在哪、代价是什么」；逐条列出坑点，每条给识别方法和规避做法；可做红黑对比（值得买 vs 别碰）或纯排雷，并给出替代方案；语气笃定，负面论断必须有真实依据，不得捏造。"},
    {"category": "GENRE", "code": "collection", "name": "合集清单型", "description": "清单化整理，标注获取方式", "sortOrder": 6,
     "promptContent": "正文按资源清单组织：开头说明收录标准和适合谁用；逐项列出，每项一句话说明+适用场景+获取方式；按用途或类型分组，标注免费或付费；结尾给一段「怎么选」的速查建议。"},
    {"category": "GENRE", "code": "journal", "name": "生活记录型", "description": "plog 碎片记录，氛围仪式感", "sortOrder": 7,
     "promptContent": "正文按 plog 式碎片记录组织：按时间或场景串联一段日常；短句为主、多留白；重氛围和仪式感，允许只写感受不堆信息量；配图叙事优先，文字点睛即可。"},
    {"category": "GENRE", "code": "help_seek", "name": "听劝求助型", "description": "现状+求助点，引导给建议", "sortOrder": 8,
     "promptContent": "正文按求助帖组织：先交代现状与背景，信息给足让评论者能判断；把困惑拆成具体的求助点列出来，方便大家逐条回复；姿态真诚谦逊、不装懂；结尾表示会听劝并后续更新，引导评论区互动。"},
    {"category": "GENRE", "code": "story", "name": "热点故事型", "description": "故事化叙事，反转升华", "sortOrder": 9,
     "promptContent": "正文按故事或热点组织：开头三行内抛出钩子；中段按叙事推进，写出具体的人物、地点、时间细节；结尾做反转或升华；若借热点或节日节点，自然点明时间背景，不生硬贴标签。"},

    {"category": "STYLE", "code": "bestie", "name": "闺蜜种草风", "description": "闺蜜聊天感，热情种草", "sortOrder": 1,
     "promptContent": "全文用跟闺蜜面对面聊天的口吻：口语化、可多用感叹号；可用「姐妹们」「真的会谢」「冲」这类称呼词和语气词；热情但有真实使用感，不像官方文案；句子短，像说话不像写作。"},
    {"category": "STYLE", "code": "professional", "name": "专业博主风", "description": "数据依据，克制冷静", "sortOrder": 2,
     "promptContent": "全文克制冷静、结论先行再给依据：引用具体数据、参数或亲身记录支撑判断；少用感叹号和语气词；专业术语第一次出现时给一句话解释；可信度优先于感染力。"},
    {"category": "STYLE", "code": "sharp", "name": "毒舌犀利风", "description": "敢下判断，对事不对人", "sortOrder": 3,
     "promptContent": "全文观点鲜明、敢下判断：吐槽与反讽并用，对事不对人；不骑墙、不和稀泥，好坏直接说；可用夸张对比制造记忆点；但有底线——不人身攻击、不歧视、不刻意引战。"},
    {"category": "STYLE", "code": "healing", "name": "治愈文艺风", "description": "短句留白，慢生活质感", "sortOrder": 4,
     "promptContent": "全文短句、留白、慢节奏：多用意象和感官描写（光、风、气味、声音）；克制抒情，不煽情不说教；像深夜随笔，允许一句成段；避免网络热梗和惊叹号。"},
    {"category": "STYLE", "code": "funny", "name": "沙雕搞笑风", "description": "自嘲玩梗，包袱密集", "sortOrder": 5,
     "promptContent": "全文自嘲、玩梗、节奏快：包袱密集，夸张比喻和反转都可以上；梗要大众化，避免冷门或有歧义的梗；搞笑不低俗，不拿具体他人开涮；可用括号补内心os。"},
    {"category": "STYLE", "code": "academic", "name": "学术考据风", "description": "术语澄清+来源标注", "sortOrder": 6,
     "promptContent": "全文严谨考据：术语先澄清再使用；关键论断标注来源或依据（如「据…」「数据显示…」）；知识分点结构化呈现；克制表情符号和语气词；承认不确定性，不把推测说成定论。"},
    {"category": "STYLE", "code": "raw", "name": "真实糙感风", "description": "大白话，像随手写的", "sortOrder": 7,
     "promptContent": "全文大白话、不修饰：允许口语小瑕疵（如「就是说」「反正」「讲真」）；像随手打字发给朋友，不像精修文案；段落可以不那么工整；真诚高于精致，宁可糙一点也不装。"}
  ]
}
```

> 种子文案红线：毒舌犀利风/避坑红黑榜等自带「不人身攻击/不捏造」底线条款，任何后续修订不得删掉底线句；skill 注入与内容安全检查（#45）正交，不得借 skill 指令绕过安全链。
