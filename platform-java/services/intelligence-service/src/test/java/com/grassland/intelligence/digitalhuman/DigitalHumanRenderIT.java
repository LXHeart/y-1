package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * 渲染经济面 IT（任务书 #105D C105D-05 / 共享契约 §9.1 补充验证、K14.3）：真实 DB 经济幂等（render
 * invocation resourceId=sessionId/segment=0/turn=null 稳定键）与第三方网络边界
 * fake——无配置拒绝、不产生 第二 run；真实远端协议未接通（REAL_NOT_RUN）明确 503。
 */
class DigitalHumanRenderIT extends IntelligenceItSupport {

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

	private UUID seedSession() {
		UUID sessionId = UUID.randomUUID();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,"
				+ " backend_id, preflight_id, controller_id, config_snapshot, state, state_entered_at, lease_epoch)"
				+ " VALUES (CAST(:s AS uuid), :owner, CAST(:p AS uuid), 1, '角色', 'backend-1', CAST(:pf AS uuid),"
				+ " CAST(:c AS uuid), CAST('{}' AS jsonb), 'connecting', now(), 1)").bind("s", sessionId.toString())
				.bind("owner", account).bind("p", UUID.randomUUID().toString()).bind("pf", UUID.randomUUID().toString())
				.bind("c", UUID.randomUUID().toString()).then().block(Duration.ofSeconds(10));
		return sessionId;
	}

	@Test
	void missingConfigRejectsWithoutEconomicWrites() {
		UUID sessionId = seedSession();
		assertThatThrownBy(() -> renders.createSessionInvocation(actor, sessionId, UUID.randomUUID())
				.block(Duration.ofSeconds(10))).isInstanceOfSatisfying(IntelligenceException.class, e -> {
					assertThat(e.status()).isEqualTo(409);
					assertThat(e.code()).isEqualTo("dh_configuration_changed");
				});
		assertThat(db.sql("SELECT count(*) AS n FROM dh_invocation WHERE stage = 'render'")
				.map(r -> r.get("n", Long.class)).one().block(Duration.ofSeconds(10))).as("缺配置拒绝不得产生经济键").isZero();
	}

	@Test
	void unapprovedProtocolExplicitlyRejected() {
		// 有配置但协议未注册适配（本卡未接通任何真实远端协议）：显式 409，不猜兼容。
		db.sql("INSERT INTO platform_model_config(capability, model_role, provider, model, base_url, health_status,"
				+ " enabled, version) VALUES ('digital_human_render', 'primary', 'omnirt', 'm1',"
				+ " 'https://render.invalid/v1', 'healthy', true, 1)").then().block(Duration.ofSeconds(10));
		assertThatThrownBy(() -> renders.resolve().block(Duration.ofSeconds(10))).isInstanceOfSatisfying(
				IntelligenceException.class, e -> assertThat(e.code()).isEqualTo("dh_configuration_changed"));
		assertThat(db.sql("SELECT count(*) AS n FROM dh_invocation WHERE stage = 'render'")
				.map(r -> r.get("n", Long.class)).one().block(Duration.ofSeconds(10))).isZero();
	}

	@Test
	void connectionGrantRenewalRealNotRun() {
		// K14.2 INTERNAL15：真实协议未接通 → 明确 503（不新建 run、不发放媒体资格）。
		assertThatThrownBy(() -> renders.renewConnectionGrant(UUID.randomUUID(), account, UUID.randomUUID(), 1)
				.block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(503));
	}
}
