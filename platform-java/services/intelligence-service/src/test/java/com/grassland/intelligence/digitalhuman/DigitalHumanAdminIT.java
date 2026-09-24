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
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanInvocationService.PreparedInvocation;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SettlementState;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

/**
 * 治理端角色隔离、开关与容量、外部依赖失败 IT（任务书 #105G C105G-03 / TC105G-03-01、TC105G-03-02、
 * TC105G-03-04 / K10 ADMIN01～06）。
 *
 * <p>
 * 真实 DB/事务/CAS/审计表；runtime 经可替换 fake transport（end 失败可控＝「runtime 不可达」注入）；
 * credits/marketplace 断言经 WireMock 最外层桩。03-04 的「结算外部依赖不可用」用控制面停用文本模型注入
 * （rehydrate 冻结引用重解析失败），恢复后同证据可收口——不假称已完成。
 */
class DigitalHumanAdminIT extends IntelligenceItSupport {

	static final WireMockServer CREDITS = new WireMockServer(0);
	static {
		CREDITS.start();
	}

	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	static {
		REDIS.start();
	}

	@DynamicPropertySource
	static void extraProps(DynamicPropertyRegistry r) {
		r.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
		r.add("credits.finance.base-url", CREDITS::baseUrl);
		r.add("marketplace.service.base-url", CREDITS::baseUrl);
	}

	/** fake runtime transport：end 失败可控（TC105G-03-04 runtime 不可达半场）。 */
	@TestConfiguration
	static class FakeRuntimeConfig {

		static final AtomicInteger END_ATTEMPTS = new AtomicInteger();
		static volatile boolean endFails = false;

