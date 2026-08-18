# #33 语音识别与 Embedding/语义检索设计

日期：2026-08-18

状态：已实现（Sandbox-first，2026-08-19）

> 实现验证命令（branch `codex/issue-33-speech-semantic-retrieval`）：
> `cd platform-java && JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./gradlew :services:intelligence-service:test :services:edge-bff:test`；
> 前端 `npm test` / `npm run typecheck` / `npm run build`。实现记录见 `docs/草场开发进度与续接指南.md` Slice 25。

范围：PRD §4.10 中尚未落地的 `voice` 与 `retrieval` 两项能力

## 1. 背景与目标

当前 Java 主线已经具备模型控制面、BYOK 路由、组织预算、并发限制、`ai_run` 审计、积分补偿、媒体三步直传和三类内容素材库。`platform_model_config` 也已经预留 `voice` 与 `retrieval` capability，但尚无实际 Provider、消费 API、领域数据或前端入口。

本功能采用 **Sandbox-first**：先交付完整、可替换的业务闭环，不在本阶段绑定真实语音或 Embedding 厂商。首版必须能验证上传、权限、模型路由、运行审计、预算边界、失败补偿、索引版本、语义排序、前端状态和 Edge fail-closed；Sandbox 结果必须明确标识，不能伪装成真实识别或生产级语义质量。

目标如下：

1. 登录用户可以上传受控音频并执行一次语音转写，得到可复制的持久化结果。
2. 素材库可以对标题、标签、分类及文本元数据建立版本化向量索引。
3. 现有 `/api/content-assets/recommendations` 可以消费任务文本或显式查询文本，返回权限范围内的语义排序和可解释原因。
4. 两种能力都经过 `AiExecutionService`，记录 capability、Provider、模型、配置版本、状态、用量和成本边界。
5. Provider 接口与业务编排解耦，后续接入真实厂商不改变公开 API 和领域表语义。

## 2. 非目标

本阶段明确不做：

- 接入或联调真实语音、Embedding 厂商。
- 提供浏览器直连第三方接口或向前端返回第三方密钥。
- 引入 PostgreSQL `vector` 扩展、pgvector 索引或独立向量数据库。
- 对视频自动抽音轨后转写；首版只接受专用音频媒体。
- 说话人分离、逐字时间戳、字幕文件生成、实时流式识别和自动翻译。
- 对图片像素、音频内容或视频内容生成多模态 Embedding；首版只索引素材的文本元数据。
- 建立新的平行素材搜索 API；语义能力扩展既有推荐接口。
- 承诺 Sandbox 的识别准确率或同义词召回质量。

## 3. 已有架构锚点

- `intelligence-service` 是语音、模型控制面、媒体和素材库的唯一领域服务。
- `MediaController` 已实现预签名票据、对象上传确认、配额、MIME/大小快照和 owner 隔离。
- `ContentAssetController` 已公开 `/api/content-assets/recommendations`；`ContentAssetRecommendationService` 当前先构造调用者可访问候选池，再使用确定性规则排序。
- 可访问候选仅来自本人个人库、当前账号被授权的商家素材、当前商家组织素材和有效公共素材。语义检索不得改变这套集合语义。
- `AiExecutionService` 已统一预算检查、模型解析、平台积分、BYOK 密钥解密、Run 创建、成功结算、失败退款和取消处理。
- intelligence Flyway 当前最新为 `V30`；实现阶段使用后续独立迁移，不改写既有迁移。
- Edge 对未登记路由、method 不匹配和关闭的 flag 统一返回 404，不存在旧后端回切。

## 4. 总体架构

新增两个彼此独立的 Provider 端口：

```text
Speech API
  -> media owner/state/type validation
  -> AiExecutionService(capability=voice)
  -> SpeechRecognitionProvider
  -> speech_transcription

Content asset mutation
  -> embedding pending row
  -> bounded background indexer
  -> AiExecutionService(capability=retrieval)
  -> EmbeddingProvider
  -> content_asset_embedding

Content asset recommendations
  -> authoritative accessible candidates
  -> query Embedding through AiExecutionService
  -> current-version vector join
  -> cosine score + existing deterministic score
  -> explained response
```

`SpeechRecognitionProvider` 只负责把已验证的音频输入转换为结构化转写结果；`EmbeddingProvider` 只负责把规范化文本转换为固定维度向量。Controller 不按 Provider 分支，领域表也不保存厂商专属响应。

首版内置：

