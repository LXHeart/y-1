---
version: "alpha"
name: "grassland-design"
description: "草场用户端与 AI 创作设计语言：以任务阶段、内容交付和收益为中心，使用品牌紫、清晰中文排版、实色工作区与完整双主题。"
typography:
  display-xl:
    fontFamily: "Space Grotesk, Inter, sans-serif"
    fontSize: "48px"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "0px"
  display-lg:
    fontFamily: "Space Grotesk, Inter, sans-serif"
    fontSize: "32px"
    fontWeight: 600
    lineHeight: 1.25
    letterSpacing: "0px"
  page-title:
    fontFamily: "Space Grotesk, Inter, sans-serif"
    fontSize: "28px"
    fontWeight: 600
    lineHeight: 1.35
    letterSpacing: "0px"
  section-title:
    fontFamily: "Space Grotesk, Inter, sans-serif"
    fontSize: "20px"
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
rounded:
  none: "0px"
  xs: "4px"
  sm: "6px"
  md: "8px"
  lg: "12px"
  xl: "16px"
  pill: "9999px"
colors:
  primary: "#533afd"
  primary-active: "#4434d4"
  on-primary: "#ffffff"
  canvas: "#f6f9fc"
  canvas-dark: "#0d0f18"
  surface: "#ffffff"
  surface-dark: "#141825"
  surface-muted: "#eef2f8"
  surface-muted-dark: "#1a1f30"
  ink: "#0d253d"
  ink-dark: "#f0f2f8"
  secondary: "#273951"
  secondary-dark: "#b4bdd0"
  muted: "#5f6f84"
  muted-dark: "#9aa7be"
  border: "#dde4ee"
  border-dark: "#303a50"
  border-control: "#748399"
  border-control-dark: "#697b96"
  link: "#4434d4"
  link-dark: "#b9b9f9"
  selected: "#efedff"
  selected-dark: "#24223e"
  grass: "#27754a"
  grass-dark: "#82c98f"
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
  task-row:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.lg}"
    padding: "16px 24px"
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
  workflow-current:
    backgroundColor: "{colors.selected}"
    textColor: "{colors.link}"
    typography: "{typography.label}"
    rounded: "{rounded.sm}"
  brand-context:
    backgroundColor: "{colors.canvas}"
    textColor: "{colors.grass}"
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
  task-row-dark:
    backgroundColor: "{colors.surface-dark}"
    textColor: "{colors.ink-dark}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.lg}"
    padding: "16px 24px"
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
  workflow-current-dark:
    backgroundColor: "{colors.selected-dark}"
    textColor: "{colors.link-dark}"
    typography: "{typography.label}"
    rounded: "{rounded.sm}"
  brand-context-dark:
    backgroundColor: "{colors.canvas-dark}"
    textColor: "{colors.grass-dark}"
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
---

# 草场产品设计规范

## Overview

**方向：让合作进展看得见的创作工作台。** 面向商家、推荐官与内容创作者，把任务、素材、交付和收益放在视觉中心。品牌紫负责行动与当前选择，稳定的浅色或深色表面承载工作；清晰的任务阶段线形成草场自己的辨识度。

修订：2026-09-09 · v2。YAML 的 `version: alpha` 是文件格式版本。本文与治理台规范定义目标设计；现有页面仍需按「迁移与验收」逐页实施。示意页使用示例数据，不能作为业务功能已上线的证据。

| 场景 | 规范与页面职责 |
|---|---|
| 用户端 `index.html`：`src/views/`、`src/layouts/`、`src/router/` | 本文。访客了解合作方式；登录后直接处理任务、交付、交易与收益 |
| AI 端 `ai.html`：`src/ai/` 及复用创作视图 | 本文。围绕素材 → 编辑 → 结果工作，保留来源上下文 |
| 治理台 `ops.html`：`src/ops/` 与治理专用面板 | [治理台规范](src/ops/DESIGN.md)。延续品牌与基础尺度，提高队列处理密度 |
| 跨入口共享组件 | 默认遵循本文；治理差异通过 `[data-app="ops"]` 映射，保持单一实现 |

### 设计判断

- **先看下一步。** 首屏提供页面标题、当前身份/对象、待处理事项和一个主要行动；数字只服务于判断。
- **把同一件事放在一起。** 任务标题、门店、真实阶段、交付要求与金额形成一行或一个区块。详情展开后保持来源和列表位置。
- **用结构建立品牌。** 延续现有 `TASK_STAGES` 的「草稿 → 审核 → 招募 → 履约 → 结算」阶段线。阶段名称说明业务顺序，不用装饰编号或伪进度。
- **为中文阅读设计。** 正文 400、重要标签 500、标题 600；正常字距。工作区减少大标题、发光、玻璃叠层和大面积渐变。
- **访客页有独立节奏。** 可以用较大的标题与真实内容样例解释合作；登录后优先显示待办和入口，不重复整块品牌宣传。

