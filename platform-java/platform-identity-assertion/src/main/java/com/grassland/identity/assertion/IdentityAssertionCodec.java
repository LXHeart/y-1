package com.grassland.identity.assertion;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Base64;

/**
 * 断言 payload 的 JSON ↔ base64url 编解码（无签名，签名由 {@link IdentityAssertionSigner} 负责）。
 *
 * <p>{@link ObjectMapper} 单例：注册 {@link JavaTimeModule} + 关闭 {@code WRITE_DATES_AS_TIMESTAMPS}
 * （Instant 序列化为 ISO-8601 字符串，payload 可读便于排障）+ 关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES}
 * （前向兼容：后续新增 claim 不破坏旧 verifier）。base64url 无 padding（URL-safe Header 友好）。
 */
public final class IdentityAssertionCodec {

    static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private IdentityAssertionCodec() {}

    /** 序列化 claims → JSON → base64url（无 padding）字符串。 */
    public static String encodePayload(IdentityAssertion assertion) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(assertion);
            return URL_ENCODER.encodeToString(json);
        } catch (Exception error) {
            throw new IdentityAssertionException("failed to encode assertion payload", error);
        }
    }

    /** base64url 字符串 → JSON → claims。非法 base64/JSON 抛 {@link IdentityAssertionException}。 */
    public static IdentityAssertion decodePayload(String payloadBase64Url) {
        try {
            byte[] json = URL_DECODER.decode(payloadBase64Url);
            return MAPPER.readValue(json, IdentityAssertion.class);
        } catch (Exception error) {
            throw new IdentityAssertionException("failed to decode assertion payload", error);
        }
    }
}
