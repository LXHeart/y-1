package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 晋升 worker IT（任务书 105-fix-2 C-03 / TC-C03-002～005 + TC-C02-004 跨晋升子场景）。
 *
 * <p>
 * 基座 {@code promotion.enabled=false}（IntelligenceItSupport）→ 除 SSE/真实 create
 * 外均直调 {@code runOnce} 控制轮次；调度真实驱动（enabled=true+250ms、零直调）归
 * DigitalHumanWorkerSchedulingIT。fake 只替换 RuntimeClient.Transport：按 sessionId
 * 脚本化 create/state 回报并记录计数；屏障用
 * {@code Sinks.One}/{@code CountDownLatch}（非阻塞，不用任意 sleep 碰运气）。HTTP/鉴权/PG/锁/V88
 * 触发器全部真实；Redis 仅 TC-C03-002 真实 create 的预检快照。 共享容器跨类自愈：BeforeEach 清 dh_* 共享表（照
 * ReaperTest 范式）。
 */
class DigitalHumanPromotionIT extends IntelligenceItSupport {

	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	static {
		REDIS.start();
	}

	@DynamicPropertySource
	static void redisProps(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
	}

	// ---------- fake transport（§12.2 F-J：只替换 Transport；状态机 + 非阻塞屏障） ----------

	/**
	 * create
	 * 计划：MATCH_CONNECTING=受理；EXPLICIT_503=入口明确拒绝；HANG_ACCEPTED=远端已受理但响应丢失；HANG_UNKNOWN=未知。
	 */
	private enum CreatePlan {
		MATCH_CONNECTING, EXPLICIT_503, HANG_ACCEPTED, HANG_UNKNOWN, TERMINAL_ENDED
	}

	/** state 计划：FROM_STORE=按已保存实例回报（无则精确 404）；GONE_404=精确 404；HANG=未知。 */
	private enum StatePlan {
		FROM_STORE, GONE_404, HANG
	}

	private static final class FakeRuntime implements DigitalHumanRuntimeClient.Transport {

		final Map<String, CreatePlan> createPlan = new ConcurrentHashMap<>();
		final Map<String, StatePlan> statePlan = new ConcurrentHashMap<>();
		/** 远端实例表：create 受理/HANG_ACCEPTED 先保存，state 按它回报（重建 worker 仍复用）。 */
		final Map<String, DigitalHumanRuntimeClient.RuntimeState> store = new ConcurrentHashMap<>();
		final Map<String, AtomicInteger> creates = new ConcurrentHashMap<>();
		final Map<String, AtomicInteger> states = new ConcurrentHashMap<>();
		final Map<String, AtomicInteger> ends = new ConcurrentHashMap<>();
		/**
		 * TC-C02-004/TC-C03-005 屏障：create 到达 transport 即倒计数并挂起回报（非阻塞）。 非 final：reset
		 * 重建——CountDownLatch 无法回卷，跨测试残留 0 会让 await() 秒过， 主线程抢在 claim/casSent
		 * 提交之前动作（首次踩坑：end 读不到 sent 标记走了旧分支）。
		 */
		CountDownLatch createReached = new CountDownLatch(1);
		// 非 final：reset 必须换新 sink——Sinks.One 一旦 emit 即终止，复用会让后续 asMono()
		// 立刻拿到旧值（首次踩坑：reset 占位值让 HANG 计划瞬间返回，测试误判 binding 不匹配）。
		Sinks.One<DigitalHumanRuntimeClient.RuntimeState> heldCreate = Sinks.one();
		volatile boolean holdCreate = false;

		private AtomicInteger counter(Map<String, AtomicInteger> map, String sessionId) {
			return map.computeIfAbsent(sessionId, key -> new AtomicInteger());
		}

		int creates(String sessionId) {
			AtomicInteger value = creates.get(sessionId);
			return value == null ? 0 : value.get();
		}

		int states(String sessionId) {
			AtomicInteger value = states.get(sessionId);
			return value == null ? 0 : value.get();
		}

		int ends(String sessionId) {
			AtomicInteger value = ends.get(sessionId);
			return value == null ? 0 : value.get();
		}

		void reset() {
			createPlan.clear();
			statePlan.clear();
			store.clear();
			creates.clear();
			states.clear();
			ends.clear();
			rearm();
		}

		/** 同一测试内的子用例隔离：重建挂起屏障（Sinks.One 终态后复用会立即返回旧值）。 */
		void rearm() {
			heldCreate = Sinks.one();
			createReached = new CountDownLatch(1);
		}

		private DigitalHumanRuntimeClient.RuntimeState matching(String sessionId, String backendId) {
			return new DigitalHumanRuntimeClient.RuntimeState(sessionId, backendId, 1, 1, "connecting", null, null,
					false, false);
		}

		@Override
		public Mono<DigitalHumanRuntimeClient.RuntimeState> createSession(
				DigitalHumanRuntimeClient.SessionBinding binding, UUID commandId) {
			String sessionId = binding.sessionId();
			counter(creates, sessionId).incrementAndGet();
			CreatePlan plan = createPlan.getOrDefault(sessionId, CreatePlan.MATCH_CONNECTING);
			return switch (plan) {
				case MATCH_CONNECTING -> {
					DigitalHumanRuntimeClient.RuntimeState state = matching(sessionId, binding.backendId());
					store.put(sessionId, state);
					yield Mono.just(state);
				}
				case EXPLICIT_503 -> Mono.error(new IntelligenceException(503, "dh_runtime_unavailable", "数字人运行时未启用。"));
				case HANG_ACCEPTED -> {
					// 远端已保存实例（受理事实），响应丢失：屏障证明到达后挂起。
					store.put(sessionId, matching(sessionId, binding.backendId()));
					createReached.countDown();
					yield heldCreate.asMono();
				}
				case HANG_UNKNOWN -> {
					createReached.countDown();
					yield heldCreate.asMono();
				}
				case TERMINAL_ENDED -> {
					DigitalHumanRuntimeClient.RuntimeState ended = new DigitalHumanRuntimeClient.RuntimeState(sessionId,
							binding.backendId(), 1, 1, "ended", null, null, false, false);
					store.put(sessionId, ended);
					yield Mono.just(ended);
				}
			};
		}

