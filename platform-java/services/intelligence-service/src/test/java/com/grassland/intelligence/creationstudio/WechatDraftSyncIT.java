package com.grassland.intelligence.creationstudio;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.creationstudio.plan.PlanJson;
import com.grassland.intelligence.creationstudio.wechat.WechatDraftSyncService;
import java.time.Duration;
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
 * 任务书 #101 C101-21（TC101-096~104 / §6.8）：公众号草稿同步。 官方形状
 * WireMock（token/uploadimg/ add_material/draft add/get/batchget）；直接驱动
 * advance（worker 关闭保证确定性，workflow 骨架由 WechatDraftWorkflowTest
 * 覆盖）。核心断言：两上传接口用途正确、draft/add 恰一次、悬置标记不重发、 回读一致才
 * succeeded、凭据轮换不提交、候选有界搜索与核实不盲写。
 */
@TestPropertySource(properties = {"creation.studio.writes-enabled=true", "creation.wechat.writes-enabled=true",
		"creation.wechat.worker-enabled=true"})
class WechatDraftSyncIT extends IntelligenceItSupport {
	@org.springframework.test.context.bean.override.mockito.MockitoBean
	private com.grassland.intelligence.orchestration.WechatDraftWorkflowStarter fixtureWechatStarter;

	private static final String ACCOUNT = "00000000-0000-4000-8000-000000000620";
	private static final String ACCOUNT_B = "00000000-0000-4000-8000-000000000621";
	private static final String APP_ID = "wxaaaa0000000000f1";
	private static final String CONTENT_URL = "https://mmbiz.qpic.cn/mmbiz/IMG-CONTENT-1.jpeg";
	private static final String COVER_MEDIA_ID = "COVER-MID-1";
	private static final String COVER_URL = "https://mmbiz.qpic.cn/mmbiz/COVER-1.jpeg";

	private static final WireMockServer WECHAT = new WireMockServer(0);
	private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
	static {
		WECHAT.start();
		REDIS.start();
	}

	@org.springframework.test.context.bean.override.convention.TestBean(methodName = "fixtureWechatClient")
	private com.grassland.intelligence.creationstudio.wechat.WechatApiClient fixtureWechatClient;
	static com.grassland.intelligence.creationstudio.wechat.WechatApiClient fixtureWechatClient() {
		return new com.grassland.intelligence.creationstudio.wechat.WechatApiClient(
				org.springframework.web.reactive.function.client.WebClient.builder().baseUrl(WECHAT.baseUrl()).build());
	}

	@org.springframework.test.context.DynamicPropertySource
	static void props(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
	}

	@org.springframework.test.context.bean.override.mockito.MockitoBean
	private com.grassland.storage.ObjectStorageAdapter storage;

	private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

	@Autowired
	private WechatDraftSyncService syncService;

	private String draftId;
	private int draftVersion;
	private String accountId;
	private String exportId;
	private String frozenHtml;

