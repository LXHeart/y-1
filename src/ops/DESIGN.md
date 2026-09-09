---
version: "alpha"
name: "grassland-admin"
description: "草场治理台设计语言：以审核队列、证据、财务处置与审计为中心，沿用品牌紫与基础尺度，采用清晰的固定字号和完整双主题。"
colors:
  primary: "#533afd"
  primary-active: "#4434d4"
  on-primary: "#ffffff"
  canvas: "#ffffff"
  canvas-dark: "#101010"
  surface: "#ffffff"
  surface-dark: "#1a1a1a"
  surface-muted: "#f8f9fa"
  surface-muted-dark: "#242424"
  ink: "#111111"
  ink-dark: "#ffffff"
  secondary: "#374151"
  secondary-dark: "#e5e7eb"
  muted: "#6b7280"
  muted-dark: "#a1a1aa"
  border: "#e5e7eb"
  border-dark: "#313131"
  border-control: "#6b7280"
  border-control-dark: "#85858f"
  link: "#4434d4"
  link-dark: "#b9b9f9"
  selected: "#efedff"
  selected-dark: "#24223e"
  success: "#17734b"
  success-dark: "#34d399"
  success-surface: "#eaf6ef"
  success-surface-dark: "#152b25"
  warning: "#8b5709"
  warning-dark: "#f59e0b"
  warning-surface: "#fff4de"
  warning-surface-dark: "#302713"
  danger: "#b42332"
  danger-dark: "#ef6b6b"
  danger-surface: "#fceeee"
  danger-surface-dark: "#341e29"
  info: "#245cb3"
  info-dark: "#60a5fa"
  info-surface: "#edf3ff"
  info-surface-dark: "#19273e"
  overlay: "rgba(13, 37, 61, 0.32)"
  overlay-dark: "rgba(7, 9, 16, 0.75)"
  media-backdrop: "#111111"
  media-ink: "#ffffff"
typography:
  page-title:
    fontFamily: "Space Grotesk, Inter, sans-serif"
    fontSize: "22px"
    fontWeight: 600
    lineHeight: 1.35
    letterSpacing: "0px"
  section-title:
    fontFamily: "Space Grotesk, Inter, sans-serif"
    fontSize: "18px"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "0px"
  card-title:
    fontFamily: "Space Grotesk, Inter, sans-serif"
    fontSize: "16px"
    fontWeight: 600
    lineHeight: 1.5
    letterSpacing: "0px"
  body:
    fontFamily: "Inter, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.6
    letterSpacing: "0px"
  body-sm:
    fontFamily: "Inter, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.55
    letterSpacing: "0px"
  label:
    fontFamily: "Inter, sans-serif"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: 1.45
    letterSpacing: "0px"
  caption:
    fontFamily: "Inter, sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "0px"
  button:
    fontFamily: "Inter, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "0px"
  numeric:
    fontFamily: "Inter, sans-serif"
    fontSize: "24px"
    fontWeight: 600
    lineHeight: 1.3
    letterSpacing: "0px"
    fontFeature: "tnum"
rounded:
  none: "0px"
  xs: "4px"
  sm: "6px"
  md: "8px"
  lg: "12px"
  xl: "16px"
  pill: "9999px"
