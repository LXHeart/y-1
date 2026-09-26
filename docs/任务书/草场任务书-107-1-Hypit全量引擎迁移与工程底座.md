# 开发任务书：Hypit全量引擎迁移与工程底座（草场任务书 #107-1）

| 项目 | 内容 |
|---|---|
| 模板 / 任务书版本 | 任务书模板 3.0.0（2026-09-25） / 3.1.0（模板对齐版） |
| 编写 / 事实核验日期 | 2026-09-25 |
| 规格状态 / 实施状态 | READY_FOR_IMPLEMENTATION / IMPLEMENTED（2026-09-26）：C01、C02 VERIFIED；C03 VERIFIED；C04～C08 IMPLEMENTED（逐卡 TC/V 见 §10；真实远程 provider live 调用与实机项 REAL_NOT_RUN 如实单列；IMPLEMENTED 不等于 VERIFIED，逐卡 VERIFIED 化与 107-2 接手续核验按 §10.1 门槛执行） |
| 作者 / 执行与集成负责人 | Codex 编写；承接当前任务书的主程负责卡级验收，107-3 主程负责全系列最终集成 |
| 目标仓库 / 分支 | `/Users/LXH/claude/y-1` / `main`，只记录，不切换 |
| 代码基线 | `a8497ed98de76c59fcb7f82fc578ca89e6be4b68`；已有模板/索引、105fix-1/107 文档及数字人调度相关 Java/测试/config 改动，完整保留；核验明细见 §2.6 |
| 上游基线 | 本地 `/Users/LXH/Downloads/GitHub-project/hypit`；commit `2c320059c1260d0f6f8101472395f91f2f9c8243`；0.2.13 |
| 顶层顺序 | [107-1](草场任务书-107-1-Hypit全量引擎迁移与工程底座.md) → [107-2](草场任务书-107-2-持久执行与Studio及克隆Agent.md) → [107-3](草场任务书-107-3-创作工作区与全量集成验收.md)；共享契约/迁移/锁文件，必须串行 |
| 本书执行范围 | C107-01 → C107-08，8 个细分执行单元；它们合起来是一份顶层任务107-1 |
| 执行模式 | AUTO_CHAIN；起始 C107-01；不要求多agent，用户未授权时不启动子代理 |
| 交付终点 / 发布边界 | 本地实现、隔离数据库/容器/浏览器验证；生产发布和真实付费调用按会话实际授权，不以任务书伪造授权 |

目标执行者：能力较弱的编码模型；本书 8 卡、系列共 24 卡。决策依据与本轮授权边界见 §1.9。

依据：[任务书模板](任务书模板.md)、[103](草场任务书-103-全模块业务一致性修复与工程优化.md)、[104](草场任务书-104-整体复核缺陷修复与生命周期门禁加固.md)、[106](草场任务书-106-104复核返工与验收补全.md)。仅借鉴其规格结构、精确写入组、反例测试与证据纪律，不继承其历史 VERIFIED 或旧技术事实。

### 契约阅读地图（全系列唯一权威位置）

| 内容 | 唯一位置 | 使用方式 |
|---|---|---|
| ID/金额/响应、API/工具、鉴权/路由/传输 | [107-1](草场任务书-107-1-Hypit全量引擎迁移与工程底座.md) v3.1.0 §6（K02/K03/K04/K07/K08/K09/K12） | 107-2/3 只消费既定契约，不另定义同名字段 |
| DDL、幂等/租约/文件journal | [107-1](草场任务书-107-1-Hypit全量引擎迁移与工程底座.md) v3.1.0 §7（K05/K06） | schema由C04一次建立；后卡接实现，不另抄一套迁移 |
| 作者代码隔离 | [107-1](草场任务书-107-1-Hypit全量引擎迁移与工程底座.md) v3.1.0 §3 K10.4 | C02建最小runner，C07/11补其授权和capture调用 |
| Studio session/写回与Agent产物 | [107-2](草场任务书-107-2-持久执行与Studio及克隆Agent.md) v3.1.0 §6（K10.1–3/K11） | 专业编辑器、分析/计划/审片按此实现 |
| fixture/测试运行器 | [107-1](草场任务书-107-1-Hypit全量引擎迁移与工程底座.md) v3.1.0 §12 K13 | 各卡选择真实fixture，区分replay/fixture/live |
| UI、部署、最终清单与交接 | [107-3](草场任务书-107-3-创作工作区与全量集成验收.md) v3.1.0 §8/§9.2/附D/§14/§14.1（107-3 含 K14） | 逐项核销；全量清单不能剪裁 |

本系列三份任务书必须共同使用 **v3.1.0**，不混用旧版同名 K/字段；模板版本不等于任务书版本。

107 系列唯一执行文件是 107-1、107-2、107-3，按此顺序实施；不存在另外的107主任务书或独立执行附件。K编号按上表定位；C107-01～24是三份任务书内部的执行单元，F01～40是功能清单编号，均不是额外任务书。

## 0. 执行协议与完成定义

1. FACT 是真实源码事实；DECISION/NEW 是本任务已经选定但尚未实现的行为/模块；EXAMPLE 只展示数据格式。不能把未来类或命令当作仓库已有实现。
2. 本书不覆盖更高优先级指令或 AGENTS.md。先确认规格/实施状态、v3.1.0 与实际授权卡范围；读根 AGENTS、本书 §0/1/9/10/13/14、当前卡及其契约引用。UI 前按 §2.2 读对应 DESIGN，续作先核对 §14.1。
3. 记录HEAD、修改/未跟踪文件、相关差异，保留现有代码。下载目录Hypit的LICENSE存在本地修改，vendor必须从固定Git对象导出，不能复制工作树。禁止reset/clean/批量覆盖。
4. 只写当前W组与§9的交集。私有 helper 可在已登记文件内自行拆分；确需新文件先由有文档修订权限的负责人在当前 W 组与全局许可同步登记具体路径、理由和回归，再实施；新增公开字段/权限/范围变化按§13修订。
5. C01 交付“源码完整性 + 实际原生基线记录”，不负责修复上游。原生失败仅在可复现并能证明来自固定版本时记 BASELINE_RECORDED（证据属性，不是卡状态）；全部命令真实执行、失败已分诊并明确交给 C02 后才可完成 C01。未知失败、缺环境/未运行仍阻塞。C02 必须修复登记缺陷并回归运行副本，107-1 出口不接受运行副本必需测试失败。

6. 不缩减全部功能范围；源码复制、iframe可打开、三个模板渲染均不是107全量完成条件。后置任务是必交付范围，不是可选第二期。
7. 执行状态 NOT_STARTED → IN_PROGRESS → IMPLEMENTED → VERIFIED；阻塞为BLOCKED。只有代码写完是IMPLEMENTED；全部本卡 DoD/TC/V/证据（含明确的诊断卡边界）满足才能 VERIFIED。FAIL/NOT_RUN/SKIPPED/PARTIAL 不计运行通过；C01 的诊断交付按第 5 条单独验收，保留原生失败，C02 必须闭合运行副本缺陷。
8. AUTO_CHAIN 仅在实际授权范围内当前卡 VERIFIED 后继续下一卡；IMPLEMENTED/BLOCKED 不解锁依赖。外部环境只阻塞对应实测：继续独立本地实现/契约验证，但不得把有阻塞的卡标VERIFIED；依赖实际交付物没满足就不伪造后卡成功。
9. 默认本地fixture/replay不收费；真实模型/Provider调用须有可核验凭据与预算/范围授权。已授权同范围调用不重复询问；生产发布不在本任务默认授权内。
10. 每条测试保留cwd、命令、起止时间、退出码、总数/失败/跳过、缓存状态和日志路径；Java XML按运行分目录归档。不以旧任务绿灯代替本次验收。
11. 新测试在tests或现有源码就近目录，vendor原生test原样保留。ART/Cxx 存测试工具生成的日志/报告/截图，不新增叙述性的完成/交付报告。前端仅装配，Vue≤800行，现有豁免只减不增。

### 0.3 完成定义（DoD）

当前卡全部步骤、REQ/AC、TC、指定V与影响回归通过；敏感信息零泄漏；未越界且保留原改动；UI适用时截图实际查看；状态、coverage与交接一致。已由当前代码满足的步骤先核验，不重复造功能；未改变且仍有效的证据可说明依据后复用。文档编写阶段没有完成这些运行验收。

## 1. 产品需求、目标与范围

### 1.1 一句话目标

将固定Hypit全部源码/依赖能力纳入可审计本地执行底座，交付Java契约、持久工程/素材、Python、全Provider授权以及可冻结执行计划。

### 1.2 背景与价值

创作者需要把参考素材、源文件、组件和生成结果保存在同一工程中，避免每次编辑都重新生成。维护者需要能够追溯引擎来源、重放补丁并验证作者代码隔离。本书先交付后续网页工作区可以调用的工程与执行底座，使完整引擎迁移可审计、可恢复；网页闭环由后两书接通。

### 1.3 范围内（可验收需求与责任）

| REQ | 用户/触发场景 | 必须交付的可观察结果 | 优先级 | 对应卡 / AC | 测试 |
|---|---|---|---|---|---|
| REQ-107-01 | 维护者引入固定引擎版本 | 完整 vendor、hash 清单、原生测试基线 | 必须 | C107-01 / AC-107-01 | TC107-01-01～04；V见§10 |
| REQ-107-02 | 维护者启动引擎并运行作者组件 | bootstrap/Runtime Host 桥、可重放补丁与最小引擎宿主 | 必须 | C107-02 / AC-107-02 | TC107-02-01～04；V见§10 |
| REQ-107-03 | 应用调用方请求项目及工具 API | API/工具 schema、Java 身份边界、禁用态/事件协议 | 必须 | C107-03 / AC-107-03 | TC107-03-01～04；V见§10 |
| REQ-107-04 | 创作者创建工程、保存或恢复版本 | 项目、文件、快照、幂等命令与迁移 | 必须 | C107-04 / AC-107-04 | TC107-04-01～04；V见§10 |
| REQ-107-05 | 创作者上传参考或选择媒体 | 参考素材、链接抓取与完整媒体工具 | 必须 | C107-05 / AC-107-05 | TC107-05-01～04；V见§10 |
| REQ-107-06 | 创作者需要转写、估时或图像处理 | 转写/估时、OpenCV、Python Programs | 必须 | C107-06 / AC-107-06 | TC107-06-01～04；V见§10 |
| REQ-107-07 | operator 配置能力、创作者授权生成 | 全 Provider、凭据/OAuth、外部执行 bridge 与素材传输 | 必须 | C107-07 / AC-107-07 | TC107-07-01～04；V见§10 |
| REQ-107-08 | 创作者准备执行或复用已有结果 | 编译、计划、价格、候选复用解析 | 必须 | C107-08 / AC-107-08 | TC107-08-01～04；V见§10 |

### 1.4 范围外（明确不做）

不重写认证/钱包/既有数字人和视频管线，不升级根前端依赖/既有Java框架，不修改生产数据、历史迁移、真实凭据或下载源码目录。其他两份任务的业务由其责任卡实现；当前卡只能消费其约定，不抢写共享契约或生成假成功桩。所有原生功能范围在107-3附D，属于系列整体交付。

### 1.5 不许顺手修

不得借本任务修复数字人调度、旧视频制作、认证/钱包、历史迁移或其他未登记问题；不得覆盖工作区已有模板、105fix-1 或 Java 改动。发现无关缺陷在 §14 对话记录，另行安排处理。

### 1.6 用户、入口与已知限制

个人工程以已验证accountId归属；全局Profile/凭据操作限部署operator，普通用户只读脱敏readiness。缺外部账号/模型权重只能阻塞相应live/真实本地验收，不删能力、不声称全部可用；上游未实现的静态抠图mapping如实unsupported，保留扩展契约。商业分发不是本任务目标，仍保留源码许可证与标识。

### 1.7 用户场景与业务闭环

| 场景 | 用户与触发 | 主流程 | 结果与失败出口 | 需求/验收 |
|---|---|---|---|---|
| SC-107-01 | 部署维护者安装固定版本并启用本地执行 | 校验源码清单 → 重放补丁 → 启动 broker/runner → 运行纯本地示例 | 真实 MP4 可解码；源码/运行副本可区分；隔离失败即停止启用 | REQ-107-01/02；TC107-02-01/03 |
| SC-107-02 | 创作者由后续 UI 或已鉴权 API 建工程 | 创建 → 导入素材 → 保存多文件 → 冻结 revision → check/plan/pricing | 读回同一工程及证据；冲突保留旧 head 和本次输入；未授权 submit=0 | REQ-107-03～08；TC107-04-02/03、TC107-08-01 |

### 1.8 产品成功标准

本书八条 REQ 全部具有当前基线的卡级证据，固定源集合完整、受限 runner 能生成真实可解码媒体，工程/素材在真实 PostgreSQL 与文件系统重读一致，计划/预算/权限失败反例可证伪。工程底座成功不等于网页或全部 Provider 已验收；本书出口由 C107-08 按 §12.5 负责。

### 1.9 未决问题与决策权限

本轮明确授权为“依据最新模板修改 107 的三份任务书”，不包含实施业务代码、真实付费调用或生产发布。功能范围和 D/K 契约来自现有 107 v3.0.0 规划，本轮保留为规划决策；不将历史稿中的“已定”解释为用户逐项批准。实施启动时核对用户实际指定的版本与卡范围；已授权全系列可跨书推进，仅授权单书则在该书出口交接。

| 项目 | 当前结论/依据 | 处理与责任 |
|---|---|---|
| 模板升级、结构与验证补齐 | 用户本轮明确要求 | 规划者直接完成，不改功能范围 |
| D-01～09、K01～14 的产品/架构方案 | 沿用 v3.0.0 的唯一规划方案，定位见契约地图 | 按本版交接，不声称取得新的生产/资金授权 |
| 局部变量、私有 helper、等价定位 | 不改变契约和登记文件范围 | 执行者自行处理；新增写入路径先按 §13.3 修订 |
| 真实 Provider 凭据、预算、模型权重和受限远程资源 | 本次未验证可用，也未新增调用授权 | 实施者准备本地环境；真实调用缺授权则只阻塞对应验证并记录解除条件 |
| 未定义的权限、费用、数据丢失、公开接口或范围变化 | 本轮不新增此类决策 | 停止受影响卡，交规划负责人按 §13 修订；不临时猜测 |

当前没有新增的产品方案二选一；外部资源可用性属于实施验证前置，不是已通过的规格事实。若实施前的源码复核推翻关键可行性，受影响规格改为 BLOCKED_DRAFT，不能让后卡自行换方案。

## 2. 仓库上下文与事实基线

### 2.1 目标端与入口

AI端 `ai.html → src/ai/main.ts → src/ai/router.ts`；用户端与治理端保持既有入口。公开业务只进入 edge-bff → intelligence-service，Node是内部Hypit执行宿主。107-1建立后端协议，107-2接Studio/Agent，107-3接完整Vue产品面。

### 2.2 设计规范与复用

根 DESIGN 为 grassland-design，AI与用户端共用；治理台只回归，使用 src/ops/DESIGN.md。复用 `src/components/GlModal.vue`、`src/components/shared/EmptyState.vue`、`src/components/LoginModal.vue` 与 `src/composables/grassland-http.ts`。Space Grotesk+Inter仅用于应用UI，不能替换作品自己的字体资源。

### 2.3 精确目录常量

路径均相对仓库根；缩写必须展开，不创建字面J/B/V目录。有限 `{a,b}` 表示两项，不能扩展为任意通配授权。

| 缩写 | 精确路径 |
|---|---|
| `U` | `platform-hypit/upstream` |
| `G` | `platform-hypit/.generated/hypit` |
| `B` | `platform-hypit/backend` |
| `JI` | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence` |
| `JIT` | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence` |
| `J` | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit` |
| `JT` | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit` |
| `JR` | `platform-java/services/intelligence-service/src/main/resources` |
| `E` | `platform-java/services/edge-bff/src/main/java/com/grassland/edge/proxy` |
| `EC` | `platform-java/services/edge-bff/src/main/java/com/grassland/edge/config` |
| `ER` | `platform-java/services/edge-bff/src/main/resources` |
| `ET` | `platform-java/services/edge-bff/src/test/java/com/grassland/edge/proxy` |
| `V` | `src/views/video-clone` |
| `ART` | `test-artifacts/task-107` |

### K01. 开工检查命令

从仓库根分别执行并记录；不输出完整环境变量或真实凭据：

```bash
pwd
git status --short
git rev-parse HEAD
git -C /Users/LXH/Downloads/GitHub-project/hypit rev-parse HEAD
node --version
npm --version
command -v pnpm
command -v docker
command -v uv
command -v ffmpeg
command -v ffprobe
```

源commit需一致；主仓HEAD变化先读相关差异，不直接覆盖新实现。具体工具版本由§3锁定；工具存在不等于模型/浏览器已准备。

### 2.4 当前事实与源码锚点

#### 2.4.1 Hypit FACT

本地源目录 `/Users/LXH/Downloads/GitHub-project/hypit`；tracked 文件 **1768**，`.ts` 文件 **977**，`.test.ts` 文件 **203**；`packages/*` **122**，`services/*` **3**，workspace 示例包 **12**，Skill Markdown **65**。详见107-3附D逐包清单。以上是源码统计，不是测试通过数。

| 源码锚点（相对 Hypit 根） | 核验结论 |
|---|---|
| `package.json`、`.node-version`、`pnpm-workspace.yaml` | 版本 0.2.13；最低 Node 22.15，仓库 Node 24.14.1；pnpm 10.33.0；Studio Vite 8.2.2 独立于 y-1 Vite 7 |
| `bin/hypit.mjs` | tsx register → Distribution resolver → external resolver；CLI 支持 JSON，不应声称只能解析人类文本 |
| `packages/video-cli/src/index.ts`、`distribution.ts`、`compiler.ts` | 应复用 video distribution 的包发现、编译器和 Runtime Host；直接 import 裸 compiler 不等于装配全部域 |
| `packages/cli/src/command.ts` | check/plan/pricing/build/status/activity/logs/results、runtime/programs/auth/packages 等完整命令面 |
| `packages/video-cli/src/{media,creation,capture,snapshot,vocabulary}.ts` | 8 类媒体命令，transcribe/measure、网页采集、已有画面快照、词汇发现 |
| `packages/runtime-local/src/{host,config,runtime,worker}.ts`、README | Worker 和 Build 观察分离；每 Build 新实现上下文；pool/action 加权限额；失去执行进程不自动重发付费请求 |
| `packages/studio/src/{server,mutation-origin,feedback-server}.ts`、`src/ui/*` | 根绝对 API、localhost 写保护、修订冲突、评论、材料库，嵌入必须真实修改路由适配 |
| `packages/video-cli/src/studio-distribution.ts` | 官方 Companion 12 个，不能只迁移通用文件编辑器 |
| `packages/provider-*/src/{provider,mapping,activation}.ts` | 6 个远程 Provider + 4 个本地 Provider；模型支持组合不能取笛卡尔积 |
| `packages/browser-capture/package.json`、`packages/provider-hyperframes-local/package.json` | capture Chrome `153.0.8010.12`；render Chrome Headless Shell `152.0.7928.2`，不是同一个二进制/缓存选择 |
| `packages/cli/src/oauth.ts` | PKCE + hosted redirect + 本机 loopback callback；远程浏览器可显示 `code#state`，容器不能假设能访问用户浏览器的 localhost |
| `packages/provider-hiapi/src/activation.ts` 等 | 原生 JSON Profile 无 publicAssetUrl 函数配置；Provider factory 支持该 callback，必须宿主注入 |
| `packages/yt-dlp/README.md` | fetch/prepare-fetch 是独立工具，不是第 5 个 Runtime Provider，也不创建 Build |
| `packages/provider-whisperx-local/README.md` | 本地路径协议必须 loopback；ASR 与语言对齐权重分开准备 |
| `services/*/pyproject.toml`、`uv.lock` | OpenCV Python 3.13；WhisperX/yt-dlp 3.10–3.13；分离 uv 环境，不能统一强行升级 |
| `packages/provider-hypihub/README.md` | 静态去背景只有 capability，HypiHub 当前未实现它；不得显示假 ready |
| `examples/README.md` | 7 个示例目录；3 个 reference 生成工程、复杂讲解的外部材料包；swap 变体并非全已重新构建 |

v3.0.0 已记录完整目录/包清单、命令与能力入口及相关测试组织；本轮增量复核范围见 §2.6，未安装上游依赖或运行整套测试。不能据此宣称每一行引擎代码均经正确性审计。执行 C107-01/02 必须补真实构建基线与适配冒烟。

#### 2.4.2 y-1 FACT 与复用位置

| 位置 | 落点 |
|---|---|
| `src/ai/router.ts` | 当前无 Hypit；新增 `video-clone` 路由，`/hypit` 作为兼容别名 |
| `src/ai/components/AiWorkspaceNavigation.vue` | 当前只有创作中心、数字人；新增视频克隆导航 |
| `src/views/ai-center/components/VideoStudioView.vue` | 现有模板/字幕/BGM 工具；只增加克隆入口子组件，不替换现有功能 |
| `src/views/ai-center/AiCreationCenter.vue` | 保持装配和体积限制；必要时从现有大区块抽组件后再接入 |
| `src/lib/creation-workspace.ts`、`src/types/creation.ts` | 当前 capability 只有 article/image/video/moments；本版独立工程，不强塞未知枚举 |
| `src/views/video/VideoAnalysisView.vue`、`mediaplatform/` | 可接来源摘要；临时 MediaArtifactStore 不是永久素材库，导入时必须落可持久媒体 |
| `security/IntelligenceCallerResolver.java` | `resolve` 真正验签、唯一消费 replay jti；预检用 resolveForPreflight |
| `ai/run/FrozenTextExecutionService.java`、`ai/ChatMessage.java` | 既有独立/批处理执行和多模态 image parts；LLM 执行在 Java |
| `ai/run/AiExecutionService.java` | prepare/settle/handleFailure/handleCancellation；远程 Need 需接执行门禁，不能仅凭“毕设”跳过运行留痕 |
| `videoproduction/VideoAssetArchiveService.java` | 小 MP4 `archiveGeneratedBytes` 可复用；返回 `/api/media/{id}` 字符串，非 mediaId DTO |
| `media/`、`ObjectStorageAdapter` | 原始素材、导出媒体的持久归档、归属和 presign 链路 |
| `platform-java/services/edge-bff/src/main/resources/application.yml` | 每条 method/path/flag 注册，业务只新增 intelligence 路由，不新增 Node 公共上游 |
| `nginx.conf`、`Dockerfile.frontend`、`deploy/digital-human/` | 参考 opt-in 片段模式；AI 专用 Studio/预览路径有独立代理与票据 |
| intelligence `src/main/resources/db/migration/` | 核验最大 V90；预留 V91，实施先检查撞号，已执行迁移禁止修改 |

上表 Java 相对路径以 `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/` 为根。

### 2.5 当前行为、问题与实现边界

已有上游导入文件和校验脚本，尚无 Hypit 公开业务、持久工程、完整Studio代理或网页克隆Agent。旧视频制作/分析和数字人流程继续使用自己的模型与工程，不能直接套用未知的CreationCapability值。问题是完整引擎能力尚未被y-1产品化，不是已有接口仅缺按钮。

### 2.6 本次核验与测试基线

2026-09-25 本轮核对：主仓 HEAD/分支与上表一致；源仓固定 commit、1768 文件/977 TS/203 TS 测试/122 包/3 服务统计一致；源工作树 LICENSE 仍有本地修改。核对 AI 路由/导航、Edge exact 匹配、Caller.resolve/resolveForPreflight、FrozenTextExecutionService.executeIndependentPrepared、VideoAssetArchiveService.archiveGeneratedBytes、ObjectStorageAdapter 签名、迁移上限 V90、根 package scripts、Java 构建/运行时 helper、设计规范及生命周期登记门禁。续作时已发现 platform-hypit/upstream、upstream-manifest.json 和 scripts/acceptance/verify-107-upstream.sh，以及 C01 的本地执行日志；引擎导入工作已开始。视频克隆视图和 Hypit API contract 仍未建立。本轮只核对存在性与文档接口，不据日志文件存在判定 C01 验收通过。文档门禁现状：docs:status 与格式/结构检查通过；docs:links 返回 1（坏链接 0，上游 281 份文档未索引），由 C01 的文档接入和 C24 的 V16 复验收口，本轮不改第三方源或降低门禁。

上游详细功能/模型/示例逐项清单沿用本系列 v3.0.0 对固定 commit 的记录，本轮未逐行重新审计；C01 用 manifest 校验完整集合，C02 及后卡验证真实运行。Node/npm 实测为 v22.22.3/10.9.8，只用于本轮文档工具；它不是已就绪的 Hypit Node 24.14.1 环境。

本轮仅执行文档结构、链接、状态与 diff 检查，结果在对话汇报；本轮没有执行 Hypit 运行验收；C01 既有执行记录待实施者复核，其余浏览器、本地模型和真实 Provider 验收未在本轮运行。JDK25、Docker、pnpm、uv、浏览器/模型准备按 §9.3 在实施前核验，不把工具存在当运行通过。

开工已存在的改动：docs/任务书/README.md、任务书模板.md；未跟踪的 105fix-1 与三份 107；intelligence-service 的 DigitalHumanCleanupWorker、DigitalHumanReconciliationWorker、DigitalHumanSessionReaper、application.yml、IntelligenceItSupport 及未跟踪 DigitalHumanWorkerSchedulingIT。续作新增可见 .gitignore/.dockerignore、数字人测试与 ReleaseMigratorUpgradeIT 改动，以及 platform-hypit 与 verify-107-upstream.sh。文档修订只更新三份 107 和索引的 107 行，其他内容保留；实施者仍须重新读取 git status/diff，不以这里的快照覆盖进行中的工作。

### 2.7 影响面与兼容要求

| 影响 | 保留义务 | 验证 |
|---|---|---|
| Edge新增模板路由 | 无模板exact/prefix行为不变；关闭flag仍404 | C03/V06 |
| AI执行环/媒体 | 预算/ai_run/归属/Outbox/原视频流程不变 | C07/10/V10 |
| 数据/文件 | 不修改历史迁移；未知远程结果不盲重提 | C04/09/23 |
| 三入口 | AI新路由不泄露到治理/用户入口；旧URL刷新/返回保留 | C22/24/V13 |
| 样式/编辑器 | DESIGN双主题；完整Studio与作品视觉区分 | C13/21/V11/V19 |

