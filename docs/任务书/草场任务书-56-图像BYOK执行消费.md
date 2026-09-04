# 草场任务书 #56：图像 BYOK 执行消费

> 来源：2026-08-30 baoyu-skills 适配调研线 B 拍板——「图像 BYOK 消费急」。目标：让「模型密钥」面板里可存可管但**从不被消费**的 image_generation 键真正参与生图路由。现状实锤：`AiExecutionService.preparePlatformAsyncExecution` 硬性要求平台 provider（AiExecutionService.java:170-172），图像/视频生成调用方一律手拼 `ProviderResolution.platform(...)`——键可存可管、执行不消费，是 #47 登记遗留。方言层设计参照 baoyu-skills 的 baoyu-codex-imagegen（11 家 provider 适配：size/ar/quality/参考图方言 + 重试/并发/缓存），本轮只落 OpenAI 兼容方言，其余登记。
> 状态：**已立项待实现**。
> 前置：无硬前置；与 #54 正交（#54 落地后其 S1.4 单点替换即自动受益）。
> 规模定性：执行环改造——1 个服务方法翻新（平台行为逐字节不变 + 新增 BYOK 分支）、1 个客户端参数化、快照冻结源头改路由解析、3 个调用点翻新。**预计无新 DDL**（ai_run 已有 byok_organization_id/credential_version 列；若实现中发现需加列，一律 ADD COLUMN IF NOT EXISTS）。

## 决策表（2026-08-30 拍板）

| # | 决策 | 选择 |
|---|---|---|
| A | 范围 | 仅 `image_generation` capability；`video_generation`（MiniMax 提交/轮询/webhook/对账链）登记边界单独立项。方言只支持 **OpenAI 兼容 `/images/generations`**（与文本 BYOK 同一假设：用户配 baseUrl 指向兼容端点）；DashScope 原生异步/即梦/Seedream 等方言后续按 baoyu-codex-imagegen 参照扩 |
| B | 执行环 | `preparePlatformAsyncExecution` 翻新为 **BYOK 感知**：平台分支行为逐字节不变（冻结 cents 预留/结算）；BYOK 分支 estimatedCents 强制 0、feature=null **不扣积分**（D-11 红线）、密钥解密入 ExecutionContext、用量入预算（0 cents + images 计量） |
| C | 独立模式接入 | `generate-image` 非任务分支从 legacy 免费路径翻新为执行环：`resolveProvider(org, account, "image_generation", allowFallback=true)`（D9 身份分叉原样复用）→ BYOK 零扣 / 平台预算闸 cents。**行为变化显式登记**：独立生图从「无闸」变「预算闸」（不扣积分——image_generation 无积分键惯例不变，仅 cents 预算口径） |
| D | 任务模式冻结 | 快照 imageGeneration 段（`CreationContextService.java:150` 组装处）改存**解析后 provider**：BYOK 命中 → `{type:'byok', provider, model, baseUrl, keyVersion}`；平台 → 现有六字段不变。校验（`FrozenImageGenerationConfigResolver.resolve`）按 type 分叉：byok 比对当前活跃 key 的 keyVersion，轮换 → 409「密钥已轮换，请重新开始创作」（与文本冻结语义一致）；platform 指纹比对不动 |
| E | 客户端参数化 | `ImageGenerationClient.generate(prompt, size, endpoint)` 增 endpoint `{baseUrl, apiKey, model}`；从 ExecutionContext 的 decryptedKey + provider.baseUrl()/model() 传入；静态 config 保持平台 env bootstrap 兜底 |
| F | 前端 | 模型密钥面板结构不动（image_generation 四开关之一已有）；仅加说明文案：图像密钥须为 OpenAI 兼容 /images/generations 端点。运行记录/用量面板自然生效（ai_run） |
| G | 调用点 | `TaskImageGenerationService`（手拼平台 → 冻结解析）、`ArticleImageService.generate` 独立分支（免费 → 执行环）、`PublicAssetBatchGenerationService`（手拼 → 控制面解析）三处翻新；`VideoGenerationService` 不动 |

