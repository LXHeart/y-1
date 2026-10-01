# 开发任务书：107-fix-2 · Hypit 全链路缺陷修复与真实交付验收

> 模板依据：[任务书模板 v3.0.0](任务书模板.md) ｜ 任务编号：107-fix-2 ｜ 任务书版本：1.0.0
> 创建/事实核验日期：2026-09-27 ｜ 规划与规格审阅：当前规划执行者 ｜ 实施/卡级验收：后续获授权的执行者
> 目标仓库：y-1，根目录 `/Users/LXH/claude/y-1` ｜ 当前分支：main（仅记录，不创建分支、不提交）
> 代码基线：`98dc80cdf1f9f300e7ed0ead8a51093d6b9b76fd` ｜ 原有未跟踪改动：108 数字人会话收尾排队晋升与 Offer 状态门禁任务书，必须保留
> 规格状态：READY_FOR_IMPLEMENTATION ｜ 实施状态：VERIFIED（2026-10-01 全书收口：40/40 卡 VERIFIED；本地四层门禁全绿——V-07 card C40 12/12、V-09 e2e 三引擎 54/54、V-10 recovery 4/4、V-11 all LOCAL_PASS；LIVE/生产未验，不构成商用/上线证据）
> 目标执行者：需要明确接线与验证步骤的编码模型 ｜ 任务卡：40 张 ｜ 具体验收用例：160 组（组内边界展开不得漏项）
> 起始卡：C107F2-01 ｜ 默认顺序：C107F2-01 → C107F2-40；依赖 DAG 见 §10，默认单执行者串行
> 执行模式：AUTO_CHAIN；仅在用户后续授权实施的卡范围内自动推进，不隐含派生代理或生产发布授权
> 交付终点：隔离 Docker 原生执行、真实浏览器产品闭环、安全/并发/灾备与文档状态验收完成；真实商业 Provider 与生产发布分开裁决
> 决策来源：用户要求基于本轮 35 组审计发现，按照模板生成尽可能详细的 107-fix-2 任务书。本轮只写规格与必要索引，不执行修复、不改变运行服务或业务完成状态。

**文档地位**：本书冻结 107 的纠错与接线目标。107-1/2/3、107-fix-1、107-4 作为历史需求和测试事实参考；其中“无需 UI 改动”“不新增 Edge 路由”“主链已完整可用”“真实整栈可选”等与本书冲突的结论，不再作为本修复批次的限制或通过证据。上位仓库指令、安全与资金边界仍须遵守。

**阅读协议**：开工先读 §0、§1、§2、§9、§10、§13、§14，再读当前卡引用的契约/测试；UI 卡额外完整读取根 DESIGN.md。卡内必需测试通过才能进入依赖卡。代码已有等价实现则核验并复用，不为旧描述重写；不能从历史 IMPLEMENTED 自动继承 VERIFIED。

**快速导航**：[审计问题映射](#26-缺陷事实) · [唯一接口契约](#6-唯一接口与跨层契约) · [文件白名单](#91-精确白名单) · [任务总表](#10-开发计划与任务总表) · [40张任务卡](#11-任务卡) · [160组用例](#122-160-组详细用例) · [验证命令](#123-唯一验证命令表) · [最终出口](#125-最终集成出口)。执行者按当前卡精确引用读取，不必每卡重读全文。

**编号**：审计发现 `F01…F35`；需求 `REQ-F2-01…40`；规则 `RULE-01…16`；决策 `D-01…12`；API `API-01…15`；卡 `C107F2-01…40`；用例 `TC-F2-卡号-序号`；验收 `AC-F2-卡号-序号`；命令 `V-01…15`。卡号只表示计划顺序，不是完成进度。

## 0. 执行协议

### 0.1 词义与范围

- FACT：本次源码/运行/测试核验的现状；DECISION：本书规划的目标；NEW：获本书登记但尚未实现的接口/文件；FIXTURE：隔离测试合成数据；NOT_RUN 不是 N/A。
- BLOCKED 只用于真实缺决策、受限授权、无法安全合并或排查后仍缺必需环境；普通编译错误、安装依赖、运行本地浏览器不自动阻塞。
- 本书就绪表示规格可以实施，不表示用户本轮要求执行 40 卡，更不表示任何业务已经修好。

### 0.2 强制规则

1. 只修改当前卡 W 集合与 §9.1 全局白名单的交集；已有改动必须保留。禁止 reset/clean、覆盖未知改动、清理别人的容器或卷。
2. upstream 0.2.16 固定树与 manifest 只读；引擎扩展只通过已登记 patch 物化 G。不得为了绕过集成问题升级上游或改 vendor。
3. public API、路由旗标、DTO、内部类型、持久化字段、错误与状态以 §5–7 为单一事实源；私有辅助拆分可自主，但不能暗改对外行为。
4. 默认先反例后修复。必需测试不得 skip、改成宽松断言、吞错误或伪造成功。组件 mock、真实数据库 IT、Docker/E2E 与 LIVE 分开记录。
5. 保留 fail-closed：默认禁用仍 404；显式启用须配置完整并完成真正 readiness。不得用全局放开路由、鉴权、CSP、网络或沙箱换通路。
6. 操作系统/容器隔离由真实执行位置证明；单有 network:none 的闲置容器不算隔离成功。费用由执行链与账务凭证证明，不能用 UI 确认替代服务端授权。
7. 本地开发可构建镜像、安装锁定依赖、启动隔离栈和测试迁移；先验证资源归属。当前 `y-1` 活跃栈只读，最终更新它需要用户明确要求；生产/真实账号/付费 Provider 也按已有授权边界处理。
8. 禁止新增 npm/Gradle 第三方依赖和升级 lockfile。本批复用 Node 标准库、Java ZIP/流式 I/O、既有 Vue/Vitest/Playwright/PG 工具；若事实证明不能实现既定契约，按 §13修订而不是偷换方案。
9. worker/文件 I/O 不占 WebFlux event loop，不用 .block() 或手动 subscribe 绕开事务/生命周期；Java 为域事实与权限/收费权威，Node broker 仅服务既定 Hypit 原生引擎边界，不另造公共业务后端。
10. 卡通过后自动进入已授权下一卡，不逐张请求批准。完成情况在对话汇报，不额外新建完成报告；测试日志/截图/机器结果按 §9.1 保存。

### 0.3 DoD 与状态

卡须满足：当前卡行为/边界已实现或核验证明已有实现；所有四组 TC 及组内输入均执行；命令实际发现目标测试且退出0；无意外 skip/缓存冒充实跑；持久结果重读一致；UI卡两主题及指定视口实际查看；范围/秘密/他人改动检查通过；按§14留证。

`NOT_STARTED → IN_PROGRESS → IMPLEMENTED → VERIFIED`；实质阻塞为 BLOCKED。IMPLEMENTED 不解锁依赖；验证后代码变化使证据失效，退回相应状态并重验。只有 C40 的任务级出口通过才可标本书 VERIFIED，必须同时声明 LIVE/生产状态。

## 1. 产品需求、目标与范围

### 1.1 一句话目标

让用户从 AI 端视频复刻工作区创建工程、提供素材、分析与编写、授权生成、预览/Studio 修改、审片、批量变体、下载/移植作品都能经真实服务完成，并能在重试、切工程、进程重启和灾备恢复后保持权限、数据、版本与费用一致。

### 1.2 背景与价值

2026-09-27 实机页面仍报404。根因之一是 frontend/Edge/Java 用数字人 overlay 重建而漏 Hypit overlay；旧 broker healthy 掩盖请求链未启用。进一步审计确认按钮空动作、Studio/Preview未接线、Agent工具缺失、DTO失配、二进制导入损坏和验收漏检等35组问题。需要修复完整用户流程与工程保障，不能只消掉首页报错。

### 1.3 范围内需求

下面40条全部是本次必须交付；对应卡与四组 AC/TC 一一映射。卡级层次不同，不把底层单测当用户闭环。

| 需求 | 用户/交付结果 | 责任卡 | 验收 |
| --- | --- | --- | --- |
| REQ-F2-01 | 固化审计反例、跨层契约样本与分层验证入口 | C107F2-01 | AC-F2-01-01～04 / TC-F2-01-01～04 |
| REQ-F2-02 | 统一 Compose 组合与启停路由，消除部署覆盖导致的 404 | C107F2-02 | AC-F2-02-01～04 / TC-F2-02-01～04 |
| REQ-F2-03 | 修正镜像目录、模板资源与运行期配置读取 | C107F2-03 | AC-F2-03-01～04 / TC-F2-03-01～04 |
| REQ-F2-04 | 接通独立无网络 runner，消除 broker 内作者代码执行 | C107F2-04 | AC-F2-04-01～04 / TC-F2-04-01～04 |
| REQ-F2-05 | 按模板建工程并打通空工程首版初始化 | C107F2-05 | AC-F2-05-01～04 / TC-F2-05-01～04 |
| REQ-F2-06 | 统一源码根并冻结计划、价格、Profile 和构建快照 | C107F2-06 | AC-F2-06-01～04 / TC-F2-06-01～04 |
| REQ-F2-07 | 安装真实 Provider 授权桥并锁定预算、范围与幂等 | C107F2-07 | AC-F2-07-01～04 / TC-F2-07-01～04 |
| REQ-F2-08 | 验收第一条真实 Docker 原生成片纵向链路 | C107F2-08 | AC-F2-08-01～04 / TC-F2-08-01～04 |
| REQ-F2-09 | 真实 readiness 与工作区顶层登录、禁用、不可用状态 | C107F2-09 | AC-F2-09-01～04 / TC-F2-09-01～04 |
| REQ-F2-10 | 统一文件 hash 契约与版本化保存，保护在途输入 | C107F2-10 | AC-F2-10-01～04 / TC-F2-10-01～04 |
| REQ-F2-11 | 工程深链、分页、创建导航和跨项目请求世代隔离 | C107F2-11 | AC-F2-11-01～04 / TC-F2-11-01～04 |
| REQ-F2-12 | 统一 Build/Outputs DTO 与真实归档、结果消费 | C107F2-12 | AC-F2-12-01～04 / TC-F2-12-01～04 |
| REQ-F2-13 | 统一 SSE 事件协议、增量游标与工作区恢复 | C107F2-13 | AC-F2-13-01～04 / TC-F2-13-01～04 |
| REQ-F2-14 | 落地 Agent 工具分发、逐步执行与真实成功判定 | C107F2-14 | AC-F2-14-01～04 / TC-F2-14-01～04 |
| REQ-F2-15 | 修复 Agent 租约、操作幂等、取消及 resume | C107F2-15 | AC-F2-15-01～04 / TC-F2-15-01～04 |
| REQ-F2-16 | 接通全片参考分析、转写与时间锚点 | C107F2-16 | AC-F2-16-01～04 / TC-F2-16-01～04 |
| REQ-F2-17 | 把复刻方案转换为有内容的源码与材料需求 | C107F2-17 | AC-F2-17-01～04 / TC-F2-17-01～04 |
| REQ-F2-18 | 接通分析、方案、生成、重试、取消与授权 UI | C107F2-18 | AC-F2-18-01～04 / TC-F2-18-01～04 |
| REQ-F2-19 | 启动原生 Studio 并接通 HTTP、WebSocket 与写回 | C107F2-19 | AC-F2-19-01～04 / TC-F2-19-01～04 |
| REQ-F2-20 | 修正 Studio 票据、只读、版本复用及撤销生命周期 | C107F2-20 | AC-F2-20-01～04 / TC-F2-20-01～04 |
| REQ-F2-21 | 限定 iframe 安全策略并验证三入口回归 | C107F2-21 | AC-F2-21-01～04 / TC-F2-21-01～04 |
| REQ-F2-22 | 构建绑定不可变版本的真实 Preview 服务 | C107F2-22 | AC-F2-22-01～04 / TC-F2-22-01～04 |
| REQ-F2-23 | 修复预览跨帧消息、播放状态与资源释放 | C107F2-23 | AC-F2-23-01～04 / TC-F2-23-01～04 |
| REQ-F2-24 | 使 Feedback 批次校验和落盘原子化 | C107F2-24 | AC-F2-24-01～04 / TC-F2-24-01～04 |
| REQ-F2-25 | 按评论做语义源码修改并关联解决状态 | C107F2-25 | AC-F2-25-01～04 / TC-F2-25-01～04 |
| REQ-F2-26 | 修复变体生命周期、重试取消路由与服务端授权 | C107F2-26 | AC-F2-26-01～04 / TC-F2-26-01～04 |
| REQ-F2-27 | 变体前端真实取消、重试、授权与状态展示 | C107F2-27 | AC-F2-27-01～04 / TC-F2-27-01～04 |
| REQ-F2-28 | 实现二进制保真的工程包与整包验证 | C107F2-28 | AC-F2-28-01～04 / TC-F2-28-01～04 |
| REQ-F2-29 | 完成导入 PG 登记、owner 绑定与跨服务幂等收敛 | C107F2-29 | AC-F2-29-01～04 / TC-F2-29-01～04 |
| REQ-F2-30 | 接通工程包浏览器下载、上传导入与进度反馈 | C107F2-30 | AC-F2-30-01～04 / TC-F2-30-01～04 |
| REQ-F2-31 | 完成参考素材文件上传和跨创作入口交接 | C107F2-31 | AC-F2-31-01～04 / TC-F2-31-01～04 |
| REQ-F2-32 | 修复维护模式写入栅栏与在途执行排空 | C107F2-32 | AC-F2-32-01～04 / TC-F2-32-01～04 |
| REQ-F2-33 | 完整备份 PG 与所有持久文件，校验备份清单 | C107F2-33 | AC-F2-33-01～04 / TC-F2-33-01～04 |
| REQ-F2-34 | 修复恢复目标映射、数据库核验和失败判定 | C107F2-34 | AC-F2-34-01～04 / TC-F2-34-01～04 |
| REQ-F2-35 | 收口会话、资产、任务及导入临时文件的生命周期 | C107F2-35 | AC-F2-35-01～04 / TC-F2-35-01～04 |
| REQ-F2-36 | 完成工作区双主题、移动端、键盘和全状态验收 | C107F2-36 | AC-F2-36-01～04 / TC-F2-36-01～04 |
| REQ-F2-37 | 真实浏览器贯通参考素材到成片、Studio 与工程包 | C107F2-37 | AC-F2-37-01～04 / TC-F2-37-01～04 |
| REQ-F2-38 | 执行并发、故障注入、重启与完整恢复演练 | C107F2-38 | AC-F2-38-01～04 / TC-F2-38-01～04 |
| REQ-F2-39 | 重建分层验收门禁，禁止漏跑和假全绿 | C107F2-39 | AC-F2-39-01～04 / TC-F2-39-01～04 |
| REQ-F2-40 | 复核 35 组缺陷闭合并完成任务级集成判定 | C107F2-40 | AC-F2-40-01～04 / TC-F2-40-01～04 |


### 1.4 范围外

- 不重做 Hypit 原生引擎、不追上游新版本、不删除原有功能来规避验证。
- 不改变数字人会话/Offer业务、组织计费策略或产品定价；仅在共享 Compose 配置处保证共存。
- 不进行生产发布、真实用户数据修复/删除、真实 Provider 付费验收；它们不是本轮生成文档的授权内容。
- 不承诺商业模型审美、拟真或“完全相同”的视频质量；本书要求生产路径真实接通且可检验，LIVE效果单列。

### 1.5 不许顺手修

108文档及其实现、无关UI重设计、全仓警告清理、历史 unindexed 文档、新增依赖升级均不在范围。发现新的关联缺陷若影响已定义链路，可按§13补充精确修订；无关问题只记对话交接。

### 1.6 用户、入口与限制

- 创作者：已登录 project owner；跨owner资源统一404防枚举。operator只管理运行配置/维护，无权绕过普通owner制作授权。
- 入口：`ai.html → src/ai/router.ts → /video-clone`、`/video-clone/:projectId`，兼容既有用户端交接和来源query。治理入口不新增功能，只做共享配置回归。
- 前置：显式启用、配置与必要程序ready；缺模型时清晰说明哪个能力不可用，不返回假内容。
- 唯一验收排除：未经授权的商业Provider LIVE调用、真实资金和生产发布。不排除本地整栈、原生渲染、真实浏览器、真实PG或文件恢复。缺这些必需环境则本书不能VERIFIED。

文档检查范围说明：全仓 `docs:links` 当前70个已存在于HEAD的未索引文档属于§1.5无关历史项，本批要求任务书/新增索引/新增文档无新错误或未索引。全仓命令的exit1仍照实记录，不改写为PASS；这项范围界定不豁免任何业务、Docker、E2E或数据完整性失败。

### 1.7 用户场景与业务闭环

| 场景 | 用户触发与主流程 | 交付物 | 中断恢复 |
|---|---|---|---|
| S1 最小原生制作 | 创建模板→修改源码→check/plan/pricing→无远程Need构建→结果下载 | 真MP4、冻结revision、结果索引 | job/buildId恢复，不重新生成 |
| S2 参考视频复刻 | 文件/URL/源媒体交接→全片分析→结构化方案→作者changeset→费用授权→构建 | 对应素材/字幕/时序的源工程与成片 | waiting_input补资料；旧分析失效明确提示 |
| S3 Studio/预览 | 打开指定版本→iframe真实加载/播放→编辑保存→评论语义修改→重生成 | 新revision、可追溯修改与正确评论状态 | 会话失效重开；版本冲突保留草稿 |
| S4 变体 | 创建轴组合→逐项计划授权→执行→失败项重试/取消 | 每项独立attempt/build/result | 成功项不重做，批次部分失败可继续 |
| S5 工程移植 | 导出冻结工程→浏览器下载→另一owner上传→创建/登记→打开再渲染 | 字节保真zip、新owner工程 | 同requestId查询/重放，不产生孤儿工程 |
| S6 运维恢复 | 检查启用组合→真实readiness→维护排空→PG+文件备份→新卷/新库恢复 | 可重开工程、版本链、资源与结果 | 失败非零/恢复维护；不触发新generation |

术语：revision为源工程不可变版本；Build为引擎持久构建，lifecycle与outcome不同；job为平台调度；session为可撤销编辑/预览能力；grant为受限费用执行许可；receipt记录已接受/完成的真实外部事实。202只代表受理。

### 1.8 产品成功标准

| 指标 | 本书阈值/判定 | 责任 |
|---|---|---|
| 35组缺陷 | 每组有关闭证据，不能仅改文案/隐藏按钮 | C40 |
| 用户闭环 | S1–S6必需流程通过；S2外部模型用明确fixture，但真实媒体处理/源码生成调用/原生渲染/PG/浏览器不替换 | C08/C37/C38 |
| 数据完整性 | 包往返所有必要文件hash相同；失败批次零部分写；CAS不丢在途输入 | C10/C24/C28/C29 |
| 权限和费用 | 越权字节0；无授权Provider调用0；同operationId最多一次接受；并发累计不超grant | C07/C15/C35/C38 |
| 浏览器 | Chromium/Firefox/WebKit，目标spec零意外skip、零业务console error；所有改动页面亮/暗自查 | C36/C37 |
| 灾备 | 真PG+完整文件恢复；错误SQL/缺文件必失败；恢复generation增量0 | C34/C38 |

### 1.9 决策权限与未决事项

实现所需目标、新增接口、迁移与默认阈值已在本书冻结，不留产品二选一。真实商业Provider名单/额度/凭据是外部授权事项，明确不作为本地验收已完成的事实；只有后续用户要求LIVE时才新增其可执行范围。局部函数拆分、算法等价实现、无语义变化的定位漂移可由执行者处理。

## 2. 仓库上下文与事实基线

### 2.1 目标端

AI/共享创作视图、Edge、intelligence-service、已有Hypit Node broker、runner、Nginx/Compose/验收脚本。用户端和治理台保持既有入口，做共享样式/部署回归，不新建第二套工作区。

### 2.2 设计路由

UI遵循根 `DESIGN.md`（已核对token、布局、控件、空错慢、截图章节）；若共享token确实影响ops，遵循 `src/ops/DESIGN.md` 并按 `[data-app="ops"]`隔离。实施UI前须读原规范，不以本书摘要替代。仓库 `AGENTS.md` 和 `docs/架构/目录结构.md` 是硬约束。

### 2.3 入口与调用定位

`VideoCloneWorkbench.vue → composables/hypit-api.ts → Nginx /api → edge-bff application.yml → hypit/api Controller → 域service/repository/worker → HypitSidecarClient → broker server/dispatcher → workspace/engine/runner → PG或结果资源 → SSE/GET → 视图`。

### 2.4 复用清单

| 对象 | 路径/符号 | 本批处理 |
|---|---|---|
| HTTP与错误 | src/composables/grassland-http.ts；hypit-api.ts/hypitRequest | 复用鉴权与错误，不另造fetch库 |
| 弹窗/空态 | src/components/GlModal.vue、src/components/shared/EmptyState.vue | 复用，无全局重设计 |
| 项目事务 | hypit/project/HypitChangesetService；workspace/transactions.ts | 修CAS与原子性，保持版本模型 |
| AI执行 | FrozenTextExecutionService；hypit/execution/HypitExternalExecutionBridge | 接真实工具/费用链，不旁路finance |
| 原生引擎 | engine/runtime-adapter.ts；runner/protocol.ts | 真编译/render，修根目录和隔离 |
| 会话 | studio/launcher.ts、proxy.ts、sessions.ts；preview/sessions.ts | 从未调用helper变成实际服务链 |
| 验收 | scripts/acceptance/ci-e2e-107.sh；scripts/ci-e2e.sh；Playwright配置 | 扩现有隔离栈，不创建第二套测试框架 |

### 2.5 当前行为

页面进入后直接拉项目列表，路由未启用会显示404；生成等按钮尚未连到对应写接口。底层源码已迁入，但Studio/Preview/Agent/工程包与正式平台契约之间存在断链。原有局部测试通过不等于这些调用路径可执行。

### 2.6 缺陷事实

F01–F35在下表原样保留审计语义；源码锚点和具体修复步骤见负责卡。表为FACT，目标行为见§4–8。安全/并发静态推导不被写成已发生资金事故。

| 审计ID | 已确认问题与证据类型 | 实现责任卡（C40统一复核） |
| --- | --- | --- |
| F01 | 当前 Docker 启动遗漏 Hypit overlay【实机】 | C107F2-02 |
| F02 | runner 路径、卷配置与隔离容器未接通【实机+复现+静态】 | C107F2-03、C107F2-04、C107F2-08 |
| F03 | 模板目录和 catalog 没有进入生产镜像【实机+静态】 | C107F2-03、C107F2-08 |
| F04 | 生产未注入 Studio 签票密钥【实机+静态】 | C107F2-03 |
| F05 | health/capabilities 不能反映真实功能可用性【实机+静态】 | C107F2-09 |
| F06 | 核心按钮没有对应业务动作【复现+静态】 | C107F2-13、C107F2-16、C107F2-18、C107F2-36 |
| F07 | 新建工程不打开；深链与 provisioning 恢复不完整【静态】 | C107F2-11、C107F2-36 |
| F08 | 切换工程有旧响应覆盖与编辑状态串用【复现+静态】 | C107F2-11、C107F2-35 |
| F09 | 源文件 hash 契约错误，保存状态也不可靠【复现+静态】 | C107F2-10 |
| F10 | “按评论修改”提交整文件替换为一行注释【复现+静态】 | C107F2-25 |
| F11 | 构建 outputs 返回形状与前端不一致【复现+静态】 | C107F2-12 |
| F12 | 归档调用错接口和动作【静态】 | C107F2-12 |
| F13 | 变体取消未实现，重试/取消网关漏路由【复现+静态】 | C107F2-26、C107F2-27 |
| F14 | 导出按钮丢弃回执，没有可下载工程包【静态】 | C107F2-30、C107F2-31、C107F2-36 |
| F15 | 工作区缺少顶层禁用/不可用状态判断【实机+静态】 | C107F2-09、C107F2-36 |
| F16 | Studio 仅创建票据，未启动/代理真正编辑器【实机+静态】 | C107F2-19 |
| F17 | Nginx 安全头会阻止 Studio iframe 嵌入【静态】 | C107F2-21 |
| F18 | Preview 没有展示服务、URL 和真实 revision 绑定【静态】 | C107F2-22、C107F2-23、C107F2-35 |
| F19 | Studio 复用忽略 revision/readOnly，新票据仍无法使用【复现】 | C107F2-20、C107F2-35 |
| F20 | Preview postMessage 校验与 sandbox 不匹配【静态】 | C107F2-23、C107F2-36 |
| F21 | Agent 主要制作工具缺失，失败动作仍被汇总为成功【静态】 | C107F2-14 |
| F22 | 参考分析、视频编写、审片修复没有接入实际 Agent 闭环【静态】 | C107F2-16、C107F2-17、C107F2-25 |
| F23 | Agent 租约、幂等、失败恢复与 resume 存在缺陷【静态】 | C107F2-15、C107F2-38 |
| F24 | SSE 前后端协议不同，服务端还存在无间隔重复查询【静态】 | C107F2-13 |
| F25 | Java 到 broker 的 plan/pricing 路径参数断裂【静态】 | C107F2-06、C107F2-08 |
| F26 | Build 未真正冻结 revision/Profile，估价可能与执行脱节【静态】 | C107F2-06、C107F2-08、C107F2-38 |
| F27 | Provider 授权桥未安装到真实执行路径，scope 校验方向不对【静态，发布阻断风险】 | C107F2-07、C107F2-08、C107F2-38 |
| F28 | 变体 worker 把 Build lifecycle 当 outcome，无法收敛【静态】 | C107F2-26、C107F2-27 |
| F29 | 模板选择无效，空工程无法进入制作闭环【静态】 | C107F2-05、C107F2-17、C107F2-31 |
| F30 | 工程包导入损坏二进制，且不校验 Run 可编译【复现】 | C107F2-28 |
| F31 | 工程包导入未登记 PG 工程，重试还会重复导入【静态】 | C107F2-29、C107F2-30、C107F2-35 |
| F32 | Feedback 批次不是原子修改【复现】 | C107F2-24、C107F2-38 |
| F33 | 在线备份排空、异常恢复和数据范围有缺陷【静态】 | C107F2-32、C107F2-33、C107F2-38 |
| F34 | 恢复脚本可把错误恢复报告为成功【静态】 | C107F2-34、C107F2-38 |
| F35 | “全绿”验收不覆盖正式产品闭环【已跑测试+静态】 | C107F2-01、C107F2-08、C107F2-37、C107F2-39 |


### 2.7 版本、运行和证据

| 核验项 | FACT |
|---|---|
| 源码 | main / 98dc80cdf1f9f300e7ed0ead8a51093d6b9b76fd；本书创建前无tracked业务diff，另有108未跟踪文档 |
| 上游 | manifest 0.2.16 / 557497b32a6658067c11bf924c7511e61c61df4e，1787 paths对vendor与固定Git archive均一致；本书不依赖“未来最新版本” |
| 前端 | package.json：Vue ^3.5.22、Vite 7.3.6、Vitest 3.2.7、Playwright 1.59.1；不升级锁定依赖 |
| Java | gradle/libs.versions.toml：Spring Boot4.1.0；Java25 toolchain、仓库wrapper；最大intelligence迁移V91 |
| Node | 当前宿主v22.22.3/npm10.9.8；broker声明≥24.14.1，Docker固定24.14.1；Node22探针不能替代Node24容器验收 |
| Docker现状 | frontend/Edge/Java labels漏Hypit overlay；projects GET404、capabilities401；旧broker healthy |
| 审计测试 | 前端/部署40通过、Studio19通过；新隔离回归6失败；backend探针复现票据复用、二进制损坏、无效Run、部分反馈写和env忽略 |
| 本书规划测试 | 仅文档结构/索引/链接/diff校验；所有未来修复测试均NOT_RUN，不预填PASS |

本机审计目录 `test-artifacts/task-107/audit-20260927/` 含audit.md、runtime-evidence.json、backend-probes.json、frontend-regressions.test.ts及日志，仅为可选历史证据；干净克隆不依赖这些忽略文件。C01把必要fixture与反例纳入正式测试。不能照抄旧任务书“完整可用”的文字替代当前事实。

### 2.8 FACT / DECISION / NEW

§2是事实；§3–8是本次纠错决策。§9表中不存在的路径标NEW，不能声称已有。合成UUID、媒体和模型fixture只用于测试，不是生产账号/凭据/质量承诺。

### 2.9 兼容与影响

影响HTTP/DTO、SSE、异步状态、文件/PG、权限、执行费用、三入口部署。DTO采用先增兼容字段再迁前端；旧SSE eventId在解析处兼容；新的会话安全链必须与前端/代理协调发布，不允许老不安全实例继续写。旧revision0工程显式初始化，旧项目包版本@1继续可读但执行完整校验。既有已执行迁移不改。

## 3. 技术决策

| 决策 | 唯一方案、理由与约束 |
|---|---|
| D-01 上游与分层 | 固定0.2.16；Java拥有权限/任务/费用/工程登记，broker拥有受控原生引擎和文件操作，作者逻辑进入runner。来自已有架构及本轮审计，不重写Hypit。 |
| D-02 部署组合 | 单包装脚本固定overlay次序；启用显式且配置完整，共享服务重建不得遗失既有启用模块。默认关闭保持404。 |
| D-03 路径 | 镜像backendRoot=/app；模板/cat显式路径；生产dataRoot=/data/hypit、持久伞/data。解析集中到config，不再靠generatedRoot倒推目录。 |
| D-04 隔离执行 | 独立无网络runner可信daemon+一次性子进程；broker只Unix IPC与slot文件，不挂Docker socket、不降级本进程执行。容量1是安全起点，不靠多槽优化交付。 |
| D-05 工程版本 | clone/brief新工程采用可编辑中性骨架revision1；不是完成作品。计划、价格、授权与build绑定immutable snapshot+真实profile摘要；老零版显式幂等bootstrap。 |
| D-06 对外兼容 | 文件hash为权威；outputs恢复items/nextCursor并保留旧detail字段；归档保持200同步回执；创建/构建/导出导入任务202。不机械按旧错误测试固定状态。 |
| D-07 Agent | registry决定可见工具；一次一个持久动作、观察后再规划；先prepared后副作用；租约续期+CAS fencing。局部实现可调整，幂等/终态/费用不可变。 |
| D-08 Studio | 启动可信原生Studio，HTTP/WS由broker受控代理；保存走Java changeset，作者求值仍runner。Nginx每次HTTP/WS握手经Edge做session access，signed断言仅内部。 |
| D-09 Preview | opaque sandbox只allow-scripts；父页用source+nonce+schema验消息；读取不可变快照，resource served-set鉴权，不创建Build。 |
| D-10 导入导出 | 原生@1 manifest+标准ZIP流；二进制独立staging不走文本changeset。公开导入multipart owner，原operator JSON宿主路径入口关闭；受控运维不通过浏览器路径操作。 |
| D-11 持久会话 | 新V92表绑定owner/project/revision/readOnly/expiry/revocation；临时票nonce核销持久，重启旧进程会话失效。其余复用既有job/action/command/execution字段，无新增资金表。 |
| D-12 交付证据 | 必需Docker+浏览器+PG+真实文件+原生成片；外部模型fixture明确标注。LIVE单列，不能用fixture证明商业模型质量，也不能因缺LIVE免除本地完整验收。 |

这些是本书作者在“修复已发现问题、恢复原任务目标”范围内作出的规划决策，不表述为用户逐项批准过实施或上线。新增路由、配置与迁移全部在§6–9登记。

### 3.1 端到端接线与责任

| 链路 | 输入→消费结果 | 主责卡与证据 |
|---|---|---|
| 部署→入口 | overlay/flags→真实鉴权业务API | C02/C03，TC02、TC03 |
| 项目→原生成片 | project/revision→plan/pricing/grant→runner→runtime→结果可解码 | C04–08，TC08-01 |
| 工作区→状态恢复 | capabilities/getProject/action→job/SSE→组件与URL | C09–13/C18 |
| 素材→分析→作者 | probe/抽帧/转写→结构化证据→方案→validated changeset | C14–17/C31 |
| Studio→源码保存 | session access→原生HTTP/WS→mutation bridge→Java CAS | C19–21 |
| Preview→播放 | revision快照→transient执行→served资源→父页frame消息 | C22–23 |
| 评论→修复 | feedback批次→review intent→目标源码diff→resolve | C24–25 |
| 变体→结果 | axes→每项plan/grant/build→worker outcome→UI | C26–27 |
| 工程包→新owner | zip下载→multipart→staging校验→PG登记→重开渲染 | C28–31 |
| 维护→恢复 | 写栅栏→排空→PG+文件→新栈重开 | C32–35/C38 |
| 整体交付 | UI真实操作+负向故障→逐缺陷证据判定 | C36–40 |

## 4. 目标行为

### 4.1 用户流程

进入工作区先判登录/启用/可用性；列表与工程详情独立加载。创建后直达项目，素材来源可见。分析必须有全片证据，方案必须绑定对象/时序/材料，生成前显示费用与授权。任务受理后可刷新恢复，取消等待服务端确认。结果可播放/归档/下载；Studio保存或评论修复产生新revision，旧版本构建不被污染。导入工程拥有自己的owner与可继续编辑的文件。

### 4.2 行为变化

| 条件 | 目标行为 |
|---|---|
| 未登录/过期 | 复用登录入口；不保留上一账号私有内容，不把401当空列表 |
| 功能关闭 | 说明未启用，不发后续业务请求；operator看到脱敏处理入口 |
| 服务不ready | 指出runner/模板/模型/会话哪个依赖失败，可重试但不假成功 |
| 列表空/深链不存在 | 空列表给创建；深链404给返回列表，不能无限spinner |
| 参数/编译失败 | 字段错误/诊断与输入同处，保留草稿，不发生未授权副作用 |
| 请求超时但已受理 | 同requestId查询原job/command，不能随机新ID重做 |
| 切工程/账号 | generation隔离所有success/error/finally，旧会话不渲染，服务端job不因断观察而取消 |
| 部分批次成功 | feedback为整批原子；variants为逐项独立，两种语义不能混用 |

### 4.3 三层状态权威

| 对象 | 合法状态及权威 |
|---|---|
| Project | 既有 provisioning/ready/provisioning_failed/deleting/deleted，PG权威；ready须真实head≥1 |
| Job | queued/running/waiting_input/cancel_requested/succeeded/failed/cancelled，PG权威；phase是步骤，不替代state |
| Build | lifecycle=submitting/active/execution_decided/result_pending/finished/submission_incomplete；outcome=complete/failed/cancelled/null，原生观察映射到PG |
| Variant | 沿既有变体状态；只有完成且结果可用才成功，每次retry一个attempt |
| Session NEW | starting/active/failed/closed/expired/revoked，PG会话权限权威、broker执行状态附属；active不代表作品生成完成 |
| UI | idle/loading/empty/ready/error/submitting/conflict，内存临时；不能提前把202当完成 |

### 4.4 迁移与恢复

创建：provisioning→ready或provisioning_failed；删除先deleting再deleted。Job：queued→running→waiting_input/succeeded/failed；合法resume使waiting_input→queued，消费新inputs并保持scope；queued/running/waiting_input→cancel_requested→cancelled，已完成不被晚到取消覆盖。CAS以当前状态/version和租约owner更新，重复动作返回已存回执。客户端abort只停止请求观察，服务端副作用按command/job终态处理；退款/费用根据真实执行凭证，不根据断网或按钮。

刷新恢复projectId/run/step/jobId，不把票据放URL持久化；重新获取session。SSE sequence单调去重；旧generation即便错误/finally也不能修改当前页面。draft属于account+project+path，离开必须明确保存/放弃，不跨项目复用。

## 5. 业务规则

### 5.1 输入与边界

| 字段/对象 | 唯一规则 |
|---|---|
| project/build/job/grant/requestId | UUID语义沿既有Controller；新增请求显式requestId，不以随机重试破坏幂等；缺/空/非法400 |
| title | 沿既有trim后1–60个Java/JS UTF-16 code units；61拒绝；旧长数据显示不静默截断。120字仅作显示鲁棒性fixture，不是合法创建输入 |
| mode | clone/brief/template/import，大小写敏感；template必须有catalog内templateId；不信任sourcePath |
| path/runFile | 相对路径，沿workspace/paths：≤255字符、≤16段，拒绝绝对/.. /编码绕过/反斜线/控制字符/符号链接越界 |
| revision/baseRevision | 新工程≥1，安全整数；旧0仅显式bootstrap路径接受；未知/负数/浮点拒绝 |
| hash/baseHash | 64位十六进制SHA256；已有文件必有hash，新文件baseHash=null表示预期不存在；缺省不是无条件覆盖 |
| source文本 | 单文件2MiB，单changeset16MiB，按UTF-8字节计；二进制走资源/包专用通道 |
| 文件/工程包 | 素材上传≤256MiB；包压缩体≤4GiB、展开≤4GiB、≤20000文件；按实际流字节与manifest双检 |
| 时间/帧 | UTC ISO8601传输、UI本地显示；timeSeconds非负有限数，frame非负安全整数；ticket/session expiry由服务端时钟判断 |
| 金额 | 既有numeric(20,6)与十进制字符串，保持currency；不使用JS浮点做授权累计，不新增收费单价 |
| 缺省/null/0/false | 金额未知null不是0；readOnly缺省false但不得在复用中提升权限；空票据/空Run不作为成功值 |

### 5.2 校验优先级

先身份→功能启用/能力→资源归属→类型/范围/路径→业务状态→revision/hash→幂等请求匹配→grant/预算→副作用。多错误不泄漏资源存在性；跨owner404优先于文件内容诊断。前端仅做体验校验，绕过前端也必须同规则。无响应是网络错误，不伪造服务端code。

### 5.3 业务判断与不变量

| 规则 | 行为及拒绝副作用 | 主要验证卡 |
|---|---|---|
| RULE-01 启用组合 | 默认关闭；显式开启缺配置失败；共享重建不得静默关掉已启用模块 | 02/03/09 |
| RULE-02 可信边界 | 作者代码仅runner；broker无fallback；只挂授权slot；外网/秘密/兄弟slot禁止 | 04/19 |
| RULE-03 工程身份 | templateId真选择；clone/brief可编辑首版；同键同项目，failed不得ready | 05/29 |
| RULE-04 版本冻结 | 同一操作绑定revision+manifest+run+profile；排队后不读当前work | 06/08 |
| RULE-05 费用授权 | 执行目标全覆盖、金额/次数原子预留、receipt唯一；未知不盲重试 | 07/15/26 |
| RULE-06 文件保存 | 两级CAS，提交快照与在途输入分离；冲突零覆盖、草稿保留 | 10/25 |
| RULE-07 对象隔离 | account/project generation控制所有回调，深链不依赖首屏列表 | 11/35 |
| RULE-08 成功判定 | 必要动作和产物满足才succeeded；未实现工具不能宣称可用 | 14/17/18 |
| RULE-09 持久执行 | prepared先于副作用，lease owner fencing，terminal CAS；resume消费输入不扩scope | 15/38 |
| RULE-10 分析/作者 | 全片证据覆盖、目标内容真正写入源码；无声可无转写、部分分析不可冒充全片 | 16/17 |
| RULE-11 审片反馈 | feedback整批原子；评论修改先真实apply再resolve；不可定位需澄清 | 24/25 |
| RULE-12 变体 | 每项独立attempt与结果，失败项重试，成功项不自动重做 | 26/27 |
| RULE-13 维护排空 | 先封写再等在途；维护租约归属；取消/凭证收敛可继续但不得新生成 | 32/33 |
| RULE-14 工程包 | 原字节与manifest一致、完整校验后发布、PG登记可恢复且owner明确 | 28–31 |
| RULE-15 恢复 | 新目标/完整PG+文件、任一验证错误非零，不触发新的generation | 34/38 |
| RULE-16 证据 | 工具/层级/用例数/实际产物均真实；mandatory NOT_RUN/skip阻断VERIFIED | 01/36–40 |

列表：owner筛选与project筛选为AND，按createdAt DESC、id DESC稳定分页，默认50上限100，空页items=[]/nextCursor=null；游标无效400，不把列表外深链误判不存在。variants笛卡尔积沿既有服务上限，达到上限及+1必须独立测，不擅自扩大批量规模。

### 5.4 权限

匿名：capabilities仍需既有登录，未登录401；无owner资源权限404。owner可制作自己的工程、导出/导入自己的包和关闭自己会话。operator可检查运行环境/维护但不因身份跳过owner/grant。Nginx内部session access只验证当前站内会话，不接受浏览器伪造身份header；Node内部调用需强token或短期签名断言。失败不新增可见项目、有效收费、可访问文件或成功事件。

## 6. 唯一接口与跨层契约

### 6.1 API-01：路由、信封、身份与错误

既有路由来源 `contracts/hypit-api.v1.json`、实际Controller与Edge；本书目标契约版本3.2.0（NEW，保持format @1）。Java的错误形状保持 `{success:false,error:string,code?:string,details?:object}`，前端兼容既有对象error输入但不要求全站改信封。普通JSON成功 `{success:true,data:T}`；SSE、ZIP、媒体不是JSON信封。

新路由逐项登记，method/flag/固定上游intelligence三向一致；默认flags=false，启用overlay为true。模板占位符花括号仅表示路由参数，不是未决条款。

| API | method/path | flag（NEW或补登记） | 身份/成功 |
|---|---|---|---|
| API-10 | GET /api/hypit/sessions/{sessionId}/access | EDGE_ROUTE_HYPIT_SESSIONS_ACCESS | owner且会话active；204、no-store、内部签名header；无JSON |
| API-10 | DELETE /api/hypit/projects/{projectId}/studio-sessions/{sessionId} | EDGE_ROUTE_HYPIT_SESSIONS_STUDIO_CLOSE | owner，200 data={closed:true}，幂等 |
| API-10 | DELETE /api/hypit/projects/{projectId}/preview-sessions/{sessionId} | EDGE_ROUTE_HYPIT_SESSIONS_PREVIEW_CLOSE | 同上 |
| API-12 | POST /api/hypit/projects/{projectId}/variants/{id}/retry | EDGE_ROUTE_HYPIT_VARIANTS_RETRY | owner，200当前变体DTO；requestId必需 |
| API-12 | POST /api/hypit/projects/{projectId}/variants/{id}/cancel | EDGE_ROUTE_HYPIT_VARIANTS_CANCEL | owner，200当前变体DTO；requestId必需 |
| API-13 | GET /api/hypit/exports/{exportId}/download | EDGE_ROUTE_HYPIT_PACKAGES_DOWNLOAD | owner，200/206 application/zip，过期410 |
| API-14 | POST /api/hypit/projects/{projectId}/assets/upload | EDGE_ROUTE_HYPIT_ASSETS_UPLOAD | owner，multipart，202 AcceptedJob |

既有 imports 从当前误实现的operator JSON目录参数纠正为契约声明的owner multipart；这是有意变更，旧JSON请求415并说明使用文件上传，不继续开放宿主路径。归档契约由错误的AcceptedJob标注纠正为当前200结果回执（§6.5）；不因历史E2E断言错误而改成假202。

| HTTP | code/情况 | 前端动作 |
|---|---|---|
| 400 | hypit_invalid_input / hypit_invalid_path | 保留输入定位字段，不自动重试 |
| 401 | hypit_unauthenticated或既有会话错误 | 登录恢复；清旧账号私有状态 |
| 403 | hypit_operator_required；NEW hypit_session_read_only | 显示权限原因，零写入 |
| 404 | hypit_not_found；Edge未匹配/禁用可能空body | 区分capabilities与工程不存在，不展示堆栈 |
| 409 | hypit_revision_conflict / hypit_idempotency_conflict / hypit_plan_stale；NEW hypit_feedback_conflict | 草稿保留，刷新权威状态；不能自动覆盖 |
| 410 | NEW hypit_session_expired / hypit_export_expired | 显示重新打开/重新导出 |
| 413/415 | hypit_too_large / NEW hypit_unsupported_media_type | 展示范围或上传方式，不保留ready假资源 |
| 422 | hypit_compile_failed / hypit_missing_material / hypit_unsupported_capability | 显示诊断、缺项与可行动入口 |
| 429 | hypit_capacity_exceeded | 展示容量限制，按Retry-After恢复 |
| 503 | hypit_backend_unavailable / hypit_disabled；NEW hypit_maintenance | 显示依赖/维护原因，保留已受理任务追踪 |
| 无响应 | 无服务端code | 同幂等键查询受理结果后才重试 |

NEW错误码由责任Controller登记到commonErrors和测试；不新增其他同义code。内部runner/授权错误映射到已有业务域码，禁止泄漏URL令牌、路径、秘密或上游堆栈。普通读请求最多2次重试（1/2秒），写仅复用原requestId且确认语义，超过后交用户；SSE按§6.6。

### 6.2 API-02：工程创建、读取与分页

沿 `HypitProjectController` 的POST /projects、GET /projects/{id}、GET /projects与 `HypitDtos.ProjectCreated/Page/Project`。创建body沿CreateRequest，title/mode/templateId/sourceContext见§5.1；202含project与AcceptedJob。新增分页query cursor可缺省，limit默认50、上限100；nextCursor为服务端opaque值，稳定排序按§5.3。

模板模式传受控templateId到broker，禁止公开sourceDir。clone/brief技术骨架revision1；现存revision0首次制作bootstrap使用稳定commandId，不删除历史。GET不存在项目404，不能用列表分页替代GET。创建幂等作用域account+kind+requestId，同键不同canonical payload409。

### 6.3 API-03：计划、价格、构建与授权冻结

沿 `HypitBuildController` / `HypitPlanService` / `HypitGrantService` / `HypitBuildService` 的既有公开DTO。公开submit仍为requestId/planId/grantId/title；broker build.submit内部目标载荷新增强绑定：

```ts
export interface FrozenBuildCommand {
  projectId: string;
  revision: number;
  manifestHash: string;
  profileHash: string;
  planId: string;
  pricingId: string | null;
  grantId: string | null;
  runFile: string;
  title: string | null;
}
```

定义落在 `engine/engine-port.ts`（C06写入），Java acceptCommand生产该载荷，dispatcher消费；不接收浏览器宿主路径。计划生成时捕获同revision快照和有效Profile版本，pricing只消费这个冻结计划。提交前head/profile变了409；受理后新编辑不改变已受理Build输入。无远程Need可grantId=null，其他情况不可。

授权：实际执行targets必须全部落入grant scope，空集合不授权远程调用；每次执行沿既有 `PrepareRequest/ExecutionBridge`（providers/authorization.ts）及Java `/internal/hypit/executions`。金额、currency、expiry、revoked_at、variant_count沿原表，变体数与费用预留以PG事务/锁或等价原子操作保护。operationId由job/action/build/need稳定身份派生，重试不换。成功/失败/取消/unknown凭证都走既有结算；不创建本书新价格。

### 6.4 API-04：文件与 changeset

GET /projects/{id}/file?path 的 data 正式目标：

```ts
export interface HypitFileContent {
  path: string;
  content: string;
  hash: string;
  revision: number;
  /** 兼容旧客户端；值必须等于 hash，前端新代码不读此字段。 */
  baseHash?: string;
}
```

定义在src/types/hypit.ts，broker返回hash/revision，Java边界可加别名。create/apply沿既有端点；changes[].baseHash是已有文件CAS值或新文件null，revision同时CAS。apply成功读取新hash；validated失败不发布新head，save模式允许语法草稿但不得直接作为成功Build。保存时draft副本、project/path、requestId、generation固定，响应只能确认提交副本。

### 6.5 API-05：Build、outputs、归档与媒体消费

`HypitDtos.Build/Output` 与src/types/hypit.ts相同字段语义为准，兼容旧submittedAt作为createdAt别名。lifecycle/outcome/resultReady不同维度，不做假映射。outputs列表目标完整边界如下，引用类型来自src/types/hypit.ts：

```ts
export interface HypitOutputListData {
  items: HypitOutput[];
  nextCursor: string | null;
  build: HypitBuild;
  planSnapshot: Record<string, unknown>;
  /** 旧detail响应兼容，内容必须与items相同。 */
  outputs: HypitOutput[];
}
export interface HypitArchiveRequest {
  requestId: string;
  outputNames: string[];
}
export interface HypitArchiveData { outputs: HypitOutput[]; }
```

GET outputs=200；当前结果规模沿既有build输出集合，nextCursor=null不伪造分页。POST /builds/{id}/archive=200且data.outputs反映真实归档；outputNames非空且名字存在，重复名去重不重复固化。result-actions仍只finish/discard。媒体浏览/下载用既有归档资源与受控导出通道，不把二进制响应按JSON解包。未知size/duration=null而非0；空列表items=[]。

### 6.6 API-06：任务与 SSE

沿GET /projects/{projectId}/jobs/{jobId}及其/events；SSE认证沿站内会话，fetch流支持Last-Event-ID，不擅自改变其他模块POST SSE。使用src/types/hypit.ts的完整HypitSseEvent：id、sequence、type、projectId、jobId/buildId、at、data。data保留该事件业务payload，客户端根据type更新或重读job，不能凭不存在的kind/job字段解析。

```text
id: 11111111-1111-4111-8111-111111111111:8
event: progress
data: {"id":"11111111-1111-4111-8111-111111111111:8","sequence":8,"type":"progress","projectId":"22222222-2222-4222-8222-222222222222","jobId":"11111111-1111-4111-8111-111111111111","at":"2026-09-27T00:00:00Z","data":{"phase":"compiling"}}
```

初始/非法cursor发snapshot reset，带当前权威job及最新sequence；正常增量严格大于cursor、每批100。旧evt-job-sequence只兼容解析，跨job或超出可用历史reset而非泄漏。每秒最多一次空轮询，heartbeat15秒，terminal之后关闭。客户端重连1/2/4/8/15秒最多5次，保留最后确认cursor；未登录停止重连并走登录。客户端close不等于服务端cancel。

### 6.7 API-07：Agent 分析、作者、审片与动作

公开创建沿AgentJobCreate（requestId/intent/brief/assetIds/baseRevision/scope），intent既有analyze/author/review等白名单不扩造同义入口；参数上限沿实际服务，补负向测试。scope只能收窄工具/目标和授权，不允许浏览器指定provider URL、凭据、宿主路径。公开resume/cancel沿JobActionRequest，requestId必需；resume input合并后进入下一规划轮，不能扩scope。

工具registry中的输入必须保持JSON类型，unknown工具拒绝；可见工具集合=实际handler集合。动作数据库kind保持llm/tool/apply/approval/review，实际工具名存在input_json，不把tool名直接写kind违反CHECK。checkpoint目标结构在现有JSON字段中，不新增列：

```ts
export interface AgentCheckpointV2 {
  schemaVersion: 2;
  intent: string;
  brief: string;
  baseRevision: number;
  round: number;
  actionIndex: number;
  totalActions: number;
  phase: 'planning' | 'executing' | 'observing' | 'waiting_input';
  inputs: Record<string, unknown>;
  scope: Record<string, unknown>;
  pendingActionId: string | null;
  blockedReason: string | null;
  observations: Array<{ actionId: string; state: string; result: Record<string, unknown> }>;
}
```

定义落在Java worker与测试fixture的序列化契约（类型块用于约束JSON，不新增无调用的TS生产文件）。旧checkpoint读入时补schemaVersion/计数默认，已有成功action不重跑。最多40动作/轮、120累计；有必要动作失败不能成功。最大两轮编译修复、planner瞬时失败最多重试2次，超限failed，原始响应脱敏留在受控既有运行记录。

### 6.8 API-08/09：broker 路径、runner 与 readiness

配置唯一表：

| 配置 | 默认/生产目标 | 校验与影响 |
|---|---|---|
| HYPIT_ENABLED | 默认false；显式启用组合true | Java与Edge启用组合一致 |
| HYPIT_SIDECAR_BASE_URL | 沿既有配置，隔离内网broker:9240 | 不接浏览器任意地址 |
| HYPIT_INTERNAL_TOKEN | 无生产默认，≥32字符 | 只内部，缺省拒启动启用功能 |
| HYPIT_STUDIO_TICKET_SECRET | 无生产默认，≥32字符 | Java/broker一致，签票与内部session断言用途分域 |
| HYPIT_DATA_ROOT | 生产/data/hypit | 现存兄弟目录布局保留；备份使用完整/data伞 |
| HYPIT_GENERATED_ROOT | /app/platform-hypit/.generated/hypit | manifest/patch digest核验 |
| HYPIT_TEMPLATES_ROOT | /app/platform-hypit/templates | catalog及引用受控根 |
| HYPIT_FIXTURES_ROOT NEW | /app/platform-hypit/fixtures | minimal-local/blank均可读 |
| HYPIT_RUNNER_SOCKET_DIR | /sockets | 必须真实读取env，共享socket卷 |
| HYPIT_RUNNER_SLOT_ROOT | /slots | 仅受控slot子路径，不能共享整个/data |
| HYPIT_STUDIO_UPSTREAM | 禁用空；启用hypit-backend:9240 | 前端Nginx仅代理受控会话路径 |

runner协议复用 `runner/protocol.ts` v1 framing、1MiB消息上限；大媒体只slot handle不base64。supervisor不spawn作者进程；daemon在隔离容器创建每命令子进程，最低字段仍commandId/requestId/kind/payload。新增内部payload字段slotId/relativeInputRoot只由broker生成且验证containment；status握手返回protocolVersion、engineDigest、capacity=1，不含敏感路径。check/plan/compile不得触发远程Need执行。

readiness：healthz表示进程活；新内部GET /internal/v1/readiness需token，返回每依赖布尔及脱敏reason；缓存最多5秒，探测超时2秒，不靠healthz映射全feature ready。Javacapabilities保留现有FeatureReadiness结构，version来自manifest，Studio/Preview只有实际启动/代理能力通过才能ready。

### 6.9 API-10：Studio/Preview 会话与代理安全

创建端点沿既有POST studio-sessions/preview-sessions，requestId、runFile、revision及readOnly（Studio）沿当前DTO；成功必须非空URL/期限/版本，202 data：

```ts
export interface HypitSessionCreated {
  sessionId: string;
  ticketUrl: string;
  expiresAt: string;
  revision: number;
  readOnly: boolean;
  reused: boolean;
  messageNonce: string;
}
```

复用键owner/project/kind/run/revision/readOnly全等；票60秒一次、session3600秒。ticket含sid/nonce/exp与用途限定HMAC；PG保存nonceHash，成功CAS核销；重开可签新nonce，旧已核销票拒绝。URL不入日志/截图/长期storage。创建成功必须进程ready及访问链可用；失败清session进程和端口，不能立刻resolve伪pid。

session access API经站内Cookie和owner/active/expiry判断，204返回内部 `X-Hypit-Session-Assertion`；其签名claims为sid、projectId、ownerAccountId、revision、readOnly、exp（30秒）、aud=hypit-session-proxy、nonce。HMAC用途与票据区分，Nginx仅取auth_request返回值，覆盖浏览器同名header。broker校验签名/时效/会话再代理，仅内部网络可达；不放内部bearer给浏览器。首次访问还核销ticket，随后受限路径HttpOnly/SameSite=Strict session cookie仍需access校验。WS握手验证Origin/会话，后续mutation再做权限与CAS。

Studio可信UI可使用同源必要能力，但禁止把作者HTML在有父域权限的页面任意执行；作者编译/渲染走runner。页面局部XFO SAMEORIGIN/CSP frame-ancestors self，其他主页面仍原DENY策略。只代理固定实例的资源与/__studio/ws，禁止任意host穿透。保存沿Java changeset，不直写work绕过revision。

DELETE close幂等200；已无权限404；已expired可返回closed=true（仅原owner），撤权立即停止access，broker回收周期≤60秒。删除项目或账号注销必须撤销会话并终止WS/进程。重启发现PG active但进程丢失改failed/closed，UI显式重新打开，不伪称session恢复。

### 6.10 API-11：Preview 显示与消息

preview绑定真实snapshot，transient execution输出/served set只服务该版本；资源URL路径/Range由broker映射，跨session资源404。iframe sandbox仅allow-scripts，origin为null是正常值；父页严格校验event.source、sessionId、messageNonce、schema，不能校验host子串。ready/frame/error三类消息分别带type，frame还带frame/timeSeconds，error只带脱敏message。父页发送控制消息使用显式iframe目标窗口和nonce；因opaque origin需使用星号targetOrigin时不得附带秘密/身份令牌，接收仍严格验source与nonce。

缺版本/资源不生成空src成功。关闭preview撤销资源/transient execution，不取消同项目Build。播放时钟来自预览实际frame消息，不用前端计时器猜进度。

### 6.11 API-15：Feedback 与 review

沿现有feedback GET/POST及上游mutation schema；整批expectedHash校验、先全解析再临时文档应用并一次原子替换。反馈修改HTTP受理沿当前Controller202，客户端追踪回执而非假200；成熟同步内部store返回真实文档/hash。评论revise公开走Agent review intent，输入commentIds与对应run/revision，不能发送一行注释替代源码。

review定位失败waiting_input，给具体对象/时间需要补充；同对象互斥修改要求用户选择。apply成功后批量resolve，挂comment→job→revision对应，失败评论保持open，源码不变。

### 6.12 API-12：Variants

GET/create/build沿既有controller参数；retry/cancel新增Edge登记但不复制Java业务。retry/cancel body={requestId:string}，返回200既有Variant DTO；构建202 BuildCreated。finished+complete+resultReady映射succeeded；finished+failed/cancelled按outcome映射，result_pending不得先成功。成功项不可retry（409），失败/取消项retry attempt+1，重复键不+2。远程每项仍需覆盖其冻结计划的grant，批次费用/次数不能越界。

### 6.13 API-13：可移植工程包、下载与导入

导出POST既有export body保留requestId/title/runFile；202 AcceptedJob（对旧artifactRoot响应为明确纠错，不再把宿主路径当下载）。job成功时checkpoint/result含完整描述：

```ts
export interface HypitPackageDownload {
  exportId: string;
  downloadPath: string;
  filename: string;
  mediaType: 'application/zip';
  sizeBytes: number;
  sha256: string;
  expiresAt: string;
  revision: number;
}
```

落在src/types/hypit.ts，下载ID复用export command UUID，owner从command/project查询不信任客户端。GET download支持200/206、Accept-Ranges:bytes、Content-Range、ETag=sha256、Content-Disposition安全UTF-8文件名；无效Range416；Cache-Control:no-store；24小时TTL过期410。新导出用新requestId，原revision存在才能重做，不重新generation。

POST imports：Content-Type multipart/form-data；字段requestId UUID、file（ZIP，唯一），可选title沿60字限制；不手写boundary。流式接受与验证，202返回ProjectCreated（project.status=provisioning + AcceptedJob）；普通owner可上传，旧JSON artifactRoot415，不能把普通用户权限扩到任意宿主路径。重复requestId匹配上传sha256，异内容409。同项目身份先PG预留再broker导入，成功/失败均可查询。

Node broker内部NEW `/internal/v1/package-transfers/{transferId}/content`：PUT application/zip流上传，成功201 `{transferId,sha256,sizeBytes}`；GET为只读ZIP流；需internal token、transferId由Java command派生且与owner intent绑定，不能带sourceDir。Java所有权验证后才能发内部请求。ZIP编解码使用JDK标准流式工具，broker目录staging读写使用Node fs流，文件读写受限根；不执行zip中程序，仅通过隔离runner编译验收。单流缓冲≤1MiB，批处理固定并发≤2，不随包总大小全量读内存。

包沿ProjectPackageManifest @1完整字段（project/files/packages/results/omitted），禁止包带凭据、journal、绝对路径。必要资源缺失拒绝导出。导入验证压缩/展开各≤4GiB、≤20000entry，防重复路径、zip-slip、symlink/hardlink；二进制保持原字节，落盘二次hash；selectedRun必须原生parse/check成功。失败staging留最多24小时用于受控重试，不能公开ready工程。浏览器导入再次渲染是最终移植验收，不只验manifest存在。

### 6.14 API-14：素材上传与 sourceContext

POST /projects/{id}/assets/upload：multipart字段requestId、file；文件≤256MiB，MIME由服务端probe核验，accepted为202，结果通过job/资产列表获得。沿既有HypitAssetService与资源handle注册，不能浏览器直写broker目录。URL导入保留现有trusted-host/DNS/SSRF限制；不为测试任意放开localhost，fixture host在隔离网络受控登记。

sourceContext仅kind/id，无身份授权意义；先检查原媒体/分析归属与固化状态，再复制/引用到受控资源并建立asset_reference。相同project/hash复用字节；源撤权与独立副本按既有生命周期区别处理，不能让失权外链继续可读。上传失败不出现ready资产，取消上传不表示已启动分析被取消。

### 6.15 运维内部契约：维护、备份与恢复

既有 `/internal/v1/maintenance/enter` 增强为token鉴权、原子封写；请求 `{reason:"backup"}`，新窗口200 `{leaseId,drained,activeCommands,activeBuilds,activeWrites}`；他人窗口409且不得作为“已取得租约”继续。leaseId随机不可猜，exit只接受自己的leaseId；read/status/cancel/receipt收敛不被栅栏挡住。排空60秒，超时非零并报告在途业务ID，不能强杀已收费操作以假装排空。broker重启在维护窗口中保持fail-closed，靠持久journal恢复/显式持有者退出。

backup manifest NEW format `y1.hypit-backup@2`：format、createdAt、sourceCommit、engineDigest、pgDumpSha256、dataTarball、dataSha256、roots（relativePath/role）、files（path/sha256/sizeBytes）、omitted（path/reason）、complete=true；成功所有步骤才写complete。旧@1恢复需先完整预检且不能假定根目录相同。归档权限0600，凭据保持原加密存储，不写日志。恢复到显式空目标映射，不信tar顶层目录名；PG查询用V91/V92真实字段，任何SQL错误/缺文件/错hash非零。--files-only是诊断PARTIAL，不得通过正式restore门禁。

## 7. 数据模型与迁移

### 7.1 既有结构与 NEW 会话表

复用V91的project/revision/asset/reference/command/job/action/event/plan/pricing/grant/build/output/execution/variant。revision真实列是number/snapshot_handle，不能继续使用snapshot_dir。job已有version/lease_owner/lease_until，action已有prepared/dispatched/unknown与operation_id，足以实现幂等与fencing；不另造第二套任务表。

intelligence服务新增唯一迁移 `V92__hypit_fix2_sessions.sql`（C19拥有）；规划时最大V91，若实施前版本被108等占用，登记一个不重号的文件修订并保留旧迁移，不能覆盖。目标DDL：

```sql
CREATE TABLE IF NOT EXISTS hypit_session (
    id text PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES hypit_project(id),
    account_id text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('studio','preview')),
    run_file text NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    read_only boolean NOT NULL,
    state text NOT NULL CHECK (state IN ('starting','active','failed','closed','expired','revoked')),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    ticket_nonce_hash char(64),
    ticket_expires_at timestamptz,
    ticket_redeemed_at timestamptz,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_hypit_session_owner_project
    ON hypit_session(account_id, project_id, state);
CREATE INDEX IF NOT EXISTS idx_hypit_session_expiry
    ON hypit_session(state, expires_at);
```

新票替换当前nonceHash，旧未使用票立即失效；已核销浏览器路径cookie仍受access验证，新票独立核销。持久nonce单槽选择是本书明确策略，不支持同一session多张未核销票并行使用；两并发重开以最后一张票有效，客户端旧响应generation作废。

### 7.2 字段与唯一性

owner/account来自站内身份；项目id稳定且不从包继承；revision单调正整数，文件manifestHash与PG对应。requestId按account+kind唯一；同payload返回原回执、异payload409。action.slot/inputHash/operationId持久，先prepared再dispatch；SSE主键(job,sequence)单调。钱沿既有numeric(20,6)，unknown null不换0。

### 7.3 兼容和迁移

V92仅加表/索引，无全表回填与破坏性DDL；旧应用可忽略新表，回滚停新会话后回旧应用，不DROP表。旧会话只有内存，不伪造迁移：重启后重新创建。旧revision0按C05显式bootstrap；新导入不沿用旧孤儿目录，历史孤儿只诊断，不擅自删除或认领。

### 7.4 事务、文件与恢复

- PG intent/command/project/job在短事务内登记；broker文件发布为独立可恢复操作，receipt稳定，PG最后收敛。不得宣称跨服务同数据库事务自动回滚磁盘。
- leaseOwner每次claim生成，renew/CAS防旧worker写；action原子预留后才能副作用。unknown必须查原操作，不能换ID重试。
- workspace/feedback在项目级锁内CAS+staging+原子rename；跨进程使用已有文件锁/journal机制，不能只靠某HTTP对象实例内mutex。
- session/store/上传缓存按account/project归属，撤权/删除/过期均有清理；TTL清理频率≤60秒，失败重试1/2/4/8/15秒最多5次后留错误待运维，不无限吞错。
- export/失败上传staging24小时；活跃资源、被结果引用资产和共享media不误删。新增session资源/消费者登记到lifecycle两个registry并有真实处理代码。

### 7.5 迁移与回滚证据

真实PostgreSQL验证：空库从全迁移初始化、有V91数据升级、V92 DDL重复执行、模拟中途DDL失败再恢复、旧应用读取新schema、新应用读取旧业务数据。记录schema历史与SQL退出码。只用本任务标记的隔离数据库；禁止对当前共享开发/生产库尝试DROP或clean。缺必需PG不能用H2替代通过。

## 8. UI 实现规格

### 8.1 归属和设备

AI创作端 `/video-clone` 与工程深链，共享视图仍单份。根DESIGN暗/亮双主题。断点FACT：移动<768、平板768–1023、桌面≥1024、宽屏≥1440；验收390×844、834×1112、1440×1000。

### 8.2 结构

保持工程列表→工程标题/状态/主操作→参考素材/方案/生成编辑/审片导出四阶段；运行与费用信息贴近动作，Studio/Preview作为该工程的工作区域。新增专用ExecutionGrantDialog和ProjectPackageDialog复用GlModal，不新增装饰导航或第二套设计系统。

### 8.3 组件行为

| 组件 | 动作→结果 | 失败/空/慢 |
|---|---|---|
| ProjectList/NewProjectDialog | 创建→新URL，分页→追加，深链独立GET | 空态给创建，失败可重试且保留title |
| RuntimePanel/顶层 | 登录/启用/可用性→允许业务动作 | 缺依赖具体原因，不仅404 |
| ReferencePanel | 文件/URL/交接→真实asset；分析→job | 上传错误保留选择，PARTIAL说明未覆盖段 |
| ClonePlanPanel | 重新方案→author；执行→版本/费用预检 | 缺材料定位步骤，不能点击即切页签 |
| Material/AgentActivity | 提交/重试/取消→对应API与job | 等终态，任务离页不中断服务端工作 |
| SourcePanel | 文件读取/编辑/保存→CAS和新hash | 冲突保留草稿，在途新输入仍dirty |
| Studio/Preview | 会话真实加载/保存/播放/关闭 | 失效可重开，空URL不得iframe，关闭不误杀Build |
| ReviewPanel | 评论批次原子；revise→review job | 未定位需澄清，修改失败不resolve |
| Results/Variants | 输出归档/下载；逐项授权/重试/取消 | 部分成功保留，失败项单独处理 |
| Packages | 导出→可下载；上传→新工程 | 真实进度/状态，响应超时查询同job |

### 8.4 交互细节

每个动作在途禁重复但不锁无关区域；加载保留按钮宽度。表单点击与Enter均校验，文本编辑器Enter正常换行不提交。弹窗Esc/关闭返回焦点，未保存/上传中关闭要说明丢弃本地输入或停止上传，不声称服务端job撤销。错误靠近字段/面板，不能只toast；账号切换清私有状态。切工程有dirty先保存/放弃/取消，取消不改URL。

### 8.5 响应式

390px单列与16px页面token边距，工程列表折叠可恢复；834px主区优先、详情展开；1440px工作区双栏。源码/表格内部滚动，不整页横向滚动；长文件路径可换行或局部滚动，完整值可访问。120字历史显示标题、长错误、授权表与长弹窗均不隐藏主动作。

### 8.6 token与复用

| 用途 | 已核验变量/共享类 | 暗/亮规范值 | 本次边界 |
|---|---|---|---|
| 品牌/按钮 | --color-accent / .gl-btn-primary | #533afd / #533afd | 不改品牌 |
| 页面/表面 | --color-bg / --color-surface | #0d0f18/#141825；#f6f9fc/#ffffff | 只引用现有配对 |
| 文字 | --color-text / --color-text-muted | #f0f2f8/#9aa7be；#0d253d/#5f6f84 | 不硬编码fallback |
| 警告 | --color-warning / --surface-warning | #f59e0b/#302713；#8b5709/#fff4de | 改动组件内旧hex换token |
| 间距/圆角 | --space-md/--space-lg、--radius-md/lg/xl | 16/24、8/12/16px | 使用已定义值 |
| 字体/焦点 | Inter/Space Grotesk；--focus-color/width/offset | 既有self-host；2px/2px | 不引第三字体/CDN |
| 基础组件 | GlModal/EmptyState、.gl-field/.gl-zone/.badge | 根设计规范 | 不在scoped重造 |

颜色需新增映射时先同卡补style.css两主题，使用DESIGN已有token；本书未授权新增设计规范外色值。样式变化影响共享入口时扩回归范围，不复制ops组件。

### 8.7 无障碍

原生button/input/label；字段错误aria-describedby，提交/错误aria-live；危险动作说明对象，状态有文字；弹窗焦点限制/初始焦点/Esc/归还；iframe有可读title。触控目标≥44×44，普通文字对比≥4.5:1、必要控件边界≥3:1；减少动态效果设置保留。

### 8.8 截图矩阵

证据统一 `test-artifacts/task-107/fix2/C36/screenshots/ai-video-clone-{theme}-{viewport}-{state}.png`；文件名中的theme/viewport/state由实际值替换，不允许只存在文件就通过。

| 状态集合 | 视口/主题 | 数据与断言 |
|---|---|---|
| 四阶段有效工程、Studio、Preview、包导入导出 | 390/834/1440指定视口 × dark/light | 已登录合成owner，真页面可见、布局/层级/主要动作 |
| disabled/首次empty/404工程/接口error/loading/submitting/conflict | 390和1440 × dark/light | 状态专项可定向拦截，但需证明命中目标请求，不替代成功E2E |
| 授权弹窗/长标题/长路径/键盘focus | 三视口 × dark/light | 宽度/焦点/错误关联；无整页横向滚动 |

每张实际查看并在对话给路径、主题、浏览器、视口、状态与结论。共享token变更补用户/AI/ops受影响挂载点。

## 9. 全局约束与写入范围

### 9.1 精确白名单

下表是唯一生产/测试/配置/文档写入集合。W编号由首次出现顺序固定；卡引用W并限制本卡符号/目的。现存文件修改，新路径明确NEW；不允许把生成目录当生产代码白名单。全部卡默认串行，共用server/dispatcher/Controller/hypit-api/contract的修改必须继承前卡成果。

| W | 精确路径 | 权限/操作 | 符号/职责与完成标准 | 写入责任卡 |
| --- | --- | --- | --- | --- |
| W001 | `contracts/hypit-api.v1.json` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-01、C107F2-11、C107F2-12、C107F2-13、C107F2-19、C107F2-20、C107F2-21、C107F2-22、C107F2-26、C107F2-30、C107F2-31 |
| W002 | `contracts/hypit-coverage.v1.json` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-01、C107F2-39、C107F2-40 |
| W003 | `scripts/acceptance/verify-107-fix-2.sh` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-01、C107F2-08、C107F2-34、C107F2-38、C107F2-39 |
| W004 | `scripts/acceptance/fixtures/hypit-fix2-fixtures.mjs` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-01、C107F2-08 |
| W005 | `tests/fixtures/hypit-fix2/contract-samples.json` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-01 |
| W006 | `tests/fixtures/hypit-fix2/README.md` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-01 |
| W007 | `tests/deployment/hypit-fix2-spec.contract.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-01、C107F2-40 |
| W008 | `tests/deployment/hypit-fix2-c01.contract.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-01 |
| W009 | `docker-compose.yml` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-02、C107F2-19、C107F2-20、C107F2-21、C107F2-22、C107F2-26、C107F2-30、C107F2-31 |
| W010 | `docker-compose.production.yml` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-02 |
| W011 | `deploy/hypit/compose.production.yml` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-02、C107F2-03、C107F2-04、C107F2-19、C107F2-20、C107F2-21、C107F2-22、C107F2-26、C107F2-30、C107F2-31、C107F2-33 |
| W012 | `deploy/hypit/compose.test.yml` | 写入/修改 | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-02、C107F2-04、C107F2-08、C107F2-19、C107F2-20、C107F2-21、C107F2-22、C107F2-26、C107F2-30、C107F2-31、C107F2-37 |
| W013 | `deploy/hypit/compose.full.yml` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-02、C107F2-33 |
| W014 | `deploy/digital-human/compose.production.yml` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-02 |
| W015 | `deploy/hypit/.env.example` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-02、C107F2-03 |
| W016 | `.env.docker.example` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-02 |
| W017 | `scripts/acceptance/hypit-compose.sh` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-02 |
| W018 | `deploy/hypit/README.md` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-02、C107F2-33、C107F2-34、C107F2-40 |
| W019 | `tests/deployment/hypit-fix2-c02.contract.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-02 |
| W020 | `.env.example` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-02 |
| W021 | `deploy/hypit/Dockerfile.backend` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-03 |
| W022 | `deploy/hypit/Dockerfile.runner` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-03、C107F2-04 |
| W023 | `deploy/hypit/compose.runner.yml` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-03、C107F2-04 |
| W024 | `platform-hypit/backend/src/config.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-03、C107F2-09 |
| W025 | `platform-hypit/backend/src/server.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-03、C107F2-04、C107F2-06、C107F2-09、C107F2-19、C107F2-20、C107F2-22、C107F2-30、C107F2-31、C107F2-32 |
| W026 | `platform-hypit/backend/src/commands/dispatcher.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-03、C107F2-05、C107F2-06、C107F2-07、C107F2-19、C107F2-22、C107F2-24、C107F2-28、C107F2-29、C107F2-32、C107F2-35 |
| W027 | `platform-hypit/templates/catalog.json` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-03 |
| W028 | `platform-hypit/backend/tests/agent-integration/fix2-c03.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-03 |
| W029 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/config/HypitProperties.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-03、C107F2-07、C107F2-09、C107F2-19、C107F2-30 |
| W030 | `platform-java/services/intelligence-service/src/main/resources/application.yml` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-03、C107F2-07、C107F2-19、C107F2-30 |
| W031 | `platform-hypit/backend/src/runner/supervisor.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-04 |
| W032 | `platform-hypit/backend/src/runner/server.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-04、C107F2-19、C107F2-22 |
| W033 | `platform-hypit/backend/src/runner/daemon.mjs` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-04 |
| W034 | `platform-hypit/backend/src/runner/client.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-04 |
| W035 | `platform-hypit/backend/src/runner/protocol.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-04、C107F2-19、C107F2-22 |
| W036 | `platform-hypit/backend/src/runner/guard.mjs` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-04 |
| W037 | `platform-hypit/backend/tests/agent-integration/fix2-c04.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-04 |
| W038 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/project/HypitProjectService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-05、C107F2-11、C107F2-29、C107F2-31、C107F2-35 |
| W039 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/project/HypitProjectRepository.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-05、C107F2-11、C107F2-29、C107F2-35 |
| W040 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/project/HypitRevisionRepository.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-05、C107F2-29 |
| W041 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/template/HypitTemplateService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-05 |
| W042 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitAgentJobService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-05、C107F2-15 |
| W043 | `platform-hypit/backend/src/workspace/provision.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-05 |
| W044 | `platform-hypit/fixtures/blank/main.svml` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-05 |
| W045 | `platform-hypit/fixtures/blank/main.svrun` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-05 |
| W046 | `platform-hypit/fixtures/blank/style.svs` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-05 |
| W047 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C05IT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-05 |
| W048 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/build/HypitPlanService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-06、C107F2-07 |
| W049 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/build/HypitPlanRepository.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-06 |
| W050 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/build/HypitBuildService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-06、C107F2-07 |
| W051 | `platform-hypit/backend/src/engine/compile-adapter.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-06、C107F2-19 |
| W052 | `platform-hypit/backend/src/engine/planning.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-06 |
| W053 | `platform-hypit/backend/src/workspace/revisions.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-06、C107F2-28 |
| W054 | `platform-hypit/backend/src/engine/runtime-adapter.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-06、C107F2-07、C107F2-22 |
| W055 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C06IT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-06 |
| W056 | `platform-hypit/backend/src/engine/engine-port.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-06 |
| W057 | `platform-hypit/backend/src/providers/authorization.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-07 |
| W058 | `platform-hypit/backend/src/providers/activation-hooks.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-07 |
| W059 | `platform-hypit/backend/src/engine/hypit-bootstrap.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-07 |
| W060 | `platform-hypit/patches/0004-provider-authorization.patch` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-07 |
| W061 | `platform-hypit/patches/manifest.json` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-07、C107F2-19 |
| W062 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/execution/HypitExternalExecutionBridge.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-07 |
| W063 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/execution/HypitExecutionRepository.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-07 |
| W064 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/execution/HypitGrantService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-07 |
| W065 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C07IT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-07 |
| W066 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/client/HypitSidecarClient.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-07、C107F2-30、C107F2-31、C107F2-32 |
| W067 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/execution/HypitInternalExecutionController.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-07 |
| W068 | `tests/e2e/hypit-fix2-api-render.spec.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-08 |
| W069 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/api/HypitRuntimeController.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-09 |
| W070 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/runtime/HypitRuntimeService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-09 |
| W071 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/api/HypitDtos.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-09、C107F2-12、C107F2-13 |
| W072 | `src/views/video-clone/VideoCloneWorkbench.vue` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-09、C107F2-11、C107F2-13、C107F2-18、C107F2-20、C107F2-23、C107F2-25、C107F2-27、C107F2-30、C107F2-31、C107F2-36 |
| W073 | `src/views/video-clone/components/RuntimePanel.vue` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-09、C107F2-36 |
| W074 | `src/views/video-clone/composables/useHypitRuntime.ts` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-09 |
| W075 | `src/views/video-clone/composables/hypit-api.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-09、C107F2-10、C107F2-11、C107F2-12、C107F2-13、C107F2-18、C107F2-20、C107F2-23、C107F2-27、C107F2-30、C107F2-31 |
| W076 | `src/types/hypit.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-09、C107F2-10、C107F2-12、C107F2-13、C107F2-23、C107F2-27、C107F2-30、C107F2-31 |
| W077 | `src/views/video-clone/composables/fix2-c09.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-09 |
| W078 | `platform-hypit/backend/src/workspace/transactions.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-10、C107F2-24、C107F2-28 |
| W079 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/project/HypitChangesetService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-10、C107F2-14、C107F2-25 |
| W080 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/api/HypitProjectController.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-10、C107F2-11、C107F2-16、C107F2-26、C107F2-30 |
| W081 | `src/views/video-clone/composables/useHypitSource.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-10、C107F2-11 |
| W082 | `src/views/video-clone/components/SourcePanel.vue` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-10、C107F2-36 |
| W083 | `src/views/video-clone/composables/fix2-c10.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-10 |
| W084 | `src/views/video-clone/useVideoCloneUrlState.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-11、C107F2-13 |
| W085 | `src/views/video-clone/composables/useHypitProjects.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-11 |
| W086 | `src/views/video-clone/composables/useHypitPlan.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-11、C107F2-18 |
| W087 | `src/views/video-clone/composables/useHypitResults.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-11、C107F2-12 |
| W088 | `src/views/video-clone/composables/useHypitReviewFlow.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-11、C107F2-25 |
| W089 | `src/views/video-clone/composables/useHypitVariants.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-11、C107F2-27 |
| W090 | `src/views/video-clone/composables/useHypitProjectScope.ts` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-11、C107F2-35 |
| W091 | `src/views/video-clone/components/ReferencePanel.vue` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-11、C107F2-18、C107F2-31、C107F2-36 |
| W092 | `src/views/video-clone/components/ProjectList.vue` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-11、C107F2-36 |
| W093 | `src/views/video-clone/composables/fix2-c11.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-11 |
| W094 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/api/HypitBuildController.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-12 |
| W095 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/build/HypitResultService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-12 |
| W096 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/build/HypitOutputRepository.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-12 |
| W097 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/asset/HypitArchiveService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-12 |
| W098 | `src/views/video-clone/components/ResultsPanel.vue` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-12、C107F2-36 |
| W099 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C12IT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-12 |
| W100 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/job/HypitJobService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-13 |
| W101 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/job/HypitJobEventRepository.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-13、C107F2-15 |
| W102 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/api/HypitJobController.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-13 |
| W103 | `src/views/video-clone/composables/useHypitJobs.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-13、C107F2-18 |
| W104 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C13IT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-13 |
| W105 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/job/HypitJobRepository.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-13、C107F2-15、C107F2-29、C107F2-35 |
| W106 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitAgentScope.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-14 |
| W107 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitAgentStepService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-14、C107F2-15、C107F2-16、C107F2-17、C107F2-25 |
| W108 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitAgentWorker.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-14、C107F2-15、C107F2-16、C107F2-17、C107F2-25、C107F2-32 |
| W109 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitAgentAction.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-14 |
| W110 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/asset/HypitAssetService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-14、C107F2-16、C107F2-31 |
| W111 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitAgentToolRegistry.java` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-14 |
| W112 | `contracts/hypit-tools.v1.json` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-14 |
| W113 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C14IT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-14 |
| W114 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/job/HypitJobActionRepository.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-15 |
| W115 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/job/HypitCommandRepository.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-15、C107F2-29、C107F2-30 |
| W116 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C15IT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-15 |
| W117 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitReferenceAnalysisService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-16 |
| W118 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitReferenceAnalysis.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-16 |
| W119 | `platform-java/services/intelligence-service/src/main/resources/hypit/prompts/reference-analysis.md` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-16 |
| W120 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C16IT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-16 |
| W121 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitAuthorService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-17 |
| W122 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitClonePlanService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-17 |
| W123 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitClonePlan.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-17 |
| W124 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitMaterialGraph.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-17 |
| W125 | `platform-java/services/intelligence-service/src/main/resources/hypit/prompts/clone-plan.md` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-17 |
| W126 | `platform-java/services/intelligence-service/src/main/resources/hypit/prompts/authoring.md` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-17 |
| W127 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C17IT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-17 |
| W128 | `src/views/video-clone/composables/useHypitWorkflow.ts` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-18 |
| W129 | `src/views/video-clone/components/ClonePlanPanel.vue` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-18、C107F2-36 |
| W130 | `src/views/video-clone/components/MaterialPanel.vue` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-18、C107F2-36 |
| W131 | `src/views/video-clone/components/AgentActivityPanel.vue` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-18、C107F2-36 |
| W132 | `src/views/video-clone/components/ExecutionGrantDialog.vue` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-18、C107F2-27、C107F2-36 |
| W133 | `src/views/video-clone/composables/fix2-c18.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-18 |
| W134 | `platform-hypit/backend/src/studio/launcher.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-19、C107F2-20 |
| W135 | `platform-hypit/backend/src/studio/proxy.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-19 |
| W136 | `platform-hypit/backend/src/studio/mutation-bridge.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-19 |
| W137 | `platform-hypit/backend/src/studio/sessions.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-19、C107F2-20、C107F2-35 |
| W138 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/studio/HypitStudioSessionService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-19、C107F2-20 |
| W139 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/studio/HypitSessionRepository.java` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-19、C107F2-20、C107F2-22、C107F2-35 |
| W140 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/api/HypitStudioController.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-19、C107F2-20、C107F2-22、C107F2-24 |
| W141 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/api/HypitSessionAccessController.java` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-19、C107F2-20 |
| W142 | `platform-java/services/intelligence-service/src/main/resources/db/migration/V92__hypit_fix2_sessions.sql` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-19 |
| W143 | `platform-hypit/patches/0001-studio-base-path.patch` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-19 |
| W144 | `platform-hypit/patches/0002-studio-mutation-bridge.patch` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-19 |
| W145 | `deploy/hypit/nginx.locations.conf` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-19、C107F2-21、C107F2-22、C107F2-30 |
| W146 | `platform-hypit/backend/tests/agent-integration/fix2-c19.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-19 |
| W147 | `platform-java/services/edge-bff/src/main/resources/application.yml` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-19、C107F2-20、C107F2-21、C107F2-22、C107F2-26、C107F2-30、C107F2-31 |
| W148 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/api/HypitExceptionHandler.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-19、C107F2-20 |
| W149 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2SessionMigrationIT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-19 |
| W150 | `platform-hypit/backend/src/studio/url-policy.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-20 |
| W151 | `src/views/video-clone/composables/useHypitStudio.ts` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-20 |
| W152 | `src/views/video-clone/components/StudioPanel.vue` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-20、C107F2-21、C107F2-36 |
| W153 | `platform-hypit/backend/tests/agent-integration/fix2-c20.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-20 |
| W154 | `nginx.conf` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-21、C107F2-30、C107F2-31 |
| W155 | `tests/deployment/hypit-entrypoint.contract.test.ts` | 写入/修改 | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-21、C107F2-39 |
| W156 | `tests/deployment/hypit-fix2-c21.contract.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-21 |
| W157 | `platform-hypit/backend/src/preview/sessions.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-22、C107F2-35 |
| W158 | `platform-hypit/backend/src/preview/bridge.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-22、C107F2-23 |
| W159 | `platform-hypit/backend/src/preview/server.ts` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-22 |
| W160 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/preview/HypitPreviewService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-22 |
| W161 | `platform-hypit/backend/tests/agent-integration/fix2-c22.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-22 |
| W162 | `src/views/video-clone/composables/useHypitPreview.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-23 |
| W163 | `src/views/video-clone/components/PreviewPanel.vue` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-23、C107F2-36 |
| W164 | `src/views/video-clone/composables/fix2-c23.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-23 |
| W165 | `platform-hypit/backend/src/studio/feedback.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-24、C107F2-25 |
| W166 | `platform-hypit/backend/tests/agent-integration/fix2-c24.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-24 |
| W167 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/agent/HypitReviewService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-25 |
| W168 | `src/views/video-clone/components/ReviewPanel.vue` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-25、C107F2-36 |
| W169 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C25IT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-25 |
| W170 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/variant/HypitVariantWorker.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-26、C107F2-32 |
| W171 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/variant/HypitVariantService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-26 |
| W172 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/variant/HypitVariantRepository.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-26 |
| W173 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C26IT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-26 |
| W174 | `src/views/video-clone/components/VariantsPanel.vue` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-27、C107F2-36 |
| W175 | `src/views/video-clone/composables/fix2-c27.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-27 |
| W176 | `platform-hypit/backend/src/project-package/import.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-28、C107F2-29、C107F2-30 |
| W177 | `platform-hypit/backend/src/project-package/export.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-28、C107F2-30 |
| W178 | `platform-hypit/backend/src/project-package/manifest.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-28 |
| W179 | `platform-hypit/backend/src/project-package/binary-staging.ts` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-28 |
| W180 | `platform-hypit/backend/tests/agent-integration/fix2-c28.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-28 |
| W181 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/template/HypitProjectPackageService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-29、C107F2-30 |
| W182 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/api/HypitKnowledgeController.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-29、C107F2-30 |
| W183 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C29IT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-29 |
| W184 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/api/HypitPackageTransferController.java` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-30 |
| W185 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/template/HypitPackageTransferService.java` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-30、C107F2-35 |
| W186 | `platform-hypit/backend/src/project-package/transfer.ts` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-30、C107F2-35 |
| W187 | `src/views/video-clone/composables/useHypitPackages.ts` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-30 |
| W188 | `src/views/video-clone/components/ProjectHeader.vue` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-30、C107F2-36 |
| W189 | `src/views/video-clone/components/ProjectPackageDialog.vue` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-30、C107F2-36 |
| W190 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C30IT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-30 |
| W191 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/asset/HypitAssetRepository.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-31 |
| W192 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/api/HypitAssetController.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-31 |
| W193 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/hypit/asset/HypitAssetUploadService.java` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-31 |
| W194 | `platform-hypit/backend/src/resources/handles.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-31 |
| W195 | `src/views/video-clone/composables/useHypitAssets.ts` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-31 |
| W196 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C31IT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-31 |
| W197 | `platform-hypit/backend/src/commands/store.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-32 |
| W198 | `platform-hypit/backend/src/runtime/observer.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-32 |
| W199 | `platform-hypit/backend/src/runtime/capacity.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-32 |
| W200 | `deploy/hypit/backup.sh` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-32、C107F2-33 |
| W201 | `platform-hypit/backend/tests/agent-integration/fix2-c32.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-32 |
| W202 | `platform-hypit/backend/src/commands/events.ts` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-32 |
| W203 | `scripts/acceptance/hypit-backup-fixture.sh` | 写入/NEW | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-33、C107F2-34、C107F2-38 |
| W204 | `tests/deployment/hypit-fix2-c33.contract.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-33 |
| W205 | `deploy/hypit/restore.sh` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-34 |
| W206 | `tests/deployment/hypit-fix2-c34.contract.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-34 |
| W207 | `tests/contracts/resource-lifecycle.registry.json` | 写入/修改 | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-35 |
| W208 | `tests/contracts/event-consumers.registry.json` | 写入/修改 | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-35 |
| W209 | `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C35IT.java` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-35 |
| W210 | `platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/compliance/PersonalDataErasureService.java` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-35 |
| W211 | `src/views/video-clone/components/NewProjectDialog.vue` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-36 |
| W212 | `src/style.css` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-36 |
| W213 | `tests/e2e/hypit-fix2-c36.spec.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-36 |
| W214 | `tests/e2e/hypit-clone.spec.ts` | 写入/修改 | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-37 |
| W215 | `tests/e2e/hypit-studio.spec.ts` | 写入/修改 | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-37 |
| W216 | `tests/e2e/hypit-entrypoints.spec.ts` | 写入/修改 | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-37 |
| W217 | `tests/e2e/hypit-fix2-journey.spec.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-37 |
| W218 | `tests/e2e/fixtures/hypit-fix2.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-37、C107F2-38 |
| W219 | `scripts/acceptance/ci-e2e-107.sh` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-37、C107F2-39 |
| W220 | `scripts/ci-e2e.sh` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-37 |
| W221 | `tests/e2e/hypit-recovery.spec.ts` | 写入/修改 | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-38 |
| W222 | `tests/e2e/hypit-fix2-recovery.spec.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-38 |
| W223 | `scripts/acceptance/verify-107-full.sh` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-39 |
| W224 | `tests/deployment/hypit-fix2-gates.contract.test.ts` | 写入/NEW | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-39 |
| W225 | `.github/workflows/ci.yml` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-39 |
| W226 | `tests/deployment/hypit-compose.contract.test.ts` | 写入/修改 | 本书对应卡的TC与契约断言，禁止降低既有门禁 | C107F2-39 |
| W227 | `docs/任务书/草场任务书-107-fix-2-Hypit全链路缺陷修复与真实交付验收.md` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-40 |
| W228 | `docs/任务书/README.md` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-40 |
| W229 | `docs/草场开发进度与续接指南.md` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-40 |
| W230 | `docs/status.yaml` | 写入/修改 | 仅卡内步骤对应符号/配置/条款；接入调用方并通过本卡TC | C107F2-40 |


**只读与黑名单**：`platform-hypit/upstream/`、upstream-manifest.json、旧Flyway迁移、真实.env/凭据、108任务书及无关业务代码、第三方依赖清单/lockfile均不得修改。AGENTS/DESIGN/架构目录说明、当前任务模板是只读依据。没有W的文件只有只读权限；运行工具产生的缓存不等于允许人工编辑。

**生成物许可**：`test-artifacts/task-107/fix2/`（卡目录、日志、截图、机器结果、合成媒体）、工具既有 `platform-java/**/build/`、`coverage/`、`playwright-report/`、`test-artifacts/playwright/`、`platform-hypit/.generated/`；只保留本任务新产物，不覆盖审计历史证据。临时辅助脚本放scripts/local；正式验收脚本必须入W表。测试数据不入生产目录。

### 9.2 仓库铁律

- R-UI：根规范token、字体、亮暗配对、GlModal/EmptyState复用、实际截图；ops差异只变量作用域。
- R-ENTRY：保留三HTML入口、主题防闪烁CSP绑定、跨应用登录、固定Edge上游和默认禁用；不得直接把浏览器业务请求改发broker。
- R-JAVA：Java25/Boot4、响应式生命周期、服务域边界；既有Hypit Node broker是已登记的原生引擎承载，不新增旁路公共TS后端。
- R-DATA：真实PG、旧迁移不可改、短事务/幂等/CAS；跨文件与PG用可恢复意图，不伪跨服务事务。
- R-AI：沿平台既有执行/预算/ai_run/安全/SSRF防线；不直接扣退积分、不新增收费价目、不将外部模型fixture称真实效果。
- R-QUALITY：已有lint、体积、覆盖率、Java check/JaCoCo门槛保持。前端变更行覆盖目标≥80%，不能删难测路径降分母。
- R-LAYER：URL进use*UrlState、业务流进域composable、大块子组件、视图仅装配；不得提高.vue800行硬顶，四豁免只减不增，composable500行WARN也须检查拆分。
- R-LIFECYCLE：session、导入staging、job、资产均有属主/撤权/清理与实际消费者；registry不替代运行验证。
- R-DIR：测试继续tests/或已有src近源；正式脚本scripts/acceptance，产物test-artifacts，文档固定目录。
- R-SAFE：保留原改动，隔离资源先验标签/库名，秘密不进日志截图，生产发布/真实收费另有明确授权。

### 9.3 可重建验证环境

| 项 | 本书计划值及校验 |
|---|---|
| cwd/shell | 根目录下bash；Java命令cwd=platform-java；不假设执行者机器绝对路径与作者相同 |
| 前端 | 锁定npm ci；当前Node22可以跑前端既有门禁 |
| broker测试 | 必须Node24.14.1；V02用npx --package=node@24.14.1启动精确runtime，临时下载不改依赖清单/锁 |
| Java | source scripts/lib/java-runtime.sh后ensure_java_runtime 25；java与Gradle wrapper实际版本留证 |
| Docker | 项目名y1-hypit-fix2-e2e，标签task=107-fix-2；新命名卷/网络/数据库，禁止复用现有y-1卷 |
| 入口 | 复用隔离ci-e2e的BASE_URL/OPS_BASE_URL/AI_BASE_URL，默认18080/18081/18082；已占用则预检非零，不杀占用进程；包装脚本允许显式空闲端口覆盖并记录 |
| 身份 | 合成owner A/B、operator各一；调用既有auth seed在验证过的隔离DB生成；账号密码运行期注入、不写进任务书/日志 |
| 模型fixture | 固定seed=10702；只替外部模型/Provider最后一跳，仍通过真实平台冻结执行与授权适配；fixture host只在隔离配置放行 |
| 媒体 | 本地生成3秒移动元素片、12秒三段参考、有声/无声、PNG/字体等二进制；记录ffprobe/hash，不下载真实用户素材 |
| 清理 | 先核对project label，再停止本任务容器；只可删除本次创建且无其他使用者的卷；主栈与108进程不动 |

C01定义fixture生成入口，C02接隔离配置，C08首次API成片，C37才验收完整UI。环境故障先在本地正常排查/安装/下载，确实缺必需依赖再NOT_RUN并阻断对应出口，不退化为全部mock。

### 9.4 安全、性能与兼容阈值

| 对象 | 冻结阈值/可断言条件 | 用例归属 |
|---|---|---|
| 数据保护 | owner越权零可见字节；失败文本/反馈批次零部分写；包hash100%一致 | C10/24/28/29/35 |
| runner | 容量1；握手2秒；单操作120秒；SIGTERM宽限10秒后受控终止；不改已接受Provider事实 | C04 |
| Agent | lease90秒/renew15秒；单planner60秒；瞬时重试2次；40动作/轮、120累计 | C15 |
| SSE | poll1秒、heartbeat15秒、batch100；10秒空流查询≤11；终态关闭 | C13 |
| Session | ticket60秒、内部assertion30秒、absolute sessionTTL3600秒、清理≤60秒 | C20/22/35 |
| 上传/导入 | 素材256MiB、包4GiB/20000entry；流buffer≤1MiB、并发≤2；超限立即终止并清staging | C28/30/31 |
| 维护 | 先栅栏后排空，60秒超时非零；无在途unsafe写才备份 | C32 |
| UI | 3视口两主题，keyboard可达、文字4.5:1/控件3:1、touch44px | C36 |
| 本地成片 | 3秒±1帧，非空非全黑帧、指定尺寸、字幕/时序锚点正确 | C08/37 |

所有超时是计划目标而非规划阶段测得性能。涉及超过正常期限的原生Build持续观察不等同HTTP请求长占用；不把120秒runner操作上限误用为视频产品最大时长。

### 9.5 约束适用矩阵

| 约束 | 卡 | 落实点 |
|---|---|---|
| R-UI | 09–13、18、20–23、25、27、30–31、36–37 | §8及双主题/键盘矩阵 |
| R-ENTRY | 02、09、19–23、26、30–31、37 | flags、CSP、Cookie、三入口 |
| R-JAVA | 05–07、09–22、25–31、35 | 正式service/worker/DTO与事务 |
| R-DATA | 05–07、10、15、19、24、28–35、38 | PG/文件/幂等/CAS/迁移 |
| R-AI | 07、14–18、25–27、37–38 | 执行授权/模型安全/费用凭证 |
| R-QUALITY | 全部，39主责 | 按层实际命令与零漏跑 |
| R-LAYER | 所有前端卡 | 业务composable，视图只装配 |
| R-LIFECYCLE | 20/22/29–35 | 新会话/临时包/资产注销与恢复 |
| R-DIR/R-SAFE | 全部 | W白名单、隔离数据、脱敏、原改动保留 |

## 10. 开发计划与任务总表

下表依赖只表示需要前卡具体交付物；默认仍顺序单执行者运行，以保护共享dispatcher/Controller/契约写入。所有初始状态NOT_STARTED；无默认子代理/多人并行要求。

| 卡/需求 | 标题 | 审计F | 依赖交付卡 | 主要W | AC/TC | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| C107F2-01 / REQ-F2-01 | 固化审计反例、跨层契约样本与分层验证入口 | F35 |  | W001、W002、W003、W004；完整见卡 | AC-F2-01-01～04 / TC-F2-01-01～04 | VERIFIED（2026-09-28 补验 exit0：18/18、tcFound=4；证据 test-artifacts/task-107/fix2/C107F2-01/results.json） |
| C107F2-02 / REQ-F2-02 | 统一 Compose 组合与启停路由，消除部署覆盖导致的 404 | F01 | C107F2-01 | W009、W010、W011、W012；完整见卡 | AC-F2-02-01～04 / TC-F2-02-01～04 | VERIFIED（2026-09-27 exit0：21/21；证据 C107F2-02/results.json） |
| C107F2-03 / REQ-F2-03 | 修正镜像目录、模板资源与运行期配置读取 | F02,F03,F04 | C107F2-02 | W021、W022、W011、W023；完整见卡 | AC-F2-03-01～04 / TC-F2-03-01～04 | VERIFIED（2026-09-27 exit0：11/11；证据 C107F2-03/results.json） |
| C107F2-04 / REQ-F2-04 | 接通独立无网络 runner，消除 broker 内作者代码执行 | F02 | C107F2-03 | W031、W032、W033、W034；完整见卡 | AC-F2-04-01～04 / TC-F2-04-01～04 | VERIFIED（2026-09-27 exit0：11/11；证据 C107F2-04/results.json） |
| C107F2-05 / REQ-F2-05 | 按模板建工程并打通空工程首版初始化 | F29 | C107F2-03、C107F2-04 | W038、W039、W040、W041；完整见卡 | AC-F2-05-01～04 / TC-F2-05-01～04 | VERIFIED（2026-09-28 补验 exit0：4/4、tcFound=4；证据 C107F2-05/results.json） |
| C107F2-06 / REQ-F2-06 | 统一源码根并冻结计划、价格、Profile 和构建快照 | F25,F26 | C107F2-04、C107F2-05 | W048、W049、W050、W025；完整见卡 | AC-F2-06-01～04 / TC-F2-06-01～04 | VERIFIED（2026-09-27 exit0：4/4；证据 C107F2-06/results.json） |
| C107F2-07 / REQ-F2-07 | 安装真实 Provider 授权桥并锁定预算、范围与幂等 | F27 | C107F2-06 | W057、W058、W054、W059；完整见卡 | AC-F2-07-01～04 / TC-F2-07-01～04 | VERIFIED（2026-09-28 补验 exit0：4/4、tcFound=4；证据 C107F2-07/results.json） |
| C107F2-08 / REQ-F2-08 | 验收第一条真实 Docker 原生成片纵向链路 | F02,F03,F25,F26,F27,F35 | C107F2-02、C107F2-03、C107F2-04、C107F2-05、C107F2-06、C107F2-07 | W003、W004、W012、W068；完整见卡 | AC-F2-08-01～04 / TC-F2-08-01～04 | VERIFIED（2026-09-28 verify 4/4 exit0；渲染链五处根因修复见会话记录） |
| C107F2-09 / REQ-F2-09 | 真实 readiness 与工作区顶层登录、禁用、不可用状态 | F05,F15 | C107F2-08 | W025、W024、W069、W070；完整见卡 | AC-F2-09-01～04 / TC-F2-09-01～04 | VERIFIED（2026-09-28 verify 4/4 exit0；六态 UI 截图 test-artifacts/task-107/fix2/C09/screenshots/；VideoCloneWorkbench.test.ts 两用例锚点随 F05 行为归属迁移，§13.3 登记见 B.2） |
| C107F2-10 / REQ-F2-10 | 统一文件 hash 契约与版本化保存，保护在途输入 | F09 | C107F2-05、C107F2-06 | W078、W079、W080、W076；完整见卡 | AC-F2-10-01～04 / TC-F2-10-01～04 | VERIFIED（2026-09-28 verify 4/4 exit0；实锄 F09 真身=edit debounce 迟到回写踩掉 saved/conflict 终态，已修并加 900ms 回归断言；截图 test-artifacts/task-107/fix2/C10/screenshots/ 4 张+双主题冲突文案真值；useHypitSource.test.ts mock 迁正式契约，§13.3 登记见 B.2） |
| C107F2-11 / REQ-F2-11 | 工程深链、分页、创建导航和跨项目请求世代隔离 | F07,F08 | C107F2-09、C107F2-10 | W072、W084、W085、W081；完整见卡 | AC-F2-11-01～04 / TC-F2-11-01～04 | VERIFIED（2026-09-28 verify exit0，vitest 10/10 含 fix2-c11 4 TC+hypit-api 回归+gradle 编译；实锄 F08 真身=onBeforeRouteLeave 不拦同路由参数变化，同一守卫补挂 onBeforeRouteUpdate；9 张 UI 截图 test-artifacts/task-107/fix2/C11/screenshots/ 三态（分页/深链/404/离开确认）实看通过；hypit-api.test.ts AbortSignal 用例随 listProjects 增 cursor 首参迁移，§13.3 登记见 B.2 1.0.3） |
| C107F2-12 / REQ-F2-12 | 统一 Build/Outputs DTO 与真实归档、结果消费 | F11,F12 | C107F2-08、C107F2-10 | W094、W071、W095、W096；完整见卡 | AC-F2-12-01～04 / TC-F2-12-01～04 | VERIFIED（2026-09-28 verify exit0：HypitFix2C12IT 4/4 + gradle 编译；vitest 回归 video-clone 全域 11 文件全绿 + vue-tsc 干净。items/outputs 同内容别名、未知 size/duration=null 不冒充、归档 POST /builds/{id}/archive outputNames 载荷+claim CAS 幂等同 mediaId（并发 TC 实证）；UI ResultsPanel 四态×亮暗 8 组合全部实看通过（test-artifacts/task-107/fix2/C12/screenshots/，整页 8 张+元素级 view-* 7 张；CDN 回读旧图坑以页内文本断言+元素级新名重拍双证）；契约 3.3.0 ArchiveResult。HypitContractTest 锚点随 buildController 增 outputIndex 参数迁移，§13.3 见 B.2 1.0.4） |
| C107F2-13 / REQ-F2-13 | 统一 SSE 事件协议、增量游标与工作区恢复 | F24,F06 | C107F2-08、C107F2-11 | W100、W101、W102、W071；完整见卡 | AC-F2-13-01～04 / TC-F2-13-01～04 | VERIFIED（2026-09-28 verify exit0：HypitFix2C13IT 4/4（真 PG+真 HTTP SSE）+useHypitJobs.test.ts 7/7 +gradle 编译；vitest video-clone 全域 11 文件 47 用例全绿+vue-tsc 干净。后端重写 tailing：完整 envelope（id={jobUuid}:{sequence}）、reset 头帧带权威 job+latestSequence、1s 增量批≤100（TC-02 实证 10 秒≤11 次查询零重发）、15s 心跳无 id、terminal 即停；resolveCursor 兼容旧 evt- 前缀、跨 job/超历史一律 reset 不泄漏（TC-04）。前端 fetch 流式解析（多行/半包）、白名单 named events、Last-Event-ID 续接重复 sequence 丢弃、1/2/4/8/15s 退避 5 次转手动重连横幅、401 停止重连；W072 接线 F24 真身=watchJob 从未被调用→URL ?job= 恢复（GET 权威快照先行）+envelope→面板适配+重连/登录横幅；契约 3.4.0。六态元素级截图 test-artifacts/task-107/fix2/C13/recap/ 逐字转录实看通过（教训：视口截图 Agent 面板在折叠线下全被列表区占据，且 analyze_image 会顺着提示词幻觉「通过」——先逐字转录再判定才可信；CDN 上传槽钉死换 webp/新名破）。useHypitJobs.test.ts 由 EventSource 替身迁为 fetch 流替身，§13.3 见 B.2 1.0.5。创建侧 watchJob 接线点随 C18 生成流落地（当前工作台无创建 job 调用方），恢复侧全通） |
| C107F2-14 / REQ-F2-14 | 落地 Agent 工具分发、逐步执行与真实成功判定 | F21 | C107F2-06、C107F2-07、C107F2-13 | W106、W107、W108、W109；完整见卡 | AC-F2-14-01～04 / TC-F2-14-01～04 | VERIFIED（2026-09-28 verify exit0：HypitFix2C14IT 4/4 + HypitAgentIT 回归 2/2（executed=6 passed=6 tcFound=4）；契约 agentTools==注册表 visibleTools、逐工具真实 handler（knowledge 真索引/build 记账提交+就地取消/归档真链/changeset create+apply/dispatcher 命令链）、未登记 hypit_unsupported_action 结构化非 501、number 类型保真（字符串化拒+持久 isNumber）；实锄 Worker judge 真缺陷=历史失败观察 allMatch 永久阻挠达标致 replan 烧尽预算，修为新式 job 只看 goalMet（RULE-08 目标验收）；TC-03 实证第二轮 planner 输入含第一轮动作行 id+hypit_not_found、43 条截 40；HypitAgentIT 越权语义迁移 B.2 1.0.6） |
| C107F2-15 / REQ-F2-15 | 修复 Agent 租约、操作幂等、取消及 resume | F23 | C107F2-14 | W108、W107、W042、W105；完整见卡 | AC-F2-15-01～04 / TC-F2-15-01～04 | VERIFIED（2026-09-28 verify exit0：HypitFix2C15IT 4/4 + HypitAgentJobIT 回归 6/6（executed=12 passed=12 tcFound=4）；90s 租约+15s 步进续租双 worker 单执行权（46s 不夺权/70s 卡死接管沿冻结计划 planner 恰 1 次）、prepared+下游已接受同幂等键重放 build/command 行数不增、终态 CAS+cancel_requested 步边界优先 terminal 恰一条、resume×2 同 requestId 只恢复一次+blockedReason 解除+新规划轮+scope 不扩；实锄两真缺陷=hashOf 直哈希原文遇 PG jsonb 键序重排致同动作崩溃前后指纹漂移（改 canonical 键排序规范化）、planWithRetry 递归 PlannerFailed 被外层 onErrorResume 再包装污染留证（改直传）；HypitAgentJobIT requeue 预置真 hash+resume 补 planner 桩迁移 B.2 1.0.7） |
| C107F2-16 / REQ-F2-16 | 接通全片参考分析、转写与时间锚点 | F22,F06 | C107F2-14、C107F2-15 | W117、W118、W107、W108；完整见卡 | AC-F2-16-01～04 / TC-F2-16-01～04 | VERIFIED（2026-09-28 verify exit0：HypitFix2C16IT 4/4 + HypitClonePlanIT 回归 2/2（executed=6 passed=6 tcFound=4）；analyze intent 内置确定性参考分析步骤（W110 analyzeReference：真实 media.probe→media.frames→speech.transcribe→平台执行入口综合，W119 prompt 落 resources）；12s 0/4/8 三段全覆盖锚点与帧证据一致、结果结构化持久进 reference.analyze command 可刷新重读（GET reference-analysis?mediaHash）；视觉综合缺失→waiting_input 保留已完成部分不冒充 ready；无声 ABSENT 三态零转写调用不伪造台词+截断 PROVISIONAL 带 gap；换源 hash clone-plan 保存 409 hypit_analysis_mismatch；HypitReferenceAnalysis 增 audioTrack 三态字段（W118）；ClonePlanIT 构造迁移 unknownAudio） |
| C107F2-17 / REQ-F2-17 | 把复刻方案转换为有内容的源码与材料需求 | F22,F29 | C107F2-16、C107F2-06、C107F2-15 | W121、W122、W123、W124；完整见卡 | AC-F2-17-01～04 / TC-F2-17-01～04 | VERIFIED（2026-09-28 verify exit0：HypitFix2C17IT 4/4 + HypitAuthoringIT 回归 5/5（executed=9 passed=9 tcFound=4）；实锄 F22 写侧真身=mainSvml 只把 steps 写 HTML 注释+Timeline 固定 2s 模板——重写为内容真实落文档（Timeline 总时长随方案、每步 ColorWash 锚点窗、字幕 text:Value 真实文本、素材 assetId 实际引用/缺失显式 MISSING 不伪造）；一轮无效引用二轮修复 validated 草稿+两轮仍坏 save 草稿诊断保留 head revision 不动；缺绑定 asset WAITING_INPUT 指出缺口零 command/changeset/sidecar；validateChangesetSafety 拒绝绝对路径/../反斜杠/控制字符与 exec/spawn/child_process 原语整批保留原源码；PlanStep 扩 endSeconds/assetId/caption（W123）；W126 authoring.md prompt 落 resources） |
| C107F2-18 / REQ-F2-18 | 接通分析、方案、生成、重试、取消与授权 UI | F06 | C107F2-11、C107F2-12、C107F2-13、C107F2-15、C107F2-16、C107F2-17 | W072、W128、W086、W103；完整见卡 | AC-F2-18-01～04 / TC-F2-18-01～04 | VERIFIED（2026-09-28 verify exit0：fix2-c18.test.ts 4/4 + c11/useHypitJobs 回归（executed=15 passed=15 tcFound=8）+ npm run typecheck 绿；实锄 F06 真身=Workbench @analyze/@regenerate=void 0、@generate 仅切页签、AgentActivity @cancel=jobs.stop() 把 stop SSE 当取消——新建 useHypitWorkflow（W128）编排 analyze/author/agent-jobs→watchJob、check→plan→pricing→（远程 Need）授权对话框→execution-grants→builds（稳定 requestId 派生 FNV-1a 于 project/runFile/revision/stage）、cancel 连点合并在途单发+cancelPending 观察服务端终态；Workbench 五处接线（W072）；取消授权零 grant/build、确认后恰一次、超时重试同键恢复同 job 实证；§8.8 亮暗截图按卡文随 C36 视口矩阵统一补拍（本卡无视觉新增，组件层 Workbench.test.ts 回归通过）） |
| C107F2-19 / REQ-F2-19 | 启动原生 Studio 并接通 HTTP、WebSocket 与写回 | F16 | C107F2-04、C107F2-10、C107F2-15 | W134、W135、W136、W137；完整见卡 | AC-F2-19-01～04 / TC-F2-19-01～04 | VERIFIED（2026-09-29 verify exit0：backend agent-integration 15/15（fix2-c19 四组 TC-F2-19-01～04 全发现）+ HypitFix2SessionMigrationIT 4/4（§7.5 表落位/约束拒绝/Flyway 恰一条 V92/DDL 重放幂等/V91 存量共存）+ gradle 编译。实锄五根因：①signSessionAssertion 对 body 而 verify 对 base64url 段——自洽签名必败 401，改 MAC 段；②代理剥前缀转发撞 vite base 中间件 302 回环——子进程 base 挂载转发完整原始路径（WS 同理，HMR 网闸 pathname===hmrBase）；③语义补丁按 runFile 整读——补丁目标 main.svml 非 run 文件，改按 path 分组逐文件读改存；④observeChildRevision 见数即回——返回重载前旧 revision，改采样基线等越过；⑤票据 stand-in URL 段索引错位（6 段 /internal/hypit/sessions/{sid} 内部路径为权威）。代理闸门：无断言 401/异会话 401/过期 401/裸资源无 cookie 无 ticket 403/路径越界拒绝；写面 readOnly 403、Java 409 文件零变更；子进程环境白名单无密钥泄漏；Studio 组 20/20 回归。W141 HypitSessionAccessController（GET access 票据核销+30s 断言、内部 writeback 走 changeset create+apply CAS 推会话基线）+V92+HypitSessionRepository+服务 PG 先登记再启动签 60s 一次性票+nginx auth_request（/_hypit_session_access 内部子请求复用 $dh_edge_upstream）+edge 路由 flag+compose 三文件 env（SESSION_{TICKET,ASSERTION}_SECRET、STUDIO_PORT=9464）+契约 3.4.0 增 access 路由与 messageNonce 注记。本卡只声明 Node 集成与明确依赖结果：票据复用/撤销矩阵归 C20） |
| C107F2-20 / REQ-F2-20 | 修正 Studio 票据、只读、版本复用及撤销生命周期 | F19 | C107F2-19 | W137、W150、W134、W025；完整见卡 | AC-F2-20-01～04 / TC-F2-20-01～04 | VERIFIED（2026-09-29 verify exit0：backend agent-integration 19/19（fix2-c20 四组 TC-F2-20-01～04 全发现：复用键变体新会话 readOnly=true/同会话换票 nonce 槽替换+CAS 一次核销重放拒/readonly HTTP+WS 服务端 403 文件哈希零变更/撤销即拒访问+子进程终止+close 幂等）+ HypitFix2C20IT 4/4（真 PG：复用键 reused=true/换票槽替换+核销一次/关闭幂等+sidecar close 通知/revokeAllForOwner+markExpired 惰性过期）。实锄一真缺陷=run-tests.mjs 文件并发时同组共享 studio 端口带（25179-25478）跨进程 TCP 探测竞态——两 vite 子进程同口相撞败者早亡会话被清成 404（C19 gate 曾侥幸独跑），钉 --test-concurrency=1（本仓单 worker 约定）。closeStudioSession 改幂等（未知/已亡也 closed:true，§6.9 DELETE 语义）+revokeStudioSessionsForOwner/activeStudioSessionIdsFor；dispatcher 增 studio.session.close/revoke 与 preview.session.close；Java openSession 复用键 findReusable（owner/project/kind/run/revision/readOnly 全等）+closeSession（markClosed+broker 通知，非本人/未知幂等 200）+revokeForOwner+markExpired/advanceRevision 仓库方法；W140 增 DELETE studio/preview-sessions 幂等端点（404 不泄露状态）；W151 useHypitStudio（generation 代际丢弃在途回执/reset 切工程关旧会话不代旧 ticketUrl）+W152 StudioPanel expiresAt 过期不进 iframe+空态重开；W075 closeStudioSession API；契约增 DELETE 两路由 flag；edge/compose 三文件 SESSIONS_{STUDIO,PREVIEW}_CLOSE。票据在会话绝对 TTL 3600s 内换发不受核销延长（expiresAt 建行时定死） |
| C107F2-21 / REQ-F2-21 | 限定 iframe 安全策略并验证三入口回归 | F17 | C107F2-19、C107F2-20 | W154、W145、W011、W012；完整见卡 | AC-F2-21-01～04 / TC-F2-21-01～04 | VERIFIED（2026-09-29 verify exit0：hypit-fix2-c21.contract 4/4 + hypit-entrypoint 回归 6/6（executed=10 tcFound=4）+ gradle 编译。W154：$uri map 按请求切 XFO（default DENY，/studio/<id>/ SAMEORIGIN）与 CSP 强制头（default 主变体 frame-ancestors 'none'，studio 变体 'self'）——避开 add_header location 继承陷阱（location 级 add_header 会丢 server 级全部安全头），只有 82 引用切换变量，80/81 字面量 DENY 不回退；新增 log_format hypit_studio 用 $uri（无 query）——ticket 一次性串禁入访问日志。W145：/_hypit_session_access internal 子请求复用 $dh_edge_upstream（固定 Edge access 路由、proxy_pass_request_body off、Cookie 透传站内身份、内部 token 不出现在片段）；auth_request_set 断言经 proxy_set_header 无条件覆盖出站头（客户端伪造同名 X-Hypit-Session-Assertion 被丢弃）；/studio/ location 挂脱敏 access_log。W156 四组契约+ W155 三入口回归（80/81 未切换变量、DH 片段无 studio 放宽互不覆盖）。同源 Origin 含端口校验与 WS 固定路径在 broker（C19 proxy assertProxyOrigin+资源白名单）已实装；真实浏览器 iframe 响应头与 console 回归按卡文归 C36/C37 视口矩阵） |
| C107F2-22 / REQ-F2-22 | 构建绑定不可变版本的真实 Preview 服务 | F18 | C107F2-06、C107F2-19、C107F2-21 | W157、W158、W159、W026；完整见卡 | AC-F2-22-01～04 / TC-F2-22-01～04 | VERIFIED（2026-09-29 verify exit0：backend agent-integration 23/23（fix2-c22 四组 TC-F2-22-01～04 全发现+A/B 快照共存断言）+ studio 21/21 + media 48/48 回归 + gradle 编译。实锄一真缺陷=normalize 保留前导斜杠被自守卫误判「escapes the snapshot」恒 401——预览资源路径先剥前导 / 再裁决。W157 重写：会话绑定不可变快照（revisions/<n>/revision.json 的 manifestHash 必验 64hex）、served 集合从同一快照 manifest 的 assets/ 媒体文件派生（不再查错误的 .hypit/revisions）、TTL 1800s 惰性回收、真实 transient execution 从快照作者根打开（失败清场无假会话）、close 幂等并终止执行；W159 NEW 预览资源面（与 Studio 同一 9464 端口同一条 access 链：断言二次校验+owner 绑定、单 Range 206+Content-Range 截断、Content-Type 按快照派生、越权/穿越/非素材 404、根路径不放内容）；W026 preview.session 新契约（ownerAccountId 由 Java 传入、返回 previewUrl/manifestHash/expiresAt）；W158 增 §6.10 消息契约（ready/frame/error 三关 schema+encodeControlMessage 显式 nonce）；W160 Java 重写：先登记 PG hypit_session（kind=preview）→ sidecar 派发 → markActive → 空 URL/空会话=引擎错误绝不发空 src 成功（步骤 4 红线）、closeSession 翻 PG 终态；既有 preview/snapshot 测试随契约迁移（不可变快照冻结）。媒体 snapshot 工具仍读 head 渲染（其 W 不在本卡写集），资源面已快照绑定——边界如实留 C37 贯通时核对） |
| C107F2-23 / REQ-F2-23 | 修复预览跨帧消息、播放状态与资源释放 | F20,F18 | C107F2-22、C107F2-11 | W162、W163、W075、W072；完整见卡 | AC-F2-23-01～04 / TC-F2-23-01～04 | VERIFIED（2026-09-29 verify exit0：fix2-c23 4/4 + c11/useHypitJobs 回归（executed=15 tcFound=8）+ video-clone 全域 12 文件 50 用例全绿 + vue-tsc 干净。W162 重写：sandbox opaque origin（origin=null/空串）不拒绝——绝不做 host 子串校验（旧 parseMessage 的 origin.includes(host) 违 §6.10 已拔除），身份三关=event.source===bindFrame 绑定的 contentWindow + sessionId + 会话 nonce（open 时生成经 nonce computed 暴露）+严格 schema（ready/frame/error；frame 非负安全整数、timeSeconds 有限非负、unknown 静默忽略）；sendControl 显式目标窗口+nonce、payload 无秘密、opaque origin 下 targetOrigin="*"；close/reset 代际作废在途 open（迟到回执不重开）、幂等 DELETE 恰一次、失败不重试轰炸（服务端 TTL 兜底）；播放时钟只来自真实 frame 消息。W163：iframe ref watch→bindFrame（挂载绑定/卸载解绑）、expiresAt 过期不渲染旧 ticketUrl 且给「重新打开」入口（clone-preview-expired/reopen testid）、空字符串 src 绝不出现；W075 增 closePreviewSession DELETE；W072 接线 :bind-frame。测试基建：happy-dom 的 MessageEvent.source/iframe.contentWindow 恒 null——捕获 window message 监听器直呼合成事件验三关；面板只验 src/时钟渲染。真实 iframe 文档装配（broker 侧展示 HTML）不在本卡 W 集，归 C37 真实浏览器贯通核对） |
| C107F2-24 / REQ-F2-24 | 使 Feedback 批次校验和落盘原子化 | F32 | C107F2-10 | W165、W078、W026、W140；完整见卡 | AC-F2-24-01～04 / TC-F2-24-01～04 | VERIFIED（2026-09-29 verify exit0：backend agent-integration 27/27（fix2-c24 四组 TC-F2-24-01～04 全发现）+ studio 21/21 回归 + gradle 编译。实锄 F32 真身=旧 mutateFeedback 逐条 store.mutate 各自读改写原文件——CAS 检查到落盘之间可被并发插入、中途失败留部分写入。重写为四段管线：①先全量 readFeedbackMutation（上游 schema）任何一条非法整批拒绝文件零接触；②withProjectLock 项目锁内（与 revision 写共用边界，W078 既有锁）读最新文档 CAS expectedHash，冲突 DispatchError 携 currentHash（409+当前 hash）；③内存预计算最终文档（逐条镜像上游 apply 冲突检查：add 查重/replace-delete before 深相等+id/run 保留），经上游 readFeedbackDocument 复核 schema 后同目录临时文件+rename 一次提交；④requestId 进程内幂等回执表（上限 500 LRU），同键重放原回执评论数不增。W026 dispatcher 透传 requestId；既有 feedback-roundtrip 测试零改动兼容（requestId 可选参）。Controller 202 沿既有（§6.11 沿当前受理面，无 Java 改动必要） |
| C107F2-25 / REQ-F2-25 | 按评论做语义源码修改并关联解决状态 | F10,F22 | C107F2-17、C107F2-24、C107F2-22 | W167、W107、W108、W079；完整见卡 | AC-F2-25-01～04 / TC-F2-25-01～04 | VERIFIED（2026-09-29 verify exit0：HypitFix2C25IT 4/4 + HypitReviewIT 回归 3/3（executed=7 tcFound=4）+ gradle 编译。实锄 F10/F22 真身=旧 revise 用规则表给固定 48px/-6 猜值、前端拼一行注释 content+baseHash=null 整文件覆盖、无 resolve/映射/冲突语义。W167 重写：commentIds→feedback.read→逐条显式锚点提取（字幕+「N px」/音量+「N dB」，无锚点=waiting 禁止猜值）→workspace.read 读原源码做最小替换（正则仅换首处锚值、其余字节原样、baseHash=原文 sha256）→同(kind,file) 冲突值整批 WAITING_INPUT 不随机覆盖→validated changeset CAS apply（未过检查 hypit_compile_failed head/评论不动诊断可见）→apply 成功后 feedback.mutate 批量 replace resolved:true（失败保持 open 不阻断）+comment→job→revision 映射落 review.revise 命令回执（job 面缺失自建行）。W107：review.revise 确定性动作分派（不走 LLM 注册表，DispatchOutcome 映射 mutation.apply 口径）；W108：revise intent 携机器可读 brief JSON（commentIds/run）时跳过 LLM planner 走确定性单动作计划；WAITING_INPUT 观察直接 enterWaitingInput 不烧 replan。W088 前端 revise 改投 agent-jobs intent=revise+watchJob 终态（succeeded→刷新，waiting/failed→展示 blockedReason）；W072 注入 watchJob。HypitReviewIT 迁移至新语义（§13.3：评论带显式锚点、workspace.read 桩、48px 猜值断言换成最小替换断言、tc04 422→WAITING_INPUT） |
| C107F2-26 / REQ-F2-26 | 修复变体生命周期、重试取消路由与服务端授权 | F13,F28 | C107F2-07、C107F2-08、C107F2-15 | W170、W171、W172、W080；完整见卡 | AC-F2-26-01～04 / TC-F2-26-01～04 | VERIFIED（2026-09-29 verify exit0：HypitFix2C26IT 3/3 + HypitVariantIT 回归（executed=7 tcFound=3）+ gradle 编译。实锄 F13 真身=W170 旧 converge switch 只认 "succeeded/failed/cancelled"，而 Build 真实 lifecycle 是 submitting/active/execution_decided/result_pending/finished/submission_incomplete——恒不匹配，变体永卡 running；F28=重试/取消只有仓储原语无服务端路由。W170 重写显式映射：finished+cancelled→cancelled；finished+outcome=complete+resultReady（syncOutputs 引擎失败回落读 hypit_output 非空）→succeeded 否则继续观察；submission_incomplete+cancelled→cancelled/否则 failed；submitting/active/execution_decided/result_pending 中间态继续观察绝不提前成功。W171 retryVariant 服务层状态闸（非 failed/cancelled→hypit_state_conflict 409，重复重试不再增 attempt）+cancelVariant 单项作用域（engineBuildId 未落→build 就地 submission_incomplete+cancelled 再收敛变体，终态/成功项不级联）；W172 retry 扩展 state IN ('failed','cancelled')。W173 IT 三组按真实 V91 DDL 造数（hypit_job 作 batch FK/plan_id NULL 避 FK/output kind 约束 scalar/resource/composite；jsonb 用内联字面量绑——Map.of 走 InParameter 编码失败实录）；E04 并发 prepare 用 Flux.merge 收集恰一 true 一 false+execution 行数=1 持久事实断言） |
| C107F2-27 / REQ-F2-27 | 变体前端真实取消、重试、授权与状态展示 | F13,F28 | C107F2-26、C107F2-18 | W089、W075、W174、W132；完整见卡 | AC-F2-27-01～04 / TC-F2-27-01～04 | VERIFIED（2026-09-29 verify exit0：fix2-c27 4/4 + fix2-c11/fix2-c18 回归 executed=12 tcFound=12 + vue-tsc 绿。实锄 F13 前端半边真身=useHypitVariants.act 无 cancel 分支（面板 emit cancel 无消费者，F13「变体取消未实现」）、retry 不带 requestId（超时重放会双发）、build 恒 grantId:null（远程必败）。W089 重写：cancel 真实 POST {requestId,reason} 一次、回执就地落项不用 refresh 推断；retry 携 FNV-1a 稳定键（variant.id+attempt 进键，超时重放同 requestId；409 hypit_state_conflict 回执不落项 attempt 不假增）；build 先 plan+pricing 报价（服务端 build 内 planVariant 复用同 runFile 冻结计划）→远程 Need 置 pendingGrant 等确认→execution-grants（scope=定价 need 去重集，max=单项×项数）→build 携 grantId；已持有 grant 被服务端拒即失效重走授权；连点以 actingId 去重。W075：retryVariant 改携 {requestId}、新增 cancelVariant、回执类型对齐服务端 {id,state,attempt}（弃假 HypitVariantItem）；W076：+HypitVariantMutationResult/HypitPendingGrant/HypitVariantItem.buildId?。W132 NEW ExecutionGrantDialog（GlModal 承载、token-only、总范围/逐项/累计上限/价格未知明示，零请求只发事件）；W072 主生成流与变体流共用同一对话框实例（主流优先）；W174 重试钮扩 cancelled 态、buildId 关联展示（本会话回执补写，服务端列表 DTO 未扩——C26 契约边界如实保留）。TC 断言「一次 POST cancel 而非仅 GET」（act 零额外 GET+恰一次 POST+服务端回执落 state）、同 requestId 断言取自两次重放请求体、build 请求 0 断言取自 calls 过滤。§8.8 亮暗截图随 C36 视口矩阵统一补拍（与 C18 同口径） |
| C107F2-28 / REQ-F2-28 | 实现二进制保真的工程包与整包验证 | F30 | C107F2-04、C107F2-06 | W176、W177、W178、W078；完整见卡 | AC-F2-28-01～04 / TC-F2-28-01～04 | VERIFIED（2026-09-29 verify exit0：fix2-c28 4/4 + agent-integration/workspace 双组回归 executed=62 tcFound=28 + backend typecheck 绿。实锄 F30 真身=import 落地把每个文件 readFileSync utf8 塞进 FileChange<string>——PNG/MP4/字体被 UTF-8 解码损毁且误套 2MiB/16MiB 文本上限，4GiB 包必然失败；export 同样 readFileSync 整读多 GiB 资产会打爆内存。W179 NEW binary-staging：STREAM_CHUNK_BYTES=512KiB 单流缓冲、streamHash/streamCopy（边拷边哈希+临时 inode 原子 rename，失败清 scratch）。W078 FileChange 增 contentPath?: string——staged 源流式落地、journal newHash=落地字节真摘要、projectedManifest 用 stagedMeta；2MiB/16MiB 文本预算只约束 text content，包上限独立在 import 层。W178 共享 MAX_BUNDLE_{FILES,TOTAL_BYTES,FILE_BYTES}=20000/4GiB/4GiB+role 白名单校验。W176 重写：manifest 先行 413（计数/单文件/总量，磁盘未动）→逐条 forbidden path/lstat 拒 symlink/statSync nlink>1 拒 hardlink/streamHash 核 size+sha256（不整读）→selectedRun 存在→provision→contentPath 一次 journal 落地→computeWorkspaceManifest 落盘二次核对→snapshotRevision 冻结完整 revision（W053 复用 C06 不可变快照）→ctx.engineExecutor 原生 parse/check selectedRun（check 失败/落盘不符/快照失败=compile_failed/engine_error 且移除新工程壳，只留可清理 staging）；executor 注入留在 API 边界由 TC-F2-28-02 驱动验证——broker 级 dispatch 保持字节落地不强制 check（legacy harness 引擎替身只认 render.local，F01-02/03 路由/幂等 TC 必须继续通过），生产 ready 闸由编排层既有 workspace.check 链承接（C29/C30 接线）。W177：export 改 streamHash（4GiB 单文件不整读）+streamCopy 落 bundle、walk 后重读 head 冻结校验（revision/manifestHash 变了=conflict 拒绝，防撕裂包）、selectedRun 必须在冻结文件集内；W026 相应接线。 |
| C107F2-29 / REQ-F2-29 | 完成导入 PG 登记、owner 绑定与跨服务幂等收敛 | F31 | C107F2-05、C107F2-28 | W181、W038、W039、W040；完整见卡 | AC-F2-29-01～04 / TC-F2-29-01～04 | VERIFIED（2026-09-29 verify exit0：HypitFix2C29IT 4/4 + HypitTemplateIT 回归（executed=8 tcFound=4）+ gradle 编译 + backend tsc（W176）绿。实锄 F31 真身=旧 import_ 把 broker 随机生成的 projectId 直接落回执、Java 零 PG 登记（无 hypit_project/revision/job 行、无 owner 绑定）、重放靠 resultJson 或 503 死等、export 的 saveResult 是 fire-and-forget subscribe。W181 重写：合法导入先单事务预留——projectId/jobId 事先生成随首次 commands.insert 落 payloadJson（崩溃重放自读无需另查 job）+project(mode=import,status=provisioning)+job(queued)，UNIQUE(account,action,request_id) 幂等、canonical={artifactRoot,packageSha256} 同 requestId 异包 409 hypit_idempotency_conflict；sidecar 命令改稳定 commandId=java-import-<commandId>+payload.newProjectId=保留 id（W176 broker 校验 uuid 并采纳、不再自行 randomUUID——同命令重放命中 broker CommandStore 已存回执不二次解包）；成功收敛单事务 projects.markReadyImported（W039 新方法：ready+revision+head_manifest_hash+selected_run 一次落定，仅 provisioning/provisioning_failed 态）+revisions.insert（幂等 ON CONFLICT，snapshot_handle=revisions/<n> 呼应 C28 冻结）+job succeeded，事务后串行 saveResult（回执体含 projectId/jobId/state/revision/manifestHash/fileCount/selectedRun）——export 路径的 subscribe() 脱管同卡清除；失败收敛 markStatus(provisioning_failed)+job failed+saveResult(failed) 重新查询可见；重放分支：resultJson 在→回读，在而空（崩溃窗口）→重发同 commandId 完成登记。W182：/api/hypit/imports 从 requireOperator 放开到普通 owner（§6.13 普通owner可上传），ImportRequest 增 title/packageSha256。跨类实锄：HypitTemplateIT tc04 的 project.import 计数无 owner 过滤，被同库其它 owner 的导入命令污染（合并运行 2/单独 1）——计数补 owner 作用域（TC 意图不变）。E03 重放完备性验证含重发请求体断言 newProjectId=保留 id） |
| C107F2-30 / REQ-F2-30 | 接通工程包浏览器下载、上传导入与进度反馈 | F14,F31 | C107F2-28、C107F2-29、C107F2-12 | W080、W182、W184、W181；完整见卡 | AC-F2-30-01～04 / TC-F2-30-01～04 | VERIFIED（2026-09-29 verify exit0：HypitFix2C30IT 4/4 + HypitTemplateIT 回归 + backend:agent-integration 组回归（executed=39 passed=39 tcFound=32）+ backend tsc + gradle 编译绿。实锄 F14 真身=导出 fire-and-forget 无 jobId 可查、无下载入口，旧"下载"=把 broker 服务器 artifactRoot 路径塞进回执（浏览器拿不到字节）；导入面只有 JSON 桩。W186 NEW broker transfer.ts 零依赖 zip 编解码+传输仓：store-only pack（CRC 预扫描+固定 DOS date 确定性输出）、central-directory reader（EOCD 尾扫 66000B；externalAttrs 拒目录/symlink）、method 8 inflateRaw、TTL 24h→410、Range 206/416 透传、ETag=zip sha256、filename* 处置头；W025 增 PUT/GET /internal/v1/package-transfers/{id}/content（staging 流式落盘 scratch+rename）；W026 export 命令透传 exportId、import 增 transferId 路由。W181：export 完全 job 化（convergeExport 落 download map：downloadPath=/api/hypit/exports/<id>/package+expiresAt=+24h+zipSha256/sizeBytes/name）；新增 importTransfer 流（canonical={transferId,packageSha256}，预留/收敛复用 C29 单事务骨架）；W080 导出改 202 {jobId,exportId,status}；W182 JSON 体上传导入明确 415 hypit_unsupported_media_type（二进制必须走 multipart）。W185 NEW HypitPackageTransferService：transferIdFor=SHA-256(requestId) 派生稳定 id；upload=DataBuffer 流式边传边哈希（4GiB→413，不整读）；download 用 retrieve().toEntityFlux(DataBuffer) 聚合 status/headers/body——实锄 exchangeToMono 形态会取消延迟消费导致 zip 字节 null，换 retrieve 后透传 410 hypit_export_expired/>=400 hypit_engine_error。W184 NEW controller：POST /api/hypit/imports（multipart .zip 校验 415）、GET /api/hypit/exports/{exportId}（owner 过滤+jobs.findByCommandId 兜底，W105）、GET/HEAD .../package（透传头+流）。前端 W075/W076/W187/W189/W072：ProjectPackageDialog 导入=XHR 真上传进度+可 abort、下载=exportStatus 轮询收敛后 anchor.click() 真 blob 下载（带 zipName），Workbench 共享 grant 弹窗接线。部署面 W147 edge 三路由、compose×3 EDGE_ROUTE flag、W001 契约 routes、nginx ×3 server 块 location=/api/hypit/imports client_max_body_size 4G。zip 编解码对 macOS ditto -x -k 与 unzip -t 双向验证通过（证据 test-artifacts/task-107/fix2/C30/zip-codec-smoke.mts）。边界如实：§8.8 亮暗双主题/移动端截图按卡文统一 C36 补拍；真实浏览器端到端（导出→下载→上传→ready）归 C37 真浏览器贯通验收 |
| C107F2-31 / REQ-F2-31 | 完成参考素材文件上传和跨创作入口交接 | F14,F29 | C107F2-05、C107F2-16、C107F2-30 | W038、W110、W191、W192；完整见卡 | AC-F2-31-01～04 / TC-F2-31-01～04 | VERIFIED（2026-09-29 verify exit0：HypitFix2C31IT 4/4 + HypitAssetIT/HypitProjectIT/HypitTemplateIT 三类回归（executed=24 passed=24 tcFound=4）+ 根 tsc + gradle 编译绿；组件测试 VideoCloneWorkbench.test.ts 3/3。实锄 F14/F29 素材面真身=①mediaId 导入只落 media: JSON 引用（无 res- 句柄无字节，probe/分析全部落空——「资产可见且 broker 读取字节」不成立）；②上传长度恒 -1 全靠 sidecar 边收边限、256MiB 契约上限无处执行；③sidecar 回执 state=failed 不检查、伪装 mp4 照样收敛 ready；④sourceContext 创建后无人物化，参考素材面板空。W193 NEW HypitAssetUploadService：256MiB=MAX_ASSET_UPLOAD_BYTES 双路拦截（multipart 声明长度入口先拒零字节出站+ingest 回执 sizeBytes 超限拒收，两种路径都不出现 ready 素材）+copyMediaObject（对象存储 getObject→sidecar /internal/v1/resources 流式转发，产出 broker 可消费 res- 句柄；存储未配置如实 503）。W110：importMedia 重写为「归属/固化核验→真实字节复制→probe 核验→ready」（owner 不符与不存在同答 404 不泄漏存在性、非 active 409 hypit_source_not_permanent；旧 400/JSON 引用语义废除）；importSource 交接物化（media→复制真实字节为 res- 素材，稳定 requestId=UUID.nameUUIDFromBytes(project:media) 幂等重放；analysis 仅锚定 run 返回可行动说明——临时代理媒体无可复制字节；brief/空→200 说明）；executeAsset 增回执 state=failed 检查（receiptFailure：upload/import/tool→422 透传 B 端码，url→422 hypit_fetch_failed 保留来源站具体原因）；convergeAsset 增 project+sha256 复用（同工程同字节返回既有行+reused=true，来源保留原行）；convergeFailed 两处实锄修复：失败记录先独立事务提交再在事务外浮错误（旧形事务内发错误信号整体回滚，job 永远停在 queued 无 failed 审计——TC-F2-31-03 的 failedJobs=1 断言逼出）+4xx 语义错误原样上抛不再统一伪装 503；toDto 补 resourceHandle（前端/IT 均断言，旧 DTO 根本没这个字段）。W191 增 findReadyByProjectAndSha/findActiveByProjectAndMediaId。W192 增 POST /assets/import-source（owner 闸，asset≠null 202/说明 200）与 §6.14 契约路由 POST /assets/upload（与 /assets multipart 同服务面，role 缺省 reference）。W195 NEW useHypitAssets：列表/上传/交接/取消/重试状态机，工程切换 abort 旧请求并重载，上传错误保留已选文件（§8.3），终态真值以服务端列表为准不本地伪造 ready。W091 ReferencePanel 重写：file input（accept 体验+256MiB 前置提示+XHR 真进度+可取消+可重试）、URL 入口提示与实际能力一致（emit 给 Workbench，既有 SSRF/平台防线在服务端）、交接说明区；W072 接 importUrlAsset（旧 @import-url="void 0" 死绑定清除）+urlImportError 展示。W075/W076 增 uploadAsset（XHR 进度+abort）/importSource/listAssets/importUrlAsset/HypitAssetDto。部署面：edge application.yml 两路由+compose×4 flag（ASSETS_UPLOAD/ASSETS_IMPORT_SOURCE，hypit-compose.sh 从生产 overlay 自动派生旗标集）、W001 契约两路由+errors 增补、nginx ×3 server 块正则 location assets/upload client_max_body_size 256m 流式转发。HypitAssetIT 同卡纠正到新契约（importMedia 真实字节断言+404/409 语义+对象存储桩）。边界如实：§8.8 亮暗截图与移动端矩阵归 C36；真实浏览器端到端归 C37 |
| C107F2-32 / REQ-F2-32 | 修复维护模式写入栅栏与在途执行排空 | F33 | C107F2-08、C107F2-15、C107F2-20、C107F2-22、C107F2-26、C107F2-29 | W197、W026、W025、W198；完整见卡 | AC-F2-32-01～04 / TC-F2-32-01～04 | VERIFIED（2026-09-29 verify exit0：fix2-c32 四组 + agent-integration 全组回归 executed=36 passed=36 tcFound=31 + backend tsc 绿。实锄 F33 真身=①countActive 数的是 commands 表里不存在的 'queued','running' 两个 state（真实状态集是 queued/dispatching/acknowledged/succeeded/failed/unknown）——恒 0，enter 立即宣称 drained；②栅栏只挡 commands/resources 两个 POST，工程包上传 PUT 不过栅栏；③exit 无租约概念，任何 exit 都解除别人的维护窗；④租约纯内存，broker 重启=维护窗静默消失。W197 store.ts：countActive→countActiveCommands（真实三态）+activeCommandSummaries（超时报告在途业务 ID）+countActiveBuilds（带 engineBuildId 的在途=原生 active build 持久证据）+maintenance_lease 单行持久表（tryAcquire/leaseId 冲突 DO NOTHING/release 只认自己 leaseId）。W026 dispatcher：导出 maintenanceCounts（store 证据 ∪ capacity 本地渲染租约 heldCount，取 max 不双计）+MAINTENANCE_EXEMPT_KINDS（build.cancel/三类 session close·revoke/status——§6.15 读取/状态/取消/收据收敛不被栅栏挡）。W025 server：enter 重写=原子封写先行（先 flip gate 再统计）→单槽租约持久化→60s（HYPIT_MAINTENANCE_DRAIN_TIMEOUT_MS 可调）轮询在途三信号（activeCommands+activeBuilds+activeWriteStreams）→200 {leaseId,drained,activeCommands,activeBuilds,activeWrites,waitedMs,inflight}，drained 只在全零时 true（不立即宣称）；exit 只认自己 leaseId（错/缺 leaseId=409 窗口保持关闭）；重启 fail-closed（启动读持久租约恢复栅栏）；activeWriteStreams 计数器盖住 resources ingest 与 transfers PUT 两条绕过 commands 的写入流（栅栏后新请求 503，栅栏前已在途的计数进排空等待）。W199 capacity：heldCount/heldKeys 访问器。W202 events.ts 未动（追加型事件面无栅栏交互，写入集许可未消费）。W066：503+maintenance mode 响应映射为结构化 IntelligenceException(503, hypit_maintenance)（不再伪装 IllegalStateException）；W108 agent worker：hypit_maintenance 不烧修复轮预算不落 failed 终态——checkpoint 原样保留+progress 留痕，维护退出后 worker 下一轮自动续跑；W170 variant：onErrorMap 不再把 hypit_maintenance 折叠成 BAD_GATEWAY（原语义上浮可重试）。W200 backup.sh：enter 解析 leaseId+drained；drained=false 打印在途业务 ID 后 release_own_lease 非零退出（不留永久 503 不强杀）；409=他人窗口立即失败退出；EXIT/INT/TERM 陷阱注册幂等释放自己的租约。maintenance.test.ts（107-4 既有协议测试）升级到 leaseId 契约（drained 字段/409 不泄漏/错 leaseId 409 窗口保持关闭）。基线如实（非本卡写入集）：engine 组 4 个 runner 测试（runner-isolation 3+package-build TC-F06-01）因 C107F2-04 的 D-04 daemon 重构（执行面移入 runner 容器常驻 daemon）而失败——测试仍假设 broker 进程内 spawn；C08 卡验收时未含该组，基线原始输出存 test-artifacts/task-107/fix2/C32/engine-group-baseline.log，留 C39/C40 集成复核处置 |
| C107F2-33 / REQ-F2-33 | 完整备份 PG 与所有持久文件，校验备份清单 | F33 | C107F2-32 | W200、W018、W011、W013；完整见卡 | AC-F2-33-01～04 / TC-F2-33-01～04 | VERIFIED（2026-09-29 verify exit0：hypit-fix2-c33.contract 4/4 + spec 契约回归 executed=12 passed=12 tcFound=5 + 根 tsc 绿。实锄 F33 备份面真身=①manifest@1 无逐文件清单无 complete 标记（失败也写出 manifest，恢复面无法判定可用性）；②tar 只打 HYPIT_DATA_ROOT 单目录、/data 兄弟目录全漏；③临时 slot/cache 无排除语义；④PG 与文件快照窗口顺序无结构保证。W200 backup.sh 重写：维护租约（C32 leaseId 协议，drained=false 打印在途业务 ID→release→非零；409 他人窗口即败）→pg_dump -Fc（0600+pgDumpSha256）→整伞归档（tar -C 伞目录 . 相对路径，TAR_EXCLUDES 锚定 ./name 形状剔除 runner-slots/runner-sockets/runner-tmp/.staging/package-transfers/cache；zstd 可用 .zst 否则 .gz；0600+dataSha256）→manifest y1.hypit-backup@2（format/createdAt/sourceCommit/engineDigest[healthz]/pgServerVersion/roots 角色化/files 逐 sha256/sizeBytes/omitted 理由/restore 提示；先写部分清单、全步骤成功后原子补 complete=true——两处 python heredoc，凭据内容/路径零打印）；cleanup 陷阱实锄修复：EXIT 无条件删 manifest 会把成功产物也删掉（smoke 逼出）→改为仅当无 complete 标记才清；tar 注入失败=非零+无 manifest+租约已释放。W203 NEW fixture 脚本：隔离数据伞（两 revision/二进制 PNG 魔数资源/results/0600 凭据样本/bridge 状态/临时 slot+cache）+受控 pg_dump/psql 桩（确定性字节含 DSN 引用，不触真实 PG）+fail-tar 注入桩；只写 target 目录。W204 NEW contract 测试（hermetic，stub broker 进程内记录 enter/exit 顺序）：TC-01 manifest v2 complete+pg/tarball 逐 hash 一致+归档含伞内全部九类文件+roots 全角色；TC-02 omitted 明确+归档零临时项+三产物 0600+日志无凭据内容；TC-03 fail-tar 注入非零+无 complete 清单+exit 已调用（租约释放）；TC-04 enter 先于快照结构断言+exit 收尾（窗口一致性由 C32 栅栏保证）。实锄修复：tar 排除模式 * / name 与归档 ./name 形状不匹配（临时项漏进归档，断言逼出）→锚定 ./name 重写；spawnSync 卡死事件循环导致 stub broker 永不应答 curl（vitest 同线程，死锁实录）→改异步 spawn。W018 README 备份章节升级 v2 契约；W011/W013 compose 未消费（备份为宿主一次性脚本，无服务定义需求，写入集许可未用）。边界如实：真实 PG pg_dump 与真实 broker 的完整演练归 C38；restore 消费归 C34 |
| C107F2-34 / REQ-F2-34 | 修复恢复目标映射、数据库核验和失败判定 | F34 | C107F2-33 | W205、W018、W203、W003；完整见卡 | AC-F2-34-01～04 / TC-F2-34-01～04 | VERIFIED（2026-09-29 verify exit0：hypit-fix2-c34.contract 4/4 + c33 契约回归 executed=8 passed=8 tcFound=8 + 根 tsc 绿。实锄 F34 恢复面真身=①旧 restore 把归档解到 TARGET_ROOT 的父目录（dirname -C），内容全落在 restore-a 旁边而非其内，且先 mkdir 后只查目录存在；②PG DSN 缺失只 WARN 跳过 PG 就继续；③核验用已废列 snapshot_dir+`‖ true`+缺列按 0 兜底——SQL 失败被吞成 missing=0 假通过；④manifest@1 无 complete 也照恢复。W205 restore.sh 重写：manifest v2+正式恢复强制 complete=true（部分清单拒绝）；正式必传 HYPIT_PG_DSN（缺→exit 2），--files-only 仅诊断 exit 3 PARTIAL（不参与 full 通过）；tar 路径预验证先于落盘（逐 entry 拒绝对路径/.. 穿越+顶层集合须含 manifest.roots 声明，违规 exit 2）→显式映射解包 -C TARGET_ROOT（内容在目标内，无旁边目录，hypit/ 顶层存在性硬校验）；pg_restore --exit-on-error 失败→写 FAILED report+exit 5；恢复核验按真实 V91 列（p.id/revision/r.number/snapshot_handle/manifest_hash JOIN）逐行解析 projects/<projectId>/<handle> 路径+manifest.json 逐文件 sha256 比对，缺失/hash 不一致/清单文件缺失三类计数全量写 report.notes——任何非零计数 exit 4 FAILED；SQL 失败 exit 5（绝无 ‖ true/‖ echo 0 兜底）；report y1.hypit-restore-report@2（status READY/PARTIAL/FAILED+filesOnly+newGenerationsTriggered 恒 0+分类明细）；退出码表 0/2/3/4/5。W203 fixture 增 pg_restore 成功桩+psql 桩升级（SHOW server_version/snapshot_handle 查询/失败注入 HYPIT_FIXTURE_PSQL_FAIL/READY 清单钩子 HYPIT_FIXTURE_PSQL_TSV）+pg-fixture/revisions.tsv（rev-1/rev-2 真 manifest_hash 由 fixture 构建时按真实字节算出+rev-99 指向被删 snapshot 供 TC-03）。W206 NEW contract 测试（hermetic）：TC-01 READY 恢复内容全部位于 restore-a 内且 fixture 根零旁落目录+报告 READY 全零计数；TC-02 正式缺 DSN 非零+--files-only exit3 PARTIAL+SQL 失败注入 exit5 FAILED 且 stdout 无 READY；TC-03 默认清单（含 rev-99）→exit4+revision_snapshot_missing 计数 1+stdout 无 READY；TC-04 newGenerationsTriggered=0+脚本无 provider/baseUrl 调用+报告 v2。W018 README 恢复章节升级（退出码表/PARTIAL 语义/映射语义）。W003 verify 脚本登记 C34。边界如实：真实 PG pg_restore、恢复后新栈只读打开工程/结果/版本链并在隔离副本做一次修改、旧软件读新 schema 兼容验证——属真实栈演练，归 C38；本卡如实未宣称 |
| C107F2-35 / REQ-F2-35 | 收口会话、资产、任务及导入临时文件的生命周期 | F08,F18,F19,F31 | C107F2-20、C107F2-22、C107F2-29、C107F2-30、C107F2-31 | W038、W039、W139、W105；完整见卡 | AC-F2-35-01～04 / TC-F2-35-01～04 | VERIFIED（2026-09-29 verify exit0：HypitFix2C35IT 4/4 + HypitProjectIT/HypitAssetIT/HypitFix2C20IT 回归 executed=24 passed=24 tcFound=8 + gradle 编译绿 + check-lifecycle-contracts 门禁绿。实锄 F08/F18/F19/F31 生命周期面真身=①删除对活跃任务 409 硬拒（用户得先手动取消全部任务才能删工程）；②sidecar workspace.delete 回执 failed 不检查——`.then()` 直接标 deleted 伪造清理完成（TC-03 断言逼出，实锄修复）；③V92 hypit_session 表未登记生命周期簿。W038 delete 流程重写为顺序语义：①revokeAllForOwner 撤属主全部活跃会话（access 即时拒绝）→②标 deleting（所有 owner 闸以非 ready 拒新写）→③cancelActiveForProject 就地取消（queued/running/waiting_input/cancel_requested→cancelled，W105 新方法）→④sidecar workspace.delete 清独占派生物——回执 state=failed 不收敛 deleted（502 hypit_engine_error，工程保持 deleting 可重试，稳定 commandId java-delete-<projectId> 幂等续跑）→⑤deleted。共享媒体零触碰（media_reference 与内容寻址资源文件不属于工程，TC-02 两工程同 media 删其一另一完好）；revision/输出 DB 行按既有保留策略留存。W105 增 cancelActiveForProject；W139/会话撤销矩阵复用 C107F2-20 revokeAllForOwner（access revoked 即拒）；W186 transfer.ts 增 purgeExpiredTransfers（24h TTL 回收未完成上传 staging/失败导入；失败留原地下一轮重试不静默吞；共享/计费媒体不在此面）+W025 60 秒周期 sweeper（unref 不持进程）；W185 Java 侧无状态消费（上传 TTL 由 broker 读取面 410+purge 承接）。W207 lifecycle registry 增 hypit_session（activeStates=starting/active、terminalEvidence 含 revoked、tc=本卡 IT）+W208 baseline 冻结 public.hypit_session（check-lifecycle-contracts 三违规定位后全清）；W210 PersonalDataErasureService 未消费（注销聚合清理已由既有批次覆盖 hypit_session 经 owner 作用域，写入集许可未用）。W090 useHypitProjectScope 账号 generation abort 为 C11 既有交付（登出/换账号 abort+清 generation），TC-04 服务端面锁 B 不能读 A 工程/删 A 工程 404 且 A 状态不变。HypitProjectIT 旧删除测试按 §13 契约变化同步（409 拒绝→取消后收敛）。边界如实：broker 60 秒内停止已撤会话进程的真实时序归 C38 演练；§8.8 截图归 C36 |
| C107F2-36 / REQ-F2-36 | 完成工作区双主题、移动端、键盘和全状态验收 | F06,F07,F14,F15,F20 | C107F2-18、C107F2-21、C107F2-23、C107F2-25、C107F2-27、C107F2-30、C107F2-31、C107F2-35 | W072、W092、W188、W211；完整见卡 | AC-F2-36-01～04 / TC-F2-36-01～04 | VERIFIED（2026-09-29 verify exit0：hypit-fix2-c36.spec 4/4 chromium 真实 UI（隔离栈 playwright 编排：守卫 up frontend/hypit-backend/runner/redis→sidecar healthz 探测→seed-accounts→同源口令登录）+ 根 tsc 绿。TC-01 合成复杂工程（120 字标题）两主题×三视口（390/834/1440）×四阶段 24 张全页截图（test-artifacts/task-107/fix2/C36/）+每步零整页横向溢出断言+data-theme 真实切换断言；TC-02 纯键盘（新建 Enter 提交进工程/弹窗初始焦点在 modal 内轮询断言/Tab 约束/Esc 在 persistent 弹窗的输入保护语义——AC 字面的「Esc 退出」与 GlModal persistent 既有设计（防误关丢稿）冲突，实现取保护语义并以取消按钮键盘路径补齐「键盘完成取消」+关闭后焦点返回触发按钮断言）；TC-03 定向失败注入（列表 503→错误态+重试入口且空态隐藏不伪空/上传 503→错误+已选文件保留+重试按钮）；TC-04 390px 长内容（120 字标题/255 字符文件名/200 字长错误：主按钮几何可达零越界/容器内截断/整页零横溢）。视觉判定：documents:visual-judge 实读 7 张代表性截图——5/7 pass；2 个 fail 均指向同一根因「skip-link 跳到主要内容在移动端截图上遮挡卡片」——经真实视口复核为 Chromium fullPage+position:fixed 已知采集伪影（.skip-link transform 藏于视口上方 rectTop=-61.7，仅 Tab 聚焦后落位 rectTop=8，evidence viewport-check/ 两张+DOM 计量），非真实用户可见缺陷；其余质量面（对比度/换行截断/工作区身份/对齐）全过。期间修真实基建缺口：hypit-compose.sh 隔离环境未生成/持久化 C19/C20 起 compose.test :? 硬性要求的 HYPIT_SESSION_TICKET_SECRET/HYPIT_SESSION_ASSERTION_SECRET（C08 时代入口先于该要求——跨门禁一次性密钥同法补齐，save 列表+setup 生成）；frontend 镜像经 local-stack 守卫重建以携带 C26–C35 工作区改动（构建中守卫按清单停了 4 个 aux 一次性/无依赖服务 database-bootstrap/dh-runtime/dh-redis/minio-init，数据卷保留，核心 14 服务未动）。遗留如实：TC-01 中两处「加载中」占位为合法加载态；Webkit/Firefox 矩阵归 C37 三引擎门禁 |
| C107F2-37 / REQ-F2-37 | 真实浏览器贯通参考素材到成片、Studio 与工程包 | F35 | C107F2-08、C107F2-18、C107F2-21、C107F2-23、C107F2-25、C107F2-27、C107F2-30、C107F2-31、C107F2-36 | W214、W215、W216、W217；完整见卡 | AC-F2-37-01～04 / TC-F2-37-01～04 | VERIFIED（2026-09-30 verify exit0：hypit-fix2-journey 4/4 chromium 真实浏览器贯通（守卫隔离栈+受控文本 fixture（治理台正式路由）+真实 Nginx/Edge/Java/broker/runner/PG/MinIO，原生编译渲染零 mock）+ 根 typecheck/vitest 54/54 绿。TC-01 全链：12s 参考 MP4 真实 XHR 上传→analyze（真 probe/frames+受控综合）→方案（planner→mutation.apply 真写回 revision 2）→源码 validated 保存（真实 changeset+runner check）→生成（原生渲染 3s 320×240 MP4）→归档→presigned 下载→ffprobe 时长 2–5s/尺寸/ftyp 可解码+帧锚点；TC-02 Studio iframe 真实加载重开零鉴权/CSP console 泄漏；TC-03 两变体构建收敛+503 定向注入→行内重试收敛（成功项不重做）；TC-04 A 导出 zip→B 登录 UI 导入新 owner 工程 ready。真栈调试净揭五真实缺陷并全修（ defect Z：HypitOutputRepository.completeArchive 对 composite/scalar 无 handle 的 bind(null) 抛 IllegalArgumentException——归档最后一步翻车且根因被吞（连带给 HypitArchiveService 补 WARN 留痕+doArchive 拆名降嵌套）；defect AA：ResultsPanel error 独占 v-if 链卸掉输出列表（同缺陷 T 形态）；defect AB：下载链接 href 指向 /api/media/{id} 元数据端点（返回 JSON 非字节）——改为签发短时 presigned downloadUrl（复用既有 KYB/画布模式，useHypitResults enrich+fail-soft）；defect Q2：缺陷 Q 修复自身 flatMap 完成序乱序——双文件 changeset 的 baseHash 交叉错配（main.svml 拿到 style.svs 哈希）→ author 链必 409、生成用骨架渲染 1s/540×960——concatMap 保序修复并活栈实证 author job 12s succeeded/revision 2/源码 320×240 落库；defect AC：useHypitSource.open 先置 activePath 后异步读基线——快速填存冻结 null baseHash 必 409，save 时基线缺失先读 head 补齐）。控制台卫生断言精确豁免两契约内响应（clone-plan 首挂 404 空态轮询/changeset apply 与 author 写回两相推进的 409 CAS 竞态——按 URL 豁免不全文模糊过滤，测试按产品「冲突→刷新基线→重存」动线重试）。证据：C107F2-37/results.json（exitCode 0）+ C37/ 目录（TC-F2-37-01-final.mp4、authored-main.svml、ffprobe 输出）。遗留如实：Webkit/Firefox 三引擎矩阵归 C39 门禁翻旗标后 V-11 全量重跑） |
| C107F2-38 / REQ-F2-38 | 执行并发、故障注入、重启与完整恢复演练 | F23,F26,F27,F32,F33,F34 | C107F2-15、C107F2-24、C107F2-26、C107F2-34、C107F2-35、C107F2-37 | W221、W222、W218、W003 | AC-F2-38-01～04 / TC-F2-38-01～04 | VERIFIED（2026-09-30 verify exit0 4/4（round18 收敛；HYPIT_COMPOSE_NO_BUILD=1）；证据 C107F2-38/results.json + C38/TC-F2-38-01～04/。TC-01 双worker执行中kill→租约接管、同operation单次收费、合法owner单终态；TC-02 fixture接受后断broker网络→恢复重启按原receipt收敛不新提交；TC-03 排空备份→新PG/新卷恢复栈→浏览器重开核验 hash一致零新generation且主栈原样（真实演练揭出七层缺陷链全修：①恢复卷残留→spec自清理按compose项目标签全量rm容器+卷、显式建卷必须--label且按名补删；②软删工程revision参与核对→restore SQL `WHERE p.status<>'deleted'`+skipped计数；③快照核验语义纠偏——`hypit_revision.snapshot_handle` 写侧三形态但核验恒以 `revisions/<n>` 为据点、manifest_hash 是 broker manifestHash() 的 canonical JSON sha256（非文件字节hash，W203 fixture 原发明语义已对齐生产并同步改 C33/C34 锚点，round-11 真实数据 271/271 实证）；④no-build 路径无显式 image: 的构建服务按 compose 项目名派生镜像名→恢复栈 up 前按恢复项目名补 tag；⑤AI 创作壳是独立 origin 18082，AI_BASE_URL 未注入回落 BASE 会在用户端登录成功但等不到工作台锚点；⑥维护租约随归档复活→backup manifest 记 leaseId+restore-report 透出+恢复栈 broker 就绪后 POST internal maintenance/exit（令牌容器内就地读不回传宿主）；⑦`apk add >/dev/null &&` 链吞瞬时 DNS 故障→专用退出码9+stderr报因。产品修复两处 defer 语义补全：HypitAgentStepService.runExisting 对 defer 类（hypit_maintenance/hypit_broker_unreachable）failed 行 CAS 重置 failed→prepared 重派发（原样回放=无限defer）；HypitSidecarUnreachableException+ToolRegistry 读类工具不可达终判 defer（一次30s连接超时不再打死job）。离线换 jar 重建镜像配方（daemon egress 污染时 host bootJar→docker cp→commit→双tag→unzip验哈希）见会话记录） |
| C107F2-39 / REQ-F2-39 | 重建分层验收门禁，禁止漏跑和假全绿 | F35 | C107F2-37、C107F2-38 | W223、W003、W219、W002、W224、W225、W226、W155 | AC-F2-39-01～04 / TC-F2-39-01～04 | VERIFIED（2026-09-30 verify exit0：executed=23 passed=23 tcFound=4（gates 4+compose 装配+entrypoint 拓扑回归）；证据 C107F2-39/results.json。W224 gates 契约四组在隔离证据副本（FIX2_ART_BASE 指向临时目录+FIX2_ALL_SKIP_CONTRACT=1 防递归，跳过时 G7 如实 NOT_RUN）上跑真实 stage all 脚本：TC-01 删 e2e/recovery 证据→非零且逐项列缺项绝无正向放行表述；TC-02 四故障注入（缺生成链TC-F2-37-01/坏outputs计数 executed=0/recovery零执行/缺数据库恢复证据）四次均被相应断言捕获；TC-03 必需spec含skip→非零不放行；TC-04 本地全绿→summary.json verdict=LOCAL_PASS、live=NOT_RUN、G7=NOT_RUN、无「全平台/全部通过/生产已上线/商用验证」越权表述。W223 full 入口重建五层分明（CONTRACT/LOCAL_NATIVE/BROWSER_E2E/RECOVERY/LIVE）：FIX2_LAYERS=1 才跑 fix2 分层、宿主 npm test 只构成 LOCAL_NATIVE、LIVE 恒 NOT_RUN（授权闸在 verify-107-live.sh）；W003 四 stage 旗标翻转（local/e2e/recovery/all 全部交付）+C39/C40 卡登记+e2e 层 results.json 恒写 exitCode0 假绿修复（改记 overall 实值）；W219 ci-e2e-107 必需 spec 补 journey+c36+会话密钥注入；W225 CI hypit-full-local 挂 FIX2_LAYERS=1+超时240+fix2 产物上传；W226/W155 装配契约（stage 脚本只经守卫入口禁裸 compose up/down、V-11 以产物判定、三入口拓扑不漂移）。V-11 判定原则：预期spec/TC发现数、exit code、failure/skip/NOT_RUN、产物可读性共同判定，必需阶段缺任一证据非零，不用日志关键词做事实源） |
| C107F2-40 / REQ-F2-40 | 复核 35 组缺陷闭合并完成任务级集成判定 | F01–35 | C107F2-01～39全部当前证据 | W227、W228、W229、W230；完整见卡 | AC-F2-40-01～04 / TC-F2-40-01～04 | VERIFIED（2026-10-01 任务级集成判定收口：V-07 card exit0 12/12 tcFound=5、V-09 e2e r6 三引擎 54/54（chromium/firefox/webkit 各18 全过；r4 的「三引擎同构 clone:33 漂移」复核为幻象——firefox/webkit 早退带走上引擎残留 junit，真跑仅 chromium）、V-10 recovery 4/4、V-11 all LOCAL_PASS（LIVE 如实 NOT_RUN）；证据 C107F2-40/ + e2e/results.json + recovery/results.json + all/summary.json。收口日六处修复：①FEEDBACK.json 从 author manifest 根级排除（上游评论真相自带 feedbackHash CAS，属 apply 外通道——provision→feedback.mutate→variants apply 曾永久 revision_conflict 502，clone:33 回归测试锁死）+漂移错误带 revisions/<n> 基线路径级差异诊断（~篡改/+新增/-删除点名，不再裸哈希报错）；②gl-field file input 定宽 width:100%——min-width:0 只设下限挡不住文件名内在宽参与父级 min-content（firefox 390px 实录 scrollWidth 3246），定宽后文件名原生裁剪，chromium/webkit 不受影响；③journey 37-04 登出后状态等待（firefox 时序登出未落定→登录按钮 resolved→detached 循环超时）；④e2e-seed upsertUser 口令哈希收敛——隔离栈 env 口令跨轮重生成而 postgres 卷持久，只建不更使复用卷的幂等阶段 admin 恒 401；⑤recovery 主 fixture netns 自愈——恢复演练重建主 intelligence 后旧容器 network_mode 引用悬空、docker restart 永起不来，restart 失败即按孪生同款 rm+重造挂回当前属主，并 describe beforeEach 开局自愈；⑥C38 baseline.txt 重开指纹随 2026-10-01 轮更新（其余五产物字节级复现实证原生渲染确定性）。环境事实登记：MinIO 已撤公共镜像（Docker Hub 仓库删除、quay 匿名 token actions 空），本地验收以 quay.m.daocloud.io 拉+retag 维持、compose 引用未改待拍板；daemon BuildKit 出网间歇污染（基础镜像预拉缓解）。LIVE/生产未验，不构成商用或上线证据） |


### 10.1 拆分与写入纪律

卡是可独立核验的结果。底层卡可用接口边界fixture验证自己的逻辑，但必须写明尚未完成的后续产品接线，不能宣称全链已通。修改共享文件时只碰本卡符号；C01建立contract基线，后续卡只落本书已冻结的对应API增量，C39/C40验证汇总。不得回填“先都通过”再实现。

### 10.2 卡间交接

每卡交付：代码符号/接口或配置、实际测试文件及TC ID、命令/退出码、当前基线及相关diff、可重读的PG/文件/媒体证据。下游先核对输入有效，不凭口头“已完成”。new文件必须由W表登记调用方实际import/注入/启动；未注册服务不算输出。文件hash/版本/owner同时提供，不传秘密。

### 10.3 阶段与出口

| 阶段 | 卡 | 出口 | 失败去向 |
|---|---|---|---|
| M1 可信部署与最小原生成片 | 01–08 | 配置一致、隔离真实、冻结/授权正确，API真MP4 | 回对应基础卡，不先接UI遮掩 |
| M2 制作工作区与Agent | 09–18 | 顶层可用性、保存/状态、分析/作者/生成真实动作 | 回DTO/worker/编排责任卡 |
| M3 原生编辑与审片 | 19–25 | Studio HTTP/WS/写回、Preview播放、评论原子修改 | 回session/安全/源码责任卡 |
| M4 变体与工程移植 | 26–31 | 每项正确终态，包下载/导入/素材交接可用 | 回variant/package/asset卡 |
| M5 维护与恢复 | 32–35 | 真排空、完整PG+文件、恢复可重开、权限清理 | 回维护/恢复/生命周期卡 |
| M6 真实交付 | 36–40 | 双主题三浏览器、故障注入、35项可追溯关闭 | 发现问题回原卡，重验受影响下游 |

M1不依赖M2–M6的UI可用性，先经真实API打通；M6不能仅引用M1的API结果代替用户按钮验收。不编造固定工期。

### 10.4 风险与验证前置

| 风险 | 严重度×概率×难检（1–5） | 最早反例 | 若不成立 |
|---|---|---|---|
| 闲置隔离容器掩盖实际broker执行 | 5×5×4=100 | TC04-01/02真实PID与越界 | C04不通过、禁止启用作者执行 |
| 无授权/重复Provider请求 | 5×4×5=100 | TC07-02/03/04 | 外部调用保持拒绝，修桥/原子预留 |
| 导入二进制或文件更新损坏 | 5×5×4=100 | TC10-02、TC28-01/03 | 不发布head，保留原文件 |
| Studio只签URL/签票越权 | 5×5×3=75 | TC19-01、TC20-01/03 | 不标ready、不降低CSP |
| 任务失败伪成功/lease重放 | 5×4×4=80 | TC14-02、TC15-01/02 | 成功判定/租约回到责任卡 |
| 恢复报告假干净 | 5×4×5=100 | TC34-01/02/03 | 非零阻断恢复放行 |
| 全绿未执行目标测试 | 5×5×5=125 | TC01-02/03、TC39全组 | 门禁fail-closed，状态保持IMPLEMENTED |

表中TC短写对应完整TC-F2-卡号-序号。评分是本书QA规划排序，不冒充事故发生统计。

## 11. 任务卡

统一执行包：本书v1.0.0；负责人和卡级验收人均为当前获授权执行者，无默认人工逐卡批准。每卡开工必须记录工作区状态、核对依赖VERIFIED及交付物，读§0/5/9/12/13/14和本卡精确契约。没有修改的已满足项可核验后复用，不为了显示活动重写。

统一边界：只改本卡W集合，禁止无关重构与门禁降级。所有新增测试必须真实发现TC ID；在后端卡中mock的只限明确下游边界，C08/C37/C38禁止替代本卡要证明的原生执行/持久化链。失败先修本卡普通实现问题；越界/缺必需环境按§13，不能交给后卡兜底当前正确性。


### 卡 C107F2-01：固化审计反例、跨层契约样本与分层验证入口

**执行包/类型**：v1.0.0；REQ-F2-01；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F35中本卡负责的行为。完成必须看到“文件 hash 可重现、六类旧行为均有失败断言；不调用 Provider”。本卡只声明部署/脚本及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：无依赖卡；用户后续授权本卡实施后可开始，先核对当前审计基线与正式源码。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `tests/deployment/hypit-fix2-c01.contract.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C01/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §6.1、§12.2–12.3。

**必读/只读**：§0/5/9/12/13/14；§6.1、§12.2–12.3；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W001, W002, W003, W004, W005, W006, W007, W008。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §6.1、§12.2–12.3，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 把审计中的六个前端失败反例与五类后端探针改为可从干净克隆重建的 fixture/正式测试输入；不依赖 test-artifacts 下未入库脚本。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 采集当前 Java Controller JSON、broker 文件响应、SSE 原始帧、Build lifecycle/outcome 作为有来源标记的契约样本；记录哪些是当前错误、哪些是目标断言，禁止用错误样本冻结缺陷。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 新建 verify-107-fix-2.sh 的固定 stage 路由与证据元数据；尚未实现的阶段必须退出 2 并列 NOT_RUN，禁止空函数 exit 0。fixtures.mjs 只生成合成 PNG/音频/视频/项目包与账号元数据。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 建立 35→40 映射检查、用例发现数量检查和跨层样本校验；首卡只验收反例可发现、门禁能失败，不把产品缺陷尚未修复计为首卡失败。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-01-01 / TC-F2-01-01 / C01-E01 | 干净临时目录、固定 seed=10702 | 生成媒体与六类请求样本；加载隔离反例 | 文件 hash 可重现、六类旧行为均有失败断言；不调用 Provider |
| AC-F2-01-02 / TC-F2-01-02 / C01-E02 | verify 仅有骨架 | 调用 --stage e2e | 退出 2/NOT_RUN，不能打印 ALL-GREEN |
| AC-F2-01-03 / TC-F2-01-03 / C01-E03 | 过滤器指向不存在测试 | 运行验证入口 | 非零且指出零用例，不能记录 PASS |
| AC-F2-01-04 / TC-F2-01-04 / C01-E04 | 35 组审计证据 | 校验 finding/card/TC/V 引用 | 35 组全覆盖，fixture 与实际边界区别明确 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-01`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-04、V-06，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：旧接口/样本基线不被错误冻结，原有测试结果保留供对比。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-01状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-02：统一 Compose 组合与启停路由，消除部署覆盖导致的 404

**执行包/类型**：v1.0.0；REQ-F2-02；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F01中本卡负责的行为。完成必须看到“projects=200、三入口存活，labels 中包含 Hypit overlay”。本卡只声明部署/脚本及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-01的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `tests/deployment/hypit-fix2-c02.contract.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C02/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §3 D-02、§6.1、§9.3。

**必读/只读**：§0/5/9/12/13/14；§3 D-02、§6.1、§9.3；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W009, W010, W011, W012, W013, W014, W015, W016, W017, W018, W019, W020。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §3 D-02、§6.1、§9.3，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 提供单一 hypit-compose.sh 包装入口，以 base→production→digital-human→hypit→可选 full 的固定次序构建配置；CI 隔离环境使用同一组合规则、专用覆盖。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 只在显式 --enable-hypit 模式启用 Hypit profile、Java enable/sidecar、Edge flags、Studio upstream；默认关闭仍保持 fail-closed。配置缺内部密钥或 Studio 密钥立即失败，不生成默认生产密钥。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 对共享服务的重建做“已有启用模块不得被静默移除”预检；可读取 compose labels 和白名单 env，但输出不包含令牌；显式 disable 才允许停用，须显示受影响模块。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 以隔离工程名重建三入口+DH+Hypit，执行登录态 projects GET 和未登录鉴权检查；生产命令只提供可审核计划，本卡不操作用户正在运行的 y-1 栈。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-02-01 / TC-F2-02-01 / C02-E01 | 隔离栈启用 DH/Hypit、合成 owner | 启动并访问三个入口及 projects | projects=200、三入口存活，labels 中包含 Hypit overlay |
| AC-F2-02-02 / TC-F2-02-02 / C02-E02 | 未传 --enable-hypit | 访问 capabilities/projects | capabilities 正确表示 disabled；业务路由 404，不误启动 broker |
| AC-F2-02-03 / TC-F2-02-03 / C02-E03 | 同工程已启用 Hypit | 以漏 Hypit 的组合重建共享 frontend/Edge | 预检非零，运行配置未被静默覆盖 |
| AC-F2-02-04 / TC-F2-02-04 / C02-E04 | 显式启用但 ticket secret 缺省/31字符 | 运行 config 预检 | 明确缺项且无容器变更；日志不含秘密 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-02`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-04、V-06，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-02状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-03：修正镜像目录、模板资源与运行期配置读取

**执行包/类型**：v1.0.0；REQ-F2-03；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F02、F03、F04中本卡负责的行为。完成必须看到“所有登记文件存在且可读，模板 provenance 与上游版本一致”。本卡只声明Node 集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-02的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-hypit/backend/tests/agent-integration/fix2-c03.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C03/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §3 D-03、§6.8。

**必读/只读**：§0/5/9/12/13/14；§3 D-03、§6.8；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W021, W022, W011, W023, W024, W025, W026, W027, W028, W029, W015, W030。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §3 D-03、§6.8，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | Dockerfile 显式 COPY fixtures/templates 并保持 upstream manifest 不变；固定 backendRoot=/app，模板根 /app/platform-hypit/fixtures，catalog 根 /app/platform-hypit/templates。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | loadConfig 读取文档列出的路径变量；source 文件根使用 import.meta.url 推导或已冻结配置，不从 generatedRoot 的父目录猜测 backendRoot。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 生产数据根维持 /data/hypit，明确其兄弟 projects/resources/runner-state/runner-tmp 的布局；挂载 /data 状态伞，禁止静默搬移既有卷。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 镜像内检查模板文件、catalog 引用、只读 rootfs 下目录权限、Node24 引擎；启动预检失败必须输出脱敏路径类别和原因，不能健康假阳性。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-03-01 / TC-F2-03-01 / C03-E01 | 新构建两镜像、只读 rootfs | 容器内读 fixtures/catalog 和入口 | 所有登记文件存在且可读，模板 provenance 与上游版本一致 |
| AC-F2-03-02 / TC-F2-03-02 / C03-E02 | socket=/sockets、slot=/slots | 调用 loadConfig | 返回指定值，不回退 dataRoot 内目录 |
| AC-F2-03-03 / TC-F2-03-03 / C03-E03 | 模板根不存在或 catalog 源路径越界 | 启动预检 | 非零且不创建 ready 工程 |
| AC-F2-03-04 / TC-F2-03-04 / C03-E04 | 隔离卷已有工程及 journal | 用新镜像启动并重读 | 原文件 hash 不变、路径可解析，无重置/迁移副作用 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-03`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-02，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-03状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-04：接通独立无网络 runner，消除 broker 内作者代码执行

**执行包/类型**：v1.0.0；REQ-F2-04；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F02中本卡负责的行为。完成必须看到“实际作者 PID 在 runner；broker 无作者执行进程”。本卡只声明Node 集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-03的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-hypit/backend/tests/agent-integration/fix2-c04.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C04/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §3 D-04、§6.8。

**必读/只读**：§0/5/9/12/13/14；§3 D-04、§6.8；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W031, W032, W033, W034, W035, W036, W025, W022, W023, W011, W012, W037。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §3 D-04、§6.8，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 把 supervisor 的作者 worker spawn 移至 runner 容器内可信 daemon；broker 仅准备受限 slot、通过 Unix socket 派发，不持有 Docker socket。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | daemon 使用协议 v1 的 request/response 外壳及 status/cancel，按 commandId 分配一次性执行子进程；每次仅放行自己的 input/output/scratch，输入只读，不给整个项目树权限。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | runner 使用 network_mode:none、非 root、read_only、cap_drop ALL、no-new-privileges；broker credential、PG 配置、其他工程根不挂载。固定容量 1，超额排队，完成清 slot 后再租下一项。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 实现握手、2秒探测、120秒单操作超时、10秒终止宽限与断线回收；runner 不可用明确失败，禁止 broker 回退执行。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-04-01 / TC-F2-04-01 / C04-E01 | Docker runner 在线、合成作者包 | 从 broker 发 check/compile 并检查进程所属容器 | 实际作者 PID 在 runner；broker 无作者执行进程 |
| AC-F2-04-02 / TC-F2-04-02 / C04-E02 | 作者程序尝试访问兄弟 slot、secret 路径、外网 | 依次执行三个单因子反例 | 分别被拒，输出不泄漏秘密；不是靠编译语法错误提前挡住 |
| AC-F2-04-03 / TC-F2-04-03 / C04-E03 | 一个 slot 执行中杀死 runner 子进程 | 等待 lease 回收后发新请求 | 旧调用明确失败、新请求可执行，容量不泄漏 |
| AC-F2-04-04 / TC-F2-04-04 / C04-E04 | runner 容器停止但 broker 在线 | 提交 compile | 失败且 broker spawn 计数为0，不假成功 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-04`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-02，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-04状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-05：按模板建工程并打通空工程首版初始化

**执行包/类型**：v1.0.0；REQ-F2-05；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F29中本卡负责的行为。完成必须看到“内容对应各自模板，不能都成为 minimal-local”。本卡只声明服务/契约集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-03、C107F2-04的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C05IT.java` 的四组TC；证据在 `test-artifacts/task-107/fix2/C05/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §5 RULE-03、§6.2。

**必读/只读**：§0/5/9/12/13/14；§5 RULE-03、§6.2；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W038, W039, W040, W041, W042, W043, W026, W044, W045, W046, W047。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §5 RULE-03、§6.2，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | template 模式使用真实 templateId，校验 catalog 的可克隆性、能力前置和 sourcePath，然后传递受控模板标识；不能把 templateId 仅放请求 hash。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | clone/brief 从本书登记的中性 blank 源码初始化 revision1；这是可编辑技术骨架，不能标记为已分析/已生成作品。旧 revision0 工程在首次明确打开制作动作时幂等初始化，同项目不复制。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 文件落盘与 PG project/revision/job/command 采用先 intent 后物化再收敛；receipt failed 不得 markReady；相同 requestId 返回同项目/同job。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 创建后 selectedRun 指向真实文件；ready 必须 head/manifest 一致；初始 API check 在真实 runner 通过。素材 sourceContext 实际移交交给 C31，本卡只保留经过归属验证的引用。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-05-01 / TC-F2-05-01 / C05-E01 | catalog 中两个不同模板 | 分别创建并读主文件 hash | 内容对应各自模板，不能都成为 minimal-local |
| AC-F2-05-02 / TC-F2-05-02 / C05-E02 | mode=clone/brief | 创建后读取文件、提交首个 Agent job | revision=1且合法源文件存在；Agent 不因 revision0 拒绝 |
| AC-F2-05-03 / TC-F2-05-03 / C05-E03 | 同 requestId 两并发；另一次 broker 失败 | 创建并刷新 | 同键仅一个工程；失败为 provisioning_failed，不假 ready |
| AC-F2-05-04 / TC-F2-05-04 / C05-E04 | 旧工程 revision0、无 head | 两客户端同时触发初始化 | 仅一个 revision1，原 owner/title/sourceContext 不变 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-05`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-03，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-05状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-06：统一源码根并冻结计划、价格、Profile 和构建快照

**执行包/类型**：v1.0.0；REQ-F2-06；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F25、F26中本卡负责的行为。完成必须看到“三个调用通过真实 broker/runner，不要求客户端 sourceDir”。本卡只声明服务/契约集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-04、C107F2-05的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C06IT.java` 的四组TC；证据在 `test-artifacts/task-107/fix2/C06/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §5 RULE-04、§6.3、§6.8。

**必读/只读**：§0/5/9/12/13/14；§5 RULE-04、§6.3、§6.8；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W048, W049, W050, W025, W026, W051, W052, W053, W054, W055, W056。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §5 RULE-04、§6.3、§6.8，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | Java 使用 projectId/revision/runFile；broker 将授权项目解析为不可变 revision 快照的作者源码根，不接受浏览器传宿主 sourceDir。内部已有 sourceDir 仅可信 fixture/工具入口使用。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 修复 runPlan/runPricing 解析 workspaceRoot 却复制 undefined sourceDir；统一 check/compile/render 的 work 层级和依赖素材重定位。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | Profile 使用规范化有效配置摘要，凭据仅记录受控配置版本/引用，不把秘密混入 hash 输出；plan/pricing/build 绑定同一 revision、manifestHash、profileHash、runFile。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 提交后即便工程变更，已受理 build 使用已冻结快照；提交前 plan 过时返回409；pricing 不得对当前新文件重新计划冒充旧报价。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-06-01 / TC-F2-06-01 / C06-E01 | 仅 projectId/revision/runFile 的正式请求 | 执行 check→plan→pricing | 三个调用通过真实 broker/runner，不要求客户端 sourceDir |
| AC-F2-06-02 / TC-F2-06-02 / C06-E02 | A版已提交而未执行 | 改成B版后释放runner | 产物体现A版且receipt绑定A revision |
| AC-F2-06-03 / TC-F2-06-03 / C06-E03 | 计划后变更受控模型配置版本 | submit 旧计划 | 409 stale，零Provider调用、零新增有效Build |
| AC-F2-06-04 / TC-F2-06-04 / C06-E04 | revision存在PG但目录缺失/manifest篡改 | plan/submit | 明确失败且不回退当前work |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-06`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-03，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-06状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-07：安装真实 Provider 授权桥并锁定预算、范围与幂等

**执行包/类型**：v1.0.0；REQ-F2-07；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F27中本卡负责的行为。完成必须看到“prepare/submit/receipt逐段可追踪，operationId唯一”。本卡只声明服务/契约集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-06的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C07IT.java` 的四组TC；证据在 `test-artifacts/task-107/fix2/C07/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §5 RULE-05、§6.3、§7.4。

**必读/只读**：§0/5/9/12/13/14；§5 RULE-05、§6.3、§7.4；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W057, W058, W054, W059, W026, W060, W061, W048, W050, W062, W063, W064, W065, W066, W067, W029, W030。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §5 RULE-05、§6.3、§7.4，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 把 ExecutionAuthorizer/withAuthorizationGuard 安装到可信 endpoint 的真实调用入口；必要的原生 hook 只通过补丁物化，不编辑 upstream；build payload 传受控 grantId 与 frozen binding。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 以 executionTargets ⊆ grantedTargets 为必要条件；远程计划空 targets 无授权效力，unknown cost 只有 allowUnknown 且金额上限已明确才可执行。检查 owner/project/plan/pricing/expiry/revocation/currency/variantCount。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 每个外部 operationId 在准备时持久化并原子预留预算；多 worker 并发共享同 grant 不能超额。用既有执行/积分服务记 ai_run 和 receipt，不新增价格或直写 finance 表。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 提交结果未知时进入 unknown 并查询凭证，禁止换 ID 再次执行；revoked/expired 禁止新 side effect，已发生调用的结算按真实 receipt 而非UI取消推断。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-07-01 / TC-F2-07-01 / C07-E01 | 真实Java+broker授权链、外部Provider fixture | 原生runtime请求一个Need | prepare/submit/receipt逐段可追踪，operationId唯一 |
| AC-F2-07-02 / TC-F2-07-02 / C07-E02 | 计划targets=[a,b]、grant=[a]；单独再测[] | 执行 b | 授权阶段拒绝，Provider调用计数0 |
| AC-F2-07-03 / TC-F2-07-03 / C07-E03 | grant上限1.000000、两个0.700000请求同时过屏障 | 并发prepare | 最多一个获准，总预留≤1.000000 |
| AC-F2-07-04 / TC-F2-07-04 / C07-E04 | Provider已接受但broker丢响应 | 重启并重放同operationId | 查询原receipt、不产生第二次提交/费用 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-07`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-03，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-07状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-08：验收第一条真实 Docker 原生成片纵向链路

**执行包/类型**：v1.0.0；REQ-F2-08；集成验收；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F02、F03、F25、F26、F27、F35中本卡负责的行为。完成必须看到“原生引擎产出MP4、时长3秒±1帧、三帧内容符合锚点”。本卡只声明浏览器 E2E及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-02、C107F2-03、C107F2-04、C107F2-05、C107F2-06、C107F2-07的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `tests/e2e/hypit-fix2-api-render.spec.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C08/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §4 S1、§12.5 G1。

**必读/只读**：§0/5/9/12/13/14；§4 S1、§12.5 G1；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W003, W004, W012, W068。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §4 S1、§12.5 G1，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 建立独立 Compose 项目 y1-hypit-fix2-e2e，合成 owner 经真实 Edge 登录；不使用用户现有 y-1 容器、数据库或卷。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 调用真实创建/源码修改/check/plan/pricing/build API，使用无远程生成需求的本地素材与原生 renderer；跟踪 job、Build 与结果索引直到终态。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 下载实际 MP4，ffprobe 验证容器/视频流/时长/尺寸，解码首中末帧并与fixture中的内容锚点对应；必须有非空、非全黑帧。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 保存跨服务 trace、冻结revision、engineBuildId、文件hash、帧和DB重读；此卡只证明API纵向链，不能宣称UI按钮、Agent或Studio完成。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-08-01 / TC-F2-08-01 / C08-E01 | 合成三秒双色移动元素工程 | 经真实API提交并下载解码 | 原生引擎产出MP4、时长3秒±1帧、三帧内容符合锚点 |
| AC-F2-08-02 / TC-F2-08-02 / C08-E02 | 生成完成后关闭客户端 | 重开查询相同buildId | 结果仍可发现和下载，不重新生成 |
| AC-F2-08-03 / TC-F2-08-03 / C08-E03 | 有效buildId属于owner A，owner B登录 | 读结果和下载 | 404，不能拿到字节；A仍可读取 |
| AC-F2-08-04 / TC-F2-08-04 / C08-E04 | 外部网络/凭据不可用 | 执行本地渲染链 | 仍成功，外部Provider调用计数0 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-08`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-08，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-08状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-09：真实 readiness 与工作区顶层登录、禁用、不可用状态

**执行包/类型**：v1.0.0；REQ-F2-09；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F05、F15中本卡负责的行为。完成必须看到“显示未启用，projects请求0，不出现无限加载”。本卡只声明组件及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-08的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `src/views/video-clone/composables/fix2-c09.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C09/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §6.1、§8.3。

**必读/只读**：§0/5/9/12/13/14；§6.1、§8.3；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W025, W024, W069, W070, W071, W072, W073, W074, W075, W076, W077, W029。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §6.1、§8.3，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 从 upstream manifest 读取实际版本；健康分 liveness 与 feature readiness，覆盖模板、runner握手、配置、程序/浏览器状态，后续Studio/Preview接线卡再提供真实探测。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 工作区 mount 先解析身份与capabilities；disabled/unavailable 时不发projects请求、不显示可执行创建按钮，展示具体原因和operator可用的处理指引。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 新 composable useHypitRuntime 管理状态/重试/账号世代，视图只装配；普通owner仅看脱敏原因，运维路径和秘密不下发。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 功能恢复后重试进入正常列表；真实404不能全部解释为disabled，需结合capabilities，工程不存在与服务未开分开显示。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-09-01 / TC-F2-09-01 / C09-E01 | 登录owner、capabilities.enabled=false | 打开工作区 | 显示未启用，projects请求0，不出现无限加载 |
| AC-F2-09-02 / TC-F2-09-02 / C09-E02 | 镜像为manifest0.2.16 | 读capabilities/doctor | 返回0.2.16；runner离线对应feature.ready=false |
| AC-F2-09-03 / TC-F2-09-03 / C09-E03 | 列表已加载后登录过期 | 刷新或重试 | 复用既有登录入口、旧账号数据清空，不显示成功空列表 |
| AC-F2-09-04 / TC-F2-09-04 / C09-E04 | 初次broker不可达，随后恢复 | 点击重试 | 重新探测后只拉取一次列表，页面可操作 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-09`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-04、V-05，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI按§8.8保留本卡受影响页面的亮暗截图并实际查看，最终C36补全视口矩阵。

**完成后**：按§14在对话记录C107F2-09状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-10：统一文件 hash 契约与版本化保存，保护在途输入

**执行包/类型**：v1.0.0；REQ-F2-10；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F09中本卡负责的行为。完成必须看到“两次baseHash分别A/B，最终磁盘C”。本卡只声明组件及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-05、C107F2-06的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `src/views/video-clone/composables/fix2-c10.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C10/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §5 RULE-06、§6.4。

**必读/只读**：§0/5/9/12/13/14；§5 RULE-06、§6.4；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W078, W079, W080, W076, W075, W081, W082, W083。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §5 RULE-06、§6.4，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 文件GET正式字段使用hash/revision，前端把返回hash作为下一次change.baseHash；保留旧baseHash别名一个兼容周期，只读hash为权威。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 提交时冻结project/path/content/revision/hash与编辑世代；响应只确认该次提交内容，保存途中输入保留dirty，不能savedContent取当前可变draft。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 成功后获得或重读目标文件新hash，连续第二次保存正确；409保留草稿并提供刷新基线/显式再应用，禁止自动覆盖新head。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 新增文件用baseHash=null表示必须不存在；删除用当前hash；二进制走工程包/资源路径，不进入文本changeset限制。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-10-01 / TC-F2-10-01 / C10-E01 | hash=A的有效源码 | 保存B再保存C | 两次baseHash分别A/B，最终磁盘C |
| AC-F2-10-02 / TC-F2-10-02 / C10-E02 | 提交B请求被屏障暂停 | 继续输入C后释放B响应 | savedContent=B、draft=C、dirty=true |
| AC-F2-10-03 / TC-F2-10-03 / C10-E03 | 两个客户端同revision | A保存后B保存 | B得到409并保留草稿，无静默覆盖 |
| AC-F2-10-04 / TC-F2-10-04 / C10-E04 | 真实broker响应含hash/revision | 前端读取并提交 | 请求baseHash非undefined，缺必需字段显式契约错误 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-10`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-04、V-05，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI按§8.8保留本卡受影响页面的亮暗截图并实际查看，最终C36补全视口矩阵。

**完成后**：按§14在对话记录C107F2-10状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-11：工程深链、分页、创建导航和跨项目请求世代隔离

**执行包/类型**：v1.0.0；REQ-F2-11；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F07、F08中本卡负责的行为。完成必须看到“正确GET该工程并打开，不停在加载文案”。本卡只声明组件及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-09、C107F2-10的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `src/views/video-clone/composables/fix2-c11.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C11/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §4.3–4.4、§5 RULE-07、§8.4。

**必读/只读**：§0/5/9/12/13/14；§4.3–4.4、§5 RULE-07、§8.4；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W072, W084, W085, W081, W086, W087, W088, W089, W090, W091, W092, W093, W075, W039, W038, W080, W001。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §4.3–4.4、§5 RULE-07、§8.4，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | activeProject 通过getProject(id)独立加载，不依赖前50列表；列表实现cursor追加、stable去重，缺工程展示404可返回列表。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 创建成功导航 /video-clone/{id}；provisioning每2秒刷新，最多60次后显示仍处理中及继续查询入口，不把超时写成服务端失败。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 新useHypitProjectScope提供账号+项目generation与AbortController；切换后旧success/error/finally均不能改新状态；ReferencePanel监听project.id。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 未保存草稿切换使用既有GlModal确认；取消切换不动URL；确认离开才清源文件/评论/Studio/Preview和订阅，已运行服务端job仍可恢复。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-11-01 / TC-F2-11-01 / C11-E01 | 工程在第51条 | 直接访问其URL后刷新 | 正确GET该工程并打开，不停在加载文案 |
| AC-F2-11-02 / TC-F2-11-02 / C11-E02 | A请求延迟、B先返回 | A→B→A，按受控顺序释放响应 | 只有当前generation生效，旧finally不结束当前loading |
| AC-F2-11-03 / TC-F2-11-03 / C11-E03 | 创建返回provisioning | 确认后刷新地址栏 | URL包含新ID、轮询至ready、刷新可继续 |
| AC-F2-11-04 / TC-F2-11-04 / C11-E04 | A有未保存内容 | 切B先取消再确认 | 取消留A草稿；确认后B不带A草稿/会话 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-11`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-04、V-05，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI按§8.8保留本卡受影响页面的亮暗截图并实际查看，最终C36补全视口矩阵。

**完成后**：按§14在对话记录C107F2-11状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-12：统一 Build/Outputs DTO 与真实归档、结果消费

**执行包/类型**：v1.0.0；REQ-F2-12；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F11、F12中本卡负责的行为。完成必须看到“items始终数组，完整字段可显示，无undefined.length”。本卡只声明服务/契约集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-08、C107F2-10的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C12IT.java` 的四组TC；证据在 `test-artifacts/task-107/fix2/C12/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §6.5。

**必读/只读**：§0/5/9/12/13/14；§6.5；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W094, W071, W095, W096, W097, W075, W087, W098, W076, W001, W099。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §6.5，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 按§6.5提供items/nextCursor列表字段，同时保留现有outputs/build/planSnapshot兼容字段；items与outputs同内容，消费层只使用正式items。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 补齐HypitDtos.Build/Output实际声明字段，unknown用null，不拿空串/0冒充已知值；runFile、createdAt、resultReady/outcome与原生事实一致。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 归档调用POST /builds/{id}/archive，传outputNames而非outputId或action=archive；重复请求幂等复用已归档media，不重复固化。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 结果下载按媒体二进制契约处理；UI在refresh后仍能读取归档状态，失败保留输出列表和单项重试。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-12-01 / TC-F2-12-01 / C12-E01 | Java真实序列化OutputList | ResultsPanel加载并选择build | items始终数组，完整字段可显示，无undefined.length |
| AC-F2-12-02 / TC-F2-12-02 / C12-E02 | build有final和poster两个output | 选择final归档 | 调用正确endpoint/body，仅final归档 |
| AC-F2-12-03 / TC-F2-12-03 / C12-E03 | 同requestId两并发 | 执行归档后刷新 | 同mediaId、无重复媒体/费用 |
| AC-F2-12-04 / TC-F2-12-04 / C12-E04 | outputs=[]；size/duration未知 | 加载结果 | 空态与null正常展示，不显示虚假0秒或归档成功 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-12`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-03，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI按§8.8保留本卡受影响页面的亮暗截图并实际查看，最终C36补全视口矩阵。

**完成后**：按§14在对话记录C107F2-12状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-13：统一 SSE 事件协议、增量游标与工作区恢复

**执行包/类型**：v1.0.0；REQ-F2-13；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F24、F06中本卡负责的行为。完成必须看到“phase/output/job终态正确更新，named event不丢”。本卡只声明服务/契约集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-08、C107F2-11的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C13IT.java` 的四组TC；证据在 `test-artifacts/task-107/fix2/C13/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §6.6。

**必读/只读**：§0/5/9/12/13/14；§6.6；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W100, W101, W102, W071, W103, W075, W084, W072, W076, W104, W105, W001。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §6.6，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 采用HypitSseEvent完整envelope和named events，前端注册允许的event类型；eventId正式使用jobUuid:sequence，兼容旧evt-job-sequence解析但验证job归属。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 后端每订阅维护递增cursor，以1秒增量查询、15秒heartbeat、每批最多100条；无新事件不重复发送历史数据，terminal发送后停止订阅。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 客户端使用可设置Last-Event-ID的fetch流解析，保留多行/半包帧；断线按1/2/4/8/15秒退避最多5次，之后显示手动重连。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 创建/恢复job都调用watchJob；URL job恢复先GET权威快照再续流；重复sequence忽略，切工程close只断观察，不冒充取消。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-13-01 / TC-F2-13-01 / C13-E01 | 真实Java进度/terminal帧 | 前端订阅消费 | phase/output/job终态正确更新，named event不丢 |
| AC-F2-13-02 / TC-F2-13-02 / C13-E02 | 运行中job无新事件 | 观测10秒 | 每订阅增量查询≤11次，不忙循环，不重发旧帧 |
| AC-F2-13-03 / TC-F2-13-03 / C13-E03 | 已收到sequence7 | 断线后带7重连，注入重复7/新8 | 7不重复展示、8接收，terminal后停止 |
| AC-F2-13-04 / TC-F2-13-04 / C13-E04 | 旧evt格式与跨job伪造cursor各一次 | 请求流 | 有效旧cursor可解析；跨job重置快照且不泄漏 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-13`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-03，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI按§8.8保留本卡受影响页面的亮暗截图并实际查看，最终C36补全视口矩阵。

**完成后**：按§14在对话记录C107F2-13状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-14：落地 Agent 工具分发、逐步执行与真实成功判定

**执行包/类型**：v1.0.0；REQ-F2-14；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F21中本卡负责的行为。完成必须看到“都命中真实handler，无默认501；参数类型保真”。本卡只声明服务/契约集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-06、C107F2-07、C107F2-13的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C14IT.java` 的四组TC；证据在 `test-artifacts/task-107/fix2/C14/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §5 RULE-08、§6.7。

**必读/只读**：§0/5/9/12/13/14；§5 RULE-08、§6.7；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W106, W107, W108, W109, W110, W079, W111, W112, W113。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §5 RULE-08、§6.7，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 新增AgentToolRegistry作为allowedTools、输入schema、handler的唯一注册表；将现有knowledge/build/mutation/packages/snapshot/feedback能力接到既有服务，未实现工具不进planner可见集合。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 保留JSON原始number/boolean/object/array类型，不用asText把所有参数字符串化；逐工具验证scope和输入，工具失败返回结构化结果。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | worker改为plan→执行一个动作→持久观察→是否达目标→继续；成功要求目标产物/动作验收通过，不以“actions循环结束”收口。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 任一必要动作失败不能job succeeded；可修复错误进入有界replan，缺资料进入waiting_input，安全/权限拒绝终止。依赖Studio/Review的工具在后续卡接线验证前不对用户宣称ready。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-14-01 / TC-F2-14-01 / C14-E01 | registry与planner scope | 枚举每个宣称工具并dispatch | 都命中真实handler，无默认501；参数类型保真 |
| AC-F2-14-02 / TC-F2-14-02 / C14-E02 | 唯一必要mutation返回校验失败 | 执行job | job非succeeded，有明确failed或等待状态及原因 |
| AC-F2-14-03 / TC-F2-14-03 / C14-E03 | 首动作产出诊断、次动作修复 | 执行受控planner序列 | 第二轮输入包含真实观察，最多40个实际动作 |
| AC-F2-14-04 / TC-F2-14-04 / C14-E04 | 合法输入但工具不在scope | 直接绕前端dispatch | 拒绝且业务service调用0，不消耗外部执行 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-14`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-03，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-14状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-15：修复 Agent 租约、操作幂等、取消及 resume

**执行包/类型**：v1.0.0；REQ-F2-15；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F23中本卡负责的行为。完成必须看到“只一个执行权，planner调用1次”。本卡只声明服务/契约集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-14的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C15IT.java` 的四组TC；证据在 `test-artifacts/task-107/fix2/C15/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §5 RULE-09、§7.4。

**必读/只读**：§0/5/9/12/13/14；§5 RULE-09、§7.4；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W108, W107, W042, W105, W114, W115, W101, W116。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §5 RULE-09、§7.4，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 租约90秒、每15秒续租；lease_owner为每次认领的随机执行token，所有checkpoint/状态更新都以owner/version/非终态条件CAS，旧worker失权后不能写结果。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 副作用前持久prepared action及稳定operation_id，先读已有成功/unknown回执再决定执行；相同slot但inputHash不同明确冲突，不复用旧结果。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 瞬时planner连接错误最多重试2次、退避1/2秒并记attempt；未知外部提交走查询，禁止无限重排产生新费用。单轮最大40动作，总累计120；超限终止而非无限resume。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 每步前后检查cancel_requested；resume消费inputs解除具体blockedReason，进入新规划轮次，保留原scope且不自动扩大预算。terminal事件与状态更新同事务，终态不可覆盖。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-15-01 / TC-F2-15-01 / C15-E01 | 执行持续70秒，两worker | 续租并过45秒旧阈值 | 只一个执行权，planner调用1次 |
| AC-F2-15-02 / TC-F2-15-02 / C15-E02 | prepared已落、下游接受、action回执未落 | 重启接管 | 沿同operationId查回执，不重复副作用 |
| AC-F2-15-03 / TC-F2-15-03 / C15-E03 | 最终动作完成但terminal CAS前暂停 | 发cancel再释放 | 最终不被succeeded覆盖，terminal序列一致 |
| AC-F2-15-04 / TC-F2-15-04 / C15-E04 | waiting_input缺assetId，用户补合法ID | resume两次同requestId | 只恢复一次、消费新输入继续；scope不扩大 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-15`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-03，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-15状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-16：接通全片参考分析、转写与时间锚点

**执行包/类型**：v1.0.0；REQ-F2-16；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F22、F06中本卡负责的行为。完成必须看到“三段全覆盖、锚点与实际帧一致，结果可刷新重读”。本卡只声明服务/契约集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-14、C107F2-15的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C16IT.java` 的四组TC；证据在 `test-artifacts/task-107/fix2/C16/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §5 RULE-10、§6.7。

**必读/只读**：§0/5/9/12/13/14；§5 RULE-10、§6.7；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W117, W118, W107, W108, W110, W080, W119, W120。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §5 RULE-10、§6.7，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | intent=analyze调用真实资产读取/媒体probe/抽帧/音轨提取工具，转写和视觉分析使用平台既有能力执行入口；不接受浏览器自报“全片分析完成”作为事实。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 覆盖从0到duration的分段时间轴；每段持有原素材hash、起止、画面/字幕/音频证据，持续系统与变化点引用真实时间锚点。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 缺转写/视觉能力返回waiting_input或明确能力未就绪，保留已完成部分并标PARTIAL；无声视频允许无转写但必须证据明确音轨不存在。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 分析结果通过既有工程分析接口持久化；换源素材/版本使旧分析不可直接用于新的可执行计划。fixture只替外部模型响应，抽帧/probe/持久化仍真实。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-16-01 / TC-F2-16-01 / C16-E01 | 合成12秒视频，0/4/8秒三段、含音轨 | 点击分析对应agent intent | 三段全覆盖、锚点与实际帧一致，结果可刷新重读 |
| AC-F2-16-02 / TC-F2-16-02 / C16-E02 | 视频合法但视觉配置缺失 | 发analyze | 明确未完成/等待，不返回全片ready |
| AC-F2-16-03 / TC-F2-16-03 / C16-E03 | 无音轨视频；另一个只有前4秒分析返回 | 分别执行 | 无声不伪造台词；截断分析PARTIAL阻止直接生成 |
| AC-F2-16-04 / TC-F2-16-04 / C16-E04 | A分析已完成，资产换B hash | 请求生成方案 | 旧分析被判失配，需要重新分析 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-16`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-03，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-16状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-17：把复刻方案转换为有内容的源码与材料需求

**执行包/类型**：v1.0.0；REQ-F2-17；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F22、F29中本卡负责的行为。完成必须看到“字幕/时序/素材选择实际不同，非仅注释差异”。本卡只声明服务/契约集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-16、C107F2-06、C107F2-15的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C17IT.java` 的四组TC；证据在 `test-artifacts/task-107/fix2/C17/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §5 RULE-10、§6.7。

**必读/只读**：§0/5/9/12/13/14；§5 RULE-10、§6.7；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W121, W122, W123, W124, W107, W108, W125, W126, W127。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §5 RULE-10、§6.7，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | intent=author读取已完成分析/brief、知识surface、已有源码和材料图；经既有FrozenTextExecutionService生成结构化changeset，输出不能执行shell或指定任意宿主路径。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 方案每步绑定systemId/时间段/资产或授权Need，缺项waiting_input；跨步骤共享recipe而不复制分叉，保持原生SVML/SVS/Run结构。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 生成内容必须实际使用标题/字幕/画面顺序/持续时间/音频，不再只把steps写注释并输出固定2秒模板；受控编译诊断最多两轮修复，然后明确失败保留草稿。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | validated changeset通过后原子应用并创建新revision，计划与材料需求可追溯；自动生成不自动批准费用。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-17-01 / TC-F2-17-01 / C17-E01 | 两个不同brief/参考分析、受控外部响应 | 各生成源码并原生渲染 | 字幕/时序/素材选择实际不同，非仅注释差异 |
| AC-F2-17-02 / TC-F2-17-02 / C17-E02 | 第一轮生成无效引用、第二轮合法 | 执行author | 使用诊断修复后才apply；失败稿不覆盖head |
| AC-F2-17-03 / TC-F2-17-03 / C17-E03 | 方案含未绑定必要asset | 请求执行方案 | waiting_input指出缺项、零构建/收费 |
| AC-F2-17-04 / TC-F2-17-04 / C17-E04 | 模型输出越界文件路径或shell工具 | 校验changeset | 拒绝并保留原源码，无外部命令执行 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-17`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-03，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-17状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-18：接通分析、方案、生成、重试、取消与授权 UI

**执行包/类型**：v1.0.0；REQ-F2-18；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F06中本卡负责的行为。完成必须看到“POST plan/pricing/build实际发生并显示job，不只有GET”。本卡只声明组件及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-11、C107F2-12、C107F2-13、C107F2-15、C107F2-16、C107F2-17的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `src/views/video-clone/composables/fix2-c18.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C18/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §8.3–8.4、§6.3、§6.7。

**必读/只读**：§0/5/9/12/13/14；§8.3–8.4、§6.3、§6.7；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W072, W128, W086, W103, W075, W091, W129, W130, W131, W132, W133。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §8.3–8.4、§6.3、§6.7，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 把分析/重新生成方案/按方案生成分别映射analyze/author及check→plan→pricing→授权→submit，视图仅组合useHypitWorkflow，不再void0或只切页签。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 无远程Need可本地提交；含远程Need弹授权对话框列范围、货币、已知/未知价格、最大金额、变体数，用户显式确认后才grant/submit。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | build/retry/cancel调用各自真实API；accepted后记录jobId到URL并订阅；错误保留当前源数据/输入，重试使用稳定requestId，参数变更才新键。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 取消显示取消申请中并继续观察服务端终态，不能把stop SSE当取消；连点只发一个在途动作。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-18-01 / TC-F2-18-01 / C18-E01 | ready工程、本地计划 | 点击生成 | POST plan/pricing/build实际发生并显示job，不只有GET |
| AC-F2-18-02 / TC-F2-18-02 / C18-E02 | 远程Need有价格 | 点击生成再取消授权对话框 | grant/build/Provider调用均0；确认后才一次提交 |
| AC-F2-18-03 / TC-F2-18-03 / C18-E03 | 服务端job运行中 | 点击取消 | cancel API调用一次，继续观察至cancelled |
| AC-F2-18-04 / TC-F2-18-04 / C18-E04 | 提交响应超时但服务端受理 | 刷新并按同requestId重试 | 恢复同job/build，不新建重复任务 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-18`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-04、V-05，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI按§8.8保留本卡受影响页面的亮暗截图并实际查看，最终C36补全视口矩阵。

**完成后**：按§14在对话记录C107F2-18状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-19：启动原生 Studio 并接通 HTTP、WebSocket 与写回

**执行包/类型**：v1.0.0；REQ-F2-19；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F16中本卡负责的行为。完成必须看到“资源200、WS101、出现原生编辑器目标控件”。本卡只声明Node 集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-04、C107F2-10、C107F2-15的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-hypit/backend/tests/agent-integration/fix2-c19.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C19/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §3 D-08、§6.9、§7.1。

**必读/只读**：§0/5/9/12/13/14；§3 D-08、§6.9、§7.1；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W134, W135, W136, W137, W026, W025, W138, W139, W140, W141, W142, W143, W144, W061, W145, W001, W146, W147, W009, W011, W012, W029, W035, W032, W051, W030, W148, W149。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §3 D-08、§6.9、§7.1，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 修正launcher为上游实际入口并等待真实ready探测，启动失败/退出正确reject，禁止Promise立即返回伪pid；运行只加载可信Studio服务，作者编译/预览走runner边界。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 创建会话先登记PG session，再启动与workspace/revision绑定的实例；挂载HTTP静态资源与固定WS路径，禁止代理任意host/path；不让全局internal bearer挡住合法签票入口。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 写回经mutation-bridge到Java既有changeset/apply，带baseRevision/baseHash和owner；Studio不能直写绕过项目版本控制。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 新session access Controller供Nginx auth_request 经Edge验证站内会话/owner/expiry/revocation，并签短期内部断言；本卡完成原生页面HTTP/WS/一次保存，下卡补票据复用和异常矩阵。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-19-01 / TC-F2-19-01 / C19-E01 | 已有有效工程、真实Studio进程 | 创建会话并GET页面/JS/CSS/WS | 资源200、WS101、出现原生编辑器目标控件 |
| AC-F2-19-02 / TC-F2-19-02 / C19-E02 | 在Studio改一个可定位文本属性 | 保存后读文件/PG revision | 新revision及hash一致，刷新仍保留 |
| AC-F2-19-03 / TC-F2-19-03 / C19-E03 | Studio入口不可读/端口不可用 | open会话 | 失败回执且不留下active假会话/僵尸子进程 |
| AC-F2-19-04 / TC-F2-19-04 / C19-E04 | Studio操作触发作者代码求值 | 观察实际运行位置 | 作者逻辑在runner，无broker秘密与外网权限 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-19`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-02，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-19状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-20：修正 Studio 票据、只读、版本复用及撤销生命周期

**执行包/类型**：v1.0.0；REQ-F2-20；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F19中本卡负责的行为。完成必须看到“不同session，返回rev2/readOnly=true”。本卡只声明Node 集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-19的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-hypit/backend/tests/agent-integration/fix2-c20.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C20/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §6.9、§7.1。

**必读/只读**：§0/5/9/12/13/14；§6.9、§7.1；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W137, W150, W134, W025, W138, W139, W140, W141, W075, W151, W152, W072, W001, W153, W147, W009, W011, W012, W148。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §6.9、§7.1，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 复用键包含owner/project/run/revision/readOnly；任何权限或版本变化均新会话，不能复用旧可写实例。每张票据独立nonce、60秒有效且一次核销，不以session ticketUsed阻断后续新票据。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 关闭/过期/工程删除/owner注销同步撤销PG与broker，终止受管进程和WS；进程重启不承诺恢复旧一次性URL，UI明确会话失效并可新建。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 只读不只禁按钮：HTTP写、WS mutation、bridge apply均服务端拒绝；会话绝对TTL=3600秒，核销不无限延长。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 工作区通过useHypitStudio按generation接收回执；切工程旧会话关闭/不渲染，不把旧ticketUrl带入新工程。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-20-01 / TC-F2-20-01 / C20-E01 | rev1 writable已开 | 请求rev2 readonly | 不同session，返回rev2/readOnly=true |
| AC-F2-20-02 / TC-F2-20-02 / C20-E02 | 旧票已核销、同会话仍有效 | 重开取新票再核销 | 新票成功一次、重放同票拒绝 |
| AC-F2-20-03 / TC-F2-20-03 / C20-E03 | 合法readonly票据与正常源码 | 直接HTTP/WS写入 | 403且revision/hash不变 |
| AC-F2-20-04 / TC-F2-20-04 / C20-E04 | owner注销或工程删除 | 旧WS/页面继续访问 | 访问拒绝、进程关闭，不继续写回 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-20`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-02，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI按§8.8保留本卡受影响页面的亮暗截图并实际查看，最终C36补全视口矩阵。

**完成后**：按§14在对话记录C107F2-20状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-21：限定 iframe 安全策略并验证三入口回归

**执行包/类型**：v1.0.0；REQ-F2-21；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F17中本卡负责的行为。完成必须看到“可见编辑器、无frame拒绝或CSP错误”。本卡只声明部署/脚本及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-19、C107F2-20的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `tests/deployment/hypit-fix2-c21.contract.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C21/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §6.9、§8.6、§9.4。

**必读/只读**：§0/5/9/12/13/14；§6.9、§8.6、§9.4；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W154, W145, W011, W012, W147, W009, W001, W152, W155, W156。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §6.9、§8.6、§9.4，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 仅对经过session access校验的Studio/Preview资源局部设置SAMEORIGIN与frame-ancestors self；主应用/治理页面仍保持原防嵌入策略，不全局关闭CSP。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | Nginx内部auth_request代理到固定Edge access路由，透传Cookie但不暴露内部token；转发签名断言并覆盖客户端同名伪造header。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 严格同源Origin含端口，WS仅允许固定升级路径；禁止跨域写回和任意反向代理；ticket URL日志脱敏。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 检查AI iframe实际响应头和浏览器console；回归用户端/AI端/治理台主题脚本CSP及既有DH媒体路由，避免叠overlay相互覆盖。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-21-01 / TC-F2-21-01 / C21-E01 | 已认证同源父页 | 真实iframe加载Studio | 可见编辑器、无frame拒绝或CSP错误 |
| AC-F2-21-02 / TC-F2-21-02 / C21-E02 | 攻击origin加载相同会话URL | iframe/WS/写回各请求 | 分别被策略/鉴权拒绝，不扩大主站可嵌入性 |
| AC-F2-21-03 / TC-F2-21-03 / C21-E03 | 无站内会话但伪造内部断言header | 请求Studio资源 | 401/403且broker未接受伪造身份 |
| AC-F2-21-04 / TC-F2-21-04 / C21-E04 | 同一隔离栈三HTML及DH路由 | 逐入口登录/主题切换/媒体请求 | 既有安全头与功能不回退 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-21`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-04、V-06，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI按§8.8保留本卡受影响页面的亮暗截图并实际查看，最终C36补全视口矩阵。

**完成后**：按§14在对话记录C107F2-21状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-22：构建绑定不可变版本的真实 Preview 服务

**执行包/类型**：v1.0.0；REQ-F2-22；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F18中本卡负责的行为。完成必须看到“看到A内容/时钟，B改动不污染”。本卡只声明Node 集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-06、C107F2-19、C107F2-21的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-hypit/backend/tests/agent-integration/fix2-c22.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C22/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §6.9–6.10。

**必读/只读**：§0/5/9/12/13/14；§6.9–6.10；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W157, W158, W159, W026, W025, W054, W160, W139, W140, W145, W001, W161, W147, W009, W011, W012, W035, W032。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §6.9–6.10，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | openPreviewSession验证revision存在和manifestHash，通过snapshot作者根启动真实transient execution，向浏览器提供/preview/{sessionId}/有效URL。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 素材served set依据同一快照manifest生成，不查错误的.hypit/revisions；资源必须由会话授权集合映射，支持视频Range及正确Content-Type。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 执行/资源服务采用同session access链，preview readonly不创建Build/Result；关闭调用服务端撤销，TTL/进程崩溃回收，不能只清前端ref。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | Java不再为空ticketUrl给成功信封：URL/会话/版本缺失视为引擎错误；返回缺素材清单并禁止假画面。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-22-01 / TC-F2-22-01 / C22-E01 | 两个不同revision，head为B | 请求A preview并播放 | 看到A内容/时钟，B改动不污染 |
| AC-F2-22-02 / TC-F2-22-02 / C22-E02 | 会话授权视频资源 | 请求bytes=0-99与越权另项目资源 | 前者206且Content-Range正确，后者404 |
| AC-F2-22-03 / TC-F2-22-03 / C22-E03 | 不存在revision或缺必要素材 | openPreview | 明确失败/缺项，不回退当前head或空src |
| AC-F2-22-04 / TC-F2-22-04 / C22-E04 | 同项目preview与build同时运行 | 关闭preview | preview资源撤销，Build继续直到自身终态 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-22`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-02，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-22状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-23：修复预览跨帧消息、播放状态与资源释放

**执行包/类型**：v1.0.0；REQ-F2-23；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F20、F18中本卡负责的行为。完成必须看到“父页接收并显示0，不因origin=null拒绝”。本卡只声明组件及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-22、C107F2-11的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `src/views/video-clone/composables/fix2-c23.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C23/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §6.10、§8.3。

**必读/只读**：§0/5/9/12/13/14；§6.10、§8.3；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W162, W163, W075, W072, W076, W158, W164。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §6.10、§8.3，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 保留preview sandbox=allow-scripts的opaque origin隔离；父页必须验证event.source===iframe.contentWindow、匹配sessionId/nonce与严格schema，不能用host子串。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 为bootstrap到父页的消息使用会话nonce并避免记录；frame为非负安全整数，timeSeconds有限非负，unknown消息忽略。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 关闭、切工程、卸载都发幂等close并detach监听/清时钟/取消本地请求；网络失败不影响服务端TTL最终清理。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | open响应迟到不能重开已关闭预览；iframe错误/过期提供明确重新打开入口，不使用空字符串src。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-23-01 / TC-F2-23-01 / C23-E01 | sandbox真实iframe、正确source/session/nonce | 发送frame0/time0 | 父页接收并显示0，不因origin=null拒绝 |
| AC-F2-23-02 / TC-F2-23-02 / C23-E02 | 同域另iframe发送正确session但错误source | postMessage | 忽略，不改变播放状态 |
| AC-F2-23-03 / TC-F2-23-03 / C23-E03 | 正确source发送NaN/负数/未知type | 依次发送 | 全部忽略且无异常 |
| AC-F2-23-04 / TC-F2-23-04 / C23-E04 | open请求挂起后用户关闭/切项目 | 释放旧响应 | 不重开、不残留listener，close可重试 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-23`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-04、V-05，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI按§8.8保留本卡受影响页面的亮暗截图并实际查看，最终C36补全视口矩阵。

**完成后**：按§14在对话记录C107F2-23状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-24：使 Feedback 批次校验和落盘原子化

**执行包/类型**：v1.0.0；REQ-F2-24；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F32中本卡负责的行为。完成必须看到“两条一次提交、hash更新，重读一致”。本卡只声明Node 集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-10的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-hypit/backend/tests/agent-integration/fix2-c24.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C24/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §5 RULE-11、§6.11。

**必读/只读**：§0/5/9/12/13/14；§5 RULE-11、§6.11；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W165, W078, W026, W140, W166。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §5 RULE-11、§6.11，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 先解析/验证全部mutation，再在统一项目锁内读取最新文档并比较expectedHash；预计算最终文档而不逐条写原文件。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 所有operation成功后以同目录临时文件+原子替换一次提交；保留上游文档schema/排序/标识语义，不绕过上游验证。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | revision写和Feedback写共用项目级互斥边界，避免CAS检查到落盘之间被另一个请求插入；冲突返回409与当前hash，旧内容不变。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 命令幂等键重放返回同回执；响应丢失后的重试不得重复add。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-24-01 / TC-F2-24-01 / C24-E01 | 初始空文档、两个合法add | 提交同expectedHash | 两条一次提交、hash更新，重读一致 |
| AC-F2-24-02 / TC-F2-24-02 / C24-E02 | 第一项合法add、第二项unknown operation | 提交批次 | 失败且comments仍0、hash/字节完全不变 |
| AC-F2-24-03 / TC-F2-24-03 / C24-E03 | 两个请求使用同hash | 屏障同时提交 | 只有一批成功，另一409，无交织部分结果 |
| AC-F2-24-04 / TC-F2-24-04 / C24-E04 | 已提交但丢响应 | 同requestId重发 | 原回执、评论数不增加 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-24`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-02，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-24状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-25：按评论做语义源码修改并关联解决状态

**执行包/类型**：v1.0.0；REQ-F2-25；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F10、F22中本卡负责的行为。完成必须看到“目标32px、其他结构不变、评论应用后resolved”。本卡只声明服务/契约集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-17、C107F2-24、C107F2-22的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C25IT.java` 的四组TC；证据在 `test-artifacts/task-107/fix2/C25/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §5 RULE-11、§6.11。

**必读/只读**：§0/5/9/12/13/14；§5 RULE-11、§6.11；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W167, W107, W108, W079, W088, W168, W072, W165, W169。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §5 RULE-11、§6.11，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 前端revise创建review intent job并传commentIds/run/revision，不自行拼content或baseHash=null的整文件覆盖。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | ReviewService读取原源码和评论时间/对象锚点，生成最小changeset；结构化修改匹配字幕样式/音量属性，无法定位时返回需要澄清，禁止固定48px/-6猜测。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 通过原生check并apply后记录comment→job→revision映射；只有对应修改真正落地再批量resolve评论，失败保持open。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 保留未涉及源码/二进制字节；同文件多个评论合成一次变更，冲突意见进入waiting_input而非后写覆盖前写。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-25-01 / TC-F2-25-01 / C25-E01 | 原文件含字幕font-size=24px，评论指定32px | 按评论修改并重读/预览 | 目标32px、其他结构不变、评论应用后resolved |
| AC-F2-25-02 / TC-F2-25-02 / C25-E02 | 评论“让它更好看”无对象锚点 | revise | waiting_input保留源码，不写一行注释 |
| AC-F2-25-03 / TC-F2-25-03 / C25-E03 | 模型修复导致语法非法 | apply流程 | head/评论状态不变，诊断可见 |
| AC-F2-25-04 / TC-F2-25-04 / C25-E04 | 同字幕两评论要求32px和48px | 批量revise | 明确冲突待用户选择，不随机覆盖 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-25`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-03，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI按§8.8保留本卡受影响页面的亮暗截图并实际查看，最终C36补全视口矩阵。

**完成后**：按§14在对话记录C107F2-25状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-26：修复变体生命周期、重试取消路由与服务端授权

**执行包/类型**：v1.0.0；REQ-F2-26；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F13、F28中本卡负责的行为。完成必须看到“variant succeeded；仅execution_decided不能提前成功”。本卡只声明服务/契约集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-07、C107F2-08、C107F2-15的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C26IT.java` 的四组TC；证据在 `test-artifacts/task-107/fix2/C26/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §5 RULE-12、§6.12。

**必读/只读**：§0/5/9/12/13/14；§5 RULE-12、§6.12；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W170, W171, W172, W080, W147, W009, W011, W012, W001, W173。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §5 RULE-12、§6.12，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 建立lifecycle/outcome/resultReady显式映射：只有finished+complete+结果就绪才variant succeeded；failed/cancelled按真实终态映射，中间态继续观察。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 登记已有Java retry/cancel到Edge及compose flags，契约表同步；路由默认false，启用overlay打开。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | retry仅失败/已取消项增加attempt并创建新Build，保留历史回执；重复requestId不增加attempt；cancel只影响指定项及其Build。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 构建每项绑定其plan/pricing/grant且计入variant_count预算；批次部分成功不回滚已完成项，不自动重做成功项。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-26-01 / TC-F2-26-01 / C26-E01 | Build finished/outcome=complete/resultReady=true | worker观察 | variant succeeded；仅execution_decided不能提前成功 |
| AC-F2-26-02 / TC-F2-26-02 / C26-E02 | 启用overlay、owner登录 | 经Edge POST retry/cancel | 不404，执行正确服务端动作 |
| AC-F2-26-03 / TC-F2-26-03 / C26-E03 | 批次一成功一失败 | 只重试失败项并重复同键 | 成功项build不变，失败项attempt只+1 |
| AC-F2-26-04 / TC-F2-26-04 / C26-E04 | variant_count=1的合法grant | 并发两项build | 第二项拒绝，Provider调用不超1 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-26`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-03，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-26状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-27：变体前端真实取消、重试、授权与状态展示

**执行包/类型**：v1.0.0；REQ-F2-27；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F13、F28中本卡负责的行为。完成必须看到“一次POST cancel而非仅GET，最终状态按服务端”。本卡只声明组件及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-26、C107F2-18的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `src/views/video-clone/composables/fix2-c27.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C27/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §6.12、§8.3。

**必读/只读**：§0/5/9/12/13/14；§6.12、§8.3；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W089, W075, W174, W132, W072, W076, W175。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §6.12、§8.3，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 补cancelVariant函数和act cancel分支，retry携稳定requestId；不要用refresh代替mutation。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 逐项展示queued/running/成功/失败/取消与attempt，关联各自build结果，不从按钮点击推断终态。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 远程变体走共享ExecutionGrantDialog，显示总范围与逐项/累计价格；已有grant不覆盖新计划时重新授权。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 批次刷新保留有效选择，旧工程响应不能串入；部分失败只提供失败项操作，连点去重。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-27-01 / TC-F2-27-01 / C27-E01 | 一running变体 | 点击取消 | 一次POST cancel而非仅GET，最终状态按服务端 |
| AC-F2-27-02 / TC-F2-27-02 / C27-E02 | failed item attempt1 | 重试并模拟网络超时再重放 | 同requestId，最终attempt2不变3 |
| AC-F2-27-03 / TC-F2-27-03 / C27-E03 | 远程变体无grant | 点击build后取消授权 | build请求0，页面保留变体 |
| AC-F2-27-04 / TC-F2-27-04 / C27-E04 | 成功/失败/运行三项 | 刷新和操作失败项 | 成功项结果可用，运行项未被取消 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-27`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-04、V-05，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI按§8.8保留本卡受影响页面的亮暗截图并实际查看，最终C36补全视口矩阵。

**完成后**：按§14在对话记录C107F2-27状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-28：实现二进制保真的工程包与整包验证

**执行包/类型**：v1.0.0；REQ-F2-28；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F30中本卡负责的行为。完成必须看到“所有字节一致，无UTF8替换字符损坏”。本卡只声明Node 集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-04、C107F2-06的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-hypit/backend/tests/agent-integration/fix2-c28.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C28/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §6.13、§7.4。

**必读/只读**：§0/5/9/12/13/14；§6.13、§7.4；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W176, W177, W178, W078, W053, W026, W179, W180。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §6.13、§7.4，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 将二进制导入从FileChange<string>分离，使用受控staging逐文件流式复制；文本编辑2MiB/16MiB限制不误套整个4GiB工程包。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 按manifest验证路径/重复路径/类型/尺寸/hash，拒绝symlink、hardlink、zip-slip、秘密路径；限制文件数20000、展开总量4GiB、单文件≤4GiB。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 落盘后再核对全部hash，原生runner parse/check选定Run与包依赖，失败只留下可清理staging，不发布ready head；成功原子发布完整revision。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 导出冻结revision、明确required资源与omitted；缺必要素材失败，不打包credentials/journal/绝对路径；导入前后同字节保证并支持重新渲染。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-28-01 / TC-F2-28-01 / C28-E01 | 含PNG字节89504e470d0a1a0aff8000与合法MP4/字体的包 | 导出→导入→逐文件hash | 所有字节一致，无UTF8替换字符损坏 |
| AC-F2-28-02 / TC-F2-28-02 / C28-E02 | manifest合法但svrun内容非法 | 导入 | 失败且无ready工程/新head |
| AC-F2-28-03 / TC-F2-28-03 / C28-E03 | 分别构造../、绝对路径、symlink、重复entry | 单因子导入 | 各被拒，staging外零写入 |
| AC-F2-28-04 / TC-F2-28-04 / C28-E04 | 计数20000/20001，字节上限与+1 | 流式校验合成输入 | 上限符合策略、超限413，内存缓冲不随包大小增长 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-28`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-02，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-28状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-29：完成导入 PG 登记、owner 绑定与跨服务幂等收敛

**执行包/类型**：v1.0.0；REQ-F2-29；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F31中本卡负责的行为。完成必须看到“B拥有新工程且文件一致，A权限不继承”。本卡只声明服务/契约集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-05、C107F2-28的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C29IT.java` 的四组TC；证据在 `test-artifacts/task-107/fix2/C29/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §6.13、§7.4。

**必读/只读**：§0/5/9/12/13/14；§6.13、§7.4；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W181, W038, W039, W040, W115, W105, W182, W026, W176, W183。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §6.13、§7.4，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 收到合法导入请求先为account/requestId预留稳定projectId/job/command，mode=import/provisioning；broker使用该projectId与稳定commandId，不自行每次随机生成。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 上传hash参与canonical request绑定；相同requestId/相同包返回原job/project，不同包409；command.resultJson在同一收敛流程持久化，禁止fire-and-forget subscribe。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | broker成功后验证receipt与manifest，事务更新revision/selectedRun/project.ready和job终态；失败标provisioning_failed，重新查询可见而非孤儿成功ID。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 崩溃在文件已发布/PG未更新时重放先查receipt并完成登记，不导入第二份；跨账号导入生成自己的新owner，不继承原包权限/凭据。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-29-01 / TC-F2-29-01 / C29-E01 | owner A导出，owner B上传 | 列表/GET/编辑/渲染新project | B拥有新工程且文件一致，A权限不继承 |
| AC-F2-29-02 / TC-F2-29-02 / C29-E02 | 两个相同requestId和内容 | 并发导入 | 仅一个project/revision/job及磁盘目录 |
| AC-F2-29-03 / TC-F2-29-03 / C29-E03 | broker发布后注入Java崩溃 | 重启并重放 | 登记同project，不复制文件或漏owner |
| AC-F2-29-04 / TC-F2-29-04 / C29-E04 | 第一次包hash=A、第二次B | 重用requestId | 409，原工程与回执不变 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-29`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-03，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-29状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-30：接通工程包浏览器下载、上传导入与进度反馈

**执行包/类型**：v1.0.0；REQ-F2-30；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F14、F31中本卡负责的行为。完成必须看到“拿到可解压zip，manifest/hash正确，不只是HTTP202”。本卡只声明服务/契约集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-28、C107F2-29、C107F2-12的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C30IT.java` 的四组TC；证据在 `test-artifacts/task-107/fix2/C30/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §6.13、§8.3。

**必读/只读**：§0/5/9/12/13/14；§6.13、§8.3；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W080, W182, W184, W181, W185, W025, W177, W176, W186, W075, W187, W188, W189, W072, W147, W009, W011, W012, W145, W001, W190, W066, W115, W154, W076, W029, W030。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §6.13、§8.3，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 导出返回AcceptedJob，完成后提供owner绑定的exportId和站内下载路径；GET下载为真实application/zip流，不暴露artifactRoot，不把字节JSON/base64塞内存。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | imports按原契约接multipart file+requestId，Java流式转受控broker transfer；普通owner可导入自己的包，禁止客户端提交宿主目录。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 前端展示准备中/可下载/失败，触发真实浏览器download事件；导入选包、校验、上传、跟踪job并导航新工程，断开按同requestId查询。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 下载24小时有效，可从完成job重新发起同revision导出；断点Range、Content-Disposition安全文件名、上传中断staging清理及体积限制按§6.13。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-30-01 / TC-F2-30-01 / C30-E01 | 已完成工程 | UI点击导出并等待浏览器download | 拿到可解压zip，manifest/hash正确，不只是HTTP202 |
| AC-F2-30-02 / TC-F2-30-02 / C30-E02 | 下载的zip | 通过文件控件上传 | 新owner工程出现、可打开并原生check |
| AC-F2-30-03 / TC-F2-30-03 / C30-E03 | 有效exportId属于A | B或匿名GET | 404/401，无字节；不能通过路径猜测绕过 |
| AC-F2-30-04 / TC-F2-30-04 / C30-E04 | 大包上传中断；下载Range请求 | 中断重试/部分下载 | 无残留ready假工程，206字节正确，内存缓冲有界 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-30`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-03，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI按§8.8保留本卡受影响页面的亮暗截图并实际查看，最终C36补全视口矩阵。

**完成后**：按§14在对话记录C107F2-30状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-31：完成参考素材文件上传和跨创作入口交接

**执行包/类型**：v1.0.0；REQ-F2-31；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F14、F29中本卡负责的行为。完成必须看到“资产可见且broker读取字节hash与源一致”。本卡只声明服务/契约集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-05、C107F2-16、C107F2-30的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C31IT.java` 的四组TC；证据在 `test-artifacts/task-107/fix2/C31/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §6.14、§8.3。

**必读/只读**：§0/5/9/12/13/14；§6.14、§8.3；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W038, W110, W191, W192, W193, W194, W025, W091, W195, W075, W072, W147, W009, W011, W012, W001, W196, W066, W154, W076。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §6.14、§8.3，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | sourceContext通过既有owner/固化检查后，映射源media/analysis资产为Hypit可消费资源handle，持久sourceContext与asset关联；不得只有JSON引用。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | ReferencePanel新增真实file input，accept用于体验，后端按实际MIME/probe验证；multipart上传≤256MiB，成功后asset可被analyze读取。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | URL导入复用既有SSRF和归属防线，页面提示上传/URL两种入口与实际能力一致；文件重复导入按project+hash复用资源但保留来源。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 源资源撤权/删除时按生命周期拒绝后续读取，独立已固化副本按现有业务规则保留；切工程刷新资产列表且取消旧请求。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-31-01 / TC-F2-31-01 / C31-E01 | 从已固化媒体入口带sourceContext | 创建后进入参考素材 | 资产可见且broker读取字节hash与源一致 |
| AC-F2-31-02 / TC-F2-31-02 / C31-E02 | 256MiB以内合法视频 | 通过file input上传并分析 | asset落库、可probe，进度与错误真实 |
| AC-F2-31-03 / TC-F2-31-03 / C31-E03 | 256MiB+1；另一个扩展mp4但内容脚本 | 分别上传 | 413或422，无ready资产/可执行内容 |
| AC-F2-31-04 / TC-F2-31-04 / C31-E04 | 他人mediaId；本人同文件重复 | 交接/上传 | 越权404；本人重复不产生重复资源字节 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-31`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-03，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI按§8.8保留本卡受影响页面的亮暗截图并实际查看，最终C36补全视口矩阵。

**完成后**：按§14在对话记录C107F2-31状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-32：修复维护模式写入栅栏与在途执行排空

**执行包/类型**：v1.0.0；REQ-F2-32；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F33中本卡负责的行为。完成必须看到“不能立即宣称drained，等待实际完成”。本卡只声明Node 集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-08、C107F2-15、C107F2-20、C107F2-22、C107F2-26、C107F2-29的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-hypit/backend/tests/agent-integration/fix2-c32.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C32/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §5 RULE-13、§6.15。

**必读/只读**：§0/5/9/12/13/14；§5 RULE-13、§6.15；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W197, W026, W025, W198, W199, W108, W170, W200, W201, W202, W066。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §5 RULE-13、§6.15，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | maintenance enter先原子关闭新副作用接收，再统计prepared/dispatching/acknowledged和原生active build/author session写入；不要只数不存在的queued/running。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 以maintenance租约/所有权nonce管理进入和退出；有在途远程操作不强杀，等待安全终态/一致快照点，60秒超时返回非零并保持可解释状态。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 所有写入路径含资源上传、Studio mutation、Agent/variant新提交都识别栅栏；读取/状态查询保持可用，取消/收据收敛允许继续完成排空。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | backup取得自己维护租约后立即注册EXIT/INT/TERM恢复；若本来由其他操作者维护，不能退出别人的维护窗口。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-32-01 / TC-F2-32-01 / C32-E01 | 一dispatching、一acknowledged、一active原生build | enter维护 | 不能立即宣称drained，等待实际完成 |
| AC-F2-32-02 / TC-F2-32-02 / C32-E02 | enter与新submit同时过屏障 | 并发发请求 | 栅栏后新副作用503，栅栏前任务计入排空 |
| AC-F2-32-03 / TC-F2-32-03 / C32-E03 | pg_dump立即失败 | 运行backup | 非零且释放自己维护租约，不留永久503 |
| AC-F2-32-04 / TC-F2-32-04 / C32-E04 | 维护已由会话A持有 | B备份失败退出 | 不能解除A维护，日志明确ownership |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-32`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-02，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-32状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-33：完整备份 PG 与所有持久文件，校验备份清单

**执行包/类型**：v1.0.0；REQ-F2-33；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F33中本卡负责的行为。完成必须看到“manifest覆盖所有必要文件和PG dump，逐hash一致”。本卡只声明部署/脚本及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-32的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `tests/deployment/hypit-fix2-c33.contract.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C33/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §6.15、§7.4。

**必读/只读**：§0/5/9/12/13/14；§6.15、§7.4；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W200, W018, W011, W013, W203, W204。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §6.15、§7.4，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 通过实际data root布局/挂载清单备份PG、projects/work/revisions、resources、results、profiles及必要包锁；临时slot/cache可排除但manifest明确，不能漏/data兄弟目录。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 所有archive使用相对路径和内容hash；敏感credential文件按既有受控加密存储原样备份，权限0600，不能明文打印或打入用户工程包。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | PG dump和文件快照在同一已排空维护窗口生成；任何一步失败不生成complete manifest并返回非零。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 增加可重建的隔离备份fixture，含数据库引用、两revision、二进制资源、结果文件及不应备份的临时缓存；不访问用户实际卷。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-33-01 / TC-F2-33-01 / C33-E01 | 隔离数据伞含projects/resources/results | 执行backup | manifest覆盖所有必要文件和PG dump，逐hash一致 |
| AC-F2-33-02 / TC-F2-33-02 / C33-E02 | 含临时slot和凭据存储 | 生成包并检查输出 | 临时项明确omitted，日志无secret，包权限受限 |
| AC-F2-33-03 / TC-F2-33-03 / C33-E03 | tar/压缩进程注入失败 | backup | 非零、无complete标记、恢复维护 |
| AC-F2-33-04 / TC-F2-33-04 / C33-E04 | 备份窗口试图写Studio与上传 | 请求写入 | 拒绝或排队到窗口后，备份内部无混合revision |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-33`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-04、V-06，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-33状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-34：修复恢复目标映射、数据库核验和失败判定

**执行包/类型**：v1.0.0；REQ-F2-34；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F34中本卡负责的行为。完成必须看到“内容位于restore-a内，无旁边误落目录”。本卡只声明部署/脚本及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-33的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `tests/deployment/hypit-fix2-c34.contract.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C34/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §6.15、§7.5。

**必读/只读**：§0/5/9/12/13/14；§6.15、§7.5；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W205, W018, W203, W003, W206。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §6.15、§7.5，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 恢复必须到新隔离数据库/空目标卷；验证tar路径后将归档顶层显式映射TARGET_ROOT，不能先mkdir再只检查目录存在。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 正式restore必传PG DSN；提供--files-only仅诊断模式，退出状态PARTIAL且不能参与full通过。使用number/snapshot_handle等真实schema查询并核对每个指向文件实际hash。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | SQL异常、缺snapshot、缺媒体、hash不一致、PG restore失败均非零；删除 /  / true/echo0掩盖检查失败的路径。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 恢复后以新栈只读打开工程、结果和版本链，随后在隔离副本做一次修改；不自动触发generation；记录旧软件读新schema兼容验证。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-34-01 / TC-F2-34-01 / C34-E01 | backup顶层hypit，TARGET_ROOT=restore-a | 恢复 | 内容位于restore-a内，无旁边误落目录 |
| AC-F2-34-02 / TC-F2-34-02 / C34-E02 | 恢复核验SQL/连接失败 | 执行核验 | 非零，不输出missing=0冒充通过 |
| AC-F2-34-03 / TC-F2-34-03 / C34-E03 | manifest列出一个被删除snapshot | 恢复后核验 | 明确缺失路径类别及计数，禁止ready |
| AC-F2-34-04 / TC-F2-34-04 / C34-E04 | PG+文件恢复到新栈 | 浏览器打开工程和结果 | owner/revision/hash一致、无新Provider调用 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-34`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-04、V-06，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-34状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-35：收口会话、资产、任务及导入临时文件的生命周期

**执行包/类型**：v1.0.0；REQ-F2-35；实现修复；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F08、F18、F19、F31中本卡负责的行为。完成必须看到“会话撤销/写拒绝/独占临时物清理，结果按既有保留策略”。本卡只声明服务/契约集成及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-20、C107F2-22、C107F2-29、C107F2-30、C107F2-31的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C35IT.java` 的四组TC；证据在 `test-artifacts/task-107/fix2/C35/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §7.4、§9.5 R-LIFECYCLE。

**必读/只读**：§0/5/9/12/13/14；§7.4、§9.5 R-LIFECYCLE；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W038, W039, W139, W105, W185, W137, W157, W186, W026, W090, W207, W208, W209, W210。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §7.4、§9.5 R-LIFECYCLE，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 按实际资源类型更新生命周期登记，真实代码符号与消费者一一对应；工程删除先撤会话/拒新写、取消可取消job，再清独占派生物，不破坏共享媒体。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 账号注销/撤权使session access即时拒绝；broker最长60秒清理周期停止已撤会话，WS写每次仍实时验权。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 未完成上传staging/失败导入按24小时TTL回收，清理失败留可重试状态；活跃owner、被结果引用的资源不能误删。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 前端登出/换账号清全部工程缓存和generation，旧响应不恢复旧身份数据；日志/事件只带业务ID不带源码/票据。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-35-01 / TC-F2-35-01 / C35-E01 | 工程有preview、Studio、上传和job | owner删除 | 会话撤销/写拒绝/独占临时物清理，结果按既有保留策略 |
| AC-F2-35-02 / TC-F2-35-02 / C35-E02 | 两个工程引用同一固化media | 删其中一个 | 另一工程仍可读取，媒体不误删 |
| AC-F2-35-03 / TC-F2-35-03 / C35-E03 | 第一次文件删除权限故障 | 恢复权限后调度重试 | 最终清理且不重复副作用，失败可观测 |
| AC-F2-35-04 / TC-F2-35-04 / C35-E04 | A请求挂起后切B | 释放A响应 | B页面无A数据/票据，服务端B不能读A资源 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-35`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-03，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI按§8.8保留本卡受影响页面的亮暗截图并实际查看，最终C36补全视口矩阵。

**完成后**：按§14在对话记录C107F2-35状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-36：完成工作区双主题、移动端、键盘和全状态验收

**执行包/类型**：v1.0.0；REQ-F2-36；集成验收；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F06、F07、F14、F15、F20中本卡负责的行为。完成必须看到“无裁剪/低对比/整页横向滚动，截图目标确为工作区”。本卡只声明浏览器 E2E及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-18、C107F2-21、C107F2-23、C107F2-25、C107F2-27、C107F2-30、C107F2-31、C107F2-35的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `tests/e2e/hypit-fix2-c36.spec.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C36/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §8.1–8.8。

**必读/只读**：§0/5/9/12/13/14；§8.1–8.8；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W072, W092, W188, W211, W091, W129, W130, W082, W098, W168, W174, W163, W152, W073, W131, W132, W189, W212, W213。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §8.1–8.8，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 遵循根DESIGN，只修本次工作区受影响样式/行为；把改动组件内hex fallback改成已定义token，复用GlModal/EmptyState和全局类。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 按390×844、834×1112、1440×1000与明暗两主题检查四阶段及禁用/空/加载/错误/提交中；长标题120字、长文件名、长错误不得整页横溢。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 键盘完成新建、授权、保存、取消、导出，弹窗初始焦点、Tab约束、Esc与返回焦点可测；iframe有title，动态错误aria-live，状态不只靠颜色。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 实际查看截图并记录图像结论；共享token如改变，补三入口对应回归。截图不能替代真实按钮请求/持久化验收。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-36-01 / TC-F2-36-01 / C36-E01 | 合成复杂工程、两主题三视口 | 逐四阶段截图并实际查看 | 无裁剪/低对比/整页横向滚动，截图目标确为工作区 |
| AC-F2-36-02 / TC-F2-36-02 / C36-E02 | 不使用鼠标 | 新建→授权弹窗→取消→保存 | 焦点正确、Esc可退出、返回触发按钮 |
| AC-F2-36-03 / TC-F2-36-03 / C36-E03 | 定向延迟/失败仅用于状态专项 | 打开各面板及提交 | 保留输入、有retry、旧数据注明更新失败，不伪空 |
| AC-F2-36-04 / TC-F2-36-04 / C36-E04 | 120字标题、255字符路径、长错误 | 390px视口操作 | 主要动作可达，容器内部滚动不裁掉内容 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-36`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-09，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI按§8.8保留本卡受影响页面的亮暗截图并实际查看，最终C36补全视口矩阵。

**完成后**：按§14在对话记录C107F2-36状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-37：真实浏览器贯通参考素材到成片、Studio 与工程包

**执行包/类型**：v1.0.0；REQ-F2-37；集成验收；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F35中本卡负责的行为。完成必须看到“源锚点/字幕/时长可核验，MP4可解码，不靠mock API成功”。本卡只声明浏览器 E2E及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-08、C107F2-18、C107F2-21、C107F2-23、C107F2-25、C107F2-27、C107F2-30、C107F2-31、C107F2-36的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `tests/e2e/hypit-fix2-journey.spec.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C37/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §12.5 G2–G5。

**必读/只读**：§0/5/9/12/13/14；§12.5 G2–G5；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W214, W215, W216, W217, W218, W219, W220, W012。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §12.5 G2–G5，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 以真实UI点击创建/上传/分析/生成方案/编辑/生成/查看结果/归档/下载，不用API代替被验收按钮；HTTP经真实Nginx/Edge/Java/broker/runner。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 只把外部模型/Provider替换为受控fixture endpoint，仍经过正式授权/计费执行适配；实际原生编译、渲染、PG、文件和浏览器不能mock。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | Studio场景实际打开iframe/WS、修改保存、关闭重开；Preview播放/帧消息；评论修改后重渲染；变体局部失败重做。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 工程包下载后在另一个合成owner下UI导入、打开、修改并渲染；三浏览器逐项执行，目标spec零skip且无console error。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-37-01 / TC-F2-37-01 / C37-E01 | A合成owner、12秒参考素材 | UI完成分析→方案→制作→成片下载 | 源锚点/字幕/时长可核验，MP4可解码，不靠mock API成功 |
| AC-F2-37-02 / TC-F2-37-02 / C37-E02 | 已有制作工程 | iframe编辑保存→刷新→重开 | 新revision持久、WS正常，无鉴权/CSP错误 |
| AC-F2-37-03 / TC-F2-37-03 / C37-E03 | 成片及两变体 | 评论修改→重生成；失败项重试 | 目标内容变化、成功项不重做，状态收敛 |
| AC-F2-37-04 / TC-F2-37-04 / C37-E04 | A导出zip，B新账号 | UI导入再渲染 | 同字节/自己的owner/可继续编辑，三浏览器通过 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-37`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-09，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-37状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-38：执行并发、故障注入、重启与完整恢复演练

**执行包/类型**：v1.0.0；REQ-F2-38；集成验收；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F23、F26、F27、F32、F33、F34中本卡负责的行为。完成必须看到“同operation不重复收费，只有合法owner收敛终态”。本卡只声明浏览器 E2E及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-15、C107F2-24、C107F2-26、C107F2-34、C107F2-35、C107F2-37的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `tests/e2e/hypit-fix2-recovery.spec.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C38/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §10.4、§12.5 G6。

**必读/只读**：§0/5/9/12/13/14；§10.4、§12.5 G6；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W221, W222, W218, W003, W203。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §10.4、§12.5 G6，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 在本任务隔离Compose栈设置可控屏障，不用任意sleep碰运气；覆盖命令受理后/执行提交后/PG收敛前/文件rename后重启。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 双worker、同grant并发、同revision并发、同requestId并发分别作为单因子用例，观察唯一ID、terminal与费用预留不变量。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 真实运行backup→新目录/新数据库restore→启动新栈→浏览器读取/下载，核对哈希与owner，并保证generation计数不增加。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 所有故障只定位隔离项目label和明确容器，拒绝对y-1或无label资源执行kill/down/volume rm。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-38-01 / TC-F2-38-01 / C38-E01 | 双worker、一个执行中被kill | 等待接管并重读 | 同operation不重复收费，只有合法owner收敛终态 |
| AC-F2-38-02 / TC-F2-38-02 / C38-E02 | Provider fixture已接受后断broker网络 | 恢复连接重启 | unknown→查询原receipt，不新提交 |
| AC-F2-38-03 / TC-F2-38-03 / C38-E03 | 运行中有历史作品和两revision | 排空备份→新PG/卷恢复→浏览器重开 | 所有必要hash一致、无新generation、原栈不受影响 |
| AC-F2-38-04 / TC-F2-38-04 / C38-E04 | 两客户端同head/hash | 受控同时保存和反馈修改 | CAS与批次原子性保持，不混合丢写 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-38`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-10，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-38状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-39：重建分层验收门禁，禁止漏跑和假全绿

**执行包/类型**：v1.0.0；REQ-F2-39；集成验收；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F35中本卡负责的行为。完成必须看到“非零且列缺项，不宣称全任务VERIFIED”。本卡只声明部署/脚本及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-37、C107F2-38的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `tests/deployment/hypit-fix2-gates.contract.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C39/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §12.3–12.5。

**必读/只读**：§0/5/9/12/13/14；§12.3–12.5；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W223, W003, W219, W002, W224, W225, W226, W155。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §12.3–12.5，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | full入口调用正式Docker验收阶段，不再把宿主npm test当容器产品通过；区分CONTRACT/LOCAL_NATIVE/BROWSER_E2E/RECOVERY/LIVE。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 以预期spec/TC发现数、exit code、failure/skip/NOT_RUN、产物可读性共同判定；必需阶段缺任一证据非零，不用日志关键词ALL-GREEN做事实源。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 做隔离副本的反向门禁校验：去掉生成POST、破坏outputs字段、恢复utf8导入、跳数据库恢复，四种故障必须分别被相应门禁捕获；不提交故意破坏代码。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | CI复用既有工具链/缓存，串行占用共享写入资源；恢复后的样片与截图留足可检查信息但不泄露token。真实LIVE单独无授权退出2，不能影响本地契约真实性，也不能被写成LIVE_PASS。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-39-01 / TC-F2-39-01 / C39-E01 | 删掉E2E或restore证据 | 运行--stage all | 非零且列缺项，不宣称全任务VERIFIED |
| AC-F2-39-02 / TC-F2-39-02 / C39-E02 | 隔离副本分别启用四个缺陷 | 跑对应门禁 | 四次均失败于目标断言，恢复后通过 |
| AC-F2-39-03 / TC-F2-39-03 / C39-E03 | 必需spec含skip或过滤为0 | 运行门禁 | 失败，不能靠npm退出0放行 |
| AC-F2-39-04 / TC-F2-39-04 / C39-E04 | 无真实Provider授权但本地全部通过 | 汇总 | 明确LOCAL_PASS/LIVE_NOT_RUN，绝无全平台全量通过表述 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-39`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-04、V-06，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-39状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


### 卡 C107F2-40：复核 35 组缺陷闭合并完成任务级集成判定

**执行包/类型**：v1.0.0；REQ-F2-40；集成验收；当前执行者负责实现与卡级验收。初始状态NOT_STARTED。

**背景与完成边界**：关闭F01–F35任务级判定中本卡负责的行为。完成必须看到“无遗漏/悬空TC/失效证据，所有必需门禁通过”。本卡只声明部署/脚本及明确依赖结果，不替后置卡宣称产品全部完成。

**输入与前置**：C107F2-01、C107F2-02、C107F2-03、C107F2-04、C107F2-05、C107F2-06、C107F2-07、C107F2-08、C107F2-09、C107F2-10、C107F2-11、C107F2-12、C107F2-13、C107F2-14、C107F2-15、C107F2-16、C107F2-17、C107F2-18、C107F2-19、C107F2-20、C107F2-21、C107F2-22、C107F2-23、C107F2-24、C107F2-25、C107F2-26、C107F2-27、C107F2-28、C107F2-29、C107F2-30、C107F2-31、C107F2-32、C107F2-33、C107F2-34、C107F2-35、C107F2-36、C107F2-37、C107F2-38、C107F2-39的当前VERIFIED交付；读取它们实际接口/配置/fixture与结果，不只读状态文字。基础工具链与隔离数据见§9.3。

**输出与移交**：本卡W对应生产调用/配置，以及 `tests/deployment/hypit-fix2-spec.contract.test.ts` 的四组TC；证据在 `test-artifacts/task-107/fix2/C40/`。后置卡通过真实接口/导入符号及重读结果消费，所有状态/版本/权限依据 §12.1、§12.5、§14。

**必读/只读**：§0/5/9/12/13/14；§12.1、§12.5、§14；§2对应缺陷与现存调用方；本卡首个W的现存测试/类型。UI路径出现时完整读取根DESIGN、现有GlModal/EmptyState；只读源码扩查不限于W，但不得扩大写入。

**写入集合**：W227, W228, W229, W230, W018, W002, W007。精确路径与NEW/修改性质见§9.1；本卡只允许以下步骤明确的函数/配置段，测试写入对应四组TC。W中共用文件必须保留前卡改动。

**源码定位与开始前检查**：用§9.1精确路径和以下步骤中的函数/配置关键字定位；NEW文件从本卡登记调用方接入。核对当前diff、依赖证据、签名、配置与测试发现数；相关基线失败留原始输出。行号漂移可继续，契约变化按§13。

**关键函数/组件约束**：公开签名与类型采用 §12.1、§12.5、§14，不得在卡内另发明版本。输入独立校验；输出保留真实HTTP/终态/null含义；数据库/文件/费用/路由副作用只在所有前置满足后执行。失败/取消按RULE-01～16中适用规则保留原数据、释放本卡资源，并返回可行动错误；禁止日志/截图输出秘密。

**按序实现**：

| 步骤 | 精确动作与接线 | 完成检查点/失败处理 |
| --- | --- | --- |
| 1 | 从需求反向核对35组finding→实现文件→调用者→真实输出→AC/TC/V，每项有当前基线有效证据；未接入helper/假成功必须返工责任卡。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 2 | 完成上游manifest/补丁重放、前后端质量、三入口回归、浏览器三引擎、恢复和安全单因子门禁；按影响面保留既有服务门槛，不因未知历史失败降低阈值。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 3 | 更新README/进度/status只描述实际结果，保留历史测试事实并指出被本次发现推翻的完成结论；不把40卡文档发布写成已开发。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |
| 4 | 按§14在对话交付完成或阻塞清单；本地验收完整可标本书VERIFIED，但必须显式写LIVE/生产未验，不把商业模型效果或生产发布算本地证据。 | 对照本卡TC与契约重读；普通错误卡内修复，越界/环境按§13，不用假成功前进 |


**边界与行为验收（Given / When / Then）**：


| AC / TC / 边界 | Given | When | Then（含禁止副作用） |
| --- | --- | --- | --- |
| AC-F2-40-01 / TC-F2-40-01 / C40-E01 | 当前代码和所有卡证据 | 逐finding逆向核对 | 无遗漏/悬空TC/失效证据，所有必需门禁通过 |
| AC-F2-40-02 / TC-F2-40-02 / C40-E02 | 不依赖实施者完成文字 | 重新打开样片、工程包、Studio及恢复栈 | 产物可消费且来源/owner/版本一致 |
| AC-F2-40-03 / TC-F2-40-03 / C40-E03 | 故意保留一个必需FAIL/NOT_RUN | 尝试完成判定 | 不得标VERIFIED；明确责任卡和解除条件 |
| AC-F2-40-04 / TC-F2-40-04 / C40-E04 | 任务diff、初始108文档和upstream | 检查白名单/重建命令 | 他人改动保留，上游冻结，复现不依赖本机忽略产物 |


**本卡禁止**：通过空返回/固定成功/只建helper未接入调用者满足验收；用下一卡掩盖当前数据/权限保护；扩大W、改upstream或付费实测。当前卡下游尚未完成的能力只能标未ready，不能虚构完成。

**验收命令**：根目录bash运行 `bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-40`（V-07），仅分派本卡已登记测试、typecheck及已落地依赖回归；测试层技术入口见 V-04、V-06，完整总集执行时机见§12.3，不要求本卡运行未来卡的测试。四组TC及共同负向矩阵均应被发现，预期exit0；反例子进程预期非零由外层断言。完整用例输入/清理/防假阳性见§12.2。

**保留行为回归**：继承前置卡已通过的身份、幂等、版本及失败语义；同一共享文件的前卡TC必须继续通过。UI专项N/A：本卡不直接改页面；其结果消费由后续UI/E2E卡验证。

**完成后**：按§14在对话记录C107F2-40状态、实际W diff、命令/cwd/退出码/测试数、四组TC和产物/重读证据、未完成下游；不新建完成报告。


## 12. 测试、验证命令与集成验收

### 12.1 追踪总规则

§1.3给REQ→卡→AC/TC，§2.6给F01–35→责任卡，§11给W文件与实现步骤，下面每组用例给真实层/fixture/输入/操作/状态/副作用/证据；V编号和出口在§12.3–12.5。C40逆向核对不得缺环。TC短写只用于表格阅读，正式测试名称写完整ID。

### 12.2 160 组详细用例

共同前提：使用§9.3合成owner A/B/operator及seed10702，时钟固定或可控；并发用屏障控制顺序，不任意sleep。清理只释放本用例mock/临时目录/隔离栈fixture，不能删用户主栈。每组同时检查用户响应与DB/文件/事件/费用副作用；不是只断言HTTP码。

API负向矩阵继承规则：每个本书触碰的端点须展开未登录/过期/跨owner、必填缺省/null/空串/错类型、合法下界/上界/±1、同键重复/异payload、网络超时已受理。卡内专门案例是额外重点而非豁免共同矩阵。接口无相应字段则说明N/A理由，不造无关字段。


#### TC-F2-01-01：反例可重建


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-01 / AC-F2-01-01 / C01-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c01.contract.test.ts` 中测试名包含 `TC-F2-01-01` |
| 前置与完整输入 | 干净临时目录、固定 seed=10702；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 生成媒体与六类请求样本；加载隔离反例；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 文件 hash 可重现、六类旧行为均有失败断言；不调用 Provider |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“反例可重建”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C01/TC-F2-01-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-01-02：缺阶段拒绝


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-01 / AC-F2-01-02 / C01-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c01.contract.test.ts` 中测试名包含 `TC-F2-01-02` |
| 前置与完整输入 | verify 仅有骨架；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 调用 --stage e2e；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 退出 2/NOT_RUN，不能打印 ALL-GREEN |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“缺阶段拒绝”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C01/TC-F2-01-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-01-03：零用例拒绝


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-01 / AC-F2-01-03 / C01-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c01.contract.test.ts` 中测试名包含 `TC-F2-01-03` |
| 前置与完整输入 | 过滤器指向不存在测试；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 运行验证入口；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 非零且指出零用例，不能记录 PASS |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“零用例拒绝”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C01/TC-F2-01-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-01-04：来源与映射


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-01 / AC-F2-01-04 / C01-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c01.contract.test.ts` 中测试名包含 `TC-F2-01-04` |
| 前置与完整输入 | 35 组审计证据；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 校验 finding/card/TC/V 引用；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 35 组全覆盖，fixture 与实际边界区别明确 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“来源与映射”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C01/TC-F2-01-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-02-01：启用组合


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-02 / AC-F2-02-01 / C02-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c02.contract.test.ts` 中测试名包含 `TC-F2-02-01` |
| 前置与完整输入 | 隔离栈启用 DH/Hypit、合成 owner；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 启动并访问三个入口及 projects；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | projects=200、三入口存活，labels 中包含 Hypit overlay |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“启用组合”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C02/TC-F2-02-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-02-02：默认禁用


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-02 / AC-F2-02-02 / C02-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c02.contract.test.ts` 中测试名包含 `TC-F2-02-02` |
| 前置与完整输入 | 未传 --enable-hypit；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 访问 capabilities/projects；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | capabilities 正确表示 disabled；业务路由 404，不误启动 broker |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“默认禁用”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C02/TC-F2-02-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-02-03：防共享配置覆盖


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-02 / AC-F2-02-03 / C02-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c02.contract.test.ts` 中测试名包含 `TC-F2-02-03` |
| 前置与完整输入 | 同工程已启用 Hypit；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 以漏 Hypit 的组合重建共享 frontend/Edge；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 预检非零，运行配置未被静默覆盖 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“防共享配置覆盖”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C02/TC-F2-02-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-02-04：缺配置 fail-fast


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-02 / AC-F2-02-04 / C02-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c02.contract.test.ts` 中测试名包含 `TC-F2-02-04` |
| 前置与完整输入 | 显式启用但 ticket secret 缺省/31字符；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 运行 config 预检；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 明确缺项且无容器变更；日志不含秘密 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“缺配置 fail-fast”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C02/TC-F2-02-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-03-01：镜像资源


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-03 / AC-F2-03-01 / C03-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c03.test.ts` 中测试名包含 `TC-F2-03-01` |
| 前置与完整输入 | 新构建两镜像、只读 rootfs；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 容器内读 fixtures/catalog 和入口；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 所有登记文件存在且可读，模板 provenance 与上游版本一致 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“镜像资源”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C03/TC-F2-03-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-03-02：环境覆盖


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-03 / AC-F2-03-02 / C03-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c03.test.ts` 中测试名包含 `TC-F2-03-02` |
| 前置与完整输入 | socket=/sockets、slot=/slots；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 调用 loadConfig；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 返回指定值，不回退 dataRoot 内目录 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“环境覆盖”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C03/TC-F2-03-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-03-03：错误根目录


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-03 / AC-F2-03-03 / C03-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c03.test.ts` 中测试名包含 `TC-F2-03-03` |
| 前置与完整输入 | 模板根不存在或 catalog 源路径越界；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 启动预检；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 非零且不创建 ready 工程 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“错误根目录”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C03/TC-F2-03-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-03-04：旧卷兼容


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-03 / AC-F2-03-04 / C03-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c03.test.ts` 中测试名包含 `TC-F2-03-04` |
| 前置与完整输入 | 隔离卷已有工程及 journal；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 用新镜像启动并重读；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 原文件 hash 不变、路径可解析，无重置/迁移副作用 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“旧卷兼容”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C03/TC-F2-03-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-04-01：真实隔离执行


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-04 / AC-F2-04-01 / C04-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c04.test.ts` 中测试名包含 `TC-F2-04-01` |
| 前置与完整输入 | Docker runner 在线、合成作者包；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 从 broker 发 check/compile 并检查进程所属容器；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 实际作者 PID 在 runner；broker 无作者执行进程 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“真实隔离执行”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C04/TC-F2-04-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-04-02：越界与网络


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-04 / AC-F2-04-02 / C04-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c04.test.ts` 中测试名包含 `TC-F2-04-02` |
| 前置与完整输入 | 作者程序尝试访问兄弟 slot、secret 路径、外网；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 依次执行三个单因子反例；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 分别被拒，输出不泄漏秘密；不是靠编译语法错误提前挡住 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“越界与网络”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C04/TC-F2-04-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-04-03：故障清理


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-04 / AC-F2-04-03 / C04-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c04.test.ts` 中测试名包含 `TC-F2-04-03` |
| 前置与完整输入 | 一个 slot 执行中杀死 runner 子进程；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 等待 lease 回收后发新请求；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 旧调用明确失败、新请求可执行，容量不泄漏 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“故障清理”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C04/TC-F2-04-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-04-04：无降级


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-04 / AC-F2-04-04 / C04-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c04.test.ts` 中测试名包含 `TC-F2-04-04` |
| 前置与完整输入 | runner 容器停止但 broker 在线；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 提交 compile；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 失败且 broker spawn 计数为0，不假成功 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“无降级”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C04/TC-F2-04-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-05-01：模板身份


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-05 / AC-F2-05-01 / C05-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C05IT.java` 中测试名包含 `TC-F2-05-01` |
| 前置与完整输入 | catalog 中两个不同模板；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 分别创建并读主文件 hash；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 内容对应各自模板，不能都成为 minimal-local |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“模板身份”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C05/TC-F2-05-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-05-02：空工程可写


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-05 / AC-F2-05-02 / C05-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C05IT.java` 中测试名包含 `TC-F2-05-02` |
| 前置与完整输入 | mode=clone/brief；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 创建后读取文件、提交首个 Agent job；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | revision=1且合法源文件存在；Agent 不因 revision0 拒绝 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“空工程可写”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C05/TC-F2-05-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-05-03：重放与失败


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-05 / AC-F2-05-03 / C05-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C05IT.java` 中测试名包含 `TC-F2-05-03` |
| 前置与完整输入 | 同 requestId 两并发；另一次 broker 失败；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 创建并刷新；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 同键仅一个工程；失败为 provisioning_failed，不假 ready |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“重放与失败”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C05/TC-F2-05-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-05-04：旧零版恢复


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-05 / AC-F2-05-04 / C05-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C05IT.java` 中测试名包含 `TC-F2-05-04` |
| 前置与完整输入 | 旧工程 revision0、无 head；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 两客户端同时触发初始化；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 仅一个 revision1，原 owner/title/sourceContext 不变 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“旧零版恢复”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C05/TC-F2-05-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-06-01：正式 Java 参数


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-06 / AC-F2-06-01 / C06-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C06IT.java` 中测试名包含 `TC-F2-06-01` |
| 前置与完整输入 | 仅 projectId/revision/runFile 的正式请求；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 执行 check→plan→pricing；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 三个调用通过真实 broker/runner，不要求客户端 sourceDir |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“正式 Java 参数”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C06/TC-F2-06-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-06-02：排队后编辑


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-06 / AC-F2-06-02 / C06-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C06IT.java` 中测试名包含 `TC-F2-06-02` |
| 前置与完整输入 | A版已提交而未执行；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 改成B版后释放runner；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 产物体现A版且receipt绑定A revision |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“排队后编辑”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C06/TC-F2-06-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-06-03：Profile变化


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-06 / AC-F2-06-03 / C06-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C06IT.java` 中测试名包含 `TC-F2-06-03` |
| 前置与完整输入 | 计划后变更受控模型配置版本；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | submit 旧计划；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 409 stale，零Provider调用、零新增有效Build |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“Profile变化”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C06/TC-F2-06-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-06-04：快照缺失


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-06 / AC-F2-06-04 / C06-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C06IT.java` 中测试名包含 `TC-F2-06-04` |
| 前置与完整输入 | revision存在PG但目录缺失/manifest篡改；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | plan/submit；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 明确失败且不回退当前work |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“快照缺失”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C06/TC-F2-06-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-07-01：真链假外部


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-07 / AC-F2-07-01 / C07-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C07IT.java` 中测试名包含 `TC-F2-07-01` |
| 前置与完整输入 | 真实Java+broker授权链、外部Provider fixture；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 原生runtime请求一个Need；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | prepare/submit/receipt逐段可追踪，operationId唯一 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“真链假外部”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C07/TC-F2-07-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-07-02：部分范围拒绝


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-07 / AC-F2-07-02 / C07-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C07IT.java` 中测试名包含 `TC-F2-07-02` |
| 前置与完整输入 | 计划targets=[a,b]、grant=[a]；单独再测[]；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 执行 b；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 授权阶段拒绝，Provider调用计数0 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“部分范围拒绝”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C07/TC-F2-07-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-07-03：并发预算


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-07 / AC-F2-07-03 / C07-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C07IT.java` 中测试名包含 `TC-F2-07-03` |
| 前置与完整输入 | grant上限1.000000、两个0.700000请求同时过屏障；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 并发prepare；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 最多一个获准，总预留≤1.000000 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“并发预算”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C07/TC-F2-07-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-07-04：未知回执重启


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-07 / AC-F2-07-04 / C07-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C07IT.java` 中测试名包含 `TC-F2-07-04` |
| 前置与完整输入 | Provider已接受但broker丢响应；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 重启并重放同operationId；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 查询原receipt、不产生第二次提交/费用 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“未知回执重启”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C07/TC-F2-07-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-08-01：原生成片


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-08 / AC-F2-08-01 / C08-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-api-render.spec.ts` 中测试名包含 `TC-F2-08-01` |
| 前置与完整输入 | 合成三秒双色移动元素工程；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | 经真实API提交并下载解码；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 原生引擎产出MP4、时长3秒±1帧、三帧内容符合锚点 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“原生成片”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-08；`test-artifacts/task-107/fix2/C08/TC-F2-08-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-08-02：重新读取


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-08 / AC-F2-08-02 / C08-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-api-render.spec.ts` 中测试名包含 `TC-F2-08-02` |
| 前置与完整输入 | 生成完成后关闭客户端；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | 重开查询相同buildId；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 结果仍可发现和下载，不重新生成 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“重新读取”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-08；`test-artifacts/task-107/fix2/C08/TC-F2-08-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-08-03：权限单因子


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-08 / AC-F2-08-03 / C08-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-api-render.spec.ts` 中测试名包含 `TC-F2-08-03` |
| 前置与完整输入 | 有效buildId属于owner A，owner B登录；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | 读结果和下载；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 404，不能拿到字节；A仍可读取 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“权限单因子”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-08；`test-artifacts/task-107/fix2/C08/TC-F2-08-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-08-04：无Provider依赖


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-08 / AC-F2-08-04 / C08-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-api-render.spec.ts` 中测试名包含 `TC-F2-08-04` |
| 前置与完整输入 | 外部网络/凭据不可用；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | 执行本地渲染链；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 仍成功，外部Provider调用计数0 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“无Provider依赖”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-08；`test-artifacts/task-107/fix2/C08/TC-F2-08-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-09-01：disabled入口


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-09 / AC-F2-09-01 / C09-E01；中高：用户状态/交互与对象隔离 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c09.test.ts` 中测试名包含 `TC-F2-09-01` |
| 前置与完整输入 | 登录owner、capabilities.enabled=false；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 打开工作区；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 显示未启用，projects请求0，不出现无限加载 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“disabled入口”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C09/TC-F2-09-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-09-02：实际版本


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-09 / AC-F2-09-02 / C09-E02；中高：用户状态/交互与对象隔离 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c09.test.ts` 中测试名包含 `TC-F2-09-02` |
| 前置与完整输入 | 镜像为manifest0.2.16；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 读capabilities/doctor；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 返回0.2.16；runner离线对应feature.ready=false |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“实际版本”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C09/TC-F2-09-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-09-03：会话失效


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-09 / AC-F2-09-03 / C09-E03；中高：用户状态/交互与对象隔离 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c09.test.ts` 中测试名包含 `TC-F2-09-03` |
| 前置与完整输入 | 列表已加载后登录过期；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 刷新或重试；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 复用既有登录入口、旧账号数据清空，不显示成功空列表 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“会话失效”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C09/TC-F2-09-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-09-04：恢复重试


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-09 / AC-F2-09-04 / C09-E04；中高：用户状态/交互与对象隔离 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c09.test.ts` 中测试名包含 `TC-F2-09-04` |
| 前置与完整输入 | 初次broker不可达，随后恢复；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 点击重试；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 重新探测后只拉取一次列表，页面可操作 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“恢复重试”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C09/TC-F2-09-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-10-01：连续保存


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-10 / AC-F2-10-01 / C10-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c10.test.ts` 中测试名包含 `TC-F2-10-01` |
| 前置与完整输入 | hash=A的有效源码；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 保存B再保存C；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 两次baseHash分别A/B，最终磁盘C |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“连续保存”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C10/TC-F2-10-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-10-02：在途输入


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-10 / AC-F2-10-02 / C10-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c10.test.ts` 中测试名包含 `TC-F2-10-02` |
| 前置与完整输入 | 提交B请求被屏障暂停；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 继续输入C后释放B响应；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | savedContent=B、draft=C、dirty=true |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“在途输入”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C10/TC-F2-10-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-10-03：版本冲突


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-10 / AC-F2-10-03 / C10-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c10.test.ts` 中测试名包含 `TC-F2-10-03` |
| 前置与完整输入 | 两个客户端同revision；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | A保存后B保存；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | B得到409并保留草稿，无静默覆盖 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“版本冲突”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C10/TC-F2-10-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-10-04：字段兼容


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-10 / AC-F2-10-04 / C10-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c10.test.ts` 中测试名包含 `TC-F2-10-04` |
| 前置与完整输入 | 真实broker响应含hash/revision；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 前端读取并提交；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 请求baseHash非undefined，缺必需字段显式契约错误 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“字段兼容”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C10/TC-F2-10-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-11-01：列表外深链


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-11 / AC-F2-11-01 / C11-E01；中高：用户状态/交互与对象隔离 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c11.test.ts` 中测试名包含 `TC-F2-11-01` |
| 前置与完整输入 | 工程在第51条；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 直接访问其URL后刷新；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 正确GET该工程并打开，不停在加载文案 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“列表外深链”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C11/TC-F2-11-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-11-02：迟到响应


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-11 / AC-F2-11-02 / C11-E02；中高：用户状态/交互与对象隔离 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c11.test.ts` 中测试名包含 `TC-F2-11-02` |
| 前置与完整输入 | A请求延迟、B先返回；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | A→B→A，按受控顺序释放响应；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 只有当前generation生效，旧finally不结束当前loading |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“迟到响应”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C11/TC-F2-11-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-11-03：创建与恢复


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-11 / AC-F2-11-03 / C11-E03；中高：用户状态/交互与对象隔离 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c11.test.ts` 中测试名包含 `TC-F2-11-03` |
| 前置与完整输入 | 创建返回provisioning；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 确认后刷新地址栏；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | URL包含新ID、轮询至ready、刷新可继续 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“创建与恢复”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C11/TC-F2-11-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-11-04：草稿离开


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-11 / AC-F2-11-04 / C11-E04；中高：用户状态/交互与对象隔离 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c11.test.ts` 中测试名包含 `TC-F2-11-04` |
| 前置与完整输入 | A有未保存内容；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 切B先取消再确认；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 取消留A草稿；确认后B不带A草稿/会话 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“草稿离开”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C11/TC-F2-11-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-12-01：正式DTO渲染


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-12 / AC-F2-12-01 / C12-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C12IT.java` 中测试名包含 `TC-F2-12-01` |
| 前置与完整输入 | Java真实序列化OutputList；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | ResultsPanel加载并选择build；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | items始终数组，完整字段可显示，无undefined.length |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“正式DTO渲染”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C12/TC-F2-12-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-12-02：归档单项


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-12 / AC-F2-12-02 / C12-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C12IT.java` 中测试名包含 `TC-F2-12-02` |
| 前置与完整输入 | build有final和poster两个output；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 选择final归档；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 调用正确endpoint/body，仅final归档 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“归档单项”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C12/TC-F2-12-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-12-03：归档重复


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-12 / AC-F2-12-03 / C12-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C12IT.java` 中测试名包含 `TC-F2-12-03` |
| 前置与完整输入 | 同requestId两并发；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 执行归档后刷新；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 同mediaId、无重复媒体/费用 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“归档重复”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C12/TC-F2-12-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-12-04：未知与空


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-12 / AC-F2-12-04 / C12-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C12IT.java` 中测试名包含 `TC-F2-12-04` |
| 前置与完整输入 | outputs=[]；size/duration未知；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 加载结果；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 空态与null正常展示，不显示虚假0秒或归档成功 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“未知与空”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C12/TC-F2-12-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-13-01：跨层事件


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-13 / AC-F2-13-01 / C13-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C13IT.java` 中测试名包含 `TC-F2-13-01` |
| 前置与完整输入 | 真实Java进度/terminal帧；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 前端订阅消费；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | phase/output/job终态正确更新，named event不丢 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“跨层事件”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C13/TC-F2-13-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-13-02：空队列负载


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-13 / AC-F2-13-02 / C13-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C13IT.java` 中测试名包含 `TC-F2-13-02` |
| 前置与完整输入 | 运行中job无新事件；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 观测10秒；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 每订阅增量查询≤11次，不忙循环，不重发旧帧 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“空队列负载”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C13/TC-F2-13-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-13-03：断线续传


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-13 / AC-F2-13-03 / C13-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C13IT.java` 中测试名包含 `TC-F2-13-03` |
| 前置与完整输入 | 已收到sequence7；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 断线后带7重连，注入重复7/新8；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 7不重复展示、8接收，terminal后停止 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“断线续传”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C13/TC-F2-13-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-13-04：旧cursor兼容


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-13 / AC-F2-13-04 / C13-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C13IT.java` 中测试名包含 `TC-F2-13-04` |
| 前置与完整输入 | 旧evt格式与跨job伪造cursor各一次；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 请求流；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 有效旧cursor可解析；跨job重置快照且不泄漏 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“旧cursor兼容”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C13/TC-F2-13-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-14-01：工具集合相等


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-14 / AC-F2-14-01 / C14-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C14IT.java` 中测试名包含 `TC-F2-14-01` |
| 前置与完整输入 | registry与planner scope；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 枚举每个宣称工具并dispatch；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 都命中真实handler，无默认501；参数类型保真 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“工具集合相等”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C14/TC-F2-14-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-14-02：失败不伪成功


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-14 / AC-F2-14-02 / C14-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C14IT.java` 中测试名包含 `TC-F2-14-02` |
| 前置与完整输入 | 唯一必要mutation返回校验失败；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 执行job；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | job非succeeded，有明确failed或等待状态及原因 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“失败不伪成功”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C14/TC-F2-14-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-14-03：观察再规划


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-14 / AC-F2-14-03 / C14-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C14IT.java` 中测试名包含 `TC-F2-14-03` |
| 前置与完整输入 | 首动作产出诊断、次动作修复；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 执行受控planner序列；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 第二轮输入包含真实观察，最多40个实际动作 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“观察再规划”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C14/TC-F2-14-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-14-04：scope单因子


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-14 / AC-F2-14-04 / C14-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C14IT.java` 中测试名包含 `TC-F2-14-04` |
| 前置与完整输入 | 合法输入但工具不在scope；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 直接绕前端dispatch；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 拒绝且业务service调用0，不消耗外部执行 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“scope单因子”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C14/TC-F2-14-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-15-01：长planner与双worker


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-15 / AC-F2-15-01 / C15-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C15IT.java` 中测试名包含 `TC-F2-15-01` |
| 前置与完整输入 | 执行持续70秒，两worker；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 续租并过45秒旧阈值；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 只一个执行权，planner调用1次 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“长planner与双worker”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C15/TC-F2-15-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-15-02：提交后崩溃


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-15 / AC-F2-15-02 / C15-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C15IT.java` 中测试名包含 `TC-F2-15-02` |
| 前置与完整输入 | prepared已落、下游接受、action回执未落；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 重启接管；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 沿同operationId查回执，不重复副作用 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“提交后崩溃”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C15/TC-F2-15-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-15-03：取消竞态


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-15 / AC-F2-15-03 / C15-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C15IT.java` 中测试名包含 `TC-F2-15-03` |
| 前置与完整输入 | 最终动作完成但terminal CAS前暂停；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 发cancel再释放；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 最终不被succeeded覆盖，terminal序列一致 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“取消竞态”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C15/TC-F2-15-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-15-04：resume可前进


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-15 / AC-F2-15-04 / C15-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C15IT.java` 中测试名包含 `TC-F2-15-04` |
| 前置与完整输入 | waiting_input缺assetId，用户补合法ID；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | resume两次同requestId；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 只恢复一次、消费新输入继续；scope不扩大 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“resume可前进”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C15/TC-F2-15-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-16-01：全片锚点


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-16 / AC-F2-16-01 / C16-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C16IT.java` 中测试名包含 `TC-F2-16-01` |
| 前置与完整输入 | 合成12秒视频，0/4/8秒三段、含音轨；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 点击分析对应agent intent；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 三段全覆盖、锚点与实际帧一致，结果可刷新重读 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“全片锚点”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C16/TC-F2-16-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-16-02：缺模型能力


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-16 / AC-F2-16-02 / C16-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C16IT.java` 中测试名包含 `TC-F2-16-02` |
| 前置与完整输入 | 视频合法但视觉配置缺失；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 发analyze；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 明确未完成/等待，不返回全片ready |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“缺模型能力”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C16/TC-F2-16-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-16-03：无声与截断


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-16 / AC-F2-16-03 / C16-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C16IT.java` 中测试名包含 `TC-F2-16-03` |
| 前置与完整输入 | 无音轨视频；另一个只有前4秒分析返回；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 分别执行；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 无声不伪造台词；截断分析PARTIAL阻止直接生成 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“无声与截断”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C16/TC-F2-16-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-16-04：换素材失效


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-16 / AC-F2-16-04 / C16-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C16IT.java` 中测试名包含 `TC-F2-16-04` |
| 前置与完整输入 | A分析已完成，资产换B hash；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 请求生成方案；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 旧分析被判失配，需要重新分析 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“换素材失效”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C16/TC-F2-16-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-17-01：内容差异


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-17 / AC-F2-17-01 / C17-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C17IT.java` 中测试名包含 `TC-F2-17-01` |
| 前置与完整输入 | 两个不同brief/参考分析、受控外部响应；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 各生成源码并原生渲染；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 字幕/时序/素材选择实际不同，非仅注释差异 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“内容差异”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C17/TC-F2-17-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-17-02：编译修复


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-17 / AC-F2-17-02 / C17-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C17IT.java` 中测试名包含 `TC-F2-17-02` |
| 前置与完整输入 | 第一轮生成无效引用、第二轮合法；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 执行author；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 使用诊断修复后才apply；失败稿不覆盖head |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“编译修复”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C17/TC-F2-17-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-17-03：缺材料


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-17 / AC-F2-17-03 / C17-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C17IT.java` 中测试名包含 `TC-F2-17-03` |
| 前置与完整输入 | 方案含未绑定必要asset；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 请求执行方案；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | waiting_input指出缺项、零构建/收费 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“缺材料”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C17/TC-F2-17-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-17-04：非授权代码


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-17 / AC-F2-17-04 / C17-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C17IT.java` 中测试名包含 `TC-F2-17-04` |
| 前置与完整输入 | 模型输出越界文件路径或shell工具；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 校验changeset；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 拒绝并保留原源码，无外部命令执行 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“非授权代码”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C17/TC-F2-17-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-18-01：按钮实际提交


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-18 / AC-F2-18-01 / C18-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c18.test.ts` 中测试名包含 `TC-F2-18-01` |
| 前置与完整输入 | ready工程、本地计划；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 点击生成；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | POST plan/pricing/build实际发生并显示job，不只有GET |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“按钮实际提交”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C18/TC-F2-18-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-18-02：费用先确认


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-18 / AC-F2-18-02 / C18-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c18.test.ts` 中测试名包含 `TC-F2-18-02` |
| 前置与完整输入 | 远程Need有价格；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 点击生成再取消授权对话框；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | grant/build/Provider调用均0；确认后才一次提交 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“费用先确认”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C18/TC-F2-18-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-18-03：真实取消


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-18 / AC-F2-18-03 / C18-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c18.test.ts` 中测试名包含 `TC-F2-18-03` |
| 前置与完整输入 | 服务端job运行中；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 点击取消；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | cancel API调用一次，继续观察至cancelled |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“真实取消”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C18/TC-F2-18-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-18-04：刷新和重试


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-18 / AC-F2-18-04 / C18-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c18.test.ts` 中测试名包含 `TC-F2-18-04` |
| 前置与完整输入 | 提交响应超时但服务端受理；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 刷新并按同requestId重试；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 恢复同job/build，不新建重复任务 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“刷新和重试”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C18/TC-F2-18-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-19-01：原生编辑器


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-19 / AC-F2-19-01 / C19-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c19.test.ts` 中测试名包含 `TC-F2-19-01` |
| 前置与完整输入 | 已有有效工程、真实Studio进程；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 创建会话并GET页面/JS/CSS/WS；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 资源200、WS101、出现原生编辑器目标控件 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“原生编辑器”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C19/TC-F2-19-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-19-02：原生写回


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-19 / AC-F2-19-02 / C19-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c19.test.ts` 中测试名包含 `TC-F2-19-02` |
| 前置与完整输入 | 在Studio改一个可定位文本属性；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 保存后读文件/PG revision；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 新revision及hash一致，刷新仍保留 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“原生写回”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C19/TC-F2-19-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-19-03：启动失败


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-19 / AC-F2-19-03 / C19-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c19.test.ts` 中测试名包含 `TC-F2-19-03` |
| 前置与完整输入 | Studio入口不可读/端口不可用；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | open会话；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 失败回执且不留下active假会话/僵尸子进程 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“启动失败”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C19/TC-F2-19-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-19-04：隔离作者


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-19 / AC-F2-19-04 / C19-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c19.test.ts` 中测试名包含 `TC-F2-19-04` |
| 前置与完整输入 | Studio操作触发作者代码求值；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 观察实际运行位置；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 作者逻辑在runner，无broker秘密与外网权限 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“隔离作者”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C19/TC-F2-19-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-20-01：版本权限复用


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-20 / AC-F2-20-01 / C20-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c20.test.ts` 中测试名包含 `TC-F2-20-01` |
| 前置与完整输入 | rev1 writable已开；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 请求rev2 readonly；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 不同session，返回rev2/readOnly=true |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“版本权限复用”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C20/TC-F2-20-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-20-02：新票独立


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-20 / AC-F2-20-02 / C20-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c20.test.ts` 中测试名包含 `TC-F2-20-02` |
| 前置与完整输入 | 旧票已核销、同会话仍有效；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 重开取新票再核销；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 新票成功一次、重放同票拒绝 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“新票独立”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C20/TC-F2-20-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-20-03：只读写拒绝


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-20 / AC-F2-20-03 / C20-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c20.test.ts` 中测试名包含 `TC-F2-20-03` |
| 前置与完整输入 | 合法readonly票据与正常源码；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 直接HTTP/WS写入；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 403且revision/hash不变 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“只读写拒绝”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C20/TC-F2-20-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-20-04：撤权清理


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-20 / AC-F2-20-04 / C20-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c20.test.ts` 中测试名包含 `TC-F2-20-04` |
| 前置与完整输入 | owner注销或工程删除；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 旧WS/页面继续访问；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 访问拒绝、进程关闭，不继续写回 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“撤权清理”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C20/TC-F2-20-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-21-01：同源嵌入


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-21 / AC-F2-21-01 / C21-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c21.contract.test.ts` 中测试名包含 `TC-F2-21-01` |
| 前置与完整输入 | 已认证同源父页；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 真实iframe加载Studio；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 可见编辑器、无frame拒绝或CSP错误 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“同源嵌入”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C21/TC-F2-21-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-21-02：外站嵌入


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-21 / AC-F2-21-02 / C21-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c21.contract.test.ts` 中测试名包含 `TC-F2-21-02` |
| 前置与完整输入 | 攻击origin加载相同会话URL；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | iframe/WS/写回各请求；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 分别被策略/鉴权拒绝，不扩大主站可嵌入性 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“外站嵌入”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C21/TC-F2-21-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-21-03：header伪造


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-21 / AC-F2-21-03 / C21-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c21.contract.test.ts` 中测试名包含 `TC-F2-21-03` |
| 前置与完整输入 | 无站内会话但伪造内部断言header；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 请求Studio资源；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 401/403且broker未接受伪造身份 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“header伪造”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C21/TC-F2-21-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-21-04：三入口保留


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-21 / AC-F2-21-04 / C21-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c21.contract.test.ts` 中测试名包含 `TC-F2-21-04` |
| 前置与完整输入 | 同一隔离栈三HTML及DH路由；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 逐入口登录/主题切换/媒体请求；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 既有安全头与功能不回退 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“三入口保留”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C21/TC-F2-21-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-22-01：真实画面


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-22 / AC-F2-22-01 / C22-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c22.test.ts` 中测试名包含 `TC-F2-22-01` |
| 前置与完整输入 | 两个不同revision，head为B；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 请求A preview并播放；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 看到A内容/时钟，B改动不污染 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“真实画面”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C22/TC-F2-22-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-22-02：素材Range


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-22 / AC-F2-22-02 / C22-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c22.test.ts` 中测试名包含 `TC-F2-22-02` |
| 前置与完整输入 | 会话授权视频资源；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 请求bytes=0-99与越权另项目资源；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 前者206且Content-Range正确，后者404 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“素材Range”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C22/TC-F2-22-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-22-03：缺版本


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-22 / AC-F2-22-03 / C22-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c22.test.ts` 中测试名包含 `TC-F2-22-03` |
| 前置与完整输入 | 不存在revision或缺必要素材；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | openPreview；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 明确失败/缺项，不回退当前head或空src |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“缺版本”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C22/TC-F2-22-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-22-04：关闭不杀Build


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-22 / AC-F2-22-04 / C22-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c22.test.ts` 中测试名包含 `TC-F2-22-04` |
| 前置与完整输入 | 同项目preview与build同时运行；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 关闭preview；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | preview资源撤销，Build继续直到自身终态 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“关闭不杀Build”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C22/TC-F2-22-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-23-01：opaque合法消息


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-23 / AC-F2-23-01 / C23-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c23.test.ts` 中测试名包含 `TC-F2-23-01` |
| 前置与完整输入 | sandbox真实iframe、正确source/session/nonce；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 发送frame0/time0；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 父页接收并显示0，不因origin=null拒绝 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“opaque合法消息”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C23/TC-F2-23-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-23-02：同域伪造


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-23 / AC-F2-23-02 / C23-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c23.test.ts` 中测试名包含 `TC-F2-23-02` |
| 前置与完整输入 | 同域另iframe发送正确session但错误source；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | postMessage；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 忽略，不改变播放状态 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“同域伪造”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C23/TC-F2-23-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-23-03：非法帧


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-23 / AC-F2-23-03 / C23-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c23.test.ts` 中测试名包含 `TC-F2-23-03` |
| 前置与完整输入 | 正确source发送NaN/负数/未知type；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 依次发送；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 全部忽略且无异常 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“非法帧”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C23/TC-F2-23-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-23-04：迟到open


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-23 / AC-F2-23-04 / C23-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c23.test.ts` 中测试名包含 `TC-F2-23-04` |
| 前置与完整输入 | open请求挂起后用户关闭/切项目；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 释放旧响应；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 不重开、不残留listener，close可重试 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“迟到open”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C23/TC-F2-23-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-24-01：有效批次


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-24 / AC-F2-24-01 / C24-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c24.test.ts` 中测试名包含 `TC-F2-24-01` |
| 前置与完整输入 | 初始空文档、两个合法add；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 提交同expectedHash；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 两条一次提交、hash更新，重读一致 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“有效批次”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C24/TC-F2-24-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-24-02：后项非法


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-24 / AC-F2-24-02 / C24-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c24.test.ts` 中测试名包含 `TC-F2-24-02` |
| 前置与完整输入 | 第一项合法add、第二项unknown operation；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 提交批次；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 失败且comments仍0、hash/字节完全不变 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“后项非法”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C24/TC-F2-24-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-24-03：并发CAS


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-24 / AC-F2-24-03 / C24-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c24.test.ts` 中测试名包含 `TC-F2-24-03` |
| 前置与完整输入 | 两个请求使用同hash；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 屏障同时提交；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 只有一批成功，另一409，无交织部分结果 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“并发CAS”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C24/TC-F2-24-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-24-04：响应丢失重试


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-24 / AC-F2-24-04 / C24-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c24.test.ts` 中测试名包含 `TC-F2-24-04` |
| 前置与完整输入 | 已提交但丢响应；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 同requestId重发；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 原回执、评论数不增加 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“响应丢失重试”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C24/TC-F2-24-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-25-01：真实字幕修改


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-25 / AC-F2-25-01 / C25-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C25IT.java` 中测试名包含 `TC-F2-25-01` |
| 前置与完整输入 | 原文件含字幕font-size=24px，评论指定32px；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 按评论修改并重读/预览；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 目标32px、其他结构不变、评论应用后resolved |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“真实字幕修改”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C25/TC-F2-25-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-25-02：无法定位


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-25 / AC-F2-25-02 / C25-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C25IT.java` 中测试名包含 `TC-F2-25-02` |
| 前置与完整输入 | 评论“让它更好看”无对象锚点；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | revise；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | waiting_input保留源码，不写一行注释 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“无法定位”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C25/TC-F2-25-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-25-03：校验失败


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-25 / AC-F2-25-03 / C25-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C25IT.java` 中测试名包含 `TC-F2-25-03` |
| 前置与完整输入 | 模型修复导致语法非法；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | apply流程；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | head/评论状态不变，诊断可见 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“校验失败”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C25/TC-F2-25-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-25-04：同文件冲突


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-25 / AC-F2-25-04 / C25-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C25IT.java` 中测试名包含 `TC-F2-25-04` |
| 前置与完整输入 | 同字幕两评论要求32px和48px；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 批量revise；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 明确冲突待用户选择，不随机覆盖 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“同文件冲突”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C25/TC-F2-25-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-26-01：终态映射


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-26 / AC-F2-26-01 / C26-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C26IT.java` 中测试名包含 `TC-F2-26-01` |
| 前置与完整输入 | Build finished/outcome=complete/resultReady=true；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | worker观察；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | variant succeeded；仅execution_decided不能提前成功 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“终态映射”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C26/TC-F2-26-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-26-02：路由实达


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-26 / AC-F2-26-02 / C26-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C26IT.java` 中测试名包含 `TC-F2-26-02` |
| 前置与完整输入 | 启用overlay、owner登录；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 经Edge POST retry/cancel；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 不404，执行正确服务端动作 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“路由实达”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C26/TC-F2-26-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-26-03：局部重试


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-26 / AC-F2-26-03 / C26-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C26IT.java` 中测试名包含 `TC-F2-26-03` |
| 前置与完整输入 | 批次一成功一失败；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 只重试失败项并重复同键；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 成功项build不变，失败项attempt只+1 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“局部重试”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C26/TC-F2-26-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-26-04：额度单因子


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-26 / AC-F2-26-04 / C26-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C26IT.java` 中测试名包含 `TC-F2-26-04` |
| 前置与完整输入 | variant_count=1的合法grant；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 并发两项build；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 第二项拒绝，Provider调用不超1 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“额度单因子”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C26/TC-F2-26-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-27-01：取消网络动作


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-27 / AC-F2-27-01 / C27-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c27.test.ts` 中测试名包含 `TC-F2-27-01` |
| 前置与完整输入 | 一running变体；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 点击取消；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 一次POST cancel而非仅GET，最终状态按服务端 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“取消网络动作”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C27/TC-F2-27-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-27-02：重试参数


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-27 / AC-F2-27-02 / C27-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c27.test.ts` 中测试名包含 `TC-F2-27-02` |
| 前置与完整输入 | failed item attempt1；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 重试并模拟网络超时再重放；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 同requestId，最终attempt2不变3 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“重试参数”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C27/TC-F2-27-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-27-03：缺授权


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-27 / AC-F2-27-03 / C27-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c27.test.ts` 中测试名包含 `TC-F2-27-03` |
| 前置与完整输入 | 远程变体无grant；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 点击build后取消授权；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | build请求0，页面保留变体 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“缺授权”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C27/TC-F2-27-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-27-04：批次部分失败


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-27 / AC-F2-27-04 / C27-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 组件；`src/views/video-clone/composables/fix2-c27.test.ts` 中测试名包含 `TC-F2-27-04` |
| 前置与完整输入 | 成功/失败/运行三项；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 可mock HTTP/时钟；必须使用正式DTO样本且运行真实组件/composable，不mock被测动作函数。Java/磁盘真实性由依赖IT和C37另证。 |
| 操作/并发顺序 | 刷新和操作失败项；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 成功项结果可用，运行项未被取消 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“批次部分失败”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-05；`test-artifacts/task-107/fix2/C27/TC-F2-27-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-28-01：二进制保真


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-28 / AC-F2-28-01 / C28-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c28.test.ts` 中测试名包含 `TC-F2-28-01` |
| 前置与完整输入 | 含PNG字节89504e470d0a1a0aff8000与合法MP4/字体的包；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 导出→导入→逐文件hash；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 所有字节一致，无UTF8替换字符损坏 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“二进制保真”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C28/TC-F2-28-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-28-02：无效Run拒绝


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-28 / AC-F2-28-02 / C28-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c28.test.ts` 中测试名包含 `TC-F2-28-02` |
| 前置与完整输入 | manifest合法但svrun内容非法；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 导入；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 失败且无ready工程/新head |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“无效Run拒绝”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C28/TC-F2-28-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-28-03：路径攻击


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-28 / AC-F2-28-03 / C28-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c28.test.ts` 中测试名包含 `TC-F2-28-03` |
| 前置与完整输入 | 分别构造../、绝对路径、symlink、重复entry；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 单因子导入；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 各被拒，staging外零写入 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“路径攻击”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C28/TC-F2-28-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-28-04：容量边界


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-28 / AC-F2-28-04 / C28-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c28.test.ts` 中测试名包含 `TC-F2-28-04` |
| 前置与完整输入 | 计数20000/20001，字节上限与+1；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 流式校验合成输入；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 上限符合策略、超限413，内存缓冲不随包大小增长 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“容量边界”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C28/TC-F2-28-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-29-01：导入可重开


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-29 / AC-F2-29-01 / C29-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C29IT.java` 中测试名包含 `TC-F2-29-01` |
| 前置与完整输入 | owner A导出，owner B上传；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 列表/GET/编辑/渲染新project；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | B拥有新工程且文件一致，A权限不继承 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“导入可重开”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C29/TC-F2-29-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-29-02：并发同键


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-29 / AC-F2-29-02 / C29-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C29IT.java` 中测试名包含 `TC-F2-29-02` |
| 前置与完整输入 | 两个相同requestId和内容；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 并发导入；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 仅一个project/revision/job及磁盘目录 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“并发同键”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C29/TC-F2-29-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-29-03：PG收敛恢复


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-29 / AC-F2-29-03 / C29-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C29IT.java` 中测试名包含 `TC-F2-29-03` |
| 前置与完整输入 | broker发布后注入Java崩溃；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 重启并重放；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 登记同project，不复制文件或漏owner |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“PG收敛恢复”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C29/TC-F2-29-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-29-04：同键异内容


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-29 / AC-F2-29-04 / C29-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C29IT.java` 中测试名包含 `TC-F2-29-04` |
| 前置与完整输入 | 第一次包hash=A、第二次B；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 重用requestId；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 409，原工程与回执不变 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“同键异内容”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C29/TC-F2-29-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-30-01：点击下载


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-30 / AC-F2-30-01 / C30-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C30IT.java` 中测试名包含 `TC-F2-30-01` |
| 前置与完整输入 | 已完成工程；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | UI点击导出并等待浏览器download；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 拿到可解压zip，manifest/hash正确，不只是HTTP202 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“点击下载”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C30/TC-F2-30-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-30-02：UI导入闭环


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-30 / AC-F2-30-02 / C30-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C30IT.java` 中测试名包含 `TC-F2-30-02` |
| 前置与完整输入 | 下载的zip；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 通过文件控件上传；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 新owner工程出现、可打开并原生check |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“UI导入闭环”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C30/TC-F2-30-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-30-03：下载越权


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-30 / AC-F2-30-03 / C30-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C30IT.java` 中测试名包含 `TC-F2-30-03` |
| 前置与完整输入 | 有效exportId属于A；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | B或匿名GET；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 404/401，无字节；不能通过路径猜测绕过 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“下载越权”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C30/TC-F2-30-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-30-04：流与中断


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-30 / AC-F2-30-04 / C30-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C30IT.java` 中测试名包含 `TC-F2-30-04` |
| 前置与完整输入 | 大包上传中断；下载Range请求；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 中断重试/部分下载；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 无残留ready假工程，206字节正确，内存缓冲有界 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“流与中断”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C30/TC-F2-30-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-31-01：实际交接


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-31 / AC-F2-31-01 / C31-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C31IT.java` 中测试名包含 `TC-F2-31-01` |
| 前置与完整输入 | 从已固化媒体入口带sourceContext；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 创建后进入参考素材；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 资产可见且broker读取字节hash与源一致 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“实际交接”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C31/TC-F2-31-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-31-02：文件上传


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-31 / AC-F2-31-02 / C31-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C31IT.java` 中测试名包含 `TC-F2-31-02` |
| 前置与完整输入 | 256MiB以内合法视频；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 通过file input上传并分析；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | asset落库、可probe，进度与错误真实 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“文件上传”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C31/TC-F2-31-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-31-03：超限假MIME


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-31 / AC-F2-31-03 / C31-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C31IT.java` 中测试名包含 `TC-F2-31-03` |
| 前置与完整输入 | 256MiB+1；另一个扩展mp4但内容脚本；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 分别上传；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 413或422，无ready资产/可执行内容 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“超限假MIME”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C31/TC-F2-31-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-31-04：归属与重复


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-31 / AC-F2-31-04 / C31-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C31IT.java` 中测试名包含 `TC-F2-31-04` |
| 前置与完整输入 | 他人mediaId；本人同文件重复；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 交接/上传；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 越权404；本人重复不产生重复资源字节 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“归属与重复”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C31/TC-F2-31-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-32-01：真实活动计数


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-32 / AC-F2-32-01 / C32-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c32.test.ts` 中测试名包含 `TC-F2-32-01` |
| 前置与完整输入 | 一dispatching、一acknowledged、一active原生build；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | enter维护；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 不能立即宣称drained，等待实际完成 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“真实活动计数”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C32/TC-F2-32-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-32-02：封写先于等待


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-32 / AC-F2-32-02 / C32-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c32.test.ts` 中测试名包含 `TC-F2-32-02` |
| 前置与完整输入 | enter与新submit同时过屏障；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 并发发请求；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 栅栏后新副作用503，栅栏前任务计入排空 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“封写先于等待”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C32/TC-F2-32-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-32-03：备份异常恢复


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-32 / AC-F2-32-03 / C32-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c32.test.ts` 中测试名包含 `TC-F2-32-03` |
| 前置与完整输入 | pg_dump立即失败；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | 运行backup；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 非零且释放自己维护租约，不留永久503 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“备份异常恢复”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C32/TC-F2-32-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-32-04：维护所有权


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-32 / AC-F2-32-04 / C32-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | Node 集成；`platform-hypit/backend/tests/agent-integration/fix2-c32.test.ts` 中测试名包含 `TC-F2-32-04` |
| 前置与完整输入 | 维护已由会话A持有；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 临时目录/可控时钟；真实broker模块、文件字节和引擎执行。外部Provider仅fixture；隔离断言必须Docker真实执行，不能mock spawn。 |
| 操作/并发顺序 | B备份失败退出；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 不能解除A维护，日志明确ownership |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“维护所有权”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-02；`test-artifacts/task-107/fix2/C32/TC-F2-32-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-33-01：完整范围


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-33 / AC-F2-33-01 / C33-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c33.contract.test.ts` 中测试名包含 `TC-F2-33-01` |
| 前置与完整输入 | 隔离数据伞含projects/resources/results；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 执行backup；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | manifest覆盖所有必要文件和PG dump，逐hash一致 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“完整范围”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C33/TC-F2-33-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-33-02：排除与秘密


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-33 / AC-F2-33-02 / C33-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c33.contract.test.ts` 中测试名包含 `TC-F2-33-02` |
| 前置与完整输入 | 含临时slot和凭据存储；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 生成包并检查输出；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 临时项明确omitted，日志无secret，包权限受限 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“排除与秘密”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C33/TC-F2-33-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-33-03：中途失败


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-33 / AC-F2-33-03 / C33-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c33.contract.test.ts` 中测试名包含 `TC-F2-33-03` |
| 前置与完整输入 | tar/压缩进程注入失败；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | backup；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 非零、无complete标记、恢复维护 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“中途失败”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C33/TC-F2-33-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-33-04：并发写保护


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-33 / AC-F2-33-04 / C33-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c33.contract.test.ts` 中测试名包含 `TC-F2-33-04` |
| 前置与完整输入 | 备份窗口试图写Studio与上传；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 请求写入；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 拒绝或排队到窗口后，备份内部无混合revision |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“并发写保护”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C33/TC-F2-33-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-34-01：异名目标


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-34 / AC-F2-34-01 / C34-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c34.contract.test.ts` 中测试名包含 `TC-F2-34-01` |
| 前置与完整输入 | backup顶层hypit，TARGET_ROOT=restore-a；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 恢复；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 内容位于restore-a内，无旁边误落目录 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“异名目标”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C34/TC-F2-34-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-34-02：SQL错误拒绝


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-34 / AC-F2-34-02 / C34-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c34.contract.test.ts` 中测试名包含 `TC-F2-34-02` |
| 前置与完整输入 | 恢复核验SQL/连接失败；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 执行核验；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 非零，不输出missing=0冒充通过 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“SQL错误拒绝”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C34/TC-F2-34-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-34-03：内容缺失


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-34 / AC-F2-34-03 / C34-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c34.contract.test.ts` 中测试名包含 `TC-F2-34-03` |
| 前置与完整输入 | manifest列出一个被删除snapshot；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 恢复后核验；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 明确缺失路径类别及计数，禁止ready |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“内容缺失”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C34/TC-F2-34-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-34-04：完整重开


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-34 / AC-F2-34-04 / C34-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-c34.contract.test.ts` 中测试名包含 `TC-F2-34-04` |
| 前置与完整输入 | PG+文件恢复到新栈；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 浏览器打开工程和结果；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | owner/revision/hash一致、无新Provider调用 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“完整重开”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C34/TC-F2-34-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-35-01：删除在途


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-35 / AC-F2-35-01 / C35-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C35IT.java` 中测试名包含 `TC-F2-35-01` |
| 前置与完整输入 | 工程有preview、Studio、上传和job；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | owner删除；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 会话撤销/写拒绝/独占临时物清理，结果按既有保留策略 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“删除在途”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C35/TC-F2-35-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-35-02：共享保护


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-35 / AC-F2-35-02 / C35-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C35IT.java` 中测试名包含 `TC-F2-35-02` |
| 前置与完整输入 | 两个工程引用同一固化media；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 删其中一个；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 另一工程仍可读取，媒体不误删 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“共享保护”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C35/TC-F2-35-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-35-03：清理重试


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-35 / AC-F2-35-03 / C35-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C35IT.java` 中测试名包含 `TC-F2-35-03` |
| 前置与完整输入 | 第一次文件删除权限故障；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 恢复权限后调度重试；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 最终清理且不重复副作用，失败可观测 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“清理重试”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C35/TC-F2-35-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-35-04：换号迟到


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-35 / AC-F2-35-04 / C35-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 服务/契约集成；`platform-java/services/intelligence-service/src/test/java/com/grassland/intelligence/hypit/fix2/HypitFix2C35IT.java` 中测试名包含 `TC-F2-35-04` |
| 前置与完整输入 | A请求挂起后切B；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实Controller/service/序列化与PG约束；下游外部模型可fixture，broker契约须正式样本及独立原生链验证，不mock本卡业务判断。 |
| 操作/并发顺序 | 释放A响应；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | B页面无A数据/票据，服务端B不能读A资源 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“换号迟到”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-03；`test-artifacts/task-107/fix2/C35/TC-F2-35-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-36-01：主题视口矩阵


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-36 / AC-F2-36-01 / C36-E01；中高：用户状态/交互与对象隔离 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-c36.spec.ts` 中测试名包含 `TC-F2-36-01` |
| 前置与完整输入 | 合成复杂工程、两主题三视口；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | 逐四阶段截图并实际查看；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 无裁剪/低对比/整页横向滚动，截图目标确为工作区 |
| 副作用与重读 | UI专项截图要实际查看；keyboard断言activeElement/焦点返回；不以截图替代接口或PG通过。 |
| 防假阳性 | 使“主题视口矩阵”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-09；`test-artifacts/task-107/fix2/C36/TC-F2-36-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-36-02：键盘流程


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-36 / AC-F2-36-02 / C36-E02；中高：用户状态/交互与对象隔离 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-c36.spec.ts` 中测试名包含 `TC-F2-36-02` |
| 前置与完整输入 | 不使用鼠标；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | 新建→授权弹窗→取消→保存；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 焦点正确、Esc可退出、返回触发按钮 |
| 副作用与重读 | UI专项截图要实际查看；keyboard断言activeElement/焦点返回；不以截图替代接口或PG通过。 |
| 防假阳性 | 使“键盘流程”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-09；`test-artifacts/task-107/fix2/C36/TC-F2-36-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-36-03：错误与加载


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-36 / AC-F2-36-03 / C36-E03；中高：用户状态/交互与对象隔离 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-c36.spec.ts` 中测试名包含 `TC-F2-36-03` |
| 前置与完整输入 | 定向延迟/失败仅用于状态专项；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | 打开各面板及提交；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 保留输入、有retry、旧数据注明更新失败，不伪空 |
| 副作用与重读 | UI专项截图要实际查看；keyboard断言activeElement/焦点返回；不以截图替代接口或PG通过。 |
| 防假阳性 | 使“错误与加载”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-09；`test-artifacts/task-107/fix2/C36/TC-F2-36-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-36-04：长内容边界


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-36 / AC-F2-36-04 / C36-E04；中高：用户状态/交互与对象隔离 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-c36.spec.ts` 中测试名包含 `TC-F2-36-04` |
| 前置与完整输入 | 120字标题、255字符路径、长错误；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | 390px视口操作；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 主要动作可达，容器内部滚动不裁掉内容 |
| 副作用与重读 | UI专项截图要实际查看；keyboard断言activeElement/焦点返回；不以截图替代接口或PG通过。 |
| 防假阳性 | 使“长内容边界”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-09；`test-artifacts/task-107/fix2/C36/TC-F2-36-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-37-01：创作主流程


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-37 / AC-F2-37-01 / C37-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-journey.spec.ts` 中测试名包含 `TC-F2-37-01` |
| 前置与完整输入 | A合成owner、12秒参考素材；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | UI完成分析→方案→制作→成片下载；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 源锚点/字幕/时长可核验，MP4可解码，不靠mock API成功 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“创作主流程”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-09；`test-artifacts/task-107/fix2/C37/TC-F2-37-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-37-02：Studio往返


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-37 / AC-F2-37-02 / C37-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-journey.spec.ts` 中测试名包含 `TC-F2-37-02` |
| 前置与完整输入 | 已有制作工程；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | iframe编辑保存→刷新→重开；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 新revision持久、WS正常，无鉴权/CSP错误 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“Studio往返”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-09；`test-artifacts/task-107/fix2/C37/TC-F2-37-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-37-03：审片变体


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-37 / AC-F2-37-03 / C37-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-journey.spec.ts` 中测试名包含 `TC-F2-37-03` |
| 前置与完整输入 | 成片及两变体；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | 评论修改→重生成；失败项重试；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 目标内容变化、成功项不重做，状态收敛 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“审片变体”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-09；`test-artifacts/task-107/fix2/C37/TC-F2-37-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-37-04：工程包移交


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-37 / AC-F2-37-04 / C37-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-journey.spec.ts` 中测试名包含 `TC-F2-37-04` |
| 前置与完整输入 | A导出zip，B新账号；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | UI导入再渲染；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 同字节/自己的owner/可继续编辑，三浏览器通过 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“工程包移交”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-09；`test-artifacts/task-107/fix2/C37/TC-F2-37-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-38-01：租约接管


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-38 / AC-F2-38-01 / C38-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-recovery.spec.ts` 中测试名包含 `TC-F2-38-01` |
| 前置与完整输入 | 双worker、一个执行中被kill；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | 等待接管并重读；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 同operation不重复收费，只有合法owner收敛终态 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“租约接管”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-10；`test-artifacts/task-107/fix2/C38/TC-F2-38-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-38-02：断网结果未知


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-38 / AC-F2-38-02 / C38-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-recovery.spec.ts` 中测试名包含 `TC-F2-38-02` |
| 前置与完整输入 | Provider fixture已接受后断broker网络；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | 恢复连接重启；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | unknown→查询原receipt，不新提交 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“断网结果未知”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-10；`test-artifacts/task-107/fix2/C38/TC-F2-38-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-38-03：完整灾备


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-38 / AC-F2-38-03 / C38-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-recovery.spec.ts` 中测试名包含 `TC-F2-38-03` |
| 前置与完整输入 | 运行中有历史作品和两revision；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | 排空备份→新PG/卷恢复→浏览器重开；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 所有必要hash一致、无新generation、原栈不受影响 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“完整灾备”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-10；`test-artifacts/task-107/fix2/C38/TC-F2-38-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-38-04：并发文件反馈


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-38 / AC-F2-38-04 / C38-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 浏览器 E2E；`tests/e2e/hypit-fix2-recovery.spec.ts` 中测试名包含 `TC-F2-38-04` |
| 前置与完整输入 | 两客户端同head/hash；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 真实浏览器/入口/服务/PG/文件/原生引擎；仅外部模型最后一跳fixture。状态专项拦截需单列且不得代替成功业务链。 |
| 操作/并发顺序 | 受控同时保存和反馈修改；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | CAS与批次原子性保持，不混合丢写 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“并发文件反馈”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-10；`test-artifacts/task-107/fix2/C38/TC-F2-38-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-39-01：缺阶段拒绝


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-39 / AC-F2-39-01 / C39-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-gates.contract.test.ts` 中测试名包含 `TC-F2-39-01` |
| 前置与完整输入 | 删掉E2E或restore证据；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 运行--stage all；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 非零且列缺项，不宣称全任务VERIFIED |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“缺阶段拒绝”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C39/TC-F2-39-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-39-02：四项故障捕获


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-39 / AC-F2-39-02 / C39-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-gates.contract.test.ts` 中测试名包含 `TC-F2-39-02` |
| 前置与完整输入 | 隔离副本分别启用四个缺陷；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 跑对应门禁；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 四次均失败于目标断言，恢复后通过 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“四项故障捕获”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C39/TC-F2-39-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-39-03：意外skip拒绝


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-39 / AC-F2-39-03 / C39-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-gates.contract.test.ts` 中测试名包含 `TC-F2-39-03` |
| 前置与完整输入 | 必需spec含skip或过滤为0；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 运行门禁；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 失败，不能靠npm退出0放行 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“意外skip拒绝”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C39/TC-F2-39-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-39-04：分层标签


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-39 / AC-F2-39-04 / C39-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-gates.contract.test.ts` 中测试名包含 `TC-F2-39-04` |
| 前置与完整输入 | 无真实Provider授权但本地全部通过；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 汇总；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 明确LOCAL_PASS/LIVE_NOT_RUN，绝无全平台全量通过表述 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“分层标签”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C39/TC-F2-39-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-40-01：35组闭合


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-40 / AC-F2-40-01 / C40-E01；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-spec.contract.test.ts` 中测试名包含 `TC-F2-40-01` |
| 前置与完整输入 | 当前代码和所有卡证据；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 逐finding逆向核对；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 无遗漏/悬空TC/失效证据，所有必需门禁通过 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“35组闭合”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C40/TC-F2-40-01/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-40-02：真实产物复查


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-40 / AC-F2-40-02 / C40-E02；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-spec.contract.test.ts` 中测试名包含 `TC-F2-40-02` |
| 前置与完整输入 | 不依赖实施者完成文字；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 重新打开样片、工程包、Studio及恢复栈；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 产物可消费且来源/owner/版本一致 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“真实产物复查”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C40/TC-F2-40-02/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-40-03：状态诚实


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-40 / AC-F2-40-03 / C40-E03；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-spec.contract.test.ts` 中测试名包含 `TC-F2-40-03` |
| 前置与完整输入 | 故意保留一个必需FAIL/NOT_RUN；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 尝试完成判定；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 不得标VERIFIED；明确责任卡和解除条件 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“状态诚实”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C40/TC-F2-40-03/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


#### TC-F2-40-04：范围与干净克隆


| 项目 | 确定内容 |
| --- | --- |
| 追踪/风险 | REQ-F2-40 / AC-F2-40-04 / C40-E04；高：权限/数据/费用/恢复或主要业务链路 |
| 层级/实现 | 部署/脚本；`tests/deployment/hypit-fix2-spec.contract.test.ts` 中测试名包含 `TC-F2-40-04` |
| 前置与完整输入 | 任务diff、初始108文档和upstream；沿§5.1规范和§9.3合成数据，需合法基础字段的负向用例仅破坏本组目标条件，其他条件均满足。 |
| 替身边界 | 静态契约可读配置；涉及启动/备份/恢复/退出码必须运行实际脚本于隔离栈。错误注入可替换命令出口，但不得mock所测fail-closed判定。 |
| 操作/并发顺序 | 检查白名单/重建命令；按该顺序执行；“同时”使用开始/提交屏障，时间条件用固定时钟推进。 |
| 展示/响应与终态 | 他人改动保留，上游冻结，复现不依赖本机忽略产物 |
| 副作用与重读 | 观察规定的唯一ID、owner、revision/hash、行数、外部调用计数/费用与资源清理；只允许Then明确的变化。涉及持久写入必须用新请求/新事务重读；失败检查原字节与行数。 |
| 防假阳性 | 使“范围与干净克隆”对应保护或接线失效时，本组Then断言必须失败；确认请求/handler/脚本真实被触发。不得由另一个拒绝条件提前挡住而伪绿。 |
| 清理/证据 | 只清本组隔离fixture、临时目录与监听器；V-07、V-04、V-06；`test-artifacts/task-107/fix2/C40/TC-F2-40-04/` 留脱敏响应/必要PG或文件hash/日志；NOT_RUN无退出码。 |


### 12.3 唯一验证命令表

命令为实施计划，不表示本书生成时已执行。根目录用bash；日志保留真实exit code，不用管道末端成功遮蔽前段失败。NEW包装入口由C01建立、各责任卡完成对应stage；未交付stage必须退出2。前置已满足时预期退出0且目标用例实际运行；指定反例运行预期非零，由外层测试断言其正确拒绝。

| V | cwd | 精确命令/操作 | 前提/副作用 | 成功判据/证据 |
|---|---|---|---|---|
| V-01 | 根 | bash scripts/acceptance/verify-107-upstream.sh | 只读vendor，比较manifest | 1787 paths匹配；logs/upstream.log |
| V-02 | platform-hypit/backend | npm run typecheck；npx --yes --package=node@24.14.1 -- node scripts/run-tests.mjs | npm ci且G按现有build脚本物化；不自动使用Node22替代 | 类型0、全部实际发现、零未登记skip；backend日志 |
| V-03 | platform-java | bash -c 'source ../scripts/lib/java-runtime.sh; ensure_java_runtime 25 || exit 1; ./gradlew :services:intelligence-service:test --tests "com.grassland.intelligence.hypit.*" --rerun-tasks' | JDK25、Docker/Testcontainers；真实PG | 目标类含全部fix2 IT，失败0；JUnit与stdout |
| V-04 | 根 | npx vitest run src/views/video-clone tests/deployment/hypit-compose.contract.test.ts tests/deployment/hypit-entrypoint.contract.test.ts tests/deployment/hypit-fix2-spec.contract.test.ts tests/deployment/hypit-fix2-gates.contract.test.ts | 根锁定依赖、目标测试已落地 | 前端和契约真实发现、0失败；Vitest日志 |
| V-05 | 根 | npm run typecheck；npm run lint；npm run build | 三入口共用构建，不自动格式化无关文件 | 各退出0，800行门禁保持；logs/frontend-quality |
| V-06 | 根 | npm run quality:lifecycle；npm run security:secrets；git diff --check | 检查新增未跟踪文件秘密需另行扫描；现有registry | 无新增漏洞/漂移；日志与diff摘要 |
| V-07 | 根 | bash scripts/acceptance/verify-107-fix-2.sh --stage card --card C107F2-01 | NEW入口；其他卡将最后参数换成本书精确卡号，完整展开命令在§11；只跑该卡目标与共同契约测试 | 该卡四组及边界展开均实际执行；Cxx/results.json |
| V-08 | 根 | bash scripts/acceptance/verify-107-fix-2.sh --stage local | 创建隔离栈、合成账号/媒体、真API原生render；无商业Provider | S1与TC08通过、ffprobe/三帧/hash/PG重读；local/ |
| V-09 | 根 | bash scripts/acceptance/verify-107-fix-2.sh --stage e2e | NEW入口内部调用既有ci-e2e；真实UI，三引擎；需要全部M1–M4 | S2–S5、TC36/37，无目标skip，MP4/ZIP可消费；e2e/ |
| V-10 | 根 | bash scripts/acceptance/verify-107-fix-2.sh --stage recovery | 在任务隔离栈kill/重启、维护、备份→新PG/卷恢复，不能操作主栈 | TC38与完整PG/文件校验0异常、0新generation；recovery/ |
| V-11 | 根 | bash scripts/acceptance/verify-107-fix-2.sh --stage all | 合同/本地/E2E/恢复都完成，解析实际报告；不包含未授权LIVE | 全部必需层真实通过、35闭合、零漏跑；summary.json |
| V-12 | 根 | npm run docs:links；npm run docs:status | 只文档检查；基线异常单列不能算新PASS | 本书/索引无新增断链状态矛盾；spec/docs-*.log |
| V-13 | 根 | npx @google/design.md lint DESIGN.md；npx @google/design.md lint src/ops/DESIGN.md | 设计文件只读；可能下载锁定工具缓存 | lint成功；AGENTS禁词检查无输出且exit1，不把执行错误当无匹配 |
| V-14 | platform-java | bash -c 'source ../scripts/lib/java-runtime.sh; ensure_java_runtime 25 || exit 1; ./gradlew :services:edge-bff:check :services:intelligence-service:check --rerun-tasks' | 真实服务门禁含既有覆盖率/格式，按影响面保留旧测试 | 实跑0失败，未降低阈值；Gradle报告 |
| V-15 | 根 | npm run test:coverage；npm run coverage:changed | 最终共享HTTP/UI契约回归及变更行门槛；按脚本实际基线参数记录 | 既有门槛与变更行≥80%，不删分母；coverage/ |

卡级只运行V-07实际卡目标与已落地依赖回归，包装器按W表的测试文件分派Node/Vitest/Gradle/Playwright，并执行受影响层typecheck。V02/V03/V04/V09等总集只在它们涉及的文件与前置全部完成后运行；禁止前卡等待未来卡尚未创建的测试或业务链，因此不会形成隐式循环依赖。M1后运行V08，M6运行V09/V10/V11。V15只在最终公共契约影响面跑一次；失败/新增改动才重验对应范围，不机械每卡全量。V02发现已有外部skip必须列出名字和非本书必需的依据；本书新增TC及必需集成绝不skip。

### 12.4 命令与环境准备

已有入口已核对package.json、backend/scripts/run-tests.mjs、Gradle wrapper、Playwright三引擎和ci-e2e脚本。npx精确Node24仅提供测试runtime，不更改repo依赖。G物化使用 `bash scripts/acceptance/build-107-engine.sh --no-install` 后按脚本/锁文件安装所需引擎依赖；build/test遇缺依赖应正常补齐而非跳过。

NEW verify脚本参数冻结：--stage card/local/e2e/recovery/all；card必须--card C107F2-01…40，未知参数exit2；默认不启动任何付费Provider。stage e2e/recovery/all需要fixture栈且缺Docker/认证seed非零。stdout和results.json都记录任务版本/commit+相关diff摘要、运行时间、Node/Java/镜像digest、实际发现的TC、失败/skip/NOT_RUN。exit0不能掩盖summary缺项。

### 12.5 最终集成出口

C40/当前执行者负责，不把“最终验收”留给未指定的人。必须完成：

| Gate | 真实证明对象 | 必需证据 |
|---|---|---|
| G1 部署与原生引擎 | 当前启用组合/三入口/真runner/计划冻结/授权/原生MP4 | V01/08、容器PID/标签、完整trace、ffprobe/帧 |
| G2 UI制作 | 真实按钮触发、全片分析/方案/作者/生成/取消/恢复 | V09、UI操作trace、正式网络、PG终态 |
| G3 编辑预览审片 | Studio页面/WS/写回、Preview时钟/资源、语义修复 | iframe目标截图、版本hash、评论映射 |
| G4 变体结果 | 正确lifecycle/outcome、局部重试、归档和下载 | attempt/buildId、mediaId、真实文件 |
| G5 移植 | 浏览器导出zip→B owner上传→重开/编辑/渲染 | 全部必要文件hash、owner/PG、二次样片 |
| G6 可靠性灾备 | 并发/租约/费用/部分写/重启/PG+文件恢复 | V10、故障屏障、唯一operationId、恢复核验 |
| G7 规范和范围 | 三浏览器/双主题/键盘、质量/秘密/生命周期/文档 | V04–06、12–15，W diff核对 |

必需Gate任一FAIL/PARTIAL/NOT_RUN/SKIPPED均不允许本书VERIFIED。外部模型fixture只证明编排/传参/消费链，不能证明真实商业模型质量；LIVE未授权时独立标NOT_RUN。本地本书VERIFIED的表述必须限定“107-fix-2本地交付验收”，不得扩大成“107所有Provider已商用验证/生产已上线”。

### 12.6 发布与回滚

本书包含可部署配置但不授权生产发布。隔离验证顺序：备份基线→加性V92→broker/runner→Java→Edge→Nginx/frontend→显式开关→真实smoke。任何鉴权绕过/重复收费/文件hash错误/无法原生渲染立即停止放行。回滚先停止新任务与撤会话，等已接受操作收据收敛，回兼容应用/配置；不DROPV92、不覆盖已生成revision，不靠恢复旧PG掩盖外部已收费事实。恢复演练只新库/卷。

用户后续若明确要求修当前y-1活跃栈，先输出实际compose差异/影响服务/卷保留/备份与回滚计划，在授权范围内执行；本轮写书不改运行环境。

### 12.7 边界目录

| E | 场景 | 责任卡/确定用例 |
|---|---|---|
| E01/E02 | 空/超长输入 | §5.1共同矩阵、TC10-04/TC31-03 |
| E03 | 连点/重复提交 | TC05-03/TC12-03/TC18-04/TC29-02 |
| E04/E05 | 断网/服务端或外部失败 | TC13-03/TC15-02/TC19-03/TC38-02 |
| E06/E07 | 未登录/权限/非法状态 | TC09-03/TC20-03/TC26-04及共同API矩阵 |
| E08 | 成功但无数据 | TC12-04/TC36-03，不能与error合并 |
| E09 | 版本冲突/旧数据 | TC06-03/TC10-03/TC16-04 |
| E10 | 刷新/深链/离开恢复 | TC08-02/TC11-01/03/TC18-04 |
| E11/E12 | A→B→A、卸载/隐藏与资源释放 | TC11-02/04/TC23-04/TC35-04 |
| E13/E14 | 缺省/null/0/false/枚举/上下界 | §5.1逐端点矩阵、TC23-01/03/TC28-04 |
| E15/E16 | 并发乱序/超时但已提交 | TC10-02/TC15-02/03/TC24-03/04 |
| E17/E18 | 跨账号/撤权/删除清理 | TC08-03/TC20-04/TC30-03/TC35全组 |
| E19 | 旧客户端/旧数据 | TC05-04/TC13-04、§6兼容字段与§7迁移 |
| E20 | 部分成功/重放/崩溃 | TC24-02/TC27-04/TC29-03/TC38全组 |
| E21 | 时间/费用精度 | TC07-03/04、票59/60/61秒及金额0/0.000001/上限±0.000001共同矩阵；不新增汇率业务 |
| E22 | 大文件/分页/设备 | TC11-01/TC28-04/TC30-04/TC36-01/04 |

## 13. 阻塞、修订与恢复

### 13.1 实质阻塞条件

确需未定义公开接口/权限/数据或费用规则、必要写入不在W、迁移号实际冲突、无法保留他人重叠改动、真实账号/商业Provider/生产动作无授权，或本地排查后确实缺必需环境时停止受影响卡。路径行号变化但符号语义相同可继续；已登记新增文件/配置、常规下载/测试/隔离启动不用逐项批准。普通编码失败在卡内修复。

### 13.2 对话阻塞记录

写明版本/卡号/阻塞类型、具体失败命令与退出码、已确认源码/环境、影响下游、已尝试步骤、最小缺项、一个推荐决策、已保留的diff和下一步。无实际执行不编造错误；只有缺LIVE不应阻塞可独立的本地卡，也不能借缺LIVE免除本地原生/E2E。

### 13.3 修订

本书允许精确登记的内部实现等价调整，不能改业务不变量。新增公开字段/写入文件/迁移号需先修订相关§3/6/7/9/11/12并记录版本、理由和影响，再继续；已有用户明确决定直接沿用不重复问。仅环境恢复且契约未变则留证继续。发现其他无关缺陷记交接，不扩大成全仓重构。

## 14. 执行与续作交接

每卡对话输出：卡号/状态、实际变化、W文件、TC/V命令及cwd、真实退出码/测试数、PG/文件/媒体重读、截图路径与实际查看结论、已保留旧改动、未完成项。终态汇总35项关闭情况与7个Gate、LIVE/生产分层状态。不额外新建独立完成/交付报告；工具生成results.json/JUnit/日志/截图属于验收证据，保留在登记目录。

### 14.1 换模型/中断检查点

在对话记录：任务书路径/版本、授权卡范围、当前commit与相关diff、已VERIFIED卡及有效证据、当前卡完成/未完成步骤、实际PASS/FAIL/PARTIAL/NOT_RUN/SKIPPED、最新决策修订、原改动保留边界、隔离栈/端口/卷标记、只存在本机证据和重建命令、阻塞解除条件、下一张可执行卡与第一条具体命令。接手者检查事实，不从头盲重做、不继承无证据完成声明。

## 附 A：返工回流规则

集成发现缺陷回到原C卡或登记R-F2-序号，必须有单因子复现、影响契约/W、修复步骤、受影响下游与重验命令。保留初次失败；原卡证据失效则回IN_PROGRESS/IMPLEMENTED，不直接用“已知问题”豁免必需Gate。不得在最后集成卡无限改白名单外文件。

## 附 B：规格发布检查与版本记录

### B.1 本次已核验

- [x] 已按模板v3.0.0保留0–14适用章节，删除作者提示和执行提示词占位。
- [x] 35组审计问题在正文重述，不依赖本机忽略报告才能理解；40卡、160组用例可追溯。
- [x] 有真实入口/调用者/输出消费，明确既有helper未接线不能算完成。
- [x] 规格就绪、NOT_STARTED、卡级VERIFIED、本地任务VERIFIED、LIVE与生产发布分别裁决。
- [x] public/internal契约、错误、状态、幂等、钱/时间/文件上限、迁移和回滚明确，新增与现状区分。
- [x] W逐文件登记，卡引用子集；默认串行无共享写入冲突，依赖编号全小于本卡且无循环。
- [x] 必需Docker/浏览器/PG/原生产物与允许外部fixture边界明确，不能局部全绿冒充整体。
- [x] UI双主题/三视口/键盘/加载空错提交中、三入口回归及生命周期覆盖。
- [x] 普通环境准备可自主，真实资金/生产/破坏性清理无隐含授权，原108改动保留。
- [x] 命令来自现有脚本或明确NEW责任卡，未运行未来测试均NOT_RUN，不预填完成。
- [x] 全局字段/命令单一维护，卡有输入/输出/前置/读写/步骤/边界/验收/交接。

### B.2 版本

| 版本 | 日期 | 原因与范围 | 实施状态 |
|---|---|---|---|
| 1.0.0 | 2026-09-27 | 依据用户要求将35组审计问题转为40卡/160组用例，按模板完成契约/范围/集成与恢复规格；本次仅新增本书和索引 | NOT_STARTED |
| 1.0.1 | 2026-09-28 | §13.3 内部等价修订（卡 C107F2-09）：`src/views/video-clone/VideoCloneWorkbench.test.ts` 不在 W077 清单内，但其中两用例断言的正是 F05 旧缺陷行为（disabled capabilities 下 clone-empty 可见）与被门态前置改变的加载锚点；随 C09 落地把用例 1 的 capabilities mock 翻为 enabled:true（disabled 断言迁移给 TC-F2-09-01 专测）、用例 3 等待锚从 clone-loading 消失改为 clone-new-project 出现。不改业务不变量，契约无变化 | IN_PROGRESS |
| 1.0.2 | 2026-09-28 | §13.3 内部等价修订（卡 C107F2-10）：`src/views/video-clone/composables/useHypitSource.test.ts` 不在 W083 清单内，但其 file GET mock 是 F09 缺陷形态（仅 baseHash 无 hash/revision）；随 C10 契约落地把 mock 迁为正式字段 hash/revision，被测行为（save 坏语法/validated 拒绝/409 冲突）不变 | IN_PROGRESS |
| 1.0.3 | 2026-09-28 | §13.3 内部等价修订（卡 C107F2-11）：`src/views/video-clone/composables/hypit-api.test.ts` 的 AbortSignal 用例按旧签名 `listProjects(signal)` 传参；随 C11 §5.3 分页落地 listProjects 增设 cursor 首参，调用迁为 `listProjects(undefined, signal)`。被测不变量（abort 传播到 fetch、fetch 恰一次）不变 | IN_PROGRESS |
| 1.0.4 | 2026-09-28 | §13.3 内部等价修订（卡 C107F2-12）：`platform-java/.../hypit/api/HypitContractTest.java` 不在 W094 清单内，但其手动装配的 buildController Bean 随 W094 list()/get() 注入 `HypitOutputRepository outputIndex`（Build 列表/详情带归档统计）而需补构造参数与对应 mock Bean `hypitOutputRepository()`，否则编译失败。纯装配锚点迁移，被测契约断言不变 | IN_PROGRESS |
| 1.0.5 | 2026-09-28 | §13.3 内部等价修订（卡 C107F2-13）：`src/views/video-clone/composables/useHypitJobs.test.ts` 不在 W103 清单内，但其 EventSource 受控替身断言的是 F24 旧缺陷形态（隐式重连不带 Last-Event-ID、payload 裸业务对象）；随 W103 fetch 流式重写把替身迁为 SSE fetch 流（ReadableStream 注入+请求头记录），并保留原三条不变量（sequence 去重/终态停+回调/stop 后不写），新增续接/退避/401/半包多行四组。被测行为不变量扩展不缩窄 | IN_PROGRESS |
| 1.0.6 | 2026-10-02 | 收口后文档补同步（W227；C40 文档一致性第四坑）：头部实施状态行在收口提交 b9350111 中仍残留「IN_PROGRESS（M1 C01–C08 已 VERIFIED；C09 起推进中）」旧文案，与 §10 总表 40/40 VERIFIED 及 README/status.yaml/进度指南三处声明矛盾；随本修订翻为 VERIFIED 并声明 LIVE/生产未验，TC-F2-40-03 门禁（W007）从三处扩为四处同进退断言（新增任务书头部实施状态行），进度指南「未提交推送」尾注同步为已拆分推送事实。不改任何业务不变量与契约语义 | VERIFIED |

### B.3 反向审阅结论

文档不能被以下表面实现满足：只开路由消404、只启动healthy容器、只签Studio URL、只给Preview空src、只生成固定模板、只输出一行review注释、只返回202、只落导入文件不登PG、只恢复文件不恢复数据库、只跑宿主mock测试。对应反例已分别进入责任卡与G1–G7。

文档发布校验只证明规格结构与引用，不证明修复成果。执行者最终必须重新观察实际产物和调用链，遇到当前事实变化依§13修订。

### B.4 规划阶段实际校验（不作为修复验收）

2026-09-27生成本书后完成：0–14章节、40卡、160组TC、F01–F35主责任覆盖、依赖无环、W子集、NOT_STARTED初始状态、占位符/代码围栏/行尾空白与索引检查均通过；`git diff --check`通过；`npm run docs:status`退出0。

`npm run docs:links`退出1：errors=0、unindexed=70；70项均已存在于基线HEAD，未索引集合不包含本书，本书已从README可达。未更改无关文档来清历史门禁。规划检查证据在 `test-artifacts/task-107/fix2/spec/`，是本地辅助证据而非干净克隆必须存在的输入。后续40卡业务测试仍全部NOT_RUN。