### 2.8 最小源码锚点（FACT；执行时仍读完整调用上下文）

`E/UpstreamResolver.java`，当前 exact 分支：

```java
if (route.exact()) {
    return path.equals(routePath);
}
```

`U/packages/runtime-host-node/src/index.ts` 的 `RuntimeHostExecution.build` request 含 `readonly id: string` 与固定 `result.repository: BuildResultRepositoryLocation`；调用者指定稳定ID是原生能力，完整类型在该文件读取，不自行猜前缀。

`platform-java/platform-storage/src/main/java/com/grassland/storage/ObjectStorageAdapter.java` 当前签名摘录：

```java
UploadTicket presignUpload(PresignRequest request);
void putObject(String key, byte[] content, String contentType);
Optional<StoredObject> headObject(String key);
```

不存在putStream；大文件按107-1 K09.4使用签名PUT流式转存。上述是现有签名，K04及卡内Hypit*类/EnginePort是NEW，必须由适配实现。

## 3. 已定技术决策

### D-01 全量 vendor + 可审计适配层
从指定 Git commit 导出全部 tracked 文件到 `platform-hypit/upstream/`，保留隐藏配置、源码测试原 `test/`、文件模式与原生目录结构；不要 rsync 工作树夹带缓存或复制空 LICENSE。新写的 y-1 测试用 `tests/`，vendor 中既有目录原样保留。

