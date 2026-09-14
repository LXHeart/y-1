package com.grassland.intelligence.creationstudio.wechat;

import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Compare visible text together with image positions; tag/attribute ordering is
 * irrelevant.
 */
final class WechatContentVerifier {
	private WechatContentVerifier() {
	}
	static List<String> tokens(String html) {
		List<String> result = new ArrayList<>();
		StringBuilder text = new StringBuilder();
		visit(Jsoup.parseBodyFragment(html == null ? "" : html).body(), result, text);
		flush(result, text);
		return result;
	}
	private static void visit(Node node, List<String> result, StringBuilder text) {
		if (node instanceof TextNode value) {
			text.append(value.getWholeText());
			return;
		}
		if (node instanceof Element element) {
			if (element.normalName().equals("img")) {
				flush(result, text);
				result.add("image:" + element.attr("src"));
				return;
			}
			if (element.normalName().equals("br") || element.isBlock())
				text.append('\n');
		}
		for (Node child : node.childNodes())
			visit(child, result, text);
		if (node instanceof Element element && element.isBlock())
			text.append('\n');
	}
	private static void flush(List<String> result, StringBuilder text) {
		String normalized = text.toString().replaceAll("[\\s\\u00a0]+", " ").strip();
		if (!normalized.isEmpty())
			result.add("text:" + normalized);
		text.setLength(0);
	}
	static List<String> imageSources(String html) {
		return Jsoup.parseBodyFragment(html == null ? "" : html).select("img").stream().map(img -> img.attr("src"))
				.toList();
	}
}
