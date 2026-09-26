package com.grassland.intelligence.hypit.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.media.HypitMediaArchiveAdapter;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.build.HypitOutputRepository;
import com.grassland.intelligence.hypit.build.HypitOutputRepository.OutputRow;
import com.grassland.intelligence.hypit.build.HypitPlanRepository;
import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.PresignRequest;
import com.grassland.storage.StoredObject;
import com.grassland.storage.UploadTicket;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * 归档端到端（任务书 #107-2 C107-10 / T10-4、T10-5 的 Java 侧语义）。
 *
 * <p>
 * 真 PostgreSQL + 内存对象存储；sidecar 以 WireMock 桩（results.export 命令 +
 * /internal/v1/resources 字节端点）。断言：claim 恰一家 → 真实字节入私有存储 → media_reference
 * 单行（确定性 mediaId/key）→ 重复归档返回同 mediaId/key 零新行； 对象 PUT 成功后 DB 故障（删行模拟）重跑仍收敛到同
 * mediaId、同 key、单行。
 */
@org.springframework.test.context.TestPropertySource(properties = {"hypit.enabled=true"})
class HypitArchiveIT extends IntelligenceItSupport {

	private static final String OWNER = "dddddddd-0000-4000-8000-00000000000d";
	private static final byte[] MP4_BYTES = {0, 0, 0, 8, 'f', 't', 'y', 'p'};
	private static final String HANDLE = "res-archive0001-it00";
	private static final WireMockServer WIRES = new WireMockServer(0);

	@Autowired
	HypitArchiveService archives;

	@Autowired
	HypitOutputRepository outputs;

	@Autowired
	HypitBuildRepository builds;

	@Autowired
	HypitPlanRepository plans;

	@Autowired
	MediaReferenceRepository mediaRefs;

	@Autowired
	DatabaseClient db;

	@Autowired
	ObjectStorageAdapter storage;

	@TestConfiguration
	static class StorageConfig {
		@Bean
		ObjectStorageAdapter hypitArchiveTestStorage() {
			return new InMemoryStorage();
		}
	}

	static final class InMemoryStorage implements ObjectStorageAdapter {

		final java.util.Map<String, byte[]> objects = new java.util.concurrent.ConcurrentHashMap<>();
		final java.util.List<String> puts = new java.util.concurrent.CopyOnWriteArrayList<>();

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
		public java.util.List<StoredObject> listObjects(String prefix) {
			return objects.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
					.map(entry -> new StoredObject(entry.getKey(), entry.getValue().length, null, null,
							java.time.Instant.now()))
					.toList();
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("hypit.internal-token", () -> "it-hypit-internal-token-0123456789abcdef");
		registry.add("hypit.sidecar-base-url", WIRES::baseUrl);
	}

