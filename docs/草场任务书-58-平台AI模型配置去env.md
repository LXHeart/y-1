# 草场任务书 #58：平台 AI 模型配置彻底去 env（控制面收口）

> 来源：2026-08-30 env 依赖盘点 + 用户六点拍板（受信白名单进平台管理 / 视频单独立项 / 冷启动治理台手工配 / 仅模型相关不进 env / sandbox 语义按建议保留 / B站抖音KYB 模型配置进平台管理）。
> 状态：**已立项待实现**（交 Qoder）。
> 前置：无硬前置；与 #57（风格 skill）正交。迁移版本 **V56**（V55 已被 #57 占用）。
> 规模定性：1 张新表 + 1 组新 admin API + 治理台 1 面板扩区块 + **约 12 处 env 消费点清删** + compose/.env/ci-e2e/secret-contract 连坐。无新业务功能，是一次配置架构收敛；行为变化集中在「无配置时的失败姿态」（fail-closed）与本地 dev 可用性。

## 决策表（2026-08-30 拍板）

| # | 决策 | 选择 |
|---|---|---|
| A | **总边界** | 模型端点 / 模型名 / 凭据 / 受信 origin 不再进 env，控制面（`platform_model_config` + `platform_provider_credential` + 新 origin 表）为唯一真相源。**保留 env**：`CRYPTO_KEK_*`、数据库、`AI_DNS_PINNING_*`、`AI_PROVIDER_ALLOW_SANDBOX`、`AI_PLATFORM_MODEL_ALLOW_INSECURE_LOOPBACK`、计价参数（`AI_SPEECH_CENTS_*`/`AI_EMBEDDING_CENTS_*`，归后续计价收敛线）、`VIDEO_GENERATION_*` 全组（决策 C） |
| B | **受信 origin 白名单** | 新表 `platform_trusted_origin`（V56，幂等 DDL，种子两行内置默认）；admin CRUD 挂 `/api/admin/ai/trusted-origins`（复用 `EDGE_ROUTE_ADMIN_AI_INTELLIGENCE` 的 `/api/admin/ai` 前缀族）；`PlatformProviderPolicy` 改读控制面缓存（写后失效事件，同 JVM）；`ai.qwen.base-url` 锚点与 `ai.platform-model.trusted-*-origins` 两个 env 白名单**删除**，内置默认 `https://dashscope.aliyuncs.com`、`https://api.openai.com` 改为 V56 种子行（治理台可见可删） |
| C | **视频生成豁免** | `ai.video-generation.*` 全组 env 与 `VideoGenerationProperties`/`FrozenVideoGenerationConfigResolver` **一行不动**，单独立项（拍板 2）。`SandboxVideoGenerationProvider` 的 allow-sandbox 装配条件保持 |
| D | **seed 全删** | `PlatformModelConfigSeeder` 整类删除（text/content_safety/voice/retrieval/image_edit/image_generation 六段全删，含 `System.getenv("IMAGE_GENERATION_MODEL")` 直读三处）。新部署冷启动 = 运营在治理台手工配置；无行 = fail-closed（见决策 F 的能力分级） |
| E | **凭据兜底链删除** | `ProviderKeyDecryptor` 的 `ai.qwen.api-key` env 兜底段删除（平台凭据无密钥 → 一律 503，文案保留现有「平台凭据缺失」句式）；speech/embedding 的 `configuredPlatformBearer` env 兜底同删；`CRYPTO_KEK_BASE64` 由可选变事实必选（部署 runbook 注明） |
| F | **无行时的能力分级** | 有真实 Sandbox 客户端实现的能力（voice/retrieval/image_edit）：控制面无行且 `allow-sandbox=true` → **内置 sandbox 平台解析**（`sandbox-speech-v1`/`sandbox-embedding-v1`/`sandbox-matting-v1`，base `https://sandbox.invalid` 免 origin 校验）；`allow-sandbox=false` 或其余能力（text/image_generation/content_safety）无行 → `denied("no_platform_model")` / content_safety 维持 L1-only 降级（ADR-D16 不变）。实现期核实点：若 `TextCompletionClient` 已支持 sandbox provider，text 一并纳入内置回落；不支持则不纳入 |
| G | **图片生成静态路径收口** | `PlatformImageResolutionService.staticProvider()` 与 `platformOrStatic()` 的静态回落删（无行即 denied）；`endpointFor` 的「无凭据控制面行用 env key」分支删；`ImageGenerationClient` 两参 `generate` 重载与 `endpoint==null` 静态回落删——endpoint 为 null 一律 503；`ImageGenerationConfig` 的 env 直读（base-url/api-key/model/provider）删，价目版本/单价字段保留（计价线） |
| H | **speech/embedding env 收敛** | `SpeechProviderProperties`/`EmbeddingProviderProperties` 的真实 provider 字段（base-url/api-key/model/provider/path/timeout/max-response-bytes/dimensions/send-dimensions）env 绑定删除，类退化为 sandbox 常量载体（或按实现简化）；`AiCapabilityProviderConfigValidator` 整类删除，其规则并入控制面 CRUD 校验（见 S1.4 规则清单） |
| I | **B站/抖音/KYB** | provider 闸写死 `"qwen"`（`BilibiliAnalysisService`/`DouyinAnalysisService`/`KybDocumentAnalysisService` 删 `@Value` provider 与 503 分支）；KYB 的 `model` 元数据字段删除，`Result` 改回填 `completion` 的真实 model/provider；timeout/切片秒数/维度等功能参数留 yml（拍板 7：这三个能力模型层面已走路由，**无需任何新的平台管理配置项**） |
| J | **sandbox 语义** | 缺省假 provider 语义保留（决策 F 的内置回落），`AI_PROVIDER_ALLOW_SANDBOX` 留 env（部署策略，生产 false 防呆）；本地 dev 零模型配置时 voice/retrieval/image_edit 可跑、其余能力需 BYOK 或治理台配行（验收注明此行为变化） |
| K | **e2e/CI** | `scripts/ci-e2e.sh` 删 `QWEN_BASE_URL`/`QWEN_API_KEY` export（`:49-50`），改为栈起来后**调治理台控制面 API** 完成三件套：建 `text/primary` 行（base `https://qwen-e2e.invalid/v1`）+ 配凭据（随机 key，明文经 API 服务端信封加密）+ 确认 origin（qwen-e2e.invalid 不在默认表则加行）；`AI_DNS_PINNING_TRUSTED_DOMAINS`（`:54`）保留；ci-e2e 需 `export CRYPTO_KEK_BASE64=<固定 32 字节测试值>`（KEK 在保留边界内）。不采用手工拼密文 SQL |
| L | **compose/secret/文档** | `docker-compose.yml:704-736` 的 `QWEN_*`（含 `:?` 必填门禁）、`AI_SPEECH_*`、`AI_EMBEDDING_*`、`IMAGE_GENERATION_*`、`AI_PLATFORM_MODEL_TRUSTED_OPENAI_COMPATIBLE_ORIGINS` 传递删除；`deploy/security/production-secret-contract.csv:12` 的 `QWEN_API_KEY` 行删除（`CRYPTO_KEK_BASE64` 升级注释为 intelligence 必选）；repo 根 `.env` 同步清理（顺手删死配置 `VIDEO_ANALYSIS_API_*` Coze 遗产）；`docs/生产发布与灾备运行手册.md` 补「先配后删」上线顺序（见「上线顺序」节） |

