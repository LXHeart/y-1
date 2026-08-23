# AGENTS.md

## UI 规则（改任何前端 UI 前必读）

本仓库是单仓双前端：用户端（`index.html` 入口，营销/内容页）与治理台（`ops.html` 入口，后台管理）。两端各有一份 DESIGN.md 设计规范：

| 范围 | 规范文件 |
|---|---|
| 用户端页面（`src/views/`、`src/layouts/`、`src/router/`） | 根 `DESIGN.md`（grassland-design，Stripe 系） |
| 治理台（`src/ops/**`，及仅被治理台引用的 `src/components/` 面板，如各 `*AdminPanel`、`ReputationAdminPanel`） | `src/ops/DESIGN.md`（grassland-admin，Cal 系） |
| 共享组件（`src/components/**`，双端引用，如 `LoginModal`、`EmptyState`） | 默认按根 `DESIGN.md`；确需两端差异化时用 `[data-app="ops"]` CSS 变量作用域隔离，禁止复制组件 |

两端品牌主色同为 `#533afd`（用户端 `{colors.primary}`，治理台 `{colors.primary}`），后台 active 态为 `#4434d4`。

### 硬性规则

1. 修改任何 UI 前必读对应 DESIGN.md（按上表路由）。
2. 颜色、字体、字号、圆角、间距只允许使用 DESIGN.md 中定义的 token，禁止硬编码新值；组件里既有 hex 色值一律换成 `var(--token)`。
3. 新增组件前先检查已有组件是否可复用；样式优先扩 `src/style.css` 全局层（`.glass-card`、`.badge`、`.gl-field`、`.gl-btn-primary`），不要在 scoped 样式里重造一套。
4. 明暗双主题：颜色 token 在 `src/style.css` 的 `:root`（暗）与 `[data-theme="light"]`（亮）两处成对定义，新增或修改 token 必须亮/暗双值同给，暗色按同色相加深派生。
5. 字体只有两款：Space Grotesk（display 标题）+ Inter（正文/UI），经 @fontsource self-host，禁止引入其他字体或外部字体 CDN。
6. 每页改完后用浏览器截图自查（明暗两主题各一张）：对比度、层级、间距是否违反 DESIGN.md。

### 校验

```bash
npx @google/design.md lint DESIGN.md
npx @google/design.md lint src/ops/DESIGN.md
grep -ri "sohne\|cal sans\|cal.com\|stripi" DESIGN.md src/ops/DESIGN.md   # 应无输出
```
