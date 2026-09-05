# 草场任务书 #78：积分与 AI 治理收敛至 AI 创作中心

背景：2026-09-05 用户提出四项结构设计（已确认按推荐方案拍板）+ 两个线上 bug。①积分与套餐只在 AI 创作中心展示，草场（商家/推荐官登录后）不再露出；②商家工作台「AI 与治理」页签整体迁入 AI 创作中心；③AI 创作中心新增个人级「模型来源」统一开关：默认平台统一模型（可设 token/钱上限，按积分计费），不用平台模型则自带密钥，且内容安全深检、内容修复等平台免费能力直接绕过；④治理台 AdminView 23 个平铺页签分类归组。Bug：⑤素材库添加素材 413（文案误报「凭据已过期」）；⑥法人身份证 18 位被误判无效，且改为只支持 18 位。

同日第二批反馈四条**并入本任务书**（卡 G–J）：⑦「权限与额度」联系方式加校验（拍板：只收 11 位手机号）；⑧发布新任务由右侧抽屉改居中弹窗；⑨发布任务赏金/霸王餐押金「无法填写」——查实为 finance_transaction 权限门禁无解释（**非 #77 回归**，git blame 已排除）+ 两处输入缺陷（拍板：保留禁用+就地解释+升级入口）；⑩资金与经营页签加左侧二级分栏、营收趋势加分页。

**设计前提变更（明写，推翻两条 #76 拍板）**：#76 D6「AI 应用无组织概念」受控放宽——创作面仍无组织，治理板块内局部引入组织上下文（仅主体下拉，无全局身份切换）；#76 D7「执行与预算零后端改动」被卡 B 打破——`AiExecutionService` / `ByokRoutingService` / `ContentSafetyService` 三处必动。

## 一、决策表（2026-09-05 拍板，全部定死）

| # | 决策点 | 定案 |
|---|---|---|
| D1 | 积分与套餐展示 | **草场全域移除**（含营销页游客）：DefaultLayout 删积分徽标 + CreditsPackagesModal 挂载；AI 应用头部徽标+弹窗保留为唯一入口。草场内 402 统一改写文案并引导跳 AI 应用充值。商家资金账户/月度账单/到店套餐、推荐官钱包/收益是现金经营账，**全部不动**；治理台「积分套餐」管理页签不动 |
| D2 | AI 与治理迁移 | 工作台删 `wtab=ai`（商家侧剩 任务与报名/主体与门店/资金与经营 三签）；三卡（组织预算/组织密钥+回退策略/主体创作审计）**全迁** AI 创作中心；组件零改动复用，AI 壳拉 `GET /api/me/organization-scopes` 过滤 owner/admin，**板块内主体下拉**（多主体），无角色则整节不渲染，不引入全局身份切换 |
| D3 | 统一模型开关 | 个人级 `modelSource: platform(默认) \| own`，**一个总开关**取代 4 个 per-capability useOwnKey 碎片开关。platform：全能力平台路由+按积分计费+个人预算（token/钱六限）生效+免费能力全开。own：各能力用自有密钥；**未配置密钥的能力禁用并引导配置（不回退平台）**；不扣积分、**跳过个人预算闸**（ai_run 计量照留）；平台免费能力绕过 = **跳过 L2 AI 深检与内容修复，L1 词库 + SimHash 保留**（本地零成本，平台底线）；绕过判定口径 = **凡该次生成解析为自有凭据即绕过（含组织 BYOK 命中），凡平台凭据照旧**。边界：个人开关只管无组织上下文的自由创作；草场任务创作（orgId 非空）走组织链不受个人开关影响；提交/评论强制安全闸（SubmissionSafetyController/CommentSafetyController）**绝不动**——BYOK 用户发布内容仍有底线拦截；视频管线内部 video_qa/video_tts 平台能力范围外不动 |
| D4 | 治理台归类 | 形态 = **第一行分类 pill（nav-pill-group）+ 第二行组内页签（category-tab）**，不做侧栏；五组定案见卡 D 表；页签定义收敛为**单源 TAB_REGISTRY**；新增 `?section=` 深链；OpsConsole/OpsApp 不动；组件物理归位不做 |
| D5 | 素材 413 修复 | 根因 = nginx 9002（MinIO 预签名反代）server 块漏配 `client_max_body_size`（默认 1m），>1MB 直传 PUT 在 nginx 即被 413，未到 MinIO。修复 = 该块加 `client_max_body_size 32m`（对齐 /api 现值，覆盖 20MB 媒体帽 + 25MB 语音帽）；前端 `putToPresignedUrl` 按状态码分文案（413=文件过大 / 403=保留凭据过期 / 其余=通用）；后端零改动 |
| D6 | 身份证校验 | 根因 = 前后端同源的**县级地址码白名单拒真**（2023 现行 2978 码 + 70 个手工兼容码，追不上历史区划；校验位权重表核对无错）。修复 = 只支持 **18 位**（删 15 位分支）；地址码校验降级为**省级前缀白名单**：前 2 位 ∈ {11–15, 21–23, 31–37, 41–46, 50–54, 61–65, 81, 82}（81/82=港澳台居民居住证，同为 18 位）；GB 11643 校验位 + 出生日期 + toUpperCase/trim 全保留；前后端同步改；存量 15 位密文行「输入留空=沿用」不卡提交、**不洗数据**、DB 无格式约束不动 |
| D7 | 权限材料联系方式校验 | **只收 11 位手机号**（用户拍板，否决电话/邮箱复合口径）：`^1[3-9]\d{9}$`，前后端同步；前端单用 `MOBILE_PHONE_PATTERN`（**勿用含座机/400 的 isValidPhone 复合**）；placeholder/maxlength=11/inputmode 就位；后端对全部材料值顺带统一长度帽 128；治理台审核侧展示不动；申诉路径复用同表单自动生效 |
| D8 | 赏金/押金权限门 | **保留禁用 + 就地解释 + 升级入口**（用户拍板）：`!canPublishBounty` 时两框保持 disabled，框下提示「发布赏金/押金任务需先升级到资金交易权限」+「去升级」跳 org 页签 permission 分节；后端 tier 闸门不动、身份条既有警示不动。顺带修两处输入缺陷：小数输入改「表单态存原始字符串、提交时才转换」；模式切换不再清值（drafts 提交归零双保险已兜底） |

