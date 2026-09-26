# 开发任务书：持久执行与Studio及克隆Agent（草场任务书 #107-2）

| 项目 | 内容 |
|---|---|
| 模板 / 任务书版本 | 任务书模板 3.0.0（2026-09-25） / 3.1.0（模板对齐版） |
| 编写 / 事实核验日期 | 2026-09-25 |
| 规格状态 / 实施状态 | READY_FOR_IMPLEMENTATION / IMPLEMENTED（2026-09-26）：C107-09～C16 全部 IMPLEMENTED（逐卡 TC/V 见 §10 卡表与 coverage 契约；头部状态行系 2026-09-26 复核补同步）；真实远程 provider/转写/隔离栈 e2e REAL_NOT_RUN 如实单列 |
| 作者 / 执行与集成负责人 | Codex 编写；承接当前任务书的主程负责卡级验收，107-3 主程负责全系列最终集成 |
| 目标仓库 / 分支 | `/Users/LXH/claude/y-1` / `main`，只记录，不切换 |
| 代码基线 | `a8497ed98de76c59fcb7f82fc578ca89e6be4b68`；已有模板/索引、105fix-1/107 文档及数字人调度相关 Java/测试/config 改动，完整保留；核验明细见 §2.6 |
| 上游基线 | 本地 `/Users/LXH/Downloads/GitHub-project/hypit`；commit `2c320059c1260d0f6f8101472395f91f2f9c8243`；0.2.13 |
| 顶层顺序 | [107-1](草场任务书-107-1-Hypit全量引擎迁移与工程底座.md) → [107-2](草场任务书-107-2-持久执行与Studio及克隆Agent.md) → [107-3](草场任务书-107-3-创作工作区与全量集成验收.md)；共享契约/迁移/锁文件，必须串行 |
| 本书执行范围 | C107-09 → C107-16，8 个细分执行单元；它们合起来是一份顶层任务107-2 |
| 执行模式 | AUTO_CHAIN；起始 C107-09；不要求多agent，用户未授权时不启动子代理 |
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
5. 不缩减全部功能范围；源码复制、iframe可打开、三个模板渲染均不是107全量完成条件。后置任务是必交付范围，不是可选第二期。
6. 执行状态 NOT_STARTED → IN_PROGRESS → IMPLEMENTED → VERIFIED；阻塞为BLOCKED。只有代码写完是IMPLEMENTED；全部本卡 DoD/TC/V/证据（含明确的诊断卡边界）满足才能 VERIFIED。FAIL/NOT_RUN/SKIPPED/PARTIAL 不计运行通过；C01 的诊断交付按第 5 条单独验收，保留原生失败，C02 必须闭合运行副本缺陷。
7. AUTO_CHAIN 仅在实际授权范围内当前卡 VERIFIED 后继续下一卡；IMPLEMENTED/BLOCKED 不解锁依赖。外部环境只阻塞对应实测：继续独立本地实现/契约验证，但不得把有阻塞的卡标VERIFIED；依赖实际交付物没满足就不伪造后卡成功。
8. 默认本地fixture/replay不收费；真实模型/Provider调用须有可核验凭据与预算/范围授权。已授权同范围调用不重复询问；生产发布不在本任务默认授权内。
9. 每条测试保留cwd、命令、起止时间、退出码、总数/失败/跳过、缓存状态和日志路径；Java XML按运行分目录归档。不以旧任务绿灯代替本次验收。
10. 新测试在tests或现有源码就近目录，vendor原生test原样保留。ART/Cxx 存测试工具生成的日志/报告/截图，不新增叙述性的完成/交付报告。前端仅装配，Vue≤800行，现有豁免只减不增。

### 0.3 完成定义（DoD）

当前卡全部步骤、REQ/AC、TC、指定V与影响回归通过；敏感信息零泄漏；未越界且保留原改动；UI适用时截图实际查看；状态、coverage与交接一致。已由当前代码满足的步骤先核验，不重复造功能；未改变且仍有效的证据可说明依据后复用。文档编写阶段没有完成这些运行验收。

## 1. 产品需求、目标与范围

### 1.1 一句话目标

在107-1交付物上建立持久Build/全类型Results、同文档预览、完整Studio与网页Agent，输出可追溯参考分析和可执行复刻方案。

### 1.2 背景与价值

创作者提交长时间生成后需要离开页面、再次打开并继续编辑，不能因 HTTP 断开丢失工作或重复付费。本书把前书的冻结计划接入持久 Build、全部 Results 和完整 Studio，再让 Agent 基于全片证据形成可检查的分析与复刻方案。结果既能人工编辑，也能由后书继续创作和审片。

### 1.3 范围内（可验收需求与责任）

| REQ | 用户/触发场景 | 必须交付的可观察结果 | 优先级 | 对应卡 / AC | 测试 |
|---|---|---|---|---|---|
| REQ-107-09 | 创作者提交计划并离开页面 | 持久 Build、状态、取消、容量、运行记录 | 必须 | C107-09 / AC-107-09 | TC107-09-01～04；V见§10 |
| REQ-107-10 | 创作者查看、下载或复用生成结果 | 全类型 Results、补写、归档、历史/显式复用 | 必须 | C107-10 / AC-107-10 | TC107-10-01～04；V见§10 |
| REQ-107-11 | 创作者预览、逐帧检查或采集网页 | 同源渲染文档预览、快照、网页采集 | 必须 | C107-11 / AC-107-11 | TC107-11-01～04；V见§10 |
| REQ-107-12 | 创作者打开工程专业编辑器 | Studio session/URL/WS/Origin 安全适配 | 必须 | C107-12 / AC-107-12 | TC107-12-01～04；V见§10 |
| REQ-107-13 | 创作者在 Studio 修改画面与参数 | 全 Studio/Companion/主题/语言/写回 | 必须 | C107-13 / AC-107-13 | TC107-13-01～04；V见§10 |
| REQ-107-14 | 创作者提交可恢复的 Agent 工作 | 全 Skill/词汇检索、持久 Agent 与工具执行器 | 必须 | C107-14 / AC-107-14 | TC107-14-01～04；V见§10 |
| REQ-107-15 | 创作者理解完整参考视频 | 全片参考理解与可定位证据 | 必须 | C107-15 / AC-107-15 | TC107-15-01～04；V见§10 |
| REQ-107-16 | 创作者制定替换与材料生成方案 | 克隆方案、材料方向与生成/复用流程 | 必须 | C107-16 / AC-107-16 | TC107-16-01～04；V见§10 |

### 1.4 范围外（明确不做）

不重写认证/钱包/既有数字人和视频管线，不升级根前端依赖/既有Java框架，不修改生产数据、历史迁移、真实凭据或下载源码目录。其他两份任务的业务由其责任卡实现；当前卡只能消费其约定，不抢写共享契约或生成假成功桩。所有原生功能范围在107-3附D，属于系列整体交付。

### 1.5 不许顺手修

不得借本任务修复数字人调度、旧视频制作、认证/钱包、历史迁移或其他未登记问题；不得覆盖工作区已有模板、105fix-1 或 Java 改动。发现无关缺陷在 §14 对话记录，另行安排处理。

### 1.6 用户、入口与已知限制

个人工程以已验证accountId归属；全局Profile/凭据操作限部署operator，普通用户只读脱敏readiness。缺外部账号/模型权重只能阻塞相应live/真实本地验收，不删能力、不声称全部可用；上游未实现的静态抠图mapping如实unsupported，保留扩展契约。商业分发不是本任务目标，仍保留源码许可证与标识。

### 1.7 用户场景与业务闭环

| 场景 | 用户与触发 | 主流程 | 结果与失败出口 | 需求/验收 |
|---|---|---|---|---|
| SC-107-03 | 创作者已确认计划并开始执行 | 提交 Build → 关闭观察页 → Worker 执行 → 重开查询 → 归档/采用结果 | 同一 Build 可恢复；失败有已完成 Outputs；未知远程请求不重发 | REQ-107-09/10；TC107-09-02/03、TC107-10-01/02 |
| SC-107-04 | 创作者需编辑并理解参考 | 打开 Studio → 保存参数 → 预览快照 → 全片分析 → 生成复刻方案 | revision 和画面一致；分析证据可定位；材料缺口进入 waiting_input | REQ-107-11～16；TC107-13-01、TC107-15-01、TC107-16-01 |

### 1.8 产品成功标准

本书八条 REQ 全部通过；Build 在观察断线和服务重启后保持真实终态，三种 Output 类型可消费，Studio 两工程并行不串会话，12 Companion 能按原生支持能力编辑，ReferenceAnalysis 覆盖全片，ClonePlan 的材料依赖可解析。由 C107-16 按 §12.5 负责本书集成。

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

### D-04 身份与会话
业务 API 全经 `IntelligenceCallerResolver` 完整验签与项目 owner 校验。sidecar 仅容器内网、不 publish 端口，internal 使用 `HYPIT_INTERNAL_TOKEN` 时序安全校验；Java 附内部 project/workspace/job 绑定。客户端不能指定磁盘路径、owner、runtime dataRoot 或任意 Provider URL。

Studio 票据由 Java 鉴权后申请：120 秒、一次性，绑定 accountId/projectId/run/revision。兑换后使用**按 session 路径限定的独立 cookie**（HttpOnly、SameSite、生产 Secure），多项目标签页不互相覆盖。默认空闲 30 分钟、硬 TTL 8 小时、并发 2，可配置；删除项目/退出登录撤销会话，WS 同样校验。不记录票据/cookie，入口响应 Referrer-Policy:no-referrer。
### D-05 保留原生 Runtime 语义
复用 `openLocalRuntimeHost` 和 distribution 的提交/Worker 流程，而非自己在 HTTP 内存里造 Build 队列。Profile、项目组件代码按 Build 独立加载；共享 execution store 的资源限流按上游 Endpoint、pool、精确模型、actionLimits、weighted claim 执行。

毕设默认渲染资源预算只允许一项重渲染同时占用，远程等待不锁死所有 Build；这只是部署默认值，不删并发功能。程序重启后区分“HTTP 观察断了、Worker 还在”和“执行上下文丢失”。后者结束该 attempt，保存已完成 Outputs 与远程回执，后续新 Build 显式复用。未知远程结果不自动重提交。
### D-07 Studio 完整嵌入而非裁剪
自写 launcher 复用上游 package/distribution/companion/run/buildLibrary 组装。补丁引入唯一 `sessionBase` URL helper，涵盖 `__studio/session/source/mutation/feedback/library/artifact/artifact-name/document/visual.html/material/storyboard/surface-preview/locales`，以及 Vite module/HMR、字体和素材 URL；禁止用未经解析的全站字符串替换。

外层验证 cookie、session、Origin/CSRF、工程、读写路径；内层转到 localhost Vite，合法转发时改写 Host/Origin 以满足上游本地写保护。不能直接把上游 mutation-origin 改成“允许全部”。两会话并行、反向代理部署、iframe 与新标签均验收。

完整保留 Studio/Comments 布局、12 Companion、时间线、词/Selection/Moment、Inspector、参数写回、素材/任务历史、名称编辑、语言包、音频播放和逐帧预览。上游禁用的绘图/emoji 占位按钮不冒充已实现功能；“评论驱动 Agent 修改”由本版新增 Java 任务承接。

沿用107-1的原样vendor+补丁、可信broker/runner、稳定Results仓库和Java执行环。本书不能另起一套临时Build队列或绕过授权。

### 3.1 端到端接线与职责

以下路径为本任务 NEW 接线，现状核验见 §2；目录缩写按 §2.3 展开，具体可写文件仍以 W 组为准。

