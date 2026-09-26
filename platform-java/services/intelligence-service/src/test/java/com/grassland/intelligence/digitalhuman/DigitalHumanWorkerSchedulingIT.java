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
@TestPropertySource(properties = {"digital-human.reaper.enabled=true", "digital-human.reaper.poll-interval-ms=250"})
class DigitalHumanWorkerSchedulingIT extends IntelligenceItSupport {

	private static final String OWNER_PREFIX = "dh-wsched-";

	@Autowired
	private DatabaseClient db;

	@Autowired
	private ApplicationContext context;

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
		return db.sql("DELETE FROM dh_session WHERE owner_account_id LIKE :p").bind("p", OWNER_PREFIX + "%").then();
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
