package com.grassland.identity.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 治理台「初始化商家账号」端到端（任务书 #71 D2/D4/D5/D10）。
 *
 * <p>
 * 覆盖：一次事务三表落库（app_users / identity_profile(merchant, org null) / account_flag
 * 首登改密）+ outbox 只发 IdentityOpened（不发 UserRegistered）； 一次性初始密码可登录且触发强制改密；重复邮箱
 * 409；非管理员 403 / 未登录 401；参数 400。
 */
class AdminMerchantAccountControllerIT extends IdentityItSupport {

	@Test
	void initCreatesAccountFlagProfileAndEventOnly() {
		var admin = seedAdmin("ma-admin@example.com");
		String email = uniqueEmail("merchant-init");

		Map<String, Object> data = initOk(admin.cookie(), email, "张老板");
		String userId = (String) data.get("userId");
		String initialPassword = (String) data.get("initialPassword");
		assertThat(userId).isNotBlank();
		assertThat(data.get("email")).isEqualTo(email);
		assertThat(data.get("displayName")).isEqualTo("张老板");
		assertThat(data.get("mustChangePassword")).isEqualTo(true);

		// app_users：role=user、active
		String storedRole = db.sql("SELECT role FROM app_users WHERE email = :email").bind("email", email)
				.map(r -> r.get(0, String.class)).one().block();
		assertThat(storedRole).isEqualTo("user");
		// identity_profile：恰一条 merchant、organization_id NULL（直读 NULL 列会 NPE，用计数）
		Long merchantProfile = db
				.sql("SELECT count(*) FROM identity_profile"
						+ " WHERE account_id = CAST(:id AS uuid) AND identity_type = 'merchant'"
						+ " AND organization_id IS NULL")
				.bind("id", userId).map(r -> r.get(0, Long.class)).one().block();
		Long totalProfiles = db.sql("SELECT count(*) FROM identity_profile WHERE account_id = CAST(:id AS uuid)")
				.bind("id", userId).map(r -> r.get(0, Long.class)).one().block();
		assertThat(merchantProfile).isEqualTo(1L);
		assertThat(totalProfiles).isEqualTo(1L);
		// account_flag：首登强制改密
		Boolean mustChange = db
				.sql("SELECT must_change_password FROM account_flag WHERE account_id = CAST(:id AS uuid)")
				.bind("id", userId).map(r -> r.get(0, Boolean.class)).one().block();
		assertThat(mustChange).isTrue();
		// outbox：只发 IdentityOpened（D10：不发 UserRegistered、不送注册积分）
		Long opened = db
				.sql("SELECT count(*) FROM outbox"
						+ " WHERE event_type = 'IdentityOpened' AND payload->>'accountId' = :id")
				.bind("id", userId).map(r -> r.get(0, Long.class)).one().block();
		Long registered = db
				.sql("SELECT count(*) FROM outbox" + " WHERE event_type = 'UserRegistered' AND aggregate_id = :id")
				.bind("id", userId).map(r -> r.get(0, Long.class)).one().block();
		assertThat(opened).isEqualTo(1L);
		assertThat(registered).isZero();
		// 初始密码是 16 位无空格明文（形态与 PasswordGenerator 一致）
		assertThat(initialPassword).hasSize(16).doesNotContain(" ");
	}

	@Test
	void initialPasswordCanLoginAndForcesPasswordChange() {
		var admin = seedAdmin("ma-login-admin@example.com");
		String email = uniqueEmail("merchant-login");
		Map<String, Object> data = initOk(admin.cookie(), email, "李老板");
		String initialPassword = (String) data.get("initialPassword");

		client().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"email\":\"" + email + "\",\"password\":\"" + initialPassword + "\"}").exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(true)
				.jsonPath("$.data.user.mustChangePassword").isEqualTo(true);
	}

	@Test
	void duplicateEmailReturns409() {
		var admin = seedAdmin("ma-dup-admin@example.com");
		String email = uniqueEmail("merchant-dup");
		initOk(admin.cookie(), email, "重复邮箱");
		initAccount(admin.cookie(), email, "再来一次").expectStatus().isEqualTo(409).expectBody().jsonPath("$.error")
				.isEqualTo("该邮箱已注册；商家账号仅支持全新邮箱初始化");
	}

	@Test
	void nonAdminAndAnonymousRejected() {
		var user = seedAccount("ma-plain@example.com");
		initAccount(user.cookie(), uniqueEmail("x1"), "普通用户").expectStatus().isForbidden();
		initAccount(null, uniqueEmail("x2"), "匿名").expectStatus().isUnauthorized();
	}

	@Test
	void invalidInputReturns400() {
		var admin = seedAdmin("ma-invalid-admin@example.com");
		initAccount(admin.cookie(), "not-an-email", "无猴子").expectStatus().isBadRequest();
		initAccount(admin.cookie(), uniqueEmail("blank-name"), "   ").expectStatus().isBadRequest();
	}

	/** 提交初始化并断言 201 成功，返回 data 映射。 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> initOk(String cookie, String email, String displayName) {
		Map<String, Object> body = initAccount(cookie, email, displayName).expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		assertThat(body).isNotNull();
		assertThat(body.get("success")).isEqualTo(true);
		return (Map<String, Object>) body.get("data");
	}

	private WebTestClient.ResponseSpec initAccount(String cookie, String email, String displayName) {
		var spec = client().post().uri("/api/admin/merchant-accounts").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"email\":\"" + email + "\",\"displayName\":\"" + displayName + "\"}");
		if (cookie != null) {
			spec = spec.header("Cookie", "y1.sid=" + cookie);
		}
		return spec.exchange();
	}

	private String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID() + "@example.com";
	}
}