| 接线段 | 入口/调用方 → 实现 | 注册、持久化与边界 | 结果消费与验证 |
|---|---|---|---|
| 持久 Build | HypitBuildController → Java command/worker → HypitSidecarClient → dispatcher → EnginePort/原生 Worker | 稳定 command/engineId、快照、PG/Runtime 状态与 Result 位置绑定 | C09 查询/SSE 真实状态 → C10 Results/自动归档 → C21 用户结果页 |
| 结果消费 | 原生 Result Repository → B/src/results/{read,export}.ts → HypitArchiveService → HypitMediaArchiveAdapter | Scalar/Resource/Composite 分型；媒体/outbox 幂等关联；引用保护 | C10 实际下载/解码/复用；C20 工程包与 C22 媒体入口消费 |
| 专业编辑器 | HypitStudioController → 票据兑换 → nginx 会话路径 → B/src/studio/proxy.ts → launcher.ts → 完整原生 Studio | cookie/Origin/WS 校验；sessionBase 重写；mutation-bridge 走 K06 CAS/journal | C12/13 浏览器操作参数 → 源文件重读 → C11 同文档预览/快照 |
| 克隆 Agent | HypitJobController → HypitAgentWorker/HypitAgentStepService → FrozenTextExecutionService → HypitToolDispatcher | 持久 action/checkpoint → 知识与媒体工具 → K11 结构化产物 | C15 ReferenceAnalysis、C16 ClonePlan 持久重读；C17 author 与 C21 面板消费 |

决策来源：本书 D 项沿用 v3.0.0 规划；采用理由、边界和替代限制写在对应 D/K。主程可选择不影响契约的局部实现，不得用新架构绕开隔离、Java 业务归属、授权或原生功能。

## 4. 目标行为与状态

### 4.1 用户流程

`读取工程与需求 → 取知识/证据 → 提议工具调用 → 服务端校验/执行 → 读取结果 → 修改草稿 → check/plan → preview/snapshot → 比对/修复 → 交付`。

- NEW：持久任务由 Java worker 驱动，页面关闭仍继续；不能在单个 HTTP 请求内挂一个数十分钟循环。
- 模型输出结构化 action `{tool, arguments}`，服务器校验允许工具、路径、输入大小、授权和依赖。禁止把自由 shell 命令直接执行。
- 代码变更写 `drafts/<jobId>/` 变更集；显示 diff、受影响文件和 check 结果。应用使用 baseRevision CAS；用户已授权本次自动实现时可自动应用，通过同一 CAS，不重复弹框。
- 首轮 + 最多 2 轮编译修复是一个修复回合；失败保留草稿与错误。完整任务默认最多 30 个 action、单步超时、可取消；达到限制进入 waiting_input，可续接，不能无限烧模型。
- 自定义组件 TS/JS 可生成并在受限执行环境编译；检查通过不意味着代码安全或画面正确，须补预览/快照审查。
- 审片使用选定 Run 的当前画面与原参考按语义事件对齐。评论保存后由用户“按评论修改”或先前授权任务接入；上游原生保存评论并不会主动通知 Agent。

业务与异步状态枚举/转换沿 107-1 §4.3/§4.4；UI 投影沿 107-3 §4.3；Build outcome、Result ready与Job state三个维度分开。页面关闭继续执行，原生失联按事实恢复，不能自动重发未知付费操作。

### 4.2 行为变化与消费

| 场景 | 当前 | 本阶段目标与下游 |
|---|---|---|
| 执行与取结果 | 尚无 Hypit 持久业务调用链 | C09/10 由固定 plan 执行到真实终态与可消费 Results，供 C20/21 使用 |
| 专业编辑 | 尚无鉴权 Studio 代理 | C11～13 双会话安全编辑、预览、保存；写回同一 revision |
| 分析与方案 | 尚无克隆 Agent | C14～16 基于全片证据落库分析/方案，缺材料进入 waiting_input，供 C17 消费 |

### 4.3 状态与迁移引用

业务/异步枚举、取消与失败副作用唯一见 107-1 v3.1.0 §4.3/§4.4 及 K05/K06；Studio/Agent 限制见本书 K10/K11，页面投影唯一见 107-3 v3.1.0 §4.3。这里不新增状态或放宽终态覆盖规则。

## 5. 业务规则与不变量

唯一规则源为 [107-1](草场任务书-107-1-Hypit全量引擎迁移与工程底座.md) v3.1.0 §5（RULE-107-01～10）及 §6/§7 的精确契约。本书只按 §12.1 映射消费，不复制 provision、Build、保存、归档、删除或回执规则。Studio/Agent 的专属判断以 107-2 §6 K10/K11 为准，UI 状态及交互以 107-3 §4.3/§8 为准；服务端与前端均不能绕开共用校验。

## 6. 接口与函数契约

业务HTTP/DTO统一沿107-1§6；本书负责Build/Results/预览/Studio/Agent/分析/方案端点的真实实现。以下K10/K11是会话与AI结构化产物唯一维护位置。

### K10. Studio 和作者代码的边界
#### K10.1 会话数据与代理

Session保存 `{id,accountId,projectId,workspaceId,runFile,revision,readOnly,basePath,vitePort,createdAt,lastSeen,expiresAt,revokedAt}`。cookie名称`hp_studio`可相同但Path必须精确`/hypit-studio/s/<id>/`；票据兑换URL为`/hypit-studio/t/<ticket>`，Set-Cookie后303跳固定base，不接受客户端next URL。

launcher复用原start.ts域装配，Vite host=127.0.0.1，port=0后读取实际端口；不固定端口池。proxy将外部session前缀转换为上游基路径，Vite base/HMR clientPath同步。代理只允许归属session的资源，不是通用open proxy。

建立补丁 `0001-studio-session-base.patch`，集中URL函数输出：session/source/mutation/feedback/library/artifact/artifact-name/document/visual.html/material/storyboard/surface-preview/locales。查询参数用URLSearchParams，resource/name编码，避免斜线或中文名失真。复查UI/material renderer/runtime-shim和Vite import资源；必须有“不请求站点根/__studio”的浏览器断言。

#### K10.2 统一源码写入

补丁 `0002-studio-workspace-transactions.patch`：将原 `replaceSourceFiles`/source PUT/parameter mutation 的最终提交委托给注入writer。writer先检查session非readOnly，再走K06；未注入时原独立CLI Studio保留原行为，不破坏上游使用。

参数值/UTF-16 source ranges由原生计算，不在y-1正则改XML。返回冲突沿用原生UI可显示的错误，保存失败保留编辑输入。同一project开两session必须共同冲突检测；不同project不共享写锁。

#### K10.3 主题与预览

补丁 `0003-studio-y1-theme.patch` 映射现有Studio CSS变量到根DESIGN token，外层Vue主题变化发 `themeChanged`，session启动传初值。JS消息检查 `event.source===iframe.contentWindow` 与session nonce；不依赖`origin='null'`作为身份。

作者画面运行在**不带allow-same-origin的sandbox iframe**，不得获得Studio cookie/父document；素材按nonce/session受控提供。需要图片/字体跨源请求的响应只允许指定预览方案，不给任意Origin加credential CORS。若opaque-origin导致WebAudio素材读取受限，由父层授权取字节，通过已验证的消息桥传 ArrayBuffer，子 iframe 自行创建 Blob URL，并在会话结束 revoke；不得通过取消sandbox解决。

代码组件服务端也是可执行代码，不能仅靠iframe声称隔离：

- Java/API/credential broker与作者Node子进程分离；作者进程不继承HYPIT_INTERNAL_TOKEN/provider secrets、不挂凭据目录/宿主home。
- 全部编译器、Source package discovery、生成组件load和capture脚本在受限作者进程执行；node:http宿主不得import用户package直接执行。
- 实现层采用部署时单独的受限execution服务/固定worker进程环境，运行项目只读snapshot、自己的临时目录；网络默认拒绝，远程Need由broker按计划代理，原生Provider传输在可信进程内执行。
- 不将Docker socket挂进sidecar动态创建容器。部署层定义所需隔离服务、网络与volume。CPU/memory/timeout均有上限；取消清理子进程树。
- 如果当前上游默认in-process Producer无法满足该边界，C02/C07必须通过可审计补丁实现typed command IPC，不把库纯函数当安全沙箱；原生CLI单用户开发模式保留但不用于网页AI生成代码的隔离保证。

### K11. Agent 执行协议与结构化产物
#### K11.1 scope 与状态

```json
{
  "readAssets":["uuid"],
  "allowedTools":["media.probe","media.tiles","transcribe","snapshot"],
  "writeProject":true,
  "applyChanges":"manual",
  "maxSteps":30,
  "maxRepairRounds":2,
  "grantIds":[],
  "intent":"analyze"
}
```

服务器按intent/权限收窄allowedTools，不能以客户端给的工具名扩权。applyChanges=manual或authorized_auto；自动应用同样必须CAS+check，用户原授权覆盖才用自动。LLM文本预算沿y-1既有执行环，外部生成授权沿grant。暂停等待不会释放已经产生的回执/材料。

每步：claim job → persist prepared action → executeIndependentPrepared保存ai_run_id → 调模型 → 校验action JSON → 执行工具或保留草稿 → persist结果和checkpoint → 更新事件 → 下一步。LLM返回无效JSON最多同回合修复2次，超限waiting_input；不得吞解析错误使用空对象继续。

#### K11.2 模型输出（NEW）

```json
{
  "action":"tool",
  "tool":"media.tiles",
  "arguments":{"assetId":"uuid","start":0,"end":4,"every":0.25},
  "reason":"确认开头标题进入与主讲人口型的时间关系"
}
```

action枚举 `tool | propose_changes | ask_input | finish`。propose_changes需 `{baseRevision,changes,summary}`；ask_input需 `{question,blockedReason,neededAssetRoles}`；finish需 `{summary,deliverableIds,unresolvedQuestions}`。不能让模型在finish里宣告Build成功，状态以真实引擎事实为准。每action一次工具调用，批次通过服务端计划分解；无自由shell action。

#### K11.3 ReferenceAnalysis v1

必填：`schemaVersion=1,referenceAssetId,sourceSha256,durationSeconds,language,audioPresence,coverage,transcriptAssetId|null,systems,events,summary,openQuestions`。

- coverage[]=`{start,end,evidenceAssetIds,inspection:overview|dense|every_frame}`，并集需覆盖[0,duration]；未覆盖段进入openQuestions，UI不得标分析完成。
- systems[]=`{id,kind,description,role,appearance,temporalBehavior,evidenceIds,confidence}`。kind为aroll/broll/caption/typography/ranking/deck/sticker/overlay/audio/scene/other。
- events[]=`{id,start,end,systemIds,spokenText|null,meaning,evidenceIds,observed,inferred}`，观察与推断分开。
- 帧采样用于证据覆盖而不是宣称看到两帧之间一切行为；发现高频变动/字幕切换时加密该区间，保留实际采样密度。
- ANALYSIS.md/TIMELINE.md由该结构生成可读文本，用户编辑后的修订保留出处，不做两份互相覆盖的主数据。

#### K11.4 ClonePlan v1

必填：`schemaVersion,projectId,baseRevision,referenceAnalysisIds,goal,transformations,roles,products,script,canvas,systems,materials,runs,reuse,costs,openQuestions`。

- transformations[]=`{axis,sourceEntityId,targetDescription,preserve,replace}`；缺事实不能编造产品功效/价格。
- roles[]=`{id,name,portraitAssetIds,voiceAssetId|null,occurrences}`；occurrences显式列aroll/avatar/broll/graphic等位置。
- script=`{language,segments:[{id,roleId|null,displayText,spokenText,selections,moments}]}`；最终语法由Script包生成，不能把这些JSON直接当SVML。
- canvas=`{width,height,frameRate:{numerator,denominator}}`；画幅变化重新验证Frame布局和字幕宽度。
- systems[]=`{id,sourceSystemId|null,component,semanticBindings,recipeChanges,evidenceIds}`；semanticBindings区分selection/moment/clock，不复制原秒数当新词时间。
- materials[]=`{id,kind,operation,model,endpointId,inputRefs,parameters,dependsOn,reuseOutput|null,expectedOutput}`；每条依赖必须可解析、无环、引用有权。
- runs[]=`{id,path,targetNames,purpose}`；scope属于同一project；材料生成Run与最终film Run可分离。
- 未选模型/缺凭据/未定材料标openQuestions/readiness；不返回任意provider URL或secret。

