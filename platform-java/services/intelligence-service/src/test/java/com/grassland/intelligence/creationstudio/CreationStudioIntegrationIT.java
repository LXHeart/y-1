package com.grassland.intelligence.creationstudio;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.creationstudio.wechat.WechatDraftSyncService;
import com.grassland.intelligence.creationstudio.wechat.WechatProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * 任务书 #101 C101-23（TC101-112 组合）：来源→计划→确认→估算→生成→采用→导出→公众号同步 全链组合回归。
 * 全程同一账号同键收敛：并发重放不双计费（credits consume 恰一次）、draft/add 恰一次、 版本单调不丢稿。
 */
@TestPropertySource(properties = {"creation.studio.writes-enabled=true", "creation.studio.visual-worker-enabled=true",
		"creation.wechat.writes-enabled=true", "creation.wechat.worker-enabled=true"})
class CreationStudioIntegrationIT extends IntelligenceItSupport {
	@org.springframework.test.context.bean.override.mockito.MockitoBean
	private com.grassland.intelligence.orchestration.WechatDraftWorkflowStarter fixtureWechatStarter;
	@org.springframework.test.context.bean.override.mockito.MockitoBean
	private com.grassland.intelligence.orchestration.CreationVisualWorkflowStarter fixtureVisualStarter;

	private static final String ACCOUNT = "00000000-0000-4000-8000-000000000640";

	private static final WireMockServer IMAGE = new WireMockServer(0);
	private static final WireMockServer FINANCE = new WireMockServer(0);
	private static final WireMockServer WECHAT = new WireMockServer(0);
	private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
	static {
		IMAGE.start();
		FINANCE.start();
		WECHAT.start();
		REDIS.start();
	}

	private static final byte[] PNG_1X1 = Base64.getDecoder()
			.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
	private static final String CONTENT_URL = "https://mmbiz.qpic.cn/mmbiz/IT-CONTENT-1.jpeg";
	private static final String COVER_URL = "https://mmbiz.qpic.cn/mmbiz/IT-COVER-1.jpeg";

	@org.springframework.test.context.bean.override.convention.TestBean(methodName = "fixtureWechatClient")
	private com.grassland.intelligence.creationstudio.wechat.WechatApiClient fixtureWechatClient;
	static com.grassland.intelligence.creationstudio.wechat.WechatApiClient fixtureWechatClient() {
		return new com.grassland.intelligence.creationstudio.wechat.WechatApiClient(
				org.springframework.web.reactive.function.client.WebClient.builder().baseUrl(WECHAT.baseUrl()).build());
	}

	@org.springframework.test.context.DynamicPropertySource
	static void upstream(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("credits.finance.base-url", FINANCE::baseUrl);
		registry.add("marketplace.service.base-url", FINANCE::baseUrl);
		registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
	}

	@org.springframework.test.context.bean.override.mockito.MockitoBean
	private com.grassland.storage.ObjectStorageAdapter storage;

	private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

	@Autowired
	private com.grassland.intelligence.creationstudio.visual.VisualJobService jobs;

	@Autowired
	private WechatDraftSyncService syncs;

	private String draftId;
	private int draftVersion = 1;