	@BeforeAll
	static void startWires() {
		WIRES.start();
		WIRES.stubFor(post(urlEqualTo("/internal/v1/commands"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"stub","state":"succeeded","result":{"kind":"resource",
						 "mediaType":"video/mp4","size":8,"sha256":"%s","handle":"%s"}}
						""".formatted(com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge
						.sha256Hex(new String(MP4_BYTES)), HANDLE))));
		WIRES.stubFor(get(urlEqualTo("/internal/v1/resources/" + HANDLE))
				.willReturn(aResponse().withHeader("Content-Type", "video/mp4").withBody(MP4_BYTES)));
	}

	@AfterAll
	static void stopWires() {
		WIRES.stop();
	}

	private UUID projectId;

	@BeforeEach
	void seed() {
		cleanup();
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'archive-it', 'clone', 'ready', 3)")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
	}

	@org.junit.jupiter.api.AfterEach
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

	private OutputRow seedOutput(String engineBuildId, String outputName) {
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
								+ " outcome = 'complete', finished_at = now() WHERE id = CAST(:id AS uuid)")
						.bind("e", engineBuildId).bind("id", row.id().toString()).then()
						.then(builds.findById(row.id())))
				.block(Duration.ofSeconds(10)).id();
		return outputs.insert(UUID.randomUUID(), buildId, outputName, "resource", "video/mp4", 8L, "{}")
				.block(Duration.ofSeconds(10));
	}

	@Test
	void archiveIsIdempotentAcrossReplayAndDbFailureWithSingleMediaRow() {
		OutputRow output = seedOutput("bld_20260926T000000000Z_ARCAAAAA", "final.video");
		UUID deterministic = HypitMediaArchiveAdapter.deterministicMediaId(output.id());
		String key = "hypit/output/" + deterministic;

		OutputRow archived = archives.archiveOutput(output.id()).block(Duration.ofSeconds(30));
		assertThat(archived.archiveState()).isEqualTo("archived");
		assertThat(archived.mediaId()).isEqualTo(deterministic);
		assertThat(archived.resourceHandle()).isEqualTo(HANDLE);
		InMemoryStorage storage = storageBean();
		assertThat(storage.objects).containsKey(key);
		assertThat(storage.objects.get(key)).isEqualTo(MP4_BYTES);
		Long mediaRows = db
				.sql("SELECT count(*) AS n FROM media_reference WHERE owner_account_id = :o"
						+ " AND id = CAST(:m AS uuid)")
				.bind("o", OWNER).bind("m", deterministic.toString()).map((r, meta) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(mediaRows).as("单条 media 行").isEqualTo(1L);

		// 重复归档：同 mediaId、同 key、零新增行（claim 失败回读现状，不二次上传）。
		OutputRow again = archives.archiveOutput(output.id()).block(Duration.ofSeconds(30));
		assertThat(again.mediaId()).isEqualTo(deterministic);
		assertThat(storageBean().objects.get(key)).isEqualTo(MP4_BYTES);
		mediaRows = db
				.sql("SELECT count(*) AS n FROM media_reference WHERE owner_account_id = :o"
						+ " AND id = CAST(:m AS uuid)")
				.bind("o", OWNER).bind("m", deterministic.toString()).map((r, meta) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(mediaRows).as("重复归档仍单条 media 行").isEqualTo(1L);
	}

	@Test
	void putSuccessThenDbFailureConvergesOnReplay() {
		OutputRow output = seedOutput("bld_20260926T000000001Z_ARCAAAAA", "final.video");
		UUID deterministic = HypitMediaArchiveAdapter.deterministicMediaId(output.id());
		String key = "hypit/output/" + deterministic;
		assertThat(archives.archiveOutput(output.id()).block(Duration.ofSeconds(30)).archiveState())
				.isEqualTo("archived");

		// 模拟「对象 PUT 成功、media 行 DB 故障丢失」：删 media 行（对象保留），重跑归档。
		db.sql("DELETE FROM media_reference WHERE id = CAST(:m AS uuid)").bind("m", deterministic.toString()).then()
				.block(Duration.ofSeconds(10));
		db.sql("UPDATE hypit_output SET archive_state = 'pending', media_id = NULL, resource_handle = NULL"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", output.id().toString()).then()
				.block(Duration.ofSeconds(10));

		OutputRow replayed = archives.archiveOutput(output.id()).block(Duration.ofSeconds(30));
		assertThat(replayed.archiveState()).isEqualTo("archived");
		assertThat(replayed.mediaId()).as("重放收敛到同 mediaId").isEqualTo(deterministic);
		InMemoryStorage storage = storageBean();
		assertThat(storage.objects.get(key)).as("同 key 幂等覆盖").isEqualTo(MP4_BYTES);
		Long mediaRows = db.sql("SELECT count(*) AS n FROM media_reference WHERE id = CAST(:m AS uuid)")
				.bind("m", deterministic.toString()).map((r, meta) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(mediaRows).as("重放后仍单条 media 行").isEqualTo(1L);
	}

	private InMemoryStorage storageBean() {
		return (InMemoryStorage) storage;
	}
}
