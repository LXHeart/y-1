package com.grassland.intelligence.digitalhuman;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanInvocationService.PreparedInvocation;
import com.grassland.intelligence.digitalhuman.DigitalHumanMetrics.Phase;
import com.grassland.intelligence.digitalhuman.DigitalHumanMetrics.RecordingResult;
import com.grassland.intelligence.digitalhuman.DigitalHumanMetrics.StableCode;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationStage;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.UsageUnits;
import com.grassland.intelligence.security.IntelligenceException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

/**
 * 隐私与撤销矩阵 IT（任务书 #105G C105G-05 / TC105G-05-03、TC105G-05-04）。
 *
 * <p>
 * 六个派发/写回窗口（WS/STT/LLM/TTS/录制/save）逐一「撤权 → 迟到回复」：真实 Postgres/Redis/经济键 + 最外层网络
 * fake（credits 桩与 runtime 控制端口），断言迟到事件
 * 不再写/派发、不重复结算、错误受控（域错误码）。指标基数（tc105g_05_04）：100 合成 sessionId
 * 不增序列；自由字符串（正文/密钥/grant 形态）绝不成为标签值。
 */
class DigitalHumanPrivacyIT extends IntelligenceItSupport {

	static final WireMockServer CREDITS = new WireMockServer(0);
	static final org.testcontainers.containers.GenericContainer<?> REDIS = new org.testcontainers.containers.GenericContainer<>(
			org.testcontainers.utility.DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

	static {
		CREDITS.start();
		REDIS.start();
	}

	@DynamicPropertySource
	static void extraProps(DynamicPropertyRegistry r) {
		r.add("credits.finance.base-url", CREDITS::baseUrl);
		r.add("marketplace.service.base-url", CREDITS::baseUrl);
		r.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
		r.add("digital-human.internal.allowed-ai-origins", () -> "https://ai.grassland.test");
	}

	/** 最外层网络替身：runtime 控制端口（DB/owner/幂等键/经济键全部真实）。 */
	@TestConfiguration
	static class PrivacyRuntimeConfig {

		@Bean
		@Primary
		DigitalHumanRuntimeClient privacyRuntime() {
			DigitalHumanRuntimeClient.Transport transport = new DigitalHumanRuntimeClient.Transport() {
				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> createSession(String sessionId, String backendId,
						UUID commandId) {
					return Mono.just(state(sessionId, "connecting"));
				}

				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> state(String sessionId) {
					return Mono.just(state(sessionId, "listening"));
				}

				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> end(String sessionId, UUID commandId,
						String reasonCode) {
					return Mono.just(state(sessionId, "ended"));
				}

				private DigitalHumanRuntimeClient.RuntimeState state(String sessionId, String state) {
					return new DigitalHumanRuntimeClient.RuntimeState(sessionId, "worker-fake", 1, 1, state, null,
							Instant.now().plusSeconds(30).toString(), false, false);
				}
			};
			return new DigitalHumanRuntimeClient(transport, new DigitalHumanRuntimeClient.Recorder() {
				@Override
				public void onCreate(String sessionId) {
				}

				@Override
				public void onEnd(String sessionId) {
				}
			});
		}

		@Bean
		FakeRecordingRuntimePort privacyRecordingRuntime() {
			return new FakeRecordingRuntimePort();
		}
	}

	static class FakeRecordingRuntimePort implements DigitalHumanRecordingService.RecordingRuntimePort {

		final List<String> startCalls = new CopyOnWriteArrayList<>();

		@Override
		public Mono<Void> startRecording(String sessionId, UUID recordingId, UUID commandId, long leaseEpoch,
				long maxDurationMs, long maxBytes) {
			return Mono.fromRunnable(() -> startCalls.add(recordingId.toString()));
		}

		@Override
		public Mono<Void> stopRecording(String sessionId, UUID recordingId, UUID commandId, long leaseEpoch,
				String reasonCode) {
			return Mono.empty();
		}
	}

	@Autowired
	private DatabaseClient db;
	@Autowired
	private DigitalHumanSessionService sessions;
	@Autowired
	private DigitalHumanTurnService turns;
	@Autowired
	private DigitalHumanInvocationService invocations;
	@Autowired
	private DigitalHumanInvocationRepository invocationRepository;
	@Autowired
	private DigitalHumanGrantService grants;
	@Autowired
	private DigitalHumanRecordingService recordings;
	@Autowired
	private DigitalHumanTranscriptService transcripts;
	@Autowired
	private FakeRecordingRuntimePort recordingRuntime;
	@Autowired
	private ByokRoutingService routing;
	@Autowired
	private EnvelopeEncryption encryption;
	@Autowired
	private ReactiveStringRedisTemplate redis;

	// 规范 UUID（权益校验 isCanonicalUuid 拒绝非 UUID；AiRunControllerIT 同款）
	private final String account = UUID.randomUUID().toString();
	private final PersonalActor actor = new PersonalActor(account);

	@BeforeEach
	void seed() {
		cleanSharedDhTables();
		String encrypted = encryption.encrypt("sk-priv-it-key");
		UUID credential = UUID.fromString(db
				.sql("INSERT INTO platform_provider_credential(name, provider, base_url, encrypted_key, key_version,"
						+ " masked_hint, enabled) VALUES ('it-priv-cred', 'openai-compatible', :baseUrl, :encrypted,"
						+ " 'v1', 'sk-***priv', true) RETURNING id::text")
				.bind("baseUrl", QWEN.baseUrl()).bind("encrypted", encrypted).map(row -> row.get("id", String.class))
				.one().block(Duration.ofSeconds(5)));
		for (String[] row : List.of(new String[]{"text", "qwen-plus"}, new String[]{"voice", "sandbox-speech-v1"},
				new String[]{"video_tts", "sandbox-tts-v1"},
				new String[]{"digital_human_render", "sandbox-video-v1"})) {
			db.sql("INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,"
					+ " health_status, enabled, version, credential_id) VALUES (:cap, 'primary', 'openai-compatible',"
					+ " :model, :baseUrl, 'healthy', true, 1, CAST(:cred AS uuid)) ON CONFLICT DO NOTHING")
					.bind("cap", row[0]).bind("model", row[1]).bind("baseUrl", QWEN.baseUrl())
					.bind("cred", credential.toString()).then().block(Duration.ofSeconds(5));
		}
		db.sql("INSERT INTO dh_catalog(singleton_id, version, config_json, updated_by) VALUES (1, 1,"
				+ " CAST(:config AS jsonb), 'it')")
				.bind("config",
						"{\"enabled\":true,\"newSessionsAllowed\":true,\"recordingEnabled\":true,"
								+ "\"customAvatarEnabled\":false,\"allowedBackendIds\":[\"dh-it-backend\"],"
								+ "\"avatars\":[],\"voices\":[]}")
				.then().block(Duration.ofSeconds(5));
		CREDITS.resetAll();
		CREDITS.stubFor(get(urlEqualTo("/internal/marketplace/reputation/" + account + "/ai-entitlement"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"success\":true,\"data\":{\"accountId\":\"" + account
								+ "\",\"aiQuotaMultiplierBps\":10000,\"policyVersion\":1}}")));
		CREDITS.stubFor(post(urlEqualTo("/internal/credits/consume"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"success\":true,\"data\":{\"source\":\"quota\",\"policyVersion\":1,"
								+ "\"transactionId\":\"11111111-1111-1111-1111-111111111111\"}}")));
		CREDITS.stubFor(post(urlEqualTo("/internal/credits/refund")).willReturn(aResponse().withStatus(200)));
		CREDITS.stubFor(
				post(urlEqualTo("/internal/credits/consume-compensations")).willReturn(aResponse().withStatus(200)));
		recordingRuntime.startCalls.clear();
	}

	/**
	 * tc105x-02-03（任务书 #105fix-1 C105X-02）：清理体抽方法双端复调——此前仅 @BeforeEach 单端，类结束
	 * 后残留要等下一类来清（共享容器跨类污染，审计 F-05）。FK 顺序照既有列表不改。凭据清理按
	 * <b>目的地</b>（provider+base_url）而非仅 name——全量套件里其他 DH 类种的 openai-compatible@QWEN
	 * 凭据残留会撞
	 * idx_platform_provider_credential_destination（attachPlatformTextCredential
	 * 同款先例）。
	 */
	private void cleanSharedDhTables() {
		redis.execute(connection -> connection.serverCommands().flushDb().flux()).then().block(Duration.ofSeconds(5));
		db.sql("DELETE FROM dh_cleanup WHERE resource_kind = 'recording_object'").then()
				.then(db.sql("DELETE FROM dh_recording").then()).then(db.sql("DELETE FROM dh_invocation").then())
				.then(db.sql("DELETE FROM dh_event").then()).then(db.sql("DELETE FROM dh_transcript").then())
				.then(db.sql("DELETE FROM dh_turn").then()).then(db.sql("DELETE FROM dh_session").then())
				.then(db.sql("DELETE FROM dh_operation").then()).then(db.sql("DELETE FROM ai_model_budget").then())
				.block(Duration.ofSeconds(10));
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
				+ " (SELECT id FROM platform_model_config WHERE credential_id IN"
				+ " (SELECT id FROM platform_provider_credential WHERE name LIKE 'it-priv-%'"
				+ " OR (provider = 'openai-compatible' AND base_url = :baseUrl))"
				+ " OR capability IN ('digital_human_render','voice','video_tts'))").bind("baseUrl", QWEN.baseUrl())
				.then()
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN"
						+ " (SELECT id FROM platform_provider_credential WHERE name LIKE 'it-priv-%'"
						+ " OR (provider = 'openai-compatible' AND base_url = :baseUrl))"
						+ " OR capability IN ('digital_human_render','voice','video_tts')")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name LIKE 'it-priv-%'"
						+ " OR (provider = 'openai-compatible' AND base_url = :baseUrl)")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.then(db.sql("DELETE FROM dh_catalog").then()).block(Duration.ofSeconds(10));
	}

	@org.junit.jupiter.api.AfterEach
	void cleanSharedDhTablesAfter() {
		cleanSharedDhTables();
	}

	// ---------- 种子 ----------

	private UUID seedSession(String state, long leaseEpoch) {
		UUID sessionId = UUID.randomUUID();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, lease_epoch)"
				+ " VALUES (CAST(:s AS uuid), :owner, CAST(:p AS uuid), 1, '角色', 'backend-1', CAST(:pf AS uuid),"
				+ " CAST(:c AS uuid), CAST('{}' AS jsonb), :state, now(), :lease)").bind("s", sessionId.toString())
				.bind("owner", account).bind("p", UUID.randomUUID().toString()).bind("pf", UUID.randomUUID().toString())
				.bind("c", UUID.randomUUID().toString()).bind("state", state).bind("lease", leaseEpoch).then()
				.block(Duration.ofSeconds(10));
		return sessionId;
	}

	private UUID startTextTurn(UUID sessionId) {
		var receipt = turns.startTurn(actor, sessionId, UUID.randomUUID(), 1, "整理三句口播要点。")
				.block(Duration.ofSeconds(10));
		return UUID.fromString(receipt.id());
	}

	private InvocationRow dispatchInvocation(UUID sessionId, UUID turnId, InvocationStage stage) {
		ProviderResolution provider = routing.resolvePlatform(DigitalHumanInvocationService.capabilityFor(stage))
				.block(Duration.ofSeconds(10));
		InvocationRow row = invocations.reserve(actor, sessionId, turnId, stage, turnId, 0, provider,
				"hash-" + UUID.randomUUID(), Instant.now().plus(Duration.ofSeconds(90)), 1000, 128, 0)
				.block(Duration.ofSeconds(10));
		invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(20));
		invocations.claimDispatch(UUID.fromString(row.id())).block(Duration.ofSeconds(20));
		return row;
	}

	private InvocationRow invocation(String id) {
		return invocationRepository.findById(UUID.fromString(id)).block(Duration.ofSeconds(10));
	}

	private UsageUnits usage() {
		return new UsageUnits(120L, 80L, null, null, null, null, "prov-req-late", "confirmed");
	}

	// ---------- TC105G-05-03 撤销矩阵 ----------

	@Test
	void tc105g_05_03_wsWindowLateConsumeRejectedAfterEnd() {
		UUID sessionId = seedSession("ready", 1);
		var ticket = grants.issue(actor, sessionId, 1, "audio", "https://ai.grassland.test")
				.block(Duration.ofSeconds(10));
		sessions.end(actor, sessionId, UUID.randomUUID(), "撤销测试").block(Duration.ofSeconds(10));

		// 迟到 WS 核销：会话已结束 → 受控 409，不建 turn。
		assertThatThrownBy(() -> grants.consumeConnection(ticket.grant(), sessionId, 1, "https://ai.grassland.test",
				UUID.randomUUID(), "pcm_s16le", 16000, 1).block(Duration.ofSeconds(10))).isInstanceOfSatisfying(
						IntelligenceException.class, e -> assertThat(e.code()).isEqualTo("dh_state_conflict"));
		Long turnCount = count("SELECT count(*) AS n FROM dh_turn");
		assertThat(turnCount).isZero();
	}

	@Test
	void tc105g_05_03_sttWindowLateTurnCreationRejectedAfterEnd() {
		UUID sessionId = seedSession("ready", 1);
		sessions.end(actor, sessionId, UUID.randomUUID(), "撤销测试").block(Duration.ofSeconds(10));

		assertThatThrownBy(() -> turns.startTurn(actor, sessionId, UUID.randomUUID(), 1, "迟到的语音转写派发。")
				.block(Duration.ofSeconds(10))).isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_state_conflict"));
		assertThat(count("SELECT count(*) AS n FROM dh_turn")).isZero();
		assertThat(count("SELECT count(*) AS n FROM dh_invocation")).isZero();
	}

	@Test
	void tc105g_05_03_llmWindowInterruptedLateReplyBlockedNoDoubleCharge() {
		UUID sessionId = seedSession("ready", 1);
		UUID turnId = startTextTurn(sessionId);
		InvocationRow row = dispatchInvocation(sessionId, turnId, InvocationStage.llm);
		PreparedInvocation prepared = invocations.rehydrate(UUID.fromString(row.id())).block(Duration.ofSeconds(10));

		// 撤权：用户打断（turn 终态）+ 已派发 abort（按预留结算、不退款）。
		turns.interrupt(actor, sessionId, UUID.randomUUID(), 1, turnId, 1).block(Duration.ofSeconds(10));
		invocations.abortAfterDispatch(UUID.fromString(row.id()), prepared.context()).block(Duration.ofSeconds(10));
		InvocationRow afterAbort = invocation(row.id());
		assertThat(afterAbort.state().name()).isEqualTo("cancelled");
		assertThat(afterAbort.settlementState().name()).isEqualTo("settled");
		assertThat(afterAbort.usageJson()).isNull();
		int versionAfterAbort = afterAbort.version();

		// 迟到回复①：状态机重入全部受控拒绝（不可能再派发/再准备）。
		assertThatThrownBy(() -> invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_invocation_terminal"));
		assertThatThrownBy(() -> invocations.claimDispatch(UUID.fromString(row.id())).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_invocation_state"));

		// 迟到回复②：直调写回也被双层挡住（ai 侧 completeRun CAS 拒二次结算；dh 侧终态零匹配）。
		assertThatThrownBy(() -> invocations.settleSuccess(UUID.fromString(row.id()), prepared.context(), usage())
				.block(Duration.ofSeconds(10))).hasMessageContaining("completion state update failed");
		InvocationRow afterLate = invocation(row.id());
		assertThat(afterLate.state().name()).isEqualTo("cancelled");
		assertThat(afterLate.usageJson()).isNull();
		assertThat(afterLate.version()).isEqualTo(versionAfterAbort);
		// 不重复费用：迟到 settle 在 ai 侧 completeRun CAS 即失败（异常先于任何预留结算/usage
		// 事件追加），dh 行版本不变——二次结算在经济上不可能发生。
	}

	@Test
	void tc105g_05_03_ttsWindowSessionEndedLateUsageSettlesExactlyOnce() {
		UUID sessionId = seedSession("ready", 1);
		UUID turnId = startTextTurn(sessionId);
		InvocationRow row = dispatchInvocation(sessionId, turnId, InvocationStage.tts);
		PreparedInvocation prepared = invocations.rehydrate(UUID.fromString(row.id())).block(Duration.ofSeconds(10));

		// 撤权：会话终止（不再有新派发；迟到 TTS 结果只记录一次经济事实）。
		sessions.end(actor, sessionId, UUID.randomUUID(), "撤销测试").block(Duration.ofSeconds(10));
		assertThatThrownBy(() -> turns.startTurn(actor, sessionId, UUID.randomUUID(), 1, "已终止会话的新派发。")
				.block(Duration.ofSeconds(10))).isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_state_conflict"));
		assertThatThrownBy(() -> invocations.claimDispatch(UUID.fromString(row.id())).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_invocation_state"));

		// 迟到 usage 第一次：按冻结价结算一次（K08：到达 usage 记录但不重结第二笔）。
		Boolean first = invocations.settleSuccess(UUID.fromString(row.id()), prepared.context(), usage())
				.block(Duration.ofSeconds(20));
		assertThat(first).isTrue();
		InvocationRow settled = invocation(row.id());
		assertThat(settled.state().name()).isEqualTo("succeeded");
		assertThat(settled.settlementState().name()).isEqualTo("settled");
		int versionAfterFirst = settled.version();

		// 迟到 usage 第二次（重复投递）：ai 侧 CAS 拒绝，绝不二次结算。
		assertThatThrownBy(() -> invocations.settleSuccess(UUID.fromString(row.id()), prepared.context(), usage())
				.block(Duration.ofSeconds(10))).hasMessageContaining("completion state update failed");
		assertThat(invocation(row.id()).version()).isEqualTo(versionAfterFirst);
	}

	@Test
	void tc105g_05_03_recordingWindowEndedSessionLateStartRejected() {
		UUID sessionId = seedSession("ready", 1);
		sessions.end(actor, sessionId, UUID.randomUUID(), "撤销测试").block(Duration.ofSeconds(10));

		assertThatThrownBy(
				() -> recordings.start(actor, sessionId, UUID.randomUUID(), 1, true).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_state_conflict"));
		assertThat(count("SELECT count(*) AS n FROM dh_recording")).isZero();
		assertThat(recordingRuntime.startCalls).isEmpty();
	}

	@Test
	void tc105g_05_03_saveWindowTombstoneBlocksLateSave() {
		UUID sessionId = seedSession("ready", 1);
		transcripts.delete(actor, sessionId, UUID.randomUUID()).block(Duration.ofSeconds(10));

		assertThatThrownBy(
				() -> transcripts.saveBuffered(actor, sessionId, 0, UUID.randomUUID()).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_content_deleted"));
		assertThat(count("SELECT count(*) AS n FROM dh_transcript")).isZero();
	}

	// ---------- TC105G-05-04 指标基数 ----------

	@Test
	void tc105g_05_04_metricsSeriesDoNotGrowWithSessionsAndCarryNoContentLabels() {
		MeterRegistry registry = new SimpleMeterRegistry();
		DigitalHumanMetrics metrics = new DigitalHumanMetrics(registry);

		// 100 个合成 sessionId 走满阶段观测：API 无会话参数——序列数在第一轮后即固定。
		for (int i = 0; i < 100; i++) {
			String syntheticSession = "synthetic-session-" + i;
			metrics.recordStageOutcome(Phase.llm, i % 2 == 0 ? StableCode.provider_failure : StableCode.other,
					Duration.ofMillis(100 + i));
			metrics.recordStage(Phase.tts, "minimax", Duration.ofMillis(200 + i));
			metrics.recordStage(Phase.render, "GRASSLAND-DH-PROMPT-MARKER-105G01", Duration.ofMillis(10));
			metrics.recordStage(Phase.first_audio, "sk-grassland-dh-FAKE-KEY-MARKER-105g03", Duration.ofMillis(5));
			metrics.recordError(Phase.connect, StableCode.dh_lease_stale);
			metrics.recordUnknownUsage();
			metrics.recordWsRejected();
			metrics.recordStaleEpochDropped();
			metrics.recordRuntimeLeaseExpired();
			metrics.recordRecording(i % 3 == 0
					? RecordingResult.partial
					: i % 3 == 1 ? RecordingResult.overflow : RecordingResult.completed);
			assertThat(syntheticSession).isNotEmpty(); // 合成会话标识仅存在于本测试局部变量
		}

		// 基数：会话轮次不产生任何新序列（micrometer 按标签组合去重）。
		int metersAfterWarmup = -1;
		for (int round = 0; round < 3; round++) {
			metrics.recordStageOutcome(Phase.llm, StableCode.provider_failure, Duration.ofMillis(1));
			if (metersAfterWarmup < 0) {
				metersAfterWarmup = registry.getMeters().size();
			} else {
				assertThat(registry.getMeters().size()).isEqualTo(metersAfterWarmup);
			}
		}

		// 标签域：dh_* 指标只允许 phase/provider/code/result（+timer 内建 percentile），
		// 无 account/session/token/text 类键；正文/密钥形态值绝不作为标签值出现。
		for (var meter : registry.getMeters()) {
			String name = meter.getId().getName();
			if (!name.startsWith("dh_")) {
				continue;
			}
			String joined = name + "|" + meter.getId().getTags();
			assertThat(joined).doesNotContain("GRASSLAND-DH").doesNotContain("sk-grassland")
					.doesNotContain("synthetic-session");
			for (var tag : meter.getId().getTags()) {
				assertThat(tag.getKey()).isIn("phase", "provider", "code", "result", "percentile", "phi");
			}
		}
		// provider 白名单：自由字符串一律 other。
		assertThat(DigitalHumanMetrics.providerTag("GRASSLAND-DH-PROMPT-MARKER-105G01"))
				.isEqualTo(DigitalHumanMetrics.PROVIDER_OTHER);
		assertThat(DigitalHumanMetrics.providerTag("minimax")).isEqualTo("minimax");
		assertThat(DigitalHumanMetrics.providerTag(null)).isEqualTo(DigitalHumanMetrics.PROVIDER_OTHER);
	}

	// ---------- 工具 ----------

	private Long count(String sql) {
		return db.sql(sql).map(row -> row.get("n", Long.class)).one().defaultIfEmpty(0L).block(Duration.ofSeconds(10));
	}
}
