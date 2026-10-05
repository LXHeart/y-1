package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 三 worker 调度驱动验证（任务书 #105fix-1 C105X-01 / TC105X-01-01/02）。
 *
 * <p>
 * 审计 F-01/F-02/F-03：三个 worker 此前只有生产方法无调度入口。本类用 {@code @TestPropertySource}
 * 内联覆盖基座内联静默（@TestPropertySource 子类覆盖是文档化语义；@DynamicPropertySource 层打不过——
 * 见基座注释）只开 reaper 且 250ms——TC-01 证明 过期会话在<b>零直调</b>下被调度器推进；TC-02
 * 反射守卫（@Scheduled/enabled/running 防误删）、 running CAS 防重入与错误路径释放、enabled=false
 * 首行返回。cleanup/reconcile 的 DB 推进语义归
 * 既有直调测试（LeaseIT/RealtimeIntegrationIT/AdminReconcileIT），本类不重复。
 */
@TestPropertySource(properties = {"digital-human.reaper.enabled=true", "digital-human.reaper.poll-interval-ms=250",
		"digital-human.promotion.enabled=true", "digital-human.promotion.poll-interval-ms=250"})
class DigitalHumanWorkerSchedulingIT extends IntelligenceItSupport {

	private static final String OWNER_PREFIX = "dh-wsched-";

	/** 晋升 fake runtime（@Primary）：create 返回匹配 binding 的 connecting；计数供调度断言。 */
	static final java.util.concurrent.atomic.AtomicInteger PROMOTION_CREATES = new java.util.concurrent.atomic.AtomicInteger();
	static final java.util.concurrent.atomic.AtomicInteger PROMOTION_STATES = new java.util.concurrent.atomic.AtomicInteger();

	@org.springframework.boot.test.context.TestConfiguration
	static class PromotionFakeRuntimeConfig {

