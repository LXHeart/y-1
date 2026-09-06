# 开发规格：存量 UI token 违规清偿（画布内容常量 + 媒体容器色 + 商家核销卡）

> 模板版本：2.4.0 ｜ 最近修订日期：2026-09-06
> 任务编号：89 ｜ 任务书版本：v1.0 ｜ 创建/更新日期：2026-09-06
> 规划模型/负责人：ZCode（规划与验收）／用户（最终验收与发布授权） ｜ 目标仓库：y-1（/Users/LXH/claude/y-1） ｜ 当前分支：main（仅记录，不自动创建）
> 代码基线：c7ed43e5f805dcb68951f132d4334b349bb3bc9d ｜ 未提交改动：仅未跟踪 `.pi/`、`docs/测试/workbench-business-audit-2026-09-06.md`（与本任务无文件重叠） ｜ 本次事实核验日期：2026-09-06
> 文档状态：READY_FOR_IMPLEMENTATION（发布检查见附 B） ｜ 目标执行者：能力较弱的编码模型 ｜ 任务卡总数：3
> 执行顺序：C-01 → C-02 → C-03（三卡无共享写入文件、理论上可并行；AUTO_CHAIN 默认串行。C-03 是唯一写入全局 `src/style.css`/`DESIGN.md` 的卡，若并行执行必须独占这两个文件）
> 执行模式：AUTO_CHAIN

---

## 0. 执行协议（受更高优先级指令与仓库硬约束约束）

按模板 §0 原文执行，本任务无豁免。补充三点本任务专属强调：

1. 本任务的灵魂是 **§3 决策 D-01 的三类着色域分类法**（UI 主题色 / 媒体容器色 / 导出内容色）。执行模型 MUST NOT 把导出内容色（封面画布、图像背景默认值）硬套主题 token——那会让导出的图片随观者明暗主题漂移，是回归不是修复。
2. MUST NOT 发明新的间距/字号 token（如 `--space-2xs`、`--text-2xs`）来「消灭」无 token 匹配的字面量；D-04 已拍板这些值保留。
3. 所有颜色/字号常量值 MUST 与现状逐字相等（D-06 批准的两项已知情微差除外）；本任务是合规收口，不是重新设计。

### 0.3 完成定义（DoD）

按模板 §0.3 原文执行。本任务全部卡均为 UI 卡，§8.8 双主题截图义务适用全部三卡（C-01/C-02 的画布内容刻意主题无关，截图目的是回归证明「页面没被改坏」，见 §8.8 表内说明）。

---

## 1. 目标与范围

### 1.1 一句话目标

清偿三处存量 UI token 违规：视频封面画布的 `system-ui` 字体改为从 `--font-body` token 解析、绘制色收敛为命名内容常量；图像工作台背景默认色命名常量化；商家核销卡的取景器裸 `#111`/`rgba()`/`white` 收敛为新注册的媒体语义 token（暗亮同值），并把可精确匹配的尺寸值置换为既有 token——全部行为零变更（D-06 两项已知情微差除外）。

### 1.2 背景与价值

`AGENTS.md` 硬性规则 2 要求颜色、字体、字号、圆角、间距只用 DESIGN.md token。全仓两轮 token 化清偿（语义色 56 文件、圆角 303 处）之后，仍残留三类「体系外」写法：画在导出图片上的 canvas 字体/颜色（`VideoStudioView.vue`）、图像编辑的内容默认色（`ImageStudioView.vue`）、扫码取景器的媒体底色（`MerchantCommerceCard.vue`，该组件还被 `RecommenderShareCard.vue:121` 注释当作 token 先例引用，存量违规正在污染先例）。这些不立即造成业务错误，但会让明暗主题与设计体系持续漂移。本次按「三类着色域」分类法逐处收口：该接 token 的接 token，刻意主题无关的给出命名常量与注释，把「为什么不用 token」变成显式决策而不是散落的魔法值。

### 1.3 范围内（明确交付）

| 需求编号 | 必须交付的可观察行为 | 负责卡号 | 对应验收编号 |
|---|---|---|---|
| REQ-001 | `VideoStudioView.vue` 画布绘制代码中 `system-ui` 清零：字体家族运行时从 CSS 变量 `--font-body` 解析（空值回退固定栈，回退栈与 token 同构）；全部绘制色（`#fff`、5 处 `rgba` 渐变/描边/衬底）与字号收敛为模块顶部命名常量，值与现状逐字相等；8 套排版注册表与绘制函数迁入纯模块 `cover-text-layout.ts` 并可单测 | C-01 | AC-001、AC-002、AC-003 |
| REQ-002 | `ImageStudioView.vue` 背景三默认色（`#ffffff`、`#667eea`、`#764ba2`）收敛为命名常量并带「内容默认值、刻意不接主题 token」注释；组件其余行为零变更 | C-02 | AC-004 |
| REQ-003 | `MerchantCommerceCard.vue` scoped 样式裸 hex/`rgba()`/`white` 清零（取景器三色改用新语义 token）；单一值恰好等于既有 token 的间距/字号置换为 token（`--space-xs` 8px、`--space-sm` 12px、`--space-md` 16px、`--text-xs` 12px 字号）；无 token 匹配的复合简写与奇数值保持字面量 | C-03 | AC-005、AC-006 |
| REQ-004 | `src/style.css` 的 `:root`（暗）与 `[data-theme="light"]`（亮）成对注册 `--color-media-backdrop`/`--color-media-scrim`/`--color-media-ink`（暗亮同值），`DESIGN.md` colors 节同步登记三键 | C-03 | AC-007、AC-008 |

### 1.4 范围外（明确不做，遇到也不处理）

- 不做间距/字号 token 体系的全量盘点与扩张（`14px`、`11px`、`10px`、`6px`、复合简写等无 token 值一律保留字面量；该欠账属「经营分类线」独立任务，见 §1.5）。
- 不改画布绘制几何与排版参数（`barH 170`、inset `44/40`、字号 `60/34px`、`lineWidth 6/4` 等全部保持现值）。
- 不改 `ImageStudioView.vue` 的数值类默认值（`blurRadius: 10`）与任何滤镜/导出逻辑。
- 不改三组件外的任何文件（含同样有存量欠账的其他组件）。
- 不改任何业务逻辑、HTTP 契约、数据模型；不新增依赖；不动 `ai.html` 入口本身（其复用的创作视图改动自动生效）。
- 不引入 `document.fonts.ready` 等新的字体加载协调机制。

### 1.5 不许顺手修

<!-- 执行中发现下列已知问题：只记入完成报告「未解决问题」，MUST NOT 动手 -->

- `src/components/OrganizationBrandCard.vue`：间距/字号未 token 盘点（历史遗留，留给经营分类线）。
- `src/components/RecommenderShareCard.vue:121`：注释引用「MerchantCommerceCard token 先例」——本任务清偿后该注释语义自动成立，MUST NOT 改注释文字。
- `src/views/article/components/ArticleImageSlots.vue:131`：lint 既有 `no-explicit-any` 警告（非阻塞基线，见 §2.7）。
- `src/views/ai-center/components/VideoStudioView.vue` 的 AI 封面生成流、视频管线逻辑。

### 1.6 用户、入口与已知限制

- 用户/调用方：用户端登录用户（商家身份用工作台核销卡；任意身份用 `/creation` 创作中心的视频/图像工作台）；AI 创作中心（`ai.html`）用户经复用视图获得同一改动。
- 使用前置条件：本地冒烟需本地 compose 栈与 `e2e-seed` 合成账号（见 §9.3）；单元/静态验收无前置。
- 已知且允许保留的限制：① 封面画布上的拉丁字符与数字字形由 `system-ui` 变为 Inter（中文字形本就走回退栈不变）——字体硬性规则的必然结果，D-06 已批准；② commerce 卡内 12px 文本改用 `--text-xs`（0.75rem）后，用户自定义浏览器根字号时这些文本随之缩放（与其余 token 化视图行为一致）；③ 画布字体在 Inter 尚未完成加载的极早期绘制会暂时走回退栈，下一次交互重绘即恢复——不引入等待机制（§1.4）。
- 验收例外：无。

---

## 2. 仓库上下文

<!-- 强模型写作时已逐项核实；本节事实错误会整本传导到每张卡 -->

### 2.1 目标端（勾选）

| 勾 | 端 | HTML 入口 | 相关目录 |
|---|---|---|---|
| [x] | 用户端 | `index.html` | `src/views/ai-center/`（`/creation` 创作中心）、`src/views/grassland/GrasslandWorkbench.vue`（工作台挂载点） |
| [ ] | 治理台 | `ops.html` | 不涉及（三组件均无治理台引用） |
| [x] | AI 创作中心 | `ai.html` | 复用 `src/views/ai-center/` 同一视图，本任务无 `src/ai/` 独立改动 |
| [x] | 共享组件 | 双端引用 | `src/components/MerchantCommerceCard.vue`（当前仅用户端工作台引用） |
| [ ] | 后端 | — | 不涉及 |
| [ ] | 构建/脚本 | — | 不涉及（截图脚本为 test-artifacts 生成物，不入库，见 §9.1） |
| [ ] | 文档/契约 | `DESIGN.md` 本身是设计规范而非业务文档；`docs/status.yaml` 不涉及（无功能状态变化） |

### 2.2 设计规范路由（改任何 UI 前必读对应规范）

按 `AGENTS.md` 与模板 §2.2 原文路由：`src/views/ai-center/**` 按根 `DESIGN.md`（grassland-design）；`src/components/**` 共享组件默认按根 `DESIGN.md`。本任务不触治理台。品牌主色 `#533afd`；字体仅 Space Grotesk（display）+ Inter（正文/UI），经 @fontsource self-host。

### 2.3 入口位置

- 页面入口：`src/views/ai-center/AiCreationCenter.vue`（用户端路由 `/creation`，`mode="platform"`，`src/router/index.ts:32-34`）；`ai.html` 经 `src/ai/` 复用同一创作中心。
- 组件入口：`src/views/ai-center/components/VideoStudioView.vue`（视频工作台，封面画布）、`src/views/ai-center/components/ImageStudioView.vue`（图像工作台，抠图背景）、`src/components/MerchantCommerceCard.vue`（`GrasslandWorkbench.vue:18` 异步引入、`:1257` 挂载，「到店套餐与核销」卡）。
- 数据/测试入口：`src/components/MerchantCommerceCard.test.ts`（既有，含完整扫码流 mock）；新建 `src/views/ai-center/components/cover-text-layout.test.ts`。

### 2.4 相关现有文件