生成 `platform-hypit/upstream-manifest.json`（commit、路径、mode、SHA-256）。校验集合相等和每文件 hash，不用“src/*.ts 数量”与“全部 ts 数量”不对称比较。构建时复制到 Git 忽略的 `.generated/hypit/`，顺序应用 `platform-hypit/patches/*.patch`；补丁前后 hash、目的、受影响测试写入 `patches/manifest.json`。运行唯一来源为这个本地源码构建结果，不下载替代版 `@hypit/hypit`。

补丁只做集成、缺陷修复、必要边界注入；不删上游功能/品牌/测试。自定义视频组件放各项目 `packages/`，不改 Distribution 的 `@hypit/*` 命名空间。
### D-02 Java 业务面 + Hypit 执行侧车
```text
ai.html Vue「视频克隆」
  ├─ /api/hypit/** → edge-bff → intelligence/hypit（身份、项目、Agent、任务、媒体）
  │                                     └─ /internal/v1/** → hypit execution host
  └─ /hypit-studio/s/:sessionId/**、/hypit-preview/s/:sessionId/**
       → nginx → sidecar 会话网关 → 绑定工程的 Studio / 受限预览

execution host（可信 broker）→ 受限 author runner（compiler / 项目代码）
               → 上游 Runtime Host → detached Worker / Build executor
               → ffmpeg + 固定 Chrome + 分离 uv Programs → Result Repository
               → 远程 Provider（经过执行授权、凭据与资源传输适配）
```

Node 是 Hypit 引擎宿主，不承接账号、项目业务数据库、LLM 决策和财务；作为本任务对模板 R-JAVA 的明确例外，允许内部 HTTP 执行面。Java 继续是唯一公开业务后端。采用 Node 原生 `node:http`，无需 Hono/Express；SDK 能力优先直接调用上游导出。对未导出的 CLI 功能，受控 `runVideoCli(argv, io)`/独立子进程与 `--json` 可用，禁止拼 shell 文本或解析彩色表格。任何使用进程 cwd 的工具必须独立进程，不在共享 HTTP 进程调用 `process.chdir()`。

源码包发现、编译及作者组件运行进入受限 runner；HTTP 宿主不直接 import 用户包。可信 Provider broker 保管凭据并代理已授权 Need。独立隔离服务、typed IPC、目录与网络限制按细则 K10 实现；原生 CLI 的单用户运行模式保留，但不能作为网页作者代码隔离的替代。

Node 固定 **24.14.1**；pnpm **10.33.0**；继承上游锁定 `tsx@4.21.0` 等依赖，不与根 npm/Vue 工具链合并。WS 代理允许 backend 独立锁定 `http-proxy@1.18.1`，类型 `@types/http-proxy@1.17.16`；其余新增依赖需在对应卡登记原因和精确版本。Java 无新框架；前端优先原生编辑控件和完整 Studio，不额外引第二套编辑器框架。
### D-03 完整功能的三种承接方式
- 原生保留：编译/Runtime/模型/Provider/结果/Studio/组件 ABI，通过适配调用，不重写简化版。
- 原生能力产品化：媒体工具、凭据配置、资源库、结果修复、工程包工具在网页高级工具中可操作；支持所列参数，不只保留终端命令。
- NEW：克隆 Agent、参考理解、方案与变体是 y-1 的编排能力，使用上游 Skill 和工具；Hypit 本身没有一个现成的 `/clone` API。
### D-06 AI 运行、凭据和费用
参考理解/方案/代码生成/审片使用 Java 的 `FrozenTextExecutionService.executeIndependent*`，多模态经 `ChatMessage.parts`；保留内容检查、模型冻结、预算与 ai_run，使用已有 `CREATION_ASSISTANT` 功能键，不借“文本任务”绕过真正图像/视频执行。

Hypit 远程 Provider 原生传输与模型语义必须保留。新增 `HypitExternalExecutionBridge`：在实际远程 Need 的 invoke/submit **之前**登记并授权；operationId 稳定映射到 ai_run，receipt/result/failure/cancellation 幂等收口。允许在 `AiExecutionService` 增加受控外部 BYOK preparation 分支，复用既有运行/并发/终态服务；显式输入冻结的 model/capability/credentialRef/owner/估价，服务器验证其来自已批准计划，禁止前端直传已解密 key。不得用既有按默认模型路由的 prepare 方法错误替换已选 Hypit 模型。

本任务外部 Hypit 模型仅使用部署者授权账户/BYOK，**不新增平台积分商品或扣费逻辑**；既有 y-1 文本流计费行为不改。Provider 实际费用、估价、未知费用分别记录，缺报价为 null，不记 0。生成授权绑定 revision、模型、替换范围、预算和批次数，已覆盖的运行不反复确认；超范围才要求新决定。preview、check、doctor 默认不得发起付费生成，pricing 可能联网查询和刷新 OAuth，要如实显示。

CLI 部署操作保留原样；由网页/Agent 发起的所有远程生成、转写均经 bridge。不得把 runtime host 的通用内部调用接口开放给浏览器以绕过此门禁。

授权 bridge 与 `publicAssetUrl` 是**宿主注入点**，不是让用户在 Profile JSON 中写函数。C107-07 通过版本绑定的 runtime endpoint activation 补丁向原生 factory 提供 callback，并装饰所有远程 invoke/submit 入口（包含即时 transcribe、自定义 Provider 和独立 Worker）。原 mapping/supports/请求编码/轮询/collect 原样调用，不能另写六套简化 Provider。进程间绑定以稳定 command/operation/grant 引用传递，回调实现从可信宿主模块加载，不序列化函数进 Profile。未取得许可时任何 remote submit 的计数必须为零。

OAuth 网页适配继续使用原生 PKCE/token 格式与 refresh 路径：容器内创建授权 flow、返回登录 URL；托管回调无法访问容器 loopback 时，用户可把服务显示的 `code#state` 粘贴回本模块的受权 flow 输入框，服务器验证一次性 state/有效期后在原 flow 内完成 token exchange。禁止把容器 localhost URL 当用户回调地址；不需要修改供应商注册 redirect URI。临时 verifier/code/state 不入通用日志和工程文件；提供 flow 取消/过期释放。
### D-09 工程快照、来源与复用
每次应用变更形成 revision（源文件、组件包实现/锁文件、素材引用、参数、Profile/能力版本摘要）。Build 绑定只读工程快照与冻结计划，不能让保存中的文件改变正在执行的工程。Result Repository 是跨 revision 稳定的 project-owned 仓库，不放进各只读 snapshot；保存原生 Outputs/资源引用；跨版本复用显式写 `build-record`/`satisfy`，禁止暗中把历史文件当缓存。Result 来源不得随活动 Profile 变化而迁移。

Studio 编辑面绑定可写的工程 head，保存使用预期 hash/CAS 并创建新 revision；预览或 Build 绑定只读 snapshot，查看历史 revision 时默认只读，需要“基于此版本编辑”才能生成新的 head。不能把只读 Build 快照误当 Studio 保存目标。

工程导出保留 Brief/Analysis/Timeline/Treatment/Progress、Source/Run/Recipes、组件包、选用素材和可复用 Results；默认排除凭据、缓存、node_modules、锁票据和运行 SQLite，产物引用需能在新目录恢复。S3 使用上游 Build Result S3 adapter，不把 resource-store-s3 当 Runtime Profile 配置选项。

### K10.4 C02 必须交付的 IPC 与部署最小闭环（NEW）
- 文件按 K04 的 runner 四模块落地。可信服务 `hypit-backend` 与隔离服务 `hypit-author-runner` 分开；初始固定 1 个执行槽，不动态调用 Docker API。槽占用由 broker 持久记录 commandId，其他任务排队；扩槽时每槽独立卷，不能共享可读工程集合。
- runner `network_mode: none`、非 root、只读 rootfs、cap_drop ALL、no-new-privileges，自己的有配额 scratch；唯一通信是专用 Unix domain socket。broker 连接 runner listener 后发消息，runner 没有可主动访问的 broker HTTP 服务。runner 不挂 PG、Runtime 凭据、全部项目仓库或任意宿主目录。
- broker 只把本次输入闭包复制到槽专用 input（runner 只读挂载）；output/scratch 为此槽独立卷。开始下一任务前杀掉旧进程树并清空槽，验证无旧文件；运行期间不能替换正在读的输入。secret 不经 env、argv、文件或 IPC 下发。
- 帧格式为长度前缀 UTF-8 JSON，单帧最大 1MiB；消息 `{v:1,commandId,requestId,kind,payload}`，响应 `{v:1,commandId,requestId,ok,result,error}`，进度另用 `kind=event`。二进制为槽内受限 handle，不 base64 塞大文件。错误不返回堆栈中的宿主路径。
- kind 白名单 `check/plan/compile/executeLocal/capture/status/cancel`；runner 子请求仅 `need.request`。broker 不相信 runner 声称的 owner/grant/价格：从当前 command 的冻结计划与 PG scope 找对应 needId，核对 capability/model/input hash，并走 K12；任意 URL、文件路径、未知 Need 不转发。
- 编译产物跨进程使用固定版本上游已有的可序列化图/目录描述；包含函数的部分留在 runner，通过句柄引用，不 JSON.stringify 函数。C02 对实际类型编写 encode/decode 往返测试，缺少导出则加最小补丁，记录上游符号与测试。不能另造一个缩水 compiler。
- 网页 capture 的外部资源经可信侧 URL 策略检查后进入限定的资源转发通道；redirect/DNS/请求目标逐次校验，不给 runner 通用外网出口。fixture local-web 仅测试配置允许其固定测试地址。网络失败保留 capture 诊断，不能静默取消隔离。
- 本地调试也用该 runner 服务执行网页提交的作者代码；直接 CLI 是独立可信开发流程。C02 先做无 secret/跨项目不可读/拒绝未知 IPC/真实本地渲染，C07 加授权 Need，C11 加 capture，C23 做部署恢复与资源压力。未过隔离测试不得将网页自定义包标 ready。

### 3.1 端到端接线与职责

以下路径为本任务 NEW 接线，现状核验见 §2；目录缩写按 §2.3 展开，具体可写文件仍以 W 组为准。

| 接线段 | 入口/调用方 → 实现 | 注册、持久化与边界 | 结果消费与验证 |
|---|---|---|---|
| 源码与启动 | C01 manifest → C02 build-107-engine.sh → B/src/main.mjs → engine/hypit-bootstrap.ts | 导出 U、重放 G、register/resolver 后装配 EnginePort；私有 helper 不替代注册 | C02 bootstrap/patch-replay 测试、真实本地 MP4；C03 SidecarClient 消费 |
| 公开工程 API | Edge application.yml/UpstreamResolver → J/api/HypitProjectController → HypitAccessService → project/command service | Caller/owner 校验 → PG command/outbox → B/commands/dispatcher → workspace journal | C04 保存后 API 重读 revision/manifest；C08 plan 读冻结 snapshot |
| 素材与计划 | HypitAssetController/工具入口 → B/src/tools/media.ts/speech.ts → 受限 runner；工程 → EnginePort.check/plan | 归属/路径/媒体校验，素材持久化；计划保留原生 Need、Profile 和价格快照 | C05/06 实际媒体与转写，C08 planId/pricing；C09 提交消费 |
| 外部执行门禁 | J/execution/HypitExternalExecutionBridge ↔ B/src/providers/authorization.ts/activation-hooks.ts | Java 授权与 ai_run → broker 回调 → 原 Provider → receipt/预算收口 | C07 fixture adapter 的真实 submit 计数/拒绝反例；C09 和即时转写复用 |

决策来源：本书 D 项沿用 v3.0.0 规划；采用理由、边界和替代限制写在对应 D/K。主程可选择不影响契约的局部实现，不得用新架构绕开隔离、Java 业务归属、授权或原生功能。

## 4. 目标行为与状态

### 4.1 用户流程与变化

用户流程按 §1.7 SC-107-01/02；现状是 §2.5 仅有上游导入，未形成业务模块，目标是同一工程从导入、保存到冻结计划均可读回。业务/异步状态见 §4.3/4.4；页面状态唯一由 107-3 §4.3 定义。

### 4.2 行为变化

| 触发 | 当前 | 本阶段目标 | 后续消费 |
|---|---|---|---|
| 创建/保存工程 | 无 Hypit 持久工程 | 有 owner/revision/command 的工程 API；冲突不覆盖 | C09/12/21 |
| 检查/计划 | 无统一 Hypit 调用链 | 原生编译、Need、价格及明确复用；不自动生成 | C09/16/17 |
| 配置/授权 | 现有 AI 执行链无 Hypit 适配 | 复用 Java 执行门禁，原 Provider 保留 | C09/14/19 |

### 4.3 业务与异步状态（唯一枚举）

| 对象 | 状态 | 语义 |
|---|---|---|
| 工程 | provisioning / ready / deleting / deleted / provisioning_failed | 文件工作区与 PG 的补偿状态，不假装跨进程事务 |
| Java 创作/工具任务 | queued / running / waiting_input / cancel_requested / succeeded / failed / cancelled | waiting_input 包括缺素材/配置、方案待应用、预算外扩；保存 checkpoint |
| Hypit 执行视图 | submitting / active / execution_decided / result_pending / finished / submission_incomplete | 原生生命周期投影；active 的阻塞原因单列 |
| Hypit outcome | null / complete / failed / cancelled | 独立字段；complete + result_pending 仍不能宣称所有结果已持久 |
| 导出归档 | pending / archiving / archived / failed | 独立于 Build outcome，可恢复且不重新生成 |
| Provider 可用性 | installed / configured / prepared / ready / unsupported / unavailable | 分字段保留配置、认证、模型支持、程序就绪；不把 installed 当 ready |

取消只停止待发工作，对已发送任务执行上游 best-effort cancel；界面同时展示本地取消和远程取消回执。失败/取消保留有用 Outputs。`result finish` 只补结果写入，`discard` 仅用于从未 active 的不完整提交，服务端必须校验适用状态。

未登录走既有登录流；非本人资源统一 404；未启用有引导；缺凭据保留方案与本地成果；不支持的模型参数解释具体组合，不偷偷换模型。保存冲突 409 展示差异，不覆盖。刷新恢复 project/run/revision/job；SSE 断线按最后 eventId 恢复，无法续接时先快照再订阅。删除有活跃工作时 409 引导先取消，不能删除 Worker 正在使用的目录。
### 4.4 状态迁移与副作用

| 对象/触发 | 允许迁移与确定动作 | 拒绝/恢复与证据 |
|---|---|---|
| 工程创建 | provisioning → ready；建立目录与 PG 回执均成功才 ready | 失败为 provisioning_failed；同 command 有界重试，不新建第二工程；TC107-04-01/04 |
| 保存/应用 | ready 下按 K06.3 校验 revision 后更新 head；普通 save 可带诊断 | 409 不移动 head，validated 编译失败保留 draft；TC107-04-02/03 |
| Job 启动/等输入 | queued → running；缺材料/授权/预算或达到 K11 上限进入 waiting_input | 补齐条件后显式 resume；新步骤仍校验 scope，页面重开不自动扩大权限；TC107-14-01/02、TC107-16-02 |
| Job 终结/取消 | running → succeeded/failed；取消先 cancel_requested，按实际执行与回执收敛 | 终态不得被迟到事件覆盖，已完成产物保留；TC107-09-03、TC107-14-03 |
| Build | submitting → 原生 active/后续生命周期；按实际 outcome 与 resultReady 分别投影 | 原生决定但结果未持久为 result_pending；finish 仅补写，unknown 不重提；TC107-09-02/04、TC107-10-02 |
| 归档 | pending → archiving → archived；归档失败单独 failed | 重试只补归档并返回同 mediaId，不重新 Build；TC107-10-03 |
| 删除 | 先关闭新副作用/撤销会话；无活跃工作且引用可保留/物化后 ready → deleting → deleted | 活跃工作返回 409 并先取消；清理失败不冒充 deleted；TC107-04-04、TC107-23-02 |

迁移不是新增宽松状态机；命令、action、execution、variant 的持久字段仍以 K05 为准，原生 Runtime 不能强行折叠为 Java Job。并发采用 K06 的 lease/CAS/事件序号，不让 UI 根据文本或 HTTP 202 自行宣告成功。

## 5. 业务规则与不变量

### 5.1 边界

| 输入 | 规则 |
|---|---|
| 标题、Brief | 标题 trim 1–60 字；Brief 1–10000 字；超限 400，不截断偷偷改意图 |
| requestId | 所有有副作用创建/执行请求必填 UUID；同 owner + action + requestId 幂等，相同键不同 body 返回 409 |
| 工程文件 | workspace 相对路径，规范化与 realpath 边界检查；拒绝绝对路径、穿越、NUL、符号链接逃逸；文件保存 ≤2MiB、一次变更集 ≤16MiB |
| 文件类别 | `.svml/.svs/.svrun/.md/.json/.ts/.js/.mjs/.cjs/.css/.html` 为作者文件；包资源/图片/音视频/字体独立二进制通道；锁文件通过包管理工具维护 |
| 参考/媒体导入 | 默认单文件 ≤500MiB；流式限额与实际 MIME/probe；文件名只作展示，不作磁盘键；长片分段分析，不硬编码仅支持短视频 |
| 工程包 | 默认压缩包 ≤1GiB，解压总量 ≤4GiB、≤20000 文件；拒绝 zip/tar slip、符号链接、设备文件、压缩炸弹；导入前列清单，构建只运行受控包工具 |
| 参考链接 | HTTP(S)；按既有 URL 安全策略逐跳处理地址和重定向；不接受 shell 参数、file:// 或任意本机路径；平台挑战/登录需求如实报告 |
| 时间/画幅 | 有限非负时间；end>start；时间窗在素材内；fps 可为正有理数；最终渲染区间 `[startFrame,endFrameExclusive)`，不能用浮点秒替代帧约束 |
| 批量变体 | 单批默认 ≤100，分页展示；生成前冻结每项参数/Run/预算；提交与重试使用不同明确 attemptId |
| Provider/程序 | 配置界面与执行界面分离；允许上游全部 schema 字段，服务端校验包/程序路径/凭据引用；密钥只走写入通道，不在通用 Profile JSON 回显 |

### 5.2 归属与资源访问

个人工程 owner=已验证 accountId，organizationId 可空。当前个人用途不建立商业租户管理，但仍必须项目隔离。账号 B 不能读账号 A 的源文件、Studio、SSE、Outputs、预览材料或原始媒体。

Workspace 资产读写、远程引用下载、浏览器 capture、打包导入各自独立做路径/网络限制。执行容器不得挂宿主 home、Docker socket 或源码仓库写权限；生成组件与 capture 脚本只获得该项目和批准工具。Provider 的凭据不传入 preview HTML/浏览器程序。

### 5.3 跨进程一致性

- provision：先落 provisioning + outbox 命令，sidecar 按 projectId 幂等建目录，回执后 ready；失败可重试，不用 Java 数据库回滚冒充文件操作回滚。
- Build：先记录提交意图、owner、revision、requestId，再派发；响应丢失通过稳定 commandId 查已提交 build，禁止重复发起生成。
- 保存：原子替换 + revision CAS；Studio 和普通编辑共用 revision 通知，不允许两个编辑器覆盖。
- 归档：服务端轮询/事件同步终态后投递；唯一 `(buildId,outputName)`，确定性 media UUID；媒体已存但关联未写时重查并补关联，不能再 insert 冲突行。
- 删除：先拒绝新命令/撤销会话，等待活跃任务结束，再清理可删工程；被其他 Run 显式复用的结果必须保留/物化，不能断开引用。
- 外部请求已提交但回执未知：记录 unknown，不自动重派；状态查询、接受已知产物、明确新 attempt 分开。
- 备份涵盖 PG 元数据、workspace/revisions、Result Repository、Profile/包锁和独立凭据备份；原生 Runtime SQLite 仅活跃状态，不能作为历史备份替代；额外备份 sidecar 的 bridge.sqlite 命令/文件 journal，并与 PG 回执对账。
### 5.4 稳定规则索引（全系列唯一维护）

下列 RULE 编号连接原有契约，不新增同名字段或第二套判断。C/REQ/AC/TC 沿用系列编号；E 是边界目录，F 是全量功能，K 是精确契约定位，不能互相替代。

| 规则 | 唯一判断位置 | 负责卡 |
|---|---|---|
| RULE-107-01 | §5.1 输入域、长度、文件、URL、批次；K07/K08 参数与互斥 | C03/04/05/06/08/16/19/20/21 |
| RULE-107-02 | §5.2 归属、K09 身份/路由/传输；107-2 K10 会话隔离 | C02/03/04/05/07/09/10/11/12/14/20/22 |
| RULE-107-03 | §5.3、K06 稳定命令、幂等、CAS、lease、journal | C04/09/10/12/17/18/19/23 |
| RULE-107-04 | §4 业务状态与 §7 K05 数据状态；107-3 §4.3 UI 投影 | C04/09/10/14/19/21 |
| RULE-107-05 | §3 D-06、§6 K12 授权、ai_run、未知费用与回执 | C07/08/09/14/16/18/19 |
| RULE-107-06 | §3 D-09、§5.3/§7 引用保留、归档、删除与恢复 | C04/10/20/23 |
| RULE-107-07 | §3 K10.4、107-2 K10.3 作者代码/预览隔离 | C02/07/11/12/17/23 |
| RULE-107-08 | 107-2 §6 K11 结构化 Agent、分析覆盖、方案、审片上限 | C14/15/16/17/18 |
| RULE-107-09 | 107-3 §8 交互/焦点/主题，§9.5 分层与生命周期 | C13/21/22/24 |
| RULE-107-10 | §12.2/§12.3 证据层级；107-3 §12.5/附 D 全量出口 | 全部卡；C24 最终核销 |

## 6. 接口与函数契约

### 6.1 统一规则

所有公开业务接口位于 `/api/hypit`，经 edge → intelligence。成功 `{success:true,data:T}`；错误 `{success:false,error:string,code:string,details?:object}`，兼容既有 error 文本习惯。字节流/SSE 除外。C107-03 将下表落实到 `contracts/hypit-api.v1.json` 与 `src/types/hypit.ts`，契约必须列出 method/path/request/response/error/flag/owner/test，edge 从具体路径清单注册，禁止宽泛放行 `/api/hypit/**`。

通用 DTO：`Project{id,ownerAccountId,title,mode,status,revision,version,selectedRun,createdAt,updatedAt}`；`Job{id,projectId,kind,state,checkpoint,progress,message,actions,outputs,errorCode,createdAt,updatedAt}`；`FileChange{path,action:put|delete,content?,baseHash?}`；`Build{id,engineBuildId?,projectId,revision,runFile,lifecycle,outcome,operations,receiptSummary,resultLocation,outputs,archiveState}`。返回 owner 仅用于本人资源展示，不接受调用者提交 owner。

列表 `{items,nextCursor}`，默认 20、最大 100，cursor 不接收原始 SQL。诊断为 `{file,line,column,severity,message}`（编译器无位置时为 null，不捏造行号）；成本 `{amount:null|string,currency,source,estimated,unknownReason?}`。

金额字符串以十进制表示，最多 6 位小数，PG numeric(20,6)/Java BigDecimal，不跨币种自动求和。公共 Build id 是提交前创建的 UUID，原生 engineBuildId 单独保存并由服务端翻译复用引用。异步返回 `AcceptedJob{jobId,state,resourceId}`；项目创建返回 `{project,job}`，Build 创建返回 `{build,job}`，均为 202。JSON 写操作使用 requestId；DELETE、multipart、纯字节请求统一使用 Idempotency-Key header。字段与条件校验见细则 K07。

### 6.2 公开业务 API（NEW，须实现）

`P` 表示 `/api/hypit/projects/{projectId}`，`B` 表示 `/api/hypit/builds/{buildId}`。下表每个 method 均为独立路由；花括号是路由模板参数；C03 必须扩展当前不支持模板的 UpstreamResolver，仅在 exact:true 分支启用整段命名匹配，并保留旧路由行为。

| API | 请求要点 | 返回/行为 | 卡 |
|---|---|---|---|
| GET `/capabilities` | 无 | enabled、version、feature readiness、templates；引擎未部署仍 200 enabled=false，不返回 secret | 03 |
| GET/POST `/projects` | POST `{requestId,title,mode:clone\|brief\|template\|import,templateId?,sourceContext?}` | 列表 / 202 `{project,job}`（provisioning） | 04 |
| GET/PATCH/DELETE `P` | PATCH requestId/title/baseVersion；DELETE Idempotency-Key | Project / 软删任务；活跃工程删除 409 | 04 |
| GET `P/files` | path 可选 | 文件树、hash、revision；不列凭据 | 04 |
| GET `P/file` | query path | UTF-8 `{path,content,hash,revision}`；二进制走 assets | 04 |
| POST `P/changesets` | `{requestId,baseRevision,applyMode:save\|validated,changes:FileChange[]}` | 草稿 changesetId/check 结果；不改生效工程 | 04 |
| POST `P/changesets/{id}/apply` | `{requestId,baseRevision}` | journal 原子提交 revision，过期 409；save 可保留错误并返回诊断，validated 必须 check 通过 | 04 |
| GET/POST `P/assets` | multipart file 或 JSON `{mediaId,role,requestId}`；Content-Type 区分 | 列表 / 素材导入任务；role=reference/portrait/product/voice/music/footage/font/other | 05 |
| GET/DELETE `P/assets/{assetId}` | DELETE Idempotency-Key | 元信息/依赖阻止删除 | 05 |
| GET `P/assets/{assetId}/content` | Range/If-Range | 200/206/416；由 Java 鉴权流式代理或受控 presign | 05 |
| POST `P/import-url` | `{requestId,url,role}` | 抓取任务；失败保留原因 | 05 |
| POST `P/tools/{tool}` | `{requestId,input}`，tool 枚举见 §6.4 | 202 AcceptedJob；不返回任意本地路径 | 05/06/11/17 |
| GET/POST `P/agent-jobs` | POST `{requestId,intent,brief,assetIds,baseRevision,scope}` | intent=analyze/plan/author/review/revise；202 AcceptedJob | 14–18 |
| GET/POST `P/jobs/{jobId}/actions` | POST `{requestId,action:resume\|cancel,input?}` | 任务 action 日志 / 更新状态；不能以 resume 扩张原预算 | 14 |
| GET `P/jobs/{jobId}` | 无 | Job | 03/14 |
| GET `P/jobs/{jobId}/events` | Last-Event-ID | SSE，授权后逐事件传输 | 03 |
| GET `/jobs/{jobId}`、GET `/jobs/{jobId}/events` | 无 / Last-Event-ID | 统一 Job 查询/SSE；项目 Job 校验 owner，全局 Job 校验 operator 与提交 account | 03/14 |
| GET/POST `/jobs/{jobId}/actions` | POST requestId/action/input；同项目端点 schema | 统一动作日志/恢复/取消；项目路径是额外校验 projectId 的别名，不另存任务 | 03/14 |
| GET/PUT `P/clone-plan` | PUT `{requestId,baseRevision,plan}` | ClonePlan，修改清除过期执行授权 | 16 |
| POST `P/check` | `{entryFile,revision}` | Author/Run/SVS 检查结果；不执行生成 | 08 |
| POST `P/plan` | `{runFile,revision}` | planId、hash、targets、needs、reuse、missingCapabilities | 08 |
| POST `P/pricing` | `{planId}` | pricingId、exact request 估价、币种、未知项；不得生成 | 08 |
| POST `P/execution-grants` | `{requestId,planId?,pricingId?,scope,maxCost,currency,allowUnknownCost,variantCount,expiresAt}` | 绑定 owner/revision/scope；即时转写可无 plan，Build 必须有 plan；不含凭据 | 07/16 |
| GET/POST `P/builds` | POST `{requestId,planId,grantId?,title?}` | 列表 / 202 `{build,job}`；确定性本地计划不需远程授权 | 09 |
| GET `B`、GET `B/events`、GET `B/logs` | 日志 cursor/limit | 快照 / SSE / 脱敏日志 | 09 |
| POST `B/cancel` | `{requestId,reason?}` | cancel_requested/现有终态；重复无新副作用 | 09 |
| POST `B/result-actions` | `{requestId,action:finish\|discard}` | 结果补写任务；不触发生成 | 10 |
| PATCH `B/presentation` | `{requestId,baseHash,title?,note?,highlightedOutputs?,outputDisplayNames?}` | 更新原 Result 展示元信息，不改 output identity | 10 |
| GET `B/outputs`、GET `B/output` | output query name | 完整 Output 类型/引用 / Scalar JSON、Resource 字节、Composite 打包下载 | 10 |
| POST `B/archive` | `{requestId,outputNames}` | 各 Output 归档状态；成功/失败/取消的可用 Output 均可归档 | 10 |
| GET `P/output-history` | name/source/cursor | 跨 Build 指定 Output 的历史 | 10 |
| POST `P/reuse` | `{requestId,baseRevision,runFile,selections:[{buildId,outputName,target}]}` | 显式 Candidate 变更集；应用后 plan 可核验新请求减少 | 10 |
| POST `P/preview-sessions` | `{requestId,runFile,revision}` | sessionId/ticketUrl/expiresAt 与空间/时间、缺失 Needs；不隐式 build | 11 |
| POST `P/studio-sessions` | `{requestId,runFile,revision,readOnly?}` | sessionId/ticketUrl/expiresAt；一次性 ticket URL；同 Run 默认复用活跃会话 | 12/13 |
| GET/POST `P/feedback` | POST requestId/baseHash/action/comment；上游 FEEDBACK.json 语义 | 时间点评论列表/增改删/resolve/reopen；独立 feedbackHash，不增源码 revision；不放第二份评论真相 | 18 |
| GET/POST `P/variants` | POST `{requestId,baseRevision,axes,variants}` | 子 Run/参数差异、批次任务；创建不等于收费执行 | 19 |
| POST `P/variants/{id}/build` | `{requestId,planId,grantId?}` | 独立 child Build；部分成功可保留 | 19 |
| GET `/templates`、GET `/templates/{id}` | 无 | 本地模板+官方示例/变体，依赖与材料可用性 | 20 |
| POST `P/export`、POST `/imports` | 导出 options/requestId；导入 multipart | 工程包任务 / 新 Project(provisioning) | 20 |
| GET `/knowledge`、GET `/vocabulary` | q/package/surface/cursor | 版本化 Skill 文档与真实模块/模型/Surface schema | 14/17 |
| GET `/runtime`、GET/PUT `/runtime/profile` | PUT `{requestId,profileId,baseHash,profile}` | 已配置/运行事实，受权部署设置 | 07/23 |
| POST `/runtime/actions` | `{requestId,action:init\|use\|unset\|up\|down\|doctor,profileId?,endpointIds?,expectedActivityHash?}` | 任务或诊断；down 有活跃工作且无匹配 expectedActivityHash 时 409 返回影响清单 | 07/23 |
| GET `/runtime/activity`、GET `/runtime/logs`、GET `/runtime/paths` | cursor/limit | 脱敏运行/容量/逻辑路径；真实宿主路径只向部署者显示 | 09/23 |
| POST `/runtime/programs/{action}` | action=prepare/up/down/status；requestId/endpointIds | 各 Program 的准备/启动/加载/健康/日志状态 | 06/23 |
| GET/PUT/DELETE `/runtime/credentials/{endpoint}/{slot}` | PUT requestId/kind/secret 或 auth flow 引用；DELETE Idempotency-Key，禁止日志记录 | GET 仅 configured/type；API key 与上游 OAuth 均保留 | 07 |
| POST `/runtime/auth-flows`、GET `/runtime/auth-flows/{id}` | endpoint/slot/requestId | HypiHub OAuth 发起/进度，返回授权 URL；PKCE/state 绑定 | 07 |
| POST `/runtime/auth-flows/{id}/complete`、DELETE `/runtime/auth-flows/{id}` | POST `{requestId,codeAndState}`；DELETE Idempotency-Key | 受权用户提交 `code#state`，原 flow token exchange / 取消；值不记日志 | 07 |
| GET `/runtime/packages`、POST `/runtime/packages` | POST requestId/specifier/exactVersion/scope/projectId? | status/install 受控部署操作，不能覆盖 vendor namespace | 17/23 |

全局 runtime/Profile/凭据/Program/package 写操作限部署管理账号；普通工程用户可以读取脱敏 readiness 并为自己的工程选已授权 Profile。部署管理账号通过 `HYPIT_OPERATOR_ACCOUNT_IDS` 服务端配置，不能用“已登录”替代。项目 Provider 包可存在于工程，但执行前同样校验部署允许的端点及授权。

### 6.3 内部协议与事件

内部统一 `/internal/v1/commands`：`{commandId,projectId?,workspaceId?,revision?,kind,payload,authorizationRef?}`。kind 必须来自 §6.2/6.4 的执行枚举；返回 `{commandId,state,result?,engineBuildId?}`；`GET /internal/v1/commands/{id}` 查询幂等结果，`GET .../{id}/events` 订阅；`GET /internal/v1/resources/{handle}` 提供 Range。内部消息不接受任意 shell 或任意磁盘根。

sidecar → Java 的外部 Need 授权/结果回执使用 `/internal/hypit/executions/{prepare,complete,fail,cancel}`，内部 token、稳定 operationId，绑定 command/project/revision/grant。prepare 的响应是短时执行许可与凭据引用，不向浏览器曝光。按需传送给 runtime 的 secret 仅进程内/私有 store，不进入 job payload/PG outbox。授权丢失时停止新 submit；已发请求继续查询回执，不重复发起。

事件格式 `{id,sequence,type,projectId,jobId?,buildId?,at,data}`；type 为 snapshot/progress/checkpoint/output/diagnostic/terminal/heartbeat。事件持久或可由事实重建；Last-Event-ID 支持重连，旧游标返回 snapshot reset；终帧幂等。不能在 Java WebFlux 事件循环里阻塞 ffmpeg、Python、打包或文件大拷贝。

### 6.4 完整工具枚举

- `media.probe/cut/frames/tile/tiles/boundaries/fetch/prepare-fetch`：保留 at/range/every-frame/ranges/transcript/around/occurrence/padding/label-time 与分页拼图参数。
- `transcribe`：明确语言，返回词/字时间、原始证据和对齐错误；`measure`：文本/语言/语速估时，不假装是生成后真实时长。
- `capture.screenshot/run/install-browser`：网页/区域截图、受控脚本交互/录制、浏览器准备；网页素材采集与成片快照区分。
- `snapshot`：从已打开 Studio 或 HTML 取指定帧/范围/网格，不创建导出 Build。
- `packages.build/pack/install/status`：作者组件、Provider、Companion 的受控编译打包与工程安装；保留原生 CLI version/check/distribution 工具给部署运维，不自行更新固定 vendor。
- 图像变换/合成、抠图、媒体 Normalize/Transform/StillVideo/ExtractAudio/ExtractFrame 等使用真实图节点与 Build 执行，不另造低保真快捷 API；高级素材工具可生成对应临时 Run。

具体工具参数直接保留上游语义，C107-03 从当前命令解析与类型生成 JSON schema；“通用工具 input”不是任意对象透传。schema 未登记的 action 返回 400。

### 6.5 错误分类

401 `hypit_unauthenticated`；403 `hypit_operator_required`；404 `hypit_not_found`；400 `hypit_invalid_input/invalid_path/unsupported_action`；409 `hypit_revision_conflict/idempotency_conflict/active_work/reference_in_use`；413 `hypit_too_large`；422 `hypit_compile_failed/unsupported_capability/missing_material`；429 `hypit_capacity_exceeded`；503 `hypit_disabled/backend_unavailable`；502 `hypit_provider_failed`。远程请求超时回执未知以 execution.state=unknown、Job waiting_input/blockedReason 表达，不据 502 自动重提交。凭据缺失/预算外扩可进入 waiting_input，明确下一步；任务错误保留上游诊断但清除密钥、签名 URL 与宿主私有路径。

### K02. 关键澄清：v2.0 中不能留给执行者猜的地方
| 项目 | v2.1 固定决定 |
|---|---|
| 公共 Build id | 使用 y-1 UUID `id`，提交前创建；上游 `engineBuildId` 为独立 nullable TEXT UNIQUE。浏览器 `/builds/{buildId}` 始终用公共 UUID。原生 Run 复用用 engineBuildId，由后端解析转换 |
| pending Build | PG 先存 `submitting`；sidecar 先持久 command→engineId，调用原生 `RuntimeHostExecution.build({id,...})`。禁止调用每次随机新 ID 的 CLI build 代替幂等桥 |
| unknown | 是 `hypit_execution.state='unknown'` 及任务 blockedReason，不是偷偷新增 Java Job 新状态。Job 使用 waiting_input，Build 保留原生 outcome |
| 工程 apply | 在 sidecar 同项目串行文件事务 + journal；PG 用命令/修订回执最终收敛；不宣称跨 PG/文件系统 ACID |
| Studio 写回 | 上游最终源码写入与 feedback 写入必须挂相同宿主事务钩子；普通 HTTP 文件接口不是唯一写通道 |
| 普通保存 | 可以保存语法错误，保存后反馈诊断；只有执行/应用 AI“可执行草稿”要求 check 通过。不能让用户无法保存正在编写的一半代码 |
| Plan 准备 | 生成授权之前允许静态编译/支持检查/估价；真实远程调用包括早期 transcribe 必须有授权 scope，不强制提前存在完整成片 Run |
| Asset 与 Result | 原始素材持久，临时抽帧有明确所属 job；Result 引用图留在原生仓库，PG 保存索引；不只归档 MP4 |
| Edge 路径匹配 | 当前 UpstreamResolver 不理解 `{id}`。C03 增加仅对 `exact:true` 且含命名段的模板匹配，保留旧分支原语义；不能直接填 YAML 然后假定已匹配 |
| 流式归档 | ObjectStorageAdapter 现只有 byte[] put/get。大文件用现有 presignUpload + WebClient 有 Content-Length 的流式 PUT，不调用不存在的 putStream；小 MP4 可复用 bytes 方法 |
| Snapshot 与 Results | 快照仅冻结输入闭包；所有 revision 的 Results 指向稳定 project-owned 仓库；不得变成 `.revisions/17/.hypit/results` 后丢历史 |
| Template 初始化 | C04 先交付最小本地 fixture；C20 补全正式模板/全部官方例。capabilities 不在 C03 假报尚不存在的模板 ready |
| 契约冻结 | JSON schema 与 Java/TS 类型须在 C03 实现；后卡只能补对应实现，新增公开字段先同步契约和测试，不能互相猜响应 |
### K03. ID、金额、时间与通用字段
- Project/Job/Command/Revision row/Asset/Plan/Grant/Variant/public Build/Output/Execution id：UUID 字符串，小写标准形式；SQL uuid。accountId 为 TEXT，不转换 UUID。
- revision/sequence 使用 JS 安全整数 0–9007199254740991；SQL bigint + CHECK；首次 ready 工程 revision=1，provisioning revision=0。`baseRevision` 必填时不能默认成当前值。
- engineBuildId 由上游 `orderedBuildId` 生成并验证，不假定 `bld_` 前缀。公开 DTO 可返回它供诊断，不用于权限判断。
- 时间戳用 ISO-8601 UTC；SQL timestamptz；媒体秒数为有限非负 number，frameRate 为 `{numerator,denominator}` 正整数；帧区间是闭开区间。
- 金额采用 `{amount:string|null,currency:string,estimated:boolean,source:string,unknownReason:string|null}`，amount 为十进制字符串、最多 6 位小数；Java BigDecimal、PG numeric(20,6)，禁止用 JS 浮点累加预算。每币种分别小计，不自动换汇。
- secret/token/code/签名 URL 不能进 payloadHash 原文存储/事件/日志。相同 requestId 的敏感请求以专用摘要校验，摘要不可作为认证值。
- JSON 中“未知/尚无”用显式 null；空列表用 `[]`；响应禁止把 null 变成字符串 `"null"`。
- 诊断位置允许 null；编译失败 HTTP 422，正常 `check` 请求返回 200 `{ok:false,diagnostics}`；文件不存在404，参数错误400。这两个语义不能混用。
- `GET capabilities` 要求登录，部署禁用仍200 enabled=false；未登录依旧401，不能为探测绕过账户边界。

#### 公共响应与异步受理

```json
{"success":true,"data":{"jobId":"11111111-1111-4111-8111-111111111111","state":"queued","resourceId":null}}
```

所有 AcceptedJob 用 `GET /api/hypit/jobs/{jobId}` 与 `/events` 查询，用 `/actions` 读取动作和提交 resume/cancel。projectId 非空校验工程 owner；为空时校验 operator 且 accountId 等于任务提交者。项目路径 `P/jobs/{jobId}`、`/events`、`/actions` 复用同一 Service 并额外校验 projectId，不建立第二套任务。C03 同时注册两组路径，C14 复用；全局 Runtime/Program 任务因此也有可查询入口。

这是通用 `AcceptedJob`。Project create 返回 `{project:Project,job:AcceptedJob}`，Build create 返回 `{build:Build,job:AcceptedJob}`，其余长任务返回 AcceptedJob。统一202；GET 资源返回200。服务端 commandId 不直接充当 jobId。

```json
{"success":false,"error":"工程已更新，请先查看差异","code":"hypit_revision_conflict","details":{"expectedRevision":3,"actualRevision":4}}
```

错误详情只返回本人资源。Error handler 仅在 Hypit controller 范围内扩展，不改变旧 controller 的 JSON 格式。网络层与业务错误保留 status/code，前端重试依据 code，禁止解析中文 message。
### K04. 模块与交接文件（NEW）
| 层 | 必建入口 | 责任 |
|---|---|---|
| backend | `B/src/main.mjs`、`src/server.ts`、`src/config.ts` | 先 register/resolver 再 import server；配置校验/生命周期 |
| 隔离执行 | `B/src/runner/{protocol,client,server,supervisor}.ts`、`deploy/hypit/Dockerfile.runner` | typed IPC、独立作者代码进程、超时与清理；不能由 HTTP 宿主直接加载项目代码 |
| 引擎桥 | `B/src/engine/hypit-bootstrap.ts`、`engine-port.ts`、`compile-adapter.ts`、`runtime-adapter.ts` | 上游类型到稳定内部 DTO；无账号业务 |
| 文件域 | `B/src/workspace/{paths,manifest,transactions,revisions}.ts` | 路径、hash、原子 journal、快照 |
| 命令域 | `B/src/commands/{store,dispatcher,events}.ts` | sidecar SQLite 命令去重、执行/状态与观察 |
| 工具域 | `B/src/tools/{media,speech,capture,packages}.ts` | 白名单输入→真实工具，不拼 shell |
| Provider | `B/src/providers/{catalog,activation-hooks,authorization,asset-transport,credentials,oauth}.ts` | 原生 factory 注入、授权与引用传输 |
| Studio | `B/src/studio/{launcher,sessions,proxy,url-policy,mutation-bridge}.ts` | 前缀/鉴权/写回/回收；原生 UI 保留 |
| Java API | `J/api/Hypit{Project,Asset,Job,Build,Runtime,Studio,Knowledge}Controller.java` | DTO/Caller/owner→service；不塞业务循环 |
| Java 基础 | `J/client/HypitSidecarClient.java`、`J/config/HypitProperties.java`、`J/security/HypitAccessService.java` | 内部调用、配置、统一归属 |
| Java 数据 | `J/project/`、`J/job/`、`J/build/`、`J/asset/` | Service/Repository/Record，按责任拆类 |
| Java AI | `J/agent/{HypitAgentWorker,HypitAgentStepService,HypitToolDispatcher,HypitKnowledgeService}.java` | 持久循环、step 单调用、知识/工具 |
| Java 执行 | `J/execution/HypitExternalExecutionBridge.java` | 授权、ai_run、回执、预算/取消 |
| Java 归档 | `J/asset/HypitArchiveService.java`、media 包新增 `HypitMediaArchiveAdapter.java` | 归档幂等、原 storage/outbox |
| 前端 | `V/VideoCloneWorkbench.vue`、`V/useVideoCloneUrlState.ts`、`V/composables/hypit-api.ts` | 纯装配、URL、统一 API |
| 契约 | `contracts/hypit-{api,tools,clone-plan,coverage}.v1.json`、`src/types/hypit.ts` | 受版本约束的业务与工具 schema |

允许每目录增加内部小文件，但不能把全部实现塞到一个 `HypitService` 或一个 `runtime.ts`。Java I/O 使用 WebFlux 链；必要阻塞操作 boundedElastic；worker claim 从数据库驱动，禁止 controller 手动 subscribe 发射任务。
### K07. 公开 API 字段补齐
107-1 §6 表是端点全集；本节固定所有复合请求的字段。未列的 optional field 不得自行添加。契约文件采用 JSON Schema draft 2020-12；自定义 business payload `additionalProperties:false`。上游 Profile/model 参数用包 schema 校验，不粗暴禁止其合法字段。

通用 `requestId` 位于 JSON body；DELETE 与纯字节/multipart 上传使用 `Idempotency-Key` header，multipart 中也带同值 requestId 时必须一致；不在 DELETE body 里写不可预测的客户端约定。GET 无幂等键。

| 请求 | 必填字段与补充约束 |
|---|---|
| ProjectCreate | requestId/title/mode；template 模式必填 templateId；import 通过 imports 端点；sourceContext 可空，否则 `{kind:media\|analysis\|brief,id:string\|null,label:string\|null}` |
| ProjectPatch | requestId/baseVersion/title；这里只改元数据，不用 source revision 加锁；返回新 version |
| ChangesetCreate | requestId/baseRevision/changes/applyMode(save或validated)；changes 1–100项，每项 path/action/content/baseHash；delete 无content，新增 baseHash=null |
| ChangesetApply | requestId/baseRevision；changeset 所属 project 必须匹配；重复应用返回同 revision |
| AssetFromLibrary | requestId/mediaId/role；后台校验 media owner/状态；不接收客户端 objectKey |
| AssetUpload | Idempotency-Key/role/file；先持久 staging，再返回 job；Content-Length未知仍累计计数拒绝超限 |
| ImportUrl | requestId/url/role；role 默认 reference；下载目标由服务器生成 |
| ToolRequest | requestId/tool-specific input/baseRevision可空；输入 assetId/relative source，不接 raw host path |
| AgentJobCreate | requestId/intent/brief/assetIds/baseRevision/scope；scope字段见K11；intent analyze/plan/author/review/revise |
| AgentResume | requestId/action(resume或cancel)/input可空；input只允许 `{answer,selectedAssetIds,approvedChangesetId,grantId}`，不能嵌任意工具指令 |
| PlanPut | requestId/baseRevision/plan；新方案新增 revision，旧授权失效；不允许任意 endpoint URL |
| Check | entryFile/revision；path限制；revision应存在；返回 `{ok,diagnostics,exports,sourceClosureHash}` |
| Plan | runFile/revision；返回 `{planId,planHash,revision,targets,needs,reuse,missingCapabilities,repositoryLocationSummary}` |
| Pricing | planId；新增不可变 pricing snapshot，返回 pricingId/planHash/costs/unknowns；不修改原请求计划 |
| Grant | requestId/planId可空/pricingId可空/scope/maxCost/currency/allowUnknownCost/variantCount/expiresAt；即时转写scope必含 assetId/hash/duration/language；缺 planId 不能授权 build；pricingId 非空必须属于该 plan，scope 保留估价摘要；即时转写 pricingId=null |
| BuildCreate | requestId/planId/grantId可空/title可空；all-local无需grant；title 1–120字 |
| BuildCancel | requestId/reason可空(≤500字)；重复终态返回现有Build，不409 |
| ResultAction | requestId/action finish或discard；失败适用状态409，不自动转成build |
| Presentation | requestId/baseHash/title/note/highlightedOutputs/outputDisplayNames 各可选；清空使用null或[]，不省略混淆 |
| Archive | requestId/outputNames(非空数组)；返回 AcceptedJob，各Output状态由GET读取 |
| Reuse | requestId/baseRevision/runFile/selections；每项 `{buildId:公共UUID,outputName,target}`；服务端翻译成 engineBuildId/原生 Candidate |
| Preview/Studio | requestId/runFile/revision；Studio 可选 readOnly，历史默认true；返回 `{sessionId,ticketUrl,expiresAt,revision,runFile}` |
| FeedbackMutation | requestId/baseHash/action add或edit或delete或resolve或reopen/comment；comment `{id?,run,at,text?,resolved?}`；输入 field 按 action 条件校验 |
| VariantsCreate | requestId/baseRevision/axes/variants；axes允许 subject/product/script/language/aspect/style/voice/cta；variants 1–100，每项 `{clientKey,title,values}`，拒绝未声明axis |
| VariantBuild | requestId/planId/grantId可空；plan必须属于该variant revision；不能拿旧基础plan执行新变体 |
| Export | requestId/revision/includeAssets/includeResults；includeResults为selected或allReferenced；不能选择导出credentials |
| ImportProject | Idempotency-Key/file/title；验证通过才 provision 新 project |
| RuntimeProfilePut | requestId/profileId/baseHash/profile；profile中只有CredentialRef，不接secret；服务器控制dataRoot/host executable roots |
| RuntimeAction | requestId/action/profileId可空/endpointIds可空/expectedActivityHash可空；down有active且无匹配hash返回409影响清单 |
| ProgramAction | requestId/endpointIds；status允许空；prepare/up只管理已登记Programs，不接自定义shell |
| CredentialPut | requestId/kind(api_key或oauth)/secret；secret写后返回configured，不回显masked末位；oauth通过flow获得优先 |
| AuthFlowCreate/Complete | create requestId/endpoint/slot；complete requestId/codeAndState；flow归属验证+一次性 state+PKCE |
| PackageInstall | requestId/specifier/exactVersion/scope(project或host)/projectId可空；不允许latest、版本范围和覆盖vendor命名空间 |

#### DTO 的关键输出

- `Project`：id/title/mode/status/revision/version/selectedRun/sourceContext/createdAt/updatedAt；不向普通用户返回绝对 workspace path。
- `Job`：id/projectId/kind/state/phase/progress(`done,total,unit` 均可空)/checkpointSummary/blockedReason/nextActions/error/createdAt/updatedAt；详细 action 另接口分页。
- `Build`：id/engineBuildId可空/projectId/revision/planId/runFile/lifecycle/outcome/operations/receiptSummary/resultReady/archiveState/outputCount/createdAt/finishedAt；不把 complete 翻译成 Java job state。
- `Output`：id/buildId/name/displayName/kind/typeRef/mediaType/sizeBytes/durationSeconds/valueSummary/archiveState/mediaId/dependencies；kind非媒体也保留typeRef。真实下载使用受控链接/字节接口。
- `FeatureReadiness`：id/installed/configured/prepared/ready/reason/action；缺凭据显示configured=false，不能用“未安装”掩盖。
- 所有列表返回 items/nextCursor；GET logs固定最多1000条；默认100条；大段日志按事件分页，不能整文件返回。

HTTP200/201/202统一：纯同步创建 changeset201；异步业务命令202；读/幂等已完成结果200；重复未完成命令仍202。第一次与重放响应资源ID相同，状态可更新。
### K08. 工具参数如何无损适配
C03 创建 `contracts/hypit-tools.v1.json`，每工具包含 `id,upstreamEntry,inputSchema,outputSchema,executionClass,permission,sideEffects,costClass,sourceArguments,testCases`。工具参数从真实解析器抽取，一项 CLI 参数一行 sourceArguments，状态只有 exposed/serverManaged/rejectedWithReason，禁止 silent omission。

| 工具 | 网页核心字段 | 必须保留的高级参数/行为 |
|---|---|---|
| media.probe | assetId | 所有流/duration/size/frameRate/audio presence，不能只第一video流 |
| media.cut | assetId/start/end | labelTime、视频/音频有效区间；真实截取，不只返回原链接 |
| media.frames | assetId、at[] 或 start/end/every | everyFrame、labelTime、原始timestamp；互斥选择校验 |
| media.tile/tiles | assetId、range/ranges | every/everyFrame、columns/rows/cell、transcriptAssetId、around/occurrence/padding；返回分页材料IDs |
| media.boundaries | assetId | 上游阈值/区间参数，返回时间与score；边界是观测不是语义镜头结论 |
| media.fetch | url | 受控输出文件路径由服务器配置；协议/重定向/失败清晰 |
| media.prepare-fetch | 无或已登记环境选择 | 不返回环境秘密；准备有log/progress |
| transcribe | assetId/language | runtime/endpoint服务端选择；原始证据与标准词时间；支持中文字符 |
| measure | text/language | 支持语速/单位参数按真实CLI schema，返回估计与依据 |
| capture.screenshot | url | viewport、区域/selector、等待策略/输出格式等按原命令 schema；路径服务器管理 |
| capture.run | projectScriptPath/args | 脚本属于当前工程/版本、批准执行；不允许任意URL脚本、host脚本；截图/录制Outputs入asset |
| capture.install-browser | 无 | 固定capture版，不用latest |
| snapshot | previewSessionId 或项目HTML assetId | at/ranges/grid/分页/尺寸；端口/URL服务器生成，不能让浏览器指定内部URL |
| packages.build/pack | projectPackagePath/revision | 受控命令、超时、编译产物tarball；不执行任意用户传cmd |
| packages.install/status | specifier/exactVersion | 工程/host作用域、namespace隔离，原生status语义 |

这些是桥字段（NEW），不是把它们原样塞进上游 CLI。适配器显式转换为真实 argv/options，并用契约测试校验转换和输出。不能猜 `--viewport` 等参数一定存在；真实 parser 不支持的业务选项由宿主API组合实现并标明新增。

工具结果统一 `ToolResult{outputs:[{assetId,role,mediaType,summary}],data,diagnostics,executionRefs}`。工具耗时返回Job，通过GET取结果；不要让LLM读取巨大base64或所有视频字节，图片以受控多模态附件传递。
### K09. 鉴权、路由与传输的具体做法
#### K09.1 Edge 模板匹配

FACT：`E/UpstreamResolver.java` 当前 `exact=true` 为字符串相等；否则按前缀。直接写 `/api/hypit/projects/{projectId}` 不会匹配 UUID。

C03 在 `exact` 分支中增加 `matchesTemplate(routePath,path)`：

1. routePath 不含 `{` 时仍调用原 `path.equals(routePath)`，旧行为不变。
2. 配置模板只允许完整段 `{identifier}`，不允许半段或任意正则；启动时错误模板报配置错误。
3. path 按 `/` 分段，段数量必须一致；静态段逐字相等；参数段必须非空，禁止 `.`/`..`/反斜线/编码斜线或NUL。
4. method 仍先按原逻辑检查，模板分支不放宽 method。
5. UUID/id合法性由 controller schema 检查；未知子路径或多一级路径在edge404。
6. 每条主书API登记 method/path/exact:true/upstream:intelligence/enabled。capabilities flag 默认true，其他默认false；flag 命名 `EDGE_ROUTE_HYPIT_<RESOURCE>_<ACTION>`，契约 routes[]保存精确名，CI比对YAML。
7. 新增 `ET/HypitRoutesTest.java`、`UpstreamResolverTemplateTest.java`；运行全部edge测试验证旧前缀/精确路径语义未变。

不得把整个 `/api/hypit` prefix 全方法打开替代逐端点门禁。内部 sidecar/Java callback 不进入公网 edge routes。

#### K09.2 Java 身份与令牌

- controller 进入 resolve 一次，将已验证 Caller 显式传入 service；service 不再 resolve 同一头以免消费第二次jti。
- requireProjectOwner 在查workspace/build/job/output之前完成；资源级查找查询本身带 account_id/project_id。
- studio/session由Java创建，sidecar只接可信内部绑定；要主动撤销时Java投递`session.revoke`，网关每请求检测撤销标志，WS收到后关闭。
- 请求路径日志只记录参数化路由，票据路径/签名URL不得原样打日志。
- csrf适用既有edge规则；Node Studio网关检查外部 Origin 和session，再代理改写为内层localhost Origin，不把任意客户端头原样当可信。
- 新 Java internal callback 用单独 `HypitInternalAuthFilter` 路径精确匹配、内部token校验；不能为了接受callback把普通用户API的Caller验证关闭。

#### K09.3 SSE

`id: <jobUuid>:<sequence>`；data为本书§6.3的Event；heartbeat15秒，客户端45秒无事件触发status检查；网络重连1/2/4/8秒，上限10秒加抖动。更新页状态时忽略已处理sequence；terminal只落一次。

无游标先发snapshot再tail。游标不可用时发snapshot并带`reset:true`，前端清旧sequence重新跟随；资源归属失败404不循环重连。Nginx该路径禁止buffer/cache，超时长于heartbeat。前端卸载关闭EventSource/AbortController/timer，但不发送cancel。

#### K09.4 二进制与存储

1. 每resourceHandle绑定project/revision/job/result来源，随机handle不是权限凭据；读取仍检查owner或已授权session。
2. `Range: bytes=start-end` 支持单范围；合法返回206/Content-Range/Accept-Ranges；越界416；完整200。素材流必须响应取消并释放句柄。
3. 大文件归档先由sidecar返回长度/hash/MIME与受保护内容handle；Java取得稳定对象key和 `PresignRequest(key,mime,ttl,metadata,contentLength)`。
4. Java WebClient URI模式原样传签名URI，流式PUT，设置准确Content-Length/Content-Type；禁止bodyToMono(byte[])用于>200MiB文件；buffer及时释放。
5. PUT成功后head校验长度/MIME，并完成媒体行/outbox/Output关联事务。中断时head已存在且校验匹配则补DB；不匹配先报冲突，不覆盖未知数据。
6. key=`media/hypit/<account-scoped-id>/<outputUuid>`，media UUID来自提前创建的hypit_output.id；并发归档认领同Output；无需发明UUID字符串散列算法。
7. 小MP4复用原archiveGeneratedBytes时先查是否已存在同mediaId，调用前后保留上述claim/补偿，原方法本身不保证重复insert成功。
8. 原始上传使用 `MediaPurpose.USER_UPLOAD`，生成视频/图片使用 VIDEO_ASSET/CONTENT_ASSET（具体类型白名单沿原规则），工程包用受保护export资源下载，不强塞媒体库的图片/视频列表。
### K12. Provider 与预算桥实现约束
1. catalog由真实包metadata/mapping/支持schema构成。每model列capability、input modes、provider组合、local/remote、是否报价；不要靠README文字搜索推测support。
2. 原生Provider封装只注入trusted factory options（包括publicAssetUrl）与执行授权装饰器；原请求编码/poll/collect仍调用上游。对于package activation未提供callback参数，补丁增可信host服务入口，不扩展用户Profile任意代码。
3. 稳定operationId在Java对应一个requestHash/Need/attempt，prepare先持久ai_run再允许submit；轮询次数不增加生成计数。
4. `AiExecutionService.prepareMediaExecution` 已支持BYOK零平台成本/feature=null的路径，但现有ProviderResolution可能无法表达外置credentialRef。先复用可表达的分支；必要时新增 `prepareHypitExternalExecution` 明确适配，不能假称原方法直接支持任意Hypit endpoint。
5. 外部实际费用写hypit_execution，BYOK平台记账为0是平台成本事实；两者不同，UI同时标“第三方费用”和“平台积分”，不能把0展示为供应商免费。
6. grant校验owner/project/revision或即时asset哈希/endpoint/model/请求数/币种/预算。并发领取剩余额度用数据库锁或CAS，不能两次各自看同一余额超支。
7. 未报价请求只允许allowUnknownCost=true且scope限定次数/媒体时长；maxCost不能被谎称为供应商硬限额。新增或扩大请求需授权，既有范围内不逐工具反复弹窗。
8. remote submit timeout不确定是否被接受：unknown + receipt信息；若Provider自带Idempotency-Key保留同键查询/协议恢复，但不能重建新Need绕过。
9. publicAssetUrl只接受当前操作有权读取的BlobRef，上传到稳定临时对象，返回外网可取且有效期覆盖请求的HTTPS；凭据URL从UI/报告脱敏。测试实测Provider fixture取回相同字节。
10. 凭据新值仅写私有store；注销/轮换对运行中任务的影响显示清楚；env只读、OS在平台原生执行、platform策略保留。doctor不自动登录/花费/安装。
## 7. 数据模型、迁移与并发

### 7.1 intelligence 新表（V91 候选，实际实施防撞号）

统一沿用现有 accountId 的 String/TEXT 表示，不因 v1 的 `user_id UUID` 猜测账号类型。所有 JSONB 以版本化 schema 校验，不能用自由 JSON 隐藏关键状态和唯一约束。

| 表 | 关键字段 | 约束与职责 |
|---|---|---|
| `hypit_project` | UUID id、account_id TEXT、workspace_id UUID、title、mode、status、revision BIGINT、selected_run、source_context JSONB、created/updated/deleted_at | workspace 唯一；owner+更新时间索引；项目状态由补偿流程推进 |
| `hypit_command` | UUID id、account_id、project_id?、action、request_id UUID、payload_hash、state、result JSONB、error、timestamps | UNIQUE(account_id,action,request_id)；写入先于外部副作用 |
| `hypit_revision` | UUID id、project_id、number、parent_number、manifest_hash、snapshot_handle、created_by、created_at | UNIQUE(project_id,number)；素材引用单独记录，可验证快照完整性 |
| `hypit_asset` | UUID id、project_id、media_id?、resource_handle、role、origin_kind/url?、sha256、mime、size、probe JSONB、status | owner 经 project；URL 脱敏；不可用临时 media artifact id 作永久资源 |
| `hypit_job` | UUID id、project_id nullable、account_id TEXT、kind、state、checkpoint JSONB、base_revision、grant_id?、attempt、cancel_requested_at、error、timestamps | durable claim/version/lease，重启恢复未发阶段；agent/input/tool 轨迹有稳定 actionId |
| `hypit_job_event` | job_id、sequence BIGINT、type、payload JSONB、created_at | PRIMARY KEY(job_id,sequence)，支持 SSE 重连；无 secret |
| `hypit_plan` | UUID id、project_id、revision、run_file、plan_hash、profile_hash、targets/needs/reuse/pricing JSONB、created_at | 不变快照；hash 变化使旧授权不可用于新计划 |
| `hypit_pricing_snapshot` | UUID id、plan_id FK、pricing_hash、costs/unknowns JSONB、created_at | 只追加；UNIQUE(plan_id,pricing_hash)；授权保存所用 pricingId |
| `hypit_execution_grant` | UUID id、project_id、account_id、plan_id nullable、pricing_id nullable、scope JSONB、max_cost?、currency、allow_unknown、variant_count、expires_at、revoked_at | 缺价格无显式 allow_unknown 不可默认为无限；不得跨工程复用 |
| `hypit_build` | UUID id PK、TEXT engine_build_id nullable UNIQUE、UUID command_id、project_id、revision、plan_id、run_file、lifecycle、outcome、result_location JSONB、timestamps | command_id 唯一；提交即登记归属；失败/取消也在；原生终态权威在 Runtime/Result |
| `hypit_output` | UUID id、build_id UUID FK、output_name、kind、media_type?、resource_handle?、value_summary、media_id?、archive_state、size/duration? | UNIQUE(build_id,output_name)；Scalar/Resource/Composite，不限 final.video |
| `hypit_execution` | UUID operation_id、job_id?、build_id UUID nullable、need_id、ai_run_id UUID nullable、provider/model、request_hash、receipt JSONB、state、estimated/actual cost、currency | 每个真实付费/转写操作的授权与结果；不重复扣记；实际未知为 null |
| `hypit_changeset` | UUID id、project_id、command_id UNIQUE、base_revision、apply_mode、change_manifest_handle、check_status、state、applied_revision?、version | save/validated；CAS/journal 应用；详细状态见 K05 |
| `hypit_asset_reference` | project_id、revision、asset_id、relative_path、sha256 | PK(project_id,revision,relative_path)；快照依赖与删除保护 |
| `hypit_job_action` | UUID id、job_id、step_index、kind、state、input_hash、input/result JSONB、ai_run_id?、operation_id?、timestamps | UNIQUE(job_id,step_index)；LLM/工具/应用可恢复轨迹 |
| `hypit_variant` | UUID id、project_id、batch_job_id、base_revision、parameters JSONB、run_file、plan_id?、build_id?、state | UNIQUE(batch_job_id,id)；每项独立结果和重试 |

数据库 FK 与索引由 C107-04 一并落实；nullable buildId 支持尚未 Build 的 transcribe 等即时请求。工程 JSON/MD、FEEDBACK.json、Results 仍由上游格式负责，PG 保存索引/归属/业务状态，不抄第二份可编辑源码或评论正文作为真相。

### 7.2 一致性与迁移验收

V91 只新增表/索引，不修改 V1–V90。执行新库、V90 升级、重复迁移检查；DDL 按既有防重方式，不能静默吞 schema 不一致。项目命令落库与 outbox 同事务；副作用完成与状态更新具幂等。停用 overlay 和 flags 是回滚方法，默认保留所有新表/volume；不把 DROP TABLE 当常规回滚。

sidecar 独立 bridge.sqlite 保存 commands、file_transactions、session 索引（具体字段见 K05），不改上游 Runtime SQLite schema。文件写入先持久 journal，崩溃后按 hash 完成或回滚，PG 依回执收敛；不能把上游 best-effort rollback 当跨库原子事务。

工程锁只覆盖变更集提交或结果元信息修改，不能长时间锁住生成任务。崩溃恢复优先完成原 command；没有可证明回执则等待人工/服务状态确认，不重派未知付费请求。

### K05. 持久状态与数据库字段补全
以下字段是107-1§7数据表的补充约束，C04 必须生成完整 migration 并测试。所有非明确 nullable 的字段 NOT NULL；FK 默认 NO ACTION，不级联删除工程/审计记录。通用 `created_at/updated_at` 默认 now，更新时显式设置 updated_at。

| 表 | 必须落实的字段/唯一性（除107-1§7字段外） |
|---|---|
| hypit_project | account_id TEXT、version BIGINT DEFAULT1、head_manifest_hash TEXT nullable；mode CHECK clone/brief/template/import；status CHECK 107-1§4枚举；UNIQUE(id,account_id) |
| hypit_command | id UUID PK、target_key TEXT、action TEXT、request_id UUID、payload_hash char(64)、payload_json JSONB（脱敏）、state CHECK queued/dispatching/acknowledged/succeeded/failed/unknown、lease_owner UUID nullable、lease_until timestamptz nullable、attempt int DEFAULT0、result_json JSONB nullable、error_code/message nullable；UNIQUE(account_id,action,request_id) |
| hypit_revision | number bigint、parent_number bigint nullable、manifest_hash char(64)、snapshot_handle TEXT、command_id UUID UNIQUE；UNIQUE(project_id,number)；(project_id,parent_number) 自引用可空 |
| hypit_changeset（新增） | id UUID、project_id、command_id UNIQUE、base_revision bigint、apply_mode TEXT CHECK save/validated、change_manifest_handle TEXT、check_status CHECK not_checked/passed/failed、state CHECK draft/applying/applied/rejected/conflict、applied_revision bigint nullable、version bigint DEFAULT1、timestamps |
| hypit_asset | origin_kind CHECK upload/library/url/tool/generated/import；origin_url TEXT nullable（脱敏）、media_id UUID nullable、resource_handle TEXT、status CHECK importing/ready/failed/deleted；无全局 hash 唯一（不同 owner 不共享权限） |
| hypit_asset_reference（新增） | project_id UUID、revision bigint、asset_id UUID、relative_path TEXT、sha256 char(64)；PK(project_id,revision,relative_path)；显式引用表用于删除保护 |
| hypit_job | id UUID、command_id UUID UNIQUE、project_id UUID nullable（全局运维允许空）、account_id TEXT、kind TEXT（contract 枚举）、state 107-1§4枚举、phase TEXT、progress_json JSONB、checkpoint_json JSONB、step_index int DEFAULT0、attempt int DEFAULT1、lease_owner/lease_until、version bigint DEFAULT1、blocked_reason TEXT nullable、error_code/message nullable |
| hypit_job_action（新增） | id UUID、job_id UUID、step_index int、kind CHECK llm/tool/apply/approval/review、state CHECK prepared/dispatched/succeeded/failed/unknown/cancelled、input_hash char(64)、input_json/result_json JSONB nullable、ai_run_id UUID nullable、operation_id UUID nullable、started/finished_at nullable；UNIQUE(job_id,step_index) |
| hypit_job_event | job_id UUID、sequence bigint、type TEXT、payload JSONB、created_at；PK(job_id,sequence)；sequence 在锁住 job 的短事务内递增 |
| hypit_plan | id UUID、project_id、revision、run_file、plan_hash/profile_hash char(64)、plan_json/pricing_json JSONB、repository_location JSONB、created_at；旧 plan 不 UPDATE 为新计划 |
| hypit_pricing_snapshot（新增） | id UUID PK、plan_id UUID FK、pricing_hash char(64)、costs_json/unknowns_json JSONB、created_at timestamptz；只追加，UNIQUE(plan_id,pricing_hash)；重复查询同摘要返回原 id |
| hypit_execution_grant | id UUID、project_id、account_id、plan_id UUID nullable（即时转写可无）、pricing_id UUID nullable FK hypit_pricing_snapshot、scope_hash char(64)、scope_json JSONB、max_cost numeric(20,6) nullable、currency TEXT、allow_unknown boolean DEFAULTfalse、variant_count int、expires_at、revoked_at nullable；CHECK max_cost>=0 或null |
| hypit_build | **id UUID PK**、engine_build_id TEXT nullable UNIQUE、command_id UUID UNIQUE、project_id、revision、plan_id、run_file、lifecycle、outcome nullable、result_location JSONB nullable、submitted/finished_at nullable；删除 v2.0 的 engine_build_id PK 假设 |
| hypit_output | id UUID、**build_id UUID FK hypit_build(id)**、output_name TEXT、kind CHECK scalar/resource/composite、media_type nullable、resource_handle nullable、value_summary JSONB、archive_state CHECK pending/archiving/archived/failed、media_id UUID nullable、archive_command_id UUID nullable、error_code nullable；UNIQUE(build_id,output_name) |
| hypit_execution | operation_id UUID PK、job_id UUID nullable、build_id UUID nullable、grant_id UUID、need_id TEXT、ai_run_id UUID nullable、endpoint_id/capability/model TEXT、request_hash char(64)、state CHECK prepared/submitting/submitted/running/succeeded/failed/cancelled/unknown、receipt JSONB nullable、estimated_cost/actual_cost numeric(20,6) nullable、currency、provider_cancel_state TEXT nullable |
| hypit_variant | id UUID、project_id、batch_job_id、ordinal int、base_revision、parameters_json、run_file、plan_id/build_id nullable、state CHECK draft/planned/queued/running/succeeded/failed/cancelled、attempt int DEFAULT1；UNIQUE(batch_job_id,ordinal) |

索引固定：project(account_id,updated_at,id)；command(state,lease_until,created_at)；job(account_id,project_id,created_at,id)、job(state,lease_until)；build(project_id,created_at,id)；output(build_id)；execution(build_id)、execution(job_id)；asset(project_id,status)。FK 可用 composite owner 约束处优先在数据库防串属主，服务端仍做授权，不只依赖 FK。

Migration 位于 `JR/db/migration/V91__hypit_video_clone.sql`；若 V91 已被别人占用，读取最大合法 V 数字并选下一空号，记录实际文件路径，不能覆写现有 migration。SQL 中 CHECK/UNIQUE 创建使用仓库既有防重方式；重复执行既不报 duplicate object，也不能覆盖已有不同约束。用 PostgreSQL 验证，不用 H2。

#### sidecar 自己的持久化（不替代 Java 业务表）

`/data/hypit/host/bridge.sqlite`：只保存 commandId→kind/payloadHash/engineBuildId/state/resultHandle、workspace journal、session 索引及资源句柄映射。与 upstream Runtime SQLite 是不同文件；禁止往上游表加业务列。

Node 内建 `node:sqlite`；同进程写事务短，磁盘 busy 使用有界重试；大媒体字节不入 SQLite。令牌秘密只保存在私有 store/带 TTL 的内存或专用受保护文件，不进入普通 command JSON。
### K06. 幂等、租约与原子文件算法
#### K06.1 HTTP 写请求通用算法

1. Caller 校验一次 → requireOwner/requireOperator → schema/资源状态检查。
2. 计算 action（固定端点语义，如 `project.create`、`build.submit`）与 targetKey；canonical JSON 摘要包含 target、revision、实质参数，不包含不相关 requestId/展示时钟。
3. 短事务插入 command；唯一冲突后读取已存在行。hash 相同返回同资源/Job，hash 不同409，不执行任何副作用。
4. 需要异步时同事务插入 Job 与分发记录；返回202。执行者用 DB claim，进程崩溃可接手。
5. worker `FOR UPDATE SKIP LOCKED` 认领，lease 30s、每10s续租；长外部调用不持 DB 锁。失去 lease 禁止新副作用；已有 request 的结果按稳定 operationId 合并。
6. 纯读/本地确定性步骤失败最多重试3次（1/2/4秒）；已提交或未知的远程生成不自动重发，保存回执并查询。
7. 成功结果和完成事件同事务；重复回执只更新时间/补缺字段，不产生第二条 artifact 或重复 ai_run。

#### K06.2 sidecar 提交 Build

```text
accept(commandId,payloadHash)
  existing + same hash → 返回原 engineBuildId/state
  existing + different hash → conflict
  new → transaction: 分配原生 orderedBuildId，记录 preparing
  准备 read-only source snapshot + 固定 ResultRepositoryLocation
  runtime.build({id:保存过的 engineBuildId, definition, catalog, attachments, result})
  持久 received/active/result handle → 回 Java
```

`RuntimeHostExecution.build` 允许调用者指定 id 是 FACT。提交中崩溃后先查询该固定 id 的 pending/active/Result；确认未提交才重放同一 id；无法证明则 unknown，不能生成新 id“重试”。公共 build.id 自提交前已存在，浏览器可立即轮询，不依赖 engine 回包才有归属。

#### K06.3 多文件应用与 Studio

1. 项目工作区专用串行写锁；校验 baseRevision、各 baseHash、路径与总大小。
2. 将所有新内容写到事务临时目录，计算完整 manifest；所有失败在发布前返回，现有 head 不动。
3. durable journal 写 `prepared`（含旧/新 hash、备份位置、commandId）；逐文件原子 rename，journal 跟踪已发布项；禁止只靠内存 rollback。
4. 全部文件和 manifest 就绪后发布 head marker/new revision，journal committed；成功回执给 Java。Java 未应答时 sidecar journal 仍可重复查询收敛。
5. 崩溃恢复：未 committed 的批次按 journal 完成发布或回滚旧文件，恢复完成之前拒绝新写；不能一半新 Source 一半旧 Run 对外编译。
6. Studio Source、参数 mutation、语义 mutation 经过同一钩子；保留上游 UTF-16 offset/expected revision 校验；禁用绕过宿主的直接 write。编辑成功广播 headChanged，但保留其他窗口尚未提交的输入。
7. `FEEDBACK.json` 使用独立 feedbackHash 冲突控制，不因增加评论强迫视频重新编译；comments 不改源码 revision，审片快照记录 feedbackHash。
8. Artifacts displayName 编辑写原 Result presentation adapter，不改源码；变更事件刷新材料库。

普通文件保存允许无效语法；`applyMode=save` 返回 saved + diagnostics。AI变更集 `applyMode=validated` 要 checkPassed；编译失败保存草稿但不移动 head。两种行为在 UI 名称/按钮中区分，不能用一个含糊“应用”暗改规则。
## 8. UI与交互规格

N/A：本任务不新增产品Vue页面和Studio视觉。C03固定响应/状态以供后两份消费；后端测试不能代替后续UI验收。

## 9. 全局约束与精确写入白名单

### 9.1 权限、生成物与黑名单

每W组为精确文件集合，NEW路径仅指设计落点，执行时先检查是否已存在。共享文件由前卡建立、后卡仅补自己负责端点/功能；先复核契约再接线，不能重置前卡实现。U导入与知识打包是显式按manifest枚举的有限集合例外。其余内部新文件先登记具体路径与所属W组再写，不以目录通配获得任意写权限。

通用可写：本107系列三份任务书与任务索引中当前卡状态/已批准技术修订、`contracts/hypit-coverage.v1.json`中本卡条目。证据仅`test-artifacts/task-107/`；临时脚本仅`scripts/local/`；G是按patch生成的忽略产物，不手改。根npm锁不并入Hypit workspace，B/package-lock由C02创建，后卡不擅自升级。

黑名单：原下载Hypit仓库全部文件、已导入U的直接修改（C01导入除外）、已执行迁移、真实env/凭据、无关业务/依赖/目录、生产数据。只读：根AGENTS/DESIGN、模板、103/104/106、platform-storage接口及原业务风格；只读不产生修复权限。V91若已占用按当前最大版本新增，必须先把实际迁移文件登记W04，不能覆盖。

### W01：C107-01 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `platform-hypit/upstream/`：唯一整目录导入例外；仅允许固定 commit 的 1768 个 tracked 路径及原 mode，精确集合由 upstream-manifest.json 冻结。不是任意目录写权限，后卡禁止编辑。
- `platform-hypit/upstream-manifest.json`
- `scripts/acceptance/verify-107-upstream.sh`
- `.gitignore`
- `.dockerignore`
- `README.md`

§13.3 登记（C01 执行期辅助修改，2026-09-25，理由=vendor 引入本仓后既有质量门禁需接入第三方树，不改变对自有文件的门禁语义；影响测试=V16 四命令+scanner 单测 15/15 过）：

- `scripts/quality/check-doc-links.mjs`：扫描集排除 `platform-hypit/upstream/`（第三方文档不属于本仓索引义务；与 .claude/.agents 同类排除）
- `scripts/security/check-tracked-secrets.ts`：跳过目录 symlink（EISDIR 崩溃修复）与 `platform-hypit/upstream/` 前缀（上游测试夹具含结构化假凭据且 U 只读不可加注；补偿控制=manifest SHA-256 + verify-107-upstream.sh 拦截任何新增/篡改文件）

### W02：C107-02 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `B/package.json`
- `B/package-lock.json`
- `B/tsconfig.json`
- `B/src/main.mjs`
- `B/src/server.ts`
- `B/src/config.ts`
- `B/src/engine/hypit-bootstrap.ts`
- `B/src/engine/engine-port.ts`
- `B/src/engine/compile-adapter.ts`
- `B/src/engine/runtime-adapter.ts`
- `B/src/runner/protocol.ts`
- `B/src/runner/client.ts`
- `B/src/runner/server.ts`
- `B/src/runner/supervisor.ts`
- `B/scripts/run-tests.mjs`
- `platform-hypit/patches/manifest.json`
- `platform-hypit/patches/0000-engine-bridge.patch`
- `deploy/hypit/Dockerfile.backend`
- `deploy/hypit/Dockerfile.runner`
- `deploy/hypit/compose.runner.yml`
- `scripts/acceptance/build-107-engine.sh`
- `B/tests/engine/bootstrap.test.ts`
- `B/tests/engine/compile-adapter.test.ts`
- `B/tests/engine/runner-isolation.test.ts`
- `B/tests/engine/patch-replay.test.ts`

§13.3 登记（C02 执行期辅助文件，2026-09-25；理由=K10.4 本地隔离层与测试共享 fixture 需要，均在 B 目录内部小文件，不改变已批准行为；影响测试=engine 组 13/13）：

- `B/src/runner/guard.mjs`：runner 进程 --import 预载——child_process 限定 node_modules 内可执行件、process.dlopen 限定 G/backend 的 node_modules（tsx/esbuild 与 sharp 工具链放行、作者代码夹带二进制拒绝）
- `B/tests/engine/chat-workspace.ts`：engine 测试共享 fixture 助手（G 内 chat-scene 构建+拷贝、稳定渲染缓存、machine 字体安装，含跨进程文件锁）

### W03：C107-03 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `contracts/hypit-api.v1.json`
- `contracts/hypit-tools.v1.json`
- `contracts/hypit-clone-plan.v1.json`
- `contracts/hypit-coverage.v1.json`
- `src/types/hypit.ts`
- `J/api/HypitProjectController.java`
- `J/api/HypitAssetController.java`
- `J/api/HypitJobController.java`
- `J/api/HypitBuildController.java`
- `J/api/HypitRuntimeController.java`
- `J/api/HypitStudioController.java`
- `J/api/HypitKnowledgeController.java`
- `J/api/HypitDtos.java`
- `J/api/HypitExceptionHandler.java`
- `J/config/HypitProperties.java`
- `J/client/HypitSidecarClient.java`
- `J/security/HypitAccessService.java`
- `E/UpstreamResolver.java`
- `E/RouteProperties.java`
- `ER/application.yml`
- `JR/application.yml`
- `ET/UpstreamResolverTemplateTest.java`
- `ET/HypitRoutesTest.java`
- `JT/api/HypitContractTest.java`
- `JT/security/HypitAccessTest.java`
- `B/tests/engine/internal-auth.test.ts`

§13.3 登记（C03 执行期辅助文件，2026-09-25）：`scripts/local/gen-107-api-contract.mjs`——契约生成一次性脚本（Git 忽略目录；产物 contracts/hypit-api.v1.json 已入库冻结，后续以入库文件为准）。

§13.3 登记（C04 执行期修改/辅助文件，2026-09-25）：
1. `platform-hypit/backend/src/server.ts`（W02 共享文件修改）——K06.2 起命令受理必须落持久存储：原进程内 Map 换 CommandStore（`<dataRoot>/bridge.sqlite`，WAL+FULL 同步），dispatcher 注入 projectsRoot/templateDir；对外 wire shape 不变（toBrokerShape），既有 engine 测试零改动全过。
2. `platform-java/.../hypit/project/HypitJson.java`——服务本地 Jackson holder（intelligence 无全局 ObjectMapper bean，注入会炸整个上下文；与 DH 侧 DigitalHumanJson 同模式）。
3. `tests/contracts/lifecycle-inventory.contract.test.ts`——required 计数 18→49：既有漂移修复（105 批次把基线推到 33 时未同步该断言，HEAD 上该用例本就红），随本卡 16 张 hypit_* 登记一并收口。
影响测试=V04 39/39（node 24.14.1）、V05 24/24、V18、V21（49 资源+31 事件、契约 12/12）全绿。

§13.3 登记（C05 执行期修改/辅助文件，2026-09-25）：
1. `platform-hypit/backend/src/server.ts`（W02 共享文件修改，接 C04 登记）——K07/K09 资源面：POST /internal/v1/resources 流式固化（边收边 hash、超限中断不留半文件、拒绝空体）+ GET /internal/v1/resources/{handle} RFC9110 单区间（If-Range 强 ETag=sha256 不匹配降级 200）；共享 HandleRegistry；顺带修真缺口：dispatcherOptions 此前漏 distributionRoot，Java 派发 media.* 必失败 media_unavailable。
2. `platform-hypit/backend/tests/engine/resources-routes.test.ts`（NEW，engine 组）——上述路由的服务级真值（9 断言：固化/全量/单区间/后缀开区间/416/If-Range 双向/空体无残留/未知与穿越句柄）。
3. `platform-hypit/backend/tests/engine/internal-auth.test.ts`（C03 文件）——server 就绪窗口 200→400×250ms（50s→100s）：C05 加入第二个起服测试后全量并发负载下引擎引导超旧窗，断言语义不变。
4. `platform-java/.../hypit/api/HypitContractTest.java`（C03 文件）——HypitAssetController 5 参构造（本卡实装）后测试配置补 assetService/resourceService mock bean；断言不变。
影响测试=V04 60/60（含新 resources-routes 9 断言与 yt-dlp 直连修复）、V05 30/30（新增 HypitAssetIT 6 用例）全绿。

§13.3 登记（C07 执行期修改/辅助文件，2026-09-25）：
1. `platform-java/.../hypit/api/HypitBuildController.java`（W03 文件修改）——§6.2 `POST P/execution-grants` 路由在 C107-03 已声明于该 controller（pending stub），本卡在此实装（W07 未列该文件）：grant 端点从 pending 换 HypitGrantService 真实现+构造参数，其余端点零改动；授权校验/缺价拒绝全在 HypitGrantService（W07 新文件）。
2. `platform-java/.../hypit/api/HypitContractTest.java`（C03 文件，接 C05 登记）——控制器构造连带的测试装配：HypitRuntimeController 增 HypitRuntimeService、HypitBuildController 增 HypitGrantService 两处 mock bean；断言不变。
3. `platform-hypit/patches/manifest.json`——W07 列了 0004 补丁文件本身，manifest 是其 sha 清单必需伴生载体（build-107-engine.sh 校验读它）；无它补丁不可复现。
4. `platform-java/.../digitalhuman/DigitalHumanLifecycleIT.java`（105B 测试文件）——C04 把 16 张 hypit_* 表登记入 lifecycle 后基线 33→49，契约侧同批更新但该 Java 断言漏同步（V05 只跑 *Hypit* 未暴露；V10 全量 check 揭露）；单行 33→49 补正，gate 本身 exit 0 未变。105fix-1 会话未占用此文件。
5. `platform-java/build.gradle.kts`（根构建文件，测试 JVM 环境旋钮非断言）——C04-C07 新增 6 个 hypit IT 上下文后，3g 下全量 V10 在 mediaplatform 簇 Java heap space（2026-09-26 实录：G1 Full GC 风暴、executor 死亡、单 worker 复现）；沿用 512m→2g（#101）→3g（2026-09-14）同类登记先例放宽到 4g，并在 jvmArgs 加 `-Dspring.test.context.cache.maxSize=12`（默认 32 无上限缓存全量驻留=本次根因；LRU 淘汰）。测试断言/覆盖门禁零改动。
影响测试=V04 providers 组 27 测/26 过/1 诚实 LIVE skip、V05 39/39、V10 详见 §10 C107-07 行。

§13.3 登记（C08 执行期修改/辅助文件，2026-09-26）：
1. `platform-hypit/backend/src/runner/server.ts`（C02 文件）——runner "plan" kind 从 NOT_IMPLEMENTED 占位接 planRunInRunner 真实现（W08 planning.ts 的 runner 半）；协议白名单本就含 plan，零协议变更。
2. `platform-hypit/backend/src/server.ts`（C02/W02 文件，接 C04/C05/C07 登记）——broker 增 plan/pricing/vocabulary 三 kind 派发（编排 runner 半+信任侧 Host 只读解析，信任分裂与 render.local 同构）；check/plan/pricing 载荷增 projectId→信任侧 projectsRoot 解析 workspace（Java 侧不持宿主路径）。
3. `platform-hypit/backend/tests/engine/runner-isolation.test.ts`（C02 文件）——"plan recognized but not implemented" 断言随 plan 实装更新为「缺 payload 仍被拒」（payload.workspaceRoot must be a string），隔离语义断言零改动。
影响测试=V04 112 测/110 过/0 挂/2 诚实 skip、V05 43/43（详见 §10 C107-08 行）。

### W04：C107-04 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `JR/db/migration/V91__hypit_video_clone.sql`
- `J/project/HypitProjectService.java`
- `J/project/HypitProjectRepository.java`
- `J/project/HypitRevisionRepository.java`
- `J/project/HypitChangesetService.java`
- `J/job/HypitCommandRepository.java`
- `J/job/HypitJobRepository.java`
- `J/job/HypitJobService.java`
- `J/job/HypitJobEventRepository.java`
- `B/src/workspace/paths.ts`
- `B/src/workspace/manifest.ts`
- `B/src/workspace/transactions.ts`
- `B/src/workspace/revisions.ts`
- `B/src/workspace/provision.ts`
- `B/src/commands/store.ts`
- `B/src/commands/dispatcher.ts`
- `B/src/commands/events.ts`
- `platform-hypit/fixtures/minimal-local/package.json`
- `platform-hypit/fixtures/minimal-local/main.svml`
- `platform-hypit/fixtures/minimal-local/main.svrun`
- `JT/project/HypitProjectIT.java`
- `JT/project/HypitMigrationIT.java`
- `JT/job/HypitCommandIT.java`
- `B/tests/workspace/paths.test.ts`
- `B/tests/workspace/transactions.test.ts`
- `B/tests/workspace/revisions.test.ts`
- `contracts/hypit-coverage.v1.json`
- `J/api/HypitProjectController.java`
- `J/api/HypitJobController.java`


- `tests/contracts/resource-lifecycle.registry.json`
- `tests/contracts/event-consumers.registry.json`
- `tests/contracts/lifecycle-inventory.baseline.json`

仅登记本卡实际新增/接通的资源与事件、真实 owner/清理/消费符号及对应 TC；C04 建表时登记现有生产路径，后卡随实现接入增量核实。baseline 只添加真实必需项，不删除既有 required 或追加豁免规避门禁。

### W05：C107-05 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `J/asset/HypitAssetService.java`
- `J/asset/HypitAssetRepository.java`
- `J/asset/HypitResourceService.java`
- `B/src/tools/media.ts`
- `B/src/resources/handles.ts`
- `B/src/resources/stream.ts`
- `B/src/resources/url-policy.ts`
- `platform-hypit/fixtures/generate-media.mjs`
- `platform-hypit/fixtures/manifest.json`
- `platform-hypit/fixtures/local-web/index.html`
- `B/tests/media/media-tools.test.ts`
- `B/tests/media/yt-dlp.test.ts`
- `JT/asset/HypitAssetIT.java`
- `contracts/hypit-coverage.v1.json`
- `B/src/commands/dispatcher.ts`
- `J/api/HypitAssetController.java`

### W06：C107-06 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `B/src/tools/speech.ts`
- `B/src/tools/image.ts`
- `B/src/programs/manager.ts`
- `B/src/programs/catalog.ts`
- `B/src/programs/health.ts`
- `deploy/hypit/prepare-programs.sh`
- `platform-hypit/fixtures/speech/catalog.json`
- `platform-hypit/fixtures/speech/speech-en.wav`
- `platform-hypit/fixtures/speech/speech-zh.wav`
- `B/tests/media/speech-tools.test.ts`
- `B/tests/media/programs.test.ts`
- `B/tests/media/image-operations.test.ts`
- `contracts/hypit-coverage.v1.json`
- `B/src/commands/dispatcher.ts`
- `J/api/HypitRuntimeController.java`

### W07：C107-07 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `B/src/providers/catalog.ts`
- `B/src/providers/activation-hooks.ts`
- `B/src/providers/authorization.ts`
- `B/src/providers/asset-transport.ts`
- `B/src/providers/credentials.ts`
- `B/src/providers/oauth.ts`
- `platform-hypit/patches/0004-provider-authorization.patch`
- `J/execution/HypitExternalExecutionBridge.java`
- `J/execution/HypitExecutionRepository.java`
- `J/execution/HypitGrantService.java`
- `J/execution/HypitInternalExecutionController.java`
- `J/runtime/HypitRuntimeService.java`
- `JI/ai/run/AiExecutionService.java`
- `JIT/ai/run/HypitExternalPreparationTest.java`
- `platform-hypit/fixtures/fake-provider-server.mjs`
- `JT/execution/HypitExecutionBridgeIT.java`
- `B/tests/providers/catalog.test.ts`
- `B/tests/providers/authorization.test.ts`
- `B/tests/providers/transport.test.ts`
- `B/tests/providers/credentials.test.ts`
- `B/tests/providers/oauth.test.ts`
- `B/tests/providers/provider-contracts.test.ts`
- `contracts/hypit-coverage.v1.json`
- `B/src/commands/dispatcher.ts`
- `J/api/HypitRuntimeController.java`
- `platform-hypit/patches/manifest.json`

### W08：C107-08 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `B/src/engine/compile-adapter.ts`
- `B/src/engine/planning.ts`
- `B/src/engine/vocabulary.ts`
- `J/build/HypitPlanService.java`
- `J/build/HypitPlanRepository.java`
- `J/build/HypitPricingRepository.java`
- `B/tests/engine/planning.test.ts`
- `JT/build/HypitPlanIT.java`
- `contracts/hypit-coverage.v1.json`
- `B/src/commands/dispatcher.ts`
- `J/api/HypitBuildController.java`
- `J/api/HypitKnowledgeController.java`

### 9.2 部署、环境与配置

Node24.14.1/pnpm10.33.0，独立B/npm与U/pnpm锁；Java沿既有JDK25/Gradle，PostgreSQL用隔离Testcontainers。安装/联网下载/本地容器/本地迁移按已授权开发范围执行。C02最小runner栈、C06本地Programs、C12Studio smoke不等待最终C23部署书才可测试；最终部署参数由107-3§9.2/C23统一。浏览器/模型未安装先记录具体失败与修复步骤，不能假ready。

### 9.3 验证环境事实与准备

| 项目 | 精确约定/本轮事实 | 实施前核对及副作用 |
|---|---|---|
| 根目录与 shell | 本机 /Users/LXH/claude/y-1；脚本从仓库根解析实际路径；shell=bash | 不把作者绝对路径硬编码进可交接脚本 |
| Node/npm/pnpm | 本轮 Node v22.22.3/npm 10.9.8；Hypit 要求 Node24.14.1/pnpm10.33.0 | C01/02 准备固定工具链与独立 U/B 锁，安装写各自依赖目录；不升级根依赖 |
| Java | 项目 JDK25/仓库 Gradle；本轮未启动 Java | platform-java 下同一 bash 会话 source ../scripts/lib/java-runtime.sh，再 ensure_java_runtime 25；java/javac/Gradle toolchain 均核对，helper 最低版本探测不等于 JDK25 已验证 |
| 数据库与容器 | Java IT 用 PostgreSQL Testcontainers；部署按 C02/C23/C24 的专用栈 | 核对实际 URL、库/卷、Compose 项目名与本轮所有权后迁移/清理，不能凭 localhost 清库 |
| 浏览器/模型 | 根 Playwright 有 chromium/firefox/webkit；Hypit capture/render 二进制独立 | C12/13/24 跑真实浏览器；C06/23 准备 uv/模型/ffmpeg；本轮可用性 NOT_RUN |
| 入口 | 已有 Playwright BASE_URL 默认 http://127.0.0.1:18080；107 实际 AI/用户/治理 origin 由隔离启动脚本产出 | V19/V13 记录真实 BASE_URL/AI_BASE_URL/OPS_BASE_URL 与代理；不继承生产变量，不据默认地址声称服务存在 |
| 数据与外部替身 | 107-1 K13 fixture、合成 owner A/B 与 operator；modelMode=replay/providerMode=fixture | 仅替换指定外部模型/Provider，目标业务 API/数据库/runner 不替身；live 另按授权 |
| 产物与清理 | ART/Cxx/ 下按 V/浏览器/运行轮次保存原始日志、XML、截图及媒体 | 仅清理本次创建并确认归属的测试资源；保留首轮失败和有效证据，不另建手工交付报告 |

本地依赖下载、隔离容器/服务/迁移和浏览器验证按开发授权推进；环境不可用先排查并记录具体失败。部署、真实付费或非隔离远程资源按会话已有授权处理，缺失时只阻塞对应卡/验证。

### 9.4 性能、安全与资源释放

默认单项重渲染并发1；普通列表20/max100、单批变体100、文件2MiB/变更集16MiB、素材500MiB、压缩1GiB/展开4GiB/20000文件。租约30秒/10秒续租，确定性重试1/2/4秒最多3次，收费unknown不重发；Studio ticket120秒、idle1800秒、hard28800秒、并发2。日志分页与流式字节传输有上限；取消必须关闭stream/process/timer，输出不泄漏token/签名URL/私有路径。所有阈值来自107-1契约与107-3C23，改变必须同步契约/边界用例。

### 9.5 仓库硬约束适用矩阵

| 约束 | 落实卡/动作 | 验证 |
|---|---|---|
| R-UI | C13完整Studio、C21/22产品UI；其余不改UI | DESIGN、亮暗/移动/键盘截图、V11/V19/V13 |
| R-ENTRY | C03/12/22/23三入口/会话/Edge | 路由拒绝/旧入口smoke、V06/V12/V13 |
| R-JAVA | 业务在intelligence；C02为本任务明确内部Node引擎例外，不新增公开Node业务上游 | WebFlux无block/手动subscribe；V05/V06/V10 |
| R-DATA | C04新增迁移/command/journal；C09/10/23恢复 | 真PostgreSQL、唯一键、故障注入、V18 |
| R-AI | C07/14/16/18预算与ai_run，复用FrozenTextExecutionService | 无授权submit=0；unknown不重提 |
| R-QUALITY | 全部；不降低覆盖率、不跳必需测试；旧格式/迁移保留 | 指定TC/V、V09/V10/V17/V18 |
| R-LAYER | C21/22；VideoCloneWorkbench 和既有五视图只装配，URL/业务/大区块按 AGENTS 分层 | Vue≤800，四豁免只减不增，composable>500 WARN；V09/V20 |
| R-LIFECYCLE | C04/09/10/14/19/23/24；核对资源 owner、派生引用、活跃态、清理和事件真实消费 | K05/K06、注册与消费证据、V21；不得仅补登记文本 |
| R-DIR | 全部；tests/、源码就近测试、scripts/acceptance/、docs/架构/、ART；U 原生 test 保留 | W 组与 diff，临时脚本仅 scripts/local/，不建额外交付文件 |
| R-SAFE | 全部；保留原改动、隔离测试、脱敏 | baseline diff与证据、V16 |

## 10. 任务总表与卡间交接

三个顶层任务串行；本书八个单元仍串行，原因是共享schema/迁移、源码补丁清单、Job状态与同一工作区，不允许抢写。业务依赖列表示必须读取的实际交付，不以“上一模型说完成了”代替核验。

| 卡 | 结果 | 需求 / AC | 写入 | 业务依赖 | 测试 / 命令 | 状态 |
|---|---|---|---|---|---|---|
| C107-01 | 完整 vendor、hash 清单、原生测试基线 | REQ-107-01 / AC-107-01 | W01 | 无 | TC107-01-01～04；V01 / V02 | VERIFIED |
| C107-02 | bootstrap/Runtime Host 桥、可重放补丁与最小引擎宿主 | REQ-107-02 / AC-107-02 | W02 | 01 | TC107-02-01～04；V01 / V03 / V04 | VERIFIED |
| C107-03 | API/工具 schema、Java 身份边界、禁用态/事件协议 | REQ-107-03 / AC-107-03 | W03 | 02 | TC107-03-01～04；V04 / V05 / V06 | VERIFIED（2026-09-25：并行工作区收敛后 V05 通配 24/24 全绿，含 HypitContractTest 5/5、HypitAccessTest 5/5；卡内 BLOCKED 解除） |
| C107-04 | 项目、文件、快照、幂等命令与迁移 | REQ-107-04 / AC-107-04 | W04 | 03 | TC107-04-01～04；V04 / V05 / V18 / V21 | IMPLEMENTED（2026-09-25：V91 16 表+Java 工程/变更集/任务面+B workspace/commands 全落地；V04 39/39（node 24.14.1）、V05 24/24、V18 checksum 7 文件、V21 49 资源+31 事件与契约 12/12 全绿；§13.3 登记三处辅助修改） |
| C107-05 | 参考素材、链接抓取与完整媒体工具 | REQ-107-05 / AC-107-05 | W05 | 04 | TC107-05-01～04；V04 / V05 | IMPLEMENTED |
| C107-06 | 转写/估时、OpenCV、Python Programs | REQ-107-06 / AC-107-06 | W06 | 02/05 | TC107-06-01～04；V04 / V07 | IMPLEMENTED（2026-09-25：B programs 域 catalog/health/manager（uv venv 隔离、状态机 up/down/status/logs、死 pid 转.down、health 身份比对不吞 mismatch）+speech 三工具（canonical 16k mono s16 经 ffmpeg 显式抽取非暗重采样、seconds→samples 越界钳制不造窗、measure 走原生 @hypit/estimate 带 estimated 标记、align 逐段对全量证据词序可解释）+image transform/compose 走上游 raster_execute.py 真像素断言（产物写 allowedRoots 保 registerResource 包含性）+dispatcher programs.*/speech/image 路由+J programs 端点（prepare/up/down/status/logs 经 sidecar）+deploy/hypit/prepare-programs.sh+真语音 fixtures（say→ffmpeg）；V04 78 测/77 过/0 挂/1 诚实 EXTERNAL_BLOCKED skip（真实 WhisperX 模型下载属 operator 工作）、V05 30/30、V07 check 0 退出+test 1053 过+image-opencv 2 过+whisperx-service 18/18 OK；无 W06 外写入故无 §13.3 登记项） |
| C107-07 | 全 Provider、凭据/OAuth、外部执行 bridge 与素材传输 | REQ-107-07 / AC-107-07 | W07 | 03/04 | TC107-07-01～04；V04 / V05 / V10 | IMPLEMENTED（2026-09-25/26：B providers 六模块（catalog 真 distribution 枚举 10 注册+factory{} 实例化、credentials 四 store 复合+0700/0600、oauth PKCE+code#state timingSafeEqual+refresh 经 replace authority、asset-transport 自有 http server sha256 ETag+字节预算+reap、authorization 执行桥+ExecutionAuthorizer permit 缓存/request_hash 变更拒绝/一次性结算、activation-hooks factory 映射+withAuthorizationGuard 装饰 start）+fake-provider-server.mjs 纯 JS HypiHub wire（/v1 前缀剥离、S256 PKCE 校验、refresh_token grant、引用 hash 记录、failBearerCalls/setPollsBeforeDone 旋钮）+6 测试文件 27 测 26 过 1 诚实 LIVE skip；0004-provider-authorization.patch（runtime-kit hostServices 槽+runtime-local 捕获后条件展开+六远程 activation services.publicAssetUrl 透传）+manifest sha 清单、G 重建两连绿（构建字节稳定+patch-replay 3/3）；Java execution 域 HypitExecutionRepository（grant/execution 行、FOR UPDATE 锁、ON CONFLICT DO NOTHING 回读、COALESCE 只补不清）/HypitExternalExecutionBridge（prepare 单事务校验→预算 CAS→幂等落行发 5min 许可+credentialRef 引用无 secret、complete/fail/cancel/markUnknown 只补原 operation+终态拒改判+unknown 保留占用）/HypitGrantService（canonicalJson scope hash、缺价无 allowUnknownCost 拒、owner 校验）/HypitInternalExecutionController（4 端点内部 token ≥32 精确 Bearer）；HypitRuntimeService 凭据/authflow 全委托 sidecar 命令域（commandAsync 不 block 事件循环、failed 如实 502）+Profile 校验 persisted=false 如实；HypitRuntimeController 实装 runtime/profile/credentials/auth-flows 九端点（operator 门禁、owner=accountId、noStore）；HypitBuildController execution-grants 从 pending 换真实现；AiExecutionService.prepareHypitExternalExecution（BYOK 零成本+feature=null+无密文路径，K12.4/12.5 红线不变）；JIT HypitExternalPreparationTest 3 用例（run 冻结 provider/model、零平台预扣、无 key 材料、组织域预算作用域、BYOK 零成本红线）；JT HypitExecutionBridgeIT 6 用例（并发抢预算恰一、假 grant/撤销/过期/跨工程/超价全拒零副作用、同 operation 重放同 hash 新许可 hash 变 409、回执幂等只补不清、终态拒改判、unknown 保留占位到明确回执、cancel 释放、内部 token 401 零副作用）；V04 105 测/103 过/0 挂/2 诚实 skip、V05 39/39、V10 全量 BUILD SUCCESSFUL 10m38s（含 §13.3 堆旋钮登记与 lifecycle 33→49 补正）；真实远程 provider live 调用 REAL_NOT_RUN（无 live key，如实单列不折算）；证据 test-artifacts/task-107/C07/（本地） |
| C107-08 | 编译、计划、价格、候选复用解析 | REQ-107-08 / AC-107-08 | W08 | 04/07 | TC107-08-01～04；V04 / V05 | IMPLEMENTED（2026-09-26：B planning.ts 双半（runner 半 planRunInRunner 走原生 loadRunFile+真 Results 仓储+native reuse/planCompilation/evaluatePlanNeeds/plannedProviderQueries 同构查询集；trusted 半 assemblePlanDocument/assemblePricingDocument 纯 Host providers/preflight/pricing 只读解析+planHash 内容寻址（volatile res_句柄归一——随机资源句柄不是计划内容，字节同一性由 Build 期 runtime 保证）+planId=hash 派生 UUID+ok 口径同 CLI（preflight+unresolved+unsupported+requestIssue）+估价行 estimatedCost/currency 缺报价恒 null 不写 0）+vocabulary.ts（真 distribution 10 provider 的 capability/offered/mapped/models/pricing/gaps+managedProgram+OAuth acquisition 槽+managed programs+对齐语言）；runner server plan kind 落地；broker server plan/pricing/vocabulary 三 kind+projectId→workspace 信任侧解析；dispatcher 路由；Java HypitPlanRepository（plan 幂等 WHERE NOT EXISTS+pricing (plan,hash) ON CONFLICT，行不可变）/HypitPlanService（check/plan/pricing 委托 sidecar、revision 冻结于计划时点、requirePlanFresh revision/profile 409、requireGrantCovers scope⊆targets）/HypitBuildController check/plan/pricing/plans 实装/HypitKnowledgeController vocabulary 实装；B planning.test.ts 7 测全绿（坏 Header/类型错/缺包/本地渲染 plan 确定性 hash/远程 Need 无端点 unresolved+null 价/真渲染后 build-record+satisfy 复用改变计划且请求数下降/vocabulary 真 distribution）；V04 112 测/110 过/0 挂/2 诚实 skip、V05 43/43；真实远程 provider 报价读取 REAL_NOT_RUN（无 live key）；证据 test-artifacts/task-107/C08/（本地） |

