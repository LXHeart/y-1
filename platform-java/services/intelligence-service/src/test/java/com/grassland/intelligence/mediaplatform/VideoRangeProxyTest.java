package com.grassland.intelligence.mediaplatform;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.security.IntelligenceErrorHandler;
import java.net.URI;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * {@link VideoRangeProxy} 单元/契约测试（草场 Slice 13 Stage 2）。用 {@link WebTestClient#bindToController}
 * 挂一个透传端点，WireMock 托管上游视频服务器。无 Spring 上下文，快。
 */
@DisplayName("VideoRangeProxy：Range/206 透传（草场 Slice 13 Stage 2）")
class VideoRangeProxyTest {

    public static final WireMockServer UPSTREAM = new WireMockServer(0);

    static {
        UPSTREAM.start();
    }

    @BeforeEach
    void resetUpstream() {
        UPSTREAM.resetAll();
    }

    private WebTestClient client(Predicate<URI> urlGuard) {
        return WebTestClient.bindToController(new TestEndpoint(urlGuard))
                .controllerAdvice(new IntelligenceErrorHandler())
                .configureClient()
                .build();
    }

    /** 透传端点：把 /t 请求转给 VideoRangeProxy（固定上游 /video，可带 Range / Content-Disposition）。 */
    @RestController
    static final class TestEndpoint {
        private final Predicate<URI> urlGuard;

        TestEndpoint(Predicate<URI> urlGuard) {
            this.urlGuard = urlGuard;
        }

        @GetMapping("/t")
        public Mono<Void> stream(ServerWebExchange exchange) {
            String range = exchange.getRequest().getHeaders().getFirst("Range");
            String disposition = exchange.getRequest().getQueryParams().getFirst("disp");
            return new VideoRangeProxy().stream(
                    new VideoRangeProxy.Request(
                            UPSTREAM.url("/video"), range, Map.of(), disposition, urlGuard),
                    exchange.getResponse());
        }
    }

    @Test
    @DisplayName("206 + Content-Range 字节透传，转发客户端 Range")
    void passesThrough206Range() {
        UPSTREAM.stubFor(get(urlEqualTo("/video")).withHeader("Range", equalTo("bytes=0-3"))
                .willReturn(aResponse().withStatus(206)
                        .withHeader("Content-Type", "video/mp4")
                        .withHeader("Content-Range", "bytes 0-3/10")
                        .withHeader("Accept-Ranges", "bytes")
                        .withBody("abcd")));

        client(uri -> true).get().uri("/t").header("Range", "bytes=0-3").exchange()
                .expectStatus().isEqualTo(HttpStatus.PARTIAL_CONTENT)
                .expectHeader().valueEquals("Content-Range", "bytes 0-3/10")
                .expectHeader().valueEquals("Accept-Ranges", "bytes")
                .expectHeader().valueEquals("Cache-Control", "no-store, private")
                .expectBody(String.class).isEqualTo("abcd");
    }

    @Test
    @DisplayName("200 全量透传")
    void passesThrough200Full() {
        UPSTREAM.stubFor(get(urlEqualTo("/video"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "video/mp4")
                        .withBody("full-bytes")));

        client(uri -> true).get().uri("/t").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("full-bytes");
    }

    @Test
    @DisplayName("跟随 ≤2 跳重定向（每跳经 urlGuard 复验）")
    void followsRedirectChain() {
        UPSTREAM.stubFor(get(urlEqualTo("/video"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/video2")));
        UPSTREAM.stubFor(get(urlEqualTo("/video2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "video/mp4").withBody("redirected")));

        client(uri -> true).get().uri("/t").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("redirected");
    }

    @Test
    @DisplayName("重定向目标被 urlGuard 拒绝 → 502")
    void rejectsRedirectToUntrusted() {
        UPSTREAM.stubFor(get(urlEqualTo("/video"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/blocked")));

        // 仅允许 /video，重定向到 /blocked 被拒
        client(uri -> !uri.getPath().contains("blocked")).get().uri("/t").exchange()
                .expectStatus().isEqualTo(502);
    }

    @Test
    @DisplayName("超过 2 跳重定向 → 502")
    void rejectsTooManyRedirects() {
        UPSTREAM.stubFor(get(urlEqualTo("/video"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/video")));

        client(uri -> true).get().uri("/t").exchange().expectStatus().isEqualTo(502);
    }

    @Test
    @DisplayName("上游 4xx → 502")
    void rejects4xxUpstream() {
        UPSTREAM.stubFor(get(urlEqualTo("/video"))
                .willReturn(aResponse().withStatus(404).withBody("not found")));

        client(uri -> true).get().uri("/t").exchange().expectStatus().isEqualTo(502);
    }

    @Test
    @DisplayName("非视频 Content-Type → 502")
    void rejectsNonVideoContentType() {
        UPSTREAM.stubFor(get(urlEqualTo("/video"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html").withBody("<html/>")));

        client(uri -> true).get().uri("/t").exchange().expectStatus().isEqualTo(502);
    }

    @Test
    @DisplayName("urlGuard 拒绝首跳目标 → 502（不发起上游请求）")
    void rejectsUntrustedInitialUrl() {
        client(uri -> false).get().uri("/t").exchange().expectStatus().isEqualTo(502);
        assertThat(UPSTREAM.findAll(getRequestedFor(urlEqualTo("/video")))).isEmpty();
    }

    @Test
    @DisplayName("注入 Content-Disposition（download 语义）")
    void appliesContentDisposition() {
        UPSTREAM.stubFor(get(urlEqualTo("/video"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "video/mp4").withBody("x")));

        client(uri -> true).get().uri("/t?disp=attachment").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Content-Disposition", "attachment");
    }
}
