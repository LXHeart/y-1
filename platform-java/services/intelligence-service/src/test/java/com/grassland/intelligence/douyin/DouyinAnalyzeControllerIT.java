package com.grassland.intelligence.douyin;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.mediaplatform.PlatformMediaService;
import com.grassland.intelligence.mediaplatform.VideoSegmentAnalysisService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * {@link DouyinAnalyzeController} 集成测试（草场 GL-P3-MEDIA-001）。
 *
 * <p>
 * 覆盖 Java 路径（progressive+≤60s+qwen → Qwen video_url + 扣积分 + 归一，提示词/归一与 Bilibili
 * 共用平台级实现）、长视频 Java FFmpeg 分段路径、422 时长校验、 401 未登录、400 非法地址。平台 Qwen 走基座
 * {@link IntelligenceItSupport#QWEN} WireMock； 积分扣减使用 WireMock，媒体准备与分段分析使用 mock
 * bean 精确验证编排。
 */
@DisplayName("Douyin analyze POST /api/douyin/analyze-video（草场 GL-P3-MEDIA-001）")
class DouyinAnalyzeControllerIT extends IntelligenceItSupport {

	private static final String SECRET = "test-douyin-secret-32-chars-min!!";
	private static final String PUBLIC_ORIGIN = "https://public.test";
	private static final String ACCOUNT = "33333333-3333-3333-3333-333333333333";

	static final WireMockServer LEGACY = new WireMockServer(0);

	static {
		LEGACY.start();
	}

	@Autowired
	private DouyinProxyToken tokenCodec;
	@MockitoBean
	private PlatformMediaService media;
	@MockitoBean
	private VideoSegmentAnalysisService segmented;

	private final ObjectMapper mapper = new ObjectMapper();

	@DynamicPropertySource
	static void douyinProps(DynamicPropertyRegistry registry) {
		registry.add("douyin.proxy.token-secret", () -> SECRET);
		registry.add("app.public-backend-origin", () -> PUBLIC_ORIGIN);
		// CreditsClient = FinanceCreditsClient；指向同一 WireMock，consume 走生产路径打桩。
		registry.add("credits.finance.base-url", LEGACY::baseUrl);
		registry.add("marketplace.service.base-url", LEGACY::baseUrl);
		registry.add("ai.douyin-analysis.provider", () -> "qwen");
		registry.add("ai.douyin-analysis.max-single-segment-seconds", () -> "60");
	}

	@BeforeEach
	void resetStubs() {
		LEGACY.resetAll();
		QWEN.resetAll();
		reset(media, segmented);
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
		// 任务书 #58：平台 text 行须挂带密凭据（seeder/env 兜底已删），否则执行层 503
		attachPlatformTextCredential();
	}

	@Test
	@DisplayName("progressive+≤60s+qwen → 200 + 归一 6 字段 + 扣积分（Java 路径）")
	void progressiveJavaPathReturnsNormalizedAndConsumesCredit() throws Exception {
		stubQwenAnalysis();
		stubCreditsOk();

		String token = tokenCodec.create(
				DouyinMediaTarget.progressive("https://v3-web.douyinvod.com/play.mp4", Map.of(), "file.mp4", 30L));

		client().post().uri("/api/douyin/analyze-video").contentType(MediaType.APPLICATION_JSON)
				.header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
				.bodyValue(Map.of("proxyVideoUrl", "/api/douyin/proxy/" + token)).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.success").isEqualTo(true).jsonPath("$.data.videoCaptions")
				.isEqualTo("[00:01] 旁白").jsonPath("$.data.charactersDescription").isEqualTo("一位女性博主")
				.jsonPath("$.data.propsDescription").isEqualTo("一个白瓷大碗").jsonPath("$.data.runId")
				.isEqualTo("chatcmpl-1");

		LEGACY.verify(postRequestedFor(urlEqualTo("/internal/credits/consume"))
				.withRequestBody(containing("\"feature\":\"video_analysis\"")));
	}

	@Test
	@DisplayName("mode=recreation + progressive+≤60s → 200 + scenes 归一 + 扣积分（复刻分镜）")
	void recreationModeReturnsScenes() throws Exception {
		stubQwenRecreation();
		stubCreditsOk();

		String token = tokenCodec.create(
				DouyinMediaTarget.progressive("https://v3-web.douyinvod.com/play.mp4", Map.of(), "file.mp4", 30L));

		client().post().uri("/api/douyin/analyze-video").contentType(MediaType.APPLICATION_JSON)
				.header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
				.bodyValue(Map.of("proxyVideoUrl", "/api/douyin/proxy/" + token, "mode", "recreation")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(true)
				.jsonPath("$.data.scenes[0].shotDescription").isEqualTo("中景正面视角，镜头缓慢推进")
				.jsonPath("$.data.scenes[0].sceneEnvironment").isEqualTo("面馆内部，暖黄灯光").jsonPath("$.data.overallStyle")
				.isEqualTo("日系暖色").jsonPath("$.data.runId").isEqualTo("chatcmpl-r");

		QWEN.verify(postRequestedFor(urlEqualTo("/chat/completions")).withRequestBody(containing("短视频分镜分析师")));
		LEGACY.verify(postRequestedFor(urlEqualTo("/internal/credits/consume"))
				.withRequestBody(containing("\"feature\":\"video_analysis\"")));
	}

	@Test
	@DisplayName("mode=recreation + >60s → 422 暂不支持分段视频")
	void recreationModeRejectsLongVideos() {
		String token = tokenCodec.create(
				DouyinMediaTarget.progressive("https://v3-web.douyinvod.com/play.mp4", Map.of(), "file.mp4", 90L));

		client().post().uri("/api/douyin/analyze-video").contentType(MediaType.APPLICATION_JSON)
				.header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
				.bodyValue(Map.of("proxyVideoUrl", "/api/douyin/proxy/" + token, "mode", "recreation")).exchange()
				.expectStatus().isEqualTo(422).expectBody().jsonPath("$.error").isEqualTo("复刻分析暂不支持分段视频");
	}

	@Test
	@DisplayName("mode 非法值 → 400")
	void invalidModeReturns400() {
		client().post().uri("/api/douyin/analyze-video").contentType(MediaType.APPLICATION_JSON)
				.header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
				.bodyValue(Map.of("proxyVideoUrl", "/api/douyin/proxy/x", "mode", "both")).exchange().expectStatus()
				.isBadRequest().expectBody().jsonPath("$.error").isEqualTo("分析模式无效");
	}

	@Test
	@DisplayName("video_url 走公开代理地址（经 PUBLIC_BACKEND_ORIGIN）")
	void javaPathSendsPublicProxyVideoUrl() throws Exception {
		stubQwenAnalysis();
		stubCreditsOk();

		String token = tokenCodec.create(
				DouyinMediaTarget.progressive("https://v3-web.douyinvod.com/play.mp4", Map.of(), "file.mp4", 20L));

		client().post().uri("/api/douyin/analyze-video").contentType(MediaType.APPLICATION_JSON)
				.header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
				.bodyValue(Map.of("proxyVideoUrl", "/api/douyin/proxy/" + token)).exchange().expectStatus().isOk();

		QWEN.verify(postRequestedFor(urlEqualTo("/chat/completions"))
				.withRequestBody(containing(PUBLIC_ORIGIN + "/api/douyin/proxy/" + token))
				.withRequestBody(containing("\"type\":\"video_url\"")));
	}

	@Test
	@DisplayName("缺少时长 → 422")
	void missingDurationReturns422() {
		String token = tokenCodec.create(
				DouyinMediaTarget.progressive("https://v3-web.douyinvod.com/play.mp4", Map.of(), "file.mp4", null));

		client().post().uri("/api/douyin/analyze-video").contentType(MediaType.APPLICATION_JSON)
				.header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
				.bodyValue(Map.of("proxyVideoUrl", "/api/douyin/proxy/" + token)).exchange().expectStatus()
				.isEqualTo(422).expectBody().jsonPath("$.error").isEqualTo("未能识别视频时长，请重新提取后再分析");
	}

	@Test
	@DisplayName("时长 >10 分钟 → 422")
	void tooLongDurationReturns422() {
		String token = tokenCodec.create(
				DouyinMediaTarget.progressive("https://v3-web.douyinvod.com/play.mp4", Map.of(), "file.mp4", 601L));

		client().post().uri("/api/douyin/analyze-video").contentType(MediaType.APPLICATION_JSON)
				.header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
				.bodyValue(Map.of("proxyVideoUrl", "/api/douyin/proxy/" + token)).exchange().expectStatus()
				.isEqualTo(422).expectBody().jsonPath("$.error").isEqualTo("当前仅支持分析 10 分钟以内的抖音视频，建议选择 30 秒到 2 分钟的视频");
	}

	@Test
	@DisplayName("progressive+>60s → Java FFmpeg 分段分析")
	void overThresholdProgressiveUsesJavaSegments() throws Exception {
		when(media.prepareDouyinVideo(any())).thenReturn(Mono.just("source"));
		when(media.createClips(eq("source"), anyLong(), eq(30)))
				.thenReturn(Mono.just(java.util.List.of("c1", "c2", "c3")));
		when(segmented.analyze(eq("douyin"), eq(java.util.List.of("c1", "c2", "c3")), any(), any()))
				.thenReturn(Mono.just(Map.of("merged", "segmented-java-result")));

		String token = tokenCodec.create(
				DouyinMediaTarget.progressive("https://v3-web.douyinvod.com/play.mp4", Map.of(), "file.mp4", 90L));

		client().post().uri("/api/douyin/analyze-video").contentType(MediaType.APPLICATION_JSON)
				.header("X-Grassland-Identity", sign(ACCOUNT, "merchant")).header("Cookie", "y1.sid=s%3Atok.sig")
				.bodyValue(Map.of("proxyVideoUrl", "/api/douyin/proxy/" + token)).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.success").isEqualTo(true).jsonPath("$.data.merged")
				.isEqualTo("segmented-java-result");

		QWEN.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
	}

	@Test
	@DisplayName("legacy 无 kind 的 token（切流窗口兼容）→ Java 路径正常解析")
	void legacyFormatTokenWorksOnJavaPath() throws Exception {
		stubQwenAnalysis();
		stubCreditsOk();

		// 直接以 legacy payload 形态（无 kind 字段）手工签一个 token。
		String token = legacyFormatToken();

		client().post().uri("/api/douyin/analyze-video").contentType(MediaType.APPLICATION_JSON)
				.header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
				.bodyValue(Map.of("proxyVideoUrl", "/api/douyin/proxy/" + token)).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.success").isEqualTo(true);
	}

	@Test
	@DisplayName("未登录 → 401")
	void noAssertionReturns401() {
		client().post().uri("/api/douyin/analyze-video").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("proxyVideoUrl", "/api/douyin/proxy/anything")).exchange().expectStatus()
				.isEqualTo(401).expectBody().jsonPath("$.error").isEqualTo("未登录");
	}

	@Test
	@DisplayName("缺少 proxyVideoUrl → 400（legacy schema 文案）")
	void missingProxyVideoUrlReturns400() {
		client().post().uri("/api/douyin/analyze-video").contentType(MediaType.APPLICATION_JSON)
				.header("X-Grassland-Identity", sign(ACCOUNT, "merchant")).bodyValue(Map.of()).exchange().expectStatus()
				.isBadRequest().expectBody().jsonPath("$.error").isEqualTo("缺少视频代理地址");
	}

	@Test
	@DisplayName("非法 proxyVideoUrl（非白名单源）→ 400")
	void invalidProxyVideoUrlReturns400() {
		client().post().uri("/api/douyin/analyze-video").contentType(MediaType.APPLICATION_JSON)
				.header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
				.bodyValue(Map.of("proxyVideoUrl", "https://evil.com/api/douyin/proxy/x")).exchange().expectStatus()
				.isBadRequest().expectBody().jsonPath("$.error").isEqualTo("视频代理地址无效");
	}

	// ---------------------------------------------------------------- stubs

	private void stubQwenAnalysis() throws Exception {
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("video_captions", "[00:01] 旁白");
		content.put("characters_description", "一位女性博主");
		content.put("voice_description", "清亮");
		content.put("props_description", "一个白瓷大碗");
		content.put("scene_description", "面馆");
		String contentJson = mapper.writeValueAsString(content);
		Map<String, Object> response = Map.of("id", "chatcmpl-1", "choices",
				java.util.List.of(Map.of("message", Map.of("content", contentJson))), "usage",
				Map.of("prompt_tokens", 100, "completion_tokens", 200));
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(mapper.writeValueAsString(response))));
	}

	private void stubQwenRecreation() throws Exception {
		Map<String, Object> scene = new LinkedHashMap<>();
		scene.put("shot_description", "中景正面视角，镜头缓慢推进");
		scene.put("character_description", "年轻女性博主");
		scene.put("action_movement", "夹起牛肉面");
		scene.put("dialogue_voiceover", "汤底浓郁");
		scene.put("scene_environment", "面馆内部，暖黄灯光");
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("scenes", java.util.List.of(scene));
		content.put("overall_style", "日系暖色");
		String contentJson = mapper.writeValueAsString(content);
		Map<String, Object> response = Map.of("id", "chatcmpl-r", "choices",
				java.util.List.of(Map.of("message", Map.of("content", contentJson))), "usage",
				Map.of("prompt_tokens", 100, "completion_tokens", 200));
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(mapper.writeValueAsString(response))));
	}

	private void stubCreditsOk() {
		LEGACY.stubFor(get(urlEqualTo("/internal/marketplace/reputation/" + ACCOUNT + "/ai-entitlement"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"success\":true,\"data\":{\"accountId\":\"" + ACCOUNT
								+ "\",\"aiQuotaMultiplierBps\":10000,\"policyVersion\":1}}")));
		LEGACY.stubFor(post(urlEqualTo("/internal/credits/consume"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"success\":true,\"data\":{\"source\":\"quota\","
								+ "\"policyVersion\":1,\"transactionId\":"
								+ "\"44444444-4444-4444-4444-444444444444\"}}")));
	}

	private void stubLegacyAnalyzeOk(String merged) throws Exception {
		Map<String, Object> envelope = Map.of("success", true, "data", Map.of("merged", merged));
		LEGACY.stubFor(post(urlEqualTo("/api/douyin/analyze-video")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(mapper.writeValueAsString(envelope))));
	}

	/** legacy douyin-proxy.service.ts 形态 payload（无 kind 字段），同 secret 手工签发。 */
	private String legacyFormatToken() {
		try {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("v", 1);
			payload.put("exp", System.currentTimeMillis() + 900_000L);
			payload.put("playableVideoUrl", "https://v3-web.douyinvod.com/play.mp4");
			payload.put("durationSeconds", 25);
			String json = mapper.writeValueAsString(payload);
			java.util.Base64.Encoder encoder = java.util.Base64.getUrlEncoder().withoutPadding();
			String encoded = encoder.encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8),
					"HmacSHA256"));
			return encoded + "."
					+ encoder.encodeToString(mac.doFinal(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