### 10.1 顶层交接门槛

107-1交付manifest、EnginePort、四类contracts、完整migration、工程/素材fixture、Provider目录/授权桥、planId/pricing快照。107-2用V01/V04/V05/V06及固定plan冒烟核验后接手。107-2交付三终态Build+result_pending、可复用Results、双会话Studio、12Companion、知识检索/Agent、ReferenceAnalysis和ClonePlan；107-3先运行V04/V05/V19和一次复刻方案读回。107-3最后核销全部F01～40。

### 10.2 防止前后卡循环依赖

C02用上游已有纯本地例验证引擎，不等待C04正式工程；C04给最小初始化fixture，不等待C20模板库。C03冻结全部API但未实现端点必须503/flag关闭。C12创建verify-107-studio.sh和真实Studio测试入口，C13扩展主题/Companion，不能等C24才补首轮浏览器验证。C23创建verify-107-full.sh并实跑部署，C24复用扩充，不能让验收脚本互相等待。

### 10.3 阶段、依赖与退出条件

| 阶段 | 可交付结果 | 卡顺序 | 进入条件 | 退出证据 | 失败去向 |
|---|---|---|---|---|---|
| M107-1A | 固定源与最小真实运行 | C01 → C02 | 固定源 Git 对象可读，授权本书 | V01/V02 基线记录，V03/V04 真实本地渲染和隔离通过 | C01 源完整性；C02 补丁/运行，不向后转嫁 |
| M107-1B | 持久工程到冻结计划 | C03 → C04 → C05 → C06 → C07 → C08 | M107-1A VERIFIED | 契约、真实 PG/素材、授权桥与 planId；§12.5 本书出口 | 返回具体 C03～08；契约变化走 §13 |

