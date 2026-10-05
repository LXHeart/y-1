# 开发任务书：107-fix-3 · Hypit 核心链路修复、验收补全与证据收口

> 模板版本：3.1.1 ｜ 任务编号：107-fix-3 ｜ 任务书版本：1.1.0 ｜ 更新：2026-10-02
> 规划负责人：主程（Codex）｜ 执行/验收负责人：后续获派发的编码模型
> 目标仓库：`/Users/LXH/claude/y-1` ｜ 分支：`main`（出书基线；实施前复核，不自动新建）
> 代码基线：`37d2b1569b0c2fdf9df2e82fa205355e235de5d0`＋§2.7 在途改动
> 规格状态：READY_FOR_IMPLEMENTATION ｜ 实施状态：IN_PROGRESS
> 任务卡：13 张；原 C107F3-01～06 编号保留，新增 07～13；起始卡 C107F3-13
> 执行顺序：13 → 01 → 02 → 03 → 04 → 07 → 08 → 09 → 10 → 11 → 12 → 05 → 06（均为 C107F3 前缀，见 §10）
> 执行模式：AUTO_CHAIN（单执行者、重型任务串行）；交付终点：必要回归及本地集成验收完成；LIVE/生产不在本轮实施授权内
> 决策依据：用户要求结合已核实问题完善既有任务书，并授权范围内技术实现、拆分和验收裁量；不新增收费规则、角色、数据可见范围或产品入口。

**阅读协议**：实施者先读根 AGENTS.md、本书 §0/§1/§9/§10/§13/§14，再读当前卡及其精确引用；UI 卡另读根 DESIGN.md。规格就绪不等于授权开工，以用户后续派发范围为准。卡级通过后自动推进授权范围内下一卡；最后必须完成 §12.5；中断按 §14.1 交接。历史文档和本机审计产物只是定位线索，本书已写实的目标契约与当前源码是执行依据。

**单一事实源**：场景在 §1.7、状态在 §4、业务约束在 §5、传输/跨模块类型在 §6、持久化在 §7、UI 文案在 §8、文件权限在 §9.1、AC 在 §11、TC 在 §12.2、命令在 §12.3；其他章节只维护映射。W 与原六卡编号稳定；原多文件 W05/W06 拆出新编号，旧号保留第一文件。

## 0. 执行协议

### 0.1 词义

MUST/MUST NOT 为必须/禁止；SHOULD 为除非有记录依据否则执行；MAY 为可选；BLOCKED 为存在实际阻塞；N/A 必须说明不适用原因；NOT_RUN 是未执行，不能当作通过。历史证据指已存在的 fix2、audit 目录及旧日志，只读；本书新证据使用 §9.3 的独立 run 目录。

### 0.2 强制规则

1. 本轮出书只改本任务书和 README 的本条索引，不实施以下计划。后续实施只写当前卡与 §9.1 的交集；业务规则、接口、安全边界变更必须先写入唯一契约。
2. 保留 105-fix-2 在途工作，不修改其 Java/TS/契约/脚本/任务书；README 只改 107-fix-3 行。其他共享文件先看增量 diff，不能覆盖或 reset/clean。
3. 遵守 AGENTS.md 单栈、最小真实依赖、重型串行；通过既有 stack 守卫启停/构建；严禁换端口或项目名另起第二套应用栈。普通 stop 保数据；fresh 会话才可精确 reset 本次资源。
4. 不增加依赖，不升级 Node/Java/框架，不修改冻结 upstream。Java 权限/版本/计费为权威，Node broker 继续只承担已批准的原生执行边界。
5. 真实 Provider 调用沿既有执行入口及预算/并发/ai_run/用量机制；禁止浏览器传凭据/目标地址、私有资源公开化、绕过计费或把模型调用改成固定成功。
6. 不改旧证据数字，不以补注释替代执行，不把失败登记当验收通过；不得 skip、过滤为零、放宽断言、降低覆盖阈值。当前源码已修复的步骤凭本次复验保留，不为了“先红后绿”人为制造业务缺陷。
7. I/O/解码/FFmpeg 不阻塞 WebFlux event loop；使用响应式数据流或受控 boundedElastic，禁止业务链 .block()/手动 subscribe。失败保留输入，按幂等键重试，不把 HTTP 202 当终态。
8. 文档与源码漂移先辨别：行号、私有变量等价变化自行继续；超出既定范围的收费/权限/可见性/核心流程决策按 §13 处理，不重复询问已有授权。

### 0.3 完成定义（DoD）

当前卡 AC、TC、V 实际通过；受影响回归通过；输入/权限/重试/恢复/清理可验证；UI 查看明暗及移动截图；新增模块被真实调用；W 范围核对无越界；证据可重建。IMPLEMENTED 只代表代码落地，VERIFIED 必须有本次证据。各卡通过不代替 C107F3-06 的本地集成出口。

### 0.4 每卡开始前检查

核对版本和授权范围→记录 HEAD/diff/在途文件→读当前符号及依赖交付→核实命令是否自起容器/子进程→检查资源占用与本卡白名单→执行当前卡要求的基线。历史失败不是当前失败；未执行标 NOT_RUN。基线有其他会话变化但本卡可安全合并时继续，不能要求整个工作区干净。

## 1. 产品需求、目标与范围

### 1.1 一句话目标

修复 Hypit 参考视频分析、方案上下文和归档权限缺口，补足 Studio/移植与门禁验收，使“本地交付完成”的声明有真实、当前且可反证的证据。

### 1.2 背景与价值

原 1.0.0 以“39/40 卡成立、主要补测/修文档”为出发点；本次审计另复现四个服务边界缺陷，源码还确认重新生成方案缺少上下文。保留现有引擎、工程、渲染、恢复实现，集中修边界，不将 3 秒固定测试动画作为真实视频理解/复刻质量的证明。

### 1.3 范围内（均为必须交付）

| REQ | 用户/触发 | 可观察结果 | 责任卡 | AC |
|---|---|---|---|---|
| REQ-001 | 创作者重试/取消变体 | 真实 HTTP/Edge 路由到服务端，状态与 DB 一致 | C107F3-01 | AC-001 |
| REQ-002 | 维护者进入备份窗口 | 新副作用被拒，原有取消/状态收敛可用 | C107F3-02 | AC-002 |
| REQ-003 | 执行者运行 engine 验收 | runner 四项有当前结果，全组被门禁实际执行，失败非零 | C107F3-03 | AC-003 |
| REQ-004 | 审计者核对测试发现数 | C32 四个 TC 独立发现；studio/media/video-clone 实数可查 | C107F3-04 | AC-004 |
| REQ-005 | 审计者核对历史声明 | 原文档路径/数字/W 归属如实，历史与新证据分开 | C107F3-05 | AC-005 |
| REQ-006 | 开发者最终交接 | 全部必需层通过，索引/状态/版本一致，完整闭环可重建 | C107F3-06 | AC-006 |
| REQ-007 | 用户分析有声/无声视频 | 真实时长、音轨、逐词转写、时间锚点正确传递 | C107F3-07 | AC-007 |
| REQ-008 | 用户分析画面 | 模型实际收到可解码帧；缺帧/读取失败不伪造分析成功 | C107F3-08 | AC-008 |
| REQ-009 | Agent 归档输出 | 仅允许当前本人有效工程的输出；外工程与未知 ID 同口径拒绝 | C107F3-09 | AC-009 |
| REQ-010 | 用户按最新分析重生成 | 服务端冻结当前工程可信分析/素材，作者与方案都消费同一上下文；刷新/重启可恢复 | C107F3-10 | AC-010 |
| REQ-011 | 用户编辑/移植作品 | Studio 保存后新版本可读；B 导入后可再次编辑并生成可解码成片 | C107F3-11 | AC-011 |
| REQ-012 | 维护者调用 LIVE 检查 | 空目录/未验证/失败非零；连通性不冒充模型效果与付费验收 | C107F3-12 | AC-012 |
| REQ-013 | 执行者运行验收 | 单栈守卫、独立 run 证据、精确发现/skip/失败解析与故障注入门禁 | C107F3-13 | AC-013 |

### 1.4 范围外与已知不处理项

不迁移到 video-clone-lite、不升级上游、不新增模型渠道或收费/退款政策、不重设计工作区、不扩大角色权限、不公开私有媒体、不部署生产。LIVE 商业效果、真实 ASR 模型效果与生产部署独立 NOT_RUN，不妨碍本地明确替身边界的验收。数字人 105-fix-2 全部在途工作不属本书。全仓未索引历史文档只记录基线差异，不做无关清理。

原稿“零生产代码变更”“不需要 UI/本地 E2E”“runner 红有档视为通过”全部撤销。C10 旧截图声明仍按事实修正；本次触碰页面另做 §8 新截图，不能借历史缺图豁免新验收。

### 1.6 用户、入口与限制

创作者使用既有 AI 端 `/video-clone/:projectId`；维护者使用既有验收脚本。登录、owner、operator、生成费用和授权仍沿当前规则。不能承诺六张采样帧等于观察了每一帧；分析必须区分观察、推断和未覆盖区间。

### 1.7 场景与闭环

| 场景 | 动机/触发 | 流程 | 最终结果 |
|---|---|---|---|
| SC-01 | 审计者纠正完成声明 | §4.1、§12.5 | 可重建的新证据与分层结论 |
| SC-02 | 创作者提供视频并重新生成 | §4.2 | 来源一致的分析、方案、工程版本、成片 |
| SC-03 | 创作者编辑或把工程交给另一账号 | §4.3 | 新 revision 持久；B 在自己的工程继续编辑渲染 |
| SC-04 | Agent/维护者触发受限动作 | §5 RULE-004/011/014 | 越权、维护、无证据场景拒绝，无禁止副作用 |

### 1.8 成功标准

REQ-001～013 均有 §11 AC、§12.2 TC 和 §12.3 V；关键字段/归属反例应在旧行为下失败；真实原生视频可解码且内容随受控输入变化；Studio/移植必须重读版本与文件，不只看 body/标题。§12.5 G1～G7 全部 PASS 才允许本地 VERIFIED，LIVE/生产另列 NOT_RUN。

### 1.9 决策权限与阻塞

技术方案由本轮用户授权规划者决定；修复 owner 校验是恢复既有隔离规则，不新增角色或可见性。采用现有 author 操作补可信上下文，不改变生成前授权流程。新增可选请求字段仅承载该意图，旧客户端兼容。无待用户决定的产品选项。运行环境不足按 §13 阻塞实际验收，不将其预先写成已完成。若根因要求改变 runner 隔离架构或收费/数据规则，必须另行修订，不能给执行者留“任选方案”。

## 2. 仓库上下文（FACT；本轮仅只读核验）

### 2.1 目标端

涉及 AI 创作端、Java intelligence-service、已有 Node broker、测试/验收与文档；无治理台 UI 改动。Node 不是新增公共业务后端。

### 2.2 设计规范路由

已读根 AGENTS.md、根 DESIGN.md 与 src/ops/DESIGN.md。实际 UI 变更只适用根 DESIGN.md；复用 `ClonePlanPanel`、`StudioPanel`、`ProjectPackageDialog`、既有按钮/提示及全局 token，不新增风格。若后续触及治理台必须先修订本书范围。

### 2.3 入口与调用链

- 页面：W24 的 `startRegenerate` → W25 `regeneratePlan` → 既有 POST agent-jobs → W17 `createAgentJob` → W18 `create` → W19 worker → W20 planner/工具。
- 分析：W13 `analyzeReference/synthesizeSegments` → broker `media.probe/media.frames/speech.transcribe` → W21 `analyze` → W19 `runReferenceAnalysis` → W22 `deriveAndSave`。
- 归档：W16 `dispatch/output.archive` → 既有 HypitArchiveService；当前内部归档没有调用者身份参数，调用边界必须补 owner 验证。
- Studio：iframe → W33 proxy → W34 mutation bridge → W36 Java session writeback → W37 changeset → broker workspace.apply；保存后 watchSource 重载。
- 变体：W17 retryVariant/cancelVariant → HypitVariantService；Edge application.yml 已有对应 POST 与 flag，需真实穿透证明。

### 2.4 复用与定位

精确路径唯一在 §9.1。W14 为新增取证服务，复用 W15 内部资源字节读取、ChatMessage.user(List)、ContentPart.image 和 FrozenTextExecutionService.executeIndependentPrepared；W23 为新增可信上下文服务，复用现有 Job/Command/Asset/Project 仓储。W52/W53 为新验收编排与解析模块，复用 local-stack 和既有 ci-e2e-107。既有公开响应均使用 HypitDtos，不另造信封。

### 2.5 已核实行为

1. probe 的真实结果是 `result.probe.duration/hasAudio`，W13 却读取顶层 durationSeconds/hasAudio；speech 返回 passages.words，W13 只读取 text。dispatcher 不做扁平化适配。
2. W13 将图片 handle 序列化为纯文本消息，没有图像 parts；平台已有多模态消息类型与内部字节读取能力可复用。
3. W25 重生成提交 author、空 assetIds、固定 brief；新 checkpoint 不继承旧分析；W19 只在 analyze 分支派生持久方案。
4. W16 output.archive 直接按 outputId 调用内部服务，未校验输出所在工程；相邻 build.status 已做 projectId 过滤。
5. 历史 journey Studio 用例只打开/重开；移植用例止于 B 工程 ready；固定模型 fixture 返回固定分段/3 秒动画，不校验实际视觉输入。
6. W46 LIVE 脚本对空 providers、UNVERIFIED 和 LIVE_FAIL 未汇总非零退出，HTTP200 可被误称 LIVE_PASS。
7. W01 当前三个 @Test 未覆盖 TC-F2-26-02；W07 的 TC-F2-32-04 为内联断言；engine 组未被 C32 card_registry 完整登记。
8. 当前 runner fixture 已启动真实 daemon、使用短 socket 路径和 waitIdle；不能照搬旧日志结论“没有 daemon”。维护豁免清单当前仅含 cancel/close/revoke/status，没有 plan。旧 engine 日志失败必须复验，不预设当前生产栅栏坏了。

### 2.6 问题映射

| 线索 | 本轮定性 | 责任 |
|---|---|---|
| 原稿 C26/maintenance/runner/机读/文档清单 | 路由/登记缺口静态确认；历史 runtime 红只作基线线索 | C107F3-01～06 |
| 审计 A01 | 工具调度边界越权反例已复现，非全栈视频泄漏结论 | C107F3-09 |
| 审计 A02/A04 | probe/转写契约反例已复现 | C107F3-07 |
| 审计 A03 | 实际模型消息无图片 parts，反例已复现 | C107F3-08 |
| 审计 A05 | 重新生成缺上下文，完整静态调用链确认 | C107F3-10 |
| 审计 A06 | Studio/移植验收缺口，不先断言功能必坏 | C107F3-11 |
| 审计 A07 | 隔离脚本空执行/UNVERIFIED 退出0已复现 | C107F3-12 |
| 本轮脚本复核 | e2e 汇总硬写 skipped=0，缺乏逐引擎完整发现校验 | C107F3-13 |

### 2.7 基线与来源核验

- HEAD 如头部。正在修改的 105-fix-2 文件覆盖 digitalhuman Java、src/views/digital-human、contracts/digital-human、IntelligenceItSupport、application.yml、scripts/ci-e2e.sh、README；原 108 书已删除并由 105-fix-2 书接替；另有相应未跟踪测试/脚本。全部保留，前四类文件本书无写权限；共用支撑与 ci-e2e 只读消费。
- 本任务书在本轮开始时为未跟踪 1.0.0，README 已有其索引。只更新该行，不重复新增，不恢复已删除的108文件。
- 前一审计轮的 1787 路径校验、前端/Node 类型检查、148通过/13跳过、Java15通过/4反例失败为 HISTORICAL；目录 `test-artifacts/task-107/audit-2026-10-02/` 只读，不是本书实施验收。
- 当前规划只运行文档只读校验：docs:links exit1（72 unindexed，errors=0）；docs:status exit0。业务、Node、Java、E2E、LIVE 均 NOT_RUN；不会为出书起服务或调用模型。
- package.json/Vitest 3.2.7/Playwright 1.59.1、backend run-tests.mjs 单并发与 Node24.14.1 用法、Java JDK25/Gradle wrapper、真实 PostgreSQL IT 支撑已核。固定 upstream0.2.16 不变。

### 2.8 事实、决策与测试数据

§2 是当前核验/注明来源的历史事实；§3～§8 是本版目标决定；§9.3 合成 fixture 是实施验收输入，不是线上事实。原“39/40成立”“70个未索引”“209处都需替换”不再作为当前事实或完成阈值。

### 2.9 兼容影响

现有路由/flag/信封/owner边界不变；agent 请求新增可选布尔字段，旧缺省行为保留。Job checkpoint 增量 JSON，不做 DDL；仅新模式要求可信快照。图片只内存传递，不新增公开链接/持久副本。共享模型/计费底座只读，失败不直接改 finance 流水。UI 路由/历史版本/既有生成授权保持。

## 3. 技术决策（DECISION）

| D | 唯一方案与依据 | 放弃的做法 |
|---|---|---|
| D-01 | C26 用现有真实 HTTP+PG IT 补动作/拒绝，C06 在同一栈补 Edge 穿透；两层各自声明 | 单靠配置/HTTP非404认定穿透成功 |
| D-02 | 维护协议不变；先复验当前代码；仅在已登记现有栅栏/测试生命周期内修复，必须完整绿色 | 根据旧日志无条件修改生产协议 |
| D-03 | runner 沿现有 daemon/client 架构修 fixture/就绪与清理；全 engine 登记且零必需失败 | known-failure/不登记作为完成出口 |
| D-04 | 历史 fix2 v1.0.0 证据字节保留；本书1.1.0新运行带自身版本/源码摘要 | 为适配版本改写历史 JSON |
| D-05 | 证据短名按“原精确路径存在→保留；全名存在→修引用；皆无→缺证据”处理 | 盲替换目录或把皆无列 justified 后继续认定通过 |
| D-06 | 历史截图/数字按实物改注记，本次UI另留 §8 证据 | 用旧截图替代新页面验收 |
| D-07 | 108→105-fix-2引用只安全增量更新，保持“他人工作及索引存在”的断言意图 | 复活108或覆盖数字人索引 |
| D-08 | 提取 W14 统一取证/归一化；W13仍管owner及业务流；broker wire形状保持 | 为迎合Java错误改Node现有返回格式 |
| D-09 | 内部认证读取帧、限制字节、内存转 data URI，通过现有多模态消息执行入口 | 将私有帧公开或把handle文本当图片 |
| D-10 | 当前 author 请求增加可选“从最新分析重生成”标志；服务端解析并冻结owner内上下文，先重放再取新快照 | 新增产品入口、浏览器自报分析事实、每次重试重新选最新分析 |
| D-11 | 在Agent工具边界验证output→build→project→owner，再复用内部归档；原内部自动归档保持 | 改全局归档owner或只隐藏按钮 |
| D-12 | LIVE脚本严格区分探测与真实验收，无真实成片/账务证据不得LIVE_PASS；本书不新增付费执行 | 虚构 /providers/.../probe 为已存在产品端点 |
| D-13 | 新总入口复用守卫/现有分层脚本，独立run目录，真实解析JUnit/TAP，逐引擎校验 | 字符串PASS、skipped=0常量、旧结果重贴 |

### 3.1 接线与责任

| 链路 | 输入→实现→消费 | 卡/证据 |
|---|---|---|
| 视频分析 | owner素材→W13/W14→真实broker探针/帧/转写→多模态执行→W21结果→W19方案 | C07/C08；TC-F3-07-01、TC-F3-08-01/02 |
| 再生成 | W24/W25标志→W17/W18→W23快照→W19/W20作者→changeset→方案持久→终态刷新 | C10；TC-F3-10-01～03 |
| 归档 | W16可信ToolCall→输出/构建/工程查询→内部archive→现状返回 | C09；TC-F3-09-01/02 |
| Studio/移植 | 真实iframe/上传动作→既有桥/Java版本→重开重读→真实render/download | C11；TC-F3-11-01/02 |
| 门禁 | W52入口→W03/Node/Gradle/ci-e2e→W53报告解析→W09/W48拒绝缺证据→C06出口 | C13/C06；TC-F3-13-01/02、TC-F3-06-03 |

## 4. 目标行为

### 4.1 SC-01 验收流程

先 C13 固定输出/发现规则；按 §10 完成各卡；本次结果归新run；C05纠正历史声明；C06串行运行必要回归、本地真实闭环和故障注入门禁。任一必需失败不得重新标全部VERIFIED。

### 4.2 SC-02 分析与再生成

登录本人ready工程→上传/选参考→owner校验→probe确认时长/音轨→帧/转写取证→实际图像parts进入模型→结构化观察/缺口持久→显示可用方案。无模型/素材错误进入既有waiting_input或错误态，不能填空成功。

点击现有“重新生成方案”→提交 §6 API-002 新标志→服务端确定当前工程最近可信分析和来源快照→202显示处理中→作者输入明确包含快照→validated changeset写回→重生成的clone.plan与新revision关联→服务端终态后前端重读方案/工程。期间换页/换号不能让旧回调改新工程；重试未知结果复用请求ID；取消只请求服务器，不自行宣告完成。

### 4.3 SC-03 编辑与移植

在Studio真实控件改内容→保存→收到新revision/hash→刷新/关闭重开仍见新值→按旧会话冲突测试拒绝覆盖。A导出工程包→B登录经UI上传→进入B的新工程→Studio修改→保存→通过既有生成授权路径本地render→下载并解码，A源工程/历史成片字节不变。

### 4.4 状态与迁移

| 对象/原状态 | 事件/条件 | 目标状态 | 持久/副作用 |
|---|---|---|---|
| agent queued/running | 必需证据/环境缺失 | state=running，checkpoint.phase=waiting_input | 留已取证与原因，无伪造SUCCEEDED |
| analysis | 证据完整但有时间缺口 | PROVISIONAL | 明示gap，禁止可执行方案 |
| agent running | 作者/方案/版本都落定 | succeeded | terminal事件后UI重读 |
| agent running | CAS/素材哈希改变 | state=running，checkpoint.phase=waiting_input | 保留旧head/输入；显式重试新快照 |
| agent running | 取消确认 | cancelled | 保留已有已提交收据，不新增生成 |
| UI submitting | 202受理 | 观察既有job | 不显示完成、不立即刷新为旧方案 |
| UI 任意 | 工程/账号切换/卸载 | 清理本地观察 | abort请求、generation gate，服务端job不因断观察自动取消 |

没有新增公开生命周期状态。`waiting_input`是作业checkpoint.phase，持久job.state仍为running；本书其他“作业进入waiting_input”均按此解释。业务分析大写状态、作业小写state、checkpoint.phase与UI本地phase分开，禁止往job.state写waiting_input。

## 5. 业务规则（唯一语义）

### 5.1 输入与约束