		@Override
		public Mono<DigitalHumanRuntimeClient.RuntimeState> createSession(String sessionId, String backendId,
				UUID commandId) {
			return createSession(
					new DigitalHumanRuntimeClient.SessionBinding(sessionId, backendId, 1, 1, 0,
							Instant.now().plusSeconds(1800), Instant.now().plusSeconds(1800), 0, null, null, 0),
					commandId);
		}

		@Override
		public Mono<DigitalHumanRuntimeClient.RuntimeState> state(String sessionId) {
			counter(states, sessionId).incrementAndGet();
			StatePlan plan = statePlan.getOrDefault(sessionId, StatePlan.FROM_STORE);
			return switch (plan) {
				case FROM_STORE -> store.containsKey(sessionId)
						? Mono.just(store.get(sessionId))
						: Mono.error(new IntelligenceException(404, "dh_not_found", "会话不存在。"));
				case GONE_404 -> Mono.error(new IntelligenceException(404, "dh_not_found", "会话不存在。"));
				case HANG -> Mono.never();
			};
		}

		@Override
		public Mono<DigitalHumanRuntimeClient.RuntimeState> end(String sessionId, UUID commandId, String reasonCode) {
			counter(ends, sessionId).incrementAndGet();
			return Mono.just(new DigitalHumanRuntimeClient.RuntimeState(sessionId, "fake", 1, 1, "ended", null, null,
					false, false));
		}
	}

	@org.springframework.boot.test.context.TestConfiguration
	static class FakeRuntimeConfig {

		@Bean
		@org.springframework.context.annotation.Primary
		DigitalHumanRuntimeClient fakeRuntime() {
			return new DigitalHumanRuntimeClient(FAKE, new DigitalHumanRuntimeClient.Recorder() {
				@Override
				public void onCreate(String sessionId) {
				}

				@Override
				public void onEnd(String sessionId) {
				}
			});
		}
	}

	/** 事件服务包装：append 注入失败 / publish 丢弃（TC-C03-005 故障注入，目标分支可定位）。 */
	static final class FailingAppendEvents extends DigitalHumanEventService {

		private final DigitalHumanEventService delegate;

		FailingAppendEvents(DigitalHumanEventService delegate) {
			super(null, null);
			this.delegate = delegate;
		}

		@Override
		public Mono<AppendResult> append(String sessionId, UUID eventId, String eventType, long leaseEpoch,
				Map<String, Object> payload) {
			return Mono.error(new IllegalStateException("tcFix2_c03_005 注入 append 持久化失败"));
		}

		@Override
		public void publishSessionState(String sessionId, AppendResult result, Map<String, Object> payload) {
			delegate.publishSessionState(sessionId, result, payload);
		}

		@Override
		public void publishLive(String sessionId, String frame) {
			delegate.publishLive(sessionId, frame);
		}
	}

	static final class DropPublishEvents extends DigitalHumanEventService {

		private final DigitalHumanEventService delegate;

		DropPublishEvents(DigitalHumanEventService delegate) {
			super(null, null);
			this.delegate = delegate;
		}

		@Override
		public Mono<AppendResult> append(String sessionId, UUID eventId, String eventType, long leaseEpoch,
				Map<String, Object> payload) {
			return delegate.append(sessionId, eventId, eventType, leaseEpoch, payload);
		}

		@Override
		public void publishSessionState(String sessionId, AppendResult result, Map<String, Object> payload) {
			// 提交后通知被丢弃（第三组）：DB/GET 必须仍可恢复，不补写随机事件。
		}
	}

	/** 可推进的固定 Clock（崩溃接续/租约过期断言用）。 */
	private static final class SettableClock extends Clock {

		private volatile Instant instant;

		SettableClock(Instant at) {
			this.instant = at;
		}

		void set(Instant at) {
			this.instant = at;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}

	/** 与 @Primary bean 共享的同一 transport 实例（断言读到的计数/状态必须是被调用的那个）。 */
	private static final FakeRuntime FAKE = new FakeRuntime();

	@Autowired
	private DatabaseClient db;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private ReactiveStringRedisTemplate redis;

	@BeforeEach
	void seedDomain() {
		QWEN.resetAll();
		FAKE.reset();
		redis.execute(connection -> connection.serverCommands().flushDb().flux()).then().block(Duration.ofSeconds(5));
		db.sql("DELETE FROM dh_operation").then().then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_session").then()).then(db.sql("DELETE FROM dh_profile_revision").then())
				.then(db.sql("DELETE FROM dh_profile").then()).then(db.sql("DELETE FROM dh_catalog").then())
				.block(Duration.ofSeconds(10));
	}

	@AfterEach
	void cleanupGateRows() {
		db.sql("DELETE FROM intelligence_account_lifecycle WHERE account_id LIKE 'dh-prom-%'").then()
				.block(Duration.ofSeconds(10));
	}

	// ---------- fixture ----------

	private void seedCatalog(int maxSessionsGlobal) {
		String config = "{\"enabled\":true,\"newSessionsAllowed\":true,\"recordingEnabled\":false,"
				+ "\"customAvatarEnabled\":false,\"maxSessionsGlobal\":" + maxSessionsGlobal
				+ ",\"maxQueuedGlobal\":10}";
		db.sql("INSERT INTO dh_catalog(singleton_id, version, config_json, updated_by) VALUES (1, 1,"
				+ " CAST(:config AS jsonb), 'it')").bind("config", config).then().block(Duration.ofSeconds(5));
	}

	private void updateCatalog(int maxSessionsGlobal) {
		String config = "{\"enabled\":true,\"newSessionsAllowed\":true,\"recordingEnabled\":false,"
				+ "\"customAvatarEnabled\":false,\"maxSessionsGlobal\":" + maxSessionsGlobal
				+ ",\"maxQueuedGlobal\":10}";
		db.sql("UPDATE dh_catalog SET config_json = CAST(:config AS jsonb) WHERE singleton_id = 1")
				.bind("config", config).then().block(Duration.ofSeconds(5));
	}

