# 开发任务书：数字人会话收尾、排队晋升与 Offer 状态闸

> 模板版本：3.1.1 ｜ 任务编号：105-fix-2 ｜ 任务书版本：2.0.0
> 原编号：108（本次迁移替代，不保留第二份可执行规格）｜ 原稿创建：2026-09-27 ｜ 更新/事实核验：2026-10-02
> 规划负责人：Codex（主程规划）｜ 目标仓库：y-1，`/Users/LXH/claude/y-1` ｜ 当前分支：main（仅记录）
> 代码基线：`37d2b1569b0c2fdf9df2e82fa205355e235de5d0` ｜ 开始调查时 `git status --short` 无输出；无原有 tracked/untracked 改动
> 规格状态：READY_FOR_IMPLEMENTATION ｜ 实施状态：IMPLEMENTED（2026-10-02 C-01～C-05 落盘；C-01～C-04 卡级 VERIFIED；C-05 代码/静态/单测/契约门禁自验证通过，V-009 真实媒体帧验收被本机环境阻塞〔Docker Desktop 容器↔宿主 TURN relay 间歇不可达，实录 test-artifacts/task-105-fix-2/e2e-smoke/〕；V-004 java-check 失败经基线 clone 对照归因基线固有脆弱性；整任务未 VERIFIED，正式门禁由编排器复跑）
> 目标执行者：能力较弱的编码模型 ｜ 任务卡总数：5 ｜ 起始卡：C-01
> 执行模式：AUTO_CHAIN ｜ 顺序：C-01 → C-02 → C-03 → C-04 → C-05
> 交付终点：范围内实现、必要回归和本地真实集成验收；不含生产部署、真实收费提供者开放
> 决策依据：用户要求迁移原 108 并按现行模板重编；保留原稿的生命周期修复目标。当前源码为 FACT，本文 D 决策为授权范围内规划选择，不把原稿的历史会话/测试声明当作本次确认。

**阅读协议**：先核对版本、规格状态和用户实际派发范围；再读适用 AGENTS.md、§0、§1、§9、§10、§13、§14、当前卡及精确引用。C-04 还须读根 DESIGN.md。卡级通过后自动推进已授权下一卡；全部卡之后必须完成 §12.5。附 C 只是待派发提示词，本轮编写文档不授权实施。

**单一事实源**：场景动机在 §1.7，步骤/状态在 §4，业务判断在 §5，接口/传输类型在 §6，存储/事务在 §7，交互在 §8，文件权限在 §9.1，AC 只在 §11 责任卡定义，TC/V 只在 §12.2/§12.3 定义，汇报与续作在 §14。表内引用不得解释为额外权限。

## 0. 执行协议

### 0.1 词义与优先级

MUST/MUST NOT 为必须/禁止；SHOULD 为有记录理由才可例外；MAY 为可选内部细节。BLOCKED 表示经排查仍缺必要决定、环境或授权，只停止受影响链路。N/A 必须有不适用理由，与 NOT_RUN 不同。系统/开发者、当前用户要求、适用 AGENTS.md 优先；本文不授予豁免。

### 0.2 强制规则

1. 只修改当前卡与 §9.1 W 白名单的交集；只读依据不等于写权限。可决定局部变量、私有 helper 和等价 SQL 写法，不能临时改变业务条件、公开签名、错误码、依赖或范围。
2. 开工记录基线与既有 diff，增量保留可安全合并的改动；不要求干净工作区，不 reset/clean，不批量提交无关内容。发现已满足的步骤先核验，不能删掉重写。
3. 沿用已有响应式服务、会话、事件、媒体和测试设施；不新增依赖、不改变价格/角色/数据可见范围。新接口语义只能采用 §6。
4. 后续实施可做常规本地准备、运行浏览器与测试；启动、构建、Testcontainers 必须按 §9.3 单栈计划先盘点并通过守卫，重型任务串行。localhost 不是可删数据的依据；真实收费、生产和非隔离资源须有另行明确授权。
5. 使用合成账号；凭据、Cookie、私钥、签名 URL 不入文档/日志/截图。证据只包含脱敏状态、计数、耗时及测试 ID。
6. 不以跳过、放宽断言、假成功、零用例或 mock 替代真实集成。测试结果明确 PASS/FAIL/PARTIAL/NOT_RUN/SKIPPED/N/A。
7. 普通编码错误和可排查的本地环境问题自行处理；实质规格/范围冲突按 §13。已有授权不重复申请；不能用不相关生产门禁阻塞本地任务。
8. 按 §14 在对话交接，不默认另建报告。仅本轮规划时不运行业务测试、不起栈、不修改本书以外的业务/契约文件。

### 0.3 卡级完成定义

所有步骤和 AC/TC/V 已落实；必需验证没有 FAIL/PARTIAL/NOT_RUN/SKIPPED；新增 diff 未越 W 边界，原有改动保留；无未说明的行为变化/错误/调试代码；UI 卡完成 §8.8 实际看图；证据与偏差已按 §14 输出。卡级 VERIFIED 可由执行模型按证据判定，不能用文件存在或口头完成声明替代。整任务 VERIFIED 另须 §12.5。

### 0.4 每卡开始前

核对版本、派发范围、前置交付物、当前 HEAD/diff；依 §2 检查稳定符号和真实调用签名；运行该卡 V 的针对性基线（新增测试不存在时只跑原类，记录区别）；核对 W、环境、隔离和归属。等价行号漂移可记录继续，实质漂移按 §13。NOT_RUN 没有退出码。

## 1. 产品需求、目标与范围

### 1.1 一句话目标

让废弃会话可安全收尾、排队会话获得名额后自动接通，并在服务端阻止未派发会话的 offer 打穿 runtime。

### 1.2 背景与价值

当前 reaper 只推进到 ending，创建路径只派发 preparing，缺少排队晋升；前端创建后无条件连接媒体。这些代码组合会使残留会话占槽、排队用户无法自动开始。原稿记载的 2026-09-27 浏览器时间戳、账号及测试数量本轮未复现，不作为本版证据。

### 1.3 范围内

| REQ | 用户/场景 | 必须交付 | 责任卡 | 验收 |
|---|---|---|---|---|
| REQ-001 | 离开页面、旧会话已进入 ending | 确认 runtime 停止后持久化 ended，释放本人活动唯一位和全局槽；未知停止结果不能冒充释放 | C-02、C-05 | AC-001、AC-002 |
| REQ-002 | 全局名额已满而开始会话 | 保留排队；空槽后按 FIFO 自动派发；取消、失败、重启不超发或复活旧会话 | C-03、C-04、C-05 | AC-003、AC-004、AC-006 |
| REQ-003 | 排队/准备中/不可 offer 状态直调连接接口 | 服务端明确拒绝，保留身份与旧租约优先级，拒绝时无 runtime 调用 | C-01 | AC-005 |
| REQ-004 | 排队晋升、SSE 断连、刷新后显式接管 | live 通知与 GET 兜底都能驱动当前控制页接通；重复/迟到事件不双连、不覆盖新账号 | C-03、C-04、C-05 | AC-003、AC-004、AC-006 |

以上均为必需，没有可跳过增强项。

### 1.4 范围外与已知不处理项

- 不实现 LLM/STT/TTS 商业效果、字幕执行环、录制/头像新能力、runtime→Java 通用回执补齐；不改 Python runtime，不部署生产。
- 不改目录容量配置语义、价格、计费、组织上下文、权限；`QUEUE_MAX=10` 与目录 `maxQueuedGlobal` 未接线仍是已知不处理项。
- 不重写所有旧会话恢复和 SSE 协议，不修旧创建路径无晋升标记的 preparing 崩溃悬挂。只为本任务新增的晋升认领建立恢复（§7.2），不能声称全局 preparing 已治理。
- 主动 end 仅补本任务晋升在途的安全收尾分支；其它旧 end、paused/reconnecting resume 行为保持；其 Python epoch 同步及总体回收时限不借本任务扩为全链重构。
- 原共享契约 K01 的失联总释放 45s 目标当前源码未满足。本任务不调整既有 30s/60s reaper 判定；本地健康链阈值采用 §5 RULE-009，不能声称达到 45s。
- 原稿「runtime 任意异常均释放」「只 append 就能 live」「queued 已支持 resume」「视频出帧≤10s」不再作为实施依据；对应纠正在 §2、§3。不是新增收费或权限。

### 1.6 用户、入口与限制

所有已通过数字人个人鉴权的账号，归属固定个人，不依据组织/商家身份扩大权限。入口为 AI 应用 `/digital-human`；目录已开启、角色及 approved 后端/预检可用。依赖不可达时可保持 ending/preparing 以守住槽位，不承诺无限故障下有限时间接通；可恢复错误须有状态、日志与重试。业务验收无豁免；文档全仓索引的既有失败与本次增量判据见§2.7/§12.3，不伪装全仓PASS。

### 1.7 场景与闭环

| 场景/REQ | 动机与前提 | 流程 | 最终结果/恢复入口 |
|---|---|---|---|
| SC-01 / REQ-001 | 使用后直接关页，再次使用 | §4.1 SC-01 | 旧会话安全终态；本人能重新创建并消费画面 |
| SC-02 / REQ-002、REQ-004 | A 占唯一槽，B 等候 | §4.1 SC-02 | B 自动接通；刷新从原会话深链显式接管 |
| SC-03 / REQ-003 | 异常客户端绕过前端等待 | §4.1 SC-03 | 明确 409，runtime 零请求 |

术语：slot 是当前创建算法的全局容量名额；权威 state 是 `dh_session.state`，Vue 只作投影；`connecting` 不是媒体已出帧。需求来源为本轮指定的原稿目标，历史作者判断经当前事实校正。

### 1.8 成功标准

| 成功标准 | 观察方式 | 判据引用 |
|---|---|---|
| 安全释放后可再创建 | DB 状态/计数 + 同 owner 浏览器新建 | AC-001、AC-002；RULE-009 |
| 排队自动出画面 | 两个账号真实页面、网络/DB/视频计数 | AC-003、AC-004、AC-006；RULE-009 |
| 拒绝发生在服务端 | 正确身份/参数的单因子负例 | AC-005 |

### 1.9 未决问题与决策权限

无阻塞性产品问题。§3 是本轮授权范围内唯一实施方案；保留已有安全/个人权限/计费规则。运行环境实时占用在实施时依 §9.3 核验，它不是本轮已启动成功的事实；无法获得唯一栈时按 §13 阻塞验收。

## 2. 仓库上下文

### 2.1–2.2 目标端与设计规范

AI 端 `ai.html → src/ai/main.ts → src/ai/router.ts`；复用 `src/views/digital-human/`，遵循根 [DESIGN.md](../../DESIGN.md)。后端是 intelligence-service，经 edge-bff 公开代理。治理台无 UI 改动；共享样式不改。行为变更仍必须明暗主题截图，不沿用原稿的 UI N/A。

### 2.3–2.4 入口、源码与复用点

| 真实路径 | 稳定符号与核验事实 | 处理 |
|---|---|---|
| `src/ai/router.ts`、`src/ai/components/AiWorkspaceNavigation.vue` | 路由名 digital-human、导航 testid nav-digital-human | 原入口复用 |
| `src/views/digital-human/DigitalHumanWorkbench.vue` | handleStartConfirm/handleTakeover 无条件 connectMedia；798 行 | C-04 纯装配 |
| `src/views/digital-human/composables/useDigitalHumanSession.ts` | start/resume/end、generation、heartbeat、applySessionEvent | C-04 生命周期扩展 |
| `src/views/digital-human/composables/useDigitalHumanMedia.ts` | connect/retry/stop；ICE_TIMEOUT_MS=10000 | 复用，无新媒体实现 |
| `src/views/digital-human/composables/useDigitalHumanEvents.ts` | openEvents、onEvent、双水位、账号票据 | 复用；新等待层不得依赖它解决 GET 乱序 |
| `src/composables/useDigitalHumanApi.ts` | createDigitalHumanApi、getSession/resumeSession/openEvents | 复用真实解包/AbortSignal |
| `platform-java/services/edge-bff/src/main/resources/application.yml` | /api/digital-human 路由及 flag | 不增端点/不改代理 |
| `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanSessionService.java` | create/allocateAndInsert/readMaxSessions/casState/connectReady/end | C-03 提取共享 binding；保持 create 回执 |
| `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanSessionReaper.java` | runScheduled/runOnce/scanExpired/CLAIM_SQL | C-02 后置 finalize |
| `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanConnectionController.java` | webrtcOffer、validateOfferShape | C-01 新闸 |
| `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanGrantService.java` | assertSessionLease 已查 owner、terminal、epoch | 不重复造终态校验 |
| `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanLeaseService.java` | resume 只允许 paused/reconnecting，heartbeat 不支持 queued | C-03 只补 queued 接管 |
| `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanEventService.java` | append 只写 DB；publishLive 独立；stream/open 消费 live sink | C-03 必须提交后通知 |
| `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanRuntimeClient.java` | SessionBinding、RuntimeState、createSession/state/end；commandId 每次随机 | 复用；不能把随机命令号说成重试幂等依据 |
| `platform-realtime/digital-human/src/grassland_dh/routes_internal.py` | create_session 已存在同 sessionId 返回原实例；未知 session_commands 返回 404；end 返回 ended | 只读，不能据此声称商业 provider 停止 |

### 2.5–2.6 当前行为与差距

1. 创建在事务中锁 catalog 单例并计 active；active 是非 queued、非 ended/failed，包含 preparing/ending。排队最大 10。事务后 createdNow 且 preparing 才派发。仓库未发现 promotion worker。
2. reaper 每 5s 单向推进：queued 超 60s→ending、connecting 超 90s→failed、活动失联先 reconnecting 再 ending；不处理 ending。注释写过事务后 end，但实际未调用，事实以方法体为准。
3. offer 已按 requirePersonal → 形状 → assertSessionLease → runtime → connectReady。terminal() 是 ended/failed，已在 grants 拒绝；ending/queued/preparing 尚可穿透。connectReady 只接受 connecting/ready，不能把其它状态的 runtime 调用误记为既有成功路径。
4. 错误信封是 `{success:false,error:字符串,code:字符串}`，由 `IntelligenceErrorHandler.handle` 生成；原稿的 `error.code` 不真实。
5. SessionRowView 不含 owner/backendId；原稿只传 row 的 dispatchRuntime 无法保持真实 binding。casState 需要 PersonalActor；本版 §6 明确参数。
6. `dh_session.worker_id/worker_lease_expires_at` 已存在，当前 DH 生产代码只读这些字段；用于 §7.2 有前缀的晋升认领，无新 DDL。当前 expires_at 可为 null，不能把它当每次重试的必然超时保障。
7. UI 深链接管先调用 resume；queued 不受支持。旧 SSE 帧/回放有精简字段，本版只将事件作为状态刷新提示，不据旧帧伪造最新租约。

