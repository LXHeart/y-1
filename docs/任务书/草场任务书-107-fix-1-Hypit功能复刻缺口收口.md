# 开发任务书：107-fix-1 · Hypit 功能复刻缺口收口

> 任务编号：107-fix-1 ｜ 任务书版本：1.1.0 ｜ 创建日期：2026-09-26（v1.0.0 出书，同日按模板 v3.0.0 全章节重写为 v1.1.0）
> 规划模型/负责人：ZCode（规划与验收设计）→ 交执行模型实现，用户验收 ｜ 目标仓库：y-1（仓库根 `/Users/LXH/claude/y-1`）｜ 当前分支：main（仅记录，不自动创建）
> 代码基线：`459e998b`（main；107 三书 24 卡与 CI fresh-clone 修复已入库；任务书自身随 `04770a24` 入库，代码基线不变）｜ 本次事实核验日期：2026-09-26
> 规格状态：READY_FOR_IMPLEMENTATION ｜ 实施状态：NOT_STARTED
> 目标执行者：能力较弱的编码模型 ｜ 任务卡总数：7 ｜ 起始卡：C107F-01
> 执行顺序：C107F-01 → C107F-02 → C107F-03 → C107F-04 → C107F-05 → C107F-06 → C107F-07（AUTO_CHAIN；独立并行说明见 §10.3）
> 执行模式：AUTO_CHAIN 自动推进
> 交付终点：本地集成验收完成（§12.5）；真实 e2e roundtrip 属 opt-in 层，本机环境不可跑时如实 NOT_RUN，不冒充通过；不默认包含生产发布
> 决策依据：用户 2026-09-26 要求对照上游 hypit 做功能复刻度审计后「出任务书修复、尽可能详细」。本书全部范围来自该审计的实锤证据（附 C）；上游行为以 `platform-hypit/upstream`（v0.2.13，commit 2c320059）源码为准，#107 契约以 `contracts/hypit-api.v1.json`、`contracts/hypit-tools.v1.json` 与 107-1 §6.2 为准。规划者已在用户授权范围内冻结全部实现决策（D-01～D-08），无未决产品决策。

**编号方案（本书内统一）**：需求 `REQ-Fxx`、规则 `RULE-Fxx`、决策 `D-xx`、接口 `API-Fxx`、卡 `C107F-xx`、验收 `AC-Fxx-nn`、用例 `TC-Fxx-nn`、命令 `VFX-n`。

**阅读协议**（执行模型遵守）：

1. 确认规格状态 READY_FOR_IMPLEMENTATION、版本 v1.1.0、授权卡范围明确；实施状态全 NOT_STARTED，已验收卡不重复实现。AUTO_CHAIN 下当前卡通过卡内验收（§0.3 DoD）后自动进入授权范围内下一张卡。
2. 动手前先读根 `AGENTS.md` 与本书 §0、§1、§9、§10、§13、§14，再读当前卡及其精确引用（§3 决策、§5 规则、§6 契约、§12 用例）。本书无 UI 改动，无 DESIGN.md 义务（§8 N/A）。
3. 只执行本书定义的卡；不自行新增卡、改变顺序或补产品方案。当前卡及其明确引用的全局小节、测试与源码构成上下文。
4. 每卡完成必须执行本卡验收并在对话中记录（§14）；卡完成不等于整任务完成，整任务另需 §12.5 集成出口通过。
5. 换模型或新会话续作时，按 §14.1 续作检查点核对代码与证据，不凭上一模型的完成声明继承 VERIFIED，也不从第一卡盲目重做。

---

## 0. 执行协议与完成定义

### 0.1 词义

- **MUST / MUST NOT**：必须 / 禁止。**SHOULD**：除非有记录在案的理由否则执行。**MAY**：允许非必须。
- **FACT**：已从当前仓库源码/测试/配置核验的事实，不得擅自改写。**DECISION/NEW**：本书已选定但尚未实现的行为/模块。**EXAMPLE**：仅展示格式，不得当业务值。
- **BLOCKED**：缺少实现所必需的决策、受限资源或环境时停止受影响卡并按 §13 报告；普通本地开发环境不可用时先排查记录，不直接阻塞。
- **N/A**：明确不适用且写明理由；与「未执行」不同。**NOT_RUN**：验证确因环境缺失未执行，如实记录，不得伪装通过。
- **B**：`platform-hypit/backend` 可信 Node broker；**G**：`platform-hypit/.generated/hypit` 引擎树（gitignore，构建产物）；**J**：`platform-java/services/intelligence-service` 的 `com.grassland.intelligence.hypit` 包；**kind**：J→B 命令类型字符串（dispatcher 路由键）；**工具 id**：`/tools/{tool}` 公开名；**operator**：`HYPIT_OPERATOR_ACCOUNT_IDS` 配置的部署管理账号。

### 0.2 强制规则（本书编号，覆盖不到的按模板与仓库指令）

1. **上游冻结**：`platform-hypit/upstream/` 全目录只读；`scripts/acceptance/verify-107-upstream.sh` 逐文件 sha256 守卫，任何改动即 V01 红。需要上游行为证据时读源码并引用路径+符号。
2. **引擎 G 物化前置**：V04（backend typecheck+test）依赖 G 树。跑 VFX-1 前先 `bash scripts/acceptance/build-107-engine.sh --no-install`；C107F-03/06 涉及渲染浏览器与 runner 槽，需完整物化+依赖安装（§9.3）。
3. **不新增 Edge 路由**：本任务全部端点已在 `contracts/hypit-api.v1.json` 声明、76 个 `EDGE_ROUTE_HYPIT_*` 旗标已存在于 `docker-compose.yml`（L323-398）。任何卡不得改 edge 清单；端点不通先查旗标与 edge 路由测试。
4. **禁止把 pending 桩换成另一种假成功**：C107F-04 的「实装」定义为真实可调用且行为符合 §6 契约；返回空列表/恒成功/吞错误均不算实装。
5. 只写当前卡写入清单（§11 各卡）与 §9.1 全局写入白名单的**交集**；黑名单优先，只读参考不是写权限。确需新文件/新路径先按 §13.3 修订本书再实施。
6. 不做与本卡无关的重构、格式化、依赖升级、文件迁移。**新增依赖：无**——MUST NOT 新增任何 npm/gradle 包。
7. 必需验收 MUST 真实执行；MUST NOT 通过放宽断言、跳用例、删测试、降低门槛或吞错误换绿灯。行为确需变更时在卡中写明新行为与回归依据。
8. 未定义的产品/安全/资金/范围决策，或需越过已批准边界时立即 BLOCKED（§13.1）；仅阅读、搜索、测试、核对源码不构成阻塞。普通编码错误在卡边界内自行修复。
9. 开工先记录已修改/未跟踪文件；工作区不必干净，但同文件他人改动须能安全保留。禁止擅自 reset/clean/批量删除；提交按用户仓库工作流，提交不等于验收通过。
10. 本地可以启动本地服务、下载依赖、重建镜像、使用隔离测试数据库与测试迁移，记录命令与结果即可；localhost 不自动意味着数据可删除，清理前核对实际连接与卷归属。真实账号、付费服务、生产资源无授权不动。
11. 凭据、Cookie、签名 URL、bundle 字节、宿主绝对路径不得泄露到代码、日志、测试报告或对话汇报。`VFX-6`（secrets 扫描）验证退出码必须直连重定向获取——`> /tmp/x.out 2>&1; echo $?`，**禁止**管道后取 `$?`（历史实录：管道掩盖退出码导致 13 处命中被「全绿」掩盖）。
12. 执行情况默认在对话中汇报（§14）；MUST NOT 新建独立的完成/交付/执行总结文件。测试工具生成的报告、日志、截图按 §9.1 登记目录保留。

### 0.3 完成定义（DoD）

一张卡只有全部满足才算完成：

- [ ] 卡内所有适用步骤已执行；已由当前代码满足的步骤经核验可跳过写入，记录依据并照常验收。
- [ ] 卡内所有验收项（VFX 命令验收 + AC/TC 行为验收）通过；FAIL/PARTIAL/NOT_RUN/SKIPPED 不计通过，预先批准排除项单列。
- [ ] 未引入未说明的行为变化、控制台错误、类型错误、构建错误。
- [ ] 新增改动未越白名单；任务前已有改动保留；无遗留调试代码与敏感信息。
- [ ] 按 §14 在对话中记录卡号、状态、命令、工作目录、退出码、用例数、关键输出与证据路径。

卡级 VERIFIED 由卡内责任人依证据判定（可为执行模型）；整任务只有 §12.5 集成出口也通过才能标 VERIFIED。

---

## 1. 产品需求、目标与范围

### 1.1 一句话目标

把 #107 审计确认的四项功能缺口（工程包导出导入断链、七条 pending 桩、四组工具无生产入口、vocabulary 词法缺半）与一处隔离边界（作者包编译未入 runner）全部收口，使 `contracts/hypit-api.v1.json` 声明的 76 条路由与 `contracts/hypit-tools.v1.json` 声明的工具面**全部真实可用且被测试锁定**。

### 1.2 背景与价值

2026-09-26 复刻度审计确认：#107 主链（创作/构建/结果/Studio/Provider 十家/媒体八工具）完整可用，但存在「契约声明了、卡表标 IMPLEMENTED 了、运行期却不可用」的四类缺口——其中 `project-package.export/import` 在 J→B 派发时必落 `unknown_kind`（被 J 侧 WireMock 桩测试掩盖），`hypit.agent` 持久 worker 在生产无任何投递者（仅测试直插表驱动）。收口后：工程包移植链真正闭环、工具面与契约三向一致且 CI 锁定、Agent 通用任务入口可达并可恢复、作者代码编译回到 K10.4 硬隔离 runner。

### 1.3 范围内（明确交付）

| 需求编号 | 用户/触发场景 | 必须交付的可观察行为 | 交付优先级 | 负责卡 | 对应验收编号 |
|---|---|---|---|---|---|
| REQ-F01 | 创作者导出工程包 / 另一账号导入复用 | POST `/api/hypit/projects/{id}/export` 与 POST `/api/hypit/imports` 对真实 B broker 全链成功；roundtrip 文件 sha256 一致；幂等重放回读同回执 | 必须 | C107F-01 | AC-F01-01～04 |
| REQ-F02 | 创作者经 `POST P/tools/{tool}` 调转写/估时/对齐/图像变换合成 | `speech.transcribe/measure/align`、`image.transform/compose` 返回 202 AcceptedJob 且产物落素材库；whisperx 未就绪时 409 不假成功；未登记工具 400 不落 job | 必须 | C107F-02 | AC-F02-01～04 |
| REQ-F03 | 工具契约三向一致且被 CI 锁定 | `contracts/hypit-tools.v1.json` 工具 id 集 == B dispatcher 实际路由 kind 集 == J `HypitAssetService` 白名单集（差集=预注记 operator 专用项），由新契约测试静态锁定，人为漂移任一侧即 CI 红 | 必须 | C107F-02/03 | AC-F02-05 |
| REQ-F04 | 创作者对当前预览取精确帧快照 / 网页取证截图 / 受控脚本采集 / 组件包构建 | `snapshot`（经 preview session，服务端解析 document）、`capture.screenshot/run/install-browser`、`packages.build/pack` 全部经 TOOLS_INVOKE 可调；URL 服务端生成、脚本出自工程 head、输出落资源句柄 | 必须 | C107F-03 | AC-F03-01～05 |
| REQ-F05 | 创作者创建/查看通用 Agent 任务并恢复 | GET/POST `P/agent-jobs` 实装：POST 按 107-1 §6.2 契约（intent 白名单）创建 `hypit.agent` job 并由 `HypitAgentWorker` 真实消费（planner 首步生成 action 计划）；`POST P/jobs/{jobId}/actions`（resume/cancel）实装 waiting_input 恢复与取消，resume 不扩张原 scope | 必须 | C107F-04 | AC-F04-01～04 |
| REQ-F06 | 部署者经 runtime 面操作/诊断 | POST `/runtime/actions`：doctor 返回聚合健康；up/down 幂等编排两程序（down 有活跃工作且 hash 不匹配 → 409 影响清单）；init/use/unset 返回 409 `hypit_runtime_managed` 显式语义。GET `/runtime/paths` 返回逻辑路径清单（operator） | 必须 | C107F-04 | AC-F04-05～07 |
| REQ-F07 | Agent/创作者查询可写组件词法 | GET `/api/hypit/vocabulary` 支持 `surface`（per-package module/import/attributes/children/ports/example）与 `visual`（形状 schema）两组参数，数据来自 G 发行版真实包 manifest（原生 `listSurfaces/visualSchema` 包装），无参响应向后兼容 | 必须 | C107F-05 | AC-F05-01～02 |
| REQ-F08 | 作者组件编译在硬隔离 runner 执行 | `packages.build/pack` 的 tsc 编译从 broker 进程内 spawnSync 迁入 K10.4 runner（`compile` kind 已在协议白名单且 server 已实现），broker 不再直接 spawn 作者代码编译；runner 不可达时显式失败不静默回落 | 必须 | C107F-06 | AC-F06-01～03 |
| REQ-F09 | 文档与证据收口 | 任务书 README 索引、进度指南 107 段、`contracts/hypit-coverage.v1.json` 证据条目同步；VFX 门禁矩阵复跑并记录；lifecycle/docs 零新增漂移 | 必须 | C107F-07 | AC-F07-01 |

编号修订后保持稳定。所有增强项同样必须验收，不由执行者跳过。

### 1.4 范围外（明确不做，遇到也不处理）

- 不实装真实 Provider 调用（live 仍 REAL_NOT_RUN，属 107-3 V15 opt-in 层既有边界）。
- 不实装 runner `executeLocal/capture` kind（`NOT_IMPLEMENTED_KINDS` 保持原样；capture 工具走 broker 受限通道，见 D-06）。
- 不改 `platform-hypit/upstream/`、不改 edge 路由清单与旗标、不新增数据库迁移（全部复用 `hypit_job/hypit_job_action/hypit_command` 既有表）。
- 不做 `packages.install/status` 经 TOOLS_INVOKE 开放（operator 级，保留在 `/runtime/packages` 专端点）。
- 不补 107-3 已知的 C21 PENDING_SCREENSHOT 证据；不动 V10 已知 DH 共享容器 flaky 家族（单类重跑绿）。
- 无 UI 改动（现有前端 `exportProject` 等 API 函数已对接目标端点，实装后自然可用）。

### 1.5 不许顺手修

执行中发现下列已知问题：只在 §14 汇报「未解决问题」中列出，MUST NOT 动手：

- `npm run docs:links` 既有 ~20 条 `[unindexed]`（`platform-java/.../resources/hypit/knowledge/**` 与 `deploy/hypit/README.md`，107 批入库即有；CI 不跑该检查、docs:status 绿）。VFX-4 以「零新增漂移」为准（基线漂移数不得增加）。
- `HypitAgentWorker.MAX_STEPS=40` 与 107-2 卡文「maxSteps 默认 30」的文字差——保持 40，不改代码不改文档。
- `scripts/local/` 下历史脚本；`tests/e2e/fixtures/test-artifacts/` 下 #106 harness 产物。
- MinIO/quay 镜像拉取超时类 CI 瞬时问题（复现按 §13 报告，不改测试不改镜像源）。
- 既有七条 pending 桩之外的任何 `pending(` 出现点（如 `HypitBuildController:337`/`HypitKnowledgeController:149`/`HypitStudioController:83` 的 neverMap 辅助行——它们是 pending 响应的公共尾部，随 C107F-04 换桩自然消亡或保留，不在七条清单内的不动）。

### 1.6 用户、入口与已知限制

- 用户/调用方：创作者（project owner，经 edge 会话）、部署者（operator）；无门店/组织上下文。
- 使用前置条件：工程 ready（`requireReadyOwner`）；whisperx 程序 up（transcribe）；preview session 存活（snapshot）；capture Chrome 已 install 且 `HYPIT_CAPTURE_BROWSER_CACHE` 已配置（capture.*）；runner 槽可用（packages.build/pack）。
- 已知且允许保留的限制：planner 首步的 LLM 输出质量不在验收范围（用执行环桩驱动结构断言，验收只锁结构/状态机/留证语义）；live provider 不可用；e2e roundtrip 需隔离栈。
- 验收例外：无。

### 1.7 用户场景与业务闭环

| 场景/关联需求 | 用户及动机 | 触发与前置条件 | 主流程 | 最终结果及去向 | 中断后的恢复入口 |
|---|---|---|---|---|---|
| S1 工程移植 / REQ-F01 | 创作者把工程打包给另一账号复用 | 工程 ready、有 published head | 前端「导出」→ POST export → 202 → 轮询 job → 下载 bundle → 新账号 POST /imports（multipart）→ 202 → 新工程 provisioning → ready | 新工程 revision=2、文件 sha256 与源一致、可继续 check/plan/build | job 失败保留原因可重试（同 requestId 幂等回读）；导入被拒不落任何 ready 状态 |
| S2 Agent 任务 / REQ-F05 | 创作者让 Agent 按简报驱动工具 | 工程 ready、intent 合法、brief 非空 | POST agent-jobs → 202 AcceptedJob → SSE 看 activity（planner→executing）→ worker 逐步执行并记 action 行 → 超步数/缺料 waiting_input → POST actions resume 带补充资料 → 继续 → succeeded/failed | 动作行与 ai_run/产物绑定、可审计；job 列表可回查 | 崩溃后租约过期重领、只重放确定性动作；cancel 单向幂等 |
| S3 快照取证 / REQ-F04 | 审片时对当前版本取精确帧对照评论 | preview session 存活 | POST tools/snapshot（at[] 或 ranges 分页）→ 202 → job 产物帧图落素材库（role=footage）→ ReviewPanel 评论引用 | 帧索引与文档帧精确对应、不新建导出 Build | session 过期 → 409 `hypit_session_expired`，重开 session 再拍 |
| S4 运维诊断 / REQ-F06 | 部署者巡检与受控停机 | operator 身份 | POST runtime/actions doctor → 聚合健康；down 前取 activity hash → down | doctor 报告可判读；down 或 409 影响清单 | 409 后先处理活跃 build 再带 hash 重试 |

核心术语已在 §0.1 定义。需求来源：用户 2026-09-26 对话指示（审计→出书）；上游语义来自 vendored 源码（附 C）。

### 1.8 产品成功标准

| 成功标准 | 观察对象与判定方法 | 目标/阈值及依据 | 对应 AC |
|---|---|---|---|
| 契约 76 路由零 pending 桩 | `grep -rn "pending(\"" platform-java/.../hypit/api/` 命中数 | 0（基线 7；`pending(` 的 neverMap 辅助行随实装删除） | AC-F04 全组 |
| 工具契约三向一致 | VFX-3 新契约测试 | 退出码 0；人为改任一侧的 diff 证据显示红（防假阳性） | AC-F02-05 |
| 导出导入 roundtrip | VFX-5（或本地隔离栈手跑，分层如实） | 文件 sha256 全一致、revision=2 | AC-F01-04 |
| 作者代码编译零 broker spawn | C107F-06 测试断言 | broker 进程无作者代码 tsc 子进程 | AC-F06-02 |

上线后才可观察的业务指标：无（本地工程任务）。

### 1.9 未决问题与决策权限

无。全部决策已冻结（§3 D-01～D-08），均属「完成 107-1 既有契约声明」授权范围内的实现决策；无价格、权限新增、数据删除、对外承诺类未决项。

---

## 2. 仓库上下文与事实基线

### 2.1 目标端

| 勾 | 端 | HTML 入口 | 相关目录 |
|---|---|---|---|
| [ ] | 用户端 | — | 不改 |
| [ ] | 治理台 | — | 不改 |
| [ ] | AI 创作中心 | — | 不改 |
| [ ] | 共享组件 | — | 不改 |
| [x] | 后端（Node broker） | — | `platform-hypit/backend/src/**`、`platform-hypit/backend/tests/**` |
| [x] | 后端（Java） | — | `platform-java/services/intelligence-service/src/{main,test}/java/com/grassland/intelligence/hypit/**` |
| [x] | 契约/测试 | — | `contracts/hypit-*.json`、`tests/deployment/hypit-*.contract.test.ts`、`tests/e2e/hypit-*.spec.ts` |
| [ ] | 构建/脚本 | — | 仅 `.github/workflows/ci.yml` hypit-contract job 一行（W28） |
| [ ] | 文档 | — | 仅 §9.1 W26 登记的状态文档 |

### 2.2 设计规范路由

N/A：无 UI 改动（§8 N/A），不触发 DESIGN.md 义务。

### 2.3 入口位置