仅 VERIFIED 解锁依赖；C01 的诊断型完成边界见该卡，不把原生失败写成 PASS。共享 schema/dispatcher、迁移、补丁、锁文件、生命周期登记按卡序增量写入；后卡只增加已登记的本卡实现，不覆盖前卡。卡级验收与本书最终出口分开，C24 是全系列最终集成责任卡。

### 10.4 高风险与验证前置

| 风险 | 失败假设 | 影响 | 等级/原因 | 最早验证 | 不成立时处理 |
|---|---|---|---|---|---|
| RISK-107-01 | 作者组件在可信进程读取凭据/串项目 | REQ-107-02/07；C02/07 | 高：执行代码与凭据边界 | TC107-02-03/04、TC107-07-01/02；合法渲染与拒绝均验证 | 禁止启用网页执行；修复 IPC/隔离，不能取消隔离 |
| RISK-107-02 | PG/文件跨进程故障形成混合 revision | REQ-107-04；C04 | 高：损坏用户工程 | TC107-04-02/03/04；journal 故障注入后重读 | 阻断新写并恢复 journal；不覆盖用户输入 |
| RISK-107-03 | 未授权或回执丢失导致重复远程提交 | REQ-107-07/08；C07/08 | 高：真实费用/审计 | TC107-07-01/04、TC107-08-03；独立 submit 计数断言 | unknown/等待授权，禁止盲重提 |