| 文件 | 相关符号 | 当前职责 | 本次关系 |
|---|---|---|---|
| `src/views/ai-center/components/VideoStudioView.vue` | `TITLE_FONT`、`SUBTITLE_FONT`、`strokeTitle`、`strokeSubtitle`、`barLayout`、`gradientLayout`、`COVER_TEXT_LAYOUTS`、`CoverTextLayout(Id)`、`renderCover`（:515） | 视频封面画布叠加文字（8 套排版） | 必须修改（删除 :535-620 绘制块，改为 import 纯模块） |
| `src/views/ai-center/components/cover-text-layout.ts` | — | 不存在 | 新建（常量 + 字体解析 + 8 套排版注册表） |
| `src/views/ai-center/components/cover-text-layout.test.ts` | — | 不存在 | 新建（TC-C01-001..004） |
| `src/views/ai-center/components/ImageStudioView.vue` | `bgConfig`（:248-251） | 抠图背景配置（纯色/渐变/模糊/图） | 必须修改（默认值改命名常量） |
| `src/components/MerchantCommerceCard.vue` | `<style scoped>` :438-472 | 卡片全部样式 | 必须修改（scanner 三色 + 精确匹配尺寸置换） |
| `src/style.css` | `:root`（:14 起）、`[data-theme="light"]`（:110 起） | 全局 token 双主题定义 | 必须修改（新增 media 三 token，暗亮同值） |
| `DESIGN.md` | `colors:` 节（:6-29） | 用户端设计规范 | 必须修改（登记 media 三键） |
| `src/types/grassland/ai-studio.ts` | `BackgroundConfig`、`BackgroundMode` | 图像工作台类型 | 只读参考（类型不动） |
| `src/utils/subtitle-timeline.ts` | 模块导出风格 | 组件旁纯模块先例 | 只读参考（新模块风格照此） |
| `src/components/MerchantCommerceCard.test.ts` | `describe('MerchantCommerceCard')` | 既有回归（含扫码流） | 只读参考（必须保持全绿） |

### 2.5 当前行为

1. **视频封面画布**（`VideoStudioView.vue`）：`renderCover()`（:515-533）把底图按比例裁切绘制到 1080px 宽画布后，按 `coverLayout` 从 `COVER_TEXT_LAYOUTS`（8 套：left-bold/center-block/top-bar/bottom-bar/center-gradient/bottom-gradient/top-stroke/center-stroke）取 `draw` 叠加标题/副标题。字体常量 `TITLE_FONT = 'bold 60px system-ui, sans-serif'`（:547）、`SUBTITLE_FONT = '34px system-ui, sans-serif'`（:548）；绘制色为散落字面量：文字 `#fff`（:553、:562、:599、:600）、描边 `rgba(0,0,0,0.7)`/`rgba(0,0,0,0.6)`（:552、:561）、衬条 `rgba(20,16,38,0.72)`（:571）、渐变遮罩 stop `rgba(0,0,0,0)`→`rgba(0,0,0,0.62)`（:580-581）、色块 `rgba(0,0,0,0.55)`（:597）、中部渐变 stop `0`/`0.55`/`0`（:606-609）。模板 `:207-208` 用 `COVER_TEXT_LAYOUTS` 渲染排版下拉。画布内容**不随明暗主题变化**（现状即如此——它画在导出图片上）。
2. **图像工作台背景**（`ImageStudioView.vue`）：`bgConfig`（:248-251）初值 `mode:'color', color:'#ffffff', gradientFrom:'#667eea', gradientTo:'#764ba2'`，由 `<input type="color">`（:94-98）可编辑，导出时直接作 `ctx.fillStyle`/渐变 stop（:437-443）。这些值是**用户内容默认值**，不参与主题。
3. **商家核销卡**（`MerchantCommerceCard.vue`）：scoped 样式已大量使用 `var(--color-*)`/`var(--radius-*)`，唯 `.scanner-box` 残留裸色：`background: #111`、字幕 `color: white; background: rgba(0, 0, 0, .65)`（:469）。`.scanner-box` 仅在 `v-if="scanning"` 时渲染（:109-112，含 `aria-label` 的 video 与提示文案）。间距/字号多为字面量（10/11/12/14/16px、6/8px 等）。
4. 全局 token：`--font-body = 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', sans-serif`（`style.css:97`，暗亮同值）；间距 `--space-xs/sm/md/lg/xl = 8/12/16/24/32px`；字号 `--text-xs/sm/base/lg = 12/13/14/16px`（rem 表达）。`src/` 中无任何 `getComputedStyle` 读 CSS 变量的先例。

### 2.6 当前问题

- 现状：三组件的着色/字体分属「UI 主题」「媒体容器」「导出内容」三个语义域，但现状里只有第一域有 token 体系；第二、三域的值以魔法字面量散落。
- 问题：`system-ui` 直接违反 AGENTS.md 硬性规则 5（字体仅两款）；裸 hex/rgba 违反硬性规则 2；且没有分类法时，「合规化」容易被误做成硬套主题 token，导致导出图/取景器随主题漂移的回归。`MerchantCommerceCard` 还被 `RecommenderShareCard.vue:121` 注释当作 token 先例引用，名实不符。
- 影响：设计体系漂移风险（明暗主题、后续新视图参照失真）；扫码取景器在亮色主题下因 `#111` 是硬编码而「碰巧」正确，一旦有人按字面合规改成 `--color-bg` 就会翻亮。
- 根因：token 体系晚于这三处代码落地，且画布/媒体两个着色域此前没有明确的豁免语义与命名收口规范。

### 2.7 基线与来源核验

| 项目 | 已核实内容/证据 |
|---|---|
| 指令与设计 | `AGENTS.md`（UI 规则全节）、根 `DESIGN.md`（colors :6-29、typography 尺度）、`src/style.css`（:root :14-108、light :110-159、`[data-app="ops"]` 作用域）已通读 |
| 版本与构建 | `package.json` scripts：`test`/`typecheck`/`lint`/`build`/`test:coverage` 存在；node v22.22.3、npm 10.9.8（本机实测） |
| 架构与业务决策 | 无 ADR 涉及；任务书 #54（封面 8 套排版注册表）、#43（图像工作台）、#75（核销卡）为历史背景 |
| 工作区 | `git rev-parse HEAD` = `c7ed43e5f805dcb68951f132d4334b349bb3bc9d`；`git status --short` 仅未跟踪 `.pi/`、`docs/测试/workbench-business-audit-2026-09-06.md`，与本任务白名单零重叠 |
| 测试基线 | 2026-09-06 作者实测：`npm run typecheck` 退出码 0；`npm run lint` 退出码 0（2 个既有 `no-explicit-any` 警告：`ArticleImageSlots.vue:131` 等，warn 档不阻塞）；`npx --yes @google/design.md lint DESIGN.md` 可用，errors 0 / warnings 11；在 `/tmp` 副本试写 media 三键（含 rgba 值）后 lint errors 仍为 0、warnings 14（+3 为与既有「定义未引用」同类警告）。`npm run test` 全量作者 NOT_RUN（无已知失败；执行者开工时按卡内定向命令先行） |
| 复用检查 | `--font-body`/`--space-*`/`--text-*` 均已存在直接复用；媒体色无既有 token（`--surface-muted` 亮色覆写为 `#eef2f8`、`--color-overlay` 亮色仅 0.32 透明度，均不可复用，见 D-03）；`getComputedStyle` 读变量无先例，本任务在新纯模块内新建局部 helper |

### 2.8 事实、决策与示例的区分

按模板 §2.8 原文执行。本任务关键标注：

- `FACT`：两个 Studio 视图的 `<style scoped>` 块 hex/rgba 计数均为 0（违规全在 TS 画布代码）；`ImageStudioView.vue` 全文件 hex/rgba 仅 :249 一行；`VideoStudioView.vue` 画布色/字体字面量完整清单见 §2.5-1；`--font-body` 暗亮同值；`scanner-box` 仅 `v-if="scanning"` 渲染。
- `DECISION`：D-01～D-06（§3）。
- `EXAMPLE`：无（本任务不含合成示例数据）。

### 2.9 影响面与兼容面

| 影响面 | 是否受影响 | 具体对象 | 兼容要求 | 验证方式 |
|---|---:|---|---|---|
| 页面/路由 | 是 | `/creation`（视频/图像 tab）、商家工作台核销卡 | 交互与布局零变化；仅 D-06 两项已知情微差 | 双主题截图（V-008）+ 既有测试 |
| 公共 HTTP 契约 | 否 | 无任何接口改动 | — | N/A |
| 数据库/缓存 | 否 | — | — | N/A |
| 权限/身份 | 否 | — | — | N/A |
| 计费/积分/资金 | 否 | — | — | N/A |
| 部署/配置 | 否 | 无新依赖、无入口/CSP 变化（不触三入口内联脚本） | 随下次前端发版自然生效 | `npm run build`（V-009） |
| 文档/状态 | 是 | `DESIGN.md` colors 三键 | 与 `style.css` token 值逐字一致 | design.md lint（V-007） |

---

## 3. 技术决策（已定案，执行期不得更改）

| 决策项 | 结论 |
|---|---|
| 语言/框架/版本 | Vue 3 SFC + TypeScript（现状体系），无框架变化 |
| 新增依赖 | 无（MUST NOT 新增任何包；design.md lint 工具按 AGENTS.md 校验节用 npx 现拉执行） |
| 文件布局 | 新建 `src/views/ai-center/components/cover-text-layout.ts` 与同名 `.test.ts`；其余为存量文件修改 |
| 错误处理策略 | 唯一新运行时分支：`--font-body` 读值为空白串时回退与 token 同构的字体栈（D-02），不抛错、不打日志 |
| 命名约定 | 画布内容常量 `COVER_*` 前缀大写下划线；媒体 token `--color-media-*`；纯模块导出驼峰（照 `subtitle-timeline.ts`） |
| 风格参照 | 纯模块导出/注释风格照 `src/utils/subtitle-timeline.ts`；组件内常量注释风格照仓库既有中文行注 |
| 配置变更 | 无 |
| 兼容/发布 | N/A：纯前端样式/常量收口，无迁移、无发布顺序约束 |

### 决策记录

#### D-01：三类着色域分类法

- 决策：① **UI 主题色**=页面 chrome，用既有 `--color-*` token，随明暗切换；② **媒体容器色**=扫码取景器等「任意亮度的视频/相机区域」，用新语义 token `--color-media-*`，**暗亮同值**（视频区在亮色 UI 下也保持深色，业界通例）；③ **导出内容色**=画在导出图片上的封面文字/衬底与图像编辑背景默认值，收敛为**组件内命名常量，刻意不接任何主题 token**（导出结果不能随观者主题漂移）。本任务三处违规按此分类分别落 ①外、②、③。
- 原因：一刀切硬套主题 token 会造成「导出图/取景器随主题变化」的视觉回归；一刀切保留字面量则违反 AGENTS.md 硬性规则 2/5。
- 放弃方案：全部硬套现有主题 token（回归）；全部保留字面量仅加注释（字体规则未清偿、commerce 卡裸色未清零）。
- 允许执行模型修改：否

#### D-02：画布字体家族从 token 运行时解析