### 2.7 基线与来源

- 本轮只读核验 HEAD/分支/status、以上源码、V88 表/触发器、`IntelligenceItSupport`、DH Offer/Session/Reaper/Lease/Event/WorkerScheduling 测试、前端三个 composable 及测试入口。
- 规范已读：根 AGENTS.md、模板 3.1.1、根 DESIGN.md、`docs/架构/目录结构.md`、`docs/架构/本地启动与资源守卫.md`；共享契约 v2.1 的 K00/K01/K03/K04/K06/K07/K13.4 作为既有规则。
- 构建配置：Java toolchain 25、Spring Boot 4.1.0（Gradle version catalog）；Vue/Vite/Vitest 来自现行 package/lock，禁止升级。
- `IntelligenceItSupport` 自动启动一台 PostgreSQL 16 Testcontainer 与本进程 WireMock/Temporal test server；不是 PG+Redis 双容器。基座将旧三个 DH worker 关闭，新增 promotion 必须同样默认静默。
- 本轮业务测试、构建、浏览器、Docker 运行验证全部 NOT_RUN；未验证任何旧文宣称的测试数量、端口存活或账号可用性。
- 文档基线已核验：2026-10-02 `npm run docs:links`退出1，284份Markdown/2246本地链接、0链接错误、72份未索引；用HEAD原README+原108还原同一链接图后，72份名单完全一致，新增未索引0，本书已可达。只把本任务增量判为PASS，全仓结果仍FAIL（范围外历史文档）。`npm run docs:status`和`git diff --check`退出0；35 W/22 TC/10 V/9 RULE/6 AC/4 REQ的定义/引用及5卡计数核验通过。

### 2.8 事实、决策、fixture

§2 方法体/配置为 FACT；§3–§8 的新增要求为 DECISION；§12 合成账号/固定时刻/注入失败为测试 fixture，不是线上现象。规格就绪不等于实施完成。

### 2.9 影响与兼容

API16 增加排队错误；API12 补 queued 同 owner 显式接管；新增后台派发与状态事件生产者。沿用旧字段、状态枚举、端点与数据归属，无迁移。旧 UI 会看到明确 offer 409；新 UI 对旧后端能等待但不能凭空晋升，因此最终联调必须包含前后端变更。机器契约的错误枚举/端点说明与共享契约局部同步在 C-01/C-03 完成，不修改其它阶段完成状态。

## 3. 技术决策

| 决策 | 唯一选择与依据 |
|---|---|
| D-01 收尾 | reaper 增加 finalize pass，采用 RULE-002；沿用共享 K13.4「确认停止才释放」。舍弃原稿任意吞错释放，避免 runtime 仍活跃时超发；不要求外部回执执行环接线 |
| D-02 晋升 | 新 Spring worker，复用 catalog 容量锁、已有 worker 字段持久认领、同 sessionId 派发；失败按是否已受理分类，RULE-003/006/007。舍弃纯内存认领和无界刷新排队期限 |
| D-03 通知 | 状态 CAS 与 durable append 同事务，提交后显式 publish；live 只是加速提示，GET 为最终快照，RULE-008。舍弃“append 等于 live”与随机事件重放补写 |
| D-04 offer | 只放行 connecting/ready；其他枚举逐一见 RULE-001。保持 grants 的终态/epoch 顺序与现有 connectReady 真实成功集合 |
| D-05 前端 | 会话 composable 注入媒体连接器；等待/取消/乱序 guard 归 composable，视图只删直连并装配。深链接管复用原 API12 的 owner+takeover+epoch，不自动接管 |
| D-06 验收 | 5 卡串行；真实 Java/PG、真实 UI/Edge/Python 媒体与合成提供者分层验证，复用受守卫控制的 ci-e2e；不依赖旧机器临时脚本/裸跑 runtime |

以上为规划技术选择；冻结可观察结果、锁序、失败分类、时间口径和 W 边界，局部 helper 可等价实现。没有新增依赖。新增配置唯一登记在 §6.8。

### 3.1 端到端接线

| 链路 | 输入→处理→消费 | 卡/证据 |
|---|---|---|
| AI 导航/开始确认 | 原路由 → start → API08 → create → preparing 或 queued；UI 保存原 sessionId | C-04/C-05；TC-C05-001/002 |
| 后台收尾 | runScheduled → runOnce → scanExpired → finalizeEnding → runtime end/state → DB ended → 容量计数 | C-02；TC-C02-001～004 |
| 后台晋升 | @Component/@Scheduled → runOnce → 持久 claim → 共享 binding → runtime → CAS+append → publishLive | C-03；TC-C03-001～005 |
| 状态消费 | SSE onEvent 提示或 3s GET → 当前控制票据/最新快照 → 注入 connectMedia → offer/answer/media-ready → video | C-04；TC-C04-001～005；C-05 |
| 直调防线 | Edge → ConnectionController → grants → 状态闸 → runtime（仅允许态）→ connectReady | C-01；TC-C01-001～004 |
| 刷新恢复 | 深链 GET → 既有显式接管按钮 → API12 queued 接管或 preparing 等待 → 当前页等待器 | C-03/C-04；TC-C03-006、TC-C04-003 |

## 4. 目标行为

### 4.1 用户流程

**SC-01**：A 登录 AI 端，选择角色、查看费用并确认开始，见到媒体帧；关闭页面，停止心跳。保留既有 reaper 失联过程；ending 过宽限后按 RULE-002 确认停止并写 ended。A 重进创建新会话并出帧。若 runtime 停止未知，旧会话继续 ending 并占槽；依赖恢复后自动重试，不显示已完成。

**SC-02**：A 已占槽，B 在另一独立账号/浏览器 context 确认开始；B 显示排队而不 offer。A 通过真实结束按钮结束后，worker 认领 B；connecting 快照到达后 B 自动连接媒体。派发未受理失败回队列、已受理结果未知保持 preparing 查询；超出排队期限而未派发则 ending 收尾。B 排队中可结束；刷新后必须显式接管；prepared 短窗口不能接管时保留接管面并继续读状态，不偷换租约。

**SC-03**：以 B 的正确身份和当前 lease/mediaEpoch 直调 offer；queued/preparing 返回 §6.4 指定错误，runtime 零调用。不可用身份、参数、租约各自按原优先级拒绝。

### 4.2 行为变化

| 情况 | 新行为/保留项 |
|---|---|
| 排队/准备中 | 等待服务端，不把 accepted/connecting 当出帧成功 |
| 名额空出 | 单次 claim 原子占槽，再派发；不重新创建会话/预检/经济键 |
| 断网/失败 | 原会话只读重试；未知停止结果保留槽；页面保留可结束入口 |
| 终态/取消 | 不复活，不自动创建替代会话，不将浏览器 abort 当取消或退款 |
| 未登录/越权/参数错 | 沿用 §6.4；不从不同错误泄露他人会话 |
| 刷新/多页/换号 | 显式接管、票据校验、旧请求失效；不自动跨页恢复控制 |

### 4.3 状态定义

| DB 状态 | 控制/动作 | UI 引用 | 离开方式 |
|---|---|---|---|
| queued | 尚无 runtime 派发；owner 可结束或显式接管 | UI-01/UI-03 | 获槽 preparing；超时/结束 ending |
| preparing | 创建/晋升派发中，占槽；禁止 offer/接管晋升租约 | UI-01/UI-03 | 确认成功 connecting；确认未受理 queued；到期/停止 ending |
| connecting/ready | 允许 offer；ready 不等于 video 已出帧 | UI-02 | 既有行为 |
| listening/responding/paused/reconnecting | 原轮次/暂停/恢复行为；直调 offer 不替代 resume | UI-02/UI-03 | 原业务迁移 |
| ending | 停止进行中，仍占槽 | UI-04 | 停止证实后 ended |
| ended/failed | 终态不可恢复；保留历史/只读动作 | UI-04 | 无新迁移 |

UI `starting/ending/mediaConnecting/leaseStale/error` 为请求状态，沿用现有 ref，不新增公开状态枚举。新 worker 不建立独立面向用户的作业状态。

### 4.4 迁移及取消

新增链为 queued→preparing→connecting；仅确认未受理才 preparing→queued；失败超时/用户取消→ending→ended。CAS 需匹配本次认领 fence，规则与存储在 §5/§7。end 优先于迟到派发；CAS 失败不发布成功事件。恢复读取原行；新建只由用户重新开始触发。客户端取消轮询只释放本页读取资源，不能改变服务端会话或财务状态。

## 5. 业务规则

### 5.1 输入语义

无新用户字段。offer、resume 用 §6.2 的既有 DTO；requestId/epoch/controllerId 语义不变。新后台参数按 §6.8 固定默认和范围。UTC 服务端时钟为权威；UI 单调耗时只用于停止本地等待，不伪造业务超时。

### 5.2 校验顺序

**RULE-001（REQ-003）**：requirePersonal → 严格解析/validateOfferShape → assertSessionLease（owner→既有终态→epoch）→ 新状态读取。connecting/ready 放行；queued/preparing 返回 dh_session_queued；ending/listening/responding/paused/reconnecting 返回 dh_state_conflict；ended/failed 已由 grants 拒绝。并发状态改变由 connectReady 最终 CAS 防止返回假 answer。拒绝零 runtime 调用、零会话/事件/账务写入。TC-C01-001～004。

### 5.3 生命周期判断

- **RULE-002（REQ-001）**：finalize 只取 ending 且 `state_entered_at < now−15s`，每轮最多100个、逐项串行。runtime.end 在事务外且每项最多5s；返回 state=ended/failed 且 cleanupPending=false，或确认该 session 的 state/end 返回404 dh_not_found且不存在已发送未决晋升标记，才允许 ending→ended。晋升已发送未决标记的404只能证明当前没查到，不能排除迟到create；保留ending直至查到并停止原实例或取得原派发明确拒绝结果。503/超时/未知异常记录类型并留 ending 下轮重试；不吞成成功、不影响后续行。CAS 匹配读取的 state_entered_at/version，写 ended_at（已有值不覆盖）、state_entered_at、updated_at、version+1；不清理账务/cleanup_pending。并发可重发幂等 end，不能承诺网络恰一次；DB终态只生效一次。TC-C02-001～004。
- **RULE-003（REQ-002）**：每轮最多晋升1个。候选按 created_at、id FIFO，跳过非 active owner/已删除行；先确保候选 owner gate 行存在并取 FOR SHARE，再锁 catalog 单例，重查容量、当前 FIFO 候选和 session 行；候选改变则释放事务并下轮重选。容量口径沿用 create；maxSessionsGlobal 解析复用 readMaxSessions（1～100夹限，非法缺省1），不得另写解析器。认领、占槽和 fence 一起提交。newSessionsAllowed 只挡新建，不撤销已入队会话；目录缺行/无法读取时不新增认领，恢复查询与收尾仍进行。TC-C03-001/002。
- **RULE-004（REQ-002/004）**：queued/preparing 期间零连接器调用、零 offer；只从当前控制页最新 GET 确认 connecting/ready 后触发连接。SSE 仅提示刷新，不能凭精简/旧事件直接建立媒体。TC-C04-001/002。
- **RULE-005（REQ-004）**：queued 接管仍校验 owner、旧 epoch、controllerId/takeover 和 operation 幂等；成功只 epoch+1、更换 controllerId、更新 lease 到期，state 保持 queued，排队时钟/FIFO不变。preparing 返回原 dh_state_conflict，前端保留接管面；不得改正在派发的 epoch。queued heartbeat 仍不发送。TC-C03-006、TC-C04-003。
- **RULE-006（REQ-002）**：dispatch 成功需要 runtime 返回同 sessionId/leaseEpoch/mediaEpoch 且可连接状态，再在当前 fence 下 CAS connecting 与 append 同事务。派发前必须按§7.2持久化sent标记，再发网络。明确未受理（发送前本地失败，或当前runtime create入口明确返回未启用的HTTP503拒绝）并确认state404，才可回queued；连接超时/5xx未知结果不得仅凭一次state404推断未受理。存在且绑定匹配按成功收敛；未知则保留preparing/sent标记并只查询，不重复create。已终态/不匹配绑定走 ending 和收尾，不复活。发生end/freeze竞态时CAS不成功，不发connecting；仅当前DB已ending/terminal或gate非active才补偿停止该runtime，不能因旧fence落败而停止新持有者合法活跃实例。end若发现本任务sent标记只受理成ending，保留marker和容量，等待原派发结果/收尾确认；不得沿旧end分支立即CAS ended。TC-C03-003/004。
- **RULE-007（REQ-002）**：仅恢复 §7.2 标记的 preparing。认领租约30s，过期由另一轮 CAS换 fence 后查询同 sessionId，按 RULE-006 收敛，不依赖进程内记忆。仅reserved未发送标记或原派发明确拒绝且确认不存在可回queued，sent未决时404仍保持占槽；队列总等待截止为创建时间+60s（age>=60s到期）；回退和接管不能无限续60s。到期未派发进 ending。过期自停/远端停止未证实不释放槽。TC-C03-004。
- **RULE-008（REQ-004）**：每次成功晋升的 connecting CAS 与一条 session.state durable 事件同事务；事务提交后才能发布 live，回滚没有通知；通知丢失用 GET/replay恢复，不补写随机重复事件。UI 等待读取每3s一次、单请求在途，单请求5s超时；SSE到达合并成一次即时读取。每个控制代次最多自动连接一次；重复 SSE/轮询不得再连。GET/session事件仅在 account ticket、generation、sessionId、controllerId、epoch 均仍有效时消费；同代次旧 seq/先发后到结果不得回退，finally/error同样受 guard。TC-C03-005、TC-C04-002/004。
- **RULE-009（时限与范围）**：健康本地 runtime 条件下：已进入 ending 的收尾≤25s（15s宽限+5s调度+5s调用）；从最后一次正常心跳到自动收尾的验收上限90s；空槽到 connecting≤10s，connecting到可消费视频≤20s。后两者分开计时，ICE自身可能占10s，禁止合并成“10s出帧”。这些是单个候选/最小本地栈阈值，批量100条/外部故障不承诺相同时限。UI 等待从本次 start/成功接管开始最多120s，超限停止自动读取/连接，提示 UI-05；不自行结束会话。TC-C02-001、TC-C05-001/002、TC-C04-005。

