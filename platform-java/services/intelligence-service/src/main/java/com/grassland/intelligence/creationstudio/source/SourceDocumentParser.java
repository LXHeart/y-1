package com.grassland.intelligence.creationstudio.source;

import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * 任务书 #101 C101-02（§6.5）：来源解析——纯计算，不抓 URL、不执行 HTML、不访问任何网络。
 *
 * <ul>
 * <li>统一 CRLF/CR 为 LF；plain-text 分支仅转义 Markdown 控制字符以保持字面呈现， markdown
 * 分支保留原标记。</li>
 * <li>CommonMark 启用 source spans（BLOCKS）+ GFM 表格扩展；顶层节点跨度对应 normalizedMarkdown
 * 的 <b>code point</b> 区间 {@code [start,end)}（不是 UTF-16 索引）； 嵌套列表仍属于一个顶层块。</li>
 * <li>块 ID 由 documentId 和块序号确定性生成；重复段落有不同块 ID。</li>
 * <li>contentHash 对完整 normalizedMarkdown 做 SHA-256。</li>
 * </ul>
 */
public final class SourceDocumentParser {

	static final int MAX_CODE_POINTS = 30_000;
	static final int MAX_UTF8_BYTES = 128 * 1024;
	static final int MAX_BLOCKS = 500;

	/** 需要转义以在 Markdown 渲染中保持字面呈现的控制字符（plain-text 分支）。 */
	private static final String ESCAPE_CHARS = "\\`*_{}[]()#+-.!>~|";

	private static final Parser PARSER = Parser.builder().extensions(List.of(TablesExtension.create()))
			.includeSourceSpans(org.commonmark.parser.IncludeSourceSpans.BLOCKS).build();

	private SourceDocumentParser() {
	}

	public record ParsedSource(String rawText, String normalizedMarkdown, String contentHash,
			List<SourceDocument.Block> blocks, List<String> warnings) {
	}