	private String seedQueued(String owner, java.time.OffsetDateTime createdAt) {
		String id = UUID.randomUUID().toString();
		db.sql("""
				INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,
				    backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, created_at,
				    lease_epoch, media_epoch, lease_expires_at, last_browser_heartbeat_at)
				VALUES (CAST(:id AS uuid), :o, gen_random_uuid(), 1, '角色', 'mock-backend', gen_random_uuid(),
				    gen_random_uuid(), '{}'::jsonb, 'queued', :created, :created, 1, 1,
				    :created + interval '30 seconds', :created)
				""").bind("id", id).bind("o", owner).bind("created", createdAt).then().block(Duration.ofSeconds(5));
		return id;
	}

	private String seedActive(String owner) {
		String id = UUID.randomUUID().toString();
		db.sql("""
				INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,
				    backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, created_at,
				    lease_epoch, media_epoch, lease_expires_at, last_browser_heartbeat_at)
				VALUES (CAST(:id AS uuid), :o, gen_random_uuid(), 1, '角色', 'mock-backend', gen_random_uuid(),
				    gen_random_uuid(), '{}'::jsonb, 'ready', now(), now(), 1, 1,
				    now() + interval '30 seconds', now())
				""").bind("id", id).bind("o", owner).then().block(Duration.ofSeconds(5));
		return id;
	}

	/** §7.2 晋升标记 preparing 行（phase=reserved/sent；worker_lease_expires_at 可控）。 */
	private String seedPreparingPromotion(String owner, java.time.OffsetDateTime createdAt, String workerId,
			java.time.OffsetDateTime leaseExpiry) {
		String id = UUID.randomUUID().toString();
		db.sql("""
				INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,
				    backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, created_at,
				    lease_epoch, media_epoch, lease_expires_at, worker_id, worker_lease_expires_at)
				VALUES (CAST(:id AS uuid), :o, gen_random_uuid(), 1, '角色', 'mock-backend', gen_random_uuid(),
				    gen_random_uuid(), '{}'::jsonb, 'preparing', :created, :created, 1, 1,
				    :created + interval '30 seconds', :worker, :lease)
				""").bind("id", id).bind("o", owner).bind("created", createdAt).bind("worker", workerId)
				.bind("lease", leaseExpiry).then().block(Duration.ofSeconds(5));
		return id;
	}

	private String seedPlainPreparing(String owner, java.time.OffsetDateTime createdAt) {
		// 无前缀 preparing 对照（旧行不由本 worker 恢复）。
		String id = UUID.randomUUID().toString();
		db.sql("""
				INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,
				    backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, created_at,
				    lease_epoch, media_epoch, lease_expires_at, worker_id)
				VALUES (CAST(:id AS uuid), :o, gen_random_uuid(), 1, '角色', 'mock-backend', gen_random_uuid(),
				    gen_random_uuid(), '{}'::jsonb, 'preparing', :created, :created, 1, 1,
				    :created + interval '30 seconds', 'legacy-worker')
				""").bind("id", id).bind("o", owner).bind("created", createdAt).then().block(Duration.ofSeconds(5));
		return id;
	}

	private DigitalHumanSessionPromotionWorker worker(Clock clock, DigitalHumanEventService eventsService) {
		return new DigitalHumanSessionPromotionWorker(db,
				context.getBean(org.springframework.transaction.reactive.TransactionalOperator.class), clock, true,
				context.getBean(DigitalHumanSessionService.class), context.getBean(DigitalHumanRuntimeClient.class),
				eventsService);
	}

	private DigitalHumanSessionPromotionWorker worker(Clock clock) {
		return worker(clock, context.getBean(DigitalHumanEventService.class));
	}