| RULE | 固定行为 |
|---|---|
| RULE-001 | 文档路径修复按D-05；精确到文件，不以父目录存在证明文件存在 |
| RULE-002 | 新数字来自本次报告；历史数字只能写历史；不得把日志出现TC字符串视为已执行 |
| RULE-003 | 每条当前通过声明绑定runId、源码摘要、TC/退出码与产物哈希；缺项不能VERIFIED |
| RULE-004 | Agent归档先校验有效当前工程owner，再output/build归属；外工程、未知、已删除同404，不泄漏名称/mediaId；归档调用数为0 |
| RULE-005 | 视频probe必须hasVideo=true、duration为有限正秒数，hasAudio为布尔；缺字段/0/负/NaN不能默认无声成功；按未就绪处理 |
| RULE-006 | 固定六个中点采样 t=duration*(2i+1)/12，i=0..5，时间以真实回执为准；缺帧/重复错位/范围越界不补造证据；覆盖结论必须有真实观察与gap |
| RULE-007 | 转写使用16kHz样本索引，startSample/endSampleExclusive除16000得到秒；单词按既有顺序保留；空passages但成功证据有效表示测过无语音，不等于转写失败；hasAudio=false才零转写调用 |
| RULE-008 | 仅消费本次已授权素材取证命令返回的帧句柄；支持image/png、image/jpeg；每帧≤4MiB、合计≤24MiB，最多6帧；逐帧读取/释放DataBuffer，超限或不可解码在模型调用前失败 |
| RULE-009 | 模型请求必须有真实图片parts+对应时间/哈希文本；日志只记计数、类型、sha256、字节数、runId，不记录data URI、token、整段私有转写 |
| RULE-010 | 新再生成模式由服务端选本工程本人最近analysis job引用的完整分析；来源asset仍ready且sha匹配、analysis=SUCCEEDED、baseRevision=head；否则不建新job/不调模型 |
| RULE-011 | 同owner/action/requestId＋同客户端请求重放同job/冻结快照；异请求409；不要把随时间变化的“latest”重新纳入旧请求幂等判定；并发CAS禁止覆盖别人的head |
| RULE-012 | 新模式先冻结快照，再调用既有作者/工具；plan持久采用稳定UUID.nameUUIDFromBytes((jobId+":clone-plan").getBytes(StandardCharsets.UTF_8))，重试不得增重复行；作者成功但plan未持久不能终态成功 |
| RULE-013 | 收费规则不改：全部模型调用走已有平台执行/用量/并发通道；build仍经check/plan/pricing/grant；错误不手工操作finance，取消不等于自动退款 |
| RULE-014 | 维护窗口只允许既有豁免清单；拒绝新commands/resources无新执行副作用；持有者leaseId才可退出，错误leaseId不能解除 |
| RULE-015 | 统计以真实JUnit/TAP执行记录为准；必需TC为0、缺失、skip/todo、失败、非法报告均非零；任何历史文件不得充本次结果 |
| RULE-016 | LIVE连接性最多PROBE_PASS；无授权/预算/目录/实现/结果证据为NOT_ENABLED/UNVERIFIED并非零；不得把预算参数仅打印后宣称执行预算已受控 |

### 5.2 校验顺序

浏览器输入形状→真实身份/owner→requestId重放/冲突→工程/素材/版本校验→可信上下文→预算与执行准备→副作用。上游回执需校验命令成功及形状；不要因response200就认为工具成功。违反RULE-004/010时，模型、归档、job/command新行都为0；既有合法幂等重放除外。

### 5.3 成功、失败与恢复

取证失败保留原素材、历史分析/方案；模型输出不能解析/没有观察不能补造证据；重试按同一业务operationId找已有工具收据。新模式worker重启只消费checkpoint快照，不能临时读取更晚分析。CAS失败保留冲突输入，不读当前hash强行覆盖。新再生成不自动新增视频付费调用。

### 5.4 权限与不变量

沿用本人有效工程owner、operator和内部认证规则，无新权限。匿名401；跨工程/未知404；featureflag关闭仍fail-closed。不改变源工程、历史revision/已归档MP4，不给客户端内部broker凭据。数据删除/撤权时依照既有job停止、资源生命周期清理；本次临时图像不建立额外持久资源。

## 6. 接口契约

### 6.1 API-001：现有变体retry/cancel（保持）

POST `/api/hypit/projects/{projectId}/variants/{id}/retry`，owner，成功200 `{success:true,data:{id,state,attempt}}`；仅failed/cancelled按现有规则retry，竞争409 `hypit_state_conflict`。POST同路径末段`cancel`，JSON `{requestId:UUID,reason?:string}`，成功200 `{success:true,data:{id,state}}`。归属未知404 `hypit_not_found`。Edge既有POST/flag/上游不改；身份使用登录Cookie→Edge签名断言，不能前端伪造。

### 6.2 API-002：agent-jobs（向后兼容增量）

现有 POST `/api/hypit/projects/{projectId}/agent-jobs`，202，HypitDtos成功信封和既有jobId/state响应保持。W17新增DTO字段、W18接收，旧create签名保留委托重载供已有调用方使用。

```java
// W17 内目标 record；java.util.UUID/List/Map
public record AgentJobRequest(UUID requestId, String intent, String brief,
        List<UUID> assetIds, Long baseRevision, Map<String, Object> scope,
        Boolean regenerateFromLatestAnalysis) {}
```

缺省/null/false走旧路径；true只用于intent=author，客户端assetIds必须缺省或空，baseRevision必填且与head相等。true的完整合成请求：

```json
{"requestId":"eeeeeeee-1073-4000-8000-000000000010","intent":"author","brief":"按最新分析与素材重新生成方案","assetIds":[],"baseRevision":2,"regenerateFromLatestAnalysis":true}
```

字段业务约束见RULE-010～013。后台不从brief猜是否新模式。W25生成请求ID并在网络不确定重试时复用；用户明确开启下一次生成才换ID。查询/事件/取消继续用既有job端点，未新增路由和Edgeflag。

### 6.3 MOD-001：取证服务（W14新增跨模块类型）

```java
// package com.grassland.intelligence.hypit.asset；java.util.List/UUID；reactor.core.publisher.Mono；ContentPart
public abstract class HypitReferenceEvidenceService {
    record Word(String text, Long startSample, Long endSampleExclusive) {}
    record Frame(String handle, double timestampSeconds) {}
    record Evidence(double durationSeconds, boolean hasAudio, String language,
            String aspectRatio, boolean transcriptionReady, List<Word> words,
            List<Frame> frames, String transcriptEvidenceHandle) {}
    public abstract Mono<Evidence> collect(UUID projectId, String sourceHandle, UUID operationId);
    public abstract Mono<List<ContentPart>> modelParts(Evidence evidence);
}
```

以上用抽象签名表示合同；实际W14为可注入的具体服务，实现上述两个public方法和嵌套records，不创建额外接口。Word时间字段允许null以表达缺口，不能拆箱成0。collect只调用固定sidecar命令；W13在owner与asset查验后调用。modelParts产出文本及图像parts，W13加分析提示通过既有FrozenTextExecutionService执行。初始化/构造注入对应测试同步。

Node wire保持：media.probe→`{probe:{duration,hasVideo,hasAudio,width,height}}`；media.frames→`{frames:[{handle,timestampSeconds}],totalTimes}`；speech.transcribe→`{language,sampleFrames,durationSec,extracted,passages:[{startSample?,endSampleExclusive?,words:[{text,startSample?,endSampleExclusive?,score?}]}],diagnostics,evidenceHandle}`。字段转换见RULE-005～009；缺少词时间锚点不能伪造0，记录缺口，转写未完整。frame宽高由probe得aspectRatio，不读不存在的frames.aspectRatio。

### 6.4 MOD-002：可信再生成上下文（W23新增）

```java
// 嵌套records落在 HypitAuthorContextService；UUID/List/Mono 与现有 HypitReferenceAnalysis 导入
public record ReferenceContext(int version, UUID analysisJobId, String analysisId,
        String mediaHash, List<UUID> assetIds, long baseRevision,
        HypitReferenceAnalysis analysis) {}
// W23具体服务必须公开实现以下方法（此处仅列签名）：
// Mono<ReferenceContext> resolve(String accountId, UUID projectId, long baseRevision)
```

resolve只读本人project的hypit.agent分析任务，按updated_at DESC/id DESC选最近有referenceAnalysisId者；通过该可信job的引用查reference.analyze命令result，复核analysisId/mediaHash/source asset。不能以全库相同sha搜索作为权限证明。W18在命令首次受理事务中冻结；W19将该快照转为结构化观察交W20，作者不能用空观察执行新模式。clonePlans增加稳定requestId的deriveAndSave重载，旧重载保持；新模式用RULE-012的稳定ID。完整JSON存储见§7，不新增public返回内部快照。

跨模块新增公开签名冻结如下（类型均沿现有包；实现分别在W18/W22，不能只写方法未接调用）：

```java
// W18：W17传 Boolean.TRUE.equals(request.regenerateFromLatestAnalysis())；旧create委托false
public Mono<Map<String, Object>> create(String accountId, UUID projectId, UUID requestId,
    String intent, String brief, List<UUID> assetIds, Long baseRevision,
    Map<String, Object> scope, boolean regenerateFromLatestAnalysis);
// W22：W19成功写回后调用；requestId用RULE-012；ctx提供来源和baseRevision
public Mono<HypitClonePlan> deriveAndSave(UUID projectId, UUID requestId,
    HypitAuthorContextService.ReferenceContext ctx, UUID sourceJobId, long resultRevision);
```

W19向W20传现有`plan(String accountId, String intent, String brief, HypitAgentScope scope, int maxSteps, List<UUID> assetIds, List<Map<String,Object>> observations, int round)`签名；首项观察为`{kind:"reference_context", referenceContext:快照结构}`，后接既有动作观察，round仍按真实动作轮数。W20识别该kind并用“参考分析上下文”标签呈现，不称作已执行动作，保留其他观察语义及旧签名。W25的regeneratePlan输入增加可选`onTerminal?: (job: {state: string}) => void`，仅当前generation的终态触发；W24回调复用原刷新函数，不另建请求服务。

### 6.5 错误与可重试性

| 条件 | 对外结果 | 恢复 |
|---|---|---|
| 匿名/归属失败 | 401 hypit_unauthenticated / 404 hypit_not_found | 登录/回本人工程，不泄漏对象 |
| 新字段组合不合法 | 400 hypit_invalid_input | 改参数；禁止副作用 |
| 同requestId异请求 | 409 hypit_idempotency_conflict | 保留原job；新动作新ID |
| 新模式缺可信完整分析、来源变化、head冲突 | 409 hypit_state_conflict（前置）或worker既有waiting_input | 显示§8提示，重新分析/刷新后显式重试 |
| broker/帧读/格式/模型不就绪 | 既有分析notReadyReason→waiting_input；同步基础设施故障503 hypit_backend_unavailable | 保留素材/旧结果，不显示内部堆栈 |
| Agent外工程output | DispatchOutcome ok=false，code=hypit_not_found，scopeRefusal=true | worker按安全拒绝终止，不replan猜ID |
| 无响应 | 没有服务端错误码 | 同requestId查询/重试，勿新增收费提交 |

公共错误使用HypitDtos现有 `{success:false,error,code}`；Node内部maintenance响应维持既有HTTP503 `{error:string}`，不套站内信封。

### 6.6 观测与不变量

记录jobId/analysisId/operationId/ai_runId/sourceHash/revision/帧计数及摘要；每个hash可与真实字节验证。对外日志不记录凭据、签名URL、data URI或私有正文。新模块必须由W13/W18/W19真实调用；模型fixture断言进入真实适配器后的请求体，不能只assert构造函数。

### 6.7 异步与媒体边界

既有SSE wire、重连和job actions保持；终态去重，按project/account generation gate忽略旧success/error/finally。图片读取复用内部fixed base URL/token，HTTP200及允许媒体类型/真实字节预算才能用；逐帧流释放和取消。公共Multipart、Range 200/206/416、Studio WS/票据均保持，由§12回归验证，不新增公开资源URL。

## 7. 数据模型与迁移

### 7.1 增量存储

不新增表/列/Flyway。W18为新模式的 `hypit_job.checkpoint_json` 增加 `regenerateFromLatestAnalysis:true` 与 `referenceContext`（MOD-002）；分析result仍在现有hypit_command，方案仍action=clone.plan；新方案result增可选 `sourceAnalysisId/sourceMediaHash/sourceJobId/baseRevision/resultRevision`，均由服务器生成。

### 7.2 字段与序列化

UUID落JSON字符串，秒为有限数，样本索引为非负整数；快照version=1；不可存data URI/帧字节/凭据。最大referenceContext UTF-8 JSON为256KiB；超限在建job前400 hypit_invalid_input，不截断事实。snapshot中的analysis使用已有结构化类型，不仅保存markdown。两张不同工程的相同视频不可互相引用job。

### 7.3 兼容

旧checkpoint无标志按旧流程；旧客户端不传新字段保持。新模式若checkpoint声明true但缺context，worker进入waiting_input，不能回退空author。历史受影响分析不自动改为正确、不批量回填；用户显式重新分析生成新证据。回滚旧应用前先停止接收并排空新模式job；不可在旧worker上执行含新模式的未完成job。

### 7.4 事务、并发与生命周期

创建先owner/合法性与已有命令重放，再冻结快照，command+job同既有事务；同key唯一约束兜底。worker仍使用现有lease/fencing更新；新逻辑不得引入无fencing写checkpoint。作者validated changeset遵守baseRevision/hash CAS；plan落档以稳定requestId幂等，部分完成重启读收据而非重写文件。图片内存随完成/错误/取消释放；不新增持久派生物/事件类型，无新资源registry项。仍跑既有生命周期门禁确认。

### 7.5 验证

TC-F3-10-02/03覆盖旧job兼容、未知结果重放、并发/worker重启和取消；TC-F3-09-02覆盖归档重放；无DDL故不执行空库升级迁移。真实PG以新事务重读，不能用repository mock证明事务或唯一性。

## 8. UI 实现规格

### 8.1 归属与结构

AI端W24既有四阶段工作区保持，W26方案面板负责展示，W25管理再生成状态；不新增页面。W24当前759行，上限800；只保留装配与短事件转发，新业务进入composable，不能提高豁免。

### 8.2～8.4 交互与文案（唯一来源）

| UI项 | 明确行为 |
|---|---|
| UI-01 再生成 | 既有按钮点击立即禁用相关重复提交，文案“重新生成中…”；保留旧方案并标处理中；202不展示成功 |
| UI-02 终态 | succeeded后重读工程/方案/源码，显示“方案已更新”；failed/waiting_input保留旧结果和可操作原因 |
| UI-03 前置失败 | 无完整分析：“请先完成参考视频分析”；来源变更：“参考素材已变化，请重新分析”；版本冲突：“工程已更新，请刷新后重试” |
| UI-04 生命周期 | 切工程/换号/卸载后停止观察并失效所有旧回调；旧请求成功、失败、finally均不能改新页面；新任务不抢焦点 |
| UI-05 Studio | 保存按钮行为复用现有Studio，成功必须新revision可重读；冲突保留输入，不自动覆盖，重开拿新会话 |
| UI-06 工程包 | 沿现有导出/导入弹窗，B导入ready后进入新工程；失败保留文件/原因，不展示源owner私有链接 |

### 8.5～8.7 视觉、响应式与无障碍

只用根DESIGN定义token与配对主题；改到的既有hex换token，不引字体/组件体系。复用全局gl-field/gl-btn-primary与既有模态。桌面≥1024、平板768～1023、移动390px覆盖；控件触控≥44px；有aria-busy/可访问名称/焦点轮廓，错误紧邻操作；键盘Tab/Enter/Escape、弹窗焦点返回，自动刷新不夺焦点。

### 8.8 截图与实际查看

C10/C11：亮/暗×桌面1440×900/移动390×844各检查方案与Studio入口；至少覆盖加载、空、错、提交中、成功、冲突；错误专项允许精确拦截并注明，不代替成功链。保留截图、视口、场景与观察结论在当前run/ui；对比度/层级/间距、横向溢出、键盘焦点逐项检查，不以截图文件存在作通过。

## 9. 全局约束

### 9.1 文件白名单 / 黑名单

文件路径仅在本表维护；每行一个精确文件。新增文件不存在是DECISION，不伪造现存符号。所有行只对未来获授权实施生效；当前出书写权限仅W10/W11。