验收者从需求重新构造至少一条高风险独立反例，确认移除目标保护后该断言会失败；不得用同一请求中的另一拒绝原因掩盖缺陷。当前执行者即可完成复核，不强制另开代理。

## 11. 详细任务卡

每卡绑定 W/REQ/AC/TC/V；保留既定实现步骤与必要源码锚点，NEW 文件/符号不等于已存在。公共行为和字段通过 K/RULE 引用；§12.2 补齐每个 TC 的实现位置、层级、fixture 与证据，不复制第二份契约。

### C107-01 · 固定源码、逐文件核验与基线

- **执行包/责任人**：107-1 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-01 / AC-107-01；VERIFIED（2026-09-25：1768 文件 manifest 校验+四类篡改反例+V02 原生基线全绿，0 失败/22 环境skip已分诊；V16 文档接入随本卡收口）。
- **类型与完成边界**：诊断/基线；交付固定源码完整性和真实原生运行基线，修复留 C02；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W01及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K01/K02，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-01-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V01 / V02。产物 `ART/C01/`；V01 必须退出0，V02 按诊断边界保留各真实退出码/失败/skip并完成分诊，不能将原生失败标 PASS。

#### 输入、必读与写入

- 前置：无。必须能读取本地Hypit仓库和固定Git对象；y-1已有107文档改动必须保留。
- 必读：`U`的原始来源根`package.json/pnpm-workspace.yaml/.node-version`、`bin/hypit.mjs`、`test/run.mjs`、`scripts/check-distribution.mjs`；源文件在复制前从指定本地clone读。
- 写入：`U/**`（一次导出）、`platform-hypit/upstream-manifest.json`、`scripts/acceptance/verify-107-upstream.sh`、`.gitignore/.dockerignore`中本任务段、根README组件说明。
- 输出给C02：精确vendor、manifest、可重复的verify脚本、依赖/测试原始基线；无需源仓库工作树clean。

#### 步骤与每步检查

| 步 | 具体动作 | 完成检查 |
|---|---|---|
| 01.1 | 记录y-1/source HEAD与status；验证commit对象存在。检查目标U：不存在可创建；已存在先校验，差异未解释不得覆盖 | 日志含两HEAD，不含.env内容 |
| 01.2 | `git archive`指定commit，解压至U；只导出tracked文件，保留文件模式/隐藏文件 | 1768路径；不复制.git/node_modules/当前空LICENSE |
| 01.3 | `git ls-tree -r`读取expected路径/mode/blob；按导出字节生成SHA256；排序写manifest，metadata写upstream commit/version | LICENSE bytes等于Git对象；不是只检查含Apache |
| 01.4 | verify脚本默认只校验本地U与入库manifest，不要求原下载目录仍存在；`--source`可额外核对原仓库 | 移走源目录概念上的fixture仍可verify；篡改一个文件/新增漏项均非零 |
| 01.5 | 递归忽略U/G的node_modules、dist、Python .venv/cache以及G本体；检查Docker build context仍包含tracked源码/uv.lock | `git status`不出现依赖目录；不误忽略U源码 |
| 01.6 | 用Node24.14.1/pnpm10.33.0安装frozen依赖，运行check/test/check:distribution；不改锁文件解决依赖漂移 | 保存每命令退出码、skip数量与原因 |
| 01.7 | 原生失败分类：本机环境/上游固定版本缺陷/真正执行错误。缺陷建立后续patch记录，不修改U；可复现且已定位为固定上游缺陷时，保留失败和 baselineStatus=BASELINE_RECORDED；本卡仅在源码完整性、实际基线执行和分诊验收全部满足后 VERIFIED，移交 C02 修复；未知原因或未运行保持 IMPLEMENTED/BLOCKED | 失败信息精确到文件/测试，不笼统称环境问题 |
| 01.8 | README记录固定版本、源码来源、保留许可和标识；记录清单统计 | 122包/3服务/12示例包/28Run/65Skill均匹配 |

#### 实现要求

manifest为 `{format:'y1.hypit-upstream@1',commit,version,files:[{path,mode,sha256,sizeBytes}]}`；相对路径排序，不含机器绝对路径。verify检测missing/extra/changed/modeChanged，输出每类路径并exit1；成功exit0。`extra`排除本任务明确列出的生成目录，不能用“排除所有隐藏文件”。脚本`set -euo pipefail`，source路径可参数化且加引号，禁止拼eval。

#### 验收

| 测试 | Given/When/Then |
|---|---|
| T01-1 | 给定固定Git对象，导出后verify：所有路径/hash/mode相同 |
| T01-2 | 在临时fixture删文件/改字节/改可执行位/加源码：四次分别失败并指出差异 |
| T01-3 | 只增加忽略的node_modules：源码verify仍通过，Git不跟踪依赖 |
| T01-4 | 原生基线：记录真实check/test/distribution结果与环境skip；不改原测试 |

命令：根`bash scripts/acceptance/verify-107-upstream.sh`；U中按01.6分别执行。耗时测试可后台运行但必须拿到结束码。禁止`rsync`当前空LICENSE、无差别git add整个U缓存、修改源下载目录。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

### C107-02 · 引擎桥、补丁机制和执行进程边界

- **执行包/责任人**：107-1 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-02 / AC-107-02；VERIFIED（2026-09-25：bootstrap/compile/runtime 三适配器+K10.4 runner IPC+0000 补丁+部署三件+V01/V03/V04 全绿，engine 组 13/13 含真实本地渲染 chat.mp4 ffprobe/ffmpeg 实证）。
- **类型与完成边界**：实现；本卡 AC/TC 和指定 V 通过，输出供 §10 所列后置卡消费；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W02及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K04/K10.4，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-02-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V01 / V03 / V04。产物 `ART/C02/`；命令按 107-1 §12.3 的预期退出码与诊断例外逐项核验，必需断言无未解释 skip 才满足 AC。

#### 输入、必读与输出

- 依赖 C01 VERIFIED：源码完整性通过、原生命令全部实际执行，已明确分诊的固定上游缺陷及失败用例交给本卡补丁修复；没有证据的未知失败不解锁本卡。
- FACT必读：`U/bin/hypit.mjs`、`packages/video-cli/src/{index,distribution,compiler,package-selection}.ts`、`packages/runtime-host-node/src/index.ts`、`packages/runtime-local/src/{host,config}.ts`。
- 写入：`B/{package.json,package-lock.json,tsconfig.json}`、`B/src/{main.mjs,server.ts,config.ts}`、`B/src/engine/**`、`B/src/runner/**`、`B/tests/engine/**`、`B/scripts/run-tests.mjs`、`platform-hypit/patches/**`、`scripts/acceptance/build-107-engine.sh`、部署基础Dockerfile。
- 输出：`EnginePort`交接接口、补丁副本G、独立宿主和runner可响应health/commands。Java/网页主入口本卡尚不启用。

#### 步骤

| 步 | 动作 | 检查 |
|---|---|---|
| 02.1 | backend独立npm包锁定TypeScript5.9.3、tsx4.21.0、@types/node24.10.1；HTTP原生node:http，代理依赖按107-1登记 | 根package-lock不被合并 |
| 02.2 | build脚本先verify U，再从源码清单构建G；逐patch先`git apply --check`再应用；记录before/after hash | 连续构建两次相同，不把node_modules复制入G |
| 02.3 | `main.mjs`先tsx register、distribution resolver、external resolver，再动态import TS服务 | 直接启动可解析所有@hypit内部包 |
| 02.4 | `engine-port.ts`定义check/plan/pricing/submit/inspect/status/cancel/openResults/vocabulary；映射与上游类型分开 | 不存在的能力启动即诊断，不返回假空列表 |
| 02.5 | compile-adapter沿video distribution discovery→load packages→compiler→Run流程；测试workspace/packageRoot/distributionRoot三者正确 | 自定义项目包可发现，其他项目同名包不污染 |
| 02.6 | runtime-adapter调用Host，build传指定id；库历史查询通过project Results adapter；close生命周期完整 | 打开关闭无句柄泄漏 |
| 02.7 | 按K10.4实现可信broker与受限author-runner的结构化IPC：消息有commandId/kind/payload，媒体只传handle；用户package加载放runner | runner环境中无内部token/key，不挂host home；未知kind拒绝 |
| 02.8 | 进程级超时/取消/输出长度限制；作者代码所有入口（含compile discovery和capture）按107-1 K10.4隔离；有需要的原生hook形成patch | 不能以“纯函数SDK”自称沙箱 |
| 02.9 | 使用semantic-composition/chat或最小等价fixture完成check/plan/纯本地渲染；先准备真实render browser | ffprobe读得出非空帧，不只是header |

#### 函数交接（NEW）

`loadHypit(distributionRoot): Promise<LoadedHypit>`：进程内一次初始化，重复同root返回缓存；第二个不同root拒绝，禁止动态串版本。

`EnginePort.check({workspaceRoot,entryFile,revision}): CheckResult`；`plan({workspaceRoot,runFile,revision,profileRef}): EnginePlan`；`submit({engineBuildId,planHandle,snapshotHandle,repositoryLocation,authorizationRef}): EngineSubmission`。前两个不能隐式生成付费素材，submit不得重新读可变head。

bridge设计不是声明这些原生API已经存在；内部必须由已读取的FACT上游函数组装，并有adapter测试。禁止实现伪compiler或解析console彩色文本。

#### 测试与交接

`B/tests/engine/bootstrap.test.ts`、`compile-adapter.test.ts`、`runner-isolation.test.ts`、`patch-replay.test.ts`。断言加载顺序、脚本header检查、自定义包、输入读限、无secret继承、重复patch构建、真实小MP4。运行`TEST_GROUP=engine npm test`和`npm run typecheck`；G原生相关测试运行。交接记录可用的EnginePort函数与限制，不提前声称所有Provider已可用。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

### C107-03 · 契约、路由、身份与事件

- **执行包/责任人**：107-1 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-03 / AC-107-03；VERIFIED（2026-09-25：76 路由契约/18 工具契约/clone-plan 契约/coverage/TS 类型/Edge 模板匹配+76 路由/Java 7 控制器+Access+Sidecar+Dtos+Handler 全部落地；ET 模板测试 7/7、HypitRoutesTest 3/3、V06 全量 edge exit0、backend internal-auth 1/1。原 BLOCKED 已解除：并行 DH 工作区收敛后 V05 通配 24/24 全绿（含 HypitContractTest 5/5——controllersBind 补 ReactiveWebApplicationContext+@EnableWebFlux、匿名 401 断言补 X-Test-Anon 头；HypitAccessTest 5/5——C04 接线后 requireProjectOwner 真实走仓储）。）
- **类型与完成边界**：实现；本卡 AC/TC 和指定 V 通过，输出供 §10 所列后置卡消费；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W03及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K03/K07/K08/K09，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-03-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V04 / V05 / V06。产物 `ART/C03/`；命令按 107-1 §12.3 的预期退出码与诊断例外逐项核验，必需断言无未解释 skip 才满足 AC。

#### 输入、必读、输出

依赖C02引擎桥；必读`E/UpstreamResolver.java`、`E/RouteProperties.java`、`ET/DigitalHumanRoutesTest.java`、`IntelligenceCallerResolver.java`、`IntelligenceException.java`。写入四个contracts（coverage骨架也在本卡）、TS DTO、J/api/config/client/security、E模板匹配/YAML、ET/JT测试。数据库业务实现留C04，contract可先校验内存fixture，但公开未就绪必须明确503。

#### 步骤

1. 从107-1§6 API全集逐method建route表，填request/response schema、状态码、flag、operator/owner条件、负责卡；用K07字段，不留`object:any`。
2. 工具契约按K08逐CLI参数登记 exposed/serverManaged/rejectedWithReason；创建schema测试拒绝未知字段/非法互斥组合。
3. 创建Java records/TS接口，日期、金额、null、UUID按K03；写同一组JSON fixture做双端序列化断言。
4. 按K09修改exact:true模板段匹配；旧无模板路径执行原逻辑；添加每method配置，不放开prefix全量转发。
5. `HypitAccessService`统一owner/operator函数；controller验签Caller一次传入service；错误advice只作用Hypit包。
6. `HypitSidecarClient`固定baseURL、内部token、超时、结构化错误；禁止从HTTP body读新的目标地址；internal callback认证单独实现。
7. Job统一查询/actions/events与项目别名按K03同时登记，global job校验operator及提交account；capabilities依据HYPIT_ENABLED、sidecar health和已部署目录拼事实：未实现项ready=false；用户未登录401，禁用已登录200。
8. 事件DTO和SSE encoder按K09，先实现snapshot/heartbeat/cursor解析，不将process stdout冒充event stream；持久事件由C04接上。

#### 验收

| 测试文件 | 必须断言 |
|---|---|
| `ET/UpstreamResolverTemplateTest.java` | 已知模板匹配、空段/多段/编码斜线/错method拒绝、旧prefix/exact不变 |
| `ET/HypitRoutesTest.java` | 每contract route在YAML有唯一条目，flag关闭404；capabilities可单独打开 |
| `JT/api/HypitContractTest.java` | 成功/错误/202/null/金额string与JSON schema一致；无raw secret |
| `JT/security/HypitAccessTest.java` | 缺失/伪造/重放签名、owner B访问A、非operator改配置拒绝 |
| `B/tests/engine/internal-auth.test.ts` | internal无/错token拒绝，日志不打印认证头 |

命令：edge全测试、intelligence `--tests '*Hypit*'`、根types验证。交接：每route和tool的机器schema、DTO JSON样例、错误码，无实现的端点仍默认关闭。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

### C107-04 · 迁移、工程、文件、版本与命令

- **执行包/责任人**：107-1 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-04 / AC-107-04；IMPLEMENTED（2026-09-25：V91__hypit_video_clone.sql 16 表（CHECK/UNIQUE/索引齐、IF NOT EXISTS 重放语义，HypitMigrationIT 断言 16 表/单条 V91 历史/关键约束拒绝/DDL 重放幂等）；Java 面 HypitProjectService（创建单事务 command+project+job、sidecar provision 补偿收敛 provisioning_failed/ready、同 requestId 重放读 result、删除活跃工作 409→deleting→deleted、title CAS）、HypitChangesetService（save/validated check/apply，baseRevision 三方 CAS 双闸 409、validated 未过 check 422 草稿保留、sidecar 错误码贯通）、HypitJobService（SSE snapshot+tailing、evt-id 续接）+ 四仓储（claim SKIP LOCKED/事件序号无洞/幂等命令 UNIQUE）；B 面 src/workspace/{paths,manifest,transactions,revisions,provision}+src/commands/{store,dispatcher,events}（K06.3 journal 故障注入三案、快照不可变与篡改检测、模板收敛）+fixtures/minimal-local；server.ts 换持久 SQLite command store（§13.3 登记）。V04 39/39（node 24.14.1）、V05 24/24、V18 checksum 7 文件、V21 49 资源+31 事件与 lifecycle 契约 12/12（16 张 hypit_* 行级登记入册，含修复 105 批次遗留的 required 计数漂移 18→实际）。修复执行期实 bug：WebClient.Builder 无 bean（本服务无该自动配置）、双 controller 同路由 Ambiguous、requireProjectOwner empty-链 switchIfEmpty 误判 404、null bind 未走 bindNull、事件循环 block() 挪 boundedElastic、provision 失败按 §5.3 统一 202 落 provisioning_failed（原 503 违约）。真实 sidecar 部署外的 IT 以 WireMock 桩验证 Java 侧状态机，文件事务真值由 B/tests/workspace 25 例覆盖——两者合成 K06 全貌。真实第三方渲染 REAL_NOT_RUN（C07 域）。）
- **类型与完成边界**：实现；本卡 AC/TC 和指定 V 通过，输出供 §10 所列后置卡消费；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W04及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K05/K06，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-04-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V04 / V05 / V18 / V21。产物 `ART/C04/`；命令按 107-1 §12.3 的预期退出码与诊断例外逐项核验，必需断言无未解释 skip 才满足 AC。

#### 输入与文件

依赖C03协议。必读最新migration、已有DH/creationstudio repository事务模式、`U/packages/studio/src/source-transaction.ts`。写入K05 migration、J/project/job/command repository/service、B/workspace/commands、对应tests；最小模板fixture也在本卡交付（正式库C20）。

#### 具体步骤

| 步 | 动作 | 检查 |
|---|---|---|
| 04.1 | 按K05生成完整SQL；UUID/account、约束/索引/nullable逐项检查 | 空库/V90升级/重放与约束测试 |
| 04.2 | 实现command put/get/claim/complete与lease；project create事务内生成project+command+job | 同request一次资源，异body409 |
| 04.3 | sidecar provision建立`projects/<id>/work`、`revisions/`、`results/`、独立资产目录，写package边界与显式Result选择 | 工程不放在Distribution源码内 |
| 04.4 | 制作最小无付费模板；复制后保存初始manifest与revision1；失败则provisioning_failed可重试 | PG不假装已ready |
| 04.5 | paths拒绝穿越、symlink、编码路径/空字节；列表屏蔽凭据、内部journal、node_modules | 读写均测试，不能只有PUT守卫 |
| 04.6 | changeset草稿/apply按K06文件journal和CAS；普通save允许坏语法、AIvalidated不允许 | 中途rename失败后恢复一致 |
| 04.7 | snapshot复制/硬链接只读字节（后续head写必须原子replace防改旧inode），锁包/素材hash、固定results配置 | 编译A版本不受head改B影响 |
| 04.8 | GET项目/列表/文件树/源码；PATCH title按metadata version；DELETE先检查活跃工作/依赖并撤销会话 | 404不泄漏他人项目 |
| 04.9 | 事件持久化按job锁递增seq；提供SSE快照/断点；修订变更通知订阅 | 不把评论事件强制编译 |

#### 事务失败恢复

provision文件成功PG未更新→重放同command返回同workspace→补PG。文件apply已commitPG未写revision→读取journal回执→补revision，不再应用第二次。删除先标deleting，清理失败保留任务/原因可重试；被Results引用的资产不删除。

#### 测试

`JT/project/HypitProjectIT.java`、`HypitMigrationIT.java`、`JT/job/HypitCommandIT.java`；`B/tests/workspace/{paths,transactions,revisions}.test.ts`。断言requestId竞态、30s lease/过期认领、原owner过滤、CAS冲突、symlink逃逸、普通坏语法保存、validated拒绝、文件故障恢复、snapshot不变。交接带两个不同owner工程fixture及一个真实可编译revision。

**生命周期核对**：同步本卡 W 组中的三个 registry/baseline 文件，真实资源/事件、属主、派生引用、活跃态、消费及清理按 K05/K06 与本卡 TC 核对；执行 V21，不能仅补登记文本。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

### C107-05 · 素材与完整参考媒体工具

- **执行包/责任人**：107-1 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-05 / AC-107-05；IMPLEMENTED（2026-09-25：V04 60/60、V05 30/30 全绿；K07 上传固化/K08 media.* 工具白名单/K09 Range 代理落地；修复三个自引入真 bug——requireReadyOwner empty 陷阱、ingest -1 哨兵误拒 413、WebClient exchangeToMono 排空连接改 toEntityFlux；yt-dlp 加 --proxy "" 直连防 macOS 系统代理劫持 loopback）。
- **类型与完成边界**：实现；本卡 AC/TC 和指定 V 通过，输出供 §10 所列后置卡消费；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W05及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K08/K09/K13，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-05-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V04 / V05。产物 `ART/C05/`；命令按 107-1 §12.3 的预期退出码与诊断例外逐项核验，必需断言无未解释 skip 才满足 AC。

#### 输入、输出与边界

依赖C04工程/任务/文件。必读`U/packages/video-cli/src/{media,media-frames,frame-grid,transcript}.ts`、`packages/yt-dlp/src/`、`services/yt-dlp/pyproject.toml`，y-1 `MediaReferenceRepository/PresignRequest/LocalMediaStreamer`实际签名。写J/asset、B/tools/media与resource、fixtures、工具schema映射、JT/B tests。

#### 步骤

1. 按K13生成silent-cuts/av-clock/alpha/multi-stream/local-web，ffprobe核验。生成脚本入fixtures，媒体产物ART。
2. 上传stream→staging→hash/probe→asset ready；超限立刻停止并清临时文件；成功前不显示可用。
3. mediaId导入校验媒体owner/status，获取并持久化引用或字节；临时VideoAnalysis地址必须重新固化，不能只存短TTL链接。
4. resourceHandle登记归属、MIME、尺寸/hash、原始角色；Range端点按K09实施，取消关闭文件。
5. 完整实现probe/cut/frames/boundaries；互斥at与range校验；视频流选择保留attached_pic等实际信息。
6. tile/tiles实现around/occurrence/padding/transcript、ranges/everyFrame与分页，不只做九宫格默认值；输出每张grid对应帧时间metadata。
7. yt-dlp用uv frozen环境、argv数组，prepare-fetch有日志；fetch先网络边界检查，follow跳转同策略；输出目标由服务器指定。
8. URL失败可重试确定性下载但不能追加生成请求；清理不完整文件；错误返回来源站挑战/认证/格式不支持的具体原因。
9. Asset删除查revision/Result/运行中引用；未被引用可软删并后续清存储；项目删除不提前破坏共享引用。

#### 验收

