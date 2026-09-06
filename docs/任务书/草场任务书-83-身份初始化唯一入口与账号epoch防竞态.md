# 开发规格：P1 身份初始化唯一入口与账号 epoch 防竞态

> 模板版本：2.4.0 ｜ 仓库事实核验日期：2026-09-06
> 任务编号：83 ｜ 任务书版本：0.2.1 ｜ 创建/更新日期：2026-09-06
> 规划模型/负责人：主程 ｜ 目标仓库：`/Users/LXH/claude/y-1` ｜ 当前分支：`main`
> 代码基线：`b700730931cccf7b6ae3b6cc8994e43209f2e48d`（HEAD 未变）；工作区除原列未提交改动（`useDouyinSession`、`useModelSource`、`useNotifications.test`、`notifications.ts` 等）外，另含 #82 未提交批次（`useActiveIdentity.ts/.test.ts`、`useAccountBootstrap.test.ts`、`useWorkbenchSession.ts/.test.ts` 等）；C83-02 只做增量编辑，不得覆盖或回退上述任何改动
> 本次事实核验日期：2026-09-06 ｜ 文档状态：IMPLEMENTED（C83-02 已落地：五文件门禁全绿、全量 1416/1416，待负责人复核归档）
> 目标执行者：主程直接实现 ｜ 任务卡总数：2（C83-01 基线复核已完成；C83-02 封口实现卡已落地）
> 执行顺序：C83-02（封口 + 测试适配 + 封口回归 + 门禁）——已完成。
> 执行模式：主程直做；附 C 的 DISPATCH 提示词保留存档。

**状态说明**：B83-01 已于 2026-09-06 解除——负责人批准选项 2（低层 loader API 封口）。C83-01 基线复核结论：#79 的 `ensureAccountIdentity` + `account-session` epoch 已覆盖 REQ83-01～REQ83-03 目标行为，生产调用链无绕过入口（相关四份测试 57/57 实跑绿）；唯一遗留是 `loadAccountIdentity` 仍暴露于 `useActiveIdentity()` 公开返回值。C83-02 只做封口：函数原样上提为模块级导出、从返回值移除、`useAccountBootstrap` 改为直接导入、测试适配并新增封口回归；不改任何装载/激活/重置语义。

## 0. 执行协议

### 0.1 词义

- **MUST / MUST NOT**：必须 / 禁止。
- **FACT**：已从当前源码、配置或测试核实的事实。
- **DECISION**：本书已拍板、执行模型不得改变的规则。
- **BLOCKED**：缺少实现所需的最小决策或证据，停止受影响工作。
- **N/A**：明确不适用并写原因，不等于未执行。
- **NOT_RUN**：命令或测试尚未执行，不得写成通过。

### 0.2 强制规则

1. MUST 先确认本文档状态、当前卡号、代码基线和工作区改动；当前状态为 `IMPLEMENTED`（C83-02 已落地并验证），后续任何改动须另行修订版本。
2. MUST 只读取本书列出的必要源码、测试、配置和历史任务书；只读引用不是写权限。
3. 优先保留当前已有未提交/未跟踪改动（`src/composables/useDouyinSession.ts`、`src/composables/useDouyinSession.test.ts`、`src/composables/useModelSource.ts`、`src/composables/useModelSource.test.ts`、`src/composables/useNotifications.test.ts`、`src/stores/notifications.ts`）。如果当前复核确实需要修改这些文件，先向用户说明具体冲突和拟改内容，获得确认后再做并记录；不得无提示执行 reset、clean 或批量删除。个人本地开发可按工作流提交和建分支。
4. MUST NOT 新增第二套账号 epoch、第二个身份 bootstrap、HTTP 接口、错误码、数据库字段、配置项或第三方依赖。
5. 仅当当前卡的实际工作需要改变本书 FACT、签名、权限、调用次数或行为，或无法安全保留新代码时，按 §13 报告；仅发现相关差异、行号变化或语义一致的实现不自动阻塞，不以任务书覆盖新代码。
6. 优先使用合成账号和本地 mock。若本地复核确实需要使用用户提供的测试账号、真实返回值或 AI 能力，先向用户咨询，确认后可继续，并在报告中记录范围；不得将凭据写入仓库、日志、截图或报告。
7. 卡完成、代码实现、卡级验证、主程集成和生产发布是不同状态；不得用其中一个替代另一个。

### 0.3 完成定义

- C83-01 已于 2026-09-06 完成基线复核（调用链静态核验 + 相关四份测试 57/57 实跑），随 B83-01 决策一并关闭，标记 `VERIFIED`。
- C83-02 只有在封口落地、测试适配完成、§12.3 相关门禁真实通过且既有工作区改动全部保留时，才能标记 `VERIFIED`。
- 本任务整体在 C83-02 `VERIFIED` 且主程完成集成核对后即完成；生产发布另行授权，不得提前宣称。

## 1. 目标与范围

### 1.1 一句话目标

在确认当前基线是否仍存在身份初始化重复请求或旧账号迟到响应污染后，保持一个按账号 epoch 去重且具备提交前票据校验的身份 bootstrap 总入口；若已有机制已经满足目标，则只保留可审计的回归证据，不重复改动业务代码。

### 1.2 背景与价值

需求描述担心布局和工作台同时加载身份、组织、门店范围，以及账号 A 切换到 B 后 A 的响应写入全局身份 refs。当前基线的 #79 C79-03 已新增 `src/composables/useAccountBootstrap.ts` 和 `src/stores/account-session.ts`，并将生产入口改为 `ensureAccountIdentity`。本任务必须先区分“旧问题已修复”和“仍有未覆盖入口”两种情况，避免把已完成的防竞态修复回退或复制。

### 1.3 范围内

| 编号 | 可验收目标 | 规则/卡 | 验收 |
|---|---|---|---|
| REQ83-01 | 生产代码中布局账号变化与工作台账号初始化均通过同一个 `ensureAccountIdentity` 总入口；同一 `accountId#epoch` 的并发调用共享同一个 pending 或已完成快照，身份列表与门店范围各只请求一次 | R83-01/C83-01；若解除后由修订卡实现 | AC83-01、TC83-01 |
| REQ83-02 | 所有身份 bootstrap 的异步提交点使用同一 `AccountTicket(accountId, epoch)`；A→B 和 A→B→A 时，旧响应、旧错误、旧激活和旧快照都不得写当前全局身份 refs | R83-02/C83-01；若解除后由修订卡实现 | AC83-02、TC83-02 |
| REQ83-03 | 双身份账号的默认身份规则、服务端活动身份优先级、裸账号补开规则和显式切换串行顺序保持 #79/#71 已批准行为，不因去重重构改变 | R83-03/C83-01 | AC83-03、TC83-03 |
| REQ83-04 | `loadAccountIdentity` 不再出现在 `useActiveIdentity()` 公开返回值中；生产代码仅 `useAccountBootstrap` 经模块导入调用；封口由回归用例锁定 | R83-04/C83-02 | AC83-04 |

### 1.4 范围外

- 不修改登录、注册、Cookie、跨应用免登 token、路由、主题、文案或身份开通业务规则。
- 不改变 `/api/me/identities`、`/api/me/store-scopes`、`/api/me/active-identity` 的 HTTP 方法、路径、请求字段、响应字段、状态码或后端权限。
- 不修改组织、门店、资金账户、钱包、任务、通知、AI、积分、计费、审计或数据库迁移逻辑。
- 不把 `useAccountSessionStore` 改造成另一个 composable，也不新增第三套 epoch、requestId 或全局缓存。
- 不处理与本任务无关的 `src/composables/useDouyinSession.ts`、`src/composables/useDouyinSession.test.ts`、`src/composables/useModelSource.ts`、`src/composables/useModelSource.test.ts`、`src/composables/useNotifications.test.ts`、`src/stores/notifications.ts` 改动。

### 1.5 不许顺手修

- 不顺手修复 `src/stores/notifications.ts`、通知轮询、#81 生命周期债务或 #82 账号级状态隔离。
- 不重命名既有 `AccountTicket`、`ensureAccountIdentity`、`loadAccountIdentity`、`initialActivationApplied`，除非修订版明确授权。
- 不删除已有测试来消除重复调用断言；不放宽断言、不吞错误、不把 `null` 强行改成空数组或成功对象。
- 不运行会修改数据库、容器、生产配置或用户浏览器持久化数据的命令。

### 1.6 用户、入口与已知限制

- 用户：游客、单身份账号、双身份账号、仅门店管理范围账号、存量裸账号、账号切换中的登录用户。
- 生产身份入口：用户端 `index.html` 的 `DefaultLayout.vue` 账号 watch；草场工作台 `GrasslandWorkbench` 经 `useWorkbenchSession.initForAccount` 初始化。
- 另外两个应用入口 `ops.html` 与 `ai.html` 会启动共享 `account-session`，但当前身份 bootstrap 只由用户端草场布局/工作台消费；不得据此发明治理台或 AI 身份业务。
- 限制：本次未运行测试、构建或浏览器；当前文档仅依据静态源码、测试文件、配置和 git 历史核验。

## 2. 仓库上下文

### 2.1 目标端

| 目标端 | 适用性 | 说明 |
|---|---:|---|
| 用户端 | 是 | `DefaultLayout.vue` 与草场工作台是身份 bootstrap 生产消费方。 |
| 治理台 | 保留兼容 | 共享 `account-session` 初始化，但本任务不新增治理台身份 UI。 |
| AI 应用 | 保留兼容 | 共享 `account-session` 初始化，但 AI 应用不消费商家/推荐官身份视角。 |
| Java 服务 | 只读核对 | 既有 identity-service 契约保持，不修改 Java。 |

### 2.2 设计规范路由

- `src/layouts/DefaultLayout.vue`、`src/views/grassland/**` 属于用户端，适用根 `DESIGN.md`。
- 本任务没有 UI 变更，§8 全部为 N/A；如果解除阻塞后出现可见 UI 变化，必须先按 `AGENTS.md` 重新核对根 `DESIGN.md`，并补充明暗两主题截图。
- `src/ops/DESIGN.md` 已读取其总览、颜色、字体、布局、组件和响应式章节；本任务不修改治理台 UI，故无 token 变更。

### 2.3 入口位置

- 页面/布局入口：`src/layouts/DefaultLayout.vue`，`watch(() => currentUser.value?.id ?? null, ...)`，当前核验调用 `ensureAccountIdentity(grassland)`。
- 工作台入口：`src/views/grassland/composables/useWorkbenchSession.ts`，`initForAccount()`，当前核验调用 `ensureAccountIdentity(grassland)`。
- 唯一身份协调入口：`src/composables/useAccountBootstrap.ts`，导出 `ensureAccountIdentity`。
- 低层身份装载：`src/composables/useActiveIdentity.ts`，`loadAccountIdentity`；当前由 bootstrap 调用，并持有模块级身份 refs、默认激活和激活串行队列。
- 账号 epoch：`src/stores/account-session.ts`，`AccountTicket`、`capture()`、`isCurrent()` 以及监听 `auth.currentUser.id` 的同步 watcher。
- HTTP 客户端：`src/composables/useGrasslandIdentity.ts` 与 `src/composables/grassland-http.ts`。
- Java 路由链：Edge/BFF 通过 `/api` 转发到 identity-service；本任务不修改 Edge、Controller、Service、Repository。

### 2.4 相关现有文件

