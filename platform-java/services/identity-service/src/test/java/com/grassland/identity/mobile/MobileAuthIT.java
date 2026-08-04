package com.grassland.identity.mobile;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.assertion.token.AccessTokenCodec;
import com.grassland.identity.assertion.token.AccessToken;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** 移动端登录/刷新/撤销主链路 IT（GL-P3-IDENTITY-001）。 */
class MobileAuthIT extends MobileTokenItSupport {

    @Test
    void loginWithDeviceInfoIssuesTokensWithoutCookie() {
        String id = seedUser("mobile-login@example.com", "correct-pass");
        client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Device-Info", DEVICE_INFO)
                .header("X-Device-Name", "iPhone 16")
                .bodyValue("{\"email\":\"mobile-login@example.com\",\"password\":\"correct-pass\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.user.id").isEqualTo(id)
                .jsonPath("$.data.tokens.expires_in").isEqualTo(900)
                .jsonPath("$.data.tokens.access_token").isNotEmpty()
                .jsonPath("$.data.tokens.refresh_token").isNotEmpty()
                // 移动端无 cookie jar，刻意不发 Set-Cookie。
                .consumeWith(r -> assertThat(r.getResponseHeaders().getFirst("Set-Cookie")).isNull());

        assertThat(activeTokenCount(id)).isEqualTo(1L);
    }

    @Test
    void loginWithoutDeviceInfoStillUsesCookiePath() {
        seedUser("web-path@example.com", "correct-pass");
        client().post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"web-path@example.com\",\"password\":\"correct-pass\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.tokens").doesNotExist()
                .consumeWith(r -> assertThat(r.getResponseHeaders().getFirst("Set-Cookie")).contains("y1.sid="));
    }

    @Test
    void accessTokenCarriesAccountAndSessionClaims() {
        String id = seedUser("claims@example.com", "correct-pass");
        Map<String, Object> tokens = loginForTokens("claims@example.com", "correct-pass", "Pixel 9");
        String raw = (String) tokens.get("access_token");

        AccessToken decoded = AccessTokenCodec.decodePayload(raw.substring(0, raw.indexOf('.')));
        assertThat(decoded.accountId()).isEqualTo(id);
        assertThat(decoded.email()).isEqualTo("claims@example.com");
        assertThat(decoded.role()).isEqualTo("user");
        assertThat(decoded.kid()).isEqualTo("access-token-v1");
        assertThat(decoded.exp() - decoded.iat()).isEqualTo(900);
        // session_token = refresh_token 行 id，下游按会话解析 active identity 时用。
        Long matching = db.sql("SELECT count(*) FROM refresh_token WHERE id = CAST(:id AS uuid)")
                .bind("id", decoded.sessionToken())
                .map(row -> row.get(0, Long.class)).one().block();
        assertThat(matching).isEqualTo(1L);
    }

    @Test
    void refreshWithBearerHeaderReturnsNewAccessTokenAndSameRefreshToken() {
        seedUser("refresh-bearer@example.com", "correct-pass");
        Map<String, Object> tokens = loginForTokens("refresh-bearer@example.com", "correct-pass", "iPad");
        String refresh = refreshTokenOf(tokens);

        client().post().uri("/api/auth/refresh")
                .header("Authorization", "Bearer " + refresh)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.access_token").isNotEmpty()
                .jsonPath("$.data.expires_in").isEqualTo(900)
                // 本版不轮换：响应不带新 refresh token，客户端继续用原来那枚（契约见设计文档 §刷新）。
                .jsonPath("$.data.refresh_token").doesNotExist();

        // 原 refresh token 仍然可用（不轮换的直接后果）。
        client().post().uri("/api/auth/refresh")
                .header("Authorization", "Bearer " + refresh)
                .exchange().expectStatus().isOk();
    }

    @Test
    void refreshWithJsonBodyWorksToo() {
        seedUser("refresh-body@example.com", "correct-pass");
        String refresh = refreshTokenOf(loginForTokens("refresh-body@example.com", "correct-pass", "Android"));

        client().post().uri("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"refresh_token\":\"" + refresh + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.access_token").isNotEmpty();
    }

    @Test
    void refreshWithUnknownTokenReturns401() {
        client().post().uri("/api/auth/refresh")
                .header("Authorization", "Bearer not-a-real-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.success").isEqualTo(false);
    }

    @Test
    void refreshWithoutAnyTokenReturns401() {
        client().post().uri("/api/auth/refresh")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void revokedTokenCannotRefresh() {
        String id = seedUser("revoke-single@example.com", "correct-pass");
        String refresh = refreshTokenOf(loginForTokens("revoke-single@example.com", "correct-pass", "Phone A"));

        client().post().uri("/api/auth/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"refresh_token\":\"" + refresh + "\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.revoked").isEqualTo(1);

        assertThat(activeTokenCount(id)).isZero();

        client().post().uri("/api/auth/refresh")
                .header("Authorization", "Bearer " + refresh)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void revokeAllDevicesKillsEveryToken() {
        String id = seedUser("revoke-all@example.com", "correct-pass");
        String first = refreshTokenOf(loginForTokens("revoke-all@example.com", "correct-pass", "Phone A"));
        refreshTokenOf(loginForTokens("revoke-all@example.com", "correct-pass", "Phone B"));
        assertThat(activeTokenCount(id)).isEqualTo(2L);

        client().post().uri("/api/auth/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"refresh_token\":\"" + first + "\",\"all_devices\":true}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.revoked").isEqualTo(2);

        assertThat(activeTokenCount(id)).isZero();
    }

    @Test
    void revokeWithUnknownTokenReturns401() {
        client().post().uri("/api/auth/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"refresh_token\":\"nope\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void revokeIsIdempotent() {
        seedUser("revoke-twice@example.com", "correct-pass");
        String refresh = refreshTokenOf(loginForTokens("revoke-twice@example.com", "correct-pass", "Phone A"));
        client().post().uri("/api/auth/revoke").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"refresh_token\":\"" + refresh + "\"}")
                .exchange().expectStatus().isOk();
        // 第二次：行已撤销 → 查不到活跃 token → 401（而非 500），且不会再改行。
        client().post().uri("/api/auth/revoke").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"refresh_token\":\"" + refresh + "\"}")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void revokeWritesAuditLog() {
        String id = seedUser("revoke-audit@example.com", "correct-pass");
        String refresh = refreshTokenOf(loginForTokens("revoke-audit@example.com", "correct-pass", "Phone A"));
        client().post().uri("/api/auth/revoke").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"refresh_token\":\"" + refresh + "\"}")
                .exchange().expectStatus().isOk();

        Long rows = db.sql("SELECT count(*) FROM identity_audit_log WHERE account_id = CAST(:id AS uuid) "
                        + "AND action = 'token_revoke'")
                .bind("id", id).map(row -> row.get(0, Long.class)).one().block();
        assertThat(rows).isEqualTo(1L);
    }
}