- 决策：新增局部函数 `canvasFontStack()`：`getComputedStyle(document.documentElement).getPropertyValue('--font-body').trim()`，非空即用，空白串回退常量 `CANVAS_FONT_FALLBACK = "'Inter', -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Hiragino Sans GB', sans-serif"`（与 token 值同构）。标题/副标题字体串由 `bold ${COVER_TITLE_SIZE_PX}px ${canvasFontStack()}` / `${COVER_SUBTITLE_SIZE_PX}px ${canvasFontStack()}` 现算。字号 60/34px 是**图像排版常量**，MUST NOT 映射 `--text-*`（rem 尺度语义不同）。
- 原因：字体家族单一来源在 `style.css`（改 token 一处生效）；回退保证测试环境/极端时序下 `ctx.font` 永远拿到合法串（canvas 对非法 font 赋值会静默保持旧值）。
- 放弃方案：在 TS 里复写一份字体栈（双源漂移）；`document.fonts.ready` 等待机制（§1.4 排除）。
- 允许执行模型修改：否

#### D-03：媒体三 token 暗亮同值注册

- 决策：`--color-media-backdrop: #111111`、`--color-media-scrim: rgba(0, 0, 0, 0.65)`、`--color-media-ink: #ffffff` 三 token 在 `style.css` 的 `:root` 与 `[data-theme="light']` **成对注册且值相同**，并在 `DESIGN.md` colors 节 `shadow-blue` 之后登记 `media-backdrop`/`media-scrim`/`media-ink` 三键（值逐字同 CSS）。两处定义各带一行中文注释说明「媒体区暗亮同值」。
- 原因：AGENTS.md 要求新 token 双主题成对定义；媒体区刻意同值是 D-01-② 的落实。作者已在 `/tmp` 副本试写三键（含 rgba 值）跑 design.md lint，errors 0。
- 放弃方案：复用 `--surface-muted`（亮色覆写 `#eef2f8`，取景器会翻亮）；复用 `--color-overlay`（亮色 0.32 透明度不足以保证白字对比）；把 `#111` 改成 DESIGN.md 既有 `ink #0d253d`（改变现值，违反值零变更原则）。
- 允许执行模型修改：否

#### D-04：尺寸只做「单一值精确匹配」置换

- 决策：仅当属性值是**单一值**且与既有 token 数值相等时置换：`8px → var(--space-xs)`、`12px → var(--space-sm)`、`16px → var(--space-md)`（间距/内边距/外边距语义）与 `font-size: 12px → var(--text-xs)`。复合简写（`6px 8px`、`7px 9px`、`5px 8px`、`2px 8px`）与无 token 值（`14px`、`11px`、`10px`、`6px`、`3px`、`2px`、`36px`、`150px`、`260px`、`320px`）一律保留字面量。逐条清单见 C-03 步骤 4。
- 原因：零视觉变更的机械置换才有客观判定标准；发明新 token 属规范扩张，需独立的间距/字号盘点任务（历史欠账）。
- 放弃方案：新发明 `--space-2xs`/`--text-2xs` 等（越权）；复合简写里混搭 token 与字面量（可读性差且无判定收益）。
- 允许执行模型修改：否

#### D-05：画布绘制代码抽纯模块 `cover-text-layout.ts`

- 决策：把 `CoverTextLayout`/`CoverTextLayoutId` 类型、`COVER_TEXT_LAYOUTS` 注册表、`strokeTitle`/`strokeSubtitle`/`barLayout`/`gradientLayout`、全部 `COVER_*` 常量与 `canvasFontStack` 迁入 `src/views/ai-center/components/cover-text-layout.ts`；组件 `import { COVER_TEXT_LAYOUTS } from './cover-text-layout'`（类型 `import type`）。模块导出：`CoverTextLayoutId`、`CoverTextLayout`、`COVER_TEXT_LAYOUTS`、`canvasFontStack`（后者供测试）。
- 原因：字体解析与绘制常量必须有单测；挂载 `VideoStudioView` 重组件不可行（AI 管线依赖重、`Image.onload` 在 happy-dom 不触发）；`defineExpose` 会污染组件公开 API。
- 放弃方案：组件内原地改 + 挂载级测试（不可行）；`defineExpose` 暴露内部（污染 API）。
- 允许执行模型修改：否

#### D-06：两项已知情视觉微差

- 决策：接受①封面画布拉丁/数字字形 `system-ui`→Inter（中文本就走回退栈不变）；②commerce 卡 12px 文本改 `--text-xs` 后随用户根字号缩放。
- 原因：①是 AGENTS.md 硬性规则 5 的直接后果；②与全站 token 化视图行为一致（可访问性正向）。
- 放弃方案：为保拉丁字形不变保留 `system-ui`（违反硬性规则，不可选）。
- 允许执行模型修改：否

---

## 4. 目标行为

行为型任务：本任务为「合规收口 + 行为零变更」型。除 D-06 两项微差外，输入、输出、异常、交互全部保持现状。

### 4.1 用户流程

不适用，原因：无新增/修改用户流程。既有流程（创作中心生成封面、图像工作台换背景、商家扫码核销）交互路径与结果不变。

### 4.2 行为变化表

| 场景 | 当前行为 | 目标行为 |
|---|---|---|
| 正常情况（三组件常规使用） | 见 §2.5 | 完全一致（D-06 微差除外） |
| 明暗主题切换 | 页面 chrome 随主题；画布/取景器不随主题 | 同左，且「不随主题」由常量注释 + 单测 + 媒体 token 同值对显式锁定 |
| 用户自定义浏览器根字号 | commerce 卡 12px 文本固定 12px | 这些文本随根字号缩放（`--text-xs`，与全站一致） |
| 封面含拉丁字符/数字 | 用 system-ui 字形渲染 | 用 Inter 字形渲染（中文不变） |
| 测试环境/极端时序（`--font-body` 读空） | 不适用（现状硬编码） | 画布字体回退 `CANVAS_FONT_FALLBACK`，绘制不失败 |
| 请求失败/未登录/权限 | 不适用，原因：无请求、登录、权限行为变化 | 同左 |

### 4.3 状态定义

N/A：本任务不新增任何组件状态；`coverLayout`、`bgConfig`、`scanning` 等既有状态语义不变。

### 4.4 状态迁移规则

N/A：同 §4.3 理由；无异步请求、轮询或订阅变化。

---

## 5. 业务规则

本任务无业务规则变化；写死以下实现性规则防漂移。

### 5.1 内容常量值表（C-01/C-02 落地后必须逐字相等）

| 常量 | 值 | 单位/格式 | 现值出处 |
|---|---|---|---|
| `COVER_TITLE_SIZE_PX` | `60` | px（字号） | :547 |
| `COVER_SUBTITLE_SIZE_PX` | `34` | px（字号） | :548 |
| `COVER_TEXT_INK` | `'#ffffff'` | canvas 颜色串 | :553/:562/:599/:600 的 `#fff` 等值长写法 |
| `COVER_STROKE_TITLE` | `'rgba(0,0,0,0.7)'` | 同上 | :552 |
| `COVER_STROKE_SUBTITLE` | `'rgba(0,0,0,0.6)'` | 同上 | :561 |
| `COVER_SCRIM_BAR` | `'rgba(20,16,38,0.72)'` | 同上 | :571 |
| `COVER_SCRIM_BLOCK` | `'rgba(0,0,0,0.55)'` | 同上 | :597 |
| `COVER_SCRIM_GRADIENT` | `'rgba(0,0,0,0.62)'` | 同上 | :581 |
| `COVER_SCRIM_GRADIENT_CENTER` | `'rgba(0,0,0,0.55)'` | 同上 | :608 |
| `COVER_SCRIM_GRADIENT_EDGE` | `'rgba(0,0,0,0)'` | 同上 | :580/:607/:609 |
| `CANVAS_FONT_FALLBACK` | `"'Inter', -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Hiragino Sans GB', sans-serif"` | CSS 字体栈 | 与 `--font-body` 同构（style.css:97） |
| `DEFAULT_BG_COLOR` | `'#ffffff'` | input[type=color] 值 | :249 |
| `DEFAULT_BG_GRADIENT_FROM` | `'#667eea'` | 同上 | :249 |
| `DEFAULT_BG_GRADIENT_TO` | `'#764ba2'` | 同上 | :249 |

注：`#fff`→`#ffffff` 是等值长写法（同为纯白），允许；其余值逐字符相等。`lineWidth 6/4`、几何数值（`barH 170`、inset 等）原样随函数迁移，不提常量、不改值。

### 5.2 校验规则

N/A：无用户输入校验变化（画布标题/副标题既有空串守卫 `if (title)` 原样保留）。

### 5.3 业务判断规则

```text
IF getComputedStyle(document.documentElement).getPropertyValue('--font-body').trim() 非空
THEN canvasFontStack() 返回该 token 值
ELSE canvasFontStack() 返回 CANVAS_FONT_FALLBACK
```

MUST NOT 把上述规则改写成其他行为（如读 `--font-display`、读 body 元素、抛错）。

### 5.4 权限与业务不变量

N/A：无权限、资金、数据不变量涉及。本任务不变量即 §5.1 值表与 D-04 置换清单，由 V-006 静态检查客观锁定。

---

## 6. 接口契约（每个受影响接口各填一份）

N/A：本任务零接口改动。既有 `useAiStudio` 等调用、`fetch` 请求、`MerchantCommerceCard.test.ts` 里 mock 的全部 `/api/v2/merchant/*` 端点契约不变（该测试必须原样全绿，作为回归证据）。

---

## 7. 数据模型与迁移（仅涉及数据库、缓存、状态管理或本地存储时填）

N/A：无数据库、缓存、storage 变化。`BackgroundConfig` 类型（`src/types/grassland/ai-studio.ts`）不动，仅初始值换常量引用。

---

## 8. UI 实现规格（仅前端卡填写）

### 8.1 页面归属

- 目标端：用户端（创作中心 `/creation` + 商家工作台）；AI 创作中心经复用视图同步生效；共享组件 1 个
- 页面入口：`src/views/ai-center/AiCreationCenter.vue`、`src/views/grassland/GrasslandWorkbench.vue`
- 设计规范：根 `DESIGN.md`
- 目标主题：**暗色 + 亮色（必选两者）**
- 目标设备：桌面（既有断点 760px 保留，不新增响应式行为）

### 8.2 页面结构

N/A：三处改动均发生在既有结构内部（替换样式值/常量引用/模块迁出），DOM 结构、层级、类名零变化（`cover-text-layout.ts` 迁出的是 `<script setup>` 内符号，模板引用改 import 后渲染结果不变）。

### 8.3 组件行为

| 组件 | 初始状态 | 用户操作 | 成功状态 | 失败状态 |
|---|---|---|---|---|
| VideoStudioView 封面画布 | 与现状一致 | 选排版/改标题（既有交互） | 画布叠加文字，字体走 `--font-body` 解析结果 | AI 生成失败文案不变（:508/:511） |
| ImageStudioView 背景配置 | 三默认色（常量化） | 切模式/改色（既有交互） | 同现状 | 无新增失败路径 |
| MerchantCommerceCard | 同现状 | 启动扫码（既有交互） | 取景器媒体三 token 渲染，视觉与现状一致 | 摄像头失败提示不变（`.scanner-notice`） |