	private static final byte[] PNG_1X1 = Base64.getDecoder()
			.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

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
		db.sql("DELETE FROM creation_wechat_media_mapping").then()
				.then(db.sql("DELETE FROM creation_wechat_draft_sync").then())
				.then(db.sql("DELETE FROM creation_wechat_account").then())
				.then(db.sql("DELETE FROM creation_export").then())
				.then(db.sql("DELETE FROM creation_draft WHERE owner_account_id IN (:a, :b)").bind("a", ACCOUNT)
						.bind("b", ACCOUNT_B).then())
				.block(Duration.ofSeconds(10));
		WECHAT.resetAll();
		stubToken("{\"access_token\":\"WX-TOKEN\",\"expires_in\":7200}");
		draftId = createDraft();
	}

	private void stubToken(String body) {
		WECHAT.stubFor(get(urlPathEqualTo("/cgi-bin/token"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
		return aResponse().withHeader("Content-Type", "application/json").withBody(body);
	}

	private String createDraft() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceType", "independent");
		body.put("title", "同步 IT 草稿");
		body.put("articleTitle", "公众号草稿同步核对");
		body.put("platform", "wechat-official");
		body.put("contentForm", "graphic");
		body.put("capability", "article");
		body.put("content", "人均 68 元的探店长文：招牌面 32 元。\n\n第二段：环境与服务。");
		Map<?, ?> response = client().post().uri("/api/creation-drafts")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		return ((Map<?, ?>) response.get("data")).get("id").toString();
	}

	private String seedMedia() {
		String objectKey = "it-wechat/" + UUID.randomUUID();
		objects.put(objectKey, PNG_1X1);
		return db.sql("""
				INSERT INTO media_reference(owner_account_id,purpose,object_key,mime_type,status,size_bytes)
				VALUES (:owner,'reference',:key,'image/png','active',70) RETURNING id
				""").bind("owner", ACCOUNT).bind("key", objectKey).map(row -> row.get(0, UUID.class).toString()).one()
				.block(Duration.ofSeconds(5));
	}

	/** 绑定并校验连接（active，version=2），采用封面+正文图并导出 wechat-html。 */
	private void prepareSnapshotAndAccount() {
		// 绑定 → verify（token stub 已就绪）
		Map<String, Object> bind = new LinkedHashMap<>();
		bind.put("requestId", UUID.randomUUID().toString());
		bind.put("displayName", "主号");
		bind.put("appId", APP_ID);
		bind.put("appSecret", "it-secret-plain-000000f1");
		Map<?, ?> bound = client().post().uri("/api/creation-channels/wechat/accounts")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(bind).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody();
		accountId = ((Map<?, ?>) bound.get("data")).get("id").toString();
		client().post().uri("/api/creation-channels/wechat/accounts/" + accountId + "/verify")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedVersion", 1)).exchange()
				.expectStatus().isOk();

		String cover = seedMedia();
		String card = seedMedia();
		Map<String, Object> workspace = new LinkedHashMap<>();
		workspace.put("schemaVersion", 1);
		workspace.put("capability", "article");
		workspace.put("delivery", Map.of("summary", "人均 68 元的探店摘要", "declarations",
				Map.of("aiGenerated", "confirmed", "commercial", "not-applicable", "original", "confirmed")));
		workspace.put("resultRefs",
				List.of(Map.of("id", cover, "refType", "media", "role", "cover", "cardId", "c-1", "position", 1),
						Map.of("id", card, "refType", "media", "role", "card", "cardId", "c-2", "position", 2)));
		Integer version = db.sql("SELECT version FROM creation_draft WHERE id = CAST(:id AS uuid)").bind("id", draftId)
				.map(row -> row.get(0, Integer.class)).one().block(Duration.ofSeconds(5));
		client().put().uri("/api/creation-drafts/" + draftId).header("X-Grassland-Identity", sign(ACCOUNT, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", version, "title", "同步 IT 草稿", "content",
						"人均 68 元的探店长文：招牌面 32 元。\n\n第二段：环境与服务。", "workspace", workspace))
				.exchange().expectStatus().isOk();
		draftVersion = version + 1;

		Map<String, Object> exportBody = new LinkedHashMap<>();
		exportBody.put("requestId", UUID.randomUUID().toString());
		exportBody.put("version", draftVersion);
		exportBody.put("format", "wechat-html");
		exportBody.put("theme", "standard");
		exportBody.put("includeTitle", false);
		Map<?, ?> exported = client().post().uri("/api/creation-drafts/" + draftId + "/exports")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(exportBody).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		Map<?, ?> data = (Map<?, ?>) exported.get("data");
		assertThat(data.get("file")).isNotNull();
		exportId = ((Map<?, ?>) data.get("file")).get("exportId").toString();
		String manifestJson = db.sql("SELECT manifest_json::text FROM creation_export WHERE id=:id")
				.bind("id", UUID.fromString(exportId)).map(row -> row.get(0, String.class)).one()
				.block(Duration.ofSeconds(5));
		Map<String, Object> manifest = PlanJson.readJson(manifestJson);
		frozenHtml = StudioTestFiles.html(objects.get(String.valueOf(manifest.get("objectKey"))), manifest);
		assertThat(frozenHtml).as("导出 HTML 已落对象存储").isNotNull();
		org.mockito.Mockito.verify(storage, org.mockito.Mockito.atLeastOnce()).putObject(
				org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.anyString());
	}

	private void stubHappyWechatFlow() {
		WECHAT.stubFor(
				post(urlPathEqualTo("/cgi-bin/media/uploadimg")).willReturn(json("{\"url\":\"" + CONTENT_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/material/add_material"))
				.willReturn(json("{\"media_id\":\"" + COVER_MEDIA_ID + "\",\"url\":\"" + COVER_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/draft/add")).willReturn(json("{\"media_id\":\"DRAFT-MID-1\"}")));
		stubDraftGetWithContent(frozenExpectedContent());
	}

	/** 回读内容＝冻结 HTML 的 /api/media 占位（封面+正文图）替换为微信 URL（比对要一致）。 */
	private String frozenExpectedContent() {
		return frozenHtml.replace("/api/media/" + coverMediaRefId(), CONTENT_URL)
				.replace("/api/media/" + cardMediaRefId(), CONTENT_URL);
	}

	private String mediaRefIdByRole(String role) {
		return db.sql("""
				SELECT (ref->>'id') AS id FROM creation_draft,
				 jsonb_array_elements(workspace_json->'resultRefs') AS ref
				WHERE id = CAST(:id AS uuid) AND ref->>'role' = :role LIMIT 1
				""").bind("id", draftId).bind("role", role).map(row -> row.get(0, String.class)).one()
				.block(Duration.ofSeconds(5));
	}

	private String coverMediaRefId() {
		return mediaRefIdByRole("cover");
	}

	private String cardMediaRefId() {
		return mediaRefIdByRole("card");
	}

	private void stubDraftGetWithContent(String content) {
		// 标题与摘要取冻结口径（导出 manifest 与 payload 同源：articleTitle ?? title / workspace
		// delivery）
		String title = db.sql("SELECT manifest_json->>'title' FROM creation_export WHERE id = CAST(:id AS uuid)")
				.bind("id", exportId).map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5));
		// 摘要来自草稿 workspace delivery（payload 同源；测试里 draftVersion=当前版本）
		String digest = db
				.sql("SELECT workspace_json->'delivery'->>'summary' FROM creation_draft WHERE id = CAST(:id AS uuid)")
				.bind("id", draftId).map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5));
		String body = "{\"news_item\":[{\"title\":" + PlanJson.json(title) + ",\"content\":" + PlanJson.json(content)
				+ ",\"digest\":" + PlanJson.json(digest == null ? "" : digest) + ",\"thumb_media_id\":\""
				+ COVER_MEDIA_ID + "\"}]}";
		// 后注册的 stub 覆盖先注册（WireMock 同匹配取最新）
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/draft/get")).willReturn(json(body)));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> createSync(Integer expectedStatus) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("accountId", accountId);
		body.put("expectedAccountVersion", 2);
		body.put("draftId", draftId);
		body.put("draftVersion", draftVersion);
		body.put("exportId", exportId);
		body.put("needOpenComment", 1);
		body.put("onlyFansCanComment", 0);
		var result = client().post().uri("/api/creation-channels/wechat/draft-syncs")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isEqualTo(expectedStatus == null ? 202 : expectedStatus)
				.expectBody(Map.class).returnResult().getResponseBody();
		return (Map<String, Object>) result.get("data");
	}

	private boolean advanceUntilDone(String syncId) {
		for (int attempt = 0; attempt < 40; attempt++) {
			if (Boolean.TRUE.equals(syncService.advance(UUID.fromString(syncId)).block(Duration.ofMinutes(2)))) {
				return true;
			}
		}
		return false;
	}

	private Map<String, Object> readSync(String syncId) {
		return (Map<String, Object>) ((Map<?, ?>) client().get()
				.uri("/api/creation-channels/wechat/draft-syncs/" + syncId)
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody()).get("data");
	}

	private int count(RequestPatternBuilder pattern) {
		return WECHAT.countRequestsMatching(pattern.build()).getCount();
	}

	// ---- TC101-096：合法 news 单篇——两上传接口用途正确、articles 长度 1、draft/add 一次 ----

	@Test
	void happyPathUploadsPurposefullyAndDispatchesOnce() {
		prepareSnapshotAndAccount();
		stubHappyWechatFlow();
		Map<String, Object> sync = createSync(null);
		String syncId = sync.get("id").toString();
		assertThat(sync.get("state")).isEqualTo("preparing");
		assertThat(advanceUntilDone(syncId)).isTrue();

		Map<String, Object> done = readSync(syncId);
		assertThat(done.get("state")).as(String.valueOf(done)).isEqualTo("succeeded");
		assertThat(done.get("externalDraftMediaId")).isEqualTo("DRAFT-MID-1");
		assertThat(done.get("verifiedAt")).isNotNull();
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/media/uploadimg")))).as("正文图走 uploadimg")
				.isEqualTo(1);
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/material/add_material")))).as("封面走 add_material")
				.isEqualTo(1);
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/draft/add")))).as("draft/add 恰一次").isEqualTo(1);
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/draft/get")))).isGreaterThanOrEqualTo(1);

		// draft/add 载荷形状：articles 长度 1、评论两字段显式 0/1、thumb=封面 media_id
		var journal = WECHAT.findAll(postRequestedFor(urlPathEqualTo("/cgi-bin/draft/add")));
		String addBody = journal.get(0).getBodyAsString();
		Map<String, Object> parsed = PlanJson.readJson(addBody);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> articles = (List<Map<String, Object>>) parsed.get("articles");
		assertThat(articles).hasSize(1);
		assertThat(articles.get(0)).containsEntry("need_open_comment", 1).containsEntry("only_fans_can_comment", 0)
				.containsEntry("thumb_media_id", COVER_MEDIA_ID);
		assertThat(String.valueOf(articles.get(0).get("content"))).contains(CONTENT_URL).doesNotContain("/api/media/");
		assertThat(articles.get(0)).containsEntry("digest", "人均 68 元的探店摘要");
	}

	@Test
	void rejectsIncompleteDeclarationsAndMetadataBeforeAnyChannelWrite() {
		prepareSnapshotAndAccount();
		String original = db.sql("SELECT workspace_json::text FROM creation_draft WHERE id=CAST(:id AS uuid)")
				.bind("id", draftId).map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5));
		for (String path : List.of("{delivery,declarations,aiGenerated}", "{delivery,declarations,commercial}",
				"{delivery,declarations,original}", "{delivery,summary}")) {
			db.sql("UPDATE creation_draft SET workspace_json=CAST(:workspace AS jsonb) #- CAST(:path AS text[]) WHERE id=CAST(:id AS uuid)")
					.bind("workspace", original).bind("path", path).bind("id", draftId).then()
					.block(Duration.ofSeconds(5));
			createSync(400);
		}
		db.sql("UPDATE creation_draft SET workspace_json=jsonb_set(CAST(:workspace AS jsonb), '{delivery,declarations,original}', '\"pending\"'::jsonb) WHERE id=CAST(:id AS uuid)")
				.bind("workspace", original).bind("id", draftId).then().block(Duration.ofSeconds(5));
		createSync(400);
		assertThat(db.sql("SELECT count(*) FROM creation_wechat_draft_sync WHERE draft_id=CAST(:id AS uuid)")
				.bind("id", draftId).map(row -> row.get(0, Long.class)).one().block(Duration.ofSeconds(5))).isZero();
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/draft/add")))).isZero();
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/media/uploadimg")))).isZero();
	}

	// ---- TC101-097：回读一致才 succeeded；不按标题猜匹配 ----

	@Test
	void contentMismatchNeverSucceedsEvenWithSameTitle() {
		prepareSnapshotAndAccount();
		WECHAT.stubFor(
				post(urlPathEqualTo("/cgi-bin/media/uploadimg")).willReturn(json("{\"url\":\"" + CONTENT_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/material/add_material"))
				.willReturn(json("{\"media_id\":\"" + COVER_MEDIA_ID + "\",\"url\":\"" + COVER_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/draft/add")).willReturn(json("{\"media_id\":\"DRAFT-MID-2\"}")));
		// 同标题但正文被平台改写
		stubDraftGetWithContent(frozenExpectedContent() + "<p>平台擅自追加的段落</p>");
		Map<String, Object> sync = createSync(null);
		assertThat(advanceUntilDone(sync.get("id").toString())).isTrue();
		Map<String, Object> done = readSync(sync.get("id").toString());
		assertThat(done.get("state")).isEqualTo("failed");
		assertThat(((Map<?, ?>) done.get("error")).get("code")).isEqualTo("STUDIO_CHANNEL_CONTENT_MISMATCH");
	}

	// ---- TC101-098：draft/add 5xx（无 errcode）→ unknown，无自动第二次派发 ----

	@Test
	void draftAddTransportFailureGoesUnknownWithoutRedispatch() {
		prepareSnapshotAndAccount();
		WECHAT.stubFor(
				post(urlPathEqualTo("/cgi-bin/media/uploadimg")).willReturn(json("{\"url\":\"" + CONTENT_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/material/add_material"))
				.willReturn(json("{\"media_id\":\"" + COVER_MEDIA_ID + "\",\"url\":\"" + COVER_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/draft/add"))
				.willReturn(aResponse().withStatus(500).withBody("bad gateway")));
		Map<String, Object> sync = createSync(null);
		String syncId = sync.get("id").toString();
		assertThat(advanceUntilDone(syncId)).isTrue();
		Map<String, Object> done = readSync(syncId);
		assertThat(done.get("state")).isEqualTo("unknown");
		assertThat(((Map<?, ?>) done.get("error")).get("code")).isEqualTo("STUDIO_UNKNOWN_OUTCOME");
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/draft/add")))).as("不自动第二次 draft/add").isEqualTo(1);
		// 再推进仍不重发
		for (int i = 0; i < 3; i++) {
			syncService.advance(UUID.fromString(syncId)).block(Duration.ofSeconds(30));
		}
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/draft/add")))).isEqualTo(1);
	}

	// ---- TC101-099：同键重放与同快照并发复用同一记录 ----

	@Test
	void sameKeyReplayAndSameSnapshotReuseSingleRecord() {
		prepareSnapshotAndAccount();
		stubHappyWechatFlow();
		Map<String, Object> first = createSync(null);
		String syncId = first.get("id").toString();
		assertThat(advanceUntilDone(syncId)).isTrue();
		// 终态重放（同 requestId）→ 200 同一行
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", first.get("requestId"));
		body.put("accountId", accountId);
		body.put("expectedAccountVersion", 2);
		body.put("draftId", draftId);
		body.put("draftVersion", draftVersion);
		body.put("exportId", exportId);
		body.put("needOpenComment", 1);
		body.put("onlyFansCanComment", 0);
		Map<?, ?> replay = client().post().uri("/api/creation-channels/wechat/draft-syncs")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		assertThat(((Map<?, ?>) replay.get("data")).get("id")).isEqualTo(syncId);
		// 不同 requestId、同快照 → 复用 succeeded 记录，不建第二行、不二次派发
		Map<String, Object> second = createSync(200);
		assertThat(second.get("id")).isEqualTo(syncId);
		assertThat(second.get("state")).isEqualTo("succeeded");
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/draft/add")))).isEqualTo(1);
		Long rows = db.sql("SELECT COUNT(*) FROM creation_wechat_draft_sync").map(row -> row.get(0, Long.class)).one()
				.block(Duration.ofSeconds(5));
		assertThat(rows).isEqualTo(1);
	}

	// ---- TC101-100/101：候选有界搜索 + 核实一致才成功 ----

	@Test
	@SuppressWarnings("unchecked")
	void candidatesBoundedSearchAndReconcileRequiresMatch() {
		prepareSnapshotAndAccount();
		WECHAT.stubFor(
				post(urlPathEqualTo("/cgi-bin/media/uploadimg")).willReturn(json("{\"url\":\"" + CONTENT_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/material/add_material"))
				.willReturn(json("{\"media_id\":\"" + COVER_MEDIA_ID + "\",\"url\":\"" + COVER_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/draft/add"))
				.willReturn(aResponse().withStatus(504).withBody("gateway timeout")));
		Map<String, Object> sync = createSync(null);
		String syncId = sync.get("id").toString();
		assertThat(advanceUntilDone(syncId)).isTrue();
		assertThat(readSync(syncId).get("state")).isEqualTo("unknown");

		// 候选：两页共 35 条（20/页），第三页不再请求；hasMore 由总数决定
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/draft/batchget"))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("\"offset\":0"))
				.willReturn(json(batchGetBody(35, 0, 20, "BATCH-"))));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/draft/batchget"))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("\"offset\":20"))
				.willReturn(json(batchGetBody(35, 20, 15, "BATCH-"))));
		Map<?, ?> candidates = client().get().uri("/api/creation-channels/wechat/draft-syncs/" + syncId + "/candidates")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		Map<?, ?> candidateData = (Map<?, ?>) candidates.get("data");
		List<Map<String, Object>> items = (List<Map<String, Object>>) candidateData.get("items");
		assertThat(candidateData.get("searchedCount")).isEqualTo(35);
		assertThat(candidateData.get("hasMore")).isEqualTo(false);
		// 候选不回显其他账号内容：只有 media_id/标题/时间/匹配位
		assertThat(items.get(0)).containsOnlyKeys("externalDraftMediaId", "title", "updatedAt", "contentMatches");
		// 30s 缓存：重复读取不再打 batchget
		int batchCalls = count(postRequestedFor(urlPathEqualTo("/cgi-bin/draft/batchget")));
		client().get().uri("/api/creation-channels/wechat/draft-syncs/" + syncId + "/candidates")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).exchange().expectStatus().isOk();
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/draft/batchget")))).isEqualTo(batchCalls);

		// B 账号读 A 的候选/同步 → 404
		client().get().uri("/api/creation-channels/wechat/draft-syncs/" + syncId + "/candidates")
				.header("X-Grassland-Identity", sign(ACCOUNT_B, null)).exchange().expectStatus().isNotFound();

		// 核实：错误 media_id（内容不符）→ 不成功
		stubDraftGetWithContent("<p>完全不同的内容</p>");
		Map<String, Object> reconcileBody = new LinkedHashMap<>();
		reconcileBody.put("requestId", UUID.randomUUID().toString());
		reconcileBody.put("expectedVersion", readSync(syncId).get("version"));
		reconcileBody.put("externalDraftMediaId", "OTHER-MID");
		client().post().uri("/api/creation-channels/wechat/draft-syncs/" + syncId + "/reconcile")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(reconcileBody).exchange().expectStatus().isEqualTo(409);
		assertThat(readSync(syncId).get("state")).isEqualTo("unknown");
		// 核实正确内容 → succeeded；全程 draft/add 仅一次（504 那次）
		stubDraftGetWithContent(frozenExpectedContent());
		reconcileBody.put("requestId", UUID.randomUUID().toString());
		reconcileBody.put("expectedVersion", readSync(syncId).get("version"));
		reconcileBody.put("externalDraftMediaId", "RECONCILED-MID");
		client().post().uri("/api/creation-channels/wechat/draft-syncs/" + syncId + "/reconcile")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(reconcileBody).exchange().expectStatus().isOk();
		assertThat(readSync(syncId).get("state")).isEqualTo("succeeded");
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/draft/add")))).isEqualTo(1);
	}

	/** 构造 batchget 页：第 offset+1..offset+count 条（标题不匹配快照——避免与 contentMatches 混淆）。 */
	private String batchGetBody(long total, int offset, int count, String prefix) {
		StringBuilder items = new StringBuilder();
		for (int index = 0; index < count && offset + index < total; index++) {
			if (items.length() > 0) {
				items.append(',');
			}
			items.append("{\"media_id\":\"").append(prefix).append(offset + index)
					.append("\",\"update_time\":1726262400,\"content\":{\"news_item\":[{\"title\":\"他号草稿")
					.append(offset + index).append("\",\"content\":\"<p>无关内容</p>\"}]}}");
		}
		return "{\"total_item\":" + total + ",\"item\":[" + items + "]}";
	}

	// ---- TC101-102：上传后、提交前轮换凭据 → 不提交草稿 ----

	@Test
	void credentialRotationBeforeSubmitBlocksDraftAdd() {
		prepareSnapshotAndAccount();
		WECHAT.stubFor(
				post(urlPathEqualTo("/cgi-bin/media/uploadimg")).willReturn(json("{\"url\":\"" + CONTENT_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/material/add_material"))
				.willReturn(json("{\"media_id\":\"" + COVER_MEDIA_ID + "\",\"url\":\"" + COVER_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/draft/add")).willReturn(json("{\"media_id\":\"SHOULD-NEVER\"}")));
		Map<String, Object> sync = createSync(null);
		String syncId = sync.get("id").toString();
		// 推进两轮：preparing→uploading、封面上传完成（正文图未传、未到提交）
		syncService.advance(UUID.fromString(syncId)).block(Duration.ofSeconds(30));
		syncService.advance(UUID.fromString(syncId)).block(Duration.ofSeconds(60));
		assertThat(readSync(syncId).get("state")).isEqualTo("uploading");
		// 轮换凭据（版本+1、回 unverified）
		client().post().uri("/api/creation-channels/wechat/accounts/" + accountId + "/rotate")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedVersion", 2, "appSecret",
						"it-secret-rotated-000f1"))
				.exchange().expectStatus().isOk();
		assertThat(advanceUntilDone(syncId)).isTrue();
		Map<String, Object> done = readSync(syncId);
		assertThat(done.get("state")).isEqualTo("failed");
		assertThat(((Map<?, ?>) done.get("error")).get("code")).isEqualTo("STUDIO_CHANNEL_ACCOUNT_INVALID");
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/draft/add")))).as("凭据轮换后不得提交草稿").isEqualTo(0);
	}

	// ---- TC101-103：悬置提交标记（崩溃/重放）不重复派发 ----

	@Test
	void danglingSubmitMarkerGoesUnknownWithoutRedispatch() {
		prepareSnapshotAndAccount();
		WECHAT.stubFor(
				post(urlPathEqualTo("/cgi-bin/media/uploadimg")).willReturn(json("{\"url\":\"" + CONTENT_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/material/add_material"))
				.willReturn(json("{\"media_id\":\"" + COVER_MEDIA_ID + "\",\"url\":\"" + COVER_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/draft/add")).willReturn(json("{\"media_id\":\"MARKER-MID\"}")));
		Map<String, Object> sync = createSync(null);
		String syncId = sync.get("id").toString();
		// 推进两轮（封面已传、正文未传），直接 SQL 置悬置标记（模拟标记落库后、派发前进程崩溃）
		syncService.advance(UUID.fromString(syncId)).block(Duration.ofSeconds(30));
		syncService.advance(UUID.fromString(syncId)).block(Duration.ofSeconds(60));
		db.sql("UPDATE creation_wechat_draft_sync SET state = 'submitting', draft_add_done = false"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", syncId).then().block(Duration.ofSeconds(5));
		assertThat(advanceUntilDone(syncId)).isTrue();
		assertThat(readSync(syncId).get("state")).isEqualTo("unknown");
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/draft/add")))).as("悬置标记禁止重派").isEqualTo(0);
	}

	// ---- TC101-104：token 失效有界刷新一次；压缩不达标明确拒绝 ----

	@Test
	void tokenInvalidRefreshesOnceThenRetries() {
		prepareSnapshotAndAccount();
		WECHAT.resetAll();
		// 场景桩：首次取 TOKEN-A；失效缓存后刷新得 TOKEN-B
		WECHAT.stubFor(get(urlPathEqualTo("/cgi-bin/token")).inScenario("refresh").whenScenarioStateIs("Started")
				.willReturn(json("{\"access_token\":\"TOKEN-A\",\"expires_in\":7200}")).willSetStateTo("Second"));
		WECHAT.stubFor(get(urlPathEqualTo("/cgi-bin/token")).inScenario("refresh").whenScenarioStateIs("Second")
				.willReturn(json("{\"access_token\":\"TOKEN-B\",\"expires_in\":7200}")));
		// 带 TOKEN-A 的上传被拒（40001：token 失效且未创建）；TOKEN-B 成功
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/media/uploadimg"))
				.withQueryParam("access_token", com.github.tomakehurst.wiremock.client.WireMock.containing("TOKEN-A"))
				.willReturn(json("{\"errcode\":40001,\"errmsg\":\"invalid credential\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/media/uploadimg"))
				.withQueryParam("access_token", com.github.tomakehurst.wiremock.client.WireMock.containing("TOKEN-B"))
				.willReturn(json("{\"url\":\"" + CONTENT_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/material/add_material"))
				.willReturn(json("{\"media_id\":\"" + COVER_MEDIA_ID + "\",\"url\":\"" + COVER_URL + "\"}")));
		WECHAT.stubFor(post(urlPathEqualTo("/cgi-bin/draft/add")).willReturn(json("{\"media_id\":\"DRAFT-MID-3\"}")));
		stubDraftGetWithContent(frozenExpectedContent());

		Map<String, Object> sync = createSync(null);
		assertThat(advanceUntilDone(sync.get("id").toString())).isTrue();
		assertThat(readSync(sync.get("id").toString()).get("state")).as("tokenRefresh").isEqualTo("succeeded");
	}

	@Test
	void oversizedDerivationRejectedExplicitly() {
		// 直接断言压缩阶梯终态拒绝（不删图强行成功）：1×1 PNG 也压不进 10 字节上限
		var processor = new com.grassland.intelligence.creationstudio.render.CreationImageProcessor();
		var error = org.assertj.core.api.Assertions.catchThrowableOfType(
				com.grassland.intelligence.security.IntelligenceException.class,
				() -> processor.deriveForWechat(PNG_1X1, 10, "#ffffff").block(Duration.ofSeconds(10)));
		assertThat(error).isNotNull();
		assertThat(error.code()).isEqualTo("STUDIO_LIMIT_EXCEEDED");
	}

	// ---- 取消语义（API101-31）：submitting → unknown；早期 → cancelled ----

	@Test
	void cancelEarlyCancelsAndSubmittingGoesUnknown() {
		prepareSnapshotAndAccount();
		WECHAT.stubFor(
				post(urlPathEqualTo("/cgi-bin/media/uploadimg")).willReturn(json("{\"url\":\"" + CONTENT_URL + "\"}")));
		Map<String, Object> sync = createSync(null);
		String syncId = sync.get("id").toString();
		Map<String, Object> cancelBody = new LinkedHashMap<>();
		cancelBody.put("requestId", UUID.randomUUID().toString());
		cancelBody.put("expectedVersion", readSync(syncId).get("version"));
		Map<?, ?> cancelled = client().post().uri("/api/creation-channels/wechat/draft-syncs/" + syncId + "/cancel")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(cancelBody).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		assertThat(((Map<?, ?>) cancelled.get("data")).get("state")).isEqualTo("cancelled");
		// 取消后不再推进副作用
		syncService.advance(UUID.fromString(syncId)).block(Duration.ofSeconds(30));
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/draft/add")))).isZero();

		// submitting 态取消 → unknown（不能证明取消）
		Map<String, Object> sync2 = createSync(null);
		db.sql("UPDATE creation_wechat_draft_sync SET state = 'submitting' WHERE id = CAST(:id AS uuid)")
				.bind("id", sync2.get("id").toString()).then().block(Duration.ofSeconds(5));
		cancelBody.put("requestId", UUID.randomUUID().toString());
		cancelBody.put("expectedVersion", readSync(sync2.get("id").toString()).get("version"));
		Map<?, ?> unknown = client().post()
				.uri("/api/creation-channels/wechat/draft-syncs/" + sync2.get("id").toString() + "/cancel")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(cancelBody).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		assertThat(((Map<?, ?>) unknown.get("data")).get("state")).isEqualTo("unknown");
	}

	@Test
	void identicalMediaCacheDoesNotStealPreviousSyncMappings() {
		prepareSnapshotAndAccount();
		stubHappyWechatFlow();
		var first = createSync(null);
		assertThat(advanceUntilDone(first.get("id").toString())).isTrue();
		Map<String, Object> body = Map.of("requestId", UUID.randomUUID().toString(), "accountId", accountId,
				"expectedAccountVersion", 2, "draftId", draftId, "draftVersion", draftVersion, "exportId", exportId,
				"author", "另一作者", "needOpenComment", 1, "onlyFansCanComment", 0);
		var second = (Map<?, ?>) client().post().uri("/api/creation-channels/wechat/draft-syncs")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isAccepted().expectBody(Map.class).returnResult()
				.getResponseBody().get("data");
		assertThat(advanceUntilDone(second.get("id").toString())).isTrue();
		for (Object id : List.of(first.get("id"), second.get("id")))
			assertThat(db.sql("SELECT count(*) FROM creation_wechat_media_mapping WHERE sync_id=:id")
					.bind("id", UUID.fromString(id.toString())).map(row -> row.get(0, Long.class)).one()
					.block(Duration.ofSeconds(5))).isEqualTo(3L);
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/media/uploadimg")))).isEqualTo(1);
		assertThat(count(postRequestedFor(urlPathEqualTo("/cgi-bin/material/add_material")))).isEqualTo(1);
	}

	@Test
	void imagePositionChangeCannotBeVerifiedAsMatching() {
		prepareSnapshotAndAccount();
		stubHappyWechatFlow();
		var document = org.jsoup.Jsoup.parseBodyFragment(frozenExpectedContent());
		var moved = document.select("img").last();
		moved.remove();
		document.body().prependChild(moved);
		stubDraftGetWithContent(document.body().html());
		var sync = createSync(null);
		assertThat(advanceUntilDone(sync.get("id").toString())).isTrue();
		assertThat(readSync(sync.get("id").toString()).get("state")).isEqualTo("failed");
	}

}
