package com.grassland.intelligence.article;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.TextRunCommand;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 文章三端点端到端（草场 intelligence Slice 3）：titles（经执行环扣积分+聚合解析 JSON）/ outline /
 * content（免费 SSE）。 关键不变量：仅 titles 扣积分（独立模式经执行环闭环）；outline/content 不扣。
 */
class ArticleControllerIT extends IntelligenceItSupport {

	/** 路由决策替身（平台解析），供 streamWith 两步桩使用。 */
	private static final com.grassland.intelligence.ai.run.RoutedTextCompletionService.Routed ROUTED =
			new com.grassland.intelligence.ai.run.RoutedTextCompletionService.Routed(
					com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution.platform(
							null, "qwen", "http://localhost/v1", "qwen-plus", 1, null), "synthetic-key");


	@MockitoBean
	private RoutedTextCompletionService ai;

	@MockitoBean
	private CreditsClient credits;

	@MockitoBean
	private FrozenTextExecutionService frozenText;

	@BeforeEach
	void stubDefaults() {
		reset(ai, credits, frozenText);
		when(ai.resolveFor(any(), any())).thenReturn(Mono.just(ROUTED));
		CreditsStubs.stubDefaults(credits);
	}

	private String signed() {
		return sign(UUID.randomUUID().toString(), "recommender");
	}

	/** 桩执行环出口：把环入口捕获的 transform 应用到给定模型输出（覆盖剥 fence + JSON 解析路径）。 */
	@SuppressWarnings("unchecked")
	private void stubIndependentAppliesTransform(String modelOutput) {
		when(frozenText.executeIndependent(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.eq(CreditFeature.ARTICLE_GENERATION), any())).thenAnswer(invocation -> {
					Function<TextCompletionResult, Object> transform = (Function<TextCompletionResult, Object>) invocation
							.getArgument(4);
					try {
						return Mono.just(traced(transform.apply(new TextCompletionResult(modelOutput, 10, 5))));
					} catch (RuntimeException error) {
						return Mono.error(error);
					}
				});
	}

	private static <T> FrozenTextExecutionService.Traced<T> traced(T value) {
		return new FrozenTextExecutionService.Traced<>(value, null, "qwen", "qwen-plus", 1, false);
	}

	// ---------- titles ----------

	@Test
	@DisplayName("titles 无断言 → 401；题材空 → 400；均不触达执行环")
	void titlesAuthAndValidation() {
		client().post().uri("/api/article-generation/titles").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "职场")).exchange().expectStatus().isUnauthorized();
		verify(frozenText, never()).executeIndependent(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(),
				any());

		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "  ")).exchange().expectStatus()
				.isBadRequest();
		verify(frozenText, never()).executeIndependent(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(),
				any());
	}

	@Test
	@DisplayName("titles 积分不足 → 402（环内拒绝透传），不调 AI")
	void titlesInsufficientCredits() {
		when(frozenText.executeIndependent(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
				.thenReturn(Mono.error(new IntelligenceException(402, "积分不足")));
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "职场", "platform", "wechat"))
				.exchange().expectStatus().isEqualTo(402);
		verify(ai, never()).streamWith(any(), any(), anyInt(), any(), any());
	}

	@Test
	@DisplayName("titles 成功 → 经执行环（ARTICLE_GENERATION）+ 剥 code fence 解析 {title,hook} + prompt 断言")
	void titlesAggregatesAndParses() {
		stubIndependentAppliesTransform("```json\n{\"titles\":[{\"title\":\"爆款\",\"hook\":\"好奇\"}]}\n```");

		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "职场", "platform", "wechat"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(true)
				.jsonPath("$.data.titles[0].title").isEqualTo("爆款").jsonPath("$.data.titles[0].hook").isEqualTo("好奇");

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor
				.forClass((Class<List<ChatMessage>>) (Class<?>) List.class);
		verify(frozenText).executeIndependent(any(), messagesCaptor.capture(), org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.eq(CreditFeature.ARTICLE_GENERATION), any());
		assertThat(messagesCaptor.getValue()).hasSize(2);
		assertThat(messagesCaptor.getValue().get(0).content()).contains("标题");
		assertThat(messagesCaptor.getValue().get(1).content()).contains("职场");
		verify(credits, never()).consume(any(), any());
	}

	@Test
	@DisplayName("titles 返回非 JSON → 502（解析失败透传，不落 500）")
	void titlesUnparseableReturns502() {
		stubIndependentAppliesTransform("这不是JSON");
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "职场")).exchange().expectStatus()
				.isEqualTo(502);
	}

	// ---------- outline / content：免费 SSE ----------

	@Test
	@DisplayName("outline 成功 → 免费 SSE（不扣积分）；prompt 含主题/标题")
	void outlineFreeStream() {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<java.util.List<com.grassland.intelligence.ai.ChatMessage>> msgsCaptor =
				ArgumentCaptor.forClass(java.util.List.class);
		when(ai.streamWith(any(), msgsCaptor.capture(), anyInt(), any(), any()))
				.thenReturn(Flux.just(new ChatChunk("# 一、开头")));

		byte[] body = client().post().uri("/api/article-generation/outline").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "职场", "title", "打工人的清晨", "platform", "wechat")).exchange().expectStatus()
				.isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM).expectBody()
				.returnResult().getResponseBody();

		assertThat(new String(body, UTF_8)).isEqualTo("data: {\"content\":\"# 一、开头\"}\n\ndata: [DONE]\n\n");
		verify(credits, never()).consume(any(), any()); // outline 免费
		java.util.List<com.grassland.intelligence.ai.ChatMessage> messages = msgsCaptor.getValue();
		assertThat(messages.get(0).content()).contains("大纲");
		assertThat(messages.get(1).content()).contains("主题：职场").contains("标题：打工人的清晨");
	}

	@Test
	@DisplayName("content 成功 → 免费 SSE；prompt 含大纲")
	void contentFreeStream() {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<java.util.List<com.grassland.intelligence.ai.ChatMessage>> msgsCaptor =
				ArgumentCaptor.forClass(java.util.List.class);
		when(ai.streamWith(any(), msgsCaptor.capture(), anyInt(), any(), any()))
				.thenReturn(Flux.just(new ChatChunk("正文段落")));

		client().post().uri("/api/article-generation/content").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(outlineBody()).exchange().expectStatus().isOk()
				.expectHeader().valueEquals("X-Accel-Buffering", "no");

		verify(credits, never()).consume(any(), any()); // content 免费
		assertThat(msgsCaptor.getValue().get(1).content()).contains("大纲：").contains("一、开头");
	}

	@Test
	@DisplayName("content 大纲过短 → 400")
	void contentShortOutline() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("topic", "职场");
		body.put("title", "标题");
		body.put("outline", "短");
		client().post().uri("/api/article-generation/content").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isBadRequest();
	}

	private static Map<String, Object> outlineBody() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("topic", "职场");
		body.put("title", "打工人的清晨");
		body.put("outline", "一、开头引子\n二、展开吐槽\n三、收尾升华");
		body.put("platform", "wechat");
		return body;
	}
}