## Colors

颜色分为品牌、表面、文字、边界与状态。YAML 中无后缀的是亮色值，`-dark` 是暗色配对；品牌按钮、按钮文字和媒体色在两主题同值。组件 `-dark` 条目用于检查暗色对比度，不代表复制 Vue 组件。

| 角色 / YAML token | 目标 CSS token | 亮色 | 暗色 |
|---|---|---|---|
| `primary` | `--color-accent` | `#533afd` | `#533afd` |
| `primary-active` | `--color-primary-active` | `#4434d4` | `#4434d4` |
| `on-primary` | `--color-on-accent` | `#ffffff` | `#ffffff` |
| `canvas` | `--color-bg` | `#f6f9fc` | `#0d0f18` |
| `surface` | `--color-surface` / `--surface-card` | `#ffffff` | `#141825` |
| `surface-muted` | `--surface-muted` / `--color-surface-hover` | `#eef2f8` | `#1a1f30` |
| `ink` | `--color-text` | `#0d253d` | `#f0f2f8` |
| `secondary` | `--color-text-secondary` | `#273951` | `#b4bdd0` |
| `muted` | `--color-text-muted` | `#5f6f84` | `#9aa7be` |
| `border` | `--color-border` | `#dde4ee` | `#303a50` |
| `border-control` | `--color-border-control` | `#748399` | `#697b96` |
| `link` | `--color-accent-2` | `#4434d4` | `#b9b9f9` |
| `selected` | `--color-surface-highlight` | `#efedff` | `#24223e` |
| `grass` | `--color-grass` | `#27754a` | `#82c98f` |
| `success` / `success-surface` | `--color-success` / `--surface-success` | `#17734b` / `#eaf6ef` | `#34d399` / `#152b25` |
| `warning` / `warning-surface` | `--color-warning` / `--surface-warning` | `#8b5709` / `#fff4de` | `#f59e0b` / `#302713` |
| `danger` / `danger-surface` | `--color-danger` / `--surface-danger` | `#b42332` / `#fceeee` | `#ef6b6b` / `#341e29` |
| `info` / `info-surface` | `--color-info` / `--surface-info` | `#245cb3` / `#edf3ff` | `#60a5fa` / `#19273e` |
| `overlay` | `--color-overlay` | `rgba(13, 37, 61, 0.32)` | `rgba(7, 9, 16, 0.75)` |
| `media-backdrop` / `media-ink` | `--color-media-backdrop` / `--color-media-ink` | `#111111` / `#ffffff` | 同亮色 |

**实现约束：** 表中为目标映射，不保证当前 `src/style.css` 已具备全部变量。新增/改值时在 `:root`（暗）与 `[data-theme="light"]`（亮）成对实现；组件只消费 `var(--token)`。亮暗切换跟随 `useThemeStore`，不得在页面自建主题存储。更改共享语义色时同时检查治理台映射。

紫色用于主行动、链接和当前阶段。苗绿仅用于推荐官身份或合作语境的小标记，不代替成功状态。中性分类不染随机颜色；状态色始终配文字，图标按需辅助。金额本身默认正文色，不能用绿色暗示未到账收益。

选中背景内的辅助文字使用 `secondary`，避免普通 muted 文字在浅紫底上对比度降低；状态徽标仍使用自己的配对底色。

普通文字对比度至少 4.5:1；大字至少 3:1；控件必要边界、图标与焦点至少 3:1。`border` 只画装饰分隔；输入框与无法靠其他方式辨识的控件使用 `border-control`。提示文字不得再叠加 opacity。暗色通过同色相表面加深、文字与状态色提亮实现，不直接反转图片或全页滤镜。

## Typography

只加载 `@fontsource/space-grotesk` 与 `@fontsource/inter`。两者不覆盖全部中文字形，中文自然回退系统中文字体；沿用 `--font-display` / `--font-body` 的系统回退栈，不新增字体依赖或外部 CDN。

