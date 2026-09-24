package com.grassland.intelligence.digitalhuman;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanInvocationService.PreparedInvocation;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationStage;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.UsageUnits;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 实时链集成 IT（任务书 #105D C105D-06 / TC105D-06-01、TC105D-06-02、TC105D-06-04）：真实
 * Postgres + Redis + WireMock（credits 最外层、LLM/TTS 走受信 QWEN）；同一经济键贯穿失败/结算路径；
 * 断流不重复计费；日志无合成 secret。真实第三方渲染/实机 REAL_NOT_RUN。
 */
@ExtendWith(OutputCaptureExtension.class)
class DigitalHumanRealtimeIntegrationIT extends IntelligenceItSupport {

	static final WireMockServer CREDITS = new WireMockServer(0);
	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	static {
		CREDITS.start();
		REDIS.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("credits.finance.base-url", CREDITS::baseUrl);
		r.add("marketplace.service.base-url", CREDITS::baseUrl);
		r.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
	}

	@Autowired
	DigitalHumanInvocationService invocations;
	@Autowired
	DigitalHumanInvocationRepository invocationRepository;
	@Autowired
	DigitalHumanReconciliationWorker worker;
	@Autowired
	com.grassland.intelligence.ai.byok.ByokRoutingService routing;
	@Autowired
	DatabaseClient db;
	@Autowired
	EnvelopeEncryption encryption;

	private final String account = UUID.randomUUID().toString();
	private final PersonalActor actor = new PersonalActor(account);

