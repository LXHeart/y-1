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
 * {@link FinanceCreditsClient}：经共享密钥 {@code X-Internal-Key} 调 finance 内部端点，
 * 200→完成、402→{@link InsufficientCreditsException}、其它 4xx→400、5xx→502。
 *
 * <p>关键修正：refund 必须发送 {@code refund:<consumeId>} 作为 operationId（finance 原样存储，
 * 与 consume 行的 {@code <consumeId>} 区分，保证一次扣减至多一次退款）。旧 {@link LegacyCreditsClient}
 * 误传原始 consume id → 与 consume 行撞车被 dedup 吞掉，退款从未生效。
 */
class FinanceCreditsClientTest {

    private static final String ACCOUNT = "44444444-4444-4444-4444-444444444444";

    private WireMockServer wireMock;
    private FinanceCreditsClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        client = new FinanceCreditsClient(wireMock.baseUrl(),
                "/internal/credits/consume", "/internal/credits/refund", "shared-secret");
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("200 → 扣减完成；请求带共享密钥与 {accountId, feature}")
    void consumeSuccess() {
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"data\":{\"consumed\":true}}")));

        client.consume(ACCOUNT, CreditFeature.COMEDY_GENERATION).block();

        wireMock.verify(postRequestedFor(urlEqualTo("/internal/credits/consume"))
                .withHeader("X-Internal-Key", equalTo("shared-secret"))
                .withRequestBody(containing("\"accountId\":\"" + ACCOUNT + "\""))
                .withRequestBody(containing("\"feature\":\"comedy_generation\"")));
    }

    @Test
    @DisplayName("consume 带 operationId；refund 派生 refund:<consumeId>（修正既有 bug，GL-P0-CRED-001）")
    void consumeCarriesOperationIdAndRefundDerivesRefundKey() {
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"data\":{\"consumed\":true}}")));
        wireMock.stubFor(post(urlEqualTo("/internal/credits/refund"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"data\":{\"refunded\":true}}")));

        CreditCharge charge = client.consume(ACCOUNT, CreditFeature.COMEDY_GENERATION).block();

        assertThat(charge).isNotNull();
        assertThat(charge.operationId()).isNotBlank();
        wireMock.verify(postRequestedFor(urlEqualTo("/internal/credits/consume"))
                .withRequestBody(containing("\"operationId\":\"" + charge.operationId() + "\"")));

        client.refund(charge, "上游失败自动退回").block();

        // refund 的 operationId 必须是 refund:<consumeId>——与 consume 行键区分，否则被 finance dedup 吞掉。
        wireMock.verify(postRequestedFor(urlEqualTo("/internal/credits/refund"))
                .withHeader("X-Internal-Key", equalTo("shared-secret"))
                .withRequestBody(containing("\"operationId\":\"refund:" + charge.operationId() + "\""))
                .withRequestBody(containing("\"feature\":\"comedy_generation\"")));
    }

    @Test
    @DisplayName("refund 上游失败不抛——不覆盖用户看到的原始上游错误")
    void refundSwallowsUpstreamFailure() {
        wireMock.stubFor(post(urlEqualTo("/internal/credits/refund"))
                .willReturn(aResponse().withStatus(500).withBody("down")));

        CreditCharge charge = new CreditCharge(ACCOUNT, CreditFeature.VIDEO_ANALYSIS, "op-1");

        // 不抛异常即为期望行为（失败已记日志）
        client.refund(charge, "失败退回").block();

        wireMock.verify(postRequestedFor(urlEqualTo("/internal/credits/refund")));
    }

    @Test
    @DisplayName("402 → InsufficientCreditsException（→402 信封）")
    void insufficientCredits() {
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume"))
                .willReturn(aResponse().withStatus(402)
                        .withBody("{\"success\":false,\"error\":\"积分不足\"}")));
        assertThatThrownBy(() -> client.consume(ACCOUNT, CreditFeature.ARTICLE_GENERATION).block())
                .isInstanceOf(InsufficientCreditsException.class);
    }

    @Test
    @DisplayName("其它 4xx → IntelligenceException(400)")
    void other4xxMapsTo400() {
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume"))
                .willReturn(aResponse().withStatus(400).withBody("bad")));
        assertThatThrownBy(() -> client.consume(ACCOUNT, CreditFeature.IMAGE_ANALYSIS).block())
                .isInstanceOfSatisfying(IntelligenceException.class,
                        e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    @DisplayName("5xx → IntelligenceException(502)")
    void serverErrorMapsTo502() {
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume"))
                .willReturn(aResponse().withStatus(500).withBody("down")));
        assertThatThrownBy(() -> client.consume(ACCOUNT, CreditFeature.VIDEO_ANALYSIS).block())
                .isInstanceOfSatisfying(IntelligenceException.class,
                        e -> assertThat(e.status()).isEqualTo(502));
    }
}