### 8.4 交互细节

N/A：无任何交互变化（按钮、弹窗、提交、反馈全部保持现状）。

### 8.5 响应式要求

既有 `@media (max-width: 760px)`（`MerchantCommerceCard.vue:471`）保留原样；本任务不新增断点、不改布局值。

### 8.6 视觉约束

按模板 §8.6 原文执行（token-only、双主题成对、`[data-app="ops"]` 隔离、字体两款、禁 overflow:hidden 掩盖）。本任务 token 映射：

| 用途 | DESIGN.md token 引用 | 实际 CSS 变量/共享类 | 暗值/亮值及定义位置 | 本次是否变更 |
|---|---|---|---|---|
| 画布正文字体家族 | typography 各字体族的 Inter 基底 | `--font-body` | 同值双主题（`style.css:97`，亮色块未覆写） | 否（新增运行时读值） |
| 取景器容器底色 | colors.`media-backdrop`（新登记） | `--color-media-backdrop` | `#111111` / `#111111`（`:root` + `[data-theme="light"]` 新增） | 新增 |
| 取景器字幕衬底 | colors.`media-scrim`（新登记） | `--color-media-scrim` | `rgba(0, 0, 0, 0.65)` 同值 | 新增 |
| 取景器字幕文字 | colors.`media-ink`（新登记） | `--color-media-ink` | `#ffffff` 同值 | 新增 |
| 卡片主内边距 16px | spacing 体系 | `--space-md` | 16px 同值 | 置换 |
| 8px 间距（11 处单一值） | spacing 体系 | `--space-xs` | 8px 同值 | 置换 |
| 12px 间距（2 处单一值） | spacing 体系 | `--space-sm` | 12px 同值 | 置换 |
| 12px 字号（4 处） | typography caption 档 | `--text-xs` | 0.75rem（=12px）同值 | 置换 |
| 画布内容色 10 处 | 刻意不映射（D-01-③） | `COVER_*` 常量 | 组件常量，双主题同值 | 新增命名 |
| 背景默认色 3 处 | 刻意不映射（D-01-③） | `DEFAULT_BG_*` 常量 | 同上 | 新增命名 |

规范 token 暂无 CSS 映射时的处理：`--color-media-*` 三 token 即本次批准的规范增补（DESIGN.md + style.css 同步，D-03），`src/style.css` 与 `DESIGN.md` 均已列入写入白名单。

### 8.7 无障碍

不改动任何可交互元素、`aria-*` 属性与焦点行为；取景器 video 既有 `aria-label="核销二维码扫描画面"` 原样保留；无新增仅以颜色传达的状态。

### 8.8 截图自查

- 页面改完后 MUST 按 §8.8 原文双主题截图；C-01/C-02 的画布/默认值属导出内容（主题无关），截图目的是**回归证明页面 chrome 未被改坏**；C-03 取景器三 token 是真实主题相关渲染点（暗亮同值也必须两张都拍）。
- 截图经仓库根项目 Playwright 无头脚本拍摄（脚本放仓库根、产物入 `test-artifacts/`，均不提交）；主题用 `addInitScript` 写 `theme-preference`（勿改属性，store 竞态会翻回）；账号用 `e2e-seed` 合成账号。
- 取景器 `scanning` 态截图：Chromium 启动参数加 `--use-fake-device-for-media-stream`（本地无真实摄像头时的标准做法）；若 fake-device 仍启动失败，按 §13 报告 BLOCKED/NOT_RUN，MUST NOT 静默跳过或拿静态态冒充。

| 页面/入口/状态 | 视口 | 合成数据与前置条件 | 暗色截图路径 | 亮色截图路径 | 自查要点 |
|---|---|---|---|---|---|
| `index.html` `/creation` 视频 tab（封面排版下拉可见，未触发生成） | 1440×900 | e2e-seed 用户登录 | `test-artifacts/task89-screenshots/user-creation-video-dark.png` | `…-light.png` | 页面 chrome 无变化；下拉 8 项正常 |
| `/creation` 图像 tab 抠图背景节（模式按钮与色板可见） | 1440×900 | 同上 | `…-user-creation-image-dark.png` | `…-light.png` | 背景选择器 UI 无变化 |
| 工作台「到店套餐与核销」卡静态态 | 1440×900 | e2e-seed 商家身份 | `…-workbench-commerce-static-dark.png` | `…-light.png` | 卡片视觉无变化 |
| 同上 `scanning` 态（取景器+字幕条渲染中） | 1440×900 | 同上 + fake-device 启动扫码 | `…-workbench-commerce-scanning-dark.png` | `…-light.png` | 取景器深色底、白字字幕条两主题一致 |

---

## 9. 全局约束（每张卡适用）

### 9.1 文件白名单 / 黑名单

| 精确路径 | 权限 | 本次操作 | 允许修改的符号/段落 | 原因与完成标准 | 所属任务卡 |
|---|---|---|---|---|---|
| `src/views/ai-center/components/VideoStudioView.vue` | 写入 | 修改 | `<script setup>` 内 :535-620 绘制块删除；:434 类型引用、:532 调用点、:207-208 模板引用改 import | 常量/注册表迁出后组件可编译、渲染不变 | C-01 |
| `src/views/ai-center/components/cover-text-layout.ts` | 写入 | 新建 | 全文件（§11 C-01 规格） | 单测可直测字体解析与绘制常量 | C-01 |
| `src/views/ai-center/components/cover-text-layout.test.ts` | 写入 | 新建 | 全文件（TC-C01-001..004） | `npm run test -- <file>` 绿 | C-01 |
| `src/views/ai-center/components/ImageStudioView.vue` | 写入 | 修改 | :248-251 `bgConfig` 初值 + 其上常量块 | grep 计数 3、行为零变更 | C-02 |
| `src/components/MerchantCommerceCard.vue` | 写入 | 修改 | `<style scoped>` :438-472 内 C-03 步骤 3/4 列出的选择器 | 裸 hex/rgba 清零、精确匹配置换完成 | C-03 |
| `src/style.css` | 写入 | 修改 | `:root` 与 `[data-theme="light"]` 各新增 3 行 media token（带注释） | 双主题成对同值；lint/typecheck 绿 | C-03 |
| `DESIGN.md` | 写入 | 修改 | `colors:` 节 `shadow-blue` 行后新增 3 键 | design.md lint errors 0；值与 CSS 逐字一致 | C-03 |
| `src/types/grassland/ai-studio.ts` | 只读参考 | 读取 | `BackgroundConfig` | 类型复用依据 | C-02 |
| `src/utils/subtitle-timeline.ts` | 只读参考 | 读取 | 模块导出风格 | 新模块风格参照 | C-01 |
| `src/components/MerchantCommerceCard.test.ts` | 只读参考 | 读取 | 全部用例 | 回归必须全绿，MUST NOT 修改 | C-03 |
| `test-artifacts/task89-screenshots/` | 生成物目录 | 新建目录 | 仅截图 PNG 与截图脚本 | `.gitignore` 已含 `test-artifacts`，保留本地不提交 | 全部 |

黑名单（禁止修改，优先级最高）：`.pi/`、`docs/测试/workbench-business-audit-2026-09-06.md`（任务前已有未跟踪内容）、`src/components/OrganizationBrandCard.vue`、`src/components/RecommenderShareCard.vue`、`package.json`、`package-lock.json`、`.github/workflows/`、`nginx.conf`、`vite.config.ts`、三个 HTML 入口、`src/ops/**`、既有已执行迁移、真实凭据文件。

- 写入白名单仅上表「写入」行；卡写入集合必须是全局写入集合的子集。
- 本任务前已有改动：仅两个未跟踪项（黑名单已列），无重叠冲突。
- 测试生成物：仅 `test-artifacts/task89-screenshots/`，不提交、不混入源码。

### 9.2 项目铁律速查

按模板 §9.2 原文执行（R-UI/R-ENTRY/R-JAVA/R-DATA/R-AI/R-QUALITY/R-SAFE 全文适用）；本任务命中项的落实见 §9.5 矩阵。

### 9.3 验证环境事实

| 项目 | 本任务的精确值/检查方式 |
|---|---|
| 仓库根/命令 shell | `/Users/LXH/claude/y-1`；zsh（前端命令均在仓库根执行） |
| Node/npm | node v22.22.3、npm 10.9.8；依赖已按 package-lock.json 安装 |
| Java/Gradle | 不涉及（纯前端任务） |
| Docker/依赖服务 | 单元/静态验收不需要；仅 V-008 截图需要本地 compose 栈 + `e2e-seed` 合成账号（商家身份一枚、普通用户一枚） |
| 入口地址 | 本地栈 BASE_URL（以本地 compose 实际端口为准，不使用生产） |
| 测试数据 | `e2e-seed` 合成账号；截图不得出现真实账号/token |
| 网络/权限 | `npx --yes @google/design.md lint` 需联网现拉（AGENTS.md 校验节既定命令，属默认允许的开发步骤）；无其他远程资源 |
| 产物目录 | `test-artifacts/task89-screenshots/`（gitignored，本地保留不提交） |

### 9.4 安全、性能与兼容规格

| 类别 | 必须明确的项目 | 本任务唯一要求/阈值 | 验证用例 |
|---|---|---|---|
| 安全 | 身份/越权 | 无身份面变化；不触任何鉴权代码 | N/A：纯样式/常量收口 |
| 隐私 | 截图脱敏 | 截图仅含合成账号数据；不含 token/签名 URL | V-008 人工核查 |
| 外部输入 | 无新增外部输入面 | 画布标题/副标题沿用既有空串守卫，未新增解析 | TC-C01-003/004 |
| 性能 | 无热路径变化 | `canvasFontStack()` 每次绘制读一次 computed style（µs 级，8 套排版仅在用户交互时绘制一次）；不引入缓存/监听 | N/A：无可断言的性能阈值 |
| 异步资源 | 无新增异步 | 不加 fonts.ready/监听器/定时器 | TC-C01-001/002 |
| 兼容 | 三入口 | `ai.html` 复用视图自动生效；不触入口脚本/CSP hash | V-009 build |

### 9.5 仓库约束适用矩阵

| 约束 | 适用卡号/文件 | 落实动作与验收；或 N/A 原因 |
|---|---|---|
| R-UI | C-01/02/03 全部 | token-only（§8.6 表）、双主题成对（D-03）、字体两款（D-02/D-06）、每页双主题截图（V-008）；不新建基础组件 |
| R-ENTRY | N/A | 无入口/身份/部署契约改动；ai.html 复用自动生效无需登记 |
| R-JAVA | N/A | 纯前端，无后端改动 |
| R-DATA | N/A | 无迁移/事务/重放 |
| R-AI | N/A | 封面画布叠加是纯前端绘制，不触执行环；AI 封面生成流零改动 |
| R-QUALITY | C-01（新测试）、C-03（回归） | Vitest 定向+全量、typecheck、lint（不降阈值）、design.md lint；`docs/status.yaml` 不动（无功能状态变化） |
| R-SAFE | 全部 | 两个未跟踪项保留不触；截图/报告脱敏；不删测试不放宽断言 |

