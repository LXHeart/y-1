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
import com.grassland.intelligence.digitalhuman.DigitalHumanArtifactService.SavePlan;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.StoredObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Mono;

/**
 * save-GC 竞争 IT（任务书 #105F C105F-03 / TC105F-03-03）：K13.5 同一 media 引用、同一 claim
 * 协议、 同一行锁——受控屏障（beginSave 事务一与 finishSave 事务二之间的 GC 领取调用）与真并发 zip 两种序，
 * 断言「清理先抢到 → save 失败不挂已删对象；save 先挂载 → GC 保留」且绝无悬空 asset；资产删除后 护栏解除（G
 * 的释放语义，worker 面在 C105F-05）。真实 DB 行锁/状态机；网络边界全 fake。
 */
@Import(DigitalHumanSaveGcRaceIT.FakeArtifactNetwork.class)
class DigitalHumanSaveGcRaceIT extends IntelligenceItSupport {

	@org.springframework.beans.factory.annotation.Autowired
	private org.springframework.r2dbc.core.DatabaseClient db;
	@org.springframework.beans.factory.annotation.Autowired
	private DigitalHumanArtifactService artifacts;
	@org.springframework.beans.factory.annotation.Autowired
	private MediaReferenceRepository mediaRefs;
	@org.springframework.beans.factory.annotation.Autowired
	private FakeArtifactFetchPort fakeFetch;
	@org.springframework.test.context.bean.override.mockito.MockitoBean
	private ObjectStorageAdapter storage;

	private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();

	static class FakeArtifactNetwork {

		@org.springframework.context.annotation.Bean
		FakeArtifactFetchPort fakeArtifactFetchPort() {
			return new FakeArtifactFetchPort();
		}
	}

	static class FakeArtifactFetchPort implements ArtifactFetchPort {

		final Map<String, byte[]> objects = new ConcurrentHashMap<>();

