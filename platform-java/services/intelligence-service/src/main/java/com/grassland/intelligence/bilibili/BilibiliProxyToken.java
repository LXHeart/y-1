package com.grassland.intelligence.bilibili;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Bilibili 代理 token 编解码（移植 legacy {@code server/src/services/bilibili-proxy.service.ts}）。
 *
 * <p>格式 {@code <base64url(payload)>.<base64url(HMAC-SHA256)>}；payload {@code {v:1,exp,kind,...}}；
 * 验签用常量时间比较（{@link MessageDigest#isEqual}）；过期→410、签名不符→403、结构非法→400、上游地址非 https→400。
 * requestHeaders 经 {referer,user-agent,origin} 白名单清洗（与 legacy 一致）。secret 与 legacy 共享，懒校验。
 */
@Component
public final class BilibiliProxyToken {

    static final int MIN_SECRET_LENGTH = 32;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final Set<String> ALLOWED_HEADER_NAMES = Set.of("referer", "user-agent", "origin");
    private static final int VERSION = 1;

    private final byte[] secret;
    private final Duration ttl;

    public BilibiliProxyToken(BilibiliProxyTokenProperties properties) {
        this.secret = properties.tokenSecret() == null ? null : properties.tokenSecret().getBytes(StandardCharsets.UTF_8);
        this.ttl = Duration.ofMillis(properties.tokenTtlMs());
    }

    public String create(BilibiliMediaTarget target) {
        ensureSecret();
        long exp = System.currentTimeMillis() + ttl.toMillis();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", VERSION);
        payload.put("exp", exp);
        payload.put("kind", target.kind());
        sanitizeHeaders(target.requestHeaders()).ifPresent(h -> payload.put("requestHeaders", h));
        if (target.filename() != null) {
            payload.put("filename", target.filename());
        }
        if (target.durationSeconds() != null) {
            payload.put("durationSeconds", target.durationSeconds());
        }
        switch (target) {
            case BilibiliMediaTarget.Progressive p -> payload.put("playableVideoUrl", p.playableVideoUrl());
            case BilibiliMediaTarget.Dash d -> {
                payload.put("videoTrackUrl", d.videoTrackUrl());
                payload.put("audioTrackUrl", d.audioTrackUrl());
            }
        }
        String encoded = encodeJson(payload);
        return encoded + "." + sign(encoded);
    }

    public BilibiliMediaTarget parse(String token) {
        ensureSecret();
        if (token == null) {
            throw new IntelligenceException(400, "视频代理凭证无效");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot >= token.length() - 1) {
            throw new IntelligenceException(400, "视频代理凭证无效");
        }
        String encoded = token.substring(0, dot);
        String signature = token.substring(dot + 1);
        String expected = sign(encoded);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new IntelligenceException(403, "视频代理凭证无效");
        }

        JsonNode payload = decodeJson(encoded);
        int version = payload.path("v").asInt(-1);
        long exp = payload.path("exp").asLong(0L);
        String kind = payload.path("kind").asText("");
        if (version != VERSION || exp == 0L || kind.isEmpty()) {
            throw new IntelligenceException(400, "视频代理凭证无效");
        }
        if (exp < System.currentTimeMillis()) {
            throw new IntelligenceException(410, "视频代理凭证已过期");
        }

        Map<String, String> headers = sanitizeHeaders(decodeHeaders(payload.path("requestHeaders"))).orElse(null);
        String filename = readStringField(payload, "filename");
        Long duration = readDuration(payload.path("durationSeconds"));

        if ("progressive".equals(kind)) {
            String url = payload.path("playableVideoUrl").asText("");
            if (url.isEmpty()) {
                throw new IntelligenceException(400, "视频代理凭证无效");
            }
            requireHttps(url);
            return new BilibiliMediaTarget.Progressive(url, headers, filename, duration);
        }
        if ("dash".equals(kind)) {
            String video = payload.path("videoTrackUrl").asText("");
            String audio = payload.path("audioTrackUrl").asText("");
            if (video.isEmpty() || audio.isEmpty()) {
                throw new IntelligenceException(400, "视频代理凭证无效");
            }
            requireHttps(video);
            requireHttps(audio);
            return new BilibiliMediaTarget.Dash(video, audio, headers, filename, duration);
        }
        throw new IntelligenceException(400, "视频代理凭证无效");
    }

    private void ensureSecret() {
        if (secret == null || secret.length < MIN_SECRET_LENGTH) {
            throw new IntelligenceException(500, "未配置 Bilibili 代理密钥（BILIBILI_PROXY_TOKEN_SECRET 须 ≥32 字符）");
        }
    }

    private String sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] signature = mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8));
            return URL_ENCODER.withoutPadding().encodeToString(signature);
        } catch (Exception e) {
            throw new IntelligenceException(500, "视频代理凭证签名失败");
        }
    }

    private static String encodeJson(Map<String, Object> payload) {
        try {
            return URL_ENCODER.encodeToString(MAPPER.writeValueAsBytes(payload));
        } catch (Exception e) {
            throw new IntelligenceException(500, "视频代理凭证编码失败");
        }
    }

    private static JsonNode decodeJson(String encoded) {
        try {
            return MAPPER.readTree(URL_DECODER.decode(encoded));
        } catch (Exception e) {
            throw new IntelligenceException(400, "视频代理凭证无效");
        }
    }

    private static void requireHttps(String url) {
        try {
            if (!"https".equalsIgnoreCase(java.net.URI.create(url).getScheme())) {
                throw new IntelligenceException(400, "视频代理凭证无效");
            }
        } catch (IntelligenceException e) {
            throw e;
        } catch (Exception e) {
            throw new IntelligenceException(400, "视频代理凭证无效");
        }
    }

    /** 仅保留 {referer,user-agent,origin}（小写键）的字符串值；空→ empty。对齐 legacy {@code sanitizeProxyRequestHeaders}。 */
    private static Optional<Map<String, String>> sanitizeHeaders(Map<String, String> input) {
        if (input == null || input.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        input.forEach((name, value) -> {
            if (value != null && ALLOWED_HEADER_NAMES.contains(name.toLowerCase())) {
                sanitized.put(name, value);
            }
        });
        return sanitized.isEmpty() ? Optional.empty() : Optional.of(sanitized);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> decodeHeaders(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && value.isTextual()) {
                headers.put(entry.getKey(), value.asText());
            }
        });
        return headers;
    }

    private static String readStringField(JsonNode payload, String field) {
        if (payload.has(field) && payload.get(field).isTextual()) {
            return payload.get(field).asText();
        }
        return null;
    }

    private static Long readDuration(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        double value = node.asDouble();
        if (!Double.isFinite(value) || value <= 0) {
            return null;
        }
        return (long) Math.ceil(value);
    }
}
