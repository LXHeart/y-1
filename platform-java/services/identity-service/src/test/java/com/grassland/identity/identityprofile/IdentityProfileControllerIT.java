package com.grassland.identity.identityprofile;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 端到端验证 IdentityProfile 活动身份（草场身份域 Slice 2G）。继承 {@link IdentityItSupport}。
 *
 * <p>
 * 2026-09-04 身份模型改版（任务书 #71 D2/D5）后开通端点收紧：merchant 自助开通一律 403（唯一
 * 来源=治理台初始化）；recommender 仅存量裸账号补开，已有任何档案 409。覆盖：裸账号开推荐官 +IdentityOpened
 * 事件、merchant 403、重复开通 409、激活/切换/切回消费者、激活未开通 409、 ActiveIdentityChanged 事件、无
 * cookie 401。outbox 计数按 account 限定。
 */
class IdentityProfileControllerIT extends IdentityItSupport {

	@Test
	void openRecommenderListAndEvent() {
		var acc = seedAccount("ip-rec@example.com");
		client().post().uri("/api/me/identities").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + acc.cookie()).bodyValue("{\"type\":\"recommender\"}").exchange()
				.expectStatus().isCreated().expectBody().jsonPath("$.data.identityType").isEqualTo("recommender")
				.jsonPath("$.data.organizationId").isEmpty();

		client().get().uri("/api/me/identities").header("Cookie", "y1.sid=" + acc.cookie()).exchange().expectStatus()
				.isOk().expectBody().jsonPath("$.data.length()").isEqualTo(1).jsonPath("$.data[0].identityType")
				.isEqualTo("recommender");