### 5.4 权限与不变量

owner 关系与 account gate 沿用现有实现；匿名401、跨owner404、旧epoch409，具体码见 §6.4。worker 不能读写组织/财务域，不能复活终态。queued 接管是原稿刷新场景的必要接线，仍限同owner显式操作，未新增角色权限。快照/事件不含正文或凭据；用户媒体输入、收费/退款、经济键不因重试增加。容量、幂等、迟到结果保护分别以 RULE-001～008 为唯一判断源。

## 6. 接口契约

### 类型与调用签名

新增跨模块 Java 声明如下（方法签名契约，不是可直接复制的完整类）；沿用所属文件已有包/import：

```java
// DigitalHumanSessionService：只提取 binding+runtime 调用，不在此做业务 CAS。
Mono<DigitalHumanRuntimeClient.RuntimeState> dispatchRuntime(
    DigitalHumanAuthorization.PersonalActor actor, SessionRowView row, String backendId);
// readMaxSessions 保持原实现，仅改包内可见供 worker 复用。
static int readMaxSessions(String configJson);
// DigitalHumanSessionReaper：runOnce 在 scanExpired 后调用；原 scanExpired 返回语义不变。
Mono<Integer> finalizeEnding(java.time.Instant now, int limit);
// DigitalHumanSessionPromotionWorker：包内测试入口；调度真实调用它。
Mono<Integer> runOnce();
// DigitalHumanEventService：只发布已提交 append 的同一事件；复用现有 frame/publishLive。
void publishSessionState(String sessionId, AppendResult result, java.util.Map<String, Object> payload);
```

`dispatchRuntime` 必须复用 profileRevision/头像绑定、expiresAt/leaseExpiresAt缺省的现有装配。actor仅取已锁行owner，不接受客户端owner；create 保持原 casState、markInitFailed、translate 和 operation 返回语义。worker 从自己锁定的行取得 backendId，不能用 `"replay"` 或假默认后端。

```ts
// useDigitalHumanSession 返回新增；Session 沿用 src/types/digital-human.ts。
setDispatchConnector: (fn: (session: Session) => Promise<void>) => void
// resume 返回 boolean：仅成功且仍属当前票据时为 true，失败/过期回包为 false。
resume: (takeover: boolean) => Promise<boolean>
```

会话模块不 import 媒体模块；Workbench 在媒体装配后注册连接器。start 返回 Session|null 不变，queued立即返回；未注册连接器只投影状态（单测/只读场景），生产装配测试必须证明已注册。新等待 helper 私有；不新增HTTP接口。

### 6.1 请求信息

| API | 现有端点 | 载体/身份/结果消费 |
|---|---|---|
| API-001（共享API16） | POST /api/digital-human/sessions/{id}/webrtc/offer | JSON；Cookie→Edge内部身份断言→个人鉴权；媒体层消费answer |
| API-002（共享API12） | POST /api/digital-human/sessions/{id}/resume | JSON；同owner、显式takeover；会话层消费Session |
| API-003（共享API10/18） | GET /api/digital-human/sessions/{id} 及 /events | owner只读；events保留afterSeq/Last-Event-ID既有校验，text/event-stream |

Edge路径/flag沿用 §2.3，不增加绕过代理的直连。offer保持原重试入口，不加后台自动无限重试；resume同次在途只发一次，若重发同次动作则复用requestId。等待读取取消只取消GET。

### 6.2 请求参数

offer 引用 `DigitalHumanConnectionController.OfferRequest`：requestId UUID、leaseEpoch Long、mediaEpoch Long、sdp String、type String；同原严格形状与未知字段校验。resume 引用 `DigitalHumanSessionController` 的现行 resume 解析与 `DigitalHumanLeaseService.resume`：requestId/leaseEpoch/controllerId/takeover 不增字段。合成有效 offer：

```json
{"requestId":"00000000-0000-4000-8000-000000000001","leaseEpoch":1,"mediaEpoch":1,"sdp":"v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n","type":"offer"}
```

此 SDP 只用于 controller fixture；真实媒体必须使用浏览器生成的完整 offer。

### 6.3 成功响应

API-001：200、Cache-Control no-store、`{success:true,data:{sdp,type:"answer",mediaEpoch,iceServers}}`；只在connectReady成功后返回。API-002：200、既有 `{success:true,data:Session}`；queued 接管响应仍queued。API-003 GET：既有 SessionSnapshot，前端复用 API 解包，不把 EndResult 当 Session。所有保持现有字段空值，不添加第二层 data。

### 6.4 错误契约

| HTTP/code（顶层） | 条件与处理 | error文案/交互 |
|---|---|---|
| 409 dh_session_queued（本次唯一新码） | RULE-001 queued/preparing，等候后可连 | 「会话正在排队等待空闲名额，请稍候。」；UI-01 |
| 409 dh_state_conflict | 新闸拒绝的其余状态；刷新状态而不盲重试offer | 「会话状态已变化。」；旧grants终态保持「会话已结束。」 |
| 409 dh_state_conflict | RULE-005 preparing接管不支持 | 原文案「会话当前状态不支持恢复。」；UI-03 |
| 409 dh_lease_stale / dh_takeover_required | 旧epoch/需显式接管，维持先后顺序 | 原服务端文案；UI-03 |
| 401 dh_auth_required | 未登录/身份过期 | 原登录提示，停止等待读取 |
| 403 dh_account_unavailable | 非个人身份或账号生命周期拒绝 | 原提示，停止新派发 |
| 404 dh_not_found | 他人/不存在资源 | 原「资源不存在。」；不得区分两者 |
| 422 dh_invalid_input | 原请求形状错误 | 原文案，零runtime |
| 503 dh_runtime_unavailable / 无响应 | 原runtime异常/连接超时 | 服务端已有错误或前端网络兜底，不能假定未受理 |

错误示例：`{"success":false,"error":"会话正在排队等待空闲名额，请稍候。","code":"dh_session_queued"}`。机器契约 errors、ErrorCode、API16.errors 与合成样例须同步新增码；不改为 error.code。

### 6.5–6.6 错误处理与观测

媒体错误仍由 useDigitalHumanMedia 的 offer_rejected/errorMessage 展示；不把排队作为媒体错误。日志允许脱敏sessionId、fence、状态、耗时、错误类型；禁止完整SDP、Cookie、key、正文、provider原始异常详情上屏。worker单项错误不使整轮停摆，也不记录为成功。关键计数及禁止副作用见 RULE-001～008/对应 TC。

### 6.7 事件与异步

晋升事件类型沿用 session.state；payload固定 `{state:"connecting",reasonCode:"queue_promoted",expiresAt:行值或null,pausedUntil:行值或null}`。eventId为本次成功事务生成的UUID、seq由append分配，同事务回滚一起撤销。持久表只存现有payload结构，不加正文或私有恢复标记。publishSessionState复用当前序列化，构造与durable回放相同的v/eventId/sessionId/seq/type/payload后调用publishLive；不得在事务完成前调用。精简帧不是最新租约证明，前端收到后必须GET（RULE-008）。旧SSE gap/重连机制继续复用；未接通的完整K06通用协议不在本卡伪装完成。

Multipart/上传/Range：N/A，本次不改这些接口。轮询、上限、重复/取消语义仅见 RULE-008/009。

### 6.8 配置

| 属性 | 默认/范围 | 登记位置与用途 |
|---|---|---|
| digital-human.reaper.finalize-grace-seconds | 15；1～60整数，非法启动失败 | application.yml与构造参数；仅finalize宽限 |
| digital-human.promotion.enabled | true；boolean | application.yml；测试基座false；仅控制新worker调度 |
| digital-human.promotion.poll-interval-ms | 5000；正整数 | application.yml/@Scheduled；调度测试250 |

认领租约30s、队列60s、单轮1个和客户端等待参数为本版内部常量，不新增部署环境菜单；原reaper enabled/poll参数保持。没有生产.env变更。

## 7. 数据模型与迁移

### 7.1–7.2 存储结构与映射

权威表为 intelligence V88 的 dh_session/dh_event。无新列、表、Flyway文件、公开枚举。状态写入与事件payload语义见 §4/§6.7。

| 字段 | 本次用途/转换 | 写入条件 |
|---|---|---|
| dh_session.worker_id | 晋升claim先为 `promotion:reserved:`+服务端UUID，网络前CAS为 `promotion:sent:`+同UUID；共同promotion前缀标识本任务，末尾UUID为fence | queued→preparing时写reserved；只有state仍preparing且fence有效才能改sent并发请求；成功/确认未受理回队列/确认停止后清空，用户end不能提前清sent |
| worker_lease_expires_at | claim到期UTC时间，now+30s；不是浏览器lease，过期不等于远端停止 | 认领/恢复CAS刷新并换UUID，保留reserved/sent阶段；成功/确认回队列清空；旧worker须比对fence+epoch才能写 |
| state_entered_at/version | 每次真实状态变化推进；重领fence只更新worker租约/updated_at/version，不伪造状态迁移 | CAS expected state、worker_id、leaseEpoch；version用于快照并发保护 |
| created_at | FIFO及本次晋升重试的总排队截止基准 | 永不更新；避免重试/接管续命 |
| ended_at | 已确认终态时间，COALESCE保留原值 | RULE-002 |
| dh_event | owner_account_id继承session；seq/eventId唯一 | CAS成功同事务append；提交后live |

sent阶段因服务在发送前后崩溃产生的不确定性必须保守保留，404不构成停止证明；本轮不宣称所有远端未知故障都能在有限时间释放，日志需单列未决项。旧 `worker_id` 无 promotion 前缀的 preparing 不由本worker恢复。测试先证明当前没有其它写入者使用该前缀；若后续基线新增冲突按 §13，不复用他人租约。

### 7.3 兼容与迁移

无DDL/回填，不改已执行迁移；旧行按原状态读取，不伪造认领。旧应用不认识本前缀时仍能读取session，治理元数据可出现内部worker标识，不赋予新权限。代码回退前须停止新认领并让本任务标记的preparing收敛，不能直接留下旧代码不会恢复的行；本地回退流程见 §12.6。

### 7.4 事务、并发与生命周期

claim/reclaim遵循 owner gate→catalog→session 锁序；gate缺行先按V88模式 INSERT account_id ON CONFLICT DO NOTHING，再FOR SHARE。每轮一owner，不先锁catalog再拿别人的gate。候选读不加锁仅供选择，事务中必须复查。网络调用绝不在DB事务/行锁中。

事务后派发；成功提交连接状态与事件的事务需要重新获取owner gate并校验fence/epoch/state，失败回退同样校验，取消/冻结已先行则不能CAS回活动态。冻结后事件写入被原触发器拒绝时不得绕过；停止runtime并保留可由既有清理链继续处理的状态。未知远端停止保留槽，不因日志警告释放。

事件/会话仍由owner归属，dh_event随既有会话清理；本任务不增加正文、缓存种类、保留期限或对象存储。生命周期注册已包含dh_session/dh_event；不用新登记一套资源，运行`quality:lifecycle`并回归DH Erasure/Cleanup。

### 7.5 迁移证据

N/A：无迁移。已有行兼容、fence重启恢复和回退排空由 TC-C03-004/TC-C05-003 验证，不以“无DDL”豁免异步恢复。

## 8. UI 实现规格

### 8.1–8.3 页面与组件

AI `/digital-human`，继续复用 DigitalHumanStage、开始确认框、接管卡、结束面板及现有会话错误区。视图只装配composable，等待/请求和代次逻辑不得塞回SFC。无新页面/组件/样式系统。

### 8.4 交互项

| UI编号 | 行为与文案 |
|---|---|
| UI-01 排队 | 展示既有「排队中」徽标与「等待媒体…」；preparing展示既有准备态；starting在创建返回后解除，mediaConnecting=false、媒体错误清空。输入继续依会话态禁用，「结束会话」可操作 |
| UI-02 接通 | verified GET达到RULE-004后启动原媒体连接；显示原连接中提示，真实视频出帧才算接通。重复事件不重新闪屏/建peer；不新增轮次/自动发言 |
| UI-03 刷新接管 | 保留原显式接管按钮及提示；resume成功才关闭接管卡和启动等待/SSE。preparing拒绝时保留卡，GET回queued或可恢复态后可再点；不自动takeover。旧epoch提示沿用服务端文本 |
| UI-04 结束 | 点结束立即停止自动连接与等待请求；请求中按钮禁用。成功以真实Session回读进入ending/终态；失败恢复结束入口，不把客户端停止等同服务端ended。终态可回原配置入口重新开始 |
| UI-05 等待失败 | 单次读取网络错保留旧状态，下轮重试；401/404停止并显示既有登录/不存在提示。120s上限提示「会话状态暂未更新，请刷新后查看或结束会话。」并停止自动等待；提供原刷新/结束入口，不伪造失败终态 |

开始确认期间沿用禁重复提交；失败保留角色与确认前输入。媒体重试只操作原Session，queued/preparing时不得调用重试连接器。状态变更不抢焦点；接管/结束失败焦点留在原动作。

### 8.5–8.7 布局、视觉与无障碍

桌面1440×900、移动390×844，沿根DESIGN断点/全局token；窄屏单列无整页横向滚动，40字合成角色名可换行。颜色/字体/间距/圆角不新增值，禁止硬编码hex；Space Grotesk/Inter不变。复用现有错误区可读文本、按钮键盘操作与focus-visible，等待状态配文字。若不得不新增样式，先按 §13 补精确W和亮暗token，不能擅改全局主题。

