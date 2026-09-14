package com.grassland.intelligence.creationstudio.render;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.markdown.MarkdownRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/**
 * One AST for text, controlled HTML and Markdown. No network access or
 * executable source HTML.
 */
final class CreationDocumentRenderer {
	static final String RENDER_VERSION = "creation-render-1.1.0";
	private static final List<org.commonmark.Extension> EXTENSIONS = List.of(TablesExtension.create());
	private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
	private static final HtmlRenderer HTML = HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true)
			.sanitizeUrls(true).build();
	private static final MarkdownRenderer MARKDOWN = MarkdownRenderer.builder().extensions(EXTENSIONS).build();
	private static final Safelist SAFE = new Safelist()
			.addTags("section", "h1", "h2", "h3", "h4", "p", "span", "strong", "em", "ul", "ol", "li", "blockquote",
					"table", "thead", "tbody", "tr", "th", "td", "pre", "code", "a", "img", "br", "hr")
			.addAttributes(":all", "class", "style", "data-render", "data-render-note", "data-media-id")
			.addAttributes("a", "href", "title", "rel").addAttributes("img", "src", "alt").addAttributes("ol", "start")
			.addAttributes("th", "colspan", "rowspan").addAttributes("td", "colspan", "rowspan")
			.addProtocols("a", "href", "http", "https").preserveRelativeLinks(true);

	private CreationDocumentRenderer() {
	}

	record BoundMedia(String mediaId, String caption, String afterText, boolean cover, int position,
			Integer afterBlockPosition, boolean stale) {
		BoundMedia(String mediaId, String caption, String afterText, boolean cover, int position) {
			this(mediaId, caption, afterText, cover, position, null, afterText != null);
		}
	}
	record Rendered(String html, String text, String markdown, List<String> warnings, List<String> unresolvedMediaIds) {
	}

	static Rendered render(String markdown, String title, boolean includeTitle, boolean citeExternalLinks,
			CreationRenderTheme theme, List<BoundMedia> media) {
		Node document = PARSER.parse(markdown == null ? "" : markdown);
		List<String> warnings = new ArrayList<>();
		Set<String> unresolved = new LinkedHashSet<>();
		Map<String, BoundMedia> byId = new LinkedHashMap<>();
		media.forEach(item -> byId.putIfAbsent(item.mediaId(), item));
		Set<String> inlineIds = new LinkedHashSet<>();
		document.accept(new AbstractVisitor() {
			@Override
			public void visit(Image image) {
				if (image.getDestination().startsWith("media:"))
					inlineIds.add(image.getDestination().substring(6));
			}
			@Override
			public void visit(HtmlBlock html) {
				warnings.add("原始 HTML 已按文字保留，不执行其中的标签或脚本");
			}
			@Override
			public void visit(HtmlInline html) {
				warnings.add("内联 HTML 已按文字保留");
			}
		});
		Element root = new Element("section").addClass("creation-render").addClass(
				theme == CreationRenderTheme.COMPACT ? "creation-render-compact" : "creation-render-standard");
		if (includeTitle && title != null && !title.isBlank())
			root.appendElement("h1").text(title);
		List<BoundMedia> pending = new ArrayList<>(byId.values());
		StringBuilder outputMarkdown = new StringBuilder();
		if (includeTitle && title != null && !title.isBlank())
			outputMarkdown.append("# ").append(title).append("\n\n");
		for (BoundMedia item : List.copyOf(pending)) {
			if (item.cover() && !inlineIds.contains(item.mediaId())) {
				root.appendChild(figure(item, null));
				outputMarkdown.append(imageMarkdown(item));
				pending.remove(item);
			}
		}

		int position = 0;
		for (Node block = document.getFirstChild(); block != null; block = block.getNext()) {
			position++;
			var fragment = Jsoup.parseBodyFragment(HTML.render(block));
			fragment.outputSettings().prettyPrint(false);
			for (Element image : List.copyOf(fragment.select("img"))) {
				String destination = image.attr("src");
				String id = destination.startsWith("media:") ? destination.substring(6) : null;
				if (id != null && byId.containsKey(id) && !byId.get(id).stale()) {
					image.attr("src", "/api/media/" + id).attr("data-render", "media").attr("data-media-id", id);
					pending.remove(byId.get(id));
				} else {
					unresolved.add(id == null ? destination : id);
					String label = image.attr("alt");
					image.replaceWith(new Element("span").attr("data-render", "external-image")
							.addClass("render-unbound").text("图片「" + label + "」待绑定"));
					warnings.add("图片「" + label + "」尚未绑定可用素材");
				}
			}
			for (org.jsoup.nodes.Node child : List.copyOf(fragment.body().childNodes()))
				root.appendChild(child);
			outputMarkdown.append(MARKDOWN.render(block)).append("\n");
			final int blockPosition = position;
			for (BoundMedia item : List.copyOf(pending)) {
				if (!item.stale() && !inlineIds.contains(item.mediaId())
						&& Integer.valueOf(blockPosition).equals(item.afterBlockPosition())) {
					root.appendChild(figure(item, null));
					outputMarkdown.append(imageMarkdown(item));
					pending.remove(item);
				}
			}
		}
		for (BoundMedia item : pending) {
			if (item.stale() || item.afterBlockPosition() != null) {
				unresolved.add(item.mediaId());
				root.appendElement("p").attr("data-render", "media").addClass("render-unbound").text("配图位置已变化，请重新核对");
				warnings.add("媒体 " + item.mediaId() + " 的段落位置已过期");
			} else if (!inlineIds.contains(item.mediaId())) {
				root.appendChild(figure(item, "未绑定段落"));
				outputMarkdown.append(imageMarkdown(item));
				warnings.add("媒体 " + item.mediaId() + " 未绑定段落，已按原有顺序附于文后");
			}
		}

		if (citeExternalLinks) {
			Map<String, Integer> links = new LinkedHashMap<>();
			for (Element link : root.select("a[href]")) {
				String href = link.attr("href");
				if (!safeLink(href))
					continue;
				int number = links.computeIfAbsent(href, ignored -> links.size() + 1);
				link.after(new Element("span").attr("data-render", "ref").text("[" + number + "]"));
			}
			if (!links.isEmpty()) {
				Element references = root.appendElement("section").attr("data-render", "references")
						.addClass("render-refs");
				references.appendElement("strong").text("参考链接");
				Element list = references.appendElement("ol");
				links.keySet().forEach(href -> list.appendElement("li").text(href));
			}
		}
		root.select("h5,h6").forEach(element -> element.tagName("h4"));
		root.select("a").forEach(link -> {
			if (!safeLink(link.attr("href")))
				link.removeAttr("href");
			link.attr("rel", "noopener nofollow");
		});
		theme.applyTo(root);
		var raw = org.jsoup.nodes.Document.createShell("");
		raw.outputSettings().prettyPrint(false);
		raw.body().appendChild(root);
		var clean = new Cleaner(SAFE).clean(raw);
		clean.outputSettings().prettyPrint(false);

		// Verify source text survived transforms and sanitization. Never log private
		// document content.
		var actual = clean.clone();
		actual.select("[data-render=media],[data-render=references],[data-render=external-image],[data-render=ref]")
				.remove();
		var expected = Jsoup.parseBodyFragment(HTML.render(document));
		if (includeTitle && title != null && !title.isBlank())
			expected.body().prependChild(new Element("h1").text(title));
		if (!normalizeWhitespace(expected.wholeText()).equals(normalizeWhitespace(actual.wholeText()))) {
			throw new IntelligenceException(500, "STUDIO_RENDER_TEXT_MISMATCH", "排版文字保留检查失败，已拒绝输出");
		}
		List<String> expectedCode = expected.select("pre code").stream().map(Element::wholeText).toList();
		List<String> actualCode = actual.select("pre code").stream().map(Element::wholeText).toList();
		if (!expectedCode.equals(actualCode)) {
			throw new IntelligenceException(500, "STUDIO_RENDER_TEXT_MISMATCH", "代码块文字保留检查失败");
		}
		var textDocument = expected.clone();
		textDocument.select("a[href]").forEach(link -> {
			if (safeLink(link.attr("href")))
				link.appendChild(new TextNode(" (" + link.attr("href") + ")"));
		});
		String text = textDocument.wholeText();
		return new Rendered(clean.body().html(), text, outputMarkdown.toString(), warnings.stream().distinct().toList(),
				List.copyOf(unresolved));
	}

	static String rewriteMarkdownMedia(String markdown, Map<String, String> paths) {
		Node document = PARSER.parse(markdown);
		document.accept(new AbstractVisitor() {
			@Override
			public void visit(Image image) {
				String id = image.getDestination().startsWith("media:") ? image.getDestination().substring(6) : "";
				if (paths.containsKey(id))
					image.setDestination(paths.get(id));
			}
		});
		return MARKDOWN.render(document);
	}

	static Set<String> inlineMediaIds(String markdown) {
		Set<String> ids = new LinkedHashSet<>();
		PARSER.parse(markdown == null ? "" : markdown).accept(new AbstractVisitor() {
			@Override
			public void visit(Image image) {
				if (image.getDestination().startsWith("media:"))
					ids.add(image.getDestination().substring(6));
			}
		});
		return ids;
	}

	private static Element figure(BoundMedia item, String note) {
		Element figure = new Element("section").attr("data-render", "media").addClass("render-media");
		if (note != null)
			figure.attr("data-render-note", note);
		figure.appendElement("img").attr("src", "/api/media/" + item.mediaId()).attr("data-media-id", item.mediaId())
				.attr("alt", item.caption() == null ? "配图" : item.caption());
		if (item.caption() != null && !item.caption().isBlank())
			figure.appendElement("p").text(item.caption());
		if (note != null)
			figure.appendElement("p").addClass("render-unbound").text(note);
		return figure;
	}
	private static String imageMarkdown(BoundMedia item) {
		Image image = new Image("media:" + item.mediaId(), "");
		image.appendChild(new org.commonmark.node.Text(item.caption() == null ? "配图" : item.caption()));
		return "\n" + MARKDOWN.render(image) + "\n\n";
	}
	private static boolean safeLink(String url) {
		try {
			java.net.URI uri = java.net.URI.create(url);
			return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
					&& uri.getHost() != null && uri.getUserInfo() == null;
		} catch (Exception invalid) {
			return false;
		}
	}
	static String normalizeWhitespace(String text) {
		return text.replaceAll("\\s+", " ").strip();
	}
}