| ID | Given/When/Then |
|---|---|
| T05-1 | 上传真实6秒媒体→probe时长/帧/音轨正确；非法MIME/超500MiB拒绝且清理 |
| T05-2 | 取0–10字节→206/正确Content-Range；越界416；他人resource404 |
| T05-3 | around同一句话第二次出现→选中正确occurrence，grid metadata时间属于该区间 |
| T05-4 | everyFrame两区间→原始帧timestamps连续对应，pagination无漏/重复 |
| T05-5 | 真实yt-dlp从本地HTTP fixture下载，ffprobe可读；无外网平台账号也能验证工具本体 |
| T05-6 | URL重定向到不允许地址/路径参数注入→拒绝，无私网读取/任意文件生成 |

文件：`B/tests/media/media-tools.test.ts`、`yt-dlp.test.ts`、`JT/asset/HypitAssetIT.java`。交接Assets+词时间fixture+工具schema+本地真实输出，不把fixture Provider结果冒充模型结果。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

### C107-06 · WhisperX、估时与OpenCV

- **执行包/责任人**：107-1 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-06 / AC-107-06；IMPLEMENTED（2026-09-25：TC107-06-01～04 全过。程序域 `B/src/programs/{catalog,health,manager}.ts`——两服务身份/uv venv 互相隔离（whisperx Python 与 image.opencv 各自 frozen uv sync，上游 lock 不动）、prepare 流式 install.log、up PID 查重+health 轮询（超时保进程可 status 续看）、down SIGTERM→SIGKILL、status state.json 原子写、死 PID 归.down、logs 尾读；`deploy/hypit/prepare-programs.sh` 一键准备两环境。speech 三工具 `B/src/tools/speech.ts`——canonicalEvidence 只做显式 ffmpeg 抽取（RIFF 直通）不暗重采样；standardEvidence 秒→16k 样本、越界窗口丢弃但词文本保留（“缺声学证据保持缺失”不造窗）；measure 原生 @hypit/estimate（zh=Han 字数、pace XOR rate、estimated:true+依据）；align 逐段独立对全量证据（无游标串段），swap 词序仍可解、relation 计数与上游补偿说明保留；whisperxTranscribe 前置 health 身份闸（身份不匹配=program_not_ready 不假 ready）。image `B/src/tools/image.ts`——transform/compose 严格上游 raster_execute.py 词表（unknown op 入口拒）、compose 四字段 frame/fit/interpolation/opacity 全必填、产物写 registry.allowedRoots[0]（registerResource 包含性）、有界进程（5min 超时+stderr 限额）不起常驻池。J `HypitRuntimeController` programs 端点五动作经 sidecar `programs.<action>`，program 名三段白名单、operator 权限。真语音 fixtures（macOS say en/zh→ffmpeg 16k mono s16）+手写词级 catalog 证据。真实 WhisperX 实测 1 例 EXTERNAL_BLOCKED（模型下载属 operator；本地服务实现/命令已交付）。V04 78/77/0/1（node 24.14.1）、V05 30/30、V07 check exit0+test 1053 过+image-opencv 2 过+whisperx-service 18/18。证据 `ART/C06/`。）
- **类型与完成边界**：实现；本卡 AC/TC 和指定 V 通过，输出供 §10 所列后置卡消费；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W06及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K08/K12/K13，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-06-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V04 / V07。产物 `ART/C06/`；命令按 107-1 §12.3 的预期退出码与诊断例外逐项核验，必需断言无未解释 skip 才满足 AC。

#### 输入与输出

依赖C02/C05；必读`U/packages/provider-whisperx-local/README.md`、`provider-image-opencv-local/src/activation.ts`、两service的pyproject/uv.lock、`speech-alignment`与`semantic-take-adjust`入口。写B/tools/speech、program管理、deploy完整环境草案、fixture音频/文本与tests。

#### 步骤

1. Programs目录按endpoint/服务身份隔离；WhisperX与OpenCV不同uv venv，后者固定Python3.13；上游lock不改。
2. prepare显式准备ASR/语言对齐/NLTK，输出进度与install.log；up观察PID和health分别报告，timeout后可继续status而非重复起进程。
3. 本地WhisperX保持loopback/共享输入路径；Normalize证据16kHz mono PCM s16 WAV后调用，返回原raw evidence，不能私自再做不透明重采样。
4. 转换标准word/character时间，检查单调、在音频范围，空语音返回明确空结果；语言由用户/分析指定，不恒写en。
5. 词证据与Script对齐生成SemanticTake使用原生库；补偿/Take adjustment保留原入口，不能自己用等分句子替代。
6. measure调用原生估时，保存language/单位/速度与estimated标记；与真实ffprobe duration区分。
7. OpenCV真实Transform/Compose，覆盖几何、色彩、denoise、alpha、encoding；在同runtime容量体系下限资源，不起不受控Python池。
8. down/status/logs真实操作；模型准备失败显示缺哪类资源，不吞报错成ready。GPU配置保留可选，CPU真实路径优先验收。

#### 验收与交接

`B/tests/media/speech-tools.test.ts`、`programs.test.ts`、`image-operations.test.ts`；原生`pnpm test:whisperx-service`、`pnpm test:image-opencv`。至少一段中文/英文真转写保留字词与合理时间；静音无假字幕；修改Script词序后对齐可解释；去噪/合成结果真实像素/alpha可断言。测试允许语言模型下载缺失标EXTERNAL_BLOCKED，但本地服务实现/命令仍必须交付，不能只留下build arg。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

### C107-07 · 全部Provider、授权桥与凭据

- **执行包/责任人**：107-1 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-07 / AC-107-07；IMPLEMENTED（2026-09-25/26：W07 全清单落地；B providers 27 测 26 过+1 诚实 LIVE skip、G 重建+patch-replay 双绿、J execution/runtime/build 装配、V04 105/103/0/2+V05 39/39+V10 全量绿；真实 live key REAL_NOT_RUN；§13.3 登记四条+堆旋钮一条；证据 ART/C07/）。
- **类型与完成边界**：实现；本卡 AC/TC 和指定 V 通过，输出供 §10 所列后置卡消费；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W07及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K03/K09/K12，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-07-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V04 / V05 / V10。产物 `ART/C07/`；命令按 107-1 §12.3 的预期退出码与诊断例外逐项核验，必需断言无未解释 skip 才满足 AC。

#### 输入、必读与文件

依赖C03/C04；阅读全部provider的activation/mapping/provider和原测试、`U/packages/cli/src/oauth.ts`、credential stores、y-1 `AiExecutionService`真实方法。写B/providers、必要activation/IPC补丁、J/execution与internal auth controller、受控AiExecutionService扩展、tests/fixture Provider server、连接schema。

#### 步骤

| 步 | 动作 | 完成检查 |
|---|---|---|
| 07.1 | 自动列举6远程/4本地Provider全部capability/mapping，模型参数与Provider限制两层保留 | coverage无未映射项，不做笛卡尔积假支持 |
| 07.2 | 连接表单/Runtime Profile校验schema，endpointId/pool/model/bindings完整；基础默认无付费自动调用 | 缺key仅影响对应ready |
| 07.3 | env/file/os/platform store配置与状态；PUT/DELETE受operator权限；文件权限0700目录/0600秘密 | GET与日志/导出无secret |
| 07.4 | OAuth flow保存短时PKCE/state，返回authorize URL，支持107-1§6.2的手工code#state交接、exchange/refresh/注销 | replay/过期/跨owner拒绝，回调不依赖用户访问容器localhost |
| 07.5 | Java Bridge准备operation/ai_run/grant消耗，发一次执行许可；原生Provider invoke/submit前调用 | 未授权实际网络submit=0 |
| 07.6 | typed IPC隔离用户Producer与可信Provider；原生mapping/supports/collect仍运行，secret仅在可信侧解析 | 用户包拿不到key/internal token |
| 07.7 | publicAssetUrl callback从已授权BlobRef取字节→临时HTTPS对象；按需要传真实URL或原生inline/upload方式 | fixture远程端能GET相同hash |
| 07.8 | 回执/轮询/成功/失败/取消幂等；submit失联unknown；重复callback不会再结算/记一次生成 | receipt/source/error脱敏保留 |
| 07.9 | 模拟所有Provider真实请求适配，覆盖不同模型input mode和unsupported边界；有授权才跑live | CONTRACT_PASS和LIVE_PASS单列 |

#### Bridge接口（NEW）

`prepare(operationId,projectId,jobId?,buildId?,needId,capability,model,endpointId,requestHash,grantId)`返回`{permitId,expiresAt,credentialRef}`；内部已验证owner。`complete(operationId,receipt,outputs,actualCost?)`等只能补原operation，不能创建无prepare的执行。

grant并发扣占用在数据库短事务完成；unknown请求保留占用直到有明确处理决定，不因HTTP超时假退款/自动追加请求。允许从plan范围授权即时/批量请求，但每个真实Need有独立operation。

#### 验收

`JT/execution/HypitExecutionBridgeIT.java`；`B/tests/providers/{catalog,authorization,transport,credentials,oauth,provider-contracts}.test.ts`。测试：双并发抢预算、假grant/模型变更、sameop重放、缺credentials、401不自动换账户、上传失败前不submit、submit丢回执、poll多次只一次AI记账、Provider reject准确原因。交接完整目录+授权机制；不把缺live key写成“功能已真验”。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

### C107-08 · 检查、计划、估价与显式复用

- **执行包/责任人**：107-1 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-08 / AC-107-08；IMPLEMENTED（2026-09-26：W08 全清单落地；B planning/vocabulary+7 测全绿（六验收 fixture 齐：坏 Header/类型错/缺包/本地渲染/远程 Need/复用 Output 真渲染）；Java plan/pricing 持久+服务+双控制器实装；V04 112/110/0/2+V05 43/43；真实远程报价 REAL_NOT_RUN；§13.3 登记三条；证据 ART/C08/）。
- **类型与完成边界**：实现；本卡 AC/TC 和指定 V 通过，输出供 §10 所列后置卡消费；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W08及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K03/K07/K08，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-08-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V04 / V05。产物 `ART/C08/`；命令按 107-1 §12.3 的预期退出码与诊断例外逐项核验，必需断言无未解释 skip 才满足 AC。

#### 输入与文件

依赖C04/C07。必读`U/packages/cli/src/{main,run-file,build-planning}.ts`、`compiler-markup-node`、`run-markup`、`project-context-node`、`packages/estimate`。写B/engine/compile-adapter补全、J/build/HypitPlanService、计划repository、相应tests；vocabulary公共查询也由本卡交付。

#### 步骤

1. 按revision读取只读source closure，Header选择frontend；分别处理Author/Recipe/Run，不能仅按后缀猜解析器。
2. 保留所有namespace/包imports与原类型约束；path诊断从物理snapshot转工程相对路径，列号不伪造。
3. check输出ok/diagnostics/exports/closure hash，不启动模型、下载程序或更改Profile。
4. plan按完整Run graph与selected Candidates计算所有needs/targets；记录本地/远程、pending references、request级supports以及缺配置。
5. `build-record/satisfy`读取授权结果并携带准确repository references；已复用Outputs不会重新生成。
6. 生成稳定planHash（canonical内容，不含随机时间/展示文字）、profileHash、sourceRevision、repositoryLocation；存不可变plan记录。
7. pricing只对该plan精确requests读取原provider报价；可能联网刷新OAuth须有当前账户权限；不submit生成。价格未知null，不写0。
8. build预检比较revision/profile/package/capability/grant范围；变化返回409要求重新plan；不得归档时重新plan冒充历史估价。
9. 将Surface/端口/Recipe/模型schema提供给knowledge与高级UI，包目录数据来自实际distribution与当前项目，不扫描未选外部包执行。

#### 验收

`B/tests/engine/planning.test.ts`、`JT/build/HypitPlanIT.java`。用一个坏Header、一个类型错、一个缺包、一个local render、一个远程Need、一个已复用Output的fixture：错误准确；纯本地可能有Needs但远程收费0；复用改变计划且减少新调用；不同revision/grant不可混用；pricing超时不导致Build自动执行。

交接：实际planId/JSON fixture、计划hash算法、Result引用映射、错误码与所有前卡测试通过记录，供C09直接实现submit。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

## 12. 测试、命令与集成验收

### 12.1 需求→AC→TC追踪与逐用例规格

| 需求 | 实现/写入 | 规则/契约 | 边界 | 验收/测试 | 验证 | 证据 |
|---|---|---|---|---|---|---|
| REQ-107-01 | C107-01/W01 | RULE-107-10；本卡 K 引用 | §12.7 中 C01 适用行 | AC-107-01 / TC107-01-01～04 | V01 / V02 | ART/C01/ 原始证据 + §14 对话 |
| REQ-107-02 | C107-02/W02 | RULE-107-02、RULE-107-07、RULE-107-10；本卡 K 引用 | §12.7 中 C02 适用行 | AC-107-02 / TC107-02-01～04 | V01 / V03 / V04 | ART/C02/ 原始证据 + §14 对话 |
| REQ-107-03 | C107-03/W03 | RULE-107-01、RULE-107-02、RULE-107-10；本卡 K 引用 | §12.7 中 C03 适用行 | AC-107-03 / TC107-03-01～04 | V04 / V05 / V06 | ART/C03/ 原始证据 + §14 对话 |
| REQ-107-04 | C107-04/W04 | RULE-107-01、RULE-107-02、RULE-107-03、RULE-107-04、RULE-107-06；本卡 K 引用 | §12.7 中 C04 适用行 | AC-107-04 / TC107-04-01～04 | V04 / V05 / V18 / V21 | ART/C04/ 原始证据 + §14 对话 |
| REQ-107-05 | C107-05/W05 | RULE-107-01、RULE-107-02；本卡 K 引用 | §12.7 中 C05 适用行 | AC-107-05 / TC107-05-01～04 | V04 / V05 | ART/C05/ 原始证据 + §14 对话 |
| REQ-107-06 | C107-06/W06 | RULE-107-01；本卡 K 引用 | §12.7 中 C06 适用行 | AC-107-06 / TC107-06-01～04 | V04 / V07 | ART/C06/ 原始证据 + §14 对话 |
| REQ-107-07 | C107-07/W07 | RULE-107-02、RULE-107-05、RULE-107-07；本卡 K 引用 | §12.7 中 C07 适用行 | AC-107-07 / TC107-07-01～04 | V04 / V05 / V10 | ART/C07/ 原始证据 + §14 对话 |
| REQ-107-08 | C107-08/W08 | RULE-107-01、RULE-107-05；本卡 K 引用 | §12.7 中 C08 适用行 | AC-107-08 / TC107-08-01～04 | V04 / V05 | ART/C08/ 原始证据 + §14 对话 |

规则定义见 107-1 §5.4；具体测试文件/层级见 §12.2，精确命令见 107-1 §12.3。

REQ-107-xx与AC-107-xx同号，对应下面TC107-xx-01～04及卡正文更细断言。测试采用W组所列文件，不另建重复测试体系；每TC写入测试标题或参数化用例metadata，coverage/test evidence可反查。所有反例必须独立触发单个拒绝条件。

#### AC-107-01 / REQ-107-01：完整 vendor、hash 清单、原生测试基线

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-01-01 | 固定commit与源工作树LICENSE有改动 | git archive并核对manifest | 1768文件字节/hash/mode与Git对象一致，122包/3服务/12示例包/28Run/65文档齐全 |
| TC107-01-02 | 正常导出副本 | 分别删文件、改字节、改执行位、加源码 | 四个独立用例非零退出并定位差异 |
| TC107-01-03 | 源码导出完成且不存在原下载目录 | 只用入库manifest校验 | 无需源目录，缓存不入库，真实源码额外文件仍失败 |
| TC107-01-04 | 固定Node/pnpm环境 | 运行全部原生基线 | 记录每条退出码/skip/失败；未运行不写通过 |

自动化落点：W01 的 verify-107-upstream.sh 与临时副本反例，U/package.json 的原生基线脚本；保存各真实结果。执行命令：V01 / V02；证据 `ART/C01/`。

#### AC-107-02 / REQ-107-02：bootstrap/Runtime Host 桥、可重放补丁与最小引擎宿主

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-02-01 | 完整G与正确resolution顺序 | 加载compiler和项目包 | check/plan/真实本地MP4成功，ffprobe有可解码帧 |
| TC107-02-02 | 相同U与补丁清单 | 两次清洁生成G | 运行源码hash相同，U未改，补丁失败立即退出 |
| TC107-02-03 | 恶意项目组件 | 读token/宿主home/别人工程并直连Java | 全部拒绝，合法本地渲染仍成功 |
| TC107-02-04 | 运行中组件或capture程序 | 触发超时或取消 | 进程树退出、槽释放、旧输出不进入下一项目 |

自动化落点：W02的test/IT/contract/spec文件；涉及截图/真实服务额外保留实际运行记录。执行命令：V01 / V03 / V04；证据 `ART/C02/`。

#### AC-107-03 / REQ-107-03：API/工具 schema、Java 身份边界、禁用态/事件协议

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-03-01 | owner A、owner B和非operator账号 | 分别读取A的工程/媒体/SSE或写Runtime | 未登录401，越权资源404，非operator写403且无副作用 |
| TC107-03-02 | exact静态、模板和原prefix路由 | 逐method并测试空段/编码斜线/多段 | 合法匹配，非法404，旧路由语义不变 |
| TC107-03-03 | disabled与未实现依赖 | GET capabilities及对应业务API | 登录用户200 enabled=false，未就绪503，无假成功 |
| TC107-03-04 | JSON fixture含null、UUID与六位小数金额 | Java/TS序列化及解析 | 成功/错误/202信封一致，金额为string，错误status/code保留 |

自动化落点：W03的test/IT/contract/spec文件；涉及截图/真实服务额外保留实际运行记录。执行命令：V04 / V05 / V06；证据 `ART/C03/`。

#### AC-107-04 / REQ-107-04：项目、文件、快照、幂等命令与迁移

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-04-01 | 同账号同requestId | 并发提交同body后提交不同body | 前者单资源/Job，后者409，无二次文件副作用 |
| TC107-04-02 | baseRevision旧或两个编辑器 | save坏语法与validated坏语法分开执行 | 普通保存有诊断；AI应用不移动head；旧revision409保留草稿 |
| TC107-04-03 | 发布第二个文件失败或PG应答丢失 | 重启恢复同command | head为完整旧版或新版，无混版且revision只增一次 |
| TC107-04-04 | 空库/基线升级库/迁移已执行库 | 分别应用迁移并检查FK/唯一索引与重放 | schema一致；非法owner关联/重复键拒绝；历史迁移hash不变 |

自动化落点：W04的test/IT/contract/spec文件；涉及截图/真实服务额外保留实际运行记录。执行命令：V04 / V05 / V18 / V21；证据 `ART/C04/`。

#### AC-107-05 / REQ-107-05：参考素材、链接抓取与完整媒体工具

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-05-01 | silent-cuts、多音轨与词定位素材 | probe/cut/frames/tile/tiles/boundaries | 时长/流选择/词occurrence/多区间分页正确，无漏帧重帧 |
| TC107-05-02 | 真实媒体和两账号 | Range正常/越界/越权请求 | 206 Content-Range准确、416正确、他人404 |
| TC107-05-03 | 500MiB边界输入或伪装MIME | 上传至上限前/等于/超过后并中途断开 | 合法完成，超限413并清临时文件，错误无ready |
| TC107-05-04 | 本地HTTP下载fixture与重定向私网反例 | 调用真实yt-dlp工具 | 允许fixture下载可probe，非法目标拒绝，不执行注入参数 |

自动化落点：W05的test/IT/contract/spec文件；涉及截图/真实服务额外保留实际运行记录。执行命令：V04 / V05；证据 `ART/C05/`。

#### AC-107-06 / REQ-107-06：转写/估时、OpenCV、Python Programs

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-06-01 | 中文、英文、静音fixture | 真实WhisperX转写和alignment | 词序/时间范围可核验；静音不编造字幕；原始证据保留 |
| TC107-06-02 | 图像alpha与几何fixture | 真实OpenCV Transform/Compose | 像素/alpha/尺寸变化可断言，不是JSON伪输出 |
| TC107-06-03 | 模型未准备/服务未健康 | prepare/up/status/down | 分别展示安装、配置、准备、健康；错误不假ready |
| TC107-06-04 | Script语言/语速与同一录音 | measure及语义Take调整 | 估计带estimated，实际duration不被覆盖，词序变化可解释 |

自动化落点：W06的test/IT/contract/spec文件；涉及截图/真实服务额外保留实际运行记录。执行命令：V04 / V07；证据 `ART/C06/`。

#### AC-107-07 / REQ-107-07：全 Provider、凭据/OAuth、外部执行 bridge 与素材传输

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-07-01 | 两个并发Need共用有限grant | 同时prepare并尝试改model/hash | 额度不超占；伪grant/过期/变更请求无remote submit |
| TC107-07-02 | 6远程Provider全部真实adapter+fixture服务 | 遍历真实mapping的submit/poll/collect/error/cancel | 请求/输出和原限制保持，外网付费调用为0 |
| TC107-07-03 | 4种凭据store与OAuth状态 | 重放state/跨owner完成/过期/refresh | 拒绝非法流；合法可刷新，日志导出无secret |
| TC107-07-04 | Provider已收submit但断回执 | 重启/重复complete与查状态 | unknown不重提、operation/ai_run只登记一次；素材传输失败前submit=0 |

自动化落点：W07的test/IT/contract/spec文件；涉及截图/真实服务额外保留实际运行记录。执行命令：V04 / V05 / V10；证据 `ART/C07/`。

#### AC-107-08 / REQ-107-08：编译、计划、价格、候选复用解析

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-08-01 | 坏Header/类型错/缺包与正常Run | check分别执行 | 诊断真实位置或null，未调用生成Provider |
| TC107-08-02 | local/remote/reuse三个计划 | plan和pricing | Needs分类准确、复用减少新请求、未知价null |
| TC107-08-03 | immutable plan及旧grant | 改变revision/profile/模型再请求执行 | 409或明确范围拒绝，不偷偷用新计划 |
| TC107-08-04 | Scalar/Resource Result Candidate | 解析build-record/satisfy与vocabulary | 服务端公共ID转原生ID；仓库来源正确，能力来自实际包 |

自动化落点：W08的test/IT/contract/spec文件；涉及截图/真实服务额外保留实际运行记录。执行命令：V04 / V05；证据 `ART/C08/`。

### 12.2 测试用例执行规格与证据分层

§12.1 的每条 TC 是必须实现的 Given/When/Then，不把同号四条当四个笼统冒烟。下表给出精确文件入口、验证层与固定数据；卡正文中的 T/函数级断言作为同卡 TC 的细分断言继续执行。测试标题/metadata 采用完整 TC107-xx-yy；不得为凑测试数复制断言。

| 卡/适用 TC | 精确实现位置（§2.3 展开缩写） | 数据与层级 |
|---|---|---|
| C107-01 / TC107-01-01～04 | `scripts/acceptance/verify-107-upstream.sh`（临时副本篡改反例）；U/package.json 的 V02 原生脚本 | 固定 Git 对象/导出临时副本；源码诊断 |
| C107-02 / TC107-02-01～04 | `B/tests/engine/bootstrap.test.ts`；`B/tests/engine/compile-adapter.test.ts`；`B/tests/engine/runner-isolation.test.ts`；`B/tests/engine/patch-replay.test.ts` | K13 合成媒体/Provider；实际 runner/工具/存储，网络外部可 fixture |
| C107-03 / TC107-03-01～04 | `B/tests/engine/internal-auth.test.ts`；`ET/UpstreamResolverTemplateTest.java`；`ET/HypitRoutesTest.java`；`JT/api/HypitContractTest.java`；`JT/security/HypitAccessTest.java` | K13 合成媒体/Provider；实际 runner/工具/存储，网络外部可 fixture；Java handler/序列化/权限用例，标 IT 的持久化断言使用真 PostgreSQL |
| C107-04 / TC107-04-01～04 | `B/tests/workspace/paths.test.ts`；`B/tests/workspace/transactions.test.ts`；`B/tests/workspace/revisions.test.ts`；`JT/project/HypitProjectIT.java`；`JT/project/HypitMigrationIT.java`；`JT/job/HypitCommandIT.java` | K13 合成媒体/Provider；实际 runner/工具/存储，网络外部可 fixture；Java handler/序列化/权限用例，标 IT 的持久化断言使用真 PostgreSQL |
| C107-05 / TC107-05-01～04 | `B/tests/media/media-tools.test.ts`；`B/tests/media/yt-dlp.test.ts`；`JT/asset/HypitAssetIT.java` | K13 合成媒体/Provider；实际 runner/工具/存储，网络外部可 fixture；Java handler/序列化/权限用例，标 IT 的持久化断言使用真 PostgreSQL |
| C107-06 / TC107-06-01～04 | `B/tests/media/speech-tools.test.ts`；`B/tests/media/programs.test.ts`；`B/tests/media/image-operations.test.ts` | K13 合成媒体/Provider；实际 runner/工具/存储，网络外部可 fixture |
| C107-07 / TC107-07-01～04 | `B/tests/providers/catalog.test.ts`；`B/tests/providers/authorization.test.ts`；`B/tests/providers/transport.test.ts`；`B/tests/providers/credentials.test.ts`；`B/tests/providers/oauth.test.ts`；`B/tests/providers/provider-contracts.test.ts`；`JIT/ai/run/HypitExternalPreparationTest.java`；`JT/execution/HypitExecutionBridgeIT.java` | K13 合成媒体/Provider；实际 runner/工具/存储，网络外部可 fixture；Java handler/序列化/权限用例，标 IT 的持久化断言使用真 PostgreSQL |
| C107-08 / TC107-08-01～04 | `B/tests/engine/planning.test.ts`；`JT/build/HypitPlanIT.java` | K13 合成媒体/Provider；实际 runner/工具/存储，网络外部可 fixture；Java handler/序列化/权限用例，标 IT 的持久化断言使用真 PostgreSQL |

逐 TC 的共同前置与断言规则：使用两个合成 owner A/B 和一个 operator，除目标变量外保持身份/归属/版本/配置有效；输入限值采用契约上限前/等于/后，null、缺失与纯空白分开；具体输入和期望以 §12.1/卡正文/K 契约为准。时间用固定时钟，竞态用屏障控制先后，重试按契约精确次数，不用随机 sleep。每例在独立工程/事务/临时卷运行，结束仅释放本例创建的资源，失败原始证据保留。