#### K11.5 审片与修复

ReviewResult=`{revision,runFile,snapshotIds,checks,issues,proposedChangesetId|null}`。checks包含画幅/帧范围/字幕溢出/音轨/材料就绪/已解析语义关系；issues包含时间点、组件或文件、现象、依据、建议，不用“整体不错”替代证据。

按意见修改最多一个明确repair回合；超过上限保存工作等输入，禁止为了无限修画面持续付费。反馈resolve只有修改已应用并生成对应预览证据后执行；不自动删除评论。
## 7. 数据模型、迁移与并发

所有PG表/约束/索引与bridge.sqlite沿107-1§7（K05/K06），由C04建立；本书不重建同名表、不修改已执行迁移。Build公共UUID与engineBuildId分离，资产/结果引用保护、Unknown回执和source journal保持。

本书补齐hypit_build/output/job_action及Agent字段的持久读写；Studio源写事务复用journal，FEEDBACK只改feedbackHash。Result Repository跨revision稳定，finish只补写不重执行。API/worker重启与文件/PG错位必须故障注入。

## 8. UI与交互规格

C12/13负责完整嵌入Studio：Source、Timeline、Inspector、Tasks/Artifacts、Comments、12官方Companion与自定义扩展均保持。所有可编辑字段实际写回，derived/fixed只读。UI实现细节与完整验收逐步见C12/13。

使用根 DESIGN 已有 token，新增需暗亮成对；`.gl-field`、`.gl-btn-primary`、`.glass-card`、`GlModal`、`EmptyState` 优先复用。Studio 控件适配是补丁范围，不篡改作品的作者配色。

桌面主工作区并列证据/预览与编辑，窄屏改纵向，时间轴可局部横滚；导航提供可见文本。所有非拖拽操作可键盘触达；时间线拖拽提供 Inspector 数值输入；输入控件聚焦时不触发播放器快捷键；IME 回车不误提交。弹窗焦点陷阱、关闭恢复焦点；错误与进度使用合适 live region。

每个受影响页面做亮/暗截图，至少桌面 1440×900 与移动 390×844：项目空/有数据、素材导入中/失败、完整参考分析、方案待生成、缺密钥、生成中/取消、编译错误/保存冲突、审片/评论、批次部分失败、结果待补写、未启用、Studio 主要面板。截图写 `test-artifacts/task-107/Cxx/`，检查层级/对比度/溢出与焦点，不因是第三方 iframe 豁免应用 UI 规范。
## 9. 全局约束与精确写入白名单

### 9.1 权限、生成物与黑名单

每W组为精确文件集合，NEW路径仅指设计落点，执行时先检查是否已存在。共享文件由前卡建立、后卡仅补自己负责端点/功能；先复核契约再接线，不能重置前卡实现。U导入与知识打包是显式按manifest枚举的有限集合例外。其余内部新文件先登记具体路径与所属W组再写，不以目录通配获得任意写权限。

通用可写：本107系列三份任务书与任务索引中当前卡状态/已批准技术修订、`contracts/hypit-coverage.v1.json`中本卡条目。证据仅`test-artifacts/task-107/`；临时脚本仅`scripts/local/`；G是按patch生成的忽略产物，不手改。根npm锁不并入Hypit workspace，B/package-lock由C02创建，后卡不擅自升级。

黑名单：原下载Hypit仓库全部文件、已导入U的直接修改（C01导入除外）、已执行迁移、真实env/凭据、无关业务/依赖/目录、生产数据。只读：根AGENTS/DESIGN、模板、103/104/106、platform-storage接口及原业务风格；只读不产生修复权限。V91若已占用按当前最大版本新增，必须先把实际迁移文件登记W04，不能覆盖。

### W09：C107-09 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `B/src/engine/runtime-adapter.ts`
- `B/src/runtime/observer.ts`
- `B/src/runtime/capacity.ts`
- `B/src/runtime/logs.ts`
- `J/build/HypitBuildService.java`
- `J/build/HypitBuildRepository.java`
- `J/build/HypitBuildObserver.java`
- `JT/build/HypitBuildIT.java`
- `B/tests/runtime/submit.test.ts`
- `B/tests/runtime/recovery.test.ts`
- `B/tests/runtime/capacity.test.ts`
- `B/tests/runtime/cancel.test.ts`
- `B/tests/runtime/events.test.ts`
- `contracts/hypit-coverage.v1.json`
- `B/src/commands/dispatcher.ts`
- `J/api/HypitBuildController.java`
- `J/api/HypitJobController.java`
- `J/api/HypitRuntimeController.java`


- `tests/contracts/resource-lifecycle.registry.json`
- `tests/contracts/event-consumers.registry.json`
- `tests/contracts/lifecycle-inventory.baseline.json`

仅登记本卡实际新增/接通的资源与事件、真实 owner/清理/消费符号及对应 TC；C04 建表时登记现有生产路径，后卡随实现接入增量核实。baseline 只添加真实必需项，不删除既有 required 或追加豁免规避门禁。

### W10：C107-10 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `B/src/results/repository.ts`
- `B/src/results/read.ts`
- `B/src/results/export.ts`
- `B/src/results/presentation.ts`
- `B/src/results/actions.ts`
- `B/src/results/reuse.ts`
- `J/build/HypitOutputRepository.java`
- `J/build/HypitResultService.java`
- `J/asset/HypitArchiveService.java`
- `JI/media/HypitMediaArchiveAdapter.java`
- `JT/asset/HypitArchiveIT.java`
- `JT/asset/HypitOutputIT.java`
- `B/tests/runtime/results.test.ts`
- `contracts/hypit-coverage.v1.json`
- `B/src/commands/dispatcher.ts`
- `J/api/HypitBuildController.java`


- `tests/contracts/resource-lifecycle.registry.json`
- `tests/contracts/event-consumers.registry.json`
- `tests/contracts/lifecycle-inventory.baseline.json`

仅登记本卡实际新增/接通的资源与事件、真实 owner/清理/消费符号及对应 TC；C04 建表时登记现有生产路径，后卡随实现接入增量核实。baseline 只添加真实必需项，不删除既有 required 或追加豁免规避门禁。

### W11：C107-11 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `B/src/preview/sessions.ts`
- `B/src/preview/bridge.ts`
- `B/src/tools/capture.ts`
- `B/src/tools/snapshot.ts`
- `J/preview/HypitPreviewService.java`
- `B/tests/media/preview.test.ts`
- `B/tests/media/capture.test.ts`
- `B/tests/media/snapshot.test.ts`
- `contracts/hypit-coverage.v1.json`
- `B/src/commands/dispatcher.ts`
- `J/api/HypitStudioController.java`
- `B/tests/studio/preview.test.ts`
- `B/tests/media/audio-clock.test.ts`

### W12：C107-12 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `B/src/studio/launcher.ts`
- `B/src/studio/sessions.ts`
- `B/src/studio/proxy.ts`
- `B/src/studio/url-policy.ts`
- `B/src/studio/mutation-bridge.ts`
- `platform-hypit/patches/0001-studio-base-path.patch`
- `platform-hypit/patches/0002-studio-mutation-bridge.patch`
- `J/studio/HypitStudioSessionService.java`
- `deploy/hypit/nginx.locations.conf`
- `B/tests/studio/sessions.test.ts`
- `B/tests/studio/proxy.test.ts`
- `B/tests/studio/base-path.test.ts`
- `B/tests/studio/mutation-bridge.test.ts`
- `contracts/hypit-coverage.v1.json`
- `B/src/commands/dispatcher.ts`
- `J/api/HypitStudioController.java`
- `platform-hypit/patches/manifest.json`
- `scripts/acceptance/verify-107-studio.sh`
- `tests/e2e/hypit-studio.spec.ts`

### W13：C107-13 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `platform-hypit/patches/0003-studio-y1-theme.patch`
- `platform-hypit/fixtures/companions/manifest.json`
- `platform-hypit/fixtures/companions/main.svml`
- `platform-hypit/fixtures/companions/main.svrun`
- `B/src/studio/theme-tokens.ts`
- `B/tests/studio/companion-parity.test.ts`
- `tests/e2e/hypit-studio.spec.ts`
- `contracts/hypit-coverage.v1.json`
- `platform-hypit/patches/manifest.json`

### W14：C107-14 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `JR/hypit/knowledge/`：构建资源集合例外；仅复制知识 index.json 登记的固定来源 65 份文档及依赖 README，内容/hash须与U一致，不允许自由新增提示替代原文。
- `J/agent/HypitAgentWorker.java`
- `J/agent/HypitAgentStepService.java`
- `J/agent/HypitToolDispatcher.java`
- `J/agent/HypitKnowledgeService.java`
- `J/agent/HypitAgentAction.java`
- `J/agent/HypitAgentScope.java`
- `J/job/HypitJobActionRepository.java`
- `platform-hypit/knowledge/index.json`
- `platform-hypit/knowledge/build-index.mjs`
- `JR/hypit/knowledge/index.json`
- `platform-java/services/intelligence-service/build.gradle.kts`
- `JT/agent/HypitAgentIT.java`
- `JT/agent/HypitToolScopeTest.java`
- `JT/agent/HypitKnowledgeTest.java`
- `contracts/hypit-coverage.v1.json`
- `B/src/commands/dispatcher.ts`
- `J/api/HypitJobController.java`
- `J/api/HypitKnowledgeController.java`


- `tests/contracts/resource-lifecycle.registry.json`
- `tests/contracts/event-consumers.registry.json`
- `tests/contracts/lifecycle-inventory.baseline.json`

仅登记本卡实际新增/接通的资源与事件、真实 owner/清理/消费符号及对应 TC；C04 建表时登记现有生产路径，后卡随实现接入增量核实。baseline 只添加真实必需项，不删除既有 required 或追加豁免规避门禁。

### W15：C107-15 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `J/agent/HypitReferenceAnalysisService.java`
- `J/agent/HypitReferenceAnalysis.java`
- `JR/hypit/prompts/reference-analysis.md`
- `JT/agent/HypitReferenceAnalysisIT.java`
- `platform-hypit/fixtures/semantic-reference/main.svml`
- `platform-hypit/fixtures/semantic-reference/main.svrun`
- `contracts/hypit-coverage.v1.json`
- `B/src/commands/dispatcher.ts`
- `J/api/HypitJobController.java`
- `JT/agent/HypitReferenceAnalysisTest.java`

### W16：C107-16 的唯一写入集合

下面每一项是单个文件。`NEW` 文件由本卡创建，已存在文件仅修改本任务所属符号；测试必须承接 §12 对应 TC。卡内简写目录只用于说明职责，不扩大本表。

