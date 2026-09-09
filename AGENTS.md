# AGENTS.md

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