---

## 10. 任务总表

| 卡 | 标题 | 端 | 对应需求 | 主要写入文件 | 依赖及交付物 | 验收编号 | 执行状态 |
|---|---|---|---|---|---|---|---|
| C-01 | 封面画布字体 token 化 + 内容常量收口（抽纯模块） | 用户端/AI 复用 | REQ-001 | `VideoStudioView.vue`、`cover-text-layout.ts`、`cover-text-layout.test.ts` | 无 | AC-001..003、TC-C01-001..004、V-001/003/004/005/006/008 | NOT_STARTED |
| C-02 | 图像工作台背景默认值常量化 | 用户端/AI 复用 | REQ-002 | `ImageStudioView.vue` | 无 | AC-004、V-003/004/005/006/008 | NOT_STARTED |
| C-03 | 媒体三 token 注册 + 核销卡颜色/尺寸收敛 | 共享组件 | REQ-003、REQ-004 | `DESIGN.md`、`style.css`、`MerchantCommerceCard.vue` | 无（与 C-01/02 无共享文件；独占 style.css/DESIGN.md 写入） | AC-005..008、TC-C03-R1、V-002/003/004/005/006/007/008 | NOT_STARTED |

### 10.1 任务卡拆分规则

按模板 §10.1 原文执行。本任务三卡按「组件/着色域」拆分；三卡可并行（无共享写入文件），AUTO_CHAIN 默认串行 C-01→C-02→C-03。

### 10.2 卡间交接协议

| 交接项 | 前置卡输出 | 后置卡读取方式 | 完成证据 |
|---|---|---|---|
| 源码 | 无卡间依赖（三卡互不消费） | — | 各卡 diff + 测试 |

---

## 11. 任务卡

### 卡 C-01：封面画布字体 token 化 + 内容常量收口（抽纯模块）

**执行包**：任务书版本 v1.0；对应需求 REQ-001；负责执行者 弱编码模型；本卡负责人/验收人 ZCode（规划模型）。

**背景**：视频封面画布叠加文字的字体用的是 `system-ui`（违反字体两款硬规则），绘制色散落为魔法字面量。画布画在**导出图片**上，内容色刻意不接主题 token（D-01-③）；仅字体家族改为从 `--font-body` 解析（D-02）。为可单测，绘制代码抽纯模块（D-05）。

**输入与前置交付物**：无依赖卡；`--font-body` token 已存在于 `src/style.css:97`（FACT）。

**输出与移交**：`cover-text-layout.ts`（导出 `CoverTextLayoutId`/`CoverTextLayout`/`COVER_TEXT_LAYOUTS`/`canvasFontStack`）+ 同名测试 + 组件改造；V-001 定向测试绿、V-006 静态检查达标。

**必读清单**：§0（三条专属强调）、§3 D-01/D-02/D-05/D-06、§5.1 值表、§8.6/§8.8；`src/style.css:97`（token 值）、`src/utils/subtitle-timeline.ts`（模块风格）、`VideoStudioView.vue:434-620`（迁移源）。

**改动文件**（本卡写入集合是 §9.1 写入集合的子集）：

| 精确路径 | 操作/权限 | 允许改动的符号 | 修改目的 | 完成标准 |
|---|---|---|---|---|
| `src/views/ai-center/components/cover-text-layout.ts` | 新建 | 全文件 | 承载常量+字体解析+8 套排版 | 导出齐全；V-001 绿 |
| `src/views/ai-center/components/VideoStudioView.vue` | 修改 | :535-620 删除；顶部 import；:434/:532 引用调整 | 消费纯模块 | typecheck 绿；渲染不变 |
| `src/views/ai-center/components/cover-text-layout.test.ts` | 新建 | 全文件 | TC-C01-001..004 | V-001 绿 |
| `src/utils/subtitle-timeline.ts` | 只读参考 | 导出风格 | 风格参照 | 不修改 |

**开始前检查**：

1. 核对 `--font-body` 现值与 §5.1 回退栈同构；记录已有未提交改动（应只有两个黑名单未跟踪项）。
2. 核对 `VideoStudioView.vue` :434（`coverLayout` ref）、:532（draw 调用）、:207-208（模板下拉）三个消费点。
3. 基线命令：`npm run typecheck`（预期退出 0）。
4. 环境：node 依赖已装，无需服务。

**锚点代码**（当前片段，非目标代码；行号基于基线 SHA）：

```ts
// src/views/ai-center/components/VideoStudioView.vue:547-548
const TITLE_FONT = 'bold 60px system-ui, sans-serif'
const SUBTITLE_FONT = '34px system-ui, sans-serif'

// :550-557（描边绘制，色值字面量）
function strokeTitle(ctx: CanvasRenderingContext2D, text: string, x: number, y: number, centered: boolean): void {
  ctx.font = TITLE_FONT
  ctx.strokeStyle = 'rgba(0,0,0,0.7)'; ctx.lineWidth = 6
  ctx.fillStyle = '#fff'
  if (centered) ctx.textAlign = 'center'
  ctx.strokeText(text, x, y); ctx.fillText(text, x, y)
  if (centered) ctx.textAlign = 'start'
}

// src/style.css:97（token 单一来源）
--font-body: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', sans-serif;
```

**本卡目标行为**：`COVER_TEXT_LAYOUTS` 8 套排版的绘制结果与现状逐像素同义（同值颜色/字号/几何），唯一差异为字体家族按 D-02 解析（D-06 微差）。类型与调用签名：

```ts
// cover-text-layout.ts 导出签名（声明即实现目标）
export type CoverTextLayoutId =
  | 'left-bold' | 'center-block'
  | 'top-bar' | 'bottom-bar'
  | 'center-gradient' | 'bottom-gradient'
  | 'top-stroke' | 'center-stroke'

export interface CoverTextLayout {
  id: CoverTextLayoutId
  label: string
  draw: (ctx: CanvasRenderingContext2D, w: number, h: number, title: string, subtitle: string) => void
}

export const COVER_TEXT_LAYOUTS: CoverTextLayout[]  // 8 项，id/label/draw 语义与现状一致

export function canvasFontStack(): string  // §5.3 规则；回退 CANVAS_FONT_FALLBACK
```

**函数级要求**：

`cover-text-layout.ts` - `canvasFontStack`

- 完整签名与类型：`export function canvasFontStack(): string`。
- 输入：无（读 `document.documentElement` 的 `--font-body`）。
- 输出：非空 token 值或 `CANVAS_FONT_FALLBACK`；同步纯读，无异常抛出路径。
- 副作用：无（getComputedStyle 读不产生写副作用）。
- 不变条件：返回值永远是合法 CSS 字体栈串（`ctx.font` 赋值不会静默失效）。
- 清理与失败：无资源需清理。

- MUST 满足：1. §5.1 全部常量逐字相等；2. `TITLE`/`SUBTITLE` 字体串由常量 + `canvasFontStack()` 组合，不再出现 `system-ui`；3. 模块不 import Vue/API（纯 TS）；4. 组件 :532 调用语义不变。
- MUST NOT：1. 改 `renderCover` 几何/裁切逻辑；2. 导出 `strokeTitle` 等内部函数；3. 引入字体等待/缓存机制；4. 把 `#fff` 写成除 `#ffffff` 外的其他值。

**做法**（按顺序执行）：

| 步骤 | 文件/符号 | 精确动作 | 完成检查点 | 失败时处理 |
|---|---|---|---|---|
| 1 | `cover-text-layout.ts` | 新建：头部注释（画布内容常量，刻意不接主题，D-01-③）；§5.1 常量块；`canvasFontStack`/`titleFont`/`subtitleFont`（内部）；迁移 `strokeTitle`/`strokeSubtitle`/`barLayout`/`gradientLayout` 与 `COVER_TEXT_LAYOUTS`（色值换 `COVER_*` 常量、字体串换函数调用，几何数值原样） | 文件内 `system-ui` 计数 0；hex/rgba 行计数 8（§5.1 所列） | 编译错误对照 §5.1 修 |
| 2 | `VideoStudioView.vue` | 删除 :535-620（类型+常量+4 函数+注册表）；`<script setup>` 顶部 `import { COVER_TEXT_LAYOUTS } from './cover-text-layout'` 与 `import type { CoverTextLayoutId } from './cover-text-layout'`；:434/:532/:207-208 引用自动成立（符号同名） | `npm run typecheck` 退出 0 | 检查遗漏消费点 |
| 3 | `cover-text-layout.test.ts` | 新建 TC-C01-001..004（见 §12.2） | `npm run test -- src/views/ai-center/components/cover-text-layout.test.ts` 退出 0、4 用例 | 修测试或代码（不改断言语义） |
| 4 | 截图 | `/creation` 视频 tab 双主题（§8.8 行 1） | 两张 PNG 落 `test-artifacts/task89-screenshots/` | 本地栈问题按 §13 报告 |

除本卡明确要求的迁移外，不实现后置卡功能、不扩展导出面。

**边界与异常场景**：

| 编号 | 场景 | 触发条件 | 预期行为 | 必测 |
|---|---|---|---|---|
| E01 | 空输入 | title/subtitle 为空串 | 既有 `if (title)` 守卫跳过绘制（原样迁移） | 是（TC-C01-003 顺带断言守卫路径之一） |
| E02 | 超长输入 | 标题超画布宽 | 现状即溢出/不换行，保持不变（本卡禁改几何） | N/A：无变化，不适用原因「行为保持现状且本卡禁止改排版」→ 必测改 N/A |
| E03 | 重复提交 | 不适用，原因：无请求/提交语义 | — | N/A |
| E04 | 网络失败 | 不适用，原因：模块无网络调用 | — | N/A |
| E05 | 服务端错误 | 不适用，原因：同 E04 | — | N/A |
| E06 | 未登录 | 不适用，原因：纯模块不涉登录；页面级登录态由既有路由守卫负责 | — | N/A |
| E07 | 无权限 | 不适用，原因：同 E06 | — | N/A |
| E08 | 数据为空 | 不适用，原因：无数据获取 | — | N/A |
| E09 | 数据过期 | 不适用，原因：无缓存数据 | — | N/A |
| E10 | 页面刷新 | 刷新后重新进入封面 tab | `coverLayout` 回默认 `'left-bold'`（现状）；画布每次交互重绘 | N/A：无变化，截图回归覆盖 |
| E11 | 用户快速切换 | 快速切换排版下拉 | 每次变更触发 `watch` 重绘（现状机制不变） | N/A：无变化 |
| E12 | 组件卸载时请求未完成 | 不适用，原因：模块无请求 | — | N/A |
| E13 | `--font-body` 读值为空/纯空白 | 测试环境或样式未注入 | 回退 `CANVAS_FONT_FALLBACK`，绘制不失败 | 是（TC-C01-002） |
| E14 | 数值边界 | 不适用，原因：常量值固定不参与计算边界 | — | N/A |
| E15 | 并发/乱序 | 不适用，原因：同步纯函数 | — | N/A |
| E16 | 超时 | 不适用，原因：无异步操作 | — | N/A |
| E17 | 跨账号/组织 | 不适用，原因：无身份面 | — | N/A |
| E18 | 权限/资源中途撤销 | 不适用，原因：同 E17 | — | N/A |
| E19 | 旧数据/旧客户端 | 不适用，原因：无持久化数据与接口版本 | — | N/A |
| E20 | 部分成功/补偿 | 不适用，原因：无事务性操作 | — | N/A |
| E21 | 日期/时区/金额 | 不适用，原因：不涉及 | — | N/A |
| E22 | 超长文案 | 同 E02 | 同 E02 | N/A |