## 模型与关键技术真相（动手前必读）

1. **唯一硬闸**：`AiExecutionService.java:170-172` `!provider.isPlatform()` 即抛 IllegalArgumentException。私有链 `reserveCreateAndCharge` 在 `prepareExecution` 里**已完整支持 BYOK**（estimatedCents=0、decrypt、billablePlatformUsage=false、预算用量入账）——B 决策是复用既有语义，不是新造。
2. **路由层零改动**：`ByokRoutingService.resolveProvider` 按 capability 泛化（`findByPersonalAndCapability` 等查询直通 image_generation）；D9 身份分叉（merchant 组织 > 平台；推荐官/消费者个人 > 平台）、组织回退策略 `fallback_not_authorized`、个人开关 `isOwnKeyEnabled` 对图像全部自然生效。解密 `ProviderKeyDecryptor.decryptIfNeeded` 泛化复用。
3. **客户端是单方言单静态端点**：`ImageGenerationClient.java:32-37` 读静态 `config.apiKey()/baseUrl()/model()`——**连任务模式实际 HTTP 也走静态 env 端点**（冻结配置只管校验与计价）。E 决策参数化是 BYOK 消费的必要条件。
4. **快照组装/校验锚点**：`CreationContextService.java:150` `complete.put("imageGeneration", imageGenerationConfig.snapshot())`；`FrozenImageGenerationConfigResolver.java:33-46` 指纹含 apiKey——byok 分支不能沿用该指纹（个人/组织 key 独立轮换），改 keyVersion 语义（D 决策）。
5. **平台控制面**：image_generation 平台回退经 `PlatformModelControlPlaneService.resolve` 已有；V51 平台凭据密文随解析下传、credentialVersion 冻结 ai_run（#47 D7）对图像同样生效。
6. **积分口径**：CreditFeature 无 image_generation 键（图像走 cents 预算口径惯例）；BYOK 图像 0 cents 0 积分与 D-11 文本语义完全一致。
7. **参考图 describe 保持免费**：`ArticleImageService.describe`（:97-106）走 `routed.completeFor` 免费惯例——独立分支翻新时该步骤不动，只翻新生图调用本身。

## S1 · 后端

1. **AiExecutionService**：`preparePlatformAsyncExecution` 翻新为 BYOK 感知（或新增 `prepareMediaExecution` 并迁移调用点，二选一，倾向直接改净不留双入口）：BYOK 时断言 estimatedCents==0 且 feature==null，走既有 reserveCreateAndCharge（billablePlatformUsage=false）+ decrypt；平台逻辑逐字节不变。
2. **ImageGenerationClient 参数化**：`generate(prompt, size, endpoint)`；endpoint 缺省回落静态 config（平台 env bootstrap 路径不变）。超时/错误映射惯例保持。
3. **TaskImageGenerationService**：provider 来源改冻结解析（`FrozenImageGenerationConfigResolver` 新 byok 分支返回的 ProviderResolution）；执行走翻新后的媒体入口；结算 BYOK 用 `settleSuccessWithCost(ctx, 0, 0, 0, 1, 0)`（images=1 计量）。
4. **ArticleImageController.generateJson/generateMultipart 非任务分支**：`resolveProvider(org, account, "image_generation", true)` → 翻新后媒体入口 → 参数化客户端 → settle/handleFailure；DENIED（fallback_not_authorized/no_platform_model）转既有 403 语义。响应契约（`/generated-images/{id}` URL + registerGeneratedMedia + moderateGeneratedAsync）不变。
5. **CreationContextService + FrozenImageGenerationConfigResolver**：D 决策双分支（组装存解析后 provider；校验 byok 比对当前活跃 keyVersion——需经 keyRepository 重查当前 key 的版本，不一致 409）。
6. **PublicAssetBatchGenerationService**：平台解析改经控制面 resolve（不再手拼），走翻新后媒体入口；逐张预算闸/部分成功语义不变。
7. **IT**：
   - ① 个人 BYOK 图像键 → 独立生图命中（ai_run provider/resolution 断言、零积分扣减、预算用量记录）；
   - ② 组织键（商家身份）命中；组织配键且策略禁回退 → `fallback_not_authorized` 403；
   - ③ keyVersion 轮换后，任务模式进行中创作 409；
   - ④ 无键平台回退回归（行为与现状逐字节一致：URL 契约、ai_run、计价）；
   - ⑤ 平台独立生图进入预算闸（配低预算 → `exceeds_run_budget` 402；若环境默认无预算限制，则显式配预算后验证）；
   - ⑥ PublicAssetBatchGeneration 链回归（批量审核流不受影响）。