| YAML token | 字体 / 字号 / 字重 / 行高 | 用途与目标 CSS |
|---|---|---|
| `display-xl` | Space Grotesk / 48 / 600 / 1.2 | 访客首屏标题；`--text-hero` 桌面上限 |
| `display-lg` | Space Grotesk / 32 / 600 / 1.25 | 访客移动端标题 |
| `page-title` | Space Grotesk / 28 / 600 / 1.35 | 用户工作区标题；`--text-display` |
| `section-title` | Space Grotesk / 20 / 600 / 1.4 | 区块标题；`--text-xl` |
| `card-title` | Space Grotesk / 16 / 600 / 1.5 | 卡片或详情小标题；`--text-lg` |
| `body` | Inter / 16 / 400 / 1.6 | 说明、编辑区、长文；`--text-lg` |
| `body-sm` | Inter / 14 / 400 / 1.55 | 默认工作区、表格；`--text-base` |
| `label` | Inter / 14 / 500 / 1.45 | 导航、字段标签；`--text-base` |
| `caption` | Inter / 13 / 400 / 1.5 | 时间、说明、徽标；`--text-sm` |
| `button` | Inter / 14 / 600 / 1.4 | 控件；`--text-base` |
| `numeric` | Inter / 24 / 600 / 1.3 | 关键金额；`--text-numeric` |

全部字距为 0。业务标签和状态不得用 10–11px 小字；桌面表格正文不低于 14px，说明不低于 13px。页面标题不占据工作区首屏的主要高度。中文长文允许自然换行，只有 ID、短金额和操作组使用 nowrap。

金额、计数和 ID 使用 Inter + `font-variant-numeric: tabular-nums`。复用 `.gl-num` 时目标字体为 `--font-body`，不新增第三款等宽字体。金额必须附币种/单位，区分「预算」「预留」「待结算」「已到账」；未知值显示「—」并说明原因，不能填 0。数字字形特性局部启用，不全局强开 `ss01`。

字重目标：`--weight-body: 400`、`--weight-label: 500`、`--weight-heading: 600`；行高对应 YAML，不在组件添加相近的新字号。移动端输入正文使用 16px，避免聚焦时浏览器自动放大。

## Layout

### 基础尺度

两份规范共用以下命名与数值。旧根规范中 `xs=4、sm=8、md=12` 的命名退出使用，以下命名与现有全局 `--space-xs` 到 `--space-xl` 对齐。

| YAML spacing | CSS | 值 | 常用位置 |
|---|---|---|---|
| `none` / `micro` | `--space-none` / `--space-micro` | 0 / 2px | 清零、细小标记 |
| `xxs` | `--space-xxs` | 4px | 徽标内部、标签与帮助文字 |
| `xs` | `--space-xs` | 8px | 图标文字、紧邻动作 |
| `sm` | `--space-sm` | 12px | 列表纵向内边距、字段组内间距 |
| `md` | `--space-md` | 16px | 字段组、移动端页面边距 |
| `lg` | `--space-lg` | 24px | 桌面页面边距、区块内边距 |
| `xl` | `--space-xl` | 32px | 大区块间距 |
| `xxl` | `--space-xxl` | 48px | 访客内容间距 |
| `section` | `--space-section` | 64px | 仅访客介绍页的大章节 |

全局尚未实现的 token 随迁移补齐，禁止只凭 YAML 名称猜 CSS 数值；尤其治理台 `--text-base` 是 16px，表格应使用治理台 `--text-sm`（14px）。

| 布局/尺寸 token | 值 | 使用边界 |
|---|---|---|
| `--layout-content` | 1200px | 访客介绍和单列内容最大宽度 |
| `--layout-wide` | 1440px | 用户工作台、AI 双栏最大宽度 |
| `--layout-reading` | 720px | 法律条款、创作文稿舒适阅读宽度 |
| `--layout-rail` | 320px | 桌面任务摘要或创作参数栏 |
| `--header-height` | 64px | 应用页头基础高度，内容多时可增高 |
| `--control-height` / `--touch-target` | 40 / 44px | 桌面控件最小高度 / 触控目标最小边长 |
| `--icon-size` / `--avatar-size` | 20 / 36px | 默认图标 / 头像；头像尺寸不代表点击热区 |
| `--focus-width` / `--focus-offset` | 2 / 2px | 可见焦点轮廓与偏移 |
| `--border-width` / `--workflow-track-height` | 1 / 4px | 分隔线 / 阶段线 |

CSS 媒体查询使用固定断点：移动 `<768px`、平板 `768–1023px`、桌面 `≥1024px`、宽屏 `≥1440px`。这些是布局常量，不用不存在的 CSS 自定义变量驱动媒体查询。

### 页面骨架

```text
用户工作台：应用导航 → 身份/组织/门店 → 页面标题与主要行动
            → 业务页签 → 筛选与真实待办 → 任务列表 → 按需展开详情
AI 创作：   应用导航 → 来源与项目 → 素材/参数 | 编辑/结果
            → 运行状态与费用 → 导出/继续编辑
访客首页：  合作方式与一个入口 → 真实任务/作品样例 → 操作说明
```

