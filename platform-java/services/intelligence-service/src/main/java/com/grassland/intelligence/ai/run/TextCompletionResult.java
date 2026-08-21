package com.grassland.intelligence.ai.run;

/**
 * OpenAI 兼容 text completion 结果（含 usage 计量供结算）。
 *
 * @param content
 *            模型返回的文本内容
 * @param inputTokens
 *            上游 prompt_tokens
 * @param outputTokens
 *            上游 completion_tokens
 * @param providerRunId
 *            上游响应顶层 {@code id}（可空；视频分析等场景透出给前端作「运行 ID」展示）
 */
public record TextCompletionResult(String content, int inputTokens, int outputTokens, String providerRunId) {
	public TextCompletionResult(String content, int inputTokens, int outputTokens) {
		this(content, inputTokens, outputTokens, null);
	}
}