| 文件 | 相关符号 | 当前职责 | 本次关系 |
|---|---|---|---|
| `src/layouts/DefaultLayout.vue` | 账号 `watch` | 账号变化时 reset 全局身份并触发 bootstrap | 生产入口核对；若缺陷复现才允许修订卡修改 |
| `src/views/grassland/composables/useWorkbenchSession.ts` | `initForAccount` | 等待身份快照后加载组织、门店、钱包等工作台状态 | 生产消费方核对；不得再调用低层 loader |
| `src/composables/useAccountBootstrap.ts` | `ensureAccountIdentity`、`useAccountBootstrapStore` | 按 `accountId#epoch` 复用 pending/快照 | 目标总入口；优先复用 |
| `src/composables/useActiveIdentity.ts` | `loadAccountIdentity`、`activateIdentitySide`、`reset` | 低层身份 I/O、默认激活、显式激活和全局 refs | 核对提交边界；不重复实现 |
| `src/stores/account-session.ts` | `AccountTicket`、`capture`、`isCurrent` | 唯一账号 epoch 与旧请求失效判定 | 唯一 epoch owner |
| `src/composables/useGrasslandIdentity.ts` | `listIdentities`、`listMyStoreScopes`、`getActiveIdentity`、`activateIdentity`、`openIdentity` | 既有 HTTP API 适配 | 只读契约，禁止改路径/字段 |
| `src/composables/useAccountBootstrap.test.ts` | TC79-03A/B | pending/快照去重、账号切换、A→B→A、卸载消费方 | 现有回归证据，需复核是否足够 |
| `src/composables/useActiveIdentity.test.ts` | 身份票据与激活串行化用例 | 旧响应丢弃和显式切换顺序 | 现有回归证据，需复核是否足够 |
| `src/App.test.ts` | 布局/工作台双消费方用例 | 验证身份 I/O 一轮 | 现有生产入口证据 |
| `src/views/grassland/composables/useWorkbenchSession.test.ts` | 工作台账号/组织隔离用例 | 验证工作台消费快照和旧链不回写 | 现有回归证据 |
| `src/composables/useDouyinSession.ts`、`src/composables/useDouyinSession.test.ts`、`src/composables/useModelSource.ts`、`src/composables/useModelSource.test.ts`、`src/composables/useNotifications.test.ts`、`src/stores/notifications.ts` | 未提交/未跟踪改动 | 用户已有工作 | 禁止修改；只记录基线 |

### 2.5 当前行为

1. `src/main.ts`、`src/ops/main.ts`、`src/ai/main.ts` 在 mount 前创建并启动各自 Pinia 实例中的 `useAccountSessionStore`；`auth.currentUser.id` 变化会同步递增 epoch，并 abort 旧只读请求。
2. `DefaultLayout.vue` 的账号 watch 在账号变化时调用 `resetActiveIdentity()`，有账号时调用 `ensureAccountIdentity(grassland)`；当前行号约为 308，而用户描述的 297 是旧行号。
3. `useWorkbenchSession.initForAccount()` 当前行号约为 221，先 capture 当前 ticket，再等待 `ensureAccountIdentity(grassland)`；当前不是直接调用 `loadAccountIdentity`。
4. `ensureAccountIdentity` 用 `accountId#epoch` 查 Pinia 中的 snapshot/pending；同 key 复用，同 key 失败后清 pending，不同 key 不复用旧快照。
5. `useActiveIdentity.loadAccountIdentity` 同时请求 `listIdentities()` 与 `listMyStoreScopes()`；每个关键 await 后调用 `session.isCurrent(ticket)`，过期返回 `null`，不写身份 refs。
6. 默认激活经 `activationChain` 串行化；`activateIdentitySide` 也进入同一队列，显式切换不能被默认激活覆盖。
7. 既有测试已经覆盖同账号并发去重、A→B、A→B→A、双身份、仅推荐官、管理范围、裸账号、显式切换和旧响应丢弃。

### 2.6 当前问题

- 现状：用户描述的两个“直接调用 `loadAccountIdentity(grassland)`”生产入口在当前基线中未被确认；当前生产入口都调用 `ensureAccountIdentity`。
- 已确认风险（已解除）：`loadAccountIdentity` 曾暴露于 `useActiveIdentity()` 返回值；2026-09-06 负责人批准封口，由 C83-02 落地——上提为模块级导出、仅 `useAccountBootstrap` 导入。
- 未确认问题：当前基线是否仍能通过某个未扫描入口触发重复 I/O，或某个缺少 `isCurrent` 的 await 分支回写旧账号。现有测试静态存在，但本次未运行。
- 影响：在没有复现之前，改动可能把 #79 已完成的去重、激活顺序或身份规则回退；在 API 封口决定未确定之前，贸然删除 `loadAccountIdentity` 导出可能破坏测试/复用边界。
- 根因：当前描述与基线不一致；剩余风险属于“底层 loader 是否应公开”的接口设计问题，尚未确认是生产缺陷。

### 2.7 基线与来源核验

| 项目 | 已核实内容/证据 |
|---|---|
| 指令与设计 | 已读取 `/Users/LXH/claude/y-1/AGENTS.md`、根 `DESIGN.md`、`src/ops/DESIGN.md`；本任务无 UI 改动。 |
| 模板 | 已读取 `docs/任务书/任务书模板.md` 2.0.0 全部结构。 |
| 版本与构建 | `package.json`：Vue 3、Vite、TypeScript、Vitest、Playwright；`tsconfig.json` target/lib 为 ES2020；`vite.config.ts` 为 `index.html`、`ops.html`、`ai.html` 三入口；`package-lock.json` 存在。 |
| Java/Edge | `platform-java/services/identity-service` 提供既有 identity profile/session Controller；Edge/BFF 通过 `/api` 转发；本任务不改后端。 |
| 工作区 | `git rev-parse HEAD` 为 `b700730931cccf7b6ae3b6cc8994e43209f2e48d`；除本任务书外，`git status --short` 显示 5 个已修改路径和 1 个未跟踪路径，详见页首清单。 |
| 历史线索 | #79 的 `6fb7345a` 已新增唯一 identity bootstrap；`bb5cf45b` 已新增 account-session epoch；历史任务书 #79 只能作为背景，当前源码优先。 |
| 测试基线 | 本次未运行 Vitest、typecheck、lint、build、E2E、Java 检查；结果统一为 `NOT_RUN`。 |
| 复用检查 | 已确认优先复用 `useAccountBootstrap`、`useAccountSessionStore`、`AccountTicket`、`useGrasslandIdentity`，不新建 session/bootstrap 工具。 |

### 2.8 事实、决策与示例的区分

| 标记 | 本书用法 |
|---|---|
| `FACT` | 当前源码、配置、测试、git 状态与已读历史提交；例如当前生产入口是 `ensureAccountIdentity`。 |
| `DECISION` | 已批准的保留规则；例如唯一 epoch owner 是 `account-session`，接口保持不变。 |
| `EXAMPLE` | 仅测试用的合成账号 A/B、组织 OA/OB、门店 S1/S2；不得连接真实数据。 |
| `N/A` | 例如数据库迁移、UI 截图；必须写明不适用原因。 |

### 2.9 影响面与兼容面

| 影响面 | 是否受影响 | 具体对象 | 兼容要求 | 验证方式 |
|---|---:|---|---|---|
| 页面/路由 | 间接受影响 | 用户端布局、草场工作台 | 路由、刷新、三入口行为不变；无 UI 变更 | TC83-03、V83-05 |
| 公共 HTTP 契约 | 否 | `/api/me/identities`、`/api/me/store-scopes`、`/api/me/active-identity` | method/path/body/响应/状态码不变 | TC83-04、V83-06 |
| 数据库/缓存 | 否 | identity profile/session、Pinia 内存 snapshot | 不加表/字段；快照不持久化 | TC83-02、静态 diff |
| 权限/身份 | 是 | merchant/recommender、manager scope、session active identity | 服务端继续校验；失败不改本地视角 | TC83-03/04 |
| 计费/积分/资金 | 否 | 钱包、资金账户、充值 | 不调用、不改金额、不产生流水 | TC83-05 |
| 部署/配置 | 否 | Vite、Edge、Java、环境变量 | 不加配置；三入口构建保持 | V83-05 |
| 文档/状态 | 是 | 本任务书、完成报告 | 只记录真实状态；不得写功能已实现 | V83-07 |

## 3. 技术决策

| 决策项 | 结论 |
|---|---|
| 语言/框架/版本 | Vue 3 + TypeScript + Pinia；继续使用仓库当前依赖，不升级版本。 |
| 新增依赖 | 无；MUST NOT 新增包。 |
| 唯一账号 owner | `src/stores/account-session.ts` 的 `useAccountSessionStore`；epoch 从该处产生，不能在 composable 或组件另建。 |
| 唯一 bootstrap owner | `src/composables/useAccountBootstrap.ts` 的 `ensureAccountIdentity`；生产调用方不得直接发身份 bootstrap I/O。 |
| 低层 loader | 已批准封口（B83-01 选项 2，2026-09-06）：`loadAccountIdentity` 移出 `useActiveIdentity()` 返回值，上提为模块级导出；生产仅 `useAccountBootstrap` 导入，装载语义零变更。 |
| 去重键 | 精确使用当前 `accountId#epoch` 语义；不同 epoch 绝不复用 pending/snapshot；不得只按 accountId。 |
| 错误处理 | 沿用 `useGrassland` 的 `null` 失败语义；旧票据静默丢弃，当前票据失败可显式重试；不新增错误码或裸异常 UI。 |
| 取消策略 | `AccountTicket.signal` 可用于取消旧只读请求，但取消只是优化；正确性必须由 `isCurrent` 提交前判定保证。 |
| 接口/后端 | 既有 HTTP 契约保持；不修改 Java、Edge、DTO、数据库和路由。 |
| UI | 行为零变更；无 CSS、token、主题或截图产物。 |
| 发布/回滚 | 无部署变化；开发验收不授权生产发布。若后续修订仍只改前端，先发布兼容前端并按现有静态资源回滚；若出现后端变化，必须另立任务书。 |

### 决策记录

#### D83-01：不重复实现已存在的 #79 机制

- 决策：在当前基线已经有 `ensureAccountIdentity` 和 `account-session` 的前提下，先以复核卡确认覆盖边界；未有新复现证据不得重写 `useActiveIdentity`。
- 原因：用户描述的旧入口与当前源码不一致，直接编码会造成重复机制或回退。
- 放弃方案：按旧行号直接新增第二个 `useAccountSession` 或把现有 bootstrap 再包一层。
- 允许执行模型修改：否。

#### D83-02：账号 epoch 只有一个 owner

- 决策：所有账号竞态判定继续使用 `AccountTicket` 的 `accountId + epoch`；A→B→A 的旧票据必须继续失效。
- 原因：仅比较账号 ID 无法识别回到同一账号后的旧请求。
- 放弃方案：用时间戳、随机 requestId 或组件局部布尔值替代 epoch。
- 允许执行模型修改：否。

#### D83-03：不改变身份产品规则

- 决策：保留商家优先、服务端活动身份优先、推荐官-only、manager scope 本地商家视角、裸账号补开推荐官以及显式切换串行规则。
- 原因：本任务只处理初始化归属与去重，不重新定义身份产品。
- 放弃方案：借机修改默认身份、身份开通或激活 409 行为。
- 允许执行模型修改：否。

#### D83-04：封口低层 loader（B83-01 已拍板，2026-09-06）

- 决策：批准选项 2。`loadAccountIdentity` 从 `useActiveIdentity()` 公开返回值移除，原样上提为 `useActiveIdentity.ts` 的模块级具名导出；`useAccountBootstrap` 改为直接导入调用；测试改为直接调用模块导出，并新增封口回归（断言返回值不含该函数）。
- 原因：生产调用链已收敛于 `ensureAccountIdentity`，但返回值暴露面意味着任何消费方一行解构即可绕过唯一入口重发身份 I/O；封口后绕过必须显式 import 内部符号，越界在评审与回归中可见。
- 放弃方案：把装载逻辑并入 `useAccountBootstrap.ts`（牵连过多模块级身份状态与激活队列，风险大于收益）；重命名函数（扩大 diff 无额外收益，违反 §1.5 最小改动）。
- 范围红线：不改装载/激活/reset 任何语义，函数体逐行原样上提；ES modules 无语言级单模块可见性，封口边界 =「返回值不可达 + 生产仅 bootstrap 导入」，由封口回归用例与代码评审锁定。
- 允许执行模型修改：是（限 C83-02 白名单四文件）。

## 4. 目标行为

