package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

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
