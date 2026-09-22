package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 会话创建 IT（任务书 #105C C105C-01 / TC105C-01-01～04）。
 *
 * <p>
 * Redis 用独立易失容器（preflight 快照 TTL 60s）；runtime 经可替换 fake transport（协议验证归
 * C01，mTLS 归 D02）——create 计数器断言「一个 runtime create」。覆盖：同键重放单资源单派发；预检零 provider
 * 调用/零 credits；过期与配置变更 410/409；容量与有界队列 429。
 */
class DigitalHumanSessionIT extends IntelligenceItSupport {

	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	static {
		REDIS.start();
	}

	@DynamicPropertySource
	static void redisProps(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
	}

	/** fake runtime transport 计数（static：@TestConfiguration 的 @Primary bean 消费）。 */
	static final AtomicInteger RUNTIME_CREATES = new AtomicInteger();
	static final AtomicInteger RUNTIME_ENDS = new AtomicInteger();

	@org.springframework.boot.test.context.TestConfiguration
	static class FakeRuntimeConfig {

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
					RUNTIME_ENDS.incrementAndGet();
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
					RUNTIME_CREATES.incrementAndGet();
				}

				@Override
				public void onEnd(String sessionId) {
					RUNTIME_ENDS.incrementAndGet();
				}
			});
		}
	}

	private static final String AVATAR_ID = "11111111-1111-4111-8111-111111111111";
	private static final String VOICE_ID = "preset-zh-natural-01";
	private static final UUID BACKEND_ID = UUID.fromString("aaaaaaa1-0000-4000-8000-000000000003");

	@Autowired
	private DatabaseClient db;

	@Autowired
	private ReactiveStringRedisTemplate redis;

	private final String account = "dh-c1-" + UUID.randomUUID();

	@BeforeEach
	void seedDomain() {
		QWEN.resetAll();
		RUNTIME_CREATES.set(0);
		RUNTIME_ENDS.set(0);
		redis.execute(connection -> connection.serverCommands().flushDb().flux()).then().block(Duration.ofSeconds(5));
		db.sql("DELETE FROM dh_profile_revision").then().then(db.sql("DELETE FROM dh_profile").then())
				.then(db.sql("DELETE FROM dh_operation").then()).then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_session").then())
				.then(db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
						+ " (SELECT id FROM platform_model_config WHERE credential_id IN"
						+ " (SELECT id FROM platform_provider_credential WHERE name LIKE 'it-dh-%')"
						+ " OR capability IN ('digital_human_render','voice','video_tts'))").then())
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN"
						+ " (SELECT id FROM platform_provider_credential WHERE name LIKE 'it-dh-%')"
						+ " OR capability IN ('digital_human_render','voice','video_tts')").then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE name LIKE 'it-dh-%'").then())
				.then(db.sql("DELETE FROM dh_catalog").then()).then(db.sql("DELETE FROM ai_run").then())
				.block(Duration.ofSeconds(10));

		String credential = db
				.sql("INSERT INTO platform_provider_credential(name, provider, base_url, enabled)"
						+ " VALUES ('it-dh-cred', 'openai-compatible', :baseUrl, true) RETURNING id::text")
				.bind("baseUrl", QWEN.baseUrl()).map(row -> row.get("id", String.class)).one()
				.block(Duration.ofSeconds(5));
		for (String[] row : List.of(new String[]{"text", "primary", "qwen-plus", null},
				new String[]{"voice", "primary", "sandbox-speech-v1", null},
				new String[]{"video_tts", "primary", "sandbox-tts-v1", null},
				new String[]{"digital_human_render", "primary", "sandbox-video-v1", BACKEND_ID.toString()})) {
			// render 配置用固定 id：目录 compatibleBackendIds/allowedBackendIds 引用它（组合交集）。
			// render 用显式固定 id（目录组合引用）；其余走列默认 gen_random_uuid()。
			var spec = (row[3] == null
					? db.sql("INSERT INTO platform_model_config(capability, model_role, provider, model,"
							+ " base_url, health_status, enabled, version, credential_id) VALUES (:cap,"
							+ " :role, 'openai-compatible', :model, :baseUrl, 'healthy', true, 1,"
							+ " CAST(:cred AS uuid)) ON CONFLICT DO NOTHING")
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
						 "avatars":[{"id":"%s","revision":1,"name":"草原少女","previewMediaId":"preset-avatar-1",
						   "source":"preset","state":"ready","compatibleBackendIds":["%s"]}],
						 "voices":[{"id":"%s","name":"自然女声","providerModelRef":"tts-zh-natural-01",
						   "compatibleBackendIds":["%s"],"enabled":true}]}
						""".formatted(BACKEND_ID, AVATAR_ID, BACKEND_ID, VOICE_ID, BACKEND_ID).replace("\n", "")).then()
				.block(Duration.ofSeconds(5));
	}

	private String createProfile() {
		byte[] body = postJson("/api/digital-human/profiles", """
				{"name":"会话角色","persona":"人设","greeting":"你好","tone":"natural","avatarId":"%s",
				 "voiceId":"%s","catalogVersion":1,"requestId":"%s"}
				""".formatted(AVATAR_ID, VOICE_ID, UUID.randomUUID()).replace("\n", ""), 201);
		return json(body).path("data").path("id").asText();
	}

	private byte[] preflight(String profileId) {
		return postJson("/api/digital-human/preflights", """
				{"profileId":"%s","profileVersion":1,"inputMode":"text","controllerId":"%s"}
				""".formatted(profileId, UUID.randomUUID()).replace("\n", ""), 200);
	}

	private byte[] postJson(String uri, String body, int expectedStatus) {
		return client().post().uri(uri).header("X-Grassland-Identity", sign(account, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus()
				.isEqualTo(expectedStatus).expectBody(byte[].class).returnResult().getResponseBody();
	}

	private static com.fasterxml.jackson.databind.JsonNode json(byte[] body) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
		} catch (Exception failure) {
			throw new IllegalStateException(failure);
		}
	}

	// ---------- TC105C-01-01：正常创建 ----------

	@Test
	void tc105c_01_01_sameKeyCreateReplaysSingleSessionAndRuntimeCreate() {
		String profileId = createProfile();
		String preflightId = json(preflight(profileId)).path("data").path("id").asText();
		String requestId = UUID.randomUUID().toString();
		String body = """
				{"preflightId":"%s","requestId":"%s","saveTranscript":false}
				""".formatted(preflightId, requestId).replace("\n", "");

		byte[] first = postJson("/api/digital-human/sessions", body, 202);
		String sessionId = json(first).path("data").path("id").asText();
		assertThat(json(first).path("data").path("state").asText()).isEqualTo("connecting");
		byte[] second = postJson("/api/digital-human/sessions", body, 202);
		assertThat(json(second).path("data").path("id").asText()).isEqualTo(sessionId);

		assertThat(RUNTIME_CREATES.get()).as("同键重放只派发一次 runtime create").isEqualTo(1);
		assertThat(count("SELECT count(*) AS n FROM dh_session WHERE state NOT IN ('ended','failed')")).isEqualTo(1L);
		assertThat(count("SELECT count(*) AS n FROM dh_operation WHERE kind = 'session_create'")).isEqualTo(1L);
		assertThat(
				count("SELECT count(*) AS n FROM dh_session WHERE preflight_id = CAST('" + preflightId + "' AS uuid)"))
				.isEqualTo(1L);
	}

	// ---------- TC105C-01-02：预检无成本 ----------

	@Test
	void tc105c_01_02_preflightCostsNothing() {
		String profileId = createProfile();
		// 有效预检 ×3（重复）。
		for (int i = 0; i < 3; i++) {
			preflight(profileId);
		}
		// 缺模型：停用 voice → 预检 409。
		db.sql("UPDATE platform_model_config SET enabled = false WHERE capability = 'voice'").then()
				.block(Duration.ofSeconds(5));
		byte[] missingModel = postJson("/api/digital-human/preflights", """
				{"profileId":"%s","profileVersion":1,"inputMode":"text","controllerId":"%s"}
				""".formatted(profileId, UUID.randomUUID()).replace("\n", ""), 409);
		assertThat(json(missingModel).path("code").asText()).isEqualTo("dh_configuration_changed");
		db.sql("UPDATE platform_model_config SET enabled = true WHERE capability = 'voice'").then()
				.block(Duration.ofSeconds(5));

		// 缺价表：render 模型改成未定价名 → 预检 409（无价不派发）。
		db.sql("UPDATE platform_model_config SET model = 'dh-render-unpriced-x'"
				+ " WHERE capability = 'digital_human_render'").then().block(Duration.ofSeconds(5));
		byte[] unpriced = postJson("/api/digital-human/preflights", """
				{"profileId":"%s","profileVersion":1,"inputMode":"text","controllerId":"%s"}
				""".formatted(profileId, UUID.randomUUID()).replace("\n", ""), 409);
		assertThat(json(unpriced).path("code").asText()).isEqualTo("dh_configuration_changed");

		// provider 调用 0（WireMock 上无任何请求）+ credits 变化 0（无 ai_run）。
		QWEN.verify(0, com.github.tomakehurst.wiremock.client.WireMock
				.postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.anyUrl()));
		assertThat(count("SELECT count(*) AS n FROM ai_run")).isZero();
	}

	// ---------- TC105C-01-03：过期与配置变更 ----------

	@Test
	void tc105c_01_03_expiredPreflightAndStaleProfile() {
		String profileId = createProfile();
		String preflightId = json(preflight(profileId)).path("data").path("id").asText();

		// Redis 丢失（TTL 过期/实例重启）→ create 410 dh_preflight_expired，无 runtime 派发。
		redis.opsForValue().delete("dh:preflight:" + preflightId).block(Duration.ofSeconds(5));
		byte[] expired = postJson("/api/digital-human/sessions", """
				{"preflightId":"%s","requestId":"%s","saveTranscript":false}
				""".formatted(preflightId, UUID.randomUUID()).replace("\n", ""), 410);
		assertThat(json(expired).path("code").asText()).isEqualTo("dh_preflight_expired");

		// profile 版本变化（PATCH 升版）→ 旧快照 create 409 dh_configuration_changed，无派发。
		String fresh = json(preflight(profileId)).path("data").path("id").asText();
		client().patch().uri("/api/digital-human/profiles/" + profileId)
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"name":"改名角色","persona":"新的人设","greeting":"你好","tone":"natural","avatarId":"%s",
						 "voiceId":"%s","catalogVersion":1,"expectedVersion":1,"requestId":"%s"}
						""".formatted(AVATAR_ID, VOICE_ID, UUID.randomUUID()).replace("\n", "")).exchange()
				.expectStatus().isEqualTo(200);
		byte[] stale = postJson("/api/digital-human/sessions", """
				{"preflightId":"%s","requestId":"%s","saveTranscript":false}
				""".formatted(fresh, UUID.randomUUID()).replace("\n", ""), 409);
		assertThat(json(stale).path("code").asText()).isEqualTo("dh_configuration_changed");
		assertThat(RUNTIME_CREATES.get()).as("过期/失效预检零派发").isEqualTo(0);
		assertThat(count("SELECT count(*) AS n FROM dh_session")).isZero();
	}

	// ---------- TC105C-01-04：全局/个人容量 ----------

	@Test
	void tc105c_01_04_capacityBoundedQueueAnd429() {
		// 第一个账号：占满 maxSessionsGlobal=1（preparing→connecting 占槽）。
		String first = "dh-c1-cap-" + UUID.randomUUID();
		String profileOf = createProfileFor(first);
		String preflightOf = json(preflightFor(first, profileOf)).path("data").path("id").asText();
		postJsonFor(first, "/api/digital-human/sessions", """
				{"preflightId":"%s","requestId":"%s","saveTranscript":false}
				""".formatted(preflightOf, UUID.randomUUID()).replace("\n", ""), 202);

		// 同账号再建：owner 活动唯一 → 409 dh_session_active。
		String secondPreflight = json(preflightFor(first, profileOf)).path("data").path("id").asText();
		byte[] ownerConflict = postJsonFor(first, "/api/digital-human/sessions", """
				{"preflightId":"%s","requestId":"%s","saveTranscript":false}
				""".formatted(secondPreflight, UUID.randomUUID()).replace("\n", ""), 409);
		assertThat(json(ownerConflict).path("code").asText()).isEqualTo("dh_session_active");

		// 其它账号：进入有界队列（queued 不占槽、不派发 runtime）。
		for (int i = 0; i < 10; i++) {
			String queued = "dh-c1-q" + i + "-" + UUID.randomUUID();
			String queuedProfile = createProfileFor(queued);
			String queuedPreflight = json(preflightFor(queued, queuedProfile)).path("data").path("id").asText();
			byte[] created = postJsonFor(queued, "/api/digital-human/sessions", """
					{"preflightId":"%s","requestId":"%s","saveTranscript":false}
					""".formatted(queuedPreflight, UUID.randomUUID()).replace("\n", ""), 202);
			assertThat(json(created).path("data").path("state").asText()).isEqualTo("queued");
		}
		assertThat(RUNTIME_CREATES.get()).as("queued 不派发 runtime").isEqualTo(1);

		// 队满（1 活动槽 + 10 排队）→ 第 12 场 429 dh_capacity_full + Retry-After。
		String overflow = "dh-c1-overflow-" + UUID.randomUUID();
		String overflowProfile = createProfileFor(overflow);
		String overflowPreflight = json(preflightFor(overflow, overflowProfile)).path("data").path("id").asText();
		var result = client().post().uri("/api/digital-human/sessions")
				.header("X-Grassland-Identity", sign(overflow, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"preflightId":"%s","requestId":"%s","saveTranscript":false}
						""".formatted(overflowPreflight, UUID.randomUUID()).replace("\n", "")).exchange().expectStatus()
				.isEqualTo(429).expectBody(byte[].class).returnResult();
		assertThat(result.getResponseHeaders().getFirst("Retry-After")).isNotBlank();
		assertThat(json(result.getResponseBody()).path("code").asText()).isEqualTo("dh_capacity_full");
	}

	// ---------- 多账号助手 ----------

	private String createProfileFor(String owner) {
		byte[] body = postJsonFor(owner, "/api/digital-human/profiles", """
				{"name":"会话角色","persona":"人设","greeting":"你好","tone":"natural","avatarId":"%s",
				 "voiceId":"%s","catalogVersion":1,"requestId":"%s"}
				""".formatted(AVATAR_ID, VOICE_ID, UUID.randomUUID()).replace("\n", ""), 201);
		return json(body).path("data").path("id").asText();
	}

	private byte[] preflightFor(String owner, String profileId) {
		return postJsonFor(owner, "/api/digital-human/preflights", """
				{"profileId":"%s","profileVersion":1,"inputMode":"text","controllerId":"%s"}
				""".formatted(profileId, UUID.randomUUID()).replace("\n", ""), 200);
	}

	private byte[] postJsonFor(String owner, String uri, String body, int expectedStatus) {
		return client().post().uri(uri).header("X-Grassland-Identity", sign(owner, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus()
				.isEqualTo(expectedStatus).expectBody(byte[].class).returnResult().getResponseBody();
	}

	private Long count(String sql) {
		return db.sql(sql).map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(5));
	}
}