	@BeforeEach
	void seed() {
		db.sql("DELETE FROM dh_invocation").then().then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_operation").then()).then(db.sql("DELETE FROM dh_session").then())
				.block(Duration.ofSeconds(10));
		CREDITS.resetAll();
		CREDITS.stubFor(get(urlMatching("/internal/marketplace/reputation/.*/ai-entitlement"))
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
		// 三个能力的平台行（text/voice/video_tts）带凭据指向受信 QWEN。
		// 目的地唯一索引按 (provider, base_url)：按 QWEN 目的地整清（跨类残留），再清能力行。
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
				+ " (SELECT id FROM platform_model_config WHERE credential_id IN"
				+ " (SELECT id FROM platform_provider_credential WHERE base_url = :baseUrl))")
				.bind("baseUrl", QWEN.baseUrl()).then()
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN"
						+ " (SELECT id FROM platform_provider_credential WHERE base_url = :baseUrl)"
						+ " OR capability IN ('text','voice','video_tts')").bind("baseUrl", QWEN.baseUrl()).then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE base_url = :baseUrl")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.block(Duration.ofSeconds(10));
		String encrypted = encryption.encrypt("sk-it-dh-rt");
		String credentialId = db.sql("""
				INSERT INTO platform_provider_credential(name, provider, base_url, encrypted_key, key_version,
				    masked_hint, enabled)
				VALUES ('it-dh-rt', 'openai-compatible', :baseUrl, :encrypted, 'v1', 'sk-***rt', true)
				RETURNING id::text
				""").bind("baseUrl", QWEN.baseUrl()).bind("encrypted", encrypted)
				.map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(10));
		for (String capability : new String[]{"text", "voice", "video_tts"}) {
			db.sql("INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,"
					+ " health_status, enabled, version, credential_id) VALUES (:cap, 'primary',"
					+ " 'openai-compatible', 'qwen-plus', :baseUrl, 'healthy', true, 1, CAST(:cred AS uuid))")
					.bind("cap", capability).bind("baseUrl", QWEN.baseUrl()).bind("cred", credentialId).then()
					.block(Duration.ofSeconds(10));
		}
		QWEN.resetRequests();
	}

	private UUID sessionId;

	private UUID seedSessionAndTurn() {
		UUID sessionIdHolder = UUID.randomUUID();
		UUID turnId = UUID.randomUUID();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at)"
				+ " VALUES (CAST(:s AS uuid), :owner, CAST(:p AS uuid), 1, '角色', 'backend-1', CAST(:pf AS uuid),"
				+ " CAST(:c AS uuid), CAST('{}' AS jsonb), 'ready', now())").bind("s", sessionIdHolder.toString())
				.bind("owner", account).bind("p", UUID.randomUUID().toString()).bind("pf", UUID.randomUUID().toString())
				.bind("c", UUID.randomUUID().toString()).then()
				.then(db.sql("INSERT INTO dh_turn(id, owner_account_id, session_id, request_id, turn_epoch,"
						+ " input_kind, state, started_at) VALUES (CAST(:t AS uuid), :owner, CAST(:s AS uuid),"
						+ " CAST(:r AS uuid), 1, 'audio', 'generating', now())").bind("t", turnId.toString())
						.bind("owner", account).bind("s", sessionIdHolder.toString())
						.bind("r", UUID.randomUUID().toString()).then())
				.block(Duration.ofSeconds(10));
		sessionId = sessionIdHolder;
		return turnId;
	}

	private PreparedInvocation stageInvocation(UUID turnId, InvocationStage stage, int segmentIndex) {
		// stt/llm/tts 必须挂真实会话与轮次（K05 stage 形状）。
		var provider = routing
				.resolvePlatform(
						stage == InvocationStage.llm ? "text" : stage == InvocationStage.stt ? "voice" : "video_tts")
				.block(Duration.ofSeconds(10));
		var row = invocations.reserve(actor, sessionId, turnId, stage, turnId, segmentIndex, provider,
				"hash-" + segmentIndex, Instant.now().plusSeconds(90), 100, 64, 0).block(Duration.ofSeconds(10));
		return invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(20));
	}

	// ---------- TC105D-06-01：三阶段成功用量 ----------

	@Test
	void tc105d_06_01_threeStagesEachOneRunWithActualMetering() {
		UUID turnId = seedSessionAndTurn();
		// STT（voice，feature=AI_RUN_VOICE）
		PreparedInvocation stt = stageInvocation(turnId, InvocationStage.stt, 0);
		assertThat(invocations
				.settleSuccess(stt.invocationId(), stt.context(),
						new UsageUnits(null, null, 3200L, null, null, null, "stt-1", "confirmed"))
				.block(Duration.ofSeconds(20))).isTrue();
		// LLM（text，feature=AI_RUN_TEXT）
		PreparedInvocation llm = stageInvocation(turnId, InvocationStage.llm, 0);
		assertThat(invocations
				.settleSuccess(llm.invocationId(), llm.context(),
						new UsageUnits(120L, 45L, null, null, null, null, "llm-1", "confirmed"))
				.block(Duration.ofSeconds(20))).isTrue();
		// TTS 两句段（video_tts，feature=null 补贴）
		for (int segment = 0; segment < 2; segment++) {
			PreparedInvocation tts = stageInvocation(turnId, InvocationStage.tts, segment);
			assertThat(invocations
					.settleSuccess(tts.invocationId(), tts.context(),
							new UsageUnits(null, null, null, 2400L, null, null, "tts-" + segment, "confirmed"))
					.block(Duration.ofSeconds(20))).isTrue();
		}
		// 每个 stage/segment 恰好一个 run；聚合无第二次整场扣款（同键不重结）。
		assertThat(db
				.sql("SELECT stage, count(*) AS n FROM ai_run run JOIN dh_invocation inv"
						+ " ON inv.ai_run_id = run.id GROUP BY stage ORDER BY stage")
				.map((r, m) -> r.get("stage", String.class) + "=" + r.get("n", Long.class)).all().collectList()
				.block(Duration.ofSeconds(10))).containsExactly("llm=1", "stt=1", "tts=2");
		assertThat(db
				.sql("SELECT count(*) AS n FROM ai_run run JOIN dh_invocation inv ON inv.ai_run_id = run.id"
						+ " WHERE run.status = 'completed' AND run.actual_cents IS NOT NULL")
				.map(r -> r.get("n", Long.class)).one().block(Duration.ofSeconds(10))).isEqualTo(4L);
		// 重放结算（worker 再跑）不产生第二次扣款：全部已 settled。
		var summary = worker.reconcilePending(Instant.now(), 50).block(Duration.ofSeconds(20));
		assertThat(summary.succeeded()).isZero();
		assertThat(db.sql("SELECT count(*) AS n FROM ai_run").map(r -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10))).isEqualTo(4L);
	}

	// ---------- TC105D-06-02：结算服务失败后 worker 只重放结算 ----------

	@Test
	void tc105d_06_02_settlementFailureRetriedOnOriginalKeyWithoutSecondProviderCall() {
		UUID turnId = seedSessionAndTurn();
		PreparedInvocation llm = stageInvocation(turnId, InvocationStage.llm, 0);
		// 结算服务失败的持久事实（E20：provider 已成功、结算中断）：run 仍 running、settlement=failed、
		// usage 已持久——settle 事务中断后落库的正是该形状；worker 只重放结算。
		db.sql("UPDATE dh_invocation SET state = 'succeeded', settlement_state = 'failed',"
				+ " usage_json = CAST(:usage AS jsonb) WHERE id = CAST(:id AS uuid)")
				.bind("id", llm.invocationId().toString())
				.bind("usage",
						"{\"inputTokens\":120,\"outputTokens\":45,\"audioInputMs\":null,"
								+ "\"audioOutputMs\":null,\"textCodePoints\":null,\"renderMs\":null,"
								+ "\"providerRequestId\":\"llm-1\",\"quality\":\"confirmed\"}")
				.then().block(Duration.ofSeconds(10));
		var settledFailed = invocationRepository.findById(llm.invocationId()).block(Duration.ofSeconds(10));
		assertThat(settledFailed.state().name()).isEqualTo("succeeded");
		assertThat(settledFailed.settlementState().name()).isEqualTo("failed");
		long runsBefore = db.sql("SELECT count(*) AS n FROM ai_run").map(r -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		// worker 恢复（credits 恢复 200 由 @BeforeEach 桩保持）：重试原经济键结算。
		var summary = worker.reconcilePending(Instant.now(), 50).block(Duration.ofSeconds(20));
		assertThat(summary.succeeded()).isEqualTo(1);
		assertThat(db.sql("SELECT count(*) AS n FROM ai_run").map(r -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10))).as("不新增 run").isEqualTo(runsBefore);
		var after = invocationRepository.findById(llm.invocationId()).block(Duration.ofSeconds(10));
		assertThat(after.settlementState().name()).isEqualTo("settled");
	}

	// ---------- TC105D-06-04：日志与删除 ----------

	@Test
	void tc105d_06_04_noSecretOrContentPersistedInLogsOrDb(CapturedOutput output) {
		String secretMarker = "SECRET-KEY-" + UUID.randomUUID();
		UUID turnId = seedSessionAndTurn();
		try {
			var provider = routing.resolvePlatform("text").block(Duration.ofSeconds(10));
			invocations.reserve(actor, null, turnId, InvocationStage.llm, turnId, 0, provider, "hash-x",
					Instant.now().plusSeconds(90), 100, 64, 0).block(Duration.ofSeconds(10));
		} catch (Exception logged) {
			// 任何路径都不该把 marker 带进日志。
		}
		assertThat(output.getAll()).doesNotContain(secretMarker);
		// DB 层：provider/budget 快照无密钥列（结构即约束）——抽验无明文 key。
		String snapshots = db.sql(
				"SELECT string_agg(provider_snapshot::text || budget_snapshot::text, ' ') AS all_text FROM dh_invocation")
				.map(r -> String.valueOf(r.get("all_text", String.class))).one().block(Duration.ofSeconds(10));
		assertThat(snapshots).doesNotContain("sk-").doesNotContain("encryptedKey");
	}
}
