package com.grassland.intelligence.creationstudio;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * 任务书 #101 C101-18（TC101-081~086 / V-M2）：真实文件导出。 解压核对 ZIP 条目与 manifest、实际
 * sha256 与字节数、媒体真实字节、历史版本快照语义、权限 404、同键幂等重建、缺媒体 failed 不伪装 ready、旧 manifest
 * 格式回归不变。
 */
@TestPropertySource(properties = {"creation.studio.writes-enabled=true"})
class CreationExportIT extends IntelligenceItSupport {

	private static final String ACCOUNT = "00000000-0000-4000-8000-000000000610";
	private static final String ACCOUNT_B = "00000000-0000-4000-8000-000000000611";

	private static final byte[] PNG_1X1 = java.util.Base64.getDecoder()
			.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

	@org.springframework.test.context.bean.override.mockito.MockitoBean
	private com.grassland.storage.ObjectStorageAdapter storage;

	private final java.util.Map<String, byte[]> objects = new java.util.concurrent.ConcurrentHashMap<>();

	private String draftId;

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
		db.sql("DELETE FROM creation_export").then().then(db.sql("DELETE FROM creation_visual_artifact").then())
				.then(db.sql("DELETE FROM creation_visual_item").then())
				.then(db.sql("DELETE FROM card_series_operation WHERE api_version = 2").then())
				.then(db.sql("DELETE FROM creation_visual_quote").then())
				.then(db.sql("DELETE FROM creation_visual_plan_revision").then())
				.then(db.sql("DELETE FROM creation_visual_plan").then())
				.then(db.sql("DELETE FROM creation_source_document").then())
				.then(db.sql("DELETE FROM creation_draft WHERE owner_account_id IN (:a, :b)").bind("a", ACCOUNT)
						.bind("b", ACCOUNT_B).then())
				.block(java.time.Duration.ofSeconds(10));
		draftId = createDraft();
	}

	private String createDraft() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceType", "independent");
		body.put("title", "导出 IT 草稿");
		body.put("articleTitle", "历史版本导出核对");
		body.put("platform", "wechat-official");
		body.put("contentForm", "graphic");
		body.put("capability", "article");
		body.put("content", "v1 正文：人均 68 元。\n\n招牌面 32 元。");
		Map<?, ?> response = client().post().uri("/api/creation-drafts")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		return ((Map<?, ?>) response.get("data")).get("id").toString();
	}

	private String seedMedia() {
		String objectKey = "it-export/" + UUID.randomUUID();
		objects.put(objectKey, PNG_1X1);
		return db.sql("""
				INSERT INTO media_reference(owner_account_id,purpose,object_key,mime_type,status,size_bytes)
				VALUES (:owner,'reference',:key,'image/png','active',70) RETURNING id
				""").bind("owner", ACCOUNT).bind("key", objectKey).map(row -> row.get(0, UUID.class).toString()).one()
				.block(java.time.Duration.ofSeconds(5));
	}

	/** 已采用引用写进草稿（resultRefs+delivery），PUT 后 version+1。 */
	private void adoptRefs(String coverMedia, String cardMedia) {
		Map<String, Object> workspace = new LinkedHashMap<>();
		workspace.put("schemaVersion", 1);
		workspace.put("capability", "article");
		workspace.put("resultRefs", List.of(
				Map.of("id", coverMedia, "refType", "media", "role", "cover", "cardId", "card-1", "position", 1),
				Map.of("id", cardMedia, "refType", "media", "role", "card", "cardId", "card-2", "position", 2)));
		Integer version = db.sql("SELECT version FROM creation_draft WHERE id = CAST(:id AS uuid)").bind("id", draftId)
				.map(row -> row.get(0, Integer.class)).one().block(java.time.Duration.ofSeconds(5));
		client().put().uri("/api/creation-drafts/" + draftId).header("X-Grassland-Identity", sign(ACCOUNT, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", version, "title",
						"导出 IT 草稿", "content", "v1 正文：人均 68 元。\n\n招牌面 32 元。", "workspace", workspace))
				.exchange().expectStatus().isOk();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> exportNew(String account, String format, Integer version, UUID requestId) {
		return exportNew(account, format, version, requestId, false);
	}

	private Map<String, Object> exportNew(String account, String format, Integer version, UUID requestId,
			boolean includeTitle) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", requestId.toString());
		body.put("version", version);
		body.put("format", format);
		body.put("theme", "standard");
		body.put("includeTitle", includeTitle);
		var result = client().post().uri("/api/creation-drafts/" + draftId + "/exports")
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult();
		return (Map<String, Object>) result.getResponseBody().get("data");
	}

	// ---- TC101-081/082：ZIP 解压核对——真实条目、hash、媒体字节、manifest ----

	@Test
	@SuppressWarnings("unchecked")
	void zipBundleContainsRealFilesHashesAndMediaBytes() throws Exception {
		String coverMedia = seedMedia();
		String cardMedia = seedMedia();
		adoptRefs(coverMedia, cardMedia);
		Map<String, Object> data = exportNew(ACCOUNT, "bundle-zip", 2, UUID.randomUUID());
		assertThat(data.get("format")).isEqualTo("bundle-zip");
		Map<String, Object> file = (Map<String, Object>) data.get("file");
		assertThat(file.get("contentType")).isEqualTo("application/zip");
		assertThat((Integer) file.get("sizeBytes")).isGreaterThan(0);
		assertThat((String) file.get("url")).contains("creation-exports/");
		// 签名 URL 不进任何持久 manifest（manifest_json 只存 objectKey/hash）
		String manifestJson = db.sql("SELECT manifest_json::text FROM creation_export WHERE id = CAST(:id AS uuid)")
				.bind("id", file.get("exportId")).map(row -> row.get(0, String.class)).one()
				.block(java.time.Duration.ofSeconds(5));
		assertThat(manifestJson).doesNotContain("sig=").doesNotContain("https://signed");
		// 对象字节真实可读且为 ZIP：解压核对条目与媒体字节
		byte[] zipBytes = objects.get(objectKeyOf(manifestJson));
		Map<String, byte[]> entries = unzip(zipBytes);
		assertThat(entries).containsKeys("README.txt", "article.html", "article.md", "article.txt", "manifest.json",
				"publication.json", "sources.json", "images/01-cover.png", "images/02-content.png");
		assertThat(new String(entries.get("article.html"))).contains("人均 68 元");
		assertThat(entries.get("images/01-cover.png")).isEqualTo(PNG_1X1);
		// 文件级 sha256 与响应一致
		String sha = com.grassland.intelligence.media.MediaChecksums.sha256(zipBytes);
		assertThat(file.get("sha256")).isEqualTo(sha);
	}

	private String manifestJsonOf(String exportId) {
		return db.sql("SELECT manifest_json::text FROM creation_export WHERE id = CAST(:id AS uuid)")
				.bind("id", exportId).map(row -> row.get(0, String.class)).one().block(java.time.Duration.ofSeconds(5));
	}

	private static String objectKeyOf(String manifestJson) throws Exception {
		com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
				.readTree(manifestJson);
		return node.get("objectKey").asText();
	}

	private static Map<String, byte[]> unzip(byte[] zipBytes) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				entries.put(entry.getName(), zip.readAllBytes());
			}
		}
		return entries;
	}

	// ---- TC101-083：历史版本快照（AC101-18）——v1 导出不混 v2 内容 ----

	@Test
	@SuppressWarnings("unchecked")
	void historicalVersionSnapshotIsIsolated() throws Exception {
		adoptRefs(seedMedia(), seedMedia());
		// 采用后为 v2；再编辑出 v3（历史导出锚定 v2 快照）
		int version = 2;
		// v3：标题与正文都改
		client().put().uri("/api/creation-drafts/" + draftId).header("X-Grassland-Identity", sign(ACCOUNT, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", version, "title", "v4 标题完全不同", "content", "v4 正文替换。")).exchange()
				.expectStatus().isOk();
		Map<String, Object> v3 = exportNew(ACCOUNT, "wechat-html", 2, UUID.randomUUID(), true);
		org.junit.jupiter.api.Assertions.assertNotNull(v3.get("file"),
				"export state=" + v3.get("state") + " error=" + v3.get("error"));
		Map<String, Object> file = (Map<String, Object>) v3.get("file");
		byte[] html = objects.get(objectKeyOf(manifestJsonOf(file.get("exportId").toString())));
		String htmlText = new String(unzip(html).get("article.html"));
		assertThat(htmlText).contains("人均 68 元").contains("历史版本导出核对");
		assertThat(htmlText).doesNotContain("v4 正文替换");
		// 重复下载无 AI 调用、无新版本变化
		Integer draftVersion = db.sql("SELECT version FROM creation_draft WHERE id = CAST(:id AS uuid)")
				.bind("id", draftId).map(row -> row.get(0, Integer.class)).one().block(java.time.Duration.ofSeconds(5));
		Map<String, Object> replay = exportNew(ACCOUNT, "wechat-html", version, UUID.randomUUID());
		assertThat(replay.get("file")).isNotNull();
		Integer after = db.sql("SELECT version FROM creation_draft WHERE id = CAST(:id AS uuid)").bind("id", draftId)
				.map(row -> row.get(0, Integer.class)).one().block(java.time.Duration.ofSeconds(5));
		assertThat(after).isEqualTo(draftVersion);
	}

	// ---- TC101-084：权限——他人导出 404；GET /exports/{id} owner 校验 ----

	@Test
	void foreignAccountCannotReadExport() {
		adoptRefs(seedMedia(), seedMedia());
		Map<String, Object> data = exportNew(ACCOUNT, "markdown", 2, UUID.randomUUID());
		String exportId = ((Map<?, ?>) data.get("file")).get("exportId").toString();
		client().get().uri("/api/creation-studio/exports/" + exportId)
				.header("X-Grassland-Identity", sign(ACCOUNT_B, null)).exchange().expectStatus().isNotFound();
		client().get().uri("/api/creation-studio/exports/" + exportId)
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).exchange().expectStatus().isOk();
	}

	// ---- TC101-085：同键幂等——重放返回同一导出；缺参数 400；缺媒体 failed 不伪装 ready ----

	@Test
	@SuppressWarnings("unchecked")
	void sameKeyReplayAndMissingMediaSemantics() {
		String media = seedMedia();
		adoptRefs(media, seedMedia());
		UUID requestId = UUID.randomUUID();
		Map<String, Object> first = exportNew(ACCOUNT, "text", 2, requestId);
		Map<String, Object> replay = exportNew(ACCOUNT, "text", 2, requestId);
		assertThat(((Map<?, ?>) replay.get("file")).get("exportId"))
				.isEqualTo(((Map<?, ?>) first.get("file")).get("exportId"));

		// 缺 requestId/version 的新格式 → 400
		client().post().uri("/api/creation-drafts/" + draftId + "/exports")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("format", "markdown")).exchange().expectStatus().isBadRequest();

		// 媒体不可用（删行）→ 整单 failed，读取返回 state/error 而非 ready
		db.sql("DELETE FROM media_reference WHERE id = CAST(:id AS uuid)").bind("id", media).then().block();
		var rejected = client().post().uri("/api/creation-drafts/" + draftId + "/exports")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "version", 2, "format", "markdown"))
				.exchange().expectStatus().isEqualTo(409).expectBody(Map.class).returnResult().getResponseBody();
		assertThat(rejected.get("code")).isEqualTo("STUDIO_MEDIA_UNAVAILABLE");
		assertThat(db.sql("SELECT count(*) FROM creation_export WHERE draft_id=:draft AND state='failed'")
				.bind("draft", UUID.fromString(draftId)).map(row -> row.get(0, Long.class)).one().block())
				.isEqualTo(1L);
	}

	// ---- TC101-086：旧 manifest 格式回归不变 ----

	@Test
	@SuppressWarnings("unchecked")
	void legacyManifestFormatUnchanged() {
		adoptRefs(seedMedia(), seedMedia());
		Map<?, ?> data = (Map<?, ?>) ((Map<?, ?>) client().post().uri("/api/creation-drafts/" + draftId + "/exports")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("version", 2)).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody()).get("data");
		assertThat(data.get("manifest")).isNotNull();
		assertThat((java.util.List<?>) data.get("downloads")).hasSize(2);
	}

	@Test
	void missingExpiredObjectRebuildsSameExportWithSeparateBuildKey() throws Exception {
		adoptRefs(seedMedia(), seedMedia());
		UUID request = UUID.randomUUID();
		var first = exportNew(ACCOUNT, "bundle-zip", 2, request);
		String id = ((Map<?, ?>) first.get("file")).get("exportId").toString();
		String oldKey = objectKeyOf(manifestJsonOf(id));
		objects.remove(oldKey);
		var rebuilt = exportNew(ACCOUNT, "bundle-zip", 2, request);
		assertThat(((Map<?, ?>) rebuilt.get("file")).get("exportId")).isEqualTo(id);
		String newKey = objectKeyOf(manifestJsonOf(id));
		assertThat(newKey).isNotEqualTo(oldKey);
		assertThat(StudioTestFiles.unzip(objects.get(newKey))).containsKeys("article.md", "article.html",
				"images/01-cover.png");
		assertThat(db.sql("SELECT count(*) FROM creation_export WHERE id=:id").bind("id", UUID.fromString(id))
				.map(row -> row.get(0, Long.class)).one().block()).isEqualTo(1L);
	}

}
