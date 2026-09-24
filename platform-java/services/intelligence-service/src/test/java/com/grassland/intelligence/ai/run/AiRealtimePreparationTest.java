package com.grassland.intelligence.ai.run;

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
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanInvocationRepository;
import com.grassland.intelligence.digitalhuman.DigitalHumanInvocationService;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SettlementState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.UsageUnits;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

/**
 * 实时执行准备（任务书 #105D C105D-01 / TC105D-01-01、TC105D-01-04）。
 *
 * <p>
 * 真实 Postgres + 事务 + 经济键；网络只经 WireMock 桩（credits 最外层、provider 本卡零调用）。四窗口崩溃
 * 契约：bind 失败整体回滚（无孤儿 run/双预留）→ 原键重试恰好一个 ai_run；bind 冲突不造第二个 run；charge
 * 失败进入既有补偿窗口；个人 own text 免个人预算但平台额度闸仍生效。
 */
class AiRealtimePreparationTest extends IntelligenceItSupport {

	static final WireMockServer CREDITS = new WireMockServer(0);
	static {
		CREDITS.start();
	}

	@Autowired
	AiExecutionService aiExecution;
	@Autowired
	DigitalHumanInvocationService invocations;
	@Autowired
	DigitalHumanInvocationRepository invocationRepository;
	@Autowired
	PriceTableService prices;
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
				.then(db.sql("DELETE FROM dh_session").then()).then(db.sql("DELETE FROM ai_provider_preference").then())
				.then(db.sql("DELETE FROM ai_provider_key").then()).then(db.sql("DELETE FROM ai_model_budget").then())
				.block(Duration.ofSeconds(10));
		// 独立平台 text 行（自种凭据，避免共享容器跨类残留；AiRunControllerIT 同款清理边界）
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
		String encrypted = encryption.encrypt("sk-rtp-platform-text");
		db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url, encrypted_key, key_version,
				        masked_hint, enabled)
				    VALUES ('it-rtp-text', 'qwen', :baseUrl, :encrypted, 'v1', 'sk-***rtp', true)
				    RETURNING id
				)
				INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
				    health_status, enabled, version, credential_id)
				SELECT 'text','primary','qwen','qwen-plus',:baseUrl,'healthy',true,1,cred.id FROM cred
				""").bind("baseUrl", QWEN.baseUrl()).bind("encrypted", encrypted).then().block(Duration.ofSeconds(10));
		CREDITS.resetAll();
		stubCreditsOk();
	}

	private void stubCreditsOk() {
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

	/** 直插最小会话/轮次（本卡无会话 HTTP 流；invocation 经济键挂真实形状）。 */
	private UUID seedTurn() {
		UUID sessionId = UUID.randomUUID();
		UUID turnId = UUID.randomUUID();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at)"
				+ " VALUES (CAST(:s AS uuid), :owner, CAST(:p AS uuid), 1, '角色', 'backend-1', CAST(:pf AS uuid),"
				+ " CAST(:c AS uuid), CAST('{}' AS jsonb), 'ready', now())").bind("s", sessionId.toString())
				.bind("owner", account).bind("p", UUID.randomUUID().toString()).bind("pf", UUID.randomUUID().toString())
				.bind("c", UUID.randomUUID().toString()).then()
				.then(db.sql("INSERT INTO dh_turn(id, owner_account_id, session_id, request_id, turn_epoch,"
						+ " input_kind, state, started_at) VALUES (CAST(:t AS uuid), :owner, CAST(:s AS uuid),"
						+ " CAST(:r AS uuid), 1, 'text', 'generating', now())").bind("t", turnId.toString())
						.bind("owner", account).bind("s", sessionId.toString()).bind("r", UUID.randomUUID().toString())
						.then())
				.block(Duration.ofSeconds(10));
		lastSessionId = sessionId;
		return turnId;
	}

	private UUID lastSessionId;

	private InvocationRow reserveLlm(UUID turnId, ProviderResolution provider) {
		return invocations
				.reserve(actor, lastSessionId, turnId, DigitalHumanRecords.InvocationStage.llm, turnId, 0, provider,
						"hash-" + UUID.randomUUID(), Instant.now().plus(Duration.ofSeconds(90)), 1000, 128, 0)
				.block(Duration.ofSeconds(10));
	}

	private ProviderResolution platformText() {
		return routing.resolvePlatform("text").block(Duration.ofSeconds(10));
	}

	private RealtimePreparation command(InvocationRow row, ProviderResolution provider, CreditFeature feature) {
		return new RealtimePreparation(UUID.fromString(row.operationId()), account, null, "text", feature, provider,
				prices.currentVersionLabel(), 1000, 128, 0);
	}

	/**
	 * 直连 AiExecutionService 前先 CAS 领工作（镜像 DigitalHumanInvocationService.prepare
	 * 的真实步骤）。
	 */
	private Instant casToPreparing(InvocationRow row) {
		Instant lease = Instant.now().plus(Duration.ofSeconds(60));
		invocationRepository.casPreparing(UUID.fromString(row.id()), lease).block(Duration.ofSeconds(10));
		return lease;
	}

	private long runCount(String operationId) {
		return db.sql("SELECT count(*) AS n FROM ai_run WHERE operation_id = CAST(:op AS uuid)").bind("op", operationId)
				.map((r, m) -> r.get("n", Long.class)).one().block(Duration.ofSeconds(10));
	}

	private long count(String sql) {
		return db.sql(sql).map((r, m) -> r.get("n", Long.class)).one().block(Duration.ofSeconds(10));
	}

	private InvocationRow invocation(String id) {
		return invocationRepository.findById(UUID.fromString(id)).block(Duration.ofSeconds(10));
	}

	// ---------- TC105D-01-01：prepare 原子性 ----------

	@Test
	void tc105d_01_01_bindFailureRollsBackThenOriginalKeyRetriesExactlyOnce() {
		UUID turnId = seedTurn();
		InvocationRow row = reserveLlm(turnId, platformText());
		AtomicInteger bindCalls = new AtomicInteger();
		String budgetJson = "{\"state\":\"prepared\"}";

		// 窗口①：run 创建后、绑定后事务提交前注入异常 → 整体回滚（无孤儿 run、无双预留）。CAS 领工作在
		// 独立事务先行提交，回滚后行停在 preparing（本进程持租约、无 run 绑定）——K08 恢复前置。
		Instant lease = casToPreparing(row);
		assertThatThrownBy(() -> aiExecution
				.prepareRealtimeExecution(command(row, platformText(), CreditFeature.AI_RUN_TEXT),
						binding -> invocationRepository
								.bindPrepared(UUID.fromString(row.id()), binding.runId(), budgetJson, lease)
								.doOnSuccess(v -> bindCalls.incrementAndGet())
								.then(Mono.error(new IllegalStateException("crash between run and commit"))))
				.block(Duration.ofSeconds(20))).isInstanceOf(IllegalStateException.class);
		assertThat(bindCalls.get()).isEqualTo(1);
		assertThat(runCount(row.operationId())).as("bind 失败必须整体回滚 run").isZero();
		InvocationRow afterCrash = invocation(row.id());
		assertThat(afterCrash.state()).as("无 run 绑定的 preparing 可按租约恢复").isEqualTo(InvocationState.preparing);
		assertThat(afterCrash.aiRunId()).isNull();

		// 窗口②：原键重试（同 operationId、同租约仍有效）→ 恰好一个 ai_run，绑定成功。
		RealtimePreparation retry = command(row, platformText(), CreditFeature.AI_RUN_TEXT);
		var result = aiExecution.prepareRealtimeExecution(retry, binding -> invocationRepository
				.bindPrepared(UUID.fromString(row.id()), binding.runId(), budgetJson, lease))
				.block(Duration.ofSeconds(20));
		assertThat(result.allowed()).isTrue();
		assertThat(runCount(row.operationId())).isEqualTo(1);
		assertThat(invocation(row.id()).state()).isEqualTo(InvocationState.prepared);

		// 窗口③：已提交绑定的原键再 prepare → bind 冲突回滚第二个 run，绝不两个 ai_run。
		assertThatThrownBy(() -> aiExecution.prepareRealtimeExecution(retry,
				binding -> invocationRepository.bindPrepared(UUID.fromString(row.id()), binding.runId(), budgetJson,
						lease))
				.block(Duration.ofSeconds(20))).isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_invocation_bound"));
		assertThat(runCount(row.operationId())).as("bind 冲突不得产生第二个 run").isEqualTo(1);
	}

	@Test
	void tc105d_01_01_chargeFailureEntersCompensationWindowWithoutOrphan() {
		UUID turnId = seedTurn();
		InvocationRow row = reserveLlm(turnId, platformText());
		String budgetJson = "{\"state\":\"prepared\"}";
		// 窗口④：事务提交后 charge 失败 → 既有补偿窗口（run failed + 补偿意图），绑定不回滚。
		Instant lease = casToPreparing(row);
		CREDITS.resetRequests();
		CREDITS.stubFor(post(urlEqualTo("/internal/credits/consume")).willReturn(aResponse().withStatus(500)));
		assertThatThrownBy(
				() -> aiExecution
						.prepareRealtimeExecution(command(row, platformText(), CreditFeature.AI_RUN_TEXT),
								binding -> invocationRepository.bindPrepared(UUID.fromString(row.id()), binding.runId(),
										budgetJson, lease))
						.block(Duration.ofSeconds(20)))
				.isInstanceOf(Exception.class);
		assertThat(runCount(row.operationId())).isEqualTo(1);
		assertThat(
				db.sql("SELECT status FROM ai_run WHERE operation_id = CAST(:op AS uuid)").bind("op", row.operationId())
						.map((r, m) -> r.get("status", String.class)).one().block(Duration.ofSeconds(10)))
				.isEqualTo("failed");
		assertThat(count("SELECT count(*) AS n FROM ai_credit_compensation WHERE run_id IN"
				+ " (SELECT id FROM ai_run WHERE operation_id = CAST('" + row.operationId() + "' AS uuid))"))
				.as("charge 失败必须落补偿意图").isEqualTo(1);
		assertThat(invocation(row.id()).state()).isEqualTo(InvocationState.prepared);

		// 崩溃恢复语义：绑定存在 → 原键 prepare 只回读原 run，绝不再生成第二个。
		var recovered = invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(20));
		assertThat(recovered.operationId().toString()).isEqualTo(row.operationId());
		assertThat(runCount(row.operationId())).isEqualTo(1);
	}

	// ---------- TC105D-01-04：个人 BYOK 预算 ----------

	@Test
	void tc105d_01_04_ownTextRecordsUsageAtZeroPlatformCostWithoutPersonalBudget() {
		// own text：个人密钥 + 模型来源总开关 own（真实路由解析，不造 BYOK 假对象）。
		db.sql("INSERT INTO ai_provider_key(organization_id, owner_account_id, capability, provider, base_url,"
				+ " model, encrypted_key, key_version, masked_hint) VALUES (NULL, :owner, 'text',"
				+ " 'openai-compatible', :baseUrl, 'own-llm-x', :encrypted, 'v1', 'sk-***own')").bind("owner", account)
				.bind("baseUrl", QWEN.baseUrl()).bind("encrypted", encryption.encrypt("sk-own-secret")).then()
				.then(db.sql("INSERT INTO ai_provider_preference(account_id, capability, use_own_key)"
						+ " VALUES (:owner, '*', true)").bind("owner", account).then())
				.block(Duration.ofSeconds(10));
		ProviderResolution own = routing.resolveProvider(null, account, "text", false).block(Duration.ofSeconds(10));
		assertThat(own.isByok()).as("own 总开关 + 个人密钥应解析为 BYOK").isTrue();

		UUID turnId = seedTurn();
		InvocationRow row = reserveLlm(turnId, own);
		CREDITS.resetRequests();
		var prepared = invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(20));
		assertThat(prepared.context().provider().isByok()).isTrue();

		// 平台 0 费、无个人预算消耗（个人 BYOK 豁免路径不建预算也不扣），积分零调用。
		CREDITS.verify(0, postRequestedFor(urlMatching("/internal/credits/.*")));
		assertThat(count("SELECT count(*) AS n FROM ai_model_budget WHERE organization_id = 'u:" + account + "'"))
				.isZero();
		assertThat(
				db.sql("SELECT model FROM ai_run WHERE operation_id = CAST(:op AS uuid)").bind("op", row.operationId())
						.map((r, m) -> r.get("model", String.class)).one().block(Duration.ofSeconds(10)))
				.isEqualTo("own-llm-x");

		// 记录 usage：按实际 token 落 run 计量与 invocation 用量，actualCents=0（平台不代扣）。
		Boolean settled = invocations
				.settleSuccess(UUID.fromString(row.id()), prepared.context(),
						new UsageUnits(10L, 5L, null, null, null, null, "own-req-1", "confirmed"))
				.block(Duration.ofSeconds(20));
		assertThat(settled).isTrue();
		var completed = db
				.sql("SELECT status, actual_cents, input_tokens, output_tokens FROM ai_run"
						+ " WHERE operation_id = CAST(:op AS uuid)")
				.bind("op", row.operationId())
				.map((r, m) -> java.util.Map.of("status", String.valueOf(r.get("status", String.class)), "actual_cents",
						String.valueOf(r.get("actual_cents", Integer.class)), "input",
						String.valueOf(r.get("input_tokens", Integer.class)), "output",
						String.valueOf(r.get("output_tokens", Integer.class))))
				.one().block(Duration.ofSeconds(10));
		assertThat(completed.get("status")).isEqualTo("completed");
		assertThat(completed.get("actual_cents")).isEqualTo("0");
		assertThat(completed.get("input")).isEqualTo("10");
		assertThat(completed.get("output")).isEqualTo("5");
		InvocationRow terminal = invocation(row.id());
		assertThat(terminal.state()).isEqualTo(InvocationState.succeeded);
		assertThat(terminal.settlementState()).isEqualTo(SettlementState.settled);
		assertThat(terminal.usageJson()).contains("own-req-1");
	}

	@Test
	void tc105d_01_04_platformPathStillGatedByPersonalBudget() {
		// 资源限额仍生效：平台 text + 个人预算 0 → 拒绝派发（own 豁免只免 own，不放行平台）。
		db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_tokens_per_run,"
				+ " max_cents_per_run, enabled) VALUES ('u:' || :owner, 'text', 'platform', 0, 0, true)")
				.bind("owner", account).then().block(Duration.ofSeconds(10));
		UUID turnId = seedTurn();
		InvocationRow row = reserveLlm(turnId, platformText());
		assertThatThrownBy(() -> invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(20)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> {
					assertThat(e.status()).isEqualTo(402);
					assertThat(e.code()).isEqualTo("exceeds_run_budget");
				});
		assertThat(runCount(row.operationId())).as("预算拒绝不得建 run").isZero();
	}
}