	public static ParsedSource parse(UUID documentId, String kind, String text) {
		if (text == null || text.isBlank()) {
			throw limit("原稿正文不能为空");
		}
		if (text.codePointCount(0, text.length()) > MAX_CODE_POINTS) {
			throw limit("原稿正文超过 " + MAX_CODE_POINTS + " 字符上限");
		}
		if (text.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
			throw limit("原稿正文超过 128KiB 字节上限");
		}
		String lfText = normalizeLineEndings(text);
		String normalizedMarkdown = "plain-text".equals(kind) ? escapeMarkdownControls(lfText) : lfText;
		if (normalizedMarkdown.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
			throw limit("原稿规范化后超过 128KiB 字节上限");
		}

		Node document = PARSER.parse(normalizedMarkdown);
		String source = normalizedMarkdown;
		int[] lineStartUtf16 = lineStarts(source);
		int[] codePointIndex = codePointIndexByUtf16(source);

		List<SourceDocument.Block> blocks = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
			if (blocks.size() >= MAX_BLOCKS) {
				throw limit("原稿顶层块超过 " + MAX_BLOCKS + " 个上限");
			}
			List<SourceSpan> spans = child.getSourceSpans();
			if (spans == null || spans.isEmpty()) {
				// 理论不发生（BLOCKS 级 span）；防御性跳过而非凭空构造跨度。
				warnings.add("存在无法定位的顶层块，已按空块跳过");
				continue;
			}
			int startUtf16 = lineStartUtf16[spans.get(0).getLineIndex()] + spans.get(0).getColumnIndex();
			SourceSpan last = spans.get(spans.size() - 1);
			int endUtf16 = lineStartUtf16[last.getLineIndex()] + last.getColumnIndex() + last.getLength();
			int startCp = codePointIndex[startUtf16];
			int endCp = codePointIndex[Math.min(endUtf16, source.length())];
			String blockText = source.substring(startUtf16, Math.min(endUtf16, source.length()));
			String blockKind = blockKind(child, warnings);
			blocks.add(new SourceDocument.Block(blockId(documentId, blocks.size()), blockKind, blocks.size(), startCp,
					endCp, blockText, sha256(blockText)));
		}
		return new ParsedSource(text, normalizedMarkdown, sha256(normalizedMarkdown), List.copyOf(blocks),
				List.copyOf(warnings));
	}

	/** 块 ID 由 documentId 和块序号确定性生成（同一文档内稳定，重新导入产生新 ID）。 */
	static String blockId(UUID documentId, int position) {
		return UUID.nameUUIDFromBytes((documentId + ":source-block:" + position).getBytes(StandardCharsets.UTF_8))
				.toString();
	}

	private static String blockKind(Node node, List<String> warnings) {
		if (node instanceof Heading) {
			return "heading";
		}
		if (node instanceof BulletList || node instanceof OrderedList) {
			return "list";
		}
		if (node instanceof BlockQuote) {
			return "quote";
		}
		if (node instanceof org.commonmark.ext.gfm.tables.TableBlock) {
			return "table";
		}
		if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
			return "code";
		}
		if (node instanceof HtmlBlock) {
			warnings.add("原始 HTML 块按原文保留，不执行其中内容");
			return "code";
		}
		if (node instanceof ThematicBreak) {
			return "paragraph";
		}
		return "paragraph";
	}

	/** CRLF/CR 统一为 LF；不做 NFKC、数字替换、全半角转换或空格删除（§5.1）。 */
	static String normalizeLineEndings(String text) {
		if (text.indexOf('\r') < 0) {
			return text;
		}
		return text.replace("\r\n", "\n").replace("\r", "\n");
	}

	/**
	 * plain-text 分支：转义 Markdown 控制字符保持字面呈现。转义只加反斜杠前缀，不删改任何字符； 行中句点无需转义，但「行首 1~9 位数字
	 * + 句点」会被解析为有序列表标记，需要转义句点。
	 */
	static String escapeMarkdownControls(String text) {
		StringBuilder escaped = new StringBuilder(text.length() + 16);
		int lineStart = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\n') {
				escaped.append(c);
				lineStart = i + 1;
				continue;
			}
			boolean needEscape = ESCAPE_CHARS.indexOf(c) >= 0;
			if (c == '.') {
				needEscape = isOrderedMarkerDot(text, lineStart, i);
			} else if (needEscape && (c == '-' || c == '+' || c == '#')) {
				// 仅行首才是块标记；行中破折号/加号/井号转义反而破坏字面呈现，保持原样。
				needEscape = i == lineStart;
			} else if (needEscape && c == '>') {
				needEscape = i == lineStart;
			}
			if (needEscape) {
				escaped.append('\\').append(c);
			} else {
				escaped.append(c);
			}
		}
		return escaped.toString();
	}

	/** 行首 1~9 位纯数字后跟句点 → 该句点需转义（有序列表标记）。 */
	private static boolean isOrderedMarkerDot(String text, int lineStart, int dotIndex) {
		int digits = dotIndex - lineStart;
		if (digits < 1 || digits > 9) {
			return false;
		}
		for (int i = lineStart; i < dotIndex; i++) {
			char c = text.charAt(i);
			if (c < '0' || c > '9') {
				return false;
			}
		}
		return true;
	}

	private static int[] lineStarts(String source) {
		List<Integer> starts = new ArrayList<>();
		starts.add(0);
		for (int i = 0; i < source.length(); i++) {
			if (source.charAt(i) == '\n') {
				starts.add(i + 1);
			}
		}
		int[] result = new int[starts.size()];
		for (int i = 0; i < starts.size(); i++) {
			result[i] = starts.get(i);
		}
		return result;
	}

	/** UTF-16 索引 → code point 索引映射（emoji 等增补平面字符计 1）。 */
	private static int[] codePointIndexByUtf16(String source) {
		int[] index = new int[source.length() + 1];
		int codePoints = 0;
		for (int i = 0; i < source.length();) {
			index[i] = codePoints;
			int cp = source.codePointAt(i);
			i += Character.charCount(cp);
			codePoints++;
		}
		index[source.length()] = codePoints;
		return index;
	}

	static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}

	private static IntelligenceException limit(String message) {
		return new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", message);
	}
}