spacing:
  none: "0px"
  micro: "2px"
  xxs: "4px"
  xs: "8px"
  sm: "12px"
  md: "16px"
  lg: "24px"
  xl: "32px"
  xxl: "48px"
  section: "64px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.button}"
    rounded: "{rounded.md}"
    padding: "0px 16px"
    height: "40px"
  button-primary-active:
    backgroundColor: "{colors.primary-active}"
    textColor: "{colors.on-primary}"
    typography: "{typography.button}"
    rounded: "{rounded.md}"
  application-header:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    height: "64px"
  control-touch:
    typography: "{typography.button}"
    rounded: "{rounded.md}"
    height: "44px"
  media-preview:
    backgroundColor: "{colors.media-backdrop}"
    textColor: "{colors.media-ink}"
    rounded: "{rounded.lg}"
  workspace:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.secondary}"
    typography: "{typography.button}"
    rounded: "{rounded.md}"
    height: "40px"
  text-input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.md}"
    height: "40px"
    padding: "8px 12px"
  helper:
    backgroundColor: "{colors.surface-muted}"
    textColor: "{colors.muted}"
    typography: "{typography.caption}"
  dialog-backdrop:
    backgroundColor: "{colors.overlay}"
  status-success:
    backgroundColor: "{colors.success-surface}"
    textColor: "{colors.success}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: "4px 8px"
  status-warning:
    backgroundColor: "{colors.warning-surface}"
    textColor: "{colors.warning}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: "4px 8px"
  status-danger:
    backgroundColor: "{colors.danger-surface}"
    textColor: "{colors.danger}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: "4px 8px"
  status-info:
    backgroundColor: "{colors.info-surface}"
    textColor: "{colors.info}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: "4px 8px"
  workspace-dark:
    backgroundColor: "{colors.canvas-dark}"
    textColor: "{colors.ink-dark}"
    typography: "{typography.body-sm}"
  button-secondary-dark:
    backgroundColor: "{colors.surface-dark}"
    textColor: "{colors.secondary-dark}"
    typography: "{typography.button}"
    rounded: "{rounded.md}"
    height: "40px"
  text-input-dark:
    backgroundColor: "{colors.surface-dark}"
    textColor: "{colors.ink-dark}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.md}"
    height: "40px"
    padding: "8px 12px"
  helper-dark:
    backgroundColor: "{colors.surface-muted-dark}"
    textColor: "{colors.muted-dark}"
    typography: "{typography.caption}"
  dialog-backdrop-dark:
    backgroundColor: "{colors.overlay-dark}"
  status-success-dark:
    backgroundColor: "{colors.success-surface-dark}"
    textColor: "{colors.success-dark}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: "4px 8px"
  status-warning-dark:
    backgroundColor: "{colors.warning-surface-dark}"
    textColor: "{colors.warning-dark}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: "4px 8px"
  status-danger-dark:
    backgroundColor: "{colors.danger-surface-dark}"
    textColor: "{colors.danger-dark}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: "4px 8px"
  status-info-dark:
    backgroundColor: "{colors.info-surface-dark}"
    textColor: "{colors.info-dark}"
    typography: "{typography.caption}"
    rounded: "{rounded.sm}"
    padding: "4px 8px"
  divider:
    backgroundColor: "{colors.border}"
    height: "1px"
  control-outline:
    backgroundColor: "{colors.border-control}"
    height: "1px"
  divider-dark:
    backgroundColor: "{colors.border-dark}"
    height: "1px"
  control-outline-dark:
    backgroundColor: "{colors.border-control-dark}"
    height: "1px"
  table-row:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.none}"
    padding: "12px 16px"
  queue-selected:
    backgroundColor: "{colors.selected}"
    textColor: "{colors.link}"
    typography: "{typography.label}"
    rounded: "{rounded.sm}"
  table-row-dark:
    backgroundColor: "{colors.surface-dark}"
    textColor: "{colors.ink-dark}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.none}"
    padding: "12px 16px"
  queue-selected-dark:
    backgroundColor: "{colors.selected-dark}"
    textColor: "{colors.link-dark}"
    typography: "{typography.label}"
    rounded: "{rounded.sm}"
  sidebar-active:
    backgroundColor: "{colors.primary-active}"
    textColor: "{colors.on-primary}"
    typography: "{typography.label}"
    rounded: "{rounded.sm}"
    height: "40px"
  sidebar:
    backgroundColor: "{colors.surface-muted}"
    textColor: "{colors.secondary}"
    width: "240px"
  sidebar-dark:
    backgroundColor: "{colors.surface-muted-dark}"
    textColor: "{colors.secondary-dark}"
    width: "240px"
  breadcrumb:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.muted}"
    typography: "{typography.caption}"
    height: "48px"
