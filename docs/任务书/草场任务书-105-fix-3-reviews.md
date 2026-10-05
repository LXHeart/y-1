# 开发任务书：OpenTalking 复刻复审修复

> 模板版本：3.1.1  
> 任务编号：105-fix-3 ｜ 任务书版本：1.0.0 ｜ 创建/核验日期：2026-10-02  
> 规划负责人：Codex 主程 ｜ 目标仓库：草场，`/Users/LXH/claude/y-1` ｜ 当前分支：`main`  
> 代码基线：`37d2b1569b0c2fdf9df2e82fa205355e235de5d0`，叠加当前未提交的 #105-fix-2 等改动，见 §2.7  
> **规格状态：BLOCKED_DRAFT** ｜ **实施状态：NOT_STARTED**  
> 目标执行者：能力较弱的编码模型 ｜ 任务卡：15 张，C-01～C-15 ｜ 起始卡：C-01  
> 待派发模式：AUTO_CHAIN，顺序见 §10；本稿不可实施，解除 B-01 并重新发布后生效  
> 交付终点：本次修复、必要回归、本地真实业务链集成验收完成；不包含生产部署、开放试点或真实付费调用  
> 授权依据：用户要求依据复审编写任务书，允许规划者决定常规技术、交互和验收；**本次会话仅授权规划**，未授权实施。

**阅读协议**：开工先核对规格状态与用户派发范围；读适用 AGENTS.md、§0、§1、§9、§10、§13、§14，再读当前卡及其明确引用。UI 卡额外读根 DESIGN.md。不得以历史任务的 IMPLEMENTED/VERIFIED 代替本任务证据。附 C 是待派发提示词，不是实施授权。

**信息唯一位置**：需求动机 §1.7，流程/状态 §4，业务规则 §5，wire/模块签名 §6，持久化 §7，交互文案 §8，文件权限 §9.1，AC §11，TC §12.2，命令 V §12.3，阻塞 §13，汇报 §14。共享契约 K 条款仅引用；本任务修订内容由本书定义，实施时同步共享契约对应条款，不平行维护矛盾口径。

## 0. 执行协议

### 0.1 词义

MUST/禁止为硬要求；N/A 必须给理由；NOT_RUN 表示未执行，不能解释为通过。规格就绪、实现完成、卡级 VERIFIED、整任务 VERIFIED 是四件不同的事。

### 0.2 强制规则

1. 仅写当前卡与 §9.1 的交集；黑名单优先。可自主选择不改变契约的私有 helper、变量和局部算法，不新增产品、公开字段、依赖、收费或数据可见范围。
2. 安全增量合并既有改动，不要求清洁工作区，不 reset/clean、不覆盖未跟踪文件、不批量提交他人修改。发现等价移动先重新定位；实质冲突按 §13。
3. 获得实施派发后，常规本地环境准备与登记的隔离测试可直接完成；遵守单栈、最小真实依赖、重型串行、单 worker。禁止裸 Compose 起停、第二套栈、全局清理、删除旧卷。
4. 合成账号、已登记的外部服务 fixture 可以用于本地验收；不得接真实用户库、真实供应商计费或生产。缺真实商业凭据不阻止已经确定协议的本地契约测试；**缺供应商协议本身**不能靠自造协议填补。
5. 不降覆盖率、不删失败断言、不把假响应、静态帧、已受理当闭环。失败/跳过/缓存命中分别记录。
6. 默认只在对话报告；测试日志、截图、机读结果保留到登记目录，不默认另建完成报告或修改业务状态文档。

### 0.3 卡级 DoD

步骤、AC、必需 TC/V 全部满足，新增 diff 在白名单内、已有改动保留、无新增敏感信息；UI 卡查看全部规定明暗截图并核验交互。存在必需 FAIL/PARTIAL/NOT_RUN/SKIPPED 则不得 VERIFIED。已由代码满足的步骤核验后可以不改，仍须提供本次有效证据。全部卡级完成后由 C-15 执行 §12.5，不能提前结束。

### 0.4 每卡开工

核对版本/授权/前置交付，记录 HEAD、相关 diff 和文件 hash；对照 §2.7 定位真实入口；运行该卡的定向基线 V，区分当前缺陷与环境错误；按 §9.3 盘点环境。缺少前卡证据不视为 VERIFIED。SAFE 等价变更可自行处理，规格或白名单变化需按 §13.3 修订。

## 1. 产品需求、目标与范围

### 1.1 目标

让数字人工作台从角色配置到完整对话、暂停恢复、字幕保存、结束和历史回访形成可用闭环，并消除复审确认的 16 项问题。

### 1.2 背景

[复审报告](../reviews/OpenTalking复刻复审-2026-10-02.md)确认：当前一些 UI 和测试能显示“成功”，但实际 Java JSON、正式 runtime 入口、对话生成与结果消费没有接成同一条链。修复优先恢复功能与留存语义，再整理界面；不重做设计系统。

### 1.3 范围内

| 需求 | 复审/优先级 | 必须交付的结果 | 责任卡 | AC |
|---|---|---|---|---|
| REQ-001 | R01/P1 | 创建、GET、暂停、恢复、结束、media-ready 返回可真实解码的权威会话 | C-01、C-07 | AC-001、AC-007 |
| REQ-002 | R02/P1 | 文字/语音/开场白进入实际生成与输出，完成后可第二轮；远端口型档不冒充静态档 | C-03～C-06、C-15 | AC-003～AC-006、AC-015 |
| REQ-003 | R03/P1 | 镜像默认入口共享会话运行体并装配双向 mTLS bridge，两 listener 同生共死 | C-02 | AC-002 |
| REQ-004 | R04/P1 | 正常 3/30/60 秒语音不被误判背压，真积压受限 | C-05 | AC-005 |
| REQ-005 | R05/P1 | 取消/隐藏/失活/结束/断线使采集及迟到授权立即失效 | C-08 | AC-008 |
| REQ-006 | R06/P1 | 保存同意与服务器偏好、版本一致，不与已有内容混淆 | C-09 | AC-009 |
| REQ-007 | R07/P2 | 暂停窗口内有显式恢复，过期引导新建，接管须符合原规则 | C-07 | AC-007 |
| REQ-008 | R08/P2 | 显式冲突重载重置同 ID 新版本字段；普通刷新保护草稿 | C-12 | AC-012 |
| REQ-009 | R09/P2 | 易失 final 不被空页抹掉；分页、保存版本、错误反馈正确 | C-09 | AC-009 |
| REQ-010 | R10/P2 | 本人历史可查看/导出保存字幕，发现并消费录制；终态深链为只读 | C-10、C-11 | AC-010、AC-011 |
| REQ-011 | R11/P2 | 发送受理后清稿，失败保稿，不确定结果沿原键 | C-07 | AC-007 |
| REQ-012 | R12/P2 | 创建中保持确认框与提交状态，失败可见且可重试 | C-07 | AC-007 |
| REQ-013 | R13/P2 | 配置、活动会话、终态、历史互斥组织，无重复开始/历史区块 | C-13 | AC-013 |
| REQ-014 | R14/P2 | 外层与 Teleport 内容接入既有样式、双主题与键盘可用 | C-13 | AC-013 |
| REQ-015 | R15/P2 | 无可用组合时提示真实限制，提供重新检查，不承诺不存在的草稿功能 | C-12、C-13 | AC-012、AC-013 |
| REQ-016 | R16/P2 | 显式播放冻结开场白与角色删除入口，遵守次数、幂等、引用及保留规则 | C-04、C-07、C-12 | AC-004、AC-007、AC-012 |

### 1.4 范围外与已知不处理项

- 不新增长期记忆、知识库、公开直播、多角色、摄像头模仿、声音克隆或 Hypit/视频克隆功能。
- 不改收费规则、平台补贴、权限、个人/组织可见性、保存时限；不开放生产、beta，不承诺商业质量或手机实机已测。
- 复审附加“形象可视预览”作为后续增强，本次不引入新的媒体获取流程；保留现有形象选择，完成已列 16 项。文案简化与首屏结构属 REQ-013/014。
- 不修全仓无关文档索引和 Java 无关模块缺陷；#105-fix-2 的 ICE/Java-check 失败不是本任务豁免，涉及本链的必须重验，无法满足则不得完成。

### 1.6 用户、入口与限制

目标是已登录的**个人账号**，进入 AI 应用的 digital-human 路由；沿用现有登录、个人模式与受控目录。管理权限只用于隔离测试配置，不给普通用户增加配置能力。默认禁用/缺批准组合时不调用模型。现有每场 10 分钟、恢复窗 30 秒、单活动会话/轮次、字幕终止后 10 分钟缓冲、费用/保存规则继续生效。无预先批准的业务测试失败例外。

### 1.7 用户场景

| 场景 | 用户目的 | 流程 | 结果 |
|---|---|---|---|
| S-01 | 保存角色并用文字交谈 | F-01、F-02 | 听到输出、看到 final，完成后继续第二轮 |
| S-02 | 语音交谈并能取消 | F-03 | 采集受用户控制，提交一次只生成一轮 |
| S-03 | 临时离开后返回 | F-04 | 窗口内恢复；过期保持原场终态 |
| S-04 | 留存并回访产物 | F-05 | 保存状态准确，刷新后从历史获取已保存文字/录制 |
| S-05 | 编辑或删除角色 | F-06 | 冲突不覆盖远端新稿，删除不破坏活动引用 |

术语：Session 为权威会话；Turn 为一轮；Invocation 为一次 stage 调用及经济键；recording 为临时/保存录制；saveTranscript 是未来 final 保存偏好，不等于“已有内容已保存”。来源为用户本轮要求、复审与现行 #105 共享契约 K01～K14。

### 1.8 成功标准

所有 REQ 对应 AC 满足；完整链同时具备实际 API、持久化终态、可辨识音频/帧、最终字幕、第二轮与历史回访证据。测试数量、200/202、videoWidth、文件存在均不能单独证明成功。追踪见 §12.1，最终判断 AC-015。

### 1.9 未决问题

| 编号 | 已确认缺口 | 推荐及影响 | 责任/解除条件 |
|---|---|---|---|
| B-01 | 当前唯一实现为 StaticRenderProvider/runtime-static；未找到已选定的远端供应商及可核验媒体协议。K14 只定义草场内部合同，不定义供应商的创建、短期资格、PCM→视频、查询/取消/计量外部协议 | 沿用 K14，选择支持短期媒体资格、可信按秒用量、取消/查询、删除及中文实时音画的第三方；静态档只用于明确标注的本地测试。不得默认引入供应商或把现有按秒计价改为按场/按帧 | 用户提供已有供应商/文档，或另行授权选型；规划者核验并补 C-06 外部协议、精确实现/配置路径、fixture 和反例，重新执行附 B 后发布。仅补一个 API key 不能解除 |

其余常规决策已在 §3～§8 固定。B-01 阻塞整份规格发布，主要影响 C-06、C-14 远端 fixture 和 C-15；不伪装成“实施时再选”。如果要先实施独立 UI 修复，必须另行裁剪成闭合的 READY 版本，本稿不作隐含授权。

## 2. 仓库上下文

### 2.1 目标端

AI 应用 UI、intelligence-service、Edge 路由清单、Python runtime、契约与本地验收脚本。用户端/治理台仅在全局样式影响时做保留行为冒烟，不改其页面与业务。

### 2.2 设计规范

UI 使用根 [DESIGN.md](../../DESIGN.md) 的 Colors、Typography、Spacing、Layout、Interaction 与验收章节；遵守 [AGENTS.md](../../AGENTS.md)。治理台规范不作为本次新 UI 设计来源，因为不修改治理台专属组件。共享 GlModal/EmptyState 只复用，不改变其基础行为。

### 2.3 入口

`ai.html → src/ai/main.ts → src/ai/router.ts`，路由 name/path 均 digital-human，懒加载 W13；导航为 AiWorkspaceNavigation。URL 状态由 W48 读取 profile/session/view，不新增路由或敏感 query。公共请求由 grassland-http → Edge → Java digitalhuman Controllers；媒体为精确音频 WS 与 WebRTC。Java → runtime 控制 9443，runtime → Java execution 9143；公开音频 9080。

### 2.4 文件与职责索引

精确文件路径和操作唯一列在 §9.1。复用分工：W01～W12 为 wire；W16～W25 为前端状态；W26～W36 为展示组件；W49～W60 为入口/编排/媒体；W61～W80 为 Java 执行、状态与数据；W85～W98 为验收/配置。新模块调用方在同表与 §11 指明。

### 2.5 当前行为

1. GET 的 Java SessionSnapshot 内嵌 session，机器契约/TS 却要求扁平；pause/resume/end 回简短回执。前端 decoder 未检查必填字段。
2. TurnService 只分配数据库轮次；internal startTurn 只改 Python 状态。Java INTERNAL08 的 llm/tts 返回 unavailable；真实语音在 STT 后关闭。RuntimeSession 默认 Fake，不是生产桥。
3. Dockerfile 启两个独立 Python 进程，工厂未注入 bridge，各有 sessions；成功测试手工补装配。
4. 音频 pending 累加不减；Java 音频上界把 44 字节 WAV 头算入 PCM 限额，修复 60 秒链时须同步处理。
5. 当前 Workbench 799 行；配置分支漏条件，字幕保存/历史/输入及授权取消存在报告中的可复现问题。

### 2.6 问题与因果

R01～R16 的复现、源码锚点和影响沿用复审报告；本轮已读相应入口/核心实现作静态复核，未重新运行业务探针。为修 R02 还必须处理其调用链上的冻结配置、完成事件落库与正文分流：Preflight/Session 当前快照只存模型标签，InternalController 再 resolveProvider；EventService.append 是通用元数据入库，不会自动完成 turn 或保护正文。这些是接通原目标必需步骤，不能只补一个函数调用。

### 2.7 基线、规范与复用核验

| 项 | FACT |
|---|---|
| 指令 | 根 AGENTS.md；仓库检索无下级 AGENTS.md；CLAUDE.md 的 Java 服务边界、请求、测试约定 |
| 前端 | Vue 3.5、Vite 7、Vitest 3.2.7、Playwright 1.59.1；package.json、vitest.config.ts、playwright.config.ts 已读 |
| 后端 | JDK 25 toolchain、仓库 Gradle、Spring Boot 4.1.0、Testcontainers 1.21.3；IntelligenceItSupport 自动建 PostgreSQLContainer |
| runtime | Python >=3.11,<3.12，uv.lock，FastAPI/uvicorn/httpx/aiortc 已有，不需要新增模型 SDK/权重 |
| 存储 | V88/V89 数字人表，V90 修复触发器；当前 intelligence 最大迁移 V92。新迁移编号实施前仍须复核 |
| 复用 | DigitalHumanInvocationService.prepare/claimDispatch/rehydrate/settleSuccess；TextBridge.stream/checkedSegments；AudioBridge.transcribe/streamTts；ContentBuffer；ProgramAdapter/PcmAudioQueue；现有各前端 composable、GlModal、EmptyState、gl-field |
| 已有改动 | #105-fix-2 已改 W01/W02/W03/W09～W14、W61 部分及 lease/reaper/promotion、Java tests、application.yml、ci-e2e、recovery fixtures；#107-fix-3 任务书/runner 也在变化。README 当前含 #107-fix-3 v1.1.0/13 卡，必须原样保留。旧 #108 删除也不恢复 |
| 历史证据 | 复审报告记录前端 158 项、Python 143 项、typecheck 通过；UI 为受控 HTTP 数据，ASGI 为受控授权。这里只引用历史范围，不记为本任务 PASS |
| 前序限制 | #105-fix-2 v2.0.1 整体 IMPLEMENTED、未 VERIFIED；真实 ICE 间歇失败，Java-check 有基线失败说明。未据此批准本任务跳过 |
| 本轮验证 | 仅源码/文档/配置及 Git 只读调查；业务测试、浏览器、容器全部 NOT_RUN，未启动服务 |

### 2.8 标记

§2 是 FACT；§3～§12 是规划者在用户授权内的 DECISION；B-01 是显式缺失的外部事实。合成数据只用于测试，不能导入真实 catalog 或升为 approved 证据。

### 2.9 影响与兼容

公开 v1 的既定扁平 wire 修复，新增本人录制只读列表；数据库仅新增录制检索索引，JSON 快照加内部 schemaVersion，不改已执行 migration；权限/收费语义不变但接线需真实核验；runtime 镜像入口改变；URL 仍三项。验证映射 §12.1。

## 3. 技术决策

### 3.1 真实接线

| 段 | 调用方 → 处理方 → 消费方 | 责任 |
|---|---|---|
| 配置/开始 | W13/W16 → W06 → Java preflight/session → 原确认框/舞台 | C-01、C-03、C-07 |
| 轮次 | Composer → Session composable → TurnController/Service → 提交后 RuntimeClient.startTurn → runtime TurnCoordinator | C-04、C-07 |
| 生成 | coordinator → ExecutionBridge → INTERNAL07/08 → 冻结 AiExecution → TextBridge/AudioBridge/批准 render 适配 | C-03～C-06 |
| 结果 | provider PCM/远端帧 → ProgramAdapter 独立播放/录制分支；runtime 事件 → Java TurnLifecycle → SSE/保存策略 → 字幕/会话 | C-04～C-06、C-09 |
| 回访 | 历史详情 → Session/Transcript/Recording API → 本人表/授权下载 → 文字/播放器/TXT/MP4/SRT | C-10、C-11 |
| 正式装配 | Docker ENTRYPOINT → 单 RuntimeHost → 两 listener 共用 registry、bridge、任务表 → supervisor shutdown | C-02 |
| 验收 | 唯一守卫栈 → 真实镜像入口/三 origin/PG/Redis → 外部协议 fixture → browser 可消费结果 | C-14、C-15 |

### 3.2 决策与依据

| 决策 | 唯一方案 | 依据 |
|---|---|---|
| D-01 | 修 Java 遵守现有扁平 v1，不让前端同时猜嵌套/简短两种成功对象；decoder 严格失败 | 机器契约已明定，修缺陷而非创造第二 wire |
| D-02 | 单 Python 进程/事件循环托管两个 uvicorn.Server，共享 RuntimeHost；不使用跨进程内存假共享 | 一期单 worker、内存运行体要求；保留双端口/双面安全 |
| D-03 | Java 管权威状态、冻结配置/成本/幂等；Python 管任务/PCM/媒体；所有模型执行沿原控制面与经济链 | K07/K08/K14 |
| D-04 | 文本通过事务后的受控 startTurn 派发；短暂断连查原 command/turn；进程重启不自动重放生成 | 正文不持久化，不能为重试引入 durable prompt/outbox |
| D-05 | 保存偏好、易失 final、已保存页分别管理；历史读取仅用服务端已保存内容 | K05/K06 的同意及删除语义 |
| D-06 | 远端只接 B-01 核验的协议，runtime-static 保留 test_only；不以静态档验收口型 | K14，当前无通用供应商合同 |
| D-07 | 新增本人录制分页读取；终态深链内联只读详情，无新路由、无组织授权 | 补齐既有回访要求，沿原资源归属 |
| D-08 | Workbench 只装配；拆 SetupPanel、SessionPanel、HistoryDetail 与 action composable | 当前 799 行，Vue 硬顶 800 |
| D-09 | 本地浏览器进入同一 Compose 网络，通过容器内loopback TCP转发访问真实三入口并执行RTC，避免复用 #105-fix-2 宿主 loopback relay 偶发成功作证据 | 不改生产 TURN，仍真实 WebRTC/媒体，不 mock peer |
| D-10 | 无批准组合不创建新草稿业务，只准确提示与重新检查 | 现有 Profile 保存要验证 catalog/形象/音色 |

### 3.3 复用与新增边界

不改 AiExecution 核心收费方法；数字人 service 装配已有入口。增加 TurnCoordinator、TurnLifecycle、RuntimeHost、HistoryDetail 与最小测试 runner；文件和消费者见 §9.1。不得修改 vendor，适配通过已有 wrapper；需要上游 patch 变更先修订白名单，本稿未授权。