| W | 精确路径 | 权限/操作 | 符号/边界 | 责任卡（C107F3） |
|---|---|---|---|---|
| W01 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C26IT.java` | 修改 | 新增TC-F2-26-02真实HTTP；保留原测试 | 01 |
| W02 | `contracts/hypit-coverage.v1.json` | 修改 | fix3登记、受影响卡重开/证据指向；不抹历史 | 05,06 |
| W03 | `scripts/acceptance/verify-107-fix-2.sh` | 修改 | C26/C32登记与FIX2_ART_BASE兼容；受影响卡docker守卫 | 13,01,03 |
| W04 | `platform-hypit/backend/tests/engine/maintenance.test.ts` | 修改 | 真实子进程/维护拒绝/退出清理 | 02 |
| W05 | `platform-hypit/backend/src/server.ts` | 修改 | 仅维护enter/exit/写栅栏及读接口边界 | 02 |
| W06 | `platform-hypit/backend/tests/engine/runner-isolation.test.ts` | 修改 | 真实daemon适配；隔离/超时断言不降级 | 03 |
| W07 | `platform-hypit/backend/tests/agent-integration/fix2-c32.test.ts` | 修改 | 独立具名TC-04；维护原断言 | 04 |
| W08 | `docs/任务书/草场任务书-107-fix-2-Hypit全链路缺陷修复与真实交付验收.md` | 修改 | 受影响状态/证据/W登记/版本注记；1.0.7复核修订 | 05,06 |
| W09 | `tests/deployment/hypit-fix2-spec.contract.test.ts` | 修改 | 新鲜证据/复核状态与105-fix-2引用；拒绝伪完成 | 13,06 |
| W10 | `docs/任务书/README.md` | 修改 | 仅107-fix-3索引行；结项时按实际状态 | 06 |
| W11 | `docs/任务书/草场任务书-107-fix-3-reviews.md` | 修改 | 本书规格修订/卡状态/B记录 | 全部 |
| W12 | `platform-hypit/backend/src/commands/dispatcher.ts` | 修改 | 维护豁免清单/计数；不放宽执行隔离 | 02 |
| W13 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/asset/HypitAssetService.java` | 修改 | analyzeReference/synthesizeSegments调用W14；删无证据补造 | 07,08 |
| W14 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/asset/HypitReferenceEvidenceService.java` | 新建 | MOD-001；collect/modelParts；由W13注入调用 | 07,08 |
| W15 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/asset/HypitResourceService.java` | 只读 | open固定内部认证/字节流复用 | 08 |
| W16 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitAgentToolRegistry.java` | 修改 | output.archive owner闸；保持其他工具语义 | 09 |
| W17 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/api/HypitProjectController.java` | 修改 | API-002可选字段/调用重载；旧响应保持 | 10 |
| W18 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitAgentJobService.java` | 修改 | 重放顺序、可信快照与增量checkpoint | 10 |
| W19 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitAgentWorker.java` | 修改 | 新模式消费快照、作者→方案→终态、fencing恢复 | 10 |
| W20 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitAgentStepService.java` | 修改 | 带快照观察的planner输入；保留旧签名委托 | 10 |
| W21 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitReferenceAnalysisService.java` | 修改 | 完整证据校验/结构化字段；不改变公开分析状态 | 07 |
| W22 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitClonePlanService.java` | 修改 | 稳定requestId重载/来源与revision元数据 | 10 |
| W23 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitAuthorContextService.java` | 新建 | MOD-002；本人job引用链取可信分析与素材 | 10 |
| W24 | `src/views/video-clone/VideoCloneWorkbench.vue` | 修改 | 再生成短事件绑定/终态重读，保持≤800行 | 10 |
| W25 | `src/views/video-clone/composables/useHypitWorkflow.ts` | 修改 | 新模式请求、同ID重试、generation gate/终态回调 | 10 |
| W26 | `src/views/video-clone/components/ClonePlanPanel.vue` | 修改 | UI-01～04禁用/状态/提示；不重做布局 | 10 |
| W27 | `src/views/video-clone/composables/fix2-c18.test.ts` | 修改 | 新字段/终态/旧回调/连点测试 | 10 |
| W28 | `src/views/video-clone/components/ClonePlanPanel.test.ts` | 修改 | 对应UI状态及键盘/焦点 | 10 |
| W29 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix3/HypitFix3EvidenceTest.java` | 新建 | probe/words/parts四个审计反例变回归 | 07,08 |
| W30 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix3/HypitFix3EvidenceIT.java` | 新建 | 真HTTP/PG及最后模型HTTP替身的实际消息断言 | 07,08 |
| W31 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix3/HypitFix3ArchiveIT.java` | 新建 | 真PG归属与归档边界、重复调用；模型可替身 | 09 |
| W32 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix3/HypitFix3AuthorContextIT.java` | 新建 | 快照/重试/竞争/恢复/旧checkpoint兼容 | 10 |
| W33 | `platform-hypit/backend/src/studio/proxy.ts` | 条件修改 | C11若反例暴露已有写回路由问题，仅原链修复 | 11 |
| W34 | `platform-hypit/backend/src/studio/mutation-bridge.ts` | 条件修改 | 原saveSource/语义补丁版本传递，不直写绕过Java | 11 |
| W35 | `platform-hypit/backend/src/studio/sessions.ts` | 条件修改 | 保存后revision推进/关闭撤销；不改授权语义 | 11 |
| W36 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/studio/HypitStudioSessionService.java` | 条件修改 | 会话写回owner/baseRevision/hash/receipt | 11 |
| W37 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/project/HypitChangesetService.java` | 条件修改 | 已定义CAS/幂等写回缺陷；不改规则 | 11 |
| W38 | `tests/e2e/hypit-fix2-journey.spec.ts` | 修改 | Studio编辑重开/导入再生成；证据根可覆盖 | 11 |
| W39 | `tests/e2e/hypit-fix3-reference.spec.ts` | 新建 | 主链、多输入、Edge变体穿透、终态/恢复 | 10,11,06 |
| W40 | `tests/e2e/fixtures/hypit-fix2-text-provider.mjs` | 修改 | 新fixture严格输入断言/按输入产结果；旧族保持 | 08,10,11 |
| W41 | `platform-hypit/backend/tests/media/fix3-reference-contract.test.ts` | 新建 | 真实probe/frames字节与speech归一化返回契约 | 07,08 |
| W42 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C16IT.java` | 修改 | 把错误的扁平probe/text替身换成真实wire；原断言保留 | 07,08 |
| W43 | `platform-hypit/backend/tests/engine/runner-daemon.ts` | 修改 | 真实短socket/state/tmp、就绪握手和失败退出清理 | 03 |
| W44 | `platform-hypit/backend/tests/engine/package-build.test.ts` | 修改 | 真实编译fixture适配，保留恶意/失败诊断 | 03 |
| W45 | `platform-hypit/backend/src/runner/daemon.mjs` | 条件修改 | 仅现有socket启动/退出生命周期缺陷，不改隔离架构 | 03 |
| W46 | `scripts/acceptance/verify-107-live.sh` | 修改 | RULE-016参数/目录/探测汇总fail-closed；独立输出 | 12 |
| W47 | `tests/deployment/hypit-fix3-live.contract.test.ts` | 新建 | 隔离副本无网络/本地探测的负向门禁 | 12 |
| W48 | `tests/deployment/hypit-fix3-spec.contract.test.ts` | 新建 | 本书需求/TC/W/状态与结果对应、缺项拒绝；C107F3-07按§13.3增量：§9.1全量清单断言随W72增量行同步71→72（W72为该卡§13.3登记二的既有合法增量、负例同步70→71；上轮V-01 tooling复验run（fix3-20261002T110937Z-5a79）记录17/1未见此红，可反推该复验早于W72行落定，本轮定向实测72≠71后同步），W48其他契约语义不动；C107F3-11按§13.3增量：§9.1全量清单断言随W73/W74增量行同步72→74、负例同步71→73（W73/W74为该卡补丁0005的合法增量，沿用W72同步先例）；并修复该测试「状态-证据一致」用例的静态负例过期性假红（C01 variant/C10 context证据真实存在后固定编号'01'/'10'注入VERIFIED合法通过——上轮tooling run 11:09已实录exit1；改为运行时选取无证据卡+合成卡退化断言，不放宽保护语义），W48其他契约语义不动 | 13,06,07,11 |
| W49 | `scripts/acceptance/stages/107-fix-2-e2e.sh` | 修改 | 解析真实skip/失败/缺引擎/零执行；输出覆盖兼容 | 13 |
| W50 | `scripts/acceptance/stages/107-fix-2-all.sh` | 修改 | 按验证后层结果推Gate；不按exit0粗赋G2～G5 | 13 |
| W51 | `tests/e2e/hypit-fix2-c36.spec.ts` | 修改 | 仅证据根覆盖及新UI文案/状态断言 | 10,13 |
| W52 | `scripts/acceptance/verify-107-fix-3.sh` | 新建 | §12.3固定stage编排，复用守卫与现有入口；C107F3-02按§13.3仅修run_backend_group的TAP落盘接线（node24默认spec reporter致.tap永不存在的实证缺陷，子shell内显式--test-reporter=tap并落.tap，不改组编排/守卫/解析契约）；C107F3-03按§13.3仅修stage_engine的W54传导复核运行方式（`-t 'TC-F3-03'`过滤下其余用例被vitest计为skipped、W053按RULE-015记skipped>0问题必然假红——实证v3.2.7过滤JSON全量status=skipped，改为整文件运行gates契约，不改stage编排/守卫/期望表）；C107F3-04按§13.3仅增域执行TC登记接线——run_backend_group增可选第2参、run_vitest读CONSTITUTES_TC，stage_domain把studio/media/video-clone三域分别经W053 `--constitutes` 登记为TC-F3-04-02/03/04（仅该域报告真实全绿才登记）；media腿增 `--allow-external-blocked-skip`（§12.2 TC-F3-04-03仅要求「当前实数可还原」、本卡要求「实数与skip存为证据」，而media组speech-tools的whisperx真实ASR用例按§1.4本就范围外NOT_RUN、测试自声明EXTERNAL_BLOCKED——仅该类测试自声明的skip记实数不拦门禁，其他任何skip/todo/失败仍非零；studio/video-clone腿与全部其他stage不用此旗标；C107F3-07按§13.3增量：stage_evidence/stage_vision的media腿与stage_domain同语义接入EXTERNAL_SKIP_OK——仅豁免既有speech-tools whisperx真实ASR外部用例自声明的EXTERNAL_BLOCKED skip并如实计实数，其他任何skip/todo/失败仍非零，不改组编排/守卫/解析契约/期望表；C107F3-07按§13.3再增量：run_java的JUnit复制源路径改用点号类名——Gradle test-results为扁平TEST-<fqcn>.xml布局，原实现先把类名.转/再拼源路径恒不存在、复制恒跳过，V-07三类JUnit全绿却报缺JUnit报告的实证缺陷，仅修该复制接线，真缺XML仍按0执行或未编译判非法）；不改组编排/守卫/解析契约/期望表 | 13,06,02,03,04,07 |
| W53 | `scripts/acceptance/hypit-fix3-results.mjs` | 新建 | JUnit/TAP结构解析、预期发现、run身份、hash汇总；C107F3-04按§13.3增量：tap/vitest/junit CLI增可选`--constitutes`（域执行类TC的诚实登记通道——§12.2 TC-F3-04-02/03/04定义为「W52域执行」，其TC证据就是该域原始报告本身，而非某个用例名；仅当本报告无任何问题且子进程退出0时并入found，非绿不登记、emit缺必需TC兜底非零，不影响既有解析/发现/退出码判定）；parseTap捕获skip/todo原因、evaluate/CLI增可选`--allow-external-blocked-skip`（默认关：仅当用例自身以`EXTERNAL_BLOCKED`声明跳过原因且旗标显式开启时，该skip计实数externalBlocked不记问题；非声明skip仍为问题；无旗标调用方行为不变） | 13,04 |
| W54 | `tests/deployment/hypit-fix3-gates.contract.test.ts` | 新建 | 汇总/退出码/子进程失败/信号/证据缺项反向测试；C107F3-03按§13.3追加本卡TC-F3-03-02（§12.2既定位置=本文件「C13创建、此卡复验」）：真实wrapper隔离副本注入仅engine子进程exit1的失败传导反例+C32 engine分派静态接线，不改既有TC-F3-13断言 | 13,03 |
| W55 | `contracts/hypit-api.v1.json` | 修改 | API-002可选标志、MOD取证wire与错误规则登记 | 07,08,10 |
| W56 | `src/types/hypit.ts` | 修改 | 仅新模式与方案可选来源字段类型 | 10 |
| W57 | `src/views/video-clone/composables/hypit-api.ts` | 修改 | 新字段透传和旧响应兼容，不另造fetch通道 | 10 |
| W58 | `docs/status.yaml` | 修改 | 仅Hypit模块本次复核状态，不改数字人 | 05,06 |
| W59 | `docs/草场开发进度与续接指南.md` | 修改 | 仅新增本次Hypit复核/交接条目 | 05,06 |
| W60 | `README.md` | 修改 | 仅Hypit完成声明与本书引用 | 05,06 |
| W61 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/api/HypitStudioController.java` | 条件修改 | 仅既有内部writeback接线缺陷 | 11 |
| W62 | `platform-java/services/intelligence-service/src/main/resources/hypit/prompts/reference-analysis.md` | 修改 | 真实帧/词锚点、观察/推断/gap输出约束 | 08 |
| W63 | `platform-hypit/backend/tests/studio/fix3-writeback.test.ts` | 新建 | 现有桥的revision/CAS/重放与失败保留断言 | 11 |
| W64 | `scripts/acceptance/hypit-fix3-fixtures.mjs` | 新建 | 合成12s三段视频/有声无声样本与sha清单；单进程FFmpeg | 13 |
| W65 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/asset/HypitArchiveService.java` | 只读 | 内部自动归档复用，owner语义保持 | 09 |
| W66 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/IntelligenceItSupport.java` | 只读 | 共用真实PG与身份签名，保留数字人在途改动 | 01,07,08,09,10 |
| W67 | `scripts/ci-e2e.sh` | 只读 | 已接守卫、按显式服务启动、测试扩展点；不改数字人在途内容 | 13,06 |
| W68 | `scripts/lib/local-stack.sh` | 只读 | local_stack_enter复用 | 13 |
| W69 | `scripts/acceptance/ci-e2e-107.sh` | 只读 | 既有Hypit模型fixture/栈启停入口 | 13,06 |
| W71 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/agent/HypitPlannerParseTest.java` | 修改 | StepService旧构造/旧planner输入兼容回归；不删解析反例 | 10 |
| W70 | `platform-hypit/backend/scripts/run-tests.mjs` | 只读 | TEST_GROUP与单并发自动发现新增test | 03,07,08,11,13 |
| W72 | `platform-hypit/backend/src/resources/handles.ts` | 修改 | C107F3-07按§13.3增量：registerResource的index读-改-写按index文件串行化（模块级per-path promise链），消除并发注册丢更新；不改resolveResource/registerResource签名与handle形状 | 07 |
| W73 | `platform-hypit/patches/0005-studio-ui-api-base.patch` | 新建 | C107F3-11按§13.3增量（条件修改——真实交互用例暴露既有链缺陷）：冻结upstream studio UI的全部运行时API/资源URL构造点是根相对字符串字面量（main.ts/i18n.ts/code.ts/writeback.ts/comments.ts/library.ts/artifact-preview.ts/material-preview.ts共13处）；部署拓扑下（nginx仅路由studio/<sid>/、根路径SPA回退）根相对__studio/*永远到不了broker代理面，且main.ts顶层fetch(/__studio/session)在部署态解析到回退HTML使模块求值中止、整棵UI不渲染（fix2只断body存在故未暴露）。补丁0005经既有补丁系统新增ui/studio-api.ts（studioUrl按vite base=HYPIT_STUDIO_BASE_PATH拼接前缀，独立运行态BASE_URL=/返回原样）并统一改写上述13处构造点；不改server/start（0001/0002服务端面保持）、不改任何鉴权/写回/CAS/权限语义，请求仍走nginx auth_request→broker拦截→Java changeset原链。**补丁0005扩展（同卡冒烟实证的第二处既有链缺陷，详见§13.3登记二）**：localization-node.ts/feedback-server.ts两个插件中间件先于studioPlugin注册（start.ts:165插件顺序），带会话前缀的/__studio/locales、/__studio/feedback到达时未被剥离前缀即匹配路径→落入vite base面404；locales是i18n引导必需（main.ts顶层await initializeI18n、!ok即throw无降级）→iframe空白。修复=照0001在server.ts的同一HYPIT_STUDIO_BASE_PATH剥前缀惯例补进两文件（env未设完全惰性；不动allowsStudioMutation等既有鉴权门）。**登记三（同日）**：冒烟全量轨迹实证0005初版studioUrl()保留BASE_URL尾斜杠拼首斜杠path产生`//__studio/*`双斜杠、被代理白名单拒绝400（i18n locales为唯一可见失败、外壳空div）；修复=apiBase去尾斜杠join，patch hunk与manifest afterSha同步再生（详见§13.3登记三） | 11 |
| W74 | `platform-hypit/patches/manifest.json` | 修改 | C107F3-11按§13.3增量（与W73同一增量）：登记0005补丁（file+sha256+purpose+逐文件before/after哈希，after=补丁链0000～0005后的最终态；含扩展后的localization-node.ts/feedback-server.ts两文件行）；G source digest随补丁链变化由既有patch-replay.test与V-05如实重验，不预设历史摘要 | 11 |
| W75 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/variant/HypitVariantService.java` | 修改 | C107F3-11按§13.3增量四（条件修改——真实用例暴露既有链缺陷）：planVariant 状态闸放行「queued 且 plan_id=null」的重试行（retry 复位形态由 TC-F3-01-01 锁定为 queued/attempt+1/plan_id=null/build_id=null，worker 认领面要求 build_id 非空，重试行无人认领→重试链死锁）；queued 且已持 plan 的在途行仍不重规划；不改 retry 响应契约、不改 C09 收费/grant 语义（plan 在 grant 之前，免费） | 11 |
| W76 | `src/views/video-clone/composables/useHypitVariants.ts` | 修改 | C107F3-11按§13.3增量四（与W75同一增量）：retryItem 在 retry 落定（queued/attempt+1）后继续走既有 startBuild（plan→pricing→grant→submit），C09 收费通道 grant 仍由调用方持有、attempt 已进键故 plan/build requestId 与上一撞不复用；不改 cancel/确认/失效重授既有语义 | 11 |
| W77 | `src/views/video-clone/composables/useHypitResults.ts` | 修改 | C107F3-11按§13.3增量五（条件修改——真实用例暴露既有链缺陷）：refresh 装载路径在 outputs 就绪后补 `enrichDownloadUrls(first.id)`（与 selectBuild 同权）——此前仅 selectBuild/归档重读触发签名，重访历史工程时已归档行的短时下载链接永不生成（按钮恒「下载签名中…」，重查腿实录 0 次签名请求）；fail-soft 吞错语义与 selectBuild 现行实现保持一致，不改 /api/media 契约 | 11 |
| W78 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/variant/HypitVariantRepository.java` | 修改 | C107F3-11按§13.3增量六（条件修改，与W75同一死锁链的真根因）：markQueued 的 CAS WHERE 只认 `state IN ('draft','planned')`——retry 复位行（queued/plan_id=null，TC-F3-01-01 锁定形态）经 W75 闸放行重新规划后 UPDATE 0 行→空 Mono→planVariant 502「变体计划未返回」（smoke12/13 两轮 15s 间隔确定性复现的实证根因；首轮「sidecar 60s block 预算」诊断为误诊——block 超时抛异常而非空返回，空 Mono 唯一来源是该 UPDATE）。修复：WHERE 放行 `state='queued' AND plan_id IS NULL` 的重试形态；已持 plan_id 的在途 queued 行仍不重复绑定（幂等闸保持）；同笔删除 W75 中针对误诊加的 plans.plan 空返回单次重试（死代码，broker 命令端点恒返回 body） | 11 |
| W79 | `src/views/video-clone/components/ClonePlanPanel.vue`、`src/views/video-clone/VideoCloneWorkbench.vue` | 修改 | C107F3-11按§13.3增量七（条件修改——V-11 门禁首跑暴露的既有链缺陷）：「重新生成方案」按钮被装配处的 `:generating="workflow.busy.value"` 全闸禁用，而 busy 含 analyzing 相位（useHypitWorkflow.ts:71）→ 参考分析在途时再生成按钮 UI 静默禁用，与 §8 UI-03 语义（分析在途放行点击→服务端 409 前置拒绝→「请先完成参考视频分析」可行动文案引导）直接冲突；TC-F3-10-04 浏览器面首次真实执行（C10 时 NOT_RUN 留 V-15，本卡门禁 V-11 全 spec 首跑）即 60s toBeEnabled 失败实证。修复：面板加可选 prop `regenerateGate`（缺省 undefined 沿用旧 generating 语义，W28 单测与既有调用方零变化），装配传 `busy && phase !== 'analyzing'`——「按方案生成」仍受 generating 全闸保护，再生成仅在生成链忙碌（planning/pricing/submitting/cancelling）或 regenerating 在途时禁用 | 11 |

