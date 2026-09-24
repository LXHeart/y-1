package com.grassland.intelligence.digitalhuman;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanInvocationService.PreparedInvocation;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SettlementState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.UsageUnits;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 数字人 invocation 状态机 IT（任务书 #105D C105D-01 / TC105D-01-02、TC105D-01-03）。
 *
 * <p>
 * 派发后响应丢失（deadline 已过的时间推进用数据注入，不 sleep）→ 重启重试原键 → unknown 待核对且不第二次
 * 派发；未派发取消走原补偿、已派发 abort 按预留结算不退款，前序成功调用保留。真实 DB/事务/经济键； credits 走 WireMock
 * 最外层桩；本卡 provider 派发是持久标记（无真实推理网络），二次派发以第二次 run/charge/claim 均不发生为断言面。
 */
class DigitalHumanInvocationIT extends IntelligenceItSupport {

	static final WireMockServer CREDITS = new WireMockServer(0);
	static {
		CREDITS.start();
	}

	@Autowired
	DigitalHumanInvocationService invocations;
	@Autowired
	DigitalHumanInvocationRepository invocationRepository;
	@Autowired
	ByokRoutingService routing;
	@Autowired
	EnvelopeEncryption encryption;
	@Autowired
	DatabaseClient db;

	// 账号 id 用规范 UUID 字符串（权益校验 isCanonicalUuid 拒绝非 UUID；AiRunControllerIT 同款格式）
	private final String account = UUID.randomUUID().toString();
	private final PersonalActor actor = new PersonalActor(account);

	@DynamicPropertySource
	static void extraProps(DynamicPropertyRegistry r) {
		r.add("credits.finance.base-url", CREDITS::baseUrl);
		// 权益检查直连 marketplace 基座（AiRunControllerIT 同款指向桩）
		r.add("marketplace.service.base-url", CREDITS::baseUrl);
	}