## 4. 流程与状态

### 4.1 业务状态

沿现有 SessionState 与 TurnState，不新增公开状态。Session：preparing/queued/connecting → ready → listening/responding → ready；paused/reconnecting 通过权威恢复进入 connecting/ready；ending 占槽直到 ended/failed 且清理条件满足。Turn：accepted/transcribing/generating/speaking → completed/interrupted/failed/unknown。未知执行不是成功，也不自动退款。

### 4.2 流程

- **F-01 配置/开始**：选择/保存有效角色 → 预检冻结配置、四费用项 → 确认保存偏好 → 创建中 → 已受理 Session → 等待权威可协商 → 实际媒体就绪 → ready。预检失效重新预检；创建不确定先查原 operation，不能新键造第二场。
- **F-02 文字/开场白**：ready + 当前控制权 → 校验输入/冻结文本 → 受理唯一 turn → startTurn → llm（开场白跳过）→ 已审句段 → 顺序 TTS/远端媒体 → final/完成 → Java turn 终态与 session ready → 第二轮。完整上下文只纳入完成的问答对。
- **F-03 语音**：明确用户点击 → 权限 → grant/WS accepted → 采集 → 再次点击提交 end → STT → 非空文本进入 F-02 后半段。end 前取消不调用 STT；end 已受理后 WS 关闭不取消服务端，改由 SSE/查询跟踪。
- **F-04 离开/恢复**：hidden 或 KeepAlive inactive 先停止采集，再 pause/停 heartbeat/peer；返回只 GET 权威快照，用户点击恢复；控制器不同需显式接管。窗口过期显示终态/新建，不伪造新 lease。
- **F-05 结束/保存/回访**：结束先撤销采集 → end → ending/终态；易失字幕在原窗口保留展示，可显式保存；历史列表 → 同 session 只读详情 → 已保存分页/导出/录制下载；刷新不从浏览器缓存恢复未保存正文。
- **F-06 冲突/删除**：编辑 → 版本冲突保草稿 → 显式重新载入替换字段及 version；删除确认 → 当前引用冲突拒绝，成功清选择并刷新，不删独立素材。

### 4.3 UI 状态

setup、starting、active、recoverable、terminal、history-list、history-detail 是本地展示模式，不写 DB。loading/error/submitting 分属具体动作。terminal snapshot 禁止进入 takeover。history-detail 不获得 liveSession 控制权。旧 account/session generation 的 success/catch/finally 都无权修改新页。

### 4.4 取消与恢复

用户取消麦克风 ≠ 取消已受理 turn ≠ 退款。requestId 表示同一业务意图；同键异内容 409。无结果只查/原键重试，不能后台换键重发模型。runtime 重启丢失正文/运行体时，经权威状态和原 invocation 核对后失败或 unknown 收口；不恢复生成，用户新一场才产生新经济键。

## 5. 业务规则

| 编号 | 唯一规则 |
|---|---|
| RULE-001 | 身份沿 DigitalHumanAuthorization.requirePersonal 与 owner_account_id；不存在/他人资源统一404，未登录401，组织模式沿原拒绝；URL不授予权限。所有回包需同时校验 account ticket、session ID、本地generation与相应epoch |
| RULE-002 | Session操作返回当前权威DTO；幂等重放不延长 pausedUntil、不重复派发、不减少epoch。GET/事件不得把正文、provider key、内部配置暴露出去 |
| RULE-003 | 预检冻结四stage的配置ID/模型/版本/凭据版本/价表与LLM来源；每次调用按冻结引用核验，不在执行时重新挑选主备。旧标签型快照不补猜，结束/历史可读，新生成拒绝dh_configuration_changed |
| RULE-004 | requestId+canonical payloadHash包括业务输入摘要；text trim后按Unicode码点1～2000。同key同内容返回原turn，同key异内容409。提交后派发一次；runtime同commandId+hash仅复用原任务。任一session仅一活动turn。TurnBinding.deadlineAt取started_at+90秒与session.expiresAt较早者，作为本次有界任务超时；已有单stage90秒上限继续受该deadline约束，render整场期限仍按K14独立处理 |
| RULE-005 | LLM上下文/句段/安全、STT/TTS格式、费用结算保持K08/K08.1：最多10完整对，64KiB，LLM输出8000码点；已审≤120码点句段，跨段32码点检查；STT 16k单声道s16le，TTS 24k单声道；无usage为pending。TTS/render不新增用户收费 |
| RULE-006 | 每stage执行必须有原invocation→原ai_run→预算/经济operation；claimDispatch唯一CAS。中断前未dispatch走原释放，已dispatch按原取消，未知保留核对；不能因重试创建第二run。session总cap及预留按K08.1，runtime不报金额 |
| RULE-007 | runtime完成事件经Java校验lease/turn/media/content代次，更新目标turn与session并提交后发布；旧轮不改新轮、不将ending改ready。文本正文仅无持久化缓冲/获同意的transcript，durable dh_event只存安全元数据。duplicate eventId不重复写入/结算 |
| RULE-008 | 背压计实际尚未消费的samples，32000 samples(2s)为上界；不计已进collector的历史样本。PCM最多960000 samples/1920000 bytes/60000ms，WAV头另算44 bytes。空、越界、序号错、end汇总错均不派发；每帧idle5s沿原协议 |
| RULE-009 | 每次abort先generation++，同步停tracks/发送器，再关闭worklet/context/WS并等待有界清理；迟到getUserMedia立即stop所得tracks。任何hidden/inactive/卸载/换号/结束/录音期WS异常执行同一清理；恢复需新用户动作，绝不自动开麦 |
| RULE-010 | savedPreference由Session.saveTranscript/transcriptVersion同步；false只停止以后final自动保存，不删除历史。saveNow使用返回version推进。客户端的最终字幕、生成中delta、服务端已保存页分离；终态reload空页不清当前易失final |
| RULE-011 | 当前会话字幕按utteranceId去重、utteranceSeq排序，已保存列表每页20/max100并显式加载更多；删除推进contentEpoch/墓碑，清易失与已保存视图，旧事件/旧读回包不复活。窗口仅为K01原终止后10分钟，迟到终态或刷新不得重开窗口，服务端410/409权威 |
| RULE-012 | 历史详情只有本人已保存字幕和原录制权限；列表记录状态expired/deleted不伪装ready。保存素材独立；会话删除不连带删除已保存asset。历史查看不pause/resume/end、不起peer、不申请mic |
| RULE-013 | 输入在收到同一提交的有效TurnReceipt或原operation确认受理后才清除；非确定结果锁定该意图并提供原键查询/重试。创建确认框到确定受理才关，失败保留已选项；不能丢稿或关闭错误容器 |
| RULE-014 | 显式重载以reloadToken驱动同ID表单reset；后台版本刷新只提示远端变化，不把新expectedVersion配旧字段提交。删除复用API06并在服务端维护活动revision引用保护 |
| RULE-015 | 非空冻结greeting在ready且本场未受理时才可显式播放；TTS-only，不调用LLM。按K13.4每场一次受理，失败/不确定仅同requestId查询，不把UI刷新变成第二次播放机会 |
| RULE-016 | Workbench仅装配，setup/active/terminal/history互斥；保留根规范双主题、响应式、焦点和完整状态。无backend仅诚实空态，无新增draft状态 |
| RULE-017 | runtime-enabled需要两个listener与bridge装配完整；mTLS核验CA、对端主机名/SAN，禁止verify=False生产fallback或HTTP内部authority。任一必要listener退出整体非零退出并关另一个 |
| RULE-018 | Fake/test_only不得升级approved；真实render仅K14允许的第三方协议、短期资格、可信用量/清理；配置缺失fail-closed，不本地推理、不静态降级 |

## 6. 接口与模块契约

### 6.1 公共会话 wire（API-001）

权威字段以 W01 definitions.Session/SessionSnapshot/MediaReadyResponse 为准，沿v=1；API08创建202/Session，API10 GET200/扁平SessionSnapshot，API11/12 pause/resume200/Session，API14 end202/Session，API17 media-ready200/MediaReadyResponse。SessionSnapshot=Session全字段加allowedActions/replayComplete/replayFromSeq，不出现内嵌session。MediaReadyResponse按现有定义校验，不能宽泛强转。

Java使用显式序列化投影（可集中toWire方法或DTO serializer），不依赖record注释改变形态；主请求、幂等路径、SSE session.snapshot统一。前端decoder接受unknown，验证required、nullability、枚举、RFC3339及safe integer，失败抛本地协议错误并由交互I-01显示；不得构造id=undefined发后续请求。普通HTTP信封仍success/data与原错误status/code。

### 6.2 轮次/内部执行（API-002）

沿K07.1/K07.3：InternalCommand={commandId,payloadHash,leaseEpoch,command,payload}，startTurn payload={binding:TurnBindingWire,inputKind:text|audio|greeting,text:string|null}。binding字段全部来自权威session/turn，尤其mediaEpoch不能拿contentEpoch填，deadlineAt不能拿首帧授权截止代替。文本/开场白按RULE-004/015；音频文本由STT产生，浏览器不可注入历史/persona/provider。

INTERNAL07预留/资格，INTERNAL08执行保持现有JSON或STT multipart、NDJSON BridgeFrame，按stage分派；每帧v/invocationId/seq/type与判别载荷严格校验，meta→delta*→usage→done或error。LLM只发已审可播句段，TTS delta为base64 PCM，done明确终态；缺usage进入unknown/pending，不以done成功掩盖。输入hash使用共享契约规范化算法，不含grant。

新增内部私有签名：
- RuntimeClient.startTurn(sessionId,commandId,payloadHash,leaseEpoch,StartTurnPayload): Mono<RuntimeState>；同模式补interrupt/pause/resume的受控命令转发，公共签名仍§6.1。
- Python TurnCoordinator.start(binding,input_kind,text): 接受或复用一个受监管任务；stop(turn_epoch)/close()有awaitable清理；无“调用成功即生成完成”返回。
- Java TurnLifecycle.acceptRuntimeEvent(sessionId,eventId,type,leaseEpoch,payload): Mono<AppendResult>；仅INTERNAL06调用，按RULE-007处理元数据与正文。公开事件形状继续W01/K06，不让runtime指定owner/金额。

### 6.3 录制回访（API-003，新补齐）

新增公开机器端点编号 **API41**：GET `/api/digital-human/sessions/{id}/recordings`，同现有DH feature flag与intelligence上游；query cursor可缺省，limit可缺省20、整数1～100；空串/坏cursor/越界422 dh_invalid_input。200 {success:true,data:{items:Recording[],nextCursor:string|null}}，Cache-Control:no-store。先验证本人且session未删除；无记录返回空页，与404/网络错误区分。

按(created_at DESC,id DESC)稳定keyset，cursor为opaque且绑定session；state只返回现有公开RecordingState六态，expired/deleted记录从可回访列表排除，不泄露内部manifest。字段复用W01 Recording，assetId可空。既有getRecording/download/save继续服务端鉴权；回访不得把assetId拼成未经校验的存储URL。本期详情直接通过既有recording授权下载/保存接口消费；保存asset已存在时显示“已保存到素材库”，不发明尚未核验的素材深链。

### 6.4 字幕/角色

API22返回{saveTranscript,version}，API23返回{savedUtterances,version}；API24使用既有TranscriptEntryPage，API25是UTF8 TXT附件；错误保留HTTP与code。API26墓碑、profile API06删除与greeting API21沿既有协议。前端只新增内部状态/props：ProfileForm.reloadToken、Composer受控draft/submitting/accepted标记、History.open(sessionId)，不增加持久字段。

为让刷新后的开场白与保存窗口可判定，SessionSnapshot追加两个可选v1扩展，**新服务端始终输出，旧客户端可以忽略**：greetingTurn为完整TurnReceipt或null（该场已受理的最早greeting；旧记录requestId也必须回填原值），transcriptSaveUntil为UTC RFC3339或null（终态且仍具备暂存保存资格时由ended_at+10分钟计算；活跃或无资格为null）。不添加allowedActions枚举。新客户端收到缺失字段视为未知，先GET补齐；未知greeting禁止另开播放请求，未知保存窗口不显示倒计时/不从观察时刻重新计时，显式保存仍由服务端判定。C-01查询现有dh_turn/ended_at即可返回，C-04迁移marker后确保同原受理事实一致。这里“不增加持久字段”只指前端状态；服务端greeting marker明确见§7.3。

### 6.5 runtime装配与配置

RuntimeHost拥有唯一sessions/task registry、mTLS AsyncClient/ExecutionBridge及关闭钩子；create_app保留测试依赖注入但正式工厂不能默认空bridge创建启用服务。启动入口固定 `python -m grassland_dh.server`；启用时音频9080、控制9443同进程，workers=1、reload=false。

复用DH_ENABLED、DH_RUNTIME_AUDIO_PORT/CONTROL_PORT、DH_JAVA_INTERNAL_BASE_URL、DH_INTERNAL_CLIENT_CERT_FILE/KEY_FILE/CA_FILE、DH_ALLOWED_AI_ORIGINS、DH_MEDIA_ROOT。内部authority统一 `https://intelligence-service:9143`，允许测试显式端口但必须TLS+对应SAN；Java listener配置沿DH_INTERNAL_ENABLED和DH_INTERNAL_TLS_*。不得新建模型地址/key环境变量。健康响应保留ok/surface/testMode/enabled；启用但host/bridge/任一listener未就绪返回503且ok=false，不返回凭据/会话正文。SHUTDOWN最多10秒关任务/peer/客户端，超时非零退出，不吞异常。

### 6.6 外部render协议门（B-01）

K14内部RenderConnection/INTERNAL14/15的目标形状继续沿共享契约，**当前机器契约尚未登记14/15，不声称已可用**。只有供应商选定后规划者才能把外部method/path、认证、PCM/帧格式、续票、查询、超时、usage映射、取消/删除结果逐项冻结并给出fixture；C-06此前不可执行。不得实现一个自定义HTTP“通用remote”来伪造供应商支持。

### 6.7 错误与幂等

不新增公共业务错误码：沿dh_invalid_input/dh_input_too_long/dh_request_conflict/dh_version_conflict/dh_state_conflict/dh_lease_stale/dh_takeover_required/dh_configuration_changed/dh_runtime_unavailable/dh_content_expired/dh_content_deleted/dh_content_blocked及既有容量/音频码。HTTP/WS映射遵循W01/K07；UI文案唯一见§8。协议解码失败是本地错误，不伪造服务端code。operation查询恢复沿既有API39，无“超时后自动换键”。

## 7. 数据与生命周期

### 7.1 既有表与不变量

dh_session/dh_turn/dh_operation/dh_invocation/ai_run/预算与流水继续原属主、唯一约束和事务；正文不加进这些表。TurnLifecycle在同一事务中校验owner与代次、更新turn终态、允许时更新session、写安全事件；提交后发布。ContentBuffer写独立无持久化Redis，自动保存通过TranscriptService现有同意/版本/墓碑路径。Redis失败不能先发布“已保存”；媒体完成、会话终止与账务pending各自保持独立事实。

### 7.2 配置快照（C-03）

利用既有dh_session.config_snapshot JSONB，新写内部schemaVersion=2，保存四stage的无密钥冻结ProviderResolution引用及priceTableVersion；BYOK/平台类型、配置ID、模型版本、credentialVersion均明确。Preflight易失快照同形扩充，session创建从预检复制，不重新择模。旧schema只含标签：历史/结束/删除仍可用，生成/恢复派发拒绝RULE-003；不从当前配置回填旧事实。存量预检最长60秒自然失效。无SQL列迁移、无key/body持久化。

专用Redis配置只在DH启用/测试注入时连接；DH默认关闭且未配URL时不得使整个intelligence-service启动失败，相关DH内容动作fail-closed。启用却缺专用连接时初始化/健康明确失败，不回退到普通持久化Redis。W118给需要真实Redis的IT提供独立无持久化实例及属性，关闭按Testcontainers生命周期；不更改其他域Redis绑定或fix2 worker策略。

### 7.3 追加迁移（C-04、C-10）

新增V93__dh_greeting_admission.sql（C-04）：dh_session增加可空uuid列greeting_request_id，幂等ADD COLUMN IF NOT EXISTS。存量已有greeting的session，按started_at/id升序选择首轮request_id回填；只填空值，不删旧turn/账务。新受理greeting必须在同一分配事务CAS该列为空并写原requestId，失败整笔回滚；同键重放原轮，别的新键409。此列不含正文、不改变“每场一次受理”既有规则。

新增V94__dh_recording_history_index.sql（C-10），仅CREATE INDEX IF NOT EXISTS idx_dh_recording_session_created_id ON dh_recording(owner_account_id,session_id,created_at DESC,id DESC)。发布前核实V93/V94未被其他工作占用；如已占用，规划者修订W和编号，不覆盖他人迁移。不改V88～V92、不增加角色权限。新库/存量库Flyway升级与再次migrate不重复创建；回滚应用保留新列/索引，禁止drop业务数据。

### 7.4 易失状态与清理

浏览器draft/final/grant仅内存，不进storage/query/log；账户或session代次切换清除。Python collector/任务/PCM、临时grant在关闭/超时释放；上传麦克风原音不创建media。录制只接输出分支，按原300秒/200MiB及GC规则；字幕删除不删除独立保存asset。新增事件/任务消费者在生命周期登记中指向实际符号，不能删除旧登记规避门禁。

## 8. UI规格

### 8.1 页面归属

仅AI /digital-human，主题使用既有store；布局沿DESIGN与K11。所有新子组件由W13装配，不复制第二页。

### 8.2 页面结构

页头“数字人工作台”+状态+单一历史入口。setup显示已保存角色选择、必要编辑区和可见开始动作；active显示舞台、输入、字幕、用量及录制；recoverable显示恢复卡；terminal显示终态、保存与回访；history显示列表或同页详情。只有setup可见完整配置/开始。舞台16:9/contain，持续“AI生成”，测试档额外“测试画面”。

### 8.3 组件职责

SetupPanel包含ProfileForm/AvatarPicker/开始操作；SessionPanel组合Stage/Composer/Transcript/Usage/Recording/EndPanel；HistoryDetail展示历史字幕和录制。业务动作进composable，props/emits只展示、输入；W13处理组件装配和生命周期转交。

### 8.4 交互项（文案唯一位置）