- `J/agent/HypitClonePlanService.java`
- `J/agent/HypitClonePlan.java`
- `J/agent/HypitMaterialGraph.java`
- `JR/hypit/prompts/clone-plan.md`
- `JT/agent/HypitClonePlanIT.java`
- `JT/agent/HypitClonePlanTest.java`
- `JT/agent/HypitMaterialFlowIT.java`
- `contracts/hypit-coverage.v1.json`
- `B/src/commands/dispatcher.ts`
- `J/api/HypitProjectController.java`

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
| C107-09 | 持久 Build、状态、取消、容量、运行记录 | REQ-107-09 / AC-107-09 | W09 | 08 | TC107-09-01～04；V04 / V05 / V21 | IMPLEMENTED（2026-09-26：B runtime 三模块 observer/capacity/logs+runtime-adapter 观察/分配/openResults+dispatcher build.submit/inspect/cancel/logs/activity 五 kind（K06.2 固定 engineBuildId 先落库再提交、容量闸默认 1 并发本地 render 远程等待放槽、取消幂等终态零触、日志两源去重脱敏、watchCapacityRelease 失败开闸有界）+5 测试文件 23 测全绿；J 面 HypitBuildRepository（advance 前进式 CAS+COALESCE 只补不清/lifecycleRank 守卫）/HypitBuildService（202 短事务 command+build+job、grant 门禁远程 page 计费必带、applyObservation 终态不倒退、事件投影 checkpoint 承载摘要+终帧收口 job 状态、未达引擎取消就地 incomplete+cancelled、logs 不可达如实 503、operator 全局 activity/logs）/HypitBuildObserver（kind 过滤断言 SKIP LOCKED、租约重排、attempt 预算落 failed）+HypitBuildIT 9 用例（真 PG：幂等回放/grant 门禁/前进式+终帧一次/失败保留 Outputs/取消幂等/引擎不可达回落/owner 隔离/observer 预算）；控制器 builds 六端点+events SSE+jobs SSE+runtime activity/logs（operator）实装；V04 135 测/133 过/0 挂/2 诚实 skip、V05 52/52、V21 49 资源+31 事件（hypit_build 行按真实 lifecycle 枚举增量核实，event-consumers 不动如实——job_event 非信封事件扫描器不可见）；已知边界如实：sidecar 未部署时 build.submit 派发退 queued 重试至 attempt 预算落 failed，Build 本体保留提交意图；真实引擎全链 REAL_NOT_RUN） |
| C107-10 | 全类型 Results、补写、归档、历史/显式复用 | REQ-107-10 / AC-107-10 | W10 | 09 | TC107-10-01～04；V04 / V05 / V10 / V21 | IMPLEMENTED（2026-09-26：B results 六模块（repository 真根解析 location.root≠仓库根实锤、read 索引/describe 不扫目录、export Scalar JSON/Resource 句柄字节/Composite value.json+逐资源句柄/external 报确切依赖不导空包、presentation 原仓写回不改身份、actions finish 走无 Provider 的 result control/discard 非未激活 409、reuse 原生 build-record+satisfy 拼接当前 Run 文本为 changeset 片段）+dispatcher 七 kind+results.test.ts 3 测（原生格式多 Output fixture+真实 chat 渲染真实媒体下载）；J 面 HypitOutputRepository（UNIQUE(build,output) 可重入索引、claim CAS、completeArchive COALESCE 只补 mediaId）、HypitResultService（索引/导出/补写/动作/历史/复用+plan/pricing 快照随详情）、HypitArchiveService+HypitArchiveDownloader（claim→export→内部资源端点下载→既有媒体管线）、HypitMediaArchiveAdapter（确定性 mediaId=nameUUID(outputId)+findByObjectKey 幂等——T10-4 PUT 后 DB 故障重放收敛同 mediaId/key 单行）；控制器 outputs/output/presentation/result-actions/archive 实装，output-history/reuse 桩接线（§13.3 辅助修改已登记）；HypitOutputIT/HypitArchiveIT（真 PG+WireMock sidecar+内存对象存储）；V04 138 测/136 过/0 挂/2 诚实 skip、V05 56/56、V21 49+31（hypit_output 行按真实 archive_state 增量核实）；真实媒体下载经真实渲染验证、真实外部存储/远程 Provider REAL_NOT_RUN） |
| C107-11 | 同源渲染文档预览、快照、网页采集 | REQ-107-11 / AC-107-11 | W11 | 05/08/09 | TC107-11-01～04；V04 | IMPLEMENTED（2026-09-26：B preview/sessions（(project,run,revision) 三元绑定、瞬态 authoring execution 白名单不建 Build、缺素材如实 missingMaterials 不造占位、关闭 revoke 不触碰渲染中 Build）+preview/bridge（__hypit* 原生 shim 协议驱动不改 source、48kHz 节目钟映射首/中/末帧 seek 无漂移、loop 包络不越源窗、gainEnvelope/audibility/fade 原样透传经原生 audioEnvelopeGainAt 评估）+tools/snapshot（renderHyperframesFrames 原生精确帧/分页网格、快照不建导出 Build）+tools/capture（capture Chrome 独立 version/cache 与渲染 Headless Shell 分离、capture.run 页内沙箱默认禁网+有界超时+输出根逃逸拒绝）+dispatcher preview.session kind+5 测试文件 15 测；J 面 HypitPreviewService+preview-sessions 端点实装；V04 153 测/151 过/0 挂/2 诚实 skip；真实浏览器帧摄影沿引擎真实渲染测试同 provider 通道） |
| C107-12 | Studio session/URL/WS/Origin 安全适配 | REQ-107-12 / AC-107-12 | W12 | 04/10/11 | TC107-12-01～04；V04 / V05 / V19 | IMPLEMENTED（2026-09-26：patch 0001（HYPIT_STUDIO_BASE_PATH 前缀在 URL 解析前剥离，会话可挂 /studio/<id>/）+0002（HYPIT_STUDIO_BRIDGE_TOKEN bearer 桥授权、未设时 localhost/同源规则不变），随 0003 过 build-107-engine.sh patch verification 5/5 并重建真实 G（pnpm 10.33.0/node24）；B studio 五模块 url-policy（HMAC 单次 ticket+会话绑定+timingSafe+base path 校验）/sessions（同 Run 复用活跃会话+单次票+过期收口）/proxy（仅 ws 升级+跨源拒+同会话前缀+路径逃逸拒）/mutation-bridge（只读拒+revision 一致 409+操作数/字节预算+token 强度）/launcher（detached 启动+token 仅子进程 env）+4 测试 9 测；J 面 HypitStudioSessionService+studio-sessions 端点；nginx.locations.conf 会话 location（同源守卫+WS 升级头）；verify-107-studio.sh（V19 exit0）+tests/e2e/hypit-studio.spec.ts（HYPIT_E2E 门禁显式 skip）；V04 162 测/160 过/0 挂/2 诚实 skip、V05 绿） |
| C107-13 | 全 Studio/Companion/主题/语言/写回 | REQ-107-13 / AC-107-13 | W13 | 12 | TC107-13-01～04；V04 / V11 / V19 | IMPLEMENTED（2026-09-26：patch 0003-studio-y1-theme（y-1 token 块注入 Studio shell：#533afd/#4434d4/暗表面/Space Grotesk+Inter/圆角；theme-tokens.ts 单一来源防漂移、assertNoExternalFonts 拒外部字体 CDN），过 build-107-engine.sh patch verification 并重建真实 G；fixtures/companions manifest+svml+svrun 覆盖全部 12 官方 Companion 族（id 集合精确断言）；companion-parity.test.ts 4 测（12 族入口存在+editable 非空+补丁 token 落 G+无 CDN）；字段级 Inspector parity 由上游 *-studio 原生套件承载（结构网在本仓）；V04 含 parity 4/4、V11 design lint exit0+负向无输出、V19 exit0） |
| C107-14 | 全 Skill/词汇检索、持久 Agent 与工具执行器 | REQ-107-14 / AC-107-14 | W14 | 03/04/07/11 | TC107-14-01～04；V04 / V05 / V21 | IMPLEMENTED（2026-09-26：knowledge/build-index.mjs 从冻结 upstream skills 索引 SKILL.md+64 references=恰 65 份（path/sha256/title/topic/sourceCommit=2c320059+内部链接闭合检查），同份索引发 platform-hypit/knowledge 与 JR resources；Java HypitKnowledgeService（检索 topic/关键词带 snippet+按路径读取逐字节 sha256 校验，漂移即拒服务）+HypitAgentScope（read_only 白名单检索/读取/状态，写工具 403）+HypitAgentStepService（动作行持久 hypit_job_action：kind 受限枚举走 'tool'、工具名入 result_json、UNIQUE(job,step) ON CONFLICT 幂等、越权如实 failed 留 code）+HypitAgentWorker（kind 过滤 SKIP LOCKED 认领、checkpoint 步进、MAX_STEPS 预算）；knowledge 端点双面（GET /api/hypit/knowledge + B knowledge.search/read kind）+job actions 端点实装；HypitKnowledgeTest（65 篇+可追溯+校验读）+HypitToolScopeTest+HypitAgentIT（真 PG：真实索引检索命中、动作行持久、越权不污染账目）；V04 166 测/164 过/0 挂/2 诚实 skip、V05 68/68 绿、V21 49+31 过；build.gradle.kts 未动（资源走默认 processResources，如实登记） |
| C107-15 | 全片参考理解与可定位证据 | REQ-107-15 / AC-107-15 | W15 | 05/06/14 | TC107-15-01～04；V04 / V05 | IMPLEMENTED（2026-09-26：HypitReferenceAnalysis schema（systems/events/segments/gaps/openQuestions+观察 inferred 分列+coverageGaps 并集纯函数+ANALYSIS.md/TIMELINE.md 生成器）+HypitReferenceAnalysisService（状态机：转写未就绪→WAITING_INPUT 视觉照常记录、有 gap→PROVISIONAL 未查全片禁 SUCCEEDED、全覆盖→SUCCEEDED；analysisId 由稳定 operationId 确定性派生→同 operation 幂等回读；持久走 C04 幂等命令 reference.analyze sha256 hash；incremental 只换指定段其余证据原样保留）；semantic-reference fixture（榜单跨镜头/reveal 词触发/B-roll 独立区间/一次音效+评论卡）+prompts/reference-analysis.md（只注命中证据、不塞全仓库）；HypitReferenceAnalysisIT 3 测（真 PG：固定预期系统合并 0..16s 稳定 systemId 事件不丢、半片 PROVISIONAL+静音片 WAITING_INPUT 带未解决问题、幂等+增量保留未动段证据）；V05 68/68 绿；LLM 确定性 fixture 回放与真实模型分开标记——真实 LLM/真实转写 REAL_NOT_RUN |
| C107-16 | 克隆方案、材料方向与生成/复用流程 | REQ-107-16 / AC-107-16 | W16 | 07/08/10/15 | TC107-16-01～04；V04 / V05 | IMPLEMENTED（2026-09-26：HypitClonePlan（READY/WAITING_INPUT/DRAFT 状态机+unboundSteps 锚点核验+assertExecutable 缺口 409+assertAnalysisUsable 分析未完成全片 409 顺序红线）+HypitMaterialGraph（assetId 去重保首登记+被 plan 引用禁删/未引用可清）+HypitClonePlanService（save 持久 C04 幂等命令 clone.plan+execute 只走 09 build.submit 不开新收费通道+latestPlan GET 侧+fromMap 宽松重建）；clone-plan GET/PUT 端点在 HypitProjectController 实装（契约测试工厂同步）；prompts/clone-plan.md（每持续系统至少一步绑 systemId 锚点、缺口不顶替不猜、shared recipe 引用不复制分叉）；HypitClonePlanIT 2 测+HypitClonePlanTest 1 测（真 PG：幂等/422 幽灵锚点/409 缺口与未完成分析/禁删）；真实构建执行 REAL_NOT_RUN（复用 09 通道，C16 不重复） |

### 10.1 顶层交接门槛

107-1交付manifest、EnginePort、四类contracts、完整migration、工程/素材fixture、Provider目录/授权桥、planId/pricing快照。107-2用V01/V04/V05/V06及固定plan冒烟核验后接手。107-2交付三终态Build+result_pending、可复用Results、双会话Studio、12Companion、知识检索/Agent、ReferenceAnalysis和ClonePlan；107-3先运行V04/V05/V19和一次复刻方案读回。107-3最后核销全部F01～40。

### 10.2 防止前后卡循环依赖

C02用上游已有纯本地例验证引擎，不等待C04正式工程；C04给最小初始化fixture，不等待C20模板库。C03冻结全部API但未实现端点必须503/flag关闭。C12创建verify-107-studio.sh和真实Studio测试入口，C13扩展主题/Companion，不能等C24才补首轮浏览器验证。C23创建verify-107-full.sh并实跑部署，C24复用扩充，不能让验收脚本互相等待。

