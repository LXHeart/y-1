# 开发任务书：107-fix-1 · Hypit 功能复刻缺口收口

> 任务编号：107-fix-1 ｜ 任务书版本：1.0.0 ｜ 创建日期：2026-09-26
> 规划模型/负责人：ZCode（规划）→ 交执行模型实现，用户验收 ｜ 目标仓库：y-1（仓库根 `/Users/LXH/claude/y-1`）｜ 分支：main（仅记录）
> 代码基线：`459e998b`（main，2026-09-26；107 三书 24 卡 + CI fresh-clone 三轮修复已全部入库推送）｜ 本次事实核验日期：2026-09-26
> 规格状态：READY_FOR_IMPLEMENTATION ｜ 实施状态：NOT_STARTED
> 目标执行者：能力较弱的编码模型 ｜ 任务卡总数：7 ｜ 起始卡：C107F-01
> 执行顺序：C107F-01 → C107F-02 → C107F-03 → C107F-04 → C107F-05 → C107F-06 → C107F-07；01/02/04/05 相互独立可并行，03 依赖 02 的契约测试基建，06 独立，07 收尾依赖全部
> 执行模式：AUTO_CHAIN ｜ 交付终点：本地集成验收完成（真实 e2e 导出导入 roundtrip 属 opt-in 层，跑不到时如实 NOT_RUN，不冒充）

## 决策依据

用户于 2026-09-26 要求对照上游 hypit（vendored `platform-hypit/upstream`，v0.2.13，commit 2c320059）审计 #107 三书的功能复刻度并出具修复任务书。审计结论（四项真实缺口 + 一处隔离边界未达目标态）已在对话中向用户汇报，用户指示按任务书模板出书。本书全部范围来自该审计的实锤证据；上游行为以 `platform-hypit/upstream` 源码为准，#107 契约以 `contracts/hypit-api.v1.json`、`contracts/hypit-tools.v1.json` 与 107-1 §6.2 为准。

---

## 0. 执行协议

同模板 v3.0.0 §0（词义、强制规则、DoD 全文见模板，此处只列本任务特别补充）：

1. **上游冻结**：`platform-hypit/upstream/` 全目录只读，任何卡不得改动（`scripts/acceptance/verify-107-upstream.sh` 逐文件 sha256 守卫）。需要上游行为证据时读源码并引用路径+符号。
2. **引擎 G 物化前置**：`platform-hypit/backend` 的 typecheck/test（V04）依赖 `.generated/hypit` 引擎树。跑 V04 前先执行 `bash scripts/acceptance/build-107-engine.sh --no-install`（测试不执行 G 代码时）或完整构建（涉及 snapshot/render 的测试需要渲染浏览器，见 §9.3）。
3. **不新增 Edge 路由**：本任务全部端点已在 `contracts/hypit-api.v1.json` 声明、76 个 `EDGE_ROUTE_HYPIT_*` 旗标已全部存在于 `docker-compose.yml`。任何卡不得改 edge 清单；若发现端点不通先查旗标与 edge 路由测试。
4. **禁止把 pending 桩换成另一种假成功**：C107F-04 实装的定义是「真实可调用且行为符合 §6 契约」，不允许返回空列表/恒成功/吞错误。
5. 其余（白名单交集、不顺手修、真实验收、BLOCKED 规则）照模板 §0.2 执行。

---

## 1. 产品需求、目标与范围

### 1.1 一句话目标

把 #107 审计确认的四项功能缺口（工程包导出导入断链、七条 pending 桩、四组工具无生产入口、vocabulary 词法缺半）与一处隔离边界（作者包编译未入 runner）全部收口，使 `contracts/hypit-api.v1.json` 声明的 76 条路由与 `contracts/hypit-tools.v1.json` 声明的工具面**全部真实可用且被测试锁定**。

### 1.2 背景与价值

2026-09-26 复刻度审计确认：#107 主链（创作/构建/结果/Studio/Provider）完整可用，但存在「契约声明了、卡表标 IMPLEMENTED 了、运行期却不可用」的四类缺口——其中 `project-package.export/import` 在 J→B 派发时必落 `unknown_kind`（被 WireMock 桩测试掩盖），`hypit.agent` 持久 worker 在生产无任何投递者。收口后：工程包移植链真正闭环、工具面与契约一致、Agent 通用任务入口可达、作者代码编译回到隔离 runner。

### 1.3 范围内（明确交付）

| 需求编号 | 用户/触发场景 | 必须交付的可观察行为 | 优先级 | 负责卡 | 验收编号 |
|---|---|---|---|---|---|
| REQ-F01 | 创作者导出工程包 / 在另一账号导入 | POST `/api/hypit/projects/{id}/export` 与 POST `/api/hypit/imports` 对真实 B broker 全链成功：导出返回 artifactRoot+fileCount+manifest，导入产出新 projectId 且 revision=2（一次 journal 变更），roundtrip 文件 sha256 一致 | 必须 | C107F-01 | AC-F01-01～03 |
| REQ-F02 | 创作者经 `POST P/tools/{tool}` 调转写/估时/对齐/图像变换合成 | `speech.transcribe/measure/align`、`image.transform/compose` 202 AcceptedJob 且 job 结果落素材库；whisperx 未就绪时 transcribe 报 `hypit_program_not_ready` 不假成功 | 必须 | C107F-02 | AC-F02-01～03 |
| REQ-F03 | 工具契约三向一致 | `contracts/hypit-tools.v1.json` 的工具 id 集 == B dispatcher 实际路由的 kind 集 == J `HypitAssetService` 白名单集（差集为契约注记的 operator 专用项），由新增契约测试静态锁定，漂移即 CI 红 | 必须 | C107F-02/03 | AC-F02-04 |
| REQ-F04 | 创作者对当前预览取精确帧快照 / 对网页取证截图 / 受限脚本采集 | `snapshot`（经 preview session，服务端解析 document）、`capture.screenshot/run/install-browser`、`packages.build/pack` 全部经 TOOLS_INVOKE 可调，URL 由服务端生成、脚本出工程 head、输出落资源句柄 | 必须 | C107F-03 | AC-F03-01～04 |
| REQ-F05 | 创作者创建/查看通用 Agent 任务并恢复 | GET/POST `P/agent-jobs` 实装：POST 按 107-1 §6.2 契约（intent 白名单）创建 `hypit.agent` job 并由 `HypitAgentWorker` 真实消费；GET 列出本人任务；`POST P/jobs/{jobId}/actions`（resume/cancel）实装 waiting_input 恢复与取消，resume 不扩张原 scope | 必须 | C107F-04 | AC-F04-01～04 |
| REQ-F06 | 部署者经 runtime 面操作/诊断 | POST `/runtime/actions`：doctor 返回聚合健康；up/down 幂等编排两程序（down 活跃工作 + hash 不匹配 → 409 影响清单）；init/use/unset 返回 409 `hypit_runtime_managed` 显式语义。GET `/runtime/paths` 返回逻辑路径清单（operator） | 必须 | C107F-04 | AC-F04-05～07 |
| REQ-F07 | Agent/创作者查询可写组件词法 | GET `/api/hypit/vocabulary` 支持 `surface`（per-package module/import/attributes/children/ports/example）与 `visual`（形状 schema）两组参数，数据来自 G 发行版真实包 manifest（原生 `listSurfaces/visualSchema` 包装），不造假目录 | 必须 | C107F-05 | AC-F05-01～02 |
| REQ-F08 | 作者组件编译在硬隔离 runner 执行 | `packages.build/pack` 的 tsc 编译从 broker 进程内 spawnSync 迁入 K10.4 runner（`compile` kind 已在协议白名单且已实现），broker 不再直接 spawn 作者代码编译 | 必须 | C107F-06 | AC-F06-01～03 |
| REQ-F09 | 文档与证据收口 | 任务书 README 索引、进度指南 107 段、`contracts/hypit-coverage.v1.json` 对应证据条目同步；V 门禁矩阵复跑并记录 | 必须 | C107F-07 | AC-F07-01 |

### 1.4 范围外（明确不做）

- 不实装真实 Provider 调用（live 仍 REAL_NOT_RUN，属 107-3 V15 opt-in 层既有边界）。
- 不实装 runner `executeLocal/capture` kind（保持 `NOT_IMPLEMENTED_KINDS` 原样；capture 工具走 broker 受限通道而非 runner，见 D-06）。
- 不改 `platform-hypit/upstream/`、不改 edge 路由清单与旗标、不新增数据库迁移（全部用既有 `hypit_job/hypit_job_action/hypit_command` 表）。
- 不做 hypit-tools 契约的 `packages.install/status` 经 TOOLS_INVOICE 开放（operator 级，保留在 `/runtime/packages` 专端点）。
- 不补 107-3 已知的 C21 PENDING_SCREENSHOT 证据、不动 V10 已知 DH 共享容器 flaky 家族。

### 1.5 不许顺手修

- `npm run docs:links` 现有 ~20 条 `[unindexed]`（`platform-java/.../resources/hypit/knowledge/**` 与 `deploy/hypit/README.md`，107 批入库即有、CI 不跑该检查、docs:status 绿）——非本书引入，不在范围；C107F-07 的 VFX-4 以 docs:status/links 的「零新增漂移」为准（基线漂移数不得增加）。
- `HypitAgentWorker.MAX_STEPS=40` 与 107-2 卡文「maxSteps 默认 30」的文字差——保持 40，不改代码不改文档（非本次范围）。
- `platform-hypit/upstream` 内任何内容；`scripts/local/` 下历史脚本。
- MinIO/quay 镜像拉取超时类 CI 瞬时问题（如复现按 §13 报告，不改测试）。

### 1.6 用户、入口与已知限制

- 用户/调用方：创作者（project owner，经 edge 会话）、部署者（operator，`HYPIT_OPERATOR_ACCOUNT_IDS`）；无门店/组织上下文。
- 前置条件：工程 ready（`requireReadyOwner`）、whisperx 程序 up（transcribe）、preview session 存活（snapshot）、capture Chrome 已 install（capture.*）。
- 已知且允许保留的限制：真实 LLM 质量不在验收范围（StepService 用平台既有 FrozenText 执行环，验收只锁结构与状态机）；live provider 调用不可用；e2e roundtrip 需隔离栈（本机可跑 `DH_E2E` 同款 hypit 栈）。
- 验收例外：无。