- dispatcher 路由：`platform-hypit/backend/src/commands/dispatcher.ts` `runKind()`——显式 switch（workspace.×8、engine 6、build.×5、results.×7、preview.session/studio.session、knowledge.×2、templates.×2、feedback.×2）+ default 内谓词 fallback（`isMediaTool`/`isPackageTool`/`isSpeechTool`/`isImageTool`）与前缀 fallback（`programs.`/`providers.`/`credentials.`/`authflow.`）。
- J 工具入口：`.../hypit/asset/HypitAssetService.runTool()`（`MEDIA_TOOLS` 8 项白名单 → `runAssetJob(action="tool.run", payload={projectId,tool,input})` → sidecar）。
- J pending 桩（七条，2026-09-26 核实行号、以符号为准）：`HypitJobController.submitAction`、`HypitProjectController.agentJobs/createAgentJob/projectJobActions/projectJobActionSubmit`、`HypitRuntimeController.action/paths`——全部走 `pending("…")` 恒 error 503。
- Agent worker：`.../hypit/agent/HypitAgentWorker`（`JOB_KIND="hypit.agent"`、`@Scheduled(poll-ms:5000)`、租约 45s、`CLAIM_LIMIT=3`、`MAX_STEPS=40`；`claimDue` SQL 认领 `state='queued' AND kind='hypit.agent'`；`driveToCompletion` 对空 `checkpoint.actions` 直接置 succeeded）。
- vocabulary：B `engine/vocabulary.ts` `describeVocabulary(distributionRoot)` → `{providers,programs,alignmentLanguages}`；上游能力面 `platform-hypit/upstream/packages/video-cli/src/vocabulary.ts`（导出 `listPackages/listSurfaces/describeSchema/visualSchema/runVocabularyCli`，`SurfaceListing` 含 module/import/attributes/children/ports/example；`visualSchema(shape?)` 覆盖 14 形状）。
- runner：`platform-hypit/backend/src/runner/protocol.ts`（`RUNNER_COMMAND_KINDS=[check,plan,compile,executeLocal,capture,status,cancel]`，帧=长度前缀 JSON、默认 1MiB 上限、大字节走 slot-local 句柄）；`runner/server.ts`（`NOT_IMPLEMENTED_KINDS={executeLocal,capture,cancel}`——**compile 已实现**）；`runner/client.ts`、`runner/supervisor.ts`（单执行槽监督）。
- packages 工具：`platform-hypit/backend/src/tools/packages.ts` `runBuild/runPack`（staged 目录 + 生成 `tsconfig.build.json` + `spawnSync(tsc,…)` 超时 120s + 产物门禁 200 文件/2MiB 单文件/16MiB 包）。
- project-package：B `src/project-package/export.ts`（`exportProjectPackage(ctx:ExportContext, projectId, {title?,selectedRun?})→ExportReceipt{artifactRoot,manifest}`，`ExportContext={projectsRoot,distributionRoot,sourceCommit}`；密钥形文件拒打包、4GiB/20000 文件上限）；`src/project-package/import.ts`（`importProjectPackage(ctx:ImportContext, bundleRootRaw, {newProjectId,requestId,title?})→ImportReceipt{projectId,revision,manifestHash,fileCount}`，`ImportContext={projectsRoot,stagingRoot,provisionTemplateDir,provisionTemplateFiles}`；verify-then-create、sha256/禁符号链接/数量体积门）。
- J 工程包编排：`.../template/HypitProjectPackageService.export/import_`（幂等 command + `replayOrDispatch(command,"project-package.export"/"project-package.import",payload)`；export 期望回包 `artifactRoot/fileCount/manifest`，import 期望 `projectId/revision/fileCount`——**FACT：B ExportReceipt 现无 fileCount，D-02 补**）。
- 信封：站内 `{success,data,error}`（`HypitDtos.success(...)`）；AcceptedJob record=`(String jobId, String state, String resourceId)`（`HypitDtos.java:44`）；Job record 含 `id/projectId/kind/state/phase`。

### 2.4 相关现有文件

| 文件 | 相关符号 | 当前职责 | 本次关系 |
|---|---|---|---|
| `platform-hypit/backend/src/commands/dispatcher.ts` | `runKind/runCommand/DispatchError` | kind 路由唯一入口 | W01 修改（01/03） |
| `platform-hypit/backend/src/project-package/export.ts` | `exportProjectPackage/ExportReceipt` | 打包 | W02 修改（01） |
| `platform-hypit/backend/src/project-package/import.ts` | `importProjectPackage/ImportReceipt` | 导入 | 只读参考（01 接线调用） |
| `platform-hypit/backend/tests/workspace/project-transfer.test.ts` | export/import 直调用例 | B 层闭环已测 | W03 扩 dispatcher 级用例 |
| `platform-java/.../hypit/template/HypitProjectPackageService.java` | `export/import_/replayOrDispatch` | J 幂等编排 | W04 核验（预计零改动） |
| `platform-java/.../hypit/template/HypitTemplateIT.java` | WireMock 桩 | mock sidecar | W05 桩字段锁定 |
| `platform-java/.../hypit/asset/HypitAssetService.java` | `MEDIA_TOOLS/runTool/runAssetJob` | 8 工具白名单 | W06 扩展 |
| `contracts/hypit-tools.v1.json` | 18 工具声明 | 无测试消费、命名漂移 | W07 重生成（D-01） |
| `platform-hypit/backend/src/tools/{speech,image,snapshot,capture,packages}.ts` | 见 §2.3 | 工具实现件 | speech/image 只读；snapshot/capture 加处理器（W09/W10）；packages 迁 runner（W22） |
| `platform-hypit/backend/src/preview/sessions.ts` | PreviewSession 注册表 | (project,runFile,revision) 绑定 | 只读参考（03 经它定位 document） |
| `platform-hypit/backend/src/resources/url-policy.ts` | 出站 URL 逐跳校验 | SSRF 防线 | 只读复用（03） |
| `platform-hypit/backend/src/engine/vocabulary.ts` | `describeVocabulary` | 能力/程序目录 | W20 扩展（05） |
| `platform-hypit/backend/src/runner/{protocol,client,server}.ts` | compile kind / 帧协议 | K10.4 隔离 | W23 扩 payload（06） |
| `.../hypit/agent/{HypitAgentWorker,HypitAgentStepService,HypitAgentScope}.java` | 见 §2.3 | 持久 agent 环 | W17/W18/W19 修改（04） |
| `.../hypit/job/{HypitJobRepository,HypitJobActionRepository}.java` | `insert/claimDue/saveCheckpoint` | job 持久面 | 只读复用（04） |
| `tests/e2e/hypit-clone.spec.ts` | clone 主链 | 隔离栈 e2e | W25 加 roundtrip（01） |
| `.github/workflows/ci.yml` | hypit-contract job vitest 清单 | CI 契约层 | W28 加一行（02） |

### 2.5 当前行为（FACT，2026-09-26 核验；执行时复核符号）

1. J `export()` → `replayOrDispatch(command,"project-package.export",payload)` → sidecar POST `/internal/v1/commands`（kind=`project-package.export`，payload=`{projectId,title?,selectedRun?}`）→ B `runKind` 无 case、无谓词命中 → `DispatchError("unknown_kind")` → 端点失败。import 同理（payload=`{artifactRoot}`）。J 期望回包字段与 B receipt 差 `fileCount`（D-02）。
2. 七条 pending 桩返回 503 error；生产代码无 `hypit.agent` INSERT（全库仅 `HypitAgentWorker.JOB_KIND` 常量、claim SQL 与测试直插）；`driveToCompletion` 对空 `checkpoint.actions` 直接 succeeded（生产入口若不定义计划生成即假成功——D-04 planner 首步堵死）。
3. `speech.*`（`speechTools=[speech.transcribe,speech.measure,speech.align]`）与 `image.*`（`imageTools=[image.transform,image.compose]`）kind 在 B dispatcher default 分支**已路由**（`isSpeechTool/isImageTool`）；J `MEDIA_TOOLS` 未放行 → 400 `hypit_unsupported_action`。
4. `tools/snapshot.ts`（`snapshotSchedule` 纯函数 + `snapshotFrames(request, options=renderHyperframesFrames[1])`）与 `tools/capture.ts`（`prepareCaptureChrome{version?,cacheDirectory}`、`RestrictedCaptureScript{script,outputRoot,allowNetwork?,timeoutMs 1..120000}`、`assertRestrictedCaptureScript` 禁 process/require/import/globalThis 与 fetch、`assertWithinOutputRoot`）模块与测试齐备，但无 kind 通道，仅 backend 测试消费。
5. `tools/packages.ts runBuild` 在 broker 进程 `spawnSync(tsc,["-p","tsconfig.build.json"],{cwd:staged,timeout:120000})`；runner `compile` kind 已在白名单且 server 已实现（`NOT_IMPLEMENTED_KINDS` 不含 compile）。
6. `/api/hypit/vocabulary` → sidecar kind `vocabulary` → B `describeVocabulary` 只返回 `{providers,programs,alignmentLanguages}`；上游 `listSurfaces/visualSchema` 未接。
7. Edge：76 旗标默认 false（仅 `EDGE_ROUTE_HYPIT_CAPABILITIES_READ=true`），全部七条目标路由的旗标已存在：`AGENT_JOBS_CREATE/LIST`、`JOBS_ACTIONS/ACTIONS_PROJECT/ACTION_SUBMIT/ACTION_SUBMIT_PROJECT`、`RUNTIME_ACTION/PATHS`。
8. CI：hypit-contract job = checkout + setup-node + V01 manifest + `npm ci` + vitest 三契约文件（`ci.yml` L263-286）。

### 2.6 当前问题

- 现状/问题/影响/根因：见 §1.2 与附 C 逐项实锤（根因全部 wire 级确认：kind 未路由/桩未换/白名单未放/实现未迁），非猜测。

### 2.7 基线与来源核验

| 项目 | 已核实内容/证据 |
|---|---|
| 指令与设计 | 根 `AGENTS.md`（目录归位/测试归位/任务书流程）；`docs/任务书/任务书模板.md` v3.0.0 全文（含附 A～D） |
| 版本与构建 | node v24.14.1（`~/.nvm/versions/node/v24.14.1/bin`）；JDK25（`scripts/lib/java-runtime.sh ensure_java_runtime 25`）；backend tsconfig `@hypit/*` paths 指向 `.generated`（`platform-hypit/backend/tsconfig.json:21-23`） |
| 架构与业务决策 | 107-1 §6.2 API 契约表、§6.4 工具枚举、K10.4 runner 白名单；107-2 C107-14 步骤 14.1-14.9；`contracts/hypit-api.v1.json` v3.1.0 76 路由 |
| 工作区 | `git rev-parse HEAD`=459e998b；`git status --short` 干净（2026-09-26） |
| 测试基线 | 2026-09-26 复核：V04 179 过/0 挂/2 诚实 skip、V05 84/84、V06 321/0/0、部署契约 19/19、lifecycle 49+31；CI run 36244210596 在跑（04770a24，docs-only 增量，不阻塞本书） |
| 复用检查 | 全部缺口均有既有实现件（export/import 函数、speech/image 工具、snapshot/capture 模块、runner compile、上游 vocabulary 函数）；本书只做接线/实装/对齐，不新造能力 |

### 2.8 事实、决策与示例的区分

`FACT`（§2.3-2.5 与附 C，已核实）/ `DECISION`（D-01～D-08 与 §4/§5/§6 目标行为）/ `EXAMPLE`（§6 JSON 示例中的合成值如 `9f1c…`、`brief` 文案）。占位符、TBD、未注明来源的行号不作为完成信息。

### 2.9 影响面与兼容面

| 影响面 | 是否受影响 | 具体对象 | 兼容要求 | 验证方式 |
|---|---|---|---|---|
| 页面/路由 | 否 | 无 UI 改动 | — | — |
| 公共 HTTP 契约 | 是 | 七条路由 503→真实语义；TOOLS_INVOKE 工具集 8→19 | 旗标默认 false 不变，未灰度方不受影响；错误码仅 §6.4 表列新增 4 个 | HypitContractTest + edge RoutesTest + VFX-3 |
| B 内部协议 | 是 | 新 kind×2（project-package.*）+ 工具白名单扩 + runner compile payload 扩 | `y1.hypit-engine-port@1` 不改版；CommandStore (commandId,payloadHash) 幂等不变 | backend 测试 |
| 数据库/缓存 | 否 | 复用 `hypit_job.checkpoint_json`（JSON 自由列扩展 `{phase,intent,brief,inputs}` 键） | 旧 checkpoint 读取兼容（新键缺省按无处理） | 既有 IT |
| 权限/身份 | 是 | agent-jobs/actions 新实装鉴权 | §5.4 表；越权 403/404 | 安全 TC |
| 计费/积分/资金 | 否 | 无 | — | — |
| 部署/配置 | 是 | `HYPIT_CAPTURE_BROWSER_CACHE` 新键（D-07） | 未配置=capture 409 显式不可用，不影响其余面 | compose 契约测试 |
| 文档/状态 | 是 | README 索引/进度指南/coverage 契约 | docs:status、links 零新增漂移 | VFX-4 |

---

## 3. 技术决策（契约冻结，局部实现按明确边界裁量）

| 决策项 | 结论 |
|---|---|
| 语言/框架/版本 | 既有栈：Node 24.14.1（backend tsx/TypeScript）+ Java 25 + Spring Boot 4（WebFlux/R2DBC） |
| 新增依赖 | 无——MUST NOT 新增任何 npm/gradle 包 |
| 文件布局与分层 | 不新建服务；改动集中在 §9.1 白名单既有/新增文件；J 新类放 `.../hypit/agent/` 与既有包结构一致 |
| 错误处理策略 | B `DispatchError(code,message)` 透传；J `IntelligenceException(status,code,message)`；站内信封 `{success,data,error}` |
| 命名约定 | 工具公开 id == B kind 字符串（D-01）；J 新方法名沿用所在类既有风格（如 `agentJobs/createAgentJob`） |
| 风格参照 | dispatcher case 风格照 `runKind` 既有分支；J 服务照 `HypitProjectPackageService` 的 command 编排模式 |
| 配置变更 | 仅 `HYPIT_CAPTURE_BROWSER_CACHE`（D-07）+ `.env.example` 一行 + ci.yml 一行（W28） |
| 兼容/发布 | 单仓单提交序；无部署拓扑变更；回滚=代码回滚（无迁移） |

### 决策记录

#### D-01：工具公开 id 以 B kind 为准，契约向实现对齐（冻结）

- 决策：`contracts/hypit-tools.v1.json` 工具 id 重命名为 B dispatcher 真实 kind：`transcribe→speech.transcribe`、`measure→speech.measure`；新增 `speech.align/image.transform/image.compose` 条目；`media.* 8、snapshot、capture.screenshot/run/install-browser、packages.build/pack/install/status` 保持。最终契约 22 个工具（本卡 C107F-02 落 17 个已路由项，C107F-03 补 snapshot/capture 5 项）。
- 原因：B kind 是运行真名；反向改 B 需动 dispatcher+全部 backend 测试+J 白名单三处且无行为收益。
- 放弃方案：B kind 改名对齐契约旧 id。
- 决策依据/权限：授权规划者（纯命名对齐，无产品语义变化）；上游 CLI 命令名经 `upstreamEntry` 字段继续可追溯。
- 约束级别：冻结（契约 id 是公开面）。

#### D-02：project-package B 响应补 `fileCount`（冻结）

- 决策：`ExportReceipt` 增加 `readonly fileCount: number`（=manifest.files.length）；`ImportReceipt` 不变。J 读取代码零改动（FACT：J 已按 `artifactRoot/fileCount/manifest` 读取）。

#### D-03：runtime/actions 分动作实装，init/use/unset 显式 409（冻结）

- 决策：`doctor`＝聚合健康（引擎就绪、两程序 health、render 容量占用、活跃 build 数与 activityHash）；`up`＝幂等确保 whisperx+image.opencv ready（内部复用 `programs.prepare/up` 管线，不旁路）；`down`＝存在活跃 build 且 `expectedActivityHash` 缺失/不匹配 → 409 附影响清单（buildId 列表+当前 activityHash），否则两程序 down；`init/use/unset` → 409 `hypit_runtime_managed`（Profile 由 `RUNTIME_PROFILE_PUT` 平台管理——107-1 §6.2 语义在嵌入式 runtime 下的显式化）。
- 放弃方案：从契约删除三动作（破坏 76 路由锁）。
- 约束级别：冻结（行为语义）。

#### D-04：agent-jobs 生产投递采用「planner 首步」（冻结）

- 决策：POST `P/agent-jobs` 落 `hypit_job(kind='hypit.agent', state='queued', phase='pending')`，`checkpoint_json={stepIndex:0, phase:'planning', scope, intent, brief, assetIds, baseRevision, inputs:{}, actions:[]}`；`HypitAgentStepService` 增 planner 首步——`actions` 为空且 `stepIndex=0` 时第一步经平台 LLM 执行环（`FrozenTextExecutionService` 独立文本流入口）生成 action 计划（prompt 模板内置 intent 语义+allowedTools 清单；输出 JSON schema 校验；失败重试 1 次，仍失败 → job failed 留 `planner_failed` 证据），写入 checkpoint（phase→executing）后逐步执行既有 `runStep` 流水。**禁止**空 actions 直接 succeeded（该现行为仅适用于测试预置 actions 场景）。
- scope 收敛：服务端按 intent 收窄 allowedTools（analyze=只读工具集；author/revise=+packages.build/pack+changeset 类；review=+snapshot+feedback 类；plan=analyze∩author），调用方 scope 只可缩不可扩（`HypitAgentScope` 沿用）。
- 放弃方案：要求调用方自带 actions（把 LLM 计划外移客户端，违背 107-2「不需要用户另开终端 coding agent」）。
- 约束级别：冻结。

#### D-05：snapshot 只经 preview session，不接受 URL（冻结）

- 决策：`snapshot` 工具输入 `previewSessionId`（必填）+ `at[]/ranges[]`（二选一，缺省 [0,中,尾]）+ `grid?/pageSize?`（每页 ≤50 帧）；B 由 preview session 注册表定位 (project,runFile,revision)，经引擎编译获得 `HyperframesDocument`，`snapshotSchedule→snapshotFrames`（原生 `renderHyperframesFrames` 渲染），产物帧图 `registerResource` 落资源句柄。URL/端口一律服务端生成（契约 testCases 原文）。assetId 直连形态不做（上游 `hypit snapshot <index.html>` 是本地开发流，平台面无对应场景）。

#### D-06：capture 走 broker 受限通道，不接 runner（冻结）

- 决策：`capture.screenshot`（URL 输入，经 `resources/url-policy.ts` 逐跳校验，受限 Chrome 截图，输出落资源句柄）；`capture.run`（脚本从工程 head 读取 workspace-relative 路径，`assertRestrictedCaptureScript` 静态门禁+`assertWithinOutputRoot` 输出约束+默认禁网+超时 1..120000ms）；`capture.install-browser`（`prepareCaptureChrome`，与 `media.prepare-fetch` 同列进 TOOLS_INVOKE——FACT：现网 MEDIA_TOOLS 已含 operator 级 prepare-fetch，一致处理）。runner `capture` kind 保持 NOT_IMPLEMENTED（107-1 §318 的 runner capture 属作者代码内联采集另案）。

#### D-07：capture Chrome 缓存目录配置键（冻结）

- 决策：新增 env `HYPIT_CAPTURE_BROWSER_CACHE`（默认空＝capture 工具未部署）。B `config.ts` 增 `captureBrowserCache` 字段；`deploy/hypit/.env.example` 增行注释；未配置时三 capture kind 返回 409 `hypit_capture_not_configured`。`deploy/hypit/compose.full.yml` browsers 卷追加 capture 缓存路径（仅注释示例，不强制挂载）。

#### D-08：作者包编译迁 runner `compile` kind（冻结目标、内部可裁量）

- 决策：`runBuild/runPack` 的 tsc 改经 `runner/client.ts` 派发 `kind="compile"`：staged 目录以 slot-local 路径传递（大字节不出帧——协议既定模式），runner 内 spawn tsc（复用 broker 现有 tsconfig.build.json 生成逻辑），broker 收诊断+产物句柄；产物门禁（200 文件/2MiB/16MiB）保留在 broker 收口处。runner 不可达 → 显式 `runner_unavailable` 失败，**禁止**静默回落 broker 编译。
- 裁量边界：runner 内 staging 传递细节可等价调整（判据：broker 进程零作者代码 tsc 子进程 + 既有 packages 测试全绿）。

### 3.1 端到端接线与职责

