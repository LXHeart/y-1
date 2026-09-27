# 开发任务书：Hypit 追平、环境验收、AI 视觉与测试质量收口

> 模板版本：3.0.0 ｜ 任务编号：107-4 ｜ 任务书版本：1.0.0 ｜ 创建/更新日期：2026-09-27
> 规划负责人：Codex ｜ 目标仓库：`/Users/LXH/claude/y-1` ｜ 当前分支：`main`（只记录，不自动切换）
> 代码基线：`bee6be6826d057f0742eeceef8d24ebfae7a8049`；已有未提交改动：`scripts/acceptance/build-107-engine.sh`、`vite.config.ts`、本任务书索引及旧 107-4 子任务书；执行者 MUST 保留并安全增量修改
> 本次事实核验日期：2026-09-27 ｜ 上游目标：`hypit-ai/hypit` `main`，远程核验值 `557497b32a6658067c11bf924c7511e61c61df4e`
> 规格状态：IMPLEMENTED ｜ 实施状态：IMPLEMENTED（2026-09-27 分层收口：源码追平/契约/LOCAL 完整环境/视觉/warning 质量全落地；LIVE 层 EXTERNAL_BLOCKED〔无凭据，双闸如实非零〕；验证栈 intelligence 依赖链 ENVIRONMENT_BLOCKED 单列；不默认生产发布。逐卡证据 test-artifacts/task-107/1074/，full 取证 run 11 ALL-GREEN——见各卡 gates 与 docs/草场开发进度与续接指南.md 107-4 节）
> 目标执行者：能力较弱的编码模型 ｜ 任务卡总数：32 ｜ 起始卡：C1074-01
> 执行顺序：C01～C08 → C09～C16 → C17～C24 → C25～C32；阶段内 AUTO_CHAIN，跨阶段按阶段出口解锁；共享契约按 C01→C08 单向写入，其余阶段只消费冻结契约
> 执行模式：AUTO_CHAIN ｜ 交付终点：本地集成验收完成；不默认包含生产发布、真实付费调用或对外上线
> 决策依据：用户明确要求把 107-4 四项工作合成一份任务书并完成后续验收规划；既有 107-1～107-3、107-fix-1 仅作事实线索；仓库 `AGENTS.md`、根 `DESIGN.md`、既有脚本和当前源码是唯一事实来源

## 0. 执行协议

### 0.1 适用硬约束

执行者必须先读根 `AGENTS.md`、本书 §0/§1/§2/§8/§9/§10/§11/§12/§13/§14，再读当前卡和精确引用。UI 卡必须读根 `DESIGN.md`；治理台本任务为 N/A，不得借治理规范覆盖 AI 端规范。不得 reset、clean、批量覆盖或删除未登记改动。不要修改 `docs/任务书/任务书模板.md`。

本书把“来源追平”“环境真实验证”“视觉截图”“warning 清理”放在同一交付合同内，但保持四个阶段的边界和状态分离。源码存在、契约通过、完整栈通过、真实 Provider 通过、用户视觉闭环通过分别记录，不能合并成一句“全部完成”。

### 0.2 状态与 DoD

卡状态为 `NOT_STARTED → IN_PROGRESS → IMPLEMENTED → VERIFIED`，缺环境或缺授权为 `BLOCKED`，不以 `SKIPPED/NOT_RUN/PARTIAL` 解锁后卡。卡级 VERIFIED 需要该卡的代码/配置/文档改动、命令退出码、Given/When/Then 用例和证据全部满足；整任务 VERIFIED 还需要 C1074-32 集成责任卡通过。

### 0.3 证据纪律

每条验证记录 cwd、命令、开始/结束时间、工具版本、退出码、通过/失败/跳过数、缓存/容器/浏览器状态和产物路径。截图、日志、JUnit/JSON、恢复报告可写入 `test-artifacts/task-107/1074/`；不新建叙述性完成报告或交付报告。凭据、Cookie、签名 URL、Authorization、真实用户数据和完整内部路径不得进入源码、截图、日志或任务书。

## 1. 需求、场景与范围

### 1.1 目标

让 y-1 的 Hypit 集成从任务书锁定的旧上游版本可审计地追平 GitHub 当前 `main`，在真实 Docker/浏览器/本地程序/隔离数据库条件下完成可恢复的执行验收，补齐 AI 创作端双主题和移动端视觉证据，并清理会掩盖测试质量的 Pinia/Vue warning 与报告噪声。

### 1.2 需求追踪