### 1.7 用户场景与业务闭环

| 场景/需求 | 用户及动机 | 触发与前置 | 主流程 | 最终结果 | 中断恢复 |
|---|---|---|---|---|---|
| 工程移植 / REQ-F01 | 创作者把工程打包给另一账号复用 | 工程 ready、有 published head | 前端「导出」→ POST export → 202 → 轮询 job → 下载 bundle → 新账号 POST /imports（multipart）→ 202 → 新工程 provisioning → ready | 新工程 revision=2、文件哈希与源一致、可继续 check/plan/build | job 失败保留原因可重试（同 requestId 幂等回读）；导入被拒不落任何 ready 状态 |
| Agent 任务 / REQ-F05 | 创作者让 Agent 按简报驱动工具 | 工程 ready、intent 合法 | POST agent-jobs → 202 AcceptedJob → SSE 看 activity → worker 逐步执行/记 action 行 → 超步数 waiting_input → POST actions resume 带补充资料 → 继续 → succeeded/failed | 动作行与产物绑定、可审计 | 崩溃后租约过期重领、只重放确定性动作；cancel 单向 |
| 快照取证 / REQ-F04 | 审片时对当前版本取精确帧 | preview session 存活 | POST tools/snapshot（at[] 或 ranges 分页）→ 202 → job 产物帧图落素材库（role=footage） | 帧索引与文档帧精确对应、不新建导出 Build | session 过期 → 409 `hypit_session_expired` 重开 session |

核心术语：**B**＝`platform-hypit/backend` 可信 Node broker；**G**＝`.generated/hypit` 引擎树；**J**＝intelligence-service hypit 包；**kind**＝J→B 命令类型字符串（dispatcher 路由键）；**工具 id**＝`/tools/{tool}` 公开名；**operator**＝`HYPIT_OPERATOR_ACCOUNT_IDS` 部署管理账号。

### 1.8 产品成功标准

| 成功标准 | 观察对象与判定 | 目标 | 对应 AC |
|---|---|---|---|
| 契约 76 路由零 pending 桩 | grep J api/ 目录 `pending(` 命中数 | 0（107-fix 前为 7） | AC-F04 |
| 工具契约三向一致 | 新契约测试 | 退出码 0；人为改任一侧即红 | AC-F02-04 |
| 导出导入 roundtrip | e2e（或本地隔离栈手跑，如实分层记录） | 文件 sha256 全一致 | AC-F01-03 |
| 作者代码编译零 broker spawn | B 测试断言 runBuild 经 runner client | broker 进程无 tsc 子进程 | AC-F06-02 |

### 1.9 未决问题与决策权限

无。全部决策已在 §3 冻结（D-01～D-08），均在「完成 107-1 既有契约声明」授权范围内，无新产品决策。

---

## 2. 仓库上下文

### 2.1 目标端

| 勾 | 端 | 相关目录 |
|---|---|---|
| [x] | 后端（Node broker） | `platform-hypit/backend/src/**`、`platform-hypit/backend/tests/**` |
| [x] | 后端（Java） | `platform-java/services/intelligence-service/src/{main,test}/java/com/grassland/intelligence/hypit/**` |
| [x] | 契约/测试 | `contracts/hypit-*.json`、`tests/deployment/hypit-*.contract.test.ts`、`tests/e2e/hypit-*.spec.ts` |
| [ ] | 前端 | 本任务不改 UI（工具调用走既有 TOOLS_INVOKE 通道与素材库，无新面板） |
| [ ] | 部署 | 仅 `.env.example` 追加键（见 D-07）；不改 compose 拓扑 |

### 2.2 设计规范路由

N/A：无 UI 改动。

### 2.3 入口位置

- dispatcher 路由：`platform-hypit/backend/src/commands/dispatcher.ts` `runKind()`（显式 switch + default 内 `isMediaTool/isPackageTool/isSpeechTool/isImageTool` 谓词与 `programs./providers./credentials./authflow.` 前缀 fallback）。
- J 工具入口：`.../hypit/asset/HypitAssetService.runTool()`（`MEDIA_TOOLS` 白名单 → `runAssetJob(action="tool.run")` → sidecar）。
- J pending 桩：`.../hypit/api/HypitJobController.submitAction`、`HypitProjectController.agentJobs/createAgentJob/projectJobActions/projectJobActionSubmit`、`HypitRuntimeController.action/paths`（全部走 `pending("…")` 恒 error）。
- Agent worker：`.../hypit/agent/HypitAgentWorker`（`JOB_KIND="hypit.agent"`、`@Scheduled` 5s、租约 45s、`MAX_STEPS=40`）。
- vocabulary：B `engine/vocabulary.ts` `describeVocabulary()`（现返回 `{providers,programs,alignmentLanguages}`）；上游能力面 `platform-hypit/upstream/packages/video-cli/src/vocabulary.ts`（`listPackages/listSurfaces/describeSchema/visualSchema/runVocabularyCli`）。
- runner：`platform-hypit/backend/src/runner/protocol.ts`（`RUNNER_COMMAND_KINDS` 已含 `compile`）、`runner/server.ts`（`NOT_IMPLEMENTED_KINDS={executeLocal,capture,cancel}`，即 **compile 已实现**）、`runner/client.ts`、`runner/supervisor.ts`。
- packages 工具：`platform-hypit/backend/src/tools/packages.ts` `runBuild/runPack`（现 `spawnSync(tsc, …)` 在 broker 进程）。

### 2.4 相关现有文件（节选，全量见 §9.1）

| 文件 | 相关符号 | 当前职责 | 本次关系 |
|---|---|---|---|
| `platform-hypit/backend/src/project-package/export.ts` | `exportProjectPackage/ExportContext/ExportReceipt` | 打包工程为 bundle（哈希清单/密钥形文件拒打包/4GiB 上限） | C107F-01 补 `fileCount` 字段 + dispatcher 接线 |
| `platform-hypit/backend/src/project-package/import.ts` | `importProjectPackage/ImportContext/ImportReceipt` | verify-then-create 导入（sha256/禁符号链接/数量体积门） | C107F-01 dispatcher 接线（`newProjectId` 由 broker 生成） |
| `platform-hypit/backend/tests/workspace/project-transfer.test.ts` | export/import 直调测试 | B 层闭环已测 | C107F-01 增加 dispatcher 级用例 |
| `.../template/HypitProjectPackageService.java` | `export/import_/replayOrDispatch` | J 幂等编排，派发 `project-package.export/import` kind | C107F-01 对齐 receipt 字段 + IT 收紧 |
| `.../template/HypitTemplateIT.java`（test） | WireMock 桩 | mock sidecar 应答两 kind | C107F-01 桩响应字段与 B receipt 锁定一致 |
| `contracts/hypit-tools.v1.json` | 18 工具声明 | 无任何测试消费（漂移：`transcribe` vs `speech.transcribe`；`image.*` 未列） | C107F-02 重生成 + 新契约测试消费 |
| `.../asset/HypitAssetService.java` | `MEDIA_TOOLS/runTool` | 8 media 白名单 | C107F-02/03 扩白名单 |

### 2.5 当前行为（断链/桩的现状，均已核实）

1. J `export()` → `replayOrDispatch(command, "project-package.export", payload)` → sidecar POST `/internal/v1/commands`（kind=`project-package.export`）→ B `runKind` 无 case、无谓词命中 → `DispatchError("unknown_kind")` → J 收 error → 端点失败。import 同理。J 期望响应字段 `artifactRoot/fileCount/manifest` 与 `projectId/revision/fileCount`；B `ExportReceipt` 现无 `fileCount`（ImportReceipt 有）。
2. 七条 pending 桩返回 503 error；`hypit.agent` job 无生产 INSERT（全库仅测试直插）；`HypitAgentWorker.driveToCompletion` 对空 `checkpoint.actions` 直接置 succeeded（生产入口若不定义计划生成即假成功——本任务用 D-04 planner 首步堵死）。
3. `speech.*`（3）/`image.*`（2）kind 在 B dispatcher **已路由**（`isSpeechTool/isImageTool`），J 白名单未放行；`snapshot`（`tools/snapshot.ts` 的 `snapshotSchedule/snapshotFrames`）与 `capture.*`（`tools/capture.ts` 的 `prepareCaptureChrome/assertRestrictedCaptureScript`）模块存在但无 kind 通道，仅 backend 测试消费。
4. `tools/packages.ts runBuild` 在 broker 进程 `spawnSync(tsc)`；runner 协议白名单已含 `compile` 且 server 已实现（`NOT_IMPLEMENTED_KINDS` 不含它）。
5. `/api/hypit/vocabulary` → sidecar kind `vocabulary` → B `describeVocabulary`：只返回 providers/programs/alignmentLanguages，无 surface 词法与 visual 形状。

### 2.6 当前问题（审计定性）

见 §1.2；每项根因均已确认（wire 级：kind 未路由 / 桩未换 / 白名单未放 / 实现未迁），非猜测。

### 2.7 基线与来源核验

| 项目 | 已核实内容/证据 |
|---|---|
| 指令与设计 | `AGENTS.md`（目录归位/测试归位）；无 UI 故无 DESIGN.md 义务 |
| 版本与构建 | node v24.14.1（`~/.nvm/versions/node/v24.14.1/bin` 前置 PATH）；JDK25 `/opt/homebrew/opt/openjdk@25/...`；backend tsconfig `@hypit/*` paths 指向 `.generated` |
| 工作区 | 基线 `459e998b`，工作树干净（2026-09-26 核验） |
| 测试基线 | V04 179/0/2、V05 84/0/0、契约 19/19、lifecycle 49+31 全绿（2026-09-26 复核记录）；CI run 36243312307 在跑（459e998b，属上一批修复验证，不阻塞本书） |
| 复用检查 | 全部缺口均有既有实现件（export/import 函数、speech/image 工具、snapshot/capture 模块、runner compile、上游 vocabulary 函数），本书只做接线/实装/对齐，不新造能力 |

### 2.8 事实/决策/示例区分

