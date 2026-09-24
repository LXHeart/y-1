package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.digitalhuman.DigitalHumanArtifactService.ArtifactFetchPort;
import com.grassland.intelligence.digitalhuman.DigitalHumanArtifactService.SaveOutcome;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.StoredObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * 下载/校验/幂等保存 IT（任务书 #105F C105F-03 / TC105F-03-01/02/04 Java 侧子断言）：真实 DB
 * （dh_recording→saving→saved 状态机、media_reference
 * 生命周期、content_asset/dh_asset_attachment 同事务挂载、dh_operation 幂等键）+ 真实 ffprobe 探针
 * + 最外层网络边界 fake（INTERNAL12 取物、 对象存储）。GC 竞争屏障序（TC105F-03-03）在
 * {@link DigitalHumanSaveGcRaceIT}。
 */
@Import(DigitalHumanArtifactIT.FakeArtifactNetwork.class)
class DigitalHumanArtifactIT extends IntelligenceItSupport {

	@Autowired
	private DatabaseClient db;
	@Autowired
	private DigitalHumanArtifactService artifacts;
	@Autowired
	private FakeArtifactFetchPort fakeFetch;
	@MockitoBean
	private ObjectStorageAdapter storage;

	private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();
	private final List<String> putCalls = new CopyOnWriteArrayList<>();

	static class FakeArtifactNetwork {

		@Bean
		FakeArtifactFetchPort fakeArtifactFetchPort() {
			return new FakeArtifactFetchPort();
		}
	}

	/** 最外层网络替身：INTERNAL12 受控产物读取（DB/owner/幂等键/探针全部真实）。 */
	static class FakeArtifactFetchPort implements ArtifactFetchPort {

		final Map<String, byte[]> objects = new ConcurrentHashMap<>();
		final List<String> fetchCalls = new CopyOnWriteArrayList<>();

