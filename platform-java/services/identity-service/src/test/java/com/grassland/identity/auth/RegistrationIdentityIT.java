package com.grassland.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 注册即推荐官（2026-09-04 身份模型改版，任务书 #71 D1）：注册事务内一律创建 recommender
 * 身份档案（裸账号概念退役）；initialIdentity 请求组件已删，旧客户端多发该字段按未知 JSON 字段忽略，不报错（Jackson
 * 默认行为）。
 */
class RegistrationIdentityIT extends IdentityItSupport {

	@Test
	void registrationCreatesRecommenderProfileAndEmitsBothEvents() {
		String email = uniqueEmail("rec");
		String code = "654321";
		seedCode(email, code);

		client().post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(registerBody(email, code, null)).exchange().expectStatus().isCreated().expectHeader()
				.exists("Set-Cookie").expectBody().jsonPath("$.success").isEqualTo(true).jsonPath("$.data.user.email")
				.isEqualTo(email).jsonPath("$.data.initialIdentity").isEqualTo("recommender");

		String accountId = db.sql("SELECT id::text FROM app_users WHERE email = :email").bind("email", email)
				.map(row -> row.get(0, String.class)).one().block();
		String storedType = db.sql("SELECT identity_type FROM identity_profile WHERE account_id = CAST(:id AS uuid)")
				.bind("id", accountId).map(row -> row.get(0, String.class)).one().block();
		// organization_id 为 NULL 列，r2dbc 直读会 NPE，改用 IS NULL 计数断言。
		Long orgNullCount = db
				.sql("SELECT count(*) FROM identity_profile"
						+ " WHERE account_id = CAST(:id AS uuid) AND organization_id IS NULL")
				.bind("id", accountId).map(row -> row.get(0, Long.class)).one().block();
		Long profileCount = db.sql("SELECT count(*) FROM identity_profile WHERE account_id = CAST(:id AS uuid)")
				.bind("id", accountId).map(row -> row.get(0, Long.class)).one().block();

		assertThat(storedType).isEqualTo("recommender");
		assertThat(orgNullCount).isEqualTo(1L);
		assertThat(profileCount).isEqualTo(1L);

		Long userRegistered = db
				.sql("SELECT count(*) FROM outbox" + " WHERE event_type = 'UserRegistered' AND aggregate_id = :acct")
				.bind("acct", accountId).map(row -> row.get(0, Long.class)).one().block();
		Long identityOpened = db
				.sql("SELECT count(*) FROM outbox"
						+ " WHERE event_type = 'IdentityOpened' AND payload->>'accountId' = :acct")
				.bind("acct", accountId).map(row -> row.get(0, Long.class)).one().block();
		assertThat(userRegistered).isEqualTo(1L);
		assertThat(identityOpened).isEqualTo(1L);
	}

	/** 旧客户端兼容：请求体多带 initialIdentity（已删组件=未知字段）→ 仍正常注册为推荐官。 */
	@Test
	void legacyExtraInitialIdentityFieldIsIgnored() {
		String email = uniqueEmail("legacy");
		String code = "654321";
		seedCode(email, code);

		client().post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(registerBody(email, code, "merchant")).exchange().expectStatus().isCreated().expectBody()
				.jsonPath("$.success").isEqualTo(true).jsonPath("$.data.initialIdentity").isEqualTo("recommender");

		String accountId = db.sql("SELECT id::text FROM app_users WHERE email = :email").bind("email", email)
				.map(row -> row.get(0, String.class)).one().block();
		String storedType = db.sql("SELECT identity_type FROM identity_profile WHERE account_id = CAST(:id AS uuid)")
				.bind("id", accountId).map(row -> row.get(0, String.class)).one().block();
		assertThat(storedType).isEqualTo("recommender");
	}

	private String registerBody(String email, String code, String legacyInitialIdentity) {
		String identityField = legacyInitialIdentity == null
				? ""
				: ",\"initialIdentity\":\"" + legacyInitialIdentity + "\"";
		return "{\"email\":\"" + email + "\",\"password\":\"correct-pass\","
				+ "\"confirmPassword\":\"correct-pass\",\"displayName\":\"New User\"," + "\"verificationCode\":\""
				+ code + "\"" + identityField + "}";
	}

	private void seedCode(String email, String code) {
		db.sql("INSERT INTO email_verification_codes(email, code, expires_at) "
				+ "VALUES (:email, :code, now() + interval '10 minutes')").bind("email", email).bind("code", code)
				.then().block();
	}

	private String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID() + "@example.com";
	}
}