| 编号 | 用户/调用方与场景 | 必须交付的可观察结果 | 优先级 | 负责卡 | 验收 |
|---|---|---|---|---|---|
| REQ-1074-01 | 维护者同步 Hypit main | 远程 commit、祖先关系、变更清单和版本工具链可复现 | 必须 | C01 | AC-01 |
| REQ-1074-02 | 维护者重建 vendor | `upstream-manifest` 与 Git archive path/hash/mode/symlink 一致 | 必须 | C02 | AC-02 |
| REQ-1074-03 | Backend 消费新增上游 API | core/Hyperframes/Provider/runtime/studio/video-cli 适配真实新 API，不假兼容 | 必须 | C03 | AC-03 |
| REQ-1074-04 | 构建和回滚 | G 可重建、patch 可重放、digest 稳定、旧版本可回滚 | 必须 | C04 | AC-04 |
| REQ-1074-05 | 运维启动完整栈 | Docker、Edge、Java、broker、runner、Postgres/对象存储健康 | 必须 | C09 | AC-09 |
| REQ-1074-06 | 创作者执行本地链 | prepare/check/plan/build/render、媒体工具、snapshot/capture 可恢复 | 必须 | C10 | AC-10 |
| REQ-1074-07 | 安全隔离与恢复 | 恶意作者包不能越界；backup/restore 后工程和 hash 一致 | 必须 | C11-C12 | AC-11/12 |
| REQ-1074-08 | Provider live | 仅授权 Provider 逐项记录 LIVE_PASS/LIVE_FAIL/EXTERNAL_BLOCKED 和预算 | 必须 | C13-C16 | AC-13~16 |
| REQ-1074-09 | AI 端导航与工作区 | `/video-clone`、工程深链、旧 `/hypit` alias 和工作区面板真实可达 | 必须 | C17-C20 | AC-17~20 |
| REQ-1074-10 | AI 端视觉与可访问性 | light/dark、桌面/平板/移动、键盘、loading/empty/error/conflict 截图通过 | 必须 | C21-C24 | AC-21~24 |
| REQ-1074-11 | 测试警告 | Pinia/Vue lifecycle/attrs warning 按类别定位并清零或记录明确例外 | 必须 | C25-C28 | AC-25~28 |
| REQ-1074-12 | 测试输出质量 | 测试隔离、负向报告、Vitest/JUnit 输出可读且 fail-closed | 必须 | C29-C32 | AC-29~32 |

### 1.3 用户场景与闭环

| 场景 | 用户与动机 | 前置条件 | 主流程 | 最终结果 | 中断恢复 |
|---|---|---|---|---|---|
| S1 来源追平 | 维护者希望使用当前 Hypit main 的能力 | 可访问 GitHub、固定 Node/pnpm | 读取 commit → archive 导入 → manifest 校验 → 适配 → G 重建 → check/render | 新 digest、manifest、patch、lockfile 可审计 | 失败保留临时副本，旧版本可回滚 |
| S2 完整执行 | 运维/创作者验证真实本地链 | Docker、浏览器缓存、uv、隔离 DB | compose up → readiness → prepare → check/plan/build/render → backup/restore | 真实 MP4、恢复报告、隔离证据 | 服务 down/租约过期/会话过期按原错误恢复，不重发付费请求 |
| S3 AI 工作区 | 创作者从 AI 中心继续 Hypit 项目 | AI 入口、合成工程 fixture | 打开入口 → 选工程 → 编辑 Source/Reference/Plan → Preview/Studio → Review/Results/Variants | 页面状态与工程/Job/Result 一致，可刷新恢复 | error/conflict/waiting_input 提供重试、恢复或重新打开入口 |
| S4 真实 Provider | 经授权的 operator 核验外部能力 | provider 凭据、预算、白名单 | catalog → 最小 probe → 记录状态/成本/样片 → 超预算停止 | 每 Provider 独立 live 记录 | 凭据不足标 EXTERNAL_BLOCKED，禁止假 ready |

核心对象：`upstream manifest` 是来源完整性清单；`G` 是补丁后的生成发行版；`Project/Revision/Build/Job/Output` 是持久工程对象；`PreviewSession/StudioSession` 是临时会话；`LIVE_PASS` 仅代表本次授权 live 探测成功，不代表生产开放。

### 1.4 范围外与不许顺手修

- 不发布生产、不切换真实生产凭据、不修改真实用户数据、不自动消费余额。
- 不借本书重写认证、钱包、计费、旧视频管线、数字人业务或治理台。
- 不升级根项目无关依赖；不删除既有迁移；不把上游 main 的新能力未经契约审查暴露给 Edge。
- 不通过删测试、放宽断言、全局关闭 console、过滤 stderr 或改成 mock 伪造完整环境通过。
- `docs:links` 现有 Hypit knowledge/deploy unindexed 项只记录，不在本书顺手清偿。

### 1.5 已知限制与未决问题

| 编号 | 事实/决定 | 影响 | 处理 |
|---|---|---|---|
| Q-01 | 当前工作区历史 manifest 记录 `2c320059...`，远程 main 核验为 `557497b...`；用户要求以最新本地代码为准，但执行前仍须实读本地 commit | C01-C04 | 以 `git rev-parse`、manifest 和远程 compare 三方一致为准；不凭旧任务书判断已追平 |
| Q-02 | 真实 Provider 名单、额度、模型和服务条款未在本书给出 | C13-C16 | 执行前通过受控环境提供；缺失时只完成 contract/local，live 标 EXTERNAL_BLOCKED |
| Q-03 | 浏览器自动化认证/连接能力可能不可用 | C21-C24 | 先排查本地 Playwright/Chrome；仍不可用则截图项 PENDING_SCREENSHOT，不能标 VERIFIED |
| Q-04 | 是否要求把 GitHub main 新功能全部公开到现有 API | C03 | 先兼容源码和本地能力；新增公开路由需另立契约，未决时阻塞，不临时扩大 Edge 面 |