全文 `FACT`（已核实的现状）与 `DECISION`（D-01～D-08）按标记识别；无 EXAMPLE 合成业务值。

### 2.9 影响面与兼容面

| 影响面 | 受影响 | 对象 | 兼容要求 | 验证 |
|---|---|---|---|---|
| 公共 HTTP 契约 | 是 | agent-jobs×2、jobs actions×2（项目级）、runtime/actions、runtime/paths 共 7 条（503→真实语义） | 旗标默认 false 不变，未灰度方不受影响 | HypitContractTest + edge RoutesTest |
| B 内部协议 | 是 | 新 kind×2（project-package.*）+ 工具 kind 白名单扩 | `y1.hypit-engine-port@1` 不改版；CommandStore 幂等不变 | backend 测试 |
| 数据库 | 否 | 无新表新列（复用 hypit_job.checkpoint_json 等） | — | 既有 IT |
| 部署/配置 | 是 | `.env.example` 追加 capture 缓存键（D-07） | 新键有默认值，未配置不启用 capture 工具（显式 409） | compose 契约测试 |
| 文档/状态 | 是 | 任务书 README、进度指南、coverage 契约 | docs:status / docs:links | C107F-07 |

---

## 3. 技术决策（冻结）

| 决策项 | 结论 |
|---|---|
| 语言/框架 | 不新增依赖、不换版本；Node 24.14.1 + Java 25 + Spring Boot 4 既有栈 |
| 新增依赖 | 无（MUST NOT 新增任何包） |
| 文件布局 | 不新建服务；B 侧改动集中在 `commands/dispatcher.ts`、`tools/*`、`project-package/`、`runner/`、`engine/vocabulary.ts`；J 侧集中在既有 Controller/Service |
| 错误处理 | 沿用 `DispatchError(code,message)`（B）与 `IntelligenceException(status,code,message)`（J）；新错误码仅 §6.5 表列的四个 |
| 命名约定 | 工具公开 id == B kind 字符串（D-01）；新 J 方法名沿用所在类既有风格 |
| 配置变更 | 仅 D-07 一个 env 键 + `.env.example` 行 |

### 决策记录

#### D-01：工具公开 id 以 B kind 为准，契约向实现对齐

- 决策：`contracts/hypit-tools.v1.json` 工具 id 重命名为 B dispatcher 真实 kind：`transcribe→speech.transcribe`、`measure→speech.measure`，新增 `speech.align/image.transform/image.compose`；`media.* 8、snapshot、capture.screenshot/run/install-browser、packages.build/pack/install/status` 保持。最终契约 22 个工具。
- 原因：B kind 是运行真名；反向改 B 需动 dispatcher+全部 backend 测试+J 白名单三处且无行为收益。
- 放弃方案：B kind 改名对齐契约旧 id（改动面大三倍）。
- 依据/权限：授权规划者（纯命名对齐，无产品语义变化）；上游 CLI 命令名经 `upstreamEntry` 字段继续可追溯。
- 约束级别：冻结（契约 id 是公开面）。

#### D-02：project-package B 响应补 `fileCount`

- 决策：`ExportReceipt` 增加 `readonly fileCount: number`；`ImportReceipt` 不变。J 读取代码零改动（J 已按 `artifactRoot/fileCount/manifest` 与 `projectId/revision/fileCount` 读取）。
- 依据：J 现网代码期望字段为准（FACT）；B 补齐是最小对齐。

#### D-03：runtime/actions 分动作实装，init/use/unset 显式 409

- 决策：`action=doctor` 返回聚合健康（引擎就绪、programs 两服务 health、render 容量占用、活跃 build 数）；`action=up`＝幂等确保 whisperx+image-opencv ready（内部复用 `programs.prepare/up` 管线，不旁路）；`action=down`＝存在活跃 build 且 `expectedActivityHash` 缺失/不匹配 → 409 附影响清单（buildId 列表），否则两程序 down；`action=init/use/unset` → 409 `hypit_runtime_managed`（Profile 由 `RUNTIME_PROFILE_PUT` 平台管理，107-1 §6.2 语义在嵌入式 runtime 下的显式化）。
- 放弃方案：从契约删除这三动作（删契约破坏 76 路由锁）。
- 约束级别：冻结（行为语义）。

#### D-04：agent-jobs 生产投递采用「planner 首步」

- 决策：POST `P/agent-jobs` 落 `hypit_job(kind=hypit.agent, state=queued)`，`checkpoint_json={stepIndex:0, scope, intent, brief, assetIds, baseRevision, actions:[]}`；`HypitAgentStepService` 增加 planner 首步语义——`actions` 为空且 `stepIndex=0` 时第一步经平台 LLM 执行环生成 action 计划（结构校验失败最多重试 1 次，仍失败 → job failed 留证），写入 checkpoint 后逐步执行既有 `runStep` 流水。**禁止**在空 actions 时直接 succeeded（现测试语义仅适用于测试预置 actions 的场景，生产入口必须先 planner）。
- scope 收敛：服务端按 intent 收窄 allowedTools（analyze=只读工具集；author/revise=+packages.build/pack+changeset 类；review=+snapshot+feedback 类），浏览器声明不能扩权（沿用 `HypitAgentScope`）。
- 放弃方案：要求调用方自带 actions（把 LLM 计划外移到客户端，违背 107-2「不需要用户另开终端 coding agent」）。
- 约束级别：冻结。

#### D-05：snapshot 只经 preview session，不接受 URL

- 决策：`snapshot` 工具输入 `previewSessionId`（必填）+ `at[]/ranges[]` + `grid?/pageSize?`；B 侧由 preview session 注册表定位 (project, runFile, revision)，经引擎编译获得 `HyperframesDocument`，`snapshotSchedule→snapshotFrames`（原生 `renderHyperframesFrames` 渲染），产物帧图注册为资源句柄返回。URL/端口一律服务端生成（契约 testCases 原文语义）。assetId 直连（不经 session）的形态本任务不做（上游 `hypit snapshot <index.html>` 是本地开发流，平台面无对应场景）。
- 约束级别：冻结。

#### D-06：capture 走 broker 受限通道，不接 runner

- 决策：`capture.screenshot`（URL 输入，经 `resources/url-policy.ts` 逐跳校验，输出落资源句柄）、`capture.run`（脚本从工程 head 读取 workspace-relative 路径，`assertRestrictedCaptureScript` 静态门禁 + `assertWithinOutputRoot` 输出约束 + 默认禁网）、`capture.install-browser`（operator 语义但经 TOOLS_INVOKE 与 `media.prepare-fetch` 同列——现网 MEDIA_TOOLS 已含 operator 级 prepare-fetch，一致处理）。runner `capture` kind 保持 NOT_IMPLEMENTED（本任务不实装，范围外）。
- 依据：107-1 §318「网页 capture 的外部资源经可信侧 URL 策略检查后进入限定的资源转发通道」——broker 受限通道即该设计，runner capture 是作者代码内联采集的另一路径，另案。
- 约束级别：冻结。

#### D-07：capture Chrome 缓存目录配置键

- 决策：新增 env `HYPIT_CAPTURE_BROWSER_CACHE`（默认空＝capture 工具未部署；非空＝启用）。B `config.ts` 增 `captureBrowserCache` 字段；`deploy/hypit/.env.example` 增行并注释；未配置时 `capture.screenshot/run/install-browser` 返回 409 `hypit_capture_not_configured`，不假装可用。compose 卷按需挂（full 层 `compose.full.yml` 已有 browsers 卷，追加路径即可）。
- 约束级别：冻结。

#### D-08：作者包编译迁 runner `compile` kind

- 决策：`tools/packages.ts runBuild/runPack` 的 tsc 执行改为经 `runner/client.ts` 派发 `kind="compile"`：staged 目录以 slot-local 路径传递（大文件不出帧——协议既定模式），runner 内 spawn tsc（复用 broker 现有 tsconfig.build.json 生成逻辑，生成物随 staged 目录落盘），broker 只收诊断与产物句柄。`NOT_IMPLEMENTED_KINDS` 保持 `{executeLocal,capture,cancel}` 不变。
- 放弃方案：保持 broker spawnSync（现网行为，与 K10.4「作者代码隔离」目标冲突）。
- 约束级别：冻结（目标行为）；runner 内部 staging 细节可等价裁量（判据：broker 进程无作者代码 tsc 子进程 + 全部既有 packages 测试绿）。

### 3.1 端到端接线与职责

| 链路段 | 入口/符号 | 本次改动 | 上游输入→下游交付 | 负责卡 | 接通证据 |
|---|---|---|---|---|---|
| export | J `HypitProjectPackageService.export` → sidecar kind `project-package.export` → B `runKind` case → `exportProjectPackage` | 补 case + 上下文构造 + fileCount | `{projectId,title?,selectedRun?}` → `ExportReceipt` | 01 | TC-F01-01/03 |
| import | J `import_` → kind `project-package.import` → `importProjectPackage`（broker 生成 newProjectId） | 同上 | `{artifactRoot}` → `ImportReceipt` | 01 | TC-F01-02/03 |
| speech/image 工具 | J `runTool`（白名单+）→ sidecar kind `speech.*/image.*` → B `runSpeechTool/runImageTool`（已存在） | 仅 J 白名单 + 契约对齐 | `{assetId,language}` 等 → 词级时间/估时/图产物 | 02 | TC-F02-01～03 |
| snapshot | J `runTool`（+snapshot）→ B 新 kind `snapshot` → preview 注册表 → `snapshotFrames` → 资源句柄 | 新 kind 处理器 | `{previewSessionId,at[]/ranges[]}` → `frames:[{assetId,frameIndex}]` | 03 | TC-F03-01/02 |
| capture | J `runTool`（+capture.*）→ B 新 kind → url-policy/受限脚本 → 资源句柄 | 新 kind 处理器 + D-07 配置 | `{url,viewport?…}` / `{scriptPath,args?}` → `outputs` | 03 | TC-F03-03/04 |
| agent-jobs | J 新 `HypitAgentJobService` → `hypit_job` INSERT → `HypitAgentWorker.runScheduled`（既有）→ `HypitAgentStepService`（+planner 首步） | 投递者 + planner + GET/POST | `{requestId,intent,brief,…}` → AcceptedJob → 动作行/终态 | 04 | TC-F04-01～04 |
| jobs actions POST | J `HypitJobController.submitAction`（resume/cancel）→ job 状态机 | 换桩为实装 | `{requestId,action,input?}` → resumed/canceled | 04 | TC-F04-03/04 |
| runtime actions/paths | J `HypitRuntimeController.action/paths` → sidecar（复用 programs/buildOps 面） | 换桩为实装 | §6.2 契约载荷 → 健康摘要/影响清单/路径清单 | 04 | TC-F04-05～07 |
| vocabulary 扩展 | J `/vocabulary`（surface/visual 参数透传）→ B `describeVocabulary` 扩展 → G 原生 `listSurfaces/visualSchema` | 参数+字段扩展 | `?surface=<pkg>&visual=<shape>` → 词法条目 | 05 | TC-F05-01/02 |
| packages.build 隔离 | B `runBuild` → `runner/client` `compile` kind → runner tsc | 迁移执行位置 | staged 路径 → 诊断+产物 | 06 | TC-F06-01～03 |