| 链路段 | 当前入口/符号 | 本次改动或复用 | 上游输入 → 下游交付 | 负责卡 | 接通证据 |
|---|---|---|---|---|---|
| export | J `HypitProjectPackageService.export` → sidecar kind → B `runKind` case → `exportProjectPackage` | 补 case+上下文+fileCount | `{projectId,title?,selectedRun?}` → `ExportReceipt` → 202 job 结果 | 01 | TC-F01-01/03 |
| import | J `import_` → kind → `importProjectPackage`（broker 生成 newProjectId） | 补 case+上下文 | `{artifactRoot}` → `ImportReceipt` → 新工程 provisioning | 01 | TC-F01-02/04 |
| speech/image 工具 | J `runTool`（白名单+5）→ sidecar kind（B 已路由） | 仅 J 白名单+契约 | `tools/{tool}` body → AcceptedJob → 产物落素材库 | 02 | TC-F02-01～04 |
| 工具契约锁 | `tests/deployment/hypit-tools.contract.test.ts`（新） | 静态解析三侧 | 契约↔B kind↔J 白名单 差集断言 | 02/03 | TC-F02-05 |
| snapshot | J `runTool`（+snapshot）→ B 新 kind → preview 注册表 → `snapshotFrames` → 资源句柄 | 新处理器 | `{previewSessionId,at[]}` → `{frames:[{assetId,frameIndex}]}` | 03 | TC-F03-01/02 |
| capture | J `runTool`（+capture.*）→ B 新 kind → url-policy/受限脚本 → 资源句柄 | 新处理器+D-07 | `{url,…}`/`{scriptPath}` → `outputs` | 03 | TC-F03-03/04 |
| packages.build/pack | J `runTool`（+2）→ B 既有 `runPackageTool`（06 迁 runner） | 白名单+迁移 | `{projectPackagePath,revision?}` → tarball 句柄 | 03/06 | TC-F03-05、TC-F06-* |
| agent-jobs | J 新 `HypitAgentJobService` → `hypit_job` INSERT → `HypitAgentWorker.runScheduled`（既有 @Scheduled）→ `HypitAgentStepService`（+planner） | 投递者+planner+GET/POST | `{requestId,intent,brief,…}` → AcceptedJob → 动作行/终态 | 04 | TC-F04-01～05/08 |
| jobs actions POST | J `HypitJobController.submitAction`（resume/cancel）→ job 状态机 | 换桩 | `{requestId,action,input?}` → resumed/canceled | 04 | TC-F04-04/05 |
| runtime actions/paths | J `HypitRuntimeController.action/paths` → sidecar（复用 programs/buildOps 面） | 换桩 | §6.6/6.7 载荷 → 健康摘要/影响清单/路径清单 | 04 | TC-F04-06/07/09 |
| vocabulary 扩展 | J `/vocabulary` 参数透传 → B `describeVocabulary` 扩展 → G 原生 `listSurfaces/visualSchema` | 参数+字段 | `?surface=&visual=` → 词法/schema | 05 | TC-F05-01/02 |
| 编译隔离 | B `runBuild` → `runner/client` compile → runner tsc | 迁移执行位置 | staged 路径 → 诊断+产物 | 06 | TC-F06-01～03 |

新模块调用方/注册位置：`HypitAgentJobService` 由 `HypitProjectController`（agent-jobs 路由）与 `HypitJobController`（actions 路由）构造注入（Spring @Service）；B 新 kind 处理器经 dispatcher default 谓词注册（`isSnapshotTool/isCaptureTool` 数组常量与既有 `speechTools` 同构）；契约测试经 ci.yml 清单注册（W28）。

---

## 4. 目标行为

### 4.1 用户流程（变化部分）

S1 导出：创作者在工程头部点「导出」→ 前端 `exportProject`（既有函数）POST export → 202 → 轮询 job → 完成后经既有受保护 export 资源通道下载 bundle；失败时错误码与原因展示。
S2 Agent：创作者填 intent+brief → POST agent-jobs → 202 → SSE 观察 planning→executing→（waiting_input 时页面展示补充入口）→ resume/cancel → 终态。
S3 快照：预览面板存活时审片者点「取帧」→ POST tools/snapshot → 202 → 帧图出现在素材库并可在评论中引用。

### 4.2 行为变化表（全部为「契约已声明但不可用 → 可用」；既有可用行为零变化）

| 场景 | 当前行为 | 目标行为 |
|---|---|---|
| POST export / POST /imports（真 broker） | `unknown_kind` 失败 | 全链成功；同 requestId 幂等回读 |
| POST `P/agent-jobs` | 503 桩 | 202 AcceptedJob；planner 首步；超步/缺料 waiting_input |
| GET `P/agent-jobs` | 503 桩 | 本人任务分页列表 |
| POST `P/jobs/{id}/actions` resume | 503 桩 | waiting_input→queued+input 并入；scope 不扩张；非 waiting_input → 409 `hypit_state_conflict` |
| POST `P/jobs/{id}/actions` cancel | 503 桩 | queued/running/waiting_input→canceled（幂等）；终态重复 cancel 200 无副作用；终态 resume 409 |
| POST `/runtime/actions` | 503 桩 | D-03 语义（doctor/up/down 实装；init/use/unset 409 `hypit_runtime_managed`） |
| GET `/runtime/paths` | 503 桩 | operator 逻辑路径清单（§6.7） |
| TOOLS_INVOKE `speech.*/image.*` | 400 `hypit_unsupported_action` | 202 → 产物落素材库；transcribe 前置程序闸 |
| TOOLS_INVOKE `snapshot/capture.*/packages.build/pack` | 400 同上 | 202 → §6.3/6.4 各产出 |
| GET `/vocabulary?surface=&visual=` | 参数被忽略 | 返回词法/形状 schema；无参响应不变 |
| `packages.build` 执行位置 | broker spawnSync tsc | runner 进程内 tsc（D-08）；runner 不可达显式失败 |

### 4.3 状态定义（新增面；既有 hypit_job.state 枚举不变）

业务对象状态（权威=`hypit_job.state` + `checkpoint_json.phase`）：

| 状态 | 进入条件 | 可执行操作 | 展示内容 | 离开条件 |
|---|---|---|---|---|
| queued(planning) | POST agent-jobs 受理 | cancel | 「规划中」 | worker 认领 |
| running(planning) | worker 认领 | cancel | planner 生成计划 | 计划落库→executing / 两次失败→failed |
| running(executing) | 计划就绪 | cancel | 当前 action/进度 | 全步完→succeeded；越步→waiting_input；异常→failed |
| running(waiting_input)（无租约） | 达到 maxSteps 或 action 明示需补充 | resume/cancel | 阻塞原因+补充入口 | resume→queued；cancel→canceled |
| succeeded/failed/canceled | 终态 | 只读 | 终态+原因 | 不重开 |

UI 请求状态（idempotent 轮询/SSE）与业务状态分离：HTTP 202 只代表受理，作业进度以 job 状态/SSE 事件为准（既有面）。

### 4.4 状态迁移规则

```text
queued(planning) --worker claim(租约45s)--> running(planning)
running(planning) --计划校验过--> running(executing)（checkpoint.actions 落库，原子 saveCheckpoint）
running(planning) --LLM 计划两次无效--> failed(planner_failed 留证)
running(executing) --全步完成--> succeeded
running(executing) --stepIndex≥maxSteps 或 action.waiting--> running(waiting_input)（lease_until 置 NULL、事件 waiting_input 终帧）
running(waiting_input) --resume(input)--> queued（stepIndex 不变、inputs 并入；allowedTools 重算 ⊆ 原集）
running(waiting_input)/queued/running --cancel--> canceled（worker 下一步边界观察停止）
任意终态 --resume--> 409 hypit_state_conflict；--cancel--> 200 幂等无新副作用（仅 canceled 上）
崩溃恢复：租约过期（45s）→ observe 侧重排或下次 claim 重领 → 只重放确定性动作（input_hash 命中跳过；unknown remote 不重发，K06.1 既有）
```

非法迁移拒绝结果：failed 上 resume → 409；succeeded 上 cancel → 409 `hypit_state_conflict`（canceled 幂等例外）。并发：cancel 与 worker step 收尾竞态时 canceled 优先，动作行已落的不回滚（state 收口 canceled）。刷新恢复：前端沿既有 job SSE（Last-Event-ID 续播）恢复视图；waiting_input 状态可刷新后继续 resume。

---

## 5. 业务规则与不变量

### 5.1 输入规则（新增/实装面权威输入）

| 字段 | 类型 | 必填 | 默认值 | 允许范围 | 空值处理 | 示例 |
|---|---|---:|---|---|---|---|
| agent-jobs `intent` | string | 是 | — | `analyze/plan/author/review/revise` | 空/未知→400 | `author` |
| agent-jobs `brief` | string | 是 | — | 1..8000 字符（trim 后计） | trim 后空→400 | `复刻这条 15s 口播` |
| agent-jobs `assetIds` | uuid[] | 否 | `[]` | ≤16 项、须本工程 ready 素材 | 缺省=空 | — |
| agent-jobs `baseRevision` | bigint | 否 | 受理时 head | ≥1 | null=受理时 head 快照 | `2` |
| agent-jobs `scope` | object | 否 | intent 默认集 | 只可缩不可扩（D-04） | 缺省=intent 集 | `{"allowedTools":["media.probe"]}` |
| actions `action` | string | 是 | — | `resume/cancel` | 未知→400 | `resume` |
| actions `input` | object | 否 | — | JSON ≤64KiB、键=补充资料句柄/文本 | resume 无 input 允许（纯确认） | `{"note":"使用 en 字幕"}` |
| runtime `action` | string | 是 | — | `init/use/unset/up/down/doctor` | 未知→400 | `doctor` |
| runtime `profileId` | string | 否 | — | 传了也 409（init/use/unset 专用） | — | — |
| runtime `endpointIds` | string[] | 否 | 两程序全集 | ⊆{`whisperx.local`,`image.opencv.local`}（可带 `image.opencv.local` 形态别名，以 `programs/catalog.ts` 为准） | 缺省=全集 | — |
| runtime `expectedActivityHash` | string | 否 | — | 仅 down；与 build.activity 聚合 hash 比 | 有活跃 build 且缺失→409 | `9f1c…` |
| snapshot `previewSessionId` | uuid | 是 | — | 存活 session | 过期/不存在/非本工程→409 `hypit_session_expired` | — |
| snapshot `at[]` | number[] | 否（与 ranges 二选一） | [0,中,尾] | 安全整数∈[0,frameCount) | 缺省走默认调度 | `[0,12,24]` |
| snapshot `ranges[]` | object[] | 否 | — | 每页 ≤50 帧、游标分页 | 与 at 并存→400 | — |
| capture.screenshot `url` | http(s) | 是 | — | url-policy 逐跳校验通过 | 拒绝目标→400 `hypit_url_denied` | `https://example.com` |
| capture.screenshot `viewport/selector/waitFor/fullPage/format` | object/string/bool | 否 | 1280x720 等 | 沿上游 flags 语义 | — | — |
| capture.run `scriptPath` | string | 是 | — | workspace-relative、存在于工程 head | 越出工程/不存在→400 | `capture/main.mjs` |
| capture.run `args` | string[] | 否 | `[]` | ≤16 项、每项 ≤2KiB | — | — |
| speech.transcribe `assetId` | uuid | 是 | — | 本工程 ready 素材（音/视） | 非 ready→400 | — |
| speech.transcribe `language` | bcp47 | 是 | — | `zh/en/ja/es/auto`（whisperx alignmentLanguages 交集） | 空/未知→400 | `zh` |
| speech.measure `text` | string | 是 | — | ≤20000 字符 | trim 后空→400 | — |
| image.transform `operations` | object[] | 是 | — | raster 词表闭合集（上游 schema） | unknown op→400 | `[{"op":"resize",…}]` |

长度计量：字符数（UTF-16 code unit，与既有 `HypitJson` 口径一致）；trim=去首尾空白。

### 5.2 校验规则（服务端权威；前端可提前拒绝但不替代）

| 规则编号 | 校验条件与先后顺序 | 校验位置 | 失败结果/文案 | 不允许发生的副作用 | TC |
|---|---|---|---|---|---|
| RULE-F01 | 白名单→requestId 非空→body 结构→业务枚举（依次短路） | J Controller/Service | 400 `hypit_unsupported_action`（未登记工具）或 `hypit_invalid_input`（结构/枚举） | 不落 command/job 行 | TC-F02-04 |
| RULE-F02 | transcribe 前置：whisperx 程序 health 且身份匹配 | B `tools/speech.ts`（已实现） | 409 `hypit_program_not_ready` | 不调 LLM、不产伪词表 | TC-F02-02 |
| RULE-F03 | agent scope：intent 默认集 ∩ 调用方 scope（只缩不扩），服务端在投递时收敛 | J 投递路径 | 越权项被剔除并记 `scope_narrowed` 事件（不 400） | 后续 action 越权→action 行 failed `hypit_scope_denied` | TC-F04-03 |
| RULE-F04 | resume 不扩张：input 并入后重算 allowedTools ⊆ 原集 | J `HypitAgentJobService.resume` | 扩张项对应 action 拒（failed 留痕） | maxSteps/预算上限不变 | TC-F04-04 |
| RULE-F05 | down 保护：活跃 build>0 且（hash 缺失或≠activityHash）→409 | J runtime action | 409 `hypit_activity_conflict`+`{buildIds,activityHash}` | 程序不动 | TC-F04-07 |
| RULE-F06 | capture 静态门禁+禁网默认+输出根约束+超时 1..120000ms | B `tools/capture.ts`（已实现） | 400 `hypit_invalid_input` | 无浏览器进程产生 | TC-F03-04 |
| RULE-F07 | 导入 bundle 逐文件 sha256+size 校验、禁路径/符号链接、数量体积门，**先全量校验后落盘** | B `import.ts`（已实现，接线不得破坏） | 400 `invalid_input` 系列 | 拒收时零工作区残留 | TC-F01-04 |

多项同时失败按表列顺序首个生效（RULE-F01 最先）。

### 5.3 业务判断规则

```text
IF runtime action="down" AND 活跃build>0 AND (expectedActivityHash 缺失 OR != activityHash)
THEN 409 hypit_activity_conflict（响应含 buildIds+当前 activityHash）
ELSE IF action="down" THEN endpointIds（缺省两程序）programs down（幂等，已 down 再 down=成功无副作用）

IF agent-jobs POST 且 intent/brief 合法
THEN 单事务 INSERT command+job(queued,phase=pending,checkpoint=planning) → 事件 accepted → 202
     worker 认领 → planner 生成 actions（≤maxSteps 条）→ 逐 action 执行
     planner 两次无效 → job failed（error_code=planner_failed，保留两次原始输出于 result_json）

IF TOOLS_INVOKE tool ∈ J 白名单（19 项）
THEN 202 AcceptedJob（复用 runAssetJob：command+job 同事务记账→sidecar 执行→收敛）
ELSE 400 hypit_unsupported_action（现状语义不变）

IF snapshot 且 session 存活且 at/ranges 合法
THEN 编译取 document → 调度 → 渲染 → 资源句柄；不创建导出 Build、不计远程模型费
```

RULE 编号关联：上述分支分别对应 RULE-F05/F03-F04/F01/F06+D-05；默认分支=按 §6 各接口契约响应。

### 5.4 权限与业务不变量

| 主体/角色 | 资源关系 | 允许操作 | 禁止操作及服务端拒绝结果 | 测试编号 |
|---|---|---|---|---|
| project owner | 本人 ready 工程 | TOOLS_INVOKE 19 工具、agent-jobs 本人 GET/POST、本人 job actions、export | 他人工程→404/403（`requireReadyOwner` 既有）；全局 `/jobs/{id}/actions` 非本人提交→403 | TC-F02-04、TC-F04-05 |
| operator | `HYPIT_OPERATOR_ACCOUNT_IDS` | runtime/actions、runtime/paths、programs、全局 jobs actions（对任意） | 非 operator→403 `hypit_operator_required`（既有码） | TC-F04-06 |
| 匿名/未登录 | — | 无 | 401/登录弹窗（edge 既有） | — |

- 不变量：同 (account,requestId) 至多一条 command（既有唯一索引兜底）；`hypit_job_action` `UNIQUE(job,step_index)` 幂等；export/import 重放回读不重打包/重落盘；错误响应与日志不含 secret/宿主绝对路径/bundle 字节。
- 校验失败副作用：RULE-F01 拒绝时零 command/job 行；RULE-F05 拒绝时程序状态不变；RULE-F07 拒收时目标工程目录不创建。

---

## 6. 接口契约（新增/实装面；其余 69 路由与 8 media 工具契约不变）

### 类型与调用签名（新增/变更的跨模块契约）

```java
// platform-java/.../hypit/agent/HypitAgentJobService.java（NEW，C107F-04）
public final class HypitAgentJobService {
  /** 创建 hypit.agent job（intent 白名单 + scope 收敛 D-04）；同 requestId 幂等返回同 job。 */
  public Mono<Map<String, Object>> create(String accountId, UUID projectId, UUID requestId,
      String intent, String brief, List<UUID> assetIds, Long baseRevision, Map<String, Object> scope);
  /** 本人任务分页列表（limit≤100 默认 20，after=jobId 游标）。 */
  public Mono<Map<String, Object>> list(String accountId, UUID projectId, int limit, UUID after, String state);
  /** resume：waiting_input→queued 并入 input（RULE-F04）；cancel：状态机 §4.4。 */
  public Mono<Map<String, Object>> submitAction(String callerAccount, UUID projectId, UUID jobId,
      UUID requestId, String action, Map<String, Object> input);
}
```

```typescript
// platform-hypit/backend/src/project-package/export.ts（C107F-01 变更）
export type ExportReceipt = {
  readonly artifactRoot: string;
  readonly fileCount: number;            // NEW（D-02）= manifest.files.length
  readonly manifest: ProjectPackageManifest;
};
```

```typescript
// platform-hypit/backend/src/tools/snapshot.ts（C107F-03 新增 kind 处理器；纯函数不动）
export async function runSnapshotTool(
  ctx: SnapshotToolContext,            // { sessions: PreviewSessionRegistry, distributionRoot, registerResource, allowedRoots }
  payload: { previewSessionId: string; at?: number[]; ranges?: unknown[]; pageSize?: number },
): Promise<{ frames: Array<{ assetId: string; frameIndex: number }>; pages: number }>;
// tools/capture.ts（C107F-03 新增）
export async function runCaptureTool(
  ctx: CaptureToolContext,             // { captureBrowserCache?: string; urlPolicy; projectRootFor; registerResource }
  kind: "capture.screenshot" | "capture.run" | "capture.install-browser",
  payload: Record<string, unknown>,
): Promise<Record<string, unknown>>;
```

```typescript
// platform-hypit/backend/src/engine/vocabulary.ts（C107F-05 扩展；既有字段不动）
export type Vocabulary = {
  readonly providers: readonly VocabularyProvider[];
  readonly programs: readonly VocabularyProgram[];
  readonly alignmentLanguages: readonly string[];
  readonly surfaces?: readonly SurfaceListing[];   // ?surface= 时存在（原生 listSurfaces 输出）
  readonly visual?: unknown;                        // ?visual= 时存在（原生 visualSchema 输出）
};
export async function describeVocabulary(distributionRoot: string,
  options?: { readonly surface?: readonly string[]; readonly visual?: string }): Promise<Vocabulary>;
```

声明完整、语法有效；`SnapshotToolContext/CaptureToolContext` 字段为实现契约（执行者按此签名落，内部辅助可裁量）。

### API-F01：POST `/api/hypit/projects/{projectId}/export`（实装修复，路由/旗标既有）

- 请求方法/路径：POST `/api/hypit/projects/{projectId}/export`；登录必须；权限 project owner（`requireReadyOwner`）。
- 幂等：`requestId`（command 唯一键 `export:{projectId}`+payloadHash 冲突 409 既有语义）。
- 请求载体：JSON body；Edge 登记：`EDGE_ROUTE_HYPIT_PACKAGES_EXPORT`（默认 false）；身份经 edge 内部断言（不信任浏览器头）。
- 超时/重试：受理即 202；job 异步完成；客户端沿既有 job 轮询/SSE。

请求参数（`title?` trim 后 ≤200 字符；`runFile?` 缺省 `main.svrun`）：

```json
{ "requestId": "0d9a4b6e-…", "title": "口播克隆-0926", "runFile": "main.svrun" }
```

成功响应（202，信封 `{success,data,error}`；data=AcceptedJob）：

```json
{ "success": true, "data": { "jobId": "b3f1…", "state": "queued", "resourceId": "proj_…" } }
```

job 完成结果（轮询 GET job / SSE 终帧 data）：

```json
{ "artifactRoot": "exports/2026/09/proj_9f1c.tar", "fileCount": 34, "manifest": { "format": "y1.hypit-project@1", "files": [ { "path": "main.svml", "sha256": "…", "sizeBytes": 1204, "role": "source" } ] } }
```