测试中明确记录输入、替身响应/延迟/异常、操作顺序、HTTP/status/code/字段、请求/费用/DB 副作用、最终状态与清理。读权限反例要证明请求确实命中 owner 检查；幂等反例要证明实际 submit/insert 数量；持久化必须刷新/重新查询；媒体必须解码或消费内容。删掉目标保护时相应断言必须失败，不能用零测试、只检查文件存在或页面 body 非空作成功标准。

| 层级 | 允许替身 | 必须真实的对象 | 不能声称 |
|---|---|---|---|
| 单元/组件 | 契约限定的依赖、时钟、请求 | 被测生产函数/组件和断言 | 数据库/跨入口全链通过 |
| 服务/契约集成 | fake-provider-server、确定性 LLM replay | handler/service/序列化；PG 锁/约束/事务用真 PostgreSQL | 浏览器接线或商业模型效果通过 |
| 隔离端到端 | 合成账号/素材、明列的外部模型/Provider | UI 点击、路由、业务 API、PG、runner、真实媒体和结果消费 | 真实收费 Provider/生产已验收 |
| UI 状态/视觉专项 | 控制 loading/empty/error 的精确接口替身 | 目标身份/页面/状态/焦点，拦截确实命中，实际查看明暗图像 | 模拟成功等于真实业务持久化 |
| 授权 live | 仅非目标辅助依赖 | 声称已验证的 Provider、回执、费用和产物 | 一个服务成功代表所有模型组合 |

真实 UI 成功闭环不得 route.fulfill 自己的业务 API；API/SQL 仅可准备前置和观察结果，不能替用户执行目标按钮再截图。跨入口登录/刷新/双主题/390×844 与 1440×900、IME、键盘、空/错/提交中分别保留证据。截图先断言目标组件可见，再实际查看对比度/层级/间距/溢出；登录页或空白截图不算通过。

本轮未执行实现期 TC，不产生任何卡级 VERIFIED；C01 既有证据按 §2.6/§14.1 复核，其余卡保持未实施状态。

### 12.3 本任务验证清单（全系列唯一命令源）

V 编号全系列稳定；本节维护完整命令，107-2/3 与卡只引用编号。每条命令分别记录 cwd、shell、起止时间、退出码、用例数/失败/跳过/缓存及原始证据。本轮未执行实现期验证；C01 已有执行痕迹但本轮未复验，其余验证仍待实施；新增脚本先由注明卡创建，不运行空脚本或零用例冒充通过。

除 V02 原生诊断基线、V11 负向检索外，预期退出码 0，必需用例实际执行且无未解释 skip；命令成功不等于用户闭环通过。V02 失败按 C01 分诊，固定上游缺陷须由 C02 补丁及 V07 运行副本回归闭合。live 缺资源记 NOT_RUN/BLOCKED，不静默替换 fixture。

| 验证 | 准备/副作用与通过标准 | 证据 |
|---|---|---|
| V01/V02/V03 | C01 固定 U 与 manifest；C02 生成 G，安装写独立依赖/缓存；源目录不改。V01 集合/hash/mode 相等；V02 记录真实基线；V03 补丁可重复重放 | 当前卡 ART/Cxx/ 下各命令原日志 |
| V04/V05/V06/V07/V10/V17/V18 | B/U/G 各锁与脚本已建；Java 同 bash 先按 §9.3 选择并核对 JDK25。Testcontainers 写隔离 PG，测试生成 XML/coverage；旧迁移 checksum 不变 | 每轮独立 XML、console、coverage，不复用被覆盖的旧报告 |
| V08/V20/V09 | C21/C22 测试先创建；类型/体积/覆盖率/三入口构建全部运行，现有 changed coverage 门槛不降低 | 前端报告/构建日志；不写功能已验收 |
| V11/V16/V21 | 设计、文档、密钥、diff、生命周期只读检查；工具报告按各自既有目录写，引用进当前卡记录 | 负向检查正确退出码；未跟踪文档额外检查 |
| V12/V13/V14/V19 | 仅使用合成 env 的隔离 Compose；配置校验不启动生产。启动脚本记录项目名、origin、卷/库归属后运行，trap 仅清理本轮资源 | config 脱敏；真实 API/PG/媒体及明暗/移动/键盘截图 |
| V15 | 仅使用已有授权的真实 Provider/预算/账户，可能收费；逐服务记录实际费用/未知项 | 独立 LIVE 状态、回执、预算；secret/签名 URL 不落日志 |

Java 验证前在 platform-java 的同一 bash 会话执行（不在本轮文档修改中启动）：

```bash
source ../scripts/lib/java-runtime.sh
ensure_java_runtime 25 || exit 1
java -version
javac -version
./gradlew --version
```

核对实际 JDK25 toolchain 后才运行对应 V；不得只记录 helper 返回成功。

#### V01 · 源码校验；C01创建

工作目录：`仓库根`。

```bash
bash scripts/acceptance/verify-107-upstream.sh
```

#### V02 · 原生基线；保留失败与skip

工作目录：`platform-hypit/upstream`。

```bash
pnpm install --frozen-lockfile
pnpm check
pnpm test
pnpm check:distribution
```

#### V03 · 生成G及重放补丁；C02创建

工作目录：`仓库根`。

```bash
bash scripts/acceptance/build-107-engine.sh
```

#### V04 · 桥全部测试；首次npm install生成独立lock，之后npm ci

工作目录：`platform-hypit/backend`。

```bash
npm ci
npm run typecheck
npm test
```

#### V05 · 本任务Java/PostgreSQL；不把测试名无匹配当成功

工作目录：`platform-java`。

```bash
./gradlew :services:intelligence-service:test --tests '*Hypit*' --rerun-tasks --no-build-cache --max-workers=2 --console=plain
```

#### V06 · 全部edge路由回归

工作目录：`platform-java`。

```bash
./gradlew :services:edge-bff:test --rerun-tasks --no-build-cache --max-workers=2 --console=plain
```

#### V07 · 适配原生与Python；模型缺失单列

工作目录：`platform-hypit/.generated/hypit`。

```bash
pnpm check
pnpm test
pnpm test:image-opencv
pnpm test:whisperx-service
```

#### V08 · C21视频克隆新工作区单测

工作目录：`仓库根`。

```bash
npm run test -- src/views/video-clone
```

#### V09 · 类型/体积/覆盖率/三入口构建

工作目录：`仓库根`。

```bash
npm run typecheck
npm run lint
npm run test:coverage
npm run coverage:changed
npm run build
```

#### V10 · 共享执行环/媒体旧回归与既有覆盖率门禁

工作目录：`platform-java`。

```bash
./gradlew :services:intelligence-service:check :services:edge-bff:check --rerun-tasks --no-build-cache --max-workers=2 --console=plain
```

#### V11 · 设计文件lint；不能替代真实截图

工作目录：`仓库根`。

```bash
npx @google/design.md lint DESIGN.md
npx @google/design.md lint src/ops/DESIGN.md
```

同目录执行规范词项负向检查：

```bash
grep -ri "sohne\|cal sans\|cal.com\|stripi" DESIGN.md src/ops/DESIGN.md
```

仅此负向检索预期无输出且退出码 1；命中返回 0 为失败，文件/执行错误不算通过。不对包含本命令的任务书全文做禁词检查。

#### V12 · 禁用/基础/完整配置；真实启动另由V13/14执行

工作目录：`仓库根`。

```bash
docker compose -f docker-compose.production.yml config
docker compose -f docker-compose.production.yml -f deploy/hypit/compose.production.yml config
docker compose -f docker-compose.production.yml -f deploy/hypit/compose.production.yml -f deploy/hypit/compose.full.yml config
```

#### V13 · C24创建；三UI浏览器/隔离账号/真实本地链

工作目录：`仓库根`。

```bash
bash scripts/acceptance/ci-e2e-107.sh
```

#### V14 · C23创建部署/恢复测试入口；C24扩充全量核销；本地完整/FS/S3/故障/恢复

工作目录：`仓库根`。

```bash
bash scripts/acceptance/verify-107-full.sh
```

#### V15 · C24创建；只有实际授权服务与预算才能调用

工作目录：`仓库根`。

```bash
bash scripts/acceptance/verify-107-live.sh
```

#### V16 · 文档/秘密/格式；新文件单独扫描

工作目录：`仓库根`。

```bash
npm run docs:links
npm run docs:status
npm run security:secrets
git diff --check
```

#### V17 · 只检查；格式化只能本任务改动文件

工作目录：`platform-java`。

```bash
./gradlew spotlessCheck --continue --console=plain
```

#### V18 · 旧迁移checksum保留；新增迁移由C04真实DB用例验证

工作目录：`仓库根`。

```bash
bash scripts/validate-released-migrations.sh
```

#### V19 · C12创建：隔离API/Studio真实浏览器smoke；C13扩到12Companion/三浏览器，C24复用

工作目录：`仓库根`。

```bash
bash scripts/acceptance/verify-107-studio.sh
```

#### V20 · C22既有入口与跨模块交接回归

工作目录：仓库根；C22创建交接测试后执行。

```bash
npm run test -- src/ai/components/AiWorkspaceNavigation.test.ts src/views/ai-center/components/VideoStudioView.test.ts src/views/video/composables/useCloneReferenceTransfer.test.ts
```

#### V21 · 生命周期真实清单/登记与消费接线

工作目录：`仓库根`；C04/09/10/14/19/23 按实际资源与事件增量登记，C24 最终重验。只读门禁，输出写当前卡证据目录。

```bash
npm run quality:lifecycle
npm run test -- tests/contracts/lifecycle-registration.contract.test.ts tests/contracts/lifecycle-inventory.contract.test.ts
```

必须现场扫描实际 SQL/Java 并命中生产者/消费者/清理代码，TC 引用真实测试文件；登记数量或文件存在不能代替资源清理与事件消费断言。不得删 baseline required 或给新增资源加豁免换绿灯。

### K13. 统一 fixture、测试入口与故障注入
#### K13.1 可重复素材（C05创建）

`platform-hypit/fixtures/` 只入库小体积参数/脚本/必要音频fixture；大生成素材写ART/fixtures。

| fixture ID | 规格 | 断言用途 |
|---|---|---|
| silent-cuts | 6秒，前3秒红、后3秒蓝，30fps，无音频 | probe/boundaries/cut、无语音路径 |
| av-clock | 8秒，时码/位置随帧变，1kHz短音在1/3/5秒出现 | 逐帧/音频对齐/seek |
| alpha-overlay | 2秒带透明移动图形 | alpha Normalize/合成 |
| multi-stream | 主video+封面attached_pic、多音轨 | 流选择不取错封面 |
| speech-en / speech-zh | 明确文本的短录音或许可可用fixture，附标准词序 | 真实WhisperX，不苛求不同模型逐毫秒一致 |
| semantic-reference | 12秒，本地组件画面：持续榜单、两个词事件、一个B-roll、一条评论卡、音效 | 全片理解/复刻/语义重排 |
| local-web | 受控HTML，按钮切换状态，可滚动区域 | capture screenshot/run/录制 |
| fake-provider-server | 本地HTTP实现成功/失败/延迟/未知/取消/取素材分支 | 真实原生adapter运行，外网调用为0 |

fixture生成先ffprobe核验时间/帧/音轨，不能用8字节ftyp占位验证真实成片。测试对模拟LLM标`modelMode=replay`、模拟Provider标`providerMode=fixture`，真实测试标live/local，证据禁止混称。

#### K13.2 固定测试命令（实施后提供）

backend package scripts固定：`typecheck: tsc --noEmit`、`test: node scripts/run-tests.mjs`。runner递归收集`tests/**/*.test.ts`，至少1文件，无匹配非零退出；使用Node24 `--import tsx --test --test-timeout=120000`。`TEST_GROUP`只允许engine/workspace/media/providers/runtime/studio/agent-integration，不任意glob逃逸。

每卡执行其测试组+类型检查；Java对应 `*Hypit<Name>*` 测试，最终module check。前端Vitest `src/views/video-clone`及受影响入口；脚本从根目录启动。命令失败保留stdout/stderr/exit code，不仅保存截图。

#### K13.3 必须故障注入的位置

- 写PG command后、发sidecar前杀Java：恢复后只执行一次。
- sidecar分配engineId后、runtime ack前杀进程：查固定ID，不二次生成。
- Provider已接收、返回receipt前断开：unknown，不盲重发。
- 多文件发布第二个rename失败：恢复后所有文件同一revision。
- 对象上传完成、media row落库前失败：补关联无第二份对象。
- Result写入失败：result_pending，finish恢复且Provider请求计数不变。
- SSE丢连接/重复event：状态不倒退、不额外cancel。
- lease过期后旧worker返回：CAS拒绝旧写，已确定产物仍按operationId幂等收集。
- Studio并行编辑同文件：后一请求冲突，界面保留文本。
- 删除被复用工程：依赖保护，不出现新工程断素材。
### 12.4 仓库候选命令（裁剪说明）

N/A：本任务已在 107-1 §12.3 选定 V01～V21，不复制模板候选目录，也不把全部命令自动用于每卡。

### 12.5 本书集成验收与完成定义

本书八卡的本地/契约验收及所有前置依赖必须有真实证据；“外部实测缺资源”与“实现未完成”分别记状态。后一本读取§10交接物并重跑smoke；不得把本书交接成功说成107全功能完成。最终全量完成由107-3全部能力核销决定。

### 12.6 发布与回滚

开发默认HYPIT_ENABLED=false、各新增Edge写路由关闭；基础未部署可探测disabled。发布顺序为新增迁移→内部宿主/runner健康→Java接口→AI静态资源/nginx→受控启用flags。回滚停新提交、保存receipt/Results、关闭flags与overlay；保留新表/卷，不DROP、不重生成、不覆盖历史迁移。生产操作仍需用户实际授权。

### 12.7 边界目录与适用性（E01～E22）

边界规则唯一源为 [107-1](草场任务书-107-1-Hypit全量引擎迁移与工程底座.md) v3.1.0 §12.7；本书仅登记责任卡/TC。每卡读取适用行并落实独立断言，不复制 22 行 N/A；无适用卡表示本书未新增该类入口，仍保留前置契约。C24 汇总三书责任，不用组合拒绝掩盖单因子失效。

| E | 场景与确定行为 | 本书适用卡/TC | 其他卡N/A原因 |
|---|---|---|---|
| E01 | 空输入：空title/brief/asset列表/command kind拒绝；无素材的纯动画Run允许 | C03 / TC107-03-01～04；C04 / TC107-04-01～04；C05 / TC107-05-01～04；C08 / TC107-08-01～04 | 无新增输入入口，复用前置schema |
| E02 | 超长输入：标题/Brief/文件长度按107-1§5拒绝，不静默截断 | C03 / TC107-03-01～04；C04 / TC107-04-01～04；C05 / TC107-05-01～04 | 无新增长度处理 |
| E03 | 重复提交：同request同资源/Job；异body409；同回执不重复记账 | C03 / TC107-03-01～04；C04 / TC107-04-01～04；C07 / TC107-07-01～04 | 只读/确定性构建步骤，无业务提交 |
| E04 | 网络失败：保留输入/已完成字节与精确错误，收费已发不盲重提 | C03 / TC107-03-01～04；C05 / TC107-05-01～04；C06 / TC107-06-01～04；C07 / TC107-07-01～04；C08 / TC107-08-01～04 | 本地源码清单，无业务网络 |
| E05 | 服务端错误：保留HTTP status/code与上游诊断，清除secret，不空成功 | C03 / TC107-03-01～04；C04 / TC107-04-01～04；C05 / TC107-05-01～04；C06 / TC107-06-01～04；C07 / TC107-07-01～04；C08 / TC107-08-01～04 | 本地失败以退出码/差异文件表示 |
| E06 | 未登录：公开API401、既有登录流；internal无/错token拒绝 | C03 / TC107-03-01～04 | 不新增鉴权入口，复用C03拒绝契约 |
| E07 | 无权限：非operator不能写配置；非owner工程和派生资源404 | C03 / TC107-03-01～04；C04 / TC107-04-01～04；C05 / TC107-05-01～04；C07 / TC107-07-01～04 | 不新增权限入口，复用C03 |
| E08 | 数据为空：空列表/无语音/无Needs正常表示，不伪造内容 | C04 / TC107-04-01～04；C05 / TC107-05-01～04；C06 / TC107-06-01～04；C08 / TC107-08-01～04 | 无业务空列表，源码清单为空必须失败 |
| E09 | 数据过期：revision/profile/grant/session过期拒绝并保留输入 | C04 / TC107-04-01～04；C07 / TC107-07-01～04；C08 / TC107-08-01～04 | 无可变业务版本，使用固定manifest |
| E10 | 页面刷新：URL+API恢复；后端Job继续，Studio会话按TTL恢复 | N/A：本书不增加该类入口；集成卡核销前置证据 | 无页面状态 |
| E11 | 用户快速切换：晚响应不写新project；两session cookie独立 | N/A：本书不增加该类入口；集成卡核销前置证据 | 无前端对象切换 |
| E12 | 组件卸载在途：释放timer/SSE/Blob/observer；不自动cancel已提交Job | N/A：本书不增加该类入口；集成卡核销前置证据 | 无Vue组件，服务进程取消见对应卡 |
| E13 | null/缺字段/纯空白/非法枚举：按schema拒绝；未知费用null不是0；无位置诊断null | C03 / TC107-03-01～04；C04 / TC107-04-01～04；C05 / TC107-05-01～04；C07 / TC107-07-01～04；C08 / TC107-08-01～04 | 输入由前置已校验类型提供 |
| E14 | 数值长度边界：0/负/NaN、上限前/等于/后独立测试；fps正有理数 | C03 / TC107-03-01～04；C04 / TC107-04-01～04；C05 / TC107-05-01～04；C06 / TC107-06-01～04；C07 / TC107-07-01～04；C08 / TC107-08-01～04 | 无新数值参数；构建版本固定 |
| E15 | 并发与乱序：CAS/lease/稳定ID/sequence保护，不覆盖终态 | C04 / TC107-04-01～04；C07 / TC107-07-01～04 | 串行只读检查，无并发业务写 |
| E16 | 超时已提交：查固定command/engineId/receipt，unknown waiting_input不重发 | C04 / TC107-04-01～04；C07 / TC107-07-01～04 | 没有外部提交，确定性操作可有界重试 |
| E17 | 跨账号访问：工程/素材/Results/SSE/Studio/knowledge项目包逐项隔离 | C02 / TC107-02-01～04；C03 / TC107-03-01～04；C04 / TC107-04-01～04；C05 / TC107-05-01～04；C07 / TC107-07-01～04 | 仅固定只读公共包数据，无私有资源入口 |
| E18 | 请求中撤权/删除：取消新副作用、撤销会话、保留可审计结果与引用 | C04 / TC107-04-01～04；C07 / TC107-07-01～04 | 只读步骤无撤权持久资源 |
| E19 | 旧数据/旧客户端：未知schema拒绝；老接口/迁移/路由不改语义 | C03 / TC107-03-01～04；C04 / TC107-04-01～04；C08 / TC107-08-01～04 | NEW模块无旧业务数据，固定源校验 |
| E20 | 部分成功/重放/补偿失败：保留Outputs、归档补偿不重复生成，不清空全批 | C04 / TC107-04-01～04；C07 / TC107-07-01～04 | 原子只读步骤失败即失败，无部分业务提交 |
| E21 | 日期/时区/金额精度：ISO UTC、过期临界前/等于/后；decimal六位、不跨币种求和 | C03 / TC107-03-01～04；C07 / TC107-07-01～04；C08 / TC107-08-01～04 | 无日期金额计算；媒体时间见数值边界 |
| E22 | 超大列表/文件/长文案：分页默认20/max100；文件/压缩限制与有界日志，不全读内存 | C03 / TC107-03-01～04；C04 / TC107-04-01～04；C05 / TC107-05-01～04 | 固定小型配置/manifest，超限输入由前置入口拒绝 |

## 13. 阻塞、技术修订与恢复

### 13.1 只停止受影响链路

实际需要改变公开契约/权限/资金/范围，关键源码不再满足方案且无法兼容，或真实外部资源缺失时，记录BLOCKED及具体影响。普通编译错误、行号漂移、可逆安装、目录/辅助类登记、本地测试失败先按现有规则修复，不逐步询问是否继续。本轮授权仅为按最新模板更新三份任务书；实际实施另按会话授权范围。已有同文件改动能安全保留则增量处理，确实无法安全合并时说明具体冲突。

### 13.2 必需阻塞信息

写清卡/REQ/TC/V、预期与实际、已尝试命令和退出码、具体文件或缺失资源、哪些步骤可继续、恢复条件。缺凭据只影响对应live，缺编译实现不能伪装成“外部服务问题”。已发远程请求receipt未知保留operation与预算占用，不以重建任务规避unknown。

### 13.3 技术修订

同语义内部拆分：有文档修订权限的负责人先在 W 组与全局许可同步登记具体路径/符号/理由/影响测试后继续；已登记文件内的私有 helper 由执行者自行处理。行为/公开schema变化：先修订受影响书的版本、契约和测试映射，保留前卡产物并重新验证；不能让执行者随意选另一架构。上游缺陷只在patch修复，U只读；升级Hypit版本不在本次范围。

## 14. 执行结果、证据与续接

每卡在对话汇报实现行为、准确文件、REQ/AC 完成情况、TC/V 的 cwd、实际命令、退出码、计数和脱敏证据，列出偏差、未验项与下一步。标明 local/fixture/replay/live 及真实验证层，不能以契约通过声称 live 可用。任务表、coverage 与对话记录同步；默认不创建独立完成报告或 handoff.json。测试工具生成的日志、XML、截图、媒体及 coverage 按 §9/§12 保留。

### 14.1 续作检查点

中断、换模型或跨书时，把以下内容直接写入交接对话；若新会话无法访问旧对话，由交接者一并提供该检查点。检查点不能只引用本机被忽略文件，证据缺失可按已入库脚本/fixture 重建，不自动认定通过。

1. 任务书完整相对路径与 v3.1.0、规格/实施状态、实际授权卡范围；当前卡及最后 VERIFIED 卡。
2. 当前 HEAD/分支、已修改与未跟踪文件、本轮写入与原有改动的区别；核对相关 diff，不覆盖其他工作。
3. 已完成行为与尚未完成步骤、对应 REQ/AC/TC/V；代码存在、IMPLEMENTED 与 VERIFIED 分开。
4. 最近命令的实际 cwd/shell、退出码、用例数/失败/跳过、证据位置与测试层级；没有取得结束码的进程写明 PID/会话及观察方式。
5. 当前服务、容器项目名、端口、库/卷归属、模型/工具版本；清理只作用于已确认的本任务资源。
6. schema/manifest/补丁版本、共享文件责任、未决回执/外部阻塞及解除条件；不粘贴密钥、Cookie 或签名 URL。
7. 下一条具体动作/命令及继续所需前置；恢复先核对源码、证据与必要 smoke。现有步骤满足则复用，不从第一卡盲目重做。

全部本地代码完成但必需外部验证未完成时，实施状态为 IMPLEMENTED，另列 externalValidation=NOT_RUN/BLOCKED 及原因；不新增混合状态，也不宣称全系列 VERIFIED。若已有会话授权覆盖全系列，本书出口通过后继续下一书；授权只覆盖本书则交接该书实际结果。

## 附 A：返工记录规则

本轮未出具实现复核结论，暂无返工卡。实施复核出现缺陷时使用R107-卡号-序号，记录原卡、单一失败反例、精确写入组、修复步骤、必须重跑的TC/V、状态；不覆盖历史失败证据、不用历史测试合计数证明修复。

## 附 B：作者发布检查与版本记录

- [x] 唯一分工与串行顺序、前后卡交接、共享文件责任已固定。
- [x] 目标、范围外、不许顺手修、当前源码事实、NEW与FACT区分完整。
- [x] 契约有唯一维护位置；ID、金额、错误、权限、状态、CAS/幂等已定义。
- [x] 精确W组、只读/黑名单、生成物、迁移防撞号与局部文件登记规则已定义。
- [x] 需求→卡/AC→TC→V→证据路径闭合，E01～E22逐项给适用或N/A。
- [x] UI适用卡有DESIGN/双主题/移动/键盘/状态要求；非UI卡不伪造截图。
- [x] 依赖版本、配置、默认禁用、上线边界与保留数据的回滚已明确。
- [x] 事实锚点与未运行边界如实记录；后续新增命令均标创建卡，本轮没有执行运行验收；已有 C01 记录另按 §2.6 复核。
- [x] 全量能力清单未删；外部阻塞不替代功能实现；只有实际测试可标通过。

- [x] §1.7/1.8/1.9 补齐用户闭环、成功标准与规划/实施授权来源。
- [x] §3.1 明确入口、调用、注册/持久化和结果消费；状态唯一源为 107-1 §4 与 107-3 §4.3。
- [x] §10.3/10.4 给出阶段出口、诊断卡边界和高风险独立反例，最终集成责任明确。
- [x] §12.1/12.2 连接规则、W、TC、V 与证据层；命令统一在 107-1 §12.3，边界行为统一在 §12.7。
- [x] §9.3/9.5 覆盖环境、副作用、视图分层、体积、生命周期及目录；新增登记路径已纳入 W 组。
- [x] §14.1 可接续已有工作，默认对话汇报，删除复制的启动提示词；没有把模板升级当实施验收。

本次作者审阅：按首卡至末卡依赖及 REQ 反查入口、契约、测试与消费点；保留原有 K01～14、24 张卡、96 条 TC 和 107-3 附 D 全量清单。结构/链接/状态检查只证明任务书质量，不证明业务通过。规格沿用 READY_FOR_IMPLEMENTATION；C01 已有导入工作，标 IN_PROGRESS 待核验，其余卡不凭文件存在升格。

| 版本 | 日期 | 原因/依据 | 范围 | 实现状态 |
|---|---|---|---|---|
| 3.0.0 | 2026-09-25 | 用户要求参考103/104/106及模板拆成107-1/2/3 | 重组v2.1全部契约/步骤/清单，补齐W/TC/V/边界与交接 | NOT_STARTED |
| 3.1.0 | 2026-09-25 | 用户要求按最新模板更新；规划者完成结构与验证对齐 | 模板 3.0.0；补齐用户场景/接线/阶段风险/证据分层/续作，统一命令并复做作者检查 | C01 IN_PROGRESS（已有导入待复验）；其他 NOT_STARTED；文档检查不等于实现验收 |