## 2. 仓库上下文与事实核验

### 2.1 入口和调用链

- 三入口：`index.html` 用户端、`ops.html` 治理台、`ai.html` AI 创作端；本任务只改 AI 端验收，不改用户端/治理台业务。
- AI 路由：`src/ai/router.ts` 的 `/video-clone`、`/video-clone/:projectId` 和 `/hypit/:pathMatch(.*)*` alias；导航：`src/ai/components/AiWorkspaceNavigation.vue`；入口卡：`src/views/ai-center/components/VideoCloneEntry.vue`。
- 主工作区：`src/views/video-clone/VideoCloneWorkbench.vue`；子组件位于同级 `components/`；取数与生命周期位于同级 `composables/`。
- Hypit API：`src/views/video-clone/composables/hypit-api.ts` 统一 `/api/hypit` 信封、错误码和 AbortSignal；SSE 由 `useHypitJobs.ts` 消费。
- Node broker：`platform-hypit/backend/src/commands/dispatcher.ts`、`src/engine/**`、`src/tools/**`、`src/runner/**`；Java 接线位于 `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/**`；Edge 旗标/路由在既有 `platform-java/services/edge-bff/**` 与部署配置。
- 部署入口：`scripts/acceptance/verify-107-upstream.sh`、`build-107-engine.sh`、`verify-107-full.sh`、`verify-107-live.sh`、`ci-e2e-107.sh`。

### 2.2 上游事实

锁定版本为 0.2.13、1768 个受管文件；GitHub 当前 main 与锁定 commit 比较为 6 个提交、82 个文件变更、19 个新增路径，重点涉及 `packages/core`、`packages/hyperframes`、`packages/provider-hyperframes-local`，以及 build-result/compiler/runtime/studio/video-cli 等已有文件。该事实仅作为 C01 调查起点，执行时必须重新核验。

### 2.3 当前测试基线

历史只读核验包括：upstream manifest 校验通过；G patch replay 通过；backend 198 pass/2 skip；前端 typecheck、lint、Vitest 通过；Studio 9/9 通过；full coverage contract 5/5 通过。`verify-107-full.sh` 阶段 0 通过但阶段 1～3 `NOT_RUN`；`verify-107-live.sh` 缺显式启用和预算时退出 2。以上不能替代本任务执行期结果。

### 2.4 复用检查

| 路径/符号 | 操作 | 原因 | 禁止事项 | 完成标准 |
|---|---|---|---|---|
| `platform-hypit/upstream-manifest.json` | 重生成 | 单一来源清单 | 不手改 hash | archive 校验全绿 |
| `scripts/acceptance/build-107-engine.sh` | 兼容迁移/版本门禁 | G 唯一构建入口 | 不绕过 frozen lockfile | 两次 digest 一致 |
| `backend/src/engine/**` | 适配新上游 API | broker 只消费 EnginePort | 不在 broker spawn 作者 tsc | backend compile/render 通过 |
| `src/ai/router.ts` | 只核对入口兼容 | 旧 alias 必须保留 | 不扩 CreationCapability | route smoke 通过 |
| `src/views/video-clone/**` | 视觉/生命周期最小修复 | 保持分层 | 不把业务逻辑堆回主视图 | 体积和测试门禁通过 |
| `scripts/quality/summarize-test-runtime.ts` | 负向报告输出治理 | 保留 fail-closed | 不吞坏报告 | 正常输出无误导 stack |
| `test-artifacts/task-107/1074/` | 工具产物 | 可审计截图/日志 | 不写人工完成报告 | 产物带 hash/metadata |

## 3. 技术决策与端到端职责

| 编号 | 决策 | 来源 | 约束 |
|---|---|---|---|
| D-01 | 上游导入使用目标 commit 的 Git archive；工作树只作辅助核对 | 仓库脚本/既有 107 约束 | 禁止复制 dirty 文件 |
| D-02 | manifest/hash/mode/symlink 是唯一来源完整性判据 | 既有脚本 | path set 与字节必须同时通过 |
| D-03 | 上游新增 API 先适配内部 EnginePort/工具契约；新增公开路由另立决策 | 本任务规划选择 | 不自动扩大 Edge 面 |
| D-04 | 本地/contract/live 分层，live 只按白名单和预算执行 | 用户要求+安全约束 | 缺凭据标阻塞，不假 ready |
| D-05 | UI 视觉按根 DESIGN token、Space Grotesk+Inter、双主题和 44px touch target | AGENTS/DESIGN | 禁止新 hex、外链字体和第三套规范 |
| D-06 | 组件外 composable 通过显式 `dispose/stop` 或注入 pinia 管理生命周期 | 现有 warning 事实 | 禁止全局吞 warning |
| D-07 | 任务产物进入 `test-artifacts/task-107/1074/`，任务结果在对话汇报 | AGENTS/模板 | 不新增完成报告文件 |