用户工作台保留商家三页签、推荐官三页签的现有信息架构，导航来源见 `src/views/grassland/workbench-tabs.ts`。设计改版不能新增一组同义导航或破坏已有 URL 恢复。

桌面可用主区 + 摘要栏；平板优先保留主工作区，详情改展开或抽屉。移动端只保留一个主列，页面边距 16px，次要筛选收起，当前筛选保留可见摘要。表格在自己的容器内横向滚动或改成带字段标签的记录卡；不能裁掉金额、状态、主行动，也不能导致整页横向滚动。

## Elevation & Depth

工作区以实色表面、间距和分隔线区分层级。同一业务对象最多一层有框容器，容器内部用列表或分区。默认卡片无阴影，悬浮层才使用阴影。

| 目标 token | 亮色 | 暗色 |
|---|---|---|
| `--shadow-card` | `none` | `none` |
| `--shadow-elevated` | `0 8px 24px rgba(13, 37, 61, 0.12)` | `0 16px 48px rgba(0, 0, 0, 0.36)` |
| `--focus-color` | `{colors.link}` | `{colors.link-dark}` |

保留 `.glass-card` 类名以兼容复用，迁移后的工作区语义是实色内容面板；关闭 backdrop blur。渐变只允许在访客介绍中作为局部、低对比的辅助，不是必须出现的品牌组件。工作台、编辑区、资金页与治理台不使用环境光晕。

## Shapes

两端共用 `rounded`：0（无框）、4（微型标记）、6（徽标/导航项）、8（按钮/输入）、12（内容容器）、16（模态框/大型预览）、9999px（头像/极短分类）。分别对应 `--radius-none/xs/sm/md/lg/xl/pill`。默认按钮采用 8px 圆角；胶囊保留给简短分类，不能作为所有动作的外形。

媒体容器按素材本身使用 16:9、4:3、1:1 或 9:16。内容样例可以承载产品个性；不改变用户上传的原图色彩以迎合主题。媒体加载失败显示原尺寸占位及重试入口，避免列表跳动。

## Components

### 复用入口

| 需求 | 现有实现 / 样式入口 | v2 处理 |
|---|---|---|
| 工作区、表格、提示、按钮 | `src/style.css`：`.gl-field`、`.gl-zone`、`.gl-table`、`.gl-btn-primary` | 扩展全局层，统一高度、圆角与状态 |
| 状态 | `.badge` 与 `.badge-*` | 使用配对语义色、明确文案；13px，默认 6px 圆角 |
| 空状态 | `src/components/shared/EmptyState.vue` | 给原因与下一步；不再各页重造 |
| 模态与登录 | `GlModal.vue`、`LoginModal.vue` | 复用焦点、关闭与主题行为 |
| 商家任务 | `MerchantTasksPanel.vue`、`TaskApplicantsPanel.vue` | 任务与报名保持相邻，阶段与下一步可读 |
| 任务详情与履约动作 | `TaskDetailModal.vue`、`EngagementNextAction.vue` | 复用同一份任务状态与可用动作 |

表中业务组件位于 `src/views/grassland/components/`，共享组件位于 `src/components/`。YAML 的 `task-row`、`workflow-current` 等是视觉契约，不要求新增同名组件。

### 按钮与字段

每个当前工作区提供一个最重要的实心紫色行动；其余使用描边或文本样式。列表行保留一个直接操作，次要动作按需展开。危险动作与主行动分开，明确写出对象和后果，不能仅靠红色或「确定」。

字段始终有可见 label，说明放在输入附近；错误关联 `aria-describedby`，保留已输入内容并定位首个错误。成功反馈使用与按钮相同的动词，例如「保存修改」→「修改已保存」。没有权限时说明缺少的权限和可行路径。

| 状态 | 行为与视觉 |
|---|---|
| 默认 / hover | 实色底；hover 使用已有表面或 active token，不放大、不发光、不改布局 |
| focus-visible | 2px `--focus-color` 轮廓，2px offset；键盘可见，不用透明阴影替代 |
| active / selected | 按下用 `primary-active`；导航用选中色 + 字重 + `aria-current` 或 `aria-selected` |
| disabled | 使用中性表面与文字，保留可读原因；由真实业务条件禁用 |
| loading / submitting | 保留按钮宽度与动作名，阻止重复提交；只锁定有关动作 |
| success / error | 服务端确认后展示结果；失败保留输入和重试入口 |

### 任务、状态与金额

