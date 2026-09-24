package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.TurnReceipt;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * 轮次 IT（任务书 #105D C105D-05 / TC105D-05-04 + turn 语义）：只允许 ready 单活动 turn、幂等
 * requestId、旧 epoch 打断不伤新轮；render 配置缺失/停用 → 明确 409 拒绝，不假成功。
 */
class DigitalHumanTurnIT extends IntelligenceItSupport {

	@Autowired
	DigitalHumanTurnService turns;
	@Autowired
	DigitalHumanRenderService renders;
	@Autowired
	DatabaseClient db;

	private final String account = UUID.randomUUID().toString();
	private final PersonalActor actor = new PersonalActor(account);

	@BeforeEach
	void seed() {
		db.sql("DELETE FROM dh_invocation").then().then(db.sql("DELETE FROM dh_event").then())
				.then(db.sql("DELETE FROM dh_transcript").then()).then(db.sql("DELETE FROM dh_turn").then())
				.then(db.sql("DELETE FROM dh_operation").then()).then(db.sql("DELETE FROM dh_session").then())
				.then(db.sql("DELETE FROM platform_model_config WHERE capability = 'digital_human_render'").then())
				.block(Duration.ofSeconds(10));
	}

	private UUID seedSession(String state, long leaseEpoch, long nextTurnEpoch) {
		UUID sessionId = UUID.randomUUID();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, lease_epoch,"
				+ " next_turn_epoch) VALUES (CAST(:s AS uuid), :owner, CAST(:p AS uuid), 1, '角色', 'backend-1',"
				+ " CAST(:pf AS uuid), CAST(:c AS uuid), CAST('{}' AS jsonb), :state, now(), :lease, :nextEpoch)")
				.bind("s", sessionId.toString()).bind("owner", account).bind("p", UUID.randomUUID().toString())
				.bind("pf", UUID.randomUUID().toString()).bind("c", UUID.randomUUID().toString()).bind("state", state)
				.bind("lease", leaseEpoch).bind("nextEpoch", nextTurnEpoch).then().block(Duration.ofSeconds(10));
		return sessionId;
	}

	@Test
	void tc105d_05_04_renderConfigMissingRejectsNotFakeSuccess() {
		// 配置缺失（每测清空 digital_human_render 行）：catalog/资格面 409，不假成功、零派发。
		assertThatThrownBy(() -> renders.resolve().block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> {
					assertThat(e.status()).isEqualTo(409);
					assertThat(e.code()).isEqualTo("dh_configuration_changed");
				});
		// 停用行同样拒绝：插入 enabled=false 行。
		db.sql("INSERT INTO platform_model_config(capability, model_role, provider, model, base_url, health_status,"
				+ " enabled, version) VALUES ('digital_human_render', 'primary', 'some-remote', 'm1',"
				+ " 'https://render.invalid/v1', 'healthy', false, 1)").then().block(Duration.ofSeconds(10));
		assertThatThrownBy(() -> renders.resolve().block(Duration.ofSeconds(10))).isInstanceOfSatisfying(
				IntelligenceException.class, e -> assertThat(e.code()).isEqualTo("dh_configuration_changed"));
		assertThat(db.sql("SELECT count(*) AS n FROM dh_invocation WHERE stage = 'render'")
				.map(r -> r.get("n", Long.class)).one().block(Duration.ofSeconds(10))).isZero();
		// 控制面（INTERNAL14/15 面）远端协议未接通：明确 503 REAL_NOT_RUN，不假成功。
		assertThatThrownBy(
				() -> renders
						.control(new DigitalHumanRenderService.ControlCommand(UUID.randomUUID(), account,
								UUID.randomUUID().toString(), 1, "interrupt", null, null))
						.block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(503));
	}

	@Test
	void readySingleActiveTurnWithIdempotencyAndEpochRules() {
		UUID sessionId = seedSession("ready", 3, 5);
		UUID requestId = UUID.randomUUID();
		TurnReceipt first = turns.startTurn(actor, sessionId, requestId, 3, "帮我把卖点改成三句口播。")
				.block(Duration.ofSeconds(10));
		assertThat(first.turnEpoch()).isEqualTo(5L); // 分配递增前的 next_turn_epoch（旧值语义）
		assertThat(db.sql("SELECT state FROM dh_session WHERE id = CAST(:s AS uuid)").bind("s", sessionId.toString())
				.map(r -> r.get("state", String.class)).one().block(Duration.ofSeconds(10))).isEqualTo("responding");
		// 幂等：同 requestId 返回原 turn。
		TurnReceipt replay = turns.startTurn(actor, sessionId, requestId, 3, "帮我把卖点改成三句口播。")
				.block(Duration.ofSeconds(10));
		assertThat(replay.id()).isEqualTo(first.id());
		// 非 ready（responding 中无活动 turn 结束）：第二个不同 requestId → 409 单活动 turn。
		assertThatThrownBy(
				() -> turns.startTurn(actor, sessionId, UUID.randomUUID(), 3, "第二条").block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_state_conflict"));
		// 打断：目标 turn 落 interrupted、session 回 ready、mediaEpoch 递增。
		var interrupted = turns
				.interrupt(actor, sessionId, UUID.randomUUID(), 3, UUID.fromString(first.id()), first.turnEpoch())
				.block(Duration.ofSeconds(10));
		assertThat(interrupted.get("effective")).isEqualTo(true);
		assertThat((Long) interrupted.get("nextMediaEpoch")).isEqualTo(2L);
		// 已终态 turn 再打断：幂等无效果（effective=false），不打断新轮。
		var noop = turns
				.interrupt(actor, sessionId, UUID.randomUUID(), 3, UUID.fromString(first.id()), first.turnEpoch())
				.block(Duration.ofSeconds(10));
		assertThat(noop.get("effective")).isEqualTo(false);
		// 打断后可开新轮，epoch 更大。
		TurnReceipt next = turns.startTurn(actor, sessionId, UUID.randomUUID(), 3, "新的一轮")
				.block(Duration.ofSeconds(10));
		assertThat(next.turnEpoch()).isEqualTo(6L);
	}

	@Test
	void nonReadyOrStaleLeaseRejectedBeforeSideEffects() {
		UUID paused = seedSession("paused", 2, 1);
		assertThatThrownBy(
				() -> turns.startTurn(actor, paused, UUID.randomUUID(), 2, "x").block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_state_conflict"));
		db.sql("UPDATE dh_session SET state = 'ended', state_entered_at = now() WHERE id = CAST(:s AS uuid)")
				.bind("s", paused.toString()).then().block(Duration.ofSeconds(10));
		UUID session = seedSession("ready", 9, 1);
		assertThatThrownBy(
				() -> turns.startTurn(actor, session, UUID.randomUUID(), 8, "x").block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						e -> assertThat(e.code()).isEqualTo("dh_state_conflict"));
		assertThat(db.sql("SELECT count(*) AS n FROM dh_turn WHERE session_id = CAST(:s AS uuid)")
				.bind("s", session.toString()).map(r -> r.get("n", Long.class)).one().block(Duration.ofSeconds(10)))
				.isZero();
	}
}