## S2 · 前端

- 模型密钥面板（AI 中心「模型密钥」）image_generation 区：加说明文案「图像密钥需为 OpenAI 兼容 /images/generations 端点（填 baseUrl）」；baseUrl 输入加轻校验提示（非 http(s) 或含空格 warn）。
- 运行记录面板确认 BYOK 图像 run 可见（provider 列、0 成本展示）。
- vitest：面板文案渲染；API 层如类型有变同步适配。

## S3 · 门禁与实测

- 前端 vitest + typecheck + build；intelligence IT 全量（重点回归 articleimage/contentlibrary 两包既有用例——响应契约不变是红线）。
- 本地栈重建后浏览器实测双身份（e2e-seed）：
  1. 推荐官注册个人图像键（指向测试用 OpenAI 兼容端点）→ 图片生成 studio 出图 → 运行记录 BYOK run（0 积分）。
  2. 商家组织键同流程；关闭个人开关/组织策略场景抽查。
  3. 无键账号平台回退出图（与现状体验一致）。
  4. 治理台预算/用量面板看到 BYOK 图像用量入账（0 cents）。
  5. 明暗截图留档。

## 验收清单

1. 注册 image_generation BYOK 键（个人与组织各一）后，**独立与任务生图均命中 BYOK**：ai_run 留痕、零积分、预算用量入账。
2. 组织配键且策略禁回退时，无该 capability 键的请求被拒（不静默扣平台额度）；个人开关 off 同理。
3. 密钥轮换后进行中的任务模式创作 409 提示重开；独立模式不受进行中状态影响。
4. 平台回退路径行为与现状一致（URL/计价/ai_run/审核全链回归零变更）。
5. 独立生图进入预算闸（耗尽 402）；三条翻新链（任务生图/独立生图/公共素材批量）全绿。
6. 门禁全绿；双身份实测截图留档。

## 已知边界（本轮不做）

- video_generation BYOK 消费（MiniMax 提交/轮询/webhook/对账链复杂，单独立项）。
- 非 OpenAI 兼容方言（DashScope 原生异步任务、即梦、Seedream；baoyu-codex-imagegen 的 provider 适配矩阵为参照，含其 parser/validator/幂等缓存设计）。
- image-to-image 参考图直传（`/images/edits` 方言，与 #54 的 D 决策边界联动）。
- 平台指纹含 apiKey 的既有设计（本轮只加 byok 分支，不动平台分支指纹语义）。
- 3:4 等新尺寸比例（方言议题随边界二）。

## 实现红线（历史教训，逐条自查）

- BYOK 分支**绝不扣积分、绝不 consume/reserveUsage**（D-11）；明文密钥只活在 ExecutionContext，不入日志/响应/outbox（D-10 §PII）。
- 平台分支行为逐字节不变——回归测试先写后改。
- `switchIfEmpty` 参数与副作用调用包 `Mono.defer`。
- 派生态 is 前缀无参方法 `@JsonIgnore`；请求 record 可选数值用包装类型。
- 若需 DDL 一律 ADD COLUMN IF NOT EXISTS；迁移号冲突顺延取空号。
- 自建 WebTestClient 必带 30s responseTimeout。