### 8.8 截图自查

C-04用UI专项夹具，C-05补真实成功链；两者区分标记。桌面和移动各覆盖暗/亮：排队、connecting、出帧、ending/ended、等待错误、接管中；初始/空/创建提交中复用既有路径做回归。文件模式 `test-artifacts/task-105-fix-2/ui/ai-digital-human-主题-状态-视口.png`，词项必须换成实际值（dark/light、queued等、1440x900/390x844），不得带真实账号。每张必须实际查看对比度、层级、间距、长名、溢出；再以键盘验证Tab顺序/按钮可达、焦点不丢。UI行为改动不接受“无样式所以N/A”。无新表格/上传/共享组件，其额外页面截图N/A。

## 9. 全局约束

### 9.1 文件白名单与黑名单

本表是**后续获授权实施**的全局W表；本轮仅迁移W12原文件与增量修改W11，不提前实施下面任何源码/契约/测试。下表现存路径已核对；标“新增”的文件当前未存在，不得当作前置交付物。

| W | 精确仓库相对路径 | 操作 | 允许符号/目的 | 卡 |
|---|---|---|---|---|
| W01 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanConnectionController.java` | 修改 | webrtcOffer状态闸及私有辅助 | C-01 |
| W02 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanOfferIT.java` | 修改 | 单因子状态/身份负例、原中继回归 | C-01 |
| W03 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanSessionReaper.java` | 修改 | finalizeEnding、注入runtime/宽限、runOnce接线 | C-02 |
| W04 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanReaperTest.java` | 修改 | 固定Clock/fake runtime、构造器适配、finalize测试 | C-02 |
| W05 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanSessionService.java` | 修改 | 提取dispatchRuntime、readMaxSessions包内可见、create等价调用；end仅补promotion在途分支 | C-03 |
| W06 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanSessionPromotionWorker.java` | 新增 | 组件、调度/claim/reconcile/事务+事件 | C-03 |
| W07 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanPromotionIT.java` | 新增 | 真实DB并发/受理未知/崩溃恢复/事件事务测试 | C-03 |
| W08 | `src/views/digital-human/DigitalHumanWorkbench.vue` | 修改 | 注册connector、start/takeover/retry装配与既有反馈；不得加域逻辑 | C-04 |
| W09 | `src/views/digital-human/composables/useDigitalHumanSession.ts` | 修改 | 等待器、connector、resume结果、全异步guard、命名listener清理 | C-04 |
| W10 | `src/views/digital-human/composables/useDigitalHumanSession.test.ts` | 修改 | 等待/接管/乱序/卸载/超时测试 | C-04 |
| W11 | `docs/任务书/README.md` | 修改 | 仅本任务索引状态，保留其它行 | C-05 |
| W12 | `docs/任务书/草场任务书-105-fix-2-数字人会话收尾排队晋升与Offer状态闸.md` | 修改 | 卡状态/检查点所需版本修订；不追加独立完成报告 | C-05 |
| W13 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanLeaseService.java` | 修改 | 只扩queued显式接管，保持既有paused/reconnecting分支 | C-03 |
| W14 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanLeaseIT.java` | 修改 | queued接管/同键重放/两控制页负例 | C-03 |
| W15 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/digitalhuman/DigitalHumanEventService.java` | 修改 | 新增publishSessionState，复用frame/publishLive；不重写通用事件协议 | C-03 |
| W16 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanEventIT.java` | 修改 | 生产通知方法的live/replay与回滚负例 | C-03 |
| W17 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/IntelligenceItSupport.java` | 修改 | 只追加digital-human.promotion.enabled=false测试属性 | C-03 |
| W18 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanWorkerSchedulingIT.java` | 修改 | C-02构造器兼容；C-03调度真实驱动与防重入/禁用用例 | C-02→C-03 |
| W19 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/digitalhuman/DigitalHumanSessionIT.java` | 修改 | create提取回归断言；不得放宽既有结果 | C-03 |
| W20 | `platform-java/services/intelligence-service/src/main/resources/application.yml` | 修改 | 只登记§6.8三个配置，先C-02后C-03 | C-02→C-03 |
| W21 | `contracts/digital-human.v1.json` | 修改 | errors/ErrorCode/API16错误集；API12 queued说明；无新端点 | C-01→C-03 |
| W22 | `contracts/digital-human.v1.examples.json` | 修改 | 排队错误与queued resume合成合法/非法样例 | C-01→C-03 |
| W23 | `tests/contracts/digital-human.contract.test.ts` | 修改 | 新错误枚举/端点/样例真实断言与删码负例 | C-01→C-03 |
| W24 | `docs/任务书/草场任务书-105-数字人工作台共享契约.md` | 修改 | K01/K03/K04/K13.4局部补充本任务差异并引用本书；不重写历史结果 | C-01→C-03 |
| W25 | `src/views/digital-human/DigitalHumanWorkbench.test.ts` | 新增 | 真实视图装配/按钮/接管结果与媒体connector组件测试 | C-04 |
| W26 | `tests/e2e/digital-human-session-recovery.spec.ts` | 新增 | §12.2真实浏览器场景、截图和视频帧断言 | C-05 |
| W27 | `scripts/acceptance/verify-105-fix-2.sh` | 新增 | §12.3唯一验收phase入口、守卫/清理/产物/返回码 | C-01→C-02→C-03→C-04→C-05 |
| W28 | `tests/deployment/digital-human-fix2-runner.test.ts` | 新增 | 假Docker静态/调用链测试：无全量/无额外服务/失败清理 | C-05 |
| W29 | `tests/e2e/fixtures/digital-human-fix2.compose.yml` | 新增 | 只给本任务测试TURN和runtime/Java测试环境接线 | C-05 |
| W30 | `scripts/ci-e2e.sh` | 修改 | 仅DH_FIX2_E2E=1时附加W29与dh-turn；默认及其它任务分支不变 | C-05 |
| W31 | `tests/e2e/fixtures/digital-human-session-recovery.ts` | 新增 | 两账号、catalog/后端合成种子、作用域DB查询/cleanup；复用既有fixture函数 | C-05 |

| W32 | `test-artifacts/task-105-fix-2/` | 仅生成/留证 | 阶段日志、截图、DB脱敏快照、测试TURN短命配置；私钥不得提交 | 各卡 |
| W33 | `test-artifacts/task-105/E/dh-test-certs/`、`test-artifacts/task-105/A02/`、`test-artifacts/playwright/`、`test-artifacts/playwright-results.xml`、`playwright-report/`、`test-artifacts/docs-links/`、`test-artifacts/compose*.txt`、`test-artifacts/compose.log` | 仅工具生成 | 现有工具固定输出；C-05复制本次脱敏有效证据至W32，不删除别次产物 | 各卡 |
| W34 | `platform-java/**/build/`、`dist/`、`coverage/` | 仅构建/测试生成 | 既有生成目录，不赋予人工修改源码或依赖权限 | 各卡 |
| W35 | `tests/e2e/digital-human-session-waiting-ui.spec.ts` | 新增 | 仅C-04 UI专项夹具/真实视图/主题截图，明确与C-05真实后端链分开 | C-04 |

只读依据：AGENTS/DESIGN/模板、§2现有API/RuntimeClient/Python代码、V88与所有迁移、数字人媒体/事件composable、既有E2E fixture、`scripts/local-stack.mjs`/`scripts/lib/local-stack.sh`、生产与DH基础test Compose、生命周期登记。可以扩展只读调查，不能自动扩展W。

黑名单：真实.env/凭据、`platform-realtime/**`、全部数据库迁移、生产部署文件、全局样式/字体、无关业务/旧任务书、依赖及lock文件。不新增报告文件。W33通配符仅覆盖工具产物，不用于源码写入。开始调查无已有改动；每卡仍重查，索引须精确替换本任务行，不覆盖整个文件。

### 9.2 铁律

仓库根AGENTS全部适用；WebFlux链路不block，worker只在既有@Scheduled顶层订阅，业务方法返回Mono；网络在事务外；fallback副作用用defer。所有状态/幂等/owner由Java和真实PG保证，前端禁用按钮不是权限防线。保持三入口/Cookie/Edge断言，不改CSP或代理规则。不新增AI消费路径，不绕价格/预算/个人生命周期。

### 9.3 环境、最小服务与切换/清理计划

**本轮规划**：只读源码/配置与文档校验，无需Docker；没有启动/停止任何服务，未盘点全机运行栈，不能据此声称全机无服务。以下为实施时必须执行的计划。

| 阶段 | 唯一目标栈/最小真实服务 | 复用/切换与收尾 |
|---|---|---|
| 静态/前端单测 | 无服务；根目录Node/npm现有lock依赖 | 不启动Docker。重型命令仍经stack run无--docker持锁；串行 |
| C-04 UI专项 | 一台现有 `vite --mode ai` 前端，HTTP全部采用明确UI夹具，无后端/Docker；启动前同样核对是否已有兼容Vite | 复用已确认兼容前端，或协调停闲置旧实例后由W27启动单实例；端口5173严格占用检查；退出只停止本次子进程 |
| Java定向/模块check | 无本项目应用栈；IntelligenceItSupport的单个PG Testcontainer，必要Ryuk；WireMock/Temporal test server同测试进程 | 先盘点并协调停已确认闲置项目应用栈，保留卷。不能与开发栈并行。Gradle单worker、无并行、一个JVM测试fork；退出释放本轮Testcontainers |
| C-05真实E2E | 唯一fresh项目 `y1-e2e-local`；基础 `docker-compose.yml` + `deploy/digital-human/compose.test.yml` + W29；显式目标frontend、redis、dh-runtime、dh-turn | 由守卫展开真实依赖逐个启动；不同时保留旧应用栈/裸跑runtime；运行后仅清本fresh资源。fresh冲突不换项目名/端口规避 |

E2E实际依赖白名单为：postgres-local、database-bootstrap（一次性）、kafka、temporal、minio、minio-init（一次性）、identity-service、finance-service、trust-service、intelligence-service、marketplace-service、edge-bff、frontend、redis、dh-redis、dh-runtime、dh-turn。frontend→edge→五领域服务为当前Compose强依赖；redis是内部身份重放守卫真实软依赖；dh-redis是runtime声明依赖；dh-turn用于Docker Desktop下宿主浏览器可达的真实媒体。不是“只启动4个容器”的承诺。禁止Hypit、观测、release-migrator及额外媒体worker。

**启动前盘点**：依次 `docker compose ls`、`docker ps`、`docker stats --no-stream`；再读容器project/working_dir/config_files标签、端口和卷归属，用 `ps -axo pid,ppid,command` 核对本仓库Vite/Java/Gradle/Playwright/Python/build进程（日志脱敏）。Docker不可用不能当作空栈。说明现存服务、复用对象、需停止且确认闲置的项目服务、目标白名单与收尾；归属未知/仍有人使用只协调，不擅停也不另开栈。用 `npm run stack -- check --project y1-e2e-local` 预检，真正变更仍经守卫带锁入口。

**执行资源**：JDK25用 `scripts/lib/java-runtime.sh` 的 ensure_java_runtime；现行Gradle测试堆上限5g，模块check必须独占重型阶段。浏览器Chromium单worker；本次没有新依赖，不安装模型/GPU/真实提供者SDK。出现持续swap/OOM/反复健康超时先停止新增加载，释放本任务资源，不叠加重试。

**夹具**：Java仅用Testcontainers库，可执行基类现有清表；严禁把该测试连接改指开发库。E2E仅fresh空库，两合成个人账号+一个合成管理员，账号名含固定测试前缀与runId；复用`tests/e2e/fixtures/task-104.ts`的registerAndLogin/accountIdOf/query和digital-human.ts的登录/导航。角色/目录/后端种子参照现有DH lifecycle spec，所有商业提供者关闭。A/B不共享账号，避免owner唯一约束掩盖全局容量行为。

**媒体夹具（C-05必须交付，不留给执行者选方案）**：W29新增测试dh-turn，镜像沿用`coturn/coturn:4.6.1`；仅宿主loopback暴露3478 tcp/udp和49160–49200有界relay端口；同default+dh-internal网络。W27在W32生成短命测试conf和随机测试用户名/密码，realm=grassland-test、external-ip=127.0.0.1、min/max-port如上、禁CLI/组播、不启TLS监听；仅隔离测试允许loopback/private peer以接通本机，不能修改生产TURN限制。runtime注入DH_TURN_URI=`turn:dh-turn:3478?transport=udp`及本轮测试资格；Java注入DIGITAL_HUMAN_TURN_ICE_SERVERS_JSON指宿主127.0.0.1:3478的同资格。会话仅合成媒体，不声称真实商业音画效果。必须断言选中候选/实际入站帧，若本机网络仍不能通，按§13记录具体网络证据并阻塞真实媒体验收，不能改用假video。此处的连通预期是需验证的技术方案，不是本轮已验证事实。

**入口与清理**：AI/用户/治理测试origin沿用ci-e2e的18082/18080/18081；这是计划值，端口被占用必须先协调，不能另开第二栈。所有Compose启停/构建通过`npm run stack -- ...`或ci-e2e已接入的local-stack；W27不裸调docker up/down。新增W30扩展只在DH_FIX2_E2E=1生效，HYPIT_E2E及各无关fixture强制0，目标spec明确，默认不开其它测试。ci-e2e已使用fresh/cleanup、单worker、显式服务，保留其失败/INT/TERM收尾；新增TURN配置由同一守卫生命周期拥有，结束删除本轮秘密文件。既有开发卷保留，普通切换只stop；只有守卫确认本次fresh资源才能reset，不自动恢复旧全量栈。

### 9.4 安全/性能/兼容

规则只引用：owner与副作用RULE-001/005；fence与停止确认RULE-002/006/007；上限RULE-003/008/009。新日志按§6.6脱敏；单批100条串行收尾、每轮1场晋升不承诺批量统一25s。浏览器验收只声明Chromium本地矩阵；没有验证的引擎/商业提供者/生产均NOT_RUN。

### 9.5 仓库约束映射

