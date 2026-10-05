package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/**
 * API16 offer 中继 IT（任务书 #105fix-1 C105X-03 / TC105X-03-02、TC105X-03-03）。
 *
 * <p>
 * runtime 经可替换 fake transport（返回固定 answer / 可注入
 * dh_lease_stale）——中继、connectReady 单向 CAS、幂等与越权全部打真实 HTTP + 真实 DB；未配置 baseUrl 的
 * 503 fail-closed 用真实 default transport 单元级断言（空 baseUrl → 503 不假成功）。
 */
class DigitalHumanOfferIT extends IntelligenceItSupport {

	private static final String SDP = "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n";

	@TestConfiguration
	static class OfferFakeRuntime {

		static volatile boolean leaseStale = false;
		static final AtomicInteger OFFERS = new AtomicInteger();

		/**
		 * TC-C01-003 在途竞态屏障：raceMode=true 时 offer 到达 transport 后挂起（非阻塞 Sinks.One， 不占
		 * reactor 事件循环——阻塞式 await 会卡住服务器处理 end 请求），由测试在 end 提交后放行。
		 */
		static volatile boolean raceMode = false;
		static volatile java.util.concurrent.CountDownLatch OFFER_REACHED = new java.util.concurrent.CountDownLatch(1);
		static volatile reactor.core.publisher.Sinks.One<DigitalHumanRuntimeClient.RtcAnswer> RACE_ANSWER = reactor.core.publisher.Sinks
				.one();