## 模型与关键技术真相（动手前必读）

1. **`ai.qwen.base-url` 的 fail-fast 不是为 seed，是 SSRF 锚点**：`ai/PlatformModelConfig.java` 的 `validate()` 注释写明「`PlatformProviderPolicy` 在构造期就用它奠定受信 origin 集」；`ai/run/TextCompletionClient.java:211-213` 每次调用 `platformProviderPolicy.validateBaseUrl(baseUrl)` / `validate("openai-compatible", baseUrl)`。本任务把锚点搬进 origin 表后，该 fail-fast 连同类一起删。**Policy 目前是构造期 final Set**——改控制面后必须变成可刷新（写后失效），这是 B 的核心工程。
2. **控制面 CRUD 已有明文入参服务端加密的先例**：`PlatformProviderCredentialController`（`/api/admin/ai/credentials`，`RotatePlatformCredentialRequest`）——决策 K 的 e2e 三件套与治理台凭据录入照此模式，不要发明新机制。
3. **`ByokRoutingService` 的 env bootstrap 语义散在四处注释**（`:161`「凭据密文随解析下传…为 null 则回落 env bootstrap」、`:197`「无凭据的平台解析（env bootstrap 兜底路径）」、`:208`/`:239` 同义）+ `fallbackStage`（`:126-143`）无行时的 env qwen 平台解析。删兜底时**四处注释与逻辑同步改**，别留说过时话的注释（历史教训：文档滞后于代码）。
4. **`ProviderKeyDecryptor`（`ai/run/ProviderKeyDecryptor.java:44-58`）三段语义**：密文→解密；平台无密钥→env `ai.qwen.api-key`；双缺→503。删第二段，503 文案沿用第三段现有句式（「该能力的凭据未配置密钥」去掉 env 兜底字样）。
5. **图片静态路径的精确位置**：`articleimage/PlatformImageResolutionService.java`——`platformOrStatic()`（`:36-44` 控制面行优先、无行回落 static）、`staticProvider()`（`:46-50`）、`endpointFor()`（`:56-70`，无凭据控制面行用 `runtimeConfig.apiKey()`、静态返回 null）；`articleimage/ImageGenerationClient.java:35-38` 两参重载 + `:49-51` `endpoint==null` 静态回落；`ai/run/AiExecutionService.java:178` 附近注释「无凭据平台传 null（HTTP 走静态 env 端点）」同改。任务书 #56 的 BYOK 单闸与快照冻结（provider/model/价目版本冻结、密钥不入快照）行为不得变。
6. **speech/embedding 的 env bearer 位置**：`speech/SpeechTranscriptionService.java:301-313`（`configuredPlatformBearer`）与 `embedding/EmbeddingExecutionService.java:157-165` 同构；两者的运行时 provider 选择已走 `providers.require(context.provider().provider())` 控制面解析，只删 bearer 兜底。
7. **`AiCapabilityProviderConfigValidator` 规则并入 CRUD 时的清单**（别丢跨能力那条）：sandbox 行 base 必须 `https://sandbox.invalid`；真实行 provider ∈ {qwen, openai-compatible}；model 非空；apiKey ≥16 字符且禁 `replace-with/placeholder/changeme/your-` 前缀；base 必须 HTTPS（`allow-insecure-loopback` + 回环例外保留 env）；path 无查询绝对路径；timeout 1s–5min；maxResponseBytes 1KiB–16MiB；embedding dimensions 1–4096；价目非负；**speech 与 embedding 真实模型名不得相同（价目表按模型名唯一索引）**——最后一条在 CRUD 层变为「跨 capability 校验」，保存前查对方 capability 的当前行。
8. **两个易漏的 env 类消费者**：`ai/run/PlatformConcurrencyLimiter.java:27-33` 用 env 类的 `readTimeout()` 做启动校验（lease TTL > read timeout）——改为 yml `ai.platform-model.read-timeout`（默认 120s）自比；`creationlineage/TextCreationLineageService.java:38` 用 `platformDefaults.model()` 做非任务模式兜底显示——改为 completion 结果回填，取不到用 `"unresolved"`。
9. **迁移与共享表**：`platform_trusted_origin` 是 intelligence 自有表，V56 一处 DDL（`IF NOT EXISTS` 幂等，OutboxRepositoryIT/TaskLifecycleMigrationTest 重放红线），**不动 database-bootstrap**；IT 容器自跑 intelligence Flyway。
10. **edge 路由契约**：新 API 尽量挂在 `/api/admin/ai/` 前缀族下（`EDGE_ROUTE_ADMIN_AI_INTELLIGENCE`，`edge-bff/application.yml:188`）；若该路由是逐路径枚举而非通配，加路径必须同步 `RouteOwnershipContractTest`（真实 yml 逐路径断言）。
11. **AdminView 页签按数字下标点**（`AdminView.vue:63-64` 注释）——本任务**不新增页签**，只扩 `AiPlatformModelsPanel.vue`，规避该雷。
12. **intelligence 没有 ObjectMapper bean**——新代码持服务局部 Jackson 实例（注入即炸上下文）。
13. **e2e 契约已锁**：`qwen-e2e.invalid` 假域名 + DNS pinning 固定表是锁死契约（`docker-compose.yml:709-711` 注释）；改造后假域名进 DB 行而非 env，pinning 表不动。

