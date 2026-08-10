package com.grassland.intelligence.douyin;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

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
 * token 签发往返（真 tokenCodec，带 UA+Referer 受信头）、HTTP 阶段不可解析时调用 Java 浏览器增强。
 */
@DisplayName("Douyin extract POST /api/douyin/extract-video（草场 GL-P3-MEDIA-001）")
class DouyinExtractControllerIT extends IntelligenceItSupport {

    private static final String SECRET = "test-douyin-secret-32-chars-min!!";
    private static final String PAGE_URL = "https://www.douyin.com/video/7123456789";

    @MockitoBean
    private DouyinResolveService resolveService;

    @MockitoBean
    private DouyinBrowserService browserService;

    @Autowired
    private DouyinProxyToken tokenCodec;

    @DynamicPropertySource
    static void douyinProps(DynamicPropertyRegistry registry) {
        registry.add("douyin.proxy.token-secret", () -> SECRET);
    }

    @BeforeEach
    void resetAll() {
        reset(resolveService, browserService);
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
    @DisplayName("HTTP 阶段不可解析 → Java Playwright/session 增强")
    void unresolvableMaterialUsesJavaBrowser() {
        when(resolveService.resolve(anyString())).thenReturn(Mono.just(material(null, null)));
        DouyinSourceMaterial enhanced = material("https://v3-web.douyinvod.com/play.mp4", 30L);
        enhanced = new DouyinSourceMaterial(enhanced.sourceUrl(), enhanced.resolvedUrl(), enhanced.videoId(),
                enhanced.author(), enhanced.title(), enhanced.coverUrl(), enhanced.durationSeconds(), enhanced.playableVideoUrl(),
                enhanced.requestHeaders(), true, "session_browser", false);
        when(browserService.enhance(anyString())).thenReturn(enhanced);

        client().post().uri("/api/douyin/extract-video").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("input", PAGE_URL)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.usedSession").isEqualTo(true)
                .jsonPath("$.data.fetchStage").isEqualTo("session_browser");
    }

    @Test
    @DisplayName("Java 浏览器增强失败 → 502")
    void browserEnhancementErrorPropagates() {
        when(resolveService.resolve(anyString())).thenReturn(Mono.just(material(null, null)));
        when(browserService.enhance(anyString())).thenReturn(material(null, null));

        client().post().uri("/api/douyin/extract-video").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("input", PAGE_URL)).exchange()
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.error").isEqualTo("抖音未能解析出可播放媒体地址，请稍后重试");
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