**本卡禁止**：改 `renderCover` 裁切/尺寸逻辑；动 AI 封面生成流（:488-513）；导出内部绘制函数；加 fonts.ready；把常量挪到 `style.css`。

**验收**：

- 测试清单：TC-C01-001（token 解析）、TC-C01-002（空回退）、TC-C01-003（center-block 绘制调用与常量断言）、TC-C01-004（top-bar/渐变遮罩 scrim 常量断言）。
- 命令验收：V-001（定向 vitest）、V-003（typecheck）、V-006（静态计数：`VideoStudioView.vue` hex/rgba=0、`system-ui`=0；`cover-text-layout.ts` hex/rgba 行=8）。
- 行为验收：
  - AC-001：Given token `--font-body` 可读，When 任一排版绘制标题，Then 捕获的 `ctx.font` 以 `bold 60px ` 开头且含 `Inter`，`fillStyle === '#ffffff'`，且不出现 `system-ui`。
  - AC-002：Given `--font-body` 读值为空串，When 调 `canvasFontStack()`，Then 返回 `CANVAS_FONT_FALLBACK`（含 `'Inter'`）。
  - AC-003：Given 组件挂载且用户切换排版，Then 下拉 8 项、绘制行为与现状一致（截图回归 + 既有交互零改动）。
- UI 验收：§8.8 行 1 双主题截图。
- 保留行为回归：V-005 全量 vitest 含 `AiCreationCenter` 相关既有用例全绿。

**完成后**：按 §14 报告。

---

### 卡 C-02：图像工作台背景默认值常量化

**执行包**：任务书版本 v1.0；对应需求 REQ-002；负责执行者 弱编码模型；本卡负责人/验收人 ZCode。

**背景**：`bgConfig` 三个默认色是**用户内容默认值**（`<input type="color">` 初值、导出图片的底色），刻意不接主题 token（D-01-③）；现状是行内魔法值，缺命名与语义注释，被审计误报为 token 违规。本卡只做命名收口，值与行为零变更。

**输入与前置交付物**：无依赖卡。

**输出与移交**：`ImageStudioView.vue` 常量块 + 初值引用；V-006 计数 3。

**必读清单**：§3 D-01、§5.1 值表下半段、§8.8；`ImageStudioView.vue:94-98`（色板模板）、`:248-251`（初值）、`:437-448`（导出消费）。

**改动文件**：

| 精确路径 | 操作/权限 | 允许改动的符号 | 修改目的 | 完成标准 |
|---|---|---|---|---|
| `src/views/ai-center/components/ImageStudioView.vue` | 修改 | `bgConfig` 初值 + 其上方新增常量块 | 命名收口 | grep 计数 3；typecheck 绿 |
| `src/types/grassland/ai-studio.ts` | 只读参考 | `BackgroundConfig` | 类型依据 | 不修改 |

**开始前检查**：核对 :248-251 现值与 §5.1 一致；`npm run typecheck` 基线 0。

**锚点代码**（当前片段）：

```ts
// src/views/ai-center/components/ImageStudioView.vue:248-251
const bgConfig = reactive<BackgroundConfig>({
  mode: 'color', color: '#ffffff', gradientFrom: '#667eea', gradientTo: '#764ba2',
  blurRadius: 10, imageFile: null,
})
```

**本卡目标行为**：零行为变更。落地形态：

```ts
// 抠图背景默认值：用户内容（导出图片底色/渐变端点），刻意不接明暗主题 token——
// 导出结果不能随观者主题漂移（任务书 #89 D-01-③）。
const DEFAULT_BG_COLOR = '#ffffff'
const DEFAULT_BG_GRADIENT_FROM = '#667eea'
const DEFAULT_BG_GRADIENT_TO = '#764ba2'

const bgConfig = reactive<BackgroundConfig>({
  mode: 'color', color: DEFAULT_BG_COLOR,
  gradientFrom: DEFAULT_BG_GRADIENT_FROM, gradientTo: DEFAULT_BG_GRADIENT_TO,
  blurRadius: 10, imageFile: null,
})
```

- MUST 满足：1. 三常量值逐字等于 §5.1；2. `blurRadius: 10` 与其余键原样；3. 注释含「刻意不接主题」语义。
- MUST NOT：1. 改 `BackgroundConfig` 类型；2. 改 :437-448 导出消费逻辑；3. 给 `blurRadius` 提常量；4. 把默认色改成品牌色。

**做法**：

| 步骤 | 文件/符号 | 精确动作 | 完成检查点 | 失败时处理 |
|---|---|---|---|---|
| 1 | `ImageStudioView.vue` `bgConfig` 上方 | 新增三常量 + 两行注释（锚点形态） | 常量与 §5.1 逐字相等 | 对照值表修 |
| 2 | `:249` 初值 | 三字面量换常量引用 | `grep -cE "#[0-9a-fA-F]{3,8}\|rgba?\(" ImageStudioView.vue` = 3（仅常量块三行） | 计数不为 3 时查漏 |
| 3 | 截图 | `/creation` 图像 tab 双主题（§8.8 行 2） | 两张 PNG | 本地栈问题按 §13 |

**边界与异常场景**：

| 编号 | 场景 | 触发条件 | 预期行为 | 必测 |
|---|---|---|---|---|
| E01-E22 | 全部 | 不适用，原因：本卡为纯字面量→命名常量搬运，无输入、请求、状态、权限、并发、持久化等任何行为面；唯一风险「值抄错」由 V-006 计数 3 + typecheck + 截图回归客观锁定 | — | N/A |

（按模板要求逐行说明：E01 空输入/E02 超长/E03 重复提交/E04 网络/E05 服务端/E06 未登录/E07 无权限/E08 空数据/E09 过期/E10 刷新/E11 快切/E12 卸载/E13 非法值/E14 数值边界/E15 并发/E16 超时/E17 跨账号/E18 权限撤销/E19 旧数据/E20 补偿/E21 日期金额/E22 超大文件——均不适用，理由同上：零行为面。）

**本卡禁止**：动滤镜/裁剪/导出逻辑；改 UI 模板；顺带修该文件其他字面量。

**验收**：

- 测试清单：无新增自动化用例。N/A 理由：happy-dom 不触发 `Image.onload`，挂载级断言到不了背景节；纯搬运由静态检查锁定（模板允许检索型负向检查作为验证）。
- 命令验收：V-003（typecheck）、V-006（计数 3）、V-005（全量回归）。
- 行为验收：
  - AC-004：Given e2e-seed 用户进入 `/creation` 图像 tab，Then 背景模式 UI 与色板初值与现状一致（截图回归），导出结果逐像素不变。
- UI 验收：§8.8 行 2 双主题截图。
- 保留行为回归：V-005 全量绿。

**完成后**：按 §14 报告。

---

### 卡 C-03：媒体三 token 注册 + 核销卡颜色/尺寸收敛

**执行包**：任务书版本 v1.0；对应需求 REQ-003、REQ-004；负责执行者 弱编码模型；本卡负责人/验收人 ZCode。

**背景**：核销卡取景器三色是**媒体容器色**（D-01-②）：视频区在亮色 UI 下也保持深色，需新语义 token 暗亮同值注册；卡片其余间距/字号做 D-04 精确匹配置换。该组件被 `RecommenderShareCard.vue:121` 注释当 token 先例引用，本卡让名实相符。

**输入与前置交付物**：无依赖卡。

**输出与移交**：DESIGN.md 三键 + style.css 双主题三 token + 组件样式收敛；V-002 回归绿、V-007 lint errors 0。

**必读清单**：§3 D-01/D-03/D-04/D-06、§8.6 表、§8.8；`src/style.css:14-34`（:root 插入点）与 `:110-131`（light 插入点）、`DESIGN.md:6-29`（colors 节）、`MerchantCommerceCard.vue:438-472`（样式块）、`MerchantCommerceCard.test.ts`（回归）。

**改动文件**：

| 精确路径 | 操作/权限 | 允许改动的符号 | 修改目的 | 完成标准 |
|---|---|---|---|---|
| `DESIGN.md` | 修改 | colors 节 `shadow-blue` 行后 | 登记 media 三键 | V-007 errors 0；值与 CSS 逐字一致 |
| `src/style.css` | 修改 | `:root` 内 `--color-border-accent` 行后；`[data-theme="light"]` 内对应色 token 区块末 | 双主题成对注册 | grep 两处各 3 token |
| `src/components/MerchantCommerceCard.vue` | 修改 | `<style scoped>` 内 C-03 步骤 3/4 清单 | 裸色清零 + 置换 | V-006 该文件 hex/rgba 计数 0 |
| `src/components/MerchantCommerceCard.test.ts` | 只读参考 | 全部 | 回归 | V-002 绿，文件零改动 |

**开始前检查**：核对 `--surface-muted` 亮色确有覆写（`#eef2f8`，证不可复用）；`npm run test -- src/components/MerchantCommerceCard.test.ts` 基线绿；记录 lint 基线 2 警告。

**锚点代码**（当前片段）：

```css
/* src/components/MerchantCommerceCard.vue:469（违规行） */
.scanner-box { position: relative; margin-top: 8px; overflow: hidden; border-radius: var(--radius-lg); background: #111; }
.scanner-box video { display: block; width: 100%; max-height: 260px; object-fit: cover; }
.scanner-box p { position: absolute; inset: auto 8px 8px; margin: 0; padding: 5px 8px; border-radius: var(--radius-sm); color: white; background: rgba(0, 0, 0, .65); }
```

```yaml
# DESIGN.md colors 节末尾（:29）
  shadow-blue: "#003770"
```

**本卡目标行为**：视觉零变化（token 同值替换）；取景器三色获得主题稳定语义；D-04 清单内间距/字号完成置换。

**做法**（按顺序执行）：

