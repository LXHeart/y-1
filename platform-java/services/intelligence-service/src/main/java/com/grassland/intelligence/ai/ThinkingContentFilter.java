package com.grassland.intelligence.ai;

/**
 * 剥离推理模型内联在 {@code content} 里的 {@code <think>…</think>} 思考块。
 *
 * <p>
 * 背景：MiniMax-M3 等推理模型即使请求侧带了关闭思考的参数，部分网关/自建部署仍会把思考过程以
 * {@code <think>} 前缀内联进 {@code choices[].message.content} 或流式 {@code delta.content}。
 * 下游按纯正文消费（JSON 解析、直接展示给创作者）时拿到的是污染文本——文章标题生成即因此
 * 报「标题生成返回了无法解析的内容」。请求参数是优化（省 token 与时延），本类是兜底：无论
 * 上游是否遵守开关，出口内容一律无思考块。
 *
 * <ul>
 * <li>非流式：{@link #strip(String)} 整段剥。</li>
 * <li>流式：{@link Stream} 跨 chunk 状态机（标签可能被 token 边界切开）。</li>
 * </ul>
 */
public final class ThinkingContentFilter {

	private static final String OPEN = "<think>";

	private static final String CLOSE = "</think>";

	private ThinkingContentFilter() {
	}

	/**
	 * 非流式剥离：去掉全部思考块；未闭合的 {@code <think>} 视为思考被截断（正文尚未产出），
	 * 其后内容一并丢弃。
	 */
	public static String strip(String content) {
		if (content == null || content.isEmpty()) {
			return content;
		}
		StringBuilder out = new StringBuilder(content.length());
		int cursor = 0;
		int open = content.indexOf(OPEN);
		while (open >= 0) {
			out.append(content, cursor, open);
			int close = content.indexOf(CLOSE, open + OPEN.length());
			if (close < 0) {
				return out.toString();
			}
			cursor = close + CLOSE.length();
			open = content.indexOf(OPEN, cursor);
		}
		out.append(content, cursor, content.length());
		return out.toString();
	}

	/**
	 * 流式剥离器。非线程安全：每个 subscription 一个实例。缓冲尾部可能是不完整的标签前缀，
	 * 扣住不发直到能判定；{@link #flush()} 在流结束时释放非思考态的残余（不吞正文尾巴）。
	 */
	public static final class Stream {

		private final StringBuilder buffer = new StringBuilder();

		private boolean inThink = false;

		/** 喂入一个 delta，返回本次可释放的正文（可能为空串）。 */
		public String feed(String delta) {
			buffer.append(delta);
			StringBuilder release = new StringBuilder();
			boolean progressed = true;
			while (progressed) {
				progressed = false;
				if (inThink) {
					int close = buffer.indexOf(CLOSE);
					if (close >= 0) {
						buffer.delete(0, close + CLOSE.length());
						inThink = false;
						progressed = true;
					}
				} else {
					int open = buffer.indexOf(OPEN);
					if (open >= 0) {
						release.append(buffer, 0, open);
						buffer.delete(0, open + OPEN.length());
						inThink = true;
						progressed = true;
					}
				}
			}
			if (inThink) {
				dropAllButPossibleTagPrefix(CLOSE);
			} else {
				releasePossibleTagHoldback(release, OPEN);
			}
			return release.toString();
		}

		/** 流结束：非思考态释放缓冲；思考态残余（未闭合即截断）丢弃。 */
		public String flush() {
			if (inThink) {
				buffer.setLength(0);
				return "";
			}
			String rest = buffer.toString();
			buffer.setLength(0);
			return rest;
		}

		/** 思考态：仅保留可能是被切断的 {@code </think>} 前缀的尾部，其余丢弃。 */
		private void dropAllButPossibleTagPrefix(String marker) {
			int keep = longestSuffixLengthThatIsPrefixOf(marker);
			if (buffer.length() > keep) {
				buffer.delete(0, buffer.length() - keep);
			}
		}

		/** 非思考态：释放除可能的 {@code <think>} 前缀尾部外的全部内容。 */
		private void releasePossibleTagHoldback(StringBuilder release, String marker) {
			int keep = longestSuffixLengthThatIsPrefixOf(marker);
			int emit = buffer.length() - keep;
			if (emit > 0) {
				release.append(buffer, 0, emit);
				buffer.delete(0, emit);
			}
		}

		private int longestSuffixLengthThatIsPrefixOf(String marker) {
			int max = Math.min(buffer.length(), marker.length() - 1);
			for (int len = max; len > 0; len--) {
				boolean matches = true;
				for (int i = 0; i < len; i++) {
					if (buffer.charAt(buffer.length() - len + i) != marker.charAt(i)) {
						matches = false;
						break;
					}
				}
				if (matches) {
					return len;
				}
			}
			return 0;
		}
	}
}
