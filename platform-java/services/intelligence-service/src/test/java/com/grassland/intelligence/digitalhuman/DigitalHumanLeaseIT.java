package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/**
 * 控制租约 IT（任务书 #105C C105C-02 / TC105C-02-01、02、04）：双页接管/隐藏不续命/终态与迟到回包。
 */
class DigitalHumanLeaseIT extends IntelligenceItSupport {

	@Autowired
	private DatabaseClient db;

	@Autowired
	private DigitalHumanLeaseService leases;

	@Autowired
	private DigitalHumanSessionService sessions;

	private final String account = "dh-c2-" + UUID.randomUUID();
	private final String other = "dh-c2-other-" + UUID.randomUUID();

	/** TC-C03-006「runtime.create/offer 均 0」的计数 fake（resume 路径不得触碰 runtime）。 */
	static final java.util.concurrent.atomic.AtomicInteger RUNTIME_CREATES = new java.util.concurrent.atomic.AtomicInteger();
	static final java.util.concurrent.atomic.AtomicInteger RUNTIME_OFFERS = new java.util.concurrent.atomic.AtomicInteger();

	@org.springframework.boot.test.context.TestConfiguration
	static class CountingRuntimeConfig {

		@Bean
		@Primary
		DigitalHumanRuntimeClient countingRuntime() {
			DigitalHumanRuntimeClient.Transport transport = new DigitalHumanRuntimeClient.Transport() {
				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> createSession(String sessionId, String backendId,
						UUID commandId) {
					RUNTIME_CREATES.incrementAndGet();
					return Mono.just(new DigitalHumanRuntimeClient.RuntimeState(sessionId, "counting", 1, 1,
							"connecting", null, null, false, false));
				}

				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> state(String sessionId) {
					return Mono.error(new com.grassland.intelligence.security.IntelligenceException(404, "dh_not_found",
							"资源不存在。"));
				}

				@Override
				public Mono<DigitalHumanRuntimeClient.RuntimeState> end(String sessionId, UUID commandId,
						String reasonCode) {
					return Mono.just(new DigitalHumanRuntimeClient.RuntimeState(sessionId, "counting", 1, 1, "ended",
							null, null, false, false));
				}

				@Override
				public Mono<DigitalHumanRuntimeClient.RtcAnswer> webrtcOffer(String sessionId, UUID commandId,
						String payloadHash, long leaseEpoch, long mediaEpoch, String sdp) {
					RUNTIME_OFFERS.incrementAndGet();
					return Mono.just(
							new DigitalHumanRuntimeClient.RtcAnswer("v=0\r\ns=counting\r\n", "answer", mediaEpoch));
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

	@org.junit.jupiter.api.BeforeEach
	void resetCounters() {
		RUNTIME_CREATES.set(0);
		RUNTIME_OFFERS.set(0);
	}

	private String seedReadySession() {
		String id = UUID.randomUUID().toString();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision,"
				+ " profile_name_at_creation, backend_id, preflight_id, controller_id, config_snapshot, state,"
				+ " state_entered_at, lease_epoch, media_epoch, lease_expires_at)"
				+ " VALUES (CAST(:id AS uuid), :o, gen_random_uuid(), 1, '角色', 'mock', gen_random_uuid(),"
				+ " CAST(:c AS uuid), '{}'::jsonb, 'ready', now(), 1, 1, now() + interval '30 seconds')").bind("id", id)
				.bind("o", account).bind("c", UUID.randomUUID().toString()).then().block(Duration.ofSeconds(5));
		return id;
	}

	private String state(String sessionId) {
		return db.sql("SELECT state FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(row -> row.get("state", String.class)).one().block(Duration.ofSeconds(5));
	}

	// ---------- TC105C-02-01：双页接管 ----------

	@Test
	void tc105c_02_01_takeoverRotatesEpochAndInvalidatesOldPage() {
		String sessionId = seedReadySession();
		String controller1 = db.sql("SELECT controller_id::text AS c FROM dh_session WHERE id = CAST(:id AS uuid)")
				.bind("id", sessionId).map(row -> row.get("c", String.class)).one().block(Duration.ofSeconds(5));
		// 先经正常 pause 进入 paused（epoch 1→2），A1 持 epoch2/controller1。
		leases.pause(person(), UUID.fromString(sessionId), UUID.randomUUID(), 1, "hidden").block(Duration.ofSeconds(5));
		long epochAfterPause = leaseEpoch(sessionId);
		assertThat(epochAfterPause).isEqualTo(2L);

		// A2（同账号新页）接管：takeover=true → epoch3。
		var resumed = leases.resume(person(), UUID.fromString(sessionId), epochAfterPause, UUID.randomUUID(), true,
				UUID.randomUUID()).block(Duration.ofSeconds(5));
		assertThat(resumed).containsEntry("resumed", true);
		Long epoch3 = leaseEpoch(sessionId);
		assertThat(epoch3).isEqualTo(3L);

		// A1 旧页（epoch2 已失效）：心跳与恢复均 409 dh_lease_stale。
		var staleHeartbeat = expectError(() -> leases
				.heartbeat(person(), UUID.fromString(sessionId), epochAfterPause, UUID.fromString(controller1))
				.block(Duration.ofSeconds(5)));
		assertThat(staleHeartbeat.status()).isEqualTo(409);
		assertThat(staleHeartbeat.code()).isEqualTo("dh_lease_stale");
		var staleResume = expectError(() -> leases.resume(person(), UUID.fromString(sessionId), epochAfterPause,
				UUID.fromString(controller1), true, UUID.randomUUID()).block(Duration.ofSeconds(5)));
		assertThat(staleResume.status()).isEqualTo(409);
		assertThat(staleResume.code()).isEqualTo("dh_lease_stale");

		// 新页不 takeover 直接接管他人会话 → 409 dh_takeover_required。
		var noTakeover = expectError(() -> leases
				.resume(person(), UUID.fromString(sessionId), epoch3, UUID.randomUUID(), false, UUID.randomUUID())
				.block(Duration.ofSeconds(5)));
		assertThat(noTakeover.status()).isEqualTo(409);
		assertThat(noTakeover.code()).isEqualTo("dh_takeover_required");

		// 同页（当前 controller）再 pause→resume 不需 takeover（epoch 继续单调递增）。
		leases.pause(person(), UUID.fromString(sessionId), UUID.randomUUID(), epoch3, "hidden")
				.block(Duration.ofSeconds(5));
		String holder = controllerOf(sessionId);
		var samePage = leases.resume(person(), UUID.fromString(sessionId), leaseEpoch(sessionId),
				UUID.fromString(holder), false, UUID.randomUUID()).block(Duration.ofSeconds(5));
		assertThat(samePage).containsEntry("resumed", true);
		assertThat(leaseEpoch(sessionId)).isGreaterThan(epoch3);
		cleanup(sessionId);
	}

	// ---------- TC105C-02-02：隐藏不能续命 ----------

	@Test
	void tc105c_02_02_pausedUntilIsFixedAndExpiryEndsSession() {
		String sessionId = seedReadySession();
		String controller = controllerOf(sessionId);
		leases.pause(person(), UUID.fromString(sessionId), UUID.randomUUID(), 1, "hidden").block(Duration.ofSeconds(5));
		String pausedUntil = db
				.sql("SELECT to_char(paused_until, 'YYYY-MM-DD HH24:MI:SS') AS p FROM dh_session"
						+ " WHERE id = CAST(:id AS uuid)")
				.bind("id", sessionId).map(row -> row.get("p", String.class)).one().block(Duration.ofSeconds(5));
		assertThat(pausedUntil).isNotNull();

		// 重复 pause（新 requestId）与心跳都不延长 pausedUntil。
		leases.pause(person(), UUID.fromString(sessionId), UUID.randomUUID(), 2, "hidden").block(Duration.ofSeconds(5));
		String after = db
				.sql("SELECT to_char(paused_until, 'YYYY-MM-DD HH24:MI:SS') AS p FROM dh_session"
						+ " WHERE id = CAST(:id AS uuid)")
				.bind("id", sessionId).map(row -> row.get("p", String.class)).one().block(Duration.ofSeconds(5));
		assertThat(after).isEqualTo(pausedUntil);

		// 推进固定时钟越过窗口：reaper 把 paused 收尾 ending（不加新 30 秒）。
		var reaper = new DigitalHumanSessionReaper(db, transactions(), fixedClock(Instant.now().plusSeconds(120)));
		reaper.scanExpired(Instant.now().plusSeconds(120), 100).block(Duration.ofSeconds(10));
		assertThat(state(sessionId)).isEqualTo("ending");
		cleanup(sessionId);
	}

	// ---------- TC105C-02-04：终态与迟到 ready ----------

	@Test
	void tc105c_02_04_endIsIdempotentAndTerminalStays() {
		String sessionId = seedReadySession();
		var first = sessions.end(person(), UUID.fromString(sessionId), UUID.randomUUID(), "user")
				.block(Duration.ofSeconds(5));
		assertThat(first.endedNow()).isTrue();
		assertThat(first.state()).isIn("ending", "ended");
		Integer versionAfterFirst = sessionVersion(sessionId);
		String terminal = state(sessionId);

		// 重复 end（新 requestId）：幂等同终态——状态与 version 不再变化（无第二次收尾副作用）。
		var second = sessions.end(person(), UUID.fromString(sessionId), UUID.randomUUID(), "user")
				.block(Duration.ofSeconds(5));
		assertThat(second.endedNow()).isFalse();
		assertThat(state(sessionId)).isEqualTo(terminal);
		assertThat(sessionVersion(sessionId)).isEqualTo(versionAfterFirst);

		// 迟到 resume/心跳：不复活（状态冲突/租约失效）；owner 槽已释放可再建。
		assertThat(expectError(
				() -> leases.resume(person(), UUID.fromString(sessionId), 1, UUID.randomUUID(), true, UUID.randomUUID())
						.block(Duration.ofSeconds(5)))
				.status()).isEqualTo(409);
		String revived = seedReadySession();
		assertThat(state(revived)).isEqualTo("ready");
		cleanup(sessionId);
		cleanup(revived);
	}

	// ---------- 任务书 105-fix-2 C-03：TC-C03-006 queued 显式接管（RULE-005） ----------

	/**
	 * queued 种子行：state='queued'、epoch=1、controller=c1（owner 唯一活动索引不冲突——每用例一 owner）。
	 */
	private String seedQueued(String owner, String controllerId) {
		String id = UUID.randomUUID().toString();
		db.sql("""
				INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,
				    backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at,
				    lease_epoch, media_epoch, lease_expires_at)
				VALUES (CAST(:id AS uuid), :o, gen_random_uuid(), 1, '角色', 'mock', gen_random_uuid(),
				    CAST(:c AS uuid), '{}'::jsonb, 'queued', now(), 1, 1, now() + interval '30 seconds')
				""").bind("id", id).bind("o", owner).bind("c", UUID.fromString(controllerId)).then()
				.block(Duration.ofSeconds(5));
		return id;
	}

	private record RowMeta(String state, Long epoch, String controller, OffsetDateTime enteredAt,
			OffsetDateTime createdAt, OffsetDateTime leaseExpiresAt) {
	}

	private RowMeta meta(String sessionId) {
		return db.sql("""
				SELECT state, lease_epoch, controller_id::text AS c, state_entered_at, created_at, lease_expires_at
				FROM dh_session WHERE id = CAST(:id AS uuid)
				""").bind("id", sessionId)
				.map(r -> new RowMeta(r.get("state", String.class), r.get("lease_epoch", Long.class),
						r.get("c", String.class), r.get("state_entered_at", OffsetDateTime.class),
						r.get("created_at", OffsetDateTime.class), r.get("lease_expires_at", OffsetDateTime.class)))
				.one().block(Duration.ofSeconds(5));
	}

	@Test
	void tcFix2_c03_006_queuedTakeoverMatrixIdempotencyAndNoRuntime() {
		String c1 = UUID.randomUUID().toString();
		String c2 = UUID.randomUUID().toString();
		String sessionId = seedQueued(account, c1);
		RowMeta before = meta(sessionId);
		assertThat(before.state()).isEqualTo("queued");
		assertThat(before.epoch()).isEqualTo(1L);

		// ① c2（新页，holder=c1）takeover=false → 409 dh_takeover_required，行不变。
		var noTakeover = expectError(() -> leases
				.resume(person(), UUID.fromString(sessionId), 1, UUID.fromString(c2), false, UUID.randomUUID())
				.block(Duration.ofSeconds(5)));
		assertThat(noTakeover.status()).isEqualTo(409);
		assertThat(noTakeover.code()).isEqualTo("dh_takeover_required");
		assertThat(meta(sessionId)).isEqualTo(before);

		// ② B（不同 owner，仅破坏 owner 前提）takeover=true → 404 dh_not_found（不泄露存在性）。
		var cross = expectError(
				() -> leases.resume(new DigitalHumanAuthorization.PersonalActor(other), UUID.fromString(sessionId), 1,
						UUID.fromString(c2), true, UUID.randomUUID()).block(Duration.ofSeconds(5)));
		assertThat(cross.status()).isEqualTo(404);
		assertThat(cross.code()).isEqualTo("dh_not_found");
		assertThat(meta(sessionId)).isEqualTo(before);

		// ③ c2 正确 takeover=true → resumed；state 保持 queued；epoch 2、controller=c2、lease
		// 已刷新；
		// state_entered_at/created_at 不动（排队时钟与 FIFO 不变，接管不续 60s）。
		var resumed = leases
				.resume(person(), UUID.fromString(sessionId), 1, UUID.fromString(c2), true, UUID.randomUUID())
				.block(Duration.ofSeconds(5));
		assertThat(resumed).containsEntry("resumed", true);
		RowMeta afterTakeover = meta(sessionId);
		assertThat(afterTakeover.state()).isEqualTo("queued");
		assertThat(afterTakeover.epoch()).isEqualTo(2L);
		assertThat(afterTakeover.controller()).isEqualTo(c2);
		assertThat(afterTakeover.enteredAt()).isEqualTo(before.enteredAt());
		assertThat(afterTakeover.createdAt()).isEqualTo(before.createdAt());
		assertThat(afterTakeover.leaseExpiresAt()).isAfter(before.leaseExpiresAt());

		// ④ 同 requestId 重放：首次成功（epoch 2→3），重放走 operation 幂等回原状态——epoch 仍 3，不二次轮换。
		UUID replayRequest = UUID.randomUUID();
		leases.resume(person(), UUID.fromString(sessionId), 2, UUID.fromString(c2), false, replayRequest)
				.block(Duration.ofSeconds(5));
		Long epochAfterFirst = meta(sessionId).epoch();
		assertThat(epochAfterFirst).isEqualTo(3L);
		var replayed = leases.resume(person(), UUID.fromString(sessionId), 2, UUID.fromString(c2), false, replayRequest)
				.block(Duration.ofSeconds(5));
		assertThat(replayed).as("重放返回 operation 终态快照").containsEntry("state", "queued");
		assertThat(meta(sessionId).epoch()).as("同操作键重放不增 epoch").isEqualTo(3L);

		// ⑤ c1 旧 epoch(1) → 409 dh_lease_stale（先于状态判定）。
		var stale = expectError(() -> leases
				.resume(person(), UUID.fromString(sessionId), 1, UUID.fromString(c1), true, UUID.randomUUID())
				.block(Duration.ofSeconds(5)));
		assertThat(stale.status()).isEqualTo(409);
		assertThat(stale.code()).isEqualTo("dh_lease_stale");

		// ⑥ preparing：接管面保留原 dh_state_conflict，不改行（不得改正在派发的 epoch）。
		db.sql("UPDATE dh_session SET state = 'preparing' WHERE id = CAST(:id AS uuid)").bind("id", sessionId).then()
				.block(Duration.ofSeconds(5));
		RowMeta preparingBefore = meta(sessionId);
		var preparing = expectError(() -> leases.resume(person(), UUID.fromString(sessionId), preparingBefore.epoch(),
				UUID.fromString(c2), true, UUID.randomUUID()).block(Duration.ofSeconds(5)));
		assertThat(preparing.status()).isEqualTo(409);
		assertThat(preparing.code()).isEqualTo("dh_state_conflict");
		assertThat(meta(sessionId)).as("preparing 行零变化").isEqualTo(preparingBefore);

		// ⑦ 全程 runtime 零调用（接管不派发、不 offer）。
		assertThat(RUNTIME_CREATES.get()).as("runtime.create 零调用").isZero();
		assertThat(RUNTIME_OFFERS.get()).as("webrtcOffer 零调用").isZero();

		// ⑧ queued heartbeat 仍不发送（白名单未含 queued）→ 409。
		var hb = expectError(() -> leases
				.heartbeat(person(), UUID.fromString(sessionId), meta(sessionId).epoch(), UUID.fromString(c2))
				.block(Duration.ofSeconds(5)));
		assertThat(hb.status()).isEqualTo(409);
		cleanup(sessionId);
	}

	/** ⑨ 两页同 epoch 并发接管（屏障同时放行）→ 恰一成功、epoch 只 +1。 */
	@Test
	void tcFix2_c03_006_concurrentTakeoverExactlyOneWinner() throws Exception {
		String c1 = UUID.randomUUID().toString();
		String c2 = UUID.randomUUID().toString();
		String c3 = UUID.randomUUID().toString();
		String sessionId = seedQueued(account, c1);
		java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			java.util.concurrent.Callable<Boolean> page2 = () -> {
				try {
					start.await();
					leases.resume(person(), UUID.fromString(sessionId), 1, UUID.fromString(c2), true, UUID.randomUUID())
							.block(Duration.ofSeconds(5));
					return Boolean.TRUE;
				} catch (Exception expected) {
					return Boolean.FALSE;
				}
			};
			java.util.concurrent.Callable<Boolean> page3 = () -> {
				try {
					start.await();
					leases.resume(person(), UUID.fromString(sessionId), 1, UUID.fromString(c3), true, UUID.randomUUID())
							.block(Duration.ofSeconds(5));
					return Boolean.TRUE;
				} catch (Exception expected) {
					return Boolean.FALSE;
				}
			};
			var futures = java.util.List.of(pool.submit(page2), pool.submit(page3));
			start.countDown();
			int winners = 0;
			for (var future : futures) {
				if (Boolean.TRUE.equals(future.get(10, java.util.concurrent.TimeUnit.SECONDS))) {
					winners++;
				}
			}
			assertThat(winners).as("并发接管恰一成功").isEqualTo(1);
			RowMeta after = meta(sessionId);
			assertThat(after.epoch()).as("epoch 只 +1").isEqualTo(2L);
			assertThat(after.state()).as("state 保持 queued").isEqualTo("queued");
			assertThat(java.util.Set.of(c2, c3)).contains(after.controller());
		} finally {
			pool.shutdownNow();
		}
		cleanup(sessionId);
	}

	// ---------- 助手 ----------

	/** 捕获域错误供断言（不吞非域异常）。 */
	private static com.grassland.intelligence.security.IntelligenceException expectError(Runnable call) {
		try {
			call.run();
		} catch (com.grassland.intelligence.security.IntelligenceException expected) {
			return expected;
		}
		throw new AssertionError("期望 IntelligenceException");
	}

	private DigitalHumanAuthorization.PersonalActor person() {
		return new DigitalHumanAuthorization.PersonalActor(account);
	}

	private org.springframework.transaction.reactive.TransactionalOperator transactions() {
		return context.getBean(org.springframework.transaction.reactive.TransactionalOperator.class);
	}

	@Autowired
	private org.springframework.context.ApplicationContext context;

	private Long leaseEpoch(String sessionId) {
		return db.sql("SELECT lease_epoch FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(row -> row.get("lease_epoch", Long.class)).one().block(Duration.ofSeconds(5));
	}

	private String controllerOf(String sessionId) {
		return db.sql("SELECT controller_id::text AS c FROM dh_session WHERE id = CAST(:id AS uuid)")
				.bind("id", sessionId).map(row -> row.get("c", String.class)).one().block(Duration.ofSeconds(5));
	}

	private Integer sessionVersion(String sessionId) {
		return db.sql("SELECT version FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(row -> row.get("version", Integer.class)).one().block(Duration.ofSeconds(5));
	}

	private static java.time.Clock fixedClock(Instant instant) {
		return java.time.Clock.fixed(instant, ZoneOffset.UTC);
	}

	private void cleanup(String sessionId) {
		db.sql("DELETE FROM dh_event WHERE session_id = CAST(:id AS uuid)").bind("id", sessionId).then()
				.then(db.sql("DELETE FROM dh_transcript WHERE session_id = CAST(:id AS uuid)").bind("id", sessionId)
						.then())
				.then(db.sql("DELETE FROM dh_turn WHERE session_id = CAST(:id AS uuid)").bind("id", sessionId).then())
				.then(db.sql("DELETE FROM dh_session WHERE id = CAST(:id AS uuid) OR owner_account_id = :o")
						.bind("id", sessionId).bind("o", account).then())
				.then(db.sql("DELETE FROM dh_operation WHERE owner_account_id = :o").bind("o", account).then())
				.block(Duration.ofSeconds(5));
	}
}