---

## 4. 目标行为（差异面）

### 4.1 行为变化表（全部为「声明了但不可用 → 可用」，无既有可用行为变化）

| 场景 | 当前行为 | 目标行为 |
|---|---|---|
| POST export/import（真 broker） | `unknown_kind` 失败 | 全链成功，幂等重放回读（K06.1） |
| POST `P/agent-jobs` | 503 桩 | 202 AcceptedJob；worker 消费；planner 首步生成计划；超步/缺料 waiting_input |
| GET `P/agent-jobs` | 503 桩 | 本人任务列表（id/intent/state/stepIndex/createdAt，按需分页 limit/after） |
| POST `P/jobs/{id}/actions` resume | 503 桩 | waiting_input → queued+input 并入 checkpoint；scope 不扩张；非 waiting_input → 409 `hypit_state_conflict` |
| POST `P/jobs/{id}/actions` cancel | 503 桩 | queued/running → canceled（幂等，终态重复无新副作用） |
| POST `/runtime/actions` | 503 桩 | D-03 语义（doctor/up/down 实装；init/use/unset 409 `hypit_runtime_managed`） |
| GET `/runtime/paths` | 503 桩 | operator 逻辑路径清单（§6.3-F06） |
| TOOLS_INVOKE `speech.*/image.*` | 400 `hypit_unsupported_action` | 202 AcceptedJob → 产物落素材库；transcribe 前置程序闸 |
| TOOLS_INVOKE `snapshot/capture.*/packages.build/pack` | 400 同上 | 202 → §6.2-F04 各产出 |
| GET `/vocabulary?surface=…` | 忽略参数 | 返回该包 surface 词法；`?visual=` 返回形状 schema |
| `packages.build` 执行位置 | broker spawnSync tsc | runner 进程内 tsc（D-08） |

### 4.2 状态定义（仅新增面）

Agent job 状态沿用 `hypit_job.state`（queued/running/succeeded/failed/canceled）+ checkpoint 内 `phase: planning|executing|waiting_input`（waiting_input 表现为 state=running 且租约释放、等待 resume，或 state 专用值——**采用前者**：state 保持 running、`lease_until` 置空、events 发 `waiting_input` 终帧，resume 后重新排队；避免新增 state 枚举破坏既有 claim SQL）。resume/cancel 迁移规则：

```text
waiting_input(=running+无租约+phase=waiting_input) --resume(input)--> queued（checkpoint.stepIndex 不变、input 并入 checkpoint.inputs）
waiting_input --cancel--> canceled
running(有租约) --cancel--> canceled（worker 侧在下一 step 边界观察到 canceled 标记即停，K06.1 既有语义）
任意终态 --resume/cancel--> 409 hypit_state_conflict（幂等 cancel 例外：canceled 上重复 cancel 返回 200 无副作用）
```

---

## 5. 业务规则

### 5.1 输入规则（新增端点/工具的权威输入）

| 字段 | 类型 | 必填 | 默认/范围 | 空值处理 |
|---|---|---:|---|---|
| agent-jobs POST `intent` | string | 是 | 枚举 `analyze/plan/author/review/revise` | 空/未知 → 400 `hypit_invalid_input` |
| `brief` | string | 是 | 1..8000 字符 | trim 后空 → 400 |
| `assetIds` | uuid[] | 否 | ≤16 | 缺省=[] |
| `baseRevision` | bigint | 否 | 当前 head | null=受理时 head |
| `scope` | object | 否 | 服务端按 intent 收窄（D-04），调用方只可缩不可扩 | 缺省=intent 默认集 |
| actions POST `action` | string | 是 | `resume/cancel` | 未知 → 400 |
| actions POST `input` | object | 否 | 仅 resume；键为补充资料句柄/文本，≤64KiB JSON | resume 无 input 允许（纯确认继续） |
| runtime actions `action` | string | 是 | `init/use/unset/up/down/doctor` | 未知 → 400 |
| `expectedActivityHash` | string | 否 | 仅 down；与 `build.activity` 聚合 hash 比对 | 有活跃 build 且缺失 → 409 附清单 |
| `endpointIds` | string[] | 否 | 仅 up/down 限定程序子集 | 缺省=两程序 |
| snapshot `previewSessionId` | uuid | 是 | 存活 session | 过期/不存在 → 409 `hypit_session_expired` |
| snapshot `at[]`/`ranges[]` | number[]/object[] | 二选一 | at∈[0,frameCount)；ranges 每页 ≤50 帧、页游标 | 都缺省 → 默认 [0,中,尾]（snapshotSchedule 语义） |
| capture.screenshot `url` | http(s) | 是 | 经 url-policy 逐跳校验 | 拒绝目标 → 400 `hypit_url_denied` |
| capture.run `scriptPath` | string | 是 | workspace-relative，存在于工程 head | 越出工程/不存在 → 400 |
| speech.transcribe `assetId/language` | uuid/bcp47 | 是 | 语言 `zh/en/ja/es/auto`（whisperx alignmentLanguages 交集） | 未就绪程序 → 409 `hypit_program_not_ready` |

### 5.2 校验规则（服务端权威，前端可早拒）

| 规则 | 条件（顺序） | 位置 | 失败结果 | 禁止副作用 | TC |
|---|---|---|---|---|---|
| RULE-F01 | 白名单→requestId→body 结构→业务枚举（依次） | J Controller/Service | 400 `hypit_invalid_input` | 不落 command/job 行 | TC-F02-01 |
| RULE-F02 | transcribe 前置：whisperx 程序 health 身份匹配 | B tools/speech.ts（已实现） | 409 `hypit_program_not_ready` | 不调 LLM/不产伪词表 | TC-F02-02 |
| RULE-F03 | agent scope 收窄：intent 默认集 ∩ 调用方 scope，只缩不扩 | J 投递时 | 越权项被剔除并记事件（不 400） | 后续 action 越权 → action 行 failed `hypit_scope_denied` | TC-F04-02 |
| RULE-F04 | resume 不扩张：input 并入后重新计算 allowedTools ⊆ 原集 | J StepService | 扩张项拒（action failed） | 不新增预算/步数上限不变 | TC-F04-03 |
| RULE-F05 | down 保护：活跃 build 数>0 且 hash 不匹配 → 409 影响清单 | J runtime action | 409 `hypit_activity_conflict` + buildIds | 程序不动 | TC-F04-06 |
| RULE-F06 | capture 默认禁网 + 静态门禁 + 输出根约束 | B tools/capture.ts（已实现） | 400 `hypit_invalid_input` | 无浏览器进程产生 | TC-F03-04 |

### 5.3 业务判断（关键分支）

```text
IF action="down" AND 活跃build>0 AND (expectedActivityHash 缺失 OR != activityHash)
THEN 409 hypit_activity_conflict（响应含 buildIds 与当前 activityHash）
ELSE IF action="down" THEN programs down（幂等）
IF agent-job POST 且 intent 合法
THEN INSERT job(queued) + 事件 accepted + 202；planner 首步失败(1 次重试后) → failed 留证
IF TOOLS_INVOKE 且 tool ∈ J 白名单
THEN 202 AcceptedJob（复用 runAssetJob）；否则 400 hypit_unsupported_action（现状不变）
```

### 5.4 权限

| 主体 | 允许 | 禁止及拒绝 | TC |
|---|---|---|---|
| project owner | TOOLS_INVOKE 19 工具、agent-jobs 本人 GET/POST、本人 job actions、export | 他人工程 → 404/403（`requireReadyOwner` 既有） | TC-F02-01 |
| operator | runtime/actions、runtime/paths、programs | 非 operator → 403 `hypit_operator_required`（既有） | TC-F04-05 |
| 全局 `/jobs/{id}/actions` | operator 或提交 account（107-1 §6.2「全局 Job 校验 operator 与提交 account」） | 其余 403 | TC-F04-04 |

不变量：同 (account,requestId) 幂等键至多一条 command（既有唯一索引）；action 行 `UNIQUE(job,step_index)` 幂等；错误响应不含 secret/绝对宿主路径（paths 端点除外且 operator-only）。

---

## 6. 接口契约（新增/实装面；既有 8 media 工具与其余 69 路由不变）

### 6.0 通用

- 信封：站内 `{success,data,error}`（grassland-http）；工具调用返回 `AcceptedJob`（jobId + 轮询/SSE 既有面）。
- 幂等：全部 POST 带 `requestId`（command 唯一键）；DELETE 类 Idempotency-Key。

### 6.1 请求信息（逐端点）

#### API-F01 `POST /api/hypit/projects/{projectId}/export`（实装修复，路由既有）

- 鉴权 project owner；body `{requestId, title?, runFile?}`；幂等 `requestId`。
- 成功 202 → job 结果 `{artifactRoot, fileCount, manifest}`；下载走既有受保护 export 资源通道（107-1 §6.2 P/export 行）。

#### API-F02 `POST /api/hypit/imports`（实装修复）

- 鉴权登录账号；multipart bundle；幂等 `requestId`。
- 成功 202 → `{projectId, revision, fileCount}`；新工程 provisioning → ready。