- 成功后前端动作：展示 artifactRoot+fileCount、启用下载（既有受保护 export 通道）。
- 错误：`400 hypit_invalid_input`（title 超长等）；`404 hypit_project_not_found`（他人/不存在）；`409 hypit_command_conflict`（同 requestId 异 payload）；`502 hypit_sidecar_error`（B `unknown_kind` 类——实装后此路径应消失，负向 TC 锁定透传语义）。
- 契约参照：107-1 §6.2 `P/export` 行；`HypitProjectPackageService.export`（既有）；`ExportReceipt`（§6 类型块）。

### API-F02：POST `/api/hypit/imports`（实装修复）

- 登录必须；权限任意登录账号（创建新工程）；幂等 `requestId`；载体 multipart（bundle 文件）；Edge：`EDGE_ROUTE_HYPIT_PACKAGES_IMPORT`。
- B 侧暂存：bundle 落 `stagingRoot`（导入器只接受 staging 内相对 `artifactRoot`，`..`/绝对路径拒绝——既有校验）。

成功 202 AcceptedJob；job 完成结果：

```json
{ "projectId": "6c2e…", "revision": 2, "fileCount": 34 }
```

- 错误：`400 hypit_invalid_input`（artifactRoot 非法/manifest 拒绝/哈希不匹配——B `invalid_input` 透传）；拒收零工作区残留（RULE-F07）。
- 前端动作：完成事件后跳新工程（provisioning→ready 轮询既有）。

### API-F03：POST `/api/hypit/projects/{projectId}/tools/{tool}`（白名单扩展）

- 登录必须；project owner；幂等 `requestId`；JSON body `{requestId, input:{…}}`；Edge：`EDGE_ROUTE_HYPIT_TOOLS_INVOKE`（既有）。
- tool ∈ 19 项白名单（D-01 终态；C107F-02 落 13、C107F-03 补 6）：

```text
media.probe/cut/frames/tile/tiles/boundaries/fetch/prepare-fetch（8，既有）
speech.transcribe/speech.measure/speech.align（3）
image.transform/image.compose（2）
snapshot（1）；capture.screenshot/capture.run/capture.install-browser（3）
packages.build/packages.pack（2）
```

- 成功 202 AcceptedJob（同 API-F01 形状）；产物统一落素材库/资源句柄，**不返回任意本地路径**（契约原句）。
- 逐工具 input/output 见 `contracts/hypit-tools.v1.json` 对应条目（重生成后）与 §5.1；代表例：

```json
// POST tools/snapshot
{ "requestId": "…", "input": { "previewSessionId": "a17b…", "at": [0, 12, 24] } }
// job 结果
{ "frames": [ { "assetId": "f001…", "frameIndex": 0 }, { "assetId": "f002…", "frameIndex": 12 }, { "assetId": "f003…", "frameIndex": 24 } ], "pages": 1 }
```

- 错误：`400 hypit_unsupported_action`（未登记）；`400 hypit_invalid_input`（参数）；`409 hypit_program_not_ready`/`hypit_session_expired`/`hypit_capture_not_configured`/`hypit_state_conflict`（按工具）。

### API-F04：GET/POST `/api/hypit/projects/{projectId}/agent-jobs`（实装）

- 登录必须；project owner；POST 幂等 `requestId`（command `agent.create:{projectId}`）；Edge：`AGENT_JOBS_CREATE/LIST`。
- POST body 与成功响应：

```json
{ "requestId": "…", "intent": "author", "brief": "按参考视频复刻 15s 口播",
  "assetIds": ["8d4f…"], "baseRevision": 2, "scope": null }
```

```json
{ "success": true, "data": { "jobId": "e5c9…", "state": "queued", "resourceId": null } }
```

- GET `?limit=20&after=<jobId>&state=waiting_input`：

```json
{ "success": true, "data": { "items": [ { "jobId": "e5c9…", "intent": "author", "state": "running",
  "stepIndex": 3, "maxSteps": 40, "createdAt": "2026-09-26T10:00:00Z", "updatedAt": "…" } ], "nextCursor": null } }
```

- 错误：`400 hypit_invalid_input`（intent/brief，RULE-F01）；`404`（他人工程）；`409 hypit_command_conflict`（同 requestId 异 payload）。

### API-F05：POST `/api/hypit/jobs/{jobId}/actions`（及项目级别名 `POST P/jobs/{jobId}/actions`）（实装）

- 登录必须；权限=job 提交 account 或 operator（全局路径）；项目级路径额外校验 projectId 归属（107-1 §6.2：项目路径是别名，不另存任务）；幂等 `requestId`；Edge：`JOBS_ACTION_SUBMIT/ACTION_SUBMIT_PROJECT`。
- body 与响应：

```json
{ "requestId": "…", "action": "resume", "input": { "note": "改用 en 字幕" } }
```

```json
{ "success": true, "data": { "jobId": "e5c9…", "state": "queued", "accepted": "resume" } }
```

- 错误：`409 hypit_state_conflict`（终态 resume / succeeded 上 cancel）；`403`（非本人非 operator）；`400 hypit_invalid_input`（action 枚举/input 超 64KiB）。cancel 幂等：canceled 上重复 cancel → 200 `{accepted:"cancel",state:"canceled"}` 无新副作用。
- GET actions（已实装，读 `hypit_job_action` 持久行）不变。

### API-F06：POST `/api/hypit/runtime/actions`（实装）

- 登录必须；operator（`requireOperator`）；幂等 `requestId`；Edge：`RUNTIME_ACTION`。
- body 与 doctor 响应（EXAMPLE 值）：

```json
{ "requestId": "…", "action": "doctor" }
```

```json
{ "success": true, "data": {
  "engine": { "ready": true, "version": "0.2.13" },
  "programs": { "whisperx.local": { "state": "up", "health": "ok" }, "image.opencv.local": { "state": "down", "health": null } },
  "render": { "active": 0, "max": 1 },
  "activity": { "activeBuilds": 1, "activityHash": "9f1c…" } } }
```

- up/down：受理 202（AcceptedJob 或同步幂等结果——**选同步幂等结果**：`{action:"up", endpoints:{…每程序终态}}`，down 同；因 programs 管线本身幂等且快）。down 409 影响清单：

```json
{ "success": false, "error": { "code": "hypit_activity_conflict", "message": "存在活跃构建",
  "buildIds": ["b1…","b2…"], "activityHash": "9f1c…" } }
```

- init/use/unset：409 `hypit_runtime_managed`（message 说明走 RUNTIME_PROFILE_PUT）。
- 错误另有：`400 hypit_invalid_input`（action 枚举/endpointIds 越界）；`403 hypit_operator_required`。

### API-F07：GET `/api/hypit/runtime/paths`（实装）

- operator；无副作用；Edge：`RUNTIME_PATHS`。响应只含**逻辑标识**（宿主绝对路径不经公网 API——与 107-1 §6.2「真实宿主路径只向部署者显示」一致：公网面=逻辑路径，宿主路径获取方式记 `deploy/hypit/README.md`）：

```json
{ "success": true, "data": { "logical": {
  "projectsRoot": "<dataRoot>/projects", "artifactsRoot": "<dataRoot>/artifacts",
  "importStagingRoot": "<dataRoot>/import-staging", "stateRoot": "<hostStateRoot>",
  "programsHome": "<dataRoot>/programs", "runnerSlots": "<dataRoot>/runner-slots",
  "captureBrowserCache": "<configured-or-null>" }, "hostPathsRevealed": false } }
```

### API-F08：GET `/api/hypit/vocabulary?surface=<pkg[,pkg…]>&visual[=<shape>]`（扩展）

- 登录必须（既有鉴权不变）；无副作用；Edge：`KNOWLEDGE_VOCABULARY`（既有）。
- `surface`：`?surface=@hypit/seedance,@hypit/gpt-image` → `data.surfaces=[SurfaceListing…]`（module/import/attributes/children/ports/example——上游字段原样）；未知包 → `400 hypit_invalid_input`。
- `visual`：`?visual=text-typography` → `data.visual=<schema>`（与上游 `visualSchema("text-typography")` 同构）；`?visual`（无值）→ 形状清单 14 项。
- 无参响应：`{providers,programs,alignmentLanguages}` 向后兼容（零字段变化）。
- cursor 分页沿 §6.2 `/vocabulary` 行既有约定。

### 6.9 错误契约汇总（新增码全列；其余沿用既有 `hypit_*` 词典）

| HTTP | 实际错误字段与值 | 触发条件 | 用户文案（要点） | 调用方动作/可重试性 |
|---|---|---|---|---|
| 400 | `error.code="hypit_unsupported_action"` | 工具/动作未登记（既有码） | 工具未登记或未开放 | 不重试 |
| 400 | `"hypit_invalid_input"` | 结构/枚举/越界（既有码） | 参数非法 | 修正入参 |
| 403 | `"hypit_operator_required"` | 非 operator 访问 runtime 面（既有码） | 需部署管理账号 | 换身份 |
| 404 | `"hypit_project_not_found"` | 他人/不存在工程（既有码） | 工程不存在 | 校验 projectId |
| 409 | `"hypit_runtime_managed"`（NEW） | runtime init/use/unset | Profile 由平台管理，请使用 runtime/profile | 改走 PROFILE_PUT；不可重试本端点 |
| 409 | `"hypit_activity_conflict"`（NEW） | down 有活跃工作且 hash 不匹配 | 存在活跃构建 | 处理 build 或带匹配 hash 重试 |
| 409 | `"hypit_capture_not_configured"`（NEW） | capture 未部署（D-07） | 采集浏览器未配置 | 联系 operator；配置后可重试 |
| 409 | `"hypit_state_conflict"` / `"hypit_program_not_ready"` / `"hypit_session_expired"` / `"hypit_command_conflict"` | 既有码，本任务接线消费 | — | 按码处置 |
| 无响应 | 无服务端错误码 | 断网/超时 | 网络错误 | 前端既有兜底；幂等键可安全重试 |

### 6.10 错误处理原则与契约不变量

- MUST NOT 把服务端堆栈/B 侧原始报文透给用户（J 收 B `DispatchError` 映射 §6.9 表；未映射码统一 502 `hypit_sidecar_error` 记日志）。
- 请求进行中重复提交：全部 POST 由 requestId 幂等兜底（同键返回同一受理）；前端沿既有按钮禁用约定。
- 不变量（逐条可断言）：**状态码不变量**——202 受理/409 冲突/4xx 入参集不变，不新增 5xx 成功形态；**字段不变量**——AcceptedJob 三字段、Export/ImportReceipt 字段集冻结；**顺序不变量**——job 事件 sequence 严格递增（events.ts 既有）、agent 动作行 step_index 单调；**重试不变量**——同 requestId 任意次重试产生同一 command 行与同一最终结果；**日志/指标**——允许记录 requestId/jobId/kind/耗时/错误码，禁止记录凭据/bundle 字节/宿主绝对路径；**追踪字段**——command/job id 既有贯穿，不新增。
- 每条不变量对应 TC：状态码/字段（TC-F01-01/02、TC-F02-01）、顺序（TC-F04-01）、重试（TC-F01-03）、日志（TC-F03-04 断言错误路径无脚本正文外泄）。

### 6.11 流式、上传与异步任务契约

- SSE：job events 沿既有（`/jobs/{id}/events`，Last-Event-ID 续播、sequence 去重、终帧幂等）——agent-jobs/actions 复用，不新建流。
- Multipart：仅 API-F02（imports）；沿既有 multipart 解析与大小上限（`HYPIT_MAX_UPLOAD_BYTES`），不手写破坏 boundary 的 Content-Type。
- 异步任务：AcceptedJob→轮询/SSE 既有；waiting_input 终帧事件 `{type:"checkpoint",data:{phase:"waiting_input",reason}}`；resume 后新事件流延续同 job。

---

## 7. 数据模型与迁移

N/A：无新表/新列/新索引/新迁移。`hypit_job.checkpoint_json` 为 JSON 自由列，扩展键 `{phase,intent,brief,inputs}`（旧 job 读取时新键缺省按无处理——worker/step 解析均 `instanceof` 防御，沿现有代码风格）；`hypit_job_action`/`hypit_command` 原样复用。事务边界：agent-jobs 受理=command+job 单事务（照 `HypitProjectService` 既有 `ProvisioningSeed` 模式）；导出/导入 J 侧幂等重放不重发 sidecar（K06.1）。R-LIFECYCLE：不新增资源种类，C107F-07 复跑 `quality:lifecycle` 确认 49+31 零漂移。

---

## 8. UI 实现规格

N/A：无 UI 改动（§1.4）。现有前端 API 函数（`exportProject` 等）对接实装后端点自然可用；不新增面板/样式，无截图义务。

---

## 9. 全局约束

### 9.1 文件白名单 / 黑名单

| 标识 | 精确路径 | 权限 | 本次操作 | 允许改动的符号/段落 | 原因与完成标准 | 所属卡 |
|---|---|---|---|---|---|---|
| W01 | `platform-hypit/backend/src/commands/dispatcher.ts` | 写入 | 修改 | `runKind` default 前新增 project-package 两 case；新增 snapshot/capture 谓词与 import | kind 路由唯一入口；VFX-1 绿 | 01/03 |
| W02 | `platform-hypit/backend/src/project-package/export.ts` | 写入 | 修改 | `ExportReceipt`+fileCount 赋值 | D-02；J 字段对齐 | 01 |
| W03 | `platform-hypit/backend/tests/workspace/project-transfer.test.ts` | 写入 | 修改/新增用例 | dispatcher 级用例（经 runCommand） | TC-F01-01～04 | 01 |
| W04 | `platform-java/.../hypit/template/HypitProjectPackageService.java` | 写入（预计零改动） | 核验 | — | 核验 receipt 字段映射已对齐并记录依据；如确需改动仅限注释 | 01 |
| W05 | `platform-java/.../hypit/template/HypitTemplateIT.java`（test） | 写入 | 修改 | WireMock 桩响应与 B receipt 同构；负向 unknown kind 透传 | TC-F01 断言 | 01 |
| W06 | `platform-java/.../hypit/asset/HypitAssetService.java` | 写入 | 修改 | `MEDIA_TOOLS`→扩展集（符号可更名 `TOOLS`，同步引用） | 白名单 8→13→19 | 02/03 |
| W07 | `contracts/hypit-tools.v1.json` | 写入 | 重生成 | 22 工具终态（02 落 17、03 补 5） | D-01；VFX-3 绿 | 02/03 |
| W08 | `tests/deployment/hypit-tools.contract.test.ts` | 写入 | 新建 | 三向一致断言 | TC-F02-05；CI 红/绿证据 | 02/03 |
| W09 | `platform-hypit/backend/src/tools/snapshot.ts` | 写入 | 修改 | 新增 `runSnapshotTool` 导出；`snapshotSchedule/snapshotFrames` 不动 | TC-F03-01/02 | 03 |
| W10 | `platform-hypit/backend/src/tools/capture.ts` | 写入 | 修改 | 新增 `runCaptureTool` 导出；受限语义函数不动 | TC-F03-03/04 | 03 |
| W11 | `platform-hypit/backend/src/config.ts` | 写入 | 修改 | `captureBrowserCache` 字段+空串默认 | D-07 | 03 |
| W12 | `deploy/hypit/.env.example` | 写入 | 修改 | 新键行+注释（`compose.full.yml` 注释示例一行） | D-07 | 03 |
| W13 | `platform-java/.../hypit/api/HypitJobController.java` | 写入 | 修改 | `submitAction` 换实装 | AC-F04 | 04 |
| W14 | `platform-java/.../hypit/api/HypitProjectController.java` | 写入 | 修改 | agent-jobs GET/POST、项目级 actions 换实装 | AC-F04 | 04 |
| W15 | `platform-java/.../hypit/api/HypitRuntimeController.java` | 写入 | 修改 | `action/paths` 换实装 | AC-F04-05～07 | 04 |
| W16 | `platform-java/.../hypit/agent/HypitAgentJobService.java` | 写入 | 新建 | §6 类型块全部签名 | 投递/列表/resume/cancel | 04 |
| W17 | `platform-java/.../hypit/agent/HypitAgentStepService.java` | 写入 | 修改 | planner 首步；waiting_input 相位 | TC-F04-01/02 | 04 |
| W18 | `platform-java/.../hypit/agent/HypitAgentWorker.java` | 写入 | 修改 | canceled 边界观察；waiting_input 不重领（§4.4 等价法） | TC-F04-05/08 | 04 |
| W19 | `platform-java/.../hypit/agent/HypitAgentScope.java` | 写入 | 修改 | intent→allowedTools 收敛表 | TC-F04-03 | 04 |
| W20 | `platform-hypit/backend/src/engine/vocabulary.ts` | 写入 | 修改 | `describeVocabulary` options 扩展 | TC-F05 | 05 |
| W21 | `platform-java/.../hypit/api/HypitKnowledgeController.java` | 写入 | 修改 | vocabulary surface/visual 参数透传 | TC-F05 | 05 |
| W22 | `platform-hypit/backend/src/tools/packages.ts` | 写入 | 修改 | runBuild/runPack 迁 runner 派发；门禁保留 | D-08 | 06 |
| W23 | `platform-hypit/backend/src/runner/protocol.ts`、`runner/server.ts`、`runner/client.ts` | 写入 | 修改 | compile payload 扩展+路径校验 | D-08 | 06 |
| W24 | J/B 新增测试文件（`.../hypit/**` test、`platform-hypit/backend/tests/**`） | 写入 | 新建 | 仅本卡 TC 对应用例 | 各卡 TC 表 | 各卡 |
| W25 | `tests/e2e/hypit-clone.spec.ts` | 写入 | 修改 | roundtrip 用例（隔离栈；不可跑 skip 带因） | TC-F01-05 | 01 |
| W26 | `docs/任务书/README.md`、`docs/草场开发进度与续接指南.md`、`contracts/hypit-coverage.v1.json`、`deploy/hypit/README.md`（paths 宿主路径获取说明）、`platform-hypit/knowledge/`（若词法文档需补索引） | 写入 | 修改 | 状态同步 | VFX-4 零漂移 | 07 |
| W27 | `docs/任务书/草场任务书-107-fix-1-Hypit功能复刻缺口收口.md` | 写入 | 修改 | 卡表状态回写（唯一允许持久化状态的文件） | §10 表 | 07 |
| W28 | `.github/workflows/ci.yml` | 写入 | 修改 | hypit-contract job vitest 清单加 hypit-tools.contract.test.ts 一行 | CI 绿 | 02 |
| — | `platform-hypit/upstream/**` | 禁止修改 | 无 | 全文件 | V01 sha256 守卫 | 全部 |
| — | `contracts/hypit-api.v1.json` | 禁止修改 | 无 | 全文件 | 76 路由锁不动（实装既有声明） | 全部 |
| — | 既有已执行 migration、真实 `.env`、`scripts/local/**` | 禁止修改 | 无 | — | — | 全部 |

- 写入白名单=上表「写入」行；卡写入集合必须是全局写入集合的子集。黑名单优先。
- 测试生成物：`test-artifacts/task-107/fix1/<卡号>/`（gates.txt 与必要日志；不入库大文件；构建目录/coverage 按工具默认不登记）。
- 本任务前已有改动：无（基线工作树干净）。

### 9.2 项目铁律速查（命中项；全文见模板 §9.2 与根 AGENTS.md）

**R-JAVA**：新 J 代码 WebFlux 非阻塞——LLM/planner 经既有执行环响应式入口，禁止 `.block()`/手动 `subscribe()` 规避生命周期；Jackson record 派生 `is…` 加 `@JsonIgnore`；可选数值包装类型；`switchIfEmpty` 副作用用 `Mono.defer`。
**R-DATA**：无迁移即无迁移铁律触发；跨进程幂等沿 K06.1（command 重放回读）既有模式。
**R-AI**：planner 首步经 `FrozenTextExecutionService.executeIndependent`（独立文本流既有入口）；不旁路 ai_run/预算/并发记录。
**R-QUALITY**：新测试进各层既有位置；`npm run quality:lifecycle` 零漂移；契约测试进 CI hypit-contract 层（W28）；自建 WebTestClient 带 30s responseTimeout。
**R-LIFECYCLE**：不新增资源种类；agent job 崩溃恢复=K06.1 租约语义；登记不新增（复用既有 job/command/action）。
**R-DIR**：手工脚本（如有）`scripts/acceptance/`、本机 `scripts/local/`、证据 `test-artifacts/task-107/fix1/`；不新建根 `test/`。
**R-SAFE**：凭据/宿主路径/bundle 字节不入日志与汇报；不放宽安全检查换绿灯（capture 禁网门禁、url-policy、SSRF 防线原样）。
**R-UI/R-LAYER/R-ENTRY**：N/A（无 UI/无五视图触碰/无入口与部署拓扑变更——R-ENTRY 仅 `.env.example` 模板行，属登记内配置变更）。

