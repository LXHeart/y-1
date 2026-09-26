package com.grassland.intelligence.digitalhuman;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanInvocationService.PreparedInvocation;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SettlementState;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 治理端 unknown 调用核对幂等 IT（任务书 #105G C105G-03 / TC105G-03-03 / K10 ADMIN05～06）。
 *
 * <p>
 * 真实状态机造 unknown（reserve→prepare→claimDispatch→deadline 注入→重入判
 * unknown）；同证据两次重放只 按原经济键结算一次；旧 expectedVersion 409；金额字段（confirmedCents
 * 等）不得自填——请求体含未定义 字段直接 422；failed 结论走既有补偿不核销。credits/marketplace 经 WireMock
 * 最外层桩。
 */
class DigitalHumanAdminReconcileIT extends IntelligenceItSupport {

	static final WireMockServer CREDITS = new WireMockServer(0);
	static {
		CREDITS.start();
	}

	@Autowired
	private DigitalHumanInvocationService invocations;
	@Autowired
	private ByokRoutingService routing;
	@Autowired
	private EnvelopeEncryption encryption;
	@Autowired
	private DatabaseClient db;

	// 权益校验要求规范 UUID 账号（AiRunControllerIT 同款）；每个调用独立账号
	// （uq_dh_session_owner_active：一个账号只容一个活动会话）。
	private final String admin = "dh-g3r-admin-" + UUID.randomUUID();

	@DynamicPropertySource
	static void extraProps(DynamicPropertyRegistry r) {
		r.add("credits.finance.base-url", CREDITS::baseUrl);
		r.add("marketplace.service.base-url", CREDITS::baseUrl);
	}

