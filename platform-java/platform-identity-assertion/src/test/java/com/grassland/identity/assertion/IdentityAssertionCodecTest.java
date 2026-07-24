package com.grassland.identity.assertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 编解码往返全字段（含 nullable）+ 非法输入抛异常。 */
class IdentityAssertionCodecTest {

    private static final Instant ISSUED = Instant.parse("2026-07-23T12:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-07-23T12:01:00Z");
    private static final Instant REAUTH = Instant.parse("2026-07-23T11:55:00Z");

    @Test
    void encodeDecode_roundTripsAllFields() {
        var original = new IdentityAssertion(
                "11111111-1111-1111-1111-111111111111",
                "recommender",
                "sid-xyz",
                "33333333-3333-3333-3333-333333333333",
                "basic_publish",
                "cookie-session",
                "level1",
                REAUTH,
                "req-9",
                "trace-9",
                "grassland-internal",
                ISSUED,
                EXPIRES,
                "user", null);

        IdentityAssertion decoded = IdentityAssertionCodec.decodePayload(IdentityAssertionCodec.encodePayload(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void encodeDecode_roundTripsServiceAssertion() {
        var service = new IdentityAssertion(
                "44444444-4444-4444-4444-444444444444",
                null, null,
                "33333333-3333-3333-3333-333333333333",
                null,
                "service", "internal", null, "req-svc", "trace-svc",
                "grassland-internal", ISSUED, EXPIRES,
                "service", "marketplace");

        IdentityAssertion decoded = IdentityAssertionCodec.decodePayload(IdentityAssertionCodec.encodePayload(service));

        assertThat(decoded).isEqualTo(service);
        assertThat(decoded.isService()).isTrue();
        assertThat(decoded.principal()).isEqualTo("marketplace");
    }

    @Test
    void encodeDecode_preservesNullables() {
        var consumer = new IdentityAssertion(
                "22222222-2222-2222-2222-222222222222", null, "sid-anon", null, null,
                "cookie-session", "level1", null, null, null,
                "grassland-internal", ISSUED, EXPIRES, null, null);

        IdentityAssertion decoded = IdentityAssertionCodec.decodePayload(IdentityAssertionCodec.encodePayload(consumer));

        assertThat(decoded.activeIdentityType()).isNull();
        assertThat(decoded.reauthenticatedAt()).isNull();
        assertThat(decoded.requestId()).isNull();
        assertThat(decoded.traceId()).isNull();
    }

    @Test
    void decode_garbageThrows() {
        assertThatThrownBy(() -> IdentityAssertionCodec.decodePayload("not-valid-base64!@#"))
                .isInstanceOf(IdentityAssertionException.class);
    }

    @Test
    void decode_nonJsonBase64Throws() {
        // valid base64url but not a JSON object → Jackson fails → wrapped exception
        assertThatThrownBy(() -> IdentityAssertionCodec.decodePayload("aGVsbG8")) // "hello"
                .isInstanceOf(IdentityAssertionException.class);
    }

    @Test
    void decode_unknownFieldIgnored_forwardCompatible() {
        // payload with an extra future claim "future_flag" must not break decode
        String json = "{\"accountId\":\"33333333-3333-3333-3333-333333333333\","
                + "\"activeIdentityType\":null,\"sessionToken\":\"s\",\"authMethod\":\"cookie-session\","
                + "\"authStrength\":\"level1\",\"reauthenticatedAt\":null,\"requestId\":null,\"traceId\":null,"
                + "\"audience\":\"grassland-internal\",\"issuedAt\":\"2026-07-23T12:00:00Z\","
                + "\"expiresAt\":\"2026-07-23T12:01:00Z\",\"future_flag\":\"x\"}";
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes());

        IdentityAssertion decoded = IdentityAssertionCodec.decodePayload(payload);

        assertThat(decoded.accountId()).isEqualTo("33333333-3333-3333-3333-333333333333");
    }
}