- `SandboxSpeechRecognitionProvider`：读取受控媒体，基于媒体校验和与请求语言产生稳定的、明确带 Sandbox 标记的转写文本。它验证链路，不声称理解真实音频内容。
- `SandboxEmbeddingProvider`：对规范化 token 做稳定哈希投影并 L2 归一化，输出固定 256 维向量。相同文本和相同模型版本必须得到逐元素相同的结果；有共同 token 的文本应比完全无关文本更接近。

模型控制面为 `voice` 和 `retrieval` 提供内置 `sandbox` 平台配置；Provider 名分别为 `sandbox`，模型名分别为 `sandbox-speech-v1` 与 `sandbox-embedding-v1`，配置版本照常冻结到 `ai_run`。如果管理员或 BYOK 路由选择了当前未安装的真实 Provider，执行必须返回 `unsupported_provider`，产生失败审计并按既有规则退款，不能静默退回 Sandbox，除非该 Run 已明确授权 fallback。

## 5. 语音识别

### 5.1 媒体与输入约束

`MediaPurpose` 新增 `SPEECH_AUDIO("speech_audio")`。浏览器继续使用现有三步流程：

1. `POST /api/media/upload-tickets`，purpose 为 `speech_audio`。
2. 浏览器直接 PUT 到对象存储的预签名地址。
3. `POST /api/media/{id}/confirm` 激活媒体。

允许的 MIME 为 `audio/mpeg`、`audio/mp4`、`audio/wav`、`audio/x-wav`、`audio/webm` 和 `audio/ogg`；大小为 1 字节至 25 MiB。媒体确认后，语音服务使用服务端媒体探测能力读取实际时长，最长 15 分钟。客户端声明的 MIME、大小或时长都不是授权事实；对象存储 HEAD、媒体签名及服务端探测不符时拒绝执行。

只有媒体 owner 可以发起转写。媒体必须同时满足：purpose=`speech_audio`、status=`active`、未过期、未删除、MIME 白名单、大小与时长限制。任一条件不符统一按受控 400/404 处理，不泄露其他账号媒体是否存在。

### 5.2 公开 API

新增登录态 API：

- `POST /api/speech/transcriptions`
  - 请求：`{ mediaId: UUID, language?: string }`
  - `language` 省略时为 `auto`；显式值使用受控 BCP-47 子集，首版接受 `zh-CN`、`en-US` 和 `auto`。
  - 成功：`201`，返回完整转写记录。
- `GET /api/speech/transcriptions/{id}`
  - 仅 owner 可读；不存在和非 owner 均返回 404。

响应字段：

```json
{
  "id": "uuid",
  "mediaId": "uuid",
  "status": "completed",
  "text": "[Sandbox] ...",
  "language": "zh-CN",
  "durationMs": 12000,
  "provider": "sandbox",
  "model": "sandbox-speech-v1",
  "modelVersion": 1,
  "aiRunId": "uuid",
  "sandbox": true,
  "createdAt": "2026-08-18T00:00:00Z",
  "completedAt": "2026-08-18T00:00:01Z"
}
```

不返回对象 key、预签名 URL、原始 Provider 响应、BYOK 信息或明文密钥。失败响应使用稳定的业务错误码与中文 message；数据库仅保存脱敏后的 `failure_code`，不保存可能包含密钥或上游响应体的异常全文。

### 5.3 执行与持久化

新增 `speech_transcription`：

| 字段 | 语义 |
|---|---|
| `id` | 转写 ID |
| `media_reference_id` | 逻辑媒体引用，不建立级联 FK |
| `owner_account_id` / `organization_id` | owner 与预算归属快照 |
| `requested_language` / `detected_language` | 请求与结果语言 |
| `duration_ms` | 服务端探测时长 |
| `status` | `processing/completed/failed` |
| `transcript_text` | 成功结果；失败为空 |
| `provider` / `model` / `platform_model_version` | 执行快照 |
| `ai_run_id` | 对应 `ai_run` |
| `failure_code` | 脱敏业务错误码 |
| 时间列 | 创建、更新、完成时间 |

执行顺序：

1. 解析登录用户并完成媒体权限与格式校验；校验失败不建 Run、不扣积分。
2. 创建 `processing` 转写记录。
3. 调用 `AiExecutionService.prepareExecution`，capability=`voice`。
4. 调用解析出的 `SpeechRecognitionProvider`。
5. 成功结果必须先可靠写入，再把 Run 结算为 completed；持久化失败按 Provider 执行失败处理，不能出现“Run 已完成但转写结果不存在”。
6. Provider 或落库失败时把转写标为 failed，并调用既有 Run 失败/退款闭环。