| 约束 | 卡/落实/验证 |
|---|---|
| R-UI | C-04/C-05；§8双主题/移动/键盘，V-006/V-009 |
| R-ENTRY | C-05；真实AI导航/Edge/身份，TC-C05-001/002，不新路由 |
| R-JAVA | C-01～03；响应式/真实PG/锁序，V-001～004 |
| R-DATA | C-02/03；无迁移但有状态/fence/事件原子，TC-C02-004、TC-C03-002～005 |
| R-AI | C-03/05；无新经济键/无商业提供者，TC-C03-003/TC-C05-002 |
| R-QUALITY | 全卡；§12门禁、非零用例、真实证据及索引 |
| R-LAYER | C-04；SFC≤800，业务流进W09；不靠删注释/压行掩盖堆逻辑 |
| R-LIFECYCLE | C-03/04/05；owner/事件随清理、监听/请求释放，V-004/V-008 |
| R-DIR | 全卡；W表为唯一位置；不创建test/或根截图 |
| R-SAFE/本机资源 | 全卡；§9.3单栈、串行、作用域清理、旧改动保留 |

## 10. 开发计划

| 卡 | 标题/对应需求 | 写入W | 依赖/交付 | AC/V | 状态 |
|---|---|---|---|---|---|
| C-01 | Offer状态闸与真实机器契约 / REQ-003 | W01/02/21/22/23/24/27 | 无；提供正确错误码与拒绝顺序 | AC-005；V-001/V-007 | VERIFIED（2026-10-02，V-001 10/0/0、V-007 19/0/0 自跑 exit0；证据 test-artifacts/task-105-fix-2/{java-offer,contract}/） |
| C-02 | 安全finalize / REQ-001 | W03/04/18/20/27 | C-01；提供停止确认后释放容量 | AC-001；V-002 | VERIFIED（2026-10-02，V-002 8/0/0 自跑 exit0；证据 test-artifacts/task-105-fix-2/java-reaper/） |
| C-03 | 晋升、恢复、事件及queued接管 / REQ-002/004 | W05/06/07/13/14/15/16/17/18/19/20/21/22/23/24/27 | C-02；后端完整状态与通知链 | AC-003；V-003/V-007 | VERIFIED（2026-10-02，V-003 26/0/0、V-007 21/0/0 自跑 exit0；证据 test-artifacts/task-105-fix-2/{java-promotion,contract}/） |
| C-04 | 前端等待与控制页生命周期 / REQ-002/004 | W08/09/10/25/27/35 | C-03；当前控制页自动接通 | AC-004；V-005/V-006 | VERIFIED（2026-10-02 第2轮：V-005 149/149、V-006 typecheck/lint/build/frontend-ui exit0；证据 test-artifacts/task-105-fix-2/{frontend,frontend-ui}/） |
| C-05 | 最小真实链集成与收口 / 全部 | W11/12/26/27/28/29/30/31 | C-01～04卡级VERIFIED；完整证据与索引 | AC-002/006；V-004/V-008/V-009/V-010 | IMPLEMENTED（2026-10-02：W26～W31 落盘，V-008 静态组全绿、W28 6/6、V-010 判据代码就绪；V-009 真实媒体帧验收 ENVIRONMENT_BLOCKED——信令链全通但宿主↔容器 ICE 间歇失败〔1/15 偶发全链通过，aioice 403 Forbidden IP 与 relay 绑定实录见 test-artifacts/task-105-fix-2/e2e-smoke/〕；V-004 FAIL 归因基线脆弱性〔基线 clone 对照：基线同败且更重〕；未 VERIFIED，返工按 §13） |

W32～34仅生成物按用途开放。共享W按表中卡序串行，禁止代理或多会话同时写共享文件/跑重型任务。

### 10.1–10.2 拆分与交接

5卡继承原卡编号，修正其实际边界；C-03耦合认领/派发/事件/接管，同一事务与fence规格不能拆给执行者各自猜测。交接按各卡输出和§14.1，必须包含实际diff、测试结果和未完成项；不编造工期。

### 10.3 阶段出口

M1=C-01→C-02→C-03，服务端契约/生命周期IT通过；M2=C-04，前端装配/迟到响应/主题验证通过；M3=C-05，最终真实集成和资源收尾。M1失败回对应后端卡，M2失败回C-04；M3按根因返工责任卡并复验，不因历史卡绿灯豁免当前失败。

### 10.4 风险与验证前置

| 风险 | 严重度/概率/难检程度（1～3） | 最早证据 | 失败去向 |
|---|---|---|---|
| 停止未知却释放/并发超槽 | 3×3×3 | TC-C02-003、TC-C03-002 | C-02/03，不放宽停止确认 |
| claim崩溃、响应丢失重复派发 | 3×2×3 | TC-C03-003/004 | C-03，不新增sessionId绕过 |
| 事件只写库、回滚仍通知 | 3×2×2 | TC-C03-005 | C-03，不能仅replay测试冒充live |
| 旧页/旧GET连接新账号 | 3×2×3 | TC-C04-004 | C-04，禁止以account同名判代次 |
| 本机媒体UDP与峰值资源 | 2×3×2 | C-05先跑SC-01单场冒烟 | 先取网络/内存证据；必要时§13阻塞，不开第二栈 |

## 11. 任务卡

### 卡 C-01：Offer状态闸与真实机器契约

**目标与责任**：实现REQ-003；执行模型负责实现/卡级验收。输入为§2基线；输出W01新闸、W21～24一致契约、W27可执行java-offer/contract阶段，供后续卡复用。

**必读**：RULE-001，§6.2/6.4，当前assertSessionLease/connectReady；W02 fake OFFERS计数和W23机器契约范式。

| 步骤 | W/定位 | 精确动作 | 检查点/失败去向 |
|---|---|---|---|
| 1 | W27 | 建phase路由、根目录定位/日志/exit传递；java-offer调用V-001所列真实命令，contract调用V-007；不预建其它业务功能 | shell语法检查；环境问题按§13 |
| 2 | W01 webrtcOffer | grants后用sessions.get读取state；按RULE-001分支，runtime调用保持惰性；保留connectReady | 编译与TC-C01-001/002；普通错误在卡内修 |
| 3 | W21/22/23/24 | 新码同步ErrorCode/errors/API16和示例，补删码负例；共享K01/K03只记录适用分支与本书引用 | V-007；禁止改端点数量/无关枚举 |
| 4 | W02 | 实现TC-C01-001～004，分别正确身份/epoch/参数，仅单条件错误 | V-001非零执行；断言码和零副作用 |

边界映射：queued/preparing/ending等TC-C01-001；越权/旧epochTC-C01-002；成功和runtime拒绝TC-C01-003；形状TC-C01-004。禁止修改media-ready/playback-reset或把新闸提前到鉴权之前。

**AC-005**：Given正确owner/epoch与合法offer，When状态属于RULE-001拒绝集合，Then获得§6.4对应错误且runtime计数/DB/事件无变化；connecting/ready仍完整中继成功。验收V-001/V-007。UI N/A：本卡不改页面。

### 卡 C-02：安全finalize与调度收尾

**目标与责任**：实现REQ-001的服务端；执行模型验收。输入C-01通过，输出W03.finalizeEnding经runOnce真实调用。必读RULE-002/009、§7及W04固定Clock范式。

| 步骤 | W/定位 | 精确动作 | 检查点/失败去向 |
|---|---|---|---|
| 1 | W03构造器/runOnce、W20 | 注入RuntimeClient和校验后的grace；新增finalizeEnding，runOnce先scanExpired再finalize；保持scanExpired原计数语义，修正文档与实际一致 | 既有reaper回归；不得改变CLAIM_SQL集合 |
| 2 | W03 finalize | 固定now/cutoff查询；逐项网络结束/精确404辨识（state方法可抛WebClientResponseException404），确认后CAS；单项失败继续下一项，保留ended_at和其它字段 | TC-C02-001～004；不要按任意404字符串判断 |
| 3 | W04/W18 | 适配真实构造器参数，补成功/宽限/未知/多实例重复/调度用例；避免同一fake clock把刚写入的ending立刻误当过期 | V-002；固定时间独立断言scan与finalize |
| 4 | W27 | 加java-reaper阶段及报告归档 | 失败状态不得被日志命令覆盖 |

**AC-001**：Given过宽限ending会话及真实PG，When调度收尾且停止已确认，Then行ended/ended_at存在、owner活动位和全局槽释放一次；临界宽限未过或停止未知不释放，重复扫描不复活/不增终态版本。V-002；SC-01真实画面归C-05的AC-002。无页面修改。

### 卡 C-03：晋升、恢复、通知与queued接管

**目标与责任**：实现REQ-002/004服务端；执行模型验收。输入C-02安全容量释放；输出W06组件、W05共享binding、W15提交后通知、W13 queued接管，供C-04消费。必读RULE-003/005/006/007/008、§6/§7、现有V88 trigger/Policy/LeaseService/EventService，不从旧稿假定row包含owner/backend。

| 步骤 | W/定位 | 精确动作 | 检查点/失败去向 |
|---|---|---|---|
| 1 | W17/W20/W27 | 先把基座promotion.enabled=false，登记生产默认与java-promotion阶段；防全类IT被新调度污染 | 原IT基线与属性覆盖检查 |
| 2 | W05/W19 | 抽取binding+runtime调用成§6签名，create仍持有原CAS/失败回执；readMaxSessions复用 | SessionIT成功/同键重放/失败/容量回归不变 |
| 3 | W06 claim/reconcile | @Component/@Scheduled、enabled/running finally释放；按§7锁序claim、30s持久fence、串行一轮一个；只恢复promotion前缀 | TC-C03-001/002/004；无网络持锁 |
| 4 | W06/W15 | runtime结果分类；成功状态CAS+append同事务、提交后publishSessionState；未知只query；W05.end识别sent标记，取消后迟到结果不接通/不提前释放 | TC-C03-003/005；故障注入必须可定位目标分支 |
| 5 | W13/W14 | resume在原锁内加入queued分支，保持队列态/期限且更换控制epoch；preparing保持冲突；同操作键重放不增epoch | TC-C03-006，既有resume/heartbeat不放宽 |
| 6 | W07/W16/W18 | 补并发屏障、fake受理/查询状态机、真实调度禁止直调、live已订阅测试；C-02构造适配保留 | V-003；每目标用例非零、非skip |
| 7 | W21/22/23/24 | queued resume说明/样例与本书规则一致，保留原状态枚举；共享契约只增补本任务必要差异，不复制整段第二份状态机 | V-007和diff范围核验 |

边界：不超槽/队头变化TC-C03-002；已受理丢响应TC-C03-003；崩溃/旧fence/冻结TC-C03-004；live/回滚TC-C03-005；控制页隔离TC-C03-006。禁止碰runtime或财务；确认现有runtime同session返回旧实例不等于网络恰一次。

**AC-003**：Given可晋升队列和可用slot，When真实worker运行，Then最老合法候选经真实binding调用变connecting、同事务产生一条晋升事件且已连接SSE收到通知；并发不超槽，未知/崩溃按RULE-006/007恢复，不产生新会话或经济键；queued显式接管仍满足原owner/epoch规则。V-003/V-007。无页面修改。

### 卡 C-04：前端等待与控制页生命周期

**目标与责任**：实现REQ-002/004客户端；执行模型验收。输入C-03接口/状态；输出W09等待器与W08真实装配。必读根DESIGN、RULE-004/005/008/009、§8、现有AccountSessionPort.capture/isCurrent和媒体stop。

| 步骤 | W/定位 | 精确动作 | 检查点/失败去向 |
|---|---|---|---|
| 1 | W09 start/resume/wait | 注入connector，queued/preparing立即返回并等待；GET单在途+超时+总上限；当前快照确认后先置连接attempt guard再await | TC-C04-001/002/005 |
| 2 | W09事件/生命周期 | watch当前session状态把SSE转为刷新提示；增加seq/请求序号防迟到；resume成功布尔返回；end/隐藏/失活/dispose/换代停止等待并使回调失效 | TC-C04-003/004；guard覆盖成功/error/finally |
| 3 | W09 listener/heartbeat | 命名函数注册与removeEventListener；新增等待不得泄漏；原heartbeat回写也校验ticket+generation，避免旧409污染新页 | TC-C04-004；不得扩大到别的composable重构 |
| 4 | W08装配 | 删除两处无条件connectMedia，注册connector；takeover仅resume返回true后关闭卡/订阅；媒体retry仅允许RULE-004集合；反馈使用既有区 | W25真实挂载证明按钮走生产链，SFC≤800 |
| 5 | W10/W25/W27/W35 | 实现TC-C04-001～005及frontend/frontend-ui阶段；W35用真实页面和UI专用HTTP/媒体夹具，完成§8.8截图与键盘检查 | V-005/V-006；失败回本卡，必要缺文件按§13 |

控制页停止后仅显式resume可重新控制，visible/activated不能凭空续接媒体。非queued start仍走同一connector一次；禁止因resume失败从旧session继续连接。

**AC-004**：Given当前控制页等待，WhenSSE或轮询任一到达正确状态，Then恰一次自动连接原session，排队/准备中零offer；取消/旧代次/旧账号/旧epoch的成功、失败、finally均不能更新新页；刷新须显式接管且失败保留接管面。V-005/V-006，UI按§8.8。真实帧由AC-006复证。

### 卡 C-05：最小真实集成与收口

**目标与责任**：执行模型完成最终集成；输入C-01～04卡级VERIFIED及证据；输出真实端到端证据、正确索引和资源清理状态。必读§9.3、§12.2 C05、§12.5、ci-e2e实际自动起栈路径，不依赖scripts/local旧探针。

| 步骤 | W/定位 | 精确动作 | 检查点/失败去向 |
|---|---|---|---|
| 1 | W27/W28 | 完整验收phase、静态fake Docker守卫测试；all按V序串行且任一步失败非零，记录无用例/skip/缓存 | V-008；不启动服务做runner静态检查 |
| 2 | W29/W30 | 按§9.3唯一测试TURN方案接入；DH_FIX2_E2E=1才加入overlay与dh-turn，基础/其它任务行为不变 | W28断言服务白名单、无bare up、无全profiles及失败清理 |
| 3 | W31/W26 | 合成双账号/角色/catalog种子和作用域清理；编写TC-C05-001～003；禁止业务成功API route.fulfill、禁止条件skip | 测试可由W27重建，不依赖忽略脚本 |
| 4 | W27 | 先Java全模块check/前端完整受影响回归/静态门禁，释放IT资源；再唯一fresh栈E2E | V-004/V-008/V-009；失败回根因卡，不顺手修无关代码 |
| 5 | W11/W12 | 核对§12.1全映射、实际看明暗图、资源已收尾；更新本任务状态和版本记录，精确改索引行 | V-010；不得改105/107历史实施状态 |

