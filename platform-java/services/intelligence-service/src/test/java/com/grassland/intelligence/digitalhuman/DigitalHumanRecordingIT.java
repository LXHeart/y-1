package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecordingService.ArtifactAcceptance;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecordingService.RecordingRuntimePort;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Recording;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * 输出AV录制 IT（任务书 #105F C105F-02 / TC105F-02-01～04 Java 侧子断言）：真实 DB （dh_recording
 * 唯一约束/活动段部分唯一/总段数 ≤2/dh_operation 幂等键/清理登记）+ 最外层网络边界 fake（runtime
 * 控制端口）。真编码/分段时钟/溢出/上限的 runtime 面在 tests/test_recording.py （跨语言同 TC
 * 各有子断言）；INTERNAL11 HTTP 回执经服务层断言（与 AvatarIT 同口径）。
 */
@Import(DigitalHumanRecordingIT.FakeRecordingNetwork.class)
class DigitalHumanRecordingIT extends IntelligenceItSupport {

	@Autowired
	private DatabaseClient db;
	@Autowired
	private DigitalHumanRecordingService recordings;
	@Autowired
	private FakeRecordingRuntimePort fakeRuntime;

	private final String account = "dh-rec-a-" + UUID.randomUUID();
	private final PersonalActor actor = new PersonalActor(account);
	private final String other = "dh-rec-b-" + UUID.randomUUID();

	/** 最外层网络替身：runtime 控制端口（DB/owner/幂等键全部真实）。 */
	static class FakeRecordingNetwork {

		@Bean
		FakeRecordingRuntimePort fakeRecordingRuntimePort() {
			return new FakeRecordingRuntimePort();
		}
	}

	static class FakeRecordingRuntimePort implements RecordingRuntimePort {

		final List<String> startCalls = new CopyOnWriteArrayList<>();
		final List<String> stopCalls = new CopyOnWriteArrayList<>();
		final java.util.Set<String> dispatchedStart = java.util.concurrent.ConcurrentHashMap.newKeySet();
		final java.util.Set<String> dispatchedStop = java.util.concurrent.ConcurrentHashMap.newKeySet();
		volatile boolean failStartWithConflict = false;

		@Override
		public Mono<Void> startRecording(String sessionId, UUID recordingId, UUID commandId, long leaseEpoch,
				long maxDurationMs, long maxBytes) {
			return Mono.defer(() -> {
				if (failStartWithConflict) {
					return Mono.error(new IntelligenceException(409, "dh_state_conflict", "runtime 拒绝录制。"));
				}
				// 真实 INTERNAL02 按 commandId 幂等：同段重复派发只受理一次（重放重派发不重复计数）。
				if (dispatchedStart.add(recordingId.toString())) {
					startCalls.add(sessionId + "|" + recordingId + "|" + maxDurationMs + "|" + maxBytes);
				}
				return Mono.empty();
			});
		}

		@Override
		public Mono<Void> stopRecording(String sessionId, UUID recordingId, UUID commandId, long leaseEpoch,
				String reasonCode) {
			return Mono.defer(() -> {
				if (dispatchedStop.add(recordingId.toString())) {
					stopCalls.add(sessionId + "|" + recordingId + "|" + reasonCode);
				}
				return Mono.empty();
			});
		}
	}

	@BeforeEach
	void seed() {
		// 组合套跑时其它 IT 留下 turn/transcript/event/invocation 子行：子表先于 dh_session 删。
		db.sql("DELETE FROM dh_cleanup WHERE resource_kind = 'recording_object'").then()
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
		fakeRuntime.startCalls.clear();
		fakeRuntime.stopCalls.clear();
		fakeRuntime.dispatchedStart.clear();
		fakeRuntime.dispatchedStop.clear();
		fakeRuntime.failStartWithConflict = false;
	}

	@org.junit.jupiter.api.AfterEach
	void cleanupRows() {
		// V89 的 dh_recording→dh_session FK：本类留下的段行会让后续 IT 的 seed（先删 session）撞外键。
		db.sql("DELETE FROM dh_cleanup WHERE resource_kind = 'recording_object'").then()
				.then(db.sql("DELETE FROM dh_recording").then())
				.then(db.sql("DELETE FROM dh_operation WHERE kind LIKE 'recording_%'").then())
				.block(Duration.ofSeconds(10));
	}