公开 POST 首版是单次请求响应，不引入不可靠的进程内 fire-and-forget。前端仍展示处理中状态；真实 Provider 如果将来需要长任务，应在保持转写资源与 GET 契约不变的前提下增加持久化队列和 `202`，不改变本阶段数据模型。

### 5.4 计费口径

Sandbox 模型价目为 0 分，不扣用户积分，但仍执行预算检查并记录 `ai_run.actual_cents=0`。这样可以验证控制面和审计，又不会为模拟结果收费。真实 Provider 上线前必须单独确定 voice 计价单位和积分功能键，不能复用文本 token 假计价。

## 6. Embedding 与语义检索

### 6.1 索引文本

每个 active 素材的索引文本按固定格式构造：

```text
title: <title>
category: <category db value>
tags: <按规范化后排序的 tags>
source: <public source，可空>
license_scope: <public license scope，可空>
```

规范化规则为 Unicode trim、连续空白折叠、ASCII 小写、空字段省略；不读取对象存储中的文件内容。规范化文本计算 SHA-256 `content_hash`，用于幂等和陈旧判断。素材 `version`、模型 ID、平台模型版本、Embedding 算法版本或 `content_hash` 任一变化，都需要新索引；旧 ready 行改为 stale，但保留审计。

### 6.2 索引表与状态机

新增 `content_asset_embedding`：

| 字段 | 语义 |
|---|---|
| `id` | 索引记录 ID |
| `asset_id` / `asset_version` | 素材及其业务版本 |
| `content_hash` | 规范化索引文本哈希 |
| `status` | `pending/processing/ready/failed/stale` |
| `provider` / `model` / `platform_model_version` | 模型快照；平台版本可空 |
| `model_version_key` | 非空路由版本键，如 `platform:1` 或 `byok:<keyVersion>` |
| `algorithm_version` | Sandbox 或真实 Adapter 的向量语义版本 |
| `dimensions` | 向量维度，首版为 256 |
| `embedding` | JSONB 数值数组；pending/failed/stale 可为空 |
| `ai_run_id` | 建索引使用的 Run |
| `failure_code` / `attempt_count` | 脱敏失败与有界重试 |
| 时间列 | 创建、开始、完成、下次重试时间 |

唯一约束覆盖 `asset_id + asset_version + provider + model + model_version_key + algorithm_version + content_hash`，保证同一索引语义幂等。唯一键不直接使用可空的 `platform_model_version`，避免 PostgreSQL 的 NULL 唯一语义产生重复索引。数据库 CHECK 约束状态值、非负重试次数，并约束 ready 行必须具有向量、正维度和完成时间。

素材创建、编辑、审核为 active 或模型版本变化时，只在同一业务事务中 upsert 一条 pending 索引意图，不同步等待 Embedding。定时 dispatcher 使用数据库原子 claim 把 pending/到期 failed 行切到 processing，单批有界，进程崩溃后的超时 processing 可被重新 claim。失败采用有上限的退避重试；超过上限保留 failed，素材 CRUD 和现有规则推荐仍可用。

启动后的后台扫描负责补齐没有当前索引的 active 素材，因此上线前存量数据无需一次性阻塞迁移。扫描和 dispatcher 都必须有批量上限、并发上限和 feature flag；不得在每次请求中无界全表回填。

每次索引执行经过 `AiExecutionService`，capability=`retrieval`，并把 Run 关联回索引行。Sandbox 索引成本为 0、不扣用户积分；真实 Provider 的批量计价在接入时另立价目规则。

### 6.3 查询 API 与兼容性

不新增平行搜索端点，扩展现有：

`GET /api/content-assets/recommendations`

新增可选查询参数：

- `query`：显式自然语言查询，trim 后 1 至 500 字符。

语义文本来源：

1. 独立模式：使用显式 `query`；未传时保持当前纯规则推荐。
2. 任务模式：显式 `query` 优先；未传时使用 Marketplace 返回的权威任务标题、描述和要求构造语义文本，不能信任前端任务 JSON。

已有参数 `applicationId/taskId/platform/contentForm/category/keywords/limit` 及其语义保持兼容。`keywords` 继续参与现有规则评分，不偷偷改写为自然语言 query。

响应在现有结构上增量增加：

```json
{
  "items": [{
    "id": "uuid",
    "score": 82,
    "ruleScore": 70,
    "semanticScore": 90,
    "reasons": ["语义匹配 90", "标签命中“开业”"]
  }],
  "query": {
    "platform": "xiaohongshu",
    "contentForm": "image-text",
    "category": "campaign",
    "terms": ["开业"],
    "semantic": {
      "status": "applied",
      "provider": "sandbox",
      "model": "sandbox-embedding-v1",
      "sandbox": true
    }
  }
}
```

