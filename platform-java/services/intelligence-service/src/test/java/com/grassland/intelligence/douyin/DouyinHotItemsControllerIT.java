package com.grassland.intelligence.douyin;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 抖音热点端到端（草场 Slice 13 Stage 1）。复用 {@link IntelligenceItSupport}（testcontainers pg + 平台 Qwen
 * WireMock），另起一个 WireMock 托管 60s 上游；超时取 1000ms 便于覆盖 504 路径。
 */
@DisplayName("抖音热点 GET /api/douyin/hot-items（草场 Slice 13 Stage 1）")
class DouyinHotItemsControllerIT extends IntelligenceItSupport {

    public static final WireMockServer DOUYIN_HOT = new WireMockServer(0);

    static {
        DOUYIN_HOT.start();
    }

    @DynamicPropertySource
    static void douyinProps(DynamicPropertyRegistry registry) {
        registry.add("douyin.hot.api-base-url", () -> DOUYIN_HOT.baseUrl() + "/v2/douyin");
        // 短超时：让超时用例（上游 delay 3s）稳定触发 504；正常用例即时响应不受影响。
        registry.add("douyin.hot.api-timeout-ms", () -> 1000L);
    }

    @BeforeEach
    void resetUpstream() {
        DOUYIN_HOT.resetAll();
    }

    @Test
    @DisplayName("归一化 + 受信过滤：不可信 link/cover 丢弃但保留 title，重排 rank")
    void normalizesAndFiltersUntrustedUrls() {
        DOUYIN_HOT.stubFor(get(urlPathEqualTo("/v2/douyin")).withQueryParam("encoding", equalTo("json"))
                .willReturn(okJson("""
                        {"code":200,"data":[
                          {"title":"爆款1","hot_value":1234,"cover":"https://p9.byteimg.com/c.jpg","link":"https://www.douyin.com/v/1"},
                          {"title":"无链接","hot_value":"56","link":"https://evil.com","cover":"http://insecure"},
                          {"cover":"https://p9.byteimg.com/x.jpg"},
                          {"title":"  "}
                        ]}
                        """)));

        client().get().uri("/api/douyin/hot-items").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.items.length()").isEqualTo(2)
                .jsonPath("$.data.items[0].rank").isEqualTo(1)
                .jsonPath("$.data.items[0].title").isEqualTo("爆款1")
                .jsonPath("$.data.items[0].hotValue").isEqualTo("1234")
                .jsonPath("$.data.items[0].url").isEqualTo("https://www.douyin.com/v/1")
                .jsonPath("$.data.items[0].cover").isEqualTo("https://p9.byteimg.com/c.jpg")
                .jsonPath("$.data.items[0].source").isEqualTo("60sapi")
                .jsonPath("$.data.items[1].rank").isEqualTo(2)
                .jsonPath("$.data.items[1].title").isEqualTo("无链接")
                .jsonPath("$.data.items[1].hotValue").isEqualTo("56")
                .jsonPath("$.data.items[1].url").doesNotExist()
                .jsonPath("$.data.items[1].cover").doesNotExist()
                .jsonPath("$.data.items[1].source").isEqualTo("60sapi");
    }

    @Test
    @DisplayName("超过 limit(10) 截断并重排 rank 1..10")
    void truncatesToLimitAndReranks() {
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0) {
                data.append(',');
            }
            data.append("{\"title\":\"t").append(i).append("\"}");
        }
        DOUYIN_HOT.stubFor(get(urlPathEqualTo("/v2/douyin")).withQueryParam("encoding", equalTo("json"))
                .willReturn(okJson("{\"code\":200,\"data\":[" + data + "]}")));

        client().get().uri("/api/douyin/hot-items").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(10)
                .jsonPath("$.data.items[0].rank").isEqualTo(1)
                .jsonPath("$.data.items[0].title").isEqualTo("t0")
                .jsonPath("$.data.items[9].rank").isEqualTo(10)
                .jsonPath("$.data.items[9].title").isEqualTo("t9");
    }

    @Test
    @DisplayName("业务 code != 200 → 502")
    void nonSuccessBusinessCodeReturns502() {
        DOUYIN_HOT.stubFor(get(urlPathEqualTo("/v2/douyin")).withQueryParam("encoding", equalTo("json"))
                .willReturn(okJson("{\"code\":500,\"message\":\"upstream error\"}")));

        client().get().uri("/api/douyin/hot-items").exchange()
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.success").isEqualTo(false);
    }

    @Test
    @DisplayName("data 非数组 → 502")
    void nonArrayDataReturns502() {
        DOUYIN_HOT.stubFor(get(urlPathEqualTo("/v2/douyin")).withQueryParam("encoding", equalTo("json"))
                .willReturn(okJson("{\"code\":200,\"data\":{}}")));

        client().get().uri("/api/douyin/hot-items").exchange().expectStatus().isEqualTo(502);
    }

    @Test
    @DisplayName("上游 HTTP 非 2xx → 502")
    void upstreamHttpErrorReturns502() {
        DOUYIN_HOT.stubFor(get(urlPathEqualTo("/v2/douyin")).withQueryParam("encoding", equalTo("json"))
                .willReturn(aResponse().withStatus(503).withBody("upstream down")));

        client().get().uri("/api/douyin/hot-items").exchange().expectStatus().isEqualTo(502);
    }

    @Test
    @DisplayName("上游超时 → 504")
    void upstreamTimeoutReturns504() {
        DOUYIN_HOT.stubFor(get(urlPathEqualTo("/v2/douyin")).withQueryParam("encoding", equalTo("json"))
                .willReturn(okJson("{\"code\":200,\"data\":[]}").withFixedDelay(3000)));

        client().get().uri("/api/douyin/hot-items").exchange()
                .expectStatus().isEqualTo(504)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error").isEqualTo("获取抖音热点超时，请稍后再试");
    }
}