---

# 草场治理台设计规范

## Overview

**方向：能快速定位对象、看清证据并完成处置的治理工作台。** 适用于 `ops.html`、`src/ops/**` 与仅被治理台引用的共享面板。修订：2026-09-09 · v2。本文直接描述已登录治理业务；基础品牌、字体、状态语义继承 [根规范](../../DESIGN.md)。

治理台面向平台管理员、审核、财务与风控人员。页面的主要内容是待办队列、业务详情、证据和审计记录。视觉重点是当前对象、状态与下一步操作；保留品牌紫，使用中性表面、固定字号、紧凑的表格和安静的分隔线。

YAML `version: alpha` 是文件格式版本。本文定义目标规范，并保留现有壳、五个业务组及权限模型；运行时差异需要按页迁移。所有 `-dark` 是主题契约与校验条目，不是另一套 Vue 组件。

### 设计判断

- 首屏直接回答「当前队列是什么、哪些记录需要处理、依据在哪里」。计数必须来自实际查询，并标明筛选范围。
- 同一业务对象的证据、决策与审计信息可连续阅读；查看详情后返回原筛选、页码和位置。
- 页面主行动留给当前处置；财务、暂停和驳回操作写清对象、原因与结果。
- 从视觉和文案区分加载、无数据、无权限、处理失败与异步处理中。

## Colors

共享状态色与根规范一致；治理台只调整中性画布、文字与边界。YAML 无后缀为亮色，`-dark` 为暗色配对。品牌按钮 `#533afd`、active `#4434d4` 和白字在两主题同值。

| 角色 / YAML token | 目标变量（`src/style.css`） | 亮色 | 暗色 |
|---|---|---|---|
| `primary` / `primary-active` | `--ops-primary` / `--ops-active` | `#533afd` / `#4434d4` | 同亮色 |
| `canvas` | `--ops-canvas` | `#ffffff` | `#101010` |
| `surface` | `--ops-surface` | `#ffffff` | `#1a1a1a` |
| `surface-muted` | `--ops-muted-surface` | `#f8f9fa` | `#242424` |
| `ink` | `--ops-ink` | `#111111` | `#ffffff` |
| `secondary` | `--ops-body` | `#374151` | `#e5e7eb` |
| `muted` | `--ops-muted` | `#6b7280` | `#a1a1aa` |
| `border` | `--ops-hairline` | `#e5e7eb` | `#313131` |
| `border-control` | `--ops-control-border` | `#6b7280` | `#85858f` |
| `link` | `--ops-link` | `#4434d4` | `#b9b9f9` |
| `selected` | `--ops-selected-surface` | `#efedff` | `#24223e` |
| `success` / `success-surface` | `--ops-success` / `--ops-success-surface` | `#17734b` / `#eaf6ef` | `#34d399` / `#152b25` |
| `warning` / `warning-surface` | `--ops-warning` / `--ops-warning-surface` | `#8b5709` / `#fff4de` | `#f59e0b` / `#302713` |
| `danger` / `danger-surface` | `--ops-error` / `--ops-error-surface` | `#b42332` / `#fceeee` | `#ef6b6b` / `#341e29` |
| `info` / `info-surface` | `--ops-info` / `--ops-info-surface` | `#245cb3` / `#edf3ff` | `#60a5fa` / `#19273e` |

`on-primary`、`overlay`、`media-backdrop`、`media-ink` 按根规范配对。颜色全部在全局 `:root`（暗）与 `[data-theme="light"]`（亮）定义；`[data-app="ops"]` 将 `--color-*` / `--surface-*` 映射到 `--ops-*`。新变量先定义两主题，再接入共享组件。对话框 Teleport 到 body 后仍需位于应用主题作用域。

侧栏当前页用 `primary-active` 实底、白字和 `aria-current="page"`。表格选中行用 `selected` 浅底、复选框/边标与 `aria-selected`（仅在适当语义组件中使用）；hover 使用 `surface-muted`，不能和选中态完全相同。内联链接用 `link`，不能把暗色按钮填充色直接用于正文链接。