`semantic.status` 取 `not_requested/applied/fallback`。fallback 时不返回伪造的 `semanticScore`，并在 `semantic.message` 提供面向用户的简短原因；不返回向量、内容哈希、内部候选数量或 Provider 原始错误。

### 6.4 授权与候选上限

授权顺序是不可变安全约束：

1. 使用现有 repository/grant/组织身份逻辑构造调用者可访问素材 ID 集合。
2. 只为这批 ID 读取当前版本 ready 向量。
3. 在 Java 中计算相似度和融合分数。

不得先对全库向量搜索再在结果页过滤。这样即使得分、耗时或数量可观察，也不会把他人个人素材、未授权商家素材、无权门店素材或过期公共素材带入计算。

首版每次最多处理 500 个已授权候选，候选在 repository 层按当前既有可见性和更新时间确定性截断，最终响应 `limit` 仍沿用当前 1 至 50。超过这个规模后的 pgvector/ANN 优化属于后续基础设施演进，不影响公开契约。

### 6.5 相似度与融合排序

Sandbox 和后续 Provider 的向量在写入前必须校验：维度与 Provider 声明相同、所有元素有限、向量范数非零。非法向量视为 Provider 失败，不能入 ready 状态。

余弦相似度从 `[-1, 1]` 归一化为 0 至 100：

```text
semanticScore = round(clamp((cosine + 1) / 2, 0, 1) * 100)
```

有当前语义向量时：

```text
finalScore = round(0.60 * semanticScore + 0.40 * ruleScore)
```

单个素材缺少当前向量时，仅保留其既有 `ruleScore`，并以 `round(0.40 * ruleScore)` 参与同一次融合排序，避免把“未索引”误报为“语义不相关”。查询 Embedding 整体失败、模型不可用或预算拒绝时，整次请求回退到当前规则排序，`score` 与上线前完全同口径，`semantic.status=fallback`。

最终稳定排序键为 `finalScore DESC, ruleScore DESC, updatedAt DESC, id ASC`，相同输入和相同数据必须得到相同顺序。

## 7. 前端设计

### 7.1 AI 中心语音入口

`AiCreationCenter` 增加登录后可见的“语音转写”分栏，使用独立 `SpeechTranscriptionPanel`：

- 单文件音频选择与上传；accept 与服务端 MIME 白名单一致。
- 展示文件名、大小、上传进度和移除操作。
- 上传确认完成后才允许“开始转写”。
- 转写中禁用重复提交；成功展示 Sandbox 标记、语言、时长、文本和复制按钮。
- 失败保留已上传媒体，允许用户修正语言或重试，不自动重复扣费。
- 页面不展示内部对象 key、Provider URL、密钥和向量。

首版不增加营销式说明、实时波形、录音器或历史列表；已有 GET 端点用于刷新/恢复单条结果和后续扩展。

### 7.2 素材库语义检索

`MediaLibraryPanel` 的“智能推荐”分栏增加自然语言搜索框和明确的搜索命令。任务模式默认可使用权威任务文本；用户输入 query 时覆盖自动语义文本。结果继续使用现有素材列表与选择交互，并增加：

- 总匹配度、规则分和可用时的语义分。
- “语义匹配”原因；fallback 时展示非阻断提示，列表仍显示规则结果。
- 请求中、无结果、模型不可用和普通失败状态。

输入、按钮和结果区使用稳定尺寸；移动端允许提示换行，不与素材选择控件重叠。

## 8. 错误与降级规则

| 场景 | 行为 |
|---|---|
| 未登录 | 401；不创建媒体业务记录或 AI Run |
| 非 owner 音频/转写 | 404，避免 IDOR 枚举 |
| MIME、大小、时长或媒体状态非法 | 400；不创建 AI Run、不计费 |
| capability 无可用模型 | 503 `no_platform_model` |
| Provider 类型尚未安装 | 503 `unsupported_provider`；已创建 Run 失败并退款 |
| 单次、日或月预算拒绝 | 402，对应既有 `exceeds_*_budget`；不调用 Provider |
| 平台模型并发已满 | 429；不调用 Provider |
| 平台积分不足 | 402 `insufficient_credits`；Sandbox 不扣积分 |
| 语音 Provider 失败 | POST 失败，转写记录 failed，Run 失败并触发退款 |
| 索引 Provider 失败 | 索引 failed 后有界重试；素材 CRUD 不失败 |
| 查询 Embedding 失败 | 200 返回规则排序，semantic.status=fallback |
| 单个素材向量缺失/过期 | 该素材只用规则分参与，不读取旧向量 |
| Edge flag 关闭/method 未登记 | fail-closed 404，无其他后端回退 |