		@Bean
		@Primary
		DigitalHumanRuntimeClient fakeRuntime() {
			DigitalHumanRuntimeClient.Transport transport = new DigitalHumanRuntimeClient.Transport() {
				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> createSession(String sessionId, String backendId,
						UUID commandId) {
					return Mono.just(new DigitalHumanRuntimeClient.RuntimeState(sessionId, "wrapper-1", 1, 1,
							"connecting", null, null, false, false));
				}

				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> state(String sessionId) {
					return Mono.just(new DigitalHumanRuntimeClient.RuntimeState(sessionId, "wrapper-1", 1, 1,
							"connecting", null, null, false, false));
				}

				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> end(String sessionId, UUID commandId,
						String reasonCode) {
					return Mono.just(new DigitalHumanRuntimeClient.RuntimeState(sessionId, "wrapper-1", 1, 1, "ended",
							null, null, false, false));
				}

				@Override
				public Mono<DigitalHumanRuntimeClient.RtcAnswer> webrtcOffer(String sessionId, UUID commandId,
						String payloadHash, long leaseEpoch, long mediaEpoch, String sdp) {
					OFFERS.incrementAndGet();
					if (raceMode) {
						// 到达即倒计数屏障，然后以非阻塞 sink 挂起 answer（事件循环不阻塞）。
						OFFER_REACHED.countDown();
						return RACE_ANSWER.asMono();
					}
					if (leaseStale) {
						return Mono.error(new com.grassland.intelligence.security.IntelligenceException(409,
								"dh_lease_stale", "会话控制权已变化。"));
					}
					return Mono.just(new DigitalHumanRuntimeClient.RtcAnswer(
							"v=0\r\no=- fake 0 0 IN IP4 127.0.0.1\r\ns=fake-answer\r\n", "answer", mediaEpoch));
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

	@Autowired
	private DatabaseClient db;

	private final String account = UUID.randomUUID().toString();
	private final String other = UUID.randomUUID().toString();

	@BeforeEach
	@AfterEach
	void cleanSessions() {
		db.sql("DELETE FROM dh_session WHERE owner_account_id IN (:a, :b)").bind("a", account).bind("b", other).then()
				.block(Duration.ofSeconds(10));
		OfferFakeRuntime.leaseStale = false;
		OfferFakeRuntime.OFFERS.set(0);
		OfferFakeRuntime.raceMode = false;
		OfferFakeRuntime.OFFER_REACHED = new java.util.concurrent.CountDownLatch(1);
		OfferFakeRuntime.RACE_ANSWER = reactor.core.publisher.Sinks.one();
	}

	private String seedConnecting() {
		String id = UUID.randomUUID().toString();
		db.sql("""
				INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,
				    backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at,
				    lease_epoch, media_epoch, lease_expires_at, last_browser_heartbeat_at)
				VALUES (CAST(:id AS uuid), :owner, gen_random_uuid(), 1, '角色', 'backend-1', gen_random_uuid(),
				    gen_random_uuid(), '{}'::jsonb, 'connecting', now(), 1, 1,
				    now() + interval '30 seconds', now())
				""").bind("id", id).bind("owner", account).then().block(Duration.ofSeconds(5));
		return id;
	}

	private String state(String sessionId) {
		return db.sql("SELECT state FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(r -> r.get("state", String.class)).one().block(Duration.ofSeconds(5));
	}

	private byte[] post(String uri, String body, String identity) {
		return client().post().uri(uri).header("X-Grassland-Identity", identity).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectBody(byte[].class).returnResult().getResponseBody();
	}

	private int status(String uri, String body, String identity) {
		return client().post().uri(uri).header("X-Grassland-Identity", identity).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().returnResult().getStatus().value();
	}

	private static String offerBody(long leaseEpoch, long mediaEpoch) {
		return "{\"requestId\":\"" + UUID.randomUUID() + "\",\"leaseEpoch\":" + leaseEpoch + ",\"mediaEpoch\":"
				+ mediaEpoch + ",\"sdp\":\"" + SDP.replace("\r\n", "\\r\\n") + "\",\"type\":\"offer\"}";
	}

	@Test
	void tc105x_03_02_relayAnswerThenConnectReadyIdempotent() throws Exception {
		String sessionId = seedConnecting();
		var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

		// 首次 offer：200，sdp/mediaEpoch 透传、iceServers=[]（未配 TURN）。
		byte[] first = post("/api/digital-human/sessions/" + sessionId + "/webrtc/offer", offerBody(1, 1),
				sign(account, null));
		var body = mapper.readTree(first);
		assertThat(body.path("data").path("type").asText()).isEqualTo("answer");
		assertThat(body.path("data").path("sdp").asText()).contains("fake-answer");
		assertThat(body.path("data").path("mediaEpoch").asLong()).isEqualTo(1);
		assertThat(body.path("data").path("iceServers").isArray()).isTrue();
		assertThat(body.path("data").path("iceServers").size()).isZero();
		assertThat(state(sessionId)).isEqualTo("ready");

		// 二次 offer（已 ready）：幂等 200，CAS 不再写（ready 保持）。
		byte[] second = post("/api/digital-human/sessions/" + sessionId + "/webrtc/offer", offerBody(1, 1),
				sign(account, null));
		assertThat(mapper.readTree(second).path("data").path("type").asText()).isEqualTo("answer");
		assertThat(state(sessionId)).isEqualTo("ready");

		// end 先行（SQL 直改终态）后再 offer：409 dh_state_conflict，不返回 answer。
		db.sql("UPDATE dh_session SET state='ended', ended_at=now(), version=version+1 WHERE id=CAST(:id AS uuid)")
				.bind("id", sessionId).then().block(Duration.ofSeconds(5));
		int afterEnd = status("/api/digital-human/sessions/" + sessionId + "/webrtc/offer", offerBody(1, 1),
				sign(account, null));
		assertThat(afterEnd).isEqualTo(409);

		// runtime 端 dh_lease_stale → 透传 409（answer 不落、状态不变）。
		String another = seedConnecting();
		OfferFakeRuntime.leaseStale = true;
		assertThat(status("/api/digital-human/sessions/" + another + "/webrtc/offer", offerBody(1, 1),
				sign(account, null))).isEqualTo(409);
		assertThat(state(another)).isEqualTo("connecting");

		// connectReady 行不存在 → 404（不暴露存在性）。
		String ghost = UUID.randomUUID().toString();
		assertThat(
				status("/api/digital-human/sessions/" + ghost + "/webrtc/offer", offerBody(1, 1), sign(account, null)))
				.isEqualTo(404);
	}

	@Test
	void tc105x_03_03_authCrossAccountAndUnreachableFailClosed() {
		// 未登录 → 401。
		String sessionId = seedConnecting();
		assertThat(status("/api/digital-human/sessions/" + sessionId + "/webrtc/offer", offerBody(1, 1), "bogus"))
				.isEqualTo(401);

		// B 访问 A 会话 → 404（不暴露存在性）。
		assertThat(status("/api/digital-human/sessions/" + sessionId + "/webrtc/offer", offerBody(1, 1),
				sign(other, null))).isEqualTo(404);

		// runtime 未配置（空 baseUrl default transport）→ 503
		// dh_runtime_unavailable，不假成功（E04）。
		DigitalHumanRuntimeClient unconfigured = new DigitalHumanRuntimeClient("", "", "", "", "");
		var error = org.assertj.core.api.Assertions
				.catchThrowable(() -> unconfigured.webrtcOffer(sessionId, 1, 1, SDP).block(Duration.ofSeconds(5)));
		assertThat(error).isInstanceOfSatisfying(com.grassland.intelligence.security.IntelligenceException.class,
				e -> assertThat(e.status()).isEqualTo(503));
	}

	// ---------- 任务书 105-fix-2 C-01：TC-C01-001～004（RULE-001 状态闸） ----------

	/** 行快照（逐字段对照不变）+ 该 session 事件计数。 */
	private record RowSnapshot(String state, int version, long leaseEpoch, long mediaEpoch, long events) {
	}

	/** 各状态单独会话；字段集合沿 seedConnecting（ended/failed 另写 ended_at）。 */
	private String seedState(String owner, String state) {
		String id = UUID.randomUUID().toString();
		boolean terminal = "ended".equals(state) || "failed".equals(state);
		var spec = db.sql("""
				INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,
				    backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at,
				    lease_epoch, media_epoch, lease_expires_at, last_browser_heartbeat_at, ended_at)
				VALUES (CAST(:id AS uuid), :owner, gen_random_uuid(), 1, '角色', 'backend-1', gen_random_uuid(),
				    gen_random_uuid(), '{}'::jsonb, :state, now(), 1, 1,
				    now() + interval '30 seconds', now(), :endedAt)
				""").bind("id", id).bind("owner", owner).bind("state", state);
		if (terminal) {
			spec = spec.bind("endedAt", java.time.OffsetDateTime.now());
		} else {
			spec = spec.bindNull("endedAt", java.time.OffsetDateTime.class);
		}
		spec.then().block(Duration.ofSeconds(5));
		return id;
	}

	private String seedState(String state) {
		return seedState(account, state);
	}

	private RowSnapshot snapshot(String sessionId) {
		return db.sql("""
				SELECT s.state, s.version, s.lease_epoch, s.media_epoch,
				       (SELECT count(*) FROM dh_event e WHERE e.session_id = s.id) AS events
				FROM dh_session s WHERE s.id = CAST(:id AS uuid)
				""").bind("id", sessionId)
				.map(r -> new RowSnapshot(r.get("state", String.class), r.get("version", Integer.class),
						r.get("lease_epoch", Long.class), r.get("media_epoch", Long.class),
						r.get("events", Long.class)))
				.one().block(Duration.ofSeconds(5));
	}

	private record PostResult(int status, byte[] body) {
	}

	private PostResult postFull(String uri, String body, String identity) {
		var result = client().post().uri(uri).header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectBody(byte[].class)
				.returnResult();
		return new PostResult(result.getStatus().value(), result.getResponseBody());
	}

	private static String codeOf(byte[] body) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("code").asText();
		} catch (Exception failure) {
			throw new IllegalStateException(failure);
		}
	}

	private static String errorOf(byte[] body) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("error").asText();
		} catch (Exception failure) {
			throw new IllegalStateException(failure);
		}
	}

