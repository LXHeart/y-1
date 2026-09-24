package com.grassland.intelligence.digitalhuman;

import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.DnsPinningResolver;
import com.grassland.intelligence.ai.run.TextCompletionClient;
import com.grassland.intelligence.ai.run.TextStreamEvent;
import com.grassland.intelligence.contentsafety.ContentSafetyService;
import com.grassland.intelligence.contentsafety.SafetyReport;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Tone;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.ProfileRevisionRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanTextBridge.CompletedPair;
import com.grassland.intelligence.digitalhuman.DigitalHumanTextPolicy.SpeechTextSegment;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 文本桥测试（任务书 #105D C105D-03 / TC105D-03-03、TC105D-03-04）：思考标签跨 chunk 不进 TTS、违禁
 * 片段跨 120 段界被拦（前段尾 32 码点跨段检测）、正常文本不损坏；11 对裁到最近 10、64KiB 边界最老整对 裁剪、不完整回答不进历史。真实
 * WireMock SSE 流 + 真实分段/组装逻辑；安全词库是外部依赖按卡以桩替换。
 */
@DisplayName("DigitalHumanTextBridge (C105D-03)")
class DigitalHumanTextBridgeTest {

	private WireMockServer provider;
	private ContentSafetyService safety;
	private DigitalHumanTextPolicy policy;
	private DigitalHumanTextBridge bridge;

	@BeforeEach
	void setUp() {
		provider = new WireMockServer(0);
		provider.start();
		safety = mock(ContentSafetyService.class);
		when(safety.checkShallow(anyString())).thenReturn(new SafetyReport(List.of(), "lex-v1", false, List.of()));
		policy = new DigitalHumanTextPolicy(safety);
		bridge = new DigitalHumanTextBridge(newClient(), policy);
	}

	@AfterEach
	void tearDown() {
		provider.stop();
	}

	private TextCompletionClient newClient() {
		return new TextCompletionClient(Duration.ofMillis(5_000), DnsPinningResolver.create(),
				mock(com.grassland.intelligence.ai.controlplane.PlatformProviderPolicy.class),
				new com.grassland.intelligence.ai.run.dialect.TextDialects(
						List.of(new com.grassland.intelligence.ai.run.dialect.OpenAiCompletionsDialect())));
	}

	private static ProfileRevisionRow profile() {
		return new ProfileRevisionRow(UUID.randomUUID().toString(), "owner-a", UUID.randomUUID().toString(), 3,
				"用简洁中文帮助整理口播思路。", "你好，今天想创作什么？", Tone.natural, null, 1, "preset-zh-natural-01", 1, 1, Instant.now(),
				Instant.now());
	}

	private void stubSse(String... dataLines) {
		StringBuilder body = new StringBuilder();
		for (String line : dataLines) {
			body.append("data: ").append(line).append("\n\n");
		}
		provider.stubFor(post(urlEqualTo("/chat/completions"))
				.willReturn(ok().withHeader("Content-Type", "text/event-stream").withBody(body.toString())));
	}

	private List<SpeechTextSegment> segmentsOf(Flux<TextStreamEvent> events) {
		return bridge.checkedSegments(events, 7).collectList().block(Duration.ofSeconds(10));
	}

	// ---------- TC105D-03-03：安全跨段与思考 ----------

	@Test
	void tc105d_03_03_thinkTagSplitAcrossChunksNeverReachesSegments() {
		// 思考标签被 token 边界切开：<thi|nk>…</thi|nk>——推理内容不得进入 TTS 段。
		stubSse("""
				{"id":"r1","choices":[{"delta":{"content":"<thi"}}]}
				""", """
				{"id":"r1","choices":[{"delta":{"content":"nk>先偷偷推理一下"}}]}
				""", """
				{"id":"r1","choices":[{"delta":{"content":"</thi"}}]}
				""", """
				{"id":"r1","choices":[{"delta":{"content":"nk>好的，三句口播来了。"}}]}
				""", """
				{"id":"r1","choices":[],"usage":{"prompt_tokens":50,"completion_tokens":30}}
				""", "[DONE]");
		var prepared = bridgeCheckedStream();
		assertThat(prepared.segments).extracting(SpeechTextSegment::text).containsExactly("好的，三句口播来了。");
		assertThat(String.join("", prepared.segments.stream().map(SpeechTextSegment::text).toList()))
				.doesNotContain("推理");
		assertThat(prepared.usageSeen).isTrue();
	}

	@Test
	void tc105d_03_03_forbiddenPhraseAcrossSegmentBoundaryBlocked() {
		// 违禁词整体跨 120 码点硬切边界：段 A 尾部只有「违」，段 B 以「禁词」开头——单段检查都不命中，
		// 靠「前段尾 32 码点 + 后段」的跨段检测拦截。
		when(safety.checkShallow(anyString())).thenReturn(new SafetyReport(List.of(), "lex-v1", false, List.of()));
		when(safety.checkShallow(org.mockito.ArgumentMatchers.argThat(probe -> probe != null && probe.contains("违禁词"))))
				.thenReturn(new SafetyReport(List.of(new SafetyReport.Finding("custom", "high", "违禁词", 0, "禁用", false)),
						"lex-v1", false, List.of()));
		StringBuilder first = new StringBuilder();
		first.append("前".repeat(119)).append("违"); // 恰 120 码点，硬切段 A 以「违」收尾
		List<TextStreamEvent> events = new ArrayList<>();
		events.add(TextStreamEvent.delta(first.toString()));
		events.add(TextStreamEvent.delta("禁词出现。收尾句。"));
		events.add(TextStreamEvent.done());
		try {
			bridge.checkedSegments(Flux.fromIterable(events), 1).collectList().block(Duration.ofSeconds(10));
			throw new IllegalStateException("应当被拦截");
		} catch (IntelligenceException expected) {
			assertThat(expected.status()).isEqualTo(422);
			assertThat(expected.code()).isEqualTo("dh_content_blocked");
		}
	}