### 3.1 接线与状态

源码状态：`sourceCommit → manifestVerified → generatedPatched → dependencyReady`；环境状态：`installed → configured → prepared → healthy`；工程状态沿用既有 `provisioning/ready/failed`、revision/head、job/build 状态；UI 状态分别为 `loading/empty/ready/submitting/waiting_input/succeeded/failed/conflict/expired/disabled`。状态不可跨层伪造：contract PASS 不得变成 live PASS，HTTP 202 不得直接显示 succeeded，session expired 不得显示仍可预览。

异步接线：POST command 返回 command/job/resource id → SSE/轮询消费 activity/terminal → 持久 job/build/result 写回 → UI 由 composable 去重和停止监听。重试使用既有 requestId/commandId/CAS；超时、租约过期、进程死亡和页面卸载不得重复付费 submit。

## 4. 目标行为与业务规则

1. 维护者能从 GitHub main 生成可审计 U/G，任何缺失、改字节、改 mode、额外源码都失败并定位路径。
2. 新旧上游能力按兼容矩阵接线；不支持的 capability 返回明确 unsupported/blocked，不显示 ready。
3. 完整栈必须经过 Docker/浏览器/uv/数据库真实链；只读 contract 不替代实跑。
4. live 逐 provider 记录状态、模型、延迟、费用/未知、样片路径和阻塞原因；预算耗尽停止新请求。
5. AI 页面从入口到工程结果形成闭环；刷新、深链、错误、冲突、waiting_input、session expired 都有恢复入口。
6. warning 清理只改装配和资源释放，不改变 API、业务状态、权限、计费或测试断言。

普通创作者只能访问自己的 Project/Revision/Build/Asset/Result；operator 才能读取 runtime readiness、credential readiness 和 live catalog；未授权 Provider 的 submit 计数必须为零；作者包编译始终在 runner；Studio session 绑定 project/run/revision，旧 revision/跨项目素材拒绝。

## 5. 接口、数据、安全与并发契约

### 5.1 接口

沿用 `contracts/hypit-api.v1.json`、`contracts/hypit-tools.v1.json`、`contracts/hypit-coverage.v1.json` 和 107-1～107-fix-1 的既有字段/错误码。卡只能引用，不得在本书复制第二份完整字段表。统一信封 `{success,data,error}`；异步返回 accepted job/resource id；错误保留 `hypit_*` code、HTTP 状态和可恢复提示。任何契约变化必须先修订本 §5 和追踪表。

### 5.2 数据与恢复

本任务不新增数据库表和生产迁移。使用既有 Project/Revision/File/Job/Build/Output/Action/Session/Command 表及文件 journal、对象存储/本地资源句柄。备份必须包含 schema/数据/文件 manifest/hash；restore 到新卷只验证，不触发新 generation/build，不覆盖非空目标。

### 5.3 并发与幂等

同一 `commandId/requestId` 相同 payload 必须回放同一结果；不同 payload 是 conflict。Build/Agent/Studio/Preview 的租约、CAS、sequence 去重、terminal 单向迁移沿用现有实现。任何重试用例必须断言副作用次数、事件 sequence、revision/head、submit count 和最终状态。

### 5.4 安全与凭据

runner 使用 read-only、非 root、cap_drop/no-new-privileges、network 受限、仅 socket/slot 共享；broker/Java internal token 不进入 runner env。URL policy 拒绝私网/凭据/坏端口/路径穿越；日志和截图脱敏；live 仅使用受控环境注入。

## 6. UI 实现规格

### 6.1 页面与结构

AI 页面只使用 `ai.html` 和根 `DESIGN.md`。`VideoCloneWorkbench.vue` 继续纯装配，URL 交给 `useVideoCloneUrlState.ts`，业务流交给 Hypit composables，新大区块放同级 components。桌面显示 ProjectList + ProjectHeader + 多面板工作区；移动端改为纵向分区/可折叠面板，关键 CTA 固定可触达。

### 6.2 视觉和无障碍

使用 `src/style.css` 已有 token、`gl-card/gl-field/gl-btn-primary` 等类；暗/亮 token 成对；Space Grotesk 标题、Inter 正文/UI；按钮 touch target ≥44px；focus-visible 明确；dialog 焦点进入/ESC/返回；错误和 job 状态使用 aria-live；长 URL/source 可视截断但保留完整 aria-label。

### 6.3 截图矩阵

至少保存 12 张：`/video-clone` 与 `/:projectId`，light/dark，1440×900、768×1024、390×844，覆盖 loaded、empty、new dialog、submitting、error/conflict/expired。文件名为 `route__theme__viewport__state.png`，metadata 包含 commit、浏览器、fixture、时间和 sha256。截图失败必须标 `PENDING_SCREENSHOT`。

## 7. 数据库/迁移

