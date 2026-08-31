package com.grassland.intelligence.ai.run.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("TextDialects 查表")
class TextDialectsTest {

	private static TextDialects all() {
		return new TextDialects(List.of(new OpenAiCompletionsDialect(), new OpenAiResponsesDialect(),
				new AnthropicMessagesDialect(), new GoogleGenerativeAiDialect()));
	}

	@Test
	@DisplayName("四个方言名各自解析到对应实现")
	void resolvesEachDialectByName() {
		TextDialects dialects = all();

		assertThat(dialects.resolve("openai-completions")).isInstanceOf(OpenAiCompletionsDialect.class);
		assertThat(dialects.resolve("openai-responses")).isInstanceOf(OpenAiResponsesDialect.class);
		assertThat(dialects.resolve("anthropic-messages")).isInstanceOf(AnthropicMessagesDialect.class);
		assertThat(dialects.resolve("google-generative-ai")).isInstanceOf(GoogleGenerativeAiDialect.class);
		assertThat(dialects.registered()).containsOnlyKeys("openai-completions", "openai-responses",
				"anthropic-messages", "google-generative-ai");
	}

	/**
	 * 回落等于「迁移前行为」：分方言之前 TextCompletionClient 对 provider 零分支，一切取值都走 OpenAI
	 * Chat Completions 形状。存量 BYOK 行的 provider 只有 @NotBlank 约束（无正则），字面上什么都可能存进去，
	 * fail-closed 会把这些行一次性打死，所以这里必须是宽容回落。
	 */
	@ParameterizedTest
	@ValueSource(strings = { "qwen", "openai-compatible", "sandbox", "totally-unknown", "  QWEN  ", "" })
	@DisplayName("未知/legacy/空 provider 一律回落 openai-completions")
	void fallsBackToOpenAiCompletions(String provider) {
		assertThat(all().resolve(provider)).isInstanceOf(OpenAiCompletionsDialect.class);
	}

	@Test
	@DisplayName("provider=null 回落而非 NPE")
	void fallsBackOnNull() {
		assertThat(all().resolve(null)).isInstanceOf(OpenAiCompletionsDialect.class);
	}

	@Test
	@DisplayName("大小写与首尾空白归一后再查表")
	void normalisesBeforeLookup() {
		assertThat(all().resolve("  Anthropic-Messages ")).isInstanceOf(AnthropicMessagesDialect.class);
	}

	/**
	 * 缺默认方言就必须**启动期**炸——否则 resolve 会在首个请求上 NPE，故障点离根因十万八千里。
	 */
	@Test
	@DisplayName("注册表缺 openai-completions 时构造即失败")
	void requiresDefaultDialect() {
		assertThatThrownBy(() -> new TextDialects(List.of(new AnthropicMessagesDialect())))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("openai-completions");
	}

	@Test
	@DisplayName("registered() 返回副本，外部改动不影响查表")
	void registeredIsDefensiveCopy() {
		TextDialects dialects = all();

		dialects.registered().clear();

		assertThat(dialects.resolve("anthropic-messages")).isInstanceOf(AnthropicMessagesDialect.class);
	}
}
