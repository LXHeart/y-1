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
}
