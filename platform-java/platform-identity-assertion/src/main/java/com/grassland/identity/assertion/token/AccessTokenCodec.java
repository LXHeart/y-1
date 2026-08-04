package com.grassland.identity.assertion.token;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;

/**
 * Access token payload 的 JSON ↔ base64url 编解码（无签名，签名由 {@link AccessTokenSigner} 负责）。
 * 镜像 {@link com.grassland.identity.assertion.IdentityAssertionCodec} 的约定。
 *
 * <p>关闭 {@code FAIL_ON_UNKNOWN_PROPERTIES}：前向兼容——后续新增 claim 不破坏旧 verifier。
 * base64url 无 padding（URL-safe Header 友好）。
 */
public final class AccessTokenCodec {

    static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private AccessTokenCodec() {}

    /** 序列化 claims → JSON → base64url（无 padding）字符串。 */
    public static String encodePayload(AccessToken token) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(token);
            return URL_ENCODER.encodeToString(json);
        } catch (Exception error) {
            throw new AccessTokenException("failed to encode access token payload", error);
        }
    }

    /** base64url 字符串 → JSON → claims。非法 base64/JSON 抛 {@link AccessTokenException}。 */
    public static AccessToken decodePayload(String payloadBase64Url) {
        try {
            byte[] json = URL_DECODER.decode(payloadBase64Url);
            return MAPPER.readValue(json, AccessToken.class);
        } catch (Exception error) {
            throw new AccessTokenException("failed to decode access token payload", error);
        }
    }
}