N/A：本任务不新增表、不新增生产 migration、不修改历史 migration。备份恢复只消费既有 schema 和文件/对象 manifest；若上游变化要求新持久字段，必须停止并另立迁移决策，不由执行者临时添加。

## 8. 全局文件白名单、黑名单与仓库约束

### 8.1 白名单

- 上游/生成：`platform-hypit/upstream-manifest.json`、`platform-hypit/upstream/**`、`platform-hypit/.generated/hypit/**`（生成物）、`platform-hypit/patches/**`、Hypit 独立 lockfile。
- Backend/Java/Edge：既有 `platform-hypit/backend/src/**`、`backend/tests/**`、`platform-java/**/hypit/**`、契约文件、已登记 deployment/config 文件。
- UI/质量：`src/ai/**`、`src/views/video-clone/**`、关联 `src/views/ai-center/components/VideoCloneEntry.vue`、`src/style.css`（仅必要 token）、相关测试、`scripts/quality/**`、既有 acceptance scripts。
- 文档/产物：本任务书、`docs/任务书/README.md`、必要状态/续接索引；测试工具生成的 `test-artifacts/task-107/1074/**`。

### 8.2 黑名单

根依赖升级、无关服务、认证/钱包/计费业务、治理台 UI、历史 migration、生产数据、真实凭据、上游下载目录、人工完成报告、删除既有测试和任何未登记新公开路由。

### 8.3 约束映射

| 约束 | 适用卡 | 判定 |
|---|---|---|
| 三入口与 AI 独立 origin | C17-C24 | 保留 `ai.html` 路由和旧 alias |
| 根 DESIGN 双主题/token/字体 | C17-C24 | 截图和 token 检查 |
| 五大视图分层/`.vue ≤800` | C17-C24 | lint/file-size；不改五大视图逻辑 |
| Java/Edge/DTO/flags | C01-C16 | 只消费既有契约，新增变化先阻塞 |
| 数据/AI/计费/生命周期 | C09-C16/C25-C32 | 状态、幂等、凭据、warning 不改变业务语义 |
| 目录与质量 | 全部 | tests、scripts/acceptance、test-artifacts 归位 |

## 9. 开发计划、阶段出口与写入顺序

| 阶段 | 卡 | 输入 | 输出 | 解锁 |
|---|---|---|---|---|
| A 来源追平 | C01-C08 | 当前源码、远程 main、旧 manifest | 新 manifest/patch/lock/digest、迁移矩阵、回滚副本 | B |
| B 完整环境 | C09-C16 | A 的固定 G、契约、部署配置 | full E2E、备份恢复、live 分层证据 | C |
| C AI 视觉 | C17-C24 | B 的可用栈/fixture、根 DESIGN | 12 张截图、a11y/响应式修复和证据 | D |
| D 质量收口 | C25-C32 | C 的 warning/console 基线 | warning 清理、输出治理、全量回归和集成判定 | 任务 VERIFIED |

阶段间无循环依赖。共享文件写入顺序：C01 核验 → C02 manifest → C03 适配矩阵 → C04 patch/lock → C05-C08 回归/文档；B/C/D 不得重新定义来源或 HTTP 契约。每张卡引用本书唯一契约，不复制字段。

## 10. 任务卡

### C1074-01：重新核验上游 main 与本地基线

- **输入/必读**：`AGENTS.md`、本书 §2.2、`platform-hypit/upstream-manifest.json`、上游 `package.json`、`git status`。
- **源码定位/操作**：Git 元数据、`verify-107-upstream.sh`；读取远程 commit/compare、packageManager、Node/pnpm、变更文件。
- **输出**：目标 commit、祖先关系、变更分类、事实核验记录。
- **禁止/异常**：远程不可达、commit 不一致或用户所称“本地最新”无法证实 → BLOCKED，不覆盖本地。
- **AC-01/TC-01**：同一命令运行两次，commit/compare 稳定；dirty source 不计入来源。

### C1074-02：重建 upstream manifest

- **输入**：C01、`verify-107-upstream.sh`、既有 manifest 格式。
- **操作**：从目标 commit `git archive` 重建目录，更新 path/hash/mode/symlink/version；运行默认和 `--source` 校验。
- **禁止**：下载目录复制、忽略新增源码、手改 hash。
- **AC-02/TC-02**：删文件/改字节/改 mode/加源码四个单因子副本均非零；干净副本 0。

### C1074-03：上游 API 变更适配矩阵

- **输入**：C01/C02；上游变更文件；`backend/src/engine/**`、tools、runner、studio。
- **操作**：对 core、Hyperframes、provider-hyperframes-local、build-result、compiler-node、runtime-local、studio、video-cli 标记 added/changed/removed；修复 import/type/capability/error mapping。
- **输出**：适配代码、compat matrix、每项 fixture。
- **禁止**：未经契约修订新增公开 API；broker 直接执行作者代码。
- **AC-03/TC-03**：新增 frame/source/store fixture 可 check/plan；旧 template 可 render；unsupported capability 显式拒绝。

