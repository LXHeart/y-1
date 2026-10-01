package com.grassland.intelligence.hypit.fix2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.JsonNode;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

/**
 * C107F2-31（F14/F29 / §6.14、§8.3）：参考素材上传与跨创作入口交接。 真 PostgreSQL + WireMock
 * sidecar 桩；对象存储经 Mockito 桩提供源字节。ingest/probe 的字节与 结构真值由 broker
 * 侧测试（tests/engine/resources-routes.test.ts、tests/media/*）承载，本卡锁 Java
 * 编排面：真实字节复制（无 media: JSON 引用）、256MiB 上限、探测失败不落 ready、越权同答 404、同 project+hash
 * 复用。
 *
 * <ul>
 * <li>TC-F2-31-01 sourceContext（已固化媒体）交接物化：broker 收到的字节与源一致（binaryEqualTo）， 落
 * res- 句柄 ready 素材；重复触发幂等（不二次复制）。</li>
 * <li>TC-F2-31-02 256MiB 内合法上传：202 → ready，probe 事实落库，素材可读。</li>
 * <li>TC-F2-31-03 256MiB+1 声明先拒（413 零字节出站）+ 回执超限拒收；伪装 mp4（脚本内容）probe 拒绝 → 422，无
 * ready 素材。</li>
 * <li>TC-F2-31-04 他人 mediaId 交接 404（对象存储零读取）；本人同文件重复上传复用同一资源， 不产生重复 ready
 * 行。</li>
 * </ul>
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitFix2C31IT extends IntelligenceItSupport {

	private static final String OWNER_A = "31000000-0000-4000-8000-00000000000a";
	private static final String OWNER_B = "31000000-0000-4000-8000-00000000000b";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	static {
		SIDECAR.start();
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	@Autowired
	DatabaseClient db;

	/** mediaId 交接的真实字节复制源（生产为对象存储）。 */
	@org.springframework.test.context.bean.override.mockito.MockitoBean
	private com.grassland.storage.ObjectStorageAdapter storage;

	@org.springframework.test.context.DynamicPropertySource
	static void sidecarProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
		registry.add("hypit.internal-token", () -> "it-hypit-internal-token-0123456789abcdef");
	}

	@BeforeEach
	void clean() {
		SIDECAR.resetAll();
		org.mockito.Mockito.reset(storage);
		for (String owner : List.of(OWNER_A, OWNER_B)) {
			db.sql("DELETE FROM hypit_asset_reference WHERE project_id IN (SELECT id FROM hypit_project"
					+ " WHERE account_id = :o)").bind("o", owner).then()
					.then(db.sql("DELETE FROM hypit_asset WHERE project_id IN (SELECT id FROM hypit_project"
							+ " WHERE account_id = :o)").bind("o", owner).then())
					.then(db.sql("DELETE FROM hypit_job_event WHERE job_id IN (SELECT id FROM hypit_job"
							+ " WHERE account_id = :o)").bind("o", owner).then())
					.then(db.sql("DELETE FROM hypit_job WHERE account_id = :o").bind("o", owner).then())
					.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", owner).then())
					.then(db.sql("DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM hypit_project"
							+ " WHERE account_id = :o)").bind("o", owner).then())
					.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", owner).then())
					.then(db.sql("DELETE FROM media_reference WHERE owner_account_id = :o").bind("o", owner).then())
					.block(Duration.ofSeconds(20));
		}
	}

	// ── seed helpers ─────────────────────────────────────────────────────────────

	private UUID insertReadyProject(String owner, String sourceContextJson) {
		UUID id = UUID.randomUUID();
		var sql = db
				.sql("INSERT INTO hypit_project (id, account_id, workspace_id, title, mode, status,"
						+ " revision, head_manifest_hash, source_context) VALUES (CAST(:id AS uuid), :owner,"
						+ " CAST(:ws AS uuid), '交接工程', 'clone', 'ready', 1, :hash, CAST(:ctx AS jsonb))")
				.bind("id", id.toString()).bind("owner", owner).bind("ws", UUID.randomUUID().toString())
				.bind("hash", "a".repeat(64));
		sql = sourceContextJson == null ? sql.bindNull("ctx", String.class) : sql.bind("ctx", sourceContextJson);
		sql.then().block(Duration.ofSeconds(10));
		return id;
	}

	private UUID insertMediaReference(String owner, String status) {
		UUID mediaId = UUID.randomUUID();
		db.sql("INSERT INTO media_reference (id, owner_account_id, purpose, object_key, mime_type,"
				+ " size_bytes, checksum, status) VALUES (CAST(:id AS uuid), :owner, 'user_upload', :key,"
				+ " 'video/mp4', 48, :checksum, :status)").bind("id", mediaId.toString()).bind("owner", owner)
				.bind("key", "it/c31/" + mediaId).bind("checksum", "b".repeat(64)).bind("status", status).then()
				.block(Duration.ofSeconds(10));
		return mediaId;
	}

	private static final byte[] SOURCE_BYTES = "c31-real-media-bytes-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

	private static String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private void stubIngestExactBytes(String handle, byte[] bytes, long sizeBytes) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/resources")).withRequestBody(binaryEqualTo(bytes))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"handle\":\"%s\",\"sha256\":\"%s\",\"sizeBytes\":%d}".formatted(handle,
								sha256Hex(bytes), sizeBytes))));
	}

	private void stubIngest(String handle, String sha256, long sizeBytes) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/resources"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"handle\":\"%s\",\"sha256\":\"%s\",\"sizeBytes\":%d}".formatted(handle, sha256,
								sizeBytes))));
	}

	private void stubProbeSucceeded() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("media.probe"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"media.probe","state":"succeeded",
						 "result":{"probe":{"durationSeconds":12.5,"hasVideo":true,"hasAudio":true}}}
						""")));
	}

	private void stubProbeFailed() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("media.probe"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"media.probe","state":"failed",
						 "error":{"code":"probe_failed","message":"ffprobe failed: not a media file"}}
						""")));
	}

	private JsonNode postMultipart(String uri, String owner, org.springframework.util.MultiValueMap<String, ?> parts) {
		String body = client().post().uri(uri).header("X-Grassland-Identity", sign(owner, null))
				.contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(parts).exchange().expectStatus().is2xxSuccessful()
				.expectBody(String.class).returnResult().getResponseBody();
		return read(body);
	}

	private static JsonNode read(String body) {
		try {
			return JSON.readTree(body == null ? "{}" : body);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private org.springframework.util.MultiValueMap<String, ?> uploadParts(UUID requestId, byte[] content,
			String contentType, String filename, Long declaredLength) {
		MultipartBodyBuilder parts = new MultipartBodyBuilder();
		var part = parts.part("file", new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return filename;
			}
		}).contentType(MediaType.parseMediaType(contentType));
		if (declaredLength != null) {
			part.headers(headers -> headers.setContentLength(declaredLength));
		}
		parts.part("role", "reference");
		parts.part("requestId", requestId.toString());
		return parts.build();
	}

	// ── TC-F2-31-01：sourceContext 交接物化（真实字节复制） ──────────────────────

	@Test
	@DisplayName("TC-F2-31-01 已固化媒体交接：res- 句柄 ready，broker 字节与源一致，幂等不二次复制")
	void tc01ImportSourceCopiesRealBytes() {
		UUID mediaId = insertMediaReference(OWNER_A, "active");
		UUID projectId = insertReadyProject(OWNER_A,
				"{\"kind\":\"media\",\"id\":\"" + mediaId + "\",\"label\":\"门头参考视频\"}");
		org.mockito.Mockito.when(storage.getObject("it/c31/" + mediaId)).thenReturn(SOURCE_BYTES);
		String handle = "res-" + sha256Hex(SOURCE_BYTES).substring(0, 16) + "-src";
		stubIngestExactBytes(handle, SOURCE_BYTES, SOURCE_BYTES.length);
		stubProbeSucceeded();

		String body = client().post().uri("/api/hypit/projects/{p}/assets/import-source", projectId)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).exchange().expectStatus().isEqualTo(202)
				.expectBody(String.class).returnResult().getResponseBody();
		JsonNode asset = read(body).path("data").path("asset");
		assertThat(asset.path("status").asText()).isEqualTo("ready");
		assertThat(asset.path("originKind").asText()).isEqualTo("import");
		assertThat(asset.path("mediaId").asText()).isEqualTo(mediaId.toString());
		// 不再是 media: JSON 引用——broker 可消费的 res- 句柄，hash 与源字节一致。
		assertThat(asset.path("resourceHandle").asText()).isEqualTo(handle);
		assertThat(asset.path("sha256").asText()).isEqualTo(sha256Hex(SOURCE_BYTES));

		String row = db
				.sql("SELECT resource_handle || '|' || sha256 FROM hypit_asset WHERE project_id"
						+ " = CAST(:p AS uuid) AND status = 'ready'")
				.bind("p", projectId.toString()).map((r, m) -> r.get(0, String.class)).one().block();
		assertThat(row).isEqualTo(handle + "|" + sha256Hex(SOURCE_BYTES));

		// 重复触发（创建后每次进入参考素材面板都会幂等物化）：不二次复制字节。
		String again = client().post().uri("/api/hypit/projects/{p}/assets/import-source", projectId)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).exchange().expectStatus().isEqualTo(202)
				.expectBody(String.class).returnResult().getResponseBody();
		assertThat(read(again).path("data").path("asset").path("id").asText()).isEqualTo(asset.path("id").asText());
		SIDECAR.verify(1, postRequestedFor(urlPathEqualTo("/internal/v1/resources")));

		// 素材可被读取（分析链路的输入面）。
		client().get().uri("/api/hypit/projects/{p}/assets/{a}", projectId, asset.path("id").asText())
				.header("X-Grassland-Identity", sign(OWNER_A, null)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.resourceHandle").isEqualTo(handle);
	}

	// ── TC-F2-31-02：256MiB 内合法上传收敛 ready ─────────────────────────────────

	@Test
	@DisplayName("TC-F2-31-02 合法视频上传：202 → ready，probe 事实落库")
	void tc02UploadWithinCapConvergesReady() {
		UUID projectId = insertReadyProject(OWNER_A, null);
		byte[] content = "fake-but-probe-stubbed-mp4-bytes".getBytes(StandardCharsets.UTF_8);
		stubIngest("res-0123456789abcdef-up", "f".repeat(64), content.length);
		stubProbeSucceeded();

		JsonNode first = postMultipart("/api/hypit/projects/" + projectId + "/assets", OWNER_A,
				uploadParts(UUID.randomUUID(), content, "video/mp4", "sample.mp4", null));
		assertThat(first.path("data").path("status").asText()).isEqualTo("ready");
		assertThat(first.path("data").path("originKind").asText()).isEqualTo("upload");
		assertThat(first.path("data").path("sha256").asText()).isEqualTo("f".repeat(64));

		// probe 事实（服务端核验结构）持久化在素材行上。
		String probe = db.sql("SELECT probe::text FROM hypit_asset WHERE id = CAST(:id AS uuid)")
				.bind("id", first.path("data").path("id").asText()).map((r, m) -> r.get(0, String.class)).one().block();
		assertThat(probe).contains("durationSeconds");
	}

	// ── TC-F2-31-03：超限与伪装内容拒收 ──────────────────────────────────────────

	@Test
	@DisplayName("TC-F2-31-03 256MiB+1 → 413 零字节出站；伪装 mp4 → 422 无 ready 资产")
	void tc03OversizeAndDisguisedContentRefused() {
		UUID projectId = insertReadyProject(OWNER_A, null);
		byte[] content = "script-content-disguised-as-mp4".getBytes(StandardCharsets.UTF_8);

		// 256MiB+1：声明长度入口先拒——没有任何字节出站、没有 ready 行。
		long oversize = 256L * 1024 * 1024 + 1;
		client().post().uri("/api/hypit/projects/{p}/assets", projectId)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(uploadParts(UUID.randomUUID(), content, "video/mp4", "big.mp4", oversize)).exchange()
				.expectStatus().isEqualTo(413).expectBody().jsonPath("$.code").isEqualTo("hypit_too_large");
		SIDECAR.verify(0, postRequestedFor(urlPathEqualTo("/internal/v1/resources")));

		// 回执超限（声明缺失、资源面边收边限的补口）：同样拒收，无 ready 素材。
		stubIngest("res-0123456789abcdef-big", "1".repeat(64), oversize);
		client().post().uri("/api/hypit/projects/{p}/assets", projectId)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(uploadParts(UUID.randomUUID(), content, "video/mp4", "big2.mp4", null)).exchange()
				.expectStatus().isEqualTo(413).expectBody().jsonPath("$.code").isEqualTo("hypit_too_large");

		// 扩展名 mp4 但内容是脚本：服务端 probe 拒绝 → 422，不出现 ready 资产；失败留档可审计。
		stubIngest("res-0123456789abcdef-evil", "2".repeat(64), content.length);
		stubProbeFailed();
		client().post().uri("/api/hypit/projects/{p}/assets", projectId)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(uploadParts(UUID.randomUUID(), content, "video/mp4", "evil.mp4", null)).exchange()
				.expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("probe_failed");
		Long ready = db
				.sql("SELECT COUNT(*) FROM hypit_asset WHERE project_id = CAST(:p AS uuid)" + " AND status = 'ready'")
				.bind("p", projectId.toString()).map((r, m) -> r.get(0, Long.class)).one().block();
		assertThat(ready).isZero();
		Long failedJobs = db
				.sql("SELECT COUNT(*) FROM hypit_job WHERE project_id = CAST(:p AS uuid)" + " AND state = 'failed'")
				.bind("p", projectId.toString()).map((r, m) -> r.get(0, Long.class)).one().block();
		assertThat(failedJobs).isEqualTo(1L);
	}

	// ── TC-F2-31-04：越权 404 与重复上传字节复用 ─────────────────────────────────

	@Test
	@DisplayName("TC-F2-31-04 他人 mediaId 交接 404 零读取；本人同文件重复复用同一资源")
	void tc04ForeignMedia404AndDuplicateReusesBytes() {
		UUID foreignMedia = insertMediaReference(OWNER_B, "active");
		UUID projectId = insertReadyProject(OWNER_A, "{\"kind\":\"media\",\"id\":\"" + foreignMedia + "\"}");

		// 他人 mediaId：与不存在同答 404（不泄漏存在性），对象存储零读取。
		client().post().uri("/api/hypit/projects/{p}/assets/import-source", projectId)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).exchange().expectStatus().isEqualTo(404)
				.expectBody().jsonPath("$.code").isEqualTo("hypit_not_found");
		org.mockito.Mockito.verifyNoInteractions(storage);
		SIDECAR.verify(0, postRequestedFor(urlPathEqualTo("/internal/v1/resources")));

		// 本人同文件重复上传（不同 requestId）：同 project+hash 复用资源——同一素材行、
		// 同一句柄，不产生第二份 ready 字节记录（broker 侧同内容单份由内容寻址落盘保证）。
		UUID plain = insertReadyProject(OWNER_A, null);
		byte[] content = "duplicate-upload-bytes".getBytes(StandardCharsets.UTF_8);
		stubIngest("res-0123456789abcdef-dup", "9".repeat(64), content.length);
		stubProbeSucceeded();
		JsonNode first = postMultipart("/api/hypit/projects/" + plain + "/assets", OWNER_A,
				uploadParts(UUID.randomUUID(), content, "video/mp4", "dup.mp4", null));
		JsonNode second = postMultipart("/api/hypit/projects/" + plain + "/assets", OWNER_A,
				uploadParts(UUID.randomUUID(), content, "video/mp4", "dup.mp4", null));
		assertThat(second.path("data").path("id").asText()).isEqualTo(first.path("data").path("id").asText());
		assertThat(second.path("data").path("reused").asBoolean()).isTrue();
		Long rows = db
				.sql("SELECT COUNT(*) FROM hypit_asset WHERE project_id = CAST(:p AS uuid)" + " AND status = 'ready'")
				.bind("p", plain.toString()).map((r, m) -> r.get(0, Long.class)).one().block();
		assertThat(rows).isEqualTo(1L);
	}
}
