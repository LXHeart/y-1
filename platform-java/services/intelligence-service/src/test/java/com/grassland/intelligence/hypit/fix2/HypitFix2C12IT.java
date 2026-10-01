package com.grassland.intelligence.hypit.fix2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.asset.HypitArchiveService;
import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.build.HypitOutputRepository;
import com.grassland.intelligence.hypit.build.HypitPlanRepository;
import com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge;
import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.PresignRequest;
import com.grassland.storage.StoredObject;
import com.grassland.storage.UploadTicket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.JsonNode;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

/**
 * C107F2-12（107-fix-2 §6.5）：统一 Build/Outputs DTO 与真实归档、结果消费（F11/F12）。
 *
 * <p>
 * 真 PostgreSQL + WireMock sidecar（results.read / results.export / 资源字节）+
 * 内存对象存储。
 *
 * <ul>
 * <li>TC-F2-12-01 GET outputs 输出 HypitOutputListData 全量形状：items 恒为数组、
 * nextCursor=null 不伪造分页、outputs=items 兼容别名；Output/Build 实际声明字段全量， 未知
 * size/duration=null 而非 0/空串（无 undefined.length 的消费前提）。</li>
 * <li>TC-F2-12-02 归档走 POST /builds/{id}/archive + outputNames（非 outputId、非
 * result-actions）：仅 final 归档、poster 不动；空 outputNames/未知名 400。</li>
 * <li>TC-F2-12-03 同 requestId 重复/并发归档：claim CAS 恰一家上传，同 mediaId、 单条 media
 * 行、单次对象 PUT。</li>
 * <li>TC-F2-12-04 outputs=[] 空态：items=[] 正常返回、build 聚合如实 pending， resultReady
 * 不冒充。</li>
 * </ul>
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitFix2C12IT extends IntelligenceItSupport {

	private static final String OWNER = "eeeeeeee-0000-4000-8000-00000000012a";
	private static final byte[] MP4_BYTES = {0, 0, 0, 8, 'f', 't', 'y', 'p'};
	private static final String HANDLE = "res-fix2c12-0001-it0000000000";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	@Autowired
	HypitArchiveService archives;

	@Autowired
	HypitOutputRepository outputs;

	@Autowired
	HypitBuildRepository builds;

	@Autowired
	HypitPlanRepository plans;

	@Autowired
	DatabaseClient db;

	@Autowired
	ObjectStorageAdapter storage;

	@TestConfiguration
	static class StorageConfig {
		@Bean
		ObjectStorageAdapter hypitFix2C12TestStorage() {
			return new CountingStorage();
		}
	}

	static final class CountingStorage implements ObjectStorageAdapter {

		final Map<String, byte[]> objects = new ConcurrentHashMap<>();
		final List<String> puts = new CopyOnWriteArrayList<>();

		@Override
		public UploadTicket presignUpload(PresignRequest request) {
			throw new UnsupportedOperationException();
		}

		@Override
		public URI presignDownload(String key, long expiresSeconds) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void putObject(String key, byte[] content, String contentType) {
			objects.put(key, content);
			puts.add(key);
		}

		@Override
		public byte[] getObject(String key) {
			return objects.get(key);
		}

		@Override
		public java.util.Optional<StoredObject> headObject(String key) {
			byte[] content = objects.get(key);
			return java.util.Optional.ofNullable(content)
					.map((value) -> new StoredObject(key, value.length, null, null, java.time.Instant.now()));
		}

		@Override
		public void deleteObject(String key) {
			objects.remove(key);
		}

		@Override
		public List<StoredObject> listObjects(String prefix) {
			return objects.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
					.map(entry -> new StoredObject(entry.getKey(), entry.getValue().length, null, null,
							java.time.Instant.now()))
					.toList();
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("hypit.internal-token", () -> "fix2-c12-internal-token-0123456789abcdef");
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
	}

	@BeforeAll
	static void startSidecar() {
		SIDECAR.start();
		String sha = HypitExternalExecutionBridge.sha256Hex(new String(MP4_BYTES));
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("results.export"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","state":"succeeded","result":{"kind":"resource","mediaType":"video/mp4",
						 "size":8,"sha256":"%s","handle":"%s"}}
						""".formatted(sha, HANDLE))));
		SIDECAR.stubFor(get(urlEqualTo("/internal/v1/resources/" + HANDLE))
				.willReturn(aResponse().withHeader("Content-Type", "video/mp4").withBody(MP4_BYTES)));
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	private UUID projectId;

	@BeforeEach
	void seed() {
		cleanup();
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'fix2-c12', 'clone', 'ready', 3)")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
	}

	@AfterEach
	void sweep() {
		cleanup();
	}

	private void cleanup() {
		db.sql("DELETE FROM hypit_job_event WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id = :o)")
				.bind("o", OWNER).then()
				.then(db.sql("DELETE FROM hypit_job WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_output WHERE build_id IN (SELECT b.id FROM hypit_build b"
						+ " JOIN hypit_project p ON p.id = b.project_id WHERE p.account_id = :o)").bind("o", OWNER)
						.then())
				.then(db.sql("DELETE FROM hypit_build WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_plan WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM media_reference WHERE owner_account_id = :o").bind("o", OWNER).then())
				.block(Duration.ofSeconds(20));
	}

	/** 造一个 finished build（engineBuildId 已回填），可附输出行（size/summary 可为 null 形态）。 */
	private UUID seedFinishedBuild(String engineBuildId, boolean resultReady, String... outputNames) {
		UUID planId = plans
				.insertPlan(UUID.randomUUID(), projectId, 3, "main.svrun", "c".repeat(64), "p".repeat(64), "{}")
				.block(Duration.ofSeconds(10)).id();
		UUID commandId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_command(id, account_id, project_id, target_key, action, request_id,"
				+ " payload_hash, payload_json, state) VALUES (CAST(:id AS uuid), :o, CAST(:p AS uuid), 't',"
				+ " 'build.submit', CAST(:r AS uuid), :h, '{}', 'acknowledged')").bind("id", commandId.toString())
				.bind("o", OWNER).bind("p", projectId.toString()).bind("r", UUID.randomUUID()).bind("h", "h".repeat(64))
				.then().block(Duration.ofSeconds(10));
		UUID buildId = builds.insert(UUID.randomUUID(), commandId, projectId, 3L, planId, "main.svrun")
				.flatMap(row -> db
						.sql("UPDATE hypit_build SET engine_build_id = :e, lifecycle = 'finished',"
								+ " outcome = 'complete', finished_at = now()"
								+ (resultReady ? ", result_location = CAST('{}' AS jsonb)" : "")
								+ " WHERE id = CAST(:id AS uuid)")
						.bind("e", engineBuildId).bind("id", row.id().toString()).then()
						.then(builds.findById(row.id())))
				.block(Duration.ofSeconds(10)).id();
		for (String name : outputNames) {
			boolean knownSize = name.equals("final.video");
			outputs.insert(UUID.randomUUID(), buildId, name, "resource", "video/mp4", knownSize ? 8L : null,
					knownSize ? "{\"type\":\"video\",\"displayName\":\"成片\"}" : "{}").block(Duration.ofSeconds(10));
		}
		return buildId;
	}

	/** 按名回读输出行（断言 DB 侧事实用）。 */
	private HypitOutputRepository.OutputRow outputRow(UUID buildId, String name) {
		return outputs.findByBuildAndName(buildId, name).block(Duration.ofSeconds(10));
	}

	private JsonNode getBody(String uri) {
		String body = client().get().uri(uri).header("X-Grassland-Identity", sign(OWNER, null)).exchange()
				.expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
		try {
			return JSON.readTree(body == null ? "{}" : body);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private JsonNode postBody(String uri, String json) {
		String body = client().post().uri(uri).header("X-Grassland-Identity", sign(OWNER, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(json).exchange().expectStatus().isOk()
				.expectBody(String.class).returnResult().getResponseBody();
		try {
			return JSON.readTree(body == null ? "{}" : body);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private void stubReadOutputs(String... names) {
		StringBuilder items = new StringBuilder();
		for (String name : names) {
			items.append("{\"name\":\"").append(name).append("\",\"kind\":\"resource\",\"mediaType\":\"video/mp4\"},");
		}
		if (items.length() > 0) {
			items.setLength(items.length() - 1);
		}
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("results.read"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
						("{\"commandId\":\"x\",\"state\":\"succeeded\",\"result\":{\"outputs\":[" + items + "]}}"))));
	}

	private long mediaRows(UUID mediaId) {
		return db.sql("SELECT count(*) AS n FROM media_reference WHERE owner_account_id = :o AND id = CAST(:m AS uuid)")
				.bind("o", OWNER).bind("m", mediaId.toString()).map((row, meta) -> row.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
	}

	// ── TC-F2-12-01 ───────────────────────────────────────────────────────
	@Test
	@DisplayName("TC-F2-12-01 OutputList 全量形状：items 恒数组、nextCursor=null、未知=null 不冒充")
	void outputsListIsFullShapeWithNullForUnknown() {
		UUID buildId = seedFinishedBuild("bld_fix2c12_0000000000000001", true, "final.video", "poster.png");
		stubReadOutputs("final.video", "poster.png");

		JsonNode data = getBody("/api/hypit/builds/" + buildId + "/outputs").path("data");
		assertThat(data.path("items").isArray()).as("items 恒为数组").isTrue();
		assertThat(data.path("items").size()).isEqualTo(2);
		assertThat(data.path("nextCursor").isNull()).as("不伪造分页").isTrue();
		assertThat(data.path("outputs").size()).as("outputs 兼容别名与 items 同内容").isEqualTo(2);
		assertThat(data.path("outputs").get(0).path("id").asText())
				.isEqualTo(data.path("items").get(0).path("id").asText());

		// Build 实际声明字段：runFile/createdAt 与原生事实一致；聚合统计真实。
		JsonNode build = data.path("build");
		assertThat(build.path("runFile").asText()).isEqualTo("main.svrun");
		assertThat(build.path("createdAt").asText()).isNotBlank();
		assertThat(build.path("resultReady").asBoolean()).isTrue();
		assertThat(build.path("outcome").asText()).isEqualTo("complete");
		assertThat(build.path("outputCount").asLong()).isEqualTo(2);
		assertThat(build.path("archiveState").asText()).isEqualTo("pending");
		assertThat(build.has("operations")).isTrue();

		// Output 实际声明字段全量；未知 size/duration=null 而非 0。
		JsonNode poster = findOutput(data.path("items"), "poster.png");
		assertThat(poster.path("sizeBytes").isNull()).as("未知 size=null 不冒充 0").isTrue();
		assertThat(poster.path("durationSeconds").isNull()).as("未知 duration=null").isTrue();
		assertThat(poster.path("mediaId").isNull()).as("未归档 mediaId=null 非空串").isTrue();
		JsonNode fin = findOutput(data.path("items"), "final.video");
		assertThat(fin.path("sizeBytes").asLong()).isEqualTo(8L);
		assertThat(fin.path("displayName").asText()).isEqualTo("成片");
		assertThat(fin.path("typeRef").asText()).isEqualTo("video");
		for (String field : List.of("id", "buildId", "name", "displayName", "kind", "typeRef", "mediaType", "sizeBytes",
				"durationSeconds", "valueSummary", "archiveState", "mediaId", "dependencies", "createdAt")) {
			assertThat(fin.has(field)).as("字段 %s 必须存在", field).isTrue();
		}
	}

	private static JsonNode findOutput(JsonNode items, String name) {
		for (JsonNode item : items) {
			if (name.equals(item.path("name").asText())) {
				return item;
			}
		}
		throw new IllegalStateException("output not found: " + name);
	}

	// ── TC-F2-12-02 ───────────────────────────────────────────────────────
	@Test
	@DisplayName("TC-F2-12-02 归档正确端点/载荷：outputNames 仅归档 final；空名/未知名 400")
	void archiveUsesCorrectEndpointBodyAndArchivesOnlyNamedOutputs() {
		UUID buildId = seedFinishedBuild("bld_fix2c12_0000000000000002", true, "final.video", "poster.png");
		stubReadOutputs("final.video", "poster.png");
		SIDECAR.resetRequests();

		// 负向：空 outputNames / 未知名 → 400 hypit_invalid_input（§6.5 名字存在性）。
		client().post().uri("/api/hypit/builds/{id}/archive", buildId).header("X-Grassland-Identity", sign(OWNER, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"%s\",\"outputNames\":[]}".formatted(UUID.randomUUID())).exchange()
				.expectStatus().isBadRequest();
		client().post().uri("/api/hypit/builds/{id}/archive", buildId).header("X-Grassland-Identity", sign(OWNER, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"%s\",\"outputNames\":[\"no-such.video\"]}".formatted(UUID.randomUUID()))
				.exchange().expectStatus().isBadRequest();

		JsonNode data = postBody("/api/hypit/builds/" + buildId + "/archive",
				("{\"requestId\":\"%s\",\"outputNames\":[\"final.video\",\"final.video\"]}")
						.formatted(UUID.randomUUID()))
				.path("data");
		assertThat(data.path("outputs").size()).as("重复名去重后只回 final").isEqualTo(1);
		assertThat(data.path("outputs").get(0).path("name").asText()).isEqualTo("final.video");
		assertThat(data.path("outputs").get(0).path("archiveState").asText()).isEqualTo("archived");

		UUID mediaId = UUID.fromString(data.path("outputs").get(0).path("mediaId").asText());
		assertThat(mediaId).isEqualTo(com.grassland.intelligence.media.HypitMediaArchiveAdapter
				.deterministicMediaId(outputRow(buildId, "final.video").id()));
		assertThat(outputRow(buildId, "poster.png").archiveState()).as("poster 不被顺带归档").isEqualTo("pending");
		assertThat(SIDECAR.countRequestsMatching(postRequestedFor(urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("results.export")).withRequestBody(containing("final.video")).build())
				.getCount()).as("export 恰一次").isEqualTo(1);
		assertThat(mediaRows(mediaId)).isEqualTo(1L);
	}

	// ── TC-F2-12-03 ───────────────────────────────────────────────────────
	@Test
	@DisplayName("TC-F2-12-03 同 requestId 并发/重复归档：同 mediaId、单 media 行、单次 PUT")
	void concurrentSameRequestArchivesOnceWithSameMediaId() {
		UUID buildId = seedFinishedBuild("bld_fix2c12_0000000000000003", true, "final.video");
		stubReadOutputs("final.video");
		// 上下文级存储 bean 与 WireMock 请求日志跨用例共享：本用例从零计数。
		((CountingStorage) storage).puts.clear();
		SIDECAR.resetRequests();
		String requestId = UUID.randomUUID().toString();
		String body = "{\"requestId\":\"" + requestId + "\",\"outputNames\":[\"final.video\"]}";

		// 服务层真并发：两路 archiveByNames 同时推进，claim CAS 恰一家上传。
		var both = reactor.core.publisher.Mono
				.zip(archives.archiveByNames(buildId, List.of("final.video")).collectList(),
						archives.archiveByNames(buildId, List.of("final.video")).collectList())
				.block(Duration.ofSeconds(30));
		assertThat(both.getT1().get(0).archiveState()).isIn("archived", "archiving");
		assertThat(both.getT2().get(0).archiveState()).isIn("archived", "archiving");

		// 同 requestId 重复 POST：幂等复用已归档 media，不再 export、不再 PUT。
		JsonNode first = postBody("/api/hypit/builds/" + buildId + "/archive", body).path("data");
		JsonNode replay = postBody("/api/hypit/builds/" + buildId + "/archive", body).path("data");
		String mediaId = first.path("outputs").get(0).path("mediaId").asText();
		assertThat(replay.path("outputs").get(0).path("mediaId").asText()).isEqualTo(mediaId);
		assertThat(mediaRows(UUID.fromString(mediaId))).as("单条 media 行").isEqualTo(1L);
		assertThat(((CountingStorage) storage).puts.stream().distinct().count()).as("单次对象 PUT").isEqualTo(1);
		assertThat(SIDECAR.countRequestsMatching(postRequestedFor(urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("results.export")).build()).getCount()).as("export 恰一次").isEqualTo(1);
	}

	// ── TC-F2-12-04 ───────────────────────────────────────────────────────
	@Test
	@DisplayName("TC-F2-12-04 outputs=[] 空态正常；聚合如实 pending、resultReady 不冒充")
	void emptyOutputsIsCleanEmptyStateWithoutFakes() {
		UUID buildId = seedFinishedBuild("bld_fix2c12_0000000000000004", false);
		stubReadOutputs();

		JsonNode data = getBody("/api/hypit/builds/" + buildId + "/outputs").path("data");
		assertThat(data.path("items").isArray()).as("空列表 items=[] 恒为数组").isTrue();
		assertThat(data.path("items").isEmpty()).isTrue();
		assertThat(data.path("outputs").isEmpty()).as("兼容别名同为空").isTrue();
		assertThat(data.path("nextCursor").isNull()).isTrue();
		assertThat(data.path("build").path("outputCount").asLong()).isEqualTo(0L);
		assertThat(data.path("build").path("archiveState").asText()).as("零输出如实 pending").isEqualTo("pending");
		assertThat(data.path("build").path("resultReady").asBoolean()).as("无结果落位不冒充 resultReady").isFalse();
	}
}