#### API-F03 `POST P/tools/{tool}` 白名单扩展（tool ∈ 19，见 D-01+§1.4）

- 逐工具输入见 §5.1 与 `contracts/hypit-tools.v1.json`（重生成后）对应条目；返回 202 AcceptedJob，产物统一落素材库/资源句柄，**不返回任意本地路径**（契约原句）。

#### API-F04 `GET/POST P/agent-jobs`

- POST body `{requestId,intent,brief,assetIds?,baseRevision?,scope?}` → 202 `AcceptedJob`。
- GET `?limit(≤100,默认20)&after(jobId 游标)&state?` → `{items:[{jobId,intent,state,stepIndex,maxSteps,createdAt,updatedAt}]}`（本人工程）。

#### API-F05 `POST P/jobs/{jobId}/actions`（含项目级别名路由）

- body `{requestId, action:"resume"|"cancel", input?}` → 202（受理，事件流见分晓）或终态 409；GET（已实装）不变。

#### API-F06 `POST /api/hypit/runtime/actions` ／ `GET /api/hypit/runtime/paths`

- actions body `{requestId, action, profileId?, endpointIds?, expectedActivityHash?}`；doctor 返回 `{engine:{ready,version}, programs:{whisperx.local:{state,health}, "image.opencv.local":{…}}, render:{active,max}, activityHash}`；up/down 返回受理/完成状态。
- paths 返回 `{logical:{projectsRoot:"<dataRoot>/projects", artifactsRoot, stagingRoot, stateRoot, programsHome, captureBrowserCache?(配置才显), runnerSlots}, hostPathsRevealed:false}`——**逻辑标识**不含宿主绝对路径；宿主路径显示属部署者本地诊断（`deploy/hypit/README.md` 记录获取方式），不经公网 API（安全收窄，与 107-1 §6.2「真实宿主路径只向部署者显示」一致——公网面只给逻辑路径）。

#### API-F07 `GET /api/hypit/vocabulary?surface=<pkg>&visual=<shape>`

- 扩展：无参=现状（providers/programs/alignmentLanguages）；`surface=<packageId[,packageId…]>` → `{surfaces:[{packageId, module, imports, attributes[], children[], ports[], example}]}`（原生 `listSurfaces` 字段）；`visual[=<shape>]` → 形状 schema（shape 枚举=上游 14 形状；缺省列形状清单）。cursor 分页沿 §6.2 `/vocabulary` 行。

### 6.2 错误契约（新增码全列）

| HTTP | code | 触发 | 调用方动作 |
|---|---|---|---|
| 400 | `hypit_unsupported_action` | 工具/动作未登记（既有码，语义不变） | 不重试 |
| 400 | `hypit_invalid_input` | 结构/枚举/越界（既有码） | 修正入参 |
| 409 | `hypit_runtime_managed` | init/use/unset（D-03） | 改走 RUNTIME_PROFILE_PUT |
| 409 | `hypit_activity_conflict` | down 有活跃工作且 hash 不匹配 | 带 hash 重试或先取消 build |
| 409 | `hypit_capture_not_configured` | capture 工具未部署（D-07） | 联系 operator |
| 409 | `hypit_state_conflict` | 终态 resume 等（既有码复用） | 刷新状态 |
| 409 | `hypit_program_not_ready` / `hypit_session_expired` | 既有码，本任务接线消费 | 起程序/重开 session |

### 6.3 契约不变量

- B kind 集 ⊇ J 白名单集 = 契约工具集 − {packages.install,status}（operator 专端点）；由 `tests/deployment/hypit-tools.contract.test.ts` 静态锁三侧（解析 contracts JSON、`HypitAssetService` 源码 Set、B `tools/*.ts` kind 数组）。
- 七条路由从「503 pending」变为真实语义后，`HypitContractTest` 与 edge `RoutesTest` 的既有断言必须更新且逐条对应 §6.1 行为（不允许仅删断言）。
- 幂等：export/import 同 requestId 重放回读不重打包；agent-jobs 同 requestId 返回同 jobId。
- 日志/事件禁止携带凭据、宿主绝对路径、bundle 字节。

---

## 7. 数据模型与迁移

N/A：无新表/新列/新迁移。`hypit_job.checkpoint_json` 扩展携带 `{phase,intent,brief,inputs}` 键（JSON 自由列，无 schema 变更）；`hypit_job_action` 复用。R-LIFECYCLE 登记不新增资源（job/action/command 均已登记），C107F-07 复跑 `quality:lifecycle` 确认零漂移。

---

## 8. UI 实现规格

N/A：无 UI 改动（现有前端 `exportProject` 等 API 函数已对接这些端点，实装后自然可用；不新增面板）。

---

## 9. 全局约束

### 9.1 文件白名单（写入行；只读参考卡内列）

| 标识 | 精确路径 | 权限 | 所属卡 | 允许改动 |
|---|---|---|---|---|
| W01 | `platform-hypit/backend/src/commands/dispatcher.ts` | 写 | 01/03 | 新增 `project-package.export/import` case；新增 snapshot/capture kind 路由与谓词 |
| W02 | `platform-hypit/backend/src/project-package/export.ts` | 写 | 01 | `ExportReceipt.fileCount` + 返回处赋值 |
| W03 | `platform-hypit/backend/tests/workspace/project-transfer.test.ts` | 写 | 01 | dispatcher 级用例（经 runCommand 全链） |
| W04 | `platform-java/.../hypit/template/HypitProjectPackageService.java` | 写 | 01 | 仅当 receipt 字段映射需对齐（预计零改动，核实后如无需改则记录依据不动） |
| W05 | `platform-java/.../hypit/template/HypitTemplateIT.java`（test） | 写 | 01 | WireMock 桩响应字段与 B receipt 一致 + 负向（unknown kind 透传） |
| W06 | `platform-java/.../hypit/asset/HypitAssetService.java` | 写 | 02/03 | `MEDIA_TOOLS` → 扩展集（重命名为 `TOOLS` 或保名扩容，符号内聚） |
| W07 | `contracts/hypit-tools.v1.json` | 写 | 02/03 | D-01 重生成（22 工具） |
| W08 | `tests/deployment/hypit-tools.contract.test.ts` | 写（新建） | 02 | 三向一致静态锁（03 落地后扩至 snapshot/capture/packages） |
| W09 | `platform-hypit/backend/src/tools/snapshot.ts` | 写 | 03 | 新增 kind 处理器导出（保留纯函数不动） |
| W10 | `platform-hypit/backend/src/tools/capture.ts` | 写 | 03 | 同上（受限语义不动） |
| W11 | `platform-hypit/backend/src/config.ts` | 写 | 03 | `captureBrowserCache` 字段 + D-07 校验 |
| W12 | `deploy/hypit/.env.example` | 写 | 03 | 新键行 |
| W13 | `platform-java/.../hypit/api/HypitJobController.java` | 写 | 04 | `submitAction` 实装 |
| W14 | `platform-java/.../hypit/api/HypitProjectController.java` | 写 | 04 | agent-jobs GET/POST、项目级 actions 实装 |
| W15 | `platform-java/.../hypit/api/HypitRuntimeController.java` | 写 | 04 | `action/paths` 实装 |
| W16 | `platform-java/.../hypit/agent/HypitAgentJobService.java` | 写（新建） | 04 | 投递/列表/resume/cancel 编排 |
| W17 | `platform-java/.../hypit/agent/HypitAgentStepService.java` | 写 | 04 | planner 首步 + waiting_input 相位 |
| W18 | `platform-java/.../hypit/agent/HypitAgentWorker.java` | 写 | 04 | claim SQL 兼容 waiting_input（无租约 running 不重领）；canceled 边界 |
| W19 | `platform-java/.../hypit/agent/HypitAgentScope.java` | 写 | 04 | intent→allowedTools 收敛表 |
| W20 | `platform-hypit/backend/src/engine/vocabulary.ts` | 写 | 05 | surface/visual 扩展（import 上游函数经 bootstrap） |
| W21 | `platform-java/.../hypit/api/HypitKnowledgeController.java` | 写 | 05 | vocabulary 参数透传 |
| W22 | `platform-hypit/backend/src/tools/packages.ts` | 写 | 06 | runBuild/runPack 迁 runner |
| W23 | `platform-hypit/backend/src/runner/{client,server}.ts` | 写 | 06 | compile payload 扩展（staged 路径/产物回传） |
| W24 | J/B 测试文件（`.../hypit/**` test 目录与 `platform-hypit/backend/tests/**` 对应新用例） | 写 | 各卡 | 仅新增/扩展本卡 TC |
| W25 | `tests/e2e/hypit-clone.spec.ts` | 写 | 01 | export→import roundtrip 用例（隔离栈；跑不到如实 skip 带因） |
| W26 | `docs/任务书/README.md`、`docs/草场开发进度与续接指南.md`、`contracts/hypit-coverage.v1.json`、`platform-hypit/knowledge/`（若 vocabulary 文档索引需补） | 写 | 07 | 状态同步 |
| W27 | `docs/任务书/草场任务书-107-fix-1-Hypit功能复刻缺口收口.md` | 写 | 07 | 卡表状态回写（唯一允许持久化状态的文件） |
| — | `platform-hypit/upstream/**` | 禁止 | 全部 | 任何改动即 V01 红 |
| — | `contracts/hypit-api.v1.json` | 禁止 | 全部 | 76 路由锁不动（本任务实装既有声明，不改契约） |

产物目录：`test-artifacts/task-107/fix1/<卡号>/`（gates.txt 与必要日志；不提交大文件）。

### 9.2 项目铁律命中

