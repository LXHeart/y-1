# AI 创作第三方资源说明

> 任务书 #101 C101-16 登记（§6.7）。本文记录创作工作台排版/渲染链路实际依赖的第三方库、
> 版本、许可证与本地化范围——不搬入未使用的代码。

## 实际依赖（均为已入库构建依赖，无运行时外链）

| 依赖 | 版本 | 许可证 | 用途与本地化范围 |
|---|---|---|---|
| org.commonmark:commonmark | 0.24.0 | BSD-2-Clause | Markdown → AST 解析（来源分块 code point 区间、排版渲染共用同一解析器）。仅用核心解析器；不使用其默认 HTML 渲染器（创作渲染自建受控访问器，见下） |
| org.commonmark:commonmark-ext-gfm-tables | 0.24.0 | BSD-2-Clause | GFM 表格扩展（表格为受控输出元素之一） |
| org.jsoup:jsoup | 1.21.1 | MIT | 渲染输出的文字提取（文字保留检查）与既有净化边界复用；不用于放行任意 HTML |
| DOMPurify（前端，npm 锁定版本） | 见 package-lock | Apache-2.0 / MPL-2.0 双许可 | 前端只读预览插入前的净化（C101-17 接线）；服务端不依赖 |

## 自建部分（非第三方代码）

- `creationstudio/render/CreationDocumentRenderer`：自研受控访问器——输出白名单元素
  （标题/段落/列表/引用/表格/代码/图片占位/链接），原文 HTML（HtmlBlock/HtmlInline）一律
  丢弃；外部图片不抓取（占位提示）；链接可按请求转脚注引用。渲染前后做文字 token 比对，
  不一致即拒绝输出（STUDIO_RENDER_TEXT_MISMATCH）。
- `creationstudio/render/CreationRenderTheme`：standard/compact 两个固定主题的内联样式，
  系统字体栈，无外链字体/CDN。

## 明确不引入

- Bun、Node 业务服务、任意 HTML 执行器（jsdom/无头浏览器）不在服务端渲染链路。
- 高危「任意 HTML 直出」路径：渲染输出不经过 innerHTML 信任，前端经 DOMPurify 后插入限定容器。

## 上游版本锚点

- 视觉计划 prompt 本地化来源：CardSeriesPrompts 2026-09-02 文字策略版
  （`visual-plan-prompts-1.0.0`）；文章配图/封面模板为本任务书新增
  （`article-visual-prompts-1.0.0`）。
- 排版渲染版本：`creation-render-1.0.0`（RenderPreview.renderVersion）。
