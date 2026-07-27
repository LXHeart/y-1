package com.grassland.intelligence.credits;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.security.IntelligenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LegacyCreditsClient}：经共享密钥 {@code X-Internal-Key} 调 legacy 内部端点，
 * 200→完成、402→{@link InsufficientCreditsException}、其它 4xx→400、5xx→502。
 */
class LegacyCreditsClientTest {

    private static final String ACCOUNT = "44444444-4444-4444-4444-444444444444";

    private WireMockServer wireMock;
    private LegacyCreditsClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        client = new LegacyCreditsClient(wireMock.baseUrl(),
                "/api/internal/credits/consume", "shared-secret");
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("200 → 扣减完成；请求带共享密钥与 {accountId, feature}")
    void consumeSuccess() {
        wireMock.stubFor(post(urlEqualTo("/api/internal/credits/consume"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"data\":{\"consumed\":true}}")));

        client.consume(ACCOUNT, CreditFeature.COMEDY_GENERATION).block();

        wireMock.verify(postRequestedFor(urlEqualTo("/api/internal/credits/consume"))
                .withHeader("X-Internal-Key", equalTo("shared-secret"))
                .withRequestBody(containing("\"accountId\":\"" + ACCOUNT + "\""))
                .withRequestBody(containing("\"feature\":\"comedy_generation\"")));
    }

    @Test
    @DisplayName("402 → InsufficientCreditsException（→402 信封）")
    void insufficientCredits() {
        wireMock.stubFor(post(urlEqualTo("/api/internal/credits/consume"))
                .willReturn(aResponse().withStatus(402)
                        .withBody("{\"success\":false,\"error\":\"积分不足\"}")));
        assertThatThrownBy(() -> client.consume(ACCOUNT, CreditFeature.ARTICLE_GENERATION).block())
                .isInstanceOf(InsufficientCreditsException.class);
    }

    @Test
    @DisplayName("其它 4xx → IntelligenceException(400)")
    void other4xxMapsTo400() {
        wireMock.stubFor(post(urlEqualTo("/api/internal/credits/consume"))
                .willReturn(aResponse().withStatus(400).withBody("bad")));
        assertThatThrownBy(() -> client.consume(ACCOUNT, CreditFeature.IMAGE_ANALYSIS).block())
                .isInstanceOfSatisfying(IntelligenceException.class,
                        e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    @DisplayName("5xx → IntelligenceException(502)")
    void serverErrorMapsTo502() {
        wireMock.stubFor(post(urlEqualTo("/api/internal/credits/consume"))
                .willReturn(aResponse().withStatus(500).withBody("down")));
        assertThatThrownBy(() -> client.consume(ACCOUNT, CreditFeature.VIDEO_ANALYSIS).block())
                .isInstanceOfSatisfying(IntelligenceException.class,
                        e -> assertThat(e.status()).isEqualTo(502));
    }
}