### C1074-04：patch、lockfile、G 重建

- **输入**：C02/C03；`platform-hypit/patches/**`、独立 lockfile、build script。
- **操作**：删除已被 upstream 吸收的 patch，重生成 before/after hash；用正确 pnpm frozen install；两次 build 比 digest。
- **AC-04/TC-04**：U 不变、patch manifest 与字节一致、digest 相同；错误 pnpm 在安装前失败且提示版本。

### C1074-05：Backend 本地行为回归

- **输入**：固定 G、backend tests、fixture。
- **操作**：backend typecheck/test、内置模板 check/render/ffprobe、runner compile、Studio、snapshot、capture、vocabulary、provider contract。
- **AC-05/TC-05**：目标测试退出 0；真实 MP4 可解码；外部缺失只计 EXTERNAL_BLOCKED/skip。

### C1074-06：Java/Edge/契约回归

- **输入**：既有 contracts、Java Hypit controllers/services、Edge flags/routes。
- **操作**：Hypit Java tests、deployment contracts、coverage contract；确认 DTO/error/status 与 B/前端一致。
- **AC-06/TC-06**：三向契约字段/错误码一致；权限/flag/202/SSE 状态不漂移；不新增 migration。

### C1074-07：前端兼容回归

- **输入**：AI route、video-clone composables/components、根 typecheck/lint/Vitest。
- **操作**：验证 `/video-clone`、`/:projectId`、`/hypit/*` alias、API envelope、SSE、file-size。
- **AC-07/TC-07**：类型、lint、相关测试通过；旧 alias 能到列表或工程深链；失败状态可呈现。

### C1074-08：来源阶段收口与回滚

- **输入**：C01-C07；旧 manifest/patch/lock snapshot。
- **操作**：临时目录重建旧版本，运行最小 check；同步上游引用和阶段证据。
- **AC-08/TC-08**：新旧版本均可按各自 digest 重建；失败不污染共享 G；对话交接给 C09。

### C1074-09：Docker/隔离栈启动

- **输入**：C08 固定 G、Dockerfiles、compose overlays。
- **操作**：compose config、镜像构建、up、health/readiness、Edge→Java→broker smoke。
- **异常**：镜像/daemon/端口失败记录环境阻塞，不切生产。
- **AC-09/TC-09**：三层 config 0；服务状态 installed/configured/prepared/healthy 可读；token 不泄露。

### C1074-10：真实本地工具链

- **输入**：隔离栈、capture/render Chrome、uv 环境、fixture。
- **操作**：Programs prepare/probe、模板 render、speech.measure、image transform/compose、snapshot/capture、yt-dlp fixture。
- **AC-10/TC-10**：至少一个真实 MP4 ffprobe 通过；成功、超时、未配置、URL policy 错误可证伪。

### C1074-11：runner 恶意包隔离

- **输入**：合法/恶意 author package、runner container。
- **操作**：读取宿主路径/token env、访问 Java internal、写 Distribution 四案；合法 compile/check 对照。
- **AC-11/TC-11**：恶意四案均拒绝、无越界副作用、合法包仍通过；日志不含秘密。

### C1074-12：备份恢复

- **输入**：project/revision/build/output/material、backup/restore scripts。
- **操作**：drain/maintenance、backup hash manifest、restore 新卷、核对数据/文件/revision/孤儿记录。
- **AC-12/TC-12**：sha256 全一致、目标空目录保护、无新 generation/build；失败可恢复服务。

### C1074-13：Provider catalog 与最小 probe

- **输入**：授权 provider 白名单、catalog、受控凭据。
- **操作**：逐 provider 读取 capability，最小 probe，不发送未授权请求。
- **AC-13/TC-13**：每项有状态/模型/延迟/样片或阻塞原因；未授权 submit=0。

### C1074-14：live 预算与取消

- **输入**：批准预算、低成本 fixture/provider、计费/submit spy。
- **操作**：成功、失败、取消、超预算四案；记录实际成本或 null。
- **AC-14/TC-14**：预算耗尽后 zero new submit；重试不重复扣费；取消终态单向。

### C1074-15：live 证据脱敏

- **输入**：live-records、service logs、截图。
- **操作**：secret scan、路径/URL/token 脱敏、证据 hash。
- **AC-15/TC-15**：敏感模式 0；状态和时间足够追溯；真实失败原因不伪装 success。

### C1074-16：环境阶段出口

- **输入**：C09-C15。
- **操作**：汇总 full/live 分层结果、更新 continuation 状态。
- **AC-16/TC-16**：LOCAL/CONTRACT/LIVE/EXTERNAL_BLOCKED 可机械区分；NOT_RUN 不解锁全量 VERIFIED。

### C1074-17：AI 入口与深链

- **输入/必读**：根 DESIGN、`src/ai/router.ts`、`AiWorkspaceNavigation.vue`、`VideoCloneEntry.vue`。
- **操作**：AI 入口、列表、工程深链、旧 hypit alias、query source。
- **AC-17/TC-17**：导航 active、返回入口、标题和错误恢复正确；无新 CreationCapability。