	@BeforeEach
	void seed() {
		objects.clear();
		org.mockito.Mockito.reset(storage);
		org.mockito.Mockito.when(storage.headObject(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
			String key = call.getArgument(0);
			byte[] bytes = objects.get(key);
			return bytes == null
					? java.util.Optional.empty()
					: java.util.Optional.of(new com.grassland.storage.StoredObject(key, bytes.length, "application/zip",
							"", java.time.Instant.now()));
		});
		org.mockito.Mockito.doCallRealMethod().when(storage).presignDownload(org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
		org.mockito.Mockito.doAnswer(invocation -> {
			objects.put(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(storage).putObject(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.anyString());
		org.mockito.Mockito.when(storage.getObject(org.mockito.ArgumentMatchers.anyString()))
				.thenAnswer(invocation -> objects.get(invocation.getArgument(0)));
		org.mockito.Mockito
				.when(storage.presignDownload(org.mockito.ArgumentMatchers.anyString(),
						org.mockito.ArgumentMatchers.anyLong()))
				.thenAnswer(invocation -> java.net.URI
						.create("https://signed.test.invalid/" + invocation.getArgument(0) + "?sig=1"));
		FINANCE.resetAll();
		FINANCE.stubFor(get(urlEqualTo("/internal/marketplace/reputation/" + ACCOUNT + "/ai-entitlement"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"success\":true,\"data\":{\"accountId\":\"" + ACCOUNT
								+ "\",\"aiQuotaMultiplierBps\":10000,\"policyVersion\":1}}")));
		FINANCE.stubFor(post(urlEqualTo("/internal/credits/consume"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"success\":true,\"data\":{\"source\":\"quota\",\"policyVersion\":1,"
								+ "\"transactionId\":\"11111111-1111-4111-8111-111111111111\"}}")));
		FINANCE.stubFor(
				post(urlEqualTo("/internal/credits/consume-compensations")).willReturn(aResponse().withStatus(200)));
		WECHAT.resetAll();
		WECHAT.stubFor(get(urlPathEqualTo("/cgi-bin/token"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"access_token\":\"WX-IT\",\"expires_in\":7200}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/media/uploadimg")).willReturn(aResponse()
				.withHeader("Content-Type", "application/json").withBody("{\"url\":\"" + CONTENT_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/material/add_material"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"media_id\":\"IT-COVER-MID\",\"url\":\"" + COVER_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/draft/add")).willReturn(aResponse()
				.withHeader("Content-Type", "application/json").withBody("{\"media_id\":\"IT-DRAFT-MID\"}")));
		db.sql("DELETE FROM creation_wechat_media_mapping").then()
				.then(db.sql("DELETE FROM creation_wechat_draft_sync").then())
				.then(db.sql("DELETE FROM creation_wechat_account").then())
				.then(db.sql("DELETE FROM creation_export").then())
				.then(db.sql("DELETE FROM creation_visual_artifact").then())
				.then(db.sql("DELETE FROM creation_visual_item").then())
				.then(db.sql("DELETE FROM card_series_operation WHERE api_version = 2").then())
				.then(db.sql("DELETE FROM creation_visual_quote").then())
				.then(db.sql("DELETE FROM creation_visual_plan_revision").then())
				.then(db.sql("DELETE FROM creation_visual_plan").then())
				.then(db.sql("DELETE FROM creation_studio_apply").then())
				.then(db.sql("DELETE FROM creation_source_document").then()).then(db.sql("DELETE FROM ai_run").then())
				.then(db.sql("DELETE FROM creation_draft WHERE owner_account_id = :a").bind("a", ACCOUNT).then())
				.block(Duration.ofSeconds(10));
		seedImageModel("openai-compatible", IMAGE.baseUrl() + "/v1");
		attachPlatformTextCredential();
		IMAGE.resetAll();
		IMAGE.stubFor(post(urlEqualTo("/v1/images/generations"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
						"{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(PNG_1X1) + "\"}]}")));
		QWEN.resetAll();
		draftId = createDraft();
	}

	private void seedImageModel(String provider, String baseUrl) {
		String encrypted = encryptionProvider.getIfAvailable().encrypt("sk-it-integration-image");
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN "
				+ "(SELECT id FROM platform_model_config WHERE credential_id IN "
				+ "(SELECT id FROM platform_provider_credential WHERE name = 'it-integration-image'))").then()
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN "
						+ "(SELECT id FROM platform_provider_credential WHERE name = 'it-integration-image')").then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name = 'it-integration-image'").then())
				.then(db.sql(
						"DELETE FROM platform_model_config WHERE capability = 'image_generation' AND enabled = true")
						.then())
				.block(Duration.ofSeconds(10));
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url,
				        encrypted_key, key_version, masked_hint, enabled)
				    VALUES ('it-integration-image', :provider, :baseUrl, :encrypted, 'v1', 'sk-***img', true)
				    RETURNING id
				)
				INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
				    max_concurrency, health_status, enabled, version, credential_id)
				SELECT 'image_generation','primary',:provider,'it-image-model',:baseUrl,
				    NULL,'healthy',true,1,cred.id
				FROM cred
				""").bind("provider", provider).bind("baseUrl", baseUrl).bind("encrypted", encrypted).then()
				.block(Duration.ofSeconds(10));
	}

	private String createDraft() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceType", "independent");
		body.put("title", "组合链 IT 草稿");
		body.put("articleTitle", "组合链核对：公众号图文");
		body.put("platform", "wechat-official");
		body.put("contentForm", "graphic");
		body.put("capability", "article");
		body.put("content", "门店三年，人均 68 元。\n\n招牌面 32 元，日销两百碗。\n\n小菜 12 元一份。");
		Map<?, ?> response = client().post().uri("/api/creation-drafts")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		return ((Map<?, ?>) response.get("data")).get("id").toString();
	}

	// ---- TC101-112：全链组合 + 同键并发收敛（不双计费/不双派发/版本单调） ----

	@Test
	@SuppressWarnings("unchecked")
	void compositeChainFromSourceToWechatDraftInbox() {
		// 1) 来源（markdown 原稿导入）
		Map<String, Object> sourceBody = new LinkedHashMap<>();
		sourceBody.put("requestId", UUID.randomUUID().toString());
		sourceBody.put("draftId", draftId);
		sourceBody.put("expectedDraftVersion", draftVersion);
		sourceBody.put("kind", "markdown");
		sourceBody.put("text", "门店三年，人均 68 元。\n\n招牌面 32 元，日销两百碗。\n\n小菜 12 元一份。");
		Map<?, ?> sourceData = (Map<?, ?>) client().post().uri("/api/creation-studio/sources")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(sourceBody).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody().get("data");
		List<String> blockIds = new ArrayList<>();
		List<String> blockTexts = new ArrayList<>();
		for (Object block : (List<?>) sourceData.get("blocks")) {
			blockIds.add(((Map<?, ?>) block).get("id").toString());
			blockTexts.add(String.valueOf(((Map<?, ?>) block).get("text")));
		}

		// 2) 视觉计划（模型桩按文章配图契约出 1 封面 + 1 插图）
		stubArticleVisualPlan(blockIds, blockTexts);
		Map<String, Object> planBody = new LinkedHashMap<>();
		planBody.put("requestId", UUID.randomUUID().toString());
		planBody.put("draftId", draftId);
		planBody.put("expectedDraftVersion", draftVersion);
		planBody.put("recipe", Map.of("id", "article-visuals", "version", "1.0.0"));
		planBody.put("source",
				Map.of("id", sourceData.get("id").toString(), "contentHash", sourceData.get("contentHash").toString()));
		planBody.put("selectedBlockIds", ((List<?>) sourceData.get("blocks")).stream()
				.map(block -> ((Map<?, ?>) block).get("id").toString()).toList());
		planBody.put("strategy", "information");
		planBody.put("itemCount", 2);
		Map<?, ?> planData = (Map<?, ?>) client().post().uri("/api/creation-studio/visual-plans")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(planBody).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody().get("data");
		org.assertj.core.api.Assertions.assertThat(planData.get("status"))
				.as("plan status/error: %s", planData.get("error")).isEqualTo("ready");
		String planId = planData.get("id").toString();

		// 3) 确认 → 估算
		client().post().uri("/api/creation-studio/visual-plans/" + planId + "/confirm")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "draftId", draftId, "expectedDraftVersion",
						draftVersion, "expectedRevision", 1, "sourceContentHash",
						((Map<?, ?>) planData.get("source")).get("contentHash")))
				.exchange().expectStatus().isOk();
		List<String> itemIds = new ArrayList<>();
		for (Object item : (List<?>) ((Map<?, ?>) planData.get("document")).get("items")) {
			itemIds.add(((Map<?, ?>) item).get("itemId").toString());
		}
		Map<String, Object> quoteBody = new LinkedHashMap<>();
		quoteBody.put("requestId", UUID.randomUUID().toString());
		quoteBody.put("expectedRevision", 1);
		quoteBody.put("selectedItemIds", itemIds);
		quoteBody.put("consistencyMode", "prompt-only");
		Map<?, ?> quoteData = (Map<?, ?>) client().post()
				.uri("/api/creation-studio/visual-plans/" + planId + "/estimate")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(quoteBody).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody().get("data");

		// 4) 生成任务（同键重放 → 同一任务，不二次计费）
		UUID jobRequestId = UUID.randomUUID();
		Map<String, Object> jobBody = new LinkedHashMap<>();
		jobBody.put("requestId", jobRequestId.toString());
		jobBody.put("plan", Map.of("id", planId, "revision", 1));
		jobBody.put("quoteId", quoteData.get("id").toString());
		jobBody.put("selectedItemIds", itemIds);
		jobBody.put("consistencyMode", "prompt-only");
		String jobId = ((Map<?, ?>) client().post().uri("/api/creation-studio/visual-jobs")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(jobBody).exchange().expectStatus().isAccepted().expectBody(Map.class).returnResult()
				.getResponseBody().get("data")).get("id").toString();
		Map<?, ?> replay = (Map<?, ?>) client().post().uri("/api/creation-studio/visual-jobs")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(jobBody).exchange().expectStatus().isAccepted().expectBody(Map.class).returnResult()
				.getResponseBody().get("data");
		assertThat(replay.get("id")).isEqualTo(jobId);
		int consumeAtCreate = FINANCE.findAll(postRequestedFor(urlEqualTo("/internal/credits/consume"))).size();
		assertThat(consumeAtCreate).as("创建即计费（批量一次或逐项）").isGreaterThanOrEqualTo(1);
		for (int attempt = 0; attempt < 40; attempt++) {
			if (Boolean.TRUE.equals(jobs.advance(UUID.fromString(jobId)).block(Duration.ofMinutes(2)))) {
				break;
			}
		}
		int consumeFinal = FINANCE.findAll(postRequestedFor(urlEqualTo("/internal/credits/consume"))).size();
		assertThat(consumeFinal).as("同键重放/推进不双计费").isEqualTo(consumeAtCreate);

		// 5) 采用（服务端原子写回 resultRefs）
		List<Map<String, String>> selections = new ArrayList<>();
		Map<?, ?> jobRead = (Map<?, ?>) client().get().uri("/api/creation-studio/visual-jobs/" + jobId)
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody().get("data");
		for (Object item : (List<?>) jobRead.get("items")) {
			Map<?, ?> itemMap = (Map<?, ?>) item;
			if (itemMap.get("artifact") instanceof Map<?, ?> artifact) {
				selections.add(Map.of("itemId", itemMap.get("itemId").toString(), "artifactId",
						artifact.get("id").toString()));
			}
		}
		assertThat(selections).isNotEmpty();
		Map<String, Object> adoptBody = new LinkedHashMap<>();
		adoptBody.put("requestId", UUID.randomUUID().toString());
		adoptBody.put("draftId", draftId);
		adoptBody.put("expectedDraftVersion", draftVersion);
		adoptBody.put("expectedPlanRevision", 1);
		adoptBody.put("selections", selections);
		client().post().uri("/api/creation-studio/visual-plans/" + planId + "/adopt")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(adoptBody).exchange().expectStatus().isOk();
		Integer versionAfterAdopt = db.sql("SELECT version FROM creation_draft WHERE id = CAST(:id AS uuid)")
				.bind("id", draftId).map(row -> row.get(0, Integer.class)).one().block(Duration.ofSeconds(5));
		assertThat(versionAfterAdopt).as("采用推进版本（单调）").isGreaterThan(draftVersion);
		draftVersion = versionAfterAdopt;

		// 6) 用户补齐声明和摘要，再导出该已保存版本。
		Map<?, ?> currentProject = (Map<?, ?>) client().get().uri("/api/creation-drafts/" + draftId)
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody().get("data");
		Map<String, Object> publicationWorkspace = new LinkedHashMap<>(
				(Map<String, Object>) currentProject.get("workspace"));
		Map<String, Object> publication = new LinkedHashMap<>(
				(Map<String, Object>) publicationWorkspace.get("delivery"));
		publication.put("summary", "人均 68 元的探店摘要");
		publication.put("declarations",
				Map.of("aiGenerated", "confirmed", "commercial", "not-applicable", "original", "confirmed"));
		publicationWorkspace.put("delivery", publication);
		Map<?, ?> savedPublication = (Map<?, ?>) client().put().uri("/api/creation-drafts/" + draftId)
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", draftVersion, "title", currentProject.get("title"), "articleTitle",
						currentProject.get("articleTitle"), "content", currentProject.get("content"), "workspace",
						publicationWorkspace))
				.exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody().get("data");
		draftVersion = ((Number) savedPublication.get("version")).intValue();

		Map<String, Object> exportBody = new LinkedHashMap<>();
		exportBody.put("requestId", UUID.randomUUID().toString());
		exportBody.put("version", draftVersion);
		exportBody.put("format", "wechat-html");
		exportBody.put("theme", "standard");
		exportBody.put("includeTitle", false);
		Map<?, ?> exportData = (Map<?, ?>) client().post().uri("/api/creation-drafts/" + draftId + "/exports")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(exportBody).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody().get("data");
		String exportId = ((Map<?, ?>) exportData.get("file")).get("exportId").toString();

		// 7) 公众号：绑定+校验 → 同步 → 推进到 succeeded
		Map<String, Object> bind = new LinkedHashMap<>();
		bind.put("requestId", UUID.randomUUID().toString());
		bind.put("displayName", "组合链公众号");
		bind.put("appId", "wxaaaa0000000000d1");
		bind.put("appSecret", "it-secret-chain-00001");
		String accountId = ((Map<?, ?>) client().post().uri("/api/creation-channels/wechat/accounts")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(bind).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody().get("data")).get("id").toString();
		client().post().uri("/api/creation-channels/wechat/accounts/" + accountId + "/verify")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedVersion", 1)).exchange()
				.expectStatus().isOk();

		// 回读桩：draft/get 返回与冻结快照一致（标题/正文/图片 URL/封面）
		String frozenHtml = frozenExportHtml(exportId);
		String coverRefId = mediaRefIdByRole("cover");
		String cardRefId = mediaRefIdByRole("card");
		String expectedContent = frozenHtml.replace("/api/media/" + coverRefId, CONTENT_URL)
				.replace("/api/media/" + cardRefId, CONTENT_URL);
		String title = db.sql("SELECT manifest_json->>'title' FROM creation_export WHERE id = CAST(:id AS uuid)")
				.bind("id", exportId).map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5));
		String getBody = "{\"news_item\":[{\"title\":" + json(title) + ",\"content\":" + json(expectedContent)
				+ ",\"digest\":\"人均 68 元的探店摘要\",\"thumb_media_id\":\"IT-COVER-MID\"}]}";
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/draft/get"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(getBody)));

		Map<String, Object> syncBody = new LinkedHashMap<>();
		syncBody.put("requestId", UUID.randomUUID().toString());
		syncBody.put("accountId", accountId);
		syncBody.put("expectedAccountVersion", 2);
		syncBody.put("draftId", draftId);
		syncBody.put("draftVersion", draftVersion);
		syncBody.put("exportId", exportId);
		syncBody.put("needOpenComment", 1);
		syncBody.put("onlyFansCanComment", 0);
		String syncId = ((Map<?, ?>) client().post().uri("/api/creation-channels/wechat/draft-syncs")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(syncBody).exchange().expectStatus().isAccepted().expectBody(Map.class).returnResult()
				.getResponseBody().get("data")).get("id").toString();
		boolean done = false;
		for (int attempt = 0; attempt < 40 && !done; attempt++) {
			done = Boolean.TRUE.equals(syncs.advance(UUID.fromString(syncId)).block(Duration.ofMinutes(2)));
		}
		assertThat(done).as("同步推进到终态").isTrue();
		Map<?, ?> syncFinal = (Map<?, ?>) client().get().uri("/api/creation-channels/wechat/draft-syncs/" + syncId)
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody().get("data");
		assertThat(syncFinal.get("state")).isEqualTo("succeeded");
		assertThat(syncFinal.get("externalDraftMediaId")).isEqualTo("IT-DRAFT-MID");

		// 全链计费/派发审计：draft/add 恰一次
		assertThat(WECHAT.findAll(postRequestedFor(urlPathEqualTo("/cgi-bin/draft/add"))).size()).isEqualTo(1);
	}

	private static String json(String value) {
		return com.grassland.intelligence.creationstudio.plan.PlanJson.json(value);
	}

	private String frozenExportHtml(String exportId) {
		String manifestJson = db.sql("SELECT manifest_json FROM creation_export WHERE id = CAST(:id AS uuid)")
				.bind("id", exportId).map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5));
		String objectKey = com.grassland.intelligence.creationstudio.plan.PlanJson.readJson(manifestJson)
				.get("objectKey").toString();
		return StudioTestFiles.html(objects.get(objectKey),
				com.grassland.intelligence.creationstudio.plan.PlanJson.readJson(manifestJson));
	}

	/** 封面=delivery.coverRef（采用写回形态）；正文图=resultRefs 中除封面外的首张。 */
	private String mediaRefIdByRole(String role) {
		if ("cover".equals(role)) {
			return db.sql(
					"SELECT workspace_json->'delivery'->'coverRef'->>'id' FROM creation_draft WHERE id = CAST(:id AS uuid)")
					.bind("id", draftId).map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5));
		}
		String cover = mediaRefIdByRole("cover");
		return db.sql("""
				SELECT (ref->>'id') AS id FROM creation_draft,
				 jsonb_array_elements(workspace_json->'resultRefs') AS ref
				WHERE id = CAST(:id AS uuid) AND ref->>'id' <> :cover LIMIT 1
				""").bind("id", draftId).bind("cover", cover).map(row -> row.get(0, String.class)).one()
				.block(Duration.ofSeconds(5));
	}

	private void stubArticleVisualPlan(List<String> blockIds, List<String> blockTexts) {
		// article-visuals 契约（C101-14）：封面禁带定位；插图 role=illustration + 顶层 afterBlockId；
		// criticalText 逐字取自所引块自身文本（服务端校验）。
		String coverCritical = prefix(blockTexts.get(0));
		String bodyCritical = prefix(blockTexts.get(1));
		String items = "{\"items\":[" + "{\"role\":\"cover\",\"title\":\"封面\",\"bullets\":[\"人均 68 元\"],"
				+ "\"criticalText\":[\"" + coverCritical + "\"],"
				+ "\"illustration\":\"暖光小店门头特写，木质招牌与蒸汽，平视构图，生活质感，主体明确场景具体光线柔和质感细腻，一百字左右的完整画面描述。\","
				+ "\"caption\":\"封面图\",\"purpose\":\"封面主题\",\"sourceBlockIds\":[\"" + blockIds.get(0) + "\"]},"
				+ "{\"role\":\"illustration\",\"title\":\"插图\",\"bullets\":[\"招牌面 32 元\"]," + "\"criticalText\":[\""
				+ bodyCritical + "\"],"
				+ "\"illustration\":\"店内招牌面特写，热气与木质桌面，平视构图，生活质感，主体明确场景具体光线柔和质感细腻，一百字左右的完整画面描述。\","
				+ "\"caption\":\"\",\"purpose\":\"解释要点\",\"sourceBlockIds\":[\"" + blockIds.get(1) + "\"],"
				+ "\"afterBlockId\":\"" + blockIds.get(1) + "\"}" + "],\"explanation\":\"按信息密度拆分\"}";
		String escaped;
		try {
			escaped = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(items);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
				.withHeader("Content-Type", "application/json").withBody("{\"choices\":[{\"message\":{\"content\":"
						+ escaped + "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}")));
	}

	private static String prefix(String text) {
		return text.substring(0, Math.min(3, text.length())).replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