状态沿用 neutral / info / warning / success / danger；资金未到账、请求已受理均不能显示 success。状态标签必须有文字。普通文字 ≥4.5:1，必要的输入边框、图标、焦点 ≥3:1；装饰表格分隔线可使用 `border`，输入边界用 `border-control`。不再对 muted 文本叠透明度。

选中行内的辅助文字使用 `secondary`，避免浅紫底降低 muted 文本的对比度。

## Typography

Space Grotesk 用于品牌与标题，Inter 用于正文、控件、表格和数字；字体均经 Fontsource 自托管，中文使用现有系统回退栈。继承根规范的 400 / 500 / 600 字重与 0 字距。

| YAML token | 字号 / 字重 / 行高 | 治理台 CSS / 场景 |
|---|---|---|
| `page-title` | 22 / 600 / 1.35 | `--text-xl`；页面标题 |
| `section-title` | 18 / 600 / 1.4 | `--text-lg`；分区或品牌标题 |
| `card-title` | 16 / 600 / 1.5 | `--text-base`；面板标题 |
| `body` | 16 / 400 / 1.6 | `--text-base`；长证据与移动输入正文 |
| `body-sm` | 14 / 400 / 1.55 | `--text-sm`；表格正文 |
| `label` | 14 / 500 / 1.45 | `--text-sm`；导航与字段标签 |
| `caption` | 13 / 400 / 1.5 | `--text-xs`；时间、辅助说明 |
| `button` | 14 / 600 / 1.4 | `--text-sm`；按钮 |
| `numeric` | 24 / 600 / 1.3 | `--text-numeric`；必要的财务总额 |

正文默认 14px，不能靠 11px 小字提升表格密度。金额右对齐，ID/日期保持可复制，用 Inter + tabular-nums，不引入第三款字体。长主体名或任务名允许两行；完整内容可通过可聚焦的详情入口获取，不能只依赖 hover tooltip。

## Layout

### 壳与导航

```text
应用页头：品牌 | 管理后台 / 运营处置 | 当前账号与角色
业务侧栏 | 面包屑：业务组 / 当前页面
         | 标题、范围说明与主要行动
         | 筛选工具条 → 结果数量/批量条
         | 单层表格 → 分页
         | 按需打开详情：上下文 → 证据 → 处置 → 审计记录
```

管理后台侧栏沿用 `src/ops/admin/adminTabs.ts` 的单一 registry：审核队列、用户与主体、交易与财务、内容与 AI、风控与审计。可见组由可见页签派生；会话内记住每组上次页签。设计不得绕过权限显示隐藏业务或再维护一份导航列表。

应用顶部的「管理后台 / 运营处置」是应用目的地；侧栏负责域内页面；页面内 tab 只切换当前对象的子内容。不得用三个相同外形的胶囊条堆叠导航层级。

| 目标 token | 值 | 用途 |
|---|---|---|
| `--ops-header-height` | 64px；移动端 112px | 移动端包含独立目的地行 |
| `--ops-sidebar-width` | 240px；平板 216px | 业务侧栏 |
| `--ops-breadcrumb-height` | 48px | 面包屑行 |
| `--ops-content-width` | 1800px | 管理后台最大工作区 |
| `--ops-console-width` | 1600px | 运营处置最大工作区 |
| `--ops-control-height` | 40px | 桌面按钮、筛选、输入最小高度 |
| `--touch-target` | 44px | 触控目标最小边长，移动端覆盖 40px |
| `--ops-icon-size` / `--icon-size` | 36 / 20px | 品牌/头像 / 控件图标 |
| `--ops-sidebar-sheet-width` | 288px | 移动侧栏上限；保留 48px 关闭区域 |
| `--ops-row-height` / `--ops-row-detail-height` | 48 / 64px | 单行 / 双行表格最小高度，允许内容撑高 |
| `--ops-drawer-width` | 480px | 常规详情抽屉上限，小屏铺满可用宽度 |

