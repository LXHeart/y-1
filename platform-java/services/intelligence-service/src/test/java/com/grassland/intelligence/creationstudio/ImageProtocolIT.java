package com.grassland.intelligence.creationstudio;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.articleimage.ImageGenerationClient;
import com.grassland.intelligence.articleimage.ImageProtocolPolicy;
import com.grassland.intelligence.articleimage.ReferenceImage;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * 任务书 #101 C101-07（§6.6、API101-02）：openai-image 原生图片协议、参考能力与能力绑定。 TC101-030～034
 * 的本地部分（V-LIVE-IMAGE 真实模型验收另行记录，不在本类冒充）。
 *
 * <p>
 * 上游图片服务经 WireMock 模拟（协议形状断言）；计划/估算链走治理台 image_generation 行 （provider 可切换
 * openai-image / openai-compatible）观察能力目录与估算闸门。
 */
@TestPropertySource(properties = {"creation.studio.writes-enabled=true"})
class ImageProtocolIT extends IntelligenceItSupport {

	private static final String ACCOUNT = "00000000-0000-4000-8000-00000000030a";

	/** 1×1 PNG（真实验证宽高与字节不在本类——本类只验证协议请求形状）。 */
	private static final byte[] PNG_1X1 = Base64.getDecoder()
			.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

	private static final WireMockServer IMAGE = new WireMockServer(0);
	private static final WireMockServer FINANCE = new WireMockServer(0);
	static {
		IMAGE.start();
		FINANCE.start();
	}

	@org.springframework.test.context.DynamicPropertySource
	static void finance(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("credits.finance.base-url", FINANCE::baseUrl);
		registry.add("marketplace.service.base-url", FINANCE::baseUrl);
	}

	@Autowired
	private ImageGenerationClient generationClient;

	private String draftId;
	private int draftVersion;