		@Override
		public Mono<byte[]> fetchArtifact(UUID resourceId, String objectRef) {
			return Mono.defer(() -> {
				byte[] bytes = objects.get(objectRef);
				if (bytes == null) {
					return Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "runtime 无此产物。"));
				}
				return Mono.just(bytes);
			});
		}
	}

	@org.junit.jupiter.api.BeforeEach
	void seed() {
		db.sql("DELETE FROM dh_asset_attachment").then()
				.then(db.sql("DELETE FROM content_asset WHERE source = 'dh_recording'").then())
				.then(db.sql("DELETE FROM media_reference WHERE domain_type = 'dh_recording'").then())
				.then(db.sql("DELETE FROM dh_cleanup WHERE resource_kind = 'recording_object'").then())
				.then(db.sql("DELETE FROM dh_recording").then())
				.then(db.sql("DELETE FROM dh_operation WHERE kind LIKE 'recording_%'").then())
				.then(db.sql("DELETE FROM dh_invocation").then()).then(db.sql("DELETE FROM dh_preview").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_turn").then()).then(db.sql("DELETE FROM dh_session").then())
				.block(Duration.ofSeconds(10));
		objectStore.clear();
		fakeFetch.objects.clear();
		reset(storage);
		doAnswer(invocation -> {
			objectStore.put(invocation.getArgument(0), invocation.getArgument(1));
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

	private UUID seedReadyRecording(String owner, byte[] video, long durationMs, boolean withSubtitle) {
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
		fakeFetch.objects.put(videoRef, video);
		byte[] srt = "1\n00:00:00,000 --> 00:00:01,000\n你好，草场\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		String subtitle = "null";
		if (withSubtitle) {
			fakeFetch.objects.put("dhr-" + recordingId + "-srt", srt);
			subtitle = "{\"objectRef\":\"dhr-" + recordingId + "-srt\",\"sha256\":\"" + MediaChecksums.sha256(srt)
					+ "\",\"sizeBytes\":" + srt.length + "}";
		}
		String manifest = "{\"recordingId\":\"" + recordingId + "\",\"startProgramMs\":1000,\"endProgramMs\":4000,"
				+ "\"partial\":false,\"video\":{\"objectRef\":\"" + videoRef + "\",\"sha256\":\""
				+ MediaChecksums.sha256(video) + "\",\"sizeBytes\":" + video.length + ",\"durationMs\":" + durationMs
				+ ",\"width\":64,\"height\":36,\"fps\":10.0,\"videoCodec\":\"h264\",\"audioCodec\":\"aac\","
				+ "\"audioSampleRate\":48000},\"subtitle\":" + subtitle + ",\"errorCode\":null}";
		db.sql("INSERT INTO dh_recording(id, owner_account_id, session_id, start_command_id, state, partial,"
				+ " start_program_ms, manifest, size_bytes, duration_ms, expires_at)"
				+ " VALUES (CAST(:r AS uuid), :owner, CAST(:s AS uuid), CAST(:cmd AS uuid), 'ready', false, 0,"
				+ " CAST(:manifest AS jsonb), :size, :duration, now() + interval '24 hours')")
				.bind("r", recordingId.toString()).bind("owner", owner).bind("s", sessionId.toString())
				.bind("cmd", UUID.randomUUID().toString()).bind("manifest", manifest).bind("size", video.length)
				.bind("duration", durationMs).then().block(Duration.ofSeconds(10));
		return recordingId;
	}

	private static byte[] fixture(String name) {
		try (var input = DigitalHumanSaveGcRaceIT.class.getResourceAsStream("/" + name)) {
			assertThat(input).as("夹具 %s 必须在 test resources", name).isNotNull();
			return input.readAllBytes();
		} catch (IOException failure) {
			throw new IllegalStateException("夹具读取失败：" + name, failure);
		}
	}

	private static long probeDurationMs(byte[] video) {
		Path temp = null;
		try {
			temp = Files.createTempFile("dh-race-it-", ".mp4");
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

	// ---------- 序一：清理先抢到 → save 失败不挂已删对象 ----------

	@org.junit.jupiter.api.Test
	void tc105f_03_03_cleanupClaimsFirstThenSaveFailsWithoutDanglingAsset() {
		byte[] valid = fixture("dh-recording-valid.mp4");
		String owner = "dh-race-a-" + UUID.randomUUID();
		PersonalActor actor = new PersonalActor(owner);
		UUID recordingId = seedReadyRecording(owner, valid, probeDurationMs(valid), false);

		// 屏障前半：save 事务一提交（saving + pending media + 计划列）。
		SavePlan plan = artifacts.beginSave(actor, recordingId, UUID.randomUUID(), "竞争一", false)
				.block(Duration.ofSeconds(10));
		assertThat(plan).isNotNull();
		assertThat(mediaStatus(plan.videoMediaId())).isEqualTo("pending");

		// 屏障点：GC 到期扫描领取同一 media（生命周期锁 + 新快照，此刻无资产引用）。
		MediaReference claimed = mediaRefs.claimCleanup(plan.videoMediaId()).block(Duration.ofSeconds(10));
		assertThat(claimed).isNotNull();
		assertThat(claimed.status().db()).isEqualTo("deleting");

		// 屏障后半：save 提交事务二——清理已抢到，save 失败且不挂已删对象。
		assertThatThrownBy(() -> artifacts.finishSave(actor, plan).block(Duration.ofSeconds(30)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> {
					assertThat(e.status()).isEqualTo(410);
					assertThat(e.code()).isEqualTo("dh_recording_expired");
				});
		assertThat(countRows("content_asset", "source = 'dh_recording'")).isZero();
		assertThat(countRows("dh_asset_attachment", "asset_id IS NOT NULL")).isZero();
		// GC 所有权未被打断（无悬空 asset、media 仍 deleting 由 GC 收尾）。
		assertThat(mediaStatus(plan.videoMediaId())).isEqualTo("deleting");
		assertThat(countRows("dh_recording", "id = CAST('" + recordingId + "' AS uuid) AND state = 'expired'"
				+ " AND error_code = 'dh_recording_expired'")).isEqualTo(1);
		assertThat(
				countRows("dh_operation",
						"resource_id = CAST('" + recordingId
								+ "' AS uuid) AND state = 'failed' AND error_code = 'dh_recording_expired'"))
				.isEqualTo(1);
	}

	// ---------- 序二：save 先挂载 → GC 保留；资产删除后护栏解除 ----------

	@org.junit.jupiter.api.Test
	void tc105f_03_03_saveMountsFirstThenGcRetainsUntilAssetDeleted() {
		byte[] valid = fixture("dh-recording-valid.mp4");
		String owner = "dh-race-b-" + UUID.randomUUID();
		PersonalActor actor = new PersonalActor(owner);
		UUID recordingId = seedReadyRecording(owner, valid, probeDurationMs(valid), true);

		SavePlan plan = artifacts.beginSave(actor, recordingId, UUID.randomUUID(), "竞争二", true)
				.block(Duration.ofSeconds(10));
		SaveOutcome outcome = artifacts.finishSave(actor, plan).block(Duration.ofSeconds(30));
		UUID assetId = UUID.fromString(outcome.operation().resultRef());
		assertThat(countRows("content_asset", "id = CAST('" + assetId + "' AS uuid) AND status = 'active'"))
				.isEqualTo(1);

		// GC 领取同一 media：主引用/附件引用护栏（生命周期锁后新快照）→ 空，不物删。
		assertThat(mediaRefs.claimCleanup(plan.videoMediaId()).block(Duration.ofSeconds(10))).isNull();
		assertThat(mediaRefs.claimCleanup(plan.subtitleMediaId()).block(Duration.ofSeconds(10))).isNull();
		assertThat(mediaStatus(plan.videoMediaId())).isEqualTo("active");
		assertThat(mediaStatus(plan.subtitleMediaId())).isEqualTo("active");
		assertThat(objectStore.get(DigitalHumanArtifactService.objectKey(plan.videoMediaId()))).isEqualTo(valid);

		// G 的释放语义：资产删除（deleted_at + 终态）后护栏解除 → GC 可领取。
		db.sql("UPDATE content_asset SET deleted_at = now(), status = 'rejected' WHERE id = CAST(:a AS uuid)")
				.bind("a", assetId.toString()).then().block(Duration.ofSeconds(10));
		MediaReference released = mediaRefs.claimCleanup(plan.videoMediaId()).block(Duration.ofSeconds(10));
		assertThat(released).isNotNull();
		assertThat(released.status().db()).isEqualTo("deleting");
		assertThat(mediaRefs.claimCleanup(plan.subtitleMediaId()).block(Duration.ofSeconds(10))).isNotNull();
	}

	// ---------- 真并发：行锁串行化，结果二选一，绝无悬空 asset ----------

	@org.junit.jupiter.api.Test
	void tc105f_03_03_concurrentClaimAndSaveSerializeToSingleOutcome() {
		byte[] valid = fixture("dh-recording-valid.mp4");
		String owner = "dh-race-c-" + UUID.randomUUID();
		PersonalActor actor = new PersonalActor(owner);
		UUID recordingId = seedReadyRecording(owner, valid, probeDurationMs(valid), false);
		SavePlan plan = artifacts.beginSave(actor, recordingId, UUID.randomUUID(), "并发", false)
				.block(Duration.ofSeconds(10));

		Mono<Object> saveLeg = artifacts.finishSave(actor, plan).map(outcome -> (Object) outcome)
				.onErrorResume(failure -> Mono.just(failure));
		Mono<Object> gcLeg = mediaRefs.claimCleanup(plan.videoMediaId()).map(ref -> (Object) ref)
				.defaultIfEmpty("gc-empty");
		var both = Mono.zip(saveLeg, gcLeg).block(Duration.ofSeconds(30));

		boolean saveWon = both.getT1() instanceof SaveOutcome;
		if (saveWon) {
			// save 先取得锁并挂载：GC 随后领取被护栏拒绝（空）。
			assertThat(both.getT2()).isEqualTo("gc-empty");
			SaveOutcome outcome = (SaveOutcome) both.getT1();
			assertThat(countRows("content_asset",
					"id = CAST('" + UUID.fromString(outcome.operation().resultRef()) + "' AS uuid)")).isEqualTo(1);
			assertThat(mediaStatus(plan.videoMediaId())).isEqualTo("active");
		} else {
			// GC 先取得锁：save 失败（410 窗口关闭），无悬空 asset。
			assertThat(both.getT1()).isInstanceOf(IntelligenceException.class);
			assertThat(((IntelligenceException) both.getT1()).code()).isEqualTo("dh_recording_expired");
			assertThat(both.getT2()).isInstanceOf(MediaReference.class);
			assertThat(countRows("content_asset", "source = 'dh_recording'")).isZero();
			assertThat(mediaStatus(plan.videoMediaId())).isEqualTo("deleting");
		}
	}
}