### 10.3 阶段、依赖与退出条件

| 阶段 | 可交付结果 | 卡顺序 | 进入条件 | 退出证据 | 失败去向 |
|---|---|---|---|---|---|
| M107-2A | 长任务到可消费 Results | C09 → C10 → C11 | 107-1 出口与交接 smoke 通过 | 真实终态/补写/归档、同文档预览；V04/V05/V10 | C09 状态或 C10 归档或 C11 预览 |
| M107-2B | 完整编辑与可执行方案 | C12 → C13 → C14 → C15 → C16 | M107-2A 与冻结契约有效 | 双 session、12 Companion、分析与方案读回；V19 和 §12.5 | 回对应 C12～16；不得退化 iframe 展示或三帧分析 |

仅 VERIFIED 解锁依赖；C01 的诊断型完成边界见该卡，不把原生失败写成 PASS。共享 schema/dispatcher、迁移、补丁、锁文件、生命周期登记按卡序增量写入；后卡只增加已登记的本卡实现，不覆盖前卡。卡级验收与本书最终出口分开，C24 是全系列最终集成责任卡。

### 10.4 高风险与验证前置

| 风险 | 失败假设 | 影响 | 等级/原因 | 最早验证 | 不成立时处理 |
|---|---|---|---|---|---|
| RISK-107-04 | 页面关闭/Worker 丢失被误当重新提交理由 | REQ-107-09/10；C09/10 | 高：重复费用或丢结果 | TC107-09-02/03/04、TC107-10-02/03 | 查询固定 ID/Result 并收敛，不以新 Build 补写 |
| RISK-107-05 | Studio 两工程 cookie/URL/WS 或预览越权 | REQ-107-11/12/13；C11/12/13 | 高：跨工程访问与写入 | TC107-12-01～04；真实两 session/错误 Origin | 撤销会话、保留编辑，修复路径校验；不放宽 CORS/sandbox |
| RISK-107-06 | Agent 输出无依据或无边界自动执行 | REQ-107-14/15/16；C14/15/16 | 高：错误素材/费用 | TC107-14-02/04、TC107-15-01/02、TC107-16-02/03 | 保留草稿/缺口 waiting_input，不标全片完成或扩大授权 |

验收者从需求重新构造至少一条高风险独立反例，确认移除目标保护后该断言会失败；不得用同一请求中的另一拒绝原因掩盖缺陷。当前执行者即可完成复核，不强制另开代理。

## 11. 详细任务卡

每卡绑定 W/REQ/AC/TC/V；保留既定实现步骤与必要源码锚点，NEW 文件/符号不等于已存在。公共行为和字段通过 K/RULE 引用；§12.2 补齐每个 TC 的实现位置、层级、fixture 与证据，不复制第二份契约。

### C107-09 · Build提交、Worker、SSE与取消

- **执行包/责任人**：107-2 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-09 / AC-107-09；IMPLEMENTED（2026-09-26，见卡表行与 coverage 契约 C107-09 条）。
- **类型与完成边界**：实现；本卡 AC/TC 和指定 V 通过，输出供 §10 所列后置卡消费；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W09及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K05/K06.2/K09.3，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-09-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V04 / V05 / V21。产物 `ART/C09/`；命令按 107-1 §12.3 的预期退出码与诊断例外逐项核验，必需断言无未解释 skip 才满足 AC。

#### 输入、源码与输出

- 依赖C08不可变计划/C07授权/C04持久命令。
- FACT必读：`U/packages/runtime-host-node/src/index.ts`中RuntimeHostExecution.build；`runtime-local/src/{host,worker,control,types}.ts`；`cli/src/commands/execution.ts`、`cli/src/observation.ts`。
- 写入：J/build/HypitBuildService、HypitBuildRepository、HypitBuildSyncWorker；J/job event/command补全；B/engine/runtime-adapter、commands store/dispatcher/events；JT/B tests。
- 输出：公共Build UUID从提交前即存在，engineBuildId正确关联；各状态/活动/日志/取消/SSE可用。

#### 步骤

| 步 | 实施 | 必查 |
|---|---|---|
| 09.1 | owner校验→核对plan/revision/grant→短事务创建public Build/command/job，立即202 | 后端不可达仍能看到提交意图和错误 |
| 09.2 | sidecar持久command→指定engineBuildId；调用原生build，必须使用冻结snapshot/仓库位置 | 再次HTTP提交同request不产生新engine ID |
| 09.3 | 接原生detached Worker；HTTP handler不等待成片完成；观察是独立功能 | 关闭页面/请求abort不会cancel |
| 09.4 | status从active runtime和Result合并，严格区分lifecycle/outcome/resultReady | failed带可用Outputs，complete但pending写入不假成功 |
| 09.5 | activity展示Endpoint/pool/model/action资源等待原因；保留原生加权预约 | 远程poll不占整任务独占槽 |
| 09.6 | progress转换为持久/可重建事件；Java同步公共索引/SSE，游标恢复按K09 | 事件重复不回退状态、不重复terminal |
| 09.7 | cancel命令幂等，停止未开始工作并best-effort远程取消；保存远端确认与本地outcome | 取消不删除已经成功的公共Output |
| 09.8 | 同步worker重启读command/build状态；未知提交查固定engineId；执行器丢失遵循上游失败语义 | 不以“恢复”名义自动再submit收费请求 |
| 09.9 | logs脱敏、分页、来源runtime/result两种；operator可看全局activity，普通用户只自己 | 路径/签名URL/key不泄漏 |

#### 状态转换断言

`submitting→active→execution_decided→result_pending→finished`中原生可跳过瞬时阶段，UI接受快照跳转但不倒退。`outcome=null→complete|failed|cancelled`后不可由新观察覆盖为另一个终态；存储补写改变resultReady不改outcome。Java job的succeeded只表示该操作完成，不等于成片成功，详情必须带Build outcome。

#### 验收/交接

`B/tests/runtime/{submit,recovery,capacity,cancel,events}.test.ts`、`JT/build/HypitBuildIT.java`。必须故障注入：提交回包丢失、API重启、execution进程被杀、SSE重复/断线、两个观察者、取消与完成竞态。断言真实fixture provider的submit次数不增加、固定engineId只有一个。交接三种终态Result和一个result_pending fixture供C10。

**生命周期核对**：同步本卡 W 组中的三个 registry/baseline 文件，真实资源/事件、属主、派生引用、活跃态、消费及清理按 K05/K06 与本卡 TC 核对；执行 V21，不能仅补登记文本。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

### C107-10 · 全部Results、复用和自动归档

- **执行包/责任人**：107-2 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-10 / AC-107-10；IMPLEMENTED（2026-09-26，见卡表行与 coverage 契约 C107-10 条）。
- **类型与完成边界**：实现；本卡 AC/TC 和指定 V 通过，输出供 §10 所列后置卡消费；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W10及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K05/K09.4，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-10-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V04 / V05 / V10 / V21。产物 `ART/C10/`；命令按 107-1 §12.3 的预期退出码与诊断例外逐项核验，必需断言无未解释 skip 才满足 AC。

#### 输入与源码

依赖C09。必读`U/packages/build-result/src/`、build-result-fs/s3、`cli/src/{result-query,result-export}.ts`、`runtime-local/src/result-writer.ts`；y-1 VideoAssetArchiveService/ObjectStorageAdapter/PresignRequest/MediaReferenceRepository/outbox实际方法。

写B/results目录（repository/read/export/presentation/actions/reuse）、J/build Output索引、J/asset/HypitArchiveService、media/HypitMediaArchiveAdapter和对应tests；所有字节转存保留现有media生命周期。

#### 步骤

1. 查询原生manifest列全公共Outputs，区分Scalar/Resource/Composite和forwarded/external/build-file，不扫描文件夹猜结果。
2. 原生完整日志/outcome/receipts可读；结果presentation title/note/highlights/Output displayName更新原仓库，不改Output身份。
3. get/export：Scalar为JSON、Resource为真实mime字节、Composite为value.json+资源目录打包；外部资源失效报告确切依赖，不导出空包。
4. public UUID→engineBuildId在服务端映射；history按Output名/Source筛选、分页，运行库和终态历史组合去重。
5. Result finish仅保存已接受事实/字节，discard只处理从未active的submission；适用性失败409，禁止转build。
6. 服务端sync发现可用Outputs后创建幂等归档command，不等用户打开页面；部分失败/取消仍归档用户需要的Output。
7. 按K09大文件流式签名PUT、小MP4复用原bytes；head确认后media/outbox关联，claim/恢复避免重复insert。
8. reuse把用户选定公共Output写成changeset：原生build-record/id/output/satisfy，在新Run显式采用；引用位置锁定原仓库。
9. 计算Result依赖/资产引用的保留关系；工程包导出可物化引用；禁止删除被使用资源导致后续Build缺件。
10. 将原提交plan/pricing快照展示到结果详情，绝不重新读取当前Profile改写历史成本。

#### 函数要求（NEW）

`indexResult(publicBuildId,manifest)`可重入，UNIQUE(build_id,output_name)；不覆盖已归档mediaId。

`archiveOutput(outputId)`先claim再下载，成功返回同mediaId；其他进程已claim则查询现状，不启动第二个上传。

`proposeReuse(projectId,baseRevision,runFile,selections)`返回changeset，不直接偷偷覆盖Run；不能复用他人private Result。

#### 验收

| ID | 场景和断言 |
|---|---|
| T10-1 | 多Output Build：图像/MP4/JSON/Composite都能导出并按正确MIME打开 |
| T10-2 | 失败Build保留先前成功素材；采用它后新计划不重复生成 |
| T10-3 | Result持久化故障后finish，调用Provider计数前后一致 |
| T10-4 | 对象PUT成功后DB故障，再归档返回同mediaId、同key、单条media/outbox关联 |
| T10-5 | 页面关闭后自动归档；重连能看到archived，不依赖前端POST |
| T10-6 | 修改活动仓库选择不改变在途/旧Result目的地；历史仍按原位置可找 |

测试：`B/tests/runtime/results.test.ts`、`JT/asset/HypitArchiveIT.java`、`HypitOutputIT.java`。交接可复用Result与真实媒体下载，不只JSON mock。

**生命周期核对**：同步本卡 W 组中的三个 registry/baseline 文件，真实资源/事件、属主、派生引用、活跃态、消费及清理按 K05/K06 与本卡 TC 核对；执行 V21，不能仅补登记文本。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

### C107-11 · 同文档预览、音频、快照与网页采集

- **执行包/责任人**：107-2 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-11 / AC-107-11；IMPLEMENTED（2026-09-26，见卡表行与 coverage 契约 C107-11 条）。
- **类型与完成边界**：实现；本卡 AC/TC 和指定 V 通过，输出供 §10 所列后置卡消费；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W11及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K08/K10，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-11-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V04。产物 `ART/C11/`；命令按 107-1 §12.3 的预期退出码与诊断例外逐项核验，必需断言无未解释 skip 才满足 AC。

#### 输入/必读/输出

依赖C05/C08/C09。必读`U/packages/studio/src/{compile,execute,programme}.ts`、`preview/render.ts`、`preview/runtime-shim.ts`、video-cli snapshot/capture、browser-capture及provider-hyperframes-local各browser配置。写B/preview、B/tools/capture、会话预览桥、fixtures与tests。

#### 步骤