### C1074-18：列表/新建/空错

- **输入**：Project fixture、API mock/隔离栈。
- **操作**：loaded/empty/loading/error/disabled、新建 dialog 成功/失败、刷新。
- **AC-18/TC-18**：状态文案可行动；dialog 焦点和 ESC 正确；错误不泄露堆栈。

### C1074-19：工作区分层与布局

- **输入**：`VideoCloneWorkbench.vue` 和同级 components/composables。
- **操作**：核对 Source/Reference/Plan/Preview/Studio/Review/Results/Variants/Runtime 顺序、滚动、折叠；必要时保持视图纯装配。
- **AC-19/TC-19**：桌面/平板/移动无横向溢出；主视图 ≤800 行；业务流留在 composable。

### C1074-20：异步状态和恢复

- **输入**：SSE/job/preview/session/revision/export fixture。
- **操作**：queued/running/waiting_input/succeeded/failed、session expired、CAS conflict、export ready。
- **AC-20/TC-20**：terminal 停止监听、sequence 去重、重试不重复副作用；每状态有下一步。

### C1074-21：双主题 token 验收

- **输入**：根 DESIGN、`src/style.css`、C19 页面。
- **操作**：light/dark 切换、grep 裸色/外链字体、对比度检查。
- **AC-21/TC-21**：token 在暗/亮成对定义；字体符合规范；无新增裸 hex/CDN。

### C1074-22：移动端与键盘验收

- **输入**：浏览器、视口 390×844/768×1024、C18-C21 fixture。
- **操作**：Tab/Shift+Tab/Enter/Escape、面板滚动、底部 CTA、长 URL/source。
- **AC-22/TC-22**：焦点顺序可走完、touch target ≥44px、无横向滚动、dialog 回焦正确。

### C1074-23：截图采集

- **输入**：稳定 fixture、浏览器、主题切换。
- **操作**：采集 12 张矩阵截图及 metadata/hash；检查目标页面和状态确实可见。
- **AC-23/TC-23**：命名/viewport/theme/route/state 完整；浏览器不可用则 PENDING_SCREENSHOT。

### C1074-24：视觉阶段出口

- **输入**：C17-C23。
- **操作**：typecheck/lint/相关 Vitest/browser smoke、截图人工检查、console error 检查。
- **AC-24/TC-24**：无阻断视觉/交互问题；截图和测试证据可追溯；未完成截图不标 VERIFIED。

### C1074-25：Pinia warning

- **输入**：warning 基线、`useGrassland`、相关测试。
- **操作**：组件外 store 改显式 pinia/依赖注入；测试独立 createPinia；afterEach 清理。
- **AC-25/TC-25**：目标范围 `PINIA_R1004=0`；跨 account/identity 无串状态。

### C1074-26：生命周期注册

- **输入**：video-canvas、digital-human、Hypit composables 及测试。
- **操作**：hooks 在 setup/effect scope 注册；异步前注册；返回 stop/dispose 并验证。
- **AC-26/TC-26**：lifecycle warning=0；卸载后无 timer/SSE/迟到写入。

### C1074-27：attrs warning

- **输入**：`MerchantTaskForm.vue` 及同类 Fragment/Teleport 组件。
- **操作**：声明 props 或稳定 attrs 传递；补 DOM 落点测试。
- **AC-27/TC-27**：`Extraneous non-props attributes`=0；属性仍到正确节点。

### C1074-28：Hypit SSE/composable 资源释放

- **输入**：`useHypitJobs.ts`、`useHypitSource.ts`、相关 tests。
- **操作**：验证 terminal stop、切工程 stop、CAS conflict、dispose 后迟到事件。
- **AC-28/TC-28**：无组件外 lifecycle warning；乱序事件不改旧项目；请求/listener 数量可断言。

### C1074-29：负向报告输出

- **输入**：`summarize-test-runtime.ts`、quality tests、坏 XML/JSON fixture。
- **操作**：捕获子进程 stdout/stderr，保留非零断言；清理临时文件。
- **AC-29/TC-29**：坏报告仍非零 fail-closed；正常日志不混入预期 stack；脱敏规则不变。

### C1074-30：全量测试隔离

- **输入**：Vitest 配置、fetch/EventSource/timer/router/pinia mocks。
- **操作**：afterEach/afterAll 清理；单文件、随机顺序、全量三次执行。
- **AC-30/TC-30**：三种运行结果一致；无执行顺序和跨用例污染。

### C1074-31：warning 门禁

- **输入**：C25-C30 warning 分类结果。
- **操作**：增加最小 warning 收集/白名单机制，新增 warning 失败；不全局 mock console。
- **AC-31/TC-31**：基线 warning 数下降；人为新增 warning 能使门禁非零并定位文件。

### C1074-32：最终集成责任卡

