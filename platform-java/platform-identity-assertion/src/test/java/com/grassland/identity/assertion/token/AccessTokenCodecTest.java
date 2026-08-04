package com.grassland.identity.assertion.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** payload 编解码：snake_case 契约 + 前向兼容（未知 claim 忽略）+ 坏输入抛 {@link AccessTokenException}。 */
class AccessTokenCodecTest {

    private static final Instant NOW = Instant.parse("2026-08-04T08:00:00Z");

    @Test
    void roundTrips_allClaims() {
        AccessToken token = AccessToken.issue(
                "11111111-1111-1111-1111-111111111111", "user@example.com", "user",
                "device-abc", "22222222-2222-2222-2222-222222222222", "access-token-v1",
                NOW, Duration.ofSeconds(900));

        AccessToken decoded = AccessTokenCodec.decodePayload(AccessTokenCodec.encodePayload(token));

        assertThat(decoded).isEqualTo(token);
        assertThat(decoded.issuedAt()).isEqualTo(NOW);
        assertThat(decoded.expiresAt()).isEqualTo(NOW.plusSeconds(900));
    }

    @Test
    void roundTrips_nullOptionalClaims() {
        AccessToken token = AccessToken.issue(
                "11111111-1111-1111-1111-111111111111", null, "user", null, "sid-x", "access-token-v1",
                NOW, Duration.ofSeconds(900));

        AccessToken decoded = AccessTokenCodec.decodePayload(AccessTokenCodec.encodePayload(token));

        assertThat(decoded.email()).isNull();
        assertThat(decoded.deviceId()).isNull();
    }

    @Test
    void payloadUsesSnakeCaseKeys() {
        AccessToken token = AccessToken.issue("acct-1", "e@x.com", "user", "d-1", "s-1", "kid-1",
                NOW, Duration.ofSeconds(60));

        String json = new String(Base64.getUrlDecoder().decode(AccessTokenCodec.encodePayload(token)),
                StandardCharsets.UTF_8);

        assertThat(json).contains("\"account_id\"").contains("\"device_id\"").contains("\"session_token\"");
    }

    @Test
    void unknownClaimsIgnored_forwardCompat() {
        String json = "{\"account_id\":\"a\",\"email\":\"e\",\"role\":\"user\",\"future_claim\":42,\"iat\":1,\"exp\":2}";
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        AccessToken decoded = AccessTokenCodec.decodePayload(payload);

        assertThat(decoded.accountId()).isEqualTo("a");
    }

    @Test
    void badBase64_throws() {
        assertThatThrownBy(() -> AccessTokenCodec.decodePayload("!!!not-base64!!!"))
                .isInstanceOf(AccessTokenException.class);
    }

    @Test
    void badJson_throws() {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString("not json".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> AccessTokenCodec.decodePayload(payload))
                .isInstanceOf(AccessTokenException.class);
    }
}