### 9.3 验证环境事实

| 项目 | 本任务的精确值/检查方式 |
|---|---|
| 仓库根/shell | `/Users/LXH/claude/y-1`；zsh（`echo ===` 需引号防 = 展开） |
| Node/npm | `export PATH="$HOME/.nvm/versions/node/v24.14.1/bin:$PATH"`；node v24.14.1；backend 依赖已装（`platform-hypit/backend/node_modules`） |
| Java/Gradle | bash 下 `cd platform-java && source ../scripts/lib/java-runtime.sh && ensure_java_runtime 25`；wrapper `./gradlew` |
| 引擎 G | `bash scripts/acceptance/build-107-engine.sh --no-install`（02/04/05 卡）；`build-107-engine.sh` 完整（03 渲染浏览器/06 runner 槽需依赖安装） |
| Docker | Testcontainers 可用（V05 依赖真 PG）；隔离 e2e 栈=DH_E2E 同款配方（`deploy/hypit/compose.test.yml`） |
| 入口地址 | 本地隔离栈 BASE_URL 按 e2e 配方 env；不默认生产 |
| 测试数据 | backend fixtures（`platform-hypit/fixtures/`：speech en/zh WAV、local-web、companions）；合成账号沿 e2e seed |
| 隔离与归属 | compose 项目名/卷按 compose.test.yml 前缀；产物只写 `test-artifacts/task-107/fix1/` |
| 网络/权限 | 本地安装/容器/浏览器可直接执行并记录；无远程受限资源 |
| 产物目录 | `test-artifacts/task-107/fix1/<卡号>/gates.txt` |

### 9.4 安全、性能与兼容规格

| 类别 | 必须明确的项目 | 本任务唯一要求/阈值 | 验证用例 |
|---|---|---|---|
| 安全 | 身份/归属/越权 | §5.4 表；agent scope 服务端收敛；全局 actions=提交者或 operator | TC-F02-04、TC-F04-03/05 |
| 隐私 | 日志/截图脱敏 | 禁凭据/bundle 字节/宿主绝对路径；paths 只回逻辑键 | TC-F04-09 |
| 外部输入 | URL/脚本/上传 | capture URL 逐跳校验；snapshot 拒 URL；脚本仅工程 head+静态门禁；bundle 逐文件哈希 | TC-F03-03/04、TC-F01-04 |
| 性能 | 数据量/上限 | snapshot 每页 ≤50 帧；agent brief ≤8000；actions input ≤64KiB；导入 4GiB/20000 文件既有门禁 | TC-F03-01、TC-F01-04 |
| 异步资源 | 超时/并发/取消 | capture 超时 1..120000ms；runner 编译超时 120s 既有；worker 租约 45s；RenderCapacity 并发 1 既有 | TC-F03-04、TC-F04-08 |
| 兼容 | 旧数据/旧客户端 | checkpoint 新键缺省兼容；无参 vocabulary 零变化；旗标默认 false 未灰度零影响 | TC-F05-02、VFX-3 |

### 9.5 仓库约束适用矩阵

| 约束 | 适用卡号/文件 | 落实动作与验收；或 N/A 原因 |
|---|---|---|
| R-UI | — | N/A：无 UI 改动（§8） |
| R-ENTRY | 03（W12） | `.env.example` 模板行登记；不改 CSP/入口/真实 .env |
| R-JAVA | 01/02/04/05 J 面 | 服务边界/WebFlux 非阻塞/Jackson record 防陷阱；VFX-2 |
| R-DATA | — | N/A：无迁移；幂等沿 K06.1 既有 |
| R-AI | 04 | planner 经 FrozenTextExecutionService；不旁路 ai_run/预算 |
| R-QUALITY | 02（W08/W28）、07 | 契约测试进 CI；lifecycle/docs 零漂移；覆盖率不降阈值 |
| R-LAYER | — | N/A：不触五视图 |
| R-LIFECYCLE | 04/07 | 复用既有登记；崩溃恢复语义 TC-F04-08；VFX-4 |
| R-DIR | 全部 | 产物 `test-artifacts/task-107/fix1/`；不新建 test/ |
| R-SAFE | 全部 | §9.4 表；VFX-6 |

---

## 10. 开发计划与任务总表

| 卡 | 标题 | 端 | 对应需求 | 主要写入文件 | 依赖及交付物 | 验收编号 | 执行状态 |
|---|---|---|---|---|---|---|---|
| C107F-01 | 工程包导出导入接线与契约锁定 | B+J | REQ-F01 | W01～W05、W25 | 无 | AC-F01-01～04；TC-F01-01～05 | NOT_STARTED |
| C107F-02 | speech/image 工具开放与工具契约三向锁 | J+契约 | REQ-F02/F03 | W06～W08、W28 | 无（可与 01 并行） | AC-F02-01～05；TC-F02-01～05 | NOT_STARTED |
| C107F-03 | snapshot/capture/packages.build·pack 工具通道 | B+J | REQ-F04 | W01、W06、W07、W08、W09～W12 | C107F-02（契约测试基建） | AC-F03-01～05；TC-F03-01～05 | NOT_STARTED |
| C107F-04 | 七条 pending 桩清偿 | J（读 B） | REQ-F05/F06 | W13～W19 | 无（planner 的 e2e 深度可选依赖 02，单元层独立） | AC-F04-01～07；TC-F04-01～09 | NOT_STARTED |
| C107F-05 | vocabulary surface/visual 词法补齐 | B+J | REQ-F07 | W20、W21 | 无 | AC-F05-01～02；TC-F05-01～02 | NOT_STARTED |
| C107F-06 | 作者包编译迁入 runner 隔离 | B | REQ-F08 | W22、W23 | 无（03 不写 W22，无冲突） | AC-F06-01～03；TC-F06-01～03 | NOT_STARTED |
| C107F-07 | 文档收口与最终集成验收 | 文档+门禁 | REQ-F09 | W26、W27 | C107F-01～06 全 VERIFIED | AC-F07-01；TC-F07-01 | NOT_STARTED |

执行状态语义：NOT_STARTED→IN_PROGRESS→IMPLEMENTED（代码完成验证未齐）→VERIFIED（§0.3 全满足）；BLOCKED 见 §13。状态在对话更新；持久化仅 W27 回写。

### 10.1 卡间交接协议

| 交接项 | 前置卡输出 | 后置卡读取方式 | 完成证据 |
|---|---|---|---|
| kind 路由 | C01：dispatcher project-package case+fileCount | C07 门禁矩阵引用 | W03 用例绿 |
| 契约测试基建 | C02：`hypit-tools.contract.test.ts` 骨架+17 工具 | C03 扩 5 项断言与契约 | VFX-3 |
| 白名单 | C02：13 项 | C03 扩至 19 项 | TC-F02-04 回归 |
| runner 编译 | C06：compile payload 契约 | C07 矩阵 | TC-F06-01 |

### 10.2 卡间共享写入冲突核查

W01 由 01/03 共写（不同 hunk：01 加 project-package case、03 加 snapshot/capture 谓词；串行 01→03 已排定）；W06/W07/W08 由 02 建、03 扩（同序）；W22 仅 06 写。其余无共享写入。

### 10.3 阶段、依赖与退出条件

| 阶段 | 要交付的结果 | 卡及先后顺序 | 进入条件 | 阶段验收/交付物 | 失败或返工去向 |
|---|---|---|---|---|---|
| M1 断链修复+契约锁 | export/import 全链可用；工具契约三向锁定 | 01、02（可并行） | 基线核验（§2.7） | AC-F01/F02 组；VFX-1/2/3 | 责任卡重做 |
| M2 能力面补齐 | snapshot/capture/packages 入口、七桩清偿、词法、runner 隔离 | 03（依赖 02）→04/05/06（可并行） | M1 出口 | AC-F03/F04/F05/F06 组 | 责任卡 |
| M3 收口 | 文档/证据/矩阵 | 07 | M2 全 VERIFIED | VFX-1～6 全绿+零漂移 | 07 |

01/02/04/05/06 相互独立（不同文件域），AUTO_CHAIN 顺序执行即可；若并行派发须遵守 §10.2 共写序。

### 10.4 高风险与验证前置

| 风险编号 | 可能失败的假设/链路 | 影响需求与卡 | 风险等级及原因 | 最早验证动作/TC | 不成立时的确定处理 |
|---|---|---|---|---|---|
| RISK-F01 | planner LLM 输出不稳定致 agent-jobs 不可用 | REQ-F05/C04 | 中：结构不可控；发现晚 | TC-F04-01/02（执行环桩驱动好/坏两态，先红后绿） | 两次失败→failed 留证属设计内不假成功；执行环入口缺失按 §13.1-8 BLOCKED |
| RISK-F02 | waiting_input 相位与既有 claim SQL 冲突（重领假死或漏领） | REQ-F05/C04 | 中：状态机回归面广 | TC-F04-04/05/08（租约语义三用例） | §4.4 等价法内修正（W18 范围） |
| RISK-F03 | runner compile payload 传 staged 大目录超帧限 | REQ-F08/C06 | 低：slot-local 路径不出帧 | TC-F06-01 | D-08 裁量边界内等价调整 |
| RISK-F04 | e2e 隔离栈本机不可跑 | REQ-F01/C01 | 低：分层验收已备 | C01 验收时探测 | TC-F01-05 NOT_RUN 如实；B/J 层仍须全绿 |
| RISK-F05 | snapshot 渲染依赖 render Headless Shell 未就绪 | REQ-F04/C03 | 中：环境重（完整 G+浏览器） | C03 步 1 探测 build-107-engine 完整物化 | 用例按既有 skip 风格带因；VFX-1 需在可跑环境复验该用例或如实 PARTIAL |

---

## 11. 任务卡

### 卡 C107F-01：工程包导出导入接线与契约锁定

**执行包**：107-fix-1 v1.1.0；对应需求 REQ-F01；执行者按 §10 顺序；本卡负责人/验收人=执行模型（卡级）。

**类型与完成边界**：实现——`project-package.export/import` 两 kind 经 B dispatcher 真实路由，J→B→receipt 全链字段一致；三层证据（B dispatcher 级测试 / J IT 桩字段锁定 / e2e roundtrip 分层如实）。留给后卡：无（07 只读 gates）。

**背景**：FACT——J 派发两 kind 必落 `unknown_kind`（§2.5-1）；`HypitTemplateIT` WireMock 桩掩盖漂移；B 实现与函数级测试齐备（§2.4）。

**输入与前置交付物**：无依赖；基线见 §2.7。

**输出与移交**：dispatcher 两 case + 上下文构造 + `ExportReceipt.fileCount`；B/J 测试与 e2e 用例；gates.txt 供 C107F-07 汇总。

**必读清单**：§3 D-02；§6 API-F01/F02 与类型块；§5.2 RULE-F07；`dispatcher.ts`（runKind 结构与 DispatchError 风格——case 怎么抛错）；`project-package/{export,import}.ts` 全文（上下文字段来源）；J `HypitProjectPackageService` 全文（幂等编排与回包字段读取）；`tests/workspace/project-transfer.test.ts`（Harness 造工程手法）。

**改动文件**（本卡写入集合=W01、W02、W03、W05、W25，§9.1 交集）：

| 精确路径 | 操作/权限 | 允许改动的符号 | 修改目的 | 完成标准 |
|---|---|---|---|---|
| W01 dispatcher.ts | 修改 | `runKind` 新增两 case+`projectPackageContexts(options)`（或内联构造） | kind 路由 | VFX-1 绿 |
| W02 export.ts | 修改 | `ExportReceipt` 类型+返回处 `fileCount` | D-02 | W03 断言过 |
| W03 project-transfer.test.ts | 修改 | 新增 dispatcher 级用例 | TC-F01-01～04 | 用例真实执行非零断言 |
| W05 HypitTemplateIT.java | 修改 | WireMock 桩 JSON+负向用例 | 字段锁定 | VFX-2 该类绿 |
| W25 hypit-clone.spec.ts | 修改 | roundtrip 用例 | TC-F01-05 | 分层如实 |
| W04 HypitProjectPackageService.java | 核验（预计零改动） | — | 字段映射核对 | 记录依据 |

**开始前检查**：1) `git status --short` 记录；HEAD 应=459e998b 或其后续。2) 核对 §2.3/2.5 锚点符号在位（`exportProjectPackage/importProjectPackage` 签名、J `replayOrDispatch` 派发字面量）。3) 基线命令：`cd platform-hypit/backend && npm test -- tests/workspace/project-transfer.test.ts`（应绿）记录用例数；J 侧 `./gradlew :services:intelligence-service:test --tests '*HypitTemplateIT*' …` 基线。4) 引擎 G 物化（本卡 export/import 不执行 G，`--no-install` 足够 typecheck）。

**源码定位**：

```text
dispatcher.ts — switch(kind)：插入点=case "feedback.mutate" 之后、default 之前；
  上下文：ExportContext={projectsRoot,distributionRoot,sourceCommit}、
  ImportContext={projectsRoot,stagingRoot,provisionTemplateDir,provisionTemplateFiles}——
  字段从 dispatcher 既有 options 装配处取同源值；sourceCommit 与 platform-hypit/templates/catalog.json
  的 sourceCommit 同源；provisionTemplate* 与 workspace.provision 的模板目录同一来源。
export.ts:50 exportProjectPackage(ctx, projectId, {title?,selectedRun?})（selectedRun 缺省 "main.svrun" 既有）
import.ts:46 importProjectPackage(ctx, bundleRootRaw, {newProjectId,requestId,title?})
  ——kind 处理器内 newProjectId=randomUUID()、requestId=commandId。
```

**本卡目标行为**：§6 API-F01/F02；kind 处理器错误直接透传 `DispatchError` code（`not_found/not_provisioned/invalid_input/too_large` → J 既有错误映射路径）。

**函数级要求**：

`dispatcher.ts` - 新增 case 处理器

- 完整签名：见 §6 类型块与源码定位（内部辅助函数，不新增公开契约）。
- 输入：payload `{projectId,title?,selectedRun?}` / `{artifactRoot}`。
- 输出：`ExportReceipt`/`ImportReceipt` 原样。
- 副作用：export 只读工程目录+写 artifacts；import 校验后落新工程（函数既有，不得破坏）。
- 不变条件：幂等由 CommandStore (commandId,payloadHash) 兜底，处理器不自建幂等；import 拒收零残留。
- 清理与失败：透传 DispatchError；无额外清理。

- MUST 满足：1. J 桩 JSON 字段集与 B receipt 完全一致（含 fileCount）；2. dispatcher 级测试经 `runCommand`（全链含 CommandStore），非直调函数；3. e2e 用例在隔离栈不可跑时 `test.skip(精确原因)` 沿 hypit-clone 既有风格。
- MUST NOT：不把 bundle 内容/哈希全量写日志；不改 `contracts/hypit-api.v1.json`；不动 import staging 布局；不在本卡动 speech/snapshot 等其他 kind。

**做法**：

| 步骤 | 文件/符号 | 精确动作 | 完成检查点 | 失败时处理 |
|---|---|---|---|---|
| 1 | W01 | 加两 case+上下文构造 | backend typecheck 过 | 上下文字段缺失→找同源值；找不到按 §13.1-1 报 |
| 2 | W02 | fileCount | W03 新断言 | — |
| 3 | W03 | 用例：正向导出/导入（revision=2+sha256 全一致）/幂等重放同 receipt/拒收零残留 | `npm test` 全绿 | 修实现或用例（卡内） |
| 4 | W05 | 桩同构+unknown_kind 透传负向 | VFX-2 该类绿 | — |
| 5 | W25 | roundtrip 用例 | 隔离栈跑或 skip 带因 | RISK-F04 流程 |

**边界与异常场景**：

| 场景编号 | 精确触发条件/输入 | 预期行为与禁止副作用 | TC/责任边界 |
|---|---|---|---|
| C01/E-a | title 空白/超 200 字符 | trim 后缺省/400；不落 command | TC-F01-01 附带断言 |
| C01/E-b | head 漂移：export 受理后并发 apply | 读受理时 head 快照；bundle 自洽 | TC-F01-01 |
| C01/E-c | 同 requestId 重放（export/import 各一） | 回读同 receipt，不重打包（二次打包计数=0 断言） | TC-F01-03 |
| C01/E-d | bundle 篡改（哈希不匹配/缺文件/符号链接/禁路径） | 400 invalid_input；目标工程目录零创建 | TC-F01-04 |
| C01/E-e | workspace 无 head（not_provisioned） | 透传错误码；不产 bundle | TC-F01-01 负向分支 |

**本卡禁止**：不做 snapshot/capture（03 卡）；不扩 J 工具白名单（02 卡）；不改 runner（06 卡）。

**验收**：

- 测试清单：TC-F01-01～05。
- 命令验收：VFX-1（backend 全量）、VFX-2（HypitTemplateIT + 新增 IT 类）、VFX-3、VFX-5（可选层如实）。
- 行为验收：
  - AC-F01-01：Given ready 工程 revision≥1，When POST export，Then 202+job 结果含 artifactRoot/fileCount/manifest 且 fileCount=manifest.files.length。
  - AC-F01-02：Given 已导出 bundle，When POST /imports，Then 202→新 projectId、revision=2、全部文件 sha256 与源一致。
  - AC-F01-03：同 (account,requestId) 二次 export/import，回读同 receipt、零重复打包副作用。
  - AC-F01-04：e2e roundtrip 全链（隔离栈；NOT_RUN 时如实标注不冒充）。
- UI 验收：N/A（无 UI 改动）。
- 保留行为回归：8 media 工具、既有 69 路由、`HypitTemplateIT` 既有用例（桩更新外）零回退。

**完成后**：按 §14 汇报；证据 `test-artifacts/task-107/fix1/C01/gates.txt`。

### 卡 C107F-02：speech/image 工具开放与工具契约三向锁

**执行包**：107-fix-1 v1.1.0；REQ-F02/F03。

**类型与完成边界**：实现——J 白名单 8→13（media8+speech3+image2）；契约重生成 17 项（D-01 已路由集）；新契约测试锁三向并进 CI（W28）。留给 C03：白名单 13→19、契约 17→22、契约测试扩 5 项。

**背景**：FACT——B dispatcher default 分支已路由 `isSpeechTool/isImageTool`（§2.5-3）；J 400 拒之；契约文件无测试消费且 id 漂移（附 C-7）。

**输入与前置交付物**：无依赖。

**输出与移交**：白名单扩展+契约 17 项+`hypit-tools.contract.test.ts` 骨架（含排除集注记机制）+CI 清单行；C03 读取扩展。

**必读清单**：§3 D-01；§6 API-F03；§6.10 不变量；`tools/speech.ts`（`speechTools/SpeechToolContext`/health 闸/canonicalEvidence 语义）、`tools/image.ts`（`imageTools/ImageToolContext`/raster 词表闭合集）、`HypitAssetService.runTool/runAssetJob`；`contracts/hypit-tools.v1.json` 现文（input/output/upstreamEntry 字段风格）。

**改动文件**：W06、W07、W08（新建）、W28。

**开始前检查**：1) 基线：J Asset 相关 IT 类基线绿；backend `npm test -- tests/tools`（speech/image 用例存在且绿）。2) 契约现文 18 项与 B kind 差集清单化（附 C-7）。3) `ci.yml` hypit-contract job 现清单三文件行号。

**源码定位**：

```text
HypitAssetService.java:43-44 — static final Set<String> MEDIA_TOOLS = Set.of("media.probe",…,"media.prepare-fetch");
runTool L~211 — if (!MEDIA_TOOLS.contains(tool)) 400。
tools/speech.ts:25 — export const speechTools = ["speech.transcribe","speech.measure","speech.align"] as const;
tools/image.ts:18 — export const imageTools = ["image.transform","image.compose"] as const;
tools/packages.ts:39 — const PACKAGE_KINDS = new Set(["packages.status","packages.build","packages.pack","packages.install"]);
tools/media.ts — mediaTools 数组（8 项）。
```

**本卡目标行为**：§6 API-F03（13 项集）；§6.10 契约不变量。

**函数级要求**：

`tests/deployment/hypit-tools.contract.test.ts`（新建）

- 输入：仓库内四源——contracts JSON、`HypitAssetService.java` 源码 Set、B `tools/*.ts` kind 常量（正则提取）、预注记排除集 `["packages.install","packages.status"]`。
- 输出：断言——契约 ids − 排除集 == J 白名单；契约 ids ⊆ B kind 全集；排除集 ⊆ B kind 全集。
- 副作用：纯静态读文件。
- 不变条件：不 import backend 模块（避免 G 依赖，CI 契约层无 G）；正则锚定常量名。
- 防假阳性：测试自带「人为漂移」自检（临时改一侧断言红的 diff 证据入 gates）。

