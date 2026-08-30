package com.grassland.intelligence.imageanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import com.grassland.intelligence.credits.InsufficientCreditsException;
import com.grassland.intelligence.imageanalysis.StylePreferencesService.StyleSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import reactor.core.publisher.Mono;

/**
 * 图片评价 9 端点集成测试（草场 intelligence Slice 6）。复用
 * {@link IntelligenceItSupport}（testcontainers postgres + WireMock Qwen +
 * 真实断言签名）。重点锁定：SSE 事件契约、积分时序（analyze 扣 IMAGE_ANALYSIS、402→SSE error 帧）、
 * 匿名语义、multipart 图片校验、风格注入、飞书缺凭据 400。
 */
class ImageAnalysisControllerIT extends IntelligenceItSupport {

	@MockitoBean
	protected RoutedTextCompletionService ai;

	/** routed 桩通用产物：内容 + 最小 usage（TextCompletionResult 解析要求 usage 存在）。 */
	private static Mono<com.grassland.intelligence.ai.run.TextCompletionResult> completion(String content) {
		return Mono.just(new com.grassland.intelligence.ai.run.TextCompletionResult(content, 1, 1, null));
	}

	@MockitoBean
	protected CreditsClient credits;

	private static final String JSON_RESULT = "{\"review\":\"很不错的商品\",\"title\":\"好评推荐\",\"tags\":[\"新鲜\",\"实惠\"]}";