基础 spacing 与根规范完全相同：`none=0、micro=2、xxs=4、xs=8、sm=12、md=16、lg=24、xl=32、xxl=48、section=64px`。CSS 使用对应 `--space-*`；桌面工作区内边距 32px，移动横向 16px、纵向 24px。工具条/表格之间 16px，标签/控件之间 8px。`section` 仅属于访客页，不用于治理工作区。

### 响应式

断点与根规范统一：`<768px` 移动、`768–1023px` 平板、`≥1024px` 桌面、`≥1440px` 宽屏。侧栏在移动端变为可关闭 sheet；背景 inert，Escape 关闭并将焦点返回打开按钮。

窄屏让工具条换行；重要筛选保留，其余进入带摘要的面板。桌面表格可在局部容器横向滚动；保留记录标识、状态、金额与主要操作，不能把整页变成横向滚动容器。移动详情使用全宽面板，固定动作栏不得遮挡内容和键盘焦点。

## Elevation & Depth

工作区默认无框。表格外围最多一层 1px 分隔边界，内部分区用行线；不要把表格、每行、单元格都套卡片。表头使用 `surface-muted`，悬浮菜单和抽屉才使用根规范的 `--shadow-elevated` 配对值。

`--shadow-card: none` 在亮暗相同；`.glass-card` 在 ops 作用域关闭 blur。保持纯色主按钮。页面不得出现营销 hero、宣传页 footer、定价卡、装饰渐变或发光指标。证据预览是真实内容，不是装饰性微缩仪表盘。

## Shapes

圆角与根规范相同：无框区 0；小标记 4；侧栏项/徽标 6；按钮/输入/表格外围 8；内容容器 12；模态与大型证据预览 16；头像 9999px。组件只用 `--radius-*`，不新增近似值。36px 头像或 20px 图标所在的可点击目标仍至少为桌面 40px、触控 44px。

## Components

### 现有实现优先

| 职责 | 复用与修改位置 |
|---|---|
| 应用壳、侧栏、面包屑 | `src/ops/OpsApp.vue`、`admin/AdminView.vue`、全局 ops 作用域 |
| 页签与权限 | `admin/adminTabs.ts`；URL 恢复进 `admin/composables/useAdminUrlState.ts` |
| 大业务区块 | `admin/tabs/`；运营处置在 `ops-console/` |
| 共享表格、工具条与按钮 | `src/style.css` 中 `[data-app="ops"]`、`.gl-field`、`.gl-table`、`.gl-btn-primary` |
| 登录、空状态与模态 | `LoginModal.vue`、`shared/EmptyState.vue`、`GlModal.vue`；位于 `src/components/` |

表中 ops 内相对路径以 `src/ops/` 为根。跨入口面板保持同一实现；视觉差异通过 CSS token 映射。`AdminView.vue` 只做 composable 装配和子组件编排，不重新堆回取数与审核逻辑。

### 队列与表格

工具条先展示对象/状态/时间范围，再放查询与刷新；刷新只锁定相关控件。数据显示查询范围与最后更新时间。徽标计数不可把「当前页条数」当成「队列总数」。无总量时显示当前已加载数量，不编造总页数。

表格使用语义化 `table`、`th scope` 与可访问名称。列顺序优先「对象 → 业务信息/证据摘要 → 状态 → 时间 → 操作」；财务列保持单位、右对齐和完整精度。单行至少 48px，双行至少 64px；不设固定高度截断正文。排序按钮可键盘操作并更新 `aria-sort`。

行内主动作直接可见，例如「查看材料」「核对账目」；低频动作可进菜单。整行可点击时，复选框和独立按钮不重复触发行点击。hover 只改变表面，选中行具有额外的勾选/边标。

批量条只在选中记录后出现，注明数量与选择范围（当前页/全部匹配）。执行前显示对象、动作、原因和影响；完成后逐条报告成功/失败，失败项保留可追踪入口，不把部分成功提示成全部完成。