- MUST 满足：1. 白名单符号可更名 `TOOLS` 但全部引用同步；2. 契约 input schema 从 `tools/*.ts` 类型注释与上游 CLI flags 复核（`--language/--rate/--pace/--rounding/--padding` 等对应关系）；3. W28 只加一行文件名。
- MUST NOT：不把 `packages.install/status` 加进 J 白名单；不在契约测试里执行 G 代码；不改动 B 工具实现（已存在）。

**做法**：

| 步 | 文件 | 动作 | 检查点 |
|---|---|---|---|
| 1 | W06 | 白名单+5（注释更新「=B 已路由 owner 级 kind」） | J 单测：5 工具 202、未知 400 |
| 2 | W07 | 契约重生成 17 项（id=B kind；upstreamEntry 留 CLI 名） | JSON 合法+字段齐 |
| 3 | W08 | 契约测试（含排除集注记） | 三向绿；漂移自检红证据 |
| 4 | W28 | ci.yml 加一行 | CI hypit-contract 绿 |

**边界与异常场景**：

| 场景编号 | 触发 | 预期 | TC |
|---|---|---|---|
| C02/E-a | whisperx down 时 transcribe | 409 `hypit_program_not_ready`；不产伪词表 | TC-F02-02 |
| C02/E-b | image.transform unknown op | 400 `hypit_invalid_input`（B 既有语义 J 层透传） | TC-F02-03 |
| C02/E-c | 未登记 tool 名 | 400 `hypit_unsupported_action`；零 command/job 行 | TC-F02-04 |
| C02/E-d | measure 空文本/超长 | 400；不落 job | TC-F02-01 附带 |

**本卡禁止**：不动 B speech/image 实现；不接 snapshot/capture（03）；不实装 pending 桩（04）。

**验收**：TC-F02-01～05；命令 VFX-1/2/3。行为验收：AC-F02-01 Given ready 工程+whisperx up，When POST tools/speech.transcribe `{assetId,language:"zh"}`，Then 202→job 产物词级时间单调非重叠；AC-F02-02 Given whisperx down，Then 409 且零产物；AC-F02-03 image.transform 正/负（unknown op 400）；AC-F02-04 未登记工具 400 零副作用；AC-F02-05 三向契约测试退出 0 且漂移自检红（防假阳性证据）。保留行为回归：8 media 工具用例零回退。

**完成后**：§14 汇报；证据 `test-artifacts/task-107/fix1/C02/gates.txt`。

### 卡 C107F-03：snapshot/capture/packages.build·pack 工具通道

**执行包**：107-fix-1 v1.1.0；REQ-F04。

**类型与完成边界**：实现——B 新增 snapshot+capture 三 kind 处理器与路由；D-07 配置；J 白名单 13→19；契约 17→22、契约测试扩 5 项。packages.build/pack 的 B 路由既有，本卡只开放白名单与契约（编译迁移归 06）。

**背景**：FACT——§2.5-4（模块+测试齐备无通道）；D-05/D-06/D-07。

**输入与前置交付物**：C107F-02 的 W07/W08 基建。

**输出与移交**：五工具可达；capture 配置键；C06 读取的 packages 工具行为基线（W03 既有用例）。

**必读清单**：§3 D-05/D-06/D-07；§6 API-F03；`tools/snapshot.ts`（SnapshotRequest/`snapshotSchedule` 纯函数/`snapshotFrames` 第二参=renderHyperframesFrames options）；`tools/capture.ts`（prepareCaptureChrome/受限脚本断言/输出根）；`resources/url-policy.ts`；`preview/sessions.ts`（注册表查询 API）；`resources/handles.ts`（registerResource 包含性写法）；`platform-hypit/upstream/packages/browser-capture/src/`（screenshot flags 语义对照）；backend `tests/media/{snapshot,capture}.test.ts`（既有用例手法）。

**改动文件**：W01、W06、W07、W08、W09～W12。

**开始前检查**：1) 完整引擎物化（render 浏览器）：`bash scripts/acceptance/build-107-engine.sh`（含依赖安装）；环境不可用先探测并记录（RISK-F05）。2) 基线：`npm test -- tests/media` 绿。3) `config.ts` 现键清单（§2.3 无 capture 键）。

**源码定位**：§6 类型块（runSnapshotTool/runCaptureTool 签名）+ §2.3 各文件符号。

**本卡目标行为**：§6 API-F03（19 项集）；D-05/D-06/D-07 语义。

**函数级要求**：

`tools/snapshot.ts` - `runSnapshotTool`

- 输入：payload（§6 签名）；session 必经注册表（不收 URL）。
- 输出：`{frames:[{assetId,frameIndex}],pages}`；帧图经 registerResource 落句柄。
- 副作用：渲染进程（render Headless Shell，provider 内部管理）；不创建导出 Build、不写工程 work 目录。
- 不变条件：帧索引∈[0,frameCount)；每页 ≤50 帧；同 (commandId) 重放幂等。
- 清理与失败：session 失效 409 `hypit_session_expired`；at 越界 400（snapshotSchedule 既有）。

`tools/capture.ts` - `runCaptureTool`

- 输入：screenshot `{url,viewport?,selector?,waitFor?,fullPage?,format?}`；run `{scriptPath,args?}`；install-browser `{}`。
- 前置：`ctx.captureBrowserCache` 空 → 409 `hypit_capture_not_configured`（install-browser 同——配置键即部署意图）。
- 副作用：screenshot 经 url-policy 逐跳校验后受限 Chrome 出网；run 脚本在页面沙箱执行、输出限 outputRoot；产物 registerResource。
- 不变条件：run 脚本必须出自工程 head（workspace-relative 解析在工程根内）；禁网默认（allowNetwork 恒 false——**本任务不开 allowNetwork=true 入口**）。
- 清理与失败：超时/网络失败留诊断不静默取消（107-1 §318 原句）；浏览器进程按 capture.test.ts 既有清理。

- MUST 满足：1. snapshot 默认调度 [0,中,尾]（既有纯函数语义不改）；2. capture.install-browser 固定版本 153.0.8010.12（`hypit.captureBrowser.version`，never latest——契约 testCase 原句）；3. W12 键行注释说明「空=未部署，配置=启用」。
- MUST NOT：不实装 runner capture kind；不放宽 assertRestrictedCaptureScript 门禁；不新增 allowNetwork 公开参数。

**做法**：

| 步 | 文件 | 动作 | 检查点 |
|---|---|---|---|
| 1 | W11/W12 | captureBrowserCache 配置+模板行 | 空配置三 kind 409 |
| 2 | W09 | runSnapshotTool（session→document→schedule→frames→句柄） | TC-F03-01/02 |
| 3 | W10 | runCaptureTool（三 kind 分派） | TC-F03-03/04 |
| 4 | W01 | kind 路由（isSnapshotTool/isCaptureTool 常量+default 分支） | VFX-1 |
| 5 | W06/W07/W08 | 白名单 19+契约 22+测试扩 | VFX-3 三向绿 |

**边界与异常场景**：

| 场景编号 | 触发 | 预期 | TC |
|---|---|---|---|
| C03/E-a | session 过期/非本工程/不存在 | 409 `hypit_session_expired` | TC-F03-02 |
| C03/E-b | at 越界 / at 与 ranges 并存 / 每页 >50 | 400 | TC-F03-01 负向 |
| C03/E-c | 未配置 capture 缓存 | 三 kind 409 `hypit_capture_not_configured` | TC-F03-03 |
| C03/E-d | url-policy 拒绝目标（重定向到禁域） | 400 `hypit_url_denied`；零出网字节到禁域 | TC-F03-03 |
| C03/E-e | capture.run：工程外 scriptPath/禁网 fetch/输出越根/超时>120000 | 400；无浏览器进程 | TC-F03-04 |
| C03/E-f | packages.build 未授权路径/超大产物 | 既有门禁 400/413 语义不变 | TC-F03-05 回归 |

**本卡禁止**：不迁 runner（06）；不实装 pending 桩（04）。

**验收**：TC-F03-01～05；VFX-1（完整 G）/VFX-3。AC-F03-01 Given 存活 preview session（frameCount≥25），When tools/snapshot at=[0,12,24]，Then 202→三帧资源句柄、frameIndex 精确对应、零新 Build；AC-F03-02 session 失效→409；AC-F03-03 未配置→409；配置后 local-web fixture 截图像素>0 且 url-policy 拒绝域零出网；AC-F03-04 capture.run 四负向全 400 且错误路径日志无脚本正文；AC-F03-05 packages.build/pack 202+既有 custom-packages 用例族零回退。

**完成后**：§14 汇报；证据 `…/C03/gates.txt`。

### 卡 C107F-04：七条 pending 桩清偿（agent-jobs / actions / runtime 面）

**执行包**：107-fix-1 v1.1.0；REQ-F05/F06。

**类型与完成边界**：实现——七条路由全部换真实语义（§4.2 表）；agent 投递/列表/resume/cancel 服务+planner 首步+worker 相位兼容；runtime doctor/up/down/paths。完成判据：`grep -rn "pending(\"" platform-java/.../hypit/api/` 零命中（§1.5 列出的 neverMap 辅助行随实装自然删除）。

**背景**：FACT——§2.5-2（七桩+无投递者+空 actions 假成功陷阱）；D-03/D-04。

**输入与前置交付物**：无硬依赖（02 的 speech 工具开放只影响 e2e 深度，单元层独立）。

**输出与移交**：`HypitAgentJobService`（§6 签名）+七端点实装+planner；07 读 gates。

**必读清单**：§3 D-03/D-04；§4.3/4.4 状态机；§5.1/5.2/5.4；§6 API-F04～F08；`HypitAgentWorker`（claim SQL/driveToCompletion/canceled 观察）、`HypitAgentStepService`（runStep/prepared action/LLM 执行环接入点/`actions.insert` 幂等）、`HypitAgentScope`（read_only 白名单机制）、`HypitJobRepository`（insert/claimDue/updateState/saveCheckpoint）、`HypitJobActionRepository`、`FrozenTextExecutionService.executeIndependent`（响应式签名与 ai_run 记账）、B `programs/manager.ts`+`runtime/capacity.ts`（doctor 数据源）、`HypitBuildObserver`/buildOps `globalActivity`（活跃 build 与 activity hash）。

**改动文件**：W13～W19+W24 对应测试。

**开始前检查**：1) 基线：`./gradlew … --tests '*HypitAgent*'`（HypitAgentIT 现绿——测试直插驱动场景）记录用例数；`--tests '*HypitContractTest*'` 基线（其中对七桩的既有断言需同步更新——逐条对应 §6 契约，不允许仅删断言）。2) 核对 `hypit_job` 列（state/phase/lease_owner/lease_until/checkpoint_json）与 events 序列既有约束。3) 确认 `HYPIT_OPERATOR_ACCOUNT_IDS` 测试配置手法（既有 runtime IT）。

**源码定位**：§2.3 七桩符号行；`HypitAgentWorker.claimDue/driveToCompletion`（L66-80 附近）；`HypitDtos.java:44 AcceptedJob`。

**本卡目标行为**：§4.2/4.3/4.4 全表；§6 API-F04～F08。

**函数级要求**（关键公开边界）：

`HypitAgentJobService.create` — §6 签名

- 输入：§5.1 字段；intent/brief 校验 RULE-F01。
- 输出：202 AcceptedJob；同 requestId 幂等回读同 job。
- 副作用：单事务 INSERT command+job（照 `HypitProjectService` ProvisioningSeed 模式）+`accepted` 事件。
- 不变条件：scope 收敛在投递时完成（RULE-F03）；baseRevision=null→受理时 head 快照。
- 清理与失败：校验失败零 command/job 行。

`HypitAgentStepService`（planner 首步）

- 输入：job 行（checkpoint.actions 空、stepIndex=0）。
- 输出：计划写入 checkpoint（≤maxSteps 条、结构校验过）后进入执行；两次无效→job failed `planner_failed`（两次原始输出留 result_json）。
- 副作用：LLM 调用经 FrozenTextExecutionService（ai_run 记账不旁路）；prepared action 行先写后调（既有 K11 语义扩展到 planner 步）。
- 不变条件：planner 输出只含 allowedTools 内工具名。

`HypitAgentWorker` — waiting_input 等价法（§4.4）：waiting_input 表现=state 'queued'+checkpoint.phase='waiting_input'+lease 置空；worker claim 到达后 StepService 先消费 inputs 再续跑；canceled 观察在每 step 边界（state 查询）。

- MUST 满足：1. 七端点行为与 §6 逐条一致；2. planner 坏输出路径有独立 TC（先红后绿）；3. resume 后 allowedTools 重算 ⊆ 原集（断言集相等或子集）；4. `HypitContractTest` 断言更新逐条对应新契约。
- MUST NOT：不新增 job state 枚举值；不改 claim SQL 语义（等价法内解决）；不在 WebFlux 事件循环阻塞（LLM/DB 全响应式）。

**做法**：

| 步 | 文件 | 动作 | 检查点 |
|---|---|---|---|
| 1 | W16 | AgentJobService 四方法 | 单测全迁移+幂等 |
| 2 | W17 | planner 首步+waiting_input 相位 | TC-F04-01/02 |
| 3 | W18 | worker 等价法+canceled 边界 | TC-F04-05/08 |
| 4 | W19 | intent→allowedTools 收敛表 | TC-F04-03 |
| 5 | W13/W14 | actions 端点（全局=提交者或 operator；项目级=owner+归属） | TC-F04-04/05 |
| 6 | W15 | runtime doctor/up/down/managed/paths | TC-F04-06/07/09 |
| 7 | W24 | IT：`HypitAgentJobIT`（执行环桩）/`HypitRuntimeActionIT` | VFX-2 |

**边界与异常场景**：

| 场景编号 | 触发 | 预期 | TC |
|---|---|---|---|
| C04/E-a | intent 非法/brief 空/assetIds>16/跨工程素材 | 400；零 command | TC-F04-01 负向 |
| C04/E-b | planner 两次坏输出 | failed+planner_failed+两次原文留证 | TC-F04-02 |
| C04/E-c | action 越权工具 | action 行 failed `hypit_scope_denied`；job 继续/收口按计划 | TC-F04-03 |
| C04/E-d | resume 连点（同 requestId） | 一回执；input 一次并入 | TC-F04-04 |
| C04/E-d2 | resume 携带扩张 scope 的 input | 扩张项对应 action 拒；原集不变 | TC-F04-04 |
| C04/E-e | cancel×2 / 终态 cancel / 终态 resume | 200 幂等 / 409 / 409 | TC-F04-05 |
| C04/E-f | down：活跃 build×{无 hash,错 hash,匹配 hash} | 409 清单 / 409 / 程序 down | TC-F04-07 |
| C04/E-g | doctor：程序 down/容量满 | 报告如实（ready:false 等）；非 operator 403 | TC-F04-06 |
| C04/E-h | worker 崩溃（杀进程）后租约过期 | 重领续跑；已确认动作不重发（unknown remote 不重发） | TC-F04-08 |
| C04/E-i | paths：任意鉴权 operator | 只逻辑键；hostPathsRevealed:false | TC-F04-09 |
| C04/E-j | 工程 owner 变更后旧 job 操作 | 403 | TC-F04-05 附带 |

**本卡禁止**：不动 B 侧（本卡纯 J，读 sidecar 既有面）；不扩工具白名单（02/03）；不动 MAX_STEPS=40。

**验收**：TC-F04-01～09；VFX-2（新增两类 IT）。AC-F04-01 Given ready 工程+执行环桩（计划=[probe→measure→finish]），When POST agent-jobs，Then 202→事件 planning→executing→succeeded、动作行三步齐+ai_run 绑定、崩溃重启后续跑不重发；AC-F04-02 桩两次返回非法 JSON→failed+留证；AC-F04-03 越权 action 留痕不执行；AC-F04-04 resume 续跑且 allowedTools 前后同集；AC-F04-05 cancel 幂等/终态 409/越权 403；AC-F04-06 doctor 四段聚合+403；AC-F04-07 down 三态；AC-F04-08 worker 恢复语义；AC-F04-09 paths 逻辑键。保留回归：`HypitAgentIT` 既有直插用例零回退。

**完成后**：§14 汇报；证据 `…/C04/gates.txt`。

### 卡 C107F-05：vocabulary surface/visual 词法补齐

**执行包**：107-fix-1 v1.1.0；REQ-F07。

**类型与完成边界**：实现——`describeVocabulary` 增 options（surface/visual）；J `/vocabulary` 参数透传；无参响应零变化。

**背景**：FACT——§2.5-6；上游函数可直接 import（G 树内）。

**输入与前置交付物**：无。

**输出与移交**：扩展后的 vocabulary 面+对拍测试。

**必读清单**：§3 决策表；§6 API-F08；`engine/vocabulary.ts` 现实现；`engine/hypit-bootstrap.ts`（`@hypit/*` import 方式）；上游 `packages/video-cli/src/vocabulary.ts`（`listSurfaces(projectRoot, packages, tags?)`、`visualSchema(shape?)`、`SurfaceListing` 字段、14 形状清单）。

**改动文件**：W20、W21+W24 测试。

**开始前检查**：1) `--no-install` 物化 G；基线 `npm test -- tests/engine`（vocabulary 用例）绿。2) 上游函数签名核对（附 C-6）。3) J `HypitKnowledgeController` vocabulary 现参数面。

**源码定位**：§2.3 vocabulary 两文件；§6 类型块（describeVocabulary options）。

**本卡目标行为**：§6 API-F08。

**函数级要求**：

`describeVocabulary(distributionRoot, options?)` — §6 签名

- 输入：`surface` 包名数组（未知包→`invalid_input`）；`visual` 形状名（14 枚举或省略=清单）。
- 输出：既有三字段恒在；`surfaces`/`visual` 按参附加。
- 副作用：纯读 G 树（distribution 变更即重算，无缓存语义变化）。
- 不变条件：不与知识文档索引混写一个响应（独立 query 参数）；上游字段原样不裁剪。

- MUST：对拍测试（B 输出 vs 上游 CLI 同输入输出或源码 schema 逐字段）。
- MUST NOT：不造目录/不裁剪字段/不加缓存层。

**做法**：B 扩展→backend 单测（任选两真实包：字段非空、未知包 400、14 形状清单完整）→J 透传+IT（含无参兼容断言）→VFX-1/2。

**边界**：E-a 未知包名→400；E-b visual 未知形状→400+形状清单；E-c 无参→三字段与基线逐字节等价（快照断言）。

**本卡禁止**：不动 providers/programs 字段结构。

**验收**：TC-F05-01/02；VFX-1/2。AC-F05-01 `?surface=@hypit/seedance` 字段集与上游 SurfaceListing 一致（对拍证据）；AC-F05-02 `?visual=text-typography` 同构+无参兼容快照。

**完成后**：§14 汇报；证据 `…/C05/gates.txt`。

### 卡 C107F-06：作者包编译迁入 runner 隔离

**执行包**：107-fix-1 v1.1.0；REQ-F08。

**类型与完成边界**：实现——runBuild/runPack 的 tsc 在 runner 进程执行（compile kind）；broker 保留 staging/tsconfig 生成与产物门禁；既有 packages 测试全绿+「broker 零作者代码 tsc」断言；runner 不可达显式失败。

**背景**：FACT——§2.5-5（runner compile 已实现）；D-08。

**输入与前置交付物**：无（03 不写 W22）。

**输出与移交**：隔离达成+等价回归；07 读 gates。

**必读清单**：§3 D-08；`runner/protocol.ts`（帧上限/slot-local 句柄模式）、`runner/server.ts`（compile 现实现与路径校验模式）、`runner/client.ts`/`supervisor.ts`（单执行槽）、`tools/packages.ts` runBuild/runPack 全文（staged/tsconfig/门禁）。

**改动文件**：W22、W23+W24 测试。

**开始前检查**：1) 完整 G+runner 槽环境；基线 `npm test -- tests/engine/custom-packages.test.ts` 用例数。2) `NOT_IMPLEMENTED_KINDS` 现集合核对（不得变）。3) runner 槽目录权限（compose.runner.yml uid 10001 语义了解即可，本地测试用本地槽）。

**源码定位**：§2.3 runner/packages 符号；`packages.ts:235 runBuild`（spawnSync 处）。

**本卡目标行为**：D-08；`packages.build/pack` 对 J/API 消费者零可观察变化（回执字段/错误码/门禁不变）。

**函数级要求**：

`runBuild/runPack`（迁移后）

- 输入/输出/门禁：不变（§6 API-F03 packages 条目+既有用例即契约）。
- 执行位置：`runner/client` 派发 `compile`，payload `{stagedDir, tsconfig, timeoutMs:120000}`（路径限 slot 根内；server 侧校验照 check/plan 模式）；产物以 slot-local 路径回读。
- 失败：runner 不可达→`DispatchError("runner_unavailable", …)` 显式；编译诊断原样回传。
- 不变条件：broker 进程零作者代码 tsc 子进程（测试以子进程计数/spy 断言）。

