# AGENTS.md

## 本机资源约束（开发、启动服务与验证前必读）

本机 Mac 的 CPU 与内存有限。所有执行者、会话、worktree 与验收脚本必须遵守：**同一时间只运行一套本项目应用栈，每个阶段只启动当前任务必需的最小服务及其真实依赖，重型任务串行执行。** 隔离测试不等于允许与开发栈同时运行。本节适用于本地开发与验证，不改变生产部署拓扑或必需验收范围。

**执行入口**：本地 Compose 启停/构建必须使用 `npm run stack -- ...`（`scripts/local-stack.mjs`）或已经接入它的验收脚本；新增入口复用 `scripts/lib/local-stack.sh`，禁止裸 Docker 命令绕过检查。用法与已接入范围见 [本地启动与资源守卫](docs/架构/本地启动与资源守卫.md)。普通开发使用显式 `stop` 保留数据；只有守卫已确认全新的 `--fresh` 隔离验收会话可以 `reset` 本次创建的测试资源。

1. **先盘点再启动**：启动服务、构建镜像或执行可能自动起容器的测试前，检查 `docker compose ls`、`docker ps`、`docker stats --no-stream`，核对 Compose project 标签、服务、端口、卷归属及当前用途；同时检查本项目已运行的开发服务器、测试进程与构建进程。Docker 不可用时不能据此判断“没有运行中的栈”。执行前说明本阶段目标栈、最小服务清单、复用/停止对象与收尾方式。
2. **单栈复用与切换**：优先复用已确认兼容的本项目栈，固定使用已核实的 Compose 项目名和文件组合；禁止通过换项目名、换端口、换 worktree 或另开一套完整栈绕过冲突。需要隔离数据库或不同配置时，先停止已确认闲置的本项目旧栈，再启动隔离栈，保留旧卷与数据；不得把破坏性测试指向开发库来“省一套栈”。旧栈仍被其他会话使用或归属不明时，不擅停，也不另起第二套，先协调释放，期间可继续不依赖该栈的工作。
3. **明确服务白名单**：按本次测试链路列出所需服务及必要依赖，使用仓库已有入口和正确的 overlay/profile 组合显式选择。禁止默认执行不带服务名的全量 `docker compose up -d`、启用全部 profiles，或顺手启动监控、日志、数字人、Hypit、媒体 worker 等无关服务。确需完整端到端链路时，记录必须服务及原因，在唯一栈内执行；不能用 `--no-deps` 或关闭必要依赖掩盖问题。
4. **先读自动起栈入口**：执行 E2E、验收脚本、Testcontainers 测试前，检查其是否会自动 build/up、另设 project、拉起额外服务或复用遗留容器。脚本名、传入服务名或分阶段启动不代表它只启动最小服务；尤其须核对 `scripts/acceptance/hypit-compose.sh`、`verify-107-fix-2.sh`、`verify-107-full.sh` 的实际行为。不满足本节时先在获准范围内修正入口，或使用已核验的等价最小路径，不能明知全量启动仍直接执行。测试专用临时容器仅限当阶段必要依赖，不得形成另一套应用栈，结束后释放。
5. **按层验证，保持验收完整**：纯文档、静态检查、无需后端的单元测试不启动 Docker 服务；集成测试只启动对应依赖；E2E 只启动覆盖该链路的服务。先定向验证，再按影响面和任务书完成必需回归；资源限制只改变执行顺序与并发，不允许静默跳过必需测试、换 mock 或把未执行记为通过。
6. **限制并发与峰值**：镜像构建、Gradle 编译/测试、浏览器 E2E、媒体渲染等重型工作，同一时间只执行一个，跨会话也不能各自同时跑一份；默认单 worker/单任务并发，具体参数先核对工具与项目配置。服务尽量逐个启动、健康后再继续；禁止并行全量构建、多套浏览器测试、多个渲染任务抢占资源。需要提高并发时先有当前资源余量依据。
7. **阶段结束即释放**：记录任务开始时已存在的服务与本次新增服务。阶段结束、失败或中断后，只停止本次启动且后续不再需要的服务、临时测试容器及相关进程；预先存在的服务仅在确认闲置且属本项目时停止。不自动恢复旧全量栈，不遗留后台 watcher 或无限重试；脚本应覆盖失败和信号退出清理，异常退出后的遗留由下次启动前盘点发现并处理。
8. **保留数据、禁止全局清理**：资源回收默认使用精确作用域的 stop；不要使用 `down -v`、`docker system prune`、`docker volume prune` 或批量停止全机容器来处理资源问题。其他项目或归属不明的服务先报告并协调，不擅自停止、删除或修改 Docker Desktop 的全局资源配置。
9. **资源紧张时停止加载**：出现明显内存压力、持续 swap 增长、OOM 或健康检查因负载反复超时时，先停止新增重型任务，释放本任务不再需要的资源并降低并发；不得通过叠加重试、新栈或更多容器解决。最小真实链路仍无法运行时，报告资源瓶颈和未完成验收，不声称验证通过。
10. **交付说明运行状态**：最终报告列明实际验证范围、使用的栈/服务、已停止的资源以及仍运行的服务和保留原因。任务书 §9.3 必须登记本次最小服务与切换/清理计划；“允许启动本地服务”或“卡片可并行”不豁免本节。
11. **E2E/验收收尾回收构建缓存**：每轮 E2E、验收脚本或镜像构建结束（无论通过、失败还是中断）后，执行 `docker builder prune -af --filter until=24h` 回收超过 24h 的构建缓存。构建缓存会跨轮无限累积（2026-10-03 曾积到 168G 把磁盘吃到 99% 只剩 4.4G）；该命令只作用于构建缓存，不触碰镜像、容器与卷，不属于第 8 条禁止的全局清理。若本轮 Docker Desktop 是本会话启动的，收尾时退出它并确认 `pgrep -f com.docker.backend` 已清零；发现残留 backend 进程必须立即清理，否则会卡死下次 Docker 启动。

