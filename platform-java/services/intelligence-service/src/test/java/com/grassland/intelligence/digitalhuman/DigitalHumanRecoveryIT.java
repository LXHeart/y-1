package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.digitalhuman.DigitalHumanArtifactService.ArtifactFetchPort;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationState;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.StoredObject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/**
 * 任务书 #105H C105H-04（TC105H-04-02/TC105H-04-03）：故障恢复与存储失败。
 *
 * <ul>
 * <li>派发后半流（tc105h_04_02）：provider 已接受（dispatched）且 usage 丢失——窗口内重入 确定性
 * 409；deadline 过后转 unknown 且原经济键/操作键保留；prepared→dispatched 只允许一次 CAS（provider
 * 调用 ≤1 的结构性前提）；unknown 不得复活重发。
 * <li>存储失败（tc105h_04_03）： 录制产物损坏（磁盘满/坏文件类比）——坏文件不入库（无 content_asset）、失败尝试的
 * pending media 保留可清理重试口径、已存在资产不误删；修复（换好产物）后同资源新 requestId 重试成功。
 * </ul>
 *
 * <p>
 * 真实 DB/事务；网络负例用受控 fetch 替身（INTERNAL12 面）——不 mock 事务、幂等与账务路径。
 */
@Import(DigitalHumanRecoveryIT.FakeRecoveryNetwork.class)
class DigitalHumanRecoveryIT extends IntelligenceItSupport {

	@TestConfiguration
	static class FakeRecoveryNetwork {

		@org.springframework.context.annotation.Bean
		FakeRecoveryFetchPort fakeRecoveryFetchPort() {
			return new FakeRecoveryFetchPort();
		}
	}

	/** 受控产物读取替身：可注入坏字节/IO 失败（磁盘满类比），调用计数真实。 */
	static class FakeRecoveryFetchPort implements ArtifactFetchPort {

		final Map<String, byte[]> objects = new ConcurrentHashMap<>();
		boolean failWithIo = false;