	@BeforeEach
	void seedDraftAndClean() {
		com.github.tomakehurst.wiremock.client.WireMock.configureFor(IMAGE.isHttpsEnabled() ? "https" : "http",
				"localhost", IMAGE.port());
		FINANCE.resetAll();
		FINANCE.stubFor(com.github.tomakehurst.wiremock.client.WireMock
				.get(urlEqualTo("/internal/marketplace/reputation/" + ACCOUNT + "/ai-entitlement"))
				.willReturn(okJson("{\"success\":true,\"data\":{\"accountId\":\"" + ACCOUNT
						+ "\",\"aiQuotaMultiplierBps\":10000,\"policyVersion\":1}}")));
		FINANCE.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/internal/credits/consume"))
				.willReturn(okJson("{\"success\":true,\"data\":{\"source\":\"quota\",\"policyVersion\":1,"
						+ "\"transactionId\":\"11111111-1111-4111-8111-111111111111\"}}")));
		FINANCE.stubFor(com.github.tomakehurst.wiremock.client.WireMock
				.post(urlEqualTo("/internal/credits/consume-compensations")).willReturn(aResponse().withStatus(200)));
		db.sql("DELETE FROM creation_visual_quote").then()
				.then(db.sql("DELETE FROM creation_visual_plan_revision").then())
				.then(db.sql("DELETE FROM creation_visual_plan").then())
				.then(db.sql("DELETE FROM creation_studio_apply").then())
				.then(db.sql("DELETE FROM creation_source_document").then())
				.then(db.sql("DELETE FROM creation_draft WHERE owner_account_id = :account").bind("account", ACCOUNT)
						.then())
				.block(java.time.Duration.ofSeconds(10));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceType", "independent");
		body.put("title", "协议 IT 草稿");
		body.put("platform", "xiaohongshu");
		body.put("contentForm", "graphic");
		body.put("capability", "article");
		body.put("content", "门店三年，人均 68 元。\n\n招牌面 32 元，日销两百碗。");
		Map<String, Object> response = client().post().uri("/api/creation-drafts")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		draftId = ((Map<?, ?>) response.get("data")).get("id").toString();
		draftVersion = (Integer) ((Map<?, ?>) response.get("data")).get("version");
		attachPlatformTextCredential();
		IMAGE.resetAll();
		QWEN.resetAll();
	}

	// ---- TC101-030：原生带参考走 Multipart /images/edits ----

	@Test
	void nativeEditsCarriesReferenceBytes() {
		IMAGE.stubFor(post(urlEqualTo("/v1/images/edits"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(imageB64Response())));
		ImageGenerationClient.Endpoint endpoint = new ImageGenerationClient.Endpoint(IMAGE.baseUrl() + "/v1",
				"sk-it-image", "gpt-image", "openai-image");
		var generated = generationClient
				.generate("门头特写，木质招牌", "1024x1024", endpoint, List.of(new ReferenceImage("image/png", PNG_1X1)))
				.block(java.time.Duration.ofSeconds(10));
		assertThat(generated).isNotNull();
		assertThat(generated.base64()).isNotBlank();

		verify(postRequestedFor(urlEqualTo("/v1/images/edits"))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("name=\"model\"")));
		verify(postRequestedFor(urlEqualTo("/v1/images/edits"))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("name=\"prompt\"")));
		verify(postRequestedFor(urlEqualTo("/v1/images/edits"))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("name=\"size\"")));
		// 参考图真实字节随 Multipart 发送（不是文本描述替代）
		assertThat(containsBytes(journalBodyOf("/v1/images/edits"), PNG_1X1)).isTrue();
		// 无参考分支未被触发
		verify(0, postRequestedFor(urlEqualTo("/v1/images/generations")));
	}

	@Test
	void nativeWithoutReferenceUsesGenerations() {
		IMAGE.stubFor(post(urlEqualTo("/v1/images/generations"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(imageB64Response())));
		ImageGenerationClient.Endpoint endpoint = new ImageGenerationClient.Endpoint(IMAGE.baseUrl() + "/v1",
				"sk-it-image", "gpt-image", "openai-image");
		generationClient.generate("门头特写", "1024x1536", endpoint, List.of()).block(java.time.Duration.ofSeconds(10));
		verify(postRequestedFor(urlEqualTo("/v1/images/generations"))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("\"size\":\"1024x1536\"")));
		verify(0, postRequestedFor(urlEqualTo("/v1/images/edits")));
	}

	// ---- §5.1：超限参考图在派发前拒绝 ----

	@Test
	void oversizedReferenceRejectedBeforeDispatch() {
		byte[] oversized = new byte[ImageProtocolPolicy.OPENAI_IMAGE_MAX_REFERENCE_BYTES + 1];
		ImageGenerationClient.Endpoint endpoint = new ImageGenerationClient.Endpoint(IMAGE.baseUrl() + "/v1",
				"sk-it-image", "gpt-image", "openai-image");
		assertThatThrownBy(() -> generationClient
				.generate("门头特写", "1024x1024", endpoint, List.of(new ReferenceImage("image/png", oversized)))
				.block(java.time.Duration.ofSeconds(10))).isInstanceOf(IntelligenceException.class);
		assertThat(IMAGE.getAllServeEvents()).isEmpty();
	}

	// ---- TC101-031：旧兼容路径不发生新调用、不带参考字节 ----

	@Test
	void legacyReferenceStaysGenerationsOnly() {
		IMAGE.stubFor(post(urlEqualTo("/v1/images/generations"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(imageB64Response())));
		ImageGenerationClient.Endpoint endpoint = new ImageGenerationClient.Endpoint(IMAGE.baseUrl() + "/v1",
				"sk-it-image", "it-image-model", "openai-compatible");
		generationClient.generate("门头特写", "1024x1024", endpoint, List.of(new ReferenceImage("image/png", PNG_1X1)))
				.block(java.time.Duration.ofSeconds(10));
		verify(1, postRequestedFor(urlEqualTo("/v1/images/generations")));
		verify(0, postRequestedFor(urlEqualTo("/v1/images/edits")));
		assertThat(containsBytes(journalBodyOf("/v1/images/generations"), PNG_1X1)).isFalse();
	}

	// ---- TC101-032：MiniMax 人物参考语义保留 ----

	@Test
	void minimaxCharacterReferencePreserved() {
		IMAGE.stubFor(post(urlEqualTo("/v1/image_generation"))
				.willReturn(okJson("{\"data\":{\"image_base64\":\"" + base64Png() + "\"}}")));
		ImageGenerationClient.Endpoint endpoint = new ImageGenerationClient.Endpoint(IMAGE.baseUrl() + "/v1",
				"sk-it-image", "image-01", "minimax");
		generationClient.generate("人物一致", "1024x1024", endpoint, List.of(new ReferenceImage("image/png", PNG_1X1)))
				.block(java.time.Duration.ofSeconds(10));
		String body = new String(journalBodyOf("/v1/image_generation"));
		assertThat(body).contains("subject_reference").contains("\"character\"");
		assertThat(ImageProtocolPolicy.supportsCharacterReference(ImageProtocolPolicy.PROTOCOL_MINIMAX_CHARACTER))
				.isTrue();
		// 人物参考 ≠ 通用参考：estimate 闸门不因 MiniMax 放开 reference-image
		assertThat(ImageProtocolPolicy.supportsImageReference(ImageProtocolPolicy.PROTOCOL_MINIMAX_CHARACTER))
				.isFalse();
	}

	// ---- TC101-033：能力绑定与 SSRF 保护基线 ----

	@Test
	void openaiImageBindsImageGenerationOnly() {
		assertThatThrownBy(() -> ImageProtocolPolicy.requireImageOnlyCapability("text", "openai-image"))
				.isInstanceOf(IntelligenceException.class).hasMessageContaining("image_generation");
		assertThatThrownBy(() -> ImageProtocolPolicy.requireImageOnlyCapability("video_generation", "openai-image"))
				.isInstanceOf(IntelligenceException.class);
		// 合法绑定与其余 provider 不受影响
		ImageProtocolPolicy.requireImageOnlyCapability("image_generation", "openai-image");
		ImageProtocolPolicy.requireImageOnlyCapability("text", "openai-completions");
		// 值集注册（控制面正则与运行期白名单同源）
		assertThat(com.grassland.intelligence.ai.controlplane.PlatformProviderNames.ALL).contains("openai-image");
		assertThat(com.grassland.intelligence.ai.controlplane.PlatformProviderNames.ORIGIN_CHECKED)
				.contains("openai-image");
	}

	// ---- API101-02 能力目录 + 估算按协议放行 ----

	@Test
	void capabilitiesAndQuoteFollowActualRouting() {
		seedImageModel("openai-image", IMAGE.baseUrl() + "/v1");
		// 能力目录：openai-image → 通用参考开放
		Map<String, Object> capabilities = getCapabilities();
		Map<?, ?> image = (Map<?, ?>) capabilities.get("image");
		assertThat(image.get("protocol")).isEqualTo("openai-image");
		assertThat(image.get("referenceKinds")).isEqualTo(List.of("image"));
		assertThat(image.get("available")).isEqualTo(true);
		assertThat(image.get("generationSizes").toString()).isEqualTo("[1024x1024, 1024x1536, 1536x1024]");

		Map<String, Object> source = importSource();
		stubModelPlanFor(source);
		Map<String, Object> plan = preparePlan(source);
		client().post().uri("/api/creation-studio/visual-plans/" + plan.get("id") + "/confirm")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "draftId", draftId, "expectedDraftVersion",
						draftVersion, "expectedRevision", 1, "sourceContentHash", source.get("contentHash")))
				.exchange().expectStatus().isOk();
		// reference-image 估算在原生协议下放行（不再是 409 STUDIO_REFERENCE_UNSUPPORTED）
		estimate(plan, "reference-image").expectStatus().isOk();

		// 切回 legacy provider：能力目录回落，reference-image 估算拒绝（TC101-031 服务端口径）
		switchImageProvider("openai-compatible");
		Map<?, ?> legacyImage = (Map<?, ?>) getCapabilities().get("image");
		assertThat(legacyImage.get("protocol")).isEqualTo("legacy-generation");
		assertThat(legacyImage.get("referenceKinds")).isEqualTo(List.of());
		estimate(plan, "reference-image").expectStatus().isEqualTo(409);
		// prompt-only 始终可估算
		estimate(plan, "prompt-only").expectStatus().isOk();
	}

	// ---- helpers ----

	private static String imageB64Response() {
		return "{\"data\":[{\"b64_json\":\"" + base64Png() + "\"}]}";
	}

	private static String base64Png() {
		return Base64.getEncoder().encodeToString(PNG_1X1);
	}

	/** 字节序列包含判断（multipart 参考图字节断言）。 */
	private static boolean containsBytes(byte[] haystack, byte[] needle) {
		outer : for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	private byte[] journalBodyOf(String path) {
		return IMAGE.getAllServeEvents().stream().filter(event -> event.getRequest().getUrl().equals(path)).findFirst()
				.orElseThrow(() -> new AssertionError("upstream not called: " + path)).getRequest().getBody();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getCapabilities() {
		Map<String, Object> response = client().get().uri("/api/creation-studio/drafts/" + draftId + "/capabilities")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> importSource() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("draftId", draftId);
		body.put("expectedDraftVersion", draftVersion);
		body.put("kind", "markdown");
		body.put("text", "门店三年，人均 68 元。\n\n招牌面 32 元，日销两百碗。");
		Map<String, Object> response = client().post().uri("/api/creation-studio/sources")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	private void stubModelPlanFor(Map<String, Object> source) {
		List<String> ids = new java.util.ArrayList<>();
		for (Object block : (List<?>) source.get("blocks")) {
			ids.add(((Map<?, ?>) block).get("id").toString());
		}
		String raw = "{\"items\":[{\"role\":\"cover\",\"title\":\"第一卡\",\"bullets\":[\"要点一\"],"
				+ "\"criticalText\":[\"68\"],\"illustration\":\"暖光门头特写，木质招牌与蒸汽，平视构图，"
				+ "生活质感，主体明确场景具体光线柔和。\",\"caption\":\"配文\",\"purpose\":\"开门见山\"," + "\"sourceBlockIds\":[\""
				+ ids.get(0) + "\"]}],\"explanation\":\"单卡样例\"}";
		String escaped;
		try {
			escaped = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(raw);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
				.withHeader("Content-Type", "application/json").withBody("{\"choices\":[{\"message\":{\"content\":"
						+ escaped + "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}")));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> preparePlan(Map<String, Object> source) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("draftId", draftId);
		body.put("expectedDraftVersion", draftVersion);
		body.put("recipe", Map.of("id", "social-card-series", "version", "1.0.0"));
		body.put("source",
				Map.of("id", source.get("id").toString(), "contentHash", source.get("contentHash").toString()));
		body.put("selectedBlockIds", ((List<?>) source.get("blocks")).stream()
				.map(block -> ((Map<?, ?>) block).get("id").toString()).toList());
		body.put("strategy", "information");
		body.put("itemCount", 1);
		Map<String, Object> response = client().post().uri("/api/creation-studio/visual-plans")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec estimate(Map<String, Object> plan,
			String consistencyMode) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("expectedRevision", plan.get("revision"));
		body.put("selectedItemIds", selectedItemIdsOf(plan));
		body.put("consistencyMode", consistencyMode);
		return client().post().uri("/api/creation-studio/visual-plans/" + plan.get("id") + "/estimate")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange();
	}

	private static List<String> selectedItemIdsOf(Map<String, Object> plan) {
		Map<?, ?> document = (Map<?, ?>) plan.get("document");
		List<String> ids = new java.util.ArrayList<>();
		for (Object item : (List<?>) document.get("items")) {
			ids.add(((Map<?, ?>) item).get("itemId").toString());
		}
		return ids;
	}

	/** 种子 image_generation 平台行（provider 可切换观察能力目录）。 */
	private void seedImageModel(String provider, String baseUrl) {
		String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-it-image");
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN "
				+ "(SELECT id FROM platform_model_config WHERE credential_id IN "
				+ "(SELECT id FROM platform_provider_credential WHERE name = 'it-protocol-image'))").then()
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN "
						+ "(SELECT id FROM platform_provider_credential WHERE name = 'it-protocol-image')").then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name = 'it-protocol-image'").then())
				.then(db.sql("DELETE FROM platform_model_config WHERE capability = 'image_generation' AND enabled"
						+ " = true").then())
				.block(java.time.Duration.ofSeconds(10));
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url,
				        encrypted_key, key_version, masked_hint, enabled)
				    VALUES ('it-protocol-image', :provider, :baseUrl, :encrypted, 'v1', 'sk-***img', true)
				    RETURNING id
				)
				INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
				    max_concurrency, health_status, enabled, version, credential_id)
				SELECT 'image_generation','primary',:provider,'it-image-model',:baseUrl,
				    NULL,'healthy',true,1,cred.id
				FROM cred
				""").bind("provider", provider).bind("baseUrl", baseUrl).bind("encrypted", encrypted).then()
				.block(java.time.Duration.ofSeconds(10));
	}

	private void switchImageProvider(String provider) {
		db.sql("UPDATE platform_provider_credential SET provider = :provider WHERE name = 'it-protocol-image'")
				.bind("provider", provider).then()
				.then(db.sql("UPDATE platform_model_config SET provider = :provider "
						+ "WHERE capability = 'image_generation' AND enabled = true").bind("provider", provider).then())
				.block(java.time.Duration.ofSeconds(10));
	}
}