| 步骤 | 文件/符号 | 精确动作 | 完成检查点 | 失败时处理 |
|---|---|---|---|---|
| 1 | `DESIGN.md` colors 节 | `shadow-blue` 行后新增三行：`  media-backdrop: "#111111"`、`  media-scrim: "rgba(0, 0, 0, 0.65)"`、`  media-ink: "#ffffff"`（缩进两空格与邻行一致） | V-007 errors 0 | 若 lint 报错且原因是键名/值格式 → B-01 阻塞报告，MUST NOT 自行改值迁就工具 |
| 2 | `src/style.css` | `:root` 的 `--color-border-accent` 行后与 `[data-theme="light"]` 色区块末各插入三行：`--color-media-backdrop: #111111;`、`--color-media-scrim: rgba(0, 0, 0, 0.65);`、`--color-media-ink: #ffffff;`，各配一行注释 `/* 媒体容器色：取景器/视频区，暗亮同值（任务书 #89 D-03） */` | 两主题块各 grep 到 3 个 `--color-media-` | 插入点漂移则按符号定位，语义一致可继续 |
| 3 | `MerchantCommerceCard.vue` :469 | `.scanner-box` 的 `background: #111` → `var(--color-media-backdrop)`；`.scanner-box p` 的 `color: white` → `var(--color-media-ink)`、`background: rgba(0, 0, 0, .65)` → `var(--color-media-scrim)` | 该文件 `grep -nE "#[0-9a-fA-F]{3,8}\|rgba?\(\|: white\|color: white"` 计数 0 | 漏改则补 |
| 4 | 同文件样式块 | D-04 精确置换，仅以下各处：**8px→`var(--space-xs)`**：`.header-actions` gap、`.form-grid` gap、`.slots-editor` gap、`.compact-orders` gap、`.resolve-form` gap/margin-top/padding-top、`.package-list` gap、`.promotion-task-box` gap、`.scanner-actions` gap 与 margin-top；**12px→`var(--space-sm)`**：`.redemption-grid` gap、`.redemption-grid > div` padding；**16px→`var(--space-md)`**：`.commerce-card` padding；**`font-size: 12px`→`var(--text-xs)`**：`.card-head p, .package-row p, .empty` 共用规则、`.compact-order p`、`.promotion-task-box`、`.scanner-notice`。其余字面量（14/11/10/6/5/3/2px、复合简写 `6px 8px`/`7px 9px`/`5px 8px`/`2px 8px`、`36px`/`150px`/`260px`/`320px`、`gap: 10px`/`14px`、`padding: 10px`/`14px` 等）一律保留 | 逐条勾对；typecheck/lint 绿 | 置换清单与现状选择器对不上时按符号定位并记录，语义一致可继续 |
| 5 | 截图 | 工作台核销卡静态 + scanning 态（fake-device）双主题（§8.8 行 3/4） | 四张 PNG | fake-device 失败按 §13 报告 |

**边界与异常场景**：

| 编号 | 场景 | 触发条件 | 预期行为 | 必测 |
|---|---|---|---|---|
| E01-E22 | 大部 | 不适用，原因：纯 CSS 值替换，无 JS 行为面；组件交互（扫码/核销/表单）零改动，由既有测试回归 | — | N/A |
| E10 | 页面刷新 | 刷新工作台 | 样式经全局 style.css 加载，token 生效；`.scanner-box` 仅 `v-if="scanning"` 渲染（现状） | 是（截图 scanning 态覆盖渲染路径） |
| E19 | 旧客户端读新样式 | 部署窗口内新旧前端混用 | `--color-media-*` 未定义时 `var()` 回退为初始值（背景透明）——仅发生在「新组件 JS+旧 CSS」混布窗口；本项目前后端同仓同发版，窗口不存在 | N/A：发布模型不存在该组合 |

（其余 E02-E09/E11-E18/E20-E22 按模板逐行口径均不适用，理由同第一行：无输入/请求/状态/权限/并发/持久化行为面。）

**本卡禁止**：动模板/脚本（`<template>`/`<script setup>` 零改动）；改既有测试；置换清单外的任何字面量；给 media token 加亮色差异值。

**验收**：

- 测试清单：TC-C03-R1（回归＝`MerchantCommerceCard.test.ts` 既有全部用例，含完整扫码流，零改动原样通过）。
- 命令验收：V-002（定向回归）、V-007（design.md lint）、V-006（hex/rgba 计数 0）。
- 行为验收：
  - AC-005：Given 亮色主题且扫码启动，Then 取景器底仍为 `#111111`、字幕条仍为黑底白字（与暗色一致、与改造前一致）。
  - AC-006：Given 任意主题浏览核销卡，Then 卡片布局与字号视觉与改造前一致（D-06-② 根字号缩放除外）。
  - AC-007：Given `src/style.css`，Then `:root` 与 `[data-theme="light"]` 各含 3 个 `--color-media-*` 且值逐字相同。
  - AC-008：Given `DESIGN.md` colors 节，Then 存在三键且值与 CSS token 逐字一致，design.md lint errors 0。
- UI 验收：§8.8 行 3/4 四张截图。
- 保留行为回归：V-002、V-005、`GrasslandWorkbench.test.ts`（只读，V-005 全量内）。

**完成后**：按 §14 报告。

---

## 12. 测试、验证命令与集成验收

### 12.1 需求追踪与验收覆盖

| 需求/不变量 | 实现卡 | 业务/契约条款 | 边界场景 | 验收编号 | 测试编号 | 执行命令/手工步骤 | 证据 |
|---|---|---|---|---|---|---|---|
| REQ-001 | C-01 | §5.1/§5.3、D-02/D-05 | C-01/E01、E13 | AC-001..003 | TC-C01-001..004 | V-001/V-006 | 测试报告+截图 |
| REQ-002 | C-02 | §5.1 下半段、D-01-③ | — | AC-004 | （静态） | V-006/V-008 | grep 输出+截图 |
| REQ-003 | C-03 | D-04 清单 | C-03/E10 | AC-005、AC-006 | TC-C03-R1 | V-002/V-006 | 测试报告+截图 |
| REQ-004 | C-03 | D-03 | — | AC-007、AC-008 | （静态） | V-007/V-006 | lint JSON+grep |

### 12.2 测试用例（逐用例）

#### TC-C01-001：字体栈读 `--font-body` token

| 项目 | 必填内容 |
|---|---|
| 对应条款 | REQ-001/AC-001/D-02 |
| 风险/类别 | 中（新运行时解析逻辑）；正向 |
| 测试层级 | 单元 |
| 实现位置 | `src/views/ai-center/components/cover-text-layout.test.ts` 用例 `字体栈优先读取 --font-body token` |
| 前置数据 | 无 |
| 输入 | `vi.stubGlobal('getComputedStyle', () => ({ getPropertyValue: (k: string) => k === '--font-body' ? "'Inter', 'PingFang SC', sans-serif" : '' }))` |
| 依赖模拟 | 仅 stub `getComputedStyle`；被测逻辑本身不 mock |
| 操作步骤 | 1. stub；2. `canvasFontStack()`；3. `vi.unstubAllGlobals()` |
| 预期展示/响应 | 返回值 === stub 提供的栈串 |
| 预期副作用 | 无 |
| 最终状态 | stub 已还原 |
| 清理 | `afterEach(vi.unstubAllGlobals)` |
| 执行与证据 | V-001 |

#### TC-C01-002：token 读空回退固定栈

| 项目 | 必填内容 |
|---|---|
| 对应条款 | REQ-001/AC-002/C-01/E13 |
| 风险/类别 | 中；边界 |
| 测试层级 | 单元 |
| 实现位置 | 同文件用例 `token 读空时回退与 --font-body 同构的栈` |
| 前置数据 | 无 |
| 输入 | stub `getComputedStyle` 返回 `getPropertyValue: () => ''`（另补一例纯空白 `'  '`） |
| 依赖模拟 | 同上 |
| 操作步骤 | 调 `canvasFontStack()`，断言 === `CANVAS_FONT_FALLBACK`（含 `'Inter'` 与 `'PingFang SC'`） |
| 预期展示/响应 | 回退串，不抛错 |
| 预期副作用 | 无 |
| 最终状态 | 无残留 |
| 清理 | 同上 |
| 执行与证据 | V-001 |

#### TC-C01-003：center-block 排版绘制调用与常量断言

| 项目 | 必填内容 |
|---|---|
| 对应条款 | REQ-001/AC-001/§5.1 |
| 风险/类别 | 高（迁移正确性核心）；正向+回归 |
| 测试层级 | 单元 |
| 实现位置 | 同文件用例 `center-block 用 token 字体与内容常量绘制` |
| 前置数据 | 构造 stub `ctx`：记录 `font/fillStyle/strokeStyle/textAlign` 赋值与 `fillText/strokeText/fillRect` 调用；`createLinearGradient` 返回 `{ addColorStop: vi.fn() }` |
| 输入 | `COVER_TEXT_LAYOUTS` 中 `id==='center-block'` 的 `draw(ctx, 1080, 1920, '标题A1', '副标题b2')` |
| 依赖模拟 | 只 mock canvas 2d 上下文（记录器），不 mock 被测绘制逻辑 |
| 操作步骤 | 1. 取 draw；2. 执行；3. 断言：标题 `font` 以 `bold 60px ` 开头且含 `Inter`；`fillStyle === '#ffffff'`；衬底 `fillStyle` 出现 `'rgba(0,0,0,0.55)'`；副标题 `font` 以 `34px ` 开头；`fillText` 分别以标题/副标题串调用 |
| 预期展示/响应 | 记录器捕获上述全部赋值 |
| 预期副作用 | 无 |
| 最终状态 | 无 |
| 清理 | 无 |
| 执行与证据 | V-001 |

#### TC-C01-004：衬条与渐变遮罩 scrim 常量断言

| 项目 | 必填内容 |
|---|---|
| 对应条款 | REQ-001/§5.1 |
| 风险/类别 | 中；回归 |
| 测试层级 | 单元 |
| 实现位置 | 同文件用例 `top-bar 与 bottom-gradient 的衬底常量` |
| 前置数据 | 同 TC-C01-003 的记录器 ctx |
| 输入 | `top-bar` 与 `bottom-gradient` 两套 `draw(ctx, 1080, 1920, 'T', 'S')` |
| 依赖模拟 | 同上 |
| 操作步骤 | 断言 top-bar 衬底 `fillStyle === 'rgba(20,16,38,0.72)'` 且 `fillRect(0, 0, 1080, 170)`；bottom-gradient 的 `addColorStop` 收到 `('rgba(0,0,0,0)')` 与 `('rgba(0,0,0,0.62)')` |
| 预期展示/响应 | 常量与 §5.1 逐字一致 |
| 预期副作用 | 无 |
| 最终状态 | 无 |
| 清理 | 无 |
| 执行与证据 | V-001 |

#### TC-C03-R1：核销卡既有全部用例回归（含扫码流）

