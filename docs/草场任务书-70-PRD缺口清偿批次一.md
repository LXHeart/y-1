# 开发规划:PRD 缺口清偿批次一(草场任务书 #70)

<!-- 弱模型执行时只需要读:第 3 节结构事实 + 第 4 节全局约束 + 当前卡。
     其余章节是给强模型自己和总控看的,不要求执行者通读。 -->

## 元信息
- 规划模型:GLM-5.3(ZCode),2026-09-04(基于 2026-09-04 PRD 12 条缺口盘点核实结论,锚点均为当日实测)
- 执行模型:能力较弱的编码模型(Qoder / 同级)
- 任务卡总数:3(卡 A–卡 C)
- 执行顺序:A → B → C(**严格串行**,任何一卡验收不绿不得进入下一卡)。三卡无文件交集,串行纯属纪律。
- 前置依赖:任务书 #69 五卡已全部落地(intelligence 迁移至 V66、douyin 已是一等 platform 值)——本批锚点基于该状态。
  **冲突警示**:若任务书 #68(大文件拆分)仍有未落地卡且涉及 `GrasslandWorkbench.vue`,卡 B 的行号锚点会漂移,一律按符号/文案定位。
- 性质:本批是 2026-09-04 PRD 缺口盘点的前三优先项——分镜增删镜头收口(用户可感知的静默丢失)、争议异议 48h 时限(资金语义规则)、平台契约补图片尺寸/视频比例两维(成本最低收益直接)。

## 1. 目标与背景

**一句话目标**:把盘点确认的三处「PRD 已定稿但代码缺失」补齐——分镜编辑的增删镜头真正落库、争议异议受 48 小时时限约束、平台规则契约携带媒体规格维度并接进规范检查。

**盘点处置表(2026-09-04,执行期不得更改)**:

| 盘点项(PRD 条款) | 处置 |
|---|---|
| §4.4 分镜编辑「增删镜头」:前端按钮只改本地数组,建任务即丢,后端无端点(半成品) | 卡 A |
| §7.1「异议需在结果公布后 48 小时内提出」:开争议无时限,代码里的 48h 是另两个语义(开庭等待窗/上诉窗) | 卡 B |
| §4.7 规范检查「图片尺寸和视频比例」「必须关键词覆盖」:契约无媒体规格维度,关键词覆盖无检查 | 卡 C |

**已定案的关键决策(执行期不得更改)**:

