package com.grassland.intelligence.ai.run;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.DnsPinningResolver;
import com.grassland.intelligence.ai.controlplane.PlatformProviderPolicy;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 计量流测试（任务书 #105D C105D-03 / TC105D-03-01、TC105D-03-02）：usage-only 尾帧精确计量与
 * [DONE] 不吞 usage；半流/负 usage/只有 DONE → 无 usage 事件（pending 依据）、不重发；旧
 * streamMessages 回归（签名与方言行为不变）。
 */
@DisplayName("MeteredTextStream (C105D-03)")
class MeteredTextStreamTest {

	private WireMockServer provider;

	@BeforeEach
	void startProvider() {
		provider = new WireMockServer(0);
		provider.start();
	}

	@AfterEach
	void stopProvider() {
		provider.stop();
	}

	private TextCompletionClient newClient() {
		return new TextCompletionClient(Duration.ofMillis(5_000), DnsPinningResolver.create(),
				org.mockito.Mockito.mock(PlatformProviderPolicy.class),
				new com.grassland.intelligence.ai.run.dialect.TextDialects(
						List.of(new com.grassland.intelligence.ai.run.dialect.OpenAiCompletionsDialect())));
	}

	private static String sse(String... dataLines) {
		StringBuilder body = new StringBuilder();
		for (String line : dataLines) {
			body.append("data: ").append(line).append("\n\n");
		}
		return body.toString();
	}

	private void stubStream(String body) {
		provider.stubFor(post(urlEqualTo("/chat/completions"))
				.willReturn(ok().withHeader("Content-Type", "text/event-stream").withBody(body)));
	}

	private static List<TextStreamEvent> run(Flux<TextStreamEvent> stream) {
		return stream.collectList().block(Duration.ofSeconds(10));
	}

	// ---------- TC105D-03-01：usage-only 尾帧 ----------

	@Test
	void tc105d_03_01_usageOnlyTailFrameYieldsExactTokensAndRunIdBeforeDone() {
		stubStream(sse("""
				{"id":"chatcmpl-run-7","choices":[{"delta":{"content":"你好"}}]}
				""", """
				{"id":"chatcmpl-run-7","choices":[{"delta":{"content":"，世界"}}]}
				""", """
				{"id":"chatcmpl-run-7","choices":[],"usage":{"prompt_tokens":128,"completion_tokens":64}}
				""", "[DONE]"));
		List<TextStreamEvent> events = run(newClient().streamMeteredMessages("openai-completions", provider.baseUrl(),
				"key", "qwen-plus", List.of(ChatMessage.user("打个招呼")), 256, false, null));
		assertThat(events).extracting(TextStreamEvent::type).containsExactly(TextStreamEvent.Type.delta,
				TextStreamEvent.Type.delta, TextStreamEvent.Type.usage, TextStreamEvent.Type.done);
		TextStreamEvent usage = events.get(2);
		assertThat(usage.inputTokens()).isEqualTo(128L);
		assertThat(usage.outputTokens()).isEqualTo(64L);
		assertThat(usage.providerRequestId()).isEqualTo("chatcmpl-run-7");
		assertThat(events.get(3).delta()).isNull();
		// 请求体带 stream_options.include_usage（K08）。
		provider.verify(1,
				postRequestedFor(urlEqualTo("/chat/completions")).withRequestBody(containing("\"stream_options\"")));
	}

	// ---------- TC105D-03-02：异常/缺 usage ----------

	@Test
	void tc105d_03_02_halfStreamInterruptionHasNoDoneAndNoUsageAndNoRetry() {
		// HTTP 200 后流中断：有 delta、无 usage、无 [DONE] → 无 done 事件（pending 依据），且不重发。
		stubStream(sse("""
				{"id":"chatcmpl-x","choices":[{"delta":{"content":"说到一半"}}]}
				"""));
		List<TextStreamEvent> events = run(newClient().streamMeteredMessages("openai-completions", provider.baseUrl(),
				"key", "qwen-plus", List.of(ChatMessage.user("继续")), 256, false, null));
		assertThat(events).extracting(TextStreamEvent::type).containsExactly(TextStreamEvent.Type.delta);
		provider.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
	}