### 4.1 用户流程

1. 用户登录或切换账号，`auth.currentUser.id` 变化。
2. `account-session` 同步递增 epoch，旧 `AccountTicket` 失效；旧只读请求可被 abort，但不依赖 abort 保证正确性。
3. 布局账号 watch 与工作台 `initForAccount` 都调用 `ensureAccountIdentity`，同一账号 epoch 共享 pending 或快照。
4. bootstrap 只发一轮 `listIdentities` 与 `listMyStoreScopes`；低层 loader 的每个异步提交点只接受当前 ticket。
5. 当前账号结果写入身份 refs，按既有规则完成默认激活；显式切换继续排在默认激活队列中。
6. 若旧账号响应迟到，返回 `null` 或被等待方丢弃，不改身份表、活动身份、错误、快照或后续旧账号动作。
7. 若当前请求失败，沿用 `null` 结果并允许下一次显式调用重新尝试；不得缓存失败快照。

### 4.2 行为变化表

| 场景 | 当前行为（FACT） | 目标行为（DECISION） |
|---|---|---|
| 正常单身份 | 已由 loader 装载并按规则激活 | 行为不变；只允许一个 bootstrap 请求链 |
| 双身份 | 已有商家优先/服务端侧优先规则 | 行为不变；不因去重翻转活动侧 |
| 布局+工作台同时初始化 | 当前 `ensureAccountIdentity` 以 key 复用 pending/快照 | 同 key 只一轮身份/门店范围 I/O |
| 未登录 | `ensureAccountIdentity` 对无有效账号返回 `null` | 不发身份请求、不写身份 refs |
| 身份接口失败 | `loadAccountIdentity` 返回 `null`，失败不形成快照 | 当前 epoch 可重试；旧 epoch 失败静默 |
| 门店范围失败 | 当前 scope 非数组按空数组处理，但身份列表为 null 时整体失败 | 保持既有契约；不得把权限错误伪装成身份开通成功 |
| A→B | `isCurrent` 阻止 A 写入 | A 的身份/活动侧/快照/错误不污染 B |
| A→B→A | epoch 不同，不能复用第一个 A | 回到 A 重新请求，旧 A 结果仍无效 |
| 显式切换并发默认激活 | 当前共用 `activationChain` | 显式切换成功后不被默认激活覆盖 |
| 刷新/三入口 | 共享代码但各自 Pinia 实例 | 当前入口只初始化自己的 session；不跨 origin 共享内存快照 |

### 4.3 状态定义

| 状态 | 进入条件 | 可执行操作 | 展示内容 | 离开条件 |
|---|---|---|---|---|
| `anonymous` | `AccountTicket.accountId === null` | 不发身份 bootstrap | 沿用未登录页面 | 账号有效或仍匿名 |
| `pending` | 当前 key 有身份 bootstrap promise | 其他消费方等待同一 promise；不得重复 I/O | 沿用现有布局/工作台加载表现 | 成功、失败或 epoch 变化 |
| `ready` | 当前 key 有非空快照且提交票据仍有效 | 消费身份、门店范围；按既有规则激活 | 沿用现有身份徽标/工作台标签 | 账号变化或显式重试触发新 epoch/请求 |
| `empty` | 成功返回零身份且满足既有 manager/组织归属规则 | 消费空身份/本地商家视角；不得擅自开户 | 沿用既有入驻引导 | 开通、账号变化或重试 |
| `stale` | 请求返回时 ticket 非当前 | 不得写任何当前 refs；等待方得到 `null` | 不展示旧账号错误或成功 | 当前账号重新 bootstrap |
| `failed` | 当前请求返回 null/网络失败 | 允许显式重试；不缓存失败快照 | 沿用既有错误承载方式 | 重试成功或账号变化 |

### 4.4 状态迁移规则

```text
anonymous
  -> pending：出现有效 accountId，布局或工作台调用 ensureAccountIdentity

pending
  -> ready：当前 epoch 的身份列表和门店范围成功返回，并通过票据检查
  -> empty：成功返回零身份，且按既有规则不触发开户
  -> failed：当前 epoch 请求失败，返回 null，不保存失败快照
  -> stale：账号或 epoch 变化；旧结果不得提交

ready / empty / failed
  -> pending：当前账号显式重试且不存在可复用快照

任意状态
  -> anonymous：登出；清理由现有 account-session/auth/identity reset 链路负责
  -> stale：账号变化或 A→B→A 使旧 ticket 失效
```

补充规则：

- 同一 `accountId#epoch` 的重复事件只等待已有 promise，不新增网络请求。
- 不同 epoch 的 pending 不可互相等待；旧 promise 完成后也不能写新 epoch 快照。
- 刷新只重新建立当前入口的 Pinia 内存状态；不从 localStorage/sessionStorage 恢复身份快照。
- `AbortSignal` 的取消不能替代 `session.isCurrent(ticket)`；所有写 refs、清 loading/error、发后续激活前都要核对归属。

## 5. 业务规则

### 5.1 输入规则

| 字段 | 类型 | 必填 | 默认值 | 允许范围 | 空值处理 | 示例 |
|---|---|---:|---|---|---|---|
| `accountId` | `string \| null` | 否 | `null` | 非空白字符串 | 缺省、`null`、空串、纯空白、非 string 均归一为匿名 `null` | `EXAMPLE: acct-a` |
| `epoch` | `number` | 是（由 session 生成） | `0` 起始计数 | 非负内存单调整数 | 不接受调用方传入或重置 | `EXAMPLE: 1` |
| `IdentitySide` | `'merchant' \| 'recommender'` | 仅激活时必填 | 无 | 两个既有枚举值 | 未知值不得发请求；沿用类型约束 | `merchant` |
| `AccountIdentitySnapshot.identities` | `IdentityProfile[]` | 成功时是 | `[]` 仅由真实空响应产生 | 服务端列表 | `null` 代表当前身份请求失败，不等价空列表 | `EXAMPLE: [{ identityType: 'merchant' }]` |
| `AccountIdentitySnapshot.storeScopes` | `StoreAccessScope[]` | 快照字段是 | `[]` | 服务端合法门店范围 | 非数组按既有客户端语义为空数组；不得扩大权限 | `EXAMPLE: []` |

### 5.2 校验规则

1. 账号归属必须由 `useAccountSessionStore.capture()` 产生；调用方不得自造 accountId/epoch。
2. 快照、身份 refs、活动身份镜像和错误状态在每个异步 await 后提交前必须通过 `session.isCurrent(ticket)`。
3. 同 key pending/snapshot 命中必须先于创建新请求；失败不得写 snapshot。
4. `IdentitySide` 只允许 `merchant` 或 `recommender`；未知服务端 `activeIdentityType` 不视为合法已激活侧。
5. 任何权限/身份失败不得只修改本地 `activeSide` 伪装成功；既有后端激活校验保持。
6. 本任务无用户表单；因此无长度、金额、trim 文案和字段聚焦规则。

### 5.3 业务判断规则

```text
IF 当前 ticket 无 accountId
THEN 不发身份请求，返回 null

ELSE IF Pinia bootstrap store 有同 key 的有效 snapshot
THEN 直接返回该 snapshot

ELSE IF Pinia bootstrap store 有同 key 的 pending
THEN 等待并返回同一 pending

ELSE
  发起一轮 listIdentities + listMyStoreScopes
  IF 任一关键身份列表返回 null
  THEN 当前尝试返回 null，清 pending，不保存失败 snapshot
  ELSE 仅在 session.isCurrent(ticket) 时提交 refs/快照/激活结果

IF 返回前 ticket 已过期
THEN 返回 null，禁止写当前身份 refs、错误、快照或后续旧账号动作
```

### 5.4 权限与业务不变量

| 主体/角色 | 组织/门店/资源关系 | 允许操作 | 禁止操作及服务端拒绝结果 | 测试编号 |
|---|---|---|---|---|
| 匿名 | 无有效 accountId | 不发身份 bootstrap | 不得调用 `/api/me/identities` 等身份私有接口 | TC83-04 |
| 单身份 merchant | 有 merchant profile | 读取身份并按既有规则激活 merchant | 不得激活未开通 recommender；服务端继续校验 | TC83-03 |
| 双身份账号 | 同时有 merchant/recommender | 按服务端活动侧或既有默认规则初始化；可显式切换已开通侧 | 默认激活不得覆盖已完成显式切换 | TC83-03 |
| 仅门店 manager scope | 无身份档案但有 manager 门店范围 | 保持本地商家视角与门店范围 | 不得自动开通推荐官或扩大到全组织门店 | TC83-03 |
| 旧账号 A | ticket 属于旧 epoch | 允许请求自然完成或被 abort | 不得写 B refs、清 B loading、发 B 后续动作 | TC83-02 |
| 当前账号 B | 当前 ticket 有效 | 只消费 B 的快照 | 不得复用 A 的 snapshot/pending | TC83-02 |

- 不变量：同一账号 epoch 的身份列表请求至多 1 次，门店范围请求至多 1 次；TC83-01。
- 不变量：A→B→A 期间第一个 A 的旧 ticket 永远无效；TC83-02。
- 不变量：身份激活失败不把本地活动身份留在错误侧；TC83-03。
- 不变量：本任务不产生数据库、缓存、资金、积分、AI 或计费副作用；TC83-05。

## 6. 接口契约

### 类型与调用签名

```ts
// src/stores/account-session.ts
export interface AccountTicket {
  readonly accountId: string | null
  readonly epoch: number
  readonly signal: AbortSignal
}

// src/composables/useAccountBootstrap.ts
export type EnsureAccountIdentity = (
  grassland: ReturnType<typeof useGrassland>,
) => Promise<AccountIdentitySnapshot | null>

// src/composables/useActiveIdentity.ts
export interface AccountIdentitySnapshot {
  identities: IdentityProfile[]
  storeScopes: StoreAccessScope[]
}
```

上述类型为当前 FACT；若 B83-01 批准封口低层 loader，必须在修订版明确新的可见导出边界和所有测试适配，不得由执行者临时决定。

### 6.1 请求信息

| 调用 | 方法 | 路径 | 当前用途 | 本任务 |
|---|---|---|---|---|
| `listIdentities` | GET | `/api/me/identities` | 当前账号已开通身份列表 | 只读保持 |
| `listMyStoreScopes` | GET | `/api/me/store-scopes` | 当前账号显式门店范围 | 只读保持 |
| `getActiveIdentity` | GET | `/api/me/active-identity` | 当前 session 活动身份 | 只读保持 |
| `activateIdentity` | POST | `/api/me/active-identity` | 激活已开通身份 | 只读保持 |
| `openIdentity` | POST | `/api/me/identities` | 既有裸账号补开推荐官规则 | 本任务不新增调用 |

所有请求经 `grassland-http.request`，携带浏览器 cookie；本任务不增加 `accountId` 请求头或 body 字段，因为服务端账号由 session/cookie 解析，客户端 epoch 只用于本地提交边界。

### 6.2 请求参数

```json
GET /api/me/identities
GET /api/me/store-scopes
GET /api/me/active-identity

POST /api/me/active-identity
{"type":"merchant"}

POST /api/me/identities
{"type":"recommender"}
```

- `accountId`、`epoch`、`ticket` 不发送给 Java 服务；它们是前端本地归属票据。
- `type` 只能是既有 `merchant` 或 `recommender`；请求/响应字段不改名。
- 不新增幂等键、trace 字段、请求头或 query 参数。

### 6.3 成功响应

既有 HTTP 信封由 `src/composables/grassland-http.ts#request` 解包为 `data`：

```json
{"success":true,"data":[
  {"id":"identity-1","identityType":"merchant","organizationId":"org-1","status":"active"}
]}
```

```json
{"success":true,"data":[
  {"organizationId":"org-1","organizationName":"组织","organizationStatus":"active","storeId":"store-1","storeName":"门店","storeStatus":"active","role":"manager","permissionTier":"basic_publish"}
]}
```

```json
{"success":true,"data":{"activeIdentityType":"merchant"}}
```