- R-JAVA：新 J 代码 WebFlux 非阻塞（LLM/planner 经既有执行环响应式入口，禁止 `.block()`）；服务边界不越 intelligence。
- R-AI：planner 首步经 `FrozenTextExecutionService` 既有入口（executeIndependent 族），不旁路 ai_run/预算记录。
- R-QUALITY：新测试进各层既有位置（backend tests / J src/test / tests/deployment / tests/e2e）；`npm run quality:lifecycle` 零漂移；契约测试进 CI hypit-contract 层（文件名以 `hypit-` 前缀自动入选——见 `.github/workflows/ci.yml` 该 job 显式三文件清单，需把新文件加进清单，属 W28 追加 ` .github/workflows/ci.yml` 写入（C107F-02））。
- R-LIFECYCLE：不新增资源种类；agent job 崩溃恢复走 K06.1 既有语义。
- R-DIR：手工脚本 `scripts/acceptance/`（若有）、本机 `scripts/local/`；证据 `test-artifacts/task-107/fix1/`。
- R-UI/R-LAYER/R-ENTRY：N/A（无 UI/无入口/无部署拓扑变更）。

> 追加 W28：`.github/workflows/ci.yml`（仅 hypit-contract job 的 vitest 文件清单行加 `tests/deployment/hypit-tools.contract.test.ts`），属 C107F-02。

### 9.3 验证环境事实

| 项目 | 值 |
|---|---|
| shell | zsh；Node 前置 `export PATH="$HOME/.nvm/versions/node/v24.14.1/bin:$PATH"` |
| 引擎 G | `bash scripts/acceptance/build-107-engine.sh --no-install`（03/06 卡涉渲染/runner 需完整物化+依赖安装） |
| Java | `source ../scripts/lib/java-runtime.sh && ensure_java_runtime 25`（bash、platform-java/ 下） |
| B 测试 | `cd platform-hypit/backend && npm run typecheck && npm test`（V04） |
| J 测试 | `./gradlew :services:intelligence-service:test --tests '*Hypit*' --rerun-tasks --no-build-cache --max-workers=2 --console=plain`（V05；Docker/Testcontainers 需可用） |
| e2e | 隔离栈同 DH_E2E 配方（`deploy/hypit/compose.test.yml`）；本机跑不了时 NOT_RUN 如实 |
| 产物 | `test-artifacts/task-107/fix1/` |

### 9.4 安全规格（命中项）

| 类别 | 要求 | TC |
|---|---|---|
| 外部输入 | capture URL 逐跳校验（url-policy 复用）；snapshot 拒 URL 输入；agent 脚本仅工程内 | TC-F03-03/04 |
| 隔离 | 作者代码 tsc 只在 runner（network_mode:none 槽）；capture 脚本静态门禁+禁网默认 | TC-F06-02 |
| 信息泄露 | paths 只回逻辑路径；日志禁宿主绝对路径/凭据/bundle 字节 | TC-F04-07 |

### 9.5 约束适用矩阵

R-JAVA→全部 J 卡；R-AI→04；R-QUALITY→02/07；R-LIFECYCLE→04/07；R-DIR/R-SAFE→全部；R-UI/R-LAYER/R-ENTRY→N/A（无 UI/分层/入口改动）。

---

## 10. 开发计划与任务总表

| 卡 | 标题 | 端 | 需求 | 主要写入 | 依赖 | 验收 | 状态 |
|---|---|---|---|---|---|---|---|
| C107F-01 | 工程包导出导入接线与契约锁定 | B+J | REQ-F01 | W01～W05、W25 | 无 | AC-F01-01～03 | NOT_STARTED |
| C107F-02 | speech/image 工具开放与工具契约三向锁 | J+契约 | REQ-F02/F03 | W06～W08、W28 | 无（可与 01 并行） | AC-F02-01～04 | NOT_STARTED |
| C107F-03 | snapshot/capture/packages.build·pack 工具通道 | B+J | REQ-F04 | W01、W09～W12、W06、W07、W08 | C107F-02（契约测试基建） | AC-F03-01～04 | NOT_STARTED |
| C107F-04 | 七条 pending 桩清偿（agent-jobs/actions/runtime 面） | J+B 读 | REQ-F05/F06 | W13～W19 | 无（planner 依赖 02 的 speech 工具可用仅影响 e2e 深度，单元层独立） | AC-F04-01～07 | NOT_STARTED |
| C107F-05 | vocabulary surface/visual 词法补齐 | B+J | REQ-F07 | W20、W21 | 无 | AC-F05-01～02 | NOT_STARTED |
| C107F-06 | 作者包编译迁入 runner 隔离 | B | REQ-F08 | W22、W23 | 无（与 03 共写 W01/W22 注意：03 不写 W22，无冲突） | AC-F06-01～03 | NOT_STARTED |
| C107F-07 | 文档收口与最终集成验收 | 文档+门禁 | REQ-F09 | W26、W27 | 全部 | AC-F07-01 | NOT_STARTED |

阶段：M1=01+02（断链修复+契约锁，先红后绿最小面）→ M2=03+04+05+06（能力面补齐）→ M3=07（收口）。共享写入冲突核查：W01 由 01/03 共写——01 加 project-package case、03 加 snapshot/capture 路由，不同 hunk，串行顺序 01→03 已排定；W06 由 02/03 共写同理；W07/W08 由 02 建、03 扩。

### 10.4 高风险

| 风险 | 影响 | 等级 | 最早验证 | 不成立时 |
|---|---|---|---|---|
| RISK-F01 | planner 首步 LLM 输出不稳定导致 agent-jobs 不可用 | 中（结构校验+1 次重试+失败留证兜底） | TC-F04-01（fixture LLM 桩驱动结构断言） | 降级：failed 留证属设计内，不假成功；若执行环入口缺失按 §13 BLOCKED |
| RISK-F02 | runner compile payload 传递 staged 大目录超帧限 | 低（slot-local 路径不出帧） | TC-F06-01 | 等价调整传递方式（D-08 判据内） |
| RISK-F03 | waiting_input 相位与既有 claim SQL 冲突（重领假死） | 中 | TC-F04-03/04（租约语义用例） | 修正 claim 过滤（W18 范围内） |
| RISK-F04 | e2e 隔离栈本机不可跑 | 低 | C107F-01 验收时探测 | NOT_RUN 如实记录，B/J 层测试仍须全绿 |

---

## 11. 任务卡

### 卡 C107F-01：工程包导出导入接线与契约锁定

**执行包**：107-fix-1 v1.0.0；REQ-F01；执行者按 §10 顺序。
**类型与完成边界**：实现——`project-package.export/import` 两 kind 经 B dispatcher 真实路由，J→B→receipt 全链字段一致，B dispatcher 级测试 + J IT 桩字段锁定 + e2e roundtrip 三层证据。
**背景**：FACT——J 派发两 kind，B `runKind` 无 case → `unknown_kind`；`HypitTemplateIT` WireMock 桩掩盖漂移；B 实现与函数级测试（project-transfer.test.ts）齐备。
**输入与前置交付物**：无依赖。
**输出与移交**：dispatcher 两 case + `ExportReceipt.fileCount`；C107F-07 读取 gates.txt。
**必读**：§3 D-02/D-03 无关本卡、D-01 无关；§6.1 API-F01/F02；§2.4 相关文件表；`platform-hypit/backend/src/commands/dispatcher.ts`（runKind 结构与 DispatchError 风格）；`project-package/{export,import}.ts` 全文；J `HypitProjectPackageService` 全文。

**改动文件**：W01（dispatcher 新增两 case 与上下文构造：`ExportContext{projectsRoot,distributionRoot,sourceCommit}`/`ImportContext{projectsRoot,stagingRoot,provisionTemplateDir,provisionTemplateFiles}`——字段来源对齐 `dispatcher.ts` 既有 `options` 装配处，缺的（如 sourceCommit）从 `HypitProperties`/模板 catalog 取同源值）、W02、W03、W05、W25；W04 预计零改动（核验后记录）。

**源码定位**：

```text
dispatcher.ts: switch(kind) 末尾 default 前 —— 现有 case 集见 §2.3；插入点在 "feedback.mutate" case 之后。
export.ts:50 exportProjectPackage(ctx, projectId, {title?,selectedRun?}) → ExportReceipt{artifactRoot,manifest}（+fileCount=W02）
import.ts:46 importProjectPackage(ctx, bundleRootRaw, {newProjectId,requestId,title?}) → ImportReceipt{projectId,revision,manifestHash,fileCount}
  —— kind 处理器内 newProjectId=crypto.randomUUID()、requestId=commandId 派发传入。
```

**目标行为**：§6.1 API-F01/F02；kind 处理器错误直接透传 `DispatchError` code（`not_found/invalid_input/too_large` → J 既有映射）。

**MUST**：1. 幂等由 CommandStore 既有 (commandId,payloadHash) 兜底，kind 处理器不自建幂等；2. export 的 `selectedRun` 缺省 `main.svrun`；3. import 拒绝时零工作区残留（函数既有语义，接线不得破坏）；4. J IT 桩 JSON 的字段集与 B receipt 完全一致（含 fileCount）。
**MUST NOT**：不把 bundle 内容写日志；不改 `contracts/hypit-api.v1.json`；不动 import staging 布局。

**做法**：

| 步 | 动作 | 检查点 |
|---|---|---|
| 1 | W01 加两 case（含上下文构造函数 `projectPackageContexts(options)` 或内联） | backend typecheck 过 |
| 2 | W02 补 fileCount（manifest.files.length） | project-transfer.test.ts 更新断言 |
| 3 | W03 新增 dispatcher 级用例：`runCommand(kind="project-package.export")` 真链路（Harness 造工程→导出→导入→revision=2、哈希一致；重复 commandId 重放回读同 receipt） | `npm test` 全绿 |
| 4 | W05 J IT：桩响应改为与 B receipt 同构 JSON；新增「B 返回 unknown_kind 时 J 透传错误码」负向 | V05 该类绿 |
| 5 | W25 e2e roundtrip（隔离栈可用则真跑；不可用 test.skip 带精确原因——沿 hypit-clone.spec 既有 skip 风格） | spec 收敛 |

**边界**：E01 空 title→缺省；E09 head 漂移（export 读受理时 head 快照）；E16 同 requestId 重放；E19 导入旧格式 bundle→`invalid_input` 拒绝零残留。

**验收**：TC-F01-01（B dispatcher 全链导出）、TC-F01-02（导入 revision=2+哈希）、TC-F01-03（e2e/本地栈 roundtrip，分层如实）；命令：V04、V05（HypitTemplateIT/ProjectPackage 相关）、VFX-契约层。AC-F01-01 Given ready 工程有 head，When POST export，Then 202+job 结果含 artifactRoot/fileCount/manifest 且 fileCount=manifest.files.length；AC-F01-02 Given 已导出 bundle，When POST /imports，Then 新 projectId、revision=2、全部文件 sha256 一致；AC-F01-03 同上经隔离栈端到端（NOT_RUN 如实标注则不算 PASS）。