### 审核详情

详情顺序：对象与当前状态 → 申请/任务内容 → 证据附件与时间 → 判定原因 → 操作 → 历史记录。审核者能在决定前看到完整依据。驳回/冻结等操作需要可见原因，字段错误紧邻字段，保留输入。

「通过审核」保持品牌主行动；「驳回申请」使用危险色文本或描边。确认对话框写明对象及后果。提交中阻止重复点击；被他人处理或状态已变化时刷新当前对象并说明冲突，不能继续显示可提交的旧决策。

### 财务、异步与审计

资金模块严格区分余额、预留、待结算、已到账，金额注明币种与单位。订单退款、资金释放与核销使用明确业务动词；不可逆操作在最终提交前展示金额、对象及影响。

`202`、结算轮询和批量后台任务展示「处理中」与追踪入口；仅服务端终态才能显示完成。审计记录按真实时间顺序展示操作者、动作、对象、结果及原因；缺失信息显示「—」，不能补造事件或时间。

### 通用状态与交互

| 场景 | 规范 |
|---|---|
| 首次加载 | 保持表头/区域高度，可用骨架；为区域提供 `aria-busy` |
| 刷新失败 | 保留上一次数据，注明更新失败与重试；不清空为 0 |
| 没有记录 | 说明队列为空；筛选无结果提供清除筛选 |
| 未登录 / 无权限 | 使用独立状态面，不挂载未经授权的业务面板 |
| 账号或后端角色变化 | 重置当前业务面与缓存，重新判断可见页签 |
| hover / active | 使用表面变化与 active token，不缩放、不发光 |
| 键盘焦点 | 2px `--ops-link` 轮廓、2px offset，不能被固定栏遮挡 |
| 禁用 | 保留可读原因；没有权限不意味着悄悄执行动作 |
| 提交中 / 失败 | 保留动作名和宽度；失败保留输入并提供重试 |

菜单和对话框具备键盘操作、Escape 与焦点返回，破坏性操作不设误触默认焦点。模态内背景 inert。链接用真实导航元素，动作使用 button；图标按钮有可访问名称，tooltip 只作补充。

动效继承 `120ms / 220ms` 和根规范 easing；减少动态效果时取消位移动画。自动刷新不夺取焦点或打断正在填写的审核意见。

## Do's and Don'ts

| 应当 | 避免 |
|---|---|
| 直接展示队列、证据与决策 | 给管理页追加宣传区、巨幅标题或 KPI 彩卡 |
| 五个业务组共用 registry、保留页面位置 | 视觉改版重写权限判断或重复侧栏 |
| 13px 辅助字、14px 表格字、40px 桌面控件 | 靠极小字号或 28px 按钮挤压内容 |
| 明确已受理、处理中、已完成 | 把请求成功当成资金到账 |
| 数据、图标、文字共同表达状态 | 只靠红绿、透明文字或悬停提示 |
| 用真实证据与审计条目说明结论 | 伪造总数、占位绩效和完成率 |

### 迁移与验收

现有壳已经具备侧栏、主题映射与权限挂载约束，继续复用。待迁移项包括输入边界 token、状态配色、触控目标与各业务面板的密度；不要因规范修订假定这些已全站完成。

每次选择一条审核/财务流程，先检查真实数据与权限，再调整共享样式及对应子面板。普通文字 ≥4.5:1、必要控件边界 ≥3:1；亮暗各留浏览器截图，并覆盖移动导航、键盘、长文本、空/错/加载/提交、筛选恢复和弹窗主题继承。证据放 `test-artifacts/<任务>/`。

规范校验：`npx @google/design.md lint DESIGN.md` 与 `npx @google/design.md lint src/ops/DESIGN.md`。组件迁移需运行受影响的已有测试和构建；金额或审核逻辑改变时补业务验证。交互示意见 [设计规范预览](../../docs/原型/design-system-v2.html)。