1. 根据选定Run/已可用Candidate取得原生transient display closure；沿上游执行白名单，不把任意Need拿去生成。
2. 使用原compileHyperframesDocument/materializeHyperframesHtml；构建served resource集合；缺素材返回对应Need/output，不生成空白占位图骗成功。
3. 保留原audio clips gainEnvelope/audibility/fade/sample clock与runtime-shim；音视频预览控制桥play/pause/seek/step不改变source。
4. session绑定project/run/revision；html/字体/图片/视频均按授权resourceHandle加载；按K10sandbox，作者程序不能访问父应用。
5. snapshot直接用当前Studio document/HTML抽指定帧与分页网格，保留exact frame标注；不会创建新导出Build。
6. capture工具准备capture Chrome；渲染准备独立Headless Shell，不能公用错误version字段；fixture网页实现截图/区域和交互录制。
7. capture.run在受限author-runner执行工程脚本，限制输出目录/网络/超时，保存媒体到asset；禁止直接eval请求body脚本。
8. 在相同帧点比较preview/snapshot/最终编码；压缩容差只适用MP4，确定性未压缩画面可严格比较。
9. 清理session时revoke BlobURL、取消音频时钟、关闭浏览器/临时server；关闭预览不取消正在渲染的独立Build。

#### 验收

测试`B/tests/studio/preview.test.ts`、`B/tests/media/capture.test.ts`、`audio-clock.test.ts`。断言首/中/末帧正确、seek无多音轨漂移、loop/fade/gain不丢、缺未生成素材远程调用0、snapshot Build数不变、capture产物真实可probe。归属/跨session材料访问拒绝；含尝试访问parent.document的作者HTML无法读取。

交接完整PreviewSession DTO、消息schema、真实local预览页面及证据素材，供Studio与Vue共用；不要让两个界面自己各写一套renderer。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

### C107-12 · Studio前缀、会话、代理与写回桥

- **执行包/责任人**：107-2 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-12 / AC-107-12；IMPLEMENTED（2026-09-26，见卡表行与 coverage 契约 C107-12 条）。
- **类型与完成边界**：实现；本卡 AC/TC 和指定 V 通过，输出供 §10 所列后置卡消费；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W12及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K06.3/K10，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-12-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V04 / V05 / V19。产物 `ART/C12/`；命令按 107-1 §12.3 的预期退出码与诊断例外逐项核验，必需断言无未解释 skip 才满足 AC。

#### 输入与具体文件

依赖C04/C10/C11。必读`U/packages/studio/start.ts`、`src/{server,feedback-server,localization-node,mutation-origin,source-transaction}.ts`与`src/ui/*`根路径请求。写B/studio所有K04文件、patches0001/0002、deploy/hypit/nginx.locations.conf、对应Java会话API和tests。

#### 步骤

| 步 | 动作 | 校验 |
|---|---|---|
| 12.1 | 完整抄组装顺序并复用导出：package discovery/domain/registry/run/buildLibrary/localization/plugins | 不能漏custom Companion或Result库 |
| 12.2 | Java鉴权后创建一次性ticket；sidecar会话字段按K10，绑定run/revision/readOnly | 历史revision不得可写 |
| 12.3 | ticket兑换path scoped cookie+303；多个session独立path；刷新复用会话 | A/B标签不互相登出 |
| 12.4 | base URL helper覆盖所有__studio请求/素材/音轨/locales；Vite module/base/HMR有相同session前缀 | network无裸`/__studio`请求 |
| 12.5 | proxy校验session/Origin/readOnly，转发时改Host/Origin成内层localhost；WS同授权 | 不关闭原mutation-origin保护 |
| 12.6 | parameter/semantic/source保存挂宿主transaction，feedback挂独立feedbackHash；Result名称原生adapter | 普通页面与Studio同时保存互相CAS |
| 12.7 | 会话空闲30分钟/最长8小时/默认2个；删除项目和注销撤销；WS/HTTP活动更新lastSeen | 过期cookie不重新激活，回收关闭进程 |
| 12.8 | Nginx禁用态上游空返回404；启用路径/Range/WS/长连接设置；Cookie/Secure按外部协议 | 基础nginx -t与开启overlay均通过 |

#### 测试不是“iframe显示即可”

`B/tests/studio/{sessions,proxy,base-path,mutation-bridge}.test.ts`；真实浏览器场景：载入→图片/字体可见→改Source→改Inspector→评论→改Output名→拖时间线→断开重连。每次操作从另一路API读回真实文件/manifest。再同时打开两项目，越权/票据重放/非同源写/无cookie WS均拒绝。证据含network根路径断言和一次保存冲突。

交接可用的完整Studio代理链与保存桥；此卡不改颜色/布局，C13负责DESIGN适配与Companion验收。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

### C107-13 · 全Studio功能、Companion与主题

- **执行包/责任人**：107-2 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-13 / AC-107-13；IMPLEMENTED（2026-09-26，见卡表行与 coverage 契约 C107-13 条）。
- **类型与完成边界**：实现；本卡 AC/TC 和指定 V 通过，输出供 §10 所列后置卡消费；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W13及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K10，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-13-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V04 / V11 / V19。产物 `ART/C13/`；命令按 107-1 §12.3 的预期退出码与诊断例外逐项核验，必需断言无未解释 skip 才满足 AC。

#### 输入与输出

依赖C12。UI修改前全文读根DESIGN；必读12个`*-studio`包入口/测试、Studio INSPECTOR/LOCALIZATION/README。写Studio主题patch、共享token映射资源、必要UI适配、tests/fixtures；不得删除原功能来减少改色工作。

#### 步骤

1. fixture覆盖audio-track/caption-fine/comment-sticker/deck-track/film/media-track/performance/ranking/screen-overlay/script/sound/typography-track全部官方Companion。
2. 每项验证lane/entity/selection/Inspector字段/value schema/合法source mutation；没有inverse的derived/fixed字段保持只读。
3. 语义Selection/Moment拖动、起止trim、offset、参数时间按原author authority；引用同一Recipe影响多个消费者要保留，不复制分叉掩盖。
4. 保留Tasks/Artifacts历史、材料预览、Output显示名、composition和单素材播放切换、进度/zoom/滚动。
5. Comments add/edit/delete/resolve/reopen/timestamp seek与IME操作保留；未实现drawing/emoji按钮仍明确disabled。
6. 英语/简中与locale-pack保留；主题桥与语言切换不重开工程、不丢草稿、播放位置和选中项。
7. UI字体/字号/颜色/间距全部映射DESIGN，Studio原色hex改为应用token；作品画面/字体目录不被替换。
8. 键盘快捷键在输入焦点时暂停；重叠实体可用右键/键盘选；移动窄屏给面板切换，时间线局部横向滚。
9. 未选Runtime但有历史Result仍可浏览；多个Film Run要求拆session而不是默默显示第一个。

#### 验收矩阵

每个Companion有至少1条“显示→选中→编辑可编辑字段→源码准确变化→再编译”测试；此外1条只读拖动拒绝、1条shared recipe联动、1条source冲突、1条12类全部加载。用原生测试加嵌入回归，不重写伪Inspector。

截图：Studio/Comments/Inspector/Library/错误态各亮暗，desktop/mobile各代表截图；字体对比/焦点可见。测试`B/tests/studio/companion-parity.test.ts`与后续e2e `hypit-studio.spec.ts`。交接全部Companion映射清单和截图，不仅“页面无报错”。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

### C107-14 · 全知识检索、持久Agent与工具调度

- **执行包/责任人**：107-2 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-14 / AC-107-14；IMPLEMENTED（2026-09-26，见卡表行与 coverage 契约 C107-14 条）。
- **类型与完成边界**：实现；本卡 AC/TC 和指定 V 通过，输出供 §10 所列后置卡消费；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W14及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K11/K12，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-14-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V04 / V05 / V21。产物 `ART/C14/`；命令按 107-1 §12.3 的预期退出码与诊断例外逐项核验，必需断言无未解释 skip 才满足 AC。

#### 输入、源码、文件

依赖C03/C04/C07/C11。必读上游SKILL及reference目录索引、`vocabulary.ts`、y-1 FrozenTextExecutionService的prepared/caller重载。写J/agent、platform-hypit/knowledge/index、JR/hypit/knowledge打包配置、agent/tool contract、JT tests。

#### 步骤

| 步 | 动作 | 检查 |
|---|---|---|
| 14.1 | 索引全部65文档和模块README，path/hash/title/topic/sourceCommit，检查内部链接 | 缺1篇即coverage失败 |
| 14.2 | 检索支持topic/package/surface/关键词和文档按需读取；模型prompt只放本任务相关证据 | 不每轮塞全仓库、不过滤掉不常见功能 |
| 14.3 | AgentJob持久scope/checkpoint/actions/lease；intent白名单，allowedTools服务端收窄 | 浏览器scope不能扩权 |
| 14.4 | 每次LLM调用先写prepared action，利用onPrepared持久ai_run_id；成功后再解释action JSON | 失败前后能定位实际消耗 |
| 14.5 | action按K11四类解析；工具由dispatcher执行、timeout/cancel受控；禁止模型shell | 无效JSON保留证据，不用空对象继续 |
| 14.6 | 编译修复最多2轮、maxSteps默认30；达到限制waiting_input；可提供补充资料resume | 原scope不被resume悄悄扩张 |
| 14.7 | propose_changes保存草稿和diff，manual等待应用，authorized_auto仍check+CAS；用户并行改动会冲突 | 不覆盖head、不吞草稿 |
| 14.8 | tool结果和checkpoint按稳定actionId记录；进程重启只重放未发的确定性动作 | unknown remote不重发 |
| 14.9 | activity事件给前端阶段/正在做什么/下一步与阻塞原因；脱敏不暴露系统key | 真实状态，不模拟进度 |

#### 函数边界（NEW）

`HypitAgentWorker.tick()`只claim/驱动一步；`HypitAgentStepService.runStep(job,action)`负责LLM+parse，返回持久可序列化结果；`HypitToolDispatcher.execute(validatedToolCall,scope)`执行已登记工具；knowledge service只读versioned文档。禁止把30步循环都写controller或一个prompt方法。

#### 验收

`JT/agent/HypitAgentIT.java`、`HypitToolScopeTest.java`、`HypitKnowledgeTest.java`。回放LLM次序：probe→tiles→read knowledge→propose changes→check失败→修复→finish；各工具仍真执行可重复fixture。断言ai_run/action绑定、取消/断线恢复、超步数、非法路径、越预算、用户并发保存。交接稳定Agent协议，不需要用户另开终端coding agent。

**生命周期核对**：同步本卡 W 组中的三个 registry/baseline 文件，真实资源/事件、属主、派生引用、活跃态、消费及清理按 K05/K06 与本卡 TC 核对；执行 V21，不能仅补登记文本。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

### C107-15 · 全片参考理解与可追溯分析

- **执行包/责任人**：107-2 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-15 / AC-107-15；IMPLEMENTED（2026-09-26，见卡表行与 coverage 契约 C107-15 条）。
- **类型与完成边界**：实现；本卡 AC/TC 和指定 V 通过，输出供 §10 所列后置卡消费；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W15及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K11.3，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-15-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V04 / V05。产物 `ART/C15/`；命令按 107-1 §12.3 的预期退出码与诊断例外逐项核验，必需断言无未解释 skip 才满足 AC。

#### 输入与输出

依赖C05/C06/C14。必读Skill `creation/reference-video.md`、`production/media.md`、`playbooks/craft`相关参考。写J/agent/analysis子域、ReferenceAnalysis schema/Markdown生成器、分析视图DTO/测试fixture；不改旧VideoAnalysis算法。

#### 实施流程

1. 读取asset/probe，标语言/声音/画幅/时长；不能只有缩略图即开始“完整复刻”。
2. 初次概览按视频全长分段，取得时间标注网格；有音频且授权/就绪则转写，静音跳过。
3. 从头到尾分析段落叙事、持续对象/布局/字幕/榜单/B-roll/音效，记录source time与evidence asset；观察和推断分开。
4. 对短暂切换/词触发效果/不清字体补密集采样；有疑点使用everyFrame和around，不以固定低帧率保证全细节覆盖。
5. 合并跨切镜持续系统，给稳定systemId，保留事件变化；字幕不都归成“文本叠加”。
6. 输出K11 ReferenceAnalysis；coverage并集检查、gap/openQuestions；未检查全片不得标阶段succeeded。
7. 生成ANALYSIS.md/TIMELINE.md可读说明，建立artifact关联；后续变更形成版本，不丢原证据。
8. 允许用户补充某段说明、指定重点或更改误识别，增量重分析只处理相关段，保留其余证据。

