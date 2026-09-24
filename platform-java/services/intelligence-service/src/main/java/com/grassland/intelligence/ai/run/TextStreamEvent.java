package com.grassland.intelligence.ai.run;

/**
 * 计量流事件（任务书 #105D C105D-03 / 共享契约 K08）。
 *
 * <p>
 * 事件契约（{@link TextCompletionClient#streamMeteredMessages}）：
 * <ul>
 * <li>{@code delta}——经思考标签剥离后的可见增量；</li>
 * <li>{@code usage}——上游<b>合法终帧</b>（choices=[]+usage 或等价）精确计量；缺失/负数不产生本事件
 * （不得回退按字数估 token）；</li>
 * <li>{@code done}——<b>仅</b>在见到方言终止标记（如 {@code [DONE]}）的干净结束时发出；半流中断 （HTTP 200
 * 后连接截断、无终止标记）不发出——调用方以此区分「完成」与「中断待核对（pending）」。</li>
 * </ul>
 */
public record TextStreamEvent(Type type, String delta, String providerRequestId, Long inputTokens, Long outputTokens) {

	public enum Type {
		delta, usage, done
	}

	public static TextStreamEvent delta(String delta) {
		return new TextStreamEvent(Type.delta, delta, null, null, null);
	}

	public static TextStreamEvent usage(String providerRequestId, long inputTokens, long outputTokens) {
		return new TextStreamEvent(Type.usage, null, providerRequestId, inputTokens, outputTokens);
	}

	public static TextStreamEvent done() {
		return new TextStreamEvent(Type.done, null, null, null, null);
	}
}
