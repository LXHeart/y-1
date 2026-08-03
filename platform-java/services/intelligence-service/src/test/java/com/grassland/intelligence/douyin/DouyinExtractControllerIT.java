package com.grassland.intelligence.douyin;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * {@link DouyinExtractController} 集成测试（草场 GL-P3-MEDIA-001）。
 *
 * <p>resolve 的 HTTP 抓取/重定向守卫由 {@link DouyinResolveServiceTest}（解析逻辑）覆盖；这里 mock
 * {@link DouyinResolveService} 聚焦 controller 层：input 校验（400，文案对齐 legacy schema）、
 * legacy {@code ExtractedDouyinVideoPayload} 契约（含前端强校验的 downloadAudioUrl/usedSession/fetchStage）、
 * token 签发往返（真 tokenCodec，带 UA+Referer 受信头）、HTTP 阶段不可解析时**整体回落 legacy**。
 */
@DisplayName("Douyin extract POST /api/douyin/extract-video（草场 GL-P3-MEDIA-001）")
class DouyinExtractControllerIT extends IntelligenceItSupport {

    private static final String SECRET = "test-douyin-secret-32-chars-min!!";
    private static final String PAGE_URL = "https://www.douyin.com/video/7123456789";

    static final WireMockServer LEGACY = new WireMockServer(0);

    static {
        LEGACY.start();
    }

    @MockitoBean
    private DouyinResolveService resolveService;

    @Autowired
    private DouyinProxyToken tokenCodec;

    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void douyinProps(DynamicPropertyRegistry registry) {
        registry.add("douyin.proxy.token-secret", () -> SECRET);
        registry.add("legacy.backend.base-url", LEGACY::baseUrl);
    }

    @BeforeEach
    void resetAll() {
        reset(resolveService);
        LEGACY.resetAll();
    }

    @Test
    @DisplayName("空 input → 400（legacy schema min 文案）")
    void emptyInputReturns400() {
        client().post().uri("/api/douyin/extract-video").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("input", "   ")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("请输入抖音分享文本或链接");
    }

    @Test
    @DisplayName("无抖音链接 → 400（legacy schema refine 文案）")
    void noDouyinUrlReturns400() {
        client().post().uri("/api/douyin/extract-video").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("input", "https://evil.com/x")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("请输入包含有效抖音 HTTPS 链接的分享文本或链接");
    }

    @Test
    @DisplayName("可解析素材 → 200 + legacy 契约字段 + token 往返验签得 progressive 流")
    void resolvableMaterialReturnsLegacyContractEnvelope() {
        when(resolveService.resolve(anyString())).thenReturn(Mono.just(material(
                "https://v3-web.douyinvod.com/play.mp4", 45L)));

        client().post().uri("/api/douyin/extract-video").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("input", PAGE_URL)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.platform").isEqualTo("douyin")
                .jsonPath("$.data.videoId").isEqualTo("7123456789")
                .jsonPath("$.data.author").isEqualTo("作者")
                .jsonPath("$.data.title").isEqualTo("标题")
                .jsonPath("$.data.coverUrl").isEqualTo("https://p3.douyinpic.com/x.jpg")
                .jsonPath("$.data.durationSeconds").isEqualTo(45)
                // 前端 useDouyinParse 强校验这三个字段（上一版脚手架缺失导致契约违反）
                .jsonPath("$.data.downloadAudioUrl").value(String.class, url ->
                        org.assertj.core.api.Assertions.assertThat(url).startsWith("/api/douyin/audio/"))
                .jsonPath("$.data.usedSession").isEqualTo(false)
                .jsonPath("$.data.fetchStage").isEqualTo("page_json")
                .jsonPath("$.data.proxyVideoUrl").value(String.class, url -> {
                    String tok = url.substring(url.lastIndexOf('/') + 1);
                    DouyinMediaTarget target = tokenCodec.parse(tok);
                    org.assertj.core.api.Assertions.assertThat(target.playableVideoUrl())
                            .isEqualTo("https://v3-web.douyinvod.com/play.mp4");
                    org.assertj.core.api.Assertions.assertThat(target.durationSeconds()).isEqualTo(45L);
                    // 对齐 legacy buildVideoAsset：token 内携带 UA + Referer（proxy 上游请求头）
                    org.assertj.core.api.Assertions.assertThat(target.requestHeaders())
                            .containsEntry("Referer", PAGE_URL)
                            .containsKey("User-Agent");
                })
                .jsonPath("$.data.downloadVideoUrl").value(String.class, url ->
                        org.assertj.core.api.Assertions.assertThat(url).startsWith("/api/douyin/download/"));
    }

    @Test
    @DisplayName("HTTP 阶段解析不出可播放地址（挑战页）→ 整体回落 legacy，透传信封")
    void unresolvableMaterialFallsBackToLegacy() throws Exception {
        when(resolveService.resolve(anyString())).thenReturn(Mono.just(material(null, null)));

        String legacyEnvelope = mapper.writeValueAsString(Map.of(
                "success", true,
                "data", Map.of("platform", "douyin", "usedSession", true, "fetchStage", "browser_network")));
        LEGACY.stubFor(post(urlEqualTo("/api/douyin/extract-video"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(legacyEnvelope)));

        client().post().uri("/api/douyin/extract-video").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("input", PAGE_URL)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.usedSession").isEqualTo(true)
                .jsonPath("$.data.fetchStage").isEqualTo("browser_network");

        // 原请求体转发给 legacy（legacy 走 Playwright/session 增强阶段）。
        LEGACY.verify(postRequestedFor(urlEqualTo("/api/douyin/extract-video"))
                .withRequestBody(containing(PAGE_URL)));
    }

    @Test
    @DisplayName("回落时 legacy 错误信封透传（状态码 + error 文案）")
    void legacyFallbackErrorPropagates() throws Exception {
        when(resolveService.resolve(anyString())).thenReturn(Mono.just(material(null, null)));

        LEGACY.stubFor(post(urlEqualTo("/api/douyin/extract-video"))
                .willReturn(aResponse().withStatus(502).withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"error\":\"未能从抖音页面或浏览器响应中解析到可下载视频地址\"}")));

        client().post().uri("/api/douyin/extract-video").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("input", PAGE_URL)).exchange()
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.error")
                .isEqualTo("未能从抖音页面或浏览器响应中解析到可下载视频地址");
    }

    @Test
    @DisplayName("resolve 错误（超时 504）透传，不回落")
    void resolveErrorPropagates() {
        when(resolveService.resolve(anyString()))
                .thenReturn(Mono.error(new IntelligenceException(504, "请求抖音页面超时")));

        client().post().uri("/api/douyin/extract-video").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("input", PAGE_URL)).exchange()
                .expectStatus().isEqualTo(504)
                .expectBody().jsonPath("$.error").isEqualTo("请求抖音页面超时");
    }

    private static DouyinSourceMaterial material(String playableVideoUrl, Long durationSeconds) {
        return new DouyinSourceMaterial(
                PAGE_URL, PAGE_URL, "7123456789", "作者", "标题",
                "https://p3.douyinpic.com/x.jpg", durationSeconds, playableVideoUrl,
                Map.of(), false, "page_json", false);
    }
}
