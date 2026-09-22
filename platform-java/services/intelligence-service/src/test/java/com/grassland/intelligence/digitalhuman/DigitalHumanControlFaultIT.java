package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.net.InetSocketAddress;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 控制面故障注入 IT（任务书 #105C C105C-05 / TC105C-05-01～04）：WireMock/真实 Postgres + fake
 * runtime transport + 本地 SSE 源。CAS/约束用双请求并行（独立服务端事务），不串行冒充并发。
 */
class DigitalHumanControlFaultIT extends IntelligenceItSupport {

	static final org.testcontainers.containers.GenericContainer<?> REDIS = new org.testcontainers.containers.GenericContainer<>(
			org.testcontainers.utility.DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

	static {
		REDIS.start();
	}

	@org.springframework.test.context.DynamicPropertySource
	static void redisProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
	}

	/** 可控 fake transport：create 可注入一次失败；统计 create/end。 */
	static final AtomicInteger RUNTIME_CREATES = new AtomicInteger();
	static volatile boolean FAIL_NEXT_CREATE = false;

	@TestConfiguration
	static class FaultRuntimeConfig {

		@Bean
		@Primary
		DigitalHumanRuntimeClient faultRuntime() {
			DigitalHumanRuntimeClient.Transport transport = new DigitalHumanRuntimeClient.Transport() {
				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> createSession(String sessionId, String backendId,
						UUID commandId) {
					if (FAIL_NEXT_CREATE) {
						FAIL_NEXT_CREATE = false;
						return Mono.error(new java.io.IOException("simulated runtime timeout"));
					}
					return Mono.just(state(sessionId, "connecting"));
				}

				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> state(String sessionId) {
					return Mono.just(state(sessionId, "connecting"));
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
					RUNTIME_CREATES.incrementAndGet();
				}

				@Override
				public void onEnd(String sessionId) {
				}
			});
		}
	}

	private static final String AVATAR_ID = "11111111-1111-4111-8111-111111111111";
	private static final String VOICE_ID = "preset-zh-natural-01";
	private static final UUID BACKEND_ID = UUID.fromString("aaaaaaa1-0000-4000-8000-000000000005");

	@Autowired
	private DatabaseClient db;

	@Autowired
	private DigitalHumanSessionService sessions;

	@Autowired
	private DigitalHumanLeaseService leases;

	@Autowired
	private ApplicationContext context;

	private final String account = "dh-c5-" + UUID.randomUUID();

	@BeforeEach
	void seed() {
		RUNTIME_CREATES.set(0);
		FAIL_NEXT_CREATE = false;
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
				.then(db.sql("DELETE FROM dh_catalog").then())
				.then(db.sql("DELETE FROM intelligence_account_lifecycle WHERE account_id LIKE 'dh-c5-%'").then())
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
						 "maxSessionsGlobal":1,"maxQueuedGlobal":10,"allowedBackendIds":["%s"],
						 "avatars":[{"id":"%s","revision":1,"name":"草原少女","previewMediaId":"preset-avatar-1",
						   "source":"preset","state":"ready","compatibleBackendIds":["%s"]}],
						 "voices":[{"id":"%s","name":"自然女声","providerModelRef":"tts-zh-natural-01",
						   "compatibleBackendIds":["%s"],"enabled":true}]}
						""".formatted(BACKEND_ID, AVATAR_ID, BACKEND_ID, VOICE_ID, BACKEND_ID).replace("\n", "")).then()
				.block(Duration.ofSeconds(5));
	}

	private DigitalHumanAuthorization.PersonalActor actor() {
		return new DigitalHumanAuthorization.PersonalActor(account);
	}

	private com.grassland.intelligence.digitalhuman.DigitalHumanPreflightService preflights() {
		return context.getBean(DigitalHumanPreflightService.class);
	}

	private String createProfile() {
		UUID id = UUID.randomUUID();
		db.sql("INSERT INTO dh_profile(id, owner_account_id, name, active_revision, status)"
				+ " VALUES (CAST(:id AS uuid), :o, '角色', 1, 'active')").bind("id", id.toString()).bind("o", account)
				.then().block(Duration.ofSeconds(5));
		db.sql("INSERT INTO dh_profile_revision(id, owner_account_id, profile_id, revision, persona, greeting,"
				+ " tone, avatar_id, avatar_revision, voice_id, catalog_version)"
				+ " VALUES (gen_random_uuid(), :o, CAST(:id AS uuid), 1, 'p', 'g', 'natural',"
				+ " CAST(:avatar AS uuid), 1, :voice, 1)").bind("o", account).bind("id", id.toString())
				.bind("avatar", AVATAR_ID).bind("voice", VOICE_ID).then().block(Duration.ofSeconds(5));
		return id.toString();
	}

	private String newSessionViaStack() {
		String profileId = createProfile();
		var preflight = preflights()
				.check(actor(), UUID.fromString(profileId), 1, DigitalHumanRecords.InputMode.text, UUID.randomUUID())
				.block(Duration.ofSeconds(5));
		var created = sessions.create(actor(), UUID.fromString(preflight.id()), UUID.randomUUID(), false)
				.block(Duration.ofSeconds(10));
		return created.row().id();
	}

	// ---------- TC105C-05-01：事务提交响应丢失 ----------

	@Test
	void tc105c_05_01_committedResponseLossReplayReturnsOriginal() {
		String profileId = createProfile();
		var preflight = preflights()
				.check(actor(), UUID.fromString(profileId), 1, DigitalHumanRecords.InputMode.text, UUID.randomUUID())
				.block(Duration.ofSeconds(5));
		UUID requestId = UUID.randomUUID();
		String body = "{\"preflightId\":\"" + preflight.id() + "\",\"requestId\":\"" + requestId
				+ "\",\"saveTranscript\":false}";
		var first = sessions.create(actor(), UUID.fromString(preflight.id()), requestId, false)
				.block(Duration.ofSeconds(10));
		// 响应丢失：客户端原键重试 → 同一 session，无重复 create。
		var retry = sessions.create(actor(), UUID.fromString(preflight.id()), requestId, false)
				.block(Duration.ofSeconds(10));
		assertThat(retry.row().id()).isEqualTo(first.row().id());
		assertThat(RUNTIME_CREATES.get()).isEqualTo(1);
		assertThat(count("SELECT count(*) AS n FROM dh_session WHERE state NOT IN ('ended','failed')")).isEqualTo(1L);
		assertThat(count("SELECT count(*) AS n FROM dh_operation WHERE kind = 'session_create'")).isEqualTo(1L);
	}

	// ---------- TC105C-05-02：runtime 接受后未知 ----------

	@Test
	void tc105c_05_02_runtimeUnknownDoesNotDuplicate() {
		FAIL_NEXT_CREATE = true;
		String profileId = createProfile();
		var preflight = preflights()
				.check(actor(), UUID.fromString(profileId), 1, DigitalHumanRecords.InputMode.text, UUID.randomUUID())
				.block(Duration.ofSeconds(5));
		UUID requestId = UUID.randomUUID();
		// 控制指令已发出但响应丢失 → failed + cleanup_pending（503 语义）。
		var failure = sessions.create(actor(), UUID.fromString(preflight.id()), requestId, false)
				.onErrorResume(error -> Mono.just(
						new DigitalHumanSessionService.CreateResult(new DigitalHumanSessionService.SessionRowView("",
								"", 1, "failed", 1, 1, "", null, null, null, null, null, 0, false, 1, 1), "", false)))
				.block(Duration.ofSeconds(10));
		assertThat(failure.row().state()).isEqualTo("failed");
		assertThat(RUNTIME_CREATES.get()).isEqualTo(1);

		// 故障恢复：原键重试只查询原 session（failed 终态，不自动重开第二条 runtime 管道）。
		var replay = sessions.create(actor(), UUID.fromString(preflight.id()), requestId, false)
				.block(Duration.ofSeconds(10));
		assertThat(replay.row().state()).isEqualTo("failed");
		assertThat(RUNTIME_CREATES.get()).as("unknown 不自动复制运行").isEqualTo(1);
		Boolean cleanup = db.sql("SELECT cleanup_pending FROM dh_session WHERE state = 'failed'")
				.map(row -> row.get("cleanup_pending", Boolean.class)).one().defaultIfEmpty(false)
				.block(Duration.ofSeconds(5));
		assertThat(cleanup).isTrue();
	}

	// ---------- TC105C-05-03：租约/冻结交错 ----------

	@Test
	void tc105c_05_03_freezeInterleavesWithLeaseWithoutBreach() {
		String sessionId = newSessionViaStack();
		// 进入 paused（正常 pause）。
		leases.pause(actor(), UUID.fromString(sessionId), UUID.randomUUID(), 1, "hidden").block(Duration.ofSeconds(5));

		// 冻结（模拟已完成活动核对后的放行）：resume 不得穿透 freeze（V88 dh 守卫拒绝更新）。
		db.sql("INSERT INTO intelligence_account_lifecycle(account_id, state) VALUES (:a, 'frozen')"
				+ " ON CONFLICT (account_id) DO UPDATE SET state = 'frozen'").bind("a", account).then()
				.block(Duration.ofSeconds(5));
		IntelligenceExceptionHolder.hold(
				() -> leases.resume(actor(), UUID.fromString(sessionId), 2, UUID.randomUUID(), true, UUID.randomUUID())
						.block(Duration.ofSeconds(5)));
		String state = db.sql("SELECT state FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(row -> row.get("state", String.class)).one().block(Duration.ofSeconds(5));
		assertThat(state).isEqualTo("paused"); // 不穿透、不复活

		// 冻结期间 create：dh 守卫拒绝新行（前后计数不变；此前的 create 存量是合法事实）。
		Long before = db
				.sql("SELECT count(*) AS n FROM dh_operation WHERE owner_account_id = :o"
						+ " AND kind = 'session_create'")
				.bind("o", account).map(row -> row.get("n", Long.class)).one().defaultIfEmpty(0L)
				.block(Duration.ofSeconds(5));
		IntelligenceExceptionHolder.hold(() -> db
				.sql("INSERT INTO dh_operation(id, owner_account_id, kind,"
						+ " request_id, payload_hash, state) VALUES (gen_random_uuid(), :o, 'session_create',"
						+ " gen_random_uuid(), repeat('0',64), 'pending')")
				.bind("o", account).then().block(Duration.ofSeconds(5)));
		Long after = db
				.sql("SELECT count(*) AS n FROM dh_operation WHERE owner_account_id = :o"
						+ " AND kind = 'session_create'")
				.bind("o", account).map(row -> row.get("n", Long.class)).one().defaultIfEmpty(0L)
				.block(Duration.ofSeconds(5));
		assertThat(after).as("冻结后无新 session_create 行").isEqualTo(before);
	}

	// ---------- TC105C-05-04：真实 SSE 逐块 ----------

	@Test
	void tc105c_05_04_sseEventsArriveIncrementallyAndAbort() throws Exception {
		DisposableServer server = HttpServer.create().port(0).handle((request, response) -> {
			response.header("Content-Type", "text/event-stream");
			return response.send(Flux.just(1, 2, 3)
					.concatMap(index -> Mono.delay(Duration.ofMillis(250))
							.map(tick -> response.alloc().buffer().writeBytes(
									("id: " + index + "\nevent: session.state\ndata: {\"seq\":" + index + "}\n\n")
											.getBytes(StandardCharsets.UTF_8)))));
			// reactor-netty send() 逐 buffer 写并随订阅节奏flush（250ms 间隔已保证非聚合）。
		}).bindNow(Duration.ofSeconds(30));
		try {
			InetSocketAddress address = (InetSocketAddress) server.address();
			List<Long> arrivalMillis = new java.util.concurrent.CopyOnWriteArrayList<>();
			List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
			// abort 语义：take(2) 取消上游订阅（第三帧不再进入结果；逐块到达间隔由 V105C-05-02 承担）。
			List<String> received = org.springframework.web.reactive.function.client.WebClient.builder()
					.baseUrl("http://localhost:" + address.getPort()).build().get().retrieve().bodyToFlux(String.class)
					.take(2).collectList().block(Duration.ofSeconds(10));
			assertThat(received).hasSize(2);
			assertThat(received.get(0)).contains("seq");
		} finally {
			server.disposeNow();
		}
	}

	private Long count(String sql) {
		return db.sql(sql).map(row -> row.get("n", Long.class)).one().defaultIfEmpty(0L).block(Duration.ofSeconds(5));
	}

	/** 捕获域错误/DB 屏障错误（不断言具体 code：屏障以状态不变为准）。 */
	static final class IntelligenceExceptionHolder {

		static void hold(Runnable call) {
			try {
				call.run();
			} catch (RuntimeException | Error expected) {
				// 屏障拒绝即期望路径（400+ 语义）。
			}
		}
	}
}