	private UUID seedSession(String owner, String state, long leaseEpoch) {
		UUID sessionId = UUID.randomUUID();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, lease_epoch)"
				+ " VALUES (CAST(:s AS uuid), :owner, CAST(:p AS uuid), 1, '角色', 'dh-it-backend',"
				+ " CAST(:pf AS uuid), CAST(:c AS uuid), CAST('{}' AS jsonb), :state, now(), :lease)")
				.bind("s", sessionId.toString()).bind("owner", owner).bind("p", UUID.randomUUID().toString())
				.bind("pf", UUID.randomUUID().toString()).bind("c", UUID.randomUUID().toString()).bind("state", state)
				.bind("lease", leaseEpoch).then().block(Duration.ofSeconds(10));
		return sessionId;
	}

	private long countRows(String table, String where) {
		Long count = db.sql("SELECT count(*) AS n FROM " + table + " WHERE " + where)
				.map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		return count == null ? 0 : count;
	}

	private static String manifestJson(String recordingId, boolean withSubtitle) {
		String subtitle = withSubtitle
				? ",\"subtitle\":{\"objectRef\":\"dhr-" + recordingId + "-srt\",\"sha256\":\"" + "b".repeat(64)
						+ "\",\"sizeBytes\":99}"
				: ",\"subtitle\":null";
		return "{\"recordingId\":\"" + recordingId + "\",\"startProgramMs\":1000,\"endProgramMs\":4000,"
				+ "\"partial\":false,\"video\":{\"objectRef\":\"dhr-" + recordingId + "-mp4\",\"sha256\":\""
				+ "a".repeat(64) + "\",\"sizeBytes\":12345,\"durationMs\":3000,\"width\":64,\"height\":36,"
				+ "\"fps\":10.0,\"videoCodec\":\"h264\",\"audioCodec\":\"aac\",\"audioSampleRate\":48000}" + subtitle
				+ ",\"errorCode\":null}";
	}

	// ---------- TC105F-02-01：start 编排与幂等 ----------

	@Test
	void tc105f_02_01_startCreatesRecordingAndDispatchesOnce() {
		UUID sessionId = seedSession(account, "ready", 1);
		UUID requestId = UUID.randomUUID();

		DigitalHumanRecordingService.StartResult created = recordings.start(actor, sessionId, requestId, 1, true)
				.block(Duration.ofSeconds(10));
		assertThat(created.createdNow()).isTrue();
		assertThat(created.recording().state()).isEqualTo("recording");
		assertThat(created.recording().sessionId()).isEqualTo(sessionId.toString());
		assertThat(fakeRuntime.startCalls).hasSize(1);
		assertThat(fakeRuntime.startCalls.get(0)).contains(sessionId.toString()).contains("300000")
				.contains(String.valueOf(200L * 1024 * 1024));

		// 同键同体重放：同一 recordingId，不二次派发。
		DigitalHumanRecordingService.StartResult replay = recordings.start(actor, sessionId, requestId, 1, true)
				.block(Duration.ofSeconds(10));
		assertThat(replay.createdNow()).isFalse();
		assertThat(replay.recording().id()).isEqualTo(created.recording().id());
		assertThat(fakeRuntime.startCalls).hasSize(1);

		// operation 收口 succeeded 且回填 resource。
		assertThat(countRows("dh_operation",
				"kind = 'recording_start' AND state = 'succeeded' AND resource_id IS NOT NULL")).isEqualTo(1);
		// start_command_id 存最初 requestId（K13：不对外暴露）。
		assertThat(countRows("dh_recording", "start_command_id = CAST('" + requestId + "' AS uuid)")).isEqualTo(1);
	}

	@Test
	void tc105f_02_01_stopArtifactReadyWithCleanupRegistration() {
		UUID sessionId = seedSession(account, "ready", 1);
		Recording created = recordings.start(actor, sessionId, UUID.randomUUID(), 1, true).block(Duration.ofSeconds(10))
				.recording();
		UUID recordingId = UUID.fromString(created.id());

		DigitalHumanRecordingService.StopResult stopped = recordings.stop(actor, recordingId, UUID.randomUUID())
				.block(Duration.ofSeconds(10));
		assertThat(stopped.recording().state()).isEqualTo("finalizing");
		assertThat(fakeRuntime.stopCalls).hasSize(1);

		// INTERNAL11 ready：manifest 落库、逐对象清理登记、ready + 24h 过期。
		ArtifactAcceptance accepted = recordings
				.applyRecordingArtifact(recordingId, 1, manifestJson(recordingId.toString(), true), null)
				.block(Duration.ofSeconds(10));
		assertThat(accepted.accepted()).isTrue();

		Recording ready = recordings.get(actor, recordingId).block(Duration.ofSeconds(10));
		assertThat(ready.state()).isEqualTo("ready");
		assertThat(ready.durationMs()).isEqualTo(3000);
		assertThat(ready.sizeBytes()).isEqualTo(12345);
		assertThat(ready.subtitleAvailable()).isTrue();
		assertThat(ready.expiresAt()).isNotNull();
		assertThat(countRows("dh_cleanup",
				"resource_kind = 'recording_object' AND resource_id = CAST('" + recordingId + "' AS uuid)"))
				.isEqualTo(2);

		// 同键重放 stop：回原终态（200 语义），不重复派发；迟到回执被墓碑拦截。
		DigitalHumanRecordingService.StopResult replay = recordings.stop(actor, recordingId, UUID.randomUUID())
				.block(Duration.ofSeconds(10));
		assertThat(replay.recording().state()).isEqualTo("ready");
		assertThat(fakeRuntime.stopCalls).hasSize(1);
		ArtifactAcceptance late = recordings
				.applyRecordingArtifact(recordingId, 1, manifestJson(recordingId.toString(), true), null)
				.block(Duration.ofSeconds(10));
		assertThat(late.accepted()).isFalse();
		assertThat(late.reason()).isEqualTo("state-ready");

		// 跨 owner 读取 → 404（不泄漏存在性）。
		assertThatThrownBy(() -> recordings.get(new PersonalActor(other), recordingId).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(404));
	}

	// ---------- TC105F-02-02/03：能力门禁、并发与状态约束 ----------

	@Test
	void tc105f_02_02_capabilityAndConcurrencyGates() {
		UUID sessionId = seedSession(account, "ready", 1);

		// 能力关闭 → 422 dh_recording_unsupported（零行零派发）。
		db.sql("UPDATE dh_catalog SET config_json = jsonb_set(config_json, '{recordingEnabled}', 'false')").then()
				.block(Duration.ofSeconds(10));
		assertThatThrownBy(
				() -> recordings.start(actor, sessionId, UUID.randomUUID(), 1, true).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> {
					assertThat(e.status()).isEqualTo(422);
					assertThat(e.code()).isEqualTo("dh_recording_unsupported");
				});
		assertThat(countRows("dh_recording", "session_id = CAST('" + sessionId + "' AS uuid)")).isZero();
		db.sql("UPDATE dh_catalog SET config_json = jsonb_set(config_json, '{recordingEnabled}', 'true')").then()
				.block(Duration.ofSeconds(10));

		// 未确认字幕授权 → 422；租约代次过期 → 409 dh_lease_stale。
		assertThatThrownBy(
				() -> recordings.start(actor, sessionId, UUID.randomUUID(), 1, false).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_invalid_input"));
		assertThatThrownBy(
				() -> recordings.start(actor, sessionId, UUID.randomUUID(), 7, true).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> {
					assertThat(e.status()).isEqualTo(409);
					assertThat(e.code()).isEqualTo("dh_lease_stale");
				});

		// 已结束会话 → 409；未知会话/跨 owner → 404。
		UUID endedSession = seedSession(account, "ended", 1);
		assertThatThrownBy(
				() -> recordings.start(actor, endedSession, UUID.randomUUID(), 1, true).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(409));
		assertThatThrownBy(() -> recordings.start(actor, UUID.randomUUID(), UUID.randomUUID(), 1, true)
				.block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(404));

		// 并发第二活动段 → 409；同会话两段收口后第三段 → 409（总段数 ≤2 锁内检查）。
		recordings.start(actor, sessionId, UUID.randomUUID(), 1, true).block(Duration.ofSeconds(10));
		assertThatThrownBy(
				() -> recordings.start(actor, sessionId, UUID.randomUUID(), 1, true).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> {
					assertThat(e.status()).isEqualTo(409);
					assertThat(e.code()).isEqualTo("dh_state_conflict");
				});
	}

	@Test
	void tc105f_02_02_twoSegmentsThenThirdRejected() {
		UUID sessionId = seedSession(account, "ready", 1);
		String firstId = recordings.start(actor, sessionId, UUID.randomUUID(), 1, true).block(Duration.ofSeconds(10))
				.recording().id();
		recordings.stop(actor, UUID.fromString(firstId), UUID.randomUUID()).block(Duration.ofSeconds(10));
		recordings.applyRecordingArtifact(UUID.fromString(firstId), 1, manifestJson(firstId, false), null)
				.block(Duration.ofSeconds(10));

		String secondId = recordings.start(actor, sessionId, UUID.randomUUID(), 1, true).block(Duration.ofSeconds(10))
				.recording().id();
		recordings.stop(actor, UUID.fromString(secondId), UUID.randomUUID()).block(Duration.ofSeconds(10));
		recordings.applyRecordingArtifact(UUID.fromString(secondId), 1, manifestJson(secondId, false), null)
				.block(Duration.ofSeconds(10));

		assertThatThrownBy(
				() -> recordings.start(actor, sessionId, UUID.randomUUID(), 1, true).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> {
					assertThat(e.status()).isEqualTo(409);
					assertThat(e.code()).isEqualTo("dh_state_conflict");
				});
		// 同 id 不再新建段：重放首段 start 同键返回原段。
		assertThat(countRows("dh_recording", "session_id = CAST('" + sessionId + "' AS uuid)")).isEqualTo(2);
	}

	// ---------- TC105F-02-03/04：确定性失败与墓碑 ----------

	@Test
	void tc105f_02_03_runtimeConflictFailsRowWithFailedReceipt() {
		UUID sessionId = seedSession(account, "ready", 1);
		fakeRuntime.failStartWithConflict = true;
		UUID requestId = UUID.randomUUID();
		assertThatThrownBy(() -> recordings.start(actor, sessionId, requestId, 1, true).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(409));
		assertThat(
				countRows("dh_recording", "session_id = CAST('" + sessionId + "' AS uuid)" + " AND state = 'failed'"))
				.isEqualTo(1);
		// 同键重放：回原失败行（failed receipt），不再派发、不新建行。
		DigitalHumanRecordingService.StartResult replay = recordings.start(actor, sessionId, requestId, 1, true)
				.block(Duration.ofSeconds(10));
		assertThat(replay.createdNow()).isFalse();
		assertThat(replay.recording().state()).isEqualTo("failed");
		assertThat(fakeRuntime.startCalls).isEmpty();
		assertThat(countRows("dh_recording", "session_id = CAST('" + sessionId + "' AS uuid)")).isEqualTo(1);
	}

	@Test
	void tc105f_02_04_partialAndFailedArtifacts() {
		UUID sessionId = seedSession(account, "ready", 1);
		String recordingId = recordings.start(actor, sessionId, UUID.randomUUID(), 1, true)
				.block(Duration.ofSeconds(10)).recording().id();
		recordings.stop(actor, UUID.fromString(recordingId), UUID.randomUUID()).block(Duration.ofSeconds(10));

		// errorCode + 部分 manifest 共存：failed 行 + 已写对象清理登记（TC105F-02-04 部分失败）。
		String partialManifest = manifestJson(recordingId, true).replace("\"partial\":false", "\"partial\":true");
		ArtifactAcceptance failed = recordings
				.applyRecordingArtifact(UUID.fromString(recordingId), 1, partialManifest, "dh_recording_overflow")
				.block(Duration.ofSeconds(10));
		assertThat(failed.accepted()).isTrue();
		Recording row = recordings.get(actor, UUID.fromString(recordingId)).block(Duration.ofSeconds(10));
		assertThat(row.state()).isEqualTo("failed");
		assertThat(row.partial()).isTrue();
		assertThat(row.errorCode()).isEqualTo("dh_recording_overflow");
		assertThat(countRows("dh_cleanup",
				"resource_kind = 'recording_object' AND resource_id = CAST('" + recordingId + "' AS uuid)"))
				.isEqualTo(2);

		// 无音画回执：failed dh_recording_failed，无产物对象登记（独立会话/账号，避免段数与活动会话唯一约束干扰）。
		String secondAccount = "dh-rec-c-" + UUID.randomUUID();
		PersonalActor secondActor = new PersonalActor(secondAccount);
		UUID secondSession = seedSession(secondAccount, "ready", 1);
		String secondId = recordings.start(secondActor, secondSession, UUID.randomUUID(), 1, true)
				.block(Duration.ofSeconds(10)).recording().id();
		recordings.stop(secondActor, UUID.fromString(secondId), UUID.randomUUID()).block(Duration.ofSeconds(10));
		recordings.applyRecordingArtifact(UUID.fromString(secondId), 1, null, "dh_recording_failed")
				.block(Duration.ofSeconds(10));
		assertThat(recordings.get(secondActor, UUID.fromString(secondId)).block(Duration.ofSeconds(10)).state())
				.isEqualTo("failed");
		assertThat(countRows("dh_cleanup",
				"resource_kind = 'recording_object' AND resource_id = CAST('" + secondId + "' AS uuid)")).isZero();

		// recording 态（未 stop）收到回执 → 墓碑拒绝（不在 finalizing）。
		String thirdAccount = "dh-rec-d-" + UUID.randomUUID();
		PersonalActor thirdActor = new PersonalActor(thirdAccount);
		UUID thirdSession = seedSession(thirdAccount, "ready", 1);
		String thirdId = recordings.start(thirdActor, thirdSession, UUID.randomUUID(), 1, true)
				.block(Duration.ofSeconds(10)).recording().id();
		ArtifactAcceptance premature = recordings
				.applyRecordingArtifact(UUID.fromString(thirdId), 1, manifestJson(thirdId, false), null)
				.block(Duration.ofSeconds(10));
		assertThat(premature.accepted()).isFalse();
		assertThat(premature.reason()).isEqualTo("state-recording");
	}

	// ---------- HTTP 信封（装配层） ----------

	@Test
	void tc105f_02_01_httpEndpointsEnvelope() {
		UUID sessionId = seedSession(account, "ready", 1);
		WebTestClient client = client();
		String recordingId = extractRecordingId(client, sessionId);

		client.get().uri("/api/digital-human/recordings/{id}", UUID.fromString(recordingId))
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.state").isEqualTo("recording");

		// 未知字段拒绝（K00）；未认证 401。
		client.post().uri("/api/digital-human/sessions/{id}/recordings", sessionId)
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"" + UUID.randomUUID() + "\",\"leaseEpoch\":1,"
						+ "\"acknowledgement\":true,\"extra\":1}")
				.exchange().expectStatus().isEqualTo(422);
		client.get().uri("/api/digital-human/recordings/{id}", UUID.fromString(recordingId)).exchange().expectStatus()
				.isEqualTo(401);

		// stop → 202 finalizing；能力与请求体面在服务层已覆盖。
		client.post().uri("/api/digital-human/recordings/{id}/stop", UUID.fromString(recordingId))
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"" + UUID.randomUUID() + "\"}").exchange().expectStatus().isAccepted()
				.expectBody().jsonPath("$.data.state").isEqualTo("finalizing");
	}

	private String extractRecordingId(WebTestClient client, UUID sessionId) {
		byte[] body = client.post().uri("/api/digital-human/sessions/{id}/recordings", sessionId)
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"" + UUID.randomUUID() + "\",\"leaseEpoch\":1,\"acknowledgement\":true}")
				.exchange().expectStatus().isAccepted().expectBody().returnResult().getResponseBody();
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data").path("id").asText();
		} catch (Exception invalid) {
			throw new IllegalStateException("解析录制响应失败", invalid);
		}
	}
}
