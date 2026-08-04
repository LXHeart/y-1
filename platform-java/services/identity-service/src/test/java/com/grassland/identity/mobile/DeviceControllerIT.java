package com.grassland.identity.mobile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** 设备清单/撤销 IT（GL-P3-IDENTITY-001）。 */
class DeviceControllerIT extends MobileTokenItSupport {

    @Test
    void listDevicesReturnsActiveTokensNewestFirst() {
        String email = "devices-list@example.com";
        String id = seedUser(email, "correct-pass");
        loginForTokens(email, "correct-pass", "Phone A");
        loginForTokens(email, "correct-pass", "Phone B");

        client().get().uri("/api/me/devices")
                .header("Cookie", "y1.sid=" + cookieFor(id))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.devices.length()").isEqualTo(2)
                .jsonPath("$.data.devices[0].device_name").isEqualTo("Phone B")
                .jsonPath("$.data.devices[1].device_name").isEqualTo("Phone A")
                .jsonPath("$.data.devices[0].expires_at").isNotEmpty()
                // cookie 登录态不对应任何 refresh token 行，故没有 current 设备。
                .jsonPath("$.data.devices[0].current").isEqualTo(false);
    }

    @Test
    void listDevicesRequiresAuth() {
        client().get().uri("/api/me/devices").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void deleteDeviceRevokesIt() {
        String email = "devices-delete@example.com";
        String id = seedUser(email, "correct-pass");
        Map<String, Object> tokens = loginForTokens(email, "correct-pass", "Phone A");
        String tokenId = deviceIdOf(id);

        client().delete().uri("/api/me/devices/" + tokenId)
                .header("Cookie", "y1.sid=" + cookieFor(id))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.success").isEqualTo(true);

        assertThat(activeTokenCount(id)).isZero();
        // 撤销后该设备的 refresh token 立即失效。
        client().post().uri("/api/auth/refresh")
                .header("Authorization", "Bearer " + refreshTokenOf(tokens))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void deleteUnknownDeviceReturns404() {
        String id = seedUser("devices-404@example.com", "correct-pass");
        client().delete().uri("/api/me/devices/" + java.util.UUID.randomUUID())
                .header("Cookie", "y1.sid=" + cookieFor(id))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void deleteOtherAccountsDeviceReturns403() {
        String ownerEmail = "devices-owner@example.com";
        String ownerId = seedUser(ownerEmail, "correct-pass");
        loginForTokens(ownerEmail, "correct-pass", "Owner Phone");
        String tokenId = deviceIdOf(ownerId);

        String strangerId = seedUser("devices-stranger@example.com", "correct-pass");
        client().delete().uri("/api/me/devices/" + tokenId)
                .header("Cookie", "y1.sid=" + cookieFor(strangerId))
                .exchange().expectStatus().isForbidden();

        assertThat(activeTokenCount(ownerId)).isEqualTo(1L);
    }

    private String deviceIdOf(String accountId) {
        return db.sql("SELECT id::text FROM refresh_token WHERE account_id = CAST(:id AS uuid) "
                        + "AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 1")
                .bind("id", accountId)
                .map(row -> row.get(0, String.class))
                .one().block();
    }
}