| 项 | 确定行为/文案/焦点 |
|---|---|
| I-01 协议/加载错误 | 所在区域role=alert显示“会话数据暂时无法读取，请重试。”；禁用依赖缺失ID的动作；重试原GET，保留上一份同session有效内容，不显示假成功 |
| I-02 开始 | 确认框GlModal保留费用与保存选择；提交时字段只读、按钮“正在创建…”并禁用，persistent阻止Esc/遮罩；受理后关闭。失败显示“创建失败，请重试。”并保留服务端安全错误说明；网络不确定改“正在确认创建结果”，只给“查询结果”。明确失败后可关闭，焦点回开始按钮 |
| I-03 输入 | 文本框受控，Enter发送、Shift+Enter换行、中文IME composing不发送；空白禁用。发送时“发送中…”，保留文本只读；确认受理清稿，失败显示“发送失败，内容已保留。”并回焦点；不确定显示“发送结果待确认”及原键查询/重试，不能编辑后沿旧键提交不同内容 |
| I-04 语音 | 文案统一“开始说话”→“提交语音”，不是“按住说话”；权限阶段“正在等待麦克风权限”及“取消”；录音时可取消，提交后显示“正在转写/回复”。隐藏/失活返回不自动开麦；错误显示“录音已停止，请重新开始。”；tracks必须已停 |
| I-05 暂停 | 可恢复显示“会话已暂停”+“恢复会话”；不同controller显示“接管并恢复”；过期显示“恢复时间已过，请开始新会话”。终态不出现接管。恢复中禁用，成功按权威快照重新协商；失败可见、不开麦 |
| I-06 字幕 | 复选框标“保存之后的字幕”；加载权威偏好前disabled显示“正在读取保存设置…”；明确区分“本次暂存”“已保存”。“保存本场字幕”“导出已保存文本”“加载更多”；保存中禁用，返回version同步后显示“字幕已保存”。错误“保存失败，请重试。”/“导出失败，请重试。”；410显示“暂存时间已过，无法再保存。”；删除沿原确认说明并显示“字幕已删除” |
| I-07 历史 | 行内“查看详情”“删除”；详情页头“返回历史”，保留本次筛选与滚动；字幕/录制各自loading/empty/error。空文案“本场没有已保存的字幕”/“本场没有可查看的录制”；下载失败保留列表、显示错误与重试，不把404变空成功 |
| I-08 角色 | 冲突“角色已在其他页面更新。重新载入将替换本页未保存内容。”，按钮“重新载入最新版本”；仅该动作推进reloadToken。删除用GlModal确认“删除角色不会删除已保存素材。”，提交中persistent，409“角色正在会话中使用，请先结束会话。”；成功回角色选择 |
| I-09 无组合 | EmptyState：“暂时没有可用的数字人组合”“当前无法创建角色或开始会话，请稍后重新检查。”；按钮“重新检查”只重拉catalog，已有历史入口仍可用，不诱导普通用户去治理配置 |
| I-10 开场白 | 显式“播放开场白”，请求中“正在提交…”，受理后“开场白已提交”且禁第二次；不确定只查询原结果。不得自动播放或重载后另生成requestId |
| I-11 产品文案 | 删除“服务端事实驱动”等实现说明；历史日期仍UTC、不改筛选语义，辅助文案“日期按UTC计算，结束日期不含当天，最多90天”。错误不用颜色单独表达 |

### 8.5 响应式

DESIGN固定断点<768、768～1023、≥1024、≥1440。验收1440×900、1024×768、768×1024、390×844、320×740。桌面主体minmax(0,1fr)+var(--layout-rail)；移动舞台先、字幕/配置按适用阶段展示、输入不fixed挡键盘。长角色名允许换行；表格局部可滚动且有标签，页面无横向溢出；弹窗max-height受视口约束并内部滚动，按钮可达，禁止overflow:hidden掩盖截断。

### 8.6 样式

只复用现有token，不增新色/字体/规范token：--color-bg/--color-surface/--color-text/--color-border/--color-accent/--color-danger/--space-*/--type-*/--radius-*、--layout-rail及.gl-field/.glass-card/.gl-btn-primary/.gl-btn-secondary/.gl-link。值和暗亮定义位置见DESIGN Colors表与src/style.css :root/[data-theme=light]，本任务不改变值。W13根及Teleport内容容器显式.gl-field；样式仅扩全局已有dh区域。字体仅自托管Inter/Space Grotesk。新布局数值只使用规范token/已定断点。

### 8.7 无障碍

表单label关联，错误role=alert，提交/完成role=status；GlModal初焦点为首个可操作项、焦点约束、关闭后回触发按钮。键盘可完成角色选择、发送、取消、恢复、保存、分页、历史返回；新详情打开焦点到标题，返回焦点到原行查看按钮。不可用说明不能只靠颜色；新点击元素必须原生button/link。

### 8.8 截图矩阵

产物放W99，名称 `ai-digital-human-主题-状态-宽度.png`（主题dark/light），每项两主题均查看原图并记录结论。

| 状态组 | 视口/数据 | 自查及责任 |
|---|---|---|
| setup、loading、empty-backend、profile-conflict、start-error | 1440×900和390×844；合成角色、受控UI错误fixture | I-01/02/08/09；C-12/13 |
| active、paused、send-error、recording、terminal-save-error | 1440×900、390×844；成功态来自真实链，错误专项可拦截单个目标请求并证明命中 | 无重复配置、麦克风状态、保存偏好；C-07/08/09/13 |
| history-empty、history-detail、history-page-error | 1440×900、390×844；本人21条字幕/2段录制 | 分页/下载/焦点；C-11/13 |
| active窄屏、dialog-long、history-long | 320×740、768×1024、1024×768；长名字、长错误、滚动 | 无截断/横向溢出/遮键盘；C-13 |
| 用户端首页、治理台首页 | 1440×900；仅全局dh样式无泄漏冒烟 | C-13；若误伤基础样式即返工，不能扩改两端 |

截图不能证明鉴权、取消竞态和真实输出；对应TC另验。所有加载/空/错误均有上述位置，无以“截图文件存在”验收。

## 9. 全局约束

### 9.1 文件白名单与保留边界

以下是**未来获派发实施时**的写入范围，本轮只写W96/W97。卡只能收窄符号范围；同文件按§10串行，不同时写入。新增文件均标新建；C-06相关权限在B-01修订就绪前不生效。

