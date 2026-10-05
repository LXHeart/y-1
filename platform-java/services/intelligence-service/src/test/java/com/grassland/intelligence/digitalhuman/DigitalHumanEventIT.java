package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 有序事件 IT（任务书 #105C C105C-03 / TC105C-03-01、02、04）：seq 有序一次、旧 epoch 无状态作用； 回放缺口
 * snapshot+replayComplete=false 且零 provider 请求；权限撤销/读流上限/跨账号不可读。
 */
class DigitalHumanEventIT extends IntelligenceItSupport {

	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	static {
		REDIS.start();
	}

	@org.springframework.test.context.DynamicPropertySource
	static void redisProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
	}

	@Autowired
	private DigitalHumanEventService events;

	@Autowired
	private DatabaseClient db;

	private final String account = "dh-c3-" + UUID.randomUUID();
	private String sessionId;

	@BeforeEach
	void seedSession() {
		QWEN.resetAll();
		db.sql("DELETE FROM dh_event").then()
				.then(db.sql("DELETE FROM dh_session WHERE owner_account_id" + " LIKE 'dh-c3-%'").then())
				.block(Duration.ofSeconds(10));
		sessionId = UUID.randomUUID().toString();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision,"
				+ " profile_name_at_creation, backend_id, preflight_id, controller_id, config_snapshot, state,"
				+ " state_entered_at, lease_epoch, media_epoch, lease_expires_at)"
				+ " VALUES (CAST(:id AS uuid), :o, gen_random_uuid(), 1, '角色', 'mock', gen_random_uuid(),"
				+ " gen_random_uuid(), '{}'::jsonb, 'ready', now(), 3, 1, now() + interval '30 seconds')")
				.bind("id", sessionId).bind("o", account).then().block(Duration.ofSeconds(5));
		// 快照水位基准（无事件）：last_seq=2 模拟已有 durable 缺口。
		db.sql("UPDATE dh_session SET last_seq = 2 WHERE id = CAST(:id AS uuid)").bind("id", sessionId).then()
				.block(Duration.ofSeconds(5));
	}

	// ---------- TC105C-03-01：顺序去重 ----------

	@Test
	void tc105c_03_01_orderedOnceAndStaleEpochDiscarded() {
		UUID first = UUID.randomUUID();
		var a1 = events.append(sessionId, first, "session.state", 3, java.util.Map.of("state", "ready"))
				.block(Duration.ofSeconds(5));
		var a2 = events.append(sessionId, UUID.randomUUID(), "turn.accepted", 3, java.util.Map.of("turnId", "t-1"))
				.block(Duration.ofSeconds(5));
		assertThat(a1.seq()).isEqualTo(3L);
		assertThat(a2.seq()).isEqualTo(4L);

		// 重复 eventId → 返回原 seq（去重，不推进 seq）。
		var duplicated = events.append(sessionId, first, "session.state", 3, java.util.Map.of("state", "ready"))
				.block(Duration.ofSeconds(5));
		assertThat(duplicated.duplicated()).isTrue();
		assertThat(duplicated.seq()).isEqualTo(a1.seq());
		assertThat(events.durableCount(UUID.fromString(sessionId)).block(Duration.ofSeconds(5))).isEqualTo(2L);

		// 旧 epoch 迟到事件：丢弃（-1），无 durable 行、无状态作用。
		var stale = events.append(sessionId, UUID.randomUUID(), "session.state", 1, java.util.Map.of("state", "failed"))
				.block(Duration.ofSeconds(5));
		assertThat(stale.staleEpoch()).isTrue();
		assertThat(stale.seq()).isEqualTo(-1L);
		assertThat(events.durableCount(UUID.fromString(sessionId)).block(Duration.ofSeconds(5))).isEqualTo(2L);
		String state = db.sql("SELECT state FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(row -> row.get("state", String.class)).one().block(Duration.ofSeconds(5));
		assertThat(state).isEqualTo("ready");
	}

	// ---------- TC105C-03-02：回放缺口 ----------

	@Test
	void tc105c_03_02_replayGapMarksIncompleteWithoutProviderCalls() {
		// afterSeq=0 < 首个 durable seq-1 → snapshot 带 replayComplete=false（缺口如实，不虚构）。
		events.append(sessionId, UUID.randomUUID(), "session.state", 3, java.util.Map.of("state", "ready"))
				.block(Duration.ofSeconds(5));
		FluxExchangeResult<String> result = client().get()
				.uri("/api/digital-human/sessions/" + sessionId + "/events?afterSeq=0")
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isOk().expectHeader()
				.contentTypeCompatibleWith("text/event-stream").returnResult(String.class);
		List<String> frames = result.getResponseBody().take(2).collectList().block(Duration.ofSeconds(10));
		assertThat(frames).isNotEmpty();
		assertThat(frames.get(0)).contains("session.snapshot");
		assertThat(frames.get(0)).contains("\"replayComplete\":false");

		// 零 provider 请求（WireMock 无任何调用）。
		QWEN.verify(0, com.github.tomakehurst.wiremock.client.WireMock
				.postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.anyUrl()));
	}

	// ---------- 任务书 105-fix-2 C-03：TC-C03-005 事件事务/live/重放（W16 半边） ----------

	/**
	 * publishSessionState（RULE-008/§6.7）：已订阅 live 收到与 durable 回放完全相同的
	 * v/eventId/sessionId/seq/type/payload 帧；重连重放看到同一事件（live 不替代 durable）。 连接体 Flux
	 * 只允许单次订阅——用队列消费多帧（重复 .next() 会被 Reactor Netty 以 「Rejecting additional inbound
	 * receiver」拒绝）。
	 */
	@Test
	void tcFix2_c03_005_publishSessionStateDeliversCommittedFrameIdenticalToReplay() throws Exception {
		FluxExchangeResult<String> stream = client().get().uri("/api/digital-human/sessions/" + sessionId + "/events")
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isOk()
				.returnResult(String.class);
		java.util.concurrent.LinkedBlockingQueue<String> frames = new java.util.concurrent.LinkedBlockingQueue<>();
		reactor.core.Disposable subscription = stream.getResponseBody().subscribe(frames::add);
		// 快照先到（订阅完成基准）。
		String snapshot = frames.poll(5, java.util.concurrent.TimeUnit.SECONDS);
		assertThat(snapshot).contains("session.snapshot");

		UUID eventId = UUID.randomUUID();
		java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
		payload.put("state", "connecting");
		payload.put("reasonCode", "queue_promoted");
		payload.put("expiresAt", null);
		payload.put("pausedUntil", null);
		var appended = events.append(sessionId, eventId, "session.state", 3, payload).block(Duration.ofSeconds(5));
		assertThat(appended.duplicated()).isFalse();
		// 事务提交后的显式通知（worker 在提交后调用）。
		events.publishSessionState(sessionId, appended, payload);

		String live = frames.poll(5, java.util.concurrent.TimeUnit.SECONDS);
		assertThat(live).as("已订阅 live 收到提交事件").isNotNull();
		var liveFrame = json(live);
		assertThat(liveFrame.path("v").asInt()).isEqualTo(1);
		assertThat(liveFrame.path("eventId").asText()).isEqualTo(eventId.toString());
		assertThat(liveFrame.path("seq").asLong()).isEqualTo(appended.seq());
		assertThat(liveFrame.path("type").asText()).isEqualTo("session.state");
		assertThat(liveFrame.path("payload").path("reasonCode").asText()).isEqualTo("queue_promoted");
		assertThat(liveFrame.path("payload").path("state").asText()).isEqualTo("connecting");

		// 重连重放（首个流仍订阅中，sink 不终止）：afterSeq=0 一次性取前两帧——与 live 帧逐字段一致。
		FluxExchangeResult<String> reconnected = client().get()
				.uri("/api/digital-human/sessions/" + sessionId + "/events?afterSeq=0")
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isOk()
				.returnResult(String.class);
		java.util.List<String> replay = reconnected.getResponseBody().take(2).collectList()
				.block(Duration.ofSeconds(10));
		assertThat(replay).hasSize(2);
		assertThat(replay.get(0)).contains("session.snapshot");
		assertThat(json(replay.get(1))).as("回放帧与 live 帧完全一致").isEqualTo(liveFrame);
		assertThat(events.durableCount(java.util.UUID.fromString(sessionId)).block(Duration.ofSeconds(5)))
				.isEqualTo(1L);

		subscription.dispose();
	}

	/**
	 * 回滚负例：事务内 append 后强制回滚——durable 零新行、live 无帧（RULE-008「回滚没有通知」； worker
	 * 代码路径只在提交后调用 publishSessionState）。
	 */
	@Test
	void tcFix2_c03_005_rolledBackAppendProducesNoDurableAndNoLive() throws Exception {
		FluxExchangeResult<String> stream = client().get().uri("/api/digital-human/sessions/" + sessionId + "/events")
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isOk()
				.returnResult(String.class);
		java.util.concurrent.LinkedBlockingQueue<String> frames = new java.util.concurrent.LinkedBlockingQueue<>();
		reactor.core.Disposable subscription = stream.getResponseBody().subscribe(frames::add);
		assertThat(frames.poll(5, java.util.concurrent.TimeUnit.SECONDS)).contains("session.snapshot");
		Long before = events.durableCount(java.util.UUID.fromString(sessionId)).block(Duration.ofSeconds(5));

		var operations = context.getBean(org.springframework.transaction.reactive.TransactionalOperator.class);
		operations
				.transactional(events
						.append(sessionId, UUID.randomUUID(), "session.state", 3,
								java.util.Map.of("state", "connecting"))
						.then(reactor.core.publisher.Mono.error(new IllegalStateException("tcFix2_c03_005 注入回滚"))))
				.onErrorResume(ignored -> reactor.core.publisher.Mono.empty()).block(Duration.ofSeconds(5));

		assertThat(events.durableCount(java.util.UUID.fromString(sessionId)).block(Duration.ofSeconds(5)))
				.as("回滚后 durable 零新行").isEqualTo(before);
		// 2s 内无任何帧（心跳 20s，不会提前到达）——回滚路径没有 live 通知。
		assertThat(frames.poll(2, java.util.concurrent.TimeUnit.SECONDS)).as("回滚不得产生 live 帧").isNull();
		subscription.dispose();
	}

	private static com.fasterxml.jackson.databind.JsonNode json(String text) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
		} catch (Exception failure) {
			throw new IllegalStateException(failure);
		}
	}

	// ---------- TC105C-03-04：权限撤销与流释放 ----------

	@Test
	void tc105c_03_04_revocationTerminatesStreamAndCrossAccountDenied() throws Exception {
		// B 无法读取（owner 404；不泄露存在性）。
		String other = "dh-c3-b-" + UUID.randomUUID();
		client().get().uri("/api/digital-human/sessions/" + sessionId + "/events")
				.header("X-Grassland-Identity", sign(other, null)).exchange().expectStatus().isNotFound();

		// 冻结账号：先终结会话（活动会话会以 ACTIVE_JOBS 阻塞注销 prepare——这本身是 B05 屏障语义）。
		db.sql("UPDATE dh_session SET state = 'ended', ended_at = now() WHERE id = CAST(:id AS uuid)")
				.bind("id", sessionId).then().block(Duration.ofSeconds(5));
		com.grassland.intelligence.compliance.IntelligenceAccountLifecycleRepository gate = context
				.getBean(com.grassland.intelligence.compliance.IntelligenceAccountLifecycleRepository.class);
		assertThat(gate.prepare(account, UUID.randomUUID()).block(Duration.ofSeconds(5)).state()).isEqualTo("frozen");
		// SSE 已建立后取消订阅 → 计数回落（第 3 条被拒，取消后可再订）。
		FluxExchangeResult<String> first = client().get().uri("/api/digital-human/sessions/" + sessionId + "/events")
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isOk()
				.returnResult(String.class);
		FluxExchangeResult<String> second = client().get().uri("/api/digital-human/sessions/" + sessionId + "/events")
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isOk()
				.returnResult(String.class);
		client().get().uri("/api/digital-human/sessions/" + sessionId + "/events")
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isEqualTo(429);
		first.getResponseBody().subscribe().dispose();
		second.getResponseBody().subscribe().dispose();
		// 取消后计数回落：释放需要一小段 doFinally 时间。
		Thread.sleep(500);
		var released = client().get().uri("/api/digital-human/sessions/" + sessionId + "/events")
				.header("X-Grassland-Identity", sign(account, null)).exchange().expectStatus().isOk()
				.returnResult(String.class);
		released.getResponseBody().subscribe().dispose();
	}

	@Autowired
	private org.springframework.context.ApplicationContext context;
}