### 卡 C107F-02：speech/image 工具开放与工具契约三向锁

**执行包**：107-fix-1 v1.0.0；REQ-F02/F03。
**类型与完成边界**：实现——J `MEDIA_TOOLS` 白名单扩至 13（media 8+speech 3+image 2，符号可更名 `TOOLS` 但导出点与引用同步）；`contracts/hypit-tools.v1.json` 重生成对齐 D-01（22 工具全量声明，其中 9 个标 `status:"planned-C107F-03"`…… **否**——契约只收已实装面：本卡后契约=13+packages.build/pack/install/status 4 个已在 B 路由的=17 个；C107F-03 再扩至 22）；新建 `tests/deployment/hypit-tools.contract.test.ts` 三向静态锁并加入 CI 清单（W28）。
**背景**：FACT——B dispatcher 已路由 speech/image（`isSpeechTool/isImageTool`），J 400 拒之；契约文件无测试消费且命名漂移。
**必读**：§3 D-01；§6.1 API-F03、§6.3 不变量；`tools/speech.ts`（speechTools/SpeechToolContext/health 闸）、`tools/image.ts`（imageTools/ImageToolContext/raster 词表约束）、`HypitAssetService.runTool/runAssetJob`。

**改动文件**：W06、W07、W08（新建）、W28。

**做法**：

| 步 | 动作 | 检查点 |
|---|---|---|
| 1 | W06 白名单扩 5 项（注释更新为「= B 已路由的 owner 级 kind」） | J 单测：5 工具 202、未知仍 400 |
| 2 | W07 契约重生成：id=B kind；`upstreamEntry` 保留 CLI 名；输入 schema 从 `tools/*.ts` 类型注释与上游 CLI flags 对齐复核（`--language/--rate` 等）；17 工具 | JSON 合法 |
| 3 | W08 契约测试：解析契约 ids ↔ 正则提取 J 白名单 Set ↔ 正则提取 B `tools/*.ts` 的 kind 数组常量（`mediaTools/speechTools/imageTools/PACKAGE_KINDS`）；断言两两差集为空（packages.install/status 属 B 路由但 J 白名单排除项——断言「差集 ⊆ 预注记排除集」） | 故意改一侧→测试红（防假阳性证据） |
| 4 | W28 CI 清单加文件 | hypit-contract job 绿 |

**边界**：E05 whisperx 未起→409 `hypit_program_not_ready`（TC-F02-02，B 层既有测试 + J 层透传断言）；E01 空 language→400；E02 超长文本 measure→按 B 既有上限拒。

**验收**：AC-F02-01 Given ready 工程+就绪程序，When POST tools/speech.transcribe {assetId,language:"zh"}，Then 202→job 产物词级时间单调；AC-F02-02 Given whisperx down，Then 409 不产伪词表；AC-F02-03 image.transform 走 raster 词表（unknown op 400，B 既有）；AC-F02-04 三向契约测试退出码 0 且人为漂移红（防假阳性：diff 证据）。命令：V04、V05（Asset 相关）、VFX-契约层。

### 卡 C107F-03：snapshot/capture/packages.build·pack 工具通道

**执行包**：107-fix-1 v1.0.0；REQ-F04。
**类型与完成边界**：实现——B 新增 5 个 kind 处理（`snapshot`、`capture.screenshot/run/install-browser`、`packages.build/pack` 中 build/pack 已路由本卡只随白名单开放——**修正**：packages.build/pack 已在 B `PACKAGE_KINDS` 路由，本卡仅 J 白名单+契约收录；真正新增 B 处理器的是 snapshot+capture 三 kind）；D-07 配置。
**必读**：§3 D-05/D-06/D-07；`tools/snapshot.ts`（SnapshotRequest/snapshotSchedule/snapshotFrames 签名——frames 选项为 `renderHyperframesFrames` 第二参）、`tools/capture.ts`（prepareCaptureChrome/RestrictedCaptureScript/assert*）、`resources/url-policy.ts`、`preview/sessions.ts`（注册表 API）、`resources/handles.ts`（registerResource）。

**改动文件**：W01（kind 路由）、W09、W10、W11、W12、W06（白名单至 19）、W07/W08（契约至 22+测试扩）。

**做法**：

| 步 | 动作 | 检查点 |
|---|---|---|
| 1 | W11/W12 captureBrowserCache 配置（空=未部署） | 空配置时三 capture kind 409 `hypit_capture_not_configured` |
| 2 | W09 snapshot 处理器：session→(project,runFile,revision)→经引擎编译取 document（复用 preview 编译路径，不新建）→schedule→frames→registerResource 每帧→`{frames:[{assetId,frameIndex}],pages}` | 单测：默认 [0,中,尾]、at 越界 400、session 失效 409 |
| 3 | W10 capture 处理器：screenshot=URL→url-policy→受限 Chrome 截图→资源；run=工程 head 读脚本→静态门禁→执行→输出根断言→资源；install-browser=prepareCaptureChrome | 复用既有 capture.test.ts 语义，新增 kind 级用例 |
| 4 | W01 路由 + W06 白名单 19 + W07/W08 契约 22 | 三向锁绿 |
| 5 | 渲染浏览器就绪路径（snapshot 需 render Headless Shell；backend 测试若环境不可用按既有 skip 风格带因） | 用例分层如实 |

**边界**：E17 snapshot 只认本工程 session；E04 capture 网络失败留诊断不静默；E14 at/ranges 越界；E12 capture 超时（30s 默认/120s 上限）。

**验收**：AC-F03-01 Given 存活 preview session，When tools/snapshot at=[0,12,24]，Then 202→三帧资源句柄、帧索引对应、无新 Build；AC-F03-02 session 过期→409 `hypit_session_expired`；AC-F03-03 Given 未配置 capture 缓存，Then 三 kind 409 `hypit_capture_not_configured`（配置后 local-web fixture 截图像素>0，沿上游 fixture 家族）；AC-F03-04 capture.run：工程外脚本 400、禁网命中 400、输出越根 400。命令：V04（需引擎 G 完整物化）、VFX-契约层。

### 卡 C107F-04：七条 pending 桩清偿（agent-jobs / actions / runtime 面）

**执行包**：107-fix-1 v1.0.0；REQ-F05/F06。
**类型与完成边界**：实现——七条路由全部换真实语义（§4.1 表）；agent 投递/列表/resume/cancel 服务 + planner 首步 + worker 相位兼容；runtime actions/paths 实装。完成后 `grep -rn "pending(" .../hypit/api/` 命中 0。
**必读**：§3 D-03/D-04；§4.2 状态机；§5.1/5.2/5.4；`HypitAgentWorker`（claim SQL/driveToCompletion/空 actions 语义）、`HypitAgentStepService`（runStep/prepared action/LLM 执行环接入点）、`HypitJobRepository`（insert/claimDue/updateState/saveCheckpoint）、`HypitJobActionRepository`、`programs/manager.ts` 与 `runtime/capacity.ts`（doctor 聚合数据源）、`HypitBuildObserver`（活跃 build 面）、`buildOps.globalActivity`（activity hash）。

**改动文件**：W13～W19 + 对应测试。

**做法**（要点）：

| 步 | 动作 | 检查点 |
|---|---|---|
| 1 | W16 `HypitAgentJobService`：`create`（校验§5.1→INSERT job+checkpoint(planner 相位)→事件 accepted→202）、`list`（本人+游标）、`resume`（waiting_input→queued+input 并入+事件）、`cancel`（状态机§4.2） | 单测全状态迁移+终态 409+幂等 cancel |
| 2 | W17 planner 首步：`runStep` 前置——`stepIndex=0 && actions 空` → 经 FrozenText 执行环生成 action 计划（prompt 模板内置 intent 语义+allowedTools 清单；输出 JSON schema 校验；失败重试 1 次后 job failed 留 `planner_failed`）；随后逐 action 既有流水 | IT：LLM 桩（WireMock/fixture 执行环）驱动 planner 输出好坏两态 |
| 3 | W18 worker 兼容：claim SQL 过滤 `lease_until IS NULL AND phase<>'waiting_input'` 的 running 行不重领（或等价：waiting_input 存 state='queued' + checkpoint 标记——**选等价法**，避免改 claim SQL：resume 前 state 置 'queued'、checkpoint.phase='waiting_input'，claim 到达后 StepService 见 phase 先消费 inputs 再执行——两法取一，判据=不改既有 claim SQL 语义且测试证明无假死） | 崩溃恢复 IT：杀 worker→租约过期→重领续跑 |
| 4 | W19 intent→allowedTools 收敛表（analyze 只读/author+revise 写包/review 快照+feedback） | 越权 action 行 failed `hypit_scope_denied` |
| 5 | W13/W14 actions 提交端点（全局=operator 或提交 account；项目级=owner+projectId 校验） | 越权 403 |
| 6 | W15 runtime：doctor（聚合 B `status`+programs health+capacity+activity hash）、up/down（复用 programs 端点内部服务，不旁路）、init/use/unset 409 `hypit_runtime_managed`、paths 逻辑清单 | §6.1 API-F06 响应断言 |

**边界**：E03 resume 连点（同 requestId 幂等一回执）；E07 终态 resume 409；E15 并发 cancel+worker step 收尾（canceled 优先，无二次副作用）；E20 worker 崩溃重放只发确定性动作；E18 工程 owner 变更后旧 job 操作 403。

**验收**：AC-F04-01 POST agent-jobs →202→事件流出现 planner→executing→（桩计划走完）succeeded，动作行含 ai_run 绑定；AC-F04-02 越权 action 不执行且留痕；AC-F04-03 waiting_input→resume（带 input）→继续→终态，scope 不变（断言 allowedTools 前后同集）；AC-F04-04 cancel 幂等；AC-F04-05 doctor 返回四段聚合且非 operator 403；AC-F04-06 down 有活跃 build 无 hash →409 附 buildIds+当前 hash；带匹配 hash→程序 down；AC-F04-07 paths 只含逻辑键。命令：V05（新增 `HypitAgentJobIT`/`HypitRuntimeActionIT` 类）、V04。