## 二、全局锚点（动手前必读，均已核对 2026-09-05 main）

### 2.1 草场前端

- **积分徽标**：`src/layouts/DefaultLayout.vue:60-67`（`{{ currentBalance }} 次`）+ CreditsPackagesModal 挂载 :145-149 + useCredits :262。AI 应用对应物 `src/ai/AiAppLayout.vue:41-48/:92-96` **保留不动**。弹窗本体 `src/components/CreditsPackagesModal.vue` 双端共用，勿删。
- **402 链路**：后端 402 文案 = `FrozenTextExecutionService.java:319` 与 `AiRunController.java:127`「积分不足」、`exceeds_run/daily/monthly_budget` 同 402（deniedException :317-325）。前端 `grassland-http.ts` 抛 `GrasslandHttpError(status, message)`（:12,:53）；冒泡收口在 `useGrassland.run()`（`src/composables/useGrassland.ts:30-36`，error.value 直接展示）——402 改写挂这里，一处覆盖全草场。
- **工作台页签**：`MERCHANT_TABS`/`RECOMMENDER_TABS` 于 `src/views/grassland/GrasslandWorkbench.vue:265-279`；ai 页签三卡挂载 :1209-1232（owner/admin 前端门禁 `useWorkbenchSession.ts:99-101`）。**三卡组件文件保留（卡 C 复用），只删草场挂载与页签项**；`?wtab=ai` 深链回落默认 tasks。
- **AI 入口已就位**：#77 卡 E 头部右侧「AI 创作」按钮（外跳 ai.html + token 免登）——402 引导跳转复用 `useCrossAppJump.jumpToAiApp`。

### 2.2 AI 创作中心（ai.html）

- 壳：`src/ai/AiAppLayout.vue`（头部/登录/积分徽标/xat 核销/门店深链 :182-199；事件桥 :251-271）。
- 九板块：`src/views/ai-center/components/AiCenterNavigation.vue:13-19`（create/assistant/speech/image-studio/image-gen/video-studio/runs/library/keys）；keys 兜底挂载 `AiCreationCenter.vue:300/:306`；platform 模式过滤清单 :412-419（治理板块不进草场，过滤逻辑不动）。
- 个人预算卡：`src/components/PersonalAiBudgetCard.vue` 现挂 runs 板块 `AiCreationCenter.vue:272-275`（端点 `useAiPersonalBudget.ts:7-13` `GET/PUT /api/ai/me/budget`）。
- 个人密钥面板：`src/components/AiProviderKeysPanel.vue`——每能力 useOwnKey 开关 :153-156、二次确认 :192-199、计费三态标签 :221-227、KEK 未配 404 容错文案 :237-239（保留）；端点 `useAiControlPlane.ts:64-78`（keys CRUD）、:113-123（preferences）。
- 组织三卡（迁移对象，零改动复用）：`AiOrgBudgetPanel.vue` + `useAiOrgBudget.ts:7-13`（`/api/ai/organizations/{orgId}/budget`）；`AiOrgProviderKeysPanel.vue`（BYOK 策略开关 :29-47）+ `useAiControlPlane.ts:82-108`（org keys + byok-policy）；`OrgCreationAuditPanel.vue` + `useCreationGenerations.ts:83`。
- 组织角色清单：`GET /api/me/organization-scopes`（`useGrasslandIdentity.ts:36`，OrganizationAccessScope[] 含角色）——AI 壳复用此端点，不引入 useActiveIdentity/身份切换。

### 2.3 后端（intelligence-service，IS = platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence）