#### 验收

合成semantic-reference固定预期：榜单贯穿多个镜头、reveal对应某词、B-roll在独立区间、一次音效和评论卡；断言analysis记录相应系统/事件且可通过asset/时间打开证据。不是要求LLM每个词字面相同，而是schema、coverage、出处真实、缺证据不胡编。另测静音片、长片分段、转写失败时视觉分析仍可进行并准确waiting_input。

测试`JT/agent/HypitReferenceAnalysisTest.java`、`HypitReferenceAnalysisIT.java`。LLM回放与真实模型演示分开标记；交接analysisId、原媒体hash、引用证据与未解决问题给C16。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

### C107-16 · 复刻方案、材料图与生成范围

- **执行包/责任人**：107-2 v3.1.0；当前主程实施并执行卡级验收。
- **关联与状态**：REQ-107-16 / AC-107-16；IMPLEMENTED（2026-09-26，见卡表行与 coverage 契约 C107-16 条）。
- **类型与完成边界**：实现；本卡 AC/TC 和指定 V 通过，输出供 §10 所列后置卡消费；本卡通过不自动代表整个系列完成。
- **写入许可**：仅§9 W16及通用文档证据范围；下方目录简称不能扩大文件清单。
- **契约必读**：K11.4/K12，位置见本书开头契约地图；此外§0/9/12/13/14必读。
- **开始前检查**：记录已有diff，核对前置交付与实际源码锚点；运行对应V取得基线，缺少未创建的本卡文件记录NOT_RUN后按步骤创建，不返回伪成功。
- **验收与证据**：TC107-16-01～04 + 原具体测试 + §12.7 本卡适用 E 边界；V04 / V05。产物 `ART/C16/`；命令按 107-1 §12.3 的预期退出码与诊断例外逐项核验，必需断言无未解释 skip 才满足 AC。

#### 输入/输出

依赖C07/C08/C10/C15；从analysis到ClonePlan/Treatment/素材依赖，再可执行材料Run。必读`creation/transformations.md`、image/video/voice craft、gpt-image/seedance Kits与选定模型README。写J/agent/plan和material子域、ClonePlan schema、任务解析与grant绑定、tests。

#### 步骤

1. 读取用户目标、analysis和已供人物/产品/声音材料；未知真实产品事实列openQuestions，不编造填满表。
2. 建transformations列表：保留什么、改变什么、每个角色/产品出现在哪里；关联所有occurrences。
3. 生成目标script/displayText/spokenText、语义Selections/Moments；重新测量/生成后对齐，不沿用原视频死秒数。
4. 每个visual system选择原域组件或明确new project component；写recipe/style/semantic bindings；代码组件需求交C17。
5. 材料节点选择精确模型和Provider支持模式，保留图片、首末帧、视频/音频参考，不压成prompt字符串；依赖图拓扑检查无环/无越权引用。
6. 材料已有→显式reuse；需新生成→独立material Run/plan；最终composition Run引用已接受Outputs。修改字幕不重生素材。
7. 展示价、未知价、授权覆盖和缺配置；生成grant绑定精确计划或即时资产范围；同意一次覆盖本次范围，内部步骤不用反复弹窗。
8. 执行依赖图并记录每项Job/Build/Output；用户采用、替换、单项重做都形成明确attempt与方案revision。
9. Treatment/Brief/Progress与结构化计划同步为可读工程文档；失败保留已成功材料和下一步。

#### 必测

`HypitClonePlanTest`验证模型/依赖/替换/引用schema；`HypitMaterialFlowIT`验证图片→视频→Normalize→对齐→最终装配链（Provider fixture、真实本地处理）。三套目标：换产品、换主持、换语言；检查角色所有出现位置关联、旧素材复用、英文变中文的语义事件不是沿用旧秒数。

边界：无声音参考不能把头像当voice；仅background-removal接口不代表Provider存在；不支持的reference输入在submit前明确拒绝。交接完整ClonePlan、材料Outputs/缺口、Run草稿和预期视觉系统给C17。

**失败与清理**：确定性本地错误修复后重跑失败用例及影响回归；已发远程请求按稳定operation查询，不能自动重发。输出半成品保留明确状态；日志/临时文件/进程按本卡资源责任清理。缺前置交付或未决契约按§13，只停止受影响链路。

**移交记录**：按 §14/§14.1 在对话中给出卡状态、实际文件、TC/V 结果、证据路径和下一动作；不新建 handoff.json 或独立完成报告。

## 12. 测试、命令与集成验收

### 12.1 需求→AC→TC追踪与逐用例规格

| 需求 | 实现/写入 | 规则/契约 | 边界 | 验收/测试 | 验证 | 证据 |
|---|---|---|---|---|---|---|
| REQ-107-09 | C107-09/W09 | RULE-107-02、RULE-107-03、RULE-107-04、RULE-107-05；本卡 K 引用 | §12.7 中 C09 适用行 | AC-107-09 / TC107-09-01～04 | V04 / V05 / V21 | ART/C09/ 原始证据 + §14 对话 |
| REQ-107-10 | C107-10/W10 | RULE-107-02、RULE-107-03、RULE-107-04、RULE-107-06；本卡 K 引用 | §12.7 中 C10 适用行 | AC-107-10 / TC107-10-01～04 | V04 / V05 / V10 / V21 | ART/C10/ 原始证据 + §14 对话 |
| REQ-107-11 | C107-11/W11 | RULE-107-02、RULE-107-07；本卡 K 引用 | §12.7 中 C11 适用行 | AC-107-11 / TC107-11-01～04 | V04 | ART/C11/ 原始证据 + §14 对话 |
| REQ-107-12 | C107-12/W12 | RULE-107-02、RULE-107-03、RULE-107-07；本卡 K 引用 | §12.7 中 C12 适用行 | AC-107-12 / TC107-12-01～04 | V04 / V05 / V19 | ART/C12/ 原始证据 + §14 对话 |
| REQ-107-13 | C107-13/W13 | RULE-107-09；本卡 K 引用 | §12.7 中 C13 适用行 | AC-107-13 / TC107-13-01～04 | V04 / V11 / V19 | ART/C13/ 原始证据 + §14 对话 |
| REQ-107-14 | C107-14/W14 | RULE-107-02、RULE-107-04、RULE-107-05、RULE-107-08；本卡 K 引用 | §12.7 中 C14 适用行 | AC-107-14 / TC107-14-01～04 | V04 / V05 / V21 | ART/C14/ 原始证据 + §14 对话 |
| REQ-107-15 | C107-15/W15 | RULE-107-08；本卡 K 引用 | §12.7 中 C15 适用行 | AC-107-15 / TC107-15-01～04 | V04 / V05 | ART/C15/ 原始证据 + §14 对话 |
| REQ-107-16 | C107-16/W16 | RULE-107-01、RULE-107-05、RULE-107-08；本卡 K 引用 | §12.7 中 C16 适用行 | AC-107-16 / TC107-16-01～04 | V04 / V05 | ART/C16/ 原始证据 + §14 对话 |

规则定义见 107-1 §5.4；具体测试文件/层级见 §12.2，精确命令见 107-1 §12.3。

REQ-107-xx与AC-107-xx同号，对应下面TC107-xx-01～04及卡正文更细断言。测试采用W组所列文件，不另建重复测试体系；每TC写入测试标题或参数化用例metadata，coverage/test evidence可反查。所有反例必须独立触发单个拒绝条件。

#### AC-107-09 / REQ-107-09：持久 Build、状态、取消、容量、运行记录

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-09-01 | 同command和指定engineId | 重复submit及丢ack后恢复 | 仅一原生Build，无新ID盲重提 |
| TC107-09-02 | 两个Build和限制1的本地render pool | 并发运行 | 资源限额遵守，远程等待不占全部渲染槽 |
| TC107-09-03 | API断线/刷新/重复SSE事件 | 重连并读快照 | sequence去重、不倒退，页面关闭不取消服务任务 |
| TC107-09-04 | 取消与完成竞态/worker丢失 | cancel或恢复观察 | 终态不翻转，部分Outputs保留，unknown等待处理 |

自动化落点：W09的test/IT/contract/spec文件；涉及截图/真实服务额外保留实际运行记录。执行命令：V04 / V05 / V21；证据 `ART/C09/`。

#### AC-107-10 / REQ-107-10：全类型 Results、补写、归档、历史/显式复用

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-10-01 | Scalar/Resource/Composite及失败部分结果 | 列出下载/归档/复用 | 全部输出MIME与字节正确，不局限MP4 |
| TC107-10-02 | 上传成功但DB关联失败 | 重试archiveOutput | 同对象key/mediaId，outbox关联不重复 |
| TC107-10-03 | result_pending和从未active的提交 | finish/discard并尝试对active discard | 补写无生成；非法discard409 |
| TC107-10-04 | 旧仓库Results被新Run引用 | 切换Profile/导出Result/删除被引用资产 | 旧目的地和历史不变，删除被阻止或先物化 |

自动化落点：W10的test/IT/contract/spec文件；涉及截图/真实服务额外保留实际运行记录。执行命令：V04 / V05 / V10 / V21；证据 `ART/C10/`。

#### AC-107-11 / REQ-107-11：同源渲染文档预览、快照、网页采集

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-11-01 | 同一document含画面/音轨/透明层 | 预览/seek/逐帧/snapshot/编码 | 帧时钟和音频gain/fade/loop一致，无收费隐式build |
| TC107-11-02 | 缺Needs的Run | 打开预览 | 明确缺材料，不伪造已生成画面 |
| TC107-11-03 | local-web交互fixture | capture screenshot/run录制 | 有真实状态变化与录制，使用独立capture浏览器 |
| TC107-11-04 | 恶意URL/作者HTML/非法消息 | capture或跨帧访问 | 网络策略拒绝，source+nonce验证，父cookie不可读 |

自动化落点：W11的test/IT/contract/spec文件；涉及截图/真实服务额外保留实际运行记录。执行命令：V04；证据 `ART/C11/`。

#### AC-107-12 / REQ-107-12：Studio session/URL/WS/Origin 安全适配

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-12-01 | A/B两个工程session | 双标签兑换票据并刷新 | 独立路径cookie，不互相覆盖；ticket一次使用 |
| TC107-12-02 | 完整Studio源/参数/评论/Outputs操作 | 经代理保存并从另一API读回 | 真实文件/presentation变化，无裸/__studio请求 |
| TC107-12-03 | 过期票据/错Origin/无cookie WS/历史revision | 分别访问或写入 | 拒绝且不改变工程；历史只读，注销撤销 |
| TC107-12-04 | Studio与普通编辑器同时改同文件 | 使用旧baseHash提交 | 409保留文本，同一journal恢复不混版 |

自动化落点：W12的test/IT/contract/spec文件；涉及截图/真实服务额外保留实际运行记录。执行命令：V04 / V05 / V19；证据 `ART/C12/`。

#### AC-107-13 / REQ-107-13：全 Studio/Companion/主题/语言/写回

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-13-01 | 全部12Companion fixture | 每项选择→Inspector编辑→再编译 | 全部注册且合法source mutation准确，derived只读 |
| TC107-13-02 | 引用同一Recipe的实体 | 调节语义时序与trim | authority保留、消费者同步，不复制伪修复 |
| TC107-13-03 | 编辑中草稿/播放位置 | 切换中文/英文与明暗主题 | 状态保留；应用token正确、作品配色未变 |
| TC107-13-04 | 移动/键盘/IME与Comments | 执行编辑/解决/重开/seek | 焦点可见，输入不误触快捷键，功能无裁剪 |

自动化落点：W13的test/IT/contract/spec文件；涉及截图/真实服务额外保留实际运行记录。执行命令：V04 / V11 / V19；证据 `ART/C13/`。