		@Bean
		@Primary
		DigitalHumanRuntimeClient fakeRuntime() {
			DigitalHumanRuntimeClient.Transport transport = new DigitalHumanRuntimeClient.Transport() {
				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> createSession(String sessionId, String backendId,
						UUID commandId) {
					return Mono.just(state(sessionId, "connecting"));
				}

				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> state(String sessionId) {
					return Mono.just(state(sessionId, "connecting"));
				}

				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> end(String sessionId, UUID commandId,
						String reasonCode) {
					END_ATTEMPTS.incrementAndGet();
					return endFails
							? Mono.error(new IllegalStateException("runtime unreachable"))
							: Mono.just(state(sessionId, "ended"));
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
	}

	private static final String AVATAR_ID = "22222222-2222-4222-8222-222222222222";
	private static final String VOICE_ID = "preset-zh-natural-01";
	private static final UUID BACKEND_ID = UUID.fromString("bbbbbbb1-0000-4000-8000-000000000003");

	@Autowired
	private DatabaseClient db;
	@Autowired
	private ReactiveStringRedisTemplate redis;
	@Autowired
	private DigitalHumanInvocationService invocations;
	@Autowired
	private ByokRoutingService routing;
	@Autowired
	private EnvelopeEncryption encryption;

	// 03-01 角色账号（会话面不需要规范 UUID）；03-04 调用面账号须规范 UUID（权益校验）。
	private final String plainUser = "dh-g3-plain-" + UUID.randomUUID();
	private final String csUser = "dh-g3-cs-" + UUID.randomUUID();
	private final String finUser = "dh-g3-fin-" + UUID.randomUUID();
	private final String adminUser = "dh-g3-admin-" + UUID.randomUUID();
	private final String invAccount = UUID.randomUUID().toString();
	private final PersonalActor invActor = new PersonalActor(invAccount);

	@BeforeEach
	void seed() {
		FakeRuntimeConfig.endFails = false;
		FakeRuntimeConfig.END_ATTEMPTS.set(0);
		QWEN.resetAll();
		CREDITS.resetAll();
		redis.execute(connection -> connection.serverCommands().flushDb().flux()).then().block(Duration.ofSeconds(5));
		db.sql("DELETE FROM dh_cleanup").then().then(db.sql("DELETE FROM dh_asset_attachment").then())
				.then(db.sql("DELETE FROM dh_recording").then()).then(db.sql("DELETE FROM dh_avatar").then())
				.then(db.sql("DELETE FROM dh_admin_audit").then()).then(db.sql("DELETE FROM dh_invocation").then())
				.then(db.sql("DELETE FROM dh_operation").then()).then(db.sql("DELETE FROM dh_transcript").then())
				.then(db.sql("DELETE FROM dh_event").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_session").then()).then(db.sql("DELETE FROM dh_preview").then())
				.then(db.sql("DELETE FROM dh_profile_revision").then()).then(db.sql("DELETE FROM dh_profile").then())
				.then(db.sql("DELETE FROM dh_catalog").then())
				.then(db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
						+ " (SELECT id FROM platform_model_config WHERE credential_id IN"
						+ " (SELECT id FROM platform_provider_credential WHERE name LIKE 'it-dh-g3-%'))").then())
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN"
						+ " (SELECT id FROM platform_provider_credential WHERE name LIKE 'it-dh-g3-%')").then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name LIKE 'it-dh-g3-%'").then())
				.block(Duration.ofSeconds(20));
		String credential = db
				.sql("INSERT INTO platform_provider_credential(name, provider, base_url, encrypted_key, key_version,"
						+ " masked_hint, enabled) VALUES ('it-dh-g3-cred', 'openai-compatible', :baseUrl, :encrypted,"
						+ " 'v1', 'sk-***g3', true) RETURNING id::text")
				.bind("baseUrl", QWEN.baseUrl()).bind("encrypted", encryption.encrypt("sk-dh-g3"))
				.map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5));
		for (String[] row : List.of(new String[]{"text", "primary", "qwen-plus", null},
				new String[]{"voice", "primary", "sandbox-speech-v1", null},
				new String[]{"video_tts", "primary", "sandbox-tts-v1", null},
				new String[]{"digital_human_render", "primary", "sandbox-video-v1", BACKEND_ID.toString()})) {
			var spec = (row[3] == null
					? db.sql("INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,"
							+ " health_status, enabled, version, credential_id) VALUES (:cap, :role,"
							+ " 'openai-compatible', :model, :baseUrl, 'healthy', true, 1, CAST(:cred AS uuid))"
							+ " ON CONFLICT DO NOTHING")
					: db.sql("INSERT INTO platform_model_config(id, capability, model_role, provider, model,"
							+ " base_url, health_status, enabled, version, credential_id)"
							+ " VALUES (CAST(:id AS uuid), :cap, :role, 'openai-compatible', :model, :baseUrl,"
							+ " 'healthy', true, 1, CAST(:cred AS uuid)) ON CONFLICT DO NOTHING").bind("id", row[3]));
			spec.bind("cap", row[0]).bind("role", row[1]).bind("model", row[2]).bind("baseUrl", QWEN.baseUrl())
					.bind("cred", credential).then().block(Duration.ofSeconds(5));
		}
		db.sql("INSERT INTO dh_catalog(singleton_id, version, config_json, updated_by) VALUES (1, 1,"
				+ " CAST(:config AS jsonb), 'it')").bind("config", """
						{"enabled":true,"newSessionsAllowed":true,"recordingEnabled":false,"customAvatarEnabled":false,
						 "maxSessionsGlobal":1,"maxQueuedGlobal":10,
						 "allowedBackendIds":["%s"],
						 "avatars":[{"id":"%s","revision":1,"name":"治理角色","previewMediaId":"preset-avatar-1",
						   "source":"preset","state":"ready","compatibleBackendIds":["%s"]}],
						 "voices":[{"id":"%s","name":"自然女声","providerModelRef":"tts-zh-natural-01",
						   "compatibleBackendIds":["%s"],"enabled":true}]}
						""".formatted(BACKEND_ID, AVATAR_ID, BACKEND_ID, VOICE_ID, BACKEND_ID).replace("\n", "")).then()
				.block(Duration.ofSeconds(5));
		// 03-04 调用面（规范 UUID 账号）权益/积分最外层桩。
		CREDITS.stubFor(get(urlEqualTo("/internal/marketplace/reputation/" + invAccount + "/ai-entitlement"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"success\":true,\"data\":{\"accountId\":\"" + invAccount
								+ "\",\"aiQuotaMultiplierBps\":10000,\"policyVersion\":1}}")));
		CREDITS.stubFor(post(urlEqualTo("/internal/credits/consume"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"success\":true,\"data\":{\"source\":\"quota\",\"policyVersion\":1,"
								+ "\"transactionId\":\"11111111-1111-1111-1111-111111111111\"}}")));
		CREDITS.stubFor(post(urlEqualTo("/internal/credits/refund")).willReturn(aResponse().withStatus(200)));
		CREDITS.stubFor(
				post(urlEqualTo("/internal/credits/consume-compensations")).willReturn(aResponse().withStatus(200)));
	}

