package com.grassland.intelligence.hypit.asset;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.JsonNode;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 素材面 HTTP 真值（任务书 #107-1 C107-05 / TC107-05-02/03 的 Java 侧 + §6.2 K07/K08/K09）。
 *
 * <p>
 * sidecar 以 WireMock 桩承载 internal 资源/命令面（ingest/probe/Range 字节真值由
 * B/tests/engine/resources-routes.test.ts 与 tests/media/* 覆盖）；本 IT 验证鉴权、
 * mediaId 导入归属/active 校验、删除引用守卫、Range 透传状态码与工具白名单。 持久化断言落真 PostgreSQL（K13：IT
 * 必须真库）。
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitAssetIT extends IntelligenceItSupport {

	private static final String OWNER_A = "cccccccc-0000-4000-8000-0000000000aa";
	private static final String OWNER_B = "cccccccc-0000-4000-8000-0000000000bb";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	static {
		SIDECAR.start();
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	@org.springframework.test.context.DynamicPropertySource
	static void sidecarProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
		registry.add("hypit.internal-token", () -> "it-hypit-internal-token-0123456789abcdef");
	}

	private UUID projectA;
	private UUID projectB;

	@org.springframework.beans.factory.annotation.Autowired
	private HypitResourceService resources;

	@BeforeEach
	void clean() {
		SIDECAR.resetAll();
		db.sql("DELETE FROM hypit_job_event WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id"
				+ " IN (:a, :b))").bind("a", OWNER_A).bind("b", OWNER_B).then()
				.then(db.sql("DELETE FROM hypit_job WHERE account_id IN (:a, :b)").bind("a", OWNER_A).bind("b", OWNER_B)
						.then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id IN (:a, :b)").bind("a", OWNER_A)
						.bind("b", OWNER_B).then())
				.then(db.sql("DELETE FROM hypit_asset_reference WHERE project_id IN (SELECT id FROM"
						+ " hypit_project WHERE account_id IN (:a, :b))").bind("a", OWNER_A).bind("b", OWNER_B).then())
				.then(db.sql("DELETE FROM hypit_asset WHERE project_id IN (SELECT id FROM"
						+ " hypit_project WHERE account_id IN (:a, :b))").bind("a", OWNER_A).bind("b", OWNER_B).then())
				.then(db.sql("DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM"
						+ " hypit_project WHERE account_id IN (:a, :b))").bind("a", OWNER_A).bind("b", OWNER_B).then())
				.then(db.sql("DELETE FROM hypit_changeset WHERE project_id IN (SELECT id FROM"
						+ " hypit_project WHERE account_id IN (:a, :b))").bind("a", OWNER_A).bind("b", OWNER_B).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id IN (:a, :b)").bind("a", OWNER_A)
						.bind("b", OWNER_B).then())
				.then(db.sql("DELETE FROM media_reference WHERE owner_account_id IN (:a, :b)").bind("a", OWNER_A)
						.bind("b", OWNER_B).then())
				.block(java.time.Duration.ofSeconds(10));
		projectA = insertReadyProject(OWNER_A, "素材工程A");
		projectB = insertReadyProject(OWNER_B, "素材工程B");
	}

	private UUID insertReadyProject(String owner, String title) {
		UUID id = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project (id, account_id, workspace_id, title, mode, status,"
				+ " revision, head_manifest_hash) VALUES (CAST(:id AS uuid), :owner,"
				+ " CAST(:ws AS uuid), :title, 'clone', 'ready', 1, :hash)").bind("id", id.toString())
				.bind("owner", owner).bind("ws", UUID.randomUUID().toString()).bind("title", title)
				.bind("hash", "a".repeat(64)).then().block(java.time.Duration.ofSeconds(10));
		return id;
	}

	private UUID insertMediaReference(String owner, String status) {
		UUID mediaId = UUID.randomUUID();
		db.sql("INSERT INTO media_reference (id, owner_account_id, purpose, object_key, mime_type,"
				+ " size_bytes, checksum, status) VALUES (CAST(:id AS uuid), :owner, 'user_upload', :key,"
				+ " 'video/mp4', 11, :checksum, :status)").bind("id", mediaId.toString()).bind("owner", owner)
				.bind("key", "it/asset/" + mediaId).bind("checksum", "b".repeat(64)).bind("status", status).then()
				.block(java.time.Duration.ofSeconds(10));
		return mediaId;
	}

	private void stubIngest(String handle, String sha256, long sizeBytes) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/resources"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"handle\":\"%s\",\"sha256\":\"%s\",\"sizeBytes\":%d}".formatted(handle, sha256,
								sizeBytes))));
	}

	private void stubProbeSuccess() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("media.probe"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"media.probe","state":"succeeded",
						 "result":{"probe":{"durationSeconds":3.5,"hasAudio":true}}}
						""")));
	}

	private JsonNode upload(String owner, UUID projectId, UUID requestId, String contentType, String filename) {
		MultipartBodyBuilder parts = new MultipartBodyBuilder();
		parts.part("file", new ByteArrayResource(new byte[]{1, 2, 3, 4, 5}) {
			@Override
			public String getFilename() {
				return filename;
			}
		}).contentType(MediaType.parseMediaType(contentType));
		parts.part("role", "reference");
		parts.part("requestId", requestId.toString());
		String body = client().post().uri("/api/hypit/projects/{p}/assets", projectId)
				.header("X-Grassland-Identity", sign(owner, null)).contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(parts.build()).exchange().expectStatus().is2xxSuccessful().expectBody(String.class)
				.returnResult().getResponseBody();
		try {
			return JSON.readTree(body == null ? "{}" : body);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	@Test
	void uploadConvergesReadyAndReplayReturnsSameAsset() {
		stubIngest("res-0123456789abcdef-0", "c".repeat(64), 5);
		stubProbeSuccess();
		UUID requestId = UUID.randomUUID();

		JsonNode first = upload(OWNER_A, projectA, requestId, "image/png", "fixture.png");
		assertThat(first.path("data").path("status").asText()).isEqualTo("ready");
		assertThat(first.path("data").path("originKind").asText()).isEqualTo("upload");
		assertThat(first.path("data").path("sha256").asText()).isEqualTo("c".repeat(64));
		String assetId = first.path("data").path("id").asText();
		assertThat(assetId).isNotBlank();

		// 同 requestId 重放：返回同一素材（幂等键不二次出站）。
		JsonNode replay = upload(OWNER_A, projectA, requestId, "image/png", "fixture.png");
		assertThat(replay.path("data").path("id").asText()).isEqualTo(assetId);

		Long rows = db.sql("SELECT COUNT(*) AS c FROM hypit_asset WHERE project_id = :p" + " AND status = 'ready'")
				.bind("p", projectA).map((row, meta) -> row.get("c", Long.class)).one().block();
		assertThat(rows).isEqualTo(1L);

		// probe 事实入库（mergedFacts 覆盖 probeJson）。
		String probe = db.sql("SELECT probe::text AS probe FROM hypit_asset WHERE id" + " = CAST(:id AS uuid)")
				.bind("id", assetId.toString()).map((row, meta) -> row.get("probe", String.class)).one().block();
		assertThat(probe).contains("durationSeconds");
	}

	@Test
	void uploadRejectsDisguisedMimeAndInvalidRole() {
		UUID requestId = UUID.randomUUID();
		client().post().uri("/api/hypit/projects/{p}/assets", projectA)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(multipart(requestId, "application/zip", "evil.zip")).exchange().expectStatus().isEqualTo(400)
				.expectBody().jsonPath("$.code").isEqualTo("hypit_invalid_input");

		client().post().uri("/api/hypit/projects/{p}/assets", projectA)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(multipart(requestId, "image/png", "ok.png", "backdoor")).exchange().expectStatus()
				.isEqualTo(400).expectBody().jsonPath("$.code").isEqualTo("hypit_invalid_input");

		// E02：已知长度超 500MiB 在入口先拒（413，不发起出站固化）。
		reactor.test.StepVerifier
				.create(resources.ingest(reactor.core.publisher.Flux.empty(), "huge.bin", "video/mp4",
						500L * 1024 * 1024 + 1))
				.expectErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(com.grassland.intelligence.security.IntelligenceException.class)
						.hasMessageContaining("500MiB"))
				.verify(java.time.Duration.ofSeconds(5));
	}

	private org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>> multipart(
			UUID requestId, String contentType, String filename) {
		return multipart(requestId, contentType, filename, "reference");
	}

	private org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>> multipart(
			UUID requestId, String contentType, String filename, String role) {
		MultipartBodyBuilder parts = new MultipartBodyBuilder();
		parts.part("file", new ByteArrayResource(new byte[]{1, 2, 3}) {
			@Override
			public String getFilename() {
				return filename;
			}
		}).contentType(MediaType.parseMediaType(contentType));
		parts.part("role", role);
		parts.part("requestId", requestId.toString());
		return parts.build();
	}

	@Test
	void importMediaValidatesOwnershipAndActiveStatus() {
		UUID mediaActive = insertMediaReference(OWNER_A, "active");
		UUID mediaPending = insertMediaReference(OWNER_A, "pending");
		UUID mediaOther = insertMediaReference(OWNER_B, "active");

		// 归属 + active：直接 ready（无 sidecar 步骤），持久化 media 引用。
		String ok = client().post().uri("/api/hypit/projects/{p}/assets", projectA)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"%s\",\"mediaId\":\"%s\",\"role\":\"reference\"}"
						.formatted(UUID.randomUUID(), mediaActive))
				.exchange().expectStatus().isEqualTo(202).expectBody(String.class).returnResult().getResponseBody();
		try {
			JsonNode node = JSON.readTree(ok == null ? "{}" : ok);
			assertThat(node.path("data").path("status").asText()).isEqualTo("ready");
			assertThat(node.path("data").path("mediaId").asText()).isEqualTo(mediaActive.toString());
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}

		// pending 未确认 → 400；他人媒体 → 400；不存在 → 400。
		client().post().uri("/api/hypit/projects/{p}/assets", projectA)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"%s\",\"mediaId\":\"%s\",\"role\":\"reference\"}"
						.formatted(UUID.randomUUID(), mediaPending))
				.exchange().expectStatus().isEqualTo(400).expectBody().jsonPath("$.code")
				.isEqualTo("hypit_invalid_input");
		client().post().uri("/api/hypit/projects/{p}/assets", projectA)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"%s\",\"mediaId\":\"%s\",\"role\":\"reference\"}"
						.formatted(UUID.randomUUID(), mediaOther))
				.exchange().expectStatus().isEqualTo(400).expectBody().jsonPath("$.code")
				.isEqualTo("hypit_invalid_input");
		client().post().uri("/api/hypit/projects/{p}/assets", projectA)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"%s\",\"mediaId\":\"%s\",\"role\":\"reference\"}"
						.formatted(UUID.randomUUID(), UUID.randomUUID()))
				.exchange().expectStatus().isEqualTo(400);
	}

	@Test
	void contentProxiesRangeStatusAndRejectsNonOwner() {
		stubIngest("res-0123456789abcdef-9", "d".repeat(64), 5);
		stubProbeSuccess();
		JsonNode asset = upload(OWNER_A, projectA, UUID.randomUUID(), "video/mp4", "clip.mp4");
		String assetId = asset.path("data").path("id").asText();

		SIDECAR.stubFor(get(urlPathEqualTo("/internal/v1/resources/res-0123456789abcdef-9"))
				.withHeader("Range", containing("bytes=1-3"))
				.willReturn(aResponse().withStatus(206).withHeader("Content-Range", "bytes 1-3/5")
						.withHeader("Content-Type", "video/mp4").withBody(new byte[]{2, 3, 4})));
		SIDECAR.stubFor(get(urlPathEqualTo("/internal/v1/resources/res-0123456789abcdef-9"))
				.withHeader("Range", containing("bytes=999-"))
				.willReturn(aResponse().withStatus(416).withHeader("Content-Range", "bytes */5")));

		byte[] partial = client().get().uri("/api/hypit/projects/{p}/assets/{a}/content", projectA, assetId)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).header("Range", "bytes=1-3").exchange()
				.expectStatus().isEqualTo(206).expectHeader().valueEquals("Content-Range", "bytes 1-3/5")
				.expectBody(byte[].class).returnResult().getResponseBody();
		assertThat(partial).containsExactly(2, 3, 4);

		client().get().uri("/api/hypit/projects/{p}/assets/{a}/content", projectA, assetId)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).header("Range", "bytes=999-").exchange()
				.expectStatus().isEqualTo(416);

		// 他人工程/他人素材：404 不泄露存在性。
		client().get().uri("/api/hypit/projects/{p}/assets/{a}/content", projectA, assetId)
				.header("X-Grassland-Identity", sign(OWNER_B, null)).exchange().expectStatus().isEqualTo(404);
		client().get().uri("/api/hypit/projects/{p}/assets/{a}/content", projectB, assetId)
				.header("X-Grassland-Identity", sign(OWNER_B, null)).exchange().expectStatus().isEqualTo(404);
	}

	@Test
	void deleteGuardsActiveReferencesAndSoftDeletesOtherwise() {
		stubIngest("res-0123456789abcdef-5", "e".repeat(64), 5);
		stubProbeSuccess();
		JsonNode asset = upload(OWNER_A, projectA, UUID.randomUUID(), "image/png", "ref.png");
		UUID assetId = UUID.fromString(asset.path("data").path("id").asText());

		db.sql("INSERT INTO hypit_asset_reference (project_id, revision, asset_id, relative_path,"
				+ " sha256) VALUES (CAST(:p AS uuid), 2, CAST(:a AS uuid), 'assets/ref.png', :hash)")
				.bind("p", projectA.toString()).bind("a", assetId.toString()).bind("hash", "e".repeat(64)).then()
				.block(java.time.Duration.ofSeconds(10));

		client().delete().uri("/api/hypit/projects/{p}/assets/{a}", projectA, assetId)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).exchange().expectStatus().isEqualTo(409)
				.expectBody().jsonPath("$.code").isEqualTo("hypit_reference_in_use");

		db.sql("DELETE FROM hypit_asset_reference WHERE asset_id = CAST(:a AS uuid)").bind("a", assetId.toString())
				.then().block(java.time.Duration.ofSeconds(10));
		client().delete().uri("/api/hypit/projects/{p}/assets/{a}", projectA, assetId)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).exchange().expectStatus().isOk();
		String status = db.sql("SELECT status FROM hypit_asset WHERE id = CAST(:id AS uuid)")
				.bind("id", assetId.toString()).map((row, meta) -> row.get("status", String.class)).one().block();
		assertThat(status).isEqualTo("deleted");
	}

	@Test
	void toolEndpointWhitelistsMediaToolsOnly() {
		stubProbeSuccess();
		client().post().uri("/api/hypit/projects/{p}/tools/media.probe", projectA)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"%s\",\"input\":{\"handle\":\"res-0123456789abcdef-0\"}}"
						.formatted(UUID.randomUUID()))
				.exchange().expectStatus().isEqualTo(202).expectBody().jsonPath("$.data.state").isEqualTo("succeeded")
				.jsonPath("$.data.tool").isEqualTo("media.probe");

		client().post().uri("/api/hypit/projects/{p}/tools/media.exfiltrate", projectA)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"%s\",\"input\":{}}".formatted(UUID.randomUUID())).exchange().expectStatus()
				.isEqualTo(400).expectBody().jsonPath("$.code").isEqualTo("hypit_unsupported_action");

		// 非本卡 schema 的 build 类 action 同样不放行。
		client().post().uri("/api/hypit/projects/{p}/tools/build.start", projectA)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"%s\",\"input\":{}}".formatted(UUID.randomUUID())).exchange().expectStatus()
				.isEqualTo(400).expectBody().jsonPath("$.code").isEqualTo("hypit_unsupported_action");
	}
}