**AC-002**：Given真实AI页面A已出帧，When关闭页面且停止被确认，Then按RULE-009时限旧行ended并释放容量，A通过真实开始操作再建且再次出帧；全过程有DB/时间/媒体证据，不能通过直接SQL把被测旧会话置ended。

**AC-006**：Given两合成账号A占槽、B排队，WhenA真实结束，ThenB在RULE-009两段时限内自动连接并连续收到媒体帧；排队零offer、事件可消费、GET恢复和取消/刷新行为可复验，无新增经济键；本轮资源/测试账号按计划收尾。V-009/TC-C05-001～003，UI按§8.8；V-004/V-008/V-010也必须通过。

## 12. 测试、命令与最终验收

### 12.1 需求追踪

| REQ/不变量 | 规则/实现W | 卡→AC | TC | V/证据 |
|---|---|---|---|---|
| REQ-001 | RULE-002/009；W03/04 | C-02→AC-001；C-05→AC-002 | TC-C02-001～004、TC-C05-001 | V-002/004/009；W32、Java XML |
| REQ-002 | RULE-003/004/006/007；W05/06/07/08/09 | C-03→AC-003；C-04→AC-004；C-05→AC-006 | TC-C03-001～004、TC-C04-001/002/005、TC-C05-002 | V-003/005/006/009；W32 |
| REQ-003 | RULE-001；W01/02/21/22/23 | C-01→AC-005 | TC-C01-001～004、TC-C05-002 | V-001/007/009；W32 |
| REQ-004 | RULE-005/008；W13/14/15/16/08/09 | C-03→AC-003；C-04→AC-004；C-05→AC-006 | TC-C03-005/006、TC-C04-002～004、TC-C05-003 | V-003/005/006/009；W32 |
| owner/fence/计费不扩 | §5.4/§7.4 | C-03/04/05→AC-003/004/006 | TC-C03-002～006、TC-C04-004、TC-C05-002/003 | V-003/004/008/009；W32 |

### 12.2 测试用例

**共享fixture**：F-J使用§9.3真实PG+Spring/WebTestClient，账号A/B分别 `dh-fix2-a-`/`dh-fix2-b-`+测试UUID；合法owner/epoch=1、mediaEpoch=1；状态行沿W02.seedConnecting字段集合，时间用固定UTC `2026-10-02T00:00:00Z`。排队/并发另建不同owner，catalog max=1、enabled=true；fake只替换RuntimeClient.Transport，记录create/offer/end/state的sessionId/epoch/调用顺序，可用Sinks.One/CountDownLatch控制完成，不能用任意sleep碰运气。F-U使用现有Vue/Vitest/happy-dom，AccountSessionPort可使票据过期、deferred Promise和fake timers，真实W09/W08被测。F-E为§9.3唯一fresh栈、真实浏览器与Python，合成外部provider。

清理J：测试基座专属库清理session前先按FK清事件/turn/transcript/operation，仅本Testcontainers环境可清表；重置catalog/fake计数，关闭测试SSE订阅/屏障。清理U：dispose/unmount、恢复mock/timers并验证没有新增等待请求；清理E：只结束本runId账号会话并关闭context，最终守卫清fresh资源，留脱敏证据。不同运行的日志不互相覆盖。

以下权限、并发、异步、跨层用例均按高风险展开；相同fixture/清理规则只引用，不复制数据准备。没有资金规则变更，但关键异步场景验证invocation/operation经济键不增加。

#### TC-C01-001：完整状态闸矩阵

- 关联/层：REQ-003、AC-005、RULE-001；高风险服务端拒绝副作用，W02真实HTTP+PG。
- 输入：F-J，各状态单独会话，均合法SDP与当前epoch；fake offer固定成功answer。
- 顺序：逐个测试queued、preparing、ending、paused、reconnecting、listening、responding、ended、failed，不混入越权/旧epoch；然后测试connecting/ready正对照。
- 断言：各错误严格符合§6.4顶层code/error；拒绝组OFFERS=0，session行逐字段/事件计数不变；正对照确实调用transport并成功。
- 清理/证据：J；V-001、W02 XML/W32。
- 防假阳性：单独覆盖ending（grants不拦的状态）；只测ended会让旧代码也通过，不能算新闸证据。

#### TC-C01-002：身份和epoch优先级

- 关联/层：REQ-003、AC-005；高风险权限隔离，W02。
- 输入：F-J B的queued；A签名、无身份、正确B+epoch0、正确B+epoch1四组，body均合法。
- 顺序：独立发送上述四组，另对B已ended行发送旧epoch验证原terminal优先级。
- 断言：依次404 dh_not_found、401 dh_auth_required、409 dh_lease_stale、409 dh_session_queued；ended仍原dh_state_conflict。全组runtime零调用；他人行无状态/事件/费用变化。
- 清理/证据：J；V-001/W32。
- 防假阳性：必须区分每种code和正身份对照，不接受宽泛“403或404”断言。

#### TC-C01-003：正常中继和竞争终态回归

- 关联/层：REQ-003、AC-005；跨runtime/DB，W02现有tc105x_03_02_relayAnswerThenConnectReadyIdempotent及新增竞态。
- 输入/顺序：F-J connecting→两次offer；另以屏障hold成功answer，在另一操作结束会话后放行answer；另注入runtime dh_lease_stale。
- 断言：正常两次200完整answer字段/no-store，ready_at只首次设置；end先提交时不返回成功answer、不复活ended；runtime拒绝透传且不改DB。
- 清理/证据：J；V-001/W32。
- 防假阳性：核对屏障实际到达transport后才结束，不能用已ended行冒充在途竞态。

#### TC-C01-004：形状与机器契约回归

| 层/位置 | 输入与操作 | 确定断言 | 证据 |
|---|---|---|---|
| 普通契约/HTTP；W02/W23 | F-J正确owner queued，type=answer、sdp空串、未知字段各独立请求；加载W21/W22，再复制fixture删除dh_session_queued枚举 | 原422先于排队409；runtime=0；机器样例合法，删码负例失败并点名；端点数量不变 | V-001/V-007、W32 |

#### TC-C02-001：收尾与时间口径

- 关联/层：REQ-001、AC-001；异步持久化，W04/W18。
- 前提：F-J ending已16s，另有同owner建新会话的唯一冲突对照；runtime.end返回同id ended、cleanupPending=false。
- 顺序：固定clock直调finalizeEnding后重读；再运行启用调度的独立用例，不直接调用生产扫描方法。
- 断言：ended/ended_at非空，version仅推进一次，同owner可再插入；原active减1；默认调度在RULE-009收尾边界内动作。
- 清理/证据：J；V-002/W32，调度用例超时有限。
- 防假阳性：不能以测试SQL直接置ended；必须记录runtime.end和真实@Scheduled调用效果。

#### TC-C02-002：宽限、批量、配置边界

- 关联/层：REQ-001、AC-001；时间敏感异步，W04。
- 输入：fixed now；ending年龄14999ms、15000ms、15001ms各一行，额外failed行；limit=0/101分别验证夹限；grace0/61非法配置。
- 顺序：分组独立清理与执行，禁止真实sleep；先读version/ended_at作为对照。
- 断言：严格小于cutoff，仅15001ms可处理；terminal不动；limit夹到1/100；非法grace启动失败，不能负宽限提前释放。
- 清理/证据：J；V-002/W32。
- 防假阳性：检查未过期行零字段变化，不能只断言有一行成功。

#### TC-C02-003：停止未知不释放、单项故障隔离

- 关联/层：REQ-001、AC-001；停止事实/容量高风险，W04。
- 输入：F-J三过期ending；A end/state503，B确认404 dh_not_found，C成功ended且cleanupPending=false；另测成功HTTP但cleanupPending=true和Mono.never超时。
- 顺序：运行一轮，检查A后B/C仍执行；恢复A为已停止后再一轮。
- 断言：A/cleanupPending=true/未知超时保持ending和占槽；B/C ended；恢复后A终态；批次错误不假成功、不饿死后续行。
- 清理/证据：J；V-002/W32。
- 防假阳性：必须区分transport异常、明确该session不存在与成功但尚有句柄，不能统一吞错。

#### TC-C02-004：并发收尾与取消中的派发

- 关联/层：REQ-001/002、AC-001/003；CAS/跨层竞态，W04/W07。
- 输入/顺序：F-J两个reaper实例同时读同一ending；hold第一个end结果，第二个先提交，再释放第一个。另在C-03晋升create在途时用户end，验证§7的marker保护。
- 断言：终态DB只推进一次；网络end可重试但不得复活；晋升未决时用户end不能先清marker/释放槽，迟到成功必须停止，fence丢失不得破坏已合法晋升的同session。
- 清理/证据：J；V-002复证reaper部分、V-003复证跨晋升部分。
- 防假阳性：单实例连续两次调用不是并发；必须用屏障证明重叠，数据库version和容量均断言。

#### TC-C03-001：真实调度/FIFO/开关

- 关联/层：REQ-002/004、AC-003；异步注册接线，W07/W18。
- 前提：F-J空槽、两个不同owner queued，created_at相同但id按确定升序；fake runtime返回匹配binding。
- 顺序：独立调度测试enabled=true、250ms周期，只等待，不直调runOnce；其它测试enabled=false并用直调控制轮次；空队列独立分组运行一轮，断言0候选/0runtime/0事件。
- 断言：按created_at/id最老合法行connecting，另一仍queued；create计数1、正确backend/avatarRevision/epochs，成功晋升事件1；enabled=false不自动运行；running防止轮次重叠。
- 清理/证据：J；V-003/W32。
- 防假阳性：必须从Spring容器取得新组件；只new一个worker再直调不能证明装配。

#### TC-C03-002：容量竞争与生命周期锁

- 关联/层：REQ-002、AC-003；P0并发/owner隔离，W07。
- 前提：F-J max1、1 active+2 queued；另准备第三owner有效preflight用于真实create。
- 顺序：先多轮确认满槽零派发；让active ended后用屏障同时启动两个worker实例及SessionService.create；另让队头owner gate冻结与认领竞争。max100/0/101/损坏配置独立分组测试现有解析。
- 断言：始终active≤解析后的max，只有一个名额被占；FIFO合法候选无双claim，new create可返回queued但不得超槽；冻结先提交则零runtime派发，认领先提交则后续守卫与收尾处理，无死锁；不因newSessionsAllowed=false取消已排队会话。
- 清理/证据：J；V-003/W32。
- 防假阳性：不仅是先释放再串行调用；核对并发屏障与每次原子占槽，记录max和全部状态计数。

#### TC-C03-003：明确未受理与已受理丢回执

- 关联/层：REQ-002、AC-003；远端副作用/幂等，W07/W19。
- 前提：F-J queued、fake实现保存sessionId→RuntimeState及调用计数，不是每次固定成功。
- 顺序：分组A明确拒绝create且state404，B create已保存实例但返回超时、state返回原实例，C create超时且state也未知；再恢复B/C可查询，重复触发worker。
- 断言：A回queued且截止不延长，下轮可按原id派发；B connecting且不再create；C保留占槽只query，不能把一次404观测当迟到create绝不会发生；无第二sessionId/预检/经济键。create旧路径同键回执和失败语义回归通过。
- 清理/证据：J；V-003/W32。
- 防假阳性：B必须先记录“remote接受”再丢响应，检查第二轮create计数仍1，不能仅注入一个普通503代替。

#### TC-C03-004：崩溃接续、旧fence及取消/冻结

- 关联/层：REQ-002、AC-003；高风险恢复，W07。
- 前提：F-J为本任务promotion前缀preparing持久行，lease未到/已到两组；另有无前缀preparing对照；分别保留reserved/sent两种持久阶段。
- 顺序：旧worker在claim后停止（取消订阅/销毁测试实例），新实例读取同DB；分别模拟remote存在匹配、未知、明确未派发三种；用户end或freeze在结果回写前提交；最后释放旧worker迟到结果。
- 断言：只到期前缀行可reclaim；旧fence零DB/事件写，不停止仍合法活跃的新持有者；remote匹配只查询后接通，未知不释放；取消先行不能复活/放出成功事件，runtime停止事实保留；无前缀行不被“顺手修”。queue创建59.999s/60s/60.001s分组验证到期不能续命。
- 清理/证据：J；V-003/W32。
- 防假阳性：重建worker仍复用持久DB与fake远端表；清空所有表再“重启成功”不能证明恢复。

#### TC-C03-005：事件事务、live与重放

- 关联/层：REQ-004、AC-003；事务/跨层，W07/W16。
- 输入/顺序：F-J先通过真实HTTP events连接并确认snapshot，之后触发晋升；在另一组注入append持久化失败；第三组提交后丢弃publish，再重连/GET。
- 断言：已有连接收到session.state connecting，不是只重连才看到；正常DB成功事件恰1；append失败时connecting CAS回滚且没有live，后续以原runtime状态恢复而非再create；publish丢失时DB/GET可恢复，不新增随机eventId补一条。账号关闭/清理后无额外可见事件。
- 清理/证据：J，取消SSE；V-003/W32。
- 防假阳性：replay和live分别断言，记录订阅完成屏障；不能拿EventService.append的返回值当浏览器收到事件。

#### TC-C03-006：queued显式接管

- 关联/层：REQ-004、AC-003；权限/幂等，W14。
- 前提：F-J A queued controller c1/epoch1，c2为新页；B不同owner。
- 顺序：c2 takeover=false、B takeover=true、c2正确takeover=true、同requestId重放、c1旧epoch请求逐一测试；preparing同请求独立测试；两页同epoch并发接管用屏障。
- 断言：分别原takeover_required/404、成功queued且epoch2/controllerc2、重放仍epoch2、旧epoch拒绝；FIFO/排队时钟不变；preparing返回state_conflict不改行；并发只一成功，runtime.create/offer均0。
- 清理/证据：J；V-003/W32。
- 防假阳性：每个权限负例只破坏一个前提；跨owner不用同时错误epoch，防止因别的拒绝条件“通过”。