| 项目 | 必填内容 |
|---|---|
| 对应条款 | REQ-003/AC-005、AC-006 |
| 风险/类别 | 高；回归 |
| 测试层级 | 组件 |
| 实现位置 | `src/components/MerchantCommerceCard.test.ts`（零改动原样运行） |
| 前置数据 | 既有 fetch/MediaStream/BarcodeDetector mock（文件内已备） |
| 输入 | 既有用例输入 |
| 依赖模拟 | 既有文件自带的全部 mock |
| 操作步骤 | `npm run test -- src/components/MerchantCommerceCard.test.ts` |
| 预期展示/响应 | 全部既有用例通过（含「识别摄像头二维码后直接调用核销接口并关闭视频流」） |
| 预期副作用 | 无 |
| 最终状态 | 无 |
| 清理 | 无 |
| 执行与证据 | V-002 |

### 12.3 本任务验证清单

| 验证编号 | 适用卡/阶段 | 工作目录与 shell | 精确命令或手工步骤 | 前置环境/副作用 | 必需性 | 通过标准 | 证据路径 |
|---|---|---|---|---|---|---|---|
| V-001 | C-01 | 仓库根/zsh | `npm run test -- src/views/ai-center/components/cover-text-layout.test.ts` | 已装依赖 | 必需 | 退出码 0、4 用例全过（非零用例） | 终端输出摘要 |
| V-002 | C-03 | 仓库根 | `npm run test -- src/components/MerchantCommerceCard.test.ts` | 同上 | 必需 | 退出码 0、既有用例数不变全绿 | 同上 |
| V-003 | 全部卡 | 仓库根 | `npm run typecheck` | 同上 | 必需 | 退出码 0 | 同上 |
| V-004 | 全部卡 | 仓库根 | `npm run lint` | 同上 | 必需 | 退出码 0；警告数 ≤ 2（既有 any 基线，不得新增） | 同上 |
| V-005 | 集成 | 仓库根 | `npm run test` | 同上 | 必需 | 退出码 0、全量用例绿 | 同上 |
| V-006 | C-01/02/03 | 仓库根 | 静态检查组（负向检查期望退出码 1 或精确计数）：①`grep -rn "system-ui" src/views/ai-center/components/VideoStudioView.vue src/views/ai-center/components/cover-text-layout.ts` 期望无输出；②`grep -cE "#[0-9a-fA-F]{3,8}\|rgba?\(" src/views/ai-center/components/VideoStudioView.vue` = 0；③同命令对 `src/components/MerchantCommerceCard.vue` = 0（另查 `color: white` 计数 0）；④对 `src/views/ai-center/components/cover-text-layout.ts` = 8；⑤对 `src/views/ai-center/components/ImageStudioView.vue` = 3；⑥`grep -c "color: white\|: #111" src/components/MerchantCommerceCard.vue` = 0 | 无 | 必需 | 计数/输出与上列完全一致；命令执行错误（文件不存在）不算通过 | 报告粘贴计数 |
| V-007 | C-03 | 仓库根 | `npx --yes @google/design.md lint DESIGN.md` | npx 联网现拉工具（AGENTS.md 校验节既定） | 必需 | errors 0；warnings ≤ 14（基线 11 + 新键 3 类） | lint JSON 摘要 |
| V-008 | 全部卡 | 仓库根 | 项目 Playwright 无头脚本按 §8.8 表拍 8 张截图并人工查看 | 本地 compose 栈 + e2e-seed 账号 + Chromium（scanning 态加 `--use-fake-device-for-media-stream`） | 必需 | 8 张 PNG 齐备、双主题、人工查看无回归 | `test-artifacts/task89-screenshots/` |
| V-009 | 集成 | 仓库根 | `npm run build` | 已装依赖 | 必需 | 退出码 0（vue-tsc + 三入口构建） | 终端摘要 |

- 卡级先跑 V-001/V-002，再做影响面回归 V-003-V-005；集成按 V-006-V-009 收口。
- 检索型负向检查期望退出码 1（无匹配）已在表内注明；命令报错 ≠ 无匹配。
- 已知基线：lint 2 个既有 any 警告（§1.5，不阻塞、不得新增）；除此之外无已知失败。

### 12.4 当前仓库命令目录

按模板 §12.4 原文执行；本任务选入 §12.3 的命令均已在作者环境核过脚本名与可用性（typecheck/lint/design.md lint 于 2026-09-06 实测）。

### 12.5 最终集成验收与完成定义

- 集成验收负责人：ZCode（规划模型）复核；用户最终验收。
- 前置条件：三卡卡级验证全过；无 BLOCKED。
- 集成清单：V-001～V-009；串联流程：用户端 `/creation` 视频/图像 tab + 商家工作台核销卡静态与 scanning 态，双主题。
- 保留行为：三组件外观（D-06 两项微差除外）、交互、既有测试、公开契约、权限、数据、主题切换行为全部保持。
- 证据：命令日志、计数输出、8 张双主题截图、design.md lint JSON。
- 变更范围：新增 diff 与 §9.1 及三卡写入集合一致；两个任务前未跟踪项原样保留。
- 交付状态：全部必需验收通过才算完成；截图缺失只能记「已实现，未完成验收」。

### 12.6 发布与回滚

N/A：无部署变化（纯前端样式收口，随前端常规发版生效；回滚即回退前端版本，无数据/配置回滚面）。

---

## 13. 阻塞规则

按模板 §13 原文执行。本任务特定阻塞编号预留：

- **B-01**：V-007 lint 对 `media-*` 三键报 error 且原因是键名/值格式不被工具接受——执行者 MUST NOT 自行改 token 值或删键迁就工具，报告后由负责人决定规范登记措辞。
- **B-02**：本地栈/e2e-seed 无法启动导致 V-008 截图无法执行——按 §13.2 报告 NOT_RUN 与原因，不得拿旧图或单主题冒充；其余已可执行验收不受影响时仅阻塞 V-008 相关收尾。
- **B-03**：发现白名单文件已被他人改动且无法安全合并——按模板 §13.1-9 报告。

---

## 14. 完成报告格式

按模板 §14 原文结构输出（实现结果/文件与范围/验证证据/偏差说明/未解决问题），每卡完成后各一份，集成验收另出总报告。UI 证据必须附 §8.8 表规定的截图路径、视口、主题与人工查看结论。

---

## 附 A：返工卡格式

按模板附 A 原文执行。

---

## 附 B：强模型写作规约与发布前检查

### B.2 发布前检查表（全部勾完才置 READY_FOR_IMPLEMENTATION）

- [x] 唯一实现方案：三类着色域分类法 + D-02～D-06 唯一方案，无二选一
- [x] 范围内/范围外/不许顺手修 三段齐全
- [x] 文件白名单（7 写入 + 4 只读 + 1 生成物目录）+ 黑名单已列
- [x] §2.5 当前行为对照真实代码核实（行号基于基线 SHA）
- [x] 成功/失败/空数据/加载：N/A 已给理由（零行为面任务，D-06 微差单列）
- [x] 重复提交与并发：E03/E11/E15 N/A 理由写明（无请求语义）
- [x] 接口字段/错误码：N/A（零接口改动，既有测试回归锁定）
- [x] 数据兼容与迁移：N/A
- [x] UI 入口/组件/交互/响应式/明暗主题：§8 全填，双主题截图 8 张定死
- [x] 每卡边界场景表无空行（C-02 以合并行+模板口径逐行说明）
- [x] 关键行为有 Given/When/Then 与自动化（AC-001..008 全映射 TC/V）
- [x] 验收可客观判断（grep 计数、退出码、lint errors、截图清单）
- [x] 无占位符/未决选项/推迟决策
- [x] BLOCKED 条件已定义（B-01～B-03）
- [x] 命令/目录/工具链/退出码已核实；作者未执行项（npm run test 全量、截图）如实标注由执行者执行
- [x] 每卡最小锚点与真实路径/符号齐全
- [x] 卡间依赖与顺序明确（三卡可并行、默认串行、C-03 独占全局文件）
- [x] 写入集合全为全局子集；只读/生成物/已有改动分开
- [x] 无未安排的共享写入冲突
- [x] §9.5 每项约束已映射或 N/A
- [x] 三入口现状、token 事实只保留已核实项
- [x] 需求→规则→卡→AC→TC→V→证据无悬空编号
- [x] 集成负责人、门禁、发布边界明确（§12.5/§12.6）
- [x] 新依赖/迁移/计费副作用：无（明确登记「无」）

反向审阅（防「满足文字违反目标」）：①执行者若把导出内容色硬套主题 token——被 D-01/§0 强调 1 与 V-006 计数（cover 模块恰 8 处常量）双重拦截；②若只改 TS 不动 commerce 卡——V-006 ③⑥ 计数不为 0；③若拿静态图冒充 scanning 态——§8.8 明文禁止 + B-02；④若删测试换绿——R-SAFE/§0.2-7 红线。

### B.3 作者审阅与版本记录

| 修订版本 | 日期 | 变化原因与决策人 | 受影响条款/卡 | 是否重做发布检查 |
|---|---|---|---|---|
| v1.0 | 2026-09-06 | 初版成书（ZCode 规划，源自用户 2026-09-06 审计反馈三处违规清单） | 全文 | 是（见 B.2） |

---

## 附 C：随任务书交给弱模型的执行提示词

```text
任务书路径：/Users/LXH/claude/y-1/docs/任务书/草场任务书-89-存量token违规清偿三组件.md
批准版本：v1.0
本次指定任务卡：C-01 → C-02 → C-03（AUTO_CHAIN，按序全部执行）

请按任务书实现指定卡，不负责重新定义需求。

1. 先遵守适用的系统/开发者/用户指令与 AGENTS.md，再按任务书阅读协议读 §0、§1、§9、§13、§14、当前卡及其引用；本任务全部为 UI 卡，必读根 DESIGN.md 与任务书 §8。
2. 确认文档 READY_FOR_IMPLEMENTATION、版本一致、基线无未声明漂移，记录当前已修改/未跟踪文件。
3. 输出简短计划，逐项对应卡内步骤。只写白名单交集；黑名单优先。
4. 保留他人已有改动；不做无关重构；可按本地工作流提交，提交不等于验收。严禁 git reset / git clean / 删除无关文件。
5. 按已定决策实现：导出内容色用命名常量刻意不接主题 token（D-01-③）；字体家族从 --font-body 解析（D-02）；媒体三 token 暗亮同值（D-03）；尺寸仅精确匹配置换（D-04）。MUST NOT 发明新 token、MUST NOT 改任何常量值。
6. 只有遇到 B-01～B-03 或模板 §13.1 情形才阻塞报告；本地命令、浏览器、本地栈、npx 拉取 lint 工具可直接执行并记录。
7. 真实执行卡内 TC/V 与回归；未运行写 NOT_RUN，不写 PASS。
8. UI 改动按 §8.8 拍 8 张双主题截图并人工查看；scanning 态用 --use-fake-device-for-media-stream，失败按 B-02 报告。
9. 按 §14 报告变更、命令/目录/退出码、脱敏证据、偏差与未完成项。不得删测试/放宽断言/降低阈值换绿灯。
10. 当前卡 DoD、TC、V 全部真实通过后自动进入下一卡；三卡全完成后由 §12.5 负责人做集成验收。
```