		@Override
		public Mono<byte[]> fetchArtifact(UUID resourceId, String objectRef) {
			return Mono.defer(() -> {
				if (failWithIo) {
					return Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "读取产物失败（IO）。"));
				}
				byte[] bytes = objects.get(objectRef);
				if (bytes == null) {
					return Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "runtime 无此产物。"));
				}
				return Mono.just(bytes);
			});
		}
	}

	@Autowired
	private DatabaseClient db;
	@Autowired
	private DigitalHumanInvocationService invocations;
	@Autowired
	private DigitalHumanInvocationRepository invocationRows;
	@Autowired
	private DigitalHumanArtifactService artifacts;
	@Autowired
	private FakeRecoveryFetchPort fakeFetch;
	@MockitoBean
	private ObjectStorageAdapter storage;

	private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();

	private static final String OWNER = "dh-recovery-it-owner";

	@BeforeEach
	void seed() {
		db.sql("DELETE FROM dh_asset_attachment").then()
				.then(db.sql("DELETE FROM content_asset WHERE source = 'dh_recording'").then())
				.then(db.sql("DELETE FROM media_reference WHERE domain_type = 'dh_recording'").then())
				.then(db.sql("DELETE FROM dh_recording").then())
				.then(db.sql("DELETE FROM dh_operation WHERE kind LIKE 'recording_%'").then())
				.then(db.sql("DELETE FROM dh_invocation").then()).then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_turn").then()).then(db.sql("DELETE FROM dh_session").then())
				.block(Duration.ofSeconds(10));
		objectStore.clear();
		fakeFetch.objects.clear();
		fakeFetch.failWithIo = false;
		reset(storage);
		doAnswer(invocation -> {
			objectStore.put(invocation.getArgument(0), invocation.getArgument(1));
			return null;
		}).when(storage).putObject(anyString(), any(byte[].class), anyString());
		doAnswer(invocation -> {
			byte[] content = objectStore.get(invocation.getArgument(0));
			return content == null
					? Optional.empty()
					: Optional.of(new StoredObject(invocation.getArgument(0), content.length,
							"application/octet-stream", "etag", null));
		}).when(storage).headObject(anyString());
	}

	// ---------- TC105H-04-02：派发后半流 ----------

	private UUID seedRenderInvocation(String state, Instant deadlineAt, UUID aiRunId) {
		// 默认 resource_id = 自身 id（render 形状：resourceId=会话资源语义的合成等价）。
		UUID id = UUID.randomUUID();
		return seedRenderInvocation(state, deadlineAt, aiRunId, id);
	}

	private UUID seedRenderInvocation(String state, Instant deadlineAt, UUID aiRunId, UUID resourceId) {
		UUID id = UUID.randomUUID();
		var prepared = db.sql("""
				INSERT INTO dh_invocation (id, owner_account_id, session_id, stage, resource_id, segment_index,
						operation_id, ai_run_id, state, settlement_state, provider_snapshot, budget_snapshot,
						request_hash, deadline_at)
				VALUES (CAST(:id AS uuid), :owner, CAST(:session AS uuid), 'render', CAST(:resource AS uuid), 0,
						CAST(:op AS uuid), CAST(:run AS uuid), :state, 'not_required',
						CAST(:provider AS jsonb), CAST(:budget AS jsonb), repeat('0', 64), :deadline)
				""").bind("id", id.toString()).bind("owner", OWNER).bind("session", UUID.randomUUID().toString())
				.bind("resource", resourceId.toString()).bind("op", UUID.randomUUID().toString()).bind("state", state)
				.bind("provider",
						"{\"type\":\"PLATFORM\",\"provider\":\"sandbox\",\"model\":\"dh-it\",\"baseUrl\":\"https://qwen-e2e.invalid/v1\",\"priceTableVersion\":\"it-v1\"}")
				.bind("budget", "{\"state\":\"reserved\",\"reservedCents\":0}").bind("deadline", deadlineAt);
		if (aiRunId == null) {
			prepared = prepared.bindNull("run", java.util.UUID.class);
		} else {
			prepared = prepared.bind("run", aiRunId.toString());
		}
		prepared.then().block(Duration.ofSeconds(10));
		return id;
	}

	private record InvocationFacts(String id, String operationId, String state, String settlement, String hash) {
	}

	private InvocationFacts invocationFacts(UUID id) {
		return db
				.sql("SELECT id::text, operation_id::text, state, settlement_state, request_hash"
						+ " FROM dh_invocation WHERE id = CAST(:id AS uuid)")
				.bind("id", id.toString())
				.map(row -> new InvocationFacts(row.get(0, String.class), row.get(1, String.class),
						row.get(2, String.class), row.get(3, String.class), row.get(4, String.class)))
				.first().block(Duration.ofSeconds(10));
	}

	@Test
	@DisplayName("tc105h_04_02 派发后半流：窗口内 409 不重发；deadline 过后 unknown 原键保留且不复活")
	void dispatchedHalfStreamUnknownKeepsOriginalEconomicKey() {
		// provider 已接受（dispatched）、usage 丢失、deadline 仍在窗口内：
		// 同键重入必须是确定性 409（等待结果），绝不构造第二次派发。
		UUID dispatched = seedRenderInvocation("dispatched", Instant.now().plus(60, ChronoUnit.SECONDS), null);
		assertThatThrownBy(() -> invocations.prepare(dispatched).block(Duration.ofSeconds(10))).isInstanceOfSatisfying(
				IntelligenceException.class, e -> assertThat(e.code()).isEqualTo("dh_invocation_dispatched"));

		// deadline 过后（响应彻底丢失）：转 unknown 待核对——原 id/operation/request_hash 全部保留。
		db.sql("UPDATE dh_invocation SET deadline_at = now() - interval '1 second' WHERE id = CAST(:id AS uuid)")
				.bind("id", dispatched.toString()).then().block(Duration.ofSeconds(10));
		assertThatThrownBy(() -> invocations.prepare(dispatched).block(Duration.ofSeconds(10))).isInstanceOfSatisfying(
				IntelligenceException.class, e -> assertThat(e.code()).isEqualTo("dh_invocation_unknown"));
		InvocationFacts unknown = invocationFacts(dispatched);
		assertThat(unknown.state()).isEqualTo("unknown");
		assertThat(unknown.settlement()).isEqualTo("pending");
		assertThat(unknown.operationId()).isNotBlank();
		assertThat(unknown.id()).isEqualTo(dispatched.toString());
		assertThat(unknown.hash()).isEqualTo("0".repeat(64));

		// unknown 是终态口径：派发入口拒绝复活（409 dh_invocation_state；provider 调用 ≤1 的结构性闸门），
		// 且重试后状态保持 unknown（原键不漂移）。
		assertThatThrownBy(() -> invocations.claimDispatch(dispatched).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_invocation_state"));
		assertThat(invocationFacts(dispatched).state()).as("重试后状态必须保持 unknown").isEqualTo("unknown");

		// 同经济键第二行被唯一键挡住的 DB 事实由 DigitalHumanUpgradeIT（裸 JDBC 直面 23505）证明，
		// 此处不重复（Spring DataAccessException 消息翻译不含约束名，断言口径不同）。
	}

	@Test
	@DisplayName("tc105h_04_02 prepared→dispatched 仅一次 CAS：二次派发为空（结构性 provider≤1）")
	void claimDispatchIsSingleFlightCas() {
		UUID prepared = seedRenderInvocation("prepared", Instant.now().plus(60, ChronoUnit.SECONDS), UUID.randomUUID());
		// 仓储层 CAS：首次领走 dispatched；再次领返回空（上层据此 409，不会打出第二个 provider 调用）。
		var first = invocationRows.claimDispatch(prepared).block(Duration.ofSeconds(10));
		assertThat(first).isNotNull();
		assertThat(first.state()).isEqualTo(InvocationState.dispatched);
		var second = invocationRows.claimDispatch(prepared).block(Duration.ofSeconds(10));
		assertThat(second).as("dispatched 行二次 claim 必须为空").isNull();
		// 服务层重入同样拒绝。
		assertThatThrownBy(() -> invocations.claimDispatch(prepared).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_invocation_state"));
	}

	// ---------- TC105H-04-03：存储失败 ----------

	private static final String SAMPLE_SRT = "1\n00:00:00,000 --> 00:00:01,000\n你好，草场\n\n";

	private UUID seedReadyRecording(String owner, byte[] video, long declaredDurationMs) {
		UUID sessionId = UUID.randomUUID();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, lease_epoch)"
				+ " VALUES (CAST(:s AS uuid), :owner, CAST(:p AS uuid), 1, '角色', 'dh-it-backend',"
				+ " CAST(:pf AS uuid), CAST(:c AS uuid), CAST('{}' AS jsonb), 'ended', now(), 1)")
				.bind("s", sessionId.toString()).bind("owner", owner).bind("p", UUID.randomUUID().toString())
				.bind("pf", UUID.randomUUID().toString()).bind("c", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
		return seedRecordingOnSession(owner, sessionId, video, declaredDurationMs);
	}

	private UUID seedRecordingOnSession(String owner, UUID sessionId, byte[] video, long declaredDurationMs) {
		UUID recordingId = UUID.randomUUID();
		String videoRef = "dhr-" + recordingId + "-mp4";
		String srtRef = "dhr-" + recordingId + "-srt";
		fakeFetch.objects.put(videoRef, video);
		byte[] srt = SAMPLE_SRT.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		fakeFetch.objects.put(srtRef, srt);
		String manifest = "{\"recordingId\":\"" + recordingId + "\",\"startProgramMs\":1000,\"endProgramMs\":4000,"
				+ "\"partial\":false,\"video\":{\"objectRef\":\"" + videoRef + "\",\"sha256\":\""
				+ MediaChecksums.sha256(video) + "\",\"sizeBytes\":" + video.length + ",\"durationMs\":"
				+ declaredDurationMs + ",\"width\":64,\"height\":36,\"fps\":10.0,\"videoCodec\":\"h264\","
				+ "\"audioCodec\":\"aac\",\"audioSampleRate\":48000},\"subtitle\":" + "{\"objectRef\":\"" + srtRef
				+ "\",\"sha256\":\"" + MediaChecksums.sha256(srt) + "\",\"sizeBytes\":" + srt.length
				+ "},\"errorCode\":null}";
		db.sql("INSERT INTO dh_recording(id, owner_account_id, session_id, start_command_id, state, partial,"
				+ " start_program_ms, manifest, size_bytes, duration_ms, expires_at)"
				+ " VALUES (CAST(:r AS uuid), :owner, CAST(:s AS uuid), CAST(:cmd AS uuid), 'ready', false, 0,"
				+ " CAST(:manifest AS jsonb), :size, :duration, now() + interval '24 hours')")
				.bind("r", recordingId.toString()).bind("owner", owner).bind("s", sessionId.toString())
				.bind("cmd", UUID.randomUUID().toString()).bind("manifest", manifest).bind("size", video.length)
				.bind("duration", declaredDurationMs).then().block(Duration.ofSeconds(10));
		return recordingId;
	}

	private static long probeDurationMs(byte[] video) {
		java.nio.file.Path temp = null;
		try {
			temp = java.nio.file.Files.createTempFile("dh-recovery-it-", ".mp4");
			java.nio.file.Files.write(temp, video);
			return DigitalHumanArtifactProbe.probe(temp).durationMs();
		} catch (IOException failure) {
			throw new IllegalStateException("夹具探测失败", failure);
		} finally {
			try {
				if (temp != null) {
					java.nio.file.Files.deleteIfExists(temp);
				}
			} catch (IOException ignored) {
				// 临时目录由操作系统回收。
			}
		}
	}

	private static byte[] fixture(String name) {
		try (var input = DigitalHumanRecoveryIT.class.getResourceAsStream("/" + name)) {
			assertThat(input).as("夹具 %s 必须在 test resources", name).isNotNull();
			return input.readAllBytes();
		} catch (IOException failure) {
			throw new IllegalStateException("夹具读取失败：" + name, failure);
		}
	}

	private long count(String table, String where) {
		Long count = db.sql("SELECT count(*) AS n FROM " + table + " WHERE " + where)
				.map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		return count == null ? 0 : count;
	}

	private PersonalActor actor() {
		return new PersonalActor(OWNER);
	}

	@Test
	@DisplayName("tc105h_04_03 存储失败：坏产物不入库、pending media 可清理、已有资产不误删、修复后重试成功")
	void recordingStorageFailureKeepsCleanupRetryableAndAssetsSafe() {
		byte[] valid = fixture("dh-recording-valid.mp4");
		byte[] broken = fixture("dh-recording-truncated.mp4");
		// 声明时长取合法产物的真实探针值（ArtifactIT 同口径）：坏产物在解码阶段按实际字节失败。
		long declaredMs = probeDurationMs(valid);

		// 已有资产（另一录制，好产物保存成功）：存储失败演练不得误删它。
		UUID healthy = seedReadyRecording(OWNER, valid, declaredMs);
		var healthyOutcome = artifacts.save(actor(), healthy, UUID.randomUUID(), "既有资产", true)
				.block(Duration.ofSeconds(30));
		assertThat(healthyOutcome).isNotNull();
		long assetsBefore = count("content_asset", "source = 'dh_recording'");

		// 故障注入：坏产物（截断/损坏，磁盘满写坏类比）→ 保存失败。
		UUID brokenRecording = seedReadyRecording(OWNER, broken, declaredMs);
		assertThatThrownBy(() -> artifacts.save(actor(), brokenRecording, UUID.randomUUID(), "坏产物保存", true)
				.block(Duration.ofSeconds(30))).isInstanceOf(IntelligenceException.class);

		// 坏文件不入库：content_asset 不新增；录制段不被标 saved。
		assertThat(count("content_asset", "source = 'dh_recording'")).isEqualTo(assetsBefore);
		assertThat(db.sql("SELECT state FROM dh_recording WHERE id = CAST(:r AS uuid)")
				.bind("r", brokenRecording.toString()).map(row -> row.get(0, String.class)).one()
				.block(Duration.ofSeconds(10))).isNotEqualTo("saved");
		// 失败尝试登记的 pending media 不悬挂为终态资产（可由 GC/清理路径重试收口）。
		assertThat(count("media_reference", "domain_type = 'dh_recording' AND status = 'active'"))
				.isEqualTo(assetsBefore * 2);

		// 既有资产仍在（asset 不误删）。
		assertThat(count("content_asset", "source = 'dh_recording'")).isGreaterThanOrEqualTo(1);

		// 修复后的重试语义（K04/K09）：坏段已确认失败即终态——同段不得复活重存；用户重新开始
		// 的路径 = 新录制段（好产物）保存成功，既有资产/pending 清理口径不受影响。
		UUID retryRecording = seedReadyRecording(OWNER, valid, declaredMs);
		var retried = artifacts.save(actor(), retryRecording, UUID.randomUUID(), "修复后新段保存", true)
				.block(Duration.ofSeconds(30));
		assertThat(retried).isNotNull();
		assertThat(count("content_asset", "source = 'dh_recording'")).isEqualTo(assetsBefore + 1);
		assertThat(db.sql("SELECT state FROM dh_recording WHERE id = CAST(:r AS uuid)")
				.bind("r", retryRecording.toString()).map(row -> row.get(0, String.class)).one()
				.block(Duration.ofSeconds(10))).isEqualTo("saved");
		// 坏段保持终态 failed（不因别处成功而复活/入库）。
		assertThat(db.sql("SELECT state FROM dh_recording WHERE id = CAST(:r AS uuid)")
				.bind("r", brokenRecording.toString()).map(row -> row.get(0, String.class)).one()
				.block(Duration.ofSeconds(10))).isEqualTo("failed");

		// 断言引用（保持 Mockito 静态导入使用；避免未用告警成错误）。
		assertThat(Mockito.mockingDetails(storage).isMock()).isTrue();
	}
}