## S1 · 后端：受信 origin 控制面化

### S1.1 V56 迁移 `db/migration/V56__platform_trusted_origin.sql`

```sql
CREATE TABLE IF NOT EXISTS platform_trusted_origin (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    origin      text NOT NULL,            -- scheme://host[:port]，无 path
    label       text NOT NULL DEFAULT '', -- 治理台备注（如「MiniMax 图像」）
    enabled     boolean NOT NULL DEFAULT true,
    version     int NOT NULL DEFAULT 0,
    updated_by  uuid,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT platform_trusted_origin_origin_key UNIQUE (origin)
);

INSERT INTO platform_trusted_origin (origin, label)
SELECT * FROM (VALUES
    ('https://dashscope.aliyuncs.com', '内置默认·Qwen/DashScope'),
    ('https://api.openai.com', '内置默认·OpenAI 兼容')
) AS seed(origin, label)
WHERE NOT EXISTS (SELECT 1 FROM platform_trusted_origin);
```

### S1.2 `controlplane/TrustedOriginService` + Controller + Policy 改造

- Repository：`DatabaseClient` 裸 SQL 惯例（照 `PlatformModelConfigRepository`）；`listEnabled()` / `listAll()` / `create(origin, label, updatedBy)` / `update(id, origin, label, enabled, expectedVersion)` / `delete(id)`（乐观锁 version，冲突 409）。
- Controller `/api/admin/ai/trusted-origins`：GET 列表 / POST 新增 / PUT 修改 / DELETE；RBAC 沿用控制面既有 admin 角色门（与 models/credentials 同一闸）。
- **校验**：`ProviderUrlGuard.validate` + 必须 HTTPS（回环例外沿 env `ai.platform-model.allow-insecure-loopback`）+ 剥 path 只留 origin + 禁重复。
- **模型行保存时的新增校验**：`CreatePlatformModelRequest`/`UpdatePlatformModelRequest` 处理器对非 sandbox 行校验 `base_url` 的 origin ∈ 启用中的 origin 表，未命中 → 422，文案「base URL 的端点不在受信列表，请先在受信端点中添加 {origin}」（治理台 UX 闭环）。
- **Policy 改造**：`PlatformProviderPolicy` 从「构造期 final Set」改为读 `TrustedOriginService` 缓存——**写后失效**：origin 表 CUD 后发 Spring `ApplicationEvent`，缓存监听清空，下次调用重拉；启动懒加载。单实例语义，多实例扩展留 TODO 注释。`ai.platform-model.trusted-qwen-origins` / `trusted-openai-compatible-origins` 两个 `@Value` 与构造参数里的 env `PlatformModelConfig`、speech/embedding properties 贡献项全部删除。

