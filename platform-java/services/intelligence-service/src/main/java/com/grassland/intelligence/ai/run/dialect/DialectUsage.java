package com.grassland.intelligence.ai.run.dialect;

import com.fasterxml.jackson.databind.JsonNode;
import com.grassland.intelligence.security.IntelligenceException;

/**
 * 方言共用的 usage 读取（计量口径唯一入口）。
 *
 * <p>四家的字段名不同（OpenAI {@code prompt_tokens/completion_tokens}、Responses
 * {@code input_tokens/output_tokens}、Anthropic 同名但在 {@code usage} 下、Google
 * {@code usageMetadata.promptTokenCount/candidatesTokenCount}），但**校验规则同一条**：
 * 必须是可转 int 的非负整数，否则 502。缺 usage 不能静默当 0——那等于平台侧免费跑掉真实 token。
 */
final class DialectUsage {

	private DialectUsage() {
	}

	/** 读一个必填计量字段；缺失/非整数/负数 → 502。 */
	static int requireInt(JsonNode usage, String fieldName) {
		if (usage == null || !usage.isObject()) {
			throw new IntelligenceException(502, "AI provider 缺少 usage");
		}
		JsonNode value = usage.get(fieldName);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
			throw new IntelligenceException(502, "AI provider usage 无效");
		}
		int parsed = value.intValue();
		if (parsed < 0) {
			throw new IntelligenceException(502, "AI provider usage 无效");
		}
		return parsed;
	}

	/**
	 * 读一个可缺省为 0 的计量字段（出现即必须合法）。
	 *
	 * <p>仅用于上游「该项为 0 时省略字段」确有其事的场景：Google 在被安全策略掐断、无候选输出时
	 * 不回 {@code candidatesTokenCount}。语义上区别于 {@link #requireInt}——那是「整个 usage 都没给」。
	 */
	static int optionalInt(JsonNode usage, String fieldName) {
		if (usage == null || !usage.isObject() || usage.get(fieldName) == null) {
			return 0;
		}
		return requireInt(usage, fieldName);
	}

	/** 溢出即错（两项相加超 int 说明上游计量不可信）。 */
	static void assertSumFits(int inputTokens, int outputTokens) {
		try {
			Math.addExact(inputTokens, outputTokens);
		} catch (ArithmeticException overflow) {
			throw new IntelligenceException(502, "AI provider usage 无效");
		}
	}
}