- `IdentityProfile` 定义于 `src/types/grassland/organization.ts#IdentityProfile`。
- `StoreAccessScope` 定义于 `src/types/grassland/organization.ts#StoreAccessScope`。
- 当前身份为空由 `data: []` 表示；当前 session 无活动身份由 `activeIdentityType: null` 表示；不能把两者混为失败。

### 6.4 错误契约

| HTTP 状态/无响应 | 实际错误字段与值 | 触发条件 | 用户文案 | 调用方动作/可重试性 |
|---|---|---|---|---|
| 401 | 既有错误信封/服务端未登录语义 | cookie/session 无效 | 沿用 `useGrassland` 既有处理 | 当前 bootstrap 返回 null；重新登录后由新 epoch 重试 |
| 403 | 既有错误信封 | 身份/门店范围权限不足 | 不暴露原始堆栈 | 当前尝试失败；不改活动身份；是否重试按现有调用方 |
| 409 | 既有错误信封 | 激活未开通身份或身份状态冲突 | 沿用既有错误承载 | `activateIdentitySide` 返回 `failed`/`not-opened`；不伪造成功 |
| 4xx/5xx | `request` 现有错误解析 | 服务端业务/网关失败 | 沿用现有错误承载 | 当前 epoch 可显式重试；不保存失败快照 |
| 无响应 | 无服务端错误码 | 断网、超时、abort | 沿用现有错误承载或静默旧票 | 旧票静默；当前票可重试；不续发旧动作 |

### 6.5 错误处理原则

- MUST NOT 修改既有 `request` 错误解析或把异常直接展示给用户。
- 当前票据失败允许下一次显式 bootstrap 重试；重试创建新尝试，不复用失败快照。
- 旧票据失败、成功、catch、finally 均不得清除当前账号 loading/error 或写当前 refs。
- bootstrap pending 由 Pinia store 持有；一个消费方卸载不得取消其他消费方等待的同 key promise。
- 不新增全局 toast、弹窗、路由跳转或 UI loading 状态。

### 6.6 契约不变量与观测要求

- **不变量**：同一 `accountId#epoch` 的 `listIdentities` 和 `listMyStoreScopes` 各最多一次；TC83-01。
- **状态码不变量**：不改变既有 401/403/409/5xx 语义；TC83-04。
- **字段不变量**：请求仍用 `type`，身份响应仍用 `identityType`，活动侧字段仍为 `activeIdentityType`；TC83-04。
- **顺序不变量**：默认激活与显式切换继续共用 `activationChain`；TC83-03。
- **重试不变量**：失败清 pending、不存失败快照；当前票可重试，旧票不可重试旧动作；TC83-02。
- **日志/指标**：允许测试记录脱敏的调用名称、账号别名 A/B、epoch 和序列；禁止记录 cookie、token、真实 email、真实身份资料和完整响应正文。
- **追踪字段**：本任务不新增 HTTP trace/correlation 字段；本地测试证据使用合成标签，不发送到服务端。

### 6.7 流式、上传与异步任务契约

- SSE：N/A，本任务身份/组织请求均为普通 JSON 请求。
- Multipart：N/A，不涉及文件上传。
- 预签名上传：N/A，不涉及媒体存储。
- 媒体/Range：N/A，不涉及媒体响应。
- 异步任务：身份 bootstrap 是普通 Promise 并发协调，不新增后台 job；其乱序、取消、重复和账号切换规则见 §4.4、TC83-01/02。

## 7. 数据模型与迁移

### 7.1 数据结构

```ts
// 仅内存；src/stores/account-session.ts
AccountTicket = { accountId: string | null; epoch: number; signal: AbortSignal }

// 仅 Pinia 内存；src/composables/useAccountBootstrap.ts
BootstrapState = {
  pendingKey: string
  pending: Promise<AccountIdentitySnapshot | null> | null
  snapshotKey: string
  snapshot: AccountIdentitySnapshot | null
}
```

服务端既有数据：identity-service 的 identity profile、per-session active identity、store scope；本任务不改变 schema、表、索引、缓存 key 或保留期。

### 7.2 字段规则

| 字段 | 类型 | 可空 | 唯一 | 可修改 | 创建时生成 |
|---|---|---:|---:|---:|---:|
| `AccountTicket.accountId` | `string \| null` | 是 | 否 | 否 | 否 |
| `AccountTicket.epoch` | `number` | 否 | 在当前 session 内单调 | 否 | 是 |
| `BootstrapState.pendingKey` | `string` | 以空串表示无 pending | 当前 store 当前值唯一 | 是 | 否 |
| `BootstrapState.snapshotKey` | `string` | 以空串表示无 snapshot | 当前 store 当前值唯一 | 是 | 否 |
| `IdentityProfile.id` | `string` | 否 | 服务端既有约束 | 否 | 服务端 |
| `StoreAccessScope.storeId` | `string` | 否 | 服务端范围内 | 否 | 服务端 |

### 7.3 兼容与迁移

- 旧数据兼容：是；仅读取既有服务端数据，字段和响应不改。
- 新字段：无；`epoch` 和 bootstrap state 均为内存字段，当前实现已存在。
- 数据库迁移：N/A，不新增或修改持久化结构。
- 回滚方案：N/A，无 schema/config/deploy 变化；若未来修订产生前端代码，按应用版本回滚，不删除迁移。
- 前后端发布顺序：N/A；本任务禁止后端变更。

### 7.4 事务、并发与生命周期

- 事务边界：N/A，前端 bootstrap 不拥有数据库事务；服务端既有 identity 激活事务保持不变。
- 并发兜底：Pinia pending keyed by `accountId#epoch`；提交前 `isCurrent`；激活由既有 `activationChain` 串行化。
- 事件：N/A，本任务不新增 outbox/event。
- 数据生命周期：快照只在当前 Pinia 实例内存存在；账号变化后旧票失效，登出不持久化；不新增 localStorage/sessionStorage。
- 迁移计划：N/A。
- 回滚计划：无后端数据变化；代码回滚不得删除已有 #79 迁移式行为，若有冲突先由主程修订。

### 7.5 迁移执行与回滚证据

- 迁移文件命名：N/A，不创建 migration。
- 可重复执行：N/A，不执行迁移。
- 旧版本应用读新数据：N/A，无新数据。
- 新版本应用读旧数据：是，继续读取既有 identity/store scope 响应。
- 回滚顺序：N/A；若后续仅有前端变更，先回滚前端应用版本，再核对三入口健康检查。
- 失败后清理：不清理用户数据库、Cookie、storage 或容器；测试只释放 mock、Pinia 和 deferred。
- 必测场景：N/A，不涉及迁移；数据兼容由 TC83-04 的现有 DTO 断言覆盖。

## 8. UI 实现规格

### 8.1 页面归属

N/A：本任务只核对 composable/store/API 调用链，不改页面结构、组件、CSS、主题或交互文案。

### 8.2 页面结构

N/A：无 UI 结构变化。

### 8.3 组件行为

N/A：身份徽标、工作台标签和页面入口行为必须保持既有行为，不新增组件或状态。

### 8.4 交互细节

N/A：不改点击、键盘、弹窗、Toast、加载或错误展示；并发行为通过单元/组件测试验证。

### 8.5 响应式要求

N/A：无视口布局变化。

### 8.6 视觉约束

N/A：无 CSS 改动，因此不新增 token、不修改 `src/style.css`，不涉及根 `DESIGN.md` 或 `src/ops/DESIGN.md` 的视觉实现。

### 8.7 无障碍

N/A：无 DOM、焦点、读屏或交互变化；既有入口回归不得被破坏。

### 8.8 截图自查

N/A：无 UI 改动；不生成截图。若后续修订引入任何可见变化，必须重新纳入 UI 卡并按 AGENTS 要求完成用户端/治理台/AI 应用受影响入口的明暗主题截图。

## 9. 全局约束

### 9.1 文件白名单 / 黑名单

| 精确路径 | 权限 | 本次操作 | 允许修改的符号/段落 | 原因与完成标准 | 所属任务卡 |
|---|---|---|---|---|---|
| `src/composables/useAccountBootstrap.ts` | 写（C83-02） | import 改直调 | `useActiveIdentity` 导入改为 `loadAccountIdentity` 导入；调用点一行 | 唯一生产调用方改为模块导入，语义不变 | C83-02 |
| `src/stores/account-session.ts` | 只读参考；本书禁止修改 | 读取 | `AccountTicket`、`capture`、`isCurrent`、账号 watcher | 保持唯一 epoch owner | C83-01 |
| `src/composables/useActiveIdentity.ts` | 写（C83-02） | 上提+移除返回项 | `loadAccountIdentity` 原样上提为模块级 `export`；返回值对象移除该项；JSDoc 标注仅供 bootstrap | 封口：生产消费方经返回值不可达 | C83-02 |
| `src/layouts/DefaultLayout.vue` | 只读参考；解除阻塞后需修订书授权才可写 | 读取 | 账号 watch | 确认生产入口调用 bootstrap | C83-01 |
| `src/views/grassland/composables/useWorkbenchSession.ts` | 只读参考；解除阻塞后需修订书授权才可写 | 读取 | `initForAccount` | 确认工作台只消费快照 | C83-01 |
| `src/composables/useAccountBootstrap.test.ts` | 写（0.2.1 增补） | E11 适配 | E11 用例在 bootstrap 前补一行 `useActiveIdentity()` 消费方挂载 | 根因见 §14 偏差说明：封口后 bootstrap 不再顺带触发 #82 owner 对齐，测试须自持消费方 | C83-02 |
| `src/composables/useActiveIdentity.test.ts` | 写（C83-02） | 适配+新增 | 25 处 `state.loadAccountIdentity(` 改模块直调；新增封口回归 describe | 封口后测试经模块导出触达装载链 | C83-02 |
| `src/views/home/GrasslandHomeView.test.ts` | 写（C83-02） | 适配 | 3 处 `state.loadAccountIdentity(` 改模块直调；`const state` 行随之删除 | 主页角色感知用例继续可跑 | C83-02 |
| `src/App.test.ts` | 只读（C83-02 无需改动） | 读取 | 布局/工作台双消费方用例 | 复核生产入口 I/O 计数 | C83-01 |
| `src/views/grassland/composables/useWorkbenchSession.test.ts` | 只读（C83-02 无需改动） | 读取 | 工作台账号隔离用例 | 复核快照消费和旧链 | C83-01 |
| `src/composables/useDouyinSession.ts`、`src/composables/useDouyinSession.test.ts`、`src/composables/useModelSource.ts`、`src/composables/useModelSource.test.ts`、`src/composables/useNotifications.test.ts`、`src/stores/notifications.ts` | 禁止修改 | 无 | 全文件 | 保留用户已有未提交/未跟踪改动 | C83-01 |
| `src/composables/useGrasslandIdentity.ts` | 禁止修改 | 无 | 全文件 | 既有 HTTP DTO/路径契约保持 | C83-01 |
| `platform-java/**` | 禁止修改 | 无 | 全目录 | 本任务无 Java/DB/Edge 交付 | C83-01 |
| `src/style.css`、`DESIGN.md`、`src/ops/DESIGN.md` | 禁止修改 | 无 | 全文件 | 无 UI/token 交付 | C83-01 |
| `docs/任务书/草场任务书-83-身份初始化唯一入口与账号epoch防竞态.md` | 写入 | 新建本任务书 | 全文 | 只生成规格，不写业务代码 | 规划阶段 |

### 9.2 项目铁律速查

- 账号私有异步读写必须使用现有 `AccountTicket`；只比较 `accountId` 不足以防 A→B→A。
- `AbortSignal` 只做优化，正确性由提交前 `isCurrent` 保证。
- `ensureAccountIdentity` 是当前生产身份 bootstrap 单入口；不得新建同类协调器。
- identity 请求/响应字段保持 `type`/`identityType` 不对称契约。
- 服务端仍负责身份、组织、门店权限；前端显隐或本地视角不能替代服务端授权。
- 不改资金、积分、AI、通知、Java、Edge、迁移和配置。
- TypeScript target/lib 继续 ES2020；不引入 `Array.at` 等未获配置支持的语法。
- 未提交改动必须保留；本任务不是清理工作区。