	// ---------- 造数（个人面走真实 HTTP；调用面走服务） ----------

	private String createProfile(String account) {
		byte[] body = postJson("/api/digital-human/profiles", """
				{"name":"治理角色","persona":"人设","greeting":"你好","tone":"natural","avatarId":"%s",
				 "voiceId":"%s","catalogVersion":1,"requestId":"%s"}
				""".formatted(AVATAR_ID, VOICE_ID, UUID.randomUUID()).replace("\n", ""), 201, sign(account, null));
		return json(body).path("data").path("id").asText();
	}

	private String preflightId(String account, String profileId) {
		byte[] body = postJson("/api/digital-human/preflights", preflightBody(profileId), 200, sign(account, null));
		return json(body).path("data").path("id").asText();
	}

	/** 建会话并返回 (sessionId, state)。 */
	/**
	 * 建会话并返回 (sessionId, state)。期望 404（关闭新建）时门可能在 preflight 或 create 任一层——
	 * preflight 已 404 即达成断言，不再发 create。
	 */
	private String[] createSession(String account, String profileId, int expectedStatus) {
		if (expectedStatus == 404) {
			byte[] pf = client().post().uri("/api/digital-human/preflights")
					.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
					.bodyValue(preflightBody(profileId)).exchange().expectStatus().isEqualTo(404).expectBody()
					.returnResult().getResponseBody();
			assertThat(json(pf).path("code").asText()).isEqualTo("dh_feature_disabled");
			return new String[]{null, "404"};
		}
		byte[] body = postJson("/api/digital-human/sessions", """
				{"preflightId":"%s","requestId":"%s","saveTranscript":false}
				""".formatted(preflightId(account, profileId), UUID.randomUUID()).replace("\n", ""), expectedStatus,
				sign(account, null));
		if (expectedStatus >= 300) {
			return new String[]{null, String.valueOf(expectedStatus)};
		}
		var node = json(body).path("data");
		return new String[]{node.path("id").asText(), node.path("state").asText()};
	}

	private static String preflightBody(String profileId) {
		return """
				{"profileId":"%s","profileVersion":1,"inputMode":"text","controllerId":"%s"}
				""".formatted(profileId, UUID.randomUUID()).replace("\n", "");
	}

	private byte[] postJson(String uri, String body, int expectedStatus, String identity) {
		return client().post().uri(uri).header("X-Grassland-Identity", identity).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isEqualTo(expectedStatus).expectBody(byte[].class)
				.returnResult().getResponseBody();
	}