- **预算**：分流点 `ai/run/AiExecutionService.java:221`（orgId 有无 → orgId \| `"u:"+accountId`，personalScope 见 `AiPersonalBudgetController.java:38-40`）；reserveAndCreateRun :214-229；校验 `ModelBudgetService.checkAndReserve` :34-58（denied `exceeds_*_budget`）；`'*/*'` 全局行先例 `AiModelBudgetRepository.java:71-98`。BYOK 现状 cents=0 但 token 照估、仍入预算（estimateForProvider :245-249）。
- **BYOK 链**：`ai/byok/ByokRoutingService.java:72-98`——orgId 非空走组织段 :101-128（组织密钥>平台，allowPlatformFallback 双闸）跳过个人；个人段 :90-97（isOwnKeyEnabled per-capability）>平台段 :131-140（`PlatformModelControlPlaneService`）。个人密钥端点 `AiProviderKeyController.java:49`；偏好 `AiProviderPreferenceController.java:32` `/api/ai/preferences[/{capability}]`；偏好表迁移 **V49__ai_provider_preference.sql**（动手前先读列名）。
- **内容安全（全部 contentsafety 包）**：编排 `ContentSafetyService.java:47-65`（L1 恒跑 → L2 ≥200 字 → SimHash），SSE 尾帧 `appendSafetyFrame` :83-103，接入点 8 处（ArticleController:258/280/336、MomentsGenerationController:85、ImageAnalysisController:121、ComedyController:137、StoryboardService:121-126、CreationAssistantController:92、VideoProductionController:671）——**绕过做在编排层内部，8 个接入点签名零改动**。L2 深检 `ContentSafetyAiChecker.java:56-74`（capability=content_safety、feature=null 免积分但走执行环吃预算）；修复 `ContentSafetyFixService.java:37-44` → `executeFree`（`FrozenTextExecutionService.java:134-143`，content_fix），未配模型 503 `ContentSafetyFixController.java:44-54`；手动复查 `ContentSafetyController.java:34`。**红线不动**：`SubmissionSafetyController.java:37` / `CommentSafetyController.java:35`。
- **既有测试连坐**：`ByokRoutingServiceTest.java`、`AiProviderPreferenceControllerIT.java`（偏好端点改写）、`MerchantProfileControllerIT.java:126-152`（15 位样本，卡 F）。
- **已知陷阱**：Jackson record 可选字段包装类型；`is` 前缀序列化陷阱；Reactor switchIfEmpty 装配期求值；新迁移幂等 DDL（否则 replay 测试挂）。

### 2.4 治理台（ops.html）

- `src/ops/admin/AdminView.vue`：tab 按钮 :13-127、面板 :130-462、`TAB_ROLES` :651-656、activeSection 类型 :699-701、audit 是 v-else 兜底 :460-462（key 拼错静默落 audit——本卡消除）；data-testid 先例已有（:141/:162 等）。
- 测试硬约束：`AdminView.test.ts`（47KB）**大量按数字下标点页签**，:65-66/:94-95 注释示警「插在中间会整片错位」——卡 D 必须同步改定位方式。
- 设计语言：`src/ops/DESIGN.md` 的 `nav-pill-group` / `category-tab`（AdminView 内 `.status-pill-group` 已在用）；配置化先例 `OpsApp.vue` NAV_ITEMS :101-105。

### 2.5 两 bug 根因锚点

- **413**：`nginx.conf:427-473` 9002 server 块无 `client_max_body_size`（默认 1m）；既有 7 处配值见 :110/:136/:164/:245/:271/:360/:386（/api 均 32m）。文案误报源 `src/composables/grassland-http.ts:115-124` `putToPresignedUrl` 对一切非 2xx 抛同一句；该封装是全站唯一直传出口，5 个 composable 调用（useGrasslandGovernance:425/454/512/736、useGrasslandMarketplace:155/175、useAiStudio:43、useGrasslandIdentity:409）一处修全覆盖。前端预检 `MediaUploader.vue:23`（20MB）；上限真值：图片 10MB/视频 20MB（MediaController.java:93-96）、通用 20MB、语音 25MB。nginx.conf 进前端镜像（Dockerfile.frontend:12）——**改后必须重打前端镜像**。
- **身份证**：前端 `src/lib/kyb-validation.ts`（文案 :25、权重 :66-67 无错、主体 :69-94、15 位分支 :73-79、地址码 :82→:151-153）、白名单 `src/lib/kyb-id-area-codes.ts`（现行 2978 :12-201 + legacy 70 :203-274）；后端 `identity-service .../kyb/MerchantProfileFields.java`（:18-21 常量、:76-86 入口文案、:88-113 校验）+ `ChineseIdAreaCodes.java`（含数量断言，退役连坐）；调用链 `MerchantProfileController.java:107`。输入框 `MerchantKybCard.vue:215-228` 无 maxlength/mask 坑。存量：V15:9 varchar(32) 无约束、V21:10 密文，回显掩码 `KybReviewDetailService.java:108`。

### 2.6 e2e / 契约连坐（先 grep 清点再动手）