- **输入**：C01-C31 全部证据、未提交文件、索引。
- **操作**：运行最终命令矩阵，核对追踪表、状态、文件白名单、docs links/status、git diff；对未运行项按阻塞协议归类。
- **AC-32/TC-32**：必须项 PASS；外部项有 `EXTERNAL_BLOCKED/NOT_RUN`；索引只保留本合并任务书；对话汇报不声称未实现功能已完成。

## 11. 测试、验证命令与追踪

| TC | Given/When/Then 核心断言 | 证据 |
|---|---|---|
| TC-01~04 | 来源/manifest/patch/digest 单因子破坏可失败，干净重建稳定 | C01-C04 logs/manifest |
| TC-05~08 | Backend、Java/Edge、前端、回滚不改变既有契约 | test output/diff |
| TC-09~12 | full stack、工具、隔离、恢复真实副作用可核验 | compose logs/MP4/restore report |
| TC-13~16 | Provider 白名单、预算、取消、脱敏、分层状态正确 | live-records/secret scan |
| TC-17~20 | 入口、工程工作区、异步状态、冲突/过期恢复可完成 | browser trace/API/SSE |
| TC-21~24 | 双主题、移动端、键盘、截图目标状态真实可见 | 12 PNG + metadata |
| TC-25~28 | Pinia/lifecycle/attrs/Hypit 资源 warning 消失且语义不变 | Vitest stderr/console capture |
| TC-29~32 | 负向报告、测试隔离、warning 门禁和最终集成 fail-closed | JSON/JUnit/summary |

### 11.1 本地/契约验证命令

```bash
git status --short
git rev-parse HEAD
gh api repos/hypit-ai/hypit/commits/main --jq '.sha'
bash scripts/acceptance/verify-107-upstream.sh --source <固定上游Git仓库>
bash scripts/acceptance/build-107-engine.sh --no-install
npm run typecheck
npm run lint
npm test -- --run
(cd platform-hypit/backend && npm test)
npx vitest run tests/deployment/hypit-full-coverage.contract.test.ts
npm run docs:status
npm run docs:links
npm run security:secrets
```

目录为仓库根，backend 命令在 `platform-hypit/backend`。本地失败退出非零；既有 unindexed link 若数量未增加按基线记录，不隐藏新增错误。

### 11.2 完整环境/live/截图

```bash
HYPIT_FULL_E2E=1 bash scripts/acceptance/verify-107-full.sh
HYPIT_LIVE_ENABLED=1 HYPIT_LIVE_BUDGET_CENTS=<approved> \
  bash scripts/acceptance/verify-107-live.sh
bash scripts/acceptance/verify-107-studio.sh
bash scripts/acceptance/ci-e2e-107.sh
```

full/live/CI 需要 Docker、浏览器缓存、隔离数据库、Node/uv/ffmpeg 和受控凭据。未满足条件时预期退出 2/非零并写解除条件；不能把阶段 0 扩写成完整通过。截图动作使用 Playwright 或仓库已配置浏览器工具，每次动作后读取页面可访问树，再保存 PNG 和 metadata；空白页、测试数量或假 fixture 不算用户闭环证据。

### 11.3 最终集成验收

C1074-32 必须确认：C01-C31 依赖已完成；来源、契约、后端、Java/Edge、完整环境、live、截图、warning、报告和文档都有独立状态；所有必须项无 FAIL/PARTIAL/NOT_RUN/SKIPPED；外部阻塞单列且不伪造全系列 VERIFIED。若 live 或完整部署是用户明确要求的必需项而仍未执行，整任务保持 IMPLEMENTED/BLOCKED，不标 VERIFIED。

## 12. 阻塞、修订与恢复

- 缺少上游 commit、固定工具链或 manifest 决策：C01 BLOCKED；恢复后从 C01 重新核验，不沿用旧 hash。
- 上游破坏既有公开契约：C03 BLOCKED；提交最小兼容方案和影响，不能临时扩 Edge。
- Docker/浏览器/模型/凭据不可用：只阻塞对应 C09-C16/C21-C24；本地可独立验证的卡继续执行，最终状态保留外部阻塞。
- warning 修复需要改变公开 composable 签名、权限或计费：C25-C31 BLOCKED，先修订 §3/§5/追踪表。
- 发现文件不在白名单：停止写入，记录路径和原因，修订 §8 后再继续。
- 换会话续作：先核对 `git status`、本书状态、最近一张 VERIFIED 卡、产物 hash、当前 commit 和未解决问题；不凭上一模型摘要跳卡。

## 13. 汇报、续作与索引

每卡在对话中汇报：状态、命令、cwd、退出码、测试统计、产物、失败/阻塞和下一卡输入。阶段出口只在输入/输出/证据齐全时推进。最终汇报必须区分源码追平、功能 contract、完整环境、live、视觉截图、warning 质量和生产发布；本任务不默认生产发布。

任务书索引只保留本合并入口 `[ #107-4 ](草场任务书-107-4-Hypit追平环境验收视觉与质量收口.md)`，旧的四份 107-4 子任务书删除，不保留重复入口。索引已有 #107-1、#107-2、#107-3、#107-fix-1 及其他任务条目必须保留。