| # | 决策 | 理由 |
|---|---|---|
| D1 | 卡 A 新增镜头**只做末尾追加**(seq=count+1),不做中间插入;删除后顺位重排;不做独立的「重排序」端点 | 前端现状 addShot 就是末尾 push(PRD 只要求增删);中间插入/显式重排是未出现的需求,YAGNI |
| D2 | 卡 A 服务端闸三条,全部沿用既有语义:仅 draft 分镜可增删(committed 409,文案与 PUT content 同款);镜头数上限 30(=StoryboardParser.MAX_SHOTS);删除后剩余 <3 → 409(PRD §4.4 一期「镜头数 3-10」下界,V60 注释「下界由 API 保证」) | 建任务后分镜 committed,行已是任务真相源,增删必须拦在编辑期;上下界与生成路径同值 |
| D3 | 卡 A 新建镜头字段缺省:visual=''/narration=''(允许空,用户随后编辑)/plannedSeconds=5(钳 4-6)/cameraMove='固定机位'/anchorImageIndex=0/**prompt=visual**(创建时兜底,此后 sticky 与 PUT content「prompt 不动」语义一致);并在**建任务入口加防呆**:分镜存在 visual 空白镜头 → 400 | TakeGenerationWorker 直接把 shot.prompt() 喂给生成(:109),空 prompt 会产废候选;防呆把失败提前到付费之前 |
| D4 | 卡 A 前端增/删改为「**等服务端确认再改本地数组**」(与 updateShot 的「本地先行」不同) | 结构性变更静默失败=本卡要修的病;内容编辑保持既有姿态不动 |
| D5 | 卡 B 起算点 = **履约最近一次结果性事件时刻** resultAnchorAt = max(非空集合 {最新 submission.reviewedAt, 最新 engagement_verification.lastCheckedAt, task_application.confirmedAt, task_application.autoConfirmedAt});全空 → null → 不设限(fail-open) | PRD「核实结果公布」在系统里没有单一事件;最近结果性事件是最保守可解释的实现,重交/重核自然刷新窗口;全空覆盖存量与未提交未确认边缘,fail-open 避免打死可用入口 |
| D6 | 卡 B 窗口配置 `trust.adjudication.dispute-open-window-hours` 默认 48,`0=禁用`(测试哨兵,照 disputeCooldownHours=0 惯例),另有 `dispute-open-window-seconds` 秒级覆盖(dev/e2e,照 voteWindowSecondsEffective 惯例);仅拦「创建新争议」,既有活跃争议幂等返回/deferred 路径/**merchant_rejection 服务断言路径一律不受影响** | merchant_rejection 是 D-03 确认窗口机制(自带 3 天窗口+客服 SLA),动它要连坐 SettlementExecution,超出本卡;超窗 → 409 |
| D7 | 卡 C 语义澄清:**关键词维度不进平台契约**(关键词主权在任务要求层 mustInclude,平台级「必须关键词」产品上不成立);落地为 ①契约新增结构化 `imageSpec`/`videoSpec`(图片尺寸/视频比例),②前端规范检查新增 mustInclude 覆盖检查 | PRD §4.7 规范检查七项中,「图片尺寸和视频比例」「必须关键词覆盖」两项是检查能力缺失,不是平台清单缺失 |
| D8 | 卡 C 后端**零 Java 改动**(PlatformCreationRuleCatalog.snapshot 是全量 convertValue,契约加字段自动随 creation_context_snapshot 版本化冻结);新增的只有一个对账单测(契约 videoSpec 与 VideoResolution.defaultFor 值集一致) | 契约三端管线已通(前端 import/gradle 拷贝 classpath/快照冻结),加字段即版本化下发,天然满足「规则更新不改变历史记录」 |

## 2. 范围

**范围内(明确交付)**:
- 卡 A:`POST /api/video-production/storyboards/{id}/shots` + `DELETE /api/video-production/shots/{shotId}` 两端点(含属主闸/draft 闸/数量界/grouping 剔除/seq 重排)、edge 路由两条、建任务 visual 防呆、前端 addShot/removeShot 写通、前后端测试
- 卡 B:marketplace 授权响应加 resultAnchorAt;trust 窗口校验(409);AdjudicationProperties 新配置;TrustItSupport 默认桩连带更新;DisputeControllerIT/EngagementDisputeAuthorizationControllerIT 新用例;前端提示文案一句
- 卡 C:契约加 imageSpec/videoSpec + version bump;TS 类型;useArticleFormatRule 规格句+mustInclude 覆盖检查;ArticleCreationView 接线;defaultResolutionFor 改读契约;CardSeries 平台默认尺寸联动;对账单测;前端测试

**范围外(明确不做,遇到也不处理)**:
- 不做镜头中间插入与显式重排序端点(D1);不动画布 grouping 的分组/分支语义(只在删镜头时剔除悬空 id)
- 不做 merchant_rejection 路径的时限(D6);不动结算/冷却期/开庭等待/上诉窗任何既有 48h 语义;不做争议截止时间的前端倒计时(后端 409 文案自解释)
- 不做平台级关键词清单(D7);不扩图卡生成尺寸白名单(仍 3 档);规范检查仍是前端提示层,不新增服务端强校验
- 本批**零数据库迁移**;不引入新依赖;不动计费/账本

## 3. 结构事实(执行者必读,全部已核实)

### 3.1 分镜与镜头(卡 A)
| 事实 | 锚点 |
|---|---|
| 前端 addShot/removeShot 纯本地数组、无网络请求;updateShot 已写通 PUT /shots/{id}/content 且注释自认「无创建端点,遗留缺口」 | `src/composables/useVideoProduction.ts:431-448`(add/remove)、`:377-395`(updateShot+注释)、`:437-448`(addShot push 空镜头) |
| 建任务 payload 只带 `{storyboardId, operationId}`——本地增删在建任务时丢弃,服务端行才是真相源 | `useVideoProduction.ts:472-477`;后端 `VideoProductionTaskController.java:76`(CreateTaskRequest(UUID storyboardId, String operationId)) |
| `canAddShot` 上限 SHOT_COUNT_MAX=30;单镜时长钳 4-6(SHOT_SECONDS_MIN/MAX);镜头按钮 `data-test="add-shot"`(add)/remove 按钮在镜头卡 | `src/types/video-production.ts:16-19`、`useVideoProduction.ts:213`;`src/views/video-production/VideoProductionView.vue:257`(remove)、`:263`(add) |
| video_shot 表:seq CHECK 1-30 + **UNIQUE(storyboard_id, seq)**(即时约束,非 DEFERRED——重排陷阱见卡 A 做法 2);video_storyboard.status ∈ draft/committed | `V60__video_production_pipeline.sql:41-56`、`V63__video_phase2_duration_resolution.sql:35-36` |
| PUT /shots/{shotId}/content 先例:属主闸 findByIdForAccount→404、committed→409「分镜已提交成片,不能再编辑镜头」、plannedSeconds 钳 4-6、prompt 不动、ShotContentRequest 全可空字段 | `VideoProductionController.java:139-170`、请求 record `:178-179` |
| 仓储既有方法:upsert(ON CONFLICT(storyboard_id,seq) 覆盖)、findByStoryboard(ORDER BY seq)、findByIdForAccount(JOIN 属主闸)、updateContent | `VideoShotRepository.java:39-99` |
| 分镜原请求(含 base64 图)存 video_storyboard.request_payload;grouping 是独立 jsonb(PATCH /storyboards/{id}/grouping 校验 shotIds ⊆ 当前镜头——**悬空 id 在下次 PATCH 会 400**) | `VideoStoryboard.java:14-21`;`VideoProductionController.normalizeGrouping:252-`、`VideoStoryboardRepository.updateGrouping` |
| take 生成直接消费 shot.prompt() | `TakeGenerationWorker.java:109` |
| 事务先例:`transactions.transactional(...)` 包多步写 | `VideoProductionTaskService.spawnRows`(:173 markCommitted 附近) |
| edge 路由前缀语义:`path.equals(routePath) || path.startsWith(routePath + "/")`,exact=true 才全等;GET/PATCH storyboards、PUT shots 在 SCRIPT 组(开关 EDGE_ROUTE_VIDEO_SCRIPT_INTELLIGENCE);**POST /storyboards 与 DELETE /shots 目前均无路由** | `edge-bff/.../proxy/UpstreamResolver.java:40-56`;`edge-bff/src/main/resources/application.yml:437-450` |
| 既有测试:StoryboardIT(SSE 落库断言用 SQL 直查)、StoryboardGroupingIT(分镜造数先例)、VideoProductionView.test.ts;**无 useVideoProduction.test.ts** | `intelligence-service/src/test/.../videoproduction/`、`src/views/video-production/` |

### 3.2 争议链路(卡 B)
| 事实 | 锚点 |
|---|---|
| 开争议链路:open → authorizer.authorize(当事方校验+取 canonical org)→ openOrDefer(活跃争议幂等 → switchIfEmpty 冷却期检查 → createNewDispute) | `trust-service/.../dispute/DisputeController.java:75-100`、`:139-168`、`:309-332` |
| 冷却期先例(本卡照抄结构):disputeCooldownSecondsEffective()==0 跳过;终局争议 decidedAt+冷却 vs now;409 带人话文案 | `DisputeController.checkDisputeCooldown:292-306` |
| trust→marketplace 授权客户端:Authorization record(engagementRef/organizationId/recommenderAccountId/premiumSupportAtAccept);AuthorizationResponse/AuthorizationData record 解码;null 放行、畸形 AuthorizationException fail-closed | `dispute/MarketplaceEngagementAuthorizationClient.java:42-47`、`:77-107` |
| marketplace 授权端点:requireServicePrincipal(trust)→ applications.findById(非 accepted 409)→ tasks.findById(isParty 403)→ data map{engagementRef/organizationId/recommenderAccountId/premiumSupportAtAccept} | `marketplace-service/.../taskcatalog/DisputeAuthorizationController.java:45-74` |
| 时间戳来源三处:SubmissionRepository.findByApplication(applicationId)(EngagementSubmission.reviewedAt);EngagementVerificationRepository.findBySubmissions(ids)(lastCheckedAt);TaskApplication.confirmedAt/autoConfirmedAt | `taskcatalog/SubmissionRepository.java:108`、`EngagementVerificationRepository.java:132`、`TaskApplication.java:43-46` |
| 配置惯例:AdjudicationProperties record 组件 + compact 构造守卫(<0 归默认/0 哨兵)+ `*SecondsEffective()`;yml 在 trust.adjudication 块 | `adjudication/AdjudicationProperties.java:22-143`;`trust-service/src/main/resources/application.yml` adjudication 块(:104-109 附近) |
| IT 基建:TrustItSupport 以 **@MockitoBean 直接 mock MarketplaceEngagementAuthorizationClient**,默认桩 `new Authorization(...)` 两参构造(:80)——**record 加字段后此桩必须连带更新**(本批声明的唯一测试基建改动) | `trust-service/src/test/.../TrustItSupport.java:65-88` |
| marketplace 侧测试先例 | `marketplace-service/src/test/.../taskcatalog/EngagementDisputeAuthorizationControllerIT.java` |
| 前端开争议入口提示文案 | `src/views/grassland/GrasslandWorkbench.vue:1162`(gl-hint) |

### 3.3 平台规则契约(卡 C)
| 事实 | 锚点 |
|---|---|
| 契约 9 平台、每条 7 字段(platformId/platformLabel/minChars/maxChars/maxTitleChars/tagHint/emojiHint/structureHints),version=2026-08-06;**无任何媒体规格维度** | `contracts/platform-format-rules.json` |
| 契约单一来源:intelligence `build.gradle.kts:69-71` processResources 从根拷贝(src/main/resources 下无此文件);前端 `src/config/platform-format-rules.ts:8` 直接 import | 两处锚点 |
| 后端加载与快照:`PlatformCreationRuleCatalog.snapshot()` 把**整条规则 JsonNode convertValue 成 Map** 再叠 version/platform/contentForm/requiresCreatorConfirmation——契约加字段零 Java 改动即随快照冻结;CreationContextService 冻结进 creation_context_snapshot | `creationcontext/PlatformCreationRuleCatalog.java:36-44`、`CreationContextService.java:139-141` |
| 前端规则消费:`PlatformFormatRule` interface + `...rule` spread(新字段自动透传,只需补 TS 类型);useArticleFormatRule 提供 formatRuleSummary/formatIssues(字数/标题检查);MomentsCreationView 也消费 formatRuleSummary | `src/config/platform-format-rules.ts:12-40`、`src/views/article/composables/useArticleFormatRule.ts:15-47`、`src/views/moments/MomentsCreationView.vue:25` |
| mustInclude 可达性:ArticleCreationView 收 props.creationHandoff(CreationHandoff.taskContext?: TaskContextSnapshot),requirements 内含 mustInclude(string[]);useArticleFormatRule 目前无 mustInclude 入参 | `src/views/article/ArticleCreationView.vue:572-574`、`:730-735`;`src/types/ai-creation.ts:48-55`;`src/types/grassland/engagement.ts:102` |
| 视频缺省分辨率两端同值集:前端 defaultResolutionFor(bilibili→横版,其余竖版);后端 VideoResolution.defaultFor 同款。**卡 C 后端不动,只加对账测试** | `src/types/video-production.ts:27-30`;`videoproduction/VideoResolution.java:24-29` |
| 图卡尺寸:后端白名单 3 档 {'1024x1024','1024x1792','1792x1024'},GenerateInput size 缺省 '1024x1792';前端 CARD_SERIES_SIZES 同 3 档 + CardSeriesPanel 尺寸下拉 | `cardseries/CardSeriesService.java:53`、`:370-371`;`src/constants/card-series-templates.ts:81-85`;`src/views/article/components/CardSeriesPanel.vue:179-182` |
| 契约守护测试:断言 platforms 长度 9、version 日期格式、前端 import 路径——本卡加字段不破 | `test/deployment/node-backend-boundary.contract.test.ts:80-89` |

### 3.4 通用门禁
- 前端:`npm run typecheck` / `npm run lint` / `npx vitest run` / `npm run build`
- 后端(按卡):`cd platform-java && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ./gradlew :services:intelligence-service:test spotlessCheck`(卡 A/C);`:services:marketplace-service:test :services:trust-service:test spotlessCheck`(卡 B)
- 全量含 Kafka 的 IT 如 flaky,加 `--max-workers=2`
- UI 改动遵守根 DESIGN.md:本批前端均为文案/提示/既有控件默认值改动,不新增样式与颜色 token

## 4. 全局约束(适用于每一张卡)
- 只允许改动当前卡列出的文件,其他文件一律不碰。
- 不改既有接口的行为语义,除非当前卡明确声明(卡 A 的两个新端点+建任务防呆、卡 B 的授权响应加字段+新 409、卡 C 的契约加字段+defaultResolutionFor 数据源切换)。
- 不引入新依赖;本批零迁移;spotlessApply 后 spotlessCheck 必须过。
- **测试纪律**:既有测试断言零改动全绿。唯一声明的例外:卡 B 的 `TrustItSupport.java:80` 默认授权桩因 record 加字段必须连带补参(断言与期望值仍零改动);若既有用例因新校验意外变红,先怀疑自己实现错了而不是改断言。
- **卡住时**:同一问题最多尝试 2 次,然后停止,原样报告错误信息和已尝试的做法。禁止猜测、禁止绕过、禁止编造。

## 5. 任务总表

| 卡 | 标题 | 主要新建 | 主要修改 | 依赖 |
|---|---|---|---|---|
| A | 分镜增删镜头端点化 | ShotStructureIT、useVideoProduction.test.ts | VideoProductionController、VideoShotRepository、VideoProductionTaskService、edge application.yml、useVideoProduction.ts、VideoProductionView(如有注释) | 无 |
| B | 争议异议 48h 时限 | (IT 用例并入既有文件) | DisputeAuthorizationController、MarketplaceEngagementAuthorizationClient、DisputeController、AdjudicationProperties、trust application.yml、TrustItSupport(桩)、DisputeControllerIT、EngagementDisputeAuthorizationControllerIT、GrasslandWorkbench.vue(一句文案) | 无 |
| C | 契约媒体规格两维+规范检查接线 | useArticleFormatRule 测试、PlatformMediaSpecContractTest | contracts/platform-format-rules.json、platform-format-rules.ts、useArticleFormatRule.ts、ArticleCreationView.vue、video-production.ts、CardSeriesPanel/useCardSeries | 无 |

## 6. 任务卡

### 卡 A:分镜增删镜头端点化
**背景**:PRD §4.4 分镜编辑要求「增删镜头」。前端按钮已有但只改本地数组,建任务时全部丢弃(服务端行是真相源)——用户可感知的静默丢失。本卡补齐两端点并把前端写通。

**改动文件**:
- 修改 `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/videoproduction/VideoProductionController.java`(新增两端点)
- 修改 `videoproduction/VideoShotRepository.java`(新增 count/delete/单行 setSeq)
- 修改 `videoproduction/VideoProductionTaskService.java`(建任务防呆一处)
- 修改 `platform-java/services/edge-bff/src/main/resources/application.yml`(两条路由)
- 修改 `src/composables/useVideoProduction.ts`(addShot/removeShot 写通+updateShot 注释)
- 新建 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/videoproduction/ShotStructureIT.java`
- 新建 `src/composables/useVideoProduction.test.ts`

**开始前检查**:读 `VideoProductionController.java:97-179`(storyboard 详情/PATCH grouping/PUT content 的鉴权与错误风格)、`VideoShotRepository.java` 全文、`StoryboardGroupingIT.java`(分镜造数手法)、`useVideoProduction.ts:377-448`、`useCommerce.test.ts`(mock grassland-http 手法)。

**做法**:
1. **仓储新增**(`VideoShotRepository`):
   - `Mono<Long> countByStoryboard(UUID storyboardId)`:`SELECT count(*)`。
   - `Mono<Boolean> delete(UUID shotId)`:`DELETE FROM video_shot WHERE id=CAST(:id AS uuid)`,rowsUpdated>0。
   - `Mono<Boolean> setSeq(UUID shotId, int seq)`:`UPDATE video_shot SET seq=:seq, updated_at=now() WHERE id=...`。
2. **POST 端点** `@PostMapping("/api/video-production/storyboards/{id}/shots")`,请求 record `ShotCreateRequest(String visual, String narration, Integer plannedSeconds, String cameraMove, Integer anchorImageIndex)`(全可空;注意包装类型——可选数值字段禁 primitive):
   - 鉴权与定位:requireUser → `storyboardRows.findById(id, caller.accountId())` 404「分镜不存在」→ committed 409「分镜已提交成片,不能再增删镜头」;
   - 数量闸:`shotRows.countByStoryboard` ≥30 → 409「镜头数已达上限 30」;
   - 归一:visual/narration 为 null/blank → "";plannedSeconds null→5,非空钳 4-6;cameraMove null/blank→"固定机位";anchorImageIndex null→0,负数→400;**prompt=visual**(D3);
   - 写入:`shotRows.upsert(storyboardId, count+1, ...)`;响应 `data` = {id,seq,visual,narration,plannedSeconds,cameraMove,anchorImageIndex,status}(照 storyboardBody 的镜头 payload 字段名)。
3. **DELETE 端点** `@DeleteMapping("/api/video-production/shots/{shotId}")`:
   - 属主闸:`shotRows.findByIdForAccount(shotId, accountId)` 404「镜头不存在」→ storyboard committed 409(同 POST 文案);
   - 下限闸:该分镜镜头数 ≤3 → 409「至少保留 3 个镜头」;
   - 事务内(`transactions.transactional`)三步:①`delete(shotId)`;②**顺位重排**:`findByStoryboard` 按既有序取回,按升序逐行 `setSeq(row.id, i+1)`——**必须升序逐行**:UNIQUE(storyboard_id,seq) 是即时约束且 seq CHECK 1-30 禁止先加偏移再归位的两段式,升序重排(值只会不变或变小)每步目标值必空闲;③grouping 剔除:storyboard.grouping 非空时解析 JSON,从 `shots[].id` 与 `branches[].shotIds` 移除该 id 后 `updateGrouping` 回写(grouping 为 null 跳过);
   - 响应 `data` = {removed: shotId, shotCount: 重排后数量}。
4. **建任务防呆**(`VideoProductionTaskService` create 入口,`VideoProductionTaskController.java:82` 调用处):分镜镜头存在 visual 为空白的行 → 400「存在未填写画面描述的镜头,请补全后再生成」。放在 spawnRows/markCommitted 之前。
5. **edge 路由**(application.yml,SCRIPT 组,与 PATCH storyboards 相邻):
   ```yaml
   - method: POST
     path: /api/video-production/storyboards
     upstream: intelligence
     enabled: ${EDGE_ROUTE_VIDEO_SCRIPT_INTELLIGENCE:true}
   - method: DELETE
     path: /api/video-production/shots
     upstream: intelligence
     enabled: ${EDGE_ROUTE_VIDEO_SCRIPT_INTELLIGENCE:true}
   ```
   (既有 `POST /api/video-production/storyboard` 是 `exact: true`,不覆盖新路径——必须新增条目。)
6. **前端**(`useVideoProduction.ts`):
   - `addShot` 改 async:`storyboardId.value` 为空或 `!canAddShot` 直接 return;POST `/api/video-production/storyboards/{storyboardId}/shots`(空 body `{}`);成功后把响应 data 经 normalizeShot 映射(保留服务端 id/seq)push 进 shots;失败落 `error`(「镜头新增失败」fallback)。删除旧注释里的「无创建端点,遗留缺口」表述(updateShot :374-375 注释同步改写为「行是任务生成的真相源」)。
   - `removeShot(index)` 改 async:取 `shots.value[index]`,无 id(防御分支)→ 本地过滤;有 id → DELETE `/api/video-production/shots/{id}`,成功后本地过滤+重排 seq,失败落 `error`。
   - 导出名与调用点签名不变(VideoProductionView 的 @click 无需改;返回 Promise 即可)。
7. **测试**:
   - `ShotStructureIT`(照 StoryboardGroupingIT 造 draft 分镜+镜头):POST 默认值落库与 seq=末尾追加;POST 上限 30/committed 409/非属主 404/负 anchorImageIndex 400;DELETE 重排后 seq 连续无空洞/下限 3 → 409/committed 409/非属主 404/grouping 悬空 id 被剔除;建任务防呆(visual 空白行 → POST /tasks 400)。
   - `useVideoProduction.test.ts`:mock grassland-http;addShot 发 POST 且返回 id 后 updateShot 能 PUT 写通;removeShot 发 DELETE;失败落 error 不改数组。

**边界**:不做中间插入/重排序端点(D1);不动 grouping 的分组分支语义;不动 SSE 生成链与画布;take/audio 级联由 ON DELETE CASCADE 天然处理(draft 期无 takes,不依赖);零迁移。

**验收**:
- `cd platform-java && JAVA_HOME=… ./gradlew :services:intelligence-service:test --tests '*ShotStructureIT' spotlessCheck` 绿,且全量 `:services:intelligence-service:test` 绿;
- `npx vitest run src/composables/useVideoProduction.test.ts src/views/video-production` 绿;`npm run typecheck`、`npm run build` 绿;
- `grep -n "遗留缺口" src/composables/useVideoProduction.ts` 无输出;
- 冒烟(可起栈时):分镜步增一镜→刷新页→restoreStoryboard 恢复后新镜仍在(附截图/断言输出);不可起栈则以 IT 断言代替。

**完成后按此格式报告**:
```
改动文件:...
执行的命令与结果:...
偏离卡面之处:无 / <列出>
卡住项:无 / <错误原文>
```

### 卡 B:争议异议 48h 时限
**背景**:PRD §7.1 要求异议在核实结果公布后 48 小时内提出,当前开争议无任何时限(代码里的两个 48h 是开庭等待窗与上诉窗,语义不同)。本卡给「创建新争议」加时限闸。

**改动文件**:
- 修改 `marketplace-service/.../taskcatalog/DisputeAuthorizationController.java`(组装并回传 resultAnchorAt)
- 修改 `trust-service/.../dispute/MarketplaceEngagementAuthorizationClient.java`(Authorization/AuthorizationData 加字段+解析)
- 修改 `trust-service/.../dispute/DisputeController.java`(窗口校验)
- 修改 `trust-service/.../adjudication/AdjudicationProperties.java`(新配置)
- 修改 `trust-service/src/main/resources/application.yml`(adjudication 块补配置)
- 修改 `trust-service/src/test/.../TrustItSupport.java`(**仅** :80 默认授权桩补参——本批唯一声明的测试基建改动)
- 修改 `DisputeControllerIT.java`、marketplace `EngagementDisputeAuthorizationControllerIT.java`(各补用例)
- 修改 `src/views/grassland/GrasslandWorkbench.vue`(:1162 提示文案补一句)

**开始前检查**:读 `DisputeController.java:75-168`、`MarketplaceEngagementAuthorizationClient.java` 全文、`DisputeAuthorizationController.java` 全文、`AdjudicationProperties.java`(disputeCooldownHours 的「<0 归默认 / 0 哨兵」写法)、`EngagementSubmission.java`/`EngagementVerification.java` record(确认 reviewedAt/lastCheckedAt 字段名)、`SubmissionRepository.findByApplication`/`EngagementVerificationRepository.findBySubmissions` 签名。

**做法**:
1. **marketplace 回传**(`DisputeAuthorizationController`):注入 SubmissionRepository + EngagementVerificationRepository;在既有 data map 基础上:
   - `subs = submissions.findByApplication(app.id()).collectList()`;`verifs = verifications.findBySubmissions(subs.map(id)).collectList()`;
   - `resultAnchorAt` = 上述 subs 的 reviewedAt、verifs 的 lastCheckedAt、app.confirmedAt、app.autoConfirmedAt 中**非空最大值**(全空→null);
   - `data.put("resultAnchorAt", anchor == null ? null : anchor.toString())`。
2. **trust 客户端解析**(`MarketplaceEngagementAuthorizationClient`):Authorization record 加组件 `Instant resultAnchorAt`(保留既有两参 compact 构造→锚点 null,老调用方编译不破);AuthorizationData 加 `String resultAnchorAt`;validate:字段 null/blank → 锚点 null(**放行**);非空但 Instant.parse 失败 → AuthorizationException(fail-closed,与该客户端既有纪律一致)。
3. **配置**(`AdjudicationProperties` + yml):新组件 `int disputeOpenWindowHours`(compact 构造守卫:`<0 → 48`;**0 保留为禁用哨兵,不归默认**——照 disputeCooldownHours 注释风格写清)、`long disputeOpenWindowSeconds`(<0→0);新方法 `disputeOpenWindowSecondsEffective()`(秒级>0 优先,否则小时×3600)。yml trust.adjudication 块补 `dispute-open-window-hours: ${TRUST_DISPUTE_OPEN_WINDOW_HOURS:48}` 与 seconds 覆盖注释(照 adjudication-window 既有条目风格)。
4. **窗口校验**(`DisputeController`):
   - `openOrDefer` 签名加参 `Instant resultAnchorAt`(open 调用处从 auth 透传);
   - switchIfEmpty 分支、冷却期通过之后,加 `checkDisputeWindow(resultAnchorAt)`(私有方法,照 checkDisputeCooldown 结构):effective==0 → 过;anchor==null → 过(debug 日志一次);`Instant.now()` 晚于 anchor+effective → 409 文案:`核实结果已公布超过 N 小时,异议期已过,无法开启争议(如有特殊情况请联系平台客服)`(N 按 effective 量级择小时/秒显示,照冷却期文案的单位择法)。
   - **不碰**:openForMarketplaceService(merchant_rejection 服务断言路径)、deferred 路径、活跃争议幂等返回(D6)。
5. **TrustItSupport 桩**::80 默认 `new Authorization(...)` 改用带 resultAnchorAt=null 的构造(两参 compact 即可,若默认桩本就走全参则补 null)。
6. **测试**:
   - `DisputeControllerIT` 新用例(默认授权桩改为可控锚点或在用例内重置桩):
     a. anchor=now-1h → 开争议 201;b. anchor=now-49h → 409 且文案含「异议期已过」;c. anchor=null → 201;d. 覆盖属性 `trust.adjudication.dispute-open-window-hours=0` 时 anchor=now-49h → 201;e. seconds 覆盖(如 =2s,anchor=now-10s)→ 409(dev 通道有效);
     f. marketplace 服务断言开 merchant_rejection(anchor 极老)仍可开(路径不受限的显式佐证)。
   - `EngagementDisputeAuthorizationControllerIT` 新用例:有 submission(reviewedAt)+确认(confirmedAt)→ resultAnchorAt=max;无任何时间戳 → null 字段存在且为 null。
7. **前端**:`GrasslandWorkbench.vue:1162` 的 gl-hint 追加一句「异议须在核实结果公布后 48 小时内提出」(不改任何逻辑)。

**边界**:不动冷却期/开庭等待/上诉窗;不动 merchant_rejection 与 deferred 语义;不做前端倒计时;不改 DisputeCase 数据模型(窗口实时计算不落库);零迁移。

**验收**:
- `cd platform-java && JAVA_HOME=… ./gradlew :services:marketplace-service:test :services:trust-service:test spotlessCheck` 全绿(既有用例断言零改动);
- `npm run typecheck`、`npx vitest run src/views/grassland` 绿;
- `grep -rn "dispute-open-window" platform-java/services/trust-service/src/main/resources/application.yml` 有输出。

### 卡 C:契约媒体规格两维 + 规范检查接线
**背景**:PRD §4.7 规范检查要求覆盖「图片尺寸和视频比例」「必须关键词和任务要求覆盖情况」。平台契约目前只有文字维度,关键词覆盖无检查。本卡给契约补 imageSpec/videoSpec 结构化维度并接进规范检查与平台缺省联动(D7:关键词不进契约,以 mustInclude 覆盖检查落地)。

**改动文件**:
- 修改 `contracts/platform-format-rules.json`(每平台加可选 imageSpec/videoSpec;version → "2026-09-04")
- 修改 `src/config/platform-format-rules.ts`(PlatformMediaSpec 类型 + PlatformFormatRule 两字段)
- 修改 `src/views/article/composables/useArticleFormatRule.ts`(规格句 + mustInclude 覆盖检查)
- 修改 `src/views/article/ArticleCreationView.vue`(从 handoff 提取 mustInclude 传入;若 ArticleCompletedView 持有同款 handoff 且消费 formatIssues,一并接——开始前检查确认)
- 修改 `src/types/video-production.ts`(defaultResolutionFor 改读契约)
- 修改 `src/views/article/components/CardSeriesPanel.vue` 或 `src/composables/useCardSeries.ts`(尺寸初值按平台 imageSpec 联动,二选一:初值定义在哪改哪)
- 新建 `src/views/article/composables/useArticleFormatRule.test.ts`(若已存在则并入)
- 新建 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/creationcontext/PlatformMediaSpecContractTest.java`

**开始前检查**:读 `contracts/platform-format-rules.json` 全文、`PlatformCreationRuleCatalog.java`(确认 convertValue 全量透传)、`useArticleFormatRule.ts` 全文、`ArticleCreationView.vue:572-574`+`:730-735`(handoff 消费)、`src/types/ai-creation.ts:48-63`、`VideoResolution.java:24-29`、`useCardSeries.ts` 的 size 初始化段、`MomentsCreationView.vue:25`(formatRuleSummary 消费点,确认规格句对它无害)。

**做法**:
1. **契约**(数值定死,执行期不得改;null 表示该平台无该维度主规格建议):
   | platformId | imageSpec | videoSpec |
   |---|---|---|
   | xiaohongshu | {aspect:"3:4",width:1080,height:1440,note:"首图 3:4 占屏最佳,9:16 与 1:1 亦可"} | {aspect:"9:16",width:1080,height:1920} |
   | douyin | {aspect:"9:16",width:1080,height:1920,note:"图集与视频同竖版规格"} | {aspect:"9:16",width:1080,height:1920} |
   | dianping | null | null |
   | kuaishou | null | {aspect:"9:16",width:1080,height:1920} |
   | wechat-channels | null | {aspect:"9:16",width:1080,height:1920} |
   | bilibili | {aspect:"16:9",width:1920,height:1080,note:"封面建议 16:9"} | {aspect:"16:9",width:1920,height:1080} |
   | wechat-official | null | null |
   | zhihu | null | null |
   | moments | {aspect:"1:1",width:1080,height:1080,note:"九宫格按 1:1 裁切"} | null |
   `version` 改 `"2026-09-04"`。
2. **TS 类型**(`platform-format-rules.ts`):
   ```ts
   export interface PlatformMediaSpec { aspect: string; width: number; height: number; note?: string }
   ```
   `PlatformFormatRule` 加 `imageSpec?: PlatformMediaSpec | null`、`videoSpec?: PlatformMediaSpec | null`(spread 透传,类型即可)。
3. **useArticleFormatRule**:
   - options 新增可选 `mustInclude?: Ref<readonly string[]>`;
   - `formatRuleSummary`:imageSpec/videoSpec 任一非空时追加「图片建议 {aspect}({width}×{height})/视频建议 {aspect}({width}×{height})」句(note 不进 summary);
   - `formatIssues` 新增:combined = (selectedTitle+content).trim;对 mustInclude 每项(trim 后非空)做子串包含检查,未覆盖 → `必须包含项「X」尚未出现在标题或正文中`;
   - 返回结构不变(formatIssues 多几条而已)。
4. **ArticleCreationView 接线**:computed 从 `props.creationHandoff?.taskContext?.requirements` 提取 `mustInclude`(`Array.isArray(x) ? x.filter(v=>typeof v==='string') : []`)传入 useArticleFormatRule;ArticleCompletedView 若同样持有 handoff 并消费 formatIssues 则同款接入(开始前检查确认,没有就不动)。
5. **视频分辨率缺省**(`src/types/video-production.ts`):`defaultResolutionFor` 改为 `getPlatformFormatRule(platform)?.videoSpec?.aspect === '16:9' ? RESOLUTION_LANDSCAPE : RESOLUTION_PORTRAIT`(import 自 `../config/platform-format-rules`;注释改为「平台缺省分辨率(读平台规则契约 videoSpec,与后端 VideoResolution.defaultFor 同值集)」。bilibili 行为不变=既有断言不破。
6. **图卡默认尺寸联动**(useCardSeries 或 CardSeriesPanel 的 size 初值处):平台 imageSpec.aspect 命中映射 {'9:16':'1024x1792','1:1':'1024x1024','16:9':'1792x1024'} 才改初值,否则维持 '1024x1792' 现状(小红书 3:4 不在生成白名单→维持现状,契约 note 已说明 9:16 亦可);用户显式选择后不再自动覆盖(仅初始化一次)。
7. **对账单测** `PlatformMediaSpecContractTest`(creationcontext 包,读 classpath `contracts/platform-format-rules.json`):
   - 断言 version=2026-09-04、9 平台、每平台 imageSpec/videoSpec 结构合法(aspect/width/height 非空,note 可缺);
   - 断言 `PlatformCreationRuleCatalog.snapshot("douyin", "article")` 含 imageSpec/videoSpec 键(全量透传证明);
   - 断言契约 videoSpec 与 `VideoResolution.defaultFor` 值集一致:bilibili→16:9/LANDSCAPE,douyin、kuaishou、wechat-channels、xiaohongshu→9:16/PORTRAIT(VideoResolution 是 videoproduction 包 package-private——若跨包不可达,则在本测试直接断言「16:9 的平台恰为 bilibili 一个、其余 videoSpec 平台均为 9:16」,并在注释里写明与 VideoResolution.defaultFor 的对应关系)。
8. **前端测试** `useArticleFormatRule.test.ts`:mustInclude 覆盖/未覆盖两态;imageSpec/videoSpec 的 summary 句;douyin(有规格)与 wechat-official(双 null)对比。

**边界**:不做平台级关键词清单(D7);不扩图卡生成白名单(3 档不变);规范检查仍为前端提示层(不阻断、不进服务端校验);后端除对账测试外零 Java 改动(D8);不动内容安全/快照结构;零迁移。

**验收**:
- `cd platform-java && JAVA_HOME=… ./gradlew :services:intelligence-service:test --tests '*PlatformMediaSpecContractTest' spotlessCheck` 绿,全量 `:services:intelligence-service:test` 绿(快照类既有测试不得红——若红,是契约字段与某处严格断言冲突,报告原文);
- `npx vitest run` 全绿、`npm run typecheck`、`npm run lint`、`npm run build` 绿;
- `node-backend-boundary.contract.test.ts` 零改动通过(platforms 仍 9、version 仍日期格式);
- 冒烟(可起栈时):抖音图文任务带 mustInclude 生成→完成页提示区出现覆盖检查与图片/视频规格句(附截图)。

## 7. 集成验收(全部卡完成后执行)
- 仓库根:`npm run typecheck`、`npm run lint`、`npx vitest run`、`npm run build` 全绿。
- `cd platform-java && JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home ./gradlew test spotlessCheck` 全绿(Kafka flaky 时 `--max-workers=2` 重跑一次)。
- `git diff --name-only` 检查:测试文件改动仅限卡 B 声明的 TrustItSupport 桩与两个 IT 的新增用例、三张卡的新建测试文件;无任何既有断言删改。
- 交付报告附:卡 A 增删冒烟记录(或 IT 断言输出);卡 B 六个新用例的测试报告摘录;卡 C 契约 diff 与快照含新维度的断言输出。

## 附:返工卡格式(强模型 review 后按此格式产出,编号 R-1、R-2…)

### 返工卡 R-1(针对卡 X)
**问题位置**:`<文件:行>`
**期望行为 vs 实际行为**:<一句话对比>
**修复做法**:
1. <步骤化,同任务卡写法>
**验收**:运行 `<命令>`,期望 `<…>`
