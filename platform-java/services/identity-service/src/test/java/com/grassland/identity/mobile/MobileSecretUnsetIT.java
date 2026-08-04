package com.grassland.identity.mobile;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * secret 未配置时的 fail-closed 行为（GL-P3-IDENTITY-001）：移动端能力全 503，Web cookie 登录照常。
 *
 * <p>刻意不继承 {@link MobileTokenItSupport}——那个基座注入 secret。这里显式置空。
 */
class MobileSecretUnsetIT extends IdentityItSupport {

    @DynamicPropertySource
    static void noSecret(DynamicPropertyRegistry r) {
        r.add("identity.mobile.access-token.secret", () -> "");
    }

    @Test
    void mobileLoginReturns503() {
        seedBcryptUser("unset-login@example.com", "correct-pass");
        client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Device-Info", "{\"os\":\"iOS\"}")
                .bodyValue("{\"email\":\"unset-login@example.com\",\"password\":\"correct-pass\"}")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.success").isEqualTo(false);
    }

    @Test
    void refreshReturns503() {
        client().post().uri("/api/auth/refresh")
                .header("Authorization", "Bearer whatever")
                .exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void revokeReturns503() {
        client().post().uri("/api/auth/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"refresh_token\":\"whatever\"}")
                .exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void webCookieLoginUnaffected() {
        seedBcryptUser("unset-web@example.com", "correct-pass");
        client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"unset-web@example.com\",\"password\":\"correct-pass\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .consumeWith(r -> assertThat(r.getResponseHeaders().getFirst("Set-Cookie")).contains("y1.sid="));
    }

    private void seedBcryptUser(String email, String password) {
        String hash = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(4, password.toCharArray());
        db.sql("INSERT INTO app_users(id, email, password_hash, display_name, role, status) "
                        + "VALUES (CAST(:id AS uuid), :email, :hash, 'Unset User', 'user', 'active')")
                .bind("id", UUID.randomUUID().toString()).bind("email", email).bind("hash", hash)
                .then().block();
    }
}