### 卡 C107F-05：vocabulary surface/visual 词法补齐

**执行包**：107-fix-1 v1.0.0；REQ-F07。
**类型与完成边界**：实现——`describeVocabulary` 增 `surfaces(?)`/`visual(?)` 参数化输出（经 bootstrap import 上游 `packages/video-cli/src/vocabulary.ts` 的 `listSurfaces/visualSchema`，以 G 发行版真实包为源，不造目录）；J `/vocabulary` 参数透传。
**必读**：§2.3 两个 vocabulary 文件；`engine/hypit-bootstrap.ts`（@hypit/* import 方式）；上游 `listSurfaces(projectRoot, packages, tags?)`/`visualSchema(shape?)` 签名与 `SurfaceListing` 字段（module/import/attributes/children/ports/example）。
**改动文件**：W20、W21 + 测试。

**做法**：B 扩展（distributionRoot 下的包清单→listSurfaces 真调用；visual 14 形状枚举透传）→ 单测（真实 G 树：任选两包断言字段非空、未知包 400）→ J 透传 + IT。**MUST NOT**：不把 65 知识文档索引与词法混写一个响应（独立 query 参数）；不缓存跨版本（distribution 变更即重算，哈希语义沿既有）。
**验收**：AC-F05-01 `?surface=@hypit/seedance` 返回 SurfaceListing 字段集与上游一致（对拍上游 CLI 输出或源码 schema）；AC-F05-02 `?visual=text-typography` schema 与上游 `visualSchema` 输出同构；无参响应向后兼容（既有字段不动）。命令：V04、V05。

### 卡 C107F-06：作者包编译迁入 runner 隔离

**执行包**：107-fix-1 v1.0.0；REQ-F08。
**类型与完成边界**：实现——`runBuild/runPack` 的 tsc 在 runner 进程执行（`compile` kind）；broker 侧保留 staging/tsconfig 生成与产物门禁；全部既有 packages 测试绿 + 新增「broker 无作者代码 tsc 子进程」断言。
**必读**：§3 D-08；`runner/{protocol,client,server,supervisor}.ts`（帧协议/slot 执行模型/compile 现实现）；`tools/packages.ts` runBuild/runPack 现实现（staged 目录/tsconfig.build.json/产物门禁）。
**改动文件**：W22、W23 + 测试。
**做法**：runner compile payload 扩展（`{stagedDir, tsconfig, timeoutMs}` → `{diagnostics, outputs}`，路径限 slot 根内——server 侧路径校验照既有 check/plan 模式）→ broker runBuild 改派发 → 测试（既有用例全绿 + 新增断言：以 spy/子进程计数证明 broker 零 spawn；runner 不可达时 `runner_unavailable` 明确失败不静默回落 broker 编译）。
**MUST NOT**：不得保留「runner 失败静默回落 broker spawnSync」路径（失败必须显式）。
**验收**：AC-F06-01 既有 custom-packages 测试族全绿（行为等价）；AC-F06-02 broker 进程零作者代码 tsc；AC-F06-03 runner down→`runner_unavailable`。命令：V04（需完整 G+runner 槽）。

### 卡 C107F-07：文档收口与最终集成验收

**执行包**：107-fix-1 v1.0.0；REQ-F09。
**类型与完成边界**：集成——全部门禁复跑矩阵 + 文档/契约状态同步 + 交付核销。
**做法**：VFX 矩阵（§12.3）逐条跑并落 `test-artifacts/task-107/fix1/C107F-07/gates.txt`；更新 W26 四处（README 索引行 107-fix-1、进度指南 107 段补「fix-1 收口」小节、coverage 契约补 fix1 证据条目——**不改既有 24 卡行**、knowledge 索引如 vocabulary 文档新增则重跑 build-index）；W27 卡表回写 VERIFIED；对话 §14 汇总。
**验收**：AC-F07-01 门禁矩阵全绿（例外逐条带因）；`npm run docs:status`/`docs:links`/`quality:lifecycle` 零漂移。

---

## 12. 测试、验证命令与集成验收

### 12.1 需求追踪（节选主线；TC 全列见各卡）

| 需求 | 卡 | AC | TC | 命令 |
|---|---|---|---|---|
| REQ-F01 | 01 | AC-F01-01～03 | TC-F01-01～03 | VFX-1/VFX-2/VFX-5 |
| REQ-F02/F03 | 02 | AC-F02-01～04 | TC-F02-01～04 | VFX-1/VFX-2/VFX-3 |
| REQ-F04 | 03 | AC-F03-01～04 | TC-F03-01～04 | VFX-1/VFX-3 |
| REQ-F05/F06 | 04 | AC-F04-01～07 | TC-F04-01～07 | VFX-2 |
| REQ-F07 | 05 | AC-F05-01～02 | TC-F05-01～02 | VFX-1/VFX-2 |
| REQ-F08 | 06 | AC-F06-01～03 | TC-F06-01～03 | VFX-1 |
| REQ-F09 | 07 | AC-F07-01 | VFX 全矩阵 | VFX-1～6 |

### 12.3 本任务验证清单

| 编号 | 适用 | 目录 | 命令 | 前置 | 必需性 | 通过标准 |
|---|---|---|---|---|---|---|
| VFX-1 | 各 B 卡 | `platform-hypit/backend` | `npm run typecheck && npm test` | 引擎 G 物化（03/06 完整+依赖） | 必需 | exit 0，用例数≥基线（179 过 + 新增），无未解释 skip |
| VFX-2 | 各 J 卡 | `platform-java` | `./gradlew :services:intelligence-service:test --tests '*Hypit*' --rerun-tasks --no-build-cache --max-workers=2 --console=plain` | JDK25+Docker | 必需 | exit 0（基线 84 + 新增） |
| VFX-3 | 02/03 | 仓库根 | `npx vitest run tests/deployment/hypit-compose.contract.test.ts tests/deployment/hypit-entrypoint.contract.test.ts tests/deployment/hypit-full-coverage.contract.test.ts tests/deployment/hypit-tools.contract.test.ts` | npm ci | 必需 | exit 0（19+新增） |
| VFX-4 | 07 | 仓库根 | `npm run quality:lifecycle && npm run docs:status && npm run docs:links` | — | 必需 | 全 exit 0，49+31 零漂移 |
| VFX-5 | 01 | 仓库根 | `npm run e2e -- tests/e2e/hypit-clone.spec.ts --project=chromium` | 隔离 hypit 栈起（DH_E2E 同款配方） | 经批准可选 | 隔离栈可用则 exit 0 含 roundtrip；不可用 NOT_RUN 如实 |
| VFX-6 | 07 | 仓库根 | `npm run security:secrets`（退出码直连重定向验证，禁管道取 `$?`） | — | 必需 | exit 0 |

### 12.5 最终集成验收

负责人：执行者（C107F-07）。前置：C01～C06 全 VERIFIED。串联流程：ready 工程 → export → import → 新工程 check/plan → TOOLS_INVOKE speech.measure → agent-jobs（桩 LLM planner）→ runtime doctor → vocabulary?surface → 全程事件流与动作行核对。保留行为：8 media 工具、69 条既有路由、76 旗标默认值、V04/V05/V06 基线用例零回退。变更范围核对 §9.1 交集。

### 12.7 边界目录

E01→02/04；E02→02（measure 上限）；E03→04；E04→03；E05→03（capture）；E06/E07→04；E08→04（agent 列表空）；E09→01（head 漂移）；E10→04（SSE 重连既有）；E11→04（跨工程 job 403）；E12→03（capture 超时）；E13→各卡 400 断言；E14→03（帧/ranges）；E15→04（cancel 竞态）；E16→01/04（requestId 幂等）；E17→03（session 归属）；E18→04（owner 变更）；E19→01（旧 bundle）；E20→04（worker 重放）；E21→N/A（无金额/时区面）；E22→04（列表分页 limit≤100）。

---

## 13. 阻塞规则

同模板 §13 全文适用。本任务特别：若实装中发现 107-1 §6.2 契约行与本书 §6 冲突（以 107-1 原文为准并 BLOCKED 上报）；若 planner 执行环入口（FrozenTextExecutionService 独立文本流）确不可用于结构化计划生成，BLOCKED 附替代方案建议，不得改用前端拼计划。

---

## 14. 执行结果汇报

同模板 §14：每卡对话汇报（卡号/状态/命令/退出码/证据路径）；最终按模板「实现结果+文件与范围」结构汇总；不新建报告文件；卡表状态仅回写 W27。

---

## 附 A：审计证据索引（规划输入，只读）

1. 断链实锤：`grep -rn "project-package" platform-hypit/backend/src/commands/dispatcher.ts` 零命中；J 派发点 `HypitProjectPackageService.java:54/80`；WireMock 掩盖点 `HypitTemplateIT.java:101/121`。
2. 桩清单：`HypitJobController:76`、`HypitProjectController:279/287/295/303`、`HypitRuntimeController:108/133`（行号为 459e998b 基线，执行时以符号复核）。
3. worker 无投递者：全库生产代码无 `hypit.agent` INSERT（仅 `HypitAgentWorker.JOB_KIND` 常量与 claim SQL）。
4. speech/image 已路由：dispatcher default 分支 `isSpeechTool/isImageTool`（`tools/speech.ts:25`、`tools/image.ts:18` kind 数组）。
5. runner compile 已实现：`runner/protocol.ts RUNNER_COMMAND_KINDS` 含 `compile`；`server.ts NOT_IMPLEMENTED_KINDS={executeLocal,capture,cancel}` 不含 `compile`。
6. vocabulary 上游能力面：`upstream/packages/video-cli/src/vocabulary.ts` 导出 `listPackages/listSurfaces/describeSchema/visualSchema`。
7. 工具契约漂移：`contracts/hypit-tools.v1.json` 18 id vs B kind 真名（`transcribe` vs `speech.transcribe`）；全仓唯一引用为 `scripts/local/task107-split-v3.py`。