| W80 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/build/HypitPlanRepository.java` | 修改 | C107F3-11按§13.3增量八（条件修改——V-11 门禁与本地复现双实证的既有链缺陷）：insertPlan 内容寻址幂等键 `(project_id, plan_hash)` **跨 revision** 命中旧行，与 requirePlanFresh 的 revision 闸（plan.revision≠project.revision→409 hypit_plan_stale）组合成**同内容永久死锁**——工程 revision 前进（如编辑保存）后源内容不变时，plan 端点永远读回旧 revision 行、builds 永远 409「重新 plan」，而用户重「按方案生成」也无法完成（重点走同链同 hash 命中同旧行）。修复：幂等键收窄为 `(project_id, plan_hash, revision)`——同内容同 revision 仍幂等读原行（计划不可变语义保留），跨 revision 同内容产生新冻结行（旧行不动）；requirePlanFresh revision 闸/「绝不 UPDATE 旧行」/C107F2-06 冻结语义均不变 | 11 |
| W81 | `src/views/video-clone/composables/useHypitSource.ts`（+同目录 `useHypitSource.test.ts`） | 修改 | C107F3-11按§13.3增量九（条件修改——V-11 门禁 TC-F2-37-01 失败的毫秒级 trace 实证前端既有链缺陷）：`open()` 的迟到解析无条件置 saveState=idle 并以服务器旧内容覆盖草稿——保存链（changesets→apply 数秒在途）中保存按钮中途复活（:disabled="saving" 失真），紧随的生成在 apply 事务提交（工程 revision 推进）前调 plan，服务端按旧 revision 冻结计划→builds 409 hypit_plan_stale 且方案面板无生成错误位→旅程静默卡死。与 `edit()` 已有的 debounce 迟到回写防护（仅守 idle/dirty）同类。修复：新增 savingPath 在途保存目标追踪（save() 发起置位/finally 与 reset() 解除）；open() 迟到解析遇在途保存：同文件只刷新 CAS 基线（草稿/saveState 由保存链收敛），跨文件换草稿但保持在途态；既有打开/冲突刷新/保存语义与既有三用例断言不变。新增 TC-F2-37-01 编辑保存段反例两例（先 RED 后 GREEN） | 11 |
| W82 | `src/views/video-clone/VideoCloneWorkbench.vue` | 修改 | C107F3-11按§13.3增量十（条件修改——smoke34 实证 console 卫生缺陷）：`startRegenerate` 用 `void` 丢弃 regeneratePlan 的 promise 而 composable 落态后 rethrow（useHypitWorkflow.ts:172，等待方需要失败信号的既有契约）→ 分析未完成窗口的 UI-03 预期 409 前置拒绝成为**浮动拒绝 pageError**（trace pageError 栈实证：`at async Object.E [as regeneratePlan]`），打脏控制台并让旅程 console 卫生断言泄漏；`startGenerate`（@generate 事件处理器不消费返回值）与 generate 的 rethrow（:242）同型。修复：两处 fire-and-forget 调用 `.catch(() => undefined)` 收口——状态已由 workflow 内段落（regenerateError/error/phase），rethrow 契约与既有测试断言不变 | 11 |

- 生成物：`test-artifacts/task-107/fix3/runs/<runId>/` 为本次运行输出，runId由入口生成；日志/JUnit/TAP/截图/媒体/manifest只由测试生成，不提交。Gradle build、Node .generated 仅按原工具正常构建生成，不手改冻结源。尖括号runId仅指运行时生成标识，不是待决产品参数。
- 黑名单：模板、冻结upstream、历史fix2/audit产物、105-fix-2数字人文件、生产凭据、既有migration、依赖清单/lockfile/CI、未登记生产文件。只读文件不得写入。维护/Studio/runner的条件修改仅修现有契约，不许改架构或另建旁路。
- W11可按§13.3补充等价私有拆分的精确登记；涉及新增产品边界仍需用户确认。W08/W02/W58～60集中C05写，C06只根据最终证据推进状态；其他卡不抢写。

### 9.2 仓库硬约束

R-UI/R-LAYER：根DESIGN、gl-field/token/字体/明暗截图；W24≤800行，业务进W25，子面板复用。R-JAVA：真实PG、响应式I/O、owner非存在性泄漏，HypitDtos信封保持。R-AI：FrozenTextExecutionService平台路由/预算/用量，不能直连替代或自算费用。R-DATA：稳定幂等/CAS/lease fencing，不修改历史migration。R-LIFECYCLE：请求/观察/帧buffer/临时进程完整清理。R-QUALITY：现有Vitest/JUnit/node:test，自动发现新增测试，保留既有覆盖率阈值。R-DIR/R-SAFE：产物在test-artifacts；工作区旧改动保留；不使用全局清理。

### 9.3 验证环境、fixture与资源计划

2026-10-05 MiniMax 接入修复计划：先在 y-1 守卫内串行执行不启动容器的 speech/TTS 定向单元与 HTTP 契约测试；再仅启用 postgres-local，复用现有卷和 MiniMax 凭据，修订本地 voice 主配置并验证 Java 适配器实际调用与既有真实 tar 音频归一。配置修订保留旧版本/history，不作全环境数据库迁移。阶段结束停止新增 postgres-local，保留数据卷和原有 Docker Desktop；验收后按规回收旧构建缓存。证据根 test-artifacts/task-107/minimax-fix-2026-10-05/。

2026-10-05 MiniMax 接入修复结果：新增原生 minimax ASR 适配器与 TTS tar 归一，当前 speech/videoproduction 域 Gradle 回归 120/120，无跳过；Java 真实 ASR 返回完整文字和字级时间戳，实际 TtsWorker 下载并提取的音频与参考逐字节一致（84660 字节/5120ms），完整 ffmpeg 解码通过。本地 voice 主配置已通过仓储修订为 minimax/asr-1.0 v2，旧配置/history 保留；现有可信 OSS 来源验证通过，未放宽来源策略。本地价目表暂缺 asr-1.0，是否沿用零积分测试价已询问用户，选择前保留 unpriced_model 闸门；不声称用户请求全链路及账本结算通过。本次 postgres-local 已停止，保留原卷与 Docker Desktop，构建缓存按规回收。


2026-10-05 MiniMax 真实调用授权：用户明确仅本地、不部署生产，使用现有 MiniMax 配置，不设本轮费用上限；遇额度耗尽、限流或不可用即记录，不做无限重试。先持 `y-1` 单栈守卫，仅启动 `docker-compose.yml` 中 postgres-local（现有 y-1_postgres_local_data 保留），只读提取平台模型/凭据配置并验证提供者能力，结束 stop 本次数据库服务，禁止 reset/删卷。无需构建镜像；不回显凭据，证据根 `test-artifacts/task-107/minimax-live-2026-10-05/`。后续集成链路如需扩展服务，先登记其最小白名单；未实际验证的能力不计通过。

2026-10-05 MiniMax 直接调用结果：MiniMax-M3 文本与两次左右换色识图通过；speech-02-hd 返回 tar 包，提取 MP3 后完整解码通过（5.12 秒）；asr-1.0 原生接口返回 HTTP 200，23 个字级时间戳，转写除标点外与输入完全匹配。已知文本/识图 746 tokens，ASR 计费时长响应为 5.184 秒，实际金额未知。平台 voice 仍为 runtime-stt-fake，TtsWorker 尚未处理本次 tar 包，真实应用全链路与账本结算未验证，不计通过。证据见上述目录 README.md 与原始 JSON。仅新增 postgres-local 已停止，原卷与预先运行的 Docker Desktop 保留。



2026-10-04 复审修复执行登记：用户已明确授权修复复审发现。保留105在途工作；本轮增加变体作用域取消/恢复测试、版本推进后的请求重放测试，修复LIVE入口及旧静态断言。证据根 `test-artifacts/task-107/fix-2026-10-04/`，历史证据只读。先无容器前端/Node定向与域回归，再独占`y1-hypit-fix3`守卫运行真实PostgreSQL Java IT；结束后才进入下表唯一`y1-e2e-local` E2E组合，三引擎单worker串行。初始Docker可用且无应用容器，无需停止旧栈；各阶段结束清理本次临时进程/容器并保留原有数据，验收后回收超过24h构建缓存；Docker Desktop为原有进程，不退出他人启动的实例。当前修复仍在验证，不以代码落地或旧状态代替本次通过。

2026-10-04 历史隔离门禁续接：用户授权修复并复查 C107F2-02/03/04 入口及全库链接错误。证据根 `test-artifacts/task-107/isolation-2026-10-04/`；启动盘点无运行中应用容器、无 Gradle/Vite/测试重型进程，Docker Desktop 为原有实例。依次独占运行：C03 `fix2-c03-images`，使用 `tests/e2e/fixtures/hypit-image-isolation.compose.yml`，只构建 backend-image/runner-image、一次运行一个只读临时探针，再单独起 broker-restart 验证既有文件；C04 `fix2-c04-runner`，使用 `deploy/hypit/compose.runner.yml`，仅 hypit-backend/hypit-author-runner；C02 `y1-hypit-fix2-e2e`，使用 base＋DH test＋Hypit test 组合，请求 frontend/redis/hypit-backend/hypit-author-runner/dh-runtime，真实依赖为 edge-bff、identity/marketplace/finance/trust/intelligence-service、postgres-local、database-bootstrap、redis、kafka、temporal、minio/minio-init、dh-redis。共享 redis 为内部断言防重放真实依赖（与 ci-e2e 入口一致），虽然未声明 depends_on，仍必须显式启动。C02 覆盖 DH/Hypit overlay 共存及开关，DH 为本卡必需；关闭态只请求 frontend/redis 及其上述基础依赖。三阶段均持 fresh/cleanup 守卫，串行构建与测试；只重置本次新建资源，各阶段退出后确认无容器并回收超过 24h 的构建缓存。保留已有卷、原有 Docker Desktop 和其他会话工作。未执行或失败项不得登记通过。 补充盘点发现旧 `fix2-c04-runner` 和 `y1-hypit-fix2-e2e` 仍有历史数据卷，无运行或停止容器；保留旧卷，不能对其 fresh/reset。本轮分别固定 `HYPIT_C04_PROJECT=fix2-c04-isolation`、`HYPIT_C02_PROJECT=y1-hypit-fix2-c02-isolation`，确认两者无容器/卷后执行；守卫仍拒绝任意其他运行栈，不能以改名绕过并存限制。 C03 两镜像验证通过后，C04 显式设置 `HYPIT_C04_NO_BUILD=1` 并通过 `HYPIT_BACKEND_IMAGE`/`HYPIT_RUNNER_IMAGE` 复用刚产出的 `fix2-c03-hypit-backend:latest` / `fix2-c03-hypit-runner:latest`，镜像 ID 落证据；避免缓存回收后重复下载/构建。独立 C04 默认仍构建，复用不减少运行期断言。 C02 在同一 fresh 守卫内先以单 worker 构建七个最新 bootJar，再经 wrapper 串行构建 frontend 真实依赖及 dh-runtime；Hypit 两镜像复用已过 C03/C04 的确切镜像，只给本轮项目增加镜像标签。所有镜像准备后设置现有 `HYPIT_COMPOSE_NO_BUILD=1` 运行 C02 两阶段，避免重复重建，不省略任何业务/配置断言。 实际冷构建在 Alpine/LLVM 下载处长时间停滞，已正常中断并清理（日志保留）；改走等价最小路径：fresh 会话内逐个运行只读、无网络、完成即删除的镜像哈希探针，七个 Java jar 与当前 bootJar 一致才复用；数字人 src/patches/scripts/四份锁与配置文件及入口脚本逐字节核对；前端执行当前源码生产构建，以已有 nginx 依赖层重新装入 dist 与三个当前配置片段。具体镜像 ID 和应用文件哈希记录在 `c02-image-verification.json`，不宣称冷安装或依赖仓库网络已验收通过。

2026-10-05 本轮续接结果：C02 13 项真实组合测试＋12 项契约回归全部通过，C03 5 项、C04 4 项全部通过；守卫/门禁定向回归 85 项通过；后端宿主全量 239 项通过、2 项外部依赖跳过（WhisperX 与 HypiHub，不计通过）。全库链接格式错误与未索引项均清零，类型检查、lint、密钥扫描、状态文档检查通过。C02 采用上文经逐文件哈希核验的现有依赖镜像与当前前端构建；冷下载失败不记成功。三卡新增容器/卷/网络均已清理，旧项目卷与原有 Docker Desktop 保留，构建缓存按规定回收。详细原始报告、实际镜像身份和服务清单在本轮证据根；此记录只关闭三项历史隔离入口及文档门禁复查，不扩大为商业模型或生产验收通过。

| 阶段 | 最小服务/真实对象 | 切换与收尾 |
|---|---|---|
| 出书（当前） | 仅文件读取和docs静态检查；应用服务白名单为空 | 不启停服务；业务测试NOT_RUN |
| Java单元 | 单Gradle worker/JVM；无容器 | 无其他重型任务时执行，进程结束即释放 |
| Java IT | Testcontainers PostgreSQL16；本进程Spring/本地WireMock最后提供者边界 | 先确认没有并行开发/验收栈和重型进程；不改共用IT支撑，框架只清本次容器 |
| Node engine/media/studio | 真实broker/daemon/FFmpeg/Studio子进程，仅该组所需；无Compose | 与Java/浏览器串行；临时端口/短socket，失败也关闭进程、等待exit后清临时根 |
| 公共入口E2E | frontend、edge-bff、identity-service、marketplace-service、finance-service、trust-service、intelligence-service、database-bootstrap、postgres-local、redis、kafka、temporal、minio、minio-init、hypit-backend、hypit-author-runner、hypit-fix2-text-provider | 前五域为当前Edge/前端真实depends_on；Kafka/Temporal/MinIO等是当前Java真实依赖，不用no-deps删掉；不用DH、监控、日志、其他媒体worker |

E2E复用W67/W69的唯一 `y1-e2e-local` fresh组合：docker-compose.yml＋tests/e2e/fixtures/hypit-fix2-model.compose.yml＋deploy/hypit/compose.test.yml。入口显式HYPIT_E2E=1、HYPIT_FIX2_TEXT_FIXTURE=1，DH_E2E=0、DH_FIX2_E2E=0、CANVAS_E2E_TEXT_FIXTURE=0；workers=1。若现有兼容栈属于活跃会话，不停、不另起；协调释放期间可做静态工作。不同阶段先结束自己的栈/临时容器，再运行下个阶段；不得同时跑IT容器与E2E栈。

每次启动前执行docker compose ls、docker ps、docker stats --no-stream、检查labels/ports/mounts及Gradle/Vite/Playwright进程；Docker不可用视环境阻塞，不能当空栈。实际依赖展开清单必须与上表核对；变化先更新本节证据，不私自放开全部profiles。重型构建/测试/解码同一时间一个。守卫记录本次新增/原有服务，普通stop保卷；仅fresh会话允许reset本次新资源，异常信号也执行cleanup。共享scripts/ci-e2e.sh的数字人在途改动只读，若入口契约不兼容则停止相应E2E并具体报告。

固定fixture：

| fixture | 合成输入/初态 | 允许替身与禁止替身 |
|---|---|---|
| F-OWN | A/B为固定测试UUID；各一ready工程，revision2，归属不共享；第三个unknown ID与deleted工程 | IT真实PG/身份/Controller；外模型和对象存储末跳可记录式替身，不能替换owner查询 |
| F-REF | W64生成12s、320×240、0/4/8秒三段红/绿/蓝视频，无声版与带单音轨版；记录源sha、六帧时间/sha；转写合成词有样本区间0..16000、64000..80000 | Java边界测试可注入真实wire形状；Node真实probe/frames/解码，speech标准化用已定义末跳ASR回执，不声称识别质量 |
| F-MODEL | 本地提供者仅替换外部商业模型最终HTTP，严格解码图片、校验时间/词/analysis标识；根据输入红蓝不同返回不同可编译工程 | 不得替换站内成功API、PG、broker、runner或FFmpeg；省略图片/上下文应返回422使链路失败 |
| F-RUNNER | 既有短路径daemon fixture、合法自定义组件与恶意读宿主探针；每测试独占slot/state/tmp | 无假runner；仅合成恶意文件，绝不读真实凭据；结束等子进程退出 |
| F-SCRIPT | 临时目录复制脚本/报告、固定本地HTTP探针；环境移除真实Provider地址与key | 专用于门禁反例，禁止访问真实模型；结果标FIXTURE不能LIVE_PASS |

Node24.14.1沿用现有npm package入口；JDK25由scripts/lib/java-runtime.sh核验。不安装新框架。临时凭据仅本次合成，不回显、不存Git。实际运行资源、端口、卷、停止情况在每卡§14交接中记录。

### 9.4 安全、性能与兼容

帧预算/快照预算固定RULE-008/§7.2；一次顺序读6帧，拒绝任意外部URL与未知句柄。保留已有500MiB上传上限，不扩大。身份按owner/内部token边界；参数重放与CAS见RULE-010～013。模型超时沿90秒现有分析预算，资源读取沿既有30秒；不叠加无界重试。调度失败必须有实际错误，不自动fallback到文本分析。

### 9.5 适用矩阵

| 规则族 | 卡 | 证明 |
|---|---|---|
| R-UI/LAYER | C10/C11 | §8、TC-F3-10-04、TC-F3-11-03、体积/lint/截图 |
| R-JAVA/DATA/AI | C01/C07～10 | 真实IT、owner/快照/模型HTTP反例、幂等用例 |
| R-LIFECYCLE | C02/C03/C08/C10/C11/C13 | 信号/重启/取消/旧回调/临时资源检查 |
| R-SAFE/DIR/QUALITY | 全部 | 新run证据、共享文件保留、门禁与最终diff |

## 10. 开发计划与任务总表

原六卡职责保留但扩大C06最终集成；C13先提供可信验收入口，编号不代表执行顺序。表内状态仅实施者按证据推进，初始全NOT_STARTED。

| 卡 | 标题/REQ | W写集（W11状态默认适用） | 依赖 | 验收 | 状态 |
|---|---|---|---|---|---|
| C107F3-13 | 可信分层验收入口与反向门禁 / REQ-013 | W03 W09 W48 W49 W50 W51 W52 W53 W54 W64 | 无 | AC-013；TC-F3-13-01～03；V-01 V-02 | VERIFIED |
| C107F3-01 | C26重试/取消真实路由补测 / REQ-001 | W01 W03 | C107F3-13工具入口 | AC-001；TC-F3-01-01～03；V-03 | NOT_STARTED |
| C107F3-02 | 维护栅栏当前复验与最小修复 / REQ-002 | W04 W05 W12（§13.3增量：W52） | C107F3-13入口 | AC-002；TC-F3-02-01～02；V-04 | VERIFIED |
| C107F3-03 | runner当前复验及engine门禁接线 / REQ-003 | W03 W06 W43 W44 W45 | C107F3-02维护协议交付 | AC-003；TC-F3-03-01～02；V-05 | VERIFIED |
| C107F3-04 | C32机读命名与域回归记录 / REQ-004 | W07 | C107F3-03 engine门禁 | AC-004；TC-F3-04-01～04；V-06 | VERIFIED |
| C107F3-07 | 探针与转写契约归一化 / REQ-007 | W13 W14 W21 W29 W30 W41 W42 W55 | C107F3-04当前域基线 | AC-007；TC-F3-07-01～03；V-07 | IMPLEMENTED |
| C107F3-08 | 真实图像输入与有界资源读取 / REQ-008 | W13 W14 W29 W30 W40 W41 W42 W55 W62 | C107F3-07 collect合同 | AC-008；TC-F3-08-01～03；V-08 | IMPLEMENTED |
| C107F3-09 | Agent归档工程归属闸 / REQ-009 | W16 W31 | C107F3-13工具入口 | AC-009；TC-F3-09-01～03；V-09 | VERIFIED |
| C107F3-10 | 可信再生成快照与前后端恢复 / REQ-010 | W17 W18 W19 W20 W22 W23 W24 W25 W26 W27 W28 W32 W39 W40 W51 W55 W56 W57 W71 | C107F3-07/08取证、09权限 | AC-010；TC-F3-10-01～04；V-10 | IMPLEMENTED |
| C107F3-11 | Studio保存与跨owner移植真实闭环 / REQ-011 | W33 W34 W35 W36 W37 W38 W39 W40 W61 W63（§13.3增量：W73 W74 W75 W76 W77 W78 W79 W80 W81 W82） | C107F3-10上下文链、03runner | AC-011；TC-F3-11-01～03；V-11 | IMPLEMENTED |
| C107F3-12 | LIVE入口fail-closed与状态分层 / REQ-012 | W46 W47 | C107F3-13报告协议 | AC-012；TC-F3-12-01～03；V-12 | IMPLEMENTED |
| C107F3-05 | 历史证据、声明与状态一致性修正 / REQ-005 | W02 W08 W58 W59 W60 | C107F3-01～04、07～12当前卡证据 | AC-005；TC-F3-05-01～03；V-13 | IMPLEMENTED |
| C107F3-06 | 最终回归、本地集成与索引交付 / REQ-006 | W02 W08 W09 W10 W11 W39 W48 W52 W58 W59 W60 | 除本卡外12卡均VERIFIED | AC-006；TC-F3-06-01～03；V-14 V-15 | NOT_STARTED |

### 10.1 拆卡与共享写入

每卡一个可验证出口；C13先建验证工具，不伪造后续业务通过。W13/W14按C07→C08；W40按C08→C10→C11；W38由C11负责；W03按C13→C01→C03；W02/W08及状态文档C05→C06单写者。全部顺序执行，不因卡可独立而并行重型工作。

### 10.2 交接

每卡交付具体符号/合同、TC报告、runId/源码摘要、未完成项及资源状态；后卡读取前卡真证据，不能只认完成文字。依赖输出变化先同步合同与调用方/测试。

### 10.3 阶段出口

M0=C13工具门禁；M1=C01～04原缺口与当前基线；M2=C07～10核心链路；M3=C11/C12深验与LIVE诚实性；M4=C05/C06文档和最终本地集成。M2输入契约未通过不得烧资源跑M3浏览器；C06任一必需失败回责任卡返工。

### 10.4 最早风险验证

C07最早验证真实Node字段，C08验证平台实际多模态消息，C09先验证外工程归档，C10先验证重放/快照与CAS，C11先验证真实保存，C13先验证门禁失败传播。当前runner若绿不重写；若必须改变隔离架构则仅相应链BLOCKED并由主程修订，不设带红完成出口。

## 11. 任务卡

各卡执行/验收责任人均为被派发模型。开始/完成统一§0.4/§0.3；路径与操作唯一见§9.1；本节W集只收窄。卡号简写C01等均指C107F3-01。

### 卡 C107F3-13：可信分层验收入口与反向门禁

**目标/输入/输出**：REQ-013；发布可复用的stage入口、独立run证据协议、fixture生成及真实JUnit/TAP解析；后卡用该入口验收。C13卡级只验工具本身，不要求尚未实现的业务TC提前通过。 输入=无；输出=W03 W09 W48 W49 W50 W51 W52 W53 W54 W64所列符号与V-01 V-02本次报告，后置卡按§10消费。

**必读与边界**：§2当前对应链、§3 D-13、RULE-015、§9.3、§12.3、§12.2本卡TC；写集 `W03 W09 W48 W49 W50 W51 W52 W53 W54 W64`。不得触碰黑名单、扩大产品/权限/计费语义；遇未登记必要写入按§13.3。

| 步骤 | 精确动作/符号 | 完成检查点与失败处理 |
|---|---|---|
| 1 | 读取W67/W69/W70与守卫，列出每个stage实际启动物；W52的all父层不抢锁，受委托脚本独立持有既有guard，直接Java/Node阶段自己接入W68。禁止嵌套争抢不兼容的project锁。 | 按TC-F3-13组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 2 | 按§12.3实现固定stage与--run参数；生成唯一runId、源码摘要和预期TC清单；传FIX2_ART_BASE到旧入口，所有当前证据只写新run，旧缺省路径兼容。 | 按TC-F3-13组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 3 | W53解析JUnit skipped/failure/error及TAP pass/fail/skip/todo；分别验证每个浏览器预期用例，报告缺失或0执行失败。W49/W50调用同一解析并按通过的目标断言生成Gate，不硬填skipped=0。 | 按TC-F3-13组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 4 | W64用固定参数生成F-REF及sha清单，单次FFmpeg串行；W38/W51输出根由后续卡接收HYPIT_FIX3_EVIDENCE_ROOT，新wrapper在调用前设置。 | 按TC-F3-13组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 5 | W54隔离注入子进程exit1、空JUnit、skipped、缺引擎、旧run报告、缺产物、信号退出；断言非零与精确清理。W48登记本书卡/TC/状态，不将未开始卡标绿。 | 按TC-F3-13组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |

**验收**：AC-013：Given缺失/跳过/失败/旧run报告，When运行聚合，Then非零且指出具体TC/引擎；合法合成报告只证明工具测试通过，不写业务LOCAL_PASS。

测试 TC-F3-13-01～03，命令 V-01 V-02。适用异常/完整输入见§12.2；每个关键负向必须证明目标保护被触发。UI N/A（本卡不改页面）；保留相邻既有测试及已定义响应。

### 卡 C107F3-01：C26重试/取消真实路由补测

**目标/输入/输出**：REQ-001；保留原TC-F2-26-01/03/04，新增可机读TC-F2-26-02；C06再做Edge公共入口穿透，不能混淆两层证据。 输入=C107F3-13工具入口；输出=W01 W03所列符号与V-03本次报告，后置卡按§10消费。

**必读与边界**：§2当前对应链、§3 API-001、RULE-004、§12.2本卡TC；写集 `W01 W03`。不得触碰黑名单、扩大产品/权限/计费语义；遇未登记必要写入按§13.3。

| 步骤 | 精确动作/符号 | 完成检查点与失败处理 |
|---|---|---|
| 1 | 复用现有seedVariantWithBuild/client/sign；client为bindToServer，用真实HTTP和PG，不mock被测Controller/VariantService。 | 按TC-F3-01组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 2 | failed变体POST retry后200 data.state=queued、attempt=2、DB build_id=null；active running变体POST cancel后data.state=cancelled，实际build按既有cancel路径收敛。 | 按TC-F3-01组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 3 | 补B访问A与未知变体均404 hypit_not_found、匿名401；记录请求命中与DB不变。去掉目标路由时正向200断言必须失败，不能只断言非404。 | 按TC-F3-01组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 4 | 新JUnit DisplayName包含TC-F2-26-02（Java方法名使用合法下划线，不含连字符）；修W03注释与发现清单，复验原类及HypitVariantIT。 | 按TC-F3-01组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |

**验收**：AC-001：Given A的failed/running变体，When经真实HTTP重试/取消，Then响应和新事务DB一致；B/未知拒绝且状态不变，报告发现四个历史TC。

测试 TC-F3-01-01～03，命令 V-03。适用异常/完整输入见§12.2；每个关键负向必须证明目标保护被触发。UI N/A（本卡不改页面）；保留相邻既有测试及已定义响应。

### 卡 C107F3-02：维护栅栏当前复验与最小修复

**目标/输入/输出**：REQ-002；确定当前维护协议是否已正确；仅在现有实现内修复已复现问题，保留全部拒绝/排空/租约语义。 输入=C107F3-13入口；输出=W04 W05 W12所列符号与V-04本次报告，后置卡按§10消费。

**必读与边界**：§2当前对应链、§3 RULE-014、§12.2本卡TC；写集 `W04 W05 W12`。不得触碰黑名单、扩大产品/权限/计费语义；遇未登记必要写入按§13.3。§13.3增量登记（C107F3-02实施时补记）：W52 `run_backend_group` 仅修TAP落盘接线——实证缺陷为 node@24.14.1 默认 reporter 是 spec（非 TAP）且 run-tests.mjs 不落 TAP 文件，原实现解析的 `.tap` 永不存在，真实绿组也会被 W053 记非法报告；修复为子shell内显式 `--test-reporter=tap` 并把原始输出落 `.tap`，不改变组编排/守卫/W053解析契约，影响TC即本卡TC-F3-02-01～02的V-04门禁。

| 步骤 | 精确动作/符号 | 完成检查点与失败处理 |
|---|---|---|
| 1 | 在独占临时目录与动态端口启动真实broker；记录PID/源码摘要/健康响应，端口占用不杀陌生进程。旧9254/旧日志不证明当前失败。 | 按TC-F3-02组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 2 | enter拿lease并等待drained；发送非豁免plan与resource ingest，应503；GET/status与已接受操作的收敛仍允许。错误lease exit409，持有者exit200。 | 按TC-F3-02组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 3 | 若当前绿，保持业务实现，仅修测试卫生与留证；若红，定位W05入口是否早于dispatch设闸、W12豁免与active计数，最小修复同一契约。超出现有隔离/数据规则按§13修订。 | 按TC-F3-02组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 4 | 在t.after与启动失败路径等待child exit后清目录；再次完整跑maintenance与agent-integration组，保留开始即绿或真实前后差异。 | 按TC-F3-02组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |

**验收**：AC-002：Given有效maintenance lease，When新副作用/错误退出/既有收敛请求交错，Then新副作用0、错误退出不解除、合法收敛与holder退出可完成。

测试 TC-F3-02-01～02，命令 V-04。适用异常/完整输入见§12.2；每个关键负向必须证明目标保护被触发。UI N/A（本卡不改页面）；保留相邻既有测试及已定义响应。

### 卡 C107F3-03：runner当前复验及engine门禁接线

**目标/输入/输出**：REQ-003；沿现有daemon架构验真实隔离/编译/超时回收，全engine成为必需门禁；没有known-failure完成出口。 输入=C107F3-02维护协议交付；输出=W03 W06 W43 W44 W45所列符号与V-05本次报告，后置卡按§10消费。

**必读与边界**：§2当前对应链、§3 D-03、RULE-015、§12.2本卡TC；写集 `W03 W06 W43 W44 W45`。不得触碰黑名单、扩大产品/权限/计费语义；遇未登记必要写入按§13.3。§13.3增量登记（C107F3-03实施时补记）：①W54 追加本卡TC-F3-03-02（§12.2既定位置=「C13创建、此卡复验」，但C13实际未落该用例；C107F3-03为唯一实施责任）——真实wrapper隔离副本注入「其他组成功、仅engine子进程exit1」，断言失败版card非零且runner engine FAIL、静态核验W03 C32含backend:engine与W52 engine分派不被删；不动TC-F3-13既有断言；②W52 stage_engine 的W54传导复核从`-t 'TC-F3-03'`过滤改为整文件运行——实证vitest v3.2.7过滤JSON中未匹配用例status=skipped（numPendingTests=N），W053 vitest门禁按RULE-015将skipped>0记问题、emit必然非零假红；整文件运行保持skip/todo=0真实门禁并顺带复核TC-F3-13组，不改stage编排/守卫/期望表。

| 步骤 | 精确动作/符号 | 完成检查点与失败处理 |
|---|---|---|
| 1 | 读W43当前短socket路径/启动命令/handshake/waitIdle，核实生成发行版与本地Node；不要重复造daemon。先运行当前四项并记录与旧ENOENT日志的差异。 | 按TC-F3-03组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 2 | 只修已有fixture独占slot/state/tmp、启动失败cleanup、daemon握手/退出生命周期；不得增加超时掩盖死锁、禁用权限模型、改为broker执行作者代码。 | 按TC-F3-03组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 3 | 确认恶意探针实际执行marker、宿主读取拒绝、秘密不泄漏、合法项目仍check；真实packages.build成功与坏源码diagnostics；超时后新slot无污染。 | 按TC-F3-03组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 4 | W03的C32明确追加backend:engine，全组零必需失败；W54反例注入该子进程失败，证明card退出非零。修复失败只BLOCKED，不“不登记”或降级通过。 | 按TC-F3-03组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |

**验收**：AC-003：Given真实daemon与合法/恶意/超时fixture，When完整engine及C32门禁运行，Then安全和回收断言通过；engine任一失败必拉低卡退出码。

测试 TC-F3-03-01～02，命令 V-05。适用异常/完整输入见§12.2；每个关键负向必须证明目标保护被触发。UI N/A（本卡不改页面）；保留相邻既有测试及已定义响应。

### 卡 C107F3-04：C32机读命名与域回归记录

**目标/输入/输出**：REQ-004；独立发现TC-F2-32-04并实跑studio/media/video-clone；为C05提供当前数字。 输入=C107F3-03 engine门禁；输出=W07所列符号与V-06本次报告，后置卡按§10消费。

**必读与边界**：§2当前对应链、§3 RULE-002/015、§12.2本卡TC；写集 `W07`。不得触碰黑名单、扩大产品/权限/计费语义；遇未登记必要写入按§13.3。§13.3增量登记（C107F3-04实施时补记）：①W52 stage_domain 域执行TC登记接线——run_backend_group 增可选第2参（构成TC）、run_vitest 读 CONSTITUTES_TC，studio/media/video-clone 三域分别经 W053 `--constitutes` 登记为 TC-F3-04-02/03/04；不改组编排/守卫/期望表。②W53 tap/vitest/junit CLI 增可选 `--constitutes`：仅当本报告无任何问题（executed>0、failed/skipped/todo/cancelled=0）且子进程退出0时把构成TC并入 found；非绿不登记，emit 缺必需TC 兜底非零——依据：§12.2 把 TC-F3-04-02/03/04 定义为「W52/Node域执行」（TC 证据=该域原始报告本身），而 W53 emit 的 found 只从报告用例名发现 TC，三域既有用例名不携带也不应携带本卡TC名（不是其验证内容）；此通道不把日志TC字符串当已执行（RULE-002/015），报告非绿时对应TC必然缺失、门禁非零。③W53 parseTap/evaluate/CLI 增可选 `--allow-external-blocked-skip`（默认关）——实证依据：V-06 attempt-1（run fix3-20261002T094819Z-18b9）中 media 组 49执行+1 skip，被skip者为 speech-tools 的 whisperx 真实ASR用例，其skip由用例自身探测 whisperx.local 不就绪后以 `EXTERNAL_BLOCKED` 声明（模型下载是运维工作），§1.4 明确真实ASR模型效果独立NOT_RUN、§9.3 media阶段最小服务无该依赖、§12.2 TC-F3-04-03 仅要求「当前实数可还原」（对比 TC-F3-04-02 studio 明确要求无skip）；该旗标仅由 stage_domain 的 media 腿显式传入，仅豁免「用例自声明 EXTERNAL_BLOCKED」的 skip 并把实数（skipped/externalBlocked）如实计入 results.json，其余任何 skip/todo/失败仍非零；studio/video-clone 腿与其他全部 stage 调用不带旗标、行为不变。影响TC：本卡TC-F3-04-02～04（V-06门禁）。

| 步骤 | 精确动作/符号 | 完成检查点与失败处理 |
|---|---|---|
| 1 | 把B enter409且不泄漏leaseId抽成具名TC-F2-32-04子测试，自起broker，原TC-03重启fail-closed断言全部保留。 | 按TC-F3-04组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 2 | 通过W52运行C32卡，报告必须found含01～04，执行日志含engine分派；不得仅从源码注释搜TC。 | 按TC-F3-04组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 3 | 串行跑studio、media、video-clone全域，保存真实报告/退出码/skip；与旧21/48/12文件50用例比较只作差异，不用旧数作目标。 | 按TC-F3-04组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |

**验收**：AC-004：Given独立子测试和完整域，When运行V-06，ThenC32四TC均执行，三个域当前实数与skip可复查，无挑选性漏跑。

测试 TC-F3-04-01～04，命令 V-06。适用异常/完整输入见§12.2；每个关键负向必须证明目标保护被触发。UI N/A（本卡不改页面）；保留相邻既有测试及已定义响应。

### 卡 C107F3-07：探针与转写契约归一化

**目标/输入/输出**：REQ-007；MOD-001 collect与真实wire兼容；A02/A04反例成为正式回归；W13不继续扩大取证逻辑。 输入=C107F3-04当前域基线；输出=W13 W14 W21 W29 W30 W41 W42 W55所列符号与V-07本次报告，后置卡按§10消费。

**必读与边界**：§2当前对应链、§3 MOD-001、RULE-005～007、§12.2本卡TC；写集 `W13 W14 W21 W29 W30 W41 W42 W55`。不得触碰黑名单、扩大产品/权限/计费语义；遇未登记必要写入按§13.3。§13.3增量登记（C107F3-07实施时补记，2026-10-02，经主控裁定授权）：W52 stage_evidence/stage_vision 的 media 腿与 stage_domain 同语义接入既有 `EXTERNAL_SKIP_OK` 通道——实证依据：V-07/V-08 固定含 media 组（W41），而该组 speech-tools 的 whisperx 真实 ASR 用例在 whisperx.local 未就绪时由用例自身以 `EXTERNAL_BLOCKED` 声明 skip（真实模型下载系运维工作，§1.4 明确范围外 NOT_RUN；实测 127.0.0.1:8765 拒连），W53 evaluate 默认把任何 skip 记问题非零，C107F3-04 已为 stage_domain media 腿确立 `--allow-external-blocked-skip` 通道但 stage_evidence（V-07）/stage_vision（V-08）的 media 腿未接入，门禁必然假红。修复为两腿 run_backend_group media 前设 `EXTERNAL_SKIP_OK=1` 并随后复位（照 stage_domain 既有 471-473 行模式），仅豁免用例自声明 EXTERNAL_BLOCKED 的 skip 并在报告中逐条保留原因与实数，其他任何 skip/todo/失败仍非零；不放宽 W53 对非声明 skip/失败/0 执行的判定，不搭建 whisperx.local 或任何新 ASR 设施。影响卡：C107F3-07/C107F3-08；受影响 TC/V：TC-F3-07-01～03、TC-F3-08-01～03 的 V-07/V-08 media 腿（§9.1 W52 行责任卡同步增 07）。§13.3增量登记二（C107F3-07实施时补记，2026-10-02）：W72 新增——W41（TC-F3-07-01）以真实 runMediaTool 调用暴露既有生产缺陷：`handles.ts` registerResource 对 handles.index.json 是无互斥的读-改-写，而 `media.frames`（media.ts）以 `Promise.all` 并发注册 6 帧，实测并发写互相覆盖、部分帧句柄落 index 丢失（`unknown handle res-…`，media 组 50 用例 1 失败实证；broker 生产同路径返回的帧句柄同样可能无法解析）。修复：registerResource 的 index 读-改-写段按 index 文件路径模块级 promise 链串行化（sha 计算等文件 I/O 留在锁外），签名/handle 形状/幂等语义不变；不改 media.ts 调用形状，W72 边界仅限该串行化，W41 断言不放宽。影响卡：C107F3-07（W41/TC-F3-07-01、V-07 media 腿）。§13.3增量登记三（C107F3-07第二轮修复补记，2026-10-02）：W52 run_java 的 JUnit 复制源路径缺陷——V-07 首跑（run fix3-20261002T121723Z-3fe7 attempt-2）gradle `BUILD SUCCESSFUL in 59s`、`build/test-results/test/` 下三类 XML 齐备且全绿（HypitFix3EvidenceTest tests=3 / HypitFix3EvidenceIT tests=7 / HypitFix2C16IT tests=4，failures=errors=skipped=0，testcase 名携 TC-F3-07-01～03），但 aggregate 三个 java runner 全为 stats:null+「缺JUnit报告（0执行或未编译）」：run_java 先把类名 `.`→`/` 转换再拼源路径 `TEST-com/grassland/…/X.xml`，而 Gradle test-results 是扁平点号文件名 `TEST-<fqcn>.xml`，源路径恒不存在、复制恒跳过（该腿系首次真实执行暴露，V-03/V-04/V-06 均为 Node 组不含 java）。修复仅限：复制源/目标统一用点号类名（Gradle 真实布局），删去无效的斜杠预转换；解析循环、「真缺 XML 判 0 执行或未编译非法」守卫、期望表与各腿编排不变；已核 W48 仅静态解析 W52 的 stage_expect() 映射、W54 不读 W52 源码，契约无冲突。影响卡：C107F3-07（V-07 java 腿；后续各调 run_java 的 stage 同修同益）；同笔增量同步 W48——其「§9.1 W01…W71 全量登记」断言（恰好 71 个 W）与本卡登记二新增的 W72 行冲突（本轮定向实测 72≠71；上轮 V-01 tooling 复验 17/1 时 W72 行尚未落行故未暴露），W72 系已按 §13.3 登记的合法写集增量，工具全量清单断言须与 §9.1 登记同步（同 C107F3-04 同步 W53 --constitutes 先例）：71→72、期望数组 W01…W72、删行负例 70→71；W48 其他契约语义（结构/索引/状态-证据一致与注入反例）不动，W048 注入反例先在红仍留 W48 责任卡 13/06 待办。

| 步骤 | 精确动作/符号 | 完成检查点与失败处理 |
|---|---|---|
| 1 | W29先加入nested probe与passages.words反例；W42替身改为Node真实字段，不能保留扁平durationSeconds/text来适配错误实现。 | 按TC-F3-07组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 2 | 新增W14 collect，固定内部命令、成功state校验、probe正时长、六中点帧、音轨分支；转写样本单位转换和空语音/缺时间的区别按RULE-005～007。 | 按TC-F3-07组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 3 | W13 owner/asset后调用collect，综合步骤消费强类型字段；W21不得把无效时长/缺观察判成功；保持既有公开状态与来源哈希。 | 按TC-F3-07组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 4 | W41对真实Node工具返回结构和字节作断言；W30真实PG/HTTP通过平台模型末跳替身捕获最终事实，检查无声零转写、失败无假分析；补契约W55。 | 按TC-F3-07组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |

**验收**：AC-007：Given12秒有声/无声素材与真实wire，When取证，Then时长/音轨/词时间正确；无声零转写，坏probe/缺词时间不可假SUCCEEDED。

测试 TC-F3-07-01～03，命令 V-07。适用异常/完整输入见§12.2；每个关键负向必须证明目标保护被触发。UI N/A（本卡不改页面）；保留相邻既有测试及已定义响应。

### 卡 C107F3-08：真实图像输入与有界资源读取

**目标/输入/输出**：REQ-008；MODEL实际收到图片字节，A03反例转绿；不公开资源，不新建模型通道。 输入=C107F3-07 collect合同；输出=W13 W14 W29 W30 W40 W41 W42 W55 W62所列符号与V-08本次报告，后置卡按§10消费。

**必读与边界**：§2当前对应链、§3 MOD-001、RULE-008/009/013、§12.2本卡TC；写集 `W13 W14 W29 W30 W40 W41 W42 W55 W62`。不得触碰黑名单、扩大产品/权限/计费语义；遇未登记必要写入按§13.3。

| 步骤 | 精确动作/符号 | 完成检查点与失败处理 |
|---|---|---|
| 1 | W14 modelParts复用W15.open依次读取本次帧，校验status/MIME/字节上限/图片解码，DataBuffer在成功、错误、取消时全部释放。 | 按TC-F3-08组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 2 | 构造ContentPart.text加ContentPart.image(data URI)，顺序对应时间锚点；W13通过FrozenTextExecutionService.executeIndependentPrepared调用，准备回调将runId并入本任务可追踪证据；平台底座不改。 | 按TC-F3-08组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 3 | W62提示只允许引用提供的观察/台词/时间，缺证据留gap；W13解析不得自动补“综合模型分段”假证据。 | 按TC-F3-08组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 4 | W40新增严格fixture分支：实际解码收到的帧，不只检查image_url键；缺图/坏base64/上下文错返回422；不同帧输入返回不同分段。旧测试家族保持。 | 按TC-F3-08组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 5 | 运行V-08：HTTP末跳抓取实际多模态消息，超预算/读错在模型前失败，日志无原始图片和密钥；再跑media与C16回归。 | 按TC-F3-08组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |

**验收**：AC-008：Given真实帧与内部认证读取，When走平台分析适配器，Then模型请求的图片可解码且sha与帧一致；缺图/超限/取消不伪成功也不泄漏资源。

测试 TC-F3-08-01～03，命令 V-08。适用异常/完整输入见§12.2；每个关键负向必须证明目标保护被触发。UI N/A（本卡不改页面）；保留相邻既有测试及已定义响应。

### 卡 C107F3-09：Agent归档工程归属闸

**目标/输入/输出**：REQ-009；A01反例转绿，复用W65内部归档，保持归档幂等与原owner。 输入=C107F3-13工具入口；输出=W16 W31所列符号与V-09本次报告，后置卡按§10消费。

**必读与边界**：§2当前对应链、§3 RULE-004/011、§12.2本卡TC；写集 `W16 W31`。不得触碰黑名单、扩大产品/权限/计费语义；遇未登记必要写入按§13.3。

| 步骤 | 精确动作/符号 | 完成检查点与失败处理 |
|---|---|---|
| 1 | 在工具handler内部先验证ToolCall.accountId拥有有效project，再查output/build并比较project；错误延迟到subscription，验证失败不急切调用archive。 | 按TC-F3-09组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 2 | 沿已有HypitProjectRepository注入owner查询，W31通过测试配置装配；当前已检索的旧测试仅mock该registry，无直接构造调用。W20保留旧构造/方法委托，W71验证兼容，不把补齐已知调用方留到实施时。 | 按TC-F3-09组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 3 | 未知/外工程/删除项目统一ok=false与hypit_not_found、scopeRefusal=true；不返回名称/mediaId。合法归档复用原服务，不改变自动归档签名或存储owner。 | 按TC-F3-09组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 4 | 真PG分别造A/B输出与已归档/待归档，捕获归档调用和返回，负向0调用；同合法输出重复不增media/归档，原build.status隔离继续通过。 | 按TC-F3-09组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |

**验收**：AC-009：Given当前A工程与B输出，Whenauthor工具archive，Then统一拒绝且归档0调用/无元数据；A合法输出重放返回同一归档。

测试 TC-F3-09-01～03，命令 V-09。适用异常/完整输入见§12.2；每个关键负向必须证明目标保护被触发。UI N/A（本卡不改页面）；保留相邻既有测试及已定义响应。

### 卡 C107F3-10：可信再生成快照与前后端恢复

**目标/输入/输出**：REQ-010；A05调用链闭合；保持既有author入口及生成授权，只补可信上下文和方案持久。 输入=C107F3-07/08取证、09权限；输出=W17 W18 W19 W20 W22 W23 W24 W25 W26 W27 W28 W32 W39 W40 W51 W55 W56 W57 W71所列符号与V-10本次报告，后置卡按§10消费。

**必读与边界**：§2当前对应链、§3 API-002/MOD-002、RULE-010～013、§7、UI-01～04、§12.2本卡TC；写集 `W17 W18 W19 W20 W22 W23 W24 W25 W26 W27 W28 W32 W39 W40 W51 W55 W56 W57 W71`。不得触碰黑名单、扩大产品/权限/计费语义；遇未登记必要写入按§13.3。

| 步骤 | 精确动作/符号 | 完成检查点与失败处理 |
|---|---|---|
| 1 | 落实API-002：DTO字段与create重载，旧签名委托false；true组合验证与幂等重放必须在再次选择latest之前，禁止先ensureInitialRevision造成无效请求副作用。 | 按TC-F3-10组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 2 | W23从本人项目分析job引用解析可信完整analysis，核对ready素材sha/head，冻结MOD-002到checkpoint；不按全库sha共享分析。 | 按TC-F3-10组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 3 | W19/W20将快照分析/素材作为真实planner观察；新模式缺快照停waiting_input；作者validated写回后用稳定requestId持久clone.plan来源/revision，最后terminal。单步已成功时恢复读收据，所有新checkpoint写带lease fencing。 | 按TC-F3-10组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 4 | W25新标志和稳定本次请求ID、onTerminal回调/generation gate；W24只短事件装配，终态重读而非202后马上refresh；W26 UI按§8；源码与方案旧值在失败时保留。 | 按TC-F3-10组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 5 | W32验证同key重放、改变latest后旧key仍同快照、并发head变化拒绝、重启不重写、取消不新增build、旧job兼容；W27/W28验证所有旧回调失效，W39验证页面实际按钮链。 | 按TC-F3-10组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |

**验收**：AC-010：Given A已有可信分析且head匹配，When重新生成并重试/刷新，Then同job/同冻结来源被作者和方案消费；新revision及方案可重读，旧回调不改B，冲突不覆盖。

测试 TC-F3-10-01～04，命令 V-10。适用异常/完整输入见§12.2；每个关键负向必须证明目标保护被触发。UI检查按§8.8，保留路由、旧生成授权及本工程历史结果。

### 卡 C107F3-11：Studio保存与跨owner移植真实闭环

**目标/输入/输出**：REQ-011；把历史只打开/ready的测试补成任务书原要求；发现已登记桥内缺陷可按原CAS/权限契约最小修复。 输入=C107F3-10上下文链、03runner；输出=W33 W34 W35 W36 W37 W38 W39 W40 W61 W63所列符号与V-11本次报告，后置卡按§10消费。

**必读与边界**：§2当前对应链、§3 §4.3、§8、RULE-004/011、§12.2本卡TC；写集 `W33 W34 W35 W36 W37 W38 W39 W40 W61 W63（§13.3增量：W73 W74 W75 W76 W77 W78 W79 W80 W81 W82）`。不得触碰黑名单、扩大产品/权限/计费语义；遇未登记必要写入按§13.3。

§13.3增量登记（C107F3-11实施时补记）：**冻结upstream studio UI部署态运行时URL缺陷（条件修改W73/W74）**。实证：冻结源内全部运行时API与资源URL构造点为根相对字符串字面量`/__studio/*`——`src/ui/main.ts`（会话加载，模块顶层await）、`src/ui/i18n.ts`（locales）、`src/ui/code.ts`（PUT source保存）、`src/ui/writeback.ts`（mutation）、`src/ui/comments.ts`×2、`src/ui/library.ts`×3（含`/__studio/artifact?`href与`/__studio/library`fetch）、`src/ui/artifact-preview.ts`（artifact URL）、`src/ui/material-preview.ts`×3（material/storyboard/surface-preview构造）共13处。部署拓扑下`deploy/hypit/nginx.locations.conf`只反代`^/studio/(?<sid>[A-Za-z0-9\-]+)/`，根相对`/__studio/*`落入根nginx.conf server 82的`location /`SPA回退（GET返回ai.html、PUT 405），永远到不了broker代理面；broker侧`planProxy`也只裁决会话前缀（本卡W63首个用例已实证根相对请求直接越界拒绝）。补丁0001只修了服务端（vite base挂载+服务端`/__studio`前缀剥离中间件），UI运行时fetch字符串未随base重写；`main.ts`顶层`fetch("/__studio/session")`在部署态解析回退HTML使JSON解析抛错、模块求值中止，整棵studio UI不渲染——fix2 journey TC-F2-37-02只断iframe body存在故未暴露，C11的TC-F3-11-01真实控件编辑保存在部署态必然失败。修复经既有补丁系统（W73补丁文件+W74 manifest登记，不改冻结upstream工作树本身、不改0000～0004语义）：新增`packages/studio/src/ui/studio-api.ts`导出`studioUrl(path)`（按`import.meta.env.BASE_URL`——部署挂载态=HYPIT_STUDIO_BASE_PATH=`/studio/<sid>/`、独立运行态`/`返回原样——拼接），13处构造点统一改经`studioUrl()`。不改鉴权/写回/CAS/权限语义：请求仍走nginx auth_request（Cookie身份+30秒断言）→broker代理面拦截→Java changeset/apply原链；不改`server.ts`/`start.ts`（0001/0002服务端面保持原样）。fix2历史证据与既有测试断言不变（TC-F2-37-02原断言仍通过）；G source digest与镜像内G随补丁链变化由既有patch-replay.test与V-05/V-15如实重验。受影响TC即本卡TC-F3-11-01～03（真实控件编辑/移植再编辑/视觉会话链全部依赖studio UI部署态可用）。同增量同步W48行注记与该测试的§9.1全量清单计数72→74（负例71→73，沿用W72增量先例），并修复该测试另一处**静态负例过期性**假红（与W73/W74无因果但同轮暴露）：「注入VERIFIED于无证据卡必须被拒绝」的负例在出书时硬编码卡'01'/'10'——C01的variant exit0 run（fix3-20261002T073207Z-1a48，TC-F2-26-01～04+TC-F3-01-01～03全发现）与C10的context exit0 run真实存在后，把'01'/'10'标VERIFIED是「合法有证据」而非应被拒绝的注入（上轮tooling 11:09复验run fix3-20261002T110937Z-5a79已实录该用例假红、exit1，属前序卡遗留红非本卡造成）；修复为运行时选取真实无证据卡+合成卡'99'退化断言，保护语义（无证据的VERIFIED必拒）不变不放宽。

§13.3增量登记二（C107F3-11实施时补记，属W73/W74同一增量的扩展）：**studio子进程localization/feedback插件中间件的base前缀剥离缺口（条件修改）**。实证（2026-10-03 chromium定向冒烟，TC-F3-11-01空白iframe 180s超时后对活容器child直探）：带会话前缀请求`GET /studio/<sid>/__studio/locales`在child上404（vite base报错页），无前缀`GET /__studio/locales`返回200语言包JSON；同child上0001已覆盖的`server.ts`端点`/studio/<sid>/__studio/session`带前缀200——证明剥离缺口仅在0001未覆盖的两个插件中间件。根因：`start.ts:165`插件注册顺序`[distributionImports, studioLocalizationPlugin, studioFeedbackPlugin, studioPlugin]`，前两者的configureServer中间件先于studioPlugin的剥前缀中间件安装，收到仍带`/studio/<sid>`前缀的原始URL做`pathname === "/__studio/locales|feedback"`精确匹配→不中→next()落入vite base面404。locales是i18n引导必需（`main.ts:23`顶层`await initializeI18n()`，`i18n.ts`的`!response.ok`即throw无降级）→模块求值中止、iframe空白。补丁0005（W73）修正UI侧URL前缀化后，该断链才首次可达并暴露——fix2的studio e2e（hypit-studio.spec.ts）无任何frameLocator/iframe UI断言（API面测试）、journey只断iframe body存在，故历史上从未暴露。修复：照0001在`server.ts:479-482`的同一惯例，在`localization-node.ts`与`feedback-server.ts`两中间件入口补`HYPIT_STUDIO_BASE_PATH`剥前缀（env未设时完全惰性——该env仅broker按会话注入`launcher.ts:54`，独立运行/无studio会话场景零行为变化）；不动`allowsStudioMutation`等既有同源/CSRF鉴权门、不动写回/CAS语义（写面在broker代理即被拦截，不到child）。throwaway构建`patch verification OK (6 patch(es))`、新G source digest `b1701c99…`由patch-replay/V-05如实重验。

§13.3增量登记三（C107F3-11实施时补记，属W73实现修正）：**studio-api.ts的base拼接双斜杠缺陷（W73自身实现bug，非upstream缺陷）**。实证（全量studio响应轨迹+IFRAME_DUMP：`test-artifacts/task-107/fix3/local-smoke/journey/TC-F3-11-01-studio-trace.log`）：i18n locales请求实际发出`GET /studio/<sid>//__studio/locales`（**双斜杠**）→broker代理白名单`asset.startsWith("/__studio")`不认`//`前缀→400，唯一失败请求、外壳`<div id="app">`为空。根因：0005初版`studioUrl()`保留了BASE_URL尾斜杠再拼首斜杠path。修复：apiBase去尾斜杠（`replace(/\/+$/, "")`；独立态`/`→空串不变），全部13处调用点参数均为首斜杠根相对路径，join后单斜杠正确。补丁0005的studio-api.ts hunk与manifest afterSha256同步再生（patch sha `0442f57f…`），throwaway构建`patch verification OK`；G source digest不含该新增文件（digest仅覆盖U追踪集，build-107-engine.sh:103-120）——其完整性由patch verification的after-sha校验与V-05/V-11如实保证。前两轮冒烟所见中间件404/剥离缺口与本次双斜杠为同一故障链的两段：中间件修复后URL才真正到达代理面并暴露join缺陷。

§13.3增量登记四（C107F3-11实施时补记）：**变体重试链死锁（条件修改W75/W76）**。实证（chromium定向冒烟 TC-F3-11-03 第四腿：variant-1 build→cancel→retry 后 `data-state=queued` 900s 不收敛；变体0同批正常 succeeded 排除环境因素）：服务端 `POST /variants/{id}/retry`（`HypitVariantService.retryVariant`→`HypitVariantRepository.retry`）只复位行为 `state='queued', attempt+1, plan_id=NULL, build_id=NULL`（响应形态由 C107F3-01 的 TC-F3-01-01 锁定，不可改动），而 worker 唯一认领面 `listActive` 要求 `build_id IS NOT NULL`（`HypitVariantRepository.java:122-127`），build 端点又要求 plan（`buildVariant` 对 plan_id=null 409「变体尚未 plan」）→重试后的行无任何代码路径再推进，重试按钮成为死链。修复按 C09 契约最小补全（grant 仍由调用方持有，服务端不自作收费主张）：①W75 `planVariant` 状态闸放行「queued 且 plan_id=null」的重试行重新规划（plan 在 grant 之前、免费；queued 且已持 plan 的在途行仍不重规划）；②W76 UI `retryItem` 在 retry 落定后续走既有 `startBuild`（plan→pricing→grant→submit），attempt 已进 requestId 键故与上一撞不复用，remote need 场景仍走既有 awaiting-grant 确认面。不动 retry 响应契约、cancel 语义、成功项拒绝重跑闸、worker 认领 SQL。W48 §9.1 全量清单断言同步 74→76（负例 73→75）。闸放行+UI 续走后的冒烟又暴露 build 端点 502「变体计划未返回」两轮 15s 间隔确定性复现——其根因定位与修正见登记六（W78）。

§13.3增量登记五（C107F3-11实施时补记）：**结果面板装载路径不生成下载签名（条件修改W77）**。实证（chromium定向冒烟 TC-F3-11-02 重查腿：A 历史工程重访后 final 行恒「下载签名中…」，`/api/media/` 签名请求 **0 次**（下载 helper 的响应观测器实录，含整页重载后仍 0））：`useHypitResults.refresh()`（面板装载唯一路径）拉取 outputs 后从不调用 `enrichDownloadUrls`，该函数只有 `selectBuild`（用户手动重选 Build）与归档成功重读两条触发路径——重访历史工程时已归档行的短时签名链接没有任何生成时机，fail-soft 吞错语义使其静默永卡。修复：refresh 的 outputs 就绪分支补 `void enrichDownloadUrls(first.id)`（与 selectBuild 同一行形态），/api/media 契约与 fail-soft 语义不变；首次归档链（leg①）不受影响。W48 §9.1 全量清单断言同步 76→77（负例 75→76）。

§13.3增量登记六（C107F3-11实施时补记，登记四 502 的根因修正，条件修改W78）：**markQueued CAS 不认 retry 复位行 → planVariant 502「变体计划未返回」**。实证（smoke12/13 chromium 定向冒烟：W75 闸+W76 续走生效后 retry 200→UI plan 200→pricing 200，build 端点 502 两轮 15s 间隔确定性复现，非瞬态竞态）：首轮诊断归因「sidecar 命令 60s block 预算超时返回空」并在 W75 加了 plans.plan 空返回单次重试——**该诊断为误诊**：`HypitSidecarClient.command()` 的 `block(Duration.ofSeconds(60))`（HypitSidecarClient.java:83）超时是抛 IllegalStateException（error 路径，非空 Mono），且 broker 命令端点（server.ts:392-412）恒返回 JSON body；502 的空 Mono 唯一来源是 `HypitVariantRepository.markQueued` 的 CAS `WHERE state IN ('draft','planned')` 不认 retry 复位后的 queued 态→UPDATE 0 行→`.one()` 空→planVariant switchIfEmpty 502。修复：W78 markQueued WHERE 放行 `state='queued' AND plan_id IS NULL` 重试形态（已持 plan_id 的在途 queued 行仍不重复绑定，幂等闸保持）；W75 同笔删除误诊版空返回重试（死代码）、保留 Logger 声明与状态闸。W48 §9.1 全量清单断言同步 77→78（负例 76→77）。

§13.3增量登记七（C107F3-11实施时补记，V-11 门禁首跑暴露）：**再生成按钮被分析忙碌全闸禁用，UI-03 前置拒绝面不可达（条件修改W79）**。实证（V-11 run fix3-20261003T045205Z-6b5a attempt-1 chromium：TC-F3-10-04 在 217 行点击参考分析后，210 行 `toBeEnabled(clone-plan-regenerate)` 60s 超时，快照按钮 `disabled`）：装配 `VideoCloneWorkbench.vue:675` 把 `workflow.busy` 直接接进面板 `generating`，而 busy 的相位集合含 `analyzing`（useHypitWorkflow.ts:71 `phase !== 'idle' && phase !== 'awaiting-grant'`）→ 分析在途时「重新生成方案」被 UI 静默禁用；§8 UI-03 的设计语义是分析在途放行点击、由服务端 409（无 SUCCEEDED 可信分析）映射第一句可行动文案「请先完成参考视频分析」——UI 禁用使该前置拒绝面永远不可达（用户也看不到原因）。该缺陷属 C10 交付的装配语义与其 e2e（TC-F3-10-04）冲突，C10 时 e2e NOT_RUN（留 V-15），本卡门禁全 spec 首跑才合体暴露。修复：ClonePlanPanel 加可选 prop `regenerateGate`（缺省 undefined=沿用旧 generating 语义，W28 vitest 全绿不破坏、其他调用方零变化），装配传 `busy && phase !== 'analyzing'`；「按方案生成」按钮的 generating 全闸保护不变。W48 §9.1 全量清单断言同步 78→79（负例 77→78）。

§13.3增量登记八（C107F3-11实施时补记，V-11 门禁 TC-F2-37-01 失败的根因，条件修改W80）：**insertPlan 内容寻址幂等跨 revision 命中旧行 × requirePlanFresh revision 闸 = 同内容永久 409 死锁**。实证（门禁 run fix3-20261003T045205Z-6b5a attempt-1 chromium：37-01 点「按方案生成」后等 build 行「已完成」900s 超时，录像 480/600/880s 三帧页面空闲、快照停在复刻方案页签无任何错误；本地 `--trace on` 定向复现（16.3m 同点同形态）的 trace 网络链：POST check 200→POST plan 200→POST pricing 200→**POST builds 409** `{"error":"工程 revision 已前进（计划 2，当前 3），重新 plan","code":"hypit_plan_stale"}`；UI submit 抛错→error 置位→phase idle 而复刻方案面板无生成错误显示位→静默回「按方案生成」可点）。根因链：①author 任务（C10 再生成写回）在工程 rev 2 时持久 plan 行（planHash H）；②用户编辑保存 AUTHORED_SVML（与 author 产物同源、内容不变）→工程 rev 2→3；③点生成→服务端 plan 端点按**服务端当前 revision** 快照规划（UI 的 requestId 键不参与服务端寻址）→broker planHash 内容寻址仍=H→`insertPlan` 的 `WHERE NOT EXISTS (project_id AND plan_hash)` 命中 rev 2 旧行读回；④pricing 消费该行→builds `requirePlanFresh`：plan.revision(2)≠project.revision(3)→409 要求「重新 plan」；⑤用户/测试重点生成走同链同 hash **永远命中旧行**——产品级死锁（非测试时序问题；fix2 时无 author 写回链故 104s 绿，37-04 绿因其 NEW-B 编辑改变了内容→planHash 变化→新建行）。修复：幂等键收窄为 `(project_id, plan_hash, revision)`——同内容同 revision 幂等语义、计划行不可变（绝不 UPDATE 旧行）、requirePlanFresh revision 门、C107F2-06「计划绑定冻结 revision」全部保留；跨 revision 同内容产生新冻结行使「重新 plan」的用户动作真正可完成。HypitPlanIT 契约同步：类 javadoc 语义更新+新增跨 revision 用例（同 hash rev3→工程 rev4→rev4 插入=新行且旧行保留、requirePlanFresh 过新行）+168 行过时注释更正；既有三用例断言不变。W48 §9.1 全量清单断言同步 79→80（负例 78→79）。

§13.3增量登记九（C107F3-11实施时补记，V-11 门禁 TC-F2-37-01 在 W80 修复后仍失败的真因，条件修改W81）：**前端 `open()` 迟到解析回踩在途保存链 → 保存按钮中途复活 → 生成在 revision 推进前 plan → builds 409**。实证（本地 `--trace on` 定向复现，毫秒级锚定 trace 网络+动作双时间线：generate click→check POST 14:41:38.750 锚定后——.540 spec 点 main.svml→open() 发 GET file（130ms 在途）；.552 fill AUTHORED_SVML；.571 点保存→save() 冻结内容+saveState=saving（按钮禁用）+baseHash 空补读基线；**.672 open() 迟到解析→saveState=idle+草稿被服务器旧内容覆盖（保存按钮复活）**；.700 toBeEnabled 通过（106ms 重试后）；.722 changesets POST；.740 生成链 plan 读库**读到 revision=2**（apply 事务 14:41:43.5 才提交 rev3，apply 202 回执 revision=3 实证）；14:41:50 plan 插入 rev2 新行（行 createdAt 实证）；14:41:52 builds 409「计划 2，当前 3」）。即 W80 修复本身工作正常（该 rev2 行是 plan 读旧 revision 后**新插入**的，非幂等命中旧行）——真实缺陷在前端：`useHypitSource.ts` 的 `edit()` 已对迟到 debounce 回写做防护（仅允许在 idle/dirty 上重申 dirty），但 `open()` 的迟到解析无同类防护，无条件置 idle。真用户同样可踩（打开文件→输入→保存→保存中再动页面）。修复：savingPath 追踪在途保存目标文件（save() 发起置位、finally/reset() 解除）；open() 迟到解析遇在途保存：同文件仅刷新 CAS 基线（savedContent/baseHash/revision），草稿与 saveState 由保存链收敛（成功后 fresh 重读+草稿同步，:134-145 原语义）；跨文件打开换草稿但 saveState 保持在途；不在途时行为与原实现逐分支等价（normal open/冲突基线刷新）。反例先行：`useHypitSource.test.ts` 新增 TC-F2-37-01 编辑保存段两用例（open 迟到解析→断言 saveState 仍 saving+草稿保留+链终态收敛；跨文件 open→换草稿+保持在途），修复前双 RED（实得 idle/草稿被覆盖）、修复后 5/5 GREEN；既有三用例断言零改动。同目录 fix2-c27 两例超时为本工作树既有状态（stash W81 后复跑同败，属他卡在途 useHypitVariants.ts，本卡不碰）。W48 §9.1 全量清单断言同步 80→81（负例 79→80）。

§13.3增量登记十（C107F3-11实施时补记，smoke34 复验 W81 后仅剩 console 卫生断言失败的实证缺陷，条件修改W82）：**工作台 fire-and-forget 调用丢弃 rethrow promise → 浮动拒绝 pageError**。smoke34（W81 修复后 37-01 定向 chromium --trace on）：旅程 1.4m 全链贯通（上传→分析→方案→编辑→**生成→归档→下载→ffprobe 魔数全过**——W81 生效，此前 16.3m 卡 builds 409），仅 console 泄漏断言挂 2 条：①「Failed to load resource 409」=agent-jobs（author 再生成在分析未完成窗口的 UI-03 预期前置拒绝，HypitAuthorContextService.noTrustedAnalysis；spec 收敛循环首点落入该窗口是预期动线，W38 benign 清单按 URL+状态精确补登）；②「GrasslandHttpError: 本工程没有可信的完整参考分析…」=pageError，trace 栈实证 `at async Object.E [as regeneratePlan]`——startRegenerate `void workflow.regeneratePlan(...)` 丢弃 promise 而该 composable 落态后 rethrow（:172，既有契约），浮动拒绝打脏控制台（真用户同踩：devtools 红 uncaught error）。修复（W82）：startRegenerate/startGenerate 两处 `.catch(() => undefined)` 收口（状态已由 workflow 内段落，rethrow 契约与 composable 测试断言不变）；startGenerate 同型预防（@generate 处理器不消费返回值，generate :242 rethrow）。VideoCloneWorkbench.vue 785→793 行（800 硬顶内）。W48 §9.1 全量清单断言同步 81→82（负例 80→81）。

§13.3增量登记十一（C107F3-11实施时补记，三引擎全量冒烟实证的跨引擎 console 噪声面；均在 W38/W39 既有文件内修正，无新增 W）：V-11 为三引擎串行门禁而官方门禁 run 此前从未跑过 firefox/webkit（chromium 失败即停）。本轮本地全量冒烟（/tmp 守卫入口副本，与 ci-e2e-107 同 env 同 spec 集）：chromium 11/11（smoke39）；firefox 首发 3 挂（smoke40，37-02/37-04/11-03——全部且仅为 console 卫生断言，功能链全绿）；webkit 首发 3 挂（smoke43，同三用例，泄漏行 44 条 100% 为同一面）。根因：**Firefox/WebKit 会把 nginx 部署面观察性 CSP Report-Only 策略（nginx.conf:57 $csp_policy_report_only——CSP_MODE 缺省姿态，Studio srcdoc 内联脚本被上报而不阻断）逐条打成 console error**，两引擎文案不同（Firefox「Content-Security-Policy: (Report-Only policy) …」/WebKit「[Report Only] Refused to execute …」），chromium 不上报。修正（W38/W39 consoleLeaks 过滤器）：只豁免带 Report-Only 标记的行——enforced 策略的阻断告警（无该标记）仍按泄漏失败；W39 的私有内容扫描（ticket=/nonce/assertion）同样排除该面（Firefox 建议文本含「or a nonce」、WebKit 文案含 hash/nonce 字样，会误中——smoke41 实证）。复验：firefox 10/11+11-03 定向绿（smoke41/42）、webkit 11/11（smoke44）。另 W38 benign 清单补登 agent-jobs 409（author 再生成在分析未完成窗口的 UI-03 预期前置拒绝面，按 URL+状态精确豁免）；W39 TC-F3-10-04 收敛判据修正两处（受理=POST 结果落定后仍 running，非 toBeEnabled/非点击瞬禁用——status=running 在 POST 前同步置位，任何点击后 ~50ms 内按钮都禁用，409 要等回包才翻回，smoke36/37 双实证）。

| 步骤 | 精确动作/符号 | 完成检查点与失败处理 |
|---|---|---|
| 1 | W38 TC-F2-37-02使用真实iframe编辑控件，把固定标识OLD改NEW并保存；捕获既有writeback请求、PG revision和文件hash，刷新/关闭重开仍NEW；不能用API代替被测保存按钮。 | 按TC-F3-11组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 2 | W63补bridge readOnly/CAS/重放与失败输入保留；若真实用例暴露W33～37/W61既有链问题，修该链而不旁路Java写文件。 | 按TC-F3-11组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 3 | W38 TC-F2-37-04 A导出→B UI上传→B新工程→真实编辑→保存→构建→下载→ffprobe/全解码；比对A历史源码/MP4不变，B归属/version不同，保留二次样片。 | 按TC-F3-11组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 4 | 所有本次证据根支持HYPIT_FIX3_EVIDENCE_ROOT；W39覆盖Edge retry/cancel穿透和受控输入差异；三浏览器串行、双主题移动/键盘实际查看，不把body存在当成功。 | 按TC-F3-11组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |

**验收**：AC-011：Given现有工程与两账号，WhenStudio保存重开及A导出B导入再编辑渲染，Then新revision/hash持久、B成片可解码、A源数据不变、越权和CAS仍拒绝。

测试 TC-F3-11-01～03，命令 V-11。适用异常/完整输入见§12.2；每个关键负向必须证明目标保护被触发。UI检查按§8.8，保留路由、旧生成授权及本工程历史结果。

### 卡 C107F3-12：LIVE入口fail-closed与状态分层

**目标/输入/输出**：REQ-012；A07反例转绿；本卡修验证语义，不实施真实付费模型调用。 输入=C107F3-13报告协议；输出=W46 W47所列符号与V-12本次报告，后置卡按§10消费。

**必读与边界**：§2当前对应链、§3 RULE-016、§12.2本卡TC；写集 `W46 W47`。不得触碰黑名单、扩大产品/权限/计费语义；遇未登记必要写入按§13.3。

| 步骤 | 精确动作/符号 | 完成检查点与失败处理 |
|---|---|---|
| 1 | 修--catalog参数解析，输出根可由HYPIT_LIVE_ARTIFACT_DIR覆盖到新run；默认未授权写NOT_ENABLED且exit2；不触碰旧C24文件。 | 按TC-F3-12组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 2 | 读取目录形状必须非空、条目名称合法；缺目录/空provider/缺base/无支持探测实现/异常均非零，逐条记录原因；未知项目不得静默跳过。 | 按TC-F3-12组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 3 | 若显式配置本地/既有探测地址，HTTP200只记PROBE_PASS、成本未知null，最终LIVE仍UNVERIFIED非零；本书不宣称该probe产品端点已存在，也不新增收费调用。只有未来获授权真实链证据才能另行实现LIVE_PASS。 | 按TC-F3-12组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 4 | 隔离副本运行空目录、缺base、HTTP失败、全部200但无成片/账务、未授权；保留每项状态，断言无商业请求，不能以预算数字打印作为预算闸证据。 | 按TC-F3-12组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |

**验收**：AC-012：Given无授权/空项/未验证/只有HTTP200，When运行LIVE入口，Then非零且准确分层，绝无LIVE_PASS；本地任务不因此伪造商用完成。

测试 TC-F3-12-01～03，命令 V-12。适用异常/完整输入见§12.2；每个关键负向必须证明目标保护被触发。UI N/A（本卡不改页面）；保留相邻既有测试及已定义响应。

### 卡 C107F3-05：历史证据、声明与状态一致性修正

**目标/输入/输出**：REQ-005；保留原稿数字/路径/W归属修复清单，以本次事实完成；不能让旧40/40声明掩盖新的未验层。 输入=C107F3-01～04、07～12当前卡证据；输出=W02 W08 W58 W59 W60所列符号与V-13本次报告，后置卡按§10消费。

**必读与边界**：§2当前对应链、§3 D-04～07、RULE-001～003、§12.2本卡TC；写集 `W02 W08 W58 W59 W60`。不得触碰黑名单、扩大产品/权限/计费语义；遇未登记必要写入按§13.3。

| 步骤 | 精确动作/符号 | 完成检查点与失败处理 |
|---|---|---|
| 1 | 逐精确文件按D-05映射，保留真实短名证据；皆不存在标MISSING并定位负责TC重跑，不用justified清单把必需缺证据免除。映射表是机器证据，不另写默认完成报告。 | 按TC-F3-05组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 2 | 逐项核C10三图/亮冲突缺口、C15回归数、C22 media、C23 video-clone、C24 studio、C37 ffprobe与收口产物；以实际运行和文件为准，不硬改成预设8/48/21。历史缺图如实标记，本次UI证据另指新run。 | 按TC-F3-05组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 3 | 核C20 W150/C31 W194/C02 W010/W013未使用声明；C32 W170/W171、maintenance测试登记；C35 event-consumers与lifecycle-inventory归属；C38 W203。查git证据再写，不编造原提交归属。 | 按TC-F3-05组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 4 | 保留fix2基线版本1.0.0与旧修订，新增1.0.7复核说明：历史通过范围/新增反例/本书修复与重新验收，不改旧results。W02登记fix3各卡与新run，受影响fix2声明只在对应补验通过后复核，不把所有原卡自动提升。 | 按TC-F3-05组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 5 | 同步W08/W58/W59/W60的当前结论：未完成最终集成时明确本次复核未通过最终出口，历史结论加时间/范围；C06通过后再整体推进。本轮不修改数字人状态。 | 按TC-F3-05组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |

**验收**：AC-005：Given原声明/路径与本次证据，When逐项修正，Then每个当前通过判断有真实可定位证据；历史字节不变，缺证据不被文案抹平。

测试 TC-F3-05-01～03，命令 V-13。适用异常/完整输入见§12.2；每个关键负向必须证明目标保护被触发。UI N/A（本卡不改页面）；保留相邻既有测试及已定义响应。

### 卡 C107F3-06：最终回归、本地集成与索引交付

**目标/输入/输出**：REQ-006；最后责任人完成§12.5，不以静态门禁代替真实用户闭环。 输入=除本卡外12卡均VERIFIED；输出=W02 W08 W09 W10 W11 W39 W48 W52 W58 W59 W60所列符号与V-14 V-15本次报告，后置卡按§10消费。

**必读与边界**：§2当前对应链、§3 §12.5全部Gate、§12.2本卡TC；写集 `W02 W08 W09 W10 W11 W39 W48 W52 W58 W59 W60`。不得触碰黑名单、扩大产品/权限/计费语义；遇未登记必要写入按§13.3。

| 步骤 | 精确动作/符号 | 完成检查点与失败处理 |
|---|---|---|
| 1 | 安全同步W09对已删除108的引用为已存在105-fix-2任务书；检查README现有在途行不变。仅断言对象更新，保留他人工作/索引保护意图。 | 按TC-F3-06组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 2 | 按影响执行V-14质量/契约/类型/生命周期及Java/Hypit回归；核对每个报告的run/source身份。共享模块失败先定位本次影响，不能用mock代替必需层。 | 按TC-F3-06组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 3 | V-15三浏览器真实参考→分析→再生成→原生MP4，Studio保存、移植二次渲染、Edge变体、取消/重启/CAS；仅最后外部模型替身，服务链/PG/字节真实。 | 按TC-F3-06组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 4 | 从REQ反查TC/产物，重放门禁负例，确认新run没有skip/0/缺引擎；检查本次diff与旧文件保护/资源清理。 | 按TC-F3-06组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |
| 5 | 只有§12.5全通过后更新本书/索引/状态的本地VERIFIED；LIVE/生产仍NOT_RUN。仅对话按§14汇报，不另生默认报告。 | 按TC-F3-06组核查；编码/测试失败本卡修复，实质边界变化按§13，不降低断言 |

**验收**：AC-006：Given所有卡实现与本次真实证据，When执行最终回归/闭环/反向门禁，Then全部必需层PASS、产物可用、无P1缺口和资源遗留；否则整任务不得VERIFIED。

测试 TC-F3-06-01～03，命令 V-14 V-15。适用异常/完整输入见§12.2；每个关键负向必须证明目标保护被触发。UI检查按§8.8，保留路由、旧生成授权及本工程历史结果。

## 12. 测试、验证命令与集成验收

### 12.1 追踪与实施状态

§1.3的REQ与§11的AC一一对应。下表为唯一TC索引；各TC继承对应卡的RULE/W范围，证据由V命令写入§9.3的当前run。全部实施验收当前为 **NOT_RUN**；规划静态核验见附B，不属于业务通过。

| 卡/REQ/AC尾号 | TC尾号（前缀TC-F3-卡号） | V | 集成出口 |
|---|---|---|---|
| 01 | 01、02、03 | V-03 | G2/G5 |
| 02 | 01、02 | V-04 | G3 |
| 03 | 01、02 | V-05 | G3 |
| 04 | 01、02、03、04 | V-06 | G1/G3 |
| 05 | 01、02、03 | V-13 | G7 |
| 06 | 01、02、03 | V-14/V-15 | G1～G7 |
| 07 | 01、02、03 | V-07 | G2/G4 |
| 08 | 01、02、03 | V-08 | G2/G4 |
| 09 | 01、02、03 | V-09 | G2 |
| 10 | 01、02、03、04 | V-10 | G2/G4/G5 |
| 11 | 01、02、03 | V-11 | G5/G6 |
| 12 | 01、02、03 | V-12 | G1 |
| 13 | 01、02、03 | V-01/V-02 | G1/G7 |

### 12.2 用例定义

风险排序采用严重度/发生概率/难检出度各1～3分，乘积越高越先覆盖负向；不改变§10依赖顺序：

| 需求 | 分值/优先级 | 验收深度 |
|---|---|---|
| REQ-009/010/013 | 3×3×3=27 / P1 | 归属、幂等/恢复、伪绿分别独立反例，真实持久化与最终回溯 |
| REQ-001/002/003/007/008/011/012 | 3×2×2=12 / P1 | 成功、拒绝、错误、生命周期及跨层真实输出 |
| REQ-004/005/006 | 2×2×2=8 / P2（最终Gate仍必需） | 全量发现/逐文件核验/证据追踪，最终验收不得省略 |

C06在确定用例后进行一次限时15分钟探索：参考分析/方案连点、A→B→A切换、Studio保存冲突、导入失败重试；只记录可复现问题及命中请求，不为凑数造缺陷。确认的P0/P1未修复不得通过出口；未复现猜测与已确认缺陷分列，按§14对话交接。

共享前提：F-OWN的A=`aaaaaaaa-1073-4000-8000-000000000001`、B=`bbbbbbbb-1073-4000-8000-000000000002`；工程/素材/output/job用固定UUID不同尾号；每例独立数据命名空间，测试结束删除本次测试数据，禁止指向开发库。F-REF具体生成和mock边界见§9.3。身份/owner/PG/业务服务不得mock；仅单元用例可mock明确写出的末端依赖。并发使用latch/barrier、固定时钟；不以任意sleep碰运气。每例记录请求/返回、前后状态摘要、禁止副作用计数；不保存令牌和私有原文。

各TC名必须进入实际测试报告（JUnit DisplayName、node:test名称或Vitest/Playwright名称），不是只写注释。需要跨层的TC用同一编号加层级后缀，按manifest登记全部子项；不能用一个层的通过代替其他层。下列高风险展开中的清理引用均包含§9.3资源收尾与共享数据清理；用例执行证据为当前run的V对应stage下原始报告及产物。基础输入合法，仅改变待测拒绝条件，防止其他错误先拒绝造成假阳性。

#### TC-F3-01-01：变体重试与取消真实HTTP

- 关联与风险：REQ-001 / AC-001；权限、异步或跨层副作用风险；真实对象与测试位置：W01（按§9.1路径）。
- 前提与输入：F-OWN：A的failed变体attempt=1/build非空；另一running变体与active build；retry空体，cancel含固定requestId及reason=fixture。
- 依赖与顺序：真实HTTP依次retry、cancel，再开新事务查询；无Controller/Service替身；仅允许§9.3及本例指定替身。
- 断言：retry=200/queued/attempt2/build_id=null；cancel=200/cancelled且build收敛；原TC-F2-26-01/03/04保留。
- 清理与证据：共享清理＋本例进程/流结束；V-03，当前run原始报告与状态/产物摘要。
- 防止假阳性：删路由应200断言失败；只看非404不合格。

#### TC-F3-01-02：身份与跨工程隔离

- 关联与风险：REQ-001 / AC-001；权限、异步或跨层副作用风险；真实对象与测试位置：W01（按§9.1路径）。
- 前提与输入：同一合法A变体；分别无身份、B身份、A身份+unknown变体。
- 依赖与顺序：三请求分别独立发送，不同时使用非法body；仅允许§9.3及本例指定替身。
- 断言：401、404、404；后两error/code一致，无状态和build改动。
- 清理与证据：共享清理＋本例进程/流结束；V-03，当前run原始报告与状态/产物摘要。
- 防止假阳性：撤掉owner保护会使B请求断言失败。

#### TC-F3-01-03：公共入口及非法状态

- 关联与风险：REQ-001 / AC-001；权限、异步或跨层副作用风险；真实对象与测试位置：W01/W39（按§9.1路径）。
- 前提与输入：A已queued变体再retry；F-OWN合法失败/运行变体经Edge登录Cookie访问。
- 依赖与顺序：先直达HTTP验证409；最终E2E用真实Edge发送合法retry/cancel并观测上游/PG；仅允许§9.3及本例指定替身。
- 断言：非法retry=409无attempt增量；Edge两操作到真实handler、状态与API-001一致。
- 清理与证据：共享清理＋本例进程/流结束；V-03及V-15，当前run原始报告与状态/产物摘要。
- 防止假阳性：仅直连Java不能标Edge通过；旗标关闭必须拒绝。

#### TC-F3-02-01：维护栅栏与排空

- 关联与风险：REQ-002 / AC-002；权限、异步或跨层副作用风险；真实对象与测试位置：W04（按§9.1路径）。
- 前提与输入：F-RUNNER临时broker；先接受一个受控未结束命令，再enter取得lease。
- 依赖与顺序：用屏障保持命令在途；等待进入维护，发送合法plan/resource ingest；释放原操作并等待drained；仅允许§9.3及本例指定替身。
- 断言：新请求503且未产生执行/文件；status与既有取消可用；drained不能提前true。
- 清理与证据：共享清理＋本例进程/流结束；V-04，当前run原始报告与状态/产物摘要。
- 防止假阳性：移除闸后副作用计数非零；不能因坏请求先400而通过。

#### TC-F3-02-02：lease隔离与退出清理

- 关联与风险：REQ-002 / AC-002；权限、异步或跨层副作用风险；真实对象与测试位置：W04（按§9.1路径）。
- 前提与输入：有效lease=L1；退出分别用L2、L1；独占临时目录。
- 依赖与顺序：错误退出后再次写请求；正确退出后重发合法请求；另一次在启动失败处触发cleanup；仅允许§9.3及本例指定替身。
- 断言：L2=409且闸仍在；L1=200恢复；进程/socket结束且不清其他目录。
- 清理与证据：共享清理＋本例进程/流结束；V-04，当前run原始报告与状态/产物摘要。
- 防止假阳性：去掉lease比较或退出等待分别破坏状态/资源断言。

#### TC-F3-03-01：真实runner四类反例

- 关联与风险：REQ-003 / AC-003；权限、异步或跨层副作用风险；真实对象与测试位置：W06/W44（按§9.1路径）。
- 前提与输入：F-RUNNER合法组件、恶意读取合成宿主秘密、语法错误源码、可控超时任务。
- 依赖与顺序：逐项跑真实daemon；超时后waitIdle再提交合法任务；仅允许§9.3及本例指定替身。
- 断言：合法编译可用；恶意marker证明已执行但秘密不可读；坏源码有diagnostics；超时slot回收且下一任务无污染。
- 清理与证据：共享清理＋本例进程/流结束；V-05，当前run原始报告与状态/产物摘要。
- 防止假阳性：替换假runner、只断ENOENT、未执行marker均失败。

#### TC-F3-03-02：engine失败传导

- 关联与风险：REQ-003 / AC-003；权限、异步或跨层副作用风险；真实对象与测试位置：W54（C13创建、此卡复验）（按§9.1路径）。
- 前提与输入：F-SCRIPT：保持其他组成功，仅engine子进程exit1。
- 依赖与顺序：调用真实wrapper隔离副本C32路径；再跑真实全engine；仅允许§9.3及本例指定替身。
- 断言：失败版card非零且engine FAIL；真实版报告列全组与四类用例，skip/todo=0。
- 清理与证据：共享清理＋本例进程/流结束；V-05，当前run原始报告与状态/产物摘要。
- 防止假阳性：删engine分派或吞exit1均由发现/退出码断言拦截。

#### TC-F3-04-01：C32第四项真实lease竞争

- 关联与风险：REQ-004 / AC-004；权限、异步或跨层副作用风险；真实对象与测试位置：W07（按§9.1路径）。
- 前提与输入：真实broker中A持L1，B发相同形状maintenance enter。
- 依赖与顺序：B调用后查维护状态；随后A退出；保留原TC03重启fail-closed；仅允许§9.3及本例指定替身。
- 断言：独立TC-F2-32-04执行；B=409且响应无L1；A仍可退出；TC01～04均发现。
- 清理与证据：共享清理＋本例进程/流结束；V-06，当前run原始报告与状态/产物摘要。
- 防止假阳性：只在注释写TC04不得计数；删隔离断言应失败。

#### TC-F3-07-01：真实probe与音轨分支

- 关联与风险：REQ-007 / AC-007；权限、异步或跨层副作用风险；真实对象与测试位置：W29/W30/W41/W42（按§9.1路径）。
- 前提与输入：F-REF有声与无声各12秒；Node原始nested probe，不给扁平durationSeconds。
- 依赖与顺序：Node真实探针；Java消费相同wire，分别调用collect/analyze；ASR末跳返回规范回执；仅允许§9.3及本例指定替身。
- 断言：duration=12（容器取样误差≤0.05s），音轨true/false正确；六中点1/3/5/7/9/11s；无声转写0次。
- 清理与证据：共享清理＋本例进程/流结束；V-07，当前run原始报告与状态/产物摘要。
- 防止假阳性：旧flat读取产生0秒/ABSENT必须失败。

#### TC-F3-07-02：逐词时间与空语音

- 关联与风险：REQ-007 / AC-007；权限、异步或跨层副作用风险；真实对象与测试位置：W29/W30（按§9.1路径）。
- 前提与输入：有声probe；ASR回执language=zh，passages.words为你好[0,16000)、草场[64000,80000)，16kHz；另例passages=[]且成功证据有效。
- 依赖与顺序：末跳依次回两类已定义回执，真实服务合并；不mock归一化；仅允许§9.3及本例指定替身。
- 断言：词顺序/文本保留，时间0～1及4～5s；空语音是已检测无词；不能读取不存在的顶层text。
- 清理与证据：共享清理＋本例进程/流结束；V-07，当前run原始报告与状态/产物摘要。
- 防止假阳性：回到top-level text解析必丢词而失败。

#### TC-F3-07-03：probe和词边界拒绝

- 关联与风险：REQ-007 / AC-007；权限、异步或跨层副作用风险；真实对象与测试位置：W29/W30（按§9.1路径）。
- 前提与输入：分别duration缺失/0/-1/NaN、hasAudio缺失/null/字符串false、hasVideo=false；另合法probe仅一个词缺startSample。
- 依赖与顺序：每次只变一个字段；NaN用单元对象或非法wire解析负例，不能声称合法JSON支持NaN；仅允许§9.3及本例指定替身。
- 断言：错误不默认0/无声；不产SUCCEEDED分析；缺词时间标缺口/未完整，不伪造0；旧分析不变。
- 清理与证据：共享清理＋本例进程/流结束；V-07，当前run原始报告与状态/产物摘要。
- 防止假阳性：无效probe若仍调模型/持久成功必须失败。

#### TC-F3-08-01：图像真的到达模型

- 关联与风险：REQ-008 / AC-008；权限、异步或跨层副作用风险；真实对象与测试位置：W30/W40/W41（按§9.1路径）。
- 前提与输入：F-REF六帧；同尺寸另一蓝红顺序素材作为对照。
- 依赖与顺序：走W13→W14→FrozenTextExecutionService→本地模型HTTP；末跳解码每张图；仅允许§9.3及本例指定替身。
- 断言：6个image parts真实可解码，时间/sha对应；两输入观察与分段不同；ai_run可关联。
- 清理与证据：共享清理＋本例进程/流结束；V-08，当前run原始报告与状态/产物摘要。
- 防止假阳性：只JSON handles或固定返回同分段不能通过。

#### TC-F3-08-02：帧预算与无效资源

- 关联与风险：REQ-008 / AC-008；权限、异步或跨层副作用风险；真实对象与测试位置：W29/W30（按§9.1路径）。
- 前提与输入：合法6帧；逐例单帧4MiB/4MiB+1、总量24MiB边界、7帧、空列表、坏PNG、text/plain、404、超时、重复句柄/越界时间。
- 依赖与顺序：对字节流使用可追踪buffer；正常边界用有效图片填充；非法项只改目标条件；仅允许§9.3及本例指定替身。
- 断言：≤边界可继续；超限/坏资源模型调用0，释放已分配buffer，无假分析/历史覆盖；总量超限用单元预算累加器测，不能被单帧先拒掩盖。
- 清理与证据：共享清理＋本例进程/流结束；V-08，当前run原始报告与状态/产物摘要。
- 防止假阳性：去掉大小/类型/释放任一保护有对应独立失败。

#### TC-F3-08-03：取消与响应式资源清理

- 关联与风险：REQ-008 / AC-008；权限、异步或跨层副作用风险；真实对象与测试位置：W29/W30（按§9.1路径）。
- 前提与输入：六帧中的第三流在屏障等待，另例第三流抛I/O错误。
- 依赖与顺序：订阅取证后触发取消/错误，释放屏障；记录scheduler和buffer引用计数；仅允许§9.3及本例指定替身。
- 断言：无event-loop阻塞或内部手动subscribe；所有已收buffer释放；不执行后续模型、不持久成功；日志无base64/密钥。
- 清理与证据：共享清理＋本例进程/流结束；V-08，当前run原始报告与状态/产物摘要。
- 防止假阳性：取消后仍读后续帧或泄漏buffer应失败。

#### TC-F3-09-01：归档owner与output归属

- 关联与风险：REQ-009 / AC-009；权限、异步或跨层副作用风险；真实对象与测试位置：W31（按§9.1路径）。
- 前提与输入：F-OWN：A/B各有效project/build/output；ToolCall.jobId合法、accountId=A、projectId=A，input.outputId分别A、B、unknown。
- 依赖与顺序：真PG查owner/output/build；分别dispatch output.archive；内部归档生产服务保持真实，仅sidecar导出/资源HTTP与媒体存储末跳用记录式替身（同output稳定媒体ID），spy记录archive调用；仅允许§9.3及本例指定替身。
- 断言：A合法调用一次；B/unknown均ok=false、hypit_not_found、scopeRefusal=true，归档0调用；不泄漏mediaId。
- 清理与证据：共享清理＋本例进程/流结束；V-09，当前run原始报告与状态/产物摘要。
- 防止假阳性：请求其余字段合法；删output→build→project比较应让B失败。

#### TC-F3-09-02：合法重放与已归档输出

- 关联与风险：REQ-009 / AC-009；权限、异步或跨层副作用风险；真实对象与测试位置：W31（按§9.1路径）。
- 前提与输入：A合法已归档output与未归档output分别测试；固定ToolCall/request标识。
- 依赖与顺序：每项重复dispatch两次，新事务读取归档记录与owner；仅允许§9.3及本例指定替身。
- 断言：同输出复用归档媒体，不新增重复media/归档，owner保持A；自动归档路径兼容。
- 清理与证据：共享清理＋本例进程/流结束；V-09，当前run原始报告与状态/产物摘要。
- 防止假阳性：只mock返回同ID不够，须检查真实PG唯一记录。

#### TC-F3-09-03：删除撤权及旧工具回归

- 关联与风险：REQ-009 / AC-009；权限、异步或跨层副作用风险；真实对象与测试位置：W31（按§9.1路径）。
- 前提与输入：A合法output；分别project删除、accountId=B但projectId=A；旧build.status合法输入。
- 依赖与顺序：在dispatch订阅前屏障删除/切换账号，随后继续；另跑status正反例；仅允许§9.3及本例指定替身。
- 断言：无archive调用或对象写；与unknown拒绝同口径；原build.status隔离保持。
- 清理与证据：共享清理＋本例进程/流结束；V-09，当前run原始报告与状态/产物摘要。
- 防止假阳性：只在装配Mono时检查、订阅时跳过检查会失败。

#### TC-F3-10-01：来源冻结与完整落地

- 关联与风险：REQ-010 / AC-010；权限、异步或跨层副作用风险；真实对象与测试位置：W32/W39（按§9.1路径）。
- 前提与输入：F-OWN A head2、ready源sha=S、有SUCCEEDED分析job J引用analysis X；API-002完整请求。
- 依赖与顺序：真实HTTP受理202，观察checkpoint；worker消费、validated写回、plan持久后终态；新事务/刷新重读；仅允许§9.3及本例指定替身。
- 断言：快照version1/source一致；作者实际请求含分析；plan来源和resultRevision对应新head；202不刷新为成功。
- 清理与证据：共享清理＋本例进程/流结束；V-10，当前run原始报告与状态/产物摘要。
- 防止假阳性：空素材/固定brief旧行为不能满足模型输入和持久化断言。

#### TC-F3-10-02：幂等及旧客户端兼容

- 关联与风险：REQ-010 / AC-010；权限、异步或跨层副作用风险；真实对象与测试位置：W32/W71（按§9.1路径）。
- 前提与输入：同请求ID的API-002；随后新增更新分析Y；同ID另改brief；旧请求省略新字段/null/false。
- 依赖与顺序：先受理X，插Y后重放原请求，再变brief重放；旧模式各独立requestId；仅允许§9.3及本例指定替身。
- 断言：原请求同job同X且无额外模型；异body409；旧checkpoint/构造/字段语义不变。
- 清理与证据：共享清理＋本例进程/流结束；V-10，当前run原始报告与状态/产物摘要。
- 防止假阳性：先重新选latest再判幂等会错误冲突，测试必须抓住。

#### TC-F3-10-03：冲突、重启与部分成功

- 关联与风险：REQ-010 / AC-010；权限、异步或跨层副作用风险；真实对象与测试位置：W32（按§9.1路径）。
- 前提与输入：API-002 base2；独立例缺分析/非SUCCEEDED/素材非ready/sha不符/快照256KiB及+1；并发请求同base2。
- 依赖与顺序：分别单项验证；用屏障先使另一写者head3；另在作者已写回但plan未持久处终止worker，再持新lease恢复；取消另例在派发前；仅允许§9.3及本例指定替身。
- 断言：缺可信分析/来源/head无效受理409且job/模型0；快照超限400，预算边界按§7；CAS不覆盖；恢复只补plan一次，旧lease不能提交；取消不新增build/擅退款。
- 清理与证据：共享清理＋本例进程/流结束；V-10，当前run原始报告与状态/产物摘要。
- 防止假阳性：只检查UI禁用、无服务端快照/CAS/fencing任何一项均失败。

#### TC-F3-10-04：UI生命周期及重试

- 关联与风险：REQ-010 / AC-010；权限、异步或跨层副作用风险；真实对象与测试位置：W27/W28/W39（按§9.1路径）。
- 前提与输入：A有方案，模拟请求进行中；页面A→B→A；成功/失败/finally分别延迟；网络中断后同请求重试。
- 依赖与顺序：组件层精确控制回调顺序，卸载后触发旧事件；E2E实际按钮由站内API完成；仅允许§9.3及本例指定替身。
- 断言：连点1请求；旧回调不改新页、不清新loading；失败保留方案/输入；终态才重读；重试同ID；键盘/文案按§8。
- 清理与证据：共享清理＋本例进程/流结束；V-10，当前run原始报告与状态/产物摘要。
- 防止假阳性：generation gate/finally任一移除有独立反例；UI mock不能代E2E。

#### TC-F3-11-01：Studio编辑保存与CAS

- 关联与风险：REQ-011 / AC-011；权限、异步或跨层副作用风险；真实对象与测试位置：W38/W63（按§9.1路径）。
- 前提与输入：F-OWN A可编辑工程，正文含OLD；另一会话保持旧revision；readOnly会话单列。
- 依赖与顺序：真实iframe控件OLD→NEW→保存→关闭重开；随后旧revision保存与readOnly写入；仅允许§9.3及本例指定替身。
- 断言：writeback命中Java、PG revision/hash变化，重开NEW；旧CAS409、readOnly拒绝且输入保留，不能直写broker绕过。
- 清理与证据：共享清理＋本例进程/流结束；V-11，当前run原始报告与状态/产物摘要。
- 防止假阳性：只打开/reopen旧内容不能满足NEW断言。

#### TC-F3-11-02：跨owner导入后再编辑渲染

- 关联与风险：REQ-011 / AC-011；权限、异步或跨层副作用风险；真实对象与测试位置：W38（按§9.1路径）。
- 前提与输入：A既有工程/成片记录sha；B独立登录；真实导出包。
- 依赖与顺序：A UI导出→B UI上传→新工程ready→Studio编辑NEW-B→保存→check/plan/grant/build→下载；仅允许§9.3及本例指定替身。
- 断言：B新owner/id/revision，ffprobe视频轨且FFmpeg全解码通过；A历史源/成片sha不变；B源码含NEW-B。
- 清理与证据：共享清理＋本例进程/流结束；V-11，当前run原始报告与状态/产物摘要。
- 防止假阳性：只ready或复制A MP4而未再渲染均失败。

#### TC-F3-11-03：视觉、移动、会话失效

- 关联与风险：REQ-011 / AC-011；权限、异步或跨层副作用风险；真实对象与测试位置：W38/W39（按§9.1路径）。
- 前提与输入：§8的明暗主题、桌面与移动尺寸；已登录目标页面；生成中、空、错、冲突状态。
- 依赖与顺序：真实查看截图；Tab/Enter操作；生成时退出登录/切换对象，Studio关闭后再调用旧会话；仅允许§9.3及本例指定替身。
- 断言：按§8布局/焦点/文案；旧会话拒绝，无新版本/旧回调污染；控制台无私有内容。
- 清理与证据：共享清理＋本例进程/流结束；V-11，当前run原始报告与状态/产物摘要。
- 防止假阳性：登录页/空白图/body存在不算证据；拦截错误需命中目标请求。

#### TC-F3-12-01：LIVE缺省与目录拒绝

- 关联与风险：REQ-012 / AC-012；权限、异步或跨层副作用风险；真实对象与测试位置：W47（按§9.1路径）。
- 前提与输入：F-SCRIPT：无授权、catalog不存在、空providers、缺base、未知provider分别完整命令输入。
- 依赖与顺序：执行脚本副本，每次清除商业凭据；每例仅改变目标条件；仅允许§9.3及本例指定替身。
- 断言：非零，NOT_ENABLED或UNVERIFIED含原因；无商业网络请求/旧证据改写。
- 清理与证据：共享清理＋本例进程/流结束；V-12，当前run原始报告与状态/产物摘要。
- 防止假阳性：空循环exit0的旧实现必须失败。

#### TC-F3-12-02：HTTP200不等于LIVE

- 关联与风险：REQ-012 / AC-012；权限、异步或跨层副作用风险；真实对象与测试位置：W47（按§9.1路径）。
- 前提与输入：本地HTTP探针逐项200；预算字段合法但无真实业务/账务/成片证据。
- 依赖与顺序：显式启用探测，仅连本地；执行到汇总；仅允许§9.3及本例指定替身。
- 断言：最多PROBE_PASS、成本null，最终UNVERIFIED且非零；无LIVE_PASS。
- 清理与证据：共享清理＋本例进程/流结束；V-12，当前run原始报告与状态/产物摘要。
- 防止假阳性：将200直接映射LIVE_PASS会失败。

#### TC-F3-12-03：错误与超时逐项汇总

- 关联与风险：REQ-012 / AC-012；权限、异步或跨层副作用风险；真实对象与测试位置：W47（按§9.1路径）。
- 前提与输入：本地探针分别500、连接拒绝、受控超时，其他项200。
- 依赖与顺序：顺序运行并检查每条结果；中断一次验证临时服务退出；仅允许§9.3及本例指定替身。
- 断言：任一未验证非零且不遗漏失败项；不泄漏凭据；不遗留本地探针。
- 清理与证据：共享清理＋本例进程/流结束；V-12，当前run原始报告与状态/产物摘要。
- 防止假阳性：吞单项异常/只统计成功数会失败。

#### TC-F3-13-01：报告反向门禁

- 关联与风险：REQ-013 / AC-013；权限、异步或跨层副作用风险；真实对象与测试位置：W54（按§9.1路径）。
- 前提与输入：F-SCRIPT独立注入exit1、0test、skip、todo、JUnit failure/error、缺engine、过期run/source、损坏JSON、缺媒体、媒体hash错。
- 依赖与顺序：其余字段保持完整，每次仅破坏一个条件；另提供完整成功报告；仅允许§9.3及本例指定替身。
- 断言：每个负例非零且定位缺口；成功样本PASS但仅FIXTURE工具测试，不提升业务卡。
- 清理与证据：共享清理＋本例进程/流结束；V-01，当前run原始报告与状态/产物摘要。
- 防止假阳性：删对应校验即有反例失败；不能在负例本身预置总状态FAIL。

#### TC-F3-13-02：信号退出及最小栈编排

- 关联与风险：REQ-013 / AC-013；权限、异步或跨层副作用风险；真实对象与测试位置：W54（按§9.1路径）。
- 前提与输入：脚本隔离副本、记录式stack命令；一个预存资源与一个本次新资源。
- 依赖与顺序：在子进程运行时SIGTERM；检查cleanup；静态解析真实脚本白名单与锁持有边界；仅允许§9.3及本例指定替身。
- 断言：退出非零，只清本次资源；不嵌套抢锁、不执行全量up/删除旧卷；真实栈清理由V-15复核。
- 清理与证据：共享清理＋本例进程/流结束；V-01，当前run原始报告与状态/产物摘要。
- 防止假阳性：用记录器仅能证明编排，不能声称真容器已清理。

#### TC-F3-13-03：确定媒体fixture生成

- 关联与风险：REQ-013 / AC-013；权限、异步或跨层副作用风险；真实对象与测试位置：W54/W64（按§9.1路径）。
- 前提与输入：F-REF固定参数与seed，空当前run输出目录。
- 依赖与顺序：单FFmpeg依次生成有声/无声样本；ffprobe及完整decode、提取六帧；仅允许§9.3及本例指定替身。
- 断言：12秒/320×240/音轨分支及红绿蓝段正确；本次sha清单对应字节；坏生成exit非零。
- 清理与证据：共享清理＋本例进程/流结束；V-02，当前run原始报告与状态/产物摘要。
- 防止假阳性：仅touch文件或只比较两hash不同失败。

普通文档/计数用例（无新增业务副作用）：

| TC / REQ、AC尾号 | 位置/层级 | 确定输入与操作 | 断言、禁止副作用 | V |
|---|---|---|---|---|
| TC-F3-04-02 / 004 | W52 / Node域执行 | 实跑完整studio组 | 原始报告非零发现且无失败/skip；不写预设21 | V-06 |
| TC-F3-04-03 / 004 | W52 / Node域执行 | 实跑完整media组 | 当前实数可还原，不写预设48 | V-06 |
| TC-F3-04-04 / 004 | W52 / 前端域执行 | 实跑src/views/video-clone全域 | 保留逐文件/用例统计与skip，不能复用旧50 | V-06 |
| TC-F3-05-01 / 005 | W02/W08 / 文件核验 | D-05每条声明路径逐文件检查 | 真路径存在且哈希可读；缺失标MISSING，旧证据字节不变 | V-13 |
| TC-F3-05-02 / 005 | W08 / 证据核验 | 对照C10/C15/C22/C23/C24/C37声明与本次报告 | 数量、截图/媒体层级及版本如实；缺图不伪称看过 | V-13 |
| TC-F3-05-03 / 005 | W02/W08/W58～60 / 文档 | D-06/07归属及各状态逐行对照git与证据 | 无臆造提交归属/重复覆盖他人索引；非最终状态不提升整体 | V-13 |
| TC-F3-06-01 / 006 | W48/W52 / 汇总 | V-14报告与13卡清单 | 每REQ有当前TC/V/证据，缺卡非零；基线例外不能改PASS | V-14 |
| TC-F3-06-02 / 006 | W39/W52 / 集成复核 | V-15执行下列G1～G7及上述高风险用例 | 三引擎均必需TC，终态/字节/PG一致；本行不代替展开用例 | V-15 |
| TC-F3-06-03 / 006 | W10/W11/W48 / 交付核验 | 最终diff、状态、run manifest、资源前后清单 | 仅已通过卡VERIFIED；旧改动保留；本次新增资源已结束 | V-14/V-15 |

证据层级：单元只证明函数/组件；Java IT证明真实HTTP/PG/服务；E2E只在站内API/持久化/原生执行真实且只替换外部模型时证明本地闭环。实际图像进入模型不证明商业模型理解质量。截图须查看目标状态；持久化须刷新/重读；异步须终态；媒体须probe+全解码+对应源码/构建身份。验收者（可同一模型）最后从RULE-004/011/012各反推一个反例重新核对，不能仅相信实现者摘要。

### 12.3 固定验证入口与命令

所有命令从 `/Users/LXH/claude/y-1` 用bash执行。以下W52入口是 **C13须实现的目标命令契约，当前未存在/未运行**，不得直接记成现有能力。统一形式：`bash scripts/acceptance/verify-107-fix-3.sh --stage 名称 --run auto`；auto生成新runId；另允许`--run 已有标识`续跑，必须核验源码摘要，重复stage保存attempt子目录而非覆盖。未知参数/stage或尚未交付测试exit2/NOT_RUN。stage失败exit1；成功exit0且下面的原始报告校验全部满足。每条命令均必需。

| V | 精确命令 | 固定执行集合 / 前置与副作用 | 证据子目录 |
|---|---|---|---|
| V-01 | `bash scripts/acceptance/verify-107-fix-3.sh --stage tooling --run auto` | W48/W54契约；无Docker；C13只验证工具，未来业务仍NOT_RUN | tooling |
| V-02 | `bash scripts/acceptance/verify-107-fix-3.sh --stage fixtures --run auto` | W64生成＋TC-F3-13-03；FFmpeg串行 | fixtures |
| V-03 | `bash scripts/acceptance/verify-107-fix-3.sh --stage variant --run auto` | 旧入口card C107F2-26＋HypitVariantIT；Java真实PG；Edge子项留V-15 | variant |
| V-04 | `bash scripts/acceptance/verify-107-fix-3.sh --stage maintenance --run auto` | engine组和agent-integration组串行，必含maintenance；无Compose | maintenance |
| V-05 | `bash scripts/acceptance/verify-107-fix-3.sh --stage engine --run auto` | 全engine＋旧C32 card＋W54失败传导；真实daemon | engine |
| V-06 | `bash scripts/acceptance/verify-107-fix-3.sh --stage domain --run auto` | 旧C32 card，studio/media组、src/views/video-clone全域串行 | domain |
| V-07 | `bash scripts/acceptance/verify-107-fix-3.sh --stage evidence --run auto` | W29/W30/W42 Java测试＋media组；真实PG与末跳模型fixture | evidence |
| V-08 | `bash scripts/acceptance/verify-107-fix-3.sh --stage vision --run auto` | V-07集合重验新增parts/资源用例；W40严格fixture输入用W30调用验证 | vision |
| V-09 | `bash scripts/acceptance/verify-107-fix-3.sh --stage archive --run auto` | W31 Java IT，原Hypit工具相关回归纳入V-14 | archive |
| V-10 | `bash scripts/acceptance/verify-107-fix-3.sh --stage context --run auto` | W32/W71 Java＋W27/W28前端；UI真实链留V-15 | context |
| V-11 | `bash scripts/acceptance/verify-107-fix-3.sh --stage e2e --run auto` | studio组W63先行，停止其进程后走下面E2E固定入口；三引擎串行 | e2e/引擎 |
| V-12 | `bash scripts/acceptance/verify-107-fix-3.sh --stage live-contract --run auto` | W47负向契约，只有本地HTTP；子LIVE预期非零，测试自身0 | live-contract |
| V-13 | `bash scripts/acceptance/verify-107-fix-3.sh --stage docs --run auto` | docs:links/docs:status＋W09/W48契约＋TC-F3-05人工逐项原始证据核验 | docs |
| V-14 | `bash scripts/acceptance/verify-107-fix-3.sh --stage regression --run auto` | 下列固定回归集合，串行；不启动E2E栈 | regression |
| V-15 | `bash scripts/acceptance/verify-107-fix-3.sh --stage all --run auto` | 最终同run串行V01～14适用集合及完整E2E；不递归all；产G1～G7 | all及各stage |

W52具体分派约束（同样由C13实现）：

1. Vitest使用`npx vitest run --maxWorkers=1 --no-file-parallelism`加上述准确W路径/域目录；JSON原始报告与退出码保存；tooling仅W48/W54，live-contract仅W47，不提前要求未建测试通过。
2. Node组使用子shell切到`platform-hypit/backend`，环境`TEST_GROUP=组名`，执行`npx --yes --package=node@24.14.1 -- node scripts/run-tests.mjs`，原始TAP写到root计算的绝对run目录；不能在backend内写相对`test-artifacts`。完整组不得筛掉红用例。
3. Java先source `scripts/lib/java-runtime.sh`并`ensure_java_runtime 25`，进入`platform-java`执行`./gradlew :services:intelligence-service:test --tests 完整类名 --rerun-tasks --no-daemon --no-parallel --max-workers=1`；类名由W表路径去src/test/java及后缀生成，每类一个`--tests`参数。JUnit默认单fork（当前无maxParallelForks覆盖）；运行前核查若漂移再按§13.3登记，不并行起IT。运行后立即复制本次目标XML，防止下一次覆盖和旧报告混入。旧card委托W03并设置绝对`FIX2_ART_BASE`到当前stage，不能裸调用可能自起栈的旧all/local/recovery。
4. V-14固定：`npm run typecheck`、`npm run lint`、`npm run quality:lifecycle`、`npm run security:secrets`、`npx @google/design.md lint DESIGN.md`；前端`src/views/video-clone`与`tests/deployment/hypit*.test.ts`完整匹配文件（shell展开非空）；backend typecheck及engine/workspace/media/providers/runtime/studio/agent-integration全部组；Java `--tests 'com.grassland.intelligence.hypit.*'`（包含原Hypit与本书fix3，实际发现清单核对），docs两个检查与`git diff --check`。重型均串行，保留覆盖率既有阈值，未运行项不可删除。
5. E2E先按§9.3盘点，设置其旗标及`E2E_WORKERS=1`、`E2E_ENGINES='chromium firefox webkit'`、`HYPIT_FIX3_EVIDENCE_ROOT`为当前绝对stage目录，同时设置既有`TASK103_EVIDENCE_DIR`为当前stage的`reports`绝对目录（复用W67现有逐引擎原始JUnit复制扩展点，变量旧名不代表本次运行103任务）；`E2E_SPECS='tests/e2e/hypit-fix2-journey.spec.ts tests/e2e/hypit-fix2-c36.spec.ts tests/e2e/hypit-fix3-reference.spec.ts'`，执行`bash scripts/acceptance/ci-e2e-107.sh`。外层不再持不兼容栈锁；沿用现有内部守卫、真实构建及每引擎清理。只指定这三个spec是本次必需范围；不能减少其原有TC。末跳fixture强制解码帧/检查上下文；六帧观察不冒充全片逐帧理解。
6. W53以本次运行manifest里的源码/版本、实际testcase、expected子项、浏览器、原始报告hash、媒体hash核验。JUnit/TAP/Vitest/Playwright分别按真实结构解析，任何必需missing/skip/todo/失败/0test/非法报告均非零；不预设历史总数。文档人工审阅只补结构化检查清单与引用，不自造PASS的业务测试报告。报告汇总不能用stdout grep TC代替执行发现。
7. V-15首次执行未交付前卡应NOT_RUN而非全绿；后卡交付后才能完整实跑。允许复用同源码且未受后续改动影响的卡级原始证据，但all manifest需记录来源run/sha及复用理由，最终E2E必须当前执行。改变的调用链须重验。保留失败attempt，shell用`set -euo pipefail`或显式保存真实退出码，不让tee吞失败。

所有新证据在§9.1生成物目录。docs:links出书基线为exit1、errors=0、unindexed=72；本次实施须无新增broken/unindexed，基线不等于全绿，可单列既存索引缺口；不能泛化到业务/安全失败。docs:status基线exit0。工具输出路径仅作证据，禁止将默认额外总结报告列作交付物。

### 12.5 最终本地集成出口

C06负责，全部适用Gate必须通过；任一未完成，整任务不可VERIFIED。

| Gate | 必须证明 | 对应证据 |
|---|---|---|
| G1 | 发现/skip/失败/身份/缺产物的反向门禁有效；LIVE不误报 | V-01/V-12/V-14，TC13/12 |
| G2 | 真实HTTP/PG权限、变体、取证、上下文事务/幂等/fencing成立 | V-03/V-07～10 |
| G3 | 完整engine与维护/隔离/回收及域回归成立 | V-04～06/V-14 |
| G4 | 模型末跳收到真实图像和词锚点，来源快照被作者及plan消费，输入变化影响输出 | TC08/10与V-15请求摘要、revision/plan |
| G5 | 三引擎真实参考→分析→重新生成→check/plan/grant/build→成片；公共Edge、Studio保存/重开、导入后再编辑渲染 | V-15全部spec，TC01-03/10/11；真实PG与可解码MP4 |
| G6 | 明暗/移动/键盘/加载空错冲突/离开/旧回调与会话撤销符合§8 | TC10-04/11-03；实际查看截图结论 |
| G7 | REQ/AC/TC/V闭合，当前数字/路径/状态如实，他人改动及旧证据保留，本次资源释放 | V-13/V-14/V-15及§14交接 |

最终媒体记录sourceHash、analysisId、jobId、base/resultRevision、buildId、输出sha、probe和完整decode结果；固定3秒动画即使能播放也不足以满足G4/G5。若商业模型、真实ASR或生产未运行，只列NOT_RUN/范围外，不使用“真实商用全链路完成”。

### 12.6 发布与回滚

生产部署N/A：本书止于本地集成，不含上线。无DDL、无新增依赖、无财务迁移。实施失败停止本次job/资源，保留旧工程/分析/方案及失败证据；新可选字段旧客户端仍兼容。代码回退只精确撤销本任务增量，不reset共享工作区，不删数据卷。上线/真实付费验证须另获明确授权，不是C06完成条件。

### 12.7 边界目录

| E | 责任TC |
|---|---|
| E01 空输入 | TC-F3-07-03、08-02、12-01 |
| E02 超长 | TC-F3-08-02、10-03 |
| E03 重复提交 | TC-F3-09-02、10-02/04 |
| E04 断网 | TC-F3-08-03、10-04 |
| E05 服务/第三方错 | TC-F3-07-03、08-02、12-03 |
| E06 未登录/过期 | TC-F3-01-02、11-03 |
| E07 权限/非法迁移 | TC-F3-01-03、09-01/03 |
| E08 成功无数据 | TC-F3-07-02、12-01 |
| E09 版本冲突 | TC-F3-10-03、11-01 |
| E10 刷新/恢复 | TC-F3-10-01/03、11-01 |
| E11 A→B→A | TC-F3-10-04 |
| E12 卸载/在途资源 | TC-F3-08-03、10-04、13-02 |
| E13 缺值/null/0/false | TC-F3-07-03、10-02 |
| E14 阈值及邻界 | TC-F3-08-02、10-03 |
| E15 并发/乱序/旧finally | TC-F3-02-01、10-03/04 |
| E16 超时已提交/幂等 | TC-F3-10-02/03/04 |
| E17 跨账号隔离 | TC-F3-09-01/03、11-02 |
| E18 在途撤权/删除 | TC-F3-09-03、11-03 |
| E19 旧客户端/数据 | TC-F3-10-02 |
| E20 部分成功/重启 | TC-F3-03-01、10-03 |
| E21 时间/金额 | TC-F3-07-02校验媒体秒/样本精度；日历/时区/金额算法N/A：本书不改此类规则 |
| E22 大文件/设备 | TC-F3-08-02、10-03、11-03；分页N/A：无新列表 |

## 13. 阻塞与修订

### 13.1 必须停止相应动作的情况

未明确授权的收费/权限/数据可见范围/核心业务流程变更；必需真实依赖不可用；唯一栈被其他会话占用且不能协调；真实敏感资源或破坏性操作未获授权；事实与冻结公开契约矛盾且无法按本书裁量解决。此时保持当前卡未验证，继续独立静态工作；不能以假成功替代真实层。普通编译/测试失败须在白名单内修复，不因出错就返问；当前已修复的问题直接复验，无需重新制造红例。

### 13.2 最小阻塞报告

在对话列：卡/REQ、原命令与错误证据、已完成和NOT_RUN、阻塞事实、推荐处理及影响、需要用户决定的最小问题。不能以历史known-failure或资源有限替代最终验收。当前规划没有待用户决定的产品问题；实施环境可用性需执行前按§9.3核实。

### 13.3 修订与恢复

等价私有实现/必要测试适配在用户技术授权内由执行者先增量登记精确W、影响卡/TC，再实现；不是任意扩大白名单。公共契约变化先修唯一章节和B记录，涉及§13.1用户决策才询问。修订版本、附C及索引同步，重新执行受影响B2/B4；恢复时核对代码与原始证据而非历史完成声明。未知源码漂移具体定位，不自动覆盖其他会话或把规范模板改成项目实现。

## 14. 执行结果汇报

默认只在对话汇报：实际完成卡/状态、用户可见变化、文件范围及关键决定、实际验证命令/发现/失败/skip/证据、未运行项和原因、剩余阻塞、下一卡。明确本地与商业/生产层级。列使用的栈/服务、本次已停资源、仍运行服务及归属原因（本轮出书没有启停服务）。不默认新建独立完成报告；机读results/原始日志/截图/媒体是验收证据，不是额外报告交付要求。

### 14.0 2026-10-04 复审修复登记

本次用户授权直接修复复审问题。已落地：变体异步请求随工程作用域失效；重试后的无build排队项可继续构建；授权重复提交保护与构建后轮询；重新生成的head检查仅用于首次受理，既有请求可跨版本推进重放且保留跨工程/异body冲突；LIVE仅作探针分层，空目录/未知项/未验证非零、证据不覆盖；取证测试类型及过期断言修复。额外修正W53身份摘要为实际Git差异与未跟踪文件内容哈希，避免同一dirty文件二次修改复用旧证据。历史记录不覆盖。

本轮实际结果：Java Hypit域202/202、前端/部署契约185/185；原生六组208通过、2项外部能力声明skip；正式e2e run `fix3-20261003T203220Z-2292` 的Chromium/Firefox/WebKit各11/11，LIVE探针契约3/3。全库docs:links仍失败（82项均在另一个在途OpenTalking评审文档，另72项未索引），不写成通过。E2E期间新增无关评审文档的内容摘要差异已独立重建核对，业务/测试源码未变；见 `e2e-source-delta.json`。E2E后仅追加变体面板表单/移动分行样式和聚焦截图，补跑20组真实组件视觉/键盘/状态检查及组件回归，不混称这次样式已再跑全栈。Java测试另补C26的AfterEach清理，消除授权残留导致的后续外键失败。

收尾：唯一 `y1-e2e-local` 栈及测试临时资源已由守卫回收；面板预览与浏览器已关闭；已执行超过24h构建缓存清理（0B可回收）。无本任务应用服务遗留，预先运行的Docker Desktop保留。

本次回归总记录为 `test-artifacts/task-107/fix-2026-10-04/summary.json`；对应原始JSON/TAP/JUnit、三浏览器报告与截图由该文件引用。记录缺失或未通过时不得据本段宣称完成。任务卡IMPLEMENTED只表示实现存在；C06全任务出口仍须原定全部必需门禁，不能把本轮修复的定向/域回归当作旧C03/C04镜像隔离等未执行场景的复验。真实商业模型/ASR效果/生产仍独立NOT_RUN。

新增与增量文件：`src/views/video-clone/composables/useHypitVariants.recovery.test.ts`、W47、W53/W54、`VariantsPanel.vue`、`useHypitVariants.ts`、C27原单测、W18/W32、W41/W63、C39 compose断言、V08数据库环境连接配置。与105在途内容无交叉覆盖。

### 14.1 续作检查点

中断时在对话给：任务书绝对路径/版本/规格状态；实际授权范围；当前HEAD及在途diff；已验证卡和对应run/source；当前卡具体步骤/失败断言；剩余NOT_RUN；进程/栈/卷及归属/清理动作；下一个确定命令和阻塞决定。新执行者读检查点后检查实际文件与证据，证据失效只重验受影响部分，不从C13盲目全部重做，不凭上一个模型的结论推进VERIFIED。

## 附 B：规格发布审阅

### B.1 调查与规划记录

2026-10-02按模板3.1.1读适用AGENTS、设计规范、现有107-fix-3、当前Java/Node/前端入口及测试/脚本；核验记录集中§2。历史审计只作线索，当前维护闸/runner已变化者不推定仍红。按单一事实源补需求/契约/真实调用、13卡/39个TC及命令；只改本书和必要索引。本轮未运行业务测试、未启动服务。出书前后均检查文档引用、状态与diff，结果见B.3。

### B.2 发布前检查结果（规格质量，不是业务通过）

- [x] 01 用户/场景/范围/闭环：§1、§4；明确本地终点及范围外。
- [x] 02 决策与授权：§1.9、§3、§13；无待定产品方案。
- [x] 03 当前事实与基线：§2；历史证据与现查源码分层。
- [x] 04 真实接线/恢复：§3.1、§6/7；W13/18/19调用新模块。
- [x] 05 成功/失败/状态/兼容：§4～8、TC07～11；202不作终态。
- [x] 06 唯一契约/字段/引用：§5/6/7/8/9/12各唯一维护，W56已纠正。
- [x] 07 UI与仓库规约：§8、§9.5；分层/体积/双主题/生命周期。
- [x] 08 完整文件权限：§9.1；调用方、W71旧构造测试、只读与生成物分明。
- [x] 09 无循环依赖/阶段出口：§10固定13→01→02→03→04→07→08→09→10→11→12→05→06。
- [x] 10 每卡可执行：§11；具体符号、步骤、W/TC/V，旧构造保留委托。
- [x] 11 边界与高风险用例：§12.2六项展开、§12.7全部E。
- [x] 12 完整追踪：§1.3→§11 AC→§12.1/2/3→§12.5。
- [x] 13 真实层与防假阳性：§9.3、§12.2；不以mock/零用例/只看文件存在过关。
- [x] 14 环境/命令/证据/未运行：§9.3、§12.3；目标新入口明确由C13实施，当前NOT_RUN。
- [x] 15 阻塞/恢复：§13；常规错误自行修复，实质未授权决定才暂停。
- [x] 16 状态与完成责任：§0/10/12.5/12.6；全部NOT_STARTED，C06最终负责。
- [x] 17 依赖/迁移/收费/脱敏：§1.4/7/9/12.6；无新增依赖DDL费用规则，日志脱敏。
- [x] 18 汇报与续作可独立核验：§14；无默认独立报告，无必须访问历史对话。
- [x] 19 附C已填实：绝对路径/版本/状态/顺序/模式/检查点齐全，不替用户授权。
- [x] 20 裁剪与引用：无附D、空表/候选菜单/待选产品方案；动态runId有定义。

### B.3 版本与规划核验

| 版本 | 日期 | 修订/检查 |
|---|---|---|
| 1.0.0 | 2026-10-02 | 用户原六卡稿，作为本次修订输入；其完成判断不自动沿用 |
| 1.1.0 | 2026-10-02 | 主程结合当前核实与历史反例重写；保留原六卡并增七卡；B2/B4完成后发布；实施全NOT_STARTED、业务测试全NOT_RUN |

规划检查：docs:status通过；docs:links基线exit1但broken errors=0、unindexed=72，属于既有索引缺口；本次只安全改本条索引，不清理全仓。最终复核：docs:status exit0，git diff --check exit0；docs:links exit1、285文档/2251本地链接/errors0/unindexed72，与基线无新增断链或未索引。结构核对13卡、39唯一TC、71精确W、20项发布检查及附C一致；初始34个在途路径中除本书/索引外哈希全部保持。未虚构业务测试执行。

### B.4 按字面执行与反向审阅

| 顺序/读取 | 模拟执行与查出的偏差 | 本稿处置及失败出口 |
|---|---|---|
| C13→C01 | 新wrapper尚不存在，旧证据默认路径会污染；C26路由无独立TC | §12.3冻结目标分派；C13先落工具；C01真HTTP，Edge在V15补齐 |
| C02→C03→C04 | 旧日志不能证明当前维护/daemon仍红；runner红有档及注释计数会伪通过 | 先复验当前源码；完整engine/真实TC发现；失败修复或§13阻塞 |
| C07→C08 | flat probe/text及JSON帧句柄可让测试绿但无真实视频证据 | nested wire、逐词样本、实际解码image parts，缺证据禁止成功；MOD-001可空时间类型修正 |
| C09 | 只校验project或只隐藏UI不能防外project output；拒绝可能被非法body掩盖 | output→build→owner全链与独立合法负例；拒绝0归档调用 |
| C10 | 新模式只固定brief无上下文；202当终态、重复latest查询、旧finally污染；已知路径/构造遗漏 | 冻结快照、稳定幂等plan、终态后读；W56纠正/W71补齐；TC10分别断言 |
| C11 | Studio仅打开、导入仅ready亦满足旧文字 | 强制真控件编辑保存重开＋B二次渲染/解码，API仅准备/观测 |
| C12 | 空providers或200被误报LIVE；预算打印当闸 | 全部负例非零，最多PROBE_PASS；本书无商业验收实现 |
| C05→C06 | 修文案/历史全绿不能证明本次闭环；其他会话索引可能被覆盖 | 当前证据/原始字节不改；逐REQ回溯与G1～G7出口；索引仅本行增量 |

反向逐REQ复核：REQ001公共Edge与PG；002维护真实副作用；003真实daemon及engine失败传导；004具名执行报告；005逐文件声明；006全部Gate；007探针/词时间；008实际图像和资源释放；009归档owner；010快照与作者/方案真实消费；011UI保存/导入成片；012LIVE负向；013run身份/缺层/skip门禁，均在§12.1定位TC/V。高风险保护的单一原因反例已写入§12.2。已发现规格缺口在本稿修正，无将产品/安全判断留给实施者的未决项。

## 附 C：可复制的实施提示词

任务书：`/Users/LXH/claude/y-1/docs/任务书/草场任务书-107-fix-3-reviews.md`
版本：1.1.0；规格状态：READY_FOR_IMPLEMENTATION；实施状态：NOT_STARTED。
待派发范围：C107F3-13 → C107F3-01 → C107F3-02 → C107F3-03 → C107F3-04 → C107F3-07 → C107F3-08 → C107F3-09 → C107F3-10 → C107F3-11 → C107F3-12 → C107F3-05 → C107F3-06。
模式：AUTO_CHAIN，单执行者、重型串行。续作检查点：无（首次实施）。本提示词随规格保存，不表示本次文档请求已授权实施；须以用户后续派发为准。

你是实施模型。先读根AGENTS.md、本书§0/1/9/10/13/14；UI另读根DESIGN.md。确认版本/规格状态及用户实际授权卡范围，按固定顺序只推进获授权卡。每卡按§0.4核实基线/在途改动/精确W和依赖，再读§11卡及其RULE/契约/TC/V；保留105-fix-2及索引已有增量。按具体步骤实现真实接线，先修保护/反例，再运行当前卡与必要回归；所有新增测试用真实可发现TC名。未执行写NOT_RUN，别把fixture/HTTP200/202/文件存在当真实效果或终态。UI实际查看明暗及移动/焦点/状态截图。

只用§9.3最小真实依赖与仓库stack守卫，先盘点再启动，单栈单重型任务，失败也精确清理本次资源。常规实现错误自行在范围内修复；实质范围变化按§13，不能降断言。获授权全部卡完成后继续C06的§12.5本地集成出口，通过后才报告整任务完成；商业LIVE与生产仍单独NOT_RUN。默认仅在对话按§14汇报，中断前给§14.1检查点，不新建默认完成报告。
