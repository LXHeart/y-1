package com.grassland.intelligence.douyin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DouyinProxyToken} 单测（草场 GL-P3-MEDIA-001）。覆盖签发/验签往返、篡改 403、过期 410、
 * 结构非法 400、非 https 上游 400、请求头白名单、短密钥 500，以及**跨实现兼容**：legacy 抖音
 * payload 无 {@code kind} 字段（legacy douyin-proxy.service.ts），parse 按 progressive 处理。
 */
@DisplayName("DouyinProxyToken（对齐 legacy douyin-proxy.service.ts + 跨实现兼容）")
class DouyinProxyTokenTest {

    private static final String SECRET = "test-douyin-secret-32-chars-min!!";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DouyinProxyToken codec = new DouyinProxyToken(new DouyinProxyTokenProperties(SECRET, 0));

    @Test
    @DisplayName("create→parse 往返：url/headers/filename/duration 保真")
    void roundTripPreservesTarget() {
        DouyinMediaTarget target = DouyinMediaTarget.progressive(
                "https://www.douyinvod.com/video.mp4",
                Map.of("User-Agent", "UA", "Referer", "https://www.douyin.com/video/1"),
                "标题-作者-1.mp4", 45L);

        DouyinMediaTarget parsed = codec.parse(codec.create(target));

        assertThat(parsed.kind()).isEqualTo("progressive");
        assertThat(parsed.playableVideoUrl()).isEqualTo("https://www.douyinvod.com/video.mp4");
        assertThat(parsed.requestHeaders()).containsEntry("User-Agent", "UA")
                .containsEntry("Referer", "https://www.douyin.com/video/1");
        assertThat(parsed.filename()).isEqualTo("标题-作者-1.mp4");
        assertThat(parsed.durationSeconds()).isEqualTo(45L);
    }

    @Test
    @DisplayName("create 只保留 referer/user-agent 头（对齐 legacy allowedProxyRequestHeaderNames）")
    void createSanitizesHeaders() {
        DouyinMediaTarget target = DouyinMediaTarget.progressive(
                "https://www.douyinvod.com/video.mp4",
                Map.of("User-Agent", "UA", "X-Evil", "1", "Cookie", "y"),
                null, null);

        DouyinMediaTarget parsed = codec.parse(codec.create(target));

        assertThat(parsed.requestHeaders()).containsOnlyKeys("User-Agent");
    }

    @Test
    @DisplayName("篡改签名 → 403")
    void tamperedSignatureReturns403() {
        String token = codec.create(DouyinMediaTarget.progressive(
                "https://www.douyinvod.com/video.mp4", null, null, null));
        String tampered = token.substring(0, token.length() - 2) + "zz";

        assertThatThrownBy(() -> codec.parse(tampered))
                .isInstanceOfSatisfying(IntelligenceException.class,
                        e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    @DisplayName("结构非法（无点/空段）→ 400")
    void malformedTokenReturns400() {
        assertThatThrownBy(() -> codec.parse("nodothere"))
                .isInstanceOfSatisfying(IntelligenceException.class,
                        e -> assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(() -> codec.parse(".sig"))
                .isInstanceOfSatisfying(IntelligenceException.class,
                        e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    @DisplayName("过期 token → 410")
    void expiredTokenReturns410() throws Exception {
        DouyinProxyToken shortLived = new DouyinProxyToken(new DouyinProxyTokenProperties(SECRET, 1));
        String token = shortLived.create(DouyinMediaTarget.progressive(
                "https://www.douyinvod.com/video.mp4", null, null, null));
        Thread.sleep(10);

        assertThatThrownBy(() -> codec.parse(token))
                .isInstanceOfSatisfying(IntelligenceException.class,
                        e -> assertThat(e.status()).isEqualTo(410));
    }

    @Test
    @DisplayName("payload 上游地址非 https → 400")
    void nonHttpsUpstreamReturns400() {
        String token = signPayload(payloadJson("http://www.douyinvod.com/video.mp4", true));

        assertThatThrownBy(() -> codec.parse(token))
                .isInstanceOfSatisfying(IntelligenceException.class,
                        e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    @DisplayName("legacy 无 kind 字段的 token → 按 progressive 解析（跨实现兼容）")
    void legacyTokenWithoutKindParsesAsProgressive() {
        String token = signPayload(payloadJson("https://www.douyinvod.com/video.mp4", false));

        DouyinMediaTarget parsed = codec.parse(token);

        assertThat(parsed.kind()).isEqualTo("progressive");
        assertThat(parsed.playableVideoUrl()).isEqualTo("https://www.douyinvod.com/video.mp4");
        assertThat(parsed.durationSeconds()).isEqualTo(30L);
    }

    @Test
    @DisplayName("kind 非 progressive → 400（抖音无 DASH）")
    void unknownKindReturns400() {
        String json = payloadJson("https://www.douyinvod.com/video.mp4", false)
                .replace("\"v\":1", "\"v\":1,\"kind\":\"dash\"");
        String token = signPayload(json);

        assertThatThrownBy(() -> codec.parse(token))
                .isInstanceOfSatisfying(IntelligenceException.class,
                        e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    @DisplayName("密钥缺失/过短 → create/parse 均 500（懒校验）")
    void shortSecretReturns500() {
        DouyinProxyToken unconfigured = new DouyinProxyToken(new DouyinProxyTokenProperties("short", 0));

        assertThatThrownBy(() -> unconfigured.create(DouyinMediaTarget.progressive(
                "https://www.douyinvod.com/video.mp4", null, null, null)))
                .isInstanceOfSatisfying(IntelligenceException.class,
                        e -> assertThat(e.status()).isEqualTo(500));
        assertThatThrownBy(() -> unconfigured.parse("abc.def"))
                .isInstanceOfSatisfying(IntelligenceException.class,
                        e -> assertThat(e.status()).isEqualTo(500));
    }

    // ---------------------------------------------------------------- helpers

    private String payloadJson(String playableUrl, boolean withKind) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        payload.put("exp", System.currentTimeMillis() + 900_000L);
        if (withKind) {
            payload.put("kind", "progressive");
        }
        payload.put("durationSeconds", 30);
        payload.put("playableVideoUrl", playableUrl);
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String signPayload(String json) {
        try {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String encoded = encoder.encodeToString(json.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return encoded + "." + encoder.encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