- e2e 无积分徽标锚（已核：`grep credits-badge tests/e2e` 空）——卡 A 低风险；`tests/e2e/ai-creation-center.spec.ts` 关注 keys 板块改名后锚。
- KYB e2e `tests/e2e/kyb-admin-review.spec.ts:142` 用 18 位 `310101199001015673`（310101 在省级白名单内）——不需改，验证即可。
- nginx 无契约测试（仅 edge-entrypoint.contract.test.ts，不涉 client_max_body_size）——卡 E 无测试连坐，需实测。
- 草场 `?wtab=ai` 深链、AI 链跳转相关 spec 先 grep。本地 macOS 跑不了完整 e2e 栈（既定事实），e2e 在 CI 跑。

### 2.7 UI 规范

- 草场改根 `DESIGN.md`、治理台改 `src/ops/DESIGN.md`；token-only 禁止硬编码；弹窗一律 GlModal；每页明暗双主题截图自查。

### 2.8 工作台体验批次锚点（卡 G–J）

- **联系方式**：`src/components/MerchantPermissionCard.vue`——材料泛型输入 :288-293（contact_info 即 :291）、`MATERIAL_LABEL.contact_info='联系方式'` :85、现仅非空校验（missingMaterials :107-108 / submit :192 / 按钮 :308）；类型 `src/types/grassland/organization.ts:117-122` MaterialType；后端 `PermissionMaterialPolicy.java:45-60` validate 只查覆盖必填+非空（缺失 IAE→400，controller :301-304），DB `V4__add_merchant_permission_request.sql:8` materials json 自由文本。先例：前端 `src/lib/kyb-validation.ts:30` `MOBILE_PHONE_PATTERN=/^1[3-9]\d{9}$/u`（:45 isValidPhone 含座机/400 分支——**只手机号场景单用 PATTERN**）；后端 `MerchantProfileFields.java:12-14`（三个电话 Pattern，含手机号款）。IT 夹具 `PermissionRequestControllerIT.java` 约 9 处 `contact_info":"c"`（:56/:202/:216/:254/:278/:302/:362/:471/:491-492）需改合法手机号。
- **任务表单抽屉**（注意实际路径在 `src/views/grassland/components/`，非 `src/components/`）：壳**内联**于 `MerchantTaskForm.vue:6-10`（Teleport + `.task-drawer-overlay/.task-drawer`，专属样式 :604-672 约 70 行，宽 min(720px,100vw) z-80，根节点补 `.gl-field` 保田垄作用域）；关闭守卫 requestClose/Esc :517-533、脏表单三选一确认 :203-219/:486-513（z-100 盖 z-80）、焦点/锁滚/焦点归还 watch :536-559。入口 `GrasslandWorkbench.vue:796`、挂载 :801-823（三模式共用一实例）。全仓唯一消费方（grep task-drawer 仅表单+其测试）；`MerchantTaskForm.drawer.test.ts` 连坐。GlModal 先例：`TaskDetailModal.vue`（wide+scroll+插槽内容包 .gl-field :4 注释同一条教训）、`PersonalSettingsModal.vue:7`（v-if + wide scroll persistent 最贴近）；**GlModal 无 open prop**（调用方 v-if）、**无焦点/锁滚**（表单 watch 原样保留）；死 prop `hasOrganizationAccess`（:280，#77 删空选项后无消费）顺手清。
- **赏金/押金**：`MerchantTaskForm.vue:148-149` 两输入框唯一共同门 `:disabled="!canPublishBounty"`（无 readonly/其他联动）；prop 链 `GrasslandWorkbench.vue:810` ← `useWorkbenchSession.ts:102`（`activeOrg?.permissionTier==='finance_transaction'`，tier 枚举 draft/basic_publish/finance_transaction）；身份条警示先例 `OrgIdentityStrip.vue:149`。模式切换清零 `switchPaymentMode` :378-388（#75 D1 设计行为，「切回值没了」的元凶）；小数吞字根因 `@input` 内 `Number()` 强转（`Number('12.')=12`、`Number('')=0`）+ `:value` 回写；后端 tier 闸已有；drafts 提交归零双保险 `useWorkbenchTaskDrafts.ts:65-68`。#77 卡 B 未触碰这两行（git blame 核实，disabled 由远早的 b2f242e4 引入）。
- **finance 页签**：现状四块纵排 `GrasslandWorkbench.vue:1169-1207`（内联钱包 `#gl-wallet` :1177-1188 / MerchantMonthlyBillCard :1190 / MerchantCommerceCard :1194-1201（emit `create-promotion-task`→openNewTaskForm，抽屉 Teleport 到 body **不受分节隐藏影响**）/ BusinessAnalyticsPanel :1203-1205）。**org 左栏先例**：常量数组+ref `:311-325`、模板 `org-split > org-rail + org-panel` v-show 分节 `:1099-1166`、CSS `:1530-1561`（152px 栏、≤720px 塌横滚，同文件**零新增**）、分节回落 `:339-345`、锚点二级切换先例 `:675-682`（v-show 隐藏元素 scrollIntoView 失效）。测试锚：`GrasslandWorkbench.test.ts:349/:504-515/:1068-1103`（`#gl-wallet` 全 DOM 唯一断言——**必须 v-show**）。营收趋势：`BusinessAnalyticsPanel.vue:76-107` 纯表格 7 列（非图表）、内部 320px 滚动 :273 无分页、粒度切换 :79-83（DEFAULT_WINDOW_DAYS {day:30, week:84, month:365}）；端点 `GET /api/analytics/series`（`useGrasslandMarketplace.ts:33-42`；admin 版 `useGrasslandGovernance.ts:74-78`）；后端 `AnalyticsController.java:223-243`（SeriesQuery :288-345 400 桶上限、补零 :252-285）、SQL `AnalyticsRepository.java:202-256`（GROUP BY 聚合无 LIMIT）——**后端零改动**；本地分页先例 `MySessionsCard.vue:168-171` sess-pager；档位 10/20/50 对齐 FEED_LIMIT_OPTIONS 习惯；治理台 admin 模式（AdminView.vue:410-412 同组件）同份实现受益。