		@Bean
		@Primary
		DigitalHumanRuntimeClient promotionFakeRuntime() {
			DigitalHumanRuntimeClient.Transport transport = new DigitalHumanRuntimeClient.Transport() {
				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> createSession(String sessionId, String backendId,
						UUID commandId) {
					PROMOTION_CREATES.incrementAndGet();
					return Mono.just(new DigitalHumanRuntimeClient.RuntimeState(sessionId, backendId, 1, 1,
							"connecting", null, null, false, false));
				}

				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> state(String sessionId) {
					PROMOTION_STATES.incrementAndGet();
					return Mono.just(new DigitalHumanRuntimeClient.RuntimeState(sessionId, "fake", 1, 1, "connecting",
							null, null, false, false));
				}

				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> end(String sessionId, UUID commandId,
						String reasonCode) {
					return Mono.just(new DigitalHumanRuntimeClient.RuntimeState(sessionId, "fake", 1, 1, "ended", null,
							null, false, false));
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

	@Autowired
	private ApplicationContext context;

	@Autowired
	private DigitalHumanSessionService sessions;

	@Autowired
	private DigitalHumanEventService events;

	private final String account = OWNER_PREFIX + UUID.randomUUID();

	@BeforeEach
	void cleanSeededSessions() {
		deleteSeeded().block(Duration.ofSeconds(10));
	}

	@AfterEach
	void cleanSeededSessionsAfter() {
		deleteSeeded().block(Duration.ofSeconds(10));
	}

	private reactor.core.publisher.Mono<Void> deleteSeeded() {
		// 晋升事件随 session 删除（dh_event.session_id FK）——先清事件再清会话。
		return db
				.sql("DELETE FROM dh_event WHERE session_id IN"
						+ " (SELECT id FROM dh_session WHERE owner_account_id LIKE :p)")
				.bind("p", OWNER_PREFIX + "%").then()
				.then(db.sql("DELETE FROM dh_session WHERE owner_account_id LIKE :p").bind("p", OWNER_PREFIX + "%")
						.then());
	}

	// ---------- TC105X-01-01 调度真实驱动回收（零直调） ----------

	@Test
	void tc105x_01_01_schedulerDrivesExpiredConnectingRecoveryWithoutDirectCall() {
		// 边界两组：connecting 90s 窗口刚过 / 过期超窗（照 LeaseIT/ReaperTest 种子法 SQL 直改）。
		String justExpired = seedExpiredConnecting(Duration.ofSeconds(91), account + "-a");
		String overWindow = seedExpiredConnecting(Duration.ofSeconds(150), account + "-b");
		// 全程不直调 scanExpired：状态只能由后台 @Scheduled（250ms）推进。
		awaitReaped(justExpired);
		awaitReaped(overWindow);
	}

	private String seedExpiredConnecting(Duration enteredAge, String owner) {
		String id = UUID.randomUUID().toString();
		db.sql("""
				INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision,
				    profile_name_at_creation, backend_id, preflight_id, controller_id, config_snapshot, state,
				    state_entered_at, lease_epoch, media_epoch, lease_expires_at, last_browser_heartbeat_at)
				VALUES (CAST(:id AS uuid), :o, gen_random_uuid(), 1, '角色', 'mock', gen_random_uuid(),
				    gen_random_uuid(), '{}'::jsonb, 'connecting', :entered, 1, 1,
				    now() + interval '30 seconds', now())
				""").bind("id", id).bind("o", owner).bind("entered", java.time.OffsetDateTime.now().minus(enteredAge))
				.then().block(Duration.ofSeconds(5));
		return id;
	}

	private void awaitReaped(String sessionId) {
		long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
		while (System.currentTimeMillis() < deadline) {
			var row = db.sql("SELECT state, cleanup_pending, error_code FROM dh_session WHERE id = CAST(:id AS uuid)")
					.bind("id", sessionId)
					.map(r -> new String[]{r.get("state", String.class),
							String.valueOf(r.get("cleanup_pending", Boolean.class)), r.get("error_code", String.class)})
					.one().block(Duration.ofSeconds(5));
			if (row != null && "failed".equals(row[0]) && "true".equals(row[1])) {
				// error_code=dh_connect_timeout 是 reaper failed 分支的专属写入——排除其他推进者。
				assertThat(row[2]).as("reaper failed 分支 error_code").isEqualTo("dh_connect_timeout");
				return;
			}
			try {
				Thread.sleep(250);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				fail("轮询被中断");
			}
		}
		fail("30s 内调度器未回收 connecting 会话 id=" + sessionId + " state=" + stateOf(sessionId));
	}

	private String stateOf(String sessionId) {
		String state = db.sql("SELECT state FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(r -> r.get("state", String.class)).one().block(Duration.ofSeconds(5));
		return state == null ? "<gone>" : state;
	}

	// ---------- TC105X-01-02 防重入、开关与反射守卫 ----------

	@Test
	void tc105x_01_02_runningCasSkipsReentryReleasesOnErrorAndEnabledGuard() throws Exception {
		// ① 反射守卫：三 worker runScheduled 带 @Scheduled 且 enabled/running 字段存在（防误删）。
		assertScheduled(DigitalHumanSessionReaper.class, "${digital-human.reaper.poll-interval-ms:5000}");
		assertScheduled(DigitalHumanCleanupWorker.class, "${digital-human.cleanup.poll-interval-ms:30000}");
		assertScheduled(DigitalHumanReconciliationWorker.class, "${digital-human.reconcile.poll-interval-ms:30000}");

		// ② 上下文绑定：@TestPropertySource 内联覆盖优先于基座 DynamicPropertySource 静默。
		assertThat(booleanField(context.getBean(DigitalHumanSessionReaper.class), "enabled"))
				.as("reaper @TestPropertySource 覆盖").isTrue();
		assertThat(booleanField(context.getBean(DigitalHumanCleanupWorker.class), "enabled")).as("cleanup 基座静默")
				.isFalse();
		assertThat(booleanField(context.getBean(DigitalHumanReconciliationWorker.class), "enabled"))
				.as("reconcile 基座静默").isFalse();

		// ③ running CAS：上一轮挂起期间再次触发 → 跳过；完成后释放 → 可再进入。
		Sinks.One<DigitalHumanSessionReaper.ReaperResult> hang = Sinks.one();
		AtomicInteger reaperCalls = new AtomicInteger();
		DigitalHumanSessionReaper counting = new DigitalHumanSessionReaper(db, transactions(), Clock.systemUTC()) {
			@Override
			Mono<DigitalHumanSessionReaper.ReaperResult> runOnce() {
				reaperCalls.incrementAndGet();
				return hang.asMono();
			}
		};
		counting.runScheduled();
		counting.runScheduled();
		assertThat(reaperCalls.get()).as("第二次进入被 running CAS 跳过").isEqualTo(1);
		hang.tryEmitValue(new DigitalHumanSessionReaper.ReaperResult(0, 0, 0, 0, 0));
		awaitReleased(counting);
		counting.runScheduled();
		assertThat(reaperCalls.get()).as("释放后可再次进入").isEqualTo(2);

		// ④ 错误路径也释放 running（doFinally 兜底，不卡死后续轮）。
		DigitalHumanSessionReaper erroring = new DigitalHumanSessionReaper(db, transactions(), Clock.systemUTC()) {
			@Override
			Mono<DigitalHumanSessionReaper.ReaperResult> runOnce() {
				return Mono.error(new IllegalStateException("tc105x-01-02 注入失败"));
			}
		};
		erroring.runScheduled();
		awaitReleased(erroring);

		// ⑤ enabled=false 方法体首行 return：计数 runOnce 证明根本未进入（三 worker 同款）。
		AtomicInteger cleanupCalls = new AtomicInteger();
		new DigitalHumanCleanupWorker(db, null, null, null, runtimePorts(), "", null, false) {
			@Override
			Mono<Void> runOnce() {
				cleanupCalls.incrementAndGet();
				return Mono.empty();
			}
		}.runScheduled();
		AtomicInteger reconcileCalls = new AtomicInteger();
		new DigitalHumanReconciliationWorker(db, null, false) {
			@Override
			Mono<DigitalHumanReconciliationWorker.ReconciliationSummary> runOnce() {
				reconcileCalls.incrementAndGet();
				return Mono.just(new DigitalHumanReconciliationWorker.ReconciliationSummary(0, 0, 0, 0, 0));
			}
		}.runScheduled();
		AtomicInteger disabledReaperCalls = new AtomicInteger();
		new DigitalHumanSessionReaper(db, transactions(), Clock.systemUTC(), false) {
			@Override
			Mono<DigitalHumanSessionReaper.ReaperResult> runOnce() {
				disabledReaperCalls.incrementAndGet();
				return Mono.empty();
			}
		}.runScheduled();
		assertThat(cleanupCalls.get()).as("cleanup enabled=false 未进入").isZero();
		assertThat(reconcileCalls.get()).as("reconcile enabled=false 未进入").isZero();
		assertThat(disabledReaperCalls.get()).as("reaper enabled=false 未进入").isZero();
	}

	private TransactionalOperator transactions() {
		return context.getBean(TransactionalOperator.class);
	}

	// ---------- 任务书 105-fix-2 C-03：TC-C03-001 真实调度/FIFO/开关（W18） ----------

	/** 只等待、不直调 runOnce：状态推进必须来自真实 @Scheduled（250ms）。 */
	private void awaitState(String sessionId, String expected, java.time.Duration limit) throws Exception {
		long deadline = System.currentTimeMillis() + limit.toMillis();
		while (System.currentTimeMillis() < deadline) {
			if (expected.equals(stateOf(sessionId))) {
				return;
			}
			Thread.sleep(100);
		}
		fail(limit.toSeconds() + "s 内调度器未把 id=" + sessionId + " 推进到 " + expected + "（实际 " + stateOf(sessionId) + "）");
	}

	/** TC-C03-001：真实调度晋升 FIFO 队头（created_at 相同→id 升序）；另一仍 queued；零直调。 */
	@Test
	void tcFix2_c03_001_schedulerPromotesOldestQueuedWithoutDirectCall() throws Exception {
		// 共享容器跨类自愈：别类（如 PromotionIT）遗留的 connecting/preparing 行占全局容量，
		// max=1 下会让本测试零认领——先清空 dh_* 会话表（照 ReaperTest 范式）。
		db.sql("DELETE FROM dh_operation").then().then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_session").then()).block(Duration.ofSeconds(10));
		// 两条候选在<b>单条 INSERT</b> 中原子落库（created_at 相同）：250ms 调度器无法在两行之间抢跑，
		// FIFO 结果由 (created_at, id) 确定序决定。
		java.time.Instant sameCreated = java.time.Instant.now().minusSeconds(5);
		String a = UUID.randomUUID().toString();
		String b = UUID.randomUUID().toString();
		db.sql("""
				INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,
				    backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, created_at,
				    lease_epoch, media_epoch, lease_expires_at)
				VALUES (CAST(:ida AS uuid), :oa, gen_random_uuid(), 1, '角色', 'mock-backend', gen_random_uuid(),
				    gen_random_uuid(), '{}'::jsonb, 'queued', :created, :created, 1, 1,
				    :created + interval '30 seconds'),
				       (CAST(:idb AS uuid), :ob, gen_random_uuid(), 1, '角色', 'mock-backend', gen_random_uuid(),
				    gen_random_uuid(), '{}'::jsonb, 'queued', :created, :created, 1, 1,
				    :created + interval '30 seconds')
				""").bind("ida", a).bind("oa", account + "-fa").bind("idb", b).bind("ob", account + "-fb")
				.bind("created", java.time.OffsetDateTime.ofInstant(sameCreated, java.time.ZoneOffset.UTC)).then()
				.block(Duration.ofSeconds(5));
		String expectedFirst = a.compareTo(b) < 0 ? a : b;
		String expectedSecond = a.compareTo(b) < 0 ? b : a;
		// 目录容量行（RULE-003：catalog 缺行不新增认领——调度晋升必须有 maxSessionsGlobal 配置）。
		db.sql("DELETE FROM dh_catalog").then().then(db
				.sql("INSERT INTO dh_catalog(singleton_id, version, config_json, updated_by)"
						+ " VALUES (1, 1, CAST(:config AS jsonb), 'it')")
				.bind("config",
						"{\"enabled\":true,\"newSessionsAllowed\":true,\"recordingEnabled\":false,"
								+ "\"customAvatarEnabled\":false,\"maxSessionsGlobal\":1,\"maxQueuedGlobal\":10}")
				.then()).block(Duration.ofSeconds(10));
		// 装配证明：worker 必须来自 Spring 容器（只 new 直调不能证明）。
		assertThat(context.getBean(DigitalHumanSessionPromotionWorker.class)).isNotNull();

		awaitState(expectedFirst, "connecting", java.time.Duration.ofSeconds(30));
		assertThat(stateOf(expectedSecond)).as("只晋升最老合法候选，另一仍 queued").isEqualTo("queued");
		assertThat(PROMOTION_CREATES.get()).as("runtime create 恰 1（同 sessionId）").isEqualTo(1);
		Long events = db.sql("""
				SELECT count(*) AS n FROM dh_event WHERE session_id = CAST(:id AS uuid)
				  AND event_type = 'session.state' AND payload->>'reasonCode' = 'queue_promoted'
				  AND payload->>'state' = 'connecting'
				""").bind("id", expectedFirst).map(r -> r.get("n", Long.class)).one().block(Duration.ofSeconds(5));
		assertThat(events).as("成功晋升 durable 事件恰 1").isEqualTo(1L);
		// worker_id 已清空（成功后清空 fence）；COALESCE 规避可空列映射（R2DBC 禁 null 映射值）。
		String marker = db.sql("SELECT COALESCE(worker_id, '') AS w FROM dh_session WHERE id = CAST(:id AS uuid)")
				.bind("id", expectedFirst).map(r -> r.get("w", String.class)).one().block(Duration.ofSeconds(5));
		assertThat(marker).as("成功晋升后 promotion 标记清空").isEmpty();
	}

	/** TC-C03-001 空队列分组：一轮 0 候选/0 runtime/0 事件。 */
	@Test
	void tcFix2_c03_001_emptyQueueRoundIsNoOp() {
		PROMOTION_CREATES.set(0);
		PROMOTION_STATES.set(0);
		Long eventBefore = db.sql("SELECT count(*) AS n FROM dh_event").map(r -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(5));
		Integer promoted = context.getBean(DigitalHumanSessionPromotionWorker.class).runOnce()
				.block(Duration.ofSeconds(10));
		assertThat(promoted).as("空队列晋升 0").isZero();
		assertThat(PROMOTION_CREATES.get()).as("空队列零 runtime create").isZero();
		assertThat(PROMOTION_STATES.get()).as("空队列零 state 查询").isZero();
		Long eventAfter = db.sql("SELECT count(*) AS n FROM dh_event").map(r -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(5));
		assertThat(eventAfter).as("空队列零事件").isEqualTo(eventBefore);
	}

	/** TC-C03-001：promotion 反射守卫、上下文绑定、running 防重入/错误释放、enabled=false 首行返回。 */
	@Test
	void tcFix2_c03_001_promotionReflectionReentryAndDisabledGuards() throws Exception {
		// ① 反射守卫：runScheduled 带 @Scheduled 且 fixedDelayString/enabled/running 存在（防误删）。
		assertScheduled(DigitalHumanSessionPromotionWorker.class, "${digital-human.promotion.poll-interval-ms:5000}");

		// ② 上下文绑定：@TestPropertySource 内联覆盖优先于基座静默。
		assertThat(booleanField(context.getBean(DigitalHumanSessionPromotionWorker.class), "enabled"))
				.as("promotion @TestPropertySource 覆盖").isTrue();

		// ③ running CAS：上一轮挂起期间再次触发 → 跳过；完成后释放 → 可再进入。
		Sinks.One<Integer> hang = Sinks.one();
		AtomicInteger calls = new AtomicInteger();
		DigitalHumanSessionPromotionWorker counting = new DigitalHumanSessionPromotionWorker(db, transactions(),
				Clock.systemUTC(), true, sessions, context.getBean(DigitalHumanRuntimeClient.class), events) {
			@Override
			Mono<Integer> runOnce() {
				calls.incrementAndGet();
				return hang.asMono();
			}
		};
		counting.runScheduled();
		counting.runScheduled();
		assertThat(calls.get()).as("第二次进入被 running CAS 跳过").isEqualTo(1);
		hang.tryEmitValue(0);
		awaitWorkerReleased(counting);
		counting.runScheduled();
		assertThat(calls.get()).as("释放后可再次进入").isEqualTo(2);

		// ④ 错误路径也释放 running（doFinally 兜底）。
		DigitalHumanSessionPromotionWorker erroring = new DigitalHumanSessionPromotionWorker(db, transactions(),
				Clock.systemUTC(), true, sessions, context.getBean(DigitalHumanRuntimeClient.class), events) {
			@Override
			Mono<Integer> runOnce() {
				return Mono.error(new IllegalStateException("tcFix2_c03_001 注入失败"));
			}
		};
		erroring.runScheduled();
		awaitWorkerReleased(erroring);

		// ⑤ enabled=false 方法体首行 return：计数 runOnce 证明根本未进入。
		AtomicInteger disabledCalls = new AtomicInteger();
		new DigitalHumanSessionPromotionWorker(db, transactions(), Clock.systemUTC(), false, sessions,
				context.getBean(DigitalHumanRuntimeClient.class), events) {
			@Override
			Mono<Integer> runOnce() {
				disabledCalls.incrementAndGet();
				return Mono.just(0);
			}
		}.runScheduled();
		assertThat(disabledCalls.get()).as("promotion enabled=false 未进入").isZero();
	}

	private static void awaitWorkerReleased(Object worker) throws Exception {
		AtomicBoolean running = (AtomicBoolean) fieldOf(worker, "running");
		long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
		while (System.currentTimeMillis() < deadline) {
			if (!running.get()) {
				return;
			}
			Thread.sleep(50);
		}
		fail("错误路径后 running 未释放");
	}

	private ObjectProvider<DigitalHumanCleanupWorker.RuntimePort> runtimePorts() {
		return new ObjectProvider<>() {
			@Override
			public DigitalHumanCleanupWorker.RuntimePort getObject() {
				throw new UnsupportedOperationException();
			}

			@Override
			public DigitalHumanCleanupWorker.RuntimePort getObject(Object... args) {
				throw new UnsupportedOperationException();
			}

			@Override
			public DigitalHumanCleanupWorker.RuntimePort getIfAvailable() {
				return null;
			}

			@Override
			public DigitalHumanCleanupWorker.RuntimePort getIfUnique() {
				return null;
			}
		};
	}

	private static void assertScheduled(Class<?> type, String expectedFixedDelay) throws Exception {
		var method = type.getMethod("runScheduled");
		Scheduled annotation = method.getAnnotation(Scheduled.class);
		assertThat(annotation).as("%s.runScheduled @Scheduled", type.getSimpleName()).isNotNull();
		assertThat(annotation.fixedDelayString()).as("%s fixedDelayString", type.getSimpleName())
				.isEqualTo(expectedFixedDelay);
		assertThat(type.getDeclaredField("enabled").getType()).isEqualTo(boolean.class);
		assertThat(type.getDeclaredField("running").getType()).isEqualTo(AtomicBoolean.class);
	}

	private static boolean booleanField(Object bean, String name) throws Exception {
		return (Boolean) fieldOf(bean, name);
	}

	private static void awaitReleased(DigitalHumanSessionReaper worker) throws Exception {
		AtomicBoolean running = (AtomicBoolean) fieldOf(worker, "running");
		long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
		while (System.currentTimeMillis() < deadline) {
			if (!running.get()) {
				return;
			}
			Thread.sleep(50);
		}
		fail("错误路径后 running 未释放");
	}

	private static Object fieldOf(Object bean, String name) throws Exception {
		// 匿名子类实例的字段声明在父类——沿继承链向上找。
		for (Class<?> type = bean.getClass(); type != null; type = type.getSuperclass()) {
			try {
				var field = type.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(bean);
			} catch (NoSuchFieldException ignored) {
				// 继续向父类找
			}
		}
		throw new NoSuchFieldException(name + " on " + bean.getClass().getName());
	}
}