## S2 · 后端：env 消费点清删（决策 D/E/F/G/H/I）

按文件清单执行，全部为删除或收紧，无新逻辑（sandbox 内置回落除外）：

1. 删 `PlatformModelConfigSeeder` 整类 + `ai.platform-model.seed-on-startup` 开关。
2. 删 `ai/PlatformModelConfig.java`（env 版）整类；其消费者改道：`ProviderKeyDecryptor`（删兜底段）、`ByokRoutingService`（`fallbackStage` 无行 → 决策 F 分级：内置 sandbox 解析或 denied，四处注释同改）、`PlatformConcurrencyLimiter`（改 yml read-timeout）、`TextCreationLineageService`（回填真实 model）、`PlatformProviderPolicy`（S1）、seeder（已删）。
3. 删 `AiCapabilityProviderConfigValidator` 整类；规则清单（真相 7）并入控制面 CRUD。
4. `PlatformImageResolutionService`/`ImageGenerationClient`/`ImageGenerationConfig` 按决策 G 收口；`ImageGenerationConfig` 保留价目字段。
5. `SpeechProviderProperties`/`EmbeddingProviderProperties` 按决策 H 缩减；两处 `configuredPlatformBearer` 删。
6. B站/抖音/KYB 按决策 I：写死 qwen、删 KYB model 元数据改回填（`KybDocumentAnalysisService.java:49/:106/:165`）。
7. sandbox 内置回落（决策 F）：`fallbackStage` 对 voice/retrieval/image_edit（实现期核实 text，真相见决策 F）在 `allow-sandbox=true` 且无行时返回 `ProviderResolution.platform(null, "sandbox", "sandbox-{speech|embedding|matting}-v1", "https://sandbox.invalid", 1, null, null)`；`allow-sandbox=false` 时维持 denied。
8. `application.yml` 清理对应 `ai.qwen`/`ai.speech`/`ai.embedding`/`ai.image-generation` 段与 `ai.bilibili-analysis.provider`、`ai.douyin-analysis.provider`、`ai.kyb-document.provider/model` 键（timeout/切片/维度留）。

## S3 · 治理台前端（`AiPlatformModelsPanel.vue` 扩展）

- 面板内新增「受信端点」区块（模型列表下方或并排子区，按 `src/ops/DESIGN.md` Cal 系）：列表（origin/label/enabled/更新时间）+ 新增/编辑/停用/删除，乐观锁冲突 409 提示刷新重试。
- 空态引导：`platform_model_config` 为空时面板顶部给一次性提示条「尚无平台模型配置，平台侧 AI 调用将不可用——先加受信端点，再添加模型与凭据」。
- 样式复用既有面板范式，禁止硬编码新 token（AGENTS.md UI 红线）；双主题截图自查。

