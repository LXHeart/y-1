package com.grassland.intelligence.ai.run;

/** OpenAI 兼容 text completion 结果（含 usage 计量供结算）。 */
public record TextCompletionResult(String content, int inputTokens, int outputTokens) {
}