#### TC-C04-001：等待与生产装配

- 关联/层：REQ-002/004、AC-004；UI异步接线，W10/W25。
- 前提：F-U真实挂载Workbench/原composable，mock API create分别返回queued/preparing/connecting；媒体spy注入。
- 顺序：从真实开始确认按钮进入；等待3s×2，模拟GET仍等待；另测connecting正常创建、未注册connector只读fixture、40字角色名的窄屏换行。
- 断言：等待时connector/offer=0、GET确实发生、starting解除/结束按钮可用；connecting路径只1连接；生产装配注册了connector（不能靠只测composable蒙混）；只读fixture无connector也不报错。
- 清理/证据：U；V-005；§8.8等待图。
- 防假阳性：不能通过不挂载按钮或未注册spy导致永远0；正对照必须能驱动实际装配调用。

#### TC-C04-002：双通道、单请求与顺序

- 关联/层：REQ-004、AC-004；并发回写，W10。
- 前提：F-U start queued、两deferred GET，media connector可hold。
- 顺序：SSE提示与3s计时器同tick；GET确认connecting；hold连接器，再重复发事件；另组只poll无SSE。先发GET旧queued延迟至新connecting快照后返回。
- 断言：同一时刻最多1 GET，新提示合并；连接guard先于await置位，connector恰1；只poll也接通；旧queued不能回退新状态/重置guard/二次offer。
- 清理/证据：U；V-005/W32。
- 防假阳性：真的在connector未完成时重复事件，不只在promise已完成之后重复调用。

#### TC-C04-003：刷新接管与失败保留

- 关联/层：REQ-004、AC-004；恢复/权限交互，W10/W25。
- 前提：F-U深链queued快照，mock resume返回queued新epoch；另一组返回state_conflict；第三组preparing经GET变queued。
- 顺序：先只加载页面，再点击接管；成功后模拟晋升；失败时再触发旧session状态事件。
- 断言：加载不调用resume/offer；成功才关闭接管卡、订阅并等待，使用新epoch；失败保留卡且不connect、不覆盖错误；preparing期间不自动takeover，用户可在状态更新后再点击。
- 清理/证据：U；V-005、§8.8接管图。
- 防假阳性：测试真实handleTakeover，不能只测resume自身catch后正常返回。

#### TC-C04-004：换号、取消、隐藏与迟到finally

- 关联/层：REQ-004、AC-004；高风险身份/生命周期，W10/W25。
- 前提：F-U A等待GET/heartbeat/resume各有deferred；捕获票据后账号A→B→A使旧票据失效；另组同账号generation更新。
- 顺序：分组执行end、notifyVisibility(false)、notifyDeactivated、dispose/unmount，再返回旧成功/401/409/throw/finally；后续触发visibility/activated。
- 断言：不发offer、不改新session/error/starting/ending/leaseStale，不重开计时器/媒体；removeEventListener匹配注册函数，新增等待器无残留；显式成功resume才开始新控制代次。end网络失败保留结束重试入口且不自动接通。
- 清理/证据：U；V-005/W32。
- 防假阳性：同时检验旧成功、失败与finally，账号字符串相同但ticket不同仍必须拒绝。

#### TC-C04-005：网络、上限与终态

- 关联/层：REQ-002/004、AC-004；异步超时，W10/W25。
- 输入/顺序：F-U依次模拟5s GET超时、单次网络失败后恢复、120s总上限、401/404、queued→ending→ended；fake timers精确推进119999/120000ms。
- 断言：失败保留旧数据并单请求重试，恢复按原session连接；上限展示UI-05并停自动等待，不伪造ended或退款；401/404停止；ending不连媒体但仍可读取至终态/上限，终态停止等待。
- 清理/证据：U；V-005、§8.8错误/终态图。
- 防假阳性：必须断言停止之后再推进时间无GET/offer；不能仅看错误字符串存在。

#### TC-C05-001：关页自愈与本人再次开始

- 关联/层：REQ-001、AC-002；真实E2E W26/W31，F-E。
- 顺序：真实AI登录/导航/选角色/确认开始；等待视频；记录最后heartbeat和DB状态，关闭页面，不发end/不改SQL目标state；轮询DB至ended；同账号重新打开并真实确认开始。
- 断言：符合RULE-009失联/收尾时限，ended_at存在、容量释放；新sessionId正常创建且出帧。每次视频必须videoWidth>0且readyState≥2，使用requestVideoFrameCallback或RTC入站framesDecoded在两个采样点增长，不能静态poster冒充。
- 清理/证据：E；V-009；W32的时间/状态查询、入站帧计数、明暗截图；视频不录真实用户。
- 防假阳性：关闭后禁止测试helper替reaper end；新会话必须由按钮完成，不用API建完再截图。

#### TC-C05-002：双账号排队晋升与直调闸

- 关联/层：REQ-002/003/004、AC-005/006；真实E2E，F-E。
- 顺序：A真实开始占唯一槽；B真实开始并保持排队。B正确身份直调一次offer确认409，网络统计区分该测试请求；再从A页面点结束，B不作重试；新回合测试B排队取消后A再结束。
- 断言：B自动流程排队offer=0（测试直调单列）、返回dh_session_queued；A结束后B按RULE-009先connecting再出帧；B仅1次正常offer且沿原session，无额外create/经济键；取消B后即使A释放也不晋升已取消行。
- 清理/证据：E；V-009；W32含A/B context区分的网络、DB/事件及帧数据，§8.8真链截图。
- 防假阳性：必须两个owner且max1，以免被owner唯一或预检错误挡住；不得SQL把B推进connecting。

#### TC-C05-003：恢复、兼容与探索收尾

- 关联/层：REQ-004、AC-006；真实恢复/资源生命周期，F-E。
- 顺序：B排队时刷新原深链、显式接管，再释放A；另一回合仅切断B的SSE读取（不mock业务响应）验证GET仍可接通；完成后让所有本任务promotion marker收敛并结束会话。用5分钟有界探索执行快速切页/连点/刷新/取消，发现按确认/存疑记录。
- 断言：未显式接管前无offer；成功后使用新epoch，同session继续；SSE断开仍可通过真实GET接通；无残留前端轮询/peer、无本runId未决marker；旧历史/清理链可读；没有确认缺陷不虚构问题。
- 清理/证据：E；V-009/W32；既有Erasure/Cleanup由V-004补回归；探索结论只在§14对话，不另建报告。
- 防假阳性：不得用reload重新创建新会话替代resume；资源检查必须读真实运行状态而非只看trap代码存在。

**证据层级**：J允许fake RuntimeClient外部transport，但HTTP/鉴权/序列化/PG/锁必须真实；U只证明生产组件行为；E必须真实UI→Edge→Java→PG/Python→媒体消费，仅外部模型及账号为合成。不用route.fulfill模拟成功业务，不以404探针证明媒体可用。截图必须实际看，GET重读证明持久化，视频须帧增长。必需用例零发现/skip不算完成。验收者从需求反查至少“ending在grants不拦”和“append不publish”两条缺陷，按字面执行后仍能抓到旧实现。

### 12.3 验证清单（V-001～V-003/V-005～V-007 已由责任卡自跑 PASS；V-004 基线无关失败、V-009 环境阻塞见 §14/阻塞记录；正式门禁由编排器复跑）

工作目录根固定`/Users/LXH/claude/y-1`，W27用bash。脚本是各责任卡要交付的入口，首次C-01建成前，基线直接执行下列同等内部命令；不存在脚本不构成“已跑”。java阶段从根进入platform-java，在同一bash source `../scripts/lib/java-runtime.sh`并`ensure_java_runtime 25`；先经过 `npm run stack -- run --project y1-e2e-local --docker --` 持锁包装，按§9.3无应用栈环境运行。

| V | phase与内部精确验证 | 前置/责任/通过标准 |
|---|---|---|
| V-001 | `bash scripts/acceptance/verify-105-fix-2.sh java-offer`；内部 `./gradlew :services:intelligence-service:test --tests 'com.grassland.intelligence.digitalhuman.DigitalHumanOfferIT' --tests 'com.grassland.intelligence.digitalhuman.DigitalHumanSessionIT' --no-daemon --max-workers=1 --no-parallel --no-build-cache --rerun-tasks` | C-01，F-J；TC-C01全部+原SessionIT实际执行、退出0 |
| V-002 | `bash scripts/acceptance/verify-105-fix-2.sh java-reaper`；同Gradle参数，精确测试类 `com.grassland.intelligence.digitalhuman.DigitalHumanReaperTest`、`com.grassland.intelligence.digitalhuman.DigitalHumanWorkerSchedulingIT` | C-02，F-J；TC-C02与既有调度非零；跨晋升子场景后由V-003补齐 |
| V-003 | `bash scripts/acceptance/verify-105-fix-2.sh java-promotion`；同Gradle参数，精确测试类 `com.grassland.intelligence.digitalhuman.DigitalHumanPromotionIT`、`com.grassland.intelligence.digitalhuman.DigitalHumanLeaseIT`、`com.grassland.intelligence.digitalhuman.DigitalHumanEventIT`、`com.grassland.intelligence.digitalhuman.DigitalHumanSessionIT`、`com.grassland.intelligence.digitalhuman.DigitalHumanWorkerSchedulingIT` | C-03，F-J；TC-C03所有反例与TC-C02-004跨层部分；无skip |
| V-004 | `bash scripts/acceptance/verify-105-fix-2.sh java-check`；内部 `./gradlew :services:intelligence-service:check --no-daemon --max-workers=1 --no-parallel --no-build-cache --rerun-tasks` | C-05；改共享IT属性故模块check必需，包含DH Erasure/Cleanup等；退出0，记录执行/失败/skip数；基线无关失败照§13，不能记全绿 |
| V-005 | `bash scripts/acceptance/verify-105-fix-2.sh frontend`；内部 `npm run test -- src/views/digital-human src/ai/components/AiWorkspaceNavigation.test.ts --maxWorkers=1 --no-file-parallelism` | C-04；无Docker，TC-C04+原媒体/事件/导航回归，非零/退出0 |
| V-006 | 根目录分别 `npm run typecheck`、`npm run lint`、`npm run build`；`bash scripts/acceptance/verify-105-fix-2.sh frontend-ui`：在单重型锁中启动 `npx vite --mode ai --host 127.0.0.1 --port 5173 --strictPort`，以AI_BASE_URL=http://127.0.0.1:5173运行 `npm run e2e -- tests/e2e/digital-human-session-waiting-ui.spec.ts --project=chromium --workers=1`，退出清子进程；按§8.8实际看图 | C-04；单重型锁/串行，SFC≤800；构建三入口；双主题/移动/键盘实际看图，不能仅静态命令0 |
| V-007 | `bash scripts/acceptance/verify-105-fix-2.sh contract`；内部 `npm run test -- tests/contracts/digital-human.contract.test.ts --maxWorkers=1 --no-file-parallelism` | C-01/C-03；无Docker；合法样例与删码负例均通过，端点引用一致 |
| V-008 | 根目录分别 `npm run test -- tests/deployment/digital-human-fix2-runner.test.ts tests/deployment/digital-human-s1-runner.test.ts --maxWorkers=1 --no-file-parallelism`、`npm run quality:lifecycle`、两份 `npx @google/design.md lint DESIGN.md` / `npx @google/design.md lint src/ops/DESIGN.md`、`rg -ni 'sohne\|cal sans\|cal.com\|stripi' DESIGN.md src/ops/DESIGN.md` | C-05；静态fake Docker不连daemon；普通命令退出0；末项预期无输出且exit1，执行错误不能算通过 |
| V-009 | `bash scripts/acceptance/verify-105-fix-2.sh e2e`；内部固定DH_FIX2_E2E=1、DH_E2E=1、DH_REAL_PROVIDERS_ENABLED=false、HYPIT_E2E=0、HYPIT_FIX2_TEXT_FIXTURE=0、CANVAS_E2E_TEXT_FIXTURE=0、E2E_WORKERS=1、E2E_ENGINES=chromium、E2E_SPECS=tests/e2e/digital-human-session-recovery.spec.ts，准备测试证书/短命TURN配置后调用 `bash scripts/ci-e2e.sh` | C-05，F-E；TC-C05三组全部实际执行，不能test.skip；单场冒烟先行，真实帧/持久化/清理证据齐；失败非零 |
| V-010 | 根目录 `npm run docs:links`、`npm run docs:status`、`git diff --check`；额外审阅新增未跟踪文件及W范围 | C-05；后两项退出0；links全局结果照实输出，本任务要求0断链且无新增未索引，基线72项见§2.7；本书索引唯一可达、状态如实。禁止为历史72项扩大写入范围 |

W27的 `all` 固定按V-007→V-001→V-002→V-003→V-004→V-005→V-006→V-008→V-009→V-010串行；V-006的UI专项在C-04完成，不依赖后置C-05文件/起栈。每phase打印工作目录/命令/开始结束/退出码/结果位置，使用pipefail，不能tee覆盖测试失败。无watcher，遇失败停在安全检查点。C-01～04只实现自己phase与通用路由，不提前执行后卡功能。必需测试报告若目标类0项或意外跳过则phase失败；UP-TO-DATE/FROM-CACHE单列，不宣称重跑。

V-010的历史索引判据由W27实现：读取固定基线`37d2b1569b0c2fdf9df2e82fa205355e235de5d0`的tracked Markdown（git ls-tree/git show只读），按现有check-doc-links的排除目录、marked解析和docs/README.md可达图计算基线未索引集合；与本次link-audit.json比较。必须errors=0、本书可达、当前未索引集合没有基线外新增项才算本任务增量PASS；全仓docs:links的原exit1/72项仍在对话和日志标FAIL。不能仅按数量72相等放行不同名单，也不能修改检查器使历史失败消失。

V-002/V-003所列测试类必须逐个使用`--tests`，完整前缀已给定；W27不得变成“名字包含关键词随便跑”。证据目录W32下按V与运行时间分目录，保存原始失败和修复后结果，Java默认XML/HTML所在build目录见W34；不打印.env。规划时没有运行这些业务命令，状态统一NOT_RUN。