	@BeforeEach
	void seed() {
		db.sql("DELETE FROM dh_invocation").then().then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_session").then()).then(db.sql("DELETE FROM ai_model_budget").then())
				.block(Duration.ofSeconds(10));
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
				+ " (SELECT id FROM platform_model_config WHERE credential_id IN"
				+ " (SELECT id FROM platform_provider_credential WHERE base_url = :baseUrl))")
				.bind("baseUrl", QWEN.baseUrl()).then()
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN"
						+ " (SELECT id FROM platform_provider_credential WHERE base_url = :baseUrl)"
						+ " OR capability = 'text'").bind("baseUrl", QWEN.baseUrl()).then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE base_url = :baseUrl")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.block(Duration.ofSeconds(10));
		String encrypted = encryption.encrypt("sk-inv-platform-text");
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url, encrypted_key, key_version,
				        masked_hint, enabled)
				    VALUES ('it-inv-text', 'qwen', :baseUrl, :encrypted, 'v1', 'sk-***inv', true)
				    RETURNING id
				)
				INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
				    health_status, enabled, version, credential_id)
				SELECT 'text','primary','qwen','qwen-plus',:baseUrl,'healthy',true,1,cred.id FROM cred
				""").bind("baseUrl", QWEN.baseUrl()).bind("encrypted", encrypted).then().block(Duration.ofSeconds(10));
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
	}

	private UUID seedSession() {
		UUID sessionId = UUID.randomUUID();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at)"
				+ " VALUES (CAST(:s AS uuid), :owner, CAST(:p AS uuid), 1, '角色', 'backend-1', CAST(:pf AS uuid),"
				+ " CAST(:c AS uuid), CAST('{}' AS jsonb), 'ready', now())").bind("s", sessionId.toString())
				.bind("owner", account).bind("p", UUID.randomUUID().toString()).bind("pf", UUID.randomUUID().toString())
				.bind("c", UUID.randomUUID().toString()).then().block(Duration.ofSeconds(10));
		return sessionId;
	}

	/** 同一会话内建轮次（epoch 由调用方递增，模拟真实 next_turn_epoch 分配）。 */
	private UUID seedTurn(UUID sessionId, long epoch) {
		UUID turnId = UUID.randomUUID();
		db.sql("INSERT INTO dh_turn(id, owner_account_id, session_id, request_id, turn_epoch,"
				+ " input_kind, state, started_at) VALUES (CAST(:t AS uuid), :owner, CAST(:s AS uuid),"
				+ " CAST(:r AS uuid), :epoch, 'text', 'generating', now())").bind("t", turnId.toString())
				.bind("owner", account).bind("s", sessionId.toString()).bind("r", UUID.randomUUID().toString())
				.bind("epoch", epoch).then().block(Duration.ofSeconds(10));
		return turnId;
	}

	private InvocationRow reserveLlm(UUID sessionId, UUID turnId) {
		ProviderResolution provider = routing.resolvePlatform("text").block(Duration.ofSeconds(10));
		return invocations
				.reserve(actor, sessionId, turnId, DigitalHumanRecords.InvocationStage.llm, turnId, 0, provider,
						"hash-" + UUID.randomUUID(), Instant.now().plus(Duration.ofSeconds(90)), 1000, 128, 0)
				.block(Duration.ofSeconds(10));
	}

	private InvocationRow invocation(String id) {
		return invocationRepository.findById(UUID.fromString(id)).block(Duration.ofSeconds(10));
	}

	private long runCount(String operationId) {
		return db.sql("SELECT count(*) AS n FROM ai_run WHERE operation_id = CAST(:op AS uuid)").bind("op", operationId)
				.map((r, m) -> r.get("n", Long.class)).one().block(Duration.ofSeconds(10));
	}

	// ---------- TC105D-01-02：派发后重启 ----------

	@Test
	void tc105d_01_02_dispatchedRestartMarksUnknownWithoutSecondDispatch() {
		UUID sessionId = seedSession();
		UUID turnId = seedTurn(sessionId, 1);
		InvocationRow row = reserveLlm(sessionId, turnId);
		PreparedInvocation prepared = invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(20));
		assertThat(prepared.context()).isNotNull();
		CREDITS.resetRequests();
		invocations.claimDispatch(UUID.fromString(row.id())).block(Duration.ofSeconds(20));
		assertThat(invocation(row.id()).state()).isEqualTo(InvocationState.dispatched);
		assertThat(CREDITS.findAll(postRequestedFor(urlMatching("/internal/credits/.*"))))
				.as("claimDispatch 只持久派发标记，不产生新经济副作用").isEmpty();

		// 响应丢失 + worker 重启：deadline 已过（时间推进以数据注入，不 sleep）。
		db.sql("UPDATE dh_invocation SET deadline_at = now() - interval '1 second' WHERE id = CAST(:id AS uuid)")
				.bind("id", row.id()).then().block(Duration.ofSeconds(10));
		assertThatThrownBy(() -> invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(20)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_invocation_unknown"));

		InvocationRow after = invocation(row.id());
		assertThat(after.state()).as("unknown 待核对").isEqualTo(InvocationState.unknown);
		assertThat(after.settlementState()).isEqualTo(SettlementState.pending);
		assertThat(runCount(row.operationId())).as("重启重试不得产生第二个 run").isEqualTo(1);
		CREDITS.verify(0, postRequestedFor(urlMatching("/internal/credits/.*")));
		// 不第二次派发：unknown 后 claimDispatch 拒绝。
		assertThatThrownBy(() -> invocations.claimDispatch(UUID.fromString(row.id())).block(Duration.ofSeconds(20)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_invocation_state"));
	}

	@Test
	void tc105d_01_02_inFlightDispatchReentryReturnsDefiniteConflict() {
		UUID sessionId = seedSession();
		UUID turnId = seedTurn(sessionId, 1);
		InvocationRow row = reserveLlm(sessionId, turnId);
		invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(20));
		invocations.claimDispatch(UUID.fromString(row.id())).block(Duration.ofSeconds(20));
		// deadline 未过：在途派发不被误判 unknown，确定性 409，状态不动。
		assertThatThrownBy(() -> invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(20)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_invocation_dispatched"));
		assertThat(invocation(row.id()).state()).isEqualTo(InvocationState.dispatched);
		assertThat(runCount(row.operationId())).isEqualTo(1);
	}

	// ---------- TC105D-01-03：取消规则 ----------

	@Test
	void tc105d_01_03_cancelBeforeDispatchCompensatesAndKeepsPriorSuccess() {
		// 同一会话两轮：前序成功调用（turn1 完成 settle，保留不动）。
		UUID sessionId = seedSession();
		UUID turn1 = seedTurn(sessionId, 1);
		InvocationRow first = reserveLlm(sessionId, turn1);
		PreparedInvocation firstPrepared = invocations.prepare(UUID.fromString(first.id()))
				.block(Duration.ofSeconds(20));
		assertThat(invocations
				.settleSuccess(UUID.fromString(first.id()), firstPrepared.context(),
						new UsageUnits(8L, 4L, null, null, null, null, "req-1", "confirmed"))
				.block(Duration.ofSeconds(20))).isTrue();
		// 前序轮次收尾（真实流程 settle 后 turn 终态；部分唯一索引 uq_dh_turn_session_active 只容一个活动 turn）
		db.sql("UPDATE dh_turn SET state = 'completed', ended_at = now() WHERE id = CAST(:t AS uuid)")
				.bind("t", turn1.toString()).then().block(Duration.ofSeconds(10));

		// 未派发取消：turn2 prepared 后主动取消 → 原 cancel-before-provider 补偿。
		UUID turn2 = seedTurn(sessionId, 2);
		InvocationRow second = reserveLlm(sessionId, turn2);
		PreparedInvocation secondPrepared = invocations.prepare(UUID.fromString(second.id()))
				.block(Duration.ofSeconds(20));
		invocations.cancelBeforeDispatch(UUID.fromString(second.id()), secondPrepared.context())
				.block(Duration.ofSeconds(20));

		InvocationRow cancelled = invocation(second.id());
		assertThat(cancelled.state()).isEqualTo(InvocationState.cancelled);
		assertThat(cancelled.settlementState()).isEqualTo(SettlementState.not_required);
		assertThat(db.sql("SELECT status FROM ai_run WHERE operation_id = CAST(:op AS uuid)")
				.bind("op", second.operationId()).map((r, m) -> r.get("status", String.class)).one()
				.block(Duration.ofSeconds(10))).isEqualTo("cancelled");
		assertThat(db
				.sql("SELECT count(*) AS n FROM ai_credit_compensation WHERE run_id IN"
						+ " (SELECT id FROM ai_run WHERE operation_id = CAST(:op AS uuid))")
				.bind("op", second.operationId()).map((r, m) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10))).as("未派发取消必须落补偿意图（已 consume 的退回）").isEqualTo(1);

		// 前序成功保留：turn1 invocation/run 均不受 turn2 取消影响。
		assertThat(invocation(first.id()).state()).isEqualTo(InvocationState.succeeded);
		assertThat(db.sql("SELECT status FROM ai_run WHERE operation_id = CAST(:op AS uuid)")
				.bind("op", first.operationId()).map((r, m) -> r.get("status", String.class)).one()
				.block(Duration.ofSeconds(10))).isEqualTo("completed");
	}

	@Test
	void tc105d_01_03_abortAfterDispatchSettlesAtReservationWithoutRefund() {
		UUID session = seedSession();
		UUID turn = seedTurn(session, 1);
		InvocationRow row = reserveLlm(session, turn);
		PreparedInvocation prepared = invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(20));
		invocations.claimDispatch(UUID.fromString(row.id())).block(Duration.ofSeconds(20));

		// 已派发主动 abort：按预留结算、不退款（无补偿意图）。
		invocations.abortAfterDispatch(UUID.fromString(row.id()), prepared.context()).block(Duration.ofSeconds(20));
		InvocationRow aborted = invocation(row.id());
		assertThat(aborted.state()).isEqualTo(InvocationState.cancelled);
		assertThat(aborted.settlementState()).as("已派发 abort 按预留成本结算").isEqualTo(SettlementState.settled);
		assertThat(db
				.sql("SELECT count(*) AS n FROM ai_credit_compensation WHERE run_id IN"
						+ " (SELECT id FROM ai_run WHERE operation_id = CAST(:op AS uuid))")
				.bind("op", row.operationId()).map((r, m) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10))).as("已派发 abort 不退款（内容可能已流出）").isZero();
		assertThat(
				db.sql("SELECT status FROM ai_run WHERE operation_id = CAST(:op AS uuid)").bind("op", row.operationId())
						.map((r, m) -> r.get("status", String.class)).one().block(Duration.ofSeconds(10)))
				.isEqualTo("cancelled");
	}
}