	@Test
	void tc105d_03_02_negativeUsageIsIgnoredPendingNotZero() {
		stubStream(sse("""
				{"id":"chatcmpl-n","choices":[{"delta":{"content":"文本"}}]}
				""", """
				{"id":"chatcmpl-n","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":-5}}
				""", "[DONE]"));
		List<TextStreamEvent> events = run(newClient().streamMeteredMessages("openai-completions", provider.baseUrl(),
				"key", "qwen-plus", List.of(ChatMessage.user("写")), 256, false, null));
		// 负 usage 不可计算为 0：无 usage 事件，只有 delta+done → 结算侧 pending。
		assertThat(events).extracting(TextStreamEvent::type).containsExactly(TextStreamEvent.Type.delta,
				TextStreamEvent.Type.done);
	}

	@Test
	void tc105d_03_02_doneOnlyStreamProducesNoUsage() {
		stubStream(sse("[DONE]"));
		List<TextStreamEvent> events = run(newClient().streamMeteredMessages("openai-completions", provider.baseUrl(),
				"key", "qwen-plus", List.of(ChatMessage.user("空")), 256, false, null));
		assertThat(events).extracting(TextStreamEvent::type).containsExactly(TextStreamEvent.Type.done);
		assertThat(events.stream().filter(e -> e.type() == TextStreamEvent.Type.usage)).isEmpty();
	}

	@Test
	void tc105d_03_02_nonApprovedDialectRejected() {
		// 首期只批准 openai-completions/compatible：注册表里有其它方言时，其 provider 明确拒绝（不猜协议）。
		TextCompletionClient withAnthropic = new TextCompletionClient(Duration.ofMillis(5_000),
				DnsPinningResolver.create(), org.mockito.Mockito.mock(PlatformProviderPolicy.class),
				new com.grassland.intelligence.ai.run.dialect.TextDialects(
						List.of(new com.grassland.intelligence.ai.run.dialect.OpenAiCompletionsDialect(),
								new com.grassland.intelligence.ai.run.dialect.AnthropicMessagesDialect())));
		try {
			run(withAnthropic.streamMeteredMessages("anthropic-messages", provider.baseUrl(), "key", "claude",
					List.of(ChatMessage.user("x")), 64, false, null));
			throw new IllegalStateException("应当拒绝");
		} catch (com.grassland.intelligence.security.IntelligenceException expected) {
			assertThat(expected.status()).isEqualTo(409);
		}
		provider.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
	}

	// ---------- 旧流回归 ----------

	@Test
	void legacyStreamMessagesBehaviorUnchanged() {
		stubStream(sse("""
				{"choices":[{"delta":{"content":"旧路径"}}]}
				""", "[DONE]"));
		List<ChatChunk> chunks = newClient().streamMessages("openai-completions", provider.baseUrl(), "key",
				"qwen-plus", List.of(ChatMessage.user("回归")), 64, false, null).collectList()
				.block(Duration.ofSeconds(10));
		assertThat(chunks).extracting(ChatChunk::content).containsExactly("旧路径");
		// 旧流请求体不带 stream_options（方言 body 语义不变）。
		provider.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notContaining("stream_options")));
	}

	@Test
	void upstreamFailureMapsTo502() {
		provider.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(500)));
		try {
			run(newClient().streamMeteredMessages("openai-completions", provider.baseUrl(), "key", "qwen-plus",
					List.of(ChatMessage.user("x")), 64, false, null));
			throw new IllegalStateException("应当失败");
		} catch (com.grassland.intelligence.security.IntelligenceException expected) {
			assertThat(expected.status()).isEqualTo(502);
		}
	}
}