	@BeforeEach
	void seed() {
		QWEN.resetAll();
		CREDITS.resetAll();
		cleanSharedDhTables();
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url, encrypted_key, key_version,
				        masked_hint, enabled)
				    VALUES ('it-dh-g3r-cred', 'qwen', :baseUrl, :encrypted, 'v1', 'sk-***g3r', true)
				    RETURNING id
				)
				INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
				    health_status, enabled, version, credential_id)
				SELECT 'text','primary','qwen','qwen-plus',:baseUrl,'healthy',true,1,cred.id FROM cred
				""").bind("baseUrl", QWEN.baseUrl()).bind("encrypted", encryption.encrypt("sk-g3r-platform-text"))
				.then().block(Duration.ofSeconds(10));
		CREDITS.stubFor(post(urlEqualTo("/internal/credits/consume"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"success\":true,\"data\":{\"source\":\"quota\",\"policyVersion\":1,"
								+ "\"transactionId\":\"11111111-1111-1111-1111-111111111111\"}}")));
		CREDITS.stubFor(post(urlEqualTo("/internal/credits/refund")).willReturn(aResponse().withStatus(200)));
		CREDITS.stubFor(
				post(urlEqualTo("/internal/credits/consume-compensations")).willReturn(aResponse().withStatus(200)));
	}

	/**
	 * tc105x-02-03（任务书 #105fix-1 C105X-02）：清理体抽方法双端复调——此前仅 @BeforeEach 单端，类结束
	 * 后残留要等下一类来清（共享容器跨类污染，审计 F-05）。FK 顺序照既有列表不改。凭据清理按
	 * <b>目的地</b>（provider+base_url）而非仅 name——全量套件里基座 attachPlatformTextCredential
	 * 种的 qwen@QWEN 凭据残留会撞 idx_platform_provider_credential_destination（基座自清同款先例）。
	 */
	private void cleanSharedDhTables() {
		db.sql("DELETE FROM dh_invocation").then().then(db.sql("DELETE FROM dh_admin_audit").then())
				.then(db.sql("DELETE FROM dh_operation").then()).then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_session").then())
				.then(db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
						+ " (SELECT id FROM platform_model_config WHERE credential_id IN"
						+ " (SELECT id FROM platform_provider_credential WHERE name LIKE 'it-dh-g3r-%'"
						+ " OR (provider = 'qwen' AND base_url = :baseUrl)))").bind("baseUrl", QWEN.baseUrl()).then())
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN"
						+ " (SELECT id FROM platform_provider_credential WHERE name LIKE 'it-dh-g3r-%'"
						+ " OR (provider = 'qwen' AND base_url = :baseUrl))" + " OR capability = 'text'")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name LIKE 'it-dh-g3r-%'"
						+ " OR (provider = 'qwen' AND base_url = :baseUrl)").bind("baseUrl", QWEN.baseUrl()).then())
				.block(Duration.ofSeconds(10));
	}

	@org.junit.jupiter.api.AfterEach
	void cleanSharedDhTablesAfter() {
		cleanSharedDhTables();
	}

	/** 新调用账号（规范 UUID）+ 对应权益桩。 */
	private String newInvAccount() {
		String account = UUID.randomUUID().toString();
		CREDITS.stubFor(get(urlEqualTo("/internal/marketplace/reputation/" + account + "/ai-entitlement"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"success\":true,\"data\":{\"accountId\":\"" + account
								+ "\",\"aiQuotaMultiplierBps\":10000,\"policyVersion\":1}}")));
		return account;
	}

	// ---------- 造数 ----------

	private UUID seedSession(String account) {
		UUID sessionId = UUID.randomUUID();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at)"
				+ " VALUES (CAST(:s AS uuid), :owner, gen_random_uuid(), 1, '角色', 'backend-1', gen_random_uuid(),"
				+ " gen_random_uuid(), '{}'::jsonb, 'ready', now())").bind("s", sessionId.toString())
				.bind("owner", account).then().block(Duration.ofSeconds(10));
		return sessionId;
	}

	private UUID seedTurn(String account, UUID sessionId, long epoch) {
		UUID turnId = UUID.randomUUID();
		db.sql("INSERT INTO dh_turn(id, owner_account_id, session_id, request_id, turn_epoch, input_kind, state,"
				+ " started_at) VALUES (CAST(:t AS uuid), :owner, CAST(:s AS uuid), gen_random_uuid(), :epoch,"
				+ " 'text', 'generating', now())").bind("t", turnId.toString()).bind("owner", account)
				.bind("s", sessionId.toString()).bind("epoch", epoch).then().block(Duration.ofSeconds(10));
		return turnId;
	}

	private DigitalHumanRecords.InvocationRow reserveLlm(String account, UUID sessionId, UUID turnId) {
		var provider = routing.resolvePlatform("text").block(Duration.ofSeconds(10));
		return invocations.reserve(new PersonalActor(account), sessionId, turnId,
				DigitalHumanRecords.InvocationStage.llm, turnId, 0, provider, "hash-" + UUID.randomUUID(),
				Instant.now().plus(Duration.ofSeconds(90)), 1000, 128, 0).block(Duration.ofSeconds(10));
	}

	/** 真实状态机造 unknown：派发后 deadline 注入 → 重入判 unknown（不第二次派发）。 */
	private DigitalHumanRecords.InvocationRow seedUnknown() {
		String account = newInvAccount();
		UUID sessionId = seedSession(account);
		UUID turnId = seedTurn(account, sessionId, 1);
		DigitalHumanRecords.InvocationRow row = reserveLlm(account, sessionId, turnId);
		invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(20));
		invocations.claimDispatch(UUID.fromString(row.id())).block(Duration.ofSeconds(20));
		db.sql("UPDATE dh_invocation SET deadline_at = now() - interval '1 second' WHERE id = CAST(:id AS uuid)")
				.bind("id", row.id()).then().block(Duration.ofSeconds(10));
		assertThatThrownBy(() -> invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(20)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_invocation_unknown"));
		return row;
	}

	private DigitalHumanRecords.InvocationRow findInvocation(String id) {
		return db.sql("""
				SELECT id::text, owner_account_id, session_id::text, turn_id::text, stage, resource_id::text,
				       segment_index, operation_id::text, ai_run_id::text, state, settlement_state,
				       provider_snapshot::text, budget_snapshot::text, usage_json::text, provider_run_id,
				       request_hash, deadline_at, next_attempt_at, version, created_at, updated_at
				FROM dh_invocation WHERE id = CAST(:id AS uuid)
				""").bind("id", id).map(DigitalHumanInvocationRepository::rowOf).one().block(Duration.ofSeconds(10));
	}

	private String reconcileBody(String requestId, int expectedVersion, String outcome, String evidenceRef,
			String usage) {
		return """
				{"requestId":"%s","expectedVersion":%d,"outcome":"%s","providerEvidenceRef":"%s","confirmedUsage":%s,
				 "reason":"核对演练"}
				""".formatted(requestId, expectedVersion, outcome, evidenceRef, usage).replace("\n", "");
	}

	private byte[] postReconcile(String invocationId, String body, int expectedStatus) {
		return client().post().uri("/api/admin/digital-human/invocations/" + invocationId + "/reconcile")
				.header("X-Grassland-Identity", signAdmin(admin)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isEqualTo(expectedStatus).expectBody(byte[].class)
				.returnResult().getResponseBody();
	}

	// ---------- TC105G-03-03：核对幂等 ----------

	@Test
	void tc105g_03_03_reconcileOnceOnOriginalEconomicKeyWithEvidenceRules() {
		// 已收口（settled）的调用不得出现在核对队列；unknown 的两行必须在。
		String settledAccount = newInvAccount();
		UUID sessionId = seedSession(settledAccount);
		UUID settledTurn = seedTurn(settledAccount, sessionId, 1);
		DigitalHumanRecords.InvocationRow settled = reserveLlm(settledAccount, sessionId, settledTurn);
		PreparedInvocation settledPrepared = invocations.prepare(UUID.fromString(settled.id()))
				.block(Duration.ofSeconds(20));
		assertThat(invocations
				.settleSuccess(UUID.fromString(settled.id()), settledPrepared.context(),
						new DigitalHumanRecords.UsageUnits(60L, 20L, null, null, null, null, "st-1", "confirmed"))
				.block(Duration.ofSeconds(20))).isTrue();

		DigitalHumanRecords.InvocationRow unknown = seedUnknown();
		DigitalHumanRecords.InvocationRow failedOutcome = seedUnknown();

		byte[] queue = client().get().uri("/api/admin/digital-human/invocations?limit=50")
				.header("X-Grassland-Identity", signAdmin(admin)).exchange().expectStatus().isOk().expectBody()
				.returnResult().getResponseBody();
		String queueText = new String(queue, StandardCharsets.UTF_8);
		assertThat(queueText).as("unknown 进入核对队列").contains(unknown.id()).contains(failedOutcome.id());
		assertThat(queueText).as("已收口调用不进核对队列").doesNotContain(settled.id());

		// 金额字段不得自填：请求体带 confirmedCents → 422（严格解析拒未定义字段）。
		String withAmount = """
				{"requestId":"%s","expectedVersion":%d,"outcome":"succeeded","providerEvidenceRef":"prov-g3r-1",
				 "confirmedUsage":{"inputTokens":120,"outputTokens":45,"audioInputMs":null,"audioOutputMs":null,
				   "textCodePoints":null,"renderMs":null,"providerRequestId":"llm-g3r","quality":"confirmed"},
				 "confirmedCents":500,"reason":"金额自填负例"}
				""".formatted(UUID.randomUUID(), findInvocation(unknown.id()).version()).replace("\n", "");
		postReconcile(unknown.id(), withAmount, 422);

		// 证据不全：succeeded 无 confirmedUsage → 422；failed 带 usage → 422。
		postReconcile(unknown.id(), reconcileBody(UUID.randomUUID().toString(), findInvocation(unknown.id()).version(),
				"succeeded", "prov-g3r-1", "null"), 422);
		postReconcile(failedOutcome.id(),
				reconcileBody(UUID.randomUUID().toString(), findInvocation(failedOutcome.id()).version(), "failed",
						"prov-g3r-f",
						"{\"inputTokens\":1,\"outputTokens\":null,\"audioInputMs\":null,\"audioOutputMs\":null,"
								+ "\"textCodePoints\":null,\"renderMs\":null,\"providerRequestId\":null,"
								+ "\"quality\":\"confirmed\"}"),
				422);

		// 有效核对：succeeded → 原经济键一次结算（confirmedCents 来自原 run 实际消耗，非自填）。
		CREDITS.resetRequests();
		String requestId = UUID.randomUUID().toString();
		String evidence = reconcileBody(requestId, findInvocation(unknown.id()).version(), "succeeded", "prov-g3r-1",
				"{\"inputTokens\":120,\"outputTokens\":45,\"audioInputMs\":null,\"audioOutputMs\":null,"
						+ "\"textCodePoints\":null,\"renderMs\":null,\"providerRequestId\":\"llm-g3r\","
						+ "\"quality\":\"confirmed\"}");
		byte[] reconciled = postReconcile(unknown.id(), evidence, 200);
		var node = json(reconciled);
		assertThat(node.path("data").path("state").asText()).isEqualTo("succeeded");
		assertThat(node.path("data").path("settlementState").asText()).isEqualTo("settled");
		assertThat(node.path("data").path("confirmedCents").isNumber()).isTrue();
		assertThat(node.path("data").path("usage").path("quality").asText()).isEqualTo("confirmed");
		assertThat(db.sql("SELECT status FROM ai_run WHERE operation_id = CAST(:op AS uuid)")
				.bind("op", unknown.operationId()).map((r, m) -> r.get("status", String.class)).one()
				.block(Duration.ofSeconds(10))).isEqualTo("completed");
		int settleCalls = CREDITS.findAll(postRequestedFor(urlEqualTo("/internal/credits/consume"))).size()
				+ CREDITS.findAll(postRequestedFor(urlEqualTo("/internal/credits/refund"))).size();

		// 同证据重放（同 requestId/expectedVersion）→ 409 dh_version_conflict；不产生第二次结算。
		postReconcile(unknown.id(), evidence, 409);
		int settleCallsAfterReplay = CREDITS.findAll(postRequestedFor(urlEqualTo("/internal/credits/consume"))).size()
				+ CREDITS.findAll(postRequestedFor(urlEqualTo("/internal/credits/refund"))).size();
		assertThat(settleCallsAfterReplay).as("重放不再触发结算调用").isEqualTo(settleCalls);
		assertThat(db.sql("SELECT count(*) AS n FROM ai_run WHERE operation_id = CAST(:op AS uuid)")
				.bind("op", unknown.operationId()).map((r, m) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10))).as("原经济键仅一个 run").isEqualTo(1L);
		// 审计幂等：同 requestId 重放不重复落审计行。
		assertThat(db
				.sql("SELECT count(*) FROM dh_admin_audit WHERE action = 'invocation_reconcile'"
						+ " AND request_id = CAST(:r AS uuid)")
				.bind("r", requestId).map((r, m) -> r.get(0, Long.class)).one().block(Duration.ofSeconds(10)))
				.isEqualTo(1L);

		// failed 结论（既有补偿、不核销）：failedOutcome 保持 unknown 直到 failed 核对 →
		// failed/not_required。
		postReconcile(failedOutcome.id(), reconcileBody(UUID.randomUUID().toString(),
				findInvocation(failedOutcome.id()).version(), "failed", "prov-g3r-f", "null"), 200);
		var failedRow = findInvocation(failedOutcome.id());
		assertThat(failedRow.state()).isEqualTo(InvocationState.failed);
		assertThat(failedRow.settlementState()).as("failed 结论走补偿，不核销用量").isEqualTo(SettlementState.not_required);
	}

	private static com.fasterxml.jackson.databind.JsonNode json(byte[] body) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
		} catch (Exception failure) {
			throw new IllegalStateException(failure);
		}
	}
}
