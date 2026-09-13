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
 * 任务书 #101 C101-12（API101-17）：视觉成品原子采用。 TC101-056～060——数据库级断言：引用只替换所选
 * item、人工文案不动、版本/归属/终态校验 409、重放一次版本变更、超限拒绝。
 */
@TestPropertySource(properties = {"creation.studio.writes-enabled=true"})
class VisualAdoptionIT extends IntelligenceItSupport {

	private static final String ACCOUNT = "00000000-0000-4000-8000-00000000060c";
	private static final String ACCOUNT_B = "00000000-0000-4000-8000-00000000060d";

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
						+ "\"transactionId\":\"22222222-2222-4222-8222-222222222222\"}}")));
		FINANCE.stubFor(
				post(urlEqualTo("/internal/credits/consume-compensations")).willReturn(aResponse().withStatus(200)));
		db.sql("DELETE FROM creation_visual_artifact").then().then(db.sql("DELETE FROM creation_visual_item").then())
				.then(db.sql("DELETE FROM card_series_operation WHERE api_version = 2").then())
				.then(db.sql("DELETE FROM creation_visual_quote").then())
				.then(db.sql("DELETE FROM creation_visual_plan_revision").then())
				.then(db.sql("DELETE FROM creation_visual_plan").then())
				.then(db.sql("DELETE FROM creation_studio_apply").then())
				.then(db.sql("DELETE FROM creation_source_document").then())
				.then(db.sql("DELETE FROM creation_draft WHERE owner_account_id IN (:a, :b)").bind("a", ACCOUNT)
						.bind("b", ACCOUNT_B).then())
				.block(java.time.Duration.ofSeconds(10));
		seedImageModel("openai-compatible", IMAGE.baseUrl() + "/v1");
		attachPlatformTextCredential();
		IMAGE.resetAll();
		QWEN.resetAll();
		IMAGE.stubFor(post(urlEqualTo("/v1/images/generations"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
						"{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(PNG_1X1) + "\"}]}")));
		draftId = createDraft();
		draftVersion = 1;
	}

	private void seedImageModel(String provider, String baseUrl) {
		String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-it-adopt-image");
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN "
				+ "(SELECT id FROM platform_model_config WHERE credential_id IN "
				+ "(SELECT id FROM platform_provider_credential WHERE name = 'it-adopt-image'))").then()
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN "
						+ "(SELECT id FROM platform_provider_credential WHERE name = 'it-adopt-image')").then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name = 'it-adopt-image'").then())
				.then(db.sql(
						"DELETE FROM platform_model_config WHERE capability = 'image_generation' AND enabled = true")
						.then())
				.block(java.time.Duration.ofSeconds(10));
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url,
				        encrypted_key, key_version, masked_hint, enabled)
				    VALUES ('it-adopt-image', :provider, :baseUrl, :encrypted, 'v1', 'sk-***img', true)
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

	private String createDraft() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceType", "independent");
		body.put("title", "采用 IT 草稿");
		body.put("platform", "xiaohongshu");
		body.put("contentForm", "graphic");
		body.put("capability", "article");
		body.put("content", "门店三年，人均 68 元。\n\n招牌面 32 元，日销两百碗。\n\n小菜 12 元一份。\n\n晚饭人均 45 元。\n\n加菜另算 10 元。\n\n会员再省 8 元。");
		Map<?, ?> response = client().post().uri("/api/creation-drafts")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		return ((Map<?, ?>) response.get("data")).get("id").toString();
	}

	// ---- helpers：来源 → 计划 → 确认 → 估算 → 任务 → 终态 ----

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
		client().post().uri("/api/creation-studio/visual-plans/" + plan.get("id") + "/confirm")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "draftId", draftId, "expectedDraftVersion",
						draftVersion, "expectedRevision", 1, "sourceContentHash",
						((Map<?, ?>) plan.get("source")).get("contentHash")))
				.exchange().expectStatus().isOk();
		return (Map<String, Object>) plan;
	}

	@SuppressWarnings("unchecked")
	private List<String> itemIdsOf(Map<String, Object> plan) {
		List<String> ids = new ArrayList<>();
		for (Object item : (List<?>) ((Map<?, ?>) plan.get("document")).get("items")) {
			ids.add(((Map<?, ?>) item).get("itemId").toString());
		}
		return ids;
	}

	/** 创建任务并推进到全部成功；返回 GET job 的 data（items 含 artifact 引用）。 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> succeededJob(Map<String, Object> plan, List<String> itemIds) {
		Map<String, Object> quoteBody = new LinkedHashMap<>();
		quoteBody.put("requestId", UUID.randomUUID().toString());
		quoteBody.put("expectedRevision", 1);
		quoteBody.put("selectedItemIds", itemIds);
		quoteBody.put("consistencyMode", "prompt-only");
		Map<?, ?> quote = (Map<?, ?>) client().post()
				.uri("/api/creation-studio/visual-plans/" + plan.get("id") + "/estimate")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(quoteBody).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody().get("data");
		Map<String, Object> createBody = new LinkedHashMap<>();
		createBody.put("requestId", UUID.randomUUID().toString());
		createBody.put("plan", Map.of("id", plan.get("id").toString(), "revision", 1));
		createBody.put("quoteId", quote.get("id").toString());
		createBody.put("selectedItemIds", itemIds);
		createBody.put("consistencyMode", "prompt-only");
		Map<?, ?> job = (Map<?, ?>) client().post().uri("/api/creation-studio/visual-jobs")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(createBody).exchange().expectStatus().isAccepted().expectBody(Map.class).returnResult()
				.getResponseBody().get("data");
		String jobId = job.get("id").toString();
		for (int attempt = 0; attempt < 40; attempt++) {
			if (Boolean.TRUE.equals(jobs.advance(UUID.fromString(jobId)).block(java.time.Duration.ofMinutes(5)))) {
				break;
			}
		}
		return (Map<String, Object>) ((Map<?, ?>) client().get().uri("/api/creation-studio/visual-jobs/" + jobId)
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody().get("data"));
	}

	private record AdoptRequest(List<Map<String, String>> selections, Integer expectedDraftVersion,
			Integer expectedPlanRevision, String draftId) {

		AdoptRequest withDraftVersion(int version) {
			return new AdoptRequest(selections, version, expectedPlanRevision, draftId);
		}

		AdoptRequest withPlanRevision(int revision) {
			return new AdoptRequest(selections, expectedDraftVersion, revision, draftId);
		}
	}

	private AdoptRequest adoptBody(List<Map<String, String>> selections) {
		return new AdoptRequest(selections, draftVersion, 1, draftId);
	}

	private Map<?, ?> adopt(String planId, AdoptRequest request, String account, Integer expectedStatus) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("draftId", request.draftId());
		body.put("expectedDraftVersion", request.expectedDraftVersion());
		body.put("expectedPlanRevision", request.expectedPlanRevision());
		body.put("selections", request.selections());
		var result = client().post().uri("/api/creation-studio/visual-plans/" + planId + "/adopt")
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isEqualTo(expectedStatus).expectBody(Map.class)
				.returnResult();
		return result.getResponseBody() == null ? null : (Map<?, ?>) result.getResponseBody().get("data");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> loadDraftWorkspace() {
		Map<?, ?> response = client().get().uri("/api/creation-drafts/" + draftId)
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		Map<?, ?> data = (Map<?, ?>) response.get("data");
		return (Map<String, Object>) data.get("workspace");
	}

	/** 写回工作区（预置人工文案/旧引用），推进 draftVersion。 */
	private void saveWorkspace(Map<String, Object> workspace) {
		client().put().uri("/api/creation-drafts/" + draftId).header("X-Grassland-Identity", sign(ACCOUNT, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", draftVersion, "title",
						"采用 IT 草稿", "content", "门店三年，人均 68 元。", "workspace", workspace))
				.exchange().expectStatus().isOk();
		draftVersion += 1;
	}

	// ---- TC101-056：采用一个候选，只替换该 item，其他文案与引用不变 ----

	@Test
	@SuppressWarnings("unchecked")
	void adoptSingleCandidateReplacesOnlyThatItem() {
		Map<String, Object> plan = readyPlan(3);
		List<String> itemIds = itemIdsOf(plan);
		Map<String, Object> job = succeededJob(plan, itemIds);
		List<Map<String, Object>> items = (List<Map<String, Object>>) job.get("items");
		Map<?, ?> coverArtifact = (Map<?, ?>) items.get(0).get("artifact");
		Map<?, ?> secondArtifact = (Map<?, ?>) items.get(1).get("artifact");

		// 先采用第 1 项（封面）——它的引用是后续要验证「不被替换」的无关素材。
		Map<?, ?> first = adopt(plan.get("id").toString(),
				adoptBody(List.of(Map.of("itemId", itemIds.get(0), "artifactId", coverArtifact.get("id").toString()))),
				ACCOUNT, 200);
		assertThat(first.get("alreadyApplied")).isEqualTo(false);
		draftVersion = (Integer) first.get("appliedVersion");

		// 预置人工文案（delivery 文本字段），采用后必须保留（§6.5 不覆盖人工编辑）。
		Map<String, Object> workspace = loadDraftWorkspace();
		Map<String, Object> delivery = new LinkedHashMap<>(
				(Map<String, ?>) workspace.getOrDefault("delivery", Map.of()));
		delivery.put("shareCopy", "人工分享配文不覆盖");
		delivery.put("summary", "人工摘要");
		Map<String, Object> saveWorkspace = new LinkedHashMap<>(workspace);
		saveWorkspace.put("delivery", delivery);
		saveWorkspace(saveWorkspace);

		// 采用第 2 项：只替换该 item 的引用，封面引用与人工文案保留。
		Map<?, ?> applied = adopt(plan.get("id").toString(),
				adoptBody(List.of(Map.of("itemId", itemIds.get(1), "artifactId", secondArtifact.get("id").toString()))),
				ACCOUNT, 200);
		assertThat(applied.get("alreadyApplied")).isEqualTo(false);
		draftVersion = (Integer) applied.get("appliedVersion");

		Map<String, Object> after = loadDraftWorkspace();
		List<Map<String, Object>> refs = (List<Map<String, Object>>) after.get("resultRefs");
		assertThat(refs).hasSize(2);
		// 文档顺序：封面在前、第 2 项在后（媒体顺序=计划顺序）
		assertThat(refs.get(0).get("id")).isEqualTo(((Map<?, ?>) coverArtifact.get("deliveryMediaRef")).get("id"));
		assertThat(refs.get(1).get("id")).isEqualTo(((Map<?, ?>) secondArtifact.get("deliveryMediaRef")).get("id"));
		assertThat(refs.get(1).get("cardId")).isEqualTo(
				((Map<?, ?>) ((List<?>) ((Map<?, ?>) plan.get("document")).get("items")).get(1)).get("cardId"));
		// 人工文案不被覆盖；封面已采用 → coverRef 保留
		Map<?, ?> deliveryAfter = (Map<?, ?>) after.get("delivery");
		assertThat(deliveryAfter.get("shareCopy")).isEqualTo("人工分享配文不覆盖");
		assertThat(deliveryAfter.get("summary")).isEqualTo("人工摘要");
		assertThat(((Map<?, ?>) deliveryAfter.get("coverRef")).get("id"))
				.isEqualTo(((Map<?, ?>) coverArtifact.get("deliveryMediaRef")).get("id"));
		assertThat((List<?>) deliveryAfter.get("mediaRefs")).hasSize(2);
		// resultAssetIds 含两项采用媒体（清理保护锚点）
		String assets = db.sql("SELECT result_asset_ids::text FROM creation_draft WHERE id = CAST(:id AS uuid)")
				.bind("id", draftId).map(row -> row.get(0, String.class)).one().block(java.time.Duration.ofSeconds(5));
		assertThat(assets).contains(((Map<?, ?>) coverArtifact.get("deliveryMediaRef")).get("id").toString(),
				((Map<?, ?>) secondArtifact.get("deliveryMediaRef")).get("id").toString());
	}

	// ---- TC101-057：采用时版本变化 → 409，旧结果保持 ----

	@Test
	void adoptRejectsStaleVersions() {
		Map<String, Object> plan = readyPlan(2);
		List<String> itemIds = itemIdsOf(plan);
		Map<String, Object> job = succeededJob(plan, itemIds);
		String artifactId = ((Map<?, ?>) ((Map<?, ?>) ((List<?>) job.get("items")).get(0)).get("artifact")).get("id")
				.toString();
		AdoptRequest request = adoptBody(List.of(Map.of("itemId", itemIds.get(0), "artifactId", artifactId)));
		adopt(plan.get("id").toString(), request.withDraftVersion(99), ACCOUNT, 409);
		adopt(plan.get("id").toString(), request.withPlanRevision(2), ACCOUNT, 409);
		Map<String, Object> workspace = loadDraftWorkspace();
		assertThat(workspace.getOrDefault("resultRefs", List.of())).isEqualTo(List.of());
	}

	// ---- TC101-058：其他账号/其他草稿/item 不匹配拒绝且不写版本 ----

	@Test
	@SuppressWarnings("unchecked")
	void adoptRejectsForeignOwnerDraftAndMismatchedCandidate() {
		Map<String, Object> plan = readyPlan(2);
		List<String> itemIds = itemIdsOf(plan);
		Map<String, Object> job = succeededJob(plan, itemIds);
		String artifactId = ((Map<?, ?>) ((Map<?, ?>) ((List<?>) job.get("items")).get(0)).get("artifact")).get("id")
				.toString();
		// 其他账号（无该计划）→ 404
		adopt(plan.get("id").toString(), adoptBody(List.of(Map.of("itemId", itemIds.get(0), "artifactId", artifactId))),
				ACCOUNT_B, 404);
		// draftId 不符 → 409
		adopt(plan.get("id").toString(),
				new AdoptRequest(List.of(Map.of("itemId", itemIds.get(0), "artifactId", artifactId)), draftVersion, 1,
						UUID.randomUUID().toString()),
				ACCOUNT, 409);
		// artifact 不属于该 item → 409
		adopt(plan.get("id").toString(), adoptBody(List.of(Map.of("itemId", itemIds.get(1), "artifactId", artifactId))),
				ACCOUNT, 409);
		Map<String, Object> workspace = loadDraftWorkspace();
		assertThat(workspace.getOrDefault("resultRefs", List.of())).isEqualTo(List.of());
	}

	// ---- TC101-060：重复采用一次版本变更；未知 artifactId/同 item 多选拒绝 ----

	@Test
	@SuppressWarnings("unchecked")
	void repeatedAdoptionIsSingleVersionChange() {
		Map<String, Object> plan = readyPlan(2);
		List<String> itemIds = itemIdsOf(plan);
		Map<String, Object> job = succeededJob(plan, itemIds);
		Map<?, ?> firstArtifact = (Map<?, ?>) ((Map<?, ?>) ((List<?>) job.get("items")).get(0)).get("artifact");
		Map<?, ?> secondArtifact = (Map<?, ?>) ((Map<?, ?>) ((List<?>) job.get("items")).get(1)).get("artifact");
		List<Map<String, String>> both = List.of(
				Map.of("itemId", itemIds.get(0), "artifactId", firstArtifact.get("id").toString()),
				Map.of("itemId", itemIds.get(1), "artifactId", secondArtifact.get("id").toString()));

		Map<?, ?> first = adopt(plan.get("id").toString(), adoptBody(both), ACCOUNT, 200);
		int versionAfterFirst = (Integer) first.get("appliedVersion");
		assertThat(first.get("alreadyApplied")).isEqualTo(false);
		// 封面在所选内 → coverRef 落位（role=cover、指向封面交付媒体）
		Map<String, Object> workspace = loadDraftWorkspace();
		Map<?, ?> coverRef = (Map<?, ?>) ((Map<?, ?>) workspace.get("delivery")).get("coverRef");
		assertThat(coverRef).isNotNull();
		assertThat(coverRef.get("id")).isEqualTo(((Map<?, ?>) firstArtifact.get("deliveryMediaRef")).get("id"));
		assertThat(coverRef.get("role")).isEqualTo("cover");
		assertThat((List<?>) workspace.get("resultRefs")).hasSize(2);

		draftVersion = versionAfterFirst;
		Map<?, ?> replay = adopt(plan.get("id").toString(), adoptBody(both), ACCOUNT, 200);
		assertThat(replay.get("alreadyApplied")).isEqualTo(true);
		assertThat(replay.get("appliedVersion")).isEqualTo(versionAfterFirst);
		// 未知 artifactId → 404
		adopt(plan.get("id").toString(),
				adoptBody(List.of(Map.of("itemId", itemIds.get(0), "artifactId", UUID.randomUUID().toString()))),
				ACCOUNT, 404);
		// 同 item 多选 → 400
		adopt(plan.get("id").toString(),
				adoptBody(List.of(Map.of("itemId", itemIds.get(0), "artifactId", firstArtifact.get("id").toString()),
						Map.of("itemId", itemIds.get(0), "artifactId", secondArtifact.get("id").toString()))),
				ACCOUNT, 400);
	}
}