#### AC-107-14 / REQ-107-14：全 Skill/词汇检索、持久 Agent 与工具执行器

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-14-01 | 65文档与包词汇 | 索引并检索/按路径读取 | 全部来源hash/commit可追溯，缺一项校验失败 |
| TC107-14-02 | replay LLM提出probe→tiles→变更→修复 | 持久worker执行真实工具 | action/ai_run/产物绑定，非单HTTP长循环 |
| TC107-14-03 | 非法tool/路径/超scope/超30步 | 执行模型输出 | 拒绝越权，限制后waiting_input，不能shell |
| TC107-14-04 | 已准备动作/未知远程动作/用户并行保存 | 重启或resume | 只恢复可证明步骤，未知不重发，CAS保护head |

自动化落点：W14的test/IT/contract/spec文件；涉及截图/真实服务额外保留实际运行记录。执行命令：V04 / V05 / V21；证据 `ART/C14/`。

#### AC-107-15 / REQ-107-15：全片参考理解与可定位证据

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-15-01 | 12秒有持续榜单/词事件/B-roll的参考 | 分段全片分析 | 覆盖完整区间，持续系统与切镜分开，有帧/词证据 |
| TC107-15-02 | 中文/无语音/短末段素材 | 建立时间线 | 不凭空补字幕，尾段不遗漏，时间均在素材范围 |
| TC107-15-03 | 同片语言输出差异或不确定元素 | 生成ReferenceAnalysis | 事实/推断与置信说明分开，可定位证据 |
| TC107-15-04 | 分析中部分工具失败/取消 | 重试确定性失败项 | 保留已分析区间，旧结果不覆盖新revision |

自动化落点：W15的test/IT/contract/spec文件；涉及截图/真实服务额外保留实际运行记录。执行命令：V04 / V05；证据 `ART/C15/`。

#### AC-107-16 / REQ-107-16：克隆方案、材料方向与生成/复用流程

| TC | Given | When | Then（客观判据） |
|---|---|---|---|
| TC107-16-01 | 同参考的换产品/换人物/换语言目标 | 生成三个ClonePlan | 保留与替换真实不同，角色一致，新措辞有新时序 |
| TC107-16-02 | 材料依赖图含复用和新生成 | 排序执行及单项失败重试 | 依赖正确；已完成材料复用，不全工程重生 |
| TC107-16-03 | 未知价/预算不够/无凭据 | 申请执行或扩范围 | waiting_input且保留方案，本地工作仍可用 |
| TC107-16-04 | 旧方案/旧授权或用户改动 | 应用新方案再尝试旧grant | revision/hash变更使旧授权不可用 |

自动化落点：W16的test/IT/contract/spec文件；涉及截图/真实服务额外保留实际运行记录。执行命令：V04 / V05；证据 `ART/C16/`。

### 12.2 测试用例执行规格与证据分层

§12.1 的每条 TC 是必须实现的 Given/When/Then，不把同号四条当四个笼统冒烟。下表给出精确文件入口、验证层与固定数据；卡正文中的 T/函数级断言作为同卡 TC 的细分断言继续执行。测试标题/metadata 采用完整 TC107-xx-yy；不得为凑测试数复制断言。

| 卡/适用 TC | 精确实现位置（§2.3 展开缩写） | 数据与层级 |
|---|---|---|
| C107-09 / TC107-09-01～04 | `B/tests/runtime/submit.test.ts`；`B/tests/runtime/recovery.test.ts`；`B/tests/runtime/capacity.test.ts`；`B/tests/runtime/cancel.test.ts`；`B/tests/runtime/events.test.ts`；`JT/build/HypitBuildIT.java` | K13 合成媒体/Provider；实际 runner/工具/存储，网络外部可 fixture；Java handler/序列化/权限用例，标 IT 的持久化断言使用真 PostgreSQL |
| C107-10 / TC107-10-01～04 | `B/tests/runtime/results.test.ts`；`JT/asset/HypitArchiveIT.java`；`JT/asset/HypitOutputIT.java` | K13 合成媒体/Provider；实际 runner/工具/存储，网络外部可 fixture；Java handler/序列化/权限用例，标 IT 的持久化断言使用真 PostgreSQL |
| C107-11 / TC107-11-01～04 | `B/tests/media/preview.test.ts`；`B/tests/media/capture.test.ts`；`B/tests/media/snapshot.test.ts`；`B/tests/studio/preview.test.ts`；`B/tests/media/audio-clock.test.ts` | K13 合成媒体/Provider；实际 runner/工具/存储，网络外部可 fixture |
| C107-12 / TC107-12-01～04 | `B/tests/studio/sessions.test.ts`；`B/tests/studio/proxy.test.ts`；`B/tests/studio/base-path.test.ts`；`B/tests/studio/mutation-bridge.test.ts`；`tests/e2e/hypit-studio.spec.ts` | K13 合成媒体/Provider；实际 runner/工具/存储，网络外部可 fixture；UI 专项与真实隔离浏览器分别记录 |
| C107-13 / TC107-13-01～04 | `B/tests/studio/companion-parity.test.ts`；`tests/e2e/hypit-studio.spec.ts` | K13 合成媒体/Provider；实际 runner/工具/存储，网络外部可 fixture；UI 专项与真实隔离浏览器分别记录 |
| C107-14 / TC107-14-01～04 | `scripts/acceptance/verify-107-upstream.sh`（临时副本篡改反例）；U/package.json 的 V02 原生脚本；`JT/agent/HypitAgentIT.java`；`JT/agent/HypitToolScopeTest.java`；`JT/agent/HypitKnowledgeTest.java` | K13 合成媒体/Provider；实际 runner/工具/存储，网络外部可 fixture；Java handler/序列化/权限用例，标 IT 的持久化断言使用真 PostgreSQL |
| C107-15 / TC107-15-01～04 | `scripts/acceptance/verify-107-upstream.sh`（临时副本篡改反例）；U/package.json 的 V02 原生脚本；`JT/agent/HypitReferenceAnalysisIT.java`；`JT/agent/HypitReferenceAnalysisTest.java` | K13 合成媒体/Provider；实际 runner/工具/存储，网络外部可 fixture；Java handler/序列化/权限用例，标 IT 的持久化断言使用真 PostgreSQL |
| C107-16 / TC107-16-01～04 | `scripts/acceptance/verify-107-upstream.sh`（临时副本篡改反例）；U/package.json 的 V02 原生脚本；`JT/agent/HypitClonePlanIT.java`；`JT/agent/HypitClonePlanTest.java`；`JT/agent/HypitMaterialFlowIT.java` | K13 合成媒体/Provider；实际 runner/工具/存储，网络外部可 fixture；Java handler/序列化/权限用例，标 IT 的持久化断言使用真 PostgreSQL |

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

### 12.3 本任务验证清单

命令唯一源为 [107-1](草场任务书-107-1-Hypit全量引擎迁移与工程底座.md) v3.1.0 §12.3 V01～V21；本书各卡适用编号见 §10 和 §12.1。执行前同时读取该编号的 cwd、环境、副作用、通过标准及证据要求，不维护第二套命令。

### 12.4 仓库候选命令（裁剪说明）

N/A：本任务已在 107-1 §12.3 选定 V01～V21，不复制模板候选目录，也不把全部命令自动用于每卡。

### 12.5 本书集成验收与完成定义

本书八卡的本地/契约验收及所有前置依赖必须有真实证据；“外部实测缺资源”与“实现未完成”分别记状态。后一本读取§10交接物并重跑smoke；不得把本书交接成功说成107全功能完成。最终全量完成由107-3全部能力核销决定。

### 12.6 发布与回滚

开发默认HYPIT_ENABLED=false、各新增Edge写路由关闭；基础未部署可探测disabled。发布顺序为新增迁移→内部宿主/runner健康→Java接口→AI静态资源/nginx→受控启用flags。回滚停新提交、保存receipt/Results、关闭flags与overlay；保留新表/卷，不DROP、不重生成、不覆盖历史迁移。生产操作仍需用户实际授权。

### 12.7 边界目录责任映射

场景/预期行为唯一源为 [107-1](草场任务书-107-1-Hypit全量引擎迁移与工程底座.md) v3.1.0 §12.7 E01～E22；这里仅维护本书卡与 TC。卡只列适用项，不复制 N/A 空表；C24 按三书全部适用行核销。

| 边界 | 本书责任卡/TC 或不适用原因 |
|---|---|
| E01 | C14 / TC107-14-01～04；C16 / TC107-16-01～04 |
| E02 | C14 / TC107-14-01～04 |
| E03 | C09 / TC107-09-01～04；C10 / TC107-10-01～04；C12 / TC107-12-01～04；C14 / TC107-14-01～04；C16 / TC107-16-01～04 |
| E04 | C09 / TC107-09-01～04；C10 / TC107-10-01～04；C11 / TC107-11-01～04；C12 / TC107-12-01～04；C14 / TC107-14-01～04；C15 / TC107-15-01～04；C16 / TC107-16-01～04 |
| E05 | C09 / TC107-09-01～04；C10 / TC107-10-01～04；C11 / TC107-11-01～04；C12 / TC107-12-01～04；C14 / TC107-14-01～04；C15 / TC107-15-01～04；C16 / TC107-16-01～04 |
| E06 | C12 / TC107-12-01～04 |
| E07 | C09 / TC107-09-01～04；C10 / TC107-10-01～04；C11 / TC107-11-01～04；C12 / TC107-12-01～04；C14 / TC107-14-01～04 |
| E08 | C10 / TC107-10-01～04；C11 / TC107-11-01～04；C14 / TC107-14-01～04；C15 / TC107-15-01～04；C16 / TC107-16-01～04 |
| E09 | C09 / TC107-09-01～04；C12 / TC107-12-01～04；C14 / TC107-14-01～04；C16 / TC107-16-01～04 |
| E10 | C09 / TC107-09-01～04；C12 / TC107-12-01～04；C13 / TC107-13-01～04 |
| E11 | C12 / TC107-12-01～04；C13 / TC107-13-01～04 |
| E12 | C11 / TC107-11-01～04；C12 / TC107-12-01～04；C13 / TC107-13-01～04 |
| E13 | C14 / TC107-14-01～04；C15 / TC107-15-01～04；C16 / TC107-16-01～04 |
| E14 | C11 / TC107-11-01～04；C15 / TC107-15-01～04 |
| E15 | C09 / TC107-09-01～04；C10 / TC107-10-01～04；C12 / TC107-12-01～04；C14 / TC107-14-01～04；C16 / TC107-16-01～04 |
| E16 | C09 / TC107-09-01～04；C10 / TC107-10-01～04；C14 / TC107-14-01～04 |
| E17 | C09 / TC107-09-01～04；C10 / TC107-10-01～04；C11 / TC107-11-01～04；C12 / TC107-12-01～04；C14 / TC107-14-01～04 |
| E18 | C09 / TC107-09-01～04；C10 / TC107-10-01～04；C12 / TC107-12-01～04；C14 / TC107-14-01～04 |
| E19 | C10 / TC107-10-01～04；C12 / TC107-12-01～04 |
| E20 | C09 / TC107-09-01～04；C10 / TC107-10-01～04；C14 / TC107-14-01～04；C15 / TC107-15-01～04；C16 / TC107-16-01～04 |
| E21 | C12 / TC107-12-01～04；C16 / TC107-16-01～04 |
| E22 | C09 / TC107-09-01～04；C10 / TC107-10-01～04；C14 / TC107-14-01～04；C15 / TC107-15-01～04 |

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
| 3.1.0 | 2026-09-25 | 用户要求按最新模板更新；规划者完成结构与验证对齐 | 模板 3.0.0；补齐用户场景/接线/阶段风险/证据分层/续作，统一命令并复做作者检查 | NOT_STARTED；文档检查不等于实现验收 |