	@Test
	void tc105d_03_03_normalTextIntactAcrossSegments() {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 5; i++) {
			text.append("第").append(i).append("句口播内容，保持完整。");
		}
		List<TextStreamEvent> events = new ArrayList<>();
		events.add(TextStreamEvent.delta(text.toString()));
		events.add(TextStreamEvent.done());
		List<SpeechTextSegment> segments = segmentsOf(Flux.fromIterable(events));
		assertThat(String.join("", segments.stream().map(SpeechTextSegment::text).toList())).isEqualTo(text.toString());
		assertThat(segments).allSatisfy(segment -> {
			assertThat(segment.text().codePointCount(0, segment.text().length()))
					.isLessThanOrEqualTo(DigitalHumanTextPolicy.MAX_SEGMENT_CODE_POINTS);
			assertThat(segment.contentEpoch()).isEqualTo(7);
		});
	}

	// ---------- TC105D-03-04：上下文裁剪 ----------

	@Test
	void tc105d_03_04_elevenPairsTruncatedToMostRecentTen() {
		List<CompletedPair> history = new ArrayList<>();
		for (int i = 1; i <= 11; i++) {
			history.add(new CompletedPair("用户问题" + i, "助手回答" + i, "u-" + i, "a-" + i));
		}
		List<ChatMessage> messages = bridge.assembleContext(profile(), history, "当前输入");
		// system + 10 对（20 条）+ 当前 = 22 条；最老的第 1 对被裁。
		assertThat(messages).hasSize(1 + 20 + 1);
		assertThat(messages.get(1).content()).isEqualTo("用户问题2");
		assertThat(messages.get(messages.size() - 1).content()).isEqualTo("当前输入");
		assertThat(messages.stream().map(ChatMessage::content).noneMatch("用户问题1"::equals)).isTrue();
		// 不自动总结：没有额外 summary 消息。
		assertThat(messages.stream().map(ChatMessage::role).filter("system"::equals).count()).isEqualTo(1);
	}

	@Test
	void tc105d_03_04_contextByteBudgetDropsOldestWholePairs() {
		// 64KiB 边界：每条约 4KB（1300 个三字节中文字符 + 协议余量）——10 对 ≈ 82KB 超预算，从最老整对裁。
		String pairText = "块".repeat(1300);
		List<CompletedPair> history = new ArrayList<>();
		for (int i = 1; i <= 30; i++) {
			history.add(new CompletedPair("问" + i + pairText, "答" + i + pairText, "u-" + i, "a-" + i));
		}
		List<ChatMessage> messages = bridge.assembleContext(profile(), history, "当前输入");
		assertThat(messages.size()).isLessThan(1 + 20 + 1);
		// 整对裁剪：消息数 = 1 + 2k + 1（奇数）。
		assertThat(messages.size() % 2).isEqualTo(0);
		assertThat(messages.get(1).content()).doesNotStartWith("问1");
	}

	@Test
	void tc105d_03_04_incompletePairsNeverEnterHistory() {
		List<CompletedPair> history = List.of(new CompletedPair("有问无答", " ", "u", "a"),
				new CompletedPair(" ", "有答无问", "u2", "a2"), new CompletedPair("完整问", "完整答", "u3", "a3"));
		List<ChatMessage> messages = bridge.assembleContext(profile(), history, "现在");
		assertThat(messages).hasSize(1 + 2 + 1);
		assertThat(messages.get(1).content()).isEqualTo("完整问");
	}

	@Test
	void tc105d_03_04_currentInputBeyondBudgetRejectedBeforeDispatch() {
		assertThatThrownBy(() -> bridge.assembleContext(profile(), List.of(), "超".repeat(30000)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> {
					assertThat(e.status()).isEqualTo(422);
				});
	}

	// ---------- 辅助 ----------

	private record Prepared(boolean usageSeen, List<SpeechTextSegment> segments) {
	}

	private Prepared bridgeCheckedStream() {
		List<TextStreamEvent> events = newClient()
				.streamMeteredMessages("openai-completions", provider.baseUrl(), "key", "qwen-plus",
						List.of(ChatMessage.user("写三句口播")), 256, false, null)
				.collectList().block(Duration.ofSeconds(10));
		boolean usageSeen = events.stream().anyMatch(e -> e.type() == TextStreamEvent.Type.usage);
		return new Prepared(usageSeen,
				bridge.checkedSegments(Flux.fromIterable(events), 7).collectList().block(Duration.ofSeconds(10)));
	}
}
