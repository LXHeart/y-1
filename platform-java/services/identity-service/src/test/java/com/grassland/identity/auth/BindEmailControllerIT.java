package com.grassland.identity.auth;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.security.Argon2PasswordHasher;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 子账号绑定邮箱端到端（任务书 #49 D10/S4）。验证码不经 SMTP（IT 直插已知码），
 * 锁定：绑定闭环（username/新邮箱双可登录）、错码 4xx、邮箱被占 409、事件落 outbox。
 */
class BindEmailControllerIT extends IdentityItSupport {

    private static final String PASSWORD = "BindPass12345";

    @Autowired
    private Argon2PasswordHasher argon2Hasher;

    @Test
    void bindEmail_success_bothIdentifiersCanLogin_andEventEmitted() {
        String username = "bindtest-zhang9";
        var seeded = seedAccount(username + "@sub.grassland.invalid");
        db.sql("INSERT INTO account_username(account_id, username) VALUES (CAST(:id AS uuid), :name)")
                .bind("id", seeded.accountId()).bind("name", username).then().block();
        seedPassword(seeded.accountId());

        String newEmail = "bound-zhang9@example.com";
        insertCode(newEmail, "654321");

        client().post().uri("/api/me/bind-email")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + seeded.cookie())
                .bodyValue("{\"email\":\"" + newEmail + "\",\"code\":\"654321\"}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.bound").isEqualTo(true);

        // 账号名与新邮箱均可登录（双查不变）
        login(username, PASSWORD).expectStatus().isOk();
        login(newEmail, PASSWORD).expectStatus().isOk();

        Long events = db.sql("SELECT COUNT(*)::int AS c FROM outbox"
                        + " WHERE event_type = 'EmailBound' AND payload->>'accountId' = :acct")
                .bind("acct", seeded.accountId())
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        org.assertj.core.api.Assertions.assertThat(events).isEqualTo(1);
    }

    @Test
    void wrongCode_rejected_withoutTouchingEmail() {
        var seeded = seedAccount("bindtest-wrong@sub.grassland.invalid");
        insertCode("wrong-target@example.com", "111222");

        client().post().uri("/api/me/bind-email")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + seeded.cookie())
                .bodyValue("{\"email\":\"wrong-target@example.com\",\"code\":\"000000\"}")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("验证码错误或已过期");

        String email = db.sql("SELECT email FROM app_users WHERE id = CAST(:id AS uuid)")
                .bind("id", seeded.accountId()).map(r -> r.get("email", String.class)).one().block();
        org.assertj.core.api.Assertions.assertThat(email).isEqualTo("bindtest-wrong@sub.grassland.invalid");
    }

    @Test
    void takenEmail_rejected409() {
        var seeded = seedAccount("bindtest-take@sub.grassland.invalid");
        seedAccount("already-taken@example.com");
        insertCode("already-taken@example.com", "333444");

        client().post().uri("/api/me/bind-email")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + seeded.cookie())
                .bodyValue("{\"email\":\"already-taken@example.com\",\"code\":\"333444\"}")
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("该邮箱已被其他账号使用");
    }

    @Test
    void malformedEmail_rejected400() {
        var seeded = seedAccount("bindtest-bad@sub.grassland.invalid");
        client().post().uri("/api/me/bind-email")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + seeded.cookie())
                .bodyValue("{\"email\":\"not-an-email\",\"code\":\"123456\"}")
                .exchange().expectStatus().isBadRequest();
    }

    // ---------- helpers ----------

    private void seedPassword(String accountId) {
        db.sql("UPDATE app_users SET password_hash = :hash WHERE id = CAST(:id AS uuid)")
                .bind("hash", argon2Hasher.hash(PASSWORD)).bind("id", accountId).then().block();
    }

    private void insertCode(String email, String code) {
        db.sql("INSERT INTO email_verification_codes(email, code, expires_at)"
                        + " VALUES (:email, :code, now() + interval '5 minutes')")
                .bind("email", email).bind("code", code).then().block();
    }

    private WebTestClient.ResponseSpec login(String identifier, String password) {
        return client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", identifier, "password", password))
                .exchange();
    }
}