## 三、卡片

### 卡 A：草场减项——去积分展示 + 402 引导 + 删 AI 页签（前端，独立）

1. `DefaultLayout.vue`：删积分徽标（:60-67）、CreditsPackagesModal 挂载（:145-149）及 useCredits 引用；草场全域不再露出积分与套餐。**勿删 CreditsPackagesModal.vue 组件本体**（AI 应用在用）。
2. 402 引导：`useGrassland.run()` catch 检测 `GrasslandHttpError` 且 status===402 → error.value 统一改写为「积分不足，请前往 AI 创作中心充值」；草场任务创作面（/creation 任务模式等计费消费面）的错误展示旁给「前往充值」动作按钮（`jumpToAiApp`，未登录先弹登录——行为对齐 #77 卡 E 入口）。
3. `GrasslandWorkbench.vue`：MERCHANT_TABS 删 ai 项（商家侧剩三签）；:1209-1232 三卡挂载块删除；`?wtab=ai` 回落 tasks；canManageAiBudget 等引用清理。
4. vitest 连坐：grep 草场积分徽标/AI 页签相关用例改写。
5. 验收：草场登录（双身份）头部无积分徽标、无积分弹窗入口；wtab=ai 深链回落；任务创作 402 场景文案+跳转可用；AI 应用积分徽标与弹窗照常；明暗双主题截图。

### 卡 B：统一模型开关（后端 intelligence，先行于卡 C）

1. **存储**：新迁移（接续 intelligence 现最大 V 号，幂等）——`ai_provider_preference` 增主行约定 `capability='*'` = 模型来源总开关（照 `'*/*'` 预算全局行先例；先读 V49 确认列名）；回填：任一 per-capability useOwnKey=true 的账号插 master 行 own=true，其余插 platform 行；per-capability 行保留不删（路由不再读）。
2. **端点**：`GET /api/ai/preferences` 响应增 `{ modelSource, masterVersion }`；新增 `PUT /api/ai/preferences/model-source` `{modelSource, expectedVersion}`（乐观锁 409、非法值 400）；per-capability `PUT /api/ai/preferences/{capability}` 下线（404）。`AiProviderPreferenceControllerIT` 改写为总开关契约。
3. **路由**（`ByokRoutingService` 个人段 :90-97 重写）：master=platform → 跳过个人段直落平台段；master=own → 逐能力取个人密钥，命中→BYOK；未命中→DENIED 新原因 `own_key_missing`（HTTP 422，文案「该能力未配置自有模型密钥，请在 AI 与治理中配置或切回平台统一模型」）。组织段（orgId 非空）**一行不动**。
4. **预算豁免**（`AiExecutionService.reserveAndCreateRun` :214-229）：scope 为个人 且 resolution 为个人 BYOK → 跳过 `checkAndReserve`，ai_run 照建、计量照旧；组织 BYOK 仍照预算（组织预算是治理语义，D3 定死）。
5. **内容安全绕过**（D3 口径=凡自有凭据即绕过，含组织 BYOK）：
   - `ContentSafetyService` 编排层（:47-65）L2 前判定「按同一 exchange 主体复用 ByokRoutingService 解析 text 路由是否 BYOK」（幂等重解析，无副作用）——BYOK 则跳过 L2；**L1+SimHash 保留**；safety 帧照发并加 skipped 标识字段（向后兼容，帧结构只增不改）；8 个接入点零改动。
   - `ContentSafetyFixService`/`ContentSafetyFixController` 与手动复查 `/api/content-safety/check`：BYOK 主体返回 skipped 结构（**不 503**，503 仅保留「平台模型未配置」）。
   - 红线复述：Submission/Comment 安全闸、视频管线内部平台能力不动。
6. IT：总开关契约（GET/PUT/乐观锁/非法值）；路由三分支（own+密钥/own+缺失 422/platform 跳过）；预算豁免（own 超个人上限仍成功、组织 BYOK 仍拒）；安全绕过（own 生成 safety 帧无 L2 结果、fix/check skipped、platform 全链照旧、组织 BYOK 生成亦跳过 L2）；迁移回放幂等。
7. 验收：`./gradlew check` 全绿；curl 序列实证 D3 表格四行语义。

### 卡 C：AI 创作中心「AI 与治理」板块（前端，依赖卡 B 契约）

