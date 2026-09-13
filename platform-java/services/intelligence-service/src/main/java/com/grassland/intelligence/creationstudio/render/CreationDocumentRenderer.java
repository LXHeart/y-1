package com.grassland.intelligence.creationstudio.render;

import com.grassland.intelligence.security.IntelligenceException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * 任务书 #101 C101-16（§6.7）：确定性排版渲染器。 同一 AST 构建
 * HTML／纯文本；输出只有受控元素（标题/段落/列表/引用/表格/代码/ 图片占位/链接），原 HTML
 * 一律丢弃（不转义输出），外部图片不抓取（占位提示），链接按请求转引用或保留（rel 安全属性）。 文字 token 比对先于返回：HTML 提取文本与
 * AST 提取文本不一致即拒绝（渲染永不改写用户文字）。
 */
final class CreationDocumentRenderer {

	static final String RENDER_VERSION = "creation-render-1.0.0";

	private CreationDocumentRenderer() {
	}

	/** 绑定媒体：afterText 为段落锚文本（空=无法定位，附文后并标注）。 */
	record BoundMedia(String mediaId, String caption, String afterText, boolean cover, int position) {
	}

	record Rendered(String html, String text, List<String> warnings) {
	}

	private static final Parser PARSER = Parser.builder().extensions(List.of(TablesExtension.create())).build();

	static Rendered render(String markdown, String title, boolean includeTitle, boolean citeExternalLinks,
			CreationRenderTheme theme, List<BoundMedia> media) {
		Node document = PARSER.parse(markdown == null ? "" : markdown);
		List<String> warnings = new ArrayList<>();

		StringBuilder html = new StringBuilder();
		html.append("<style>").append(theme.css()).append("</style>");
		html.append("<div class=\"creation-render creation-render-")
				.append(theme == CreationRenderTheme.COMPACT ? "compact" : "standard").append("\">");
		if (includeTitle && title != null && !title.isBlank()) {
			html.append("<h1 data-render=\"title\">").append(escape(title)).append("</h1>");
		}
		// 封面媒体置于最前（§6.7 封面结构）
		for (BoundMedia item : media) {
			if (item.cover()) {
				html.append(figureOf(item, null));
			}
		}

		// 引用编号（citeExternalLinks）：外部链接转脚注
		Map<String, Integer> referenceNumbers = new LinkedHashMap<>();
		List<String> references = new ArrayList<>();
		List<BoundMedia> pending = new ArrayList<>(media.stream().filter(item -> !item.cover()).toList());

		for (Node block = document.getFirstChild(); block != null; block = block.getNext()) {
			renderBlock(html, block, citeExternalLinks, referenceNumbers, references);
			// 段落锚定：该块文本与绑定 afterText 一致 → 图挂其后（按 position 顺序）
			String blockText = blockTextOf(block).strip();
			List<BoundMedia> anchored = pending.stream()
					.filter(item -> item.afterText() != null && !item.afterText().isBlank()
							&& item.afterText().strip().equals(blockText))
					.sorted(java.util.Comparator.comparingInt(BoundMedia::position)).toList();
			for (BoundMedia item : anchored) {
				html.append(figureOf(item, null));
				pending.remove(item);
			}
		}
		// 未绑定媒体：按原顺序附于文后并明确标注（§6.7 旧稿无精确位置）
		for (BoundMedia item : pending) {
			html.append(figureOf(item, "未绑定段落"));
			warnings.add("媒体 " + item.mediaId() + " 未绑定段落，已按原顺序附于文后");
		}
		if (citeExternalLinks && !references.isEmpty()) {
			html.append("<div class=\"render-refs\" data-render=\"references\"><strong>参考链接</strong><ol>");
			for (String reference : references) {
				html.append("<li>").append(escape(reference)).append("</li>");
			}
			html.append("</ol></div>");
		}
		html.append("</div>");
		String rawHtml = html.toString();

		// 文字保留检查（先于返回）：剔除渲染装置（绑定图/引用表/外图占位）后的可见文本
		// 必须与 AST 文本一致——排版永不改写用户文字。
		String astText = normalizeWhitespace(astText(document, title, includeTitle));
		org.jsoup.nodes.Document parsed = Jsoup.parse(rawHtml);
		parsed.select("[data-render=media],[data-render=references],[data-render=external-image],[data-render=ref]")
				.remove();
		String htmlText = normalizeWhitespace(parsed.wholeText().replace("\u00a0", " "));
		if (!astText.equals(htmlText)) {
			throw new IntelligenceException(500, "STUDIO_RENDER_TEXT_MISMATCH", "排版文字保留检查失败，已拒绝输出");
		}
		return new Rendered(rawHtml, astText, List.copyOf(warnings));
	}