## S4 · 部署面连坐

1. `docker-compose.yml`：删 `:704-712` QWEN 段（含 `:?` 门禁与 DNS pinning 注释改写）、`:714-733` AI_SPEECH/AI_EMBEDDING 段、`:734` trusted-origins env、`:735-736` IMAGE_GENERATION 段；`VIDEO_GENERATION_*` 不动。
2. repo 根 `.env`：删 `QWEN_*`、`VIDEO_ANALYSIS_API_*`（死配置）；补注释「模型配置见治理台 AI 模型页」。
3. `scripts/ci-e2e.sh`：按决策 K 改造（删 QWEN export，加 CRYPTO_KEK 固定测试值 + 三件套 curl；admin 账号用 e2e-admin 惯例）。
4. `deploy/security/production-secret-contract.csv`：删 QWEN_API_KEY 行；CRYPTO_KEK_BASE64 注明必选。
5. `test/deployment/java-runtime.contract.test.ts` 引用 qwen-e2e 处同步（`docker-compose.yml` 引用列表）。
6. `docs/生产发布与灾备运行手册.md` 补上线顺序节（见下）。

## 上线顺序（写进 runbook，本节为任务书定稿）

1. **先配后删**：当前版本（env 仍在）运行时，控制面行已优先于 env——在生产治理台预先配好各能力模型行 + 凭据 + origin（沙箱演练一遍全能力冒烟）。
2. 发新版（含 V56）：控制面有行，行为无差。
3. 从生产 env/compose 移除 QWEN_* 等变量并重启：验证无 env 兜底路径下全能力正常。
4. 回滚安全：V56 为纯新增表，回滚旧版本不影响（旧版本仍读 env，故 env 变量在下一次稳定窗口前不要从 Secret Manager 物理删除）。

## S5 · 测试与验收

**门禁**：全量 IT（testcontainers）绿 + 前端 vitest + `RouteOwnershipContractTest`（若动路由）+ e2e CI 绿（`scripts/ci-e2e.sh` 新路径）。

**grep 断言（写进验证脚本或手工执行并留痕）**：

```bash
# intelligence 主代码无模型 env 消费（video 豁免组除外）
grep -rn "ai\.qwen\.\|ai\.speech\.\|ai\.embedding\.\|ai\.image-generation\." \
  platform-java/services/intelligence-service/src/main --include="*.java" | grep -v videoproduction   # 应无输出
grep -rn "QWEN_\|AI_SPEECH_\|AI_EMBEDDING_\|IMAGE_GENERATION_\|AI_CONTENT_SAFETY_MODEL\|TRUSTED_OPENAI_COMPATIBLE" \
  docker-compose.yml platform-java/services/intelligence-service/src/main/resources/application.yml    # 应无输出（VIDEO_GENERATION_ 除外）
```

**行为验收（本地冒烟，无任何模型 env 起栈）**：

1. 冷启动空库：治理台「AI 模型」面板空态提示可见；平台 text 调用返回 no_platform_model/denied；voice 转写在 allow-sandbox=true 下返回 Sandbox 假结果。
2. 治理台配置闭环：加 origin `https://api.minimaxi.com` → 建 image_generation 行（base 指向它）→ 配凭据 → 独立生图成功（或 mock）；未加 origin 先建行 → 422 引导文案。
3. origin 写后生效：保存/停用 origin 后**不重启**，下一次文本调用即按新表校验（写后失效事件）。
4. 凭据 fail-closed：模型行无凭据且无 env 兜底 → 503「平台凭据缺失」；content_safety 无行 → 审核仍 L1-only 通过。
5. BYOK 回归：个人 BYOK 文本/图像生成不受影响（#56 单闸行为不变）。
6. e2e：ci-e2e.sh 新三件套路径下全量通过。
7. 明示的行为变化（写入交付说明）：本地 dev 零配置时 text/image_generation/content_safety 不可用（需 BYOK 或治理台配行），voice/retrieval/image_edit 走 sandbox。

## 明确不做（边界）

- 视频生成控制面化（单独立项，`VIDEO_GENERATION_*` 原样）。
- 计价体系收敛（`AI_SPEECH_CENTS_*` 等留 env）。
- B站/抖音/KYB 的新配置面（写死 qwen 即终点）。
- BYOK 流、价目表、冻结快照机制的任何语义变化。
- origin 表的多实例广播失效（单实例事件足够，多实例留 TODO）。