		@Override
		public Mono<byte[]> fetchArtifact(UUID resourceId, String objectRef) {
			return Mono.defer(() -> {
				fetchCalls.add(resourceId + "|" + objectRef);
				byte[] bytes = objects.get(objectRef);
				if (bytes == null) {
					return Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "runtime 无此产物。"));
				}
				return Mono.just(bytes);
			});
		}
	}

	@BeforeEach
	void seed() {
		// 依赖序自清（共享容器跨类残留自愈）：附件→资产→media→登记→段→操作→会话链。
		db.sql("DELETE FROM dh_asset_attachment").then()
				.then(db.sql("DELETE FROM content_asset WHERE source = 'dh_recording'").then())
				.then(db.sql("DELETE FROM media_reference WHERE domain_type = 'dh_recording'").then())
				.then(db.sql("DELETE FROM dh_cleanup WHERE resource_kind = 'recording_object'").then())
				.then(db.sql("DELETE FROM dh_recording").then())
				.then(db.sql("DELETE FROM dh_operation WHERE kind LIKE 'recording_%'").then())
				.then(db.sql("DELETE FROM dh_invocation").then()).then(db.sql("DELETE FROM dh_preview").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_turn").then()).then(db.sql("DELETE FROM dh_session").then())
				.then(db.sql("DELETE FROM dh_catalog").then()).block(Duration.ofSeconds(10));
		db.sql("INSERT INTO dh_catalog(singleton_id, version, config_json, updated_by) VALUES (1, 1,"
				+ " CAST(:config AS jsonb), 'it')")
				.bind("config",
						"{\"enabled\":true,\"newSessionsAllowed\":true,\"recordingEnabled\":true,"
								+ "\"customAvatarEnabled\":false,\"allowedBackendIds\":[\"dh-it-backend\"],"
								+ "\"avatars\":[],\"voices\":[]}")
				.then().block(Duration.ofSeconds(10));
		objectStore.clear();
		putCalls.clear();
		fakeFetch.objects.clear();
		fakeFetch.fetchCalls.clear();
		reset(storage);
		doAnswer(invocation -> {
			String key = invocation.getArgument(0);
			byte[] content = invocation.getArgument(1);
			objectStore.put(key, content);
			putCalls.add(key);
			return null;
		}).when(storage).putObject(anyString(), any(byte[].class), anyString());
		doAnswer(invocation -> {
			String key = invocation.getArgument(0);
			byte[] content = objectStore.get(key);
			return content == null
					? Optional.empty()
					: Optional.of(new StoredObject(key, content.length, "application/octet-stream", "etag", null));
		}).when(storage).headObject(anyString());
		doAnswer(invocation -> {
			String key = invocation.getArgument(0);
			byte[] content = objectStore.get(key);
			if (content == null) {
				throw new IllegalStateException("未配置的对象 key：" + key);
			}
			return content;
		}).when(storage).getObject(anyString());
	}

	@org.junit.jupiter.api.AfterEach
	void cleanupRows() {
		db.sql("DELETE FROM dh_asset_attachment").then()
				.then(db.sql("DELETE FROM content_asset WHERE source = 'dh_recording'").then())
				.then(db.sql("DELETE FROM media_reference WHERE domain_type = 'dh_recording'").then())
				.then(db.sql("DELETE FROM dh_cleanup WHERE resource_kind = 'recording_object'").then())
				.then(db.sql("DELETE FROM dh_recording").then())
				.then(db.sql("DELETE FROM dh_operation WHERE kind LIKE 'recording_%'").then())
				.block(Duration.ofSeconds(10));
	}

	// ---------- 造数与夹具 ----------

	private UUID seedReadyRecording(String owner, byte[] video, long manifestDurationMs, boolean withSubtitle) {
		UUID sessionId = UUID.randomUUID();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, lease_epoch)"
				+ " VALUES (CAST(:s AS uuid), :owner, CAST(:p AS uuid), 1, '角色', 'dh-it-backend',"
				+ " CAST(:pf AS uuid), CAST(:c AS uuid), CAST('{}' AS jsonb), 'ended', now(), 1)")
				.bind("s", sessionId.toString()).bind("owner", owner).bind("p", UUID.randomUUID().toString())
				.bind("pf", UUID.randomUUID().toString()).bind("c", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
		UUID recordingId = UUID.randomUUID();
		String videoRef = "dhr-" + recordingId + "-mp4";
		String srtRef = "dhr-" + recordingId + "-srt";
		fakeFetch.objects.put(videoRef, video);
		byte[] srt = SAMPLE_SRT.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		fakeFetch.objects.put(srtRef, srt);
		String manifest = "{\"recordingId\":\"" + recordingId + "\",\"startProgramMs\":1000,\"endProgramMs\":4000,"
				+ "\"partial\":false,\"video\":{\"objectRef\":\"" + videoRef + "\",\"sha256\":\""
				+ MediaChecksums.sha256(video) + "\",\"sizeBytes\":" + video.length + ",\"durationMs\":"
				+ manifestDurationMs + ",\"width\":64,\"height\":36,\"fps\":10.0,\"videoCodec\":\"h264\","
				+ "\"audioCodec\":\"aac\",\"audioSampleRate\":48000},\"subtitle\":"
				+ (withSubtitle
						? "{\"objectRef\":\"" + srtRef + "\",\"sha256\":\"" + MediaChecksums.sha256(srt)
								+ "\",\"sizeBytes\":" + srt.length + "}"
						: "null")
				+ ",\"errorCode\":null}";
		db.sql("INSERT INTO dh_recording(id, owner_account_id, session_id, start_command_id, state, partial,"
				+ " start_program_ms, manifest, size_bytes, duration_ms, expires_at)"
				+ " VALUES (CAST(:r AS uuid), :owner, CAST(:s AS uuid), CAST(:cmd AS uuid), 'ready', false, 0,"
				+ " CAST(:manifest AS jsonb), :size, :duration, now() + interval '24 hours')")
				.bind("r", recordingId.toString()).bind("owner", owner).bind("s", sessionId.toString())
				.bind("cmd", UUID.randomUUID().toString()).bind("manifest", manifest).bind("size", video.length)
				.bind("duration", manifestDurationMs).then().block(Duration.ofSeconds(10));
		return recordingId;
	}

	private static final String SAMPLE_SRT = "1\n00:00:00,000 --> 00:00:01,000\n你好，草场\n\n";

	private static byte[] fixture(String name) {
		try (var input = DigitalHumanArtifactIT.class.getResourceAsStream("/" + name)) {
			assertThat(input).as("夹具 %s 必须在 test resources", name).isNotNull();
			return input.readAllBytes();
		} catch (IOException failure) {
			throw new IllegalStateException("夹具读取失败：" + name, failure);
		}
	}

	private static long probeDurationMs(byte[] video) {
		Path temp = null;
		try {
			temp = Files.createTempFile("dh-artifact-it-", ".mp4");
			Files.write(temp, video);
			return DigitalHumanArtifactProbe.probe(temp).durationMs();
		} catch (IOException failure) {
			throw new IllegalStateException("夹具探测失败", failure);
		} finally {
			try {
				if (temp != null) {
					Files.deleteIfExists(temp);
				}
			} catch (IOException ignored) {
				// 临时目录由操作系统回收。
			}
		}
	}

	private long countRows(String table, String where) {
		Long count = db.sql("SELECT count(*) AS n FROM " + table + " WHERE " + where)
				.map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		return count == null ? 0 : count;
	}

	private String mediaStatus(UUID mediaId) {
		return db.sql("SELECT status FROM media_reference WHERE id = CAST(:id AS uuid)").bind("id", mediaId.toString())
				.map(row -> row.get("status", String.class)).one().block(Duration.ofSeconds(10));
	}

	// ---------- TC105F-03-01：产物验证（四文件逐项） ----------

	@Test
	void tc105f_03_01_saveValidatesArtifactsOnlyQualifiedFileBecomesAsset() {
		// ① 有效文件：真实 ffprobe 通过 → 个人资产 + 字幕附件。
		byte[] valid = fixture("dh-recording-valid.mp4");
		long validDurationMs = probeDurationMs(valid);
		String ownerA = "dh-art-a-" + UUID.randomUUID();
		UUID validRecording = seedReadyRecording(ownerA, valid, validDurationMs, true);
		PersonalActor actorA = new PersonalActor(ownerA);
		SaveOutcome saved = artifacts.save(actorA, validRecording, UUID.randomUUID(), "有效录制", true)
				.block(Duration.ofSeconds(30));
		assertThat(saved.completedNow()).isTrue();
		assertThat(saved.operation().state().name()).isEqualTo("succeeded");
		UUID assetId = UUID.fromString(saved.operation().resultRef());
		assertThat(countRows("content_asset",
				"id = CAST('" + assetId + "' AS uuid) AND library_type = 'personal' AND status = 'active'"
						+ " AND media_reference_id = CAST('"
						+ DigitalHumanArtifactService.derivedMediaId(validRecording, "video") + "' AS uuid)"))
				.isEqualTo(1);
		assertThat(mediaStatus(DigitalHumanArtifactService.derivedMediaId(validRecording, "video")))
				.isEqualTo("active");
		assertThat(countRows("dh_asset_attachment",
				"asset_id = CAST('" + assetId + "' AS uuid) AND kind = 'subtitle' AND media_reference_id = CAST('"
						+ DigitalHumanArtifactService.derivedMediaId(validRecording, "subtitle") + "' AS uuid)"))
				.isEqualTo(1);
		assertThat(countRows("dh_recording", "id = CAST('" + validRecording + "' AS uuid) AND state = 'saved'"
				+ " AND asset_id = CAST('" + assetId + "' AS uuid)")).isEqualTo(1);
		assertThat(putCalls).hasSize(2); // mp4 + srt 各一次
		assertThat(objectStore.get(DigitalHumanArtifactService
				.objectKey(DigitalHumanArtifactService.derivedMediaId(validRecording, "video")))).isEqualTo(valid);
		// media 永久（expires_at 清空）且不占上传配额（source=generated）。
		assertThat(countRows("media_reference",
				"domain_type = 'dh_recording' AND expires_at IS NULL" + " AND source = 'generated'")).isEqualTo(2);

		// ②③④ 截断/无音轨/错时长：确定性 dh_media_invalid，不落资产、不写对象、段/操作收 failed。
		byte[] truncated = fixture("dh-recording-truncated.mp4");
		byte[] noAudio = fixture("dh-recording-noaudio.mp4");
		List<String> badOwners = List.of("dh-art-t-" + UUID.randomUUID(), "dh-art-n-" + UUID.randomUUID(),
				"dh-art-d-" + UUID.randomUUID());
		List<UUID> badRecordings = List.of(seedReadyRecording(badOwners.get(0), truncated, validDurationMs, false),
				seedReadyRecording(badOwners.get(1), noAudio, validDurationMs, false),
				// 错时长：字节有效但 manifest 声称的 durationMs 偏差 > 200ms。
				seedReadyRecording(badOwners.get(2), valid, validDurationMs + 5000, false));
		List<UUID> badKeys = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		for (int i = 0; i < badRecordings.size(); i++) {
			PersonalActor actor = new PersonalActor(badOwners.get(i));
			UUID recordingId = badRecordings.get(i);
			UUID badKey = badKeys.get(i);
			assertThatThrownBy(
					() -> artifacts.save(actor, recordingId, badKey, "坏产物", false).block(Duration.ofSeconds(30)))
					.isInstanceOfSatisfying(IntelligenceException.class, e -> {
						assertThat(e.status()).isEqualTo(409);
						assertThat(e.code()).isEqualTo("dh_media_invalid");
					});
			assertThat(countRows("dh_recording",
					"id = CAST('" + recordingId
							+ "' AS uuid) AND state = 'failed' AND error_code = 'dh_media_invalid'"))
					.as("坏产物 %s 收 failed", recordingId).isEqualTo(1);
			assertThat(countRows("content_asset",
					"source = 'dh_recording' AND owner_account_id = '" + badOwners.get(i) + "'"))
					.as("坏产物 %s 不落资产", recordingId).isZero();
			assertThat(
					countRows("dh_operation",
							"resource_id = CAST('" + recordingId
									+ "' AS uuid) AND state = 'failed' AND error_code = 'dh_media_invalid'"))
					.isEqualTo(1);
			assertThat(mediaStatus(DigitalHumanArtifactService.derivedMediaId(recordingId, "video")))
					.as("坏产物 %s media 留 pending 由 GC 兜底", recordingId).isEqualTo("pending");
			// 同键重放回原失败（K04）。
			assertThatThrownBy(
					() -> artifacts.save(actor, recordingId, badKey, "坏产物", false).block(Duration.ofSeconds(30)))
					.isInstanceOfSatisfying(IntelligenceException.class,
							e -> assertThat(e.code()).isEqualTo("dh_media_invalid"));
		}
		// 坏产物不写对象存储（仍只有有效案例的 2 次 put）。
		assertThat(putCalls).hasSize(2);
	}

	// ---------- TC105F-03-02：同键重试与响应丢失 ----------

	@Test
	void tc105f_03_02_sameKeyRetryReturnsSameAssetWithSingleSideEffects() {
		byte[] valid = fixture("dh-recording-valid.mp4");
		String owner = "dh-art-r-" + UUID.randomUUID();
		PersonalActor actor = new PersonalActor(owner);
		UUID recordingId = seedReadyRecording(owner, valid, probeDurationMs(valid), true);
		UUID requestId = UUID.randomUUID();

		// 首次保存成功后"丢响应"（调用方无感知），同键重试。
		SaveOutcome first = artifacts.save(actor, recordingId, requestId, "重试", true).block(Duration.ofSeconds(30));
		SaveOutcome retry = artifacts.save(actor, recordingId, requestId, "重试", true).block(Duration.ofSeconds(30));
		assertThat(retry.completedNow()).isFalse();
		assertThat(retry.operation().resultRef()).isEqualTo(first.operation().resultRef());
		assertThat(retry.operation().id()).isEqualTo(first.operation().id());
		assertThat(countRows("content_asset", "source = 'dh_recording' AND owner_account_id = '" + owner + "'"))
				.isEqualTo(1);
		assertThat(countRows("media_reference", "domain_type = 'dh_recording'")).isEqualTo(2);
		assertThat(putCalls).hasSize(2); // 对象各一次，重试零副作用

		// 真并发同键（两路同时进事务一）：行锁串行化，同 assetId、无第二资产/对象。
		UUID concurrentRecording = seedReadyRecording(owner, valid, probeDurationMs(valid), false);
		UUID concurrentKey = UUID.randomUUID();
		Mono<SaveOutcome> left = artifacts.save(actor, concurrentRecording, concurrentKey, "并发", false);
		Mono<SaveOutcome> right = artifacts.save(actor, concurrentRecording, concurrentKey, "并发", false);
		var both = Mono.zip(left, right).block(Duration.ofSeconds(30));
		assertThat(both.getT1().operation().resultRef()).isEqualTo(both.getT2().operation().resultRef());
		assertThat(countRows("content_asset", "source = 'dh_recording' AND owner_account_id = '" + owner + "'"))
				.isEqualTo(2);
		assertThat(countRows("dh_asset_attachment", "asset_id IS NOT NULL")).isEqualTo(1);
		// 同键不同体（includeSubtitles 变化）→ 409。
		assertThatThrownBy(
				() -> artifacts.save(actor, recordingId, requestId, "重试", false).block(Duration.ofSeconds(30)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_request_conflict"));
	}

	@Test
	void tc105f_03_02_httpSaveEnvelope() throws Exception {
		byte[] valid = fixture("dh-recording-valid.mp4");
		String owner = "dh-art-h-" + UUID.randomUUID();
		UUID recordingId = seedReadyRecording(owner, valid, probeDurationMs(valid), true);
		WebTestClient client = client();
		String body = "{\"requestId\":\"%s\",\"title\":\"HTTP保存\",\"includeSubtitles\":true}"
				.formatted(UUID.randomUUID());
		String created = client.post().uri("/api/digital-human/recordings/{id}/save", recordingId)
				.header("X-Grassland-Identity", sign(owner, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isAccepted().expectBody(String.class).returnResult()
				.getResponseBody();
		String assetId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).path("data")
				.path("resultRef").asText();
		assertThat(assetId).isNotBlank();
		// 同键重放：200 同一 succeeded。
		client.post().uri("/api/digital-human/recordings/{id}/save", recordingId)
				.header("X-Grassland-Identity", sign(owner, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody().jsonPath("$.data.resultRef")
				.isEqualTo(assetId);
		// 未登录 401；未知字段 422（严格解码）。
		client.post().uri("/api/digital-human/recordings/{id}/save", recordingId)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isUnauthorized();
		client.post().uri("/api/digital-human/recordings/{id}/save", recordingId)
				.header("X-Grassland-Identity", sign(owner, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"" + UUID.randomUUID() + "\",\"title\":\"x\",\"includeSubtitles\":true,"
						+ "\"extra\":1}")
				.exchange().expectStatus().isEqualTo(422);
	}

	// ---------- TC105F-03-04：下载与过期 ----------

	@Test
	void tc105f_03_04_downloadRangeExpiryAndSavedAssetLifecycle() {
		byte[] valid = fixture("dh-recording-valid.mp4");
		String owner = "dh-art-d-" + UUID.randomUUID();
		String other = "dh-art-o-" + UUID.randomUUID();
		PersonalActor actor = new PersonalActor(owner);
		UUID readyRecording = seedReadyRecording(owner, valid, probeDurationMs(valid), true);

		// 200 全量 mp4（ready 段直读 runtime 产物）。
		var full = artifacts.download(actor, readyRecording, "mp4", null).block(Duration.ofSeconds(30));
		assertThat(full.body()).isEqualTo(valid);
		assertThat(full.contentType()).isEqualTo("video/mp4");
		assertThat(full.rangeStart()).isNull();
		// 200 srt。
		var srt = artifacts.download(actor, readyRecording, "srt", null).block(Duration.ofSeconds(30));
		assertThat(new String(srt.body(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(SAMPLE_SRT);
		// 206 单区间切片 + 416（越界/多区间/畸形）。
		var partial = artifacts.download(actor, readyRecording, "mp4", "bytes=10-29").block(Duration.ofSeconds(30));
		assertThat(partial.rangeStart()).isEqualTo(10L);
		assertThat(partial.rangeEnd()).isEqualTo(29L);
		assertThat(partial.totalSize()).isEqualTo(valid.length);
		assertThat(partial.body()).containsExactly(java.util.Arrays.copyOfRange(valid, 10, 30));
		var openEnded = artifacts.download(actor, readyRecording, "mp4", "bytes=" + (valid.length - 5) + "-")
				.block(Duration.ofSeconds(30));
		assertThat(openEnded.body()).hasSize(5);
		for (String bad : List.of("bytes=" + valid.length + "-", "bytes=0-1,3-4", "chunks=0-1", "bytes=a-b",
				"bytes=5-1")) {
			assertThatThrownBy(
					() -> artifacts.download(actor, readyRecording, "mp4", bad).block(Duration.ofSeconds(30)))
					.as("Range %s 应 416", bad)
					.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(416));
		}
		// artifact 非法 422；他人 404。
		assertThatThrownBy(() -> artifacts.download(actor, readyRecording, "avi", null).block(Duration.ofSeconds(30)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(422));
		assertThatThrownBy(() -> artifacts.download(new PersonalActor(other), readyRecording, "mp4", null)
				.block(Duration.ofSeconds(30)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(404));
		assertThatThrownBy(
				() -> artifacts.download(actor, UUID.randomUUID(), "mp4", null).block(Duration.ofSeconds(30)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(404));

		// 未完成段 409。
		UUID unfinished = seedReadyRecording(owner, valid, probeDurationMs(valid), false);
		db.sql("UPDATE dh_recording SET state = 'finalizing' WHERE id = CAST(:r AS uuid)")
				.bind("r", unfinished.toString()).then().block(Duration.ofSeconds(10));
		assertThatThrownBy(() -> artifacts.download(actor, unfinished, "mp4", null).block(Duration.ofSeconds(30)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(409));

		// 过 24h 临时：ready + expires_at 已过 → 410。
		UUID expired = seedReadyRecording(owner, valid, probeDurationMs(valid), false);
		db.sql("UPDATE dh_recording SET expires_at = now() - interval '2 hours' WHERE id = CAST(:r AS uuid)")
				.bind("r", expired.toString()).then().block(Duration.ofSeconds(10));
		assertThatThrownBy(() -> artifacts.download(actor, expired, "mp4", null).block(Duration.ofSeconds(30)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> {
					assertThat(e.status()).isEqualTo(410);
					assertThat(e.code()).isEqualTo("dh_recording_expired");
				});

		// 已存 asset：下载走原库生命周期——保存后仍可下载；资产删除后 410。
		SaveOutcome saved = artifacts.save(actor, readyRecording, UUID.randomUUID(), "生命周期", true)
				.block(Duration.ofSeconds(30));
		fakeFetch.fetchCalls.clear();
		UUID assetId = UUID.fromString(saved.operation().resultRef());
		var afterSave = artifacts.download(actor, readyRecording, "mp4", "bytes=0-99").block(Duration.ofSeconds(30));
		assertThat(afterSave.body()).hasSize(100);
		assertThat(fakeFetch.fetchCalls).isEmpty(); // saved 后走本库存储，不再打 runtime
		var savedSrt = artifacts.download(actor, readyRecording, "srt", null).block(Duration.ofSeconds(30));
		assertThat(new String(savedSrt.body(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(SAMPLE_SRT);
		db.sql("UPDATE content_asset SET deleted_at = now(), status = 'rejected' WHERE id = CAST(:a AS uuid)")
				.bind("a", assetId.toString()).then().block(Duration.ofSeconds(10));
		assertThatThrownBy(() -> artifacts.download(actor, readyRecording, "mp4", null).block(Duration.ofSeconds(30)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> {
					assertThat(e.status()).isEqualTo(410);
					assertThat(e.code()).isEqualTo("dh_recording_expired");
				});
		assertThatThrownBy(() -> artifacts.download(actor, readyRecording, "srt", null).block(Duration.ofSeconds(30)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(410));
	}

	@Test
	void tc105f_03_04_httpDownloadHeaders() {
		byte[] valid = fixture("dh-recording-valid.mp4");
		String owner = "dh-art-dh-" + UUID.randomUUID();
		UUID recordingId = seedReadyRecording(owner, valid, probeDurationMs(valid), true);
		WebTestClient client = client();
		String auth = sign(owner, null);

		client.get().uri("/api/digital-human/recordings/{id}/download?artifact=mp4", recordingId)
				.header("X-Grassland-Identity", auth).exchange().expectStatus().isOk().expectHeader()
				.contentType("video/mp4").expectHeader()
				.valueMatches("Content-Disposition", "attachment; filename=\"recording-" + recordingId + "\\.mp4\"")
				.expectBody(byte[].class).isEqualTo(valid);
		client.get().uri("/api/digital-human/recordings/{id}/download?artifact=mp4", recordingId)
				.header("X-Grassland-Identity", auth).header("Range", "bytes=0-99").exchange().expectStatus()
				.isEqualTo(206).expectHeader().valueMatches("Content-Range", "bytes 0-99/" + valid.length)
				.expectBody(byte[].class).isEqualTo(java.util.Arrays.copyOfRange(valid, 0, 100));
		client.get().uri("/api/digital-human/recordings/{id}/download?artifact=mp4", recordingId)
				.header("X-Grassland-Identity", auth).header("Range", "bytes=0-1,3-4").exchange().expectStatus()
				.isEqualTo(416);
		client.get().uri("/api/digital-human/recordings/{id}/download?artifact=mp4", recordingId).exchange()
				.expectStatus().isUnauthorized();
		client.get().uri("/api/digital-human/recordings/{id}/download?artifact=avi", recordingId)
				.header("X-Grassland-Identity", auth).exchange().expectStatus().isEqualTo(422);
	}
}