	@BeforeEach
	void resetMocks() {
		Mockito.reset(ai, credits);
		CreditsStubs.stubDefaults(credits);
		when(ai.complete(any(), any(), anyInt(), any(), any())).thenReturn(completion(JSON_RESULT));
		when(ai.completeFor(any(), any(), any(), anyInt(), any(), any())).thenReturn(completion(JSON_RESULT));
		db.sql("DELETE FROM intelligence_outbox").then().block();
		db.sql("DELETE FROM ai_credit_compensation").then().block();
		db.sql("DELETE FROM ai_run").then().block();
		// 自种子平台 text/primary 配置指向 QWEN（执行环路由依赖；不依赖其他测试类留下的状态）
		db.sql("DELETE FROM platform_model_concurrency_slot").then().block();
		db.sql("DELETE FROM platform_model_config").then().block();
		String platformConfigId = db
				.sql("INSERT INTO platform_model_config(capability, model_role, provider, model, "
						+ "base_url, max_concurrency, health_status, enabled, version) "
						+ "VALUES ('text','primary','qwen','qwen-plus',:baseUrl,1,'healthy',true,1) RETURNING id::text")
				.bind("baseUrl", QWEN.baseUrl()).map((row, meta) -> row.get("id", String.class)).one().block();
		db.sql("INSERT INTO platform_model_concurrency_slot(config_id, slot_no) VALUES (CAST(:id AS uuid), 1)")
				.bind("id", platformConfigId).then().block();
		// 独立 analyze 已迁执行环：真实环 + QWEN 非流式桩（usage 供结算计量）
		QWEN.resetAll();
		QWEN.stubFor(com.github.tomakehurst.wiremock.client.WireMock
				.post(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/chat/completions"))
				.willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("{\"choices\":[{\"message\":{\"content\":\"" + JSON_RESULT.replace("\"", "\\\"")
								+ "\"}}]," + "\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":30}}")));
		// 任务书 #58：平台 text 行须挂带密凭据（seeder/env 兜底已删），否则执行层 503
		attachPlatformTextCredential();
	}

	// ---------------- analyze ----------------

	@Test
	void analyzeStreamsProgressResultDoneAndChargesImageAnalysis() {
		String body = client().post().uri("/api/image-analysis/analyze").header(header(), sign("user-analyze", null))
				.contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(analyzeForm("120", null, "taobao")).exchange()
				.expectStatus().isOk().expectHeader().contentType(MediaType.TEXT_EVENT_STREAM).expectBody(String.class)
				.returnResult().getResponseBody();

		assertThat(body).contains("\"type\":\"progress\"");
		assertThat(body).contains("\"stage\":\"draft\"");
		assertThat(body).contains("\"stage\":\"optimize\"");
		assertThat(body).contains("\"stage\":\"complete\"");
		assertThat(body).contains("\"type\":\"result\"");
		assertThat(body).contains("\"review\":\"很不错的商品\"");
		assertThat(body).contains("\"imageCount\":1");
		assertThat(body).endsWith("data: [DONE]\n\n");
		// 多轮管线一次计费：经执行环 3 参 consume（draft+optimize 两轮共用一个 AI run）
		verify(credits).consume(eq("user-analyze"), eq(CreditFeature.IMAGE_ANALYSIS), any());
	}

	@Test
	void analyzeInsufficientCreditsSurfacesAsSseErrorFrameOverHttp200() {
		when(credits.consume(any(), eq(CreditFeature.IMAGE_ANALYSIS), any()))
				.thenReturn(Mono.error(new InsufficientCreditsException()));
		QWEN.resetRequests();

		String body = client().post().uri("/api/image-analysis/analyze").header(header(), sign("user-broke", null))
				.contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(analyzeForm("100", null, "taobao")).exchange()
				.expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

		assertThat(body).contains("\"type\":\"error\"");
		assertThat(body).contains("\"error\":\"积分不足\"");
		assertThat(QWEN.getAllServeEvents()).isEmpty();
	}

	@Test
	void analyzeAnonymousEmitsGenericErrorFrameNot401() {
		String body = client().post().uri("/api/image-analysis/analyze").contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(analyzeForm("100", null, "taobao")).exchange().expectStatus().isOk().expectBody(String.class)
				.returnResult().getResponseBody();

		assertThat(body).contains("\"type\":\"error\"");
		assertThat(body).contains("\"error\":\"评价生成失败，请稍后重试\"");
		verify(credits, never()).consume(any(), any());
	}

	@Test
	void analyzeBadTextFieldReturns400BeforeSse() {
		client().post().uri("/api/image-analysis/analyze").header(header(), sign("user-bad", null))
				.contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(analyzeForm("5", null, "taobao")) // reviewLength
																										// 5 不在 15-300 /
																										// 0
				.exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error")
				.isEqualTo("评价字数需在 15-300 之间，或填 0 不限制");
	}

	@Test
	void analyzeInjectsUserStylePreferencesIntoPrompt() {
		// 先写风格偏好
		client().put().uri("/api/image-analysis/style-preferences").header(header(), sign("user-style", null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("preferences", List.of("偏好短句"))).exchange()
				.expectStatus().isOk();

		String body = client().post().uri("/api/image-analysis/analyze").header(header(), sign("user-style", null))
				.contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(analyzeForm("100", null, "taobao")).exchange()
				.expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
		assertThat(body).contains("\"type\":\"result\"");

		// 风格偏好注入环内轮次 prompt（draft/optimize 至少一轮带「偏好短句」）
		QWEN.verify(com.github.tomakehurst.wiremock.client.WireMock
				.postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/chat/completions"))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("偏好短句")));
	}

	// ---------------- draft ----------------

	@Test
	void draftStreamsResultWithoutCharging() {
		String body = client().post().uri("/api/image-analysis/step/draft").header(header(), sign("user-draft", null))
				.contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(analyzeForm("100", null, "taobao")).exchange()
				.expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

		assertThat(body).contains("\"type\":\"result\"");
		assertThat(body).contains("\"imageCount\":1");
		assertThat(body).endsWith("data: [DONE]\n\n");
		verify(credits, never()).consume(any(), any());
	}

	@Test
	void draftAnonymousCanGenerate() {
		client().post().uri("/api/image-analysis/step/draft").contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(analyzeForm("100", null, "taobao")).exchange().expectStatus().isOk().expectBody(String.class)
				.value(s -> assertThat(s).contains("\"type\":\"result\""));
	}

	// ---------------- optimize / style-refine ----------------

	@Test
	void optimizeReturnsJsonResultWithoutImageCount() {
		client().post().uri("/api/image-analysis/step/optimize").header(header(), sign("user-opt", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("review", "待优化文案", "reviewLength", 100, "platform", "taobao")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(true).jsonPath("$.data.review")
				.isEqualTo("很不错的商品").jsonPath("$.data.imageCount").doesNotExist();
	}

	@Test
	void optimizeRejectsEmptyReview() {
		client().post().uri("/api/image-analysis/step/optimize").header(header(), sign("user-opt", null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("review", "  ", "reviewLength", 100))
				.exchange().expectStatus().isBadRequest();
	}

	@Test
	void styleRefineReturnsJsonResult() {
		client().post().uri("/api/image-analysis/step/style-refine").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("review", "待调整文案", "reviewLength", 100)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.review").isEqualTo("很不错的商品");
	}

	// ---------------- 风格偏好 ----------------

	@Test
	void stylePreferencesAnonymousReturnsEmptyList() {
		client().get().uri("/api/image-analysis/style-preferences").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.preferences").isArray().jsonPath("$.data.preferences.length()").isEqualTo(0);
	}

	@Test
	void stylePreferencesRoundTrip() {
		client().put().uri("/api/image-analysis/style-preferences").header(header(), sign("user-pref", null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("preferences", List.of("偏好短句", "口语化")))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.preferences[0]").isEqualTo("偏好短句");

		client().get().uri("/api/image-analysis/style-preferences").header(header(), sign("user-pref", null)).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.preferences.length()").isEqualTo(2);
	}

	@Test
	void stylePreferencesPutRequiresAuth() {
		client().put().uri("/api/image-analysis/style-preferences").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("preferences", List.of("x"))).exchange().expectStatus().isUnauthorized();
	}

	@Test
	void stylePreferencesOptimizeCallsLlm() {
		when(ai.completeFor(any(), any(), any(), anyInt(), any(), any()))
				.thenReturn(completion("偏好短句\n偏好短句\n口语化"));
		client().post().uri("/api/image-analysis/style-preferences/optimize")
				.header(header(), sign("user-opt-pref", null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("preferences", List.of("偏好短句", "偏好短的句子"))).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.preferences.length()").isEqualTo(3);
		verify(ai).completeFor(any(), any(), any(), anyInt(), any(), any());
	}

	@Test
	void saveStyleMemorySkipsLlmWhenOriginalEqualsEdited() {
		StyleSnapshot snap = new StyleSnapshot("评价内容", null, null);
		client().post().uri("/api/image-analysis/save-style-memory").header(header(), sign("user-mem", null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("original", snap, "edited", snap)).exchange()
				.expectStatus().isOk();
		verify(ai, never()).completeFor(any(), any(), any(), anyInt(), any(), any());
	}

	// ---------------- export-feishu ----------------

	@Test
	void exportFeishuMissingCredentialsReturns400() {
		MultipartBodyBuilder b = new MultipartBodyBuilder();
		b.part("review", "评价内容").contentType(MediaType.TEXT_PLAIN);
		client().post().uri("/api/image-analysis/export-feishu").header(header(), sign("user-feishu", null))
				.contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(b.build()).exchange().expectStatus()
				.isBadRequest().expectBody().jsonPath("$.error").isEqualTo("飞书应用凭证未配置，请在设置中填写 App ID 和 App Secret");
	}

	@Test
	void exportFeishuRequiresAuth() {
		MultipartBodyBuilder b = new MultipartBodyBuilder();
		b.part("review", "评价内容").contentType(MediaType.TEXT_PLAIN);
		client().post().uri("/api/image-analysis/export-feishu").contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(b.build()).exchange().expectStatus().isUnauthorized();
	}

	// ---------------- helpers ----------------

	private String header() {
		return "X-Grassland-Identity";
	}

	private static org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>> analyzeForm(
			String reviewLength, String feelings, String platform) {
		MultipartBodyBuilder b = new MultipartBodyBuilder();
		if (reviewLength != null) {
			b.part("reviewLength", reviewLength).contentType(MediaType.TEXT_PLAIN);
		}
		if (feelings != null) {
			b.part("feelings", feelings).contentType(MediaType.TEXT_PLAIN);
		}
		if (platform != null) {
			b.part("platform", platform).contentType(MediaType.TEXT_PLAIN);
		}
		b.part("images", pngResource()).contentType(MediaType.IMAGE_PNG);
		return b.build();
	}

	private static ByteArrayResource pngResource() {
		return new ByteArrayResource(pngBytes()) {
			@Override
			public String getFilename() {
				return "photo.png";
			}
		};
	}

	private static byte[] pngBytes() {
		// PNG signature（8 字节 magic）+ 少量填充。service.validateAndEncode 仅校验 magic。
		return new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01, 0x02};
	}

	private static boolean hasImagePartContainingStyle(ChatMessage message) {
		if (!message.multimodal() || message.parts() == null) {
			return false;
		}
		boolean hasImage = false;
		boolean hasStyle = false;
		for (ContentPart part : message.parts()) {
			if (part instanceof ContentPart.Image) {
				hasImage = true;
			} else if (part instanceof ContentPart.Text t && t.text().contains("用户个人风格偏好")) {
				hasStyle = true;
			}
		}
		return hasImage && hasStyle;
	}
}