日志只能记录 Run ID、转写 ID、素材 ID、Provider/模型、稳定错误码和耗时，不能记录音频字节、完整转写文本、搜索原文、Embedding、密钥或 Provider 原始响应。

## 9. Edge、配置与发布

- `/api/speech` 增加独立 Edge 路由及 `EDGE_ROUTE_SPEECH_INTELLIGENCE`，只登记 POST/GET 所需 method。
- `/api/content-assets/recommendations` 复用现有 `EDGE_ROUTE_CONTENT_ASSETS_INTELLIGENCE`，不扩大 `/api/content-assets` 以外的匹配。
- intelligence 增加 voice/retrieval Sandbox 配置、批处理大小、索引并发、claim 超时和重试上限，全部有保守默认值。
- Docker Compose 与示例环境变量同步新增 Edge flag；不写入任何真实密钥。
- Sandbox 模型配置只在对应 capability 没有当前配置时补齐，不覆盖管理员已有配置。
- 发布时先执行数据库迁移，再启用 intelligence，最后开启 Edge speech flag；语义检索即使索引尚未补齐也可通过规则排序工作。

## 10. 测试策略

### 10.1 Provider 与算法单元测试

- Sandbox speech 相同媒体校验和与语言输出稳定，不同输入可区分，结果明确标 Sandbox。
- Sandbox embedding 维度固定、数值有限、范数为 1、相同文本逐元素一致。
- 共享 token 的文本相似度高于完全无关文本。
- 余弦归一化、60/40 融合、缺向量和稳定 tie-break 正确。
- NaN、Infinity、零向量和维度不符被拒绝。

### 10.2 Intelligence 集成测试

- speech_audio 开票、确认、owner 读取和合法转写 round-trip。
- 非 owner、错误 purpose、pending/expired/deleted、非法 MIME、超大小和超时长全部 fail closed。
- voice Run 的 capability、Provider、模型版本、0 成本和转写关联正确。
- Provider 失败时转写 failed、Run failed、退款/补偿语义正确。
- 素材新增/编辑/审核激活产生 pending；dispatcher claim 幂等、崩溃超时可重领、失败有界重试。
- 素材版本、内容哈希或模型版本变化后旧向量 stale，查询只读当前 ready 行。
- personal/merchant/grant/store/public 的授权矩阵与当前推荐一致，跨账号和跨组织素材不进入候选。
- 查询 Embedding 失败时返回 200 规则结果；无 query 的独立模式响应排序与改造前一致。
- 500 候选与 50 返回上限生效，不出现无界收集。

### 10.3 Edge 与前端测试

- Edge speech flag、method allowlist、关闭时 404 与上游透传。
- 语音面板覆盖登录门禁、MIME accept、三步上传、处理中、成功复制、失败重试和 Sandbox 标记。
- 素材面板覆盖 query 参数、任务自动语义、分数原因、fallback、空结果和选择行为。
- 现有 `AiCreationCenter` 标签顺序与登录门禁测试同步更新。

### 10.4 完整门禁

实现完成后至少运行：

```bash
./gradlew :services:intelligence-service:test :services:edge-bff:test
npm test
npm run typecheck
npm run build
git diff --check
```

使用仓库约定的 JDK 25 运行 Gradle。若相关测试依赖 Testcontainers/PostgreSQL，必须同时运行对应 integration test source set，不能只以编译通过代替数据库行为验证。

## 11. 完成标准

满足以下条件才可把 #33 剩余能力标记为已开发：

1. 语音三步上传、owner 校验、Sandbox 转写、持久化结果和前端复制链路完整。
2. 素材索引状态机、存量补建、版本失效和稳定 Sandbox 向量完整。
3. 既有推荐接口真实消费查询向量，并在权限过滤后的候选中计算相似度。
4. 语义失败不会破坏现有规则推荐，未请求语义时保持兼容。
5. voice/retrieval 均有 `ai_run`、模型版本、成本和失败审计，Sandbox 不收积分。
6. Edge 路由 fail-closed，公开响应不泄露密钥、向量、对象 key 或跨租户信息。
7. Java、Edge、前端和类型/构建门禁全部通过。
8. `CLAUDE.md`、API 契约矩阵、开发进度指南和 `项目速览.md` 同步为实现后的事实状态，避免再次产生文档漂移。