1. `AiCenterNavigation.vue` keys 板块 label 改「AI 与治理」（**id 保留 keys** 最小连坐；platform 模式过滤 :412-419 不变）。
2. 板块内容自上而下：
   - **模型来源卡**：platform（默认）/ own 二选一；切 own 二次确认，警示文案必须含「切换后平台内容安全深检、内容修复等免费能力将不再提供；未配置密钥的能力将不可用」；接卡 B model-source 端点（409 冲突提示照 PersonalAiBudgetCard 先例）。
   - **platform 态**：PersonalAiBudgetCard（自 runs 板块 :272-275 迁入）+「按平台计费、超限硬停」说明；runs 板块只留 AiRunHistoryPanel。
   - **own 态**：AiProviderKeysPanel 改造版——删每能力 useOwnKey 开关与计费三态标签，保留密钥 CRUD/轮换/停用/404 容错；密钥缺失能力标「未配置·不可用」并引导。
   - **商家主体治理节（条件渲染）**：壳层拉 `GET /api/me/organization-scopes` 过滤 owner/admin → 主体下拉（多主体切换）+ 三卡复用挂载（AiOrgBudgetPanel / AiOrgProviderKeysPanel / OrgCreationAuditPanel，组件零改动，props 传所选 orgId）；无角色整节不渲染。**不装载 useActiveIdentity、无全局身份切换**。
3. own 模式 UI 连坐：内容安全检查步/修复/手动复查入口隐藏或显示「自有模型模式不提供平台内容检查」（后端 skipped 兜底已兜）。
4. vitest：AiProviderKeysPanel 改造测试、板块导航、模型来源开关交互（二次确认/409）。
5. 验收：平台态预算卡可读写；own 态密钥 CRUD 可用、未配能力禁用；商家 owner 登录见主体治理三节（选主体→数据随动）、纯个人账号不见该节；safety 相关入口 own 态收口；明暗双主题截图。

### 卡 D：治理台页签归类（前端 + 测试重构，独立）

1. `AdminView.vue` 抽 **TAB_REGISTRY 单源**（key/显示名/group/roles/组件），类型联合与 TAB_ROLES 由 registry 派生；渲染改 registry 遍历 + 组件 map，**v-else audit 兜底（:460-462）消除**——audit 入 registry，未知 key 不再可能发生。
2. 两行导航：第一行分类 pill（`nav-pill-group`），第二行组内页签（`category-tab`）；组可见性 = 组内页签 TAB_ROLES 并集；默认选第一可见组第一签。**五组定案**：

| 分组（data-testid=`admin-group-{key}`） | 页签 |
|---|---|
| 审核队列 review | KYB 审核、主体更名、推荐官认证、任务审核、审判官准入、权限审核、门店媒体、公共素材 |
| 用户与主体 users-org | 用户管理、账号前缀、等级与权益 |
| 交易与财务 finance | 财务对账、积分套餐、订单核销、经营分析 |
| 内容与 AI content-ai | AI 模型、创作风格、去AI味、BGM 曲库、首页热点、视频任务 |
| 风控与审计 risk-audit | 风险调查、统一审计 |

3. 每个页签按钮加 `data-testid="admin-tab-{key}"`；`?section=` 深链（mounted 读参定位 + 切换 replaceState，非法值回落默认）。
4. **测试重构**：`AdminView.test.ts`（47KB）按下标点击处全部改 getByTestId/role name——先 grep `.admin-tabs button` / `nth(` 类定位清点再动手；全部用例绿。
5. 面板内容、OpsConsole、OpsApp、组件物理位置一律不动。
6. 验收：四角色（platform_admin/customer_service/risk/content_reviewer）各自可见分组正确（并集口径）；23 页签全可达且功能无回归；?section= 直达；`npx @google/design.md lint src/ops/DESIGN.md` 过；明暗双主题截图。

### 卡 E：素材上传 413 修复（nginx + 前端文案，独立，小卡）

1. `nginx.conf:427-473`（9002 MinIO 预签名反代块）`location /` 内加 `client_max_body_size 32m;`（根因修复；32m 对齐 /api 现值并覆盖语音 25MB 帽）。
2. `grassland-http.ts:115-124` `putToPresignedUrl` 按状态码分文案：413 →「文件过大，超出大小上限（图片 ≤10MB / 视频 ≤20MB）」；403 → 保留「上传凭据（预签名链接）可能已过期，请重试」；其余 →「附件上传失败（${status}），请重试」。一处修，5 个 composable 调用面全覆盖。
3. vitest：putToPresignedUrl 三分支（mock fetch 返回 413/403/500）。
4. 后端、MEDIA_MAX_OBJECT_BYTES、前端预检上限均不动。
5. 验收：**重打前端镜像**（nginx.conf 在镜像内）起栈后，>1MB（建议 5MB）图片经素材库「添加素材」上传成功（修复前 413）；>20MB 前端预检拦截；手动构造过期票据验证 403 文案。

### 卡 F：身份证校验修复（前端 + 后端 identity，独立，小卡）