	// ---- 块级渲染 ----

	private static void renderBlock(StringBuilder html, Node block, boolean citeExternalLinks,
			Map<String, Integer> referenceNumbers, List<String> references) {
		if (block instanceof Heading heading) {
			html.append("<h").append(Math.min(heading.getLevel() + 1, 6)).append(">");
			renderInlines(html, heading, citeExternalLinks, referenceNumbers, references);
			html.append("</h").append(Math.min(heading.getLevel() + 1, 6)).append(">");
		} else if (block instanceof Paragraph paragraph) {
			html.append("<p>");
			renderInlines(html, paragraph, citeExternalLinks, referenceNumbers, references);
			html.append("</p>");
		} else if (block instanceof BulletList list) {
			html.append("<ul>");
			for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
				html.append("<li>");
				renderChildren(html, item, citeExternalLinks, referenceNumbers, references);
				html.append("</li>");
			}
			html.append("</ul>");
		} else if (block instanceof OrderedList list) {
			html.append("<ol");
			if (list.getStartNumber() != 1) {
				html.append(" start=\"").append(list.getStartNumber()).append("\"");
			}
			html.append(">");
			for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
				html.append("<li>");
				renderChildren(html, item, citeExternalLinks, referenceNumbers, references);
				html.append("</li>");
			}
			html.append("</ol>");
		} else if (block instanceof BlockQuote quote) {
			html.append("<blockquote>");
			for (Node child = quote.getFirstChild(); child != null; child = child.getNext()) {
				renderBlock(html, child, citeExternalLinks, referenceNumbers, references);
			}
			html.append("</blockquote>");
		} else if (block instanceof FencedCodeBlock code) {
			html.append("<pre data-render=\"code\"><code>");
			html.append(escape(code.getLiteral()));
			html.append("</code></pre>");
		} else if (block instanceof org.commonmark.node.IndentedCodeBlock code) {
			html.append("<pre data-render=\"code\"><code>");
			html.append(escape(code.getLiteral()));
			html.append("</code></pre>");
		} else if (block instanceof TableBlock table) {
			renderTable(html, table, citeExternalLinks, referenceNumbers, references);
		} else if (block instanceof ThematicBreak) {
			html.append("<hr>");
		} else if (block instanceof HtmlBlock) {
			// 原 HTML 一律丢弃（受控输出；§6.7 注入防护）
		} else {
			renderChildren(html, block, citeExternalLinks, referenceNumbers, references);
		}
	}

	private static void renderTable(StringBuilder html, TableBlock table, boolean citeExternalLinks,
			Map<String, Integer> referenceNumbers, List<String> references) {
		html.append("<table data-render=\"table\">");
		// GFM 结构：TableBlock > (TableHead | TableBody) > TableRow > TableCell
		for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
			boolean header = section instanceof org.commonmark.ext.gfm.tables.TableHead;
			for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
				html.append("<tr>");
				for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
					String tag = header ? "th" : "td";
					html.append("<").append(tag).append(">");
					renderChildren(html, cell, citeExternalLinks, referenceNumbers, references);
					html.append("</").append(tag).append(">");
				}
				html.append("</tr>");
			}
		}
		html.append("</table>");
	}

	private static void renderChildren(StringBuilder html, Node parent, boolean citeExternalLinks,
			Map<String, Integer> referenceNumbers, List<String> references) {
		for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
			if (child instanceof Text text) {
				html.append(escape(text.getLiteral()));
			} else if (child instanceof Code code) {
				html.append("<code>").append(escape(code.getLiteral())).append("</code>");
			} else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
				html.append('\n');
			} else if (child instanceof Emphasis emphasis) {
				html.append("<em>");
				renderChildren(html, emphasis, citeExternalLinks, referenceNumbers, references);
				html.append("</em>");
			} else if (child instanceof StrongEmphasis strong) {
				html.append("<strong>");
				renderChildren(html, strong, citeExternalLinks, referenceNumbers, references);
				html.append("</strong>");
			} else if (child instanceof Link link) {
				renderLink(html, link, citeExternalLinks, referenceNumbers, references);
			} else if (child instanceof Image image) {
				renderImagePlaceholder(html, image);
			} else if (child instanceof HtmlInline) {
				// 内联 HTML 丢弃（转义防护）
			} else {
				renderChildren(html, child, citeExternalLinks, referenceNumbers, references);
			}
		}
	}

	private static void renderInlines(StringBuilder html, Node parent, boolean citeExternalLinks,
			Map<String, Integer> referenceNumbers, List<String> references) {
		renderChildren(html, parent, citeExternalLinks, referenceNumbers, references);
	}

	private static void renderLink(StringBuilder html, Link link, boolean citeExternalLinks,
			Map<String, Integer> referenceNumbers, List<String> references) {
		String url = link.getDestination() == null ? "" : link.getDestination();
		StringBuilder label = new StringBuilder();
		collectText(link, label);
		if (citeExternalLinks && isExternal(url)) {
			int number = referenceNumbers.computeIfAbsent(url, key -> referenceNumbers.size() + 1);
			while (references.size() < number) {
				references.add("");
			}
			references.set(number - 1, url);
			html.append(escape(label.toString())).append("<sup data-render=\"ref\">[").append(number).append("]</sup>");
			return;
		}
		html.append("<a href=\"").append(escape(url)).append("\" rel=\"noopener nofollow\">");
		html.append(escape(label.toString()));
		html.append("</a>");
	}

	/** 外部图片不抓取：占位提示（域名可读；相对路径保留为占位）。 */
	private static void renderImagePlaceholder(StringBuilder html, Image image) {
		String url = image.getDestination() == null ? "" : image.getDestination();
		StringBuilder alt = new StringBuilder();
		collectText(image, alt);
		String host = hostOf(url);
		html.append("<span class=\"render-unbound\" data-render=\"external-image\" role=\"img\" aria-label=\"")
				.append(escape(alt.toString())).append("\">外部图片").append(host.isEmpty() ? "" : "（" + escape(host) + "）")
				.append("——需先入库绑定后才会出现在成品中</span>");
	}

	private static String figureOf(BoundMedia item, String note) {
		StringBuilder figure = new StringBuilder("<figure data-render=\"media\"");
		if (note != null) {
			figure.append(" data-render-note=\"").append(escape(note)).append("\"");
		}
		figure.append("><img src=\"/api/media/").append(escape(item.mediaId())).append("\" alt=\"")
				.append(escape(item.caption() == null ? "配图" : item.caption())).append("\">");
		if (item.caption() != null && !item.caption().isBlank()) {
			figure.append("<figcaption>").append(escape(item.caption())).append("</figcaption>");
		}
		if (note != null) {
			figure.append("<figcaption class=\"render-unbound\">").append(escape(note)).append("</figcaption>");
		}
		return figure.append("</figure>").toString();
	}

	// ---- 文本提取（AST 与 HTML 双侧） ----

	private static String astText(Node document, String title, boolean includeTitle) {
		StringBuilder text = new StringBuilder();
		if (includeTitle && title != null && !title.isBlank()) {
			text.append(title.strip()).append('\n');
		}
		document.accept(new AbstractVisitor() {
			@Override
			public void visit(Text node) {
				text.append(node.getLiteral());
				visitChildren(node);
			}

			@Override
			public void visit(Code code) {
				text.append(code.getLiteral());
			}

			@Override
			public void visit(FencedCodeBlock code) {
				text.append(code.getLiteral());
			}

			@Override
			public void visit(org.commonmark.node.IndentedCodeBlock code) {
				text.append(code.getLiteral());
			}

			@Override
			public void visit(SoftLineBreak softLineBreak) {
				text.append(' ');
			}

			@Override
			public void visit(HardLineBreak hardLineBreak) {
				text.append(' ');
			}

			@Override
			public void visit(Image image) {
				// 图片 alt 与 HTML 侧外部图占位（比对时剥离）对称——不进文本流
			}

			@Override
			public void visit(HtmlBlock htmlBlock) {
				// 原 HTML 不参与文字比对（已被丢弃）
			}

			@Override
			public void visit(HtmlInline htmlInline) {
			}
		});
		return text.toString();
	}

	private static void collectText(Node node, StringBuilder out) {
		for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
			if (child instanceof Text text) {
				out.append(text.getLiteral());
			} else if (child instanceof Code code) {
				out.append(code.getLiteral());
			} else {
				collectText(child, out);
			}
		}
	}

	private static String blockTextOf(Node block) {
		StringBuilder out = new StringBuilder();
		collectText(block, out);
		return out.toString();
	}

	static String normalizeWhitespace(String text) {
		return text.replaceAll("\\s+", " ").strip();
	}

	private static boolean isExternal(String url) {
		return url.startsWith("http://") || url.startsWith("https://");
	}

	private static String hostOf(String url) {
		try {
			if (!isExternal(url)) {
				return "";
			}
			return URI.create(url).getHost() == null ? "" : URI.create(url).getHost();
		} catch (Exception error) {
			return "";
		}
	}

	private static String escape(String text) {
		return org.jsoup.nodes.Entities.escape(text == null ? "" : text);
	}
}
