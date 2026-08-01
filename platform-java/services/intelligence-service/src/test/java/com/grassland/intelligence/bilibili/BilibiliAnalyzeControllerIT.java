package com.grassland.intelligence.bilibili;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link BilibiliAnalyzeController} 集成测试（草场 Slice 13 Stage 5）。
 *
 * <p>覆盖 Java 路径（progressive+≤60s+qwen → Qwen video_url + 扣积分 + 归一）、回落路径（DASH / >60s → 透传 cookie
 * 转发 legacy）、422 时长校验、401 未登录、400 非法地址。平台 Qwen 走基座 {@link IntelligenceItSupport#QWEN} WireMock；
 * legacy 积分扣减 + analyze 回落同走一个 legacy WireMock（生产同在 backend:3000）。
 *
 * <p>归一细节（snake/camel 双态、人物/道具线索回填、video_script 数组→多行）由
 * {@code BilibiliAnalysisResultNormalizerTest} 单测覆盖；此处只断言端到端壳与路由分流。
 */
@DisplayName("Bilibili analyze POST /api/bilibili/analyze-video（草场 Slice 13 Stage 5）")
class BilibiliAnalyzeControllerIT extends IntelligenceItSupport {

    private static final String SECRET = "test-bilibili-secret-32-chars-min!!";
    private static final String PUBLIC_ORIGIN = "https://public.test";
    private static final Map<String, String> HEADERS = Map.of();

    static final WireMockServer LEGACY = new WireMockServer(0);
    static {
        LEGACY.start();
    }

    @Autowired
    private BilibiliProxyToken tokenCodec;

    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void bilibiliProps(DynamicPropertyRegistry registry) {
        registry.add("bilibili.proxy.token-secret", () -> SECRET);
        registry.add("app.public-backend-origin", () -> PUBLIC_ORIGIN);
        registry.add("credits.legacy.base-url", LEGACY::baseUrl);
        registry.add("legacy.backend.base-url", LEGACY::baseUrl);
        registry.add("ai.bilibili-analysis.provider", () -> "qwen");
        registry.add("ai.bilibili-analysis.max-single-segment-seconds", () -> "60");
    }

    @BeforeEach
    void resetStubs() {
        LEGACY.resetAll();
        QWEN.resetAll();
    }

    @Test
    @DisplayName("progressive+≤60s+qwen → 200 + 归一 6 字段 + 扣积分（Java 路径）")
    void progressiveJavaPathReturnsNormalizedAndConsumesCredit() throws Exception {
        stubQwenAnalysis();
        stubCreditsOk();

        String token = tokenCodec.create(new BilibiliMediaTarget.Progressive(
                "https://upos-sz-mirrorali.bilivideo.com/p.mp4", HEADERS, "file.mp4", 30L));

        client().post().uri("/api/bilibili/analyze-video").contentType(MediaType.APPLICATION_JSON)
                .header("X-Grassland-Identity", sign("acct-1", "merchant"))
                .bodyValue(Map.of("proxyVideoUrl", "/api/bilibili/proxy/" + token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.videoCaptions").isEqualTo("[00:01] 旁白")
                .jsonPath("$.data.charactersDescription").isEqualTo("一位女性博主")
                .jsonPath("$.data.propsDescription").isEqualTo("一个白瓷大碗")
                .jsonPath("$.data.runId").isEqualTo("chatcmpl-1");

        LEGACY.verify(postRequestedFor(urlEqualTo("/api/internal/credits/consume"))
                .withRequestBody(containing("\"feature\":\"video_analysis\"")));
    }

    @Test
    @DisplayName("video_url 走公开代理地址（经 PUBLIC_BACKEND_ORIGIN）")
    void javaPathSendsPublicProxyVideoUrl() throws Exception {
        stubQwenAnalysis();
        stubCreditsOk();

        String token = tokenCodec.create(new BilibiliMediaTarget.Progressive(
                "https://upos-sz-mirrorali.bilivideo.com/p.mp4", HEADERS, "file.mp4", 20L));

        client().post().uri("/api/bilibili/analyze-video").contentType(MediaType.APPLICATION_JSON)
                .header("X-Grassland-Identity", sign("acct-1", "merchant"))
                .bodyValue(Map.of("proxyVideoUrl", "/api/bilibili/proxy/" + token))
                .exchange()
                .expectStatus().isOk();

        QWEN.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(containing(PUBLIC_ORIGIN + "/api/bilibili/proxy/" + token))
                .withRequestBody(containing("\"type\":\"video_url\"")));
    }

    @Test
    @DisplayName("缺少时长 → 422")
    void missingDurationReturns422() {
        String token = tokenCodec.create(new BilibiliMediaTarget.Progressive(
                "https://upos-sz-mirrorali.bilivideo.com/p.mp4", HEADERS, "file.mp4", null));

        client().post().uri("/api/bilibili/analyze-video").contentType(MediaType.APPLICATION_JSON)
                .header("X-Grassland-Identity", sign("acct-1", "merchant"))
                .bodyValue(Map.of("proxyVideoUrl", "/api/bilibili/proxy/" + token))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.error").isEqualTo("未能识别视频时长，请重新提取后再分析");
    }

    @Test
    @DisplayName("时长 >10 分钟 → 422")
    void tooLongDurationReturns422() {
        String token = tokenCodec.create(new BilibiliMediaTarget.Progressive(
                "https://upos-sz-mirrorali.bilivideo.com/p.mp4", HEADERS, "file.mp4", 601L));

        client().post().uri("/api/bilibili/analyze-video").contentType(MediaType.APPLICATION_JSON)
                .header("X-Grassland-Identity", sign("acct-1", "merchant"))
                .bodyValue(Map.of("proxyVideoUrl", "/api/bilibili/proxy/" + token))
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.error")
                .isEqualTo("当前仅支持分析 10 分钟以内的 B 站视频，建议选择 30 秒到 2 分钟的视频");
    }

    @Test
    @DisplayName("DASH+≤60s → 回落 legacy（透传响应信封，不调 Qwen/积分）")
    void dashFallsBackToLegacy() throws Exception {
        stubLegacyAnalyzeOk("dash-legacy-result");

        String token = tokenCodec.create(new BilibiliMediaTarget.Dash(
                "https://upos-sz-mirrorali.bilivideo.com/v.m4s",
                "https://upos-sz-mirrorali.bilivideo.com/a.m4s", HEADERS, "file.mp4", 20L));

        client().post().uri("/api/bilibili/analyze-video").contentType(MediaType.APPLICATION_JSON)
                .header("X-Grassland-Identity", sign("acct-1", "merchant"))
                .header("Cookie", "y1.sid=s%3Atok.sig")
                .bodyValue(Map.of("proxyVideoUrl", "/api/bilibili/proxy/" + token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.merged").isEqualTo("dash-legacy-result");

        // 回落：intelligence 不调 Qwen，不扣积分（legacy 自行扣）。
        QWEN.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
        LEGACY.verify(0, postRequestedFor(urlEqualTo("/api/internal/credits/consume")));
        // legacy analyze 收到转发 cookie。
        LEGACY.verify(postRequestedFor(urlEqualTo("/api/bilibili/analyze-video"))
                .withHeader("Cookie", equalTo("y1.sid=s%3Atok.sig")));
    }

    @Test
    @DisplayName("progressive+>60s → 回落 legacy（需切片）")
    void overThresholdProgressiveFallsBackToLegacy() throws Exception {
        stubLegacyAnalyzeOk("segmented-legacy-result");

        String token = tokenCodec.create(new BilibiliMediaTarget.Progressive(
                "https://upos-sz-mirrorali.bilivideo.com/p.mp4", HEADERS, "file.mp4", 90L));

        client().post().uri("/api/bilibili/analyze-video").contentType(MediaType.APPLICATION_JSON)
                .header("X-Grassland-Identity", sign("acct-1", "merchant"))
                .bodyValue(Map.of("proxyVideoUrl", "/api/bilibili/proxy/" + token))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.merged").isEqualTo("segmented-legacy-result");
    }

    @Test
    @DisplayName("未登录 → 401")
    void noAssertionReturns401() {
        client().post().uri("/api/bilibili/analyze-video").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("proxyVideoUrl", "/api/bilibili/proxy/anything"))
                .exchange()
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.error").isEqualTo("未登录");
    }

    @Test
    @DisplayName("缺少 proxyVideoUrl → 400")
    void missingProxyVideoUrlReturns400() {
        client().post().uri("/api/bilibili/analyze-video").contentType(MediaType.APPLICATION_JSON)
                .header("X-Grassland-Identity", sign("acct-1", "merchant"))
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("缺少可分析的视频地址");
    }

    @Test
    @DisplayName("非法 proxyVideoUrl（非白名单源）→ 400")
    void invalidProxyVideoUrlReturns400() {
        client().post().uri("/api/bilibili/analyze-video").contentType(MediaType.APPLICATION_JSON)
                .header("X-Grassland-Identity", sign("acct-1", "merchant"))
                .bodyValue(Map.of("proxyVideoUrl", "https://evil.com/api/bilibili/proxy/x"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("视频代理地址无效");
    }

    private void stubQwenAnalysis() throws Exception {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("video_captions", "[00:01] 旁白");
        content.put("characters_description", "一位女性博主");
        content.put("voice_description", "清亮");
        content.put("props_description", "一个白瓷大碗");
        content.put("scene_description", "面馆");
        String contentJson = mapper.writeValueAsString(content);
        Map<String, Object> response = Map.of(
                "id", "chatcmpl-1",
                "choices", java.util.List.of(Map.of("message", Map.of("content", contentJson))));
        QWEN.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(mapper.writeValueAsString(response))));
    }

    private void stubCreditsOk() {
        LEGACY.stubFor(post(urlEqualTo("/api/internal/credits/consume"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
    }

    private void stubLegacyAnalyzeOk(String merged) throws Exception {
        Map<String, Object> envelope = Map.of("success", true, "data", Map.of("merged", merged));
        LEGACY.stubFor(post(urlEqualTo("/api/bilibili/analyze-video"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(mapper.writeValueAsString(envelope))));
    }
}