1. 前端 `src/lib/kyb-validation.ts`：删 15 位分支（:73-79）；文案 :25 改「请输入有效的身份证号（18 位）」；地址码校验（:82 与 isValidIdAreaCode :151-153）降级为省级前缀白名单——前 2 位 ∈ {11,12,13,14,15,21,22,23,31,32,33,34,35,36,37,41,42,43,44,45,46,50,51,52,53,54,61,62,63,64,65,81,82}。`kyb-id-area-codes.ts` 整文件退役（内联省级集合，删 ~270 行死数据），连坐 import 清理。
2. 后端 `MerchantProfileFields.java`：删 LEGACY_ID（:18）与 15 位分支（:89-94）；hasValidAreaCode（:111-113）同步降级省级；文案 :83 改「法人身份证号格式无效，请输入 18 位身份证号」；`ChineseIdAreaCodes.java` 退役（注意删其中的数量断言）。前后端规则逐条等价（现状即如此，保持）。
3. **不动**：校验位算法（权重/校验码核对无错）、出生日期校验、toUpperCase/trim、输入框属性、DB schema、密文存储与掩码回显；存量 15 位「输入留空=沿用」不卡提交、不洗数据。
4. 测试连坐：`kyb-validation.test.ts`——原 15 位「接受」用例（:76-80/:91/:99）**反转为拒绝**；:87 旧黑龙江区划码「拒绝」用例**反转为接受**（历史码 18 位合法即过）；新增省级外前缀（如 99）拒绝。`MerchantKybCard.test.ts` 同步。`MerchantProfileControllerIT.java:126-152` 的 15 位样本 `110105491231002` 改 18 位（如 `11010519491231002X`），并补 15 位→400 用例。前后端用例样本对齐同一组号码。
5. 验收：真实历史区划 18 位号（如 4127xx/510221 开头 + 合法校验位，用校验函数生成）前后端均通过；15 位前后端均 400/表单报错；文案已更新；KYB 全流程（填写→提交→治理台审核掩码回显）无回归。

### 卡 G：权限材料联系方式手机号校验（前端 + 后端 identity，独立小卡）

1. 前端 `MerchantPermissionCard.vue`：仅对 `contact_info` 材料加**手机号**校验（申诉路径复用同表单，自动生效）——单用 `MOBILE_PHONE_PATTERN`（11 位、1[3-9] 开头，**勿用含座机/400 的 isValidPhone**）；错误文案「请输入有效的手机号（11 位）」并入 :301 提示区、:192 submit 守卫、:308 按钮 disabled；placeholder 改「填写联系方式（11 位手机号）」+ `maxlength="11"` + `inputmode`。
2. 后端 `PermissionMaterialPolicy.validate`（:45-60）：非空检查后追加 `contact_info` 手机号校验（复用 `MerchantProfileFields` 既有手机号 Pattern，或抽 mobile-only 静态方法共用），非法抛 IAE（现有 400 处理器兜住）；顺带给全部材料值统一长度帽 128。
3. 测试连坐：`PermissionRequestControllerIT.java` 约 9 处 `contact_info":"c"` 夹具改合法手机号；补「非手机号→400」用例；前端补格式校验用例。
4. 验收：字母/座机/邮箱/位数不对→前后端均拦；11 位手机号通过；治理台审核侧展示正常。

### 卡 H：发布新任务抽屉改居中弹窗（前端，独立）

1. `MerchantTaskForm.vue`：抽屉壳（:6-10 结构 + :604-672 样式）整体替换为 `<GlModal v-if="open" :title="drawerTitle" wide scroll persistent @close="requestClose">`；插槽内容包 `.gl-field` 保田垄样式作用域（TaskDetailModal 同款）；底部操作条迁 `#actions` 插槽（滚动体外常驻可见）。
2. 行为保留：`@close` 接 `requestClose` 保住脏表单三选一确认（GlModal 的 × 与 Esc 都走 emit close，persistent 短路遮罩误触）；初始焦点/背景锁滚/焦点归还 watch（:536-559）原样保留（GlModal 无此三样）；三选一确认框**单独 `<Teleport to="body">` 提层**（原靠 z-100 盖 z-80 抽屉，迁移后需独立层，唯一新写点）。
3. 清理：`.task-drawer-*` 样式全删；死 prop `hasOrganizationAccess`（:280）删；`MerchantTaskForm.drawer.test.ts` 连坐改锚（壳选择器/结构断言按 GlModal 重写）。
4. 验收：三模式表单在弹窗内完整可用（wide=960px、scroll=72vh）；Esc/× 触发三选一确认而非直接关闭；发布/草稿/修订链路无回归；明暗双主题截图。

### 卡 I：赏金/押金可解释化 + 输入修复（前端，独立）