	/** TC-C01-001：完整状态闸矩阵（逐状态单独会话，仅状态这一条件变化）。 */
	@Test
	void tc105f2_c01_001_stateGateRejectionMatrixThenPositiveControls() {
		var expectedCodes = new java.util.LinkedHashMap<String, String>();
		expectedCodes.put("queued", "dh_session_queued");
		expectedCodes.put("preparing", "dh_session_queued");
		expectedCodes.put("ending", "dh_state_conflict");
		expectedCodes.put("paused", "dh_state_conflict");
		expectedCodes.put("reconnecting", "dh_state_conflict");
		expectedCodes.put("listening", "dh_state_conflict");
		expectedCodes.put("responding", "dh_state_conflict");
		// 终态由 grants 先拒（105-fix-2 前既有行为；只测 ended 不构成新闸证据）。
		expectedCodes.put("ended", "dh_state_conflict");
		expectedCodes.put("failed", "dh_state_conflict");

		for (var entry : expectedCodes.entrySet()) {
			String sessionId = seedState(entry.getKey());
			RowSnapshot before = snapshot(sessionId);
			PostResult result = postFull("/api/digital-human/sessions/" + sessionId + "/webrtc/offer", offerBody(1, 1),
					sign(account, null));
			assertThat(result.status()).as("state=%s HTTP", entry.getKey()).isEqualTo(409);
			assertThat(codeOf(result.body())).as("state=%s code", entry.getKey()).isEqualTo(entry.getValue());
			if ("queued".equals(entry.getKey()) || "preparing".equals(entry.getKey())) {
				assertThat(errorOf(result.body())).isEqualTo("会话正在排队等待空闲名额，请稍候。");
			} else if ("ended".equals(entry.getKey()) || "failed".equals(entry.getKey())) {
				assertThat(errorOf(result.body())).isEqualTo("会话已结束。");
			} else {
				assertThat(errorOf(result.body())).isEqualTo("会话状态已变化。");
			}
			assertThat(snapshot(sessionId)).as("state=%s 行/事件不变", entry.getKey()).isEqualTo(before);
			assertThat(OfferFakeRuntime.OFFERS.get()).as("state=%s 拒绝零 runtime", entry.getKey()).isZero();
			// 单 owner 仅一行非终态：断言后即清，避免唯一索引挡下一状态。
			db.sql("DELETE FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId).then()
					.block(Duration.ofSeconds(5));
		}

		// 正对照：connecting/ready 放行并完整中继成功。
		String connecting = seedState("connecting");
		PostResult ok = postFull("/api/digital-human/sessions/" + connecting + "/webrtc/offer", offerBody(1, 1),
				sign(account, null));
		assertThat(ok.status()).isEqualTo(200);
		assertThat(codeOf(ok.body())).isEmpty();
		assertThat(state(connecting)).isEqualTo("ready");
		assertThat(OfferFakeRuntime.OFFERS.get()).isEqualTo(1);
		// owner 活动唯一：先清上一行（已 ready），再种 ready 对照。
		db.sql("DELETE FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", connecting).then()
				.block(Duration.ofSeconds(5));

		String ready = seedState("ready");
		PostResult okReady = postFull("/api/digital-human/sessions/" + ready + "/webrtc/offer", offerBody(1, 1),
				sign(account, null));
		assertThat(okReady.status()).isEqualTo(200);
		assertThat(state(ready)).isEqualTo("ready");
		assertThat(OfferFakeRuntime.OFFERS.get()).isEqualTo(2);
	}

	/** TC-C01-002：身份与 epoch 优先级（B 的 queued；四组单因子 + 终态旧 epoch 对照）。 */
	@Test
	void tc105f2_c01_002_authAndEpochPriorityOnQueued() {
		String queuedId = seedState(other, "queued");
		RowSnapshot beforeGroups = snapshot(queuedId);

		// ① A 的签名访问 B 会话 → 404 dh_not_found。
		PostResult cross = postFull("/api/digital-human/sessions/" + queuedId + "/webrtc/offer", offerBody(1, 1),
				sign(account, null));
		assertThat(cross.status()).isEqualTo(404);
		assertThat(codeOf(cross.body())).isEqualTo("dh_not_found");

		// ② 无身份 → 401 dh_auth_required。
		PostResult anon = postFull("/api/digital-human/sessions/" + queuedId + "/webrtc/offer", offerBody(1, 1),
				"bogus");
		assertThat(anon.status()).isEqualTo(401);
		assertThat(codeOf(anon.body())).isEqualTo("dh_auth_required");

		// ③ 正确 B + epoch 0 → 409 dh_lease_stale（grants 旧租约先于状态闸）。
		PostResult stale = postFull("/api/digital-human/sessions/" + queuedId + "/webrtc/offer", offerBody(0, 1),
				sign(other, null));
		assertThat(stale.status()).isEqualTo(409);
		assertThat(codeOf(stale.body())).isEqualTo("dh_lease_stale");

		// ④ 正确 B + 当前 epoch 1 → 409 dh_session_queued（新闸）。
		PostResult queued = postFull("/api/digital-human/sessions/" + queuedId + "/webrtc/offer", offerBody(1, 1),
				sign(other, null));
		assertThat(queued.status()).isEqualTo(409);
		assertThat(codeOf(queued.body())).isEqualTo("dh_session_queued");
		assertThat(errorOf(queued.body())).isEqualTo("会话正在排队等待空闲名额，请稍候。");

		// 全组 runtime 零调用；B 行无状态/事件/费用变化；A 名下无行。
		assertThat(OfferFakeRuntime.OFFERS.get()).isZero();
		assertThat(snapshot(queuedId)).as("四组后 B 行/事件不变").isEqualTo(beforeGroups);
		assertThat(db.sql("SELECT count(*) AS n FROM dh_session WHERE owner_account_id = :o").bind("o", account)
				.map(r -> r.get("n", Long.class)).one().block(Duration.ofSeconds(5))).isEqualTo(0L);

		// ⑤ B 已 ended 行 + 旧 epoch：原 terminal 优先（grants），仍 dh_state_conflict「会话已结束。」。
		db.sql("DELETE FROM dh_session WHERE owner_account_id = :o").bind("o", other).then()
				.block(Duration.ofSeconds(5));
		String endedId = seedState(other, "ended");
		PostResult terminal = postFull("/api/digital-human/sessions/" + endedId + "/webrtc/offer", offerBody(0, 1),
				sign(other, null));
		assertThat(terminal.status()).isEqualTo(409);
		assertThat(codeOf(terminal.body())).isEqualTo("dh_state_conflict");
		assertThat(errorOf(terminal.body())).isEqualTo("会话已结束。");
		assertThat(OfferFakeRuntime.OFFERS.get()).isZero();
	}

	/** TC-C01-003：正常中继 ready_at 幂等 + 在途 end 竞态 + runtime 拒绝透传。 */
	@Test
	void tc105f2_c01_003_relayReadyAtIdempotentAndInFlightEndRace() throws Exception {
		var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

		// (a) connecting → 两次 offer：200 完整 answer、no-store；ready_at 只首次设置。
		String sessionId = seedState("connecting");
		var firstExchange = client().post().uri("/api/digital-human/sessions/" + sessionId + "/webrtc/offer")
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(offerBody(1, 1)).exchange();
		firstExchange.expectHeader().value("Cache-Control", v -> assertThat(v).contains("no-store"));
		byte[] first = firstExchange.expectBody(byte[].class).returnResult().getResponseBody();
		assertThat(mapper.readTree(first).path("data").path("type").asText()).isEqualTo("answer");
		assertThat(mapper.readTree(first).path("data").path("sdp").asText()).contains("fake-answer");
		assertThat(mapper.readTree(first).path("data").path("mediaEpoch").asLong()).isEqualTo(1);
		var readyAtFirst = readyAt(sessionId);
		assertThat(readyAtFirst).isNotNull();

		byte[] second = post("/api/digital-human/sessions/" + sessionId + "/webrtc/offer", offerBody(1, 1),
				sign(account, null));
		assertThat(mapper.readTree(second).path("data").path("type").asText()).isEqualTo("answer");
		assertThat(readyAt(sessionId)).isEqualTo(readyAtFirst);
		assertThat(OfferFakeRuntime.OFFERS.get()).isEqualTo(2);

		// (b) 屏障竞态：offer 已到达 transport（仍 connecting）后，另一真实 end 操作提交，再放行 answer
		// ——不得返回成功 answer、不复活 ended。
		OfferFakeRuntime.raceMode = true;
		var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
		try {
			var pending = pool.submit(() -> postFull("/api/digital-human/sessions/" + sessionId + "/webrtc/offer",
					offerBody(1, 1), sign(account, null)));
			long tBarrier = System.nanoTime();
			assertThat(OfferFakeRuntime.OFFER_REACHED.await(5, java.util.concurrent.TimeUnit.SECONDS))
					.as("屏障必须证明 offer 已到达 transport").isTrue();
			byte[] endBody = post("/api/digital-human/sessions/" + sessionId + "/end",
					"{\"requestId\":\"" + UUID.randomUUID() + "\",\"reason\":\"user\"}", sign(account, null));
			assertThat(codeOf(endBody)).isEmpty();
			assertThat(state(sessionId)).isEqualTo("ended");
			long endMs = (System.nanoTime() - tBarrier) / 1_000_000;

			// end 已提交（ended）后放行挂起的 answer：sink 非阻塞，事件循环不被占用。
			var emitResult = OfferFakeRuntime.RACE_ANSWER.tryEmitValue(new DigitalHumanRuntimeClient.RtcAnswer(
					"v=0\r\no=- fake 0 0 IN IP4 127.0.0.1\r\ns=fake-answer\r\n", "answer", 1));
			assertThat(emitResult.isSuccess()).as("挂起 answer 必须成功放行").isTrue();
			PostResult raced = pending.get(15, java.util.concurrent.TimeUnit.SECONDS);
			assertThat(raced.status()).as("end 耗时=%sms，竞态响应 body=%s", endMs,
					raced.body() == null ? "null" : new String(raced.body(), java.nio.charset.StandardCharsets.UTF_8))
					.isEqualTo(409);
			assertThat(codeOf(raced.body())).isEqualTo("dh_state_conflict");
			assertThat(state(sessionId)).isEqualTo("ended");
		} finally {
			OfferFakeRuntime.raceMode = false;
			OfferFakeRuntime.RACE_ANSWER.tryEmitValue(new DigitalHumanRuntimeClient.RtcAnswer("v=0", "answer", 1));
			pool.shutdownNow();
		}

		// (c) runtime 端 dh_lease_stale → 透传 409 且不改 DB（offer 到达 transport 属允许路径）。
		String another = seedState("connecting");
		RowSnapshot beforePassthrough = snapshot(another);
		OfferFakeRuntime.leaseStale = true;
		try {
			PostResult passthrough = postFull("/api/digital-human/sessions/" + another + "/webrtc/offer",
					offerBody(1, 1), sign(account, null));
			assertThat(passthrough.status()).isEqualTo(409);
			assertThat(codeOf(passthrough.body())).isEqualTo("dh_lease_stale");
			assertThat(snapshot(another)).as("runtime 拒绝透传不改 DB").isEqualTo(beforePassthrough);
		} finally {
			OfferFakeRuntime.leaseStale = false;
		}
	}

	private java.time.OffsetDateTime readyAt(String sessionId) {
		return db.sql("SELECT ready_at FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(r -> r.get("ready_at", java.time.OffsetDateTime.class)).one().block(Duration.ofSeconds(5));
	}

	/** TC-C01-004：形状校验先于排队 409（422 零 runtime）+ 机器契约同步真实断言。 */
	@Test
	void tc105f2_c01_004_shapeValidationBeforeGateAndMachineContractSync() throws Exception {
		String queuedId = seedState("queued");
		String sd = SDP.replace("\r\n", "\\r\\n");

		// type=answer → 422（原形状校验先于排队 409）。
		PostResult wrongType = postFull(
				"/api/digital-human/sessions/" + queuedId + "/webrtc/offer", "{\"requestId\":\"" + UUID.randomUUID()
						+ "\",\"leaseEpoch\":1,\"mediaEpoch\":1,\"sdp\":\"" + sd + "\",\"type\":\"answer\"}",
				sign(account, null));
		assertThat(wrongType.status()).isEqualTo(422);
		assertThat(codeOf(wrongType.body())).isEqualTo("dh_invalid_input");

		// sdp 空串 → 422。
		PostResult blankSdp = postFull(
				"/api/digital-human/sessions/" + queuedId + "/webrtc/offer", "{\"requestId\":\"" + UUID.randomUUID()
						+ "\",\"leaseEpoch\":1,\"mediaEpoch\":1,\"sdp\":\"\"," + "\"type\":\"offer\"}",
				sign(account, null));
		assertThat(blankSdp.status()).isEqualTo(422);
		assertThat(codeOf(blankSdp.body())).isEqualTo("dh_invalid_input");

		// 未知字段（K00 拒）→ 422。
		PostResult unknownField = postFull("/api/digital-human/sessions/" + queuedId + "/webrtc/offer",
				"{\"requestId\":\"" + UUID.randomUUID() + "\",\"leaseEpoch\":1,\"mediaEpoch\":1,\"sdp\":\"" + sd
						+ "\",\"type\":\"offer\",\"orgId\":\"o-1\"}",
				sign(account, null));
		assertThat(unknownField.status()).isEqualTo(422);
		assertThat(codeOf(unknownField.body())).isEqualTo("dh_invalid_input");

		assertThat(OfferFakeRuntime.OFFERS.get()).as("形状拒绝零 runtime").isZero();
		assertThat(state(queuedId)).isEqualTo("queued");

		// 机器契约：加载 W21/W22，断言新码三处同步 + 端点数量不变 + 合成样例合法。
		java.nio.file.Path repoRoot = java.nio.file.Paths.get(System.getProperty("user.dir"));
		while (repoRoot != null && !java.nio.file.Files.exists(repoRoot.resolve("contracts/digital-human.v1.json"))) {
			repoRoot = repoRoot.getParent();
		}
		assertThat(repoRoot).as("必须能定位仓库根 contracts/").isNotNull();
		var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		var contract = mapper.readTree(repoRoot.resolve("contracts/digital-human.v1.json").toFile());
		assertThat(contract.path("definitions").path("ErrorCode").path("enum").toString())
				.contains("dh_session_queued");
		boolean registered = false;
		for (var err : contract.path("errors")) {
			if ("dh_session_queued".equals(err.path("code").asText())) {
				registered = true;
				assertThat(err.path("http").asInt()).isEqualTo(409);
			}
		}
		assertThat(registered).as("errors 注册表缺 dh_session_queued").isTrue();
		var api16 = java.util.stream.StreamSupport.stream(contract.path("endpoints").spliterator(), false)
				.filter(e -> "API16".equals(e.path("id").asText())).findFirst().orElseThrow();
		assertThat(api16.path("errors").toString()).contains("dh_session_queued");
		assertThat(contract.path("endpoints").size()).as("端点数量不变").isEqualTo(59);

		var examples = mapper.readTree(repoRoot.resolve("contracts/digital-human.v1.examples.json").toFile());
		var queuedSample = java.util.stream.StreamSupport.stream(examples.path("valid").spliterator(), false)
				.filter(v -> "error-envelope-session-queued".equals(v.path("name").asText())).findFirst().orElseThrow();
		assertThat(queuedSample.path("payload").path("code").asText()).isEqualTo("dh_session_queued");
		assertThat(queuedSample.path("payload").path("error").asText()).isEqualTo("会话正在排队等待空闲名额，请稍候。");
	}
}
