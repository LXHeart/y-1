package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * 回收器测试（任务书 #105C C105C-02 / TC105C-02-03）：Java/浏览器消失后的独立回收。
 *
 * <p>
 * 固定时钟推进 + fake transport（网络隔断语义：runtime 不可达不阻塞回收）；不真实 sleep 45 秒（真实 kill 归
 * H）。断言：心跳失联先 reconnecting、窗口过后 ending；slot 释放（owner 活动唯一允许新会话）；费用/账务
 * 不在本卡（pending 独立，D 阶段结算）。
 */
class DigitalHumanReaperTest extends IntelligenceItSupport {

	@Autowired
	private DatabaseClient db;

	@Autowired
	private ApplicationContext context;

	private final String account = "dh-reap-" + UUID.randomUUID();

	@org.junit.jupiter.api.BeforeEach
	void clearSharedSessions() {
		// 共享容器跨类自愈（基座对 ai_run 同款语义）：回收器扫描是全局的，别类残留的
		// dh_session/dh_operation 行会污染 claimed 计数。
		db.sql("DELETE FROM dh_operation").then().then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_session").then()).block(Duration.ofSeconds(10));
	}

	private String seedSession(String state, java.time.Duration heartbeatAge) {
		return seedSession(state, heartbeatAge, account);
	}

	private String seedSession(String state, java.time.Duration heartbeatAge, String owner) {
		String id = UUID.randomUUID().toString();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision,"
				+ " profile_name_at_creation, backend_id, preflight_id, controller_id, config_snapshot, state,"
				+ " state_entered_at, lease_epoch, media_epoch, lease_expires_at, last_browser_heartbeat_at)"
				+ " VALUES (CAST(:id AS uuid), :o, gen_random_uuid(), 1, '角色', 'mock', gen_random_uuid(),"
				+ " gen_random_uuid(), '{}'::jsonb, :state, :entered, 1, 1,"
				+ " :heartbeat + interval '30 seconds', :heartbeat)").bind("id", id).bind("o", owner)
				.bind("state", state).bind("entered", java.time.OffsetDateTime.now().minusSeconds(60))
				.bind("heartbeat", java.time.OffsetDateTime.now().minus(heartbeatAge)).then()
				.block(Duration.ofSeconds(5));
		return id;
	}

	@Test
	void tc105c_02_03_browserLossReleasesWithinWindowWithoutRealSleep() {
		// ready 场：最后心跳 31s 前（>30s 失联阈值）→ 先 reconnecting（保留 30s 恢复窗）。
		String sessionId = seedSession("ready", java.time.Duration.ofSeconds(31));
		var reaper = reaper(Instant.now().plusSeconds(31));
		var first = reaper.scanExpired(Instant.now().plusSeconds(31), 100).block(Duration.ofSeconds(10));
		assertThat(first.claimed()).isGreaterThanOrEqualTo(1);
		assertThat(state(sessionId)).isEqualTo("reconnecting");

		// 再推 31s（窗口外）：reconnecting → ending；再推进后终态保持（ending 由 end 流程/D 收口）。
		reaper(Instant.now().plusSeconds(62)).scanExpired(Instant.now().plusSeconds(62), 100)
				.block(Duration.ofSeconds(10));
		assertThat(state(sessionId)).isEqualTo("ending");

		// queued 60s 超时 → ending；connecting 90s 超时 → failed（cleanup_pending 置位）。
		// 每 seed 独立 owner（owner 活动唯一约束）。
		String queued = seedSession("queued", java.time.Duration.ofSeconds(120), account + "-q");
		reaper(Instant.now().plusSeconds(120)).scanExpired(Instant.now().plusSeconds(120), 100)
				.block(Duration.ofSeconds(10));
		assertThat(state(queued)).isEqualTo("ending");
		String connecting = seedSession("connecting", java.time.Duration.ofSeconds(150), account + "-c");
		reaper(Instant.now().plusSeconds(150)).scanExpired(Instant.now().plusSeconds(150), 100)
				.block(Duration.ofSeconds(10));
		assertThat(state(connecting)).isEqualTo("failed");
		assertThat(cleanupPending(connecting)).isTrue();

		// slot 释放：ending 仍占 owner 活动位（K05：非 ended/failed 均算活动）；只有推进到
		// ended 后同 owner 才可再建（failed 同理由 connecting 场展示）。
		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> seedSession("ready", java.time.Duration.ZERO, account + "-q"))
				.hasMessageContaining("uq_dh_session_owner_active");
		db.sql("UPDATE dh_session SET state = 'ended', ended_at = now(), version = version + 1"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", queued).then().block(Duration.ofSeconds(5));
		String revived = seedSession("ready", java.time.Duration.ZERO, account + "-q");
		assertThat(state(revived)).isEqualTo("ready");
		cleanupAll();
	}

	private Boolean cleanupPending(String sessionId) {
		return db.sql("SELECT cleanup_pending FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(row -> row.get("cleanup_pending", Boolean.class)).one().block(Duration.ofSeconds(5));
	}

	private String state(String sessionId) {
		return db.sql("SELECT state FROM dh_session WHERE id = CAST(:id AS uuid)").bind("id", sessionId)
				.map(row -> row.get("state", String.class)).one().block(Duration.ofSeconds(5));
	}

	private DigitalHumanSessionReaper reaper(Instant at) {
		return new DigitalHumanSessionReaper(db,
				context.getBean(org.springframework.transaction.reactive.TransactionalOperator.class),
				java.time.Clock.fixed(at, ZoneOffset.UTC));
	}

	private void cleanupAll() {
		db.sql("DELETE FROM dh_session WHERE owner_account_id = :o").bind("o", account).then()
				.then(db.sql("DELETE FROM dh_operation WHERE owner_account_id = :o").bind("o", account).then())
				.block(Duration.ofSeconds(5));
	}
}
