package com.grassland.intelligence.creationstudio;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.creationstudio.visual.VisualJobService;
import com.grassland.intelligence.creationstudio.visual.VisualItemRepository;
import java.util.ArrayList;
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
 * 任务书 #101 C101-10（API101-13～16）：异步视觉任务。 TC101-046～051——上游经 WireMock 计数； 推进直接调
 * {@link VisualJobService#advance}（workflow 骨架的确定性由 CreationVisualWorkflowTest
 * 覆盖， 这里验证领域语义与 DB 断言，不只看 HTTP 202）。
 */
@TestPropertySource(properties = {"creation.studio.writes-enabled=true"})
class VisualJobIT extends IntelligenceItSupport {

	private static final String ACCOUNT = "00000000-0000-4000-8000-00000000060a";
	private static final String ACCOUNT_B = "00000000-0000-4000-8000-00000000060b";

	private static final byte[] PNG_1X1 = Base64.getDecoder()
			.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

	private static final WireMockServer IMAGE = new WireMockServer(0);
	private static final WireMockServer FINANCE = new WireMockServer(0);
	static {
		IMAGE.start();
		FINANCE.start();
	}

	@org.springframework.test.context.DynamicPropertySource
	static void upstream(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("credits.finance.base-url", FINANCE::baseUrl);
		registry.add("marketplace.service.base-url", FINANCE::baseUrl);
	}

	@org.springframework.test.context.bean.override.mockito.MockitoBean
	private com.grassland.storage.ObjectStorageAdapter storage;

	private final java.util.Map<String, byte[]> objects = new java.util.concurrent.ConcurrentHashMap<>();

	@Autowired
	private VisualJobService jobs;
	@Autowired
	private VisualItemRepository itemRows;

	private String draftId;
	private int draftVersion;

	@BeforeEach
	void seed() {
		objects.clear();
		org.mockito.Mockito.reset(storage);
		org.mockito.Mockito.doAnswer(invocation -> {
			objects.put(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(storage).putObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.anyString());
		org.mockito.Mockito.when(storage.getObject(org.mockito.ArgumentMatchers.anyString()))
				.thenAnswer(invocation -> objects.get(invocation.getArgument(0)));
		FINANCE.resetAll();
		FINANCE.stubFor(get(urlEqualTo("/internal/marketplace/reputation/" + ACCOUNT + "/ai-entitlement"))
				.willReturn(okJson("{\"success\":true,\"data\":{\"accountId\":\"" + ACCOUNT
						+ "\",\"aiQuotaMultiplierBps\":10000,\"policyVersion\":1}}")));
		FINANCE.stubFor(post(urlEqualTo("/internal/credits/consume"))
				.willReturn(okJson("{\"success\":true,\"data\":{\"source\":\"quota\",\"policyVersion\":1,"
						+ "\"transactionId\":\"11111111-1111-4111-8111-111111111111\"}}")));
		FINANCE.stubFor(
				post(urlEqualTo("/internal/credits/consume-compensations")).willReturn(aResponse().withStatus(200)));
		db.sql("DELETE FROM creation_visual_artifact").then().then(db.sql("DELETE FROM creation_visual_item").then())
				.then(db.sql("DELETE FROM card_series_operation WHERE api_version = 2").then())
				.then(db.sql("DELETE FROM creation_visual_quote").then())
				.then(db.sql("DELETE FROM creation_visual_plan_revision").then())
				.then(db.sql("DELETE FROM creation_visual_plan").then())
				.then(db.sql("DELETE FROM creation_studio_apply").then())
				.then(db.sql("DELETE FROM creation_source_document").then())
				.then(db.sql("DELETE FROM creation_draft WHERE owner_account_id = :a").bind("a", ACCOUNT).then())
				.block(java.time.Duration.ofSeconds(10));
		seedImageModel("openai-compatible", IMAGE.baseUrl() + "/v1");
		attachPlatformTextCredential();
		IMAGE.resetAll();
		QWEN.resetAll();
		stubUpstreamSuccess();
		draftId = createDraft();
		draftVersion = 1;
	}

	private void stubUpstreamSuccess() {
		IMAGE.stubFor(post(urlEqualTo("/v1/images/generations"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
						"{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(PNG_1X1) + "\"}]}")));
	}

	private String createDraft() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceType", "independent");
		body.put("title", "视觉任务 IT 草稿");
		body.put("platform", "xiaohongshu");
		body.put("contentForm", "graphic");
		body.put("capability", "article");
		body.put("content",
				"门店三年，人均 68 元。\n\n招牌面 32 元，日销两百碗。\n\n小菜 12 元一份。\n\n" + "晚饭人均 45 元。\n\n加菜另算 10 元。\n\n会员再省 8 元。");
		Map<?, ?> response = client().post().uri("/api/creation-drafts")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		return ((Map<?, ?>) response.get("data")).get("id").toString();
	}

	// ---- helpers：来源 → 计划（N 项）→ 确认 → 估算 ----

	@SuppressWarnings("unchecked")
	private Map<String, Object> importSource() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("draftId", draftId);
		body.put("expectedDraftVersion", draftVersion);
		body.put("kind", "markdown");
		body.put("text",
				"门店三年，人均 68 元。\n\n招牌面 32 元，日销两百碗。\n\n小菜 12 元一份。\n\n" + "晚饭人均 45 元。\n\n加菜另算 10 元。\n\n会员再省 8 元。");
		Map<?, ?> response = client().post().uri("/api/creation-studio/sources")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	private void stubModelPlan(List<String> blockIds, int count) {
		StringBuilder items = new StringBuilder("[");
		for (int index = 0; index < count; index++) {
			if (index > 0) {
				items.append(',');
			}
			items.append("{\"role\":\"").append(index == 0 ? "cover" : "content").append("\",").append("\"title\":\"第")
					.append(index + 1).append("卡\",").append("\"bullets\":[\"要点一\",\"要点二\"],")
					.append("\"criticalText\":[\"元\"],")
					.append("\"illustration\":\"暖光小店门头特写，木质招牌与蒸汽，平视构图，生活质感，" + "主体明确场景具体光线柔和质感细腻，一百字左右的完整画面描述。\",")
					.append("\"caption\":\"配文").append(index + 1).append("\",").append("\"purpose\":\"承载第")
					.append(index + 1).append("个要点\",").append("\"sourceBlockIds\":[\"")
					.append(blockIds.get(index % blockIds.size())).append("\"]}");
		}
		items.append("]");
		String raw = "{\"items\":" + items + ",\"explanation\":\"按信息密度拆分\"}";
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
	private Map<String, Object> readyPlan(int itemCount) {
		Map<String, Object> source = importSource();
		List<String> blockIds = new ArrayList<>();
		for (Object block : (List<?>) source.get("blocks")) {
			blockIds.add(((Map<?, ?>) block).get("id").toString());
		}
		stubModelPlan(blockIds, itemCount);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("draftId", draftId);
		body.put("expectedDraftVersion", draftVersion);
		body.put("recipe", Map.of("id", "social-card-series", "version", "1.0.0"));
		body.put("source",
				Map.of("id", source.get("id").toString(), "contentHash", source.get("contentHash").toString()));
		body.put("selectedBlockIds", List.of());
		body.put("strategy", "information");
		body.put("itemCount", itemCount);
		Map<?, ?> rawPlan = client().post().uri("/api/creation-studio/visual-plans")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		Map<?, ?> plan = (Map<?, ?>) rawPlan.get("data");
		// confirm
		client().post().uri("/api/creation-studio/visual-plans/" + plan.get("id") + "/confirm")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "draftId", draftId, "expectedDraftVersion",
						draftVersion, "expectedRevision", 1, "sourceContentHash",
						((Map<?, ?>) plan.get("source")).get("contentHash")))
				.exchange().expectStatus().isOk();
		return (Map<String, Object>) plan;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> quote(Map<String, Object> plan, List<String> itemIds) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("expectedRevision", 1);
		body.put("selectedItemIds", itemIds);
		body.put("consistencyMode", "prompt-only");
		return (Map<String, Object>) client().post()
				.uri("/api/creation-studio/visual-plans/" + plan.get("id") + "/estimate")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody()
				.get("data");
	}

	@SuppressWarnings("unchecked")
	private List<String> itemIdsOf(Map<String, Object> plan) {
		List<String> ids = new ArrayList<>();
		for (Object item : (List<?>) ((Map<?, ?>) plan.get("document")).get("items")) {
			ids.add(((Map<?, ?>) item).get("itemId").toString());
		}
		return ids;
	}

	/** 创建任务（prompt-only）；返回响应 data（错误态返回 null——调用方只断言状态）。 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> createJob(Map<String, Object> plan, Map<String, Object> quote, List<String> itemIds,
			Integer expectedStatus, UUID requestId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", requestId.toString());
		body.put("plan", Map.of("id", plan.get("id").toString(), "revision", 1));
		body.put("quoteId", quote.get("id").toString());
		body.put("selectedItemIds", itemIds);
		body.put("consistencyMode", "prompt-only");
		var result = client().post().uri("/api/creation-studio/visual-jobs")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isEqualTo(expectedStatus).expectBody(Map.class)
				.returnResult();
		return result.getResponseBody() == null ? null : (Map<String, Object>) result.getResponseBody().get("data");
	}

	/** 推进到终态（真实 workflow 可能并发推进，循环收敛）。 */
	private boolean advanceUntilDone(String jobId) {
		for (int attempt = 0; attempt < 40; attempt++) {
			if (Boolean.TRUE.equals(jobs.advance(UUID.fromString(jobId)).block(java.time.Duration.ofMinutes(5)))) {
				return true;
			}
		}
		return false;
	}

	private int upstreamCalls() {
		return IMAGE.getAllServeEvents().size();
	}

	// ---- TC101-046/047：整组六图，首图先行、成功后其余派发、全部成功 ----

	@Test
	void sixItemJobRunsToSucceeded() {
		Map<String, Object> plan = readyPlan(6);
		List<String> itemIds = itemIdsOf(plan);
		Map<String, Object> quote = quote(plan, itemIds);
		Map<String, Object> job = createJob(plan, quote, itemIds, 202, UUID.randomUUID());
		String jobId = job.get("id").toString();

		assertThat(advanceUntilDone(jobId)).isTrue();

		Map<?, ?> loaded = (Map<?, ?>) client().get().uri("/api/creation-studio/visual-jobs/" + jobId)
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody().get("data");
		assertThat(loaded.get("state")).isEqualTo("succeeded");
		assertThat((List<?>) loaded.get("items")).hasSize(6);
		// 6 张图各一次供应商调用；每项都有成品 artifact 与确定性 run
		assertThat(upstreamCalls()).isEqualTo(6);
		for (Object item : (List<?>) loaded.get("items")) {
			assertThat(((Map<?, ?>) item).get("state")).isEqualTo("succeeded");
			assertThat(((Map<?, ?>) item).get("artifact")).isNotNull();
		}
	}

	// ---- TC101-046：首图失败 → 后续停止派发（不自动换锚） ----

	@Test
	void firstItemFailureStopsRemaining() {
		Map<String, Object> plan = readyPlan(6);
		List<String> itemIds = itemIdsOf(plan);
		Map<String, Object> quote = quote(plan, itemIds);
		Map<String, Object> job = createJob(plan, quote, itemIds, 202, UUID.randomUUID());
		String jobId = job.get("id").toString();
		// 首项确定性失败（4xx，run 已绑定 → failed）
		IMAGE.resetAll();
		IMAGE.stubFor(post(urlEqualTo("/v1/images/generations")).willReturn(aResponse().withStatus(400)
				.withHeader("Content-Type", "application/json").withBody("{\"error\":{\"message\":\"bad image\"}}")));

		assertThat(advanceUntilDone(jobId)).isTrue();
		Map<?, ?> loaded = (Map<?, ?>) client().get().uri("/api/creation-studio/visual-jobs/" + jobId)
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody().get("data");
		assertThat(loaded.get("state")).isEqualTo("failed");
		for (Object item : (List<?>) loaded.get("items")) {
			String state = ((Map<?, ?>) item).get("state").toString();
			assertThat(state).isIn("failed", "cancelled");
		}
		// 只派发了首项（后续停止，不自动换锚/替图）
		assertThat(upstreamCalls()).isEqualTo(1);
	}

	// ---- TC101-049：未派发取消 → 已发出项不受影响、未发项停止 ----

	@Test
	void cancelBeforeDispatchCancelsAll() {
		Map<String, Object> plan = readyPlan(3);
		List<String> itemIds = itemIdsOf(plan);
		Map<String, Object> quote = quote(plan, itemIds);
		Map<String, Object> job = createJob(plan, quote, itemIds, 202, UUID.randomUUID());
		String jobId = job.get("id").toString();

		client().post().uri("/api/creation-studio/visual-jobs/" + jobId + "/cancel")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedVersion", 1)).exchange()
				.expectStatus().isOk();

		Map<?, ?> loaded = (Map<?, ?>) client().get().uri("/api/creation-studio/visual-jobs/" + jobId)
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody().get("data");
		assertThat(loaded.get("state")).isEqualTo("cancelled");
		assertThat(upstreamCalls()).isZero();
		// 版本冲突：旧版本取消被拒
		client().post().uri("/api/creation-studio/visual-jobs/" + jobId + "/cancel")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedVersion", 1)).exchange()
				.expectStatus().isEqualTo(409);
	}

	// ---- TC101-050：quote 过期/参数漂移 → 409，模型调用 0 ----

	@Test
	void expiredQuoteRejectedWithoutModelCalls() {
		Map<String, Object> plan = readyPlan(2);
		List<String> itemIds = itemIdsOf(plan);
		Map<String, Object> quote = quote(plan, itemIds);
		// 过期 quote：直接回拨过期时间
		db.sql("UPDATE creation_visual_quote SET expires_at = now() - INTERVAL '1 second'").then()
				.block(java.time.Duration.ofSeconds(10));
		createJob(plan, quote, itemIds, 409, UUID.randomUUID());
		assertThat(upstreamCalls()).isZero();

		// 异参重放：同 requestId 不同 selectedItemIds → 409 STUDIO_OPERATION_CONFLICT
		Map<String, Object> freshQuote = quote(plan, itemIds);
		UUID requestId = UUID.randomUUID();
		createJob(plan, freshQuote, itemIds, 202, requestId);
		createJob(plan, freshQuote, itemIds.subList(0, 1), 409, requestId);
	}

	// ---- TC101-051：unknown 主动重做需显式确认；确认后新 attempt 且旧项保留审计 ----

	@Test
	void unknownRedoRequiresAcknowledgement() {
		Map<String, Object> plan = readyPlan(2);
		List<String> itemIds = itemIdsOf(plan);
		Map<String, Object> quote = quote(plan, itemIds);
		// 首项 502（run 已绑定 → unknown）
		IMAGE.resetAll();
		IMAGE.stubFor(post(urlEqualTo("/v1/images/generations"))
				.willReturn(aResponse().withStatus(502).withHeader("Content-Type", "application/json").withBody("{}")));
		Map<String, Object> job = createJob(plan, quote, itemIds, 202, UUID.randomUUID());
		String jobId = job.get("id").toString();
		advanceUntilDone(jobId);
		UUID firstAttempt = UUID
				.fromString(((Map<?, ?>) ((List<?>) job.get("items")).get(0)).get("attemptId").toString());
		assertThat(itemRows.findById(firstAttempt).block(java.time.Duration.ofSeconds(10)).state())
				.isEqualTo("unknown");

		// 不带确认的新 job → 409 STUDIO_UNKNOWN_OUTCOME
		Map<String, Object> freshQuote = quote(plan, itemIds);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("plan", Map.of("id", plan.get("id").toString(), "revision", 1));
		body.put("quoteId", freshQuote.get("id").toString());
		body.put("selectedItemIds", itemIds);
		body.put("consistencyMode", "prompt-only");
		client().post().uri("/api/creation-studio/visual-jobs").header("X-Grassland-Identity", sign(ACCOUNT, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isEqualTo(409);

		// 带确认 → 新 job 新 attempt；旧 unknown 项保留审计
		body.put("requestId", UUID.randomUUID().toString());
		body.put("acknowledgedUnknownAttemptIds", List.of(firstAttempt.toString()));
		stubUpstreamSuccess();
		Map<?, ?> redone = (Map<?, ?>) client().post().uri("/api/creation-studio/visual-jobs")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isEqualTo(202).expectBody(Map.class).returnResult()
				.getResponseBody().get("data");
		assertThat(redone.get("id").toString()).isNotEqualTo(jobId);
		advanceUntilDone(redone.get("id").toString());
		assertThat(itemRows.findById(firstAttempt).block(java.time.Duration.ofSeconds(10)).state())
				.isEqualTo("unknown"); // 旧项保留
	}

	// ---- 归属隔离：B 读 A 的任务 → 404 ----

	@Test
	void foreignAccountGets404() {
		Map<String, Object> plan = readyPlan(1);
		List<String> itemIds = itemIdsOf(plan);
		Map<String, Object> quote = quote(plan, itemIds);
		Map<String, Object> job = createJob(plan, quote, itemIds, 202, UUID.randomUUID());
		client().get().uri("/api/creation-studio/visual-jobs/" + job.get("id"))
				.header("X-Grassland-Identity", sign(ACCOUNT_B, null)).exchange().expectStatus().isNotFound();
	}

	// ---- helpers ----

	private void seedImageModel(String provider, String baseUrl) {
		String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-it-job-image");
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN "
				+ "(SELECT id FROM platform_model_config WHERE credential_id IN "
				+ "(SELECT id FROM platform_provider_credential WHERE name = 'it-job-image'))").then()
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN "
						+ "(SELECT id FROM platform_provider_credential WHERE name = 'it-job-image')").then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name = 'it-job-image'").then())
				.then(db.sql(
						"DELETE FROM platform_model_config WHERE capability = 'image_generation' AND enabled = true")
						.then())
				.block(java.time.Duration.ofSeconds(10));
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url,
				        encrypted_key, key_version, masked_hint, enabled)
				    VALUES ('it-job-image', :provider, :baseUrl, :encrypted, 'v1', 'sk-***img', true)
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
}