1. `MerchantTaskForm.vue:148-149`：`!canPublishBounty` 时两框**保持 disabled**，框下新增提示条「发布赏金/押金任务需先升级到资金交易权限」+「去升级」按钮（emit 到 workbench：`subTab='org'` + `orgSection='permission'` 并关闭表单）；身份条既有警示（OrgIdentityStrip.vue:149）不动；后端 tier 闸门不动。
2. 小数输入修复：赏金/押金表单态改存**原始字符串**（去掉 `@input` 内 `Number()` 强转），仅提交/校验时转换——输入 `12.`、全选清空不再被回写成 `12`/`0`。
3. 模式切换不再清值：`switchPaymentMode`（:378-388）删两行归零（提交链路 `useWorkbenchTaskDrafts.ts:65-68` 双保险已兜底）。
4. 验收：basic_publish 主体开表单→两框灰死+提示可见+「去升级」直达权限分节；finance_transaction 主体正常填写含小数；commission↔freebie 切换再切回**值保留**；提交后非激活模式字段仍不落库（drafts 归零验证）。

### 卡 J：资金与经营二级分栏 + 营收趋势分页（前端，独立）

1. `GrasslandWorkbench.vue` finance 页签照 org 左栏模式改造：`FINANCE_SECTIONS = [account 资金账户（默认）| bill 月度账单 | commerce 到店套餐与核销 | analytics 经营分析]` + `financeSection` ref（镜像 :311-325）；模板 :1175 `gl-zone-body` 内改 `org-split > org-rail + org-panel`，四块各包 `v-show` 分节（**必须 v-show**：`#gl-wallet` 测试断言 + 组件不重挂载不重拉数据）；CSS 零新增（org-rail 系同文件复用）；`watch(activeOrgId)` 分节回落默认（照 :339-345）。
2. 锚点联动：通知锚 `gl-wallet` 加二级分节映射，`watch(grasslandAnchor)`（:675-682）先切 subTab 再切 financeSection 再滚动（v-show 隐藏元素滚不动，org 先例同款处理）。
3. `BusinessAnalyticsPanel.vue` 营收趋势表加**前端本地分页**：`total = buckets.length` 已知，computed slice 翻页 + 每页 10/20/50（sess-pager 先例 `MySessionsCard.vue:168-171`）；粒度切换/重新查询/模式切换时页码归零；去掉 `.series-table` 320px 内滚（:273，分页后无双滚动）；admin 模式同份实现受益；**后端零改动**。
4. 测试连坐：`GrasslandWorkbench.test.ts`（finance 断言 :349/:504-515/:1068-1103 v-show 保绿 + 补分节切换用例）；BusinessAnalyticsPanel 测试补分页交互。
5. 验收：四分节切换正确、切节不重拉数据；`#gl-wallet` 通知锚点可达；营收趋势翻页/换档位/换粒度页码归零；MerchantCommerceCard 的「创建推广任务」emit→任务弹窗（卡 H 产物）仍可用；明暗双主题截图。

## 四、集成验收

1. 后端门禁：`./gradlew check` 全绿（intelligence 新 IT + identity IT 改写 + 迁移回放幂等）。
2. 前端门禁：vitest 全量（AdminView 大改 + AiProviderKeysPanel/kyb-validation/grassland-http 连坐）+ e2e CI 绿。
3. 部署：**前端镜像必重打**（nginx.conf 变更，Dockerfile.frontend:12）+ Java 服务 jar 重打（疑似旧 jar 就 clean，unzip+grep -a 内容探针验真）→ compose 起栈。
4. 手工冒烟清单：
   - 草场双身份登录：头部无积分徽标、商家侧无 AI 页签（三签）；AI 应用积分照常；
   - 模型来源开关：platform 预算卡生效 → 切 own（确认弹窗警示可见）→ 未配密钥能力禁用 → 配密钥后生成成功、不扣积分、safety 帧无深检 → 切回 platform 全恢复；
   - 商家 owner 在 AI 应用见主体治理三节并可用；纯个人不见；
   - 治理台四角色分组导航正确、?section= 直达、23 签无回归；
   - 5MB 素材上传成功（413 已修）、403/超限文案正确；
   - 历史区划 18 位身份证通过、15 位被拒；
   - 权限申请联系方式：非手机号（字母/座机/邮箱）前后端均拦、11 位手机号通过；
   - 发布新任务居中弹窗、三模式可用、脏表单三选一确认保留；
   - basic_publish 主体赏金/押金灰死且有提示+「去升级」直达权限分节；finance_transaction 主体可填含小数、模式切换回切值保留；
   - finance 四分栏切换正确、gl-wallet 锚点可达、营收趋势分页可用、套餐卡「创建推广任务」仍弹任务表单。
5. 截图自查：AI 治理板块（platform/own/商家三态）、治理台分组导航、KYB 提示、任务发布弹窗、资金四分栏——明暗双主题各一张。

## 五、执行顺序

独立小卡/前端卡先行并行：卡 E、F、G（后端+前端小卡）与卡 H、I、J（纯前端，J 依赖 H 的弹窗产物做验收但不阻塞开发）→ 卡 A（独立）→ 卡 B（后端，卡 C 的契约前置）→ 卡 C（依赖 B）→ 卡 D（独立、测试量最大，可全程并行推进）。Qoder 完成声明需附每卡验收证据（尤其卡 B 的 curl 序列表与卡 D 的四角色截图）；完成后由我方复核门禁与冒烟。