	private static com.fasterxml.jackson.databind.JsonNode json(byte[] body) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
		} catch (Exception failure) {
			throw new IllegalStateException(failure);
		}
	}

	// ---------- TC105G-03-01：角色隔离 ----------

	@Test
	void tc105g_03_01_roleIsolationOnAllSixAdminEndpoints() {
		// 元数据种子：普通账号 A 的会话 + 转写（治理面不得带出正文）。
		String accountA = "dh-g3-a-" + UUID.randomUUID();
		String profileId = createProfile(accountA);
		String sessionId = createSession(accountA, profileId, 202)[0];
		db.sql("INSERT INTO dh_transcript(id, owner_account_id, session_id, utterance_id, utterance_seq, role,"
				+ " final_text, status, started_at, ended_at, content_epoch) VALUES (gen_random_uuid(), :o,"
				+ " CAST(:s AS uuid), gen_random_uuid(), 1, 'user', '隐私正文标记G3XYZ', 'complete', now()," + " now(), 1)")
				.bind("o", accountA).bind("s", sessionId).then().block(Duration.ofSeconds(5));

		// 未登录 → 401（六端点逐一）。
		for (String uri : new String[]{"/api/admin/digital-human/config", "/api/admin/digital-human/sessions",
				"/api/admin/digital-human/invocations"}) {
			client().get().uri(uri).exchange().expectStatus().isUnauthorized();
		}
		client().put().uri("/api/admin/digital-human/config").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
				.exchange().expectStatus().isUnauthorized();
		client().post().uri("/api/admin/digital-human/sessions/" + sessionId + "/terminate")
				.contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isUnauthorized();
		client().post().uri("/api/admin/digital-human/invocations/" + UUID.randomUUID() + "/reconcile")
				.contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isUnauthorized();

		// 普通用户 / customer_service / finance → 403 dh_admin_required（契约错误码）。
		for (String identity : List.of(sign(plainUser, null), signWithRole(csUser, null, null, "customer_service"),
				signWithRole(finUser, null, null, "finance"))) {
			for (String uri : new String[]{"/api/admin/digital-human/config", "/api/admin/digital-human/sessions",
					"/api/admin/digital-human/invocations"}) {
				client().get().uri(uri).header("X-Grassland-Identity", identity).exchange().expectStatus().isForbidden()
						.expectBody().jsonPath("$.code").isEqualTo("dh_admin_required");
			}
			client().put().uri("/api/admin/digital-human/config").header("X-Grassland-Identity", identity)
					.contentType(MediaType.APPLICATION_JSON)
					.bodyValue("{\"expectedVersion\":1,\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"r\"}")
					.exchange().expectStatus().isForbidden().expectBody().jsonPath("$.code")
					.isEqualTo("dh_admin_required");
			client().post().uri("/api/admin/digital-human/sessions/" + sessionId + "/terminate")
					.header("X-Grassland-Identity", identity).contentType(MediaType.APPLICATION_JSON)
					.bodyValue("{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"紧急\"}").exchange()
					.expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("dh_admin_required");
			client().post().uri("/api/admin/digital-human/invocations/" + UUID.randomUUID() + "/reconcile")
					.header("X-Grassland-Identity", identity).contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
					.exchange().expectStatus().isForbidden().expectBody().jsonPath("$.code")
					.isEqualTo("dh_admin_required");
		}

		// platform_admin：可元数据管理——配置读取、会话元数据分页（无正文）、诚实 404。
		byte[] config = client().get().uri("/api/admin/digital-human/config")
				.header("X-Grassland-Identity", signAdmin(adminUser)).exchange().expectStatus().isOk().expectBody()
				.returnResult().getResponseBody();
		assertThat(json(config).path("data").path("version").asInt()).isEqualTo(1);
		byte[] sessions = client().get().uri("/api/admin/digital-human/sessions?limit=10")
				.header("X-Grassland-Identity", signAdmin(adminUser)).exchange().expectStatus().isOk().expectBody()
				.returnResult().getResponseBody();
		String sessionsText = new String(sessions, StandardCharsets.UTF_8);
		assertThat(sessionsText).contains(sessionId);
		assertThat(sessionsText).contains("profileNameAtCreation");
		assertThat(sessionsText).as("治理面会话列表不得带出转写正文").doesNotContain("隐私正文标记G3XYZ");
		assertThat(json(sessions).path("data").path("items").isArray()).isTrue();
		client().get().uri("/api/admin/digital-human/invocations?limit=10")
				.header("X-Grassland-Identity", signAdmin(adminUser)).exchange().expectStatus().isOk();
		// 诚实 404：不存在的会话/调用不得假称处置成功。
		client().post().uri("/api/admin/digital-human/sessions/" + UUID.randomUUID() + "/terminate")
				.header("X-Grassland-Identity", signAdmin(adminUser)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"不存在\"}").exchange().expectStatus()
				.isNotFound().expectBody().jsonPath("$.code").isEqualTo("dh_not_found");
	}

	// ---------- TC105G-03-02：开关与容量 ----------

	@Test
	void tc105g_03_02_newSessionToggleCapacityAndAudit() {
		String accountA = "dh-g3-cap-a-" + UUID.randomUUID();
		String accountB = "dh-g3-cap-b-" + UUID.randomUUID();
		String accountC = "dh-g3-cap-c-" + UUID.randomUUID();
		String profileA = createProfile(accountA);
		String profileB = createProfile(accountB);
		String profileC = createProfile(accountC);

		// 默认 capacity=1、实测 1：A 建成（preparing/connecting），B 只能排队。
		String[] first = createSession(accountA, profileA, 202);
		assertThat(first[1]).isIn("preparing", "connecting");
		String[] second = createSession(accountB, profileB, 202);
		assertThat(second[1]).as("容量满 → 排队而非占用活动位").isEqualTo("queued");

		// 关闭新建：新请求 404 dh_feature_disabled；end 不受影响。
		byte[] updated = client().put().uri("/api/admin/digital-human/config")
				.header("X-Grassland-Identity", signAdmin(adminUser)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"newSessionsAllowed\":false,\"expectedVersion\":1,\"requestId\":\"" + UUID.randomUUID()
						+ "\",\"reason\":\"维护窗口\"}")
				.exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
		assertThat(json(updated).path("data").path("version").asInt()).isEqualTo(2);
		assertThat(json(updated).path("data").path("newSessionsAllowed").asBoolean()).isFalse();
		createSession(accountC, profileC, 404);
		// end 可用：管理员终止 A 的会话仍成功（K10：开关只挡新建）。
		client().post().uri("/api/admin/digital-human/sessions/" + first[0] + "/terminate")
				.header("X-Grassland-Identity", signAdmin(adminUser)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"容量演练\"}").exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.state").isEqualTo("ended");

		// 扩容 capacity=2 并恢复新建：C 再建 → preparing（不再排队）。
		client().put().uri("/api/admin/digital-human/config").header("X-Grassland-Identity", signAdmin(adminUser))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"newSessionsAllowed\":true,\"maxSessionsGlobal\":2,\"expectedVersion\":2,"
						+ "\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"扩容演练\"}")
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.version").isEqualTo(3);
		assertThat(createSession(accountC, profileC, 202)[1]).isIn("preparing", "connecting");

		// 旧 expectedVersion → 409 dh_version_conflict（CAS）；未启用后端 → 422。
		client().put().uri("/api/admin/digital-human/config").header("X-Grassland-Identity", signAdmin(adminUser))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"maxSessionsGlobal\":5,\"expectedVersion\":1,\"requestId\":\"" + UUID.randomUUID()
						+ "\",\"reason\":\"过期版本\"}")
				.exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code")
				.isEqualTo("dh_version_conflict");
		client().put().uri("/api/admin/digital-human/config").header("X-Grassland-Identity", signAdmin(adminUser))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"allowedBackendIds\":[\"ghost-backend\"],\"expectedVersion\":3,\"requestId\":\""
						+ UUID.randomUUID() + "\",\"reason\":\"非法后端\"}")
				.exchange().expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("dh_invalid_input");
		client().put().uri("/api/admin/digital-human/config").header("X-Grassland-Identity", signAdmin(adminUser))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"maxSessionsGlobal\":0,\"expectedVersion\":3,\"requestId\":\"" + UUID.randomUUID()
						+ "\",\"reason\":\"越界容量\"}")
				.exchange().expectStatus().isEqualTo(422);

		// 审计准确：两次生效更新各留一行（含新版本号元数据）；被拒的尝试不改配置也不落生效行。
		List<String> auditRows = db
				.sql("SELECT action || ':' || (metadata_json->>'newVersion') FROM dh_admin_audit"
						+ " WHERE actor_account_id = :actor AND action = 'config_update' ORDER BY created_at")
				.bind("actor", adminUser).map((r, m) -> r.get(0, String.class)).all().collectList()
				.block(Duration.ofSeconds(5));
		assertThat(auditRows).as("审计与生效更新一一对应").containsExactly("config_update:2", "config_update:3");
		assertThat(db.sql("SELECT config_json->>'maxSessionsGlobal' FROM dh_catalog WHERE singleton_id = 1")
				.map((r, m) -> r.get(0, String.class)).one().block(Duration.ofSeconds(5))).isEqualTo("2");
	}

	// ---------- TC105G-03-04：外部依赖失败 ----------

	@Test
	void tc105g_03_04_externalDependencyFailureKeepsRecoverableState() {
		// 半场 A：runtime 不可达时的终止——runtime.end 注入失败，DB 终态仍如实收口（reaper 兜底语义），
		// 不假称 runtime 已确认；审计与 runtime 尝试事实在。
		String accountA = "dh-g3-ext-a-" + UUID.randomUUID();
		String sessionId = createSession(accountA, createProfile(accountA), 202)[0];
		FakeRuntimeConfig.endFails = true;
		byte[] terminated = client().post().uri("/api/admin/digital-human/sessions/" + sessionId + "/terminate")
				.header("X-Grassland-Identity", signAdmin(adminUser)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"runtime 故障演练\"}").exchange()
				.expectStatus().isOk().expectBody().returnResult().getResponseBody();
		assertThat(json(terminated).path("data").path("state").asText()).isEqualTo("ended");
		assertThat(FakeRuntimeConfig.END_ATTEMPTS.get()).as("runtime 通知已尝试（失败不吞为静默成功）").isGreaterThanOrEqualTo(1);
		assertThat(db
				.sql("SELECT count(*) FROM dh_admin_audit WHERE action = 'session_terminate'"
						+ " AND resource_id = CAST(:s AS uuid)")
				.bind("s", sessionId).map((r, m) -> r.get(0, Long.class)).one().block(Duration.ofSeconds(5)))
				.isEqualTo(1L);
		FakeRuntimeConfig.endFails = false;

		// 半场 B：结算外部依赖（控制面文本模型停用 → 冻结引用重解析失败）——reconcile 如实报错、
		// unknown/pending 原样保留，恢复后同证据一次收口。
		String invocationId = seedUnknownInvocation();
		db.sql("UPDATE platform_model_config SET enabled = false WHERE capability = 'text'").then()
				.block(Duration.ofSeconds(5));
		String evidenceBody = reconcileBody(invocationId, currentVersion(invocationId));
		client().post().uri("/api/admin/digital-human/invocations/" + invocationId + "/reconcile")
				.header("X-Grassland-Identity", signAdmin(adminUser)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(evidenceBody).exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code")
				.isEqualTo("dh_configuration_changed");
		var stillUnknown = findInvocation(invocationId);
		assertThat(stillUnknown.state()).as("失败不落终态、保 pending 可恢复").isEqualTo(InvocationState.unknown);
		assertThat(stillUnknown.settlementState()).isEqualTo(SettlementState.pending);
		// 恢复（控制面重新启用）→ 同证据核对一次收口。
		db.sql("UPDATE platform_model_config SET enabled = true WHERE capability = 'text'").then()
				.block(Duration.ofSeconds(5));
		client().post().uri("/api/admin/digital-human/invocations/" + invocationId + "/reconcile")
				.header("X-Grassland-Identity", signAdmin(adminUser)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(evidenceBody).exchange().expectStatus().isOk().expectBody().jsonPath("$.data.state")
				.isEqualTo("succeeded");
		assertThat(db
				.sql("SELECT count(*) AS n FROM ai_run run JOIN dh_invocation inv ON inv.ai_run_id = run.id"
						+ " WHERE inv.id = CAST(:i AS uuid)")
				.bind("i", invocationId).map((r, m) -> r.get("n", Long.class)).one().block(Duration.ofSeconds(5)))
				.as("恢复收口不新增 run").isEqualTo(1L);
	}

	/**
	 * 造 unknown 调用（真实状态机：reserve→prepare→claimDispatch→deadline 注入→重入判 unknown）。
	 */
	private String seedUnknownInvocation() {
		UUID sessionId = UUID.randomUUID();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at)"
				+ " VALUES (CAST(:s AS uuid), :owner, gen_random_uuid(), 1, '角色', 'backend-1', gen_random_uuid(),"
				+ " gen_random_uuid(), '{}'::jsonb, 'ready', now())").bind("s", sessionId.toString())
				.bind("owner", invAccount).then().block(Duration.ofSeconds(5));
		UUID turnId = UUID.randomUUID();
		db.sql("INSERT INTO dh_turn(id, owner_account_id, session_id, request_id, turn_epoch, input_kind, state,"
				+ " started_at) VALUES (CAST(:t AS uuid), :owner, CAST(:s AS uuid), gen_random_uuid(), 1, 'text',"
				+ " 'generating', now())").bind("t", turnId.toString()).bind("owner", invAccount)
				.bind("s", sessionId.toString()).then().block(Duration.ofSeconds(5));
		var provider = routing.resolvePlatform("text").block(Duration.ofSeconds(10));
		var row = invocations
				.reserve(invActor, sessionId, turnId, DigitalHumanRecords.InvocationStage.llm, turnId, 0, provider,
						"hash-" + UUID.randomUUID(), Instant.now().plus(Duration.ofSeconds(90)), 1000, 128, 0)
				.block(Duration.ofSeconds(10));
		PreparedInvocation prepared = invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(20));
		assertThat(prepared.context()).isNotNull();
		invocations.claimDispatch(UUID.fromString(row.id())).block(Duration.ofSeconds(20));
		db.sql("UPDATE dh_invocation SET deadline_at = now() - interval '1 second' WHERE id = CAST(:id AS uuid)")
				.bind("id", row.id()).then().block(Duration.ofSeconds(5));
		assertThatThrownBy(() -> invocations.prepare(UUID.fromString(row.id())).block(Duration.ofSeconds(20)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_invocation_unknown"));
		return row.id();
	}

	private DigitalHumanRecords.InvocationRow findInvocation(String id) {
		return db.sql("""
				SELECT id::text, owner_account_id, session_id::text, turn_id::text, stage, resource_id::text,
				       segment_index, operation_id::text, ai_run_id::text, state, settlement_state,
				       provider_snapshot::text, budget_snapshot::text, usage_json::text, provider_run_id,
				       request_hash, deadline_at, next_attempt_at, version, created_at, updated_at
				FROM dh_invocation WHERE id = CAST(:id AS uuid)
				""").bind("id", id).map(DigitalHumanInvocationRepository::rowOf).one().block(Duration.ofSeconds(5));
	}

	private int currentVersion(String invocationId) {
		return findInvocation(invocationId).version();
	}

	private static String reconcileBody(String invocationId, int expectedVersion) {
		return """
				{"requestId":"%s","expectedVersion":%d,"outcome":"succeeded","providerEvidenceRef":"prov-evidence-g3",
				 "confirmedUsage":{"inputTokens":120,"outputTokens":45,"audioInputMs":null,"audioOutputMs":null,
				   "textCodePoints":null,"renderMs":null,"providerRequestId":"llm-g3","quality":"confirmed"},
				 "reason":"外部依赖失败演练"}
				""".formatted(UUID.randomUUID(), expectedVersion).replace("\n", "");
	}
}