	private String state(String sessionId) {
		String value = db.sql("SELECT state FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(r -> r.get("state", String.class)).one().block(Duration.ofSeconds(5));
		return value == null ? "<gone>" : value;
	}

	/**
	 * worker_id 可空：COALESCE 空串表示「无标记」（R2DBC 禁止 null 映射值，Reactor 禁止 null onNext）。
	 */
	private String marker(String sessionId) {
		return db.sql("SELECT COALESCE(worker_id, '') AS w FROM dh_session WHERE id = CAST(:id AS uuid)")
				.bind("id", sessionId).map(r -> r.get("w", String.class)).one().block(Duration.ofSeconds(5));
	}

	private Long eventCount(String sessionId) {
		return db.sql("SELECT count(*) AS n FROM dh_event WHERE session_id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(r -> r.get("n", Long.class)).one().block(Duration.ofSeconds(5));
	}

	/** create 容量同款 active 口径（§2.5 / SessionService / RULE-003）。 */
	private long activeCount() {
		Long active = db
				.sql("SELECT count(*) FILTER (WHERE state <> 'queued'"
						+ " AND state NOT IN ('ended','failed')) AS active FROM dh_session")
				.map((r, meta) -> r.get("active", Long.class)).one().block(Duration.ofSeconds(5));
		return active == null ? -1 : active;
	}

	private void freezeGate(String owner) {
		db.sql("UPDATE intelligence_account_lifecycle SET state = 'frozen', frozen_at = now()"
				+ " WHERE account_id = :o").bind("o", owner).then().block(Duration.ofSeconds(5));
	}

	private void unfreezeGate(String owner) {
		db.sql("UPDATE intelligence_account_lifecycle SET state = 'active', frozen_at = NULL WHERE account_id = :o")
				.bind("o", owner).then().block(Duration.ofSeconds(5));
	}

	/** 真实 create 夹具（TC-C03-002 第三竞争者）：dh_profile 行 + Redis 预检快照。 */
	private UUID seedProfileAndPreflight(String owner) {
		String profileId = UUID.randomUUID().toString();
		db.sql("""
				INSERT INTO dh_profile(id, owner_account_id, name, active_revision, status, version)
				VALUES (CAST(:id AS uuid), :o, '角色C', 1, 'active', 1)
				""").bind("id", profileId).bind("o", owner).then().block(Duration.ofSeconds(5));
		String preflightId = UUID.randomUUID().toString();
		String snapshot = """
				{"id":"%s","ownerId":"%s","profileId":"%s","profileVersion":1,"catalogVersion":1,
				 "inputMode":"text","backendId":"mock-backend","controllerId":"%s","llmModel":"qwen-plus",
				 "sttModel":"sandbox-speech-v1","ttsModel":"sandbox-tts-v1","renderModel":"sandbox-video-v1",
				 "priceTableVersion":"pt-1","expiresAt":"2026-10-03T00:00:00Z"}
				""".formatted(preflightId, owner, profileId, UUID.randomUUID()).replace("\n", "");
		redis.opsForValue().set("dh:preflight:" + preflightId, snapshot, Duration.ofSeconds(60))
				.block(Duration.ofSeconds(5));
		return UUID.fromString(preflightId);
	}

	// ---------- TC-C03-002：容量竞争与生命周期锁 ----------

	@Test
	void tcFix2_c03_002_fullSlotZeroDispatchThenClaimCreateRaceNeverOverSlot() throws Exception {
		String ownerA = "dh-prom-a-" + UUID.randomUUID();
		String ownerB = "dh-prom-b-" + UUID.randomUUID();
		String ownerX = "dh-prom-x-" + UUID.randomUUID();
		String ownerC = "dh-prom-c-" + UUID.randomUUID();
		seedCatalog(1);
		String active = seedActive(ownerX);
		String queuedA = seedQueued(ownerA, java.time.OffsetDateTime.now());
		String queuedB = seedQueued(ownerB, java.time.OffsetDateTime.now());
		SettableClock clock = new SettableClock(Instant.now());
		DigitalHumanSessionPromotionWorker first = worker(clock);
		DigitalHumanSessionPromotionWorker second = worker(clock);

		// 满槽多轮：零晋升/零派发（newSessionsAllowed 只挡新建，不撤销已入队会话——A/B 保持 queued）。
		for (int round = 0; round < 3; round++) {
			assertThat(first.runOnce().block(Duration.ofSeconds(10))).as("满槽第%s轮零晋升".formatted(round + 1)).isZero();
		}
		assertThat(FAKE.creates(queuedA)).isZero();
		assertThat(FAKE.creates(queuedB)).isZero();
		assertThat(state(queuedA)).isEqualTo("queued");
		assertThat(state(queuedB)).isEqualTo("queued");

		// 槽释放（原 active ended）→ 屏障同时启动两个 worker 实例 + 真实 SessionService.create。
		db.sql("UPDATE dh_session SET state = 'ended', ended_at = now(), version = version + 1 WHERE id = CAST(:id AS uuid)")
				.bind("id", active).then().block(Duration.ofSeconds(5));
		UUID preflightId = seedProfileAndPreflight(ownerC);
		var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
		try {
			var race = java.util.List.of(pool.submit(() -> first.runOnce().block(Duration.ofSeconds(30))),
					pool.submit(() -> second.runOnce().block(Duration.ofSeconds(30))),
					pool.submit(() -> context.getBean(DigitalHumanSessionService.class)
							.create(new PersonalActor(ownerC), preflightId, UUID.randomUUID(), false)
							.block(Duration.ofSeconds(30))));
			for (var future : race) {
				future.get(40, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}

		// 始终 active ≤ 解析后的 max（=1）：只一个名额被占；FIFO 合法候选无双 claim；create 可 queued 但不超槽。
		assertThat(activeCount()).as("并发后 active 恰 1（不超槽）").isEqualTo(1L);
		String createdRow = db.sql("SELECT id::text AS id FROM dh_session WHERE owner_account_id = :o")
				.bind("o", ownerC).map(r -> r.get("id", String.class)).one().block(Duration.ofSeconds(5));
		int createsTotal = FAKE.creates(queuedA) + FAKE.creates(queuedB)
				+ (createdRow == null ? 0 : FAKE.creates(createdRow));
		assertThat(createsTotal).as("全部 runtime create 合计 ≤1").isLessThanOrEqualTo(1);
		String slotHolder = slotHolderId();
		assertThat(slotHolder).as("占槽者必是候选 A/B 之一或新 create 行").isNotNull();

		// 夹限分组隔离：清掉竞态残留的 queued 行（否则 FIFO 队头不是新种子的 queuedD）。
		db.sql("DELETE FROM dh_event WHERE session_id IN (SELECT id FROM dh_session WHERE state = 'queued')").then()
				.then(db.sql("DELETE FROM dh_session WHERE state = 'queued'").then()).block(Duration.ofSeconds(10));

		// maxSessionsGlobal 解析分组：0/101 走真实目录容量（行为核验）；损坏/缺字段配置由 jsonb 列
		// 强制合法 JSON、无法入库——用包内静态解析器直测现有解析（readMaxSessions，不另写解析器）。
		updateCatalog(0);
		assertThat(first.runOnce().block(Duration.ofSeconds(10))).as("max=0 夹到 1：active≥1 零晋升").isZero();
		assertThat(DigitalHumanSessionService.readMaxSessions("{broken-json")).as("损坏配置缺省 1").isEqualTo(1);
		assertThat(DigitalHumanSessionService.readMaxSessions("{}")).as("缺字段缺省 1").isEqualTo(1);
		assertThat(DigitalHumanSessionService.readMaxSessions("{\"maxSessionsGlobal\":0}")).as("0 夹到 1").isEqualTo(1);
		assertThat(DigitalHumanSessionService.readMaxSessions("{\"maxSessionsGlobal\":101}")).as("101 夹到 100")
				.isEqualTo(100);
		assertThat(DigitalHumanSessionService.readMaxSessions("{\"maxSessionsGlobal\":100}")).as("100 边界内")
				.isEqualTo(100);
		String queuedD = seedQueued("dh-prom-d-" + UUID.randomUUID(), java.time.OffsetDateTime.now());
		updateCatalog(101);
		assertThat(first.runOnce().block(Duration.ofSeconds(10))).as("max=101 夹到 100：可晋升").isEqualTo(1);
		assertThat(state(queuedD)).isEqualTo("connecting");
	}

	private String slotHolderId() {
		return db.sql("""
				SELECT id::text AS id FROM dh_session
				WHERE state <> 'queued' AND state NOT IN ('ended','failed')
				""").map(r -> r.get("id", String.class)).one().block(Duration.ofSeconds(5));
	}

	/** gate 冻结竞态：先冻结则零派发；认领先提交（create 在途）则守卫中止 + 补偿停止，不接通。 */
	@Test
	void tcFix2_c03_002_gateFrozenRacesWithClaim() throws Exception {
		String ownerA = "dh-prom-fa-" + UUID.randomUUID();
		String ownerB = "dh-prom-fb-" + UUID.randomUUID();
		seedCatalog(1);

		// ① 冻结先提交：FIFO 扫描跳过非 active owner → 零候选/零 runtime/零认领。
		String frozen = seedQueued(ownerA, java.time.OffsetDateTime.now());
		freezeGate(ownerA);
		SettableClock clock = new SettableClock(Instant.now());
		assertThat(worker(clock).runOnce().block(Duration.ofSeconds(10))).isZero();
		assertThat(FAKE.creates(frozen)).as("冻结先提交零派发").isZero();
		assertThat(state(frozen)).isEqualTo("queued");
		assertThat(marker(frozen)).as("冻结行零认领标记").isEmpty();
		unfreezeGate(ownerA);

		// ② 认领先提交：create 在途时冻结，成功 CAS 事务内 gate 复查中止 → 补偿停止该 runtime，
		// 不接通、零事件；不能因旧 fence 落败停止别的实例（此处只有本实例）。
		// 子用例隔离：①的行是更老的 FIFO 候选，②的屏障不能复用①的 sink/latch。
		rearmBarrierAndCleanSessions();
		String claimed = seedQueued(ownerB, java.time.OffsetDateTime.now());
		FAKE.createPlan.put(claimed, CreatePlan.HANG_ACCEPTED);
		DigitalHumanSessionPromotionWorker racer = worker(clock);
		var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
		try {
			var pending = pool.submit(() -> racer.runOnce().block(Duration.ofSeconds(30)));
			assertThat(FAKE.createReached.await(10, TimeUnit.SECONDS)).as("屏障：create 已到达且挂起").isTrue();
			freezeGate(ownerB);
			FAKE.heldCreate.tryEmitValue(new DigitalHumanRuntimeClient.RuntimeState(claimed, "mock-backend", 1, 1,
					"connecting", null, null, false, false));
			pending.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		assertThat(state(claimed)).as("冻结守卫：不接通").isEqualTo("preparing");
		assertThat(FAKE.ends(claimed)).as("gate 非 active 补偿停止").isGreaterThanOrEqualTo(1);
		// 停止已证实，但 marker 清空被 V88 冻结屏障物理拒绝（非终态写一致拒绝，日志单列）——
		// 清理由冻结账号既有收尾链承担；此处断言屏障持有标记而非谎报已清。
		assertThat(marker(claimed)).as("冻结屏障推迟 marker 清理").startsWith("promotion:sent:");
		assertThat(eventCount(claimed)).as("冻结路径零事件").isZero();
		unfreezeGate(ownerB);
	}

	/** 子用例隔离：删前组行并重置屏障（Sinks.One/CountDownLatch 一次性，复用会拿到旧值）。 */
	private void rearmBarrierAndCleanSessions() {
		db.sql("DELETE FROM dh_operation").then().then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_turn").then()).then(db.sql("DELETE FROM dh_transcript").then())
				.then(db.sql("DELETE FROM dh_session").then()).block(Duration.ofSeconds(10));
		FAKE.rearm();
	}

	// ---------- TC-C03-003：明确未受理与已受理丢回执 ----------

	@Test
	void tcFix2_c03_003_notAcceptedLostReceiptAndUnknownClassification() {
		SettableClock clock = new SettableClock(Instant.now());
		// 三组独立 owner 各自晋升：容量放大到 100，避免前组 connecting 行占满 max=1 让后组零认领
		// （容量竞争语义归 tcFix2_c03_002 专测）。
		seedCatalog(100);
		DigitalHumanSessionPromotionWorker round = worker(clock);

		// A：明确拒绝（create 入口 503）+ state 精确 404 → 回 queued，截止不延长，下轮原 id 再派发。
		String ownerA = "dh-prom-na-" + UUID.randomUUID();
		String a = seedQueued(ownerA, java.time.OffsetDateTime.now());
		java.time.OffsetDateTime createdAtA = createdAt(a);
		FAKE.createPlan.put(a, CreatePlan.EXPLICIT_503);
		FAKE.statePlan.put(a, StatePlan.GONE_404);
		assertThat(round.runOnce().block(Duration.ofSeconds(10))).isZero();
		assertThat(state(a)).as("明确未受理回 queued").isEqualTo("queued");
		assertThat(marker(a)).as("回队清空标记").isEmpty();
		assertThat(createdAt(a)).as("回队不延长 60s 截止（created_at 不动）").isEqualTo(createdAtA);
		FAKE.createPlan.put(a, CreatePlan.MATCH_CONNECTING);
		assertThat(round.runOnce().block(Duration.ofSeconds(10))).isEqualTo(1);
		assertThat(state(a)).isEqualTo("connecting");
		assertThat(FAKE.creates(a)).as("仍是原 sessionId（不新建会话）").isEqualTo(2);

		// B：已受理丢回执（远端保存实例后响应丢失，state 回原实例）→ connecting 且不再 create。
		String ownerB = "dh-prom-lr-" + UUID.randomUUID();
		String b = seedQueued(ownerB, java.time.OffsetDateTime.now());
		FAKE.createPlan.put(b, CreatePlan.HANG_ACCEPTED);
		assertThat(round.runOnce().block(Duration.ofSeconds(30))).isEqualTo(1);
		assertThat(state(b)).as("受理丢失按成功收敛").isEqualTo("connecting");
		assertThat(FAKE.creates(b)).isEqualTo(1);
		assertThat(round.runOnce().block(Duration.ofSeconds(10))).as("已 connecting 无新轮晋升").isZero();
		assertThat(FAKE.creates(b)).as("第二轮 create 计数仍 1").isEqualTo(1);
		assertThat(eventCount(b)).as("晋升事件恰 1").isEqualTo(1);

		// C：create 超时且 state 未知 → 保留 preparing+sent 只查询（不凭观测释放槽，不重复 create）。
		String ownerC = "dh-prom-uk-" + UUID.randomUUID();
		String c = seedQueued(ownerC, java.time.OffsetDateTime.now());
		FAKE.createPlan.put(c, CreatePlan.HANG_UNKNOWN);
		assertThat(round.runOnce().block(Duration.ofSeconds(30))).isZero();
		assertThat(state(c)).as("未知保持 preparing 占槽").isEqualTo("preparing");
		assertThat(marker(c)).as("sent 未决标记保留").startsWith("promotion:sent:");
		int createsAfterFirst = FAKE.creates(c);
		FAKE.statePlan.put(c, StatePlan.GONE_404);
		assertThat(round.runOnce().block(Duration.ofSeconds(10))).isZero();
		assertThat(FAKE.creates(c)).as("sent 未决绝不重复 create").isEqualTo(createsAfterFirst);
		assertThat(state(c)).isEqualTo("preparing");
		assertThat(marker(c)).startsWith("promotion:sent:");
	}

	private java.time.OffsetDateTime createdAt(String sessionId) {
		return db.sql("SELECT created_at FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(r -> r.get("created_at", java.time.OffsetDateTime.class)).one().block(Duration.ofSeconds(5));
	}

	// ---------- TC-C03-004：崩溃接续、旧 fence、无前缀对照、队列截止 ----------

	@Test
	void tcFix2_c03_004_crashRecoveryReservedSentNoPrefixAndQueueDeadline() {
		SettableClock clock = new SettableClock(Instant.now());
		Instant now = clock.instant();
		seedCatalog(100); // 认领需要目录容量行（缺行不新增认领，RULE-003）；子用例行累积，容量竞争归 c03_002 专测
		DigitalHumanSessionPromotionWorker round = worker(clock);

		// ① reserved（未发送）租约已到 → 回 queued（下轮 FIFO 正常认领，不重复派发旧 fence）。
		// 回队与认领同轮合法（FIFO 收敛更快）：给该行注入「明确未受理+state 404」，整轮净效果=回队。
		String ownerR = "dh-prom-cr-" + UUID.randomUUID();
		String reserved = seedPreparingPromotion(ownerR, ts(now.minusSeconds(5)),
				DigitalHumanSessionPromotionWorker.PROMOTION_RESERVED_PREFIX + UUID.randomUUID(),
				ts(now.minusSeconds(1)));
		FAKE.createPlan.put(reserved, CreatePlan.EXPLICIT_503);
		FAKE.statePlan.put(reserved, StatePlan.GONE_404);
		assertThat(round.runOnce().block(Duration.ofSeconds(10))).isZero();
		assertThat(state(reserved)).as("reserved 过期回 queued").isEqualTo("queued");
		assertThat(marker(reserved)).isEmpty();

		// ② sent（已发送未决）租约已到 + remote 存在匹配 → 只查询后接通（不重复 create，creates==0）。
		String ownerS = "dh-prom-cs-" + UUID.randomUUID();
		String sent = seedPreparingPromotion(ownerS, ts(now.minusSeconds(5)),
				DigitalHumanSessionReaper.PROMOTION_SENT_PREFIX + UUID.randomUUID(), ts(now.minusSeconds(1)));
		FAKE.store.put(sent, new DigitalHumanRuntimeClient.RuntimeState(sent, "mock-backend", 1, 1, "connecting", null,
				null, false, false));
		assertThat(round.runOnce().block(Duration.ofSeconds(10))).isEqualTo(1);
		assertThat(state(sent)).isEqualTo("connecting");
		assertThat(FAKE.creates(sent)).as("恢复只查询不重复 create").isZero();
		assertThat(FAKE.states(sent)).as("恢复走了 state 查询").isGreaterThanOrEqualTo(1);
		assertThat(marker(sent)).as("成功后清空标记").isEmpty();
		assertThat(eventCount(sent)).isEqualTo(1L);

		// ③ sent 未决 + state 未知 → 保留 preparing + 新 fence（租约已刷新）。
		String ownerU = "dh-prom-cu-" + UUID.randomUUID();
		String unknown = seedPreparingPromotion(ownerU, ts(now.minusSeconds(5)),
				DigitalHumanSessionReaper.PROMOTION_SENT_PREFIX + UUID.randomUUID(), ts(now.minusSeconds(1)));
		FAKE.statePlan.put(unknown, StatePlan.HANG);
		assertThat(round.runOnce().block(Duration.ofSeconds(30))).isZero();
		assertThat(state(unknown)).isEqualTo("preparing");
		assertThat(marker(unknown)).startsWith("promotion:sent:");
		assertThat(workerLease(unknown)).as("重领刷新租约").isAfter(ts(now));

		// ④ sent 未决 + state 精确 404 → 仍保持占槽（404 不构成未受理证明）。
		String ownerG = "dh-prom-cg-" + UUID.randomUUID();
		String gone = seedPreparingPromotion(ownerG, ts(now.minusSeconds(5)),
				DigitalHumanSessionReaper.PROMOTION_SENT_PREFIX + UUID.randomUUID(), ts(now.minusSeconds(1)));
		FAKE.statePlan.put(gone, StatePlan.GONE_404);
		assertThat(round.runOnce().block(Duration.ofSeconds(10))).isZero();
		assertThat(state(gone)).as("sent 未决 404 保持占槽").isEqualTo("preparing");
		assertThat(marker(gone)).startsWith("promotion:sent:");

		// ⑤ 无前缀 preparing 对照：不由本 worker 恢复（不「顺手修」）。
		String ownerN = "dh-prom-cn-" + UUID.randomUUID();
		String legacy = seedPlainPreparing(ownerN, ts(now.minusSeconds(5)));
		assertThat(round.runOnce().block(Duration.ofSeconds(10))).isZero();
		assertThat(state(legacy)).as("无前缀行零改动").isEqualTo("preparing");
		assertThat(marker(legacy)).isEqualTo("legacy-worker");

		// ⑥ 队列截止 59.999s/60s/60.001s：到期不能续命（age>=60s 进 ending，不被认领）。
		String young = seedQueued("dh-prom-dy-" + UUID.randomUUID(), ts(now.minusMillis(59999)));
		String exact = seedQueued("dh-prom-de-" + UUID.randomUUID(), ts(now.minusSeconds(60)));
		String over = seedQueued("dh-prom-do-" + UUID.randomUUID(), ts(now.minusMillis(60001)));
		assertThat(round.runOnce().block(Duration.ofSeconds(10))).isEqualTo(1);
		assertThat(state(young)).as("59.999s 仍可晋升").isEqualTo("connecting");
		assertThat(state(exact)).as("60s 整到期进 ending").isEqualTo("ending");
		assertThat(state(over)).isEqualTo("ending");
	}

	/** 用户 end/cancel 与旧 fence：marker 保护、迟到结果不接通、不停止合法新持有者。 */
	@Test
	void tcFix2_c03_004_oldFenceLosesAndUserEndKeepsMarkerUntilResolved() throws Exception {
		SettableClock clock = new SettableClock(Instant.now());
		seedCatalog(1);

		// ① 旧 fence 落败：新持有者（模拟另一实例重领后的新 marker）合法活跃——旧 create 迟到结果
		// 不接通、零 DB/事件写、不停止新持有者实例。
		String ownerE = "dh-prom-of-" + UUID.randomUUID();
		String e = seedQueued(ownerE, java.time.OffsetDateTime.now());
		FAKE.createPlan.put(e, CreatePlan.HANG_ACCEPTED);
		DigitalHumanSessionPromotionWorker oldWorker = worker(clock);
		var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
		try {
			var pending = pool.submit(() -> oldWorker.runOnce().block(Duration.ofSeconds(30)));
			assertThat(FAKE.createReached.await(10, TimeUnit.SECONDS)).as("屏障：旧 worker create 已到达").isTrue();
			String oldMarker = marker(e);
			assertThat(oldMarker).startsWith("promotion:sent:");
			// 模拟新实例重领：换 fence（新 UUID），租约刷新，阶段保留 sent。
			String newMarker = DigitalHumanSessionReaper.PROMOTION_SENT_PREFIX + UUID.randomUUID();
			db.sql("UPDATE dh_session SET worker_id = :next, worker_lease_expires_at = now() + interval '30 seconds',"
					+ " version = version + 1, updated_at = now() WHERE id = CAST(:id AS uuid)").bind("next", newMarker)
					.bind("id", e).then().block(Duration.ofSeconds(5));
			Integer versionAfterSwap = version(e);
			FAKE.heldCreate.tryEmitValue(new DigitalHumanRuntimeClient.RuntimeState(e, "mock-backend", 1, 1,
					"connecting", null, null, false, false));
			pending.get(30, TimeUnit.SECONDS);
			assertThat(state(e)).as("旧 fence 落败不接通").isEqualTo("preparing");
			assertThat(marker(e)).as("新持有者 marker 完好").isEqualTo(newMarker);
			assertThat(version(e)).as("旧 fence 零 DB 写").isEqualTo(versionAfterSwap);
			assertThat(eventCount(e)).isZero();
			assertThat(FAKE.ends(e)).as("不停止仍合法活跃的新持有者实例").isZero();
		} finally {
			pool.shutdownNow();
		}

		// ② 用户 end 与 create 在途（TC-C02-004 跨晋升子场景）：end 只受理成 ending、保留 marker 与
		// 容量；迟到成功必须停止该 runtime，不复活、不发 connecting、不提前释放。
		// 子用例隔离：①已消耗挂起屏障（Sinks.One 终态），且①的 preparing 行不在候选集但屏障必须换新。
		rearmBarrierAndCleanSessions();
		String ownerF = "dh-prom-ue-" + UUID.randomUUID();
		String f = seedQueued(ownerF, java.time.OffsetDateTime.now());
		FAKE.createPlan.put(f, CreatePlan.HANG_ACCEPTED);
		DigitalHumanSessionPromotionWorker endRacer = worker(clock);
		var pool2 = java.util.concurrent.Executors.newSingleThreadExecutor();
		try {
			var pending = pool2.submit(() -> endRacer.runOnce().block(Duration.ofSeconds(30)));
			assertThat(FAKE.createReached.await(10, TimeUnit.SECONDS)).as("屏障：create 在途").isTrue();
			String sentMarker = marker(f);
			var endResult = context.getBean(DigitalHumanSessionService.class)
					.end(new PersonalActor(ownerF), UUID.fromString(f), UUID.randomUUID(), "user")
					.block(Duration.ofSeconds(10));
			assertThat(endResult.endedNow()).isTrue();
			assertThat(endResult.state()).isEqualTo("ending");
			assertThat(state(f)).as("用户 end 只受理成 ending（不立即 CAS ended）").isEqualTo("ending");
			assertThat(marker(f)).as("sent 标记与容量保留，不被 end 提前清除").isEqualTo(sentMarker);

			// 迟到成功回报：CAS 落败 → DB 已 ending → 补偿停止该 runtime（RULE-006）。
			FAKE.heldCreate.tryEmitValue(new DigitalHumanRuntimeClient.RuntimeState(f, "mock-backend", 1, 1,
					"connecting", null, null, false, false));
			pending.get(30, TimeUnit.SECONDS);
			assertThat(state(f)).as("迟到结果不接通、不复活 ended").isEqualTo("ending");
			assertThat(eventCount(f)).as("取消先行零 connecting 事件").isZero();
			assertThat(FAKE.ends(f)).as("迟到成功必须被停止").isGreaterThanOrEqualTo(1);
			assertThat(marker(f)).as("停止证实后清空标记").isEmpty();
		} finally {
			pool.shutdownNow();
		}
	}

	private Integer version(String sessionId) {
		return db.sql("SELECT version FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(r -> r.get("version", Integer.class)).one().block(Duration.ofSeconds(5));
	}

	private java.time.OffsetDateTime workerLease(String sessionId) {
		return db.sql("SELECT worker_lease_expires_at FROM dh_session WHERE id = CAST(:id AS uuid)")
				.bind("id", sessionId).map(r -> r.get("worker_lease_expires_at", java.time.OffsetDateTime.class)).one()
				.block(Duration.ofSeconds(5));
	}

	private static java.time.OffsetDateTime ts(Instant at) {
		return java.time.OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
	}

	// ---------- TC-C03-005：事件事务、live 与重放 ----------

	@Test
	void tcFix2_c03_005_liveDeliveredAppendFailureRollsBackAndPublishLostRecoversViaGet() throws Exception {
		SettableClock clock = new SettableClock(Instant.now());
		seedCatalog(1);
		DigitalHumanEventService realEvents = context.getBean(DigitalHumanEventService.class);

		// 第一组：已有 SSE 连接收到 session.state connecting（不是只重连才看到）。
		// 连接体 Flux 只允许单次订阅——用队列消费多帧（重复 .next() 会被 Reactor Netty 拒绝）。
		String ownerL = "dh-prom-lv-" + UUID.randomUUID();
		String live = seedQueued(ownerL, java.time.OffsetDateTime.now());
		var stream = client().get().uri("/api/digital-human/sessions/" + live + "/events")
				.header("X-Grassland-Identity", sign(ownerL, null)).exchange().expectStatus().isOk()
				.returnResult(String.class);
		java.util.concurrent.LinkedBlockingQueue<String> frames = new java.util.concurrent.LinkedBlockingQueue<>();
		reactor.core.Disposable subscription = stream.getResponseBody().subscribe(frames::add);
		assertThat(frames.poll(5, java.util.concurrent.TimeUnit.SECONDS)).contains("session.snapshot");
		assertThat(worker(clock).runOnce().block(Duration.ofSeconds(10))).isEqualTo(1);
		String frame = frames.poll(5, java.util.concurrent.TimeUnit.SECONDS);
		assertThat(frame).as("已订阅 live 收到晋升事件").isNotNull();
		var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		var node = mapper.readTree(frame);
		assertThat(node.path("type").asText()).isEqualTo("session.state");
		assertThat(node.path("payload").path("reasonCode").asText()).isEqualTo("queue_promoted");
		assertThat(node.path("payload").path("state").asText()).isEqualTo("connecting");
		subscription.dispose();
		assertThat(eventCount(live)).isEqualTo(1L);
		// 组 1 的 connecting 行占用 max=1 唯一名额——后续组放大目录容量，避免容量干扰断言。
		updateCatalog(100);

		// 第二组：append 持久化失败 → connecting CAS 回滚、零 live；后续以原 runtime 状态恢复（不 re-create）。
		String ownerF = "dh-prom-fl-" + UUID.randomUUID();
		String failed = seedQueued(ownerF, java.time.OffsetDateTime.now());
		DigitalHumanSessionPromotionWorker failingWorker = worker(clock, new FailingAppendEvents(realEvents));
		assertThat(failingWorker.runOnce().block(Duration.ofSeconds(10))).isZero();
		assertThat(state(failed)).as("append 失败 CAS 回滚保持 preparing").isEqualTo("preparing");
		assertThat(eventCount(failed)).as("回滚零事件").isZero();
		assertThat(FAKE.creates(failed)).as("远端已受理（create 恰一次）").isEqualTo(1);
		// 推进时钟越过认领租约：下一轮重领只查询收敛，接通且事件恰 1（恢复而非再 create）。
		clock.set(clock.instant().plusSeconds(31));
		DigitalHumanSessionPromotionWorker recovered = worker(clock);
		assertThat(recovered.runOnce().block(Duration.ofSeconds(10))).isEqualTo(1);
		assertThat(state(failed)).isEqualTo("connecting");
		assertThat(eventCount(failed)).isEqualTo(1L);
		assertThat(FAKE.creates(failed)).as("恢复不重复 create").isEqualTo(1);

		// 第三组：提交后丢弃 publish → DB/GET 可恢复（durable 事件仍在），不新增随机 eventId 补一条。
		String ownerP = "dh-prom-dp-" + UUID.randomUUID();
		String dropped = seedQueued(ownerP, java.time.OffsetDateTime.now());
		DigitalHumanSessionPromotionWorker dropWorker = worker(clock, new DropPublishEvents(realEvents));
		assertThat(dropWorker.runOnce().block(Duration.ofSeconds(10))).isEqualTo(1);
		assertThat(state(dropped)).isEqualTo("connecting");
		assertThat(eventCount(dropped)).as("durable 事件已提交").isEqualTo(1L);
		var snapshot = client().get().uri("/api/digital-human/sessions/" + dropped)
				.header("X-Grassland-Identity", sign(ownerP, null)).exchange().expectStatus().isOk()
				.expectBody(byte[].class).returnResult().getResponseBody();
		assertThat(mapper.readTree(snapshot).path("data").path("session").path("state").asText())
				.as("GET 兜底读到 connecting").isEqualTo("connecting");
		assertThat(dropWorker.runOnce().block(Duration.ofSeconds(10))).as("无第二晋升").isZero();
		assertThat(eventCount(dropped)).as("不补写随机重复事件").isEqualTo(1L);
	}
}
