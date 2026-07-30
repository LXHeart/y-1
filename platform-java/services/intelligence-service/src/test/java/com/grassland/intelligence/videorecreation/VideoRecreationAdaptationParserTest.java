package com.grassland.intelligence.videorecreation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class VideoRecreationAdaptationParserTest {

    private VideoRecreationAdaptationParser parser(String origin) {
        MockEnvironment env = new MockEnvironment();
        if (origin != null) env.setProperty("app.public-backend-origin", origin);
        return new VideoRecreationAdaptationParser(env);
    }

    private Map<String, Object> body(String platform, String proxy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platform", platform);
        body.put("proxyVideoUrl", proxy);
        body.put("extractedContent", Map.of("videoScript", "脚本内容"));
        return body;
    }

    @Test
    void parsesJsonAndStripsUnknownFields() {
        Map<String, Object> body = body("douyin", "/api/douyin/proxy/token-1");
        body.put("userInstructions", Map.of("scriptInstruction", "要求", "bogus", "x"));
        body.put("unexpected", "ignored");

        VideoRecreationAdaptationRequest req = parser(null).parseJson(body);

        assertThat(req.platform()).isEqualTo("douyin");
        assertThat(req.extractedContent()).containsEntry("videoScript", "脚本内容");
        assertThat(req.userInstructions()).containsOnlyKeys("scriptInstruction");
        assertThat(req.referenceImages()).isEmpty();
    }

    @Test
    void rejectsCrossPlatformProxyPath() {
        assertThatThrownBy(() -> parser(null).parseJson(body("douyin", "/api/bilibili/proxy/token-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAbsoluteProxyWhenOriginUnconfigured() {
        assertThatThrownBy(() -> parser(null).parseJson(body("douyin", "https://evil.example/api/douyin/proxy/t")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsSameOriginAbsoluteProxy() {
        VideoRecreationAdaptationRequest req = parser("https://app.example")
                .parseJson(body("douyin", "https://app.example/api/douyin/proxy/token-1"));
        assertThat(req.platform()).isEqualTo("douyin");
    }

    @Test
    void rejectsEmptyExtractedContent() {
        Map<String, Object> body = body("douyin", "/api/douyin/proxy/token-1");
        body.put("extractedContent", Map.of("videoScript", "   "));
        assertThatThrownBy(() -> parser(null).parseJson(body))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlongExtractedContent() {
        Map<String, Object> body = body("douyin", "/api/douyin/proxy/token-1");
        body.put("extractedContent", Map.of("videoScript", "x".repeat(20_001)));
        assertThatThrownBy(() -> parser(null).parseJson(body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("提取内容过长");
    }

    @Test
    void rejectsNonStringExtractedField() {
        Map<String, Object> body = body("douyin", "/api/douyin/proxy/token-1");
        body.put("extractedContent", Map.of("videoScript", 123));
        assertThatThrownBy(() -> parser(null).parseJson(body))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidPlatform() {
        assertThatThrownBy(() -> parser(null).parseJson(body("kuaishou", "/api/douyin/proxy/token-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
