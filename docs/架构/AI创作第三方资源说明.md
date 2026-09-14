# AI 创作第三方资源说明

> 来源：任务书 [#101](../任务书/草场任务书-101-AI创作工作流与图文交付升级.md) C101-16。核对日期：2026-09-15。
> 范围：图文工作台的来源解析、排版渲染与预览依赖；不是所有 AI 供应商或全仓依赖清单。下列库经构建依赖打包，不表示第三方源码已复制入库。

## 实际依赖

| 依赖 | 当前版本 | 许可证 | 实际使用范围 |
|---|---|---|---|
| `org.commonmark:commonmark` | 0.24.0 | BSD-2-Clause | Markdown AST 解析、`HtmlRenderer` 和 `MarkdownRenderer`；来源解析与排版共用 CommonMark 语义 |
| `org.commonmark:commonmark-ext-gfm-tables` | 0.24.0 | BSD-2-Clause | GFM 表格解析与渲染扩展 |
| `org.jsoup:jsoup` | 1.21.1 | MIT | HTML 片段处理、白名单净化、主题样式装配与文字保留检查 |
| `dompurify` | 3.4.13（锁定版本） | MPL-2.0 OR Apache-2.0 | 前端预览 HTML 净化；服务端不依赖 |

Java 版本声明在 [Intelligence build.gradle.kts](../../platform-java/services/intelligence-service/build.gradle.kts)，前端实际锁定版本见 [package-lock.json](../../package-lock.json)。依赖更新时同步核对版本、许可证和使用方式，不从旧任务报告推断当前版本。

## 渲染与净化链路

[CreationDocumentRenderer](../../platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/creationstudio/render/CreationDocumentRenderer.java) 当前按以下顺序工作：

1. CommonMark 解析 AST；使用 `HtmlRenderer` 的 `escapeHtml(true)` 和 `sanitizeUrls(true)`，并用 `MarkdownRenderer` 输出 Markdown。
2. 原始 HTML 块和内联 HTML **转义为文字保留**，不执行标签或脚本；不是旧说明中的“一律丢弃”。
3. 已绑定媒体用稳定 `mediaId` 转成受控 `/api/media/{id}` 引用；外部图片及失效绑定转为待绑定提示，渲染器不抓取远程图片。
4. 可选将安全外链列为参考链接，应用固定主题，再由 jsoup `Cleaner/Safelist` 白名单净化。
5. 校验净化前后正文文字，并单独校验代码块内容；不一致以 `STUDIO_RENDER_TEXT_MISMATCH` 拒绝输出。
6. 前端 [sanitizeArticleHtml](../../src/views/article/composables/useArticleRender.ts) 再经 DOMPurify、元素/属性及样式约束处理，最后由 [ArticleFormatPanel](../../src/views/article/components/ArticleFormatPanel.vue) 插入只读预览容器。

“自建”指媒体绑定、主题、净化规则、版本检查和导出编排；不再表述为完全替代 CommonMark HTML 渲染器的自研访问器。

## 主题与运行边界

[CreationRenderTheme](../../platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/creationstudio/render/CreationRenderTheme.java) 提供 `standard`、`compact` 两个固定主题，输出内联样式，使用 Inter 与系统回退字体，不请求外部字体 CDN。导出内容的排版与应用壳的主题分别管理。

该服务端排版链路不引入 Bun、Node 业务服务、jsdom 或无头浏览器执行任意 HTML。项目其他媒体能力的 Java Playwright driver 不属于此渲染链路。前端确实会插入净化后的 HTML，不能把这点写成“不使用 innerHTML”。

## 版本锚点

| 内容 | 当前标识与来源 |
|---|---|
| 视觉计划提示词 | `visual-plan-prompts-1.0.0`，见 [VisualPlanPrompts](../../platform-java/services/intelligence-service/src/main/java/com/grassland/intelligence/creationstudio/plan/VisualPlanPrompts.java)；沿用 CardSeriesPrompts 文字策略 |
| 文章配图/封面提示词 | `article-visual-prompts-1.0.0`，同上 |
| 排版渲染 | `creation-render-1.1.0`，由 `CreationDocumentRenderer.RENDER_VERSION` 写入 `RenderPreview.renderVersion` |
| 模板目录 | [contracts/creation-recipes.v1.json](../../contracts/creation-recipes.v1.json)，前后端单源契约 |

第三方库版本、提示词版本、渲染版本和草稿业务版本是不同维度。更新任一项时应保持来源可追溯，并核对预览、文本、Markdown 与导出行为是否一致。
