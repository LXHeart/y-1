package com.grassland.intelligence.digitalhuman;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanGrantService.ExecutionGrant;
import com.grassland.intelligence.digitalhuman.DigitalHumanInvocationService.PreparedInvocation;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationStage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * INTERNAL08 STT multipart 执行面 IT（任务书 #105fix-1 C105X-04 /
 * TC105X-04-03、TC105X-04-04）： 真实 Postgres + Redis + QWEN（STT
 * /audio/transcriptions 走受信 WireMock）。断言 NDJSON 四帧序、 grant 单次核销（GETDEL：重放 409
 * dh_grant_invalid 既有语义）、invocation 终态与单次结算、 不建 media_reference；multipart 负例（缺
 * part/非法 meta/超限/hash 不符）不派发；provider 失败走 fail 补偿 + error 终结帧（HTTP 200
 * 后）。llm/tts 维持 503 占位。
 */
class DigitalHumanExecuteSttIT extends IntelligenceItSupport {

	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);
	private static final WireMockServer CREDITS = new WireMockServer(0);

	static {
		REDIS.start();
		CREDITS.start();
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry r) {
		r.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
		// credits/marketplace 指向 CREDITS 桩（prepare/settle 经济链）；QWEN 只承载 provider 上游。
		r.add("credits.finance.base-url", CREDITS::baseUrl);
		r.add("marketplace.service.base-url", CREDITS::baseUrl);
	}

	@Autowired
	DigitalHumanInternalServer internalServer;
	@Autowired
	DigitalHumanGrantService grants;
	@Autowired
	DigitalHumanInvocationService invocations;
	@Autowired
	com.grassland.intelligence.ai.byok.ByokRoutingService routing;
	@Autowired
	DatabaseClient db;
	@Autowired
	EnvelopeEncryption encryption;

	private final String account = UUID.randomUUID().toString();
	private final PersonalActor actor = new PersonalActor(account);
	private UUID sessionId;

	private WebTestClient internalClient() {
		// 直接绑定 internal 面的 RouterFunction（handled() 错误映射在路由 lambda 内）；
		// mutate + responseTimeout 30s 为仓库惯例（bindToRouterFunction 无直接超时参数）。
		return WebTestClient.bindToRouterFunction(internalServer.routes()).configureClient()
				.responseTimeout(Duration.ofSeconds(30)).build();
	}

	@BeforeEach
	void seed() {
		// FK 顺序照 C105X-02 双端自清纪律：dh_event/dh_transcript 先于会话族（共库其他类的残留行引用）。
		db.sql("DELETE FROM dh_event").then().then(db.sql("DELETE FROM dh_transcript").then())
				.then(db.sql("DELETE FROM dh_invocation").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_operation").then()).then(db.sql("DELETE FROM dh_session").then())
				.block(Duration.ofSeconds(10));
		QWEN.resetAll();
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
		QWEN.stubFor(post(urlEqualTo("/audio/transcriptions"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"text\":\"测试转写结果\",\"language\":\"zh\",\"duration\":1.0,"
								+ "\"usage\":{\"input_tokens\":4,\"output_tokens\":2}}")));
		// 三个能力的平台行（text/voice/video_tts）带凭据指向受信 QWEN（目的地唯一索引按目的地整清）。
		db.sql("DELETE FROM platform_model_config WHERE credential_id IN"
				+ " (SELECT id FROM platform_provider_credential WHERE base_url = :baseUrl)")
				.bind("baseUrl", QWEN.baseUrl()).then()
				.then(db.sql("DELETE FROM platform_provider_credential WHERE base_url = :baseUrl")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.block(Duration.ofSeconds(10));
		String encrypted = encryption.encrypt("sk-it-dh-stt");
		String credentialId = db.sql("""
				INSERT INTO platform_provider_credential(name, provider, base_url, encrypted_key, key_version,
				    masked_hint, enabled)
				VALUES ('it-dh-stt', 'openai-compatible', :baseUrl, :encrypted, 'v1', 'sk-***stt', true)
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
	}

	@AfterEach
	void cleanCredentials() {
		db.sql("DELETE FROM platform_model_config WHERE credential_id IN"
				+ " (SELECT id FROM platform_provider_credential WHERE base_url = :baseUrl)"
				+ " OR capability IN ('text','voice','video_tts')").bind("baseUrl", QWEN.baseUrl()).then()
				.then(db.sql("DELETE FROM platform_provider_credential WHERE base_url = :baseUrl")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.block(Duration.ofSeconds(10));
	}

	// ---------- 公共装配 ----------

	private String seedSessionAndTurn() {
		UUID holder = UUID.randomUUID();
		UUID turnId = UUID.randomUUID();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at)"
				+ " VALUES (CAST(:s AS uuid), :owner, CAST(:p AS uuid), 1, '角色', 'backend-1', CAST(:pf AS uuid),"
				+ " CAST(:c AS uuid), CAST('{}' AS jsonb), 'ready', now())").bind("s", holder.toString())
				.bind("owner", account).bind("p", UUID.randomUUID().toString()).bind("pf", UUID.randomUUID().toString())
				.bind("c", UUID.randomUUID().toString()).then()
				.then(db.sql("INSERT INTO dh_turn(id, owner_account_id, session_id, request_id, turn_epoch,"
						+ " input_kind, state, started_at) VALUES (CAST(:t AS uuid), :owner, CAST(:s AS uuid),"
						+ " CAST(:r AS uuid), 1, 'audio', 'generating', now())").bind("t", turnId.toString())
						.bind("owner", account).bind("s", holder.toString()).bind("r", UUID.randomUUID().toString())
						.then())
				.block(Duration.ofSeconds(10));
		sessionId = holder;
		return turnId.toString();
	}

	private record PreparedGrant(PreparedInvocation prepared, ExecutionGrant grant, String inputHash) {
	}

	private PreparedGrant prepareStt(UUID turnId, String inputHash) {
		var provider = routing.resolvePlatform("voice").block(Duration.ofSeconds(10));
		var row = invocations.reserve(actor, sessionId, turnId, InvocationStage.stt, turnId, 0, provider, inputHash,
				Instant.now().plusSeconds(90), 0, 0, 60).block(Duration.ofSeconds(20));
		PreparedInvocation prepared = invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(20));
		ExecutionGrant grant = grants.issueExecution(account, UUID.fromString(row.id()), sessionId, InvocationStage.stt,
				inputHash, prepared.deadlineAt()).block(Duration.ofSeconds(10));
		return new PreparedGrant(prepared, grant, inputHash);
	}

	private static byte[] wavBytes(int samples) {
		byte[] all = new byte[44 + samples * 2];
		all[0] = 'R';
		all[1] = 'I';
		all[2] = 'F';
		all[3] = 'F';
		return all;
	}

	private static String metaJson(String inputHash, long durationMs) {
		return "{\"v\":1,\"inputHash\":\"" + inputHash + "\",\"input\":{\"durationMs\":" + durationMs
				+ ",\"pcmSha256\":\"" + "c".repeat(64) + "\"}}";
	}

	private WebTestClient.RequestHeadersSpec<?> executeMultipart(String invocationId, String grant, String metaJson,
			byte[] wav) {
		MultipartBodyBuilder builder = new MultipartBodyBuilder();
		builder.part("meta", new ByteArrayResource(metaJson.getBytes(StandardCharsets.UTF_8)) {
		}).contentType(org.springframework.http.MediaType.APPLICATION_JSON);
		builder.part("wav", new ByteArrayResource(wav) {
		}).contentType(org.springframework.http.MediaType.parseMediaType("audio/wav"));
		return internalClient().post().uri("/internal/digital-human/invocations/" + invocationId + "/execute")
				.contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
				.header("Authorization", "Bearer " + grant).body(BodyInserters.fromMultipartData(builder.build()));
	}

	private List<String> lines(byte[] body) {
		return new String(body, StandardCharsets.UTF_8).lines().toList();
	}

	private String invocationState(String invocationId) {
		return db
				.sql("SELECT state::text AS state, settlement_state::text AS settlement FROM dh_invocation"
						+ " WHERE id = CAST(:id AS uuid)")
				.bind("id", invocationId)
				.map((row, meta) -> row.get("state", String.class) + "/" + row.get("settlement", String.class)).one()
				.block(Duration.ofSeconds(10));
	}

	// ---------- TC105X-04-03：NDJSON 四帧与单次核销 ----------

	@Test
	void tc105x_04_03_sttExecuteStreamsFramesSettlesOnceAndConsumesGrantOnce() {
		String turnId = seedSessionAndTurn();
		String inputHash = "a".repeat(64);
		PreparedGrant pg = prepareStt(UUID.fromString(turnId), inputHash);
		String invocationId = pg.prepared().invocationId().toString();

		byte[] body = executeMultipart(invocationId, pg.grant().grant(), metaJson(inputHash, 1000), wavBytes(16000))
				.exchange().expectStatus().isOk().expectHeader().valueMatches("Content-Type", "application/x-ndjson.*")
				.expectBody(byte[].class).returnResult().getResponseBody();
		List<String> frames = lines(body);
		assertThat(frames).hasSize(4);
		frames.forEach(frame -> assertThat(frame.length()).as("每行 ≤128KiB").isLessThanOrEqualTo(128 * 1024));
		assertThat(frames.get(0)).contains("\"type\":\"meta\"").contains("\"seq\":0").contains("\"format\":\"wav\"");
		assertThat(frames.get(1)).contains("\"type\":\"delta\"").contains("\"seq\":1").contains("测试转写结果")
				.contains("\"audioBase64\":null");
		assertThat(frames.get(2)).contains("\"type\":\"usage\"").contains("\"seq\":2").contains("\"inputTokens\":4")
				.contains("\"outputTokens\":2").contains("\"audioInputMs\":1000").contains("\"quality\":\"confirmed\"");
		assertThat(frames.get(3)).contains("\"type\":\"done\"").contains("\"seq\":3")
				.contains("\"state\":\"succeeded\"");

		// grant 单次核销（GETDEL）：重放同 grant → 409 dh_grant_invalid（既有语义；TC 字面的 401 与
		// GrantService 既有映射不一致，以既有代码为准，交接中记录）。
		byte[] replay = executeMultipart(invocationId, pg.grant().grant(), metaJson(inputHash, 1000), wavBytes(16000))
				.exchange().expectStatus().isEqualTo(409).expectBody(byte[].class).returnResult().getResponseBody();
		assertThat(lines(replay).get(0)).contains("dh_grant_invalid");

		// invocation 终态 succeeded + settled；结算走原经济键单次（不二次扣）。
		assertThat(invocationState(invocationId)).isEqualTo("succeeded/settled");
		QWEN.verify(1, com.github.tomakehurst.wiremock.client.WireMock
				.postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/audio/transcriptions")));

		// 不建 media_reference（STT 不产生用户媒体引用；本测试账号随机，任何引用行都不该存在）。
		Long mediaRefs = db.sql("SELECT count(*)::int AS n FROM media_reference WHERE owner_account_id = :owner")
				.bind("owner", account).map((row, meta) -> row.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(mediaRefs).isZero();

		// 结算幂等：credits consume 恰一次（重放 settle 触发 completeRun CAS 落空 → 既有
		// IllegalStateException 确定性失败，不再产生第二次扣费）。
		assertThatThrownBy(() -> invocations
				.settleSuccess(UUID.fromString(invocationId), pg.prepared().context(),
						new DigitalHumanRecords.UsageUnits(4L, 2L, 1000L, null, null, null, null, "confirmed"))
				.block(Duration.ofSeconds(10))).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("AI run completion state update failed");
		CREDITS.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
				com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/internal/credits/consume")));
	}

	// ---------- multipart 负例（不核销、不 claimDispatch） ----------

	@Test
	void tc105x_04_03_missingPartsAndInvalidMetaRejectedWithoutConsumption() {
		String turnId = seedSessionAndTurn();
		String inputHash = "a".repeat(64);
		PreparedGrant pg = prepareStt(UUID.fromString(turnId), inputHash);
		String invocationId = pg.prepared().invocationId().toString();

		// 缺 wav part → 422。
		MultipartBodyBuilder onlyMeta = new MultipartBodyBuilder();
		onlyMeta.part("meta", new ByteArrayResource(metaJson(inputHash, 1000).getBytes(StandardCharsets.UTF_8)) {
		}).contentType(org.springframework.http.MediaType.APPLICATION_JSON);
		internalClient().post().uri("/internal/digital-human/invocations/" + invocationId + "/execute")
				.header("Authorization", "Bearer " + pg.grant().grant())
				.contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
				.body(BodyInserters.fromMultipartData(onlyMeta.build())).exchange().expectStatus().isEqualTo(422);

		// meta 非法 JSON → 422。
		byte[] rejected = executeMultipart(invocationId, pg.grant().grant(), "{not-json", wavBytes(16000)).exchange()
				.expectStatus().isEqualTo(422).expectBody(byte[].class).returnResult().getResponseBody();
		assertThat(lines(rejected).get(0)).contains("dh_invalid_input");

		// wav 超 1.92MB（1.92MB+1）→ 422。
		byte[] oversized = executeMultipart(invocationId, pg.grant().grant(), metaJson(inputHash, 60000),
				new byte[1_920_001]).exchange().expectStatus().isEqualTo(422).expectBody(byte[].class).returnResult()
				.getResponseBody();
		assertThat(lines(oversized).get(0)).contains("dh_invalid_input");

		// inputHash 不符 → 409 既有（consumeExecution 匹配失败即焚毁）。
		// 单活跃会话唯一约束：先收口本账号第一个会话再种第二个。
		db.sql("UPDATE dh_session SET state='ended', ended_at=now() WHERE owner_account_id = :owner AND state='ready'")
				.bind("owner", account).then().block(Duration.ofSeconds(10));
		String otherTurn = seedSessionAndTurn();
		PreparedGrant pg2 = prepareStt(UUID.fromString(otherTurn), "b".repeat(64));
		byte[] hashMismatch = executeMultipart(pg2.prepared().invocationId().toString(), pg2.grant().grant(),
				metaJson("e".repeat(64), 1000), wavBytes(16000)).exchange().expectStatus().isEqualTo(409)
				.expectBody(byte[].class).returnResult().getResponseBody();
		assertThat(lines(hashMismatch).get(0)).contains("dh_grant_invalid");

		// 负例不消耗 pg.grant（仅 hashMismatch 案焚毁 pg2）：pg grant 仍可执行成功。
		executeMultipart(invocationId, pg.grant().grant(), metaJson(inputHash, 1000), wavBytes(16000)).exchange()
				.expectStatus().isOk();

		// 负例不派发：pg2 行保持 prepared（hash 不符只核销不推进状态机）。
		assertThat(invocationState(pg2.prepared().invocationId().toString())).isEqualTo("prepared/not_required");
	}

	@Test
	void tc105x_04_03_llmAndTtsStagesStayUnavailable() {
		String turnId = seedSessionAndTurn();
		String inputHash = "a".repeat(64);
		var provider = routing.resolvePlatform("text").block(Duration.ofSeconds(10));
		var row = invocations.reserve(actor, sessionId, UUID.fromString(turnId), InvocationStage.llm,
				UUID.fromString(turnId), 0, provider, inputHash, Instant.now().plusSeconds(90), 0, 0, 0)
				.block(Duration.ofSeconds(20));
		var result = internalClient().post().uri("/internal/digital-human/invocations/" + row.id() + "/execute")
				.header("Authorization", "Bearer test-grant")
				.bodyValue("{\"v\":1,\"inputHash\":\"" + inputHash + "\",\"input\":{}}").exchange()
				.expectBody(String.class).returnResult();
		System.out.println("DEBUG-LLM-STATUS=" + result.getStatus());
		System.out.println("DEBUG-LLM-BODY=" + result.getResponseBody());
		assertThat(result.getStatus().value()).isEqualTo(503);
		assertThat(result.getResponseBody()).contains("dh_runtime_unavailable").contains("随真实档配置落地");
	}

	// ---------- TC105X-04-04：provider 失败 → fail 补偿 + error 终结帧 ----------

	@Test
	void tc105x_04_04_providerFailureMarksFailedWithoutHiddenCharge() {
		QWEN.stubFor(post(urlEqualTo("/audio/transcriptions"))
				.willReturn(aResponse().withStatus(500).withBody("upstream boom")));
		String turnId = seedSessionAndTurn();
		String inputHash = "a".repeat(64);
		PreparedGrant pg = prepareStt(UUID.fromString(turnId), inputHash);
		String invocationId = pg.prepared().invocationId().toString();

		byte[] body = executeMultipart(invocationId, pg.grant().grant(), metaJson(inputHash, 1000), wavBytes(16000))
				.exchange().expectStatus().isOk().expectBody(byte[].class).returnResult().getResponseBody();
		List<String> frames = lines(body);
		assertThat(frames).hasSize(1);
		assertThat(frames.get(0)).contains("\"type\":\"error\"").contains("\"code\":\"provider_failure\"")
				.contains("\"retryable\":true");

		assertThat(invocationState(invocationId)).isEqualTo("failed/not_required");
		// 无隐藏扣费：补偿按预留结算（K08 第 3 行 handleFailure 既有语义）→ consume 恰一次、无新增计费。
		CREDITS.verify(1, com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
				com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/internal/credits/consume")));
	}
}