| W | 精确仓库相对路径 | 权限 | 符号边界/完成标准 | 责任卡 |
|---|---|---|---|---|
| W01 | `contracts/digital-human.v1.json` | 写入：修改 | Session、内部执行/render、API41；按卡顺序增量，不改无关端点 | C-01、C-06、C-10 |
| W02 | `contracts/digital-human.v1.examples.json` | 写入：修改 | 对应正常/非法fixtures | C-01、C-06、C-10 |
| W03 | `docs/任务书/草场任务书-105-数字人工作台共享契约.md` | 写入：修改 | 只同步本书已定wire、K14协议与回访；保留fix2 | C-01、C-06、C-10、C-15 |
| W04 | `tests/contracts/digital-human.contract.test.ts` | 写入：修改 | 对应契约断言 | C-01、C-06、C-10 |
| W05 | `src/types/digital-human.ts` | 写入：修改 | Session、Recording页类型，与机器契约一致 | C-01、C-10 |
| W06 | `src/composables/useDigitalHumanApi.ts` | 写入：修改 | 严格decodeSession/快照、API41；保留HTTP状态 | C-01、C-10 |
| W07 | `src/composables/useDigitalHumanApi.test.ts` | 写入：修改 | 扩展：Java wire夹具解码/非法形态/API41 | C-01、C-10 |
| W08 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanRecords.java` | 写入：修改 | 公开DTO投影、录制Page，不改领域枚举 | C-01、C-10 |
| W09 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanSessionController.java` | 写入：修改 | GET/pause/resume/end响应 | C-01 |
| W10 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanLeaseService.java` | 写入：修改 | 返回Session、受控pause/resume派发；保留fix2租约 | C-01、C-04 |
| W11 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanSessionService.java` | 写入：修改 | DTO、config_snapshot、render接线；保留排队/收尾 | C-01、C-03、C-06 |
| W12 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanConnectionController.java` | 写入：修改 | mediaReady响应；保留Offer闸 | C-01 |
| W13 | `src/views/digital-human/DigitalHumanWorkbench.vue` | 写入：修改 | 纯装配与区块替换；各卡顺序接线 | C-07、C-08、C-09、C-11、C-12、C-13 |
| W14 | `src/views/digital-human/DigitalHumanWorkbench.test.ts` | 写入：修改 | 真实DTO/区块/事件与交互断言，保留fix2 | C-01、C-07、C-08、C-09、C-11、C-12、C-13 |
| W15 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanWireIT.java` | 写入：新建 | 新增：WebTestClient真实序列化，导出脱敏合成wire测试数据 | C-01 |
| W16 | `src/views/digital-human/composables/useDigitalHumanSession.ts` | 写入：修改 | 创建/发送/恢复/开场白，原operation幂等恢复 | C-07 |
| W17 | `src/views/digital-human/composables/useDigitalHumanSession.test.ts` | 写入：修改 | 失败保稿与权威恢复；保留fix2 | C-07 |
| W18 | `src/views/digital-human/composables/useDigitalHumanMicrophone.ts` | 写入：修改 | generation及统一清理、WS结果消费 | C-08 |
| W19 | `src/views/digital-human/composables/useDigitalHumanMicrophone.test.ts` | 写入：修改 | 迟到权限/断线/隐藏/结束测试 | C-08 |
| W20 | `src/views/digital-human/composables/useDigitalHumanTranscript.ts` | 写入：修改 | 偏好、易失/保存分页、版本、错误及墓碑 | C-09 |
| W21 | `src/views/digital-human/composables/useDigitalHumanTranscript.test.ts` | 写入：修改 | 高风险保存/删除/账号切换顺序 | C-09 |
| W22 | `src/views/digital-human/composables/useDigitalHumanProfiles.ts` | 写入：修改 | 重载与删除、无组合重查 | C-12 |
| W23 | `src/views/digital-human/composables/useDigitalHumanProfiles.test.ts` | 写入：修改 | 并发版本、删除引用与草稿 | C-12 |
| W24 | `src/views/digital-human/composables/useDigitalHumanHistory.ts` | 写入：修改 | 详情选择/分页请求与generation | C-11 |
| W25 | `src/views/digital-human/composables/useDigitalHumanHistory.test.ts` | 写入：修改 | 切换、错误、空态与返回 | C-11 |
| W26 | `src/views/digital-human/components/DigitalHumanComposer.vue` | 写入：修改 | 受控draft、提交/取消/文案，纯props/emits | C-07、C-08、C-13 |
| W27 | `src/views/digital-human/components/DigitalHumanStartDialog.vue` | 写入：修改 | 创建中/错误/焦点/Teleport样式 | C-07、C-13 |
| W28 | `src/views/digital-human/components/DigitalHumanTranscript.vue` | 写入：修改 | 独立保存/暂存、分页/错误 | C-09、C-13 |
| W29 | `src/views/digital-human/components/DigitalHumanEndPanel.vue` | 写入：修改 | 接真实字幕错误与保存状态 | C-09、C-13 |
| W30 | `src/views/digital-human/components/DigitalHumanHistory.vue` | 写入：修改 | 详情入口/返回、准确文案/焦点 | C-11、C-13 |
| W31 | `src/views/digital-human/components/DigitalHumanProfileForm.vue` | 写入：修改 | reloadToken、草稿基线、样式 | C-12、C-13 |
| W32 | `src/views/digital-human/components/DigitalHumanProfileForm.test.ts` | 写入：修改 | 同ID重载与版本冲突 | C-12 |
| W33 | `src/views/digital-human/components/DigitalHumanSetupPanel.vue` | 写入：新建 | 新增：由Workbench调用的配置区 | C-13 |
| W34 | `src/views/digital-human/components/DigitalHumanSessionPanel.vue` | 写入：新建 | 新增：由Workbench调用的会话区 | C-13 |
| W35 | `src/views/digital-human/components/DigitalHumanHistoryDetail.vue` | 写入：新建 | 新增：由Workbench调用的只读详情 | C-11、C-13 |
| W36 | `src/views/digital-human/components/DigitalHumanHistoryDetail.test.ts` | 写入：新建 | 新增：字幕、录制与焦点测试 | C-11 |
| W37 | `src/views/digital-human/composables/useDigitalHumanWorkbenchActions.ts` | 写入：新建 | 新增：W13调用，集中动作编排/清理；不复制域状态 | C-07、C-08、C-09、C-12 |
| W38 | `src/views/digital-human/composables/useDigitalHumanWorkbenchActions.test.ts` | 写入：新建 | 新增：装配动作测试 | C-07、C-08、C-09、C-12 |
| W39 | `src/style.css` | 写入：修改 | 仅现有dh样式区与新增dh组件类，不改基础token值/全局选择器 | C-13 |
| W40 | `tests/deployment/digital-human-fix3-runner.test.ts` | 写入：新建 | 新增：runner白名单、证据裁决/清理测试 | C-01、C-14 |
| W41 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanExecuteStagesIT.java` | 写入：新建 | 新增：真实handler/PG，外部provider WireMock | C-03 |
| W42 | `platform-realtime/digital-human/tests/test_runtime_host.py` | 写入：新建 | 新增：正式host/双listener/mTLS/退出，非手工共享状态 | C-02 |
| W43 | `platform-realtime/digital-human/tests/test_audio_turn_wiring.py` | 写入：修改 | 长音频/队列/空输入/end与abort | C-05 |
| W44 | `platform-realtime/digital-human/tests/test_turn_coordinator.py` | 写入：新建 | 新增：受控provider边界，生产编排/媒体/事件 | C-04 |
| W45 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanTurnDispatchIT.java` | 写入：新建 | 新增：真实事务、command幂等/故障/完成事件 | C-04 |
| W46 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanTranscriptIT.java` | 写入：修改 | 真实PG保存/版本/墓碑验证；不放宽原断言 | C-09 |
| W47 | `src/views/digital-human/components/DigitalHumanTranscript.test.ts` | 写入：修改 | 偏好、分页、空/错与终态 | C-09 |
| W48 | `src/views/digital-human/useDigitalHumanUrlState.ts` | 写入：修改 | 沿三query，history-detail状态组合，禁止新增敏感URL | C-11 |
| W49 | `platform-realtime/digital-human/src/grassland_dh/app.py` | 写入：修改 | 统一host注入/health/路由surface | C-02 |
| W50 | `platform-realtime/digital-human/src/grassland_dh/server.py` | 写入：新建 | 新增：镜像调用的单进程双uvicorn入口/信号/SSL | C-02 |
| W51 | `platform-realtime/digital-human/src/grassland_dh/runtime_host.py` | 写入：新建 | 新增：sessions/tasks/bridge唯一属主；C04注册coordinator | C-02、C-04 |
| W52 | `platform-realtime/digital-human/src/grassland_dh/bridge.py` | 写入：修改 | TLS客户端必需、stage流处理、render控制/续票 | C-02、C-03、C-06 |
| W53 | `platform-realtime/digital-human/Dockerfile` | 写入：修改 | ENTRYPOINT切server，保留非root/固定依赖 | C-02 |
| W54 | `platform-realtime/digital-human/src/grassland_dh/routes_internal.py` | 写入：修改 | host/coordinator实际调用、命令校验；不静默Fake降级 | C-02、C-04、C-06 |
| W55 | `platform-realtime/digital-human/src/grassland_dh/routes_audio.py` | 写入：修改 | 真实队列、end交接、abort与错误事件 | C-05 |
| W56 | `platform-realtime/digital-human/src/grassland_dh/audio.py` | 写入：修改 | PCM样本/时长与WAV边界 | C-05 |
| W57 | `platform-realtime/digital-human/src/grassland_dh/turn_coordinator.py` | 写入：新建 | 新增：runtime host/路由调用，按stage编排/取消/完成 | C-04、C-05 |
| W58 | `platform-realtime/digital-human/src/grassland_dh/adapters.py` | 写入：修改 | 隔离Fake/生产bridge adapter，不直接吞播放队列 | C-04、C-06 |
| W59 | `platform-realtime/digital-human/src/grassland_dh/media.py` | 写入：修改 | 远端音画接ProgramAdapter，播放/录制独立消费 | C-04、C-06 |
| W60 | `platform-realtime/digital-human/src/grassland_dh/remote_renderer.py` | 写入：新建 | 新增：仅B-01冻结的供应商PCM/视频协议；未定协议不可写 | C-06 |
| W61 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanInternalController.java` | 写入：修改 | execute stage、事件交Lifecycle、render内部14/15 | C-03、C-04、C-05、C-06 |
| W62 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanInvocationService.java` | 写入：修改 | 冻结引用/原经济键prepare/dispatch/settle，保留取消表 | C-03 |
| W63 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanTextBridge.java` | 写入：修改 | 上下文、checkedSegments流尾/完整性 | C-03 |
| W64 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanAudioBridge.java` | 写入：修改 | STT/TTS计量与PCM+WAV头边界 | C-03、C-05 |
| W65 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanTtsClient.java` | 写入：修改 | 流输出边界，复用受信origin/DNS，禁止回退异步 | C-03 |
| W66 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanTurnService.java` | 写入：修改 | payloadHash、提交后派发、greeting/interrupt | C-04 |
| W67 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanRuntimeClient.java` | 写入：修改 | 默认TLS与startTurn/interrupt/pause/resume transport | C-02、C-04 |
| W68 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanTurnLifecycle.java` | 写入：新建 | 新增：INTERNAL06消费，权威CAS/正文分流 | C-04 |
| W69 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanEventService.java` | 写入：修改 | 扁平snapshot、提交后事件发布，保留fix2事件逻辑 | C-01、C-04 |
| W70 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanContentBuffer.java` | 写入：修改 | 正文/完整上下文易失缓冲与有界终态清理 | C-03、C-04 |
| W71 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanTranscriptService.java` | 写入：修改 | final受同意/墓碑保护，save版本/分页契约 | C-04、C-09 |
| W72 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanPreflightService.java` | 写入：修改 | 冻结四stage配置、render批准门与成本预留 | C-03、C-06 |
| W73 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanRenderService.java` | 写入：修改 | render invocation同键/短期媒体资格/真实usage | C-06 |
| W74 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanRenderProvider.java` | 写入：修改 | 按B-01补齐现有interface必要续票/查询契约，禁止凭空协议 | C-06 |
| W75 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/StaticRenderProvider.java` | 写入：修改 | 仅test_only，禁止伪造faceCount/真实usage/approved | C-06 |
| W76 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanRemoteRenderProvider.java` | 写入：新建 | 新增：B-01命名协议绑定实现，由RenderService注册调用 | C-06 |
| W77 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanRecordingController.java` | 写入：修改 | API41 GET会话录制列表 | C-10 |
| W78 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanRecordingService.java` | 写入：修改 | owner/有效状态/keyset；复用原下载权限 | C-10 |
| W79 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanMediaRepository.java` | 写入：修改 | 录制keyset查询，不改资产写入 | C-10 |
| W80 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanRecordingHistoryIT.java` | 写入：新建 | 新增：权限/排序/分页/删除/下载重鉴权 | C-10 |
| W81 | `platform-java/services/intelligence-service/src/main/resources/db/migration/V93__dh_greeting_admission.sql` | 写入：新建 | 新增：§7.3会话greeting_request_id及无损回填 | C-04 |
| W82 | `platform-java/services/edge-bff/src/main/resources/application.yml` | 写入：修改 | 仅API41 GET同flag/upstream路由 | C-10 |
| W83 | `platform-java/services/edge-bff/src/test/java/com/grassland/edge/DigitalHumanRecordingRouteTest.java` | 写入：新建 | 新增：沿已核实com.grassland.edge测试包，API41 method/flag/fail-closed路由测试 | C-10 |
| W84 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanRenderIT.java` | 写入：修改 | 供应商协议/经济键/撤销/清理负例 | C-06 |
| W85 | `scripts/acceptance/verify-105-fix-3.sh` | 写入：新建 | 新增：§12.3 phases，调用local-stack守卫，串行/零用例失败/清理 | C-01、C-14 |
| W86 | `tests/e2e/fixtures/digital-human-fix3.compose.yml` | 写入：新建 | 新增：外部provider fixture与浏览器加入唯一栈、证书/内部Java开启 | C-14 |
| W87 | `tests/e2e/fixtures/digital-human-fix3-provider.py` | 写入：新建 | 新增：外部LLM/STT/TTS及B-01冻结render协议fixture，非业务API替身 | C-14 |
| W88 | `tests/e2e/fixtures/Dockerfile.digital-human-browser` | 写入：新建 | 新增：Playwright1.59.1镜像+仓库锁依赖，runner服务调用 | C-14 |
| W89 | `tests/e2e/digital-human-fix3-journey.spec.ts` | 写入：新建 | 新增：真实业务链/输出/第二轮/保存历史/取消 | C-14、C-15 |
| W90 | `tests/e2e/digital-human-fix3-ui.spec.ts` | 写入：新建 | 新增：精确错误与视觉fixture，明确不是后端E2E | C-13、C-14、C-15 |
| W91 | `tests/e2e/fixtures/digital-human-fix3.ts` | 写入：新建 | 新增：隔离合成账号/固定文本/计数观测与清理，供W89/W90使用 | C-14 |
| W92 | `deploy/digital-human/compose.test.yml` | 写入：修改 | host双面TLS修正、无持久化content Redis，保持旧测试入口兼容 | C-02、C-14 |
| W93 | `deploy/digital-human/compose.production.yml` | 写入：修改 | 只修host/authority/health模板；不执行部署、不默认开启 | C-02 |
| W94 | `.env.example` | 写入：修改 | 仅已有DH装配变量说明，无真实值/无模型参数 | C-02 |
| W95 | `.env.docker.example` | 写入：修改 | 同W94 | C-02 |
| W96 | `docs/任务书/README.md` | 写入：修改 | 只增量更新本任务一行，其他任务状态不动 | C-15 |
| W97 | `docs/任务书/草场任务书-105-fix-3-reviews.md` | 写入：修改 | 只填本任务实际实施/验证记录；规格变化由规划者先修订 | C-15 |
| W98 | `platform-java/services/intelligence-service/src/main/resources/application.yml` | 写入：修改 | DH bridge/content Redis明确装配配置；保留fix2/Hypit其他配置 | C-02、C-03 |
| W99 | `test-artifacts/task-105-fix-3/` | 生成物 | 生成物目录：日志/JSON/JUnit/截图/合成wire/短命cert；不提交，私钥退出删除 | C-01～C-15 |
| W100 | `tests/contracts/resource-lifecycle.registry.json` | 写入：修改 | 本任务新增任务/运行体/远端资源实际清理符号 | C-04、C-06 |
| W101 | `tests/contracts/event-consumers.registry.json` | 写入：修改 | 新增TurnLifecycle实际生产/消费/幂等映射 | C-04 |
| W102 | `platform-realtime/digital-human/tests/test_audio.py` | 写入：修改 | 样本/WAV精确边界 | C-05 |
| W103 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanAudioBridgeTest.java` | 写入：修改 | 60秒PCM加44字节头与越界 | C-05 |
| W104 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanExecuteSttIT.java` | 写入：修改 | multipart真实handler的60秒上下界 | C-05 |
| W105 | `src/views/digital-human/useDigitalHumanUrlState.test.ts` | 写入：修改 | 终态/历史深链恢复/非法query | C-11 |
| W106 | `platform-realtime/digital-human/src/grassland_dh/tls_protocol.py` | 写入：新建 | 新增：uvicorn内部listener从实际TLS会话校验client SAN并传可信scope，非header | C-02 |
| W107 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanRuntimeAssemblyIT.java` | 写入：新建 | 新增：运行镜像实际入口/两个端口/桥接，不能手工注入app | C-02 |
| W108 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanInternalServer.java` | 写入：修改 | 注册INTERNAL14/15精确路由；保留mTLS边界 | C-06 |
| W109 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanCatalogService.java` | 写入：修改 | runtime-static仅test_only，批准档匹配真实协议证据 | C-06 |
| W110 | `platform-realtime/digital-human/tests/test_remote_renderer.py` | 写入：新建 | 新增：批准协议帧/取消/资格/错误，fixture非商业实测 | C-06 |
| W111 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanContentRedisConfiguration.java` | 写入：新建 | 新增：专用无持久化Redis连接工厂及Qualifier；ContentBuffer/Grant使用，不全局覆盖 | C-03 |
| W112 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanGrantService.java` | 写入：修改 | 注入专用易失Redis，原核销/TTL逻辑保持 | C-03 |
| W113 | `platform-java/services/intelligence-service/src/main/resources/db/migration/V94__dh_recording_history_index.sql` | 写入：新建 | 新增：§7.3录制检索索引 | C-10 |
| W114 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanSessionReaper.java` | 写入：修改 | 扫描超期turn、恢复未知派发；保留fix2收尾逻辑 | C-04 |
| W115 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanTurnRecoveryIT.java` | 写入：新建 | 新增：崩溃窗口、原键恢复、不重放生成 | C-04 |
| W116 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanInvocationRepository.java` | 写入：修改 | 原invocation状态/超期查询，保持稳定键和事务 | C-03、C-04 |
| W117 | `tests/e2e/fixtures/digital-human-fix3-loopback.mjs` | 写入：新建 | 新增：W85/W88启动的浏览器容器内TCP loopback转发，非业务代理；退出关闭 | C-14 |
| W118 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/IntelligenceItSupport.java` | 写入：修改 | 专用无持久化测试Redis/DH内容连接属性与关闭；保留fix2 worker默认静默及其他测试配置 | C-03 |

只读参考：根AGENTS.md、CLAUDE.md、DESIGN.md、src/ops/DESIGN.md、模板、复审报告、package.json/package-lock.json、vitest/playwright配置、根docker-compose.yml、scripts/local-stack.mjs、scripts/lib/local-stack.sh、scripts/lib/java-runtime.sh、scripts/ci-e2e.sh、scripts/acceptance/verify-105-fix-2.sh、既有AI/finance/identity服务与所有未列测试。允许按调用链读取更多文件，不因此取得写权限。

禁止：真实.env/凭据、vendor源码、历史已执行migration、依赖锁与CI门槛、其他任务书/索引行、全局Docker设置。缺文件权限按§13.3修订，不让弱模型私自补范围。

生成物额外允许工具固定目录：`platform-java/**/build/`、`platform-realtime/digital-human/.venv/`、`coverage/`、`dist/`、`playwright-report/`、`test-artifacts/playwright/`及`test-artifacts/playwright-results.xml`；仅工具产出，不代表编辑其既有内容或提交权限。本任务证据归档W99，不覆盖复审/fix2原产物。短命测试私钥/口令运行结束删除，只保留证书指纹/非敏感拓扑与失败摘要。

实施前逐文件记录相关diff/hash；W01/02/03/09～14/16/17/61/69/98/114等与fix2重叠，先保留当前修改再增量修复。W96必须保留所有现有行，尤其#105-fix-2、#107-fix-3和旧#108重编号结果；不能把其状态改成当前任务状态。

### 9.2 命中的仓库铁律

- **R-UI/R-LAYER**：AGENTS UI/体积规则、根DESIGN；Vue硬顶800，不加豁免。W13只装配，取数/动作在域composable；不复制基础组件，不新增字体/CDN。
- **R-ENTRY/R-JAVA**：三入口保持，API_UPSTREAM仍edge-bff:8080，Edge method/flag不命中404；Java25/Spring、响应式I/O，禁止.block/手工subscribe规避链路。阻塞媒体/SDK任务在受监管worker，不能占WebFlux事件循环。
- **R-DATA/R-AI**：Flyway只追加，PG真实锁/唯一约束；用AiExecution既有预算/积分/冻结provider/核对链，跨服务不直写finance；K08取消与未知严格区分。trusted origin/DNS pinning/SSRF不放宽。
- **R-LIFECYCLE**：正文、采集、peer、临时录制、远端session与事件都有实际清理/消费责任，登记W100/W101不能代替运行验证。
- **R-QUALITY/R-DIR/R-SAFE**：现有Vitest/Java/Python/Playwright体系、现有覆盖率门槛，不另建工具链；DOM仅文件级happy-dom。自建WebTestClient responseTimeout=30s；单worker与重型串行。目录按AGENTS，敏感信息脱敏，结果§14。

### 9.3 验证环境与资源计划

| 阶段/项 | 精确计划 |
|---|---|
| 本轮规划 | 不启动任何服务；未做Docker运行盘点，不引用上轮“无容器”作为当前结论。本轮新增服务0、业务测试NOT_RUN |
| 工具版本检查 | 仓库根bash：node --version、npm --version、uv --version；Python由uv run --frozen python --version核实3.11；Java在platform-java用source ../scripts/lib/java-runtime.sh与ensure_java_runtime 25，再java -version及./gradlew --version。只读规划未执行版本命令 |
| 开工盘点 | 启动/构建/Testcontainers前执行docker compose ls、docker ps（含Compose标签）、docker stats --no-stream和ps进程盘点；核对端口/卷/用途、其他会话重型锁。Docker不可用不能推断无栈 |
| 唯一项目 | 固定y1-e2e-local；兼容现有栈经确认可复用，否则先协调停止本项目闲置服务并保留卷。归属不明不停止也不另起一套。fresh前有旧资源则拒绝并说明，不改项目名绕过 |
| 无Docker阶段 | 定向TS/Python纯单测、typecheck/lint/build/docs；用守卫重型锁串行。Python loopback服务只为当项fixture，退出关闭 |
| Java阶段 | 停应用栈后经npm run stack -- run --project y1-e2e-local --docker --包装；真实PostgreSQLContainer及对应独立无持久化Redis、WireMock，按当前测试基座创建，单Gradle worker、maxParallelForks=1。禁止同时运行Compose应用栈；测试结束释放本阶段容器 |
| 正式host专项 | C-02用本任务runtime镜像、mTLS loopback/测试Java handler验证；外部模型不启动。真实Java双向桥由C-15补，不把fixture说成全链 |
| 本地完整链 | 基础docker-compose.yml + deploy/digital-human/compose.test.yml + W86；不叠加Hypit/canvas/fix2 TURN或observability profile。显式服务闭包：postgres-local、database-bootstrap、redis、kafka、minio、minio-init、temporal、identity-service、finance-service、trust-service、marketplace-service、intelligence-service、edge-bff、frontend、dh-redis、dh-runtime、dh-fix3-provider、dh-fix3-browser |
| 为什么有六个Java服务 | 当前frontend→edge，edge depends_on五个领域服务；identity登录与内部断言、intelligence执行/媒体、finance账务均真实；marketplace/trust及Temporal/Kafka是已核实启动/业务依赖。不能--no-deps跳过。无需独立media worker、Hypit、监控或日志栈 |
| 构建/启动 | W85复用local-stack.sh。先config检查实际服务闭包，逐镜像build，逐依赖up并等health，再启动当前业务服务；重型操作不并行。database-bootstrap/minio-init为必需一次性服务。浏览器最后运行，E2E_WORKERS=1/retries=0，三引擎逐个 |
| 浏览器与origin | W88基于mcr.microsoft.com/playwright:v1.59.1-noble，以锁文件安装测试依赖；测试浏览器与runtime同唯一网络。W117在浏览器容器内绑定127.0.0.1的18080/18081/18082，纯TCP转发到frontend的80/81/82，不解析或替换业务响应；容器BASE_URL=http://127.0.0.1:18080、OPS_BASE_URL=http://127.0.0.1:18081、AI_BASE_URL=http://127.0.0.1:18082。这样所有浏览器保持loopback安全上下文以使用麦克风，RTC仍在同容器网络。宿主查看同端口，不修改生产CORS/TURN |
| 数据/fixture | W91经既有seed方法创建fix3-person-a/b两个人账号、合成积分/非零价表、平台control-plane配置、角色“回归助手”；口令随机短命。通过真实控制面API配置模型，不能环境变量替代生产模型配置。只有W87模拟批准外部提供者；网络阻止真实外呼 |
| 数据隔离 | Testcontainers用其专属数据库；Compose仅守卫fresh确认的本轮卷与数据库，名称/标签写W99 manifest。普通开发库绝不做清理/迁移反例 |
| 收尾 | W85 trap EXIT/INT/TERM，精确停止本次服务、browser、provider与进程并等待退出；fresh会话仅守卫管理本次测试资源reset；复用栈默认stop保留卷。既有服务不自动恢复，不删旧卷/全局prune |
| 资源不足 | 先停止新增负载，回收本任务临时资源并降为单worker；相同最小链仍不足，记录RESOURCE/ENVIRONMENT_BLOCKED与NOT_RUN，不再加栈/无限重试 |

测试账号、临时secret、端口/标签与服务清单由runner记机读摘要，不含口令。正常本地准备不需重复请示；真实付费和生产不在范围。B-01是协议事实缺口，与Docker/配额问题分开。

### 9.4 安全、性能与兼容

| 项 | 规则/量化边界 | 验证 |
|---|---|---|
| 权限/正文/撤销 | RULE-001/007/011/012，未保存正文只易失，日志仅code/phase/脱敏id | TC-C04-002、TC-C09-002、TC-C10-001 |
| 费用/冻结/重复 | RULE-003/006，原run/operation与pending预算，禁重新路由 | TC-C03-001、TC-C04-001 |
| 大小/性能 | RULE-005/008/011，历史每页≤100，queue≤32000未消费samples，collector≤960000 | TC-C05-001/002、TC-C10-001 |
| 生命周期 | RULE-009/017，host10s退出、idle5s、既有执行deadline90s及session期限；超过不得残留工作 | TC-C02-001、TC-C04-002、TC-C08-001 |
| 兼容 | API-001既定wire纠偏；API41添加；旧schema不能静默择新模型；三入口CSS无泄漏 | TC-C01-001、TC-C03-001、TC-C13-001 |

### 9.5 约束矩阵

R-UI/R-LAYER→C-07～C-13/V-008；R-ENTRY/R-JAVA→C-01～C-06/C-10/V-002～005；R-DATA/R-AI→C-03/04/06/10及其高风险TC；R-LIFECYCLE→C-02/04/06/08/09/V-009；R-QUALITY/R-DIR/R-SAFE→全部，V-001～V-011与§14。根DESIGN lint必需；治理DESIGN本轮无修改，三入口样式冒烟仍必需。

## 10. 开发计划

| 卡 | 可验收结果 | REQ | W写入集合 | 依赖/交接 | 验收 | 状态 |
|---|---|---|---|---|---|---|
| C-01 | 会话wire贯通 | 001 | W01、W02、W03、W04、W05、W06、W07、W08、W09、W10、W11、W12、W14、W15、W40、W69、W85 | 无；DTO/序列化fixture给C-07 | AC-001/TC-C01-001/V-001、V-002 | NOT_STARTED |
| C-02 | 正式runtime装配 | 003 | W42、W49、W50、W51、W52、W53、W54、W67、W92、W93、W94、W95、W98、W106、W107 | C-01；host/bridge给C-03/04 | AC-002/TC-C02-001/V-003 | NOT_STARTED |
| C-03 | 真实stage执行与冻结 | 002 | W11、W41、W52、W61、W62、W63、W64、W65、W70、W72、W98、W111、W112、W116、W118 | C-02；stage流/冻结快照给C-04 | AC-003/TC-C03-001/V-012、V-003 | NOT_STARTED |
| C-04 | 完整轮次/事件/恢复 | 002、016 | W10、W44、W45、W51、W54、W57、W58、W59、W61、W66、W67、W68、W69、W70、W71、W81、W100、W101、W114、W115、W116 | C-03；完整编排给C-05/06/07 | AC-004/TC-C04-001、TC-C04-002/V-013 | NOT_STARTED |
| C-05 | 音频输入与背压 | 002、004 | W43、W55、W56、W57、W61、W64、W102、W103、W104 | C-04；真实音频输入给C-08 | AC-005/TC-C05-001、TC-C05-002/V-014 | NOT_STARTED |
| C-06 | 批准协议远端渲染 | 002 | W01、W02、W03、W04、W11、W52、W54、W58、W59、W60、W61、W72、W73、W74、W75、W76、W84、W100、W108、W109、W110 | C-05+B-01解除；协议fixture规范给C-14 | AC-006/TC-C06-001/V-004 | NOT_STARTED |
| C-07 | 开始/发送/恢复/开场白 | 001、007、011、012、016 | W13、W14、W16、W17、W26、W27、W37、W38 | C-01/04；共享W顺序在C-06后 | AC-007/TC-C07-001、TC-C07-002/V-006 | NOT_STARTED |
| C-08 | 采集生命周期 | 005 | W13、W14、W18、W19、W26、W37、W38 | C-05/07 | AC-008/TC-C08-001/V-006 | NOT_STARTED |
| C-09 | 保存偏好与字幕 | 006、009 | W13、W14、W20、W21、W28、W29、W37、W38、W46、W47、W71 | C-04/08 | AC-009/TC-C09-001、TC-C09-002/V-006、V-015 | NOT_STARTED |
| C-10 | 本人录制列表接口 | 010 | W01、W02、W03、W04、W05、W06、W07、W08、W77、W78、W79、W80、W82、W83、W113 | C-06/09；API41给C-11 | AC-010/TC-C10-001/V-001、V-005 | NOT_STARTED |
| C-11 | 历史只读详情 | 010 | W13、W14、W24、W25、W30、W35、W36、W48、W105 | C-09/10 | AC-011/TC-C11-001/V-006 | NOT_STARTED |
| C-12 | 角色重载/删除/空态 | 008、015、016 | W13、W14、W22、W23、W31、W32、W37、W38 | C-11；避免共享W冲突 | AC-012/TC-C12-001/V-006 | NOT_STARTED |
| C-13 | 布局分层与双主题 | 013、014、015 | W13、W14、W26、W27、W28、W29、W30、W31、W33、W34、W35、W39、W90 | C-07～C-12 | AC-013/TC-C13-001/V-007、V-008 | NOT_STARTED |
| C-14 | 可重建验收入口 | 全部 | W40、W85、W86、W87、W88、W89、W90、W91、W92、W117 | C-02～C-13全部交付 | AC-014/TC-C14-001/V-009 | NOT_STARTED |
| C-15 | 最终回归/本地集成 | 全部 | W03、W89、W90、W96、W97 | C-01～C-14已VERIFIED | AC-015/TC-C15-001、TC-C15-002/V-001～V-018 | NOT_STARTED |

表中W集合与§9.1逐项对应，卡步骤只能收窄，不能因同目录/连续编号扩大权限。顺序固定C-01→C-02→C-03→C-04→C-05→C-06→C-07→C-08→C-09→C-10→C-11→C-12→C-13→C-14→C-15；这是共享写入与资源串行顺序，不授予启动多个代理的权限。

### 10.1 拆卡边界

每卡只负责表列结果；同域常规返工在原卡白名单处理，跨卡契约变化由规划者修订。卡级VERIFIED才能解锁依赖；IMPLEMENTED不等于通过。按现有实现核验而非先删后写。

### 10.2 交接

按各卡输出W/符号与AC/TC/V交接，记录commit或文件hash及相关未提交diff；不得仅交一句“已完成”。后卡可读取先卡，不能隐式改其合同。

### 10.3 阶段

M1=C-01～C-06，真实入口与可执行完整管线，B-01未解不得通过；M2=C-07～C-13，全部用户交互闭环；M3=C-14～C-15，本地真实业务链及回归证据。M1中的外部fixture证据只说明协议，商业质量门禁仍不冒充PASS。

### 10.4 风险前置

高风险：RISK-01序列化假象→C-01实际handler JSON；RISK-02双进程/无bridge→C-02正式host；RISK-03重复推理/错误冻结/正文持久化→C-03/04真实PG与并发；RISK-04供应商不满足K14→B-01先解决、不得降级；RISK-05取消/切号迟到污染→C-08/09可控Promise顺序；RISK-06只有信令无媒体→C-14同网真实浏览器+C-15内容验证。任何关键假设不成立回责任卡/§13，不能缩减验收目标。

## 11. 任务卡

全部卡类型为修复实现（C-14为验收基础设施、C-15为集成）；执行与验收责任人均为收到派发的编码模型。每卡共享§0.3/0.4，不另增加人工批准门。失败在卡内W范围先修；超契约、缺依赖或B-01则按§13停止受影响链。

### 卡 C-01：统一公开会话 DTO 与严格解码

**目标/输入**：REQ-001；无；以当前机器契约和真实Controller为输入。

**交付/消费**：W08/W09/W11/W69返回同形wire；W07/W15产生真实序列化反例，供C-07读取；W85基础验证入口供后卡复用。

**必读与定位**：API-001、RULE-001/002、I-01；W08 SessionSnapshot、W09 getSession/pause/resume/end、W06 decodeSession。

**文件边界**：W01、W02、W03、W04、W05、W06、W07、W08、W09、W10、W11、W12、W14、W15、W40、W69、W85；仅§9.1登记符号及下表步骤。W99仅生成证据。不得扩大到同目录其他文件。

| 步骤 | W/符号 | 精确动作 | 完成检查点 |
|---|---|---|---|
| 1 | W08/W09/W10/W11/W12/W69 | 集中Session/SessionSnapshot公开投影，所有成功及幂等路径从权威行构造；补GET snapshot的可选greeting状态，见§6.4补充。SSE初始快照使用同投影。禁止只改注释/TS cast | 真实handler返回键集合与W01一致；嵌套/简短回执反例失败 |
| 2 | W06/W07/W15/W04 | decoder检查必填/空值/数值/枚举；Java IT通过WebTestClient保存合成wire到W99，TS读取该产物复验；静态fixtures只能补负例，不代替Java输出 | 缺id、leaseEpoch或billing均拒绝；无undefined URL |
| 3 | W01/W02/W03/W05 | 保持既定会话形态，仅同步greeting恢复字段及相应例子；不得扩allowedActions魔法字符串 | schema、TS、Java真实JSON一致 |
| 4 | W85/W40 | 先交付§12.3中非E2E phases与公共日志/守卫/退出码框架；尚无测试类/未交付phase必须非零，不能以0用例通过 | C-01可独立运行V-001/V-002，后卡不会依赖尚未存在runner |

**验收**：AC-001：Given本人创建/暂停/恢复/结束与GET、SSE快照，When经真实Java序列化进入前端解码，Then字段和状态符合API-001、request URL总有有效id/epoch，旧lease/幂等行为不变；故意简短/嵌套对象不得被接受。

**测试/异常映射**：TC-C01-001；命令V-001、V-002，边界输入/异常顺序唯一见§12.2。UI：I-01组件反馈；最终外观归C-13。保留行为：Offer/排队/fix2事件不退化；不改变allowedActions权限。

### 卡 C-02：单运行体双 listener 与 mTLS 正式装配

**目标/输入**：REQ-003；C-01正式wire；现有bridge、安全路由与镜像。

**交付/消费**：W50启动唯一W51，双surface同registry/bridge；W42/W107证明默认入口，不再测试手工共享。

**必读与定位**：D-02、API-002/§6.5、RULE-017；W49 create_app、W53 ENTRYPOINT、W52 _new_client。

**文件边界**：W42、W49、W50、W51、W52、W53、W54、W67、W92、W93、W94、W95、W98、W106、W107；仅§9.1登记符号及下表步骤。W99仅生成证据。不得扩大到同目录其他文件。

| 步骤 | W/符号 | 精确动作 | 完成检查点 |
|---|---|---|---|
| 1 | W49/W50/W51/W53 | 构造一个RuntimeHost并显式传给两个app；同事件循环启动两个uvicorn.Server，禁多worker/reload；统一signals，任一listener启动/运行失败则关另一个并非零退出 | PID/host实例唯一，control创建的session在audio可见 |
| 2 | W52/W106/W67 | 客户端SSLContext验证CA+hostname并载入client cert；内部listener验证实际握手client SAN=intelligence-service，拒伪header；公开audio不因此暴露internal路由 | 无证书/错SAN/TLS材料缺失分别fail-closed |
| 3 | W92～W95/W98 | 修正https://intelligence-service:9143及双向证书/Java listener模板；启用health反映host+两个listener；disabled仍可health且拒业务，不改生产默认关闭 | 模板同源码装配，不靠测试注入 |
| 4 | W42/W107 | 用真实模块入口子进程/镜像启动，创建会话后音频面访问同binding；kill任一server、SIGTERM、错误证书各自验证清理 | 10秒内关tasks/peers/clients；V-003 |

**验收**：AC-002：Given正式镜像入口与有效测试证书，Whencontrol创建会话再由audio消费并关闭入口，Then两面共享同一运行体、bridge真实TLS可达且资源释放；错证书/缺bridge不得health报可用。

**测试/异常映射**：TC-C02-001；命令V-003，边界输入/异常顺序唯一见§12.2。UI：N/A：仅运行装配。保留行为：仅已登记surface暴露；原授权/Origin/票据TTL保持。

### 卡 C-03：冻结四项配置并接通 Java LLM/TTS 执行

**目标/输入**：REQ-002；C-02 bridge/内部TLS；既有AiExecution和Text/AudioBridge。

**交付/消费**：W61 INTERNAL07/08实际执行stage；W41验证冻结、经济键及输出，供coordinator调用。

**必读与定位**：D-03、RULE-003/005/006/007、§6.2、§7.1/7.2；K08/K08.1。

**文件边界**：W11、W41、W52、W61、W62、W63、W64、W65、W70、W72、W98、W111、W112、W116、W118；仅§9.1登记符号及下表步骤。W99仅生成证据。不得扩大到同目录其他文件。

| 步骤 | W/符号 | 精确动作 | 完成检查点 |
|---|---|---|---|
| 1 | W72/W11/W62/W116 | 预检保存无密钥配置引用而非标签；session复制schema2，invocation取对应冻结stage（own仅LLM）；prepare/claimDispatch/rehydrate沿原键，不再resolveProvider(null)选当前模型 | 配置换版/撤钥在dispatch前拒绝、无新run |
| 2 | W111/W98/W70/W112/W72/W118 | 新增专用contentRedis bean，显式Qualifier给正文/资格/预检消费者；DH_CONTENT_REDIS_URL只基础设施连接，独立no-AOF/RDB实例，禁止全局覆盖普通Redis bean | 启动装配与测试查到指定实例，正文不入普通Redis |
| 3 | W61/W63/W64/W65/W52 | 实现JSON llm/tts分派并复用prepare→claim→stream→usage/settle；LLM安全句段流尾全部输出，TTS只收已审句段及冻结voice；校验NDJSON顺序/长度/终态，失败/未知按原取消表 | 不再503占位；multi-segment/尾句不丢，缺usage不置0 |
| 4 | W41 | 真实handler/PG/账务入口，外部模型仅WireMock；冻结切换、重复核销、断流、已dispatch取消、补偿失败、cap耗尽各单因子断言 | V-012通过且provider调用/ai_run/经济operation数匹配 |

**验收**：AC-003：Given同一冻结session的text/stt/tts调用，WhenINTERNAL07/08执行并发生成功或确定错误，Then真实流与用量沿原经济链完成；配置漂移拒绝，重复/未知不再派发，不增加收费规则。

**测试/异常映射**：TC-C03-001；命令V-012、V-003，边界输入/异常顺序唯一见§12.2。UI：N/A：前端费用展示保持原契约。保留行为：既有STT multipart、BYOK LLM、平台TTS补贴与安全审查。

### 卡 C-04：轮次派发、完成事件与重启恢复

**目标/输入**：REQ-002、REQ-016；C-03真实stage流；C-01 session投影；C-02 host。

**交付/消费**：Java提交后实际startTurn，Python监管任务输出并回报，Java终态可第二轮；greeting一次受理持久化。

**必读与定位**：F-02/F-04、RULE-004/006/007/015、§6.2、§7.1/7.3。

**文件边界**：W10、W44、W45、W51、W54、W57、W58、W59、W61、W66、W67、W68、W69、W70、W71、W81、W100、W101、W114、W115、W116；仅§9.1登记符号及下表步骤。W99仅生成证据。不得扩大到同目录其他文件。

| 步骤 | W/符号 | 精确动作 | 完成检查点 |
|---|---|---|---|
| 1 | W66/W67/W81 | 执行V93无损迁移；turn hash包含输入摘要、lease与kind；事务分配/operation绑定/greeting CAS，提交后await受控command受理。未知受理查runtime.state及原turn，不换commandId。interrupt/pause/resume也转发到实际任务 | 重复请求一turn，一次start；不同正文同键409；第二greeting请求409 |
| 2 | W51/W54/W57/W58/W59 | host注册TurnCoordinator；startTurn挂监管task，校验binding，顺序执行llm→tts→ProgramAdapter；greeting直接tts。不得调用Fake当真实流或把播放队列drain到测试数组；播放与录制为独立分支 | 实际消费者收到PCM/帧；command响应不假称完成 |
| 3 | W61/W68/W69/W70/W71 | INTERNAL06调用Lifecycle，校验事件与全部代次；正文进易失/获同意保存，durable事件安全元数据；事务CAS目标turn终态和允许的session状态，提交后SSE；usage.updated仅Java产生 | 重复/旧事件无副作用；final可消费且DB终态一致 |
| 4 | W114/W115/W116/W100/W101 | 既有reaper调度调用超期轮次恢复：以RULE-004截止为准，沿既有reaper每5秒扫描；reserved/prepared按原键取消，dispatched未知只核对/收口，不重放生成。登记真实清理/消费符号 | 重启失正文不新增run，旧completion不覆盖新轮/ending |
| 5 | W44/W45/W115 | 建立提交前/提交后未派发/派发后丢ack/输出后丢事件四个故障点；用屏障而非sleep固定顺序 | V-013；已知失败不吞、必需终态不是只断言202 |

**验收**：AC-004：Given有效ready会话，When文字或开场白受理并完成，Then实际生成/输出/final/持久化turn终态和session ready齐全且可第二轮；重复、打断、断连、重启均不重复生成/结算或复活旧输出。

**测试/异常映射**：TC-C04-001、TC-C04-002；命令V-013，边界输入/异常顺序唯一见§12.2。UI：N/A：控制按钮C-07接线，媒体真实结果C-15验。保留行为：fix2排队、lease、收尾条件；未保存正文隐私。

### 卡 C-05：修复音频队列与提交交接

**目标/输入**：REQ-002、REQ-004；C-04 TurnCoordinator。

**交付/消费**：长语音走同一真实链；collector/未消费queue分别限额。

**必读与定位**：F-03、RULE-008、API-002；W55 audio_ws/_dispatch_stt、W56 PcmCollector。

**文件边界**：W43、W55、W56、W57、W61、W64、W102、W103、W104；仅§9.1登记符号及下表步骤。W99仅生成证据。不得扩大到同目录其他文件。

| 步骤 | W/符号 | 精确动作 | 完成检查点 |
|---|---|---|---|
| 1 | W55/W56 | 接收器与消费器用有界队列，水位按samples累计，消费后递减；不能用帧数近似worklet小块。collector独立做seq/字节/时长/end核对；5秒idle与队列溢出走既有close code | 3/30/60秒持续发送不误4429；真未消费>32000拒绝 |
| 2 | W55/W57 | end有效后把完整WAV与原TurnBinding交监管任务，WS断开不撤已受理生成；abort/断线未end向Java回报目标轮interrupted并归ready，零STT；stt空文本停止后续stage | 没有悬挂transcribing/内存，不能把contentEpoch当mediaEpoch |
| 3 | W64/W61 | Java multipart校验与AudioBridge限額均以PCM1920000+44头计算，时长/样本核对；不因边界修复放宽其他格式 | 60000ms成功，60001ms/额外sample拒绝，原音不存media |
| 4 | W43/W102/W103/W104 | 固定时钟、消费者屏障做长录音与背压反例，分开测权限错误和队列错误 | V-014；恢复旧累加逻辑会在3秒正例失败 |

**验收**：AC-005：Given合法16k单声道PCM，When持续提交3/30/60秒或故意制造真实积压，Then正常长度完整进入STT及后续流程，只有实际背压/越界拒绝；abort前后经济与资源结果符合F-03。

**测试/异常映射**：TC-C05-001、TC-C05-002；命令V-014，边界输入/异常顺序唯一见§12.2。UI：N/A：麦克风UI由C-08。保留行为：auth首帧、Origin、票据一次核销、序号与idle。

### 卡 C-06：远端渲染协议及正式媒体适配（B-01 阻塞）

**目标/输入**：REQ-002；C-05与B-01关闭后的新版本。本卡当前不可执行。

**交付/消费**：一项已命名且证据可核对的供应商协议，经Java凭据边界和runtime媒体桥接入；不能交“通用空适配”。

**必读与定位**：D-06、RULE-018、§6.6、K14.1～14.5。

**文件边界**：W01、W02、W03、W04、W11、W52、W54、W58、W59、W60、W61、W72、W73、W74、W75、W76、W84、W100、W108、W109、W110；仅§9.1登记符号及下表步骤。W99仅生成证据。不得扩大到同目录其他文件。

| 步骤 | W/符号 | 精确动作 | 完成检查点 |
|---|---|---|---|
| 1 | W03/W01/W02/W04 | 规划者先依据B-01提供的真实合同补齐外部端点、传输、认证、计量、关闭与删除形状，并补机器INTERNAL14/15。重新发布前本步骤及后续均不得由弱模型猜填 | 协议证据可核验，B-01关闭；否则保持BLOCKED_DRAFT |
| 2 | W73/W74/W76/W61/W108/W72/W11 | 按已冻结协议注册Java provider；连接时创建唯一render invocation并绑定原run，签发短期媒体资格；续票/interrupt/close/usage仅同providerSessionRef。同步preflight冻结与cap | 长期key不出Java，未知create不重复建会话 |
| 3 | W52/W54/W58～W60 | runtime只用短期媒体资格发PCM/接远端视频，接ProgramAdapter播放/录制；过期续票受原lease/deadline，取消/关闭有实际回执 | 远端帧来源可追踪，静态占位不能过真实协议用例 |
| 4 | W75/W109/W100/W84/W110 | runtime-static限制test_only，失败不降级；远端资源登记与删除/查询真实；运行协议回放负例与无配置fail-closed | V-004；未跑商业服务仍REAL_NOT_RUN，绝不伪造REAL_PASS |

**验收**：AC-006：Given已批准且冻结的远端协议配置，When创建/输出/打断/续票/关闭及查询用量，Then音画与控制经过真实适配代码、原经济键可核对、长期凭据隔离、远端未知清理保持pending；静态档不可被批准或替代。

**测试/异常映射**：TC-C06-001；命令V-004，边界输入/异常顺序唯一见§12.2。UI：保持“AI生成”/测试档标记。保留行为：现有K14费用与资源权限；不引入本地推理。B-01解除前不能声称此卡步骤已可实施。

### 卡 C-07：开始、发送、恢复和开场白交互

**目标/输入**：REQ-001、REQ-007、REQ-011、REQ-012、REQ-016；C-01 wire、C-04轮次；执行顺序在C-06后。

**交付/消费**：useSession/action composable与真实按钮连接，失败不丢稿/隐藏错误。

**必读与定位**：F-01/F-02/F-04、RULE-013/015、I-01～I-05/I-10。

**文件边界**：W13、W14、W16、W17、W26、W27、W37、W38；仅§9.1登记符号及下表步骤。W99仅生成证据。不得扩大到同目录其他文件。

| 步骤 | W/符号 | 精确动作 | 完成检查点 |
|---|---|---|---|
| 1 | W37/W13/W16 | 先抽action装配，WorkBench不得增越800行；创建确认框到确定受理才关，未知查询原operation；visible返回仅同步权威状态，paused按钮走resume/接管 | 开始失败可见、过期不续命、terminal不接管 |
| 2 | W26/W27/W16 | Composer受控draft/submitting；IME防误发、受理后清稿；失败保原文；未知原键查询。StartDialog绑定starting/error/persistent，不再用孤立startError隐藏sessionError | 无重复提交；旧finally不改新session |
| 3 | W16/W13/W37 | greeting读取冻结snapshot状态；显式按钮调用requestGreeting，首次请求键在同session生命周期复用；刷新先GET，不从本地推断尚未播放 | 同场不重复受理，不调用LLM |
| 4 | W14/W17/W38 | 实际组件mount并操作按钮，使用C-01真实Java wire正例和503/不确定反例 | V-006；失败文案、稿件、requestId/URL与焦点均断言 |

**验收**：AC-007：Given开始确认/文字稿件/可恢复会话或非空开场白，When成功、失败、不确定或返回页面，ThenI-02/03/05/10的反馈与原键行为成立；不会丢稿、自动开麦、续过期会话或重播开场白。

**测试/异常映射**：TC-C07-001、TC-C07-002；命令V-006，边界输入/异常顺序唯一见§12.2。UI：§8.8开始/发送/暂停组。保留行为：fix2排队轮询、reveal、Offer合法闸。

### 卡 C-08：麦克风统一取消与资源清理

**目标/输入**：REQ-005；C-05提交协议、C-07动作装配。

**交付/消费**：每个退出点调用同一幂等cleanup，后续权限/回调失效。

**必读与定位**：F-03/F-04、RULE-009、I-04；W18 start/abort及WS handlers。

**文件边界**：W13、W14、W18、W19、W26、W37、W38；仅§9.1登记符号及下表步骤。W99仅生成证据。不得扩大到同目录其他文件。

| 步骤 | W/符号 | 精确动作 | 完成检查点 |
|---|---|---|---|
| 1 | W18 | abort/cleanup第一步递增generation并停发送；保存每次start自己的ticket/generation，权限resolve若过期立即stop tracks；随后断开worklet/context/WS，清计时器 | 取消后授权迟到不进入recording |
| 2 | W37/W13/W18 | hidden/inactive/卸载/换号/结束先cleanup，再业务pause/end；WS在requesting/recording时异常同样清理；end已受理只关闭本地采集，服务端结果继续SSE跟踪 | 浏览器麦克风track全部ended，恢复必须用户新点击 |
| 3 | W18/W26 | 按契约消费accepted/transcript/completed/error或交SSE权威流程，不无视WS消息；不同turn/epoch消息不得推进UI | 无无限transcribing、旧消息无效 |
| 4 | W19/W14/W38 | 权限Promise可控、A→B→A、WSclose、隐藏/结束各单因子反例；断言track.stop/context.close/worklet.disconnect和无新POST | V-006，不以mock abort函数代替真实cleanup |

**验收**：AC-008：Given权限申请中或正在采集，When取消、隐藏、失活、结束、换号或WS断线，Then当前与迟到tracks都停止，所有旧回调失效；已end受理的轮次不被浏览器断线误取消或退款。

**测试/异常映射**：TC-C08-001；命令V-006，边界输入/异常顺序唯一见§12.2。UI：§8.8 recording/paused组。保留行为：现有重采样与60秒限制；录制输出和麦克风输入不混淆。

### 卡 C-09：字幕保存、分页和删除一致性

**目标/输入**：REQ-006、REQ-009；C-04正文事件、C-08退出清理。

**交付/消费**：偏好权威同步、完整易失/保存视图、错误可见。

**必读与定位**：F-05、RULE-010/011、I-06、API22～26。

**文件边界**：W13、W14、W20、W21、W28、W29、W37、W38、W46、W47、W71；仅§9.1登记符号及下表步骤。W99仅生成证据。不得扩大到同目录其他文件。

| 步骤 | W/符号 | 精确动作 | 完成检查点 |
|---|---|---|---|
| 1 | W20/W13/W37 | 以account+session+contentEpoch建立状态代次；Session创建/GET/事件同步偏好与version，尚未取得权威值不显示false假状态；savedEntries与ephemeralFinal分离，合并展示去重 | saveTranscript=true立即正确，空页不抹final |
| 2 | W20/W28/W29 | 保存成功推进version，分页nextCursor显式加载、失败不覆盖旧页；save/export/delete各有error/submitting。结束窗口不以迟到观察时间重置，未知截止以服务器拒绝为准，已知终态参考服务端时间 | 连续保存使用新版本；全部21条可达；终态error传入面板 |
| 3 | W20/W71 | 删除墓碑后清两份视图、停后续保存，旧response/event拒绝；410/409过期禁保存，禁止把浏览器正文上传补救。服务端现有已满足则只补验 | 删除及新账号不被迟到结果污染 |
| 4 | W21/W46/W47/W14/W38 | 组件+真实PG测试保存偏好、version、分页、删除race及导出内容 | V-015/V-006；断言服务器内容与页面，而非只返回true |

**验收**：AC-009：Given创建已勾保存或本场未保存final，When结束、加载已保存页、连续保存、删除或导出失败，Then偏好/版本准确、可用final不消失、分页完整、错误可见，墓碑与过期内容不复活。

**测试/异常映射**：TC-C09-001、TC-C09-002；命令V-006、V-015，边界输入/异常顺序唯一见§12.2。UI：§8.8 terminal-save-error/history组。保留行为：关闭偏好不删已有文本、独立录制字幕/素材不受误删。

### 卡 C-10：本人录制分页读取与 Edge 接线

**目标/输入**：REQ-010；C-06机器契约与C-09保存规则。

**交付/消费**：API41真实列表与授权下载入口，C-11使用。

**必读与定位**：API-003、RULE-001/012、§7.3。

**文件边界**：W01、W02、W03、W04、W05、W06、W07、W08、W77、W78、W79、W80、W82、W83、W113；仅§9.1登记符号及下表步骤。W99仅生成证据。不得扩大到同目录其他文件。

| 步骤 | W/符号 | 精确动作 | 完成检查点 |
|---|---|---|---|
| 1 | W113/W79/W78 | 追加V94索引，按owner/session有效性查询可回访状态，用(created_at,id) DESC keyset；cursor绑定session，limit严格1～100 | 同时间戳跨页不重不漏、deleted/expired不冒充ready |
| 2 | W77/W82/W83 | Controller接requirePersonal；Edge增加GET同flag/upstream，禁止放宽整个前缀method；flag=false仍404 | 真实公共路由可达，跨owner与错method拒绝 |
| 3 | W01～W08/W80 | 登记API41/RecordingPage/例子、前端listRecordings；真实PG含本人21条与他人记录/删除race，下载仍再鉴权 | V-001/V-005；counts不等于可访问内容 |

**验收**：AC-010：Given本人与他人会话/录制及相同创建时间，When经Edge分页读取并下载，Then只得到本人可回访记录，顺序完整且权限每次校验；匿名/越权/坏cursor/flag关闭没有内容泄露。

**测试/异常映射**：TC-C10-001；命令V-001、V-005，边界输入/异常顺序唯一见§12.2。UI：N/A：由C-11呈现。保留行为：原API34～38录制/保存与生命周期。

### 卡 C-11：历史只读详情与终态深链

**目标/输入**：REQ-010；C-09字幕与C-10API41。

**交付/消费**：HistoryDetail由Workbench可达，保存结果刷新后可消费。

**必读与定位**：F-05、RULE-012、I-07、W48三query。

**文件边界**：W13、W14、W24、W25、W30、W35、W36、W48、W105；仅§9.1登记符号及下表步骤。W99仅生成证据。不得扩大到同目录其他文件。

| 步骤 | W/符号 | 精确动作 | 完成检查点 |
|---|---|---|---|
| 1 | W24/W48/W13 | history行open写view=history+session；终态session深链直接详情/终态，不设置liveSession控制权，不发resume；账号/session切换取消并代次隔离 | 返回保留筛选滚动，非法query仍过滤 |
| 2 | W35/W30 | 组合只读Transcript和录制列表，load-more、TXT/MP4/SRT下载；MP4用授权blob/ObjectURL受控播放并撤销，保存资产显示既有asset状态；缺失/过期清楚反馈 | 不是只有count或删除按钮，能消费文件 |
| 3 | W25/W36/W105/W14 | 操作详情/分页/导出/返回；旧A请求晚于B响应返回不覆盖；404与成功空页分别测试 | V-006；terminal无接管按钮/peer/mic调用 |

**验收**：AC-011：Given本人终态会话已保存文字和录制，When列表打开详情或刷新深链，Then能读取所有分页并消费导出/录制，返回列表位置可恢复；没有控制会话副作用或跨账号缓存。

**测试/异常映射**：TC-C11-001；命令V-006，边界输入/异常顺序唯一见§12.2。UI：§8.8 history组。保留行为：会话删除解释与独立素材保留。

### 卡 C-12：角色显式重载、删除与无组合反馈

**目标/输入**：REQ-008、REQ-015、REQ-016；C-11装配基线。

**交付/消费**：同ID远端新稿能真正载入，删除闭环不破坏引用。

**必读与定位**：F-06、RULE-014/016、I-08/09；W31 watch、W22 reload/update。

**文件边界**：W13、W14、W22、W23、W31、W32、W37、W38；仅§9.1登记符号及下表步骤。W99仅生成证据。不得扩大到同目录其他文件。

| 步骤 | W/符号 | 精确动作 | 完成检查点 |
|---|---|---|---|
| 1 | W22/W31/W37 | 保留editingVersion与草稿绑定；后台profile.version变更只标stale；显式reload取得新profile后提升reloadToken，一次重置全部字段与editingVersion | 不会以新version提交旧字段 |
| 2 | W22/W13/W37 | 增加删除确认→deleteProfile→成功清selection刷新；失败保选择与文案，409显示引用原因；无backend仅重查catalog/保留历史，不创建未定义draft | 已有ProfileService服务端引用规则继续生效 |
| 3 | W23/W32/W14/W38 | 两页面版本冲突、显式重载/普通刷新、删除活动引用及无backend入口参数化测试 | V-006；最终提交payload是用户实际看到的字段 |

**验收**：AC-012：Given同ID角色被另一页面更新或正在使用，When当前页重载/保存/删除，Then显式重载与版本同步、普通刷新不覆盖草稿、活动引用不能删除；无backend提示与可操作入口一致。

**测试/异常映射**：TC-C12-001；命令V-006，边界输入/异常顺序唯一见§12.2。UI：§8.8 profile-conflict/empty-backend组。保留行为：个人角色归属、素材独立，角色保存验证不放宽。

### 卡 C-13：工作台分层、阶段布局与主题收口

**目标/输入**：REQ-013、REQ-014、REQ-015；C-07～C-12交互已通过。

**交付/消费**：W33/W34经W13真正挂载，工作台只装配且不超体积门槛。

**必读与定位**：RULE-016、§8全部、根DESIGN。

**文件边界**：W13、W14、W26、W27、W28、W29、W30、W31、W33、W34、W35、W39、W90；仅§9.1登记符号及下表步骤。W99仅生成证据。不得扩大到同目录其他文件。

| 步骤 | W/符号 | 精确动作 | 完成检查点 |
|---|---|---|---|
| 1 | W13/W33/W34/W35 | 配置分支整体受setup控制，活动/终态/历史互斥；仅一个历史入口；已保存角色默认摘要与开始行动可见，编辑在setup展开 | 活动下无完整配置/开始新场，首屏可找到主要动作 |
| 2 | W26～W31/W33～W35/W39 | 根与Teleport内容补gl-field，复用既有全局dh样式/token；简化内部术语、文案统一I表，保持标签/焦点/加载错误 | 明暗控件同一体系、无硬编码新色/字体 |
| 3 | W14/W13 | 核对视图仅装配，提取逻辑保持既有composable；运行体积/类型/lint/build，检查三入口基础样式 | W13及新增SFC≤800，无新增豁免 |
| 4 | W39/§8.8 | 逐项实际查看明暗及320/390/768/1024/1440截图，键盘操作与长弹窗滚动另测 | V-007/008；不是只保存PNG |

**验收**：AC-013：Given配置/活动/暂停/终态/历史及错误状态，When桌面和窄屏切换两主题并用键盘操作，Then仅相应区块出现、主要动作可达、样式与焦点符合§8，没有重复表单或新增横向溢出。

**测试/异常映射**：TC-C13-001；命令V-007、V-008，边界输入/异常顺序唯一见§12.2。UI：§8.8全部矩阵。保留行为：用户/治理首页样式冒烟，AI应用壳/导航保持。

### 卡 C-14：单栈 fixture 与可重建验收脚本

**目标/输入**：全部REQ的证据基础；C-01～C-13；尤其B-01供应商协议已冻结。

**交付/消费**：W85公开phase命令从新克隆构造真实业务验收，W89/90各自标明层级。

**必读与定位**：§9.3、§12.2证据层级、§12.3/12.5。

**文件边界**：W40、W85、W86、W87、W88、W89、W90、W91、W92、W117；仅§9.1登记符号及下表步骤。W99仅生成证据。不得扩大到同目录其他文件。

| 步骤 | W/符号 | 精确动作 | 完成检查点 |
|---|---|---|---|
| 1 | W85/W86/W88/W91/W92/W117 | 复用local-stack守卫；按§9.3白名单与当前依赖图逐构建/启动，生成测试证书/账号/控制面配置；浏览器同唯一网络真实WS/RTC，W117提供纯TCP loopback以保留安全上下文，禁止复制全量起栈脚本或裸docker绕过 | config闭包无无关服务，现有栈冲突时拒绝；失败/信号清理 |
| 2 | W87/W91 | fixture只替换外部LLM/STT/TTS/render，按已批准协议返回确定音频/帧/usage；计数、故障屏障接口只在测试网络；生产业务handler、鉴权、数据库、经济链不替换 | 请求内容/配置/调用数可观测；无真实外呼 |
| 3 | W89/W90 | 真实journey从用户按钮做开始/发送/语音/保存/结束/历史；错误视觉专项精确拦截且证明命中，不混为E2E。增加输出音频频谱/时长、帧序列/来源及第二轮判定 | 旧只验202/responding的完成判据不能过 |
| 4 | W85/W40 | 记录版本/SHA/hash、各层PASS/FAIL/NOT_RUN及测试数；零用例/跳过/非零任一失败，保留首次失败/重跑，捕获结果不吞退出码 | V-009，脚本不依赖本机review-probes或旧忽略目录 |

**验收**：AC-014：Given新克隆与登记工具，When执行runner正常/失败/中断/空用例分支，Then只使用唯一最小栈，能重建确定fixture、执行真实目标链、产出可归属证据并清理；假成功与静默skip均使gate失败。

**测试/异常映射**：TC-C14-001；命令V-009，边界输入/异常顺序唯一见§12.2。UI：W90承载§8.8；W89承载真实成功态。保留行为：既有ci-e2e/fix2/Hypit入口不改；生产配置不开启。

### 卡 C-15：最终回归、本地集成与索引收口

**目标/输入**：REQ-001～REQ-016；C-01～C-14卡级VERIFIED且无B-01。

**交付/消费**：本任务本地集成结论及必要索引，不创建默认独立报告。

**必读与定位**：§12.1/12.5、§14、附B.4。

**文件边界**：W03、W89、W90、W96、W97；仅§9.1登记符号及下表步骤。W99仅生成证据。不得扩大到同目录其他文件。

| 步骤 | W/符号 | 精确动作 | 完成检查点 |
|---|---|---|---|
| 1 | W89/W90/W99 | 按§12.3串行运行定向基线、模块回归、真实完整journey及视觉检查；保存原始日志/计数与失败首轮 | 所有必需门禁实跑，缓存/跳过不冒充 |
| 2 | W89/W90/实际diff | 从每REQ反推至少一个容易被绿灯掩盖的反例：实际JavaJSON、正规镜像、长录音、取消迟到、空页覆盖、历史回访、旧完成事件；核对真实调用/消费 | TC-C15-001/002；范围内失败回原卡而非改断言 |
| 3 | W96/W97 | 仅在有效证据满足时更新本任务状态/README行；明确LOCAL/PER_PROVIDER/DEVICE层，不改变旧任务状态或生产开关 | 无生产/商业实测则相应NOT_RUN，不能写beta通过 |
| 4 | W99/§14 | 检查新增diff与W表、清理本次资源、报告仍运行项目与原因；给可复制续作检查点 | 未满足任一必需项保持IMPLEMENTED或BLOCKED |

**验收**：AC-015：Given所有修复合并，When按§12.5走完真实本地业务链和回归，Then16项问题均有当前代码有效证据、产出可消费且无必需未完成项；运行资源有收尾，真实供应商/实机未测不被宣称已通过。

**测试/异常映射**：TC-C15-001、TC-C15-002；命令V-001～V-018，边界输入/异常顺序唯一见§12.2。UI：§8.8全部，实际查看。保留行为：fix2排队/收尾/Offer、数字人隐私/授权/录制及三入口。

## 12. 测试、命令与集成验收

### 12.1 需求追踪

证据统一W99，目录结构为phase/UTC运行批次；每份证据标本书版本、HEAD、相关diff/hash、命令与真实层。初稿全部NOT_RUN，历史复审日志不填本表PASS。

| REQ | 规则 | 卡/AC | TC | V |
|---|---|---|---|---|
| 001 | RULE-002/API-001 | C-01/AC-001，C-07/AC-007 | TC-C01-001、TC-C07-001 | V-001/002/006/010 |
| 002 | RULE-003～007/018 | C-03～06/AC-003～006，C-15/AC-015 | TC-C03-001、TC-C04-001/002、TC-C05-002、TC-C06-001、TC-C15-001 | V-004/010/012/013/014/016/017 |
| 003 | RULE-017 | C-02/AC-002 | TC-C02-001 | V-003/010 |
| 004 | RULE-008 | C-05/AC-005 | TC-C05-001/002 | V-014 |
| 005 | RULE-009 | C-08/AC-008 | TC-C08-001 | V-006/010 |
| 006 | RULE-010 | C-09/AC-009 | TC-C09-001/002 | V-006/015 |
| 007 | F-04/RULE-002 | C-07/AC-007 | TC-C07-002 | V-006/010 |
| 008 | RULE-014 | C-12/AC-012 | TC-C12-001 | V-006 |
| 009 | RULE-010/011 | C-09/AC-009 | TC-C09-001/002 | V-006/015 |
| 010 | RULE-001/012/API-003 | C-10/11、AC-010/011 | TC-C10-001、TC-C11-001 | V-005/006/010 |
| 011 | RULE-013 | C-07/AC-007 | TC-C07-001 | V-006/010 |
| 012 | RULE-013 | C-07/AC-007 | TC-C07-001 | V-006/008 |
| 013 | RULE-016 | C-13/AC-013 | TC-C13-001 | V-007/008 |
| 014 | §8/RULE-016 | C-13/AC-013 | TC-C13-001 | V-007/008 |
| 015 | D-10/RULE-016 | C-12/13、AC-012/013 | TC-C12-001、TC-C13-001 | V-006/008 |
| 016 | RULE-014/015 | C-04/07/12、AC-004/007/012 | TC-C04-001、TC-C07-002、TC-C12-001 | V-006/013 |
| 验收可信性 | §12.2/9.3 | C-14/15、AC-014/015 | TC-C14-001、TC-C15-001/002 | V-009/010/011/016～018 |

### 12.2 测试用例

**共享fixture**

- **F-UNIT**：真实生产composable/组件/handler；账号A/B均合成个人账号，session S1/S2、turn T1/T2用不同合法UUID，lease=3、media=7、content=2、transcriptVersion=4，故意不让三种epoch相等。时钟固定2026-10-02T00:00:00Z；不同测试独立清理。不记录实际账号。
- **F-API**：IntelligenceItSupport真实PG与本任务专用无持久化Redis；真实服务/序列化/事务/预算/鉴权，内部断言用现有签名fixture。只有外部provider允许WireMock。文本输入固定“请用一句话介绍草场。”，回复固定“草场帮助你完成内容创作。”，usage inputTokens=12/outputTokens=9；TTS为24k mono s16le，2秒440Hz PCM，后段660Hz，Java保留真实流代码。
- **F-LOCAL**：W85构建的唯一fresh栈、真实三origin/UI/API/DB/Redis/mTLS/RTC/录制。外部模型仅W87：STT返回上述输入；LLM返回两句与真实格式usage；TTS返回确定频率/时长；远端fixture的协议以B-01冻结版本为准，输出带确定色块/序号的非静态帧以证明实际媒体路径。该证据不声称口型质量。
- **F-UI**：真实AI页面，仅为指定错误/加载/空态精确拦截一项HTTP；必须断言该请求确实命中。不能用于完成业务API/账务/输出链的证明。
- 清理：每TC用自己创建的owner/resource标识删除隔离数据/关闭tasks/tracks/context/ObjectURL；不清整库或其他任务资源。时间用fake clock，竞态用deferred promise/屏障，禁止靠任意sleep碰运气。

#### TC-C01-001：真实 wire 与状态动作（P1）

- 关联：REQ-001/AC-001，E06/E09/E13/E15；W15→W07/W14，服务契约+组件层。
- 前提：F-API，A的S1完整字段，serverNow固定；创建、GET、pause、resume、end、media-ready和SSE snapshot都走真实handler。
- 顺序：保存Java实际JSON，逐个喂生产decoder；再单独删id、删leaseEpoch、billing=null、state=bad、epoch=-1/9007199254740992、nested session、{paused:true}、{state:ended}；各负例只破坏一项。
- 断言：正例字段全、URL没有undefined、操作状态可更新；所有非法形态拒绝且不发后续resume/end。pause重放不改pausedUntil，其他用户/匿名不泄露数据。
- 清理/证据：F-API清理，V-001/002，wire JSON脱敏归W99/wire，组件动作日志只ID摘要。
- 防假阳性：不能手写“符合前端期望”的JSON代替服务响应；恢复旧Java record或宽松decoder时对应断言必失败。

#### TC-C02-001：正式双面与资源生存期（P1）

- 关联：REQ-003/AC-002，E04/E05/E12/E20；W42/W107。
- 前提：真实W50/W53入口+临时CA及正确SAN，F-API Java内部面；禁测试直接赋app.state.sessions。
- 顺序：内部创建S1→audio有效grant→观察同binding；分别缺cert、错CA、错client SAN、伪造证书header、错误authority；另杀control listener、SIGTERM整host。
- 断言：两个监听同host/registry，真实TLS双向通；负例握手或初始化拒绝、无业务请求；任一必要listener退出，另一个10秒内关闭，process非零；正常停机无tasks/peers/httpx遗留，health不是永远ok。
- 清理/证据：关闭全部本次子进程/证书，V-003日志/端口/PID摘要；没有secret。
- 防假阳性：恢复双进程工厂或bridge=None时跨面读/授权必须失败；不只检验源码字符串含host。

#### TC-C03-001：冻结、阶段流与经济幂等（P1）

- 关联：REQ-002/AC-003，E03/E05/E07/E14/E16/E20；W41，真实PG/执行服务。
- 输入：F-API，预检冻结配置V1/credential C1/price P1；分别平台LLM与own LLM，STT/TTS固定平台。文本边界1/2000/2001码点，句段119/120/121，空白；固定4段输出含无句号尾段。
- 顺序：同invocation两个请求在claimDispatch前屏障并发；预检后更改配置V2/撤销C1；旧schema标签快照；provider在首delta后断流/缺usage/错误usage；cap剩余小于本次预留；取消发生dispatch前与后各一例。
- 断言：合法流所有已审段和尾句正确，最多一次provider调用/ai_run/经济operation；漂移、超长、预算不足派发0；own只影响LLM；缺usage不填0，不自动重调/退款；账务失败仅结算重试。秘密/正文不进入普通Redis/日志/dh_event。
- 清理/证据：F-API清理，V-012，各case输出原run/operation计数、余额差/预算状态，无真实费用。
- 防假阳性：真实AiExecution/claim/settle不能mock；不能同时用“未登录”掩盖版本保护失效。

#### TC-C04-001：文字/开场白/第二轮与重放（P1）

- 关联：REQ-002/016、AC-004，E01/E02/E03/E07/E16；W44/W45，真实编排+PG。
- 输入：F-API/F-UNIT，ready S1，T1文字；greeting为“你好，欢迎来到草场。”，无greeting另设角色。
- 顺序：发送T1至实际输出完成→发T2；同requestId/同text并发两次；同key异text；同场两把不同key并发greeting；刷新后重取snapshot再次尝试greeting；预留后进程崩溃重启。
- 断言：两轮各自有LLM/TTS调用、可消费输出、final、DB completed与ready；同键一轮/一份收费；greeting只有TTS且每场一次受理，空greeting422零provider；持久marker与原requestId一致，旧多个greeting存量回填不删账务。
- 清理/证据：V-013，真实命令/输出/表计数；F-API清理。
- 防假阳性：只出现turnId/responding/202不通过；只前端disabled挡重播也不通过，必须直调后端负例。

#### TC-C04-002：完成乱序、隐私、取消与崩溃（P1）

- 关联：REQ-002/AC-004，E04/E09/E12/E15/E18/E20；W45/W115。
- 前提：F-API，S1正在T1；正文canary仅合成“FIX3_PRIVATE_CANARY”；saveTranscript=false。
- 顺序：T1输出中interrupt→T2→迟到T1 completed；重复eventId两次；旧lease/未来lease、旧media/content各独立事件；删除字幕后迟到final；故障分别在事务提交前、提交后未dispatch、provider已dispatch丢ack、完成持久化后丢live通知。时钟推进到90秒截止及下一次5秒scan。
- 断言：旧轮不终结T2、不将ending变ready，不重放生成；元数据可恢复，未保存正文不在dh_event/SQL日志/普通Redis；未知原invocation保持核对，任务与采集释放。已保存偏好true另例只进合法transcript。
- 清理/证据：V-013，表状态/原键/清理清单；不把canary放生产日志。
- 防假阳性：不得mock掉INTERNAL06或turn CAS；换回“仅append事件不更新状态”时第二轮或终态断言失败。

#### TC-C05-001：长音频与真实背压（P1）

- 关联：REQ-004/AC-005，E02/E14/E22；W43/W102/W103/W104。
- 输入：16k mono s16le，3/30/60秒=48000/480000/960000 samples；分别320sample和worklet小块128sample分帧；seq从0单调，end汇总实际samples/frames/bytes。
- 顺序：消费者正常逐帧处理（虚拟时钟每20ms或8ms推进）；独立case锁住消费屏障，pending=31999、32000、32001 samples；边界PCM1920000 bytes及WAV1920044 bytes；额外1sample/60001ms独立负例。
- 断言：3/30/60秒均不4429，真实Java STT正常受理；仅pending>32000才4429且清buffer；越界4413/Java422，provider0；历史collector样本不计队列。
- 清理/证据：V-014；清队列/task/内存；记录长度/序号/close码不记音频正文。
- 防假阳性：消费者从未启动或使用总帧数计背压时正例必失败；不能把长录音缩短到2秒。

#### TC-C05-002：end/abort/转写空结果（P1）

- 关联：REQ-002/004、AC-005，E04/E05/E12/E16；W43/W104。
- 前提：F-API且三个epoch互不相等，合法grant。
- 顺序：空PCM end、错误seq/end汇总、5秒idle、有效PCM abort、未end断线；另例有效end已受理后立即WSclose；STT返回空白；合法3秒STT到LLM/TTS/完成。
- 断言：未end拒绝路径零STT且turn interrupted/failed符合原原因，session可继续；已end链继续一次、无重复STT；空转写不调LLM/TTS；deadline/mediaEpoch正确；成功最终不是1011占位。
- 清理/证据：V-014；collector与任务释放/原invocation计数。
- 防假阳性：auth全部真实通过，不能由票据错误掩盖end/队列校验；STT后占位503会使成功链失败。

#### TC-C06-001：远端协议及隔离（P1；B-01 未解 NOT_RUN）

- 关联：REQ-002/AC-006，E03/E05/E07/E16/E18/E20；W84/W110。
- 前提：B-01关闭后的已冻结供应商协议文档+录制协议回放fixture；真实Java adapter/runtime media，不是真实商业费用。
- 顺序：创建→PCM输入→视频回流→interrupt→同会话继续→资格续期→close→查询usage；并发同key/create超时，过期grant/lease撤销，伪造usage/恶意redirect，关闭未知/迟到结果，配置禁用；static标approved负例。
- 断言：按K14原run/同providerSessionRef，真实取消/查询语义，短票≤30秒且≤lease，未知不新create；媒体有可消费音画且来源明确，长期key不离Java；不满足证据/按秒价表能力的组合不可approved。
- 清理/证据：V-004；远端测试资源删除确认/未知分别记录；fixture只能记PROTOCOL_LOCAL_PASS，商业REAL_NOT_RUN。
- 防假阳性：不能让W87自定义协议即宣称供应商支持。本TC外部字段和响应序列随B-01由规划者填定，当前不满足可实施条件，绝不能勾PASS。

#### TC-C07-001：受理、失败与未知结果保稿（P1）

- 关联：REQ-001/011/012、AC-007，E03/E05/E09/E15/E16；W14/W17/W38。
- 前提：真实组件F-UNIT，draft“请保留这段文字”，创建弹窗save=true；成功数据使用C-01真实wire。
- 顺序：连点2次；create/turn分别503、422、超时但operation受理；延后S1请求，切S2后释放旧success/catch/finally；IME composing Enter、普通Enter、Shift+Enter分别操作。
- 断言：一次在途请求；503稿件/选择保留、错误可见焦点正确；确认受理才清draft/关dialog；未知只用原requestId查询，不新建session/turn；旧finally不解除S2 submitting；中文输入不误发。
- 清理/证据：V-006；卸载清请求，DOM/请求序列，错误截图V-008。
- 防假阳性：不stub父级发送函数返回void；恢复emit后立刻清空时保稿反例失败。

#### TC-C07-002：暂停恢复与开场白回访（P1）

- 关联：REQ-007/016、AC-007，E07/E10/E12/E15；W14/W17/W38。
- 输入：pausedUntil=00:00:30Z；固定时钟00:00:29Z、00:00:30Z；本controller和另一controller分别；greeting snapshot为null、已有T1、缺字段三种。
- 顺序：hidden→pause→visible→GET→显式恢复；并行重复恢复；过期/ended/failed深链；greeting点击→超时→原键查询→刷新；旧lease返回在新lease之后。
- 断言：无自动开麦、可恢复时按钮可达，过期不resume、不延pausedUntil；同controller不误接管，不同controller明确takeover；greeting同session只一受理，缺状态时先GET不猜能播放。
- 清理/证据：V-006；停止计时器/peer；V-010验证真实后端。
- 防假阳性：将R01修好后仍无恢复按钮应失败；新页面只本地seen=false会被刷新重播反例发现。

#### TC-C08-001：权限迟到及退出矩阵（P1）

- 关联：REQ-005/AC-008，E04/E10/E11/E12/E15/E16/E18；W19/W38/W14。
- 前提：真实mic composable，getUserMedia用deferred返回带stop spy的MediaStreamTrack；WS/AudioContext接口测试double只替浏览器依赖。
- 顺序：start→abort→resolve权限；start→hidden/KeepAlive inactive/end/unmount/换号分别单因子；recording→WSclose；A→B→A后释放A第一次权限；end发送前后各断线，旧finally在第二次start后返回。
- 断言：过期track立即stop一次，无recording复活/额外grant/音频chunk；当前tracks ended、context close、worklet disconnect、timer取消；新操作不被旧finally清掉；已end无业务退款/重复STT。
- 清理/证据：V-006，事件序列/资源计数；真实browser的授权/录音标识由V-010观察。
- 防假阳性：用真实abort，不mock业务清理；删generation++时首个反例必须失败。

#### TC-C09-001：保存偏好、空页、分页、版本（P1）

- 关联：REQ-006/009、AC-009，E05/E08/E09/E13/E22；W21/W46/W47/W14。
- 输入：S1 save=true/version4；另例false但有2条本地final；服务端已存21条序号1～21（默认页20），与本地一条utteranceId重复。
- 顺序：创建→偏好读取→结束自动reload；取第2页→再reload首页；saveNow返回version5→再次保存；setSave=false；导出500与成功TXT各例。
- 断言：保存设置真实且loading不假false；未保存空列表不清2条final；分页全部21条不重不漏，失败保前页；第二次expectedVersion5；关闭偏好不删已有；TXT解码含完整已存文字/顺序，不含未存delta；错误显示在terminal。
- 清理/证据：V-006/015，PG重读与下载内容hash/合成摘录；ObjectURL撤销。
- 防假阳性：固定mock version=1或只断言保存返回true不能通过。

#### TC-C09-002：墓碑/到期/切号竞态（P1）

- 关联：REQ-006/009、AC-009，E09/E11/E15/E17/E18/E20；W21/W46。
- 前提：F-API，S1 contentEpoch2，未保存缓冲；终止时间00:00:00Z，当前00:09:59Z/00:10:00Z。
- 顺序：开始read/save→delete推进epoch3→旧read/final/save回包到达；独立case切账号A→B→A；迟到终态事件在9分钟收到；Redis被清后刷新再点击保存。
- 断言：epoch2内容不复活/不跨账号；10分钟截止不因观察/刷新变19分钟；服务端410/409后不可再保存，不从浏览器重新上传；关闭偏好并非删除；已保存录制asset保持。
- 清理/证据：V-006/015，DB墓碑/内容查询、浏览器状态/网络零补传。
- 防假阳性：所有旧请求原先合法，不能用401代替墓碑保护；不能靠UI清屏掩盖服务端再次落库。

#### TC-C10-001：录制回访权限与分页（P1）

- 关联：REQ-010/AC-010，E06/E07/E08/E14/E17/E18/E19/E22；W80/W83。
- 输入：A的S1有21条有效录制，其中相同created_at两个不同UUID；另有expired/deleted及B的S2；limit=1/20/100/0/101、另一session cursor、缺/坏UUID。
- 顺序：GET各页，切limit继续，下载一条；分页后删除该session再读/下载；匿名/组织模式/他人ID/flag=false/错误method分别单因子。
- 断言：keyset顺序完整，空200与404区分；越权不泄漏数量/metadata，下载重新鉴权；索引新库/升级/再次migrate成功，旧数据/资产不丢。
- 清理/证据：V-005；真实Edge路由/Java/PG与查询结果，删除本次数据。
- 防假阳性：不能仅mock listRecordings返回一页或只在前端隐藏他人记录。

#### TC-C11-001：历史实际消费与深链（P1）

- 关联：REQ-010/AC-011，E04/E08/E10/E11/E15/E22；W25/W36/W105/W14，最终W89。
- 输入：F-UNIT/F-LOCAL，A终态S1含21条字幕、2条录制（1 saved、1 ready）、B另一会话。
- 顺序：历史行查看→加载更多→导出TXT→授权下载MP4/SRT→返回→刷新?view=history&session=S1；A详情请求延后后切B，释放A；500/404各单因子。
- 断言：能读第21条和播放非空正确时长音视频，SRT时序合法；终态没有接管/peer/mic/resume请求；返回筛选与焦点；旧内容不覆盖B，错误非空态成功。
- 清理/证据：V-006/010，下载内容/trace/截图；ObjectURL与资源清理。
- 防假阳性：仅显示recordingCount、资产ID或文件存在不通过，必须实际消费。

#### TC-C12-001：角色冲突/删除/目录空态（P1）

- 关联：REQ-008/015/016、AC-012，E05/E07/E09/E15/E18；W23/W32/W14/W38。
- 输入：P1 version1 persona=“本页旧稿”；远端version2 persona=“另一页面最新稿”；P1有活动session另例；catalog无approved backend。
- 顺序：提交冲突→普通后台刷新→检查草稿→点击显式重载→保存；并发删除/开始引用；目录空后重新检查恢复。
- 断言：后台刷新不更新editingVersion为2而保留旧稿可提交；显式重载所有字段和version2一起替换；后续payload为最新可见字段；活动引用删除409且角色保留；无backend不承诺可创建、history仍可达。
- 清理/证据：V-006，真实ProfileService引用保留回归纳V-016；UI截图V-008。
- 防假阳性：仅watch id时同ID重载用例必失败；删除不能只断言按钮消失。

**普通用例（低风险布局/文案）**

| TC/关联 | 层级/位置 | 前提与操作 | 预期/禁止结果 | V |
|---|---|---|---|---|
| TC-C13-001 / REQ-013/014/015、AC-013、E08/E13/E22 | 视觉/键盘/W90+W14 | §8.8全部状态及视口，长角色名100码点、长错误500字；明暗切换；Tab/Enter/Esc；IME与移动键盘 | I表控件/焦点/标签可用，active/terminal无配置分支与第二开始；Teleport同主题；无横向页溢出/遮挡；W13≤800 | V-007/008 |

#### TC-C14-001：runner真实层与收尾（高风险资源）

- 关联：AC-014，E05/E12/E20；W40/W85。
- 前提：模拟守卫命令响应做脚本契约单测，另用唯一隔离栈验证运行路径。
- 顺序：正常phase、未知phase、缺测试文件、0用例、意外skip、命令exit1、server启动半途失败、SIGTERM、存在他人栈；分别检查。
- 断言：未知exit2，缺用例/skip/失败非零；不继续后phase；只允许明确service闭包，build串行，先盘点；保留原有服务/卷，关本次进程与证书；JSON结果不把mock层改成E2E。
- 清理/证据：V-009；失败日志/守卫记录/PID与资源清单；不运行全局prune。
- 防假阳性：字符串含trap不足以证明清理；至少一次真实中断后确认资源。

#### TC-C15-001：从用户入口到历史的完整链（P1）

- 关联：全部REQ/AC-015，E03/E04/E10/E20/E22；W89，F-LOCAL。
- 顺序：个人A登录AI入口→保存角色→费用确认save=true→开始并ready→显式开场白→文字T1→3秒语音T2→开始输出录制→文字T3→停止/保存录制→暂停并恢复→文字T4→保存字幕→结束→刷新历史查看/导出→同账号另页查看。所有被验收动作通过真实UI点击。
- 断言：每轮完整assistant final、可辨识输出PCM（440/660Hz，时长与fixture在±100ms）、视频实际解码多帧且带fixture帧序号/预期色块；不能只videoWidth/帧hash不同。turn均终态、session可第二轮；greeting零LLM；PG原invocation/run/经济键对应且无重复扣款；TXT包含已保存完整文本，MP4经ffprobe为H.264+yuv420p/AAC/48k、duration>0并实际播放，SRT UTF8/顺序/区间有效且与录制段匹配。
- 证据：V-010；浏览器trace、网络请求摘要、provider计数、PG安全元数据、音频频谱/帧序列/ffprobe、实际查看截图。三引擎串行；真实mic硬件与商业口型不在此声称。
- 清理：W85唯一栈清理，个人产物只在本次fresh库/卷。
- 防假阳性：route.fulfill业务成功API、SQL直接置completed、绕过按钮提前save、静态runner输出替远端fixture、只202/responding，任一均不满足。

#### TC-C15-002：最终反例复核（P1）

- 关联：全部REQ/AC-015，E07/E09/E11/E15/E16/E17/E18/E20；W89/W90。
- 前提：真实F-LOCAL两个人账号/双页面；F-UI仅可补精确错误态。
- 顺序：①S1生成中打断→T2→迟到T1完成；②权限pending→取消→授权；③未保存final→end→空保存页；④开场白受理后刷新再点；⑤账号切换后旧字幕/下载响应；⑥API响应已提交但浏览器丢响应，再原key查询；⑦runtime重启后恢复原invocation核对；⑧真实JSON/默认镜像入口复核。
- 断言：分别对应RULE-004/007/009/010/011/013/015/017，无旧任务污染、新收费、隐私复活或假就绪。把旧缺陷行为临时恢复到受控测试double/断言对照时，该反例应失败；不得改业务源码制造演示。
- 清理/证据：V-010/008与各定向V，保存首次失败/修复证据及实际diff复核结论，缺哪层即标哪层NOT_RUN。
- 防假阳性：不要同时叠加多个拒绝条件；检查是否实际命中所声称的业务路径，不靠截图/未登录页面通过。

**证据层级**

单元可mock浏览器API/网络/时钟但不mock被测生产逻辑；服务集成真实handler/序列化/PG/事务，只有外部模型fixture；本地E2E真实UI、路由、API、鉴权、状态、输出消费；UI错误专项单独标记。协议fixture不证明商业音画质量/真实账单，桌面WebKit不证明iOS实机。未知/未测写NOT_RUN，不能填N/A或PASS。

### 12.3 验证清单

**统一入口约定**：以下命令全部在仓库根用bash执行。W85由C-01先创建基础phase；C-13交付UI spec，C-14补真实E2E phase。C-01先V-002再V-001；java-wire将本次合成响应位置及源hash写W99清单，contract从最近匹配当前源hash的成功记录读取并注入DH_WIRE_FIXTURE_FILE；没有该记录就明确失败，不能改用手写成功快照。表中“内部命令”是runner必须逐字等价执行的固定清单，不是候选菜单。不存在目标测试/phase、0用例或skip必须非零退出。规划期本表**全部NOT_RUN**。

Java共同前缀：进入platform-java，`source ../scripts/lib/java-runtime.sh`、`ensure_java_runtime 25`，通过根`npm run stack -- run --project y1-e2e-local --docker -- bash -c`持锁执行Gradle；附加`--no-daemon --max-workers=1 --no-parallel --no-build-cache --rerun-tasks`，只在无应用栈阶段执行Testcontainers。测试包统一`com.grassland.intelligence.digitalhuman`，表中类名是该完整包下的精确类，不用全包通配代替定向验证。

Python共同前缀：工作目录platform-realtime/digital-human，`uv run --frozen python -m pytest -q`，PYTHONPATH=src，单进程不加xdist；vendor/overlay仅沿既有bootstrap创建，禁止修改vendor。缺已登记依赖可按lock准备，不升级。

| V | 外部精确命令 | 内部固定执行/发现范围 | 前置/副作用 | 必需通过标准 |
|---|---|---|---|---|
| V-001 | `bash scripts/acceptance/verify-105-fix-3.sh contract` | `npm run test -- tests/contracts/digital-human.contract.test.ts src/composables/useDigitalHumanApi.test.ts --maxWorkers=1 --no-file-parallelism` | C-01开始，无Docker | schema及当前wire正/负例均发现并通过 |
| V-002 | `bash scripts/acceptance/verify-105-fix-3.sh java-wire` | Gradle `:services:intelligence-service:test`，--tests DigitalHumanWireIT、DigitalHumanSessionIT、DigitalHumanLeaseIT、DigitalHumanEventIT、DigitalHumanOfferIT（完整包前缀见上） | PG/Testcontainers；合成wire生成W99 | JUnit目标类各>0、0失败/跳过，真实JSON产物可读；缓存不计实跑 |
| V-003 | `bash scripts/acceptance/verify-105-fix-3.sh runtime-host` | pytest tests/test_runtime_host.py tests/test_security.py tests/test_control_contract.py；再Gradle test --tests DigitalHumanRuntimeAssemblyIT --tests DigitalHumanInternalTlsIT | 先纯Python后单独镜像/Java；临时TLS/端口 | 正式入口、握手/共享registry/关闭均实测 |
| V-004 | `bash scripts/acceptance/verify-105-fix-3.sh render` | Gradle test --tests DigitalHumanRenderIT；随后pytest tests/test_remote_renderer.py | B-01已解除；仅外部fixture | 协议/计量/取消/续票负例全过；不能报商业REAL_PASS |
| V-005 | `bash scripts/acceptance/verify-105-fix-3.sh history-api` | Gradle intelligence:test --tests DigitalHumanRecordingHistoryIT --tests DigitalHumanRecordingIT --tests DigitalHumanSchemaIT；然后edge-bff:test --tests com.grassland.edge.DigitalHumanRecordingRouteTest | PG迁移V93/V94；分模块串行 | 新库/升级/重跑、权限/排序/Edge全过 |
| V-006 | `bash scripts/acceptance/verify-105-fix-3.sh frontend` | `npm run test -- src/views/digital-human src/composables/useDigitalHumanApi.test.ts src/ai/components/AiWorkspaceNavigation.test.ts --maxWorkers=1 --no-file-parallelism` | 无Docker；本阶段定向回归 | 相关真实组件/composable断言全过，0skip |
| V-007 | `bash scripts/acceptance/verify-105-fix-3.sh quality` | 顺序npm run typecheck、npm run lint、npm run build、npx @google/design.md lint DESIGN.md；最后`rg -ni 'sohne|cal sans|cal.com|stripi' DESIGN.md src/ops/DESIGN.md` | 无Docker，构建输出dist | 前四退出0、文件体积不过顶；最后无输出且exit1，exit2不是通过 |
| V-008 | `bash scripts/acceptance/verify-105-fix-3.sh ui` | 守卫内启动`npx vite --mode ai --host 127.0.0.1 --port 5173 --strictPort`，AI_BASE_URL=http://127.0.0.1:5173；`npm run e2e -- tests/e2e/digital-human-fix3-ui.spec.ts --project=chromium --workers=1 --retries=0`；退出关闭本次Vite/browser | 仅UI专项，无后端；若5173占用先查归属不另换端口开一套 | §8.8状态/键盘断言，实际打开查看所有主题截图并记结论；UI层结果明确 |
| V-009 | `bash scripts/acceptance/verify-105-fix-3.sh harness` | `npm run test -- tests/deployment/digital-human-fix3-runner.test.ts tests/deployment/digital-human-topology.test.ts tests/deployment/digital-human-ci.test.ts --maxWorkers=1 --no-file-parallelism`；npm run quality:lifecycle；`bash -n scripts/acceptance/verify-105-fix-3.sh` | C-01基础分支可先测，C-14完整必测；真实中断场景随V-010 | 测试/登记/语法0，清理/无假成功分支可证 |
| V-010 | `bash scripts/acceptance/verify-105-fix-3.sh e2e` | W85按§9.3守卫搭唯一栈并启动W117转发，W88内`npm run e2e -- tests/e2e/digital-human-fix3-journey.spec.ts --project=chromium --workers=1 --retries=0`，再分别firefox/webkit同命令；每引擎使用独立本轮合成账号资源但不另起栈 | 真实Java/PG/Redis/runtime/RTC；外部fixture；单引擎串行 | TC-C15-001/002及收尾全过，任何skip/零用例/偶发一次成功不通过 |
| V-011 | `bash scripts/acceptance/verify-105-fix-3.sh docs` | npm run docs:links、npm run docs:status、git diff --check；再核对本书/README链接及新增diff/W表 | 仅文档产物；不改无关文档 | links/status全局原始结论保留；本任务链接可达/无新增断链、diff无空白错；其他基线失败明确分列 |
| V-012 | `bash scripts/acceptance/verify-105-fix-3.sh java-stages` | Gradle intelligence:test --tests DigitalHumanExecuteStagesIT --tests DigitalHumanInvocationIT --tests DigitalHumanTextBridgeTest --tests DigitalHumanAudioBridgeTest --tests DigitalHumanExecuteSttIT | 真实PG/外部WireMock/独立Redis | TC-C03-001及原经济链回归0失败 |
| V-013 | `bash scripts/acceptance/verify-105-fix-3.sh turns` | Gradle intelligence:test --tests DigitalHumanTurnDispatchIT --tests DigitalHumanTurnRecoveryIT --tests DigitalHumanTurnIT --tests DigitalHumanEventIT --tests DigitalHumanReaperTest；随后pytest tests/test_turn_coordinator.py tests/test_fake_pipeline.py tests/test_media.py tests/test_realtime_isolation.py | 先Java再Python；不并发重型 | 轮次真实完成/迟到/重启/迁移/greeting全部满足 |
| V-014 | `bash scripts/acceptance/verify-105-fix-3.sh audio` | pytest tests/test_audio.py tests/test_audio_turn_wiring.py；随后Gradle intelligence:test --tests DigitalHumanAudioBridgeTest --tests DigitalHumanExecuteSttIT | 60秒音频固定虚拟时钟，不需真实mic | TC-C05-001/002边界、真实handler通过 |
| V-015 | `bash scripts/acceptance/verify-105-fix-3.sh transcript` | Gradle intelligence:test --tests DigitalHumanTranscriptIT --tests DigitalHumanPrivacyIT --tests DigitalHumanContentBufferTest --tests DigitalHumanSaveGcRaceIT | 真实PG/易失Redis | 保存/墓碑/导出与独立asset回归 |
| V-016 | `bash scripts/acceptance/verify-105-fix-3.sh java-regression` | Gradle `:services:intelligence-service:check :services:edge-bff:check`，共同单worker参数 | 前序定向通过后；不能与Compose并行 | 当前模块测试/格式/JaCoCo原门槛；原失败如实FAIL，不因历史说明变绿 |
| V-017 | `bash scripts/acceptance/verify-105-fix-3.sh python-regression` | `uv run --frozen python -m pytest -q tests` | runtime全套串行、无另一栈 | 所有现有/新增runtime测试发现且0失败 |
| V-018 | `bash scripts/acceptance/verify-105-fix-3.sh frontend-regression` | `npm run test:coverage -- --maxWorkers=1 --no-file-parallelism`；`npm run coverage:changed` | 第三轮跨模块/公共契约修复，前端全回归一次；coverage-final.json必须本次 | 现有阈值保持，变更可执行行≥80%；包含未跟踪新文件，不设COVERAGE_BASE_REF把它们漏掉 |

为避免缩写被弱模型拼错，W85实现时将上表“Gradle intelligence:test”展开为`:services:intelligence-service:test`，“edge-bff:test”展开为`:services:edge-bff:test`，每个--tests都使用完整包名。这些是**确定转换规则**，不是让执行者自行选择测试集。W40测试精确断言phase→目标类/文件与公共参数映射。

W99证据：每phase目录包含command.log、results.json（版本、SHA、相关diff/hash、开始/结束、退出码、发现/通过/失败/跳过数、缓存状态、层级、资源清理）、JUnit/截图/媒体内容验证结果。原始输出保留首轮失败，后续重跑另目录。短命secret不得写command.log。全量门禁与定向门禁分开，实际相同基线证据可复用，但需核实hash及覆盖范围。

**基线失败处理**：规划只引用复审/fix2结果，不批准任何新豁免。V-016若失败，先在本W范围判断本任务影响；独立已存在失败需原命令、具体用例、隔离基线证据及不影响本链的依据，再由负责人明确裁决。未经裁决不得把整任务VERIFIED。V-011可能有全仓历史未索引文件；保留全局FAIL，同时证明本任务增量无新增错误，不扩大修复范围。

### 12.4 命令索引

本任务命令统一见§12.3，无候选菜单。

### 12.5 最终集成验收与完成定义

负责人：当前执行模型，责任C-15。前提：B-01关闭、规格READY、C-01～C-14卡级VERIFIED、当前代码包含全部交付。

1. V-001～V-018所有本任务必需项通过；同基线有效证据可按§12.3复用，未测不算通过。
2. TC-C15-001真实链贯穿三origin→Edge→Java→Python→外部协议fixture→媒体/字幕→保存→历史；只替换批准外部provider。三引擎串行，必须看到实际生成完成与下一轮。
3. TC-C15-002反向复核、§8.8全部截图查看与交互检查、权限/费用/删除/资源清理成立；原fix2回归Offer、晋升、ending收尾不得破坏。
4. 检查每REQ→卡/W→AC→TC→V→本次有效证据；本地结果不可升级真实第三方/设备结果。**LOCAL_INTEGRATION_PASS只证明本地完整业务与协议；真实商业模型质量/成本和iOS/Android实机仍按实际标REAL_NOT_RUN/DEVICE_NOT_RUN，不阻止本地既定终点，也不得据此开放beta。**
5. 差异、文档与资源收尾无遗漏。卡实现完但必需验收未完仅IMPLEMENTED；范围内失败/缺环境保留BLOCKED，不声称功能全部修好。

### 12.6 本地更新与回退顺序

仅本地验收/配置模板变化，不授权生产部署。

| 阶段 | 操作 | 成功/回退触发 |
|---|---|---|
| 更新前 | 盘点并保留原数据/已有工作；创建守卫认可的fresh测试资源，保存镜像/源码基线 | 归属不明不动 |
| 本地更新 | Flyway V93/V94→Java→runtime host→frontend；串行健康检查，再跑真实链 | migration/握手/真实链失败停止继续；不再创建第二栈 |
| 本地回退 | 停本次新增服务，使用原应用版本与原配置；保留新增列/索引及所有数据，feature默认关闭 | 不运行旧代码发起新greeting绕过约束；旧schema只允许结束/历史 |
| 收尾 | 守卫停止/清理本次fresh资源、删除临时secret、报告保留资源 | 不恢复旧全量栈、不删除原有卷 |

### 12.7 边界目录

| E | 场景 | 责任TC |
|---|---|---|
| E01 | 空输入 | TC-C04-001、TC-C05-002 |
| E02 | 超长输入 | TC-C03-001、TC-C05-001 |
| E03 | 重复提交 | TC-C03-001、TC-C04-001、TC-C07-001 |
| E04 | 断网/断流 | TC-C02-001、TC-C05-002、TC-C15-002 |
| E05 | 服务端/第三方错误 | TC-C03-001、TC-C06-001、TC-C07-001 |
| E06 | 未登录/失效 | TC-C01-001、TC-C10-001 |
| E07 | 无权/非法状态 | TC-C04-001、TC-C07-002、TC-C10-001 |
| E08 | 成功空数据 | TC-C09-001、TC-C11-001、TC-C13-001 |
| E09 | 过期/版本冲突 | TC-C09-002、TC-C12-001 |
| E10 | 深链/刷新/返回 | TC-C07-002、TC-C11-001 |
| E11 | A→B→A | TC-C08-001、TC-C09-002 |
| E12 | 卸载/隐藏/失活 | TC-C02-001、TC-C08-001 |
| E13 | 缺字段/null/0/false/非法枚举 | TC-C01-001、TC-C09-001 |
| E14 | 极值及±1 | TC-C03-001、TC-C05-001、TC-C10-001 |
| E15 | 乱序/旧finally | TC-C04-002、TC-C07-001、TC-C08-001 |
| E16 | 已提交却取消/超时 | TC-C03-001、TC-C05-002、TC-C07-001 |
| E17 | 跨账号/组织访问 | TC-C09-002、TC-C10-001；门店N/A：个人数字人不新增门店上下文 |
| E18 | 撤权/删除/派生清理 | TC-C04-002、TC-C06-001、TC-C09-002、TC-C10-001 |
| E19 | 旧数据/客户端 | TC-C01-001、TC-C03-001、TC-C10-001 |
| E20 | 部分成功/补偿/重放/重启 | TC-C03-001、TC-C04-002、TC-C06-001、TC-C14-001 |
| E21 | 时区/金额精度 | TC-C03-001维持整数分/原价表；历史筛选回归useDigitalHumanHistory.test.ts继续UTC半开90天，V-006；不新建时区/收费逻辑 |
| E22 | 大列表/文件/设备 | TC-C05-001、TC-C09-001、TC-C10-001、TC-C13-001 |

## 13. 阻塞与修订

### 13.1 必须阻塞

B-01当前未解决；不得实施整份草稿。发布后仅在实际触发以下条件时停止受影响卡：真实入口/契约变化无法等价定位；未定义产品/安全/费用/数据行为；需写白名单外文件或新增依赖；真实付费/生产授权缺失；已有改动不能安全合并；最小真实验收链经排查仍缺环境/资源；范围内失败原因无法判定或需越界修复。普通编码错误、工具安装、合法本地准备不构成额外审批。

### 13.2 最小阻塞记录

| 编号 | 类型/影响 | 已确认/已尝试 | 缺少与推荐 | 保留 |
|---|---|---|---|---|
| B-01 | 外部协议事实；阻塞C-06及其fixture/最终集成，整规格不可READY | 已查RenderProvider唯一实现、RenderService、runtime adapters/routes、K14与历史真实门禁；均无已接通远端协议。静态档不能完成真实口型 | 先提供已选供应商及文档位置；若未选，需另行授权选型。推荐满足K14短期资格/按秒可信用量/取消查询与删除的单一供应商；不新增收费单位 | 其余修复/测试方案已写实；无业务修改/运行测试 |

执行中新增阻塞按§14在对话给版本/卡号、具体失败动作、已核验证据、影响卡、最小缺项/推荐及保留资源，不只写“环境问题”。

### 13.3 修订与恢复

规划者补外部合同、W路径/配置注册、TC确定输入与响应，再做附B.2/B.4、更新版本/附C/索引。若供应商不支持K14，不能让编码模型临时改收费/凭据/存储边界；提出影响由用户决定。环境恢复但合同不变可记录证据继续。新编号被其他工作占用、安全可合并diff等按具体变化处理，不回退他人工作。

## 14. 汇报与续作

每卡在对话输出：卡号/状态、实现结果、实际命令、发现/通过/失败/跳过数、证据层级及路径、剩余问题、下一卡。UI列截图绝对路径、浏览器/精确视口/主题与实际查看结论。最终另列原有服务、本次新增/已停止/仍运行与保留原因；不得写“全部测试通过”掩盖未运行或缓存。

默认不新建完成报告、review.md或独立测试总结文件。W99日志/机读结果/截图是必要证据，不属于额外报告；本轮规划只交本书与索引。业务状态文件不在本次白名单。

### 14.1 续作检查点

中断/换模型前在对话保留：本书路径/版本；授权卡范围与模式；HEAD/相关diff；已VERIFIED卡与当前有效证据；当前步骤/未完项；PASS/FAIL/PARTIAL/NOT_RUN/SKIPPED分别清单；最新决策/阻塞；已有改动边界；资源/产物/重建命令；下一张可执行卡和具体动作。接手者核验hash与输出，不盲信上一模型，也不先从头重做。

## 附 A：返工

N/A：本版为规划初稿，没有本任务已实现后的返工结论。执行中偏差回原责任卡；扩大合同或W范围先修订本书，不新增无限范围“修到全绿”卡。

## 附 B：作者发布检查与审阅

### B.1 本轮执行记录

已按模板调查根指令/设计、真实源码与调用链、机器合同、存储/测试/起栈入口/现有diff；依据用户授权固定常规方案；先设计跨层结果与反例，再拆15卡。只新增本书、安全增量更新README。未执行任何业务测试/服务启动；业务验证均NOT_RUN。

### B.2 发布检查（规格审阅，不是测试结果）

| # | 检查 | 本版结论/定位 |
|---|---|---|
| 01 | 用户/价值/范围/成功与恢复流程 | 满足，§1/4 |
| 02 | 无阻塞性未决、唯一方案 | **不满足：B-01**，§1.9/6.6/13 |
| 03 | 当前事实/入口/复用/基线 | 满足，§2；历史结果明确分层 |
| 04 | 真实调用到结果消费 | 已定义§3.1；远端具体外部段仍被B-01阻塞 |
| 05 | 成功/失败/空/加载/幂等/取消/兼容 | 满足已定部分，§4～8 |
| 06 | 唯一字段/文案/文件/命令位置 | 满足已定部分，§5/6/7/8/9.1/12.3 |
| 07 | UI/主题/焦点/分层/硬约束 | 满足，§8/9.2/9.5/C-13 |
| 08 | W覆盖调用/测试/配置/迁移/产物与旧改动 | 已定部分满足，§9.1；B-01协议注册与test_only验收配置所需路径需修订后复查 |
| 09 | 依赖/共享写入/最终联调责任 | 满足，§10/11；C-01先交基础runner，C-13先交UI spec，消除测试依赖环 |
| 10 | 每卡按字面可执行 | **C-06不满足，禁止执行**；其余已按引用和步骤展开 |
| 11 | 边界/高风险用例展开 | 已定部分满足，§12.2/12.7；TC-C06-001外部响应由B-01补齐 |
| 12 | REQ→卡/W→AC→TC→V→证据 | 追踪已建，§12.1；不是本轮验收PASS |
| 13 | mock/真实层与防假阳性 | 满足，§12.2，特别TC-C15 |
| 14 | 环境/清理/命令/结果状态 | 满足计划，§9.3/12.3；业务全NOT_RUN |
| 15 | 阻塞/恢复边界 | 满足，§13 |
| 16 | 初稿NOT_STARTED与卡/集成/发布分离 | 满足，头部/§0.3/10/12.5/12.6 |
| 17 | 依赖/迁移/费用/敏感信息 | 满足已定范围，§7/9；无收费变化/新锁依赖，远端仍B-01 |
| 18 | 汇报/重建/无默认报告 | 满足，§14/W85；不依赖旧本机探针 |
| 19 | 附C填实/草稿不可执行/无附D | 满足，附C |
| 20 | 无空表/候选菜单/未填模板占位 | 已裁剪；唯一缺失事实明确列B-01，不能因此宣称READY |

**裁决：BLOCKED_DRAFT。** B-01关闭并补齐C-06的真实外部合同、精确注册/配置/fixture后，重新逐项审阅；不可只改状态字段。

本轮文档静态核验：15张卡、118个W、15个AC、20组TC、18条V的定义及引用已检查；现存修改路径均存在、新建路径明确标注，文档本地链接可达。README插入本任务行后逐字移除该行可还原插入前全文，原索引内容已保留；限定两文件的git diff --check通过。上述仅为文档检查，不是业务验收。

### B.3 版本记录

| 版本 | 日期 | 原因/责任 | 影响/检查 |
|---|---|---|---|
| 1.0.0 | 2026-10-02 | Codex依据16项复审与当前源码编制；用户授权常规规划决策 | 全部；B.2/B.4已执行，因B-01不发布READY；实施NOT_STARTED |

### B.4 按字面执行与反向审阅

已从C-01走至C-15，并逐条REQ反查：
- C-01先有真实Java wire再前端解码；新增greeting恢复字段精确定义，不让页面刷新绕过每场一次；轮次持久marker在C-04原子实现。
- C-02不用测试人工共享app内存替镜像入口；mTLS/共享registry/双listener退出都需可观察证据。
- C-03～05拒绝“只有prepare/202/STT即完成”，包含冻结快照、费用、不落正文、完成事件、媒体消费者和重启；音频边界含WAV头。
- C-06检查到供应商协议事实缺失，无法为其外部method/frame/usage声称冻结；这直接维持草稿状态，不能交弱模型猜实现。
- C-07～12反查了丢稿、错误被隐藏、没有恢复入口、同ID草稿覆盖、保存设置错误、空页抹字幕、旧response复活和历史只有计数等反例；已有按钮/API定义不等于真实接线。
- C-13不以表面加样式绕过分支/分层；C-14不能晚于其依赖验证工具才首次造基础runner，已把基础phase前置C-01、UI spec前置C-13；C-15禁止fixture冒商业证据和信令冒媒体。
- 共享W按唯一顺序、所有新增文件有调用方/测试发现路径；迁移不删旧数据；未执行业务测试不写PASS；README只增本任务，已有改动保留。

## 附 C：随任务书交给编码模型的执行提示词

```text
任务书路径：/Users/LXH/claude/y-1/docs/任务书/草场任务书-105-fix-3-reviews.md
执行版本：1.0.0
规格状态：BLOCKED_DRAFT（不可执行；须先解决B-01，由规划者补齐协议并重新发布READY_FOR_IMPLEMENTATION）
待派发任务卡：C-01 → C-02 → C-03 → C-04 → C-05 → C-06 → C-07 → C-08 → C-09 → C-10 → C-11 → C-12 → C-13 → C-14 → C-15
执行模式：AUTO_CHAIN
续作检查点：无
授权依据：以用户实际派发指令为准；生成任务书和本提示词不构成实施授权，用户限定的范围优先。

当前版本必须停在规格检查，不开始业务实现。B-01解除并取得新READY版本后：
1. 遵守适用指令与AGENTS.md，按阅读协议读§0、§1、§9、§10、§13、§14、当前卡及精确引用；UI另读根DESIGN.md。
2. 核对新版本/授权卡/代码基线/已有diff与前置证据，保留#105-fix-2、#107-fix-3等在途修改；不要求清洁工作区。
3. 按已定契约及W白名单实施，不重新定义产品或供应商协议；常规代码/环境问题自行处理，实质缺口按§13。
4. 真实执行TC/V与必要回归，分清单元、协议、UI专项、本地E2E和商业/实机证据。单栈、最小真实依赖、重型串行。
5. 卡级§0.3通过后按§14在对话报告并继续已授权下一卡，不默认新建报告；必需日志/截图保留W99。
6. 全部卡后完成§12.5；未满足不得宣称整任务完成。中断按§14.1交接，清理本次资源并说明仍运行项。
```