		Long count = db
				.sql("SELECT COUNT(*)::int AS c FROM outbox"
						+ " WHERE event_type = 'IdentityOpened' AND payload->>'accountId' = :acct")
				.bind("acct", acc.accountId()).map(r -> r.get("c", Integer.class)).one().block().longValue();
		assertThat(count).isEqualTo(1);
	}

	/** 商家身份唯一来源=治理台初始化：自助开通（带或不带 organizationId）一律 403。 */
	@Test
	void openMerchantSelfServiceForbidden() {
		var acc = seedAccount("ip-merchant@example.com");
		client().post().uri("/api/me/identities").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + acc.cookie()).bodyValue("{\"type\":\"merchant\"}").exchange()
				.expectStatus().isForbidden().expectBody().jsonPath("$.error").isEqualTo("商家身份由平台初始化，不支持自助开通");
		// 带 organizationId 的旧形态同样 403（organizationId 已恒忽略）。
		client().post().uri("/api/me/identities").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + acc.cookie())
				.bodyValue("{\"type\":\"merchant\",\"organizationId\":\"" + UUID.randomUUID() + "\"}").exchange()
				.expectStatus().isForbidden();
	}

	/** D5 双向收紧：已有任何身份档案的账号（含仅商家档案）不可再自助开推荐官。 */
	@Test
	void duplicateOpenReturns409() {
		var acc = seedAccount("ip-dup@example.com");
		openIdentity(acc.cookie(), "recommender").expectStatus().isCreated();
		client().post().uri("/api/me/identities").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + acc.cookie()).bodyValue("{\"type\":\"recommender\"}").exchange()
				.expectStatus().isEqualTo(409).expectBody().jsonPath("$.error").isEqualTo("该账号已有身份档案，无需再开通");
	}

	@Test
	void merchantOnlyAccountCannotOpenRecommender() {
		var acc = seedAccount("ip-merchant-only@example.com");
		insertProfile(acc.accountId(), "merchant");
		client().post().uri("/api/me/identities").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + acc.cookie()).bodyValue("{\"type\":\"recommender\"}").exchange()
				.expectStatus().isEqualTo(409);
	}

	@Test
	void activateSwitchAndDeactivate() {
		var acc = seedAccount("ip-act@example.com");
		// 双身份账号只能由平台/存量数据构成（自助开通已收紧），测试直插双档案。
		insertProfile(acc.accountId(), "merchant");
		insertProfile(acc.accountId(), "recommender");

		// 激活 merchant
		client().post().uri("/api/me/active-identity").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + acc.cookie()).bodyValue("{\"type\":\"merchant\"}").exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.activeIdentityType").isEqualTo("merchant");

		// 切到 recommender（同一时间仅一个）
		client().post().uri("/api/me/active-identity").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + acc.cookie()).bodyValue("{\"type\":\"recommender\"}").exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.activeIdentityType").isEqualTo("recommender");

		// GET 当前活动身份
		client().get().uri("/api/me/active-identity").header("Cookie", "y1.sid=" + acc.cookie()).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.activeIdentityType").isEqualTo("recommender");

		// 切回消费者 → per-session identity_session.active_identity_type 为 NULL（COUNT 避免可空列入
		// Flux）
		client().delete().uri("/api/me/active-identity").header("Cookie", "y1.sid=" + acc.cookie()).exchange()
				.expectStatus().isOk();
		Long nullActive = db
				.sql("SELECT COUNT(*)::int AS c FROM identity_session"
						+ " WHERE account_id = CAST(:acct AS uuid) AND active_identity_type IS NULL")
				.bind("acct", acc.accountId()).map(r -> r.get("c", Integer.class)).one().block().longValue();
		assertThat(nullActive).isEqualTo(1);
	}

	@Test
	void activateUnopenedReturns409() {
		var acc = seedAccount("ip-unopen@example.com");
		client().post().uri("/api/me/active-identity").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + acc.cookie()).bodyValue("{\"type\":\"recommender\"}").exchange()
				.expectStatus().isEqualTo(409);
	}

	/** V13 只回填迁移执行前的历史 profile；运行时新画像绝不能成为绕过显式开通的身份授权。 */
	@Test
	void runtimeProfileWithoutOpenedIdentityStillCannotActivate() {
		var acc = seedAccount("ip-runtime-profile@example.com");
		db.sql("INSERT INTO recommender_profile(account_id) VALUES (CAST(:acct AS uuid))").bind("acct", acc.accountId())
				.then().block();

		client().post().uri("/api/me/active-identity").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + acc.cookie()).bodyValue("{\"type\":\"recommender\"}").exchange()
				.expectStatus().isEqualTo(409).expectBody().jsonPath("$.error").isEqualTo("未开通该身份，请先开通");
	}

	@Test
	void activeIdentityChangedEvent() {
		var acc = seedAccount("ip-evt@example.com");
		openIdentity(acc.cookie(), "recommender");
		client().post().uri("/api/me/active-identity").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + acc.cookie()).bodyValue("{\"type\":\"recommender\"}").exchange()
				.expectStatus().isOk();

		Long count = db
				.sql("SELECT COUNT(*)::int AS c FROM outbox"
						+ " WHERE event_type = 'ActiveIdentityChanged' AND aggregate_id = :acct")
				.bind("acct", acc.accountId()).map(r -> r.get("c", Integer.class)).one().block().longValue();
		assertThat(count).isEqualTo(1);
	}

	@Test
	void rejectsRequestsWithoutSessionCookie() {
		client().get().uri("/api/me/identities").exchange().expectStatus().isUnauthorized();
		client().post().uri("/api/me/active-identity").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"type\":\"recommender\"}").exchange().expectStatus().isUnauthorized();
	}

	private WebTestClient.ResponseSpec openIdentity(String cookie, String type) {
		return client().post().uri("/api/me/identities").contentType(MediaType.APPLICATION_JSON)
				.header("Cookie", "y1.sid=" + cookie).bodyValue("{\"type\":\"" + type + "\"}").exchange();
	}

	/** 直插身份档案（绕过已收紧的开通端点，模拟存量/平台初始化账号）。 */
	private void insertProfile(String accountId, String identityType) {
		db.sql("INSERT INTO identity_profile(id, account_id, identity_type, status) "
				+ "VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), :type, 'active')")
				.bind("id", UUID.randomUUID().toString()).bind("acct", accountId).bind("type", identityType).then()
				.block();
	}
}