- MUST：禁止静默回落 broker 编译路径（任何条件下）。
- MUST NOT：不动 `NOT_IMPLEMENTED_KINDS`；不实装 executeLocal/capture。

**做法**：W23 compile payload 扩展+server 校验→W22 runBuild/runPack 改派发→W24 测试（等价回归+零 spawn 断言+不可达显式失败）→VFX-1。

**边界**：E-a runner 槽不可用→`runner_unavailable`（不回落）；E-b 编译错误→诊断原样（与基线错误输出 diff 等价）；E-c 产物超门禁→既有 400/413 语义（broker 收口处不变）。

**本卡禁止**：不改 packages 工具的公开回执；不动 03 的白名单。

**验收**：TC-F06-01～03；VFX-1。AC-F06-01 custom-packages 用例族全绿（行为等价）；AC-F06-02 broker 零作者代码 tsc（断言证据）；AC-F06-03 runner down→显式 `runner_unavailable`。

**完成后**：§14 汇报；证据 `…/C06/gates.txt`。

### 卡 C107F-07：文档收口与最终集成验收

**执行包**：107-fix-1 v1.1.0；REQ-F09；负责人=执行者。

**类型与完成边界**：集成——VFX 矩阵复跑+文档/契约状态同步+交付核销；无代码改动（除非文档）。

**输入与前置交付物**：C107F-01～06 全 VERIFIED 及各自 gates.txt。

**输出与移交**：`test-artifacts/task-107/fix1/C107F-07/gates.txt`（矩阵汇总）；W26/W27 更新；§14 总汇报。

**必读清单**：§12 全节；各卡 gates；`docs/任务书/README.md` 107-fix-1 行；`docs/草场开发进度与续接指南.md` 107 段；`contracts/hypit-coverage.v1.json` 结构（只补 fix1 证据条目，不改既有 24 卡行）。

**改动文件**：W26、W27。

**开始前检查**：六卡 gates 齐备；工作区无未预期改动。

**做法**：

| 步 | 动作 | 检查点 |
|---|---|---|
| 1 | VFX-1～6 逐条跑（VFX-5 按可用性如实） | 矩阵全绿或带因 |
| 2 | 串联流程（§12.5）走一遍并记录 | 跨面证据 |
| 3 | W26 四~五处同步（README 状态、进度指南 fix-1 小节、coverage fix1 条目、deploy README paths 说明、knowledge 索引如需） | VFX-4 零漂移 |
| 4 | W27 卡表回写 VERIFIED+版本记录（附 B.3） | 状态一致 |

**边界**：E-a 矩阵有 NOT_RUN/PARTIAL→如实标注不标 VERIFIED 对应项；E-b 发现回归→退责任卡重做后重验。

**本卡禁止**：不改代码（发现代码问题退责任卡）；不宣称生产可用（交付终点=本地集成验收）。

**验收**：AC-F07-01 VFX-1～6 全绿（例外逐条带因）+文档零漂移+追踪表（§12.1）无悬空编号。

**完成后**：§14 总汇报（含 14.1 检查点）。

---

## 12. 测试、验证命令与集成验收

### 12.1 需求→AC→TC 追踪与逐用例规格

| 需求/不变量 | 实现卡 | 业务/契约条款 | 边界场景 | 验收编号 | 测试编号 | 执行命令 | 证据 |
|---|---|---|---|---|---|---|---|
| REQ-F01 | C01 | §6 API-F01/F02、RULE-F07 | C01/E-a～e | AC-F01-01～04 | TC-F01-01～05 | VFX-1/2/3/5 | …/C01/gates.txt |
| REQ-F02 | C02 | §6 API-F03、RULE-F01/F02 | C02/E-a～d | AC-F02-01～04 | TC-F02-01～04 | VFX-1/2/3 | …/C02/gates.txt |
| REQ-F03 | C02/03 | §6.10 三向不变量 | — | AC-F02-05 | TC-F02-05 | VFX-3 | 同上 |
| REQ-F04 | C03 | §6 API-F03、D-05/06/07、RULE-F06 | C03/E-a～f | AC-F03-01～05 | TC-F03-01～05 | VFX-1/3 | …/C03/gates.txt |
| REQ-F05 | C04 | §4.3/4.4、§6 API-F04/F05、D-04、RULE-F03/F04 | C04/E-a～e/j | AC-F04-01～05/08 | TC-F04-01～05/08 | VFX-2 | …/C04/gates.txt |
| REQ-F06 | C04 | §6 API-F06/F07、D-03、RULE-F05 | C04/E-f～i | AC-F04-06/07/09 | TC-F04-06/07/09 | VFX-2 | 同上 |
| REQ-F07 | C05 | §6 API-F08 | C05/E-a～c | AC-F05-01～02 | TC-F05-01/02 | VFX-1/2 | …/C05/gates.txt |
| REQ-F08 | C06 | D-08、§6.10 回执不变量 | C06/E-a～c | AC-F06-01～03 | TC-F06-01～03 | VFX-1 | …/C06/gates.txt |
| REQ-F09 | C07 | §12.5 | — | AC-F07-01 | TC-F07-01 | VFX-1～6 | …/C107F-07/gates.txt |

#### TC-F01-01：导出全链（dispatcher 级）

| 项目 | 必填内容 |
|---|---|
| 对应条款 | REQ-F01 / AC-F01-01 / API-F01 / C01/E-b、E-e |
| 风险/类别 | 高（本任务主断链）；正向+负向 |
| 测试层级 | 服务集成（B dispatcher 真链路） |
| 实现位置 | `platform-hypit/backend/tests/workspace/project-transfer.test.ts` 新用例（经 `runCommand`） |
| 前置数据 | Harness 按 minimal-local 模板造 ready 工程 revision=1、附加 2 文件 |
| 输入 | kind=`project-package.export`，payload `{projectId, title:"t", selectedRun:"main.svrun"}` |
| 依赖模拟 | 无 mock——走真实 CommandStore/workspace/export 函数（B 层不含 G 执行） |
| 操作步骤 | 1) runCommand 提交；2) 断言回执三字段；3) fileCount==manifest.files.length；4) artifactRoot 落 artifacts 根内；5) 负向：无 head 工程→`not_provisioned` 透传 |
| 预期展示/响应 | 回执 `{artifactRoot,fileCount,manifest}`；job 语义按 CommandStore 状态 |
| 预期副作用 | 只读工程+写 artifacts；无 G 执行、无网络 |
| 最终状态 | 工程目录不变；bundle 可被 import 消费（接 TC-F01-02） |
| 清理 | Harness 目录删除（测试自清） |
| 执行与证据 | VFX-1；gates.txt 记录用例名与断言数 |
| 防止假阳性 | 用例必须经 runCommand（CommandStore 幂等在环）；直调函数版本不算——去掉 kind 路由（回到基线）该用例必红 |

#### TC-F01-02：导入全链与拒收零残留

| 项目 | 必填内容 |
|---|---|
| 对应条款 | REQ-F01 / AC-F01-02 / API-F02 / RULE-F07 / C01/E-d |
| 风险/类别 | 高；正向+负向 |
| 测试层级 | 服务集成（B dispatcher） |
| 实现位置 | 同上文件 |
| 前置数据 | TC-F01-01 产出的 bundle；另造篡改版（改一字节）与符号链接版 |
| 输入 | kind=`project-package.import`，payload `{artifactRoot}` |
| 依赖模拟 | 无 |
| 操作步骤 | 1) 正常导入→`{projectId,revision:2,fileCount}`；2) 新工程逐文件 sha256 与源一致；3) 篡改版→`invalid_input` 且新工程目录零创建；4) 符号链接条目→`invalid_input` |
| 预期展示/响应 | ImportReceipt 三字段 |
| 预期副作用 | 正向：新工程+一次 journal 变更（revision=2）；负向：零残留 |
| 最终状态 | 正向工程可被后续 workspace.check 消费（追加断言） |
| 清理 | Harness 自清 |
| 执行与证据 | VFX-1 |
| 防止假阳性 | 负向用例后 `existsSync(新工程根)===false` 必断言；跳过哈希校验的实现在此必红 |

#### TC-F01-03：幂等重放回读

| 项目 | 必填内容 |
|---|---|
| 对应条款 | REQ-F01 / AC-F01-03 / §6.10 重试不变量 / C01/E-c |
| 风险/类别 | 中；幂等/回归 |
| 测试层级 | 服务集成 |
| 实现位置 | 同上文件 |
| 前置数据 | 同 TC-F01-01 |
| 输入 | 同 commandId+payload 二次 runCommand（export 与 import 各一用例） |
| 依赖模拟 | 无 |
| 操作步骤 | 1) 首次提交记录 receipt 与 artifacts 目录 mtime/条目计数；2) 二次提交；3) 断言 receipt 全等、无新 bundle 目录/计数不变 |
| 预期响应 | 同一 receipt |
| 预期副作用 | 零二次打包 |
| 最终状态 | CommandStore 单行 |
| 清理 | 自清 |
| 执行与证据 | VFX-1 |
| 防止假阳性 | 计数器断言（新目录数=0）；重打包实现在此必红 |

#### TC-F01-04：e2e roundtrip（分层）

| 项目 | 必填内容 |
|---|---|
| 对应条款 | REQ-F01 / AC-F01-04 |
| 风险/类别 | 中；端到端 |
| 测试层级 | 隔离端到端（真实 UI/edge/J/B） |
| 实现位置 | `tests/e2e/hypit-clone.spec.ts` 新用例 |
| 前置数据 | 隔离 hypit 栈（compose.test.yml 配方）+ seed 账号 |
| 输入 | 前端流程：建工程→（脚本注入两文件）→export→下载→imports→断言 |
| 依赖模拟 | 仅隔离栈允许的外部（无真实 provider） |
| 操作步骤 | 1) 建工程；2) export API；3) job 完成；4) POST /imports multipart；5) 新工程 ready 且文件列表匹配 |
| 预期响应 | 全链 202→终态；文件 sha256 匹配 |
| 预期副作用 | 两工程并存 |
| 最终状态 | 断言后清理（栈级） |
| 清理 | 隔离栈销毁 |
| 执行与证据 | VFX-5；栈不可用→NOT_RUN+精确原因（不算 PASS） |
| 防止假阳性 | 不允许 route.fulfill 假装后端；文件内容逐字节断言 |

#### TC-F02-01：speech.transcribe 正向（含 E-d）

| 项目 | 必填内容 |
|---|---|
| 对应条款 | REQ-F02 / AC-F02-01 / RULE-F01 / C02/E-d |
| 风险/类别 | 高；正向 |
| 测试层级 | J IT（真实 sidecar 路径）+B 单测基线 |
| 实现位置 | J `HypitAssetToolIT`（新/扩展）+ backend `tests/tools/speech.test.ts`（既有） |
| 前置数据 | fixtures `speech/zh-*.wav`（真 macOS say 合成）固化工程；whisperx 程序 up（本地服务） |
| 输入 | `{assetId, language:"zh"}` |
| 依赖模拟 | 无（whisperx 本地服务为被测依赖） |
| 操作步骤 | 1) POST tools/speech.transcribe；2) 202；3) job 终态；4) words[].{start,end} 单调且不重叠 |
| 预期响应 | AcceptedJob→job 结果词级时间 |
| 预期副作用 | 产物证据落素材库 |
| 最终状态 | 素材可被引用 |
| 清理 | 测试工程清理 |
| 执行与证据 | VFX-2 |
| 防止假阳性 | 单调断言逐词；空 audio→空词表不造词（附带断言） |

#### TC-F02-02：transcribe 程序未就绪

| 项目 | 必填内容 |
|---|---|
| 对应条款 | REQ-F02 / AC-F02-02 / RULE-F02 / C02/E-a |
| 风险/类别 | 高；负向 |
| 测试层级 | B 单测（既有 health 闸）+J 透传断言 |
| 实现位置 | backend `tests/tools/speech.test.ts`（既有）+ J IT 负向 |
| 前置数据 | whisperx down/身份不匹配 |
| 输入 | 同正向 |
| 依赖模拟 | 无 |
| 操作步骤 | 程序 down→调→断言 409 `hypit_program_not_ready`、零词表产物、零 LLM 调用 |
| 预期响应 | 409 |
| 预期副作用 | 无 |
| 执行与证据 | VFX-1/2 |
| 防止假阳性 | 「假 ready」实现在此必红（身份闸既有） |

#### TC-F02-03：image.transform 词表正负

| 项目 | 必填内容 |
|---|---|
| 对应条款 | REQ-F02 / AC-F02-03 / C02/E-b |
| 风险/类别 | 中；正负 |
| 测试层级 | B 单测（既有）+J 透传 |
| 实现位置 | backend `tests/tools/image.test.ts`（既有）+J IT |
| 输入 | 正向 `[{"op":"resize","width":100}]`；负向 `{"op":"evil"}` |
| 预期 | 202/产物；400 `hypit_invalid_input` |
| 防止假阳性 | unknown op 必 400（词表闭合） |

#### TC-F02-04：白名单负向零副作用

| 项目 | 必填内容 |
|---|---|
| 对应条款 | REQ-F02 / AC-F02-04 / RULE-F01 / §5.4 |
| 风险/类别 | 高；安全负向 |
| 测试层级 | J IT |
| 实现位置 | `HypitAssetToolIT` |
| 输入 | tool=`speech.transcribe`（扩展前基线红）→扩展后 202；tool=`evil.tool`→400；跨工程 assetId→404 |
| 预期 | 400 `hypit_unsupported_action` 且 hypit_command/hypit_job 行数不变（SQL 计数断言） |
| 防止假阳性 | 计数断言；先登记后拒绝实现在此必红 |

#### TC-F02-05：工具契约三向锁

| 项目 | 必填内容 |
|---|---|
| 对应条款 | REQ-F03 / AC-F02-05 / §6.10 |
| 风险/类别 | 高；契约回归 |
| 测试层级 | 契约静态（CI hypit-contract 层） |
| 实现位置 | `tests/deployment/hypit-tools.contract.test.ts` |
| 前置数据 | 仓库四源文件 |
| 操作步骤 | 1) 解析契约 ids；2) 正则提取 J Set 与 B kind 常量；3) 断言两两差集=排除集注记；4) 人为漂移自检（临时改 J 白名单一处→测试红→还原），diff 证据入 gates |
| 预期 | 退出 0；漂移红 |
| 防止假阳性 | 漂移自检证据必须留（无自检视同未验证） |

#### TC-F03-01：snapshot 正向（含 E-b 负向）

对应 REQ-F04/AC-F03-01；B 单测+J 透传；前置=完整 G+render 浏览器+存活 session（frameCount≥25）；输入 at=[0,12,24]；断言三帧句柄、frameIndex 精确、**零新 Build**（build 计数不变）；负向 at 越界/并存/每页>50→400。防假阳性：不建 Build 断言（走 build.submit 的错误实现在此红）。

#### TC-F03-02：snapshot session 失效

对应 AC-F03-02/C03/E-a；过期/跨工程/不存在 session→409 `hypit_session_expired`；零渲染进程。防假阳性：跨工程 session 必拒（归属校验）。

#### TC-F03-03：capture 未配置与配置后（含 E-d）

对应 AC-F03-03；未配置→三 kind 409 `hypit_capture_not_configured`；配置后 local-web fixture（`platform-hypit/fixtures/local-web`）截图像素>0；url-policy 拒绝域（重定向至禁域 fixture）→400 `hypit_url_denied` 且零出网字节。防假阳性：像素与出网双向断言。

#### TC-F03-04：capture.run 边界（含日志脱敏）

对应 AC-F03-04/RULE-F06；四负向（工程外 scriptPath、fetch 调用、输出越根、timeout>120000）全 400 且无浏览器进程；错误日志无脚本正文（断言日志内容）。防假阳性：进程计数断言。

#### TC-F03-05：packages.build/pack 白名单回归

对应 AC-F03-05/C03/E-f；202+回执字段与基线一致；既有 `custom-packages.test.ts` 族零回退（VFX-1）。防假阳性：与 06 卡改动叠加后复跑。

#### TC-F04-01：agent-jobs 全环（桩 LLM）

| 项目 | 必填内容 |
|---|---|
| 对应条款 | REQ-F05 / AC-F04-01 / D-04 / C04/E-a |
| 风险/类别 | 高；正向+状态迁移 |
| 测试层级 | J IT（真 PG；执行环桩） |
| 实现位置 | `HypitAgentJobIT`（新） |
| 前置数据 | ready 工程+两素材；FrozenText 执行环以测试桩替换（WireMock/内联 bean——沿仓库 IT 桩手法），桩计划=`[media.probe(素材A), speech.measure(text), finish]` |
| 输入 | POST agent-jobs `{requestId,intent:"author",brief:"…",assetIds:[A],baseRevision:2}` |
| 依赖模拟 | 仅 LLM 执行环（被测计划生成）；工具执行走真实 dispatcher 面（B 不在 IT 内——action 落行断言以 sidecar 桩回执为准，注明层） |
| 操作步骤 | 1) POST→202；2) 驱动 worker（直调 runOnce 或等待调度）；3) 断言事件 planning→executing→succeeded；4) hypit_job_action 三行（kind='tool'）+ai_run 绑定；5) 负向：intent 非法/brief 空→400 零 command |
| 预期副作用 | command+job+action 行；checkpoint phase 演进 |
| 最终状态 | job succeeded |
| 清理 | IT 数据自清 |
| 执行与证据 | VFX-2 |
| 防止假阳性 | 空计划直通的实现（现状）在此必红——桩计划非空且步骤断言逐步 |

#### TC-F04-02：planner 坏输出两次失败

对应 AC-F04-01 附带/D-04/C04/E-b；桩两次返回非法 JSON→job failed+`planner_failed`+两次原文在 result_json；不执行任何 action。防假阳性：留证断言（原文可检索）。

#### TC-F04-03：scope 越权留痕

对应 AC-F04-02/RULE-F03/C04/E-c；scope 声明含越权工具→投递被剔除+`scope_narrowed` 事件；计划含越权 action→该行 failed `hypit_scope_denied`、其余步骤照常。防假阳性：剔除事件断言（静默剔除实现必红）。

#### TC-F04-04：resume 续跑与不扩张（含连点）

对应 AC-F04-03/RULE-F04/C04/E-d、E-d2；构造 waiting_input（stepIndex 达限或 action 明示）；同 requestId 连点 resume→一回执；input 并入后 allowedTools 重算==原集（子集断言）；携带扩张项→对应 action 拒。防假阳性：集相等断言。

#### TC-F04-05：cancel 幂等/终态/越权（含 E-j）

对应 AC-F04-04/C04/E-e、E-j；cancel×2→200 同态零新副作用（事件计数）；succeeded 上 cancel→409；failed 上 resume→409；canceled 上 cancel→200 幂等；非本人非 operator→403；owner 变更后旧 job 操作→403。防假阳性：事件计数断言。

#### TC-F04-06：runtime doctor（含 403）

对应 AC-F04-05/C04/E-g；operator 得四段聚合（engine/programs/render/activity——程序 down 如实 ready:false）；非 operator 403 `hypit_operator_required`。防假阳性：down 状态不粉饰断言。

#### TC-F04-07：down 三态

对应 AC-F04-06/RULE-F05/C04/E-f；活跃 build×{无 hash→409+buildIds+当前 hash；错 hash→409；匹配 hash→程序 down}；无活跃 build 直接 down 幂等。防假阳性：409 响应含 buildIds 与 activityHash 双字段断言。

#### TC-F04-08：worker 崩溃恢复

对应 AC-F04-01 恢复语义/C04/E-h；执行中杀 worker（IT 内停调度+租约过期模拟）→重领续跑；已确认动作不重发（action 行数不增）；unknown remote 不重发。防假阳性：行数不增断言。

#### TC-F04-09：runtime paths 逻辑键

对应 AC-F04-07/C04/E-i；operator 得逻辑键集（七键+hostPathsRevealed:false）；响应与日志无宿主绝对路径（断言不含 `/Users/`、`/home/`、盘符）。防假阳性：字符串断言。

#### TC-F05-01：surface 词法对拍

对应 REQ-F07/AC-F05-01/C05/E-a；B 单测：`?surface=@hypit/seedance` 输出与上游 `listSurfaces` 同输入逐字段对拍（import 上游函数直调对拍）；未知包 400。防假阳性：对拍而非快照造数据。

#### TC-F05-02：visual 与无参兼容（含 E-b/E-c）

对应 AC-F05-02；`?visual=text-typography` 与上游 `visualSchema` 同构；`?visual` 列 14 形状；无参响应与基线快照逐字节等价。防假阳性：快照等价断言。

