package com.grassland.identity.mobile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 每账号活跃 refresh token 上限：超出时撤销最旧的（GL-P3-IDENTITY-001）。 */
class RefreshTokenCapIT extends MobileTokenItSupport {

    @DynamicPropertySource
    static void cap(DynamicPropertyRegistry r) {
        r.add("identity.mobile.refresh-token.max-active-per-account", () -> "2");
    }

    @Test
    void thirdLoginRevokesOldestToken() {
        String email = "cap@example.com";
        String id = seedUser(email, "correct-pass");
        Map<String, Object> first = loginForTokens(email, "correct-pass", "Phone 1");
        Map<String, Object> second = loginForTokens(email, "correct-pass", "Phone 2");
        assertThat(activeTokenCount(id)).isEqualTo(2L);

        Map<String, Object> third = loginForTokens(email, "correct-pass", "Phone 3");

        assertThat(activeTokenCount(id)).isEqualTo(2L);
        assertThat(activeDeviceNames(id)).containsExactly("Phone 3", "Phone 2");

        // 最旧的那个 token 已撤销 → 不能再刷新；后两个仍可用。
        client().post().uri("/api/auth/refresh")
                .header("Authorization", "Bearer " + refreshTokenOf(first))
                .exchange().expectStatus().isUnauthorized();
        client().post().uri("/api/auth/refresh")
                .header("Authorization", "Bearer " + refreshTokenOf(second))
                .exchange().expectStatus().isOk();
        client().post().uri("/api/auth/refresh")
                .header("Authorization", "Bearer " + refreshTokenOf(third))
                .exchange().expectStatus().isOk();
    }

    private List<String> activeDeviceNames(String accountId) {
        return db.sql("SELECT device_name FROM refresh_token WHERE account_id = CAST(:id AS uuid) "
                        + "AND revoked_at IS NULL ORDER BY created_at DESC, id DESC")
                .bind("id", accountId)
                .map(row -> row.get(0, String.class))
                .all().collectList().block();
    }
}