## UI 规则（改任何前端 UI 前必读）

本仓库有三个应用入口、两套设计规范：用户端（`index.html`，商家/推荐官/消费者）与 AI 创作端（`ai.html`）共用产品规范，治理台（`ops.html`）使用治理规范：

| 范围 | 规范文件 |
|---|---|
| 用户端与 AI 创作端（`src/views/`、`src/layouts/`、`src/router/`、`src/ai/`） | 根 `DESIGN.md`（grassland-design，任务与内容工作台） |
| 治理台（`src/ops/**`，及仅被治理台引用的 `src/components/` 面板，如各 `*AdminPanel`、`ReputationAdminPanel`） | `src/ops/DESIGN.md`（grassland-admin，队列与处置工作台） |
| 共享组件（`src/components/**`，双端引用，如 `LoginModal`、`EmptyState`） | 默认按根 `DESIGN.md`；确需两端差异化时用 `[data-app="ops"]` CSS 变量作用域隔离，禁止复制组件 |

两端品牌主色同为 `#533afd`（用户端 `{colors.primary}`，治理台 `{colors.primary}`），后台 active 态为 `#4434d4`。

### 硬性规则

1. 修改任何 UI 前必读对应 DESIGN.md（按上表路由）。
2. 颜色、字体、字号、圆角、间距只允许使用 DESIGN.md 中定义的 token，禁止硬编码新值；组件里既有 hex 色值一律换成 `var(--token)`。
3. 新增组件前先检查已有组件是否可复用；样式优先扩 `src/style.css` 全局层（`.glass-card`、`.badge`、`.gl-field`、`.gl-btn-primary`），不要在 scoped 样式里重造一套。
4. 明暗双主题：颜色 token 在 `src/style.css` 的 `:root`（暗）与 `[data-theme="light"]`（亮）两处成对定义，新增或修改 token 必须亮/暗双值同给；暗色表面按同色相加深，文字/状态按对比度提亮。规范的目标 token 未落地时先补全定义再引用。
5. 字体只有两款：Space Grotesk（display 标题）+ Inter（正文/UI），经 @fontsource self-host，禁止引入其他字体或外部字体 CDN。
6. 每页改完后用浏览器截图自查（明暗两主题各一张）：对比度、层级、间距是否违反 DESIGN.md；同时检查移动端、键盘焦点和加载/空/错/提交中状态。

### 校验

```bash
npx @google/design.md lint DESIGN.md
npx @google/design.md lint src/ops/DESIGN.md
grep -ri "sohne\|cal sans\|cal.com\|stripi" DESIGN.md src/ops/DESIGN.md   # 应无输出
```

## 组件分层规约（改五个大视图前必读）

五个大视图 SFC（`GrasslandWorkbench.vue`、`AdminView.vue`、`ArticleCreationView.vue`、`VideoProductionView.vue`、`ImageAnalysisView.vue`）已按任务书 #91 拆分到位，后续触碰必须维持分层去向，禁止把逻辑重新堆回视图：

1. **URL 状态**（地址栏同步/恢复）→ 进视图目录下的 `use*UrlState.ts` composable。
2. **取数与业务流** → 进域 composable（如 `composables/use*` 或视图目录 `composables/`），视图只持有装配与模板绑定。
3. **新页签 / 大区块** → 拆子组件放进视图同级 `components/`（治理台为 `src/ops/admin/tabs/`），跨面板共享的纯数据/工具进同目录共享模块（如 `adminTabs.ts`、`admin-format.ts`）。
4. 视图 SFC 仅允许「纯装配」：组合 composable、provide/inject、子组件编排；函数/区块只有测试桩依赖或单处十行内使用才可留在视图。
5. **体积门禁**：`.vue` 硬顶 800 行（`npm run lint` 末步自动跑 `scripts/check-file-size.mjs`）；豁免清单四文件（DisputeDetailView 1024 / AiCreationCenter 964 / MerchantKybCard 947 / PrecedentLibrary 828）只减不增；`composables/*.ts` 超 500 行仅 WARN。

## 目录归位规约

完整地图与旧路径迁移对照见 [docs/架构/目录结构.md](docs/架构/目录结构.md)。

- 根目录保留应用入口、构建/部署配置和仓库说明；新增截图、一次性脚本与专题文档放入对应目录。
- 自动化测试统一放 `tests/`，前端已有的就近 `src/**/*.test.ts` 继续与源码同目录；不要重新创建 `test/`。
- 需入库的手工验收脚本放 `scripts/acceptance/`；本机临时脚本放 `scripts/local/`（Git 忽略）。默认从仓库根目录执行，产物写 `test-artifacts/<任务>/`。
- 已入库的历史截图保存在 `docs/测试/screenshots/`；本地产物保存在 `test-artifacts/`。任务书约定的 `docs/任务书/evidence/` 保持其指定路径。
- 架构与项目说明放 `docs/架构/`；`docs/status.yaml` 和 `docs/草场开发进度与续接指南.md` 保持固定位置。
