package com.grassland.intelligence.bilibili;

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
 * {@link BilibiliExtractController} 集成测试（草场 Slice 13 Stage 3）。
 *
 * <p>resolve 的 HTTP 抓取/WBI 已由 {@link BilibiliResolveServiceTest}（解析逻辑）+
 * {@code VideoRangeProxyTest}/{@code DouyinHotItemsControllerIT}（同构 HTTP 模式）覆盖；这里 mock
 * {@link BilibiliResolveService} 聚焦 controller 层：input 校验（400）、token 签发往返（真 tokenCodec）、
 * progressive/DASH 响应壳、resolve 错误透传。page 守卫静态锁定（SSRF 边界不放开），故 resolve 走 mock。
 */
@DisplayName("Bilibili extract POST /api/bilibili/extract-video（草场 Slice 13 Stage 3）")
class BilibiliExtractControllerIT extends IntelligenceItSupport {

    private static final String SECRET = "test-bilibili-secret-32-chars-min!!";
    private static final String PAGE_URL = "https://www.bilibili.com/video/BV1x";
    private static final Map<String, String> HEADERS = Map.of(
            "referer", PAGE_URL, "user-agent", "UA", "origin", "https://www.bilibili.com");

    @MockitoBean
    private BilibiliResolveService resolveService;

    @Autowired
    private BilibiliProxyToken tokenCodec;

    @DynamicPropertySource
    static void bilibiliProps(DynamicPropertyRegistry registry) {
        registry.add("bilibili.proxy.token-secret", () -> SECRET);
    }

    @BeforeEach
    void resetMock() {
        reset(resolveService);
    }

    @Test
    @DisplayName("空 input → 400")
    void emptyInputReturns400() {
        client().post().uri("/api/bilibili/extract-video").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("input", "   ")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("请输入包含 B 站链接的分享文本或链接");
    }

    @Test
    @DisplayName("无 B 站链接 → 400")
    void noBilibiliUrlReturns400() {
        client().post().uri("/api/bilibili/extract-video").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("input", "https://evil.com/x")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("请输入包含有效 B 站 HTTPS 链接的分享文本或链接");
    }

    @Test
    @DisplayName("progressive resolve → 200 + 字段 + token 往返验签得 progressive 流")
    void progressiveResolveReturnsEnvelope() {
        when(resolveService.resolve(anyString())).thenReturn(Mono.just(new BilibiliSourceMaterial.Progressive(
                PAGE_URL, PAGE_URL, "BV1x", "作者", "标题", "https://pic/x.jpg", 120L,
                "https://upos-sz-mirrorali.bilivideo.com/progressive.mp4", HEADERS)));

        client().post().uri("/api/bilibili/extract-video").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("input", PAGE_URL)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.platform").isEqualTo("bilibili")
                .jsonPath("$.data.videoId").isEqualTo("BV1x")
                .jsonPath("$.data.author").isEqualTo("作者")
                .jsonPath("$.data.title").isEqualTo("标题")
                .jsonPath("$.data.coverUrl").isEqualTo("https://pic/x.jpg")
                .jsonPath("$.data.durationSeconds").isEqualTo(120)
                .jsonPath("$.data.playbackMode").isEqualTo("progressive")
                .jsonPath("$.data.proxyVideoUrl").value(String.class, url -> {
                    String tok = url.substring(url.lastIndexOf('/') + 1);
                    BilibiliMediaTarget target = tokenCodec.parse(tok);
                    org.assertj.core.api.Assertions.assertThat(target)
                            .isInstanceOf(BilibiliMediaTarget.Progressive.class)
                            .extracting(t -> ((BilibiliMediaTarget.Progressive) t).playableVideoUrl())
                            .isEqualTo("https://upos-sz-mirrorali.bilivideo.com/progressive.mp4");
                })
                .jsonPath("$.data.downloadVideoUrl").value(String.class, url ->
                        org.assertj.core.api.Assertions.assertThat(url).startsWith("/api/bilibili/download/"));
    }

    @Test
    @DisplayName("DASH resolve → 200 + playbackMode dash + token 往返得 Dash 双轨")
    void dashResolveReturnsEnvelope() {
        when(resolveService.resolve(anyString())).thenReturn(Mono.just(new BilibiliSourceMaterial.Dash(
                PAGE_URL, PAGE_URL, "BV1x", "作者", "标题", null, 90L,
                "https://upos-sz-mirrorali.bilivideo.com/v.m4s",
                "https://upos-sz-mirrorali.bilivideo.com/a.m4s", HEADERS)));

        client().post().uri("/api/bilibili/extract-video").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("input", PAGE_URL)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.playbackMode").isEqualTo("dash")
                .jsonPath("$.data.coverUrl").doesNotExist()
                .jsonPath("$.data.proxyVideoUrl").value(String.class, url -> {
                    String tok = url.substring(url.lastIndexOf('/') + 1);
                    BilibiliMediaTarget target = tokenCodec.parse(tok);
                    org.assertj.core.api.Assertions.assertThat(target).isInstanceOf(BilibiliMediaTarget.Dash.class);
                });
    }

    @Test
    @DisplayName("resolve 错误（422 无双轨）透传")
    void resolveErrorPropagates() {
        when(resolveService.resolve(anyString())).thenReturn(Mono.error(
                new IntelligenceException(422, "当前 B 站视频缺少可用的音视频双轨，暂不支持预览或下载")));

        client().post().uri("/api/bilibili/extract-video").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("input", PAGE_URL)).exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.error")
                .isEqualTo("当前 B 站视频缺少可用的音视频双轨，暂不支持预览或下载");
    }
}