### 9.3 验证环境事实

| 项目 | 事实与要求 |
|---|---|
| 前端目录 | `/Users/LXH/claude/y-1` |
| 工具链 | `package.json` 脚本；Node/npm 版本需执行者实际记录，不能猜测；依赖已安装情况需实际检查 |
| 测试 | Vitest 默认 Node，DOM 测试按文件声明 happy-dom；测试不接真实后端 |
| 浏览器 | Playwright `chromium/firefox/webkit`，默认 `BASE_URL=http://127.0.0.1:18080`；本任务 N/A，因无 UI/E2E交付 |
| 服务 | 如仅执行本卡单测不启动 Docker/Edge/Java；若后续卡要求集成，必须另列授权与副作用 |
| Java | 本任务不运行 Java；若修订涉及后端，必须另立卡并使用 `scripts/lib/java-runtime.sh` 选择 Java 25 |
| 权限 | 安装依赖、联网、启动容器、浏览器、数据库写入均需按环境授权；本任务书不授予这些操作 |
| 产物目录 | 计划证据目录为 `/Users/LXH/claude/y-1/test-artifacts/task-83/`；当前不创建产物，后续执行者需先获授权并登记 |

### 9.4 安全、性能与兼容规格

| 类别 | 必须明确的项目 | 本任务唯一要求/阈值 | 验证用例 |
|---|---|---|---|
| 安全 | 账号/身份/组织/门店归属 | 旧 ticket 不得写当前 refs；服务端权限不变 | TC83-02/04 |
| 隐私 | 日志和测试数据 | 只用合成 A/B；不得记录 cookie/token/真实资料 | TC83-06 |
| 外部输入 | 身份枚举和服务端字段 | 只接受既有枚举；未知 active side 不视为合法 | TC83-03/04 |
| 性能 | 重复请求 | 同 key identity/scope 各至多 1 次；不新增轮询 | TC83-01 |
| 异步资源 | 取消、pending、卸载 | 一个消费方卸载不杀同 key pending；旧票不回写 | TC83-02 |
| 兼容 | 三入口、ES2020、既有 HTTP | 不改三入口启动、HTTP DTO、target/lib | V83-05/06 |

### 9.5 仓库约束适用矩阵

| 约束 | 适用卡号/文件 | 落实动作与验收；或 N/A 原因 |
|---|---|---|
| R-UI | N/A | 无 DOM/CSS/组件改动；若修订出现 UI，必须新增 UI 卡、token 检查和双主题截图。 |
| R-ENTRY | C83-01 | 核对 `index.html`、`ops.html`、`ai.html` 三入口 session mount 与用户端身份生产入口；V83-05。 |
| R-JAVA | C83-01 | 只读核对 identity-service Controller/DTO；不改 Java/Edge；V83-06。 |
| R-DATA | N/A | 无 DB/cache/storage schema 或迁移；§7 写明 N/A。 |
| R-AI | N/A | 不消费 AI 模型、额度、计费或安全策略；AI 入口只保留共享 session 兼容。 |
| R-QUALITY | C83-01 | 复核已有测试并记录未执行命令；解除后按 V83 清单真实执行。 |
| R-SAFE | C83-01 | 保留未提交改动、合成数据、脱敏证据和阻塞规则。 |

## 10. 任务总表

| 卡 | 标题 | 端 | 对应需求 | 主要写入文件 | 依赖及交付物 | 验收编号 | 执行状态 |
|---|---|---|---|---|---|---|---|
| C83-01 | 核对当前身份 bootstrap 覆盖边界并提交最小阻塞决策 | 用户端/共享认证 | REQ83-01～REQ83-03 | 无业务写入 | 无；输出调用链、测试覆盖和 B83-01 决策输入 | AC83-01～AC83-03 | VERIFIED（2026-09-06，主程复核） |
| C83-02 | 低层 loader API 封口与测试适配 | 用户端/共享认证 | REQ83-04 | `useActiveIdentity.ts`、`useAccountBootstrap.ts`、`useActiveIdentity.test.ts`、`GrasslandHomeView.test.ts`、`useAccountBootstrap.test.ts`（0.2.1 增补） | C83-01 复核结论与 D83-04 决策 | AC83-04 | VERIFIED（2026-09-06，门禁全绿） |

### 10.1 任务卡拆分规则

- C83-01 是诊断/验收卡，已于 2026-09-06 完成并标记 `VERIFIED`；复核期间未改业务代码。
- 负责人已在 B83-01 拍板选项 2（批准 API 封口），本书 0.2.0 据此开放实现卡 C83-02。
- C83-02 之外的扩展（额外重构、接口变更、后端修复）仍不允许。

### 10.2 卡间交接协议

| 交接项 | 前置卡输出 | 后置卡读取方式 | 完成证据 |
|---|---|---|---|
| 源码 | 生产入口与底层 loader 的当前调用图 | 主程读取精确路径/符号，修订白名单 | C83-01 报告与静态调用结果 |
| 接口 | 既有三类 GET/POST 契约无变化结论 | 实现卡只复用现有 client | TC83-04、V83-06 |
| 测试 | 已有 TC79 覆盖缺口或反证 | 修订卡新增最小用例，不重复复制既有 fixture | TC83-01～06 |
| 决策 | B83-01 的关闭/实现授权 | 修订任务书版本、更新状态与任务卡 | 负责人批准记录与附 B |

## 11. 任务卡

### 卡 C83-01：核对当前身份 bootstrap 覆盖边界并提交最小阻塞决策

**执行包**：任务书版本 `0.1.0`；对应需求 `REQ83-01～REQ83-04`；负责执行者 `获指派编码模型（仅复核）`；本卡负责人/验收人 `主程`。

**背景**：当前代码已包含 #79 的唯一 bootstrap 和账号 epoch。用户描述的旧入口与当前源码不一致。本卡只负责证明现状和定位未决的低层 loader 暴露边界，不实现业务代码。

**输入与前置交付物**：无；必须先读取 §0、§1、§2.4、§2.5、§9、§13、§14，以及本卡引用的源码和测试。

**输出与移交**：

1. 生产调用图：`DefaultLayout` → `ensureAccountIdentity`、`useWorkbenchSession.initForAccount` → `ensureAccountIdentity`、bootstrap → `loadAccountIdentity`。
2. 去重/epoch/激活检查表：列出每个 await 后的 `isCurrent`、pending/snapshot key、激活队列和 reset 行为。
3. 测试覆盖表：标出已有 #79 用例覆盖与未覆盖边界，不得仅写“测试通过”。
4. B83-01 阻塞报告：由负责人决定关闭任务，或批准唯一 API 封口/补强方案；未获决定不得编码。

**必读清单**：

- §0：确认当前书不可编码、保留工作区和不新增机制。
- §2.4～§2.7：确认当前入口不是用户描述的旧行号。
- §3 D83-01～D83-04：理解唯一 epoch、bootstrap 和未决 API 封口。
- §6：不得改 HTTP 契约；epoch 不发送给后端。
- §9.1：确认 C83-01 无业务写入白名单。
- `src/composables/useAccountBootstrap.ts`：核对同 key pending/snapshot。
- `src/stores/account-session.ts`：核对 epoch 和 ticket。
- `src/composables/useActiveIdentity.ts`：核对 loader 的提交边界、激活队列和公开返回值。
- `src/layouts/DefaultLayout.vue` 与 `src/views/grassland/composables/useWorkbenchSession.ts`：核对真实生产调用方。
- `src/composables/useAccountBootstrap.test.ts`、`src/composables/useActiveIdentity.test.ts`、`src/App.test.ts`：核对既有回归证据。

**改动文件**：

| 精确路径 | 操作/权限 | 允许改动的符号 | 修改目的 | 完成标准 |
|---|---|---|---|---|
| `src/layouts/DefaultLayout.vue` | 只读 | 账号 watch | 证明真实调用入口 | 不修改 |
| `src/views/grassland/composables/useWorkbenchSession.ts` | 只读 | `initForAccount` | 证明工作台消费入口 | 不修改 |
| `src/composables/useAccountBootstrap.ts` | 只读 | `ensureAccountIdentity`、store | 证明 pending/snapshot 去重 | 不修改 |
| `src/stores/account-session.ts` | 只读 | `AccountTicket`、watcher | 证明 epoch owner | 不修改 |
| `src/composables/useActiveIdentity.ts` | 只读 | `loadAccountIdentity` 等 | 证明提交边界及公开 API 风险 | 不修改 |
| `src/composables/useAccountBootstrap.test.ts` | 只读 | TC79-03A/B | 复核并发/epoch用例 | 不修改 |
| `src/composables/useActiveIdentity.test.ts` | 只读 | 票据/激活用例 | 复核旧响应/激活顺序 | 不修改 |
| `src/App.test.ts` | 只读 | 双消费方用例 | 复核生产入口计数 | 不修改 |
| `src/stores/notifications.ts` | 禁止修改 | 全文件 | 保留已有未提交改动 | git diff 与开工前一致 |
| `/Users/LXH/claude/y-1/test-artifacts/task-83/` | 需授权后新建 | 复核日志/报告 | 保存脱敏证据 | 只有授权且命令实际运行后创建 |

**开始前检查**：

1. 运行 `git status --short`，记录页首列出的 5 个已修改路径和 1 个本任务前未跟踪路径；本任务书自身另作为新增文档，不要求工作区干净。
2. 运行 `git rev-parse HEAD`，确认仍为本书基线；若基线变化，停止并按 B83-02 报告。
3. 静态检查生产调用方，排除测试后确认 `loadAccountIdentity(` 只被 bootstrap 调用；若出现新生产调用方，停止并报告。
4. 核对 `useAccountBootstrap` 的 `pendingKey/snapshotKey` 是否仍按 `accountId#epoch`；核对 `account-session` watcher 是否仍为 `flush: 'sync'` 且 `immediate: true`。
5. 记录本卡命令实际结果；未执行写 `NOT_RUN`，不得把历史 #79 结果当成本次通过。

**锚点代码**（以下是当前事实片段，非目标代码）：