### 12.4 命令入口

本任务命令统一见§12.3；没有候选命令菜单。

### 12.5 最终集成出口

C-05执行模型负责。前置C-01～04卡级VERIFIED、V-004/005/007/008通过、唯一栈可用。执行TC-C05-001→002→003并完成§8.8真链截图、V-006人工部分与V-010；追踪§12.1每个REQ/AC都有当前基线有效证据。检查真实接线、终态、经济键、owner、无残留资源和新增diff范围；代码变动使证据失效则重跑受影响层。

全量必需项PASS才可整任务VERIFIED；仅实现但未跑真实帧/持久化/资源检查是IMPLEMENTED，必需场景PARTIAL/NOT_RUN/skip不能算完成。返工按根因回责任卡，无关失败如实报告不擅扩范围。不得由本出口推导生产/商业提供者可用。

### 12.6 发布与回滚

生产发布N/A：本任务终点为本地集成验收。若需回退本地本任务代码，先在本任务隔离catalog关闭新会话创建（既有newSessionsAllowed=false），让已有队列排空并保持promotion恢复/收尾可执行，检查promotion前缀行收敛和本runId会话停止，再回退本任务差异；不reset他人代码/不删卷。未决远端状态不可确认时停止回退，报告待处理session脱敏ID与恢复动作，不能直接用旧代码掩盖悬挂。无数据库迁移，旧记录读取由TC-C05-003复证。

### 12.7 边界目录

| E | 覆盖/理由 |
|---|---|
| E01 空输入、E02超长、E13缺字段/非法枚举 | TC-C01-004与V-007既有请求schema边界；本次不增加输入字段或重设SDP大小 |
| E03 重复提交/连点 | TC-C03-006、TC-C04-001/002 |
| E04 断网/中断、E05服务端错误 | TC-C02-003、TC-C03-003、TC-C04-005 |
| E06 未登录/过期、E07权限/状态迁移 | TC-C01-001/002、TC-C03-006 |
| E08 成功但无数据 | TC-C03-001空队列/禁用分组；无新列表UI |
| E09 版本/租约冲突 | TC-C03-004/006、TC-C04-002/004 |
| E10 刷新/深链 | TC-C04-003、TC-C05-003 |
| E11 切账号/对象、E12卸载在途 | TC-C04-004，不因个人域而写N/A |
| E14 数值边界 | TC-C02-002、TC-C03-002/004、TC-C04-005 |
| E15 并发/乱序 | TC-C02-004、TC-C03-002、TC-C04-002 |
| E16 超时/取消/已受理重试 | TC-C03-003/004、TC-C05-002 |
| E17 跨账号/组织 | TC-C01-002、TC-C03-006；组织输入N/A，个人域不得接受组织上下文 |
| E18 撤权/注销/清理 | TC-C03-002/004/005、V-004既有Erasure/Cleanup；V-008生命周期门禁 |
| E19 旧数据/客户端 | TC-C03-004无前缀对照、TC-C05-003；旧UI错误结果TC-C01-001 |
| E20 部分成功/事件/重启 | TC-C03-003～005；不能用单轮成功替代 |
| E21 日期/时区/金额 | TC-C02-002、TC-C03-004固定UTC；金额计算N/A（不改计价），经济键不增见TC-C03-003/TC-C05-002 |
| E22 大列表/长文案/分页 | UI长名见§8.5/TC-C04-001；无新增列表/分页，分页N/A |

## 13. 阻塞与恢复

### 13.1 实质阻塞

找不到经搜索仍必需的入口/依赖；实际实现必须改变未定义公开契约/权限/价格/数据可见范围；W外写入或新依赖；现实锁序/协议无法满足既定行为；与AGENTS冲突；普通排查后仍无唯一栈/真实媒体/凭据；已有改动无法安全合并。这些情况只阻塞受影响卡及依赖卡。普通编译错误、行号漂移、可合并diff、合理环境准备不单独构成阻塞。

### 13.2 对话阻塞记录

报告任务书版本/卡号/递增B编号、影响REQ/步骤、已确认源码/命令与脱敏证据、已尝试、最小缺失项、一个推荐方案及影响、已保留文件/环境、解除条件与下一动作。不新增报告文件，不把未跑项写成FAIL或PASS；未执行用NOT_RUN。

### 13.3 修订与恢复

规划者/负责人在原书唯一维护位置更新受影响规则/W/TC/卡/附C并记版本。现有用户授权内的等价修订可直接处理；新收费/权限/可见范围/核心流程或扩大范围需用户确认，不能用技术偏差报告代替授权。只恢复环境不强改产品规格；恢复后核对版本、依赖与证据，相关代码变化重验。无关发现仅在对话列出，不新增卡或接手别的任务。

## 14. 执行结果汇报（默认仅在对话中）

每卡输出简短状态、实现变化、实际命令/工作目录/退出码/用例数、关键输出与证据引用、偏差/未完成项。全部卡及§12.5后再汇总整体：

- 任务路径/版本/授权卡/集成基线；实施状态及一句结果，不把规格READY等同已实现。
- 文件路径、操作、核心符号、对应卡/REQ、原有改动保留与W边界核对；工具生成物单列。
- 每个V/TC/AC结果：PASS/FAIL/PARTIAL/NOT_RUN/SKIPPED/N/A、实际退出码/通过失败跳过数、输出摘要与路径；未运行无退出码。UI证据附入口、浏览器、视口、主题、实际看图结论。
- 已满足与未满足REQ/AC、偏差及批准依据、基线失败与当前复验结果、范围外问题、后续负责卡/具体动作。
- 使用的唯一栈/最小服务、任务前已存在资源、本次新增资源、已停止/清理项、仍运行项及原因。不能只写“已清理”。

不默认新建独立完成报告/交付报告/总结，不自动追加任务书报告章节。测试工具日志/截图/报告仍按W32～34保留；必要文件证据不能被一句对话“通过”替代。没有专门产物的检查可引用含完整命令/退出码的本对话记录，脱敏后可核对/重建。

### 14.1 续作检查点

换模型/会话或中断前，在对话逐项给出：任务书路径/版本；授权卡范围/AUTO_CHAIN；当前HEAD/相关diff；已VERIFIED卡及证据；当前卡已做/未做步骤；各V的PASS/FAIL/PARTIAL/NOT_RUN/SKIPPED；最新决定/待同步修订；已有改动保留边界/新增文件；栈与产物/只在本机的证据和重建命令；阻塞及解除条件；下一卡及下一条具体动作。接手者核对实际代码/证据，不能盲目从C-01重做或继承无证完成声明；需要保存检查点文件时须有用户明确路径授权。

## 附 A：返工

需要返工时在对话给R-01起的卡号，注明原版本/基线、真实路径+符号、REQ/AC/TC、复现前提/步骤/实际vs预期/证据、确认根因、唯一修订决策、W交集写入、具体步骤和回归V。根因不明先有界诊断；不得用返工授权无限修仓库。当前无返工卡。

## 附 B：规划发布检查

### B.1 本轮工作与事实边界

已按理解目标→当前源码/调用链/测试/改动核验→冻结行为→验收设计→拆卡→反向审阅顺序重编。保留5个原卡编号；旧稿归档依据由Git历史保存，改为本文件并精确替换README唯一索引。没有实现功能、运行本任务业务测试或改模板。规划文档验证结果登记在B.3，不计作业务PASS。

### B.2 发布检查（规格检查，不是实施验收）

- [x] 用户/场景/价值/范围与排除项闭合：§1；恢复流程§4。
- [x] 无未决产品选项；既有安全规则与本轮技术授权区分：§1.9、D-01～06。
- [x] 当前入口/源码/复用/基线核验，历史测试不冒充：§2.3～2.8。
- [x] 真实注册/调用方/状态消费及恢复链：§3.1、§6、§7。
- [x] 成功/失败/加载/空/取消/权限/并发/兼容完整：§4～8、§12.2。
- [x] 单一事实源与类型/存储转换：阅读协议、§5/6/7；卡引用W/RULE/TC。
- [x] UI入口/响应式/键盘/明暗与SFC门禁：§8、§9.5。
- [x] W含源码/调用方/测试/配置/契约/索引/生成物，黑名单明确：§9.1。
- [x] 依赖无环、共享写入串行、责任卡负责最终联调：§10/11。
- [x] 每卡输入/输出/稳定符号/具体步骤与失败去向：§11。
- [x] E边界有TC或具体N/A；高风险展开：§12.2/12.7。
- [x] REQ→RULE/W→卡→AC→TC→V→证据闭合：§12.1。
- [x] mock与真实层、非零用例/帧增长/实际看图：§12.2/12.3。
- [x] 环境/最小服务/fixture/命令/副作用/清理：§9.3、V清单；规划业务测试NOT_RUN。
- [x] 阻塞/恢复不滥用审批且不放行未授权变更：§13。
- [x] 实施初稿和所有卡NOT_STARTED；卡级/集成/生产分别判断：§0.3/12.5/12.6。
- [x] 无新依赖/迁移/收费；测试TURN资格隔离与秘密清理：§6.8/7/9.3。
- [x] 汇报/证据/续作可用，无默认独立报告：§14。
- [x] 附C已填实，派发范围不冒充本轮实施授权；附D删除。
- [x] 作者说明/空表/候选菜单已裁剪；引用与占位检查见B.3/B.4。

### B.3 修订与文档核验

| 版本 | 日期 | 依据/变化 | 影响 | 检查 |
|---|---|---|---|---|
| 2.0.0 | 2026-10-02 | 用户要求原108→105-fix-2；Codex按模板3.1.1重编，纠正真实信封、状态闸范围、SSE通知、queued接管、停止确认与资源约束；补认领恢复/反例 | 全文5卡；旧稿1.0.0仅历史来源 | B.2/B.4逐项完成；文档命令实际结果在发布对话登记 |
| 2.0.1 | 2026-10-02 | C-05 实施收口：头部/§10 卡状态与 §12.3 标题按实际验证结果同步（卡级 VERIFIED 与 C-05 IMPLEMENTED/环境阻塞如实标注），README 索引行精确替换 | 状态字段与索引；不改规格内容 | 自验证证据 test-artifacts/task-105-fix-2/；整任务 VERIFIED 判定留待 §12.5 出口 |

实施验收尚未执行，所有实施TC/V为NOT_RUN；规划已执行的文档检查单独如下，不计入业务PASS：

| 规划检查 | 实际结果 | 证据/判断 |
|---|---|---|
| docs:links | FAIL，exit1；errors=0、unindexed=72 | `test-artifacts/docs-links/link-audit.json`及本轮对话；HEAD同一72项，新增0，本书已收录；增量检查PASS |
| docs:status | PASS，exit0 | 本轮命令输出，状态文档一致；不代表本任务已实现 |
| git diff --check及文档结构/引用核验 | PASS | 本轮对话：5卡、全部编号无重复/悬空、现有W路径可定位，新增W明确标记 |

本文READY只表示规格可派发；不更新其它任务的业务完成状态。

### B.4 按字面执行审阅

| 沿卡模拟/反查 | 发现的旧稿问题 | 本版落点与反例 |
|---|---|---|
| C-01读取→实现→验证 | error.code不存在；终态原grants已拒；其它态connectReady不成功 | §2.5、RULE-001、§6.4、TC-C01-001/002，不能只测ended |
| C-02收尾→C-03消费容量 | 吞所有网络错释放违反既有停止确认；时间25/40/45s混写 | RULE-002/009、§1.4限制、TC-C02-001～004 |
| C-03装配与重启 | row缺owner/backend、无持久claim、append不live、基座调度污染 | §6签名/§7fence、W15/W17/W18、TC-C03-001～005 |
| C-04按步骤接线 | queued resume实际拒绝、resume失败仍可能连旧session、preparing被当离队即连 | RULE-004/005/008、W13、resume boolean、TC-C04-001～004 |
| C-05重建环境与产出 | 老端口/裸跑假设、临时探针缺失、Docker绕守卫、只看videoWidth | §9.3唯一栈/完整脚本W、V-009、TC-C05帧增长/真实按钮/清理 |
| 从REQ反查 | 模块/截图存在也可“满足文字”但无业务闭环 | §12.1、TC-C05-001～003、§12.5，分别重读DB/真实媒体/结束资源 |

审阅按C-01→05逐卡核对输入/W/规则/TC/V/失败去向，再从4条REQ反向核对入口与产出；没有以新增子代理或历史对话作为前提。

## 附 C：交给编码模型的执行提示词

```text
任务书路径：/Users/LXH/claude/y-1/docs/任务书/草场任务书-105-fix-2-数字人会话收尾排队晋升与Offer状态闸.md
执行版本：2.0.0
规格状态：READY_FOR_IMPLEMENTATION
派发任务卡：C-01 → C-02 → C-03 → C-04 → C-05
执行模式：AUTO_CHAIN
续作检查点：无
授权依据：以用户实际派发指令为准；生成任务书和本提示词不构成实施授权，用户限定的卡范围优先。

请按任务书实现指定卡，不负责重新定义需求。
1. 先读适用AGENTS.md与任务书§0、§1、§9、§10、§13、§14，再读当前卡及精确引用；UI额外读根DESIGN.md。
2. 核对READY、2.0.0版本、授权卡范围和当前基线；每卡按§0.4核对W、依赖与已有diff，续作按§14.1查代码和证据，已满足步骤不要重写。
3. 给出对应卡步骤的简短计划后实施；常规代码/环境准备自行处理，实质阻塞按§13，不扩大权限/价格/范围。
4. 严格执行§9.3：先盘点、同一栈、最小真实依赖、守卫入口、重型串行和作用域清理；禁止裸Docker起栈或并行应用栈。
5. 执行当前卡AC/TC/V与回归；遵守§12.2证据层级，§8.8实际看明暗与移动图；未跑如实NOT_RUN，不能skip或用mock冒充真实媒体。
6. §0.3通过后按§14在对话简报并继续已授权下一卡，不默认新建完成报告；中断前给§14.1续作检查点。
7. 全卡后由你继续§12.5最终集成；只有出口通过才声明整任务完成。本任务不含生产部署或真实商业提供者开放。
```