任务行的信息顺序为「标题/门店 → 明文阶段与截止时间 → 交付与真实计数 → 金额及口径 → 下一步」。阶段线是已有生命周期的摘要，不能根据视觉段数推导结算结果，也不显示虚构百分比。招募关闭、履约完成、结算完成必须按真实状态区分；取消或驳回明确写出原因，暂停阶段线。

| 状态用途 | 样式 | 示例文案 |
|---|---|---|
| 草稿、未开始、普通分类 | neutral | 草稿、图文、门店任务 |
| 已接受请求、运行或等待中 | info | 审核中、生成中、结算处理中 |
| 用户需要介入、临近截止 | warning | 待补充材料、待验收 |
| 服务端确认完成 | success | 已到账、已通过、已完成 |
| 操作失败、审核驳回 | danger | 生成失败、已驳回·待修改 |

### 创作与交易

创作面固定展示来源（任务/门店/自由创作）、已有素材、输出目标。任务创作保留锁定条件；自由创作不显示组织或门店选择器。输入、运行和结果处于同一工作流，主要空间留给内容本身。已有编辑能力优先，不能为展示模型能力而堆入口卡。

异步任务区分排队、进行、完成和失败；有真实进度才展示百分比。显示可用的取消、重试或继续编辑入口；切页返回能够恢复任务。生成前说明费用/积分及扣费时机，失败是否扣费必须依据业务规则。交易页明确币种、价格、订单状态和核销条件。

### 空、错、慢与浮层

- 首次空状态解释需要什么资料，并给一个具体行动；筛选无结果保留筛选并提供清除入口。
- 加载失败与空列表分开。刷新失败保留上一次数据，注明「更新失败」及时间，不能静默替换为 0。
- `202`、资金预留、结算轮询等处理中状态保留追踪入口，不能用成功徽标提前宣布完成。
- 对话框有标题、焦点限制、Escape 关闭和焦点返回；未保存内容关闭前说明会丢失哪些修改。抽屉/Teleport 到 body 后仍继承正确应用与主题 token。
- 反馈靠近操作；全局 toast 使用适当的 live region。动画采用 `--duration-fast: 120ms` / `--duration-normal: 220ms` 与 `--ease-out: cubic-bezier(0.16, 1, 0.3, 1)`；尊重减少动态效果设置。

## Do's and Don'ts

| 应当 | 避免 |
|---|---|
| 用真实阶段、交付物、金额口径建立辨识度 | 给每个模块叠渐变、玻璃、彩色统计卡 |
| 让中文标题可读、UI 字号稳定 | 300 字重、负字距、10px 状态小字 |
| 先复用全局样式和现有业务组件 | 新建另一套按钮、模态、状态配色 |
| 布局随内容与权限变化 | 固定宣传式首屏、伪造指标和案例背书 |
| 金额显示单位并按列对齐 | 只靠颜色区分待结算和已到账 |
| 触控目标至少 44×44px；紧邻操作保证间距 | 用小图标或圆形轮廓声称热区足够 |

### 迁移与验收

本次规范与 [交互示意页](docs/原型/design-system-v2.html) 定义新方向。页面实施顺序：共享 token/控件 → 工作台任务列表与详情 → AI 输入/运行/结果 → 治理队列 → 访客首页。每次只迁移一个可验收流程，保留现有路由与业务 composable 分层。

| 当前差异 | 迁移动作 |
|---|---|
| 暗色 accent 为 `#665efd`，部分说明对比度不足 | 分离按钮与链接语义，落地配对文字/状态色 |
| `.glass-card` 有 blur，`.gl-btn-primary` 为渐变胶囊 | 原类名逐步落实实色、无 blur、8px 按钮 |
| 用户端 `--text-sm`、徽标和视图标题与 v2 不一致 | 局部验证字号/换行后再推广共享值 |
| `--font-mono` 使用系统等宽栈 | `.gl-num` 改用 Inter + tabular-nums，并核对金额对齐 |
| 新映射表中部分变量尚不存在 | 先补亮暗定义，再让组件使用，不凭变量名猜值 |

旧 `button-primary-pill` → `button-primary`；`card-dashboard-mockup` → 真实内容区；`pill-tag-soft` → 按业务选择中性分类或状态；`nav-bar-on-mesh` → 应用导航。移除旧宣传范式不代表删除对应功能。

验收需覆盖亮/暗各一张浏览器截图，桌面与移动端首屏、长标题/长金额、键盘焦点、加载/空/错/提交中、减少动态效果。普通文字 ≥4.5:1、必要控件边界 ≥3:1；截图与检查报告放 `test-artifacts/<任务>/`。规范文件运行两份 design.md lint；实际组件修改再运行受影响的现有测试与构建。