#### TC-F06-01：编译等价回归

对应 REQ-F08/AC-F06-01/C06/E-b；既有 custom-packages 族全绿（回执/错误输出与基线 diff 等价）。防假阳性：与基线输出 diff 断言。

#### TC-F06-02：broker 零作者代码 tsc

对应 AC-F06-02；测试以子进程 spy/计数（`node:child_process` spawn 监听）断言 runBuild 期间 broker 进程零 tsc 子进程。防假阳性：计数=0 断言（回落实现必红）。

#### TC-F06-03：runner 不可达显式失败

对应 AC-F06-03/C06/E-a；停 runner 槽→runBuild→`runner_unavailable`（非静默回落、非超时悬挂）。防假阳性：错误码断言。

#### TC-F07-01：门禁矩阵与文档零漂移

对应 REQ-F09/AC-F07-01；VFX-1～6 全绿（VFX-5 按可用性如实）；`quality:lifecycle` 49+31 与 `docs:status` 绿、`docs:links` 漂移数不增；§12.1 表无悬空编号（逐行核对）。防假阳性：漂移计数前后对比记录。

### 12.2 测试用例执行规格与证据分层

| 验证层 | 可替换的依赖 | 必须真实执行的对象 | 不能据此声称 |
|---|---|---|---|
| 单元/组件（B tools 单测） | 外部程序 health（可注入态） | 被测纯函数/门禁断言 | J 链路或权限已通 |
| 服务/契约集成（B dispatcher 测试、J IT） | LLM 执行环（桩）；whisperx 可用性（down 场景注入） | dispatcher 全链、Controller/Service+真 PG、WireMock 仅替 sidecar 且字段同构 | 浏览器入口或真实 B 容器已通 |
| 契约静态（hypit-tools.contract） | 无 | 三侧源文件解析 | 运行期行为 |
| 隔离端到端（VFX-5） | 真实 provider（禁）；账号=seed | 真实 UI/API/edge/J/B 全链 | 商业模型效果/生产可用 |

- mock 边界：J IT 的 WireMock 只替「sidecar HTTP 面」且桩 JSON 字段与 B receipt 同构（TC-F01 防漂移条款）；被测编排/鉴权/幂等不得 mock。
- 缺陷修复反例：TC-F01-01（去掉路由回基线必红）、TC-F02-02（假 ready 必红）、TC-F04-01（空计划直通必红）、TC-F06-02（回落必红）——先红后绿证据入各卡 gates。
- 截图：无 UI 改动，N/A（§8）。
- 持久化结果补刷新/重读：TC-F01-02 导入后 workspace.check 追加断言；异步终态：全部 job 类 TC 断言持久终态而非 202。

### 12.3 本任务验证清单（VFX；作者选定，执行者不得删减）

| 验证编号 | 适用卡/阶段 | 工作目录与 shell | 精确命令或手工步骤 | 前置环境/副作用 | 必需性 | 通过标准 | 证据路径 |
|---|---|---|---|---|---|---|---|
| VFX-1 | 各 B 卡/集成 | `platform-hypit/backend`（zsh，Node PATH 前置） | `npm run typecheck && npm test` | 引擎 G 物化（03/06 完整+依赖）；Docker 不需要 | 必需 | exit 0；用例数≥基线（179 过/2 skip）+新增全过；无未解释新 skip | `test-artifacts/task-107/fix1/<卡>/gates.txt` |
| VFX-2 | 各 J 卡/集成 | `platform-java`（bash+`ensure_java_runtime 25`） | `./gradlew :services:intelligence-service:test --tests '*Hypit*' --rerun-tasks --no-build-cache --max-workers=2 --console=plain` | Docker/Testcontainers（真 PG） | 必需 | exit 0；基线 84 类面+新增类全过 | 同上 |
| VFX-3 | 02/03/集成 | 仓库根（Node PATH） | `npx vitest run tests/deployment/hypit-compose.contract.test.ts tests/deployment/hypit-entrypoint.contract.test.ts tests/deployment/hypit-full-coverage.contract.test.ts tests/deployment/hypit-tools.contract.test.ts` | `npm ci` 已装 | 必需 | exit 0（基线 19+新增） | 同上 |
| VFX-4 | 07 | 仓库根 | `npm run quality:lifecycle && npm run docs:status && npm run docs:links` | — | 必需 | 前两者 exit 0；docs:links 漂移数≤基线（~20，记录前后值） | `…/C107F-07/gates.txt` |
| VFX-5 | 01/集成 | 仓库根 | 隔离栈起（compose.test.yml 配方）后 `npm run e2e -- tests/e2e/hypit-clone.spec.ts --project=chromium`；栈毕销毁 | Docker+浏览器+栈资源 | 经批准可选 | 可跑则 exit 0 含 roundtrip 用例；不可跑→NOT_RUN+精确原因（不算 PASS） | 同上 |
| VFX-6 | 07/集成 | 仓库根 | `npm run security:secrets > /tmp/secrets-fix1.out 2>&1; echo EXIT=$?`（直连重定向） | — | 必需 | EXIT=0 | 同上 |

- 卡级先跑最相关用例（如 C01 先 `npm test -- tests/workspace/project-transfer.test.ts`）；卡验收过后再 VFX 全量影响回归。
- 已知基线失败：无（V04/V05/契约/lifecycle 基线全绿，§2.7）；V10 DH 共享容器 flaky 家族不在本书门禁（不跑全量 Java test）。
- Gradle 重跑语义：`--rerun-tasks --no-build-cache` 确保 VFX-2 实跑；结果记录不得写 UP-TO-FDATE/FROM-CACHE 为「本轮执行」。

### 12.4 当前仓库命令目录（裁剪说明）

模板 §12.4 全目录不复制；本书选用：VFX-1（=V04 域）、VFX-2（=V05 域）、VFX-3（契约层）、VFX-4（lifecycle+docs）、VFX-5（e2e 单 spec）、VFX-6（secrets）。未选：`npm run lint`/`build`（无前端改动）、`gradlew check` 全量（非本书门禁，避免引入已知 DH flaky 噪声）、design lint（无 UI）。Java 运行时准备：

```bash
cd platform-java && source ../scripts/lib/java-runtime.sh && ensure_java_runtime 25 || exit 1
java -version && ./gradlew --version
```

### 12.5 最终集成验收与完成定义

- 集成验收负责人/责任卡：执行者（C107F-07）。
- 前置条件：C01～C06 全 VERIFIED、gates 齐备、无 BLOCKED。
- 集成清单：VFX-1～6（VFX-5 如实分层）。
- 串联流程：ready 工程 → export → imports 新工程 → 新工程 BUILD_CHECK（既有端点冒烟）→ TOOLS_INVOKE speech.measure → agent-jobs（桩计划全环）→ runtime doctor → vocabulary?surface → 全程 job 事件流与动作行核对（S1/S2/S4 场景贯穿）。
- 保留行为：8 media 工具、69 条既有路由回执、76 旗标默认值、V04/V05/V06 基线用例、无参 vocabulary 响应——零回退。
- 证据：各卡 gates.txt+§14 对话验收记录（命令/退出码/用例数/脱敏摘要）。
- 变更范围：diff 与 §9.1 全集核对；未覆盖任务前改动（基线干净，应只有本书改动）。
- 交付状态：全部必需验收通过才是完成；VFX-5 NOT_RUN 时标注「已实现，e2e 层未验收」不写全绿。
- 端到端追踪：§12.1 无悬空编号；§3.1 每链路段有真实调用方与消费结果。
- 复核与返工：按 §10.4 反例复验；发现偏差退责任卡，修复后按影响面重验。

### 12.6 发布与回滚

N/A：无部署变化（唯一配置键默认空=不启用）；本地验收通过不授权生产发布。`HYPIT_CAPTURE_BROWSER_CACHE` 启用属部署侧决策，按 `deploy/hypit/README.md` 既有流程另行操作。

### 12.7 边界目录与适用性（E01～E22，全任务一次）

| 编号 | 检查场景 | 负责卡/TC 或 N/A 原因 |
|---|---|---|
| E01 | 空输入 | C02（brief/title/score 空串 400）TC-F02-01/04 |
| E02 | 超长输入 | C02（measure ≤20000）/C04（brief ≤8000）附带断言 |
| E03 | 重复提交/连点 | C04/E-d TC-F04-04；requestId 幂等全局兜底 TC-F01-03 |
| E04 | 断网/连接中断 | C03（capture 网络失败留诊断）；e2e 断言属既有面，本书不新增 N/A 说明：工具 job 失败语义沿既有 AcceptedJob 轮询 |
| E05 | 服务端/第三方错误 | C01（sidecar 错误透传 502 域）；TC-F01-01 负向 |
| E06 | 未登录/会话过期 | edge 既有 401；本书端点鉴权沿 `callers.resolve` 既有——无新会话语义，N/A 补充说明 |
| E07 | 无权限/非法状态迁移 | TC-F02-04（404/403）、TC-F04-05（403/409） |
| E08 | 成功但无数据 | C04 GET agent-jobs 空列表 `{items:[],nextCursor:null}`（TC-F04-01 附带）；C05 `?visual` 空清单 |
| E09 | 数据过期/版本冲突 | C01/E-b（head 快照）；C04 baseRevision 快照 |
| E10 | 刷新/深链/离开恢复 | job SSE Last-Event-ID 既有；waiting_input 刷新后可 resume（§4.4）——沿既有 events 面，无新增前端 |
| E11 | 快速切换账号/工程 | TC-F04-05（越权 403）；工具跨工程素材 404（TC-F02-04） |
| E12 | 失活时在途请求/计时器 | C03 capture 超时上限（TC-F03-04）；B 进程语义无前端计时器，N/A 其余 |
| E13 | 缺字段/null/空白/0/false 分别处理 | §5.1 空值列+RULE-F01 顺序；TC-F02-04 |
| E14 | 数值上下界与相邻越界 | C03 at∈[0,frameCount)、每页≤50、超时 1..120000（TC-F03-01/04） |
| E15 | 并发/乱序/旧 finally | C04 cancel×step 竞态（TC-F04-05 canceled 优先）；事件 sequence 既有 |
| E16 | 超时/取消但服务端已提交 | requestId 幂等重试（TC-F01-03）；cancel 后 worker 边界停（TC-F04-05） |
| E17 | 跨账号/组织访问与缓存隔离 | 无缓存面；403/404 即隔离（TC-F02-04/TC-F04-05） |
| E18 | 在途撤权/资源删除 | C04/E-j（owner 变更 403）；工程删除后 job 操作 404（附带断言 TC-F04-05） |
| E19 | 旧数据/旧客户端兼容 | checkpoint 新键缺省兼容（§2.9）；无参 vocabulary 快照（TC-F05-02） |
| E20 | 部分成功/补偿/重放/重启 | TC-F04-08（worker 重放）；TC-F01-03（command 重放） |
| E21 | 日期/时区/金额精度 | N/A：无金额/时区面（CreatedAt ISO-8601 既有） |
| E22 | 大列表/分页/设备边界 | agent-jobs limit≤100 游标（TC-F04-01 附带）；导入 4GiB 门禁既有（TC-F01-04） |

---

## 13. 阻塞、技术修订与恢复

### 13.1 必须阻塞的情况（发现=经核对确认当前卡实际实现会触发；仅阅读/搜索/测试不构成阻塞）

1. 搜索后仍无法定位本书锚点符号，或迁移后实现改变约定职责——仅行号漂移不阻塞；必要写入路径变更按 §13.3。
2. 当前卡需改变未声明的签名、权限、数据、路由或行为，或前置卡交付物与约定不符（含发现 107-1 §6.2 契约行与本书 §6 冲突——以 107-1 原文为准并 BLOCKED）。
3. 需要新增接口字段/错误码但 §6 未定义。
4. 必须决定未定义的产品/安全/资金/幂等/隐私/兼容行为——含 planner 执行环入口确不可用于结构化生成（RISK-F01 兜底分支触发时 BLOCKED 附替代方案，不得改前端拼计划）。
5. 需写白名单外/只读/禁改文件，或引入新依赖（本书依赖=无）。
6. 测试失败且卡内调查无法定因，或修复越卡边界；卡内普通编码错误自行修。
7. 本书与指令/设计规范冲突。
8. 必需验证缺环境/凭据/浏览器/服务且本地不可重建（先排查记录；本地浏览器/DB/容器/安装属默认环境不需审批）。
9. 目标文件他人改动无法安全合并，或出现未知生产操作/计费/删除需求。

仅受影响卡停止；无依赖且无共写冲突的已授权卡可继续，不私领新任务。

### 13.2 阻塞报告（对话中按此格式，不建文件）

```text
BLOCKED
任务书版本 / 卡号 / 阻塞编号：<v1.1.0 / C107F-xx / B-xx>
类型与影响：<需求/契约/范围/安全/基线/环境/权限；阻塞哪些步骤与下游卡>
原因：<具体>
已确认内容：<源码路径+符号、命令/输出、已完成内容；脱敏>
缺少的信息：<最小决策/交付物/授权>
已经尝试：<批准范围内的检查与结果>
建议决策：<一个推荐方案及影响>
已保留状态：<改动/测试结果/未动文件/产物位置>
```

### 13.3 修订与恢复

- 规划者提供决策并更新本书受影响章节（版本号+附 B.3 记录）；用户会话中明确补充的决定有效。
- 执行者核对新版本/基线/前置交付物后恢复；旧证据保留但不得当新版本验收。
- 独立发现的无关缺陷列 §14「未解决问题」（§1.5 清单含），不扩卡修。
- 环境恢复且行为/范围不变时可记录证据后继续；已变代码重跑相关验证。

---

## 14. 执行结果、证据与续接

- 每卡完成后对话记录：卡号、状态、实现结果、验收命令与工作目录、退出码/用例数、关键输出、证据引用、偏差与未完成项。
- 全部卡+集成验收后按模板结构汇总：**实现结果**（版本/卡号/状态/功能摘要/测试构建截图各 PASS·FAIL·PARTIAL·NOT_RUN·SKIPPED·N/A/已满足与未满足需求）；**文件与范围**（逐路径表：操作/核心变更/对应卡与需求/原有改动保留）；**验证证据**（V/TC/AC 逐行：命令/结果/退出码/关键输出/证据引用）；**偏差说明**（无或条款+批准记录；基线失败不伪装 PASS）；**未解决问题**（§1.5 项+后续交接）。
- 默认不新建任何报告文件；测试工具产物按 §9.1 目录保留。

### 14.1 续作检查点（中断/换会话前输出，对话内可复制文本）

```text
任务书路径 / 版本：docs/任务书/草场任务书-107-fix-1-Hypit功能复刻缺口收口.md / v1.1.0
本次授权卡范围 / 执行模式：<C107F-xx…或全部> / AUTO_CHAIN
当前代码基线 / 本任务相关未提交改动：<SHA> / <文件摘要>
已 VERIFIED 卡及可核对证据：<卡号+gates 路径+关键退出码>
正在执行的卡 / 已完成步骤 / 尚未完成步骤：<…>
必需验证的 PASS、FAIL、PARTIAL、NOT_RUN、SKIPPED 分别有哪些：<…>
最新有效决策 / 规格修订 / 尚待同步项：<D-xx 或 §13 修订记录>
已有改动保留边界 / 本次新增文件：<…>
环境与产物 / 哪些证据只存在本机 / 重建命令：<引擎物化命令/VFX 序号>
阻塞及受影响卡 / 解除条件：<B-xx 或无>
下一张可执行卡 / 下一条具体动作：<…>
```

---

## 附 A：返工卡规则

按模板附 A 执行：强模型 review 后产出 `R-xx` 返工卡（原版本/基线、问题位置+符号、对应条款、最小复现、期望与实际、已确认根因、唯一修复方案、写入清单（须进 §9.1）、修复步骤、回归要求（缺陷复现测试+旧行为+受影响后置卡）、验收与 §14 汇报）。根因未确认先出有边界诊断卡；返工不是无限「修到全绿」授权。本书当前无返工卡。

## 附 B：作者发布检查与版本记录

### B.2 发布前检查（v1.1.0 自查结论）

- §1 独立可解释（场景 S1-S4+主流程+成功标准）；未决=无（§1.9）。
- §3.1 十二链路段全接通（入口→消费+注册位置）；新模块调用方明确（AgentJobService→两 Controller；kind 谓词→dispatcher；契约测试→CI 清单）。
- 状态区分：业务（hypit_job.state+phase）/受理（202）分离（§4.3）；cancel/resume/终态分立（§4.4）。
- 唯一维护位置：字段 §5.1/§6、规则 §5.2-5.4、命令 §12.3、契约 §6；卡引用编号不复制。
- 阶段 M1→M3 出口明确、依赖无循环（§10.3）、诊断性反例前置（§10.4 RISK）；最终联调有责任卡（C07）。
- 高风险反例：TC-F01-01（回基线必红）/TC-F02-02/TC-F04-01/TC-F06-02 四处先红后绿；mock 边界 §12.2（WireMock 仅替 sidecar 且字段同构）。
- 白名单/黑名单齐（§9.1 二十八写入行+四禁改行）；卡写入⊆全局（各卡改动文件表）；共写冲突已排（§10.2）。
- E01-E22 全适用行映射 TC（§12.7）；N/A 均有原因。
- 命令/目录/退出码/副作用核实（§12.3/§9.3）；基线如实（§2.7，CI run 在跑不掩写为绿）。
- 无占位符/TBD；BLOCKED 条件明确（§13.1 九条）；普通编码错误不触发阻塞。
- 编号链 REQ→RULE→D→API→C→AC→TC→VFX→证据无悬空（§12.1）。
- 集成负责人（C07）/最终门禁（VFX 矩阵）/上线边界（§12.6 N/A）明确；新依赖=无；迁移=无；计费=无。

### B.3 版本记录

| 修订版本 | 日期 | 变化原因与决策人 | 受影响条款/卡 | 是否重做发布检查 |
|---|---|---|---|---|
| 1.0.0 | 2026-09-26 | 初版出书（七卡；随 04770a24 入库）；ZCode 规划 | 全书 | 是（当轮自查） |
| 1.1.0 | 2026-09-26 | 用户指示严格对照 `docs/任务书/任务书模板.md` 重写：§0 协议内联、§6 逐接口完整契约（请求/参数/成功/错误/不变量/签名块）、§11 卡补全结构（开始前检查/函数级要求/边界表/禁止）、§12.1 二十九用例逐条规格、§12.2 证据分层、§12.4-12.6 补齐、§13/§14 全文、附 A/B 落档；决策与锚点零变化（D-01～D-08 原样）；ZCode | 结构性重写，需求/卡数/门禁不变 | 是（B.2 重查通过） |

## 附 C：审计证据索引（规划输入，只读；2026-09-26 核验）

1. 断链实锤：`grep -rn "project-package" platform-hypit/backend/src/commands/dispatcher.ts` 零命中；J 派发点 `HypitProjectPackageService.java:54/80`（字面量 `"project-package.export"/"project-package.import"`）；WireMock 掩盖点 `HypitTemplateIT.java:101/121`（`containing("project-package.import")` 桩应答任意 kind）。
2. 七桩清单：`HypitJobController.submitAction`、`HypitProjectController.agentJobs/createAgentJob/projectJobActions/projectJobActionSubmit`、`HypitRuntimeController.action/paths`（`pending("…")` 恒 error；neverMap 辅助行另有三处随实装消亡）。
3. worker 无投递者：生产代码无 `hypit.agent` INSERT（仅 `HypitAgentWorker.JOB_KIND` 常量、claim SQL 与测试直插）；`driveToCompletion` 空 actions→succeeded。
4. speech/image 已路由：dispatcher default 分支 `isSpeechTool/isImageTool`（`tools/speech.ts:25 speechTools`、`tools/image.ts:18 imageTools`）。
5. runner compile 已实现：`runner/protocol.ts RUNNER_COMMAND_KINDS` 含 `compile`；`runner/server.ts:28 NOT_IMPLEMENTED_KINDS={executeLocal,capture,cancel}` 不含 compile。
6. vocabulary 上游能力面：`upstream/packages/video-cli/src/vocabulary.ts` 导出 `listPackages/listSurfaces/describeSchema/visualSchema`（SurfaceListing/14 形状）。
7. 工具契约漂移：`contracts/hypit-tools.v1.json` 18 id（`transcribe/measure` vs B `speech.transcribe/speech.measure`；`image.*` 未列）；全仓唯一引用 `scripts/local/task107-split-v3.py`（无测试消费）。
8. Edge 旗标齐备：`docker-compose.yml` L323-398 的 76 `EDGE_ROUTE_HYPIT_*` 含七条目标路由旗标（AGENT_JOBS_*/JOBS_ACTION*/RUNTIME_ACTION/RUNTIME_PATHS）。