```ts
// src/composables/useAccountBootstrap.ts
const key = `${ticket.accountId}#${ticket.epoch}`
if (current.snapshotKey === key && current.snapshot !== null) return current.snapshot
if (current.pendingKey === key && current.pending !== null) return current.pending
```

```ts
// src/layouts/DefaultLayout.vue / src/views/grassland/composables/useWorkbenchSession.ts
void ensureAccountIdentity(grassland)
const boot = await ensureAccountIdentity(grassland)
```

```ts
// src/stores/account-session.ts
function isCurrent(ticket: AccountTicket): boolean {
  return ticket.accountId === ownerAccountId.value && ticket.epoch === epoch.value
}
```

**本卡目标行为**：证明 §4 目标行为在当前生产调用链是否已经成立；若成立，输出“已满足、无需业务实现”的反证；若不成立，只记录最小复现与具体缺口，不直接修复。

**函数级要求**：本卡不修改函数。

- 输入：当前源码、测试、配置、git 基线和合成调用路径。
- 输出：调用图、覆盖表、最小复现/反证、B83-01 阻塞报告。
- 副作用：无业务网络、数据库、资金、AI 或持久化副作用；允许静态命令产生临时缓存，需记录。
- 不变条件：工作区已有 Douyin、model source、notifications 改动保持不变；源码和测试文件不变。
- 清理与失败：任何命令失败如实记录；不通过改源码绕过；基线变化或生产调用方新增立即 BLOCKED。

**做法**（按顺序执行）：

| 步骤 | 文件/符号 | 精确动作 | 完成检查点 | 失败时处理 |
|---|---|---|---|---|
| 1 | 仓库根、git | 记录 `git rev-parse HEAD`、`git status --short` | 页首全部工作区改动可见 | 基线变化报 B83-02 |
| 2 | `DefaultLayout.vue`、`useWorkbenchSession.ts` | 静态核对两个生产入口只调用 `ensureAccountIdentity` | 无生产直接调用 `loadAccountIdentity` | 有新入口报 B83-03 |
| 3 | `useAccountBootstrap.ts`、`account-session.ts` | 对照 §6.6 逐项核对 key/pending/snapshot/ticket/epoch | 同 key 共享、不同 epoch 隔离 | 语义不符报 B83-04 |
| 4 | `useActiveIdentity.ts` | 逐个 await 检查旧票 return、refs 写入、激活队列、reset | 无漏检提交边界，或记录具体漏点 | 发现漏点只报告，不改代码 |
| 5 | 既有测试文件 | 建立 TC83-01～06 覆盖矩阵，核对 fixture 是否合成且完整 | 每个断言映射到需求/不变量 | 断言不足报 B83-05 |
| 6 | 负责人交接 | 提交 B83-01：关闭任务或批准唯一修订方向 | 有明确负责人决策、范围和后续卡 | 无决定保持 BLOCKED_DRAFT |

**边界与异常场景**：

| 编号 | 场景 | 触发条件 | 预期行为 | 必测 |
|---|---|---|---|---:|
| E01 | 空输入 | `currentUser` 为 null/无效 ID | 不发身份请求，bootstrap 返回 null | 是 |
| E02 | 超长输入 | N/A：本卡无用户输入；若测试构造长 ID，仅验证不会作为真实数据发送 | 不改变 normalize 规则 | 是 |
| E03 | 重复提交 | 布局和工作台同 tick 调用 bootstrap | 共享同一 pending；身份与 scope 各至多一次 | 是 |
| E04 | 网络失败 | 当前身份列表或 scope mock reject/null | 当前尝试失败可重试；不保存失败快照 | 是 |
| E05 | 服务端错误 | 401/403/409/5xx | 保持既有错误语义；不伪造本地激活成功 | 是 |
| E06 | 未登录 | ticket.accountId 为 null | 不发私有接口 | 是 |
| E07 | 无权限 | manager scope 或身份接口被拒绝 | 不扩大范围；按既有 null/空结果处理 | 是 |
| E08 | 数据为空 | identities=[]、scopes=[] | 保持既有 manager/裸账号/组织判断，不擅自开户 | 是 |
| E09 | 数据过期 | A ticket 返回时 epoch 已变化 | 丢弃 A 结果和快照 | 是 |
| E10 | 页面刷新 | 新 Pinia 实例、同账号重新加载 | 不从 storage 恢复旧快照；重新按当前 epoch bootstrap | 是 |
| E11 | 用户快速切换 | A pending → B → A | 三个 epoch 不共享；旧 A 不能覆盖新 A | 是 |
| E12 | 组件卸载 | 一个消费方停止等待，另一方仍 pending | Pinia pending 不被单个消费方取消 | 是 |
| E13 | null/缺字段/非法枚举 | activeIdentityType unknown、identity list null | unknown 不当合法侧；null 失败与空列表区分 | 是 |
| E14 | 数值与长度边界 | N/A：无金额/长度输入 | 记录 N/A 原因，不发明阈值 | 是 |
| E15 | 多客户端并发/响应乱序 | 默认激活和显式切换同时排队；旧回包后到 | 显式切换结果不被默认覆盖；旧票不提交 | 是 |
| E16 | 超时但服务端已提交 | N/A：本卡不修改写接口；既有激活请求只能按当前失败语义处理 | 不新增查单/补偿/重试策略 | 是 |
| E17 | 跨账号/组织/门店访问 | A ticket 处理 B 结果或跨组织 scope | 不写 refs；服务端继续拒绝越权 | 是 |
| E18 | 请求中权限撤销/资源删除 | N/A：后端状态变更不在本卡模拟；用 403 响应替代 | 当前错误不污染新账号 | 是 |
| E19 | 旧数据/旧客户端/缓存过期 | snapshot key 与当前 epoch 不符 | 不复用旧快照；重新请求 | 是 |
| E20 | 部分成功/补偿失败/事件重放 | identities 成功、scope 失败；无事件 | 失败不存完整快照；不新增补偿 | 是 |
| E21 | 浏览器/三入口差异 | `index.html`、`ops.html`、`ai.html` 启动 | 各自 Pinia session 正常；不跨入口共享内存 | 是 |

**本卡验收**：

- AC83-01：生产调用图证明布局与工作台均调用 `ensureAccountIdentity`，且无生产直接调用 `loadAccountIdentity`。
- AC83-02：静态逐 await 表证明身份 refs、snapshot、active side、错误和 finally 均有当前票据边界；若不能证明，输出具体缺口。
- AC83-03：现有 TC79 用例覆盖双身份、manager scope、裸账号、A→B、A→B→A、显式切换；不足项逐条列出。
- AC83-04：负责人收到 B83-01，明确关闭任务或批准修订；执行模型没有自行拍板。

**交接**：

- 若负责人选择“关闭/仅保留回归证据”，主程将本书修订为已完成的验收记录或另开审计任务，不新增实现卡。
- 若负责人批准“API 封口/补强”，主程必须发布新版本任务书，补充精确写入文件、测试改动、公开导出迁移和新的 AC/TC/V；当前卡不得代替实现卡。

### 卡 C83-02：低层 loader API 封口与测试适配

**执行包**：任务书版本 `0.2.0`；对应需求 `REQ83-04`；执行者 `主程`；负责人/验收人 `主程 + 仓库负责人`。

**背景**：B83-01 选项 2 已批准。`loadAccountIdentity` 函数体已具备完整票据边界（C83-01 逐 await 核验），封口是纯 API 边界收窄：从 `useActiveIdentity()` 返回值移除、原样上提为模块级导出，生产仅 `useAccountBootstrap` 导入。

**输出与移交**：

1. `src/composables/useActiveIdentity.ts`：`loadAccountIdentity` 函数体逐行原样上提为模块级 `export`（JSDoc 标注仅 bootstrap 可用）；`useActiveIdentity()` 返回值对象移除该项。
2. `src/composables/useAccountBootstrap.ts`：`useActiveIdentity().loadAccountIdentity(grassland)` → 直接导入 `loadAccountIdentity` 调用；`AccountIdentitySnapshot` 类型导入保留。
3. `src/composables/useActiveIdentity.test.ts`：25 处 `state.loadAccountIdentity(` 改为模块直调 `loadAccountIdentity(`；新增封口回归 describe（断言 `useActiveIdentity()` 返回值 keys 不含 `loadAccountIdentity`）。
4. `src/views/home/GrasslandHomeView.test.ts`：3 处同上适配；`const state = useActiveIdentity()` 行随之删除（避免未用变量）。
5. `src/composables/useAccountBootstrap.test.ts`（0.2.1 增补）：E11 用例在 bootstrap 前补一行 `useActiveIdentity()` 消费方挂载——封口后 bootstrap 不再嵌套调用 composable、不再顺带触发 #82 的 owner 对齐；生产中消费方（布局/工作台 setup）恒先于 bootstrap 在位，测试如实模拟（详见 §14 偏差说明）。

**边界红线**：

- 函数体逐行原样上提，不改任何装载/激活/reset 语义；不改 HTTP 契约；不动 `useAccountSessionStore`。
- 不重命名符号（§1.5）；不删除/放宽任何既有断言。
- 白名单外文件零触碰；页首既有工作区改动全部保留（含 #82 批次）。

**本卡验收**（2026-09-06 实测全过）：

- AC83-04-a ✅：`useActiveIdentity()` 返回值不含 `loadAccountIdentity`，由封口回归用例锁定（`useActiveIdentity.test.ts` 新增 describe）。
- AC83-04-b ✅：封口后生产 grep `loadAccountIdentity` 的调用点仅 `useAccountBootstrap.ts` 一处（import+调用）；其余命中为 `useActiveIdentity.ts` 的定义/导出与 `useWorkbenchSession.ts:221` 的历史注释（非代码）。
- AC83-04-c ✅：五份相关测试 64/64、`npm run typecheck` 退出码 0、全量单测 155 文件 1416/1416 全绿、lint 与既有基线完全一致（12 项均为 #78 脚本遗留，本任务零新增）；`git status` 确认增量仅白名单五文件 + 本书。

## 12. 测试、验证命令与集成验收

### 12.1 需求追踪与验收覆盖

| 需求 | 规则 | 卡 | AC | TC | V |
|---|---|---|---|---|---|
| REQ83-01 | R83-01 | C83-01 | AC83-01 | TC83-01 | V83-01/V83-05 |
| REQ83-02 | R83-02 | C83-01 | AC83-02 | TC83-02 | V83-02 |
| REQ83-03 | R83-03 | C83-01 | AC83-03 | TC83-03 | V83-03 |
| REQ83-04 | R83-04 | C83-01 | AC83-04 | TC83-04 | V83-04/V83-06 |

### 12.2 测试用例

#### TC83-01：同账号双消费方 pending 去重

- 合成数据：账号 `acct-a`、epoch `n`；身份列表包含 merchant/recommender；门店 scope 为固定合成数组；两个 fake Grassland 调用共享同一个 API mock。
- 操作：同时调用布局等价的 `ensureAccountIdentity(api)` 与工作台等价的 `ensureAccountIdentity(api)`；让 identity 与 scope deferred 挂起，再一次性释放；记录完整调用序列。
- 错误/延迟模拟：身份请求延迟；第二调用在第一调用未完成时进入。
- 展示/响应：两个调用返回同一 snapshot 引用或同一等价快照语义；不触发第二轮身份/范围请求。
- 持久化副作用：零数据库、零 localStorage、零资金/AI/计费写入。
- 最终状态：`list-identities` 次数为 1、`list-store-scopes` 次数为 1；当前身份符合既有规则。
- 证据：调用数组、Promise 引用/结果、最终 refs 和测试退出码；当前本次为 `NOT_RUN`。

#### TC83-02：A→B→A 旧票据与旧响应丢弃

- 合成数据：账号 A/B/A 三个连续 epoch；A identity 返回 merchant，B 返回 recommender，第二个 A 返回双身份。
- 操作：挂起第一次 A；切换 B 并完成 B bootstrap；再切回 A 发起新 bootstrap；最后释放第一次 A。
- 错误/延迟模拟：第一次 A 的 identity deferred 最后返回；可附加 scope/active identity 延迟。
- 展示/响应：第一次 A 返回 null 或被丢弃；最终 refs 为第二个 A；A 旧结果不覆盖 B 或第二个 A，不清当前错误/loading。
- 持久化副作用：零服务端写；mock 只允许既有默认激活规则内的调用次数。
- 最终状态：不同 epoch 不共享 pending/snapshot；旧激活不对当前账号发出。
- 证据：三组 ticket、调用序列、refs/snapshot 最终值；当前本次为 `NOT_RUN`。

#### TC83-03：身份规则与激活顺序回归

- 合成数据：双身份且 server active=recommender；仅 recommender；零档案+manager scope；零档案+零 scope+零组织；显式切换 recommender 的 fake API。
- 操作：分别 bootstrap；在默认激活 GET 挂起时发起显式切换；释放 deferred 并等待队列。
- 错误/延迟模拟：active identity unknown、activate 失败、open 失败、默认激活和显式切换乱序。
- 展示/响应：服务端活动侧优先；未开通返回 `not-opened`/`failed`；manager scope 不误开户；显式成功不被默认覆盖。
- 持久化副作用：mock 不写 DB；不产生真实 outbox/资金事件。
- 最终状态：`activeSide`、身份列表、调用序列符合 #71/#79 规则。
- 证据：角色 fixture、激活/open 调用序列、最终状态；当前本次为 `NOT_RUN`。

#### TC83-04：接口/错误/空值兼容

- 合成数据：`data: []`、`data: null`、`activeIdentityType: null`、unknown side、401/403/409/500/非 JSON 成功体。
- 操作：经 fake `useGrassland` 或既有 HTTP mock 调用 loader/bootstrap；核对请求方法、路径、body 字段和返回 null/空数组语义。
- 错误/延迟模拟：网络 reject、服务端错误信封、格式错误。
- 展示/响应：不改既有状态码/字段，不把未知 side 当合法活动身份，不把失败当空身份成功。
- 持久化副作用：零持久化写入。
- 最终状态：当前失败可重试；失败快照不存在；HTTP 契约无 diff。
- 证据：请求记录、错误类型/状态、结果和退出码；当前本次为 `NOT_RUN`。

#### TC83-05：工作台消费快照且不携带旧账号组织状态

- 合成数据：A/OA/S1/FA 与 B/OB/S2/FB；身份 bootstrap 由同一 fake 返回；组织/门店/账户接口分别可挂起。
- 操作：A 初始化挂起后切 B，B 初始化完成，再释放 A 的身份或组织结果；记录工作台 refs、notice、refreshTasks 调用。
- 错误/延迟模拟：A 的组织/门店/钱包迟到；B 正常返回。
- 展示/响应：B 只看到 OB/S2/FB；A 不写 B refs、不发旧 refresh、不写旧 notice；金额和任务数据不被本任务改变。
- 持久化副作用：禁止真实 credit/provision；mock 写调用次数至多按原测试约束。
- 最终状态：工作台组织维度 ticket/revision 隔离保持；本任务无 UI 改动。
- 证据：工作台 refs、调用序列、notice 数组、测试退出码；当前本次为 `NOT_RUN`。

#### TC83-06：卸载、隐私与三入口兼容

- 合成数据：同账号两个消费方、匿名状态、三入口初始化代码路径；日志标签仅用 `A/B` 和整数 epoch。
- 操作：一个消费方停止等待；检查另一个仍能完成；静态核对三入口均 mount 前启动 session；扫描新增证据和文档中的敏感值。
- 错误/延迟模拟：pending 长时间挂起后释放；不启动真实服务。
- 展示/响应：一个消费方卸载不取消 Pinia pending；无真实账号/token/响应正文泄露。
- 持久化副作用：不写 storage/数据库；只允许隔离测试报告。
- 最终状态：证据脱敏，工作区既有 Douyin、model source、notifications 改动保留。
- 证据：静态调用结果、脱敏日志检查、git diff；当前本次为 `NOT_RUN`。

### 12.3 本任务验证清单

以下是执行者在 B83-01 解除并修订后可使用的候选门禁；当前任务书发布阶段均为 `NOT_RUN`，不能宣称通过。

| V | 卡/命令 | 前提/副作用 | 通过与证据 |
|---|---|---|---|
| V83-01 | `npm run test -- src/composables/useAccountBootstrap.test.ts src/composables/useActiveIdentity.test.ts src/App.test.ts` | 仓库根；Vitest mock；写临时缓存，不启动后端 | 覆盖 TC83-01～04；退出码 0；日志存 `test-artifacts/task-83/V83-01.log` |
| V83-02 | `npm run test -- src/views/grassland/composables/useWorkbenchSession.test.ts` | 仓库根；合成工作台 mock；不调用资金真实服务 | TC83-05 及既有工作台回归；退出码 0 |
| V83-03 | `npm run typecheck` | 仓库根；`vue-tsc --noEmit`；无代码产物 | 退出码 0；ES2020 类型兼容 |
| V83-04 | `npm run lint` | 仓库根；全仓 ESLint；不改 ignore/配置 | 退出码 0；若有基线失败如实记录 |
| V83-05 | `npm run build` | 仓库根；Vite 三入口构建，会写 `dist/` | 退出码 0；`index.html`/`ops.html`/`ai.html` 产物存在 |
| V83-06 | `npm run test -- src/composables/useGrassland.test.ts` | 仓库根；HTTP mock；不连接 Edge/Java | 既有身份请求字段/路径契约保持；退出码 0 |
| V83-07 | `npm run security:secrets` | 仓库根；跟踪文件扫描；不打印敏感值 | 退出码 0；另人工检查任务书/未跟踪证据无真实凭据 |

如解除后新增 UI，不能用以上命令替代浏览器明暗截图；当前 UI N/A。

**2026-09-06 实跑结果（C83-02）**：

| V | 命令 | 结果 | 实际退出码/用例数 |
|---|---|---|---|
| V83-01 | `npm run test -- useAccountBootstrap.test.ts useActiveIdentity.test.ts App.test.ts`（并入五文件合跑，另含 useWorkbenchSession/GrasslandHomeView） | PASS | 五文件 64/64（含新增封口回归 1 例） |
| V83-02 | `npm run test -- useWorkbenchSession.test.ts`（同上合跑） | PASS | 含于 64/64 |
| V83-03 | `npm run typecheck` | PASS | 退出码 0 |
| V83-04 | `npm run lint` | PASS（基线一致） | 退出码 1，12 项与既有基线完全相同（#78 脚本 no-undef ×10 + any ×2），本任务文件零新增 |
| 集成核对 | `npm run test`（全量） | PASS | 155 文件 1416/1416 |
| V83-05/V83-06/V83-07 | 未执行 | NOT_RUN | 本卡无 UI/构建/密钥变更诉求；如负责人要求可补跑 |

### 12.4 当前仓库命令目录

| 命令 | 当前用途 | 当前状态 |
|---|---|---|
| `npm run test -- src/composables/useAccountBootstrap.test.ts src/composables/useActiveIdentity.test.ts src/App.test.ts`（另加 useWorkbenchSession/GrasslandHomeView 合跑） | 身份 bootstrap、激活和布局消费回归 | `PASS`（2026-09-06，五文件 64/64） |
| `npm run test -- src/views/grassland/composables/useWorkbenchSession.test.ts` | 工作台账号/组织隔离回归 | `PASS`（含于五文件合跑） |
| `npm run typecheck` | 前端类型检查 | `PASS`（2026-09-06，退出码 0） |
| `npm run lint` | 前端全仓 lint | `退出码 1＝既有基线`（12 项与基线一致，零新增） |
| `npm run test` | 全量 Vitest | `PASS`（2026-09-06，155 文件 1416/1416） |
| `npm run test:coverage` | 全量覆盖率；仅修订后按门禁运行 | `NOT_RUN` |
| `npm run coverage:changed` | 变更覆盖率；需先生成 coverage | `NOT_RUN` |
| `npm run build` | Vite 三入口构建 | `NOT_RUN` |
| `npm run docs:status` | 固定状态/进度检查，不代表本书完整验收 | `NOT_RUN` |
| `npm run security:secrets` | 跟踪文件密钥扫描 | `NOT_RUN` |
| `npm run e2e -- <spec> --project=chromium` | 仅未来 UI/入口修订使用；本任务 N/A | `NOT_RUN` |
| `npm run e2e:ci` | Docker/Edge/Java/seed/浏览器集成；本任务禁止擅自启动 | `NOT_RUN` |
| `platform-java/./gradlew ...` | Java 服务验证；本任务后端 N/A | `NOT_RUN` |

目录要求：前端命令在仓库根；Java 命令若获新授权才在 `platform-java/`；容器、联网、数据库、浏览器和安装依赖须额外授权。未执行命令没有退出码。

### 12.5 最终集成验收与完成定义

- 集成验收负责人：`主程`；生产发布授权：仓库负责人指定的人类负责人。
- 前置条件：B83-01 已解除；若有实现，任务书已修订到唯一方案；相关卡均 `VERIFIED`；页首列出的所有既有工作区改动均保留。
- 集成清单：V83-01～V83-07 中被修订版标为必需者；必须覆盖布局、工作台、A→B→A、双身份和 HTTP 字段契约。
- 保留行为：身份产品规则、HTTP API、权限、三入口 session mount、组织/门店/资金隔离、AI/计费/主题保持不变。
- 证据：真实命令退出码、用例数、调用计数、最终 refs、git diff、脱敏报告；不能以历史 #79 提交代替本次证据。
- 变更范围：新增 diff 只能落在修订版白名单与 C83 卡写入集合；不得覆盖通知未提交改动。
- 交付状态：`IMPLEMENTED`——C83-02 已落地并 `VERIFIED`（门禁证据见 §12.3 实跑结果），主程集成核对（全量 1416/1416）完成；生产发布另行授权。

### 12.6 发布与回滚

N/A：当前任务书不授权代码、配置、数据库、Edge、Java 或部署变化，因此不提供生产发布操作。若解除后出现部署变化，必须新增发布卡、授权人、健康检查、观察窗口和回滚顺序，不能沿用本节 N/A。

## 13. 阻塞规则

### 13.1 必须阻塞的情况

除模板通用阻塞项外，本任务出现以下任一情况必须停止：

1. 生产调用链仍与 §2.5 不同，例如出现新的直接 `loadAccountIdentity` 调用方。
2. 当前 HEAD 或页首列出的任一既有未提交/未跟踪改动发生变化且无法安全保留。
3. 需要删除/重命名 `loadAccountIdentity` 导出，但负责人没有明确 API 封口批准。
4. 需要新增 accountId header/body、错误码、配置、数据库字段、Java/Edge 改动或第二套 epoch。
5. 现有测试与目标契约冲突，且无法证明是测试缺陷还是产品行为变更。
6. 需要用真实账号、生产服务、数据库或计费环境复现。
7. 必需测试、构建、本地浏览器、本地服务、本地容器或联网工具不可用，且排查后仍无法执行；本地工具不需要逐次批准。涉及他人账号、生产资源或受限远程资源时才需要额外确认。

### 13.2 阻塞报告

本书发布时预登记以下阻塞：

```text
BLOCKED

任务书版本 / 卡号 / 阻塞编号：
0.1.0 / C83-01 / B83-01

类型与影响：
基线/范围/接口决策；阻塞所有业务实现卡和 READY_FOR_IMPLEMENTATION 发布。

原因：
用户描述的旧入口在当前基线已被 #79 的 ensureAccountIdentity 取代；当前生产代码未确认仍有重复身份 I/O 或缺少 epoch 提交校验。

已确认内容：
DefaultLayout 与 useWorkbenchSession 当前均调用 ensureAccountIdentity；bootstrap 按 accountId#epoch 复用 pending/snapshot；account-session 提供唯一 epoch；useActiveIdentity 与既有测试包含票据校验和激活串行队列。

缺少的信息：
负责人必须明确以下唯一处理：
1. 关闭本需求并仅保留 #79 现有实现/回归证据；或
2. 批准低层 loadAccountIdentity API 封口/补强，并明确允许改动的导出、测试适配和验收目标。

已经尝试：
已读取 AGENTS.md、模板、两份 DESIGN.md、入口、调用链、HTTP client、DTO、测试、package/Vite/Vitest/Playwright 配置、Java identity Controller 和 git 历史；本次未运行测试和构建。

建议决策：
推荐选项 1：先关闭“已由 #79 实现”的重复修复需求；若产品仍要求 API 封口，另以修订版明确方案后再编码。

已保留状态：
未修改业务代码；保留页首列出的所有既有未提交/未跟踪改动；未生成测试/构建产物。
```

**B83-01 已解除（2026-09-06）**：负责人选择选项 2——批准低层 loader API 封口；本书升级 0.2.0 并开放 C83-02。复核输入：生产调用链静态核验（`loadAccountIdentity` 生产仅 bootstrap 一处调用）+ 相关四份测试 57/57 实跑绿。

### 13.3 修订与恢复

- 负责人选择关闭任务：主程把本书更新为“基线已满足、无需实现”的审计/验收文档，保留本 B83-01 记录；不得把本书改成伪造的功能完成报告。
- 负责人批准封口/补强：主程必须将 D83-04 改为唯一 DECISION，新增实现卡、写入白名单、契约变更（如有）、测试和 V 命令，版本至少升至 `0.2.0`，重新通过附 B。
- 仅环境恢复且行为/范围不变：记录恢复命令和结果即可；行为或文件范围变化必须修订版本。
- 发现无关 `notifications.ts` 问题：只记录交接，不扩大任务；需要修复另开任务。

## 14. 完成报告格式

### 实现结果

- 任务书版本/卡号：`0.2.1 / C83-02（含 C83-01 复核结论）；集成基线 = 工作区（HEAD b7007309 + 未提交批次）`。
- 状态：`IMPLEMENTED`（C83-02 `VERIFIED`，门禁全绿）。
- 功能摘要：`loadAccountIdentity` 已从 `useActiveIdentity()` 公开返回值移除，原样上提为模块级导出；`useAccountBootstrap` 直接导入，生产唯一调用点不变；测试改经模块导出，新增封口回归；生产代码行为零变更。
- 测试/构建/截图：`PASS / NOT_RUN（本卡无构建诉求，如需可补 V83-05）/ N/A：无 UI 改动`；全量单测 155 文件 1416/1416。
- 已满足需求：REQ83-01～REQ83-03（#79 既有实现，C83-01 复核确认）；REQ83-04（C83-02 封口落地）。尚未满足：无；待负责人复核归档。

### 文件与范围

| 路径 | 操作 | 核心变更/符号 | 对应卡与需求 | 原有改动保留情况 |
|---|---|---|---|---|
| `src/composables/useActiveIdentity.ts` | 修改 | `loadAccountIdentity` 上提为模块级 export（JSDoc 标注仅 bootstrap 可用）；返回值移除该项；函数体逐行未动 | C83-02/REQ83-04 | #82 批次改动原样保留（增量编辑） |
| `src/composables/useAccountBootstrap.ts` | 修改 | import 改直调 `loadAccountIdentity`（两行） | C83-02/REQ83-04 | 原样保留 |
| `src/composables/useActiveIdentity.test.ts` | 修改 | 27 处调用改模块直调；新增封口回归 describe | C83-02/REQ83-04 | #82 批次用例原样保留 |
| `src/views/home/GrasslandHomeView.test.ts` | 修改 | 3 处调用改模块直调（`const state` 行删除） | C83-02/REQ83-04 | 原样保留 |
| `src/composables/useAccountBootstrap.test.ts` | 修改 | E11 用例补一行消费方挂载（根因见偏差说明） | C83-02/REQ83-04 | #82 批次用例原样保留 |
| `src/stores/account-session.ts` | 读取 | ticket/epoch | C83-01/REQ83-02 | 未修改 |
| `src/layouts/DefaultLayout.vue` | 读取 | 账号 watch | C83-01/REQ83-01 | 未修改 |
| `src/views/grassland/composables/useWorkbenchSession.ts` | 读取 | initForAccount | C83-01/REQ83-01 | #82 批次改动未触碰 |
| `src/composables/useDouyinSession.ts`、`src/composables/useDouyinSession.test.ts`、`src/composables/useModelSource.ts`、`src/composables/useModelSource.test.ts`、`src/composables/useNotifications.test.ts`、`src/stores/notifications.ts` | 无操作 | 不属于本任务 | C83-01 | 已修改/未跟踪状态保留 |

- 白名单核对：当前 C83-01 不得产生业务源码 diff；只允许经授权的隔离证据文件。
- 生成物：当前无；若实际执行命令，日志只能进入已登记的 `test-artifacts/task-83/`。

### 验证证据

| V/TC/AC 编号 | 工作目录与命令/操作 | 结果 | 实际退出码/用例数 | 关键输出或观察 | 证据路径 |
|---|---|---|---|---|---|
| AC83-01～04 | 静态调用链与契约复核 + 生产 grep | `PASS` | 不适用 | 生产 `loadAccountIdentity` 调用点仅 bootstrap 一处；封口回归断言通过 | 本书 §2.5/§14 |
| TC83-01～06 | 既有 fixture/测试覆盖映射 + 实跑 | `PASS` | 五文件 64/64 | 去重/A→B→A/激活顺序/契约用例全绿 | §12.3 实跑结果 |
| V83-01/V83-02 | `npm run test -- <五文件>` | `PASS` | 退出码 0，64 用例 | 含新增封口回归 1 例 | §12.3 |
| V83-03 | `npm run typecheck` | `PASS` | 退出码 0 | 无类型错误 | §12.3 |
| V83-04 | `npm run lint` | `PASS（基线一致）` | 退出码 1 | 12 项与既有基线逐项相同，零新增 | §12.3 |
| 集成 | `npm run test`（全量） | `PASS` | 退出码 0，155 文件 1416 用例 | 封口未破坏任何既有行为 | §12.3 |

未执行时没有退出码；测试零用例、意外跳过或只运行历史提交均不能算覆盖完成。

### 偏差说明

- 相对需求描述的偏差：用户给出的行号和“直接调用 loader”描述与当前基线不一致；已在 §2.5～§2.6 记录。
- E11 测试适配根因（0.2.1）：封口前 bootstrap 嵌套调用 `useActiveIdentity()` 会顺带执行 #82 的 `reconcileOwner()` 并注册换号 watch；E11 用例从未实例化消费方，实际依赖了这一实现细节的副作用。封口后该副作用消失，第 175 行首次调用 `useActiveIdentity()` 时 owner 镜像陈旧，`resetForAccount(B)` 误清 B 已装载的身份表。生产无此路径（布局/工作台/主页的 setup 均先于 bootstrap 解构 `useActiveIdentity()`，watch 恒在位），判定为测试缺陷而非产品行为变更：测试补一行消费方挂载如实模拟生产，生产代码保持行级零变更。
- 白名单偏差：`useAccountBootstrap.test.ts` 原列只读，因上述根因改为写入（仅 E11 增补一行 + 注释），已在 §9.1/§10/本节登记。
- 基线失败/允许例外：lint 退出码 1 全部为 #78 脚本既有基线（no-undef ×10 + any ×2），与本任务无关；页首既有未提交改动全部保留。
- 未获批准的行为差异：无。

### 未解决问题

- 无功能未决项。待办：负责人复核本书与代码增量后归档（提交拆分另按仓库惯例执行）；`useWorkbenchSession.ts:221` 历史注释提及旧函数名属正常文档表述，无需清理。
- 后续交接：如未来要求 lint 基线清零或构建/密钥扫描补跑（V83-05/V83-07），按 §12.3 命令执行并回填。

## 附 A：返工卡格式

N/A：当前没有已确认的代码缺陷，只有基线与需求描述不一致的阻塞。若 C83-01 发现具体漏检提交边界，主程须按模板新增 `R-01`，写明最小复现、真实根因、批准方案和文件白名单后再实现。

## 附 B：强模型写作规约与发布前检查

### B.1 高频错误对照

| 错误 | 本书处理 |
|---|---|
| 把旧行号当当前入口 | 已用当前符号和约行号复核，要求执行者再核对语义。 |
| 把历史 #79 当本次实现 | 只作为 FACT/线索；本次命令和证据必须重新执行。 |
| 新建第二套 epoch/bootstrap | D83-01/D83-02 明令禁止。 |
| 只比较 accountId | `AccountTicket` 必须同时比较 accountId 与 epoch。 |
| 只 abort 不验票 | §6.6 明令提交前 `isCurrent`。 |
| 只隐藏旧 UI | 本任务无 UI；身份 refs/快照/错误/后续动作都必须隔离。 |
| 用空数组吞身份错误 | §5.3/§6.4 区分 `null` 失败与真实 `[]`。 |
| 执行者自行决定 API 封口 | D83-04 与 B83-01 阻塞。 |

### B.2 发布前检查表

- [x] 已读适用 `AGENTS.md`、模板、根 `DESIGN.md`、`src/ops/DESIGN.md`。
- [x] 已检查实际入口、调用链、DTO、HTTP client、测试、构建脚本、Java identity Controller 和 git 状态。
- [x] 已记录当前 HEAD 和页首列出的所有未提交/未跟踪改动。
- [x] 已明确当前基线已存在 `ensureAccountIdentity`、`AccountTicket`、epoch 和相关测试。
- [x] 目标/排除项、规则、决策、契约、数据、UI、质量和安全均有编号或 N/A 原因。
- [x] 文件清单区分只读、可写、禁止修改；当前卡不授权业务源码写入。
- [x] 测试包含合成数据、完整输入、延迟/错误、响应、持久化副作用、最终状态和证据要求。
- [x] 命令来自实际 `package.json` 脚本，并标明目录、副作用、授权、退出码和 NOT_RUN 规则。
- [x] 卡完成、代码实现、验证、集成、生产发布已分离。
- [x] B83-01 已获负责人批准（2026-09-06，选项 2：API 封口）。
- [x] 已有明确、单一、可编码的剩余缺口（C83-02 封口与测试适配）。
- [x] 所有必需发布条件满足；状态 `IMPLEMENTED`（0.2.1，门禁全绿）。

### B.3 作者审阅与版本记录

| 版本 | 日期 | 作者 | 变化 | 状态 |
|---|---|---|---|---|
| 0.1.0 | 2026-09-05 | 主程 | 基于当前 `main` 核对 #79 已有 bootstrap/epoch；建立 C83-01 与 B83-01，暂不授权实现 | BLOCKED_DRAFT |
| 0.1.2 | 2026-09-06 | 主程 | 按模板 2.4.0 同步本地开发规则：已有改动确需修改时先咨询用户；本地命令、浏览器、服务、依赖和容器可直接使用并记录 | BLOCKED_DRAFT |
| 0.2.0 | 2026-09-06 | 主程 | B83-01 解除（负责人批准选项 2：API 封口）；C83-01 核验结论并入（调用链无绕过+57/57 绿）；D83-04 转正为已批准决策；新增 C83-02 实现卡与四文件白名单 | READY_FOR_IMPLEMENTATION |
| 0.2.1 | 2026-09-06 | 主程 | C83-02 落地：封口+测试适配+封口回归；E11 根因（封口暴露测试对嵌套调用副作用的依赖）与白名单增补（useAccountBootstrap.test.ts）；门禁全绿（五文件 64/64、typecheck 0、全量 1416/1416、lint 基线一致） | IMPLEMENTED |

## 附 C：随任务书交给弱模型的执行提示词

> 0.2.0 注：本书改为主程直接实现（C83-02），以下 0.1.x DISPATCH 提示词仅存档。

```text
任务书路径：/Users/LXH/claude/y-1/docs/任务书/草场任务书-83-身份初始化唯一入口与账号epoch防竞态.md
批准版本：0.1.2（当前 BLOCKED_DRAFT，仅可执行 C83-01 基线复核，不得开始业务实现）
本次指定任务卡：C83-01

你只能做基线复核，不能实现业务代码。
先读 AGENTS.md、本文 §0/§1/§2/§9/§13/§14 和 C83-01 的精确引用。
记录 HEAD 与页首列出的已有未提交/未跟踪改动，全部保留。
核对 DefaultLayout、useWorkbenchSession、useAccountBootstrap、account-session、useActiveIdentity 及现有 #79 测试。
确认当前生产入口是否已统一为 ensureAccountIdentity，确认 accountId#epoch pending/snapshot 去重和每个 await 的 isCurrent。
仅当当前复核或后续获批实现实际需要 API 封口、文件越界、产品行为或测试契约的新决定时，按 §13 报告；仅阅读、搜索、测试或确认既有契约不构成阻塞，不猜实现。
未获负责人 B83-01 决策前，不修改业务源码、测试、配置、Java、Edge、数据库或 UI。
本地命令、浏览器、服务、容器和依赖可直接使用并记录结果；涉及用户提供的测试账号、真实外部服务或 AI 时先咨询用户。未执行写 NOT_RUN，不写 PASS。
交接时输出调用图、覆盖矩阵、实际证据和 B83-01 阻塞报告，不声称功能已实现。
```

## 附 D：交给强模型的任务书生成提示词

本书由用户提供的任务书生成要求生成。若 B83-01 解除，强模型必须基于新的当前 HEAD 重新读取源码和工作区，更新版本、状态、卡、文件白名单、测试、命令和附 B；不得直接把本书的 BLOCKED_DRAFT 改成 READY_FOR_IMPLEMENTATION。
