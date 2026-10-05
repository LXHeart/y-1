# OpenTalking 复刻复审（2026-10-02）

**结论：已有较完整的控制面、组件和测试框架，但还不能认定为可用的个人版数字人复刻。** 当前主要问题在真实对话接线、前后端协议、音频生命周期和历史内容闭环，另外存在可直接看到的页面状态与样式问题。

本次确认 **16 项问题：6 项 P1、10 项 P2**。P1 指优先修复的核心流程/隐私状态/部署阻断；P2 指正常使用中会遇到的功能或交互缺陷，不代表可以忽略。

审查基线为本地 `37d2b156` 加工作区原有未提交改动，包含进行中的 `105-fix-2`。本轮只增加审查报告与本地证据，没有修改业务源码、没有提交代码，也没有部署或调用收费模型。

## 对照范围与证据边界

- 使用 agent-reach 的 GitHub/gh 路由读取原项目。GitHub `main` 与本地锁定版本均为 [`8c739a5a6f114daf71aeace832a668c3ad60f536`](https://github.com/datascale-ai/opentalking/tree/8c739a5a6f114daf71aeace832a668c3ad60f536)，不是上游版本落后导致的差异。
- 对照 [上游 README](https://github.com/datascale-ai/opentalking/blob/8c739a5a6f114daf71aeace832a668c3ad60f536/README.zh.md)、本地 `docs/产品/数字人工作台需求文档.md`、#105 契约与当前实现。重点审查数字人模块：Vue 工作台、Java digitalhuman 包、Python wrapper、部署入口与测试。
- 草场的一期明确排除长期记忆、知识库、公开直播、多角色、摄像头模仿和声音克隆；这些是与上游的**范围差异**，本报告不把它们算作一期漏做。AI 壳中的独立视频克隆/Hypit 工作流不属于本轮数字人闭环验收。
- 浏览器使用真实 AI 应用和真实组件，HTTP 层提供受控数据/错误。该证据能证明 UI 行为，**不能证明 Java 联调、真实模型、WebRTC 音画或生产可用**。协议复现另按 Java 当前返回结构注入，区别于理想契约数据。
- Python 使用真实 ASGI/WS 路由，授权边界使用测试 transport；无 Docker、无外部模型调用。三项追加单测断言的是“缺陷现象存在”，其通过不表示功能合格。

## P1：先处理这些问题

### R01 · 会话协议不一致，刷新接管和结束操作无法正确落到页面

**位置：** [DigitalHumanRecords.java:197](../../platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanRecords.java#L197)、[DigitalHumanLeaseService.java:77](../../platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanLeaseService.java#L77)、[DigitalHumanSessionController.java:117](../../platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanSessionController.java#L117)、[useDigitalHumanApi.ts:221](../../src/composables/useDigitalHumanApi.ts#L221)。

Java GET 返回 `{session: {...}, allowedActions, replayComplete, replayFromSeq}`；pause 返回 `{paused:true}`，resume 返回 `{resumed:true}`，end 返回 `{state:...}`。前端四者全部直接解码为完整 `Session`；解码又没有必填字段校验。注释写“扁平”并没有改变 Java record 的实际结构。

**复现：** 浏览器注入当前 Java 返回形态后，接管请求变成 `/api/digital-human/sessions/undefined/resume`，缺失 `leaseEpoch`；end 已收到 `{state:'ended'}`，页面仍为活动会话，因为 `ended.id` 不匹配当前会话 ID。queued 等待轮询同样依赖顶层 `id/state`，会被嵌套快照破坏。

**修复方向：** 统一公开 DTO 与契约；或明确区分操作回执与会话快照，操作成功后读取/归一化权威快照。增加真正经过 Java 序列化、前端解码和页面按钮的协议测试。

证据：[ui-wire-observations.json](../../test-artifacts/opentalking-review-2026-10-02/ui-wire-observations.json)、[接管截图](../../test-artifacts/opentalking-review-2026-10-02/wire-nested-snapshot-undefined-resume.png)。

### R02 · 核心对话仍未接通，配置真实模型也不能直接解决

**位置：** [DigitalHumanTurnService.java:66](../../platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanTurnService.java#L66)、[routes_internal.py:197](../../platform-realtime/digital-human/src/grassland_dh/routes_internal.py#L197)、[routes_audio.py:233](../../platform-realtime/digital-human/src/grassland_dh/routes_audio.py#L233)。

文字接口完成数据库轮次分配后就返回，没有派发文字内容、调用生成链或发布对应完成事件。Python `startTurn` 也只是设置 `active_turn_id` 和 `responding`，没有调用已附着的 `RuntimeSession.start_turn()`。真实语音路径在 STT 后明确记录 `llm/tts stages not wired`，随后以 1011 关闭。当前唯一注册的渲染 provider 是 `StaticRenderProvider`，其画面是静态图与能量律动，不是真实口型渲染。

**复现：** 实际内部 command 路由返回 202、状态 responding，但附着管线的 `start_turn` 调用数为 **0**。现有 E2E 自己也注明 turn 停留 responding，不要求真正回复完成。

**修复方向：** 接通文字/语音→LLM→TTS→渲染/媒体输出→字幕/完成事件→用量结算的整条路径，并验证第二轮可正常开始。静态演示档与真实第三方档应明确区分。

证据：[runtime-observations.json](../../test-artifacts/opentalking-review-2026-10-02/runtime-observations.json)。

### R03 · 正式镜像入口缺少 bridge 装配，音频与控制面还分属不同内存

**位置：** [app.py:122](../../platform-realtime/digital-human/src/grassland_dh/app.py#L122)、[Dockerfile](../../platform-realtime/digital-human/Dockerfile)、[routes_audio.py:125](../../platform-realtime/digital-human/src/grassland_dh/routes_audio.py#L125)。

`create_internal()` / `create_audio()` 都没有构造或注入 `ExecutionBridge`。Dockerfile 又分别启动两个 Python 进程；每个 app 独立初始化 `sessions={}`，音频面无法访问控制面创建的内存 RuntimeSession。现有成功的测试主动注入 bridge，并执行 `audio.state.sessions = internal.state.sessions`，正式入口没有对应装配。

**复现：** 直接使用正式工厂得到 `internal_bridge=null`、`audio_bridge=null`、`shared_session_registry=false`；公开音频连接立即收到 1011 `dh_runtime_unavailable`。这是入口实现问题，不应仅归因于缺少密钥或 TURN 环境。

**修复方向：** 设计并落实共享会话/媒体运行体的真实部署方式，注入带 mTLS、Origin 配置的 bridge；用镜像真实入口测试，健康检查需反映必要依赖和控制面可用性。

### R04 · 正常语音约两秒就触发“容量超限”

**位置：** [routes_audio.py:90](../../platform-realtime/digital-human/src/grassland_dh/routes_audio.py#L90)。

`pending` 每收一帧递增，消费进 collector 后没有递减。所谓两秒队列背压实际变成了整段录音累计上限；与 UI 承诺的最长 60 秒不符。AudioWorklet 输出小于 320 样本时，每帧仍至少加 1，可能更早触发。

**复现：** 授权成功后按约 20ms 间隔发送 101 帧，每帧 320 个 16kHz 样本，共 **2.02 秒音频**，收到 4429 `dh_capacity_full`。发送不是积压突发，实测发送耗时约 2.54 秒。

**修复方向：** 对实际未消费队列计算背压；整段时长/字节限制继续交给 collector。覆盖正常 3/30/60 秒与真正积压两类测试。

### R05 · 取消授权等待后仍会开始录音，隐藏/结束也没有完整停止采集

**位置：** [useDigitalHumanMicrophone.ts:234](../../src/views/digital-human/composables/useDigitalHumanMicrophone.ts#L234)、[useDigitalHumanMicrophone.ts:321](../../src/views/digital-human/composables/useDigitalHumanMicrophone.ts#L321)、[DigitalHumanWorkbench.vue:147](../../src/views/digital-human/DigitalHumanWorkbench.vue#L147)。

`abort()` 没有增加 generation，无法使正在等待的 `getUserMedia()` 失效。页面可见性监听只调用会话 pause；麦克风没有对应监听，Workbench 的结束按钮也仅调用 `end()`，KeepAlive 停用只停止下行媒体/录制轮询。WS 在录音期间关闭时也不会停止 tracks。

**复现：** 开始申请权限→点击取消→权限 Promise 迟到成功，麦克风状态变为 recording，track.stop 未调用。随后发出 hidden 事件，采集仍不停止。该现象由注入媒体环境的真实 composable 探针确认，未使用真实麦克风。

**修复方向：** 取消、隐藏、结束、失活、WS 故障统一失效在途请求并同步停止 tracks/AudioContext；恢复必须由明确的新动作发起。

### R06 · 服务端开启了字幕保存，页面却显示“当前不保存”

**位置：** [useDigitalHumanTranscript.ts:38](../../src/views/digital-human/composables/useDigitalHumanTranscript.ts#L38)、[DigitalHumanWorkbench.vue:700](../../src/views/digital-human/DigitalHumanWorkbench.vue#L700)。

字幕 composable 把 `saved=false`、`transcriptVersion=1` 作为独立初值，没有在创建/接管会话后同步 `session.saveTranscript/transcriptVersion`。只有部分冲突处理才尝试对齐。

**复现：** 开始弹窗勾选保存，创建返回 `saveTranscript:true`；页面复选框仍未选中，并显示“当前不保存字幕”。这会让用户对实际留存状态产生错误判断。

**修复方向：** 以权威会话快照初始化并更新保存状态/版本，区分保存偏好与已有保存记录。测试应同时断言请求、返回值和页面状态。

## P2：功能与 UI 闭环

### R07 · 从后台返回没有“恢复会话”入口

[useDigitalHumanSession.ts:431](../../src/views/digital-human/composables/useDigitalHumanSession.ts#L431) 隐藏时 pause 并停心跳，返回仅更新可见性；[DigitalHumanWorkbench.vue:120](../../src/views/digital-human/DigitalHumanWorkbench.vue#L120) 只有 leaseStale 才展示接管。普通 paused 不满足条件，输入被禁用，媒体“重新连接”又因非 connecting/ready 直接返回。

按理想会话回包隔离 R01 后仍可复现：显示“已暂停”，没有恢复按钮。应在可恢复窗口显示明确 resume 动作，过期后引导新建，不依靠用户手动刷新。

### R08 · “重新载入最新版本”不更新表单，后续保存可能覆盖新内容

[DigitalHumanProfileForm.vue:174](../../src/views/digital-human/components/DigitalHumanProfileForm.vue#L174) 只监听 profile.id。冲突后重新获取同一 ID 的新 version，父层更新了期望版本并清除冲突，但表单仍是旧字段。

浏览器复现：服务器返回“其他页面保存的新描述”，输入框仍显示“本页尚未保存的旧稿”。再保存会带新版本号提交旧字段。应让显式重新载入触发表单重置，同时保留普通后台刷新不覆盖草稿的规则。

### R09 · 字幕结束后消失、分页截断，保存版本也没有推进

[useDigitalHumanTranscript.ts:67](../../src/views/digital-human/composables/useDigitalHumanTranscript.ts#L67) 直接用已保存列表覆盖当前最终字幕。结束时 Workbench 自动调用 reload；未保存时 API 返回空列表，本场仍在保存窗口内的可见字幕就消失。有保存内容时也只取默认第一页 20 条，忽略 nextCursor。

同文件 [saveNow:175](../../src/views/digital-human/composables/useDigitalHumanTranscript.ts#L175) 丢弃服务器返回的新 version。探针确认连续两次保存都发送 expectedVersion=1，即使第一次已返回 version=2。保存/导出失败又只返回 false，终态面板 `error` 固定传 null，用户看不到失败原因。

应分离易失字幕与已保存分页，保存成功同步版本并刷新保存状态；错误必须可见。探针已确认“空页覆盖”和“版本不推进”两种现象。

### R10 · 历史会话只能删除，无法查看已保存字幕与录制结果

[DigitalHumanHistory.vue:64](../../src/views/digital-human/components/DigitalHumanHistory.vue#L64) 的单行操作只有删除，虽然展示“录制 2 段 / 已存素材 1 个”，没有详情、字幕查看/导出、录制列表或素材跳转。需求文档 §6.4/6.5 要求的历史内容回访未闭环。

另外，[DigitalHumanWorkbench.vue:231](../../src/views/digital-human/DigitalHumanWorkbench.vue#L231) 对所有深链快照都展示“正在其他页面进行”和“接管”，包括 ended/failed。应提供终态只读详情，并按生命周期决定恢复/导出操作。

### R11 · 发送失败立即丢失输入

[DigitalHumanComposer.vue:139](../../src/views/digital-human/components/DigitalHumanComposer.vue#L139) emit 后立刻清空 text，父级异步请求失败没有回填机制。浏览器注入 503 后，错误可见但输入已空。应确认受理后清空，失败保留原文；不确定结果需按原幂等键查询/重试。

### R12 · 创建失败时弹窗消失，错误没有显示在配置区

[DigitalHumanWorkbench.vue:623](../../src/views/digital-human/DigitalHumanWorkbench.vue#L623) 先关闭确认框再 await start。失败写入 sessionError；配置区显示的是 startError，sessionError 只放在活动会话/接管卡中。

浏览器注入创建 503 后，页面没有任何对应错误提示，也没有持续的创建中反馈。应保留弹窗至明确结果，或在开始区统一显示 starting/sessionError。

### R13 · 活动会话下方重复出现完整角色配置和“开始新会话”

[DigitalHumanWorkbench.vue:253](../../src/views/digital-human/DigitalHumanWorkbench.vue#L253) 两个空态条件都包含 profileAreaVisible，但后面的 `v-else` 没有限制它。存在 liveSession/sessionSnapshot 时前两条件为 false，于是反而落到完整表单。

浏览器截图确认同时出现舞台、结束会话、角色编辑、开始会话和两个历史入口；390px 页面约 2897px 高。应将整个配置分支放进 profileAreaVisible 容器，明确配置/进行中/结束三个视图。

### R14 · 全局样式作用域漏接，暗色主题出现原生白底控件

[DigitalHumanWorkbench.vue:2](../../src/views/digital-human/DigitalHumanWorkbench.vue#L2) 根仅有 dh-page，而 [style.css:561](../../src/style.css#L561)、[style.css:617](../../src/style.css#L617) 等规则需要 `.gl-field` 祖先。表单自身带该类，所以部分字段正常；开始区、历史链接、重连按钮、外层错误提示没有继承同一套样式。

亮暗截图均可看到原生 select 和灰/白底的历史/重连按钮，暗色尤其明显。开始弹窗的表格也需检查 Teleport 后的样式作用域。应接上已有全局组件类/作用域，避免另外硬编码一套视觉值。

### R15 · 无可用渲染后端时，提示“可先保存角色”但实际没有编辑入口

[DigitalHumanWorkbench.vue:253](../../src/views/digital-human/DigitalHumanWorkbench.vue#L253) 在 approvedBackendIds 为空时只显示空态，整个角色表单不渲染；但描述明确承诺角色配置可以先行保存。浏览器复现 form count=0。

应决定并兑现一种行为：提供允许的草稿配置流程，或明确说明须先有可用组合才能创建角色，给可执行的下一步。

### R16 · 开场白与角色管理的 API 没有接到工作台

[useDigitalHumanApi.ts:262](../../src/composables/useDigitalHumanApi.ts#L262) 已有 requestGreeting，工作台和子组件没有调用，也没有需求 §6.5 指定的“播放开场白”按钮。用户能填写保存开场白却无法播放。deleteProfile 同样有 API，但角色列表只有选择/新建，没有删除入口。

应补充带状态/幂等处理的开场白动作，以及遵守运行中引用约束的角色删除入口。仅存在 API 定义不能算 UI 功能完成。

## 视觉体验的进一步判断

本轮样例在 **390px 与 320px 没有横向页面溢出**，主体亮暗主题有对应 token，已有表单标签/按钮与部分错误态结构。这些基础不需要推倒重做。

但初始页是占满整行的长表单，1440×900 首屏看不到“查看费用并开始”；形象选择只有名字胶囊，没有可视预览。对数字人产品而言，用户在开始前无法直观看到要使用的形象。建议在修复以上缺陷后，将“角色与形象预览—必要配置—开始”组织成更短的主流程，进行中集中展示舞台、字幕和控制。

“会话状态由服务端事实驱动”“UTC 半开区间”等内部术语不适合直接作为主要产品说明；“按住说话”实际是点击开始、再次点击提交，也应统一文案与行为。这些是附加体验建议，不纳入上述 16 项缺陷计数。

## 为什么现有测试通过仍不足以认定完成

1. [digital-human-workbench.spec.ts:305](../../tests/e2e/digital-human-workbench.spec.ts#L305) 的“完整对话流”只要求 turn 有 ID、状态 responding/ready、打断返回 2xx，注释明确允许停在 responding；没有要求实际字幕、可辨识输出音频和完成后第二轮。
2. 工作台组件测试给 pause/resume/end 注入完整 Session，绕过当前 Java 的真实回执；契约文件检查并不验证 Java 序列化结果。因此 R01 可以在大量绿灯下保留。
3. 音频接线成功测试主动共享两个 app 的 sessions 并注入 bridge，不能证明 Dockerfile 的双进程工厂已装配。
4. 本地 #105H 记录仍区分 LOCAL_ACCEPTED 和真实提供者门禁；#105-fix-2 当前说明也写着整任务未 VERIFIED。本次没有把这些历史记录升级为当前实测通过。

建议先补“生产入口 + Java 实际 JSON + 真实 Vue 按钮 + 输出结果”的纵向测试，再按修复影响做回归。尤其应覆盖：正常三秒以上语音、实际一次完整回复/第二轮、后台返回、取消迟到授权、保存后刷新与历史回访。

## 本次实际验证与资源记录

| 检查 | 本次结果 | 范围 |
|---|---|---|
| 前端数字人及机器契约 | 158 项通过 / 15 文件 | 单 worker；不是 Java 联调 |
| Python runtime 现有测试 | 143 项通过 | 顺序执行；含受控 fake/ASGI/媒体测试 |
| TypeScript 检查 | 通过 | `npm run typecheck` |
| 浏览器检查 | 完成 | Chromium，真实 AI 页面 + HTTP 受控数据；1440×900、390×844、320px，亮暗截图 |
| Java 返回形态回放 | 复现缺陷 | 接管 URL 含 undefined；结束回执未更新 UI |
| Python 追加运行探针 | 复现缺陷 | 正式工厂缺 bridge、正常 2.02s 音频被关闭、startTurn 零管线调用 |
| 三项追加单测探针 | 三项均复现 | 取消后迟到授权仍录音、字幕被空页覆盖、保存版本不推进 |
| Java 集成/完整 Compose E2E | 本轮未运行 | 本轮没有启动后端栈，不能据此声称通过 |
| 真实提供者与 iOS/Android 实机 | 本轮未运行 | 未验证真实音画质量、延迟、计费与移动设备 |

一次临时 Vitest 配置合并扩大了 include 范围，发现后已终止并收紧到单个探针文件；中断日志保留，不计作完整回归通过。收紧后的 3 项探针在 1 个文件内完成。

**本次资源计划与收尾（对应仓库 §9.3 要求）：**

- 开始已执行 docker compose ls / docker ps / docker stats 和进程盘点；Docker 可用，未发现运行中的项目容器或项目开发/构建/测试进程。已有 Playwright MCP 服务未动。
- 不启动应用后端栈；最小服务仅本次创建的 Vite `127.0.0.1:5173`，供前端 UI 检查。Python 采用进程内 TestClient/ASGI，无额外数据库/Redis/模型服务。
- 单 worker、重型测试串行；没有并行构建或多浏览器测试，也没有调用模型或删除数据。
- 浏览器已关闭，Vite 与本轮测试/探针进程已停止；中断测试留下的本轮 worker 也已精确终止。没有本轮新建且仍运行的服务。原有 MCP 服务保留。
- 没有停止他人容器，没有删除卷，没有全局清理。业务源码和原有未提交改动保持原状。

## 证据入口

- [测试与复现产物目录](../../test-artifacts/opentalking-review-2026-10-02)
- [前端测试日志](../../test-artifacts/opentalking-review-2026-10-02/vitest.log) / [Python 测试日志](../../test-artifacts/opentalking-review-2026-10-02/pytest.log) / [类型检查](../../test-artifacts/opentalking-review-2026-10-02/typecheck.log)
- [UI 复现记录](../../test-artifacts/opentalking-review-2026-10-02/ui-observations.json) / [协议回放记录](../../test-artifacts/opentalking-review-2026-10-02/ui-wire-observations.json) / [运行时探针](../../test-artifacts/opentalking-review-2026-10-02/runtime-observations.json) / [追加单测探针](../../test-artifacts/opentalking-review-2026-10-02/review-probes.log)
- [桌面配置亮色](../../test-artifacts/opentalking-review-2026-10-02/profile-desktop-light.png) / [暗色](../../test-artifacts/opentalking-review-2026-10-02/profile-desktop-dark.png)
- [移动配置亮色](../../test-artifacts/opentalking-review-2026-10-02/profile-mobile-light.png) / [暗色](../../test-artifacts/opentalking-review-2026-10-02/profile-mobile-dark.png)
- [活动会话与重复配置](../../test-artifacts/opentalking-review-2026-10-02/live-desktop-light.png) / [移动暗色](../../test-artifacts/opentalking-review-2026-10-02/live-mobile-dark.png)
- [移动历史暗色](../../test-artifacts/opentalking-review-2026-10-02/history-mobile-dark.png) / [结束深链错误接管](../../test-artifacts/opentalking-review-2026-10-02/terminal-deeplink-takeover.png)
