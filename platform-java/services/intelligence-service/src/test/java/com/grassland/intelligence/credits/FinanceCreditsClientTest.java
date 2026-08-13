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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.grassland.intelligence.ai.run.CreditCompensationRepository;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.security.IntelligenceServiceAssertionIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * {@link FinanceCreditsClient}：经命名服务断言调 finance 内部端点，
 * 200→完成、402→{@link InsufficientCreditsException}、其它 4xx→400、5xx→502。
 *
 * <p>关键修正：refund 必须发送 {@code refund:<consumeId>} 作为 operationId（finance 原样存储，
 * 与 consume 行的 {@code <consumeId>} 区分，保证一次扣减至多一次退款）。旧回退实现
 * 误传原始 consume id → 与 consume 行撞车被 dedup 吞掉，退款从未生效。
 */
class FinanceCreditsClientTest {

    private static final String ACCOUNT = "44444444-4444-4444-4444-444444444444";

    private WireMockServer wireMock;
    private FinanceCreditsClient client;
    private MarketplaceAiEntitlementClient entitlements;
    private IntelligenceServiceAssertionIssuer assertionIssuer;
    private CreditCompensationRepository compensationRepository;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        entitlements = mock(MarketplaceAiEntitlementClient.class);
        assertionIssuer = mock(IntelligenceServiceAssertionIssuer.class);
        compensationRepository = mock(CreditCompensationRepository.class);
        when(compensationRepository.enqueueUnknownConsume(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Mono.empty());
        when(compensationRepository.markCompletedByOperationId(
                org.mockito.ArgumentMatchers.any())).thenReturn(Mono.just(true));
        when(assertionIssuer.issueService("grassland-finance")).thenReturn("intelligence-finance-assertion");
        when(entitlements.get(ACCOUNT)).thenReturn(Mono.just(
                new MarketplaceAiEntitlementClient.AiEntitlement(ACCOUNT, 15_000, 7)));
        client = new FinanceCreditsClient(wireMock.baseUrl(),
                "/internal/credits/consume", "/internal/credits/refund",
                entitlements, assertionIssuer, compensationRepository);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("200 → 扣减完成；请求带服务断言与 {accountId, feature}")
    void consumeSuccess() {
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(successfulConsume("quota", 7))));

        CreditCharge charge = client.consume(ACCOUNT, CreditFeature.COMEDY_GENERATION).block();

        assertThat(charge.source()).isEqualTo(CreditCharge.Source.QUOTA);
        assertThat(charge.policyVersion()).isEqualTo(7);
        verify(entitlements).get(ACCOUNT);
        wireMock.verify(postRequestedFor(urlEqualTo("/internal/credits/consume"))
                .withHeader("X-Grassland-Identity", equalTo("intelligence-finance-assertion"))
                .withRequestBody(containing("\"accountId\":\"" + ACCOUNT + "\""))
                .withRequestBody(containing("\"feature\":\"comedy_generation\""))
                .withRequestBody(containing("\"aiQuotaMultiplierBps\":15000"))
                .withRequestBody(containing("\"policyVersion\":7")));
    }

    @Test
    @DisplayName("consume 带 operationId；refund 派生 refund:<consumeId>（修正既有 bug，GL-P0-CRED-001）")
    void consumeCarriesOperationIdAndRefundDerivesRefundKey() {
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(successfulConsume("paid", 7))));
        wireMock.stubFor(post(urlEqualTo("/internal/credits/refund"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"data\":{\"refunded\":true}}")));

        CreditCharge charge = client.consume(ACCOUNT, CreditFeature.COMEDY_GENERATION).block();

        assertThat(charge).isNotNull();
        assertThat(charge.source()).isEqualTo(CreditCharge.Source.PAID);
        assertThat(charge.operationId()).isNotBlank();
        wireMock.verify(postRequestedFor(urlEqualTo("/internal/credits/consume"))
                .withRequestBody(containing("\"operationId\":\"" + charge.operationId() + "\"")));

        client.refund(charge, "上游失败自动退回").block();

        // refund 的 operationId 必须是 refund:<consumeId>——与 consume 行键区分，否则被 finance dedup 吞掉。
        wireMock.verify(postRequestedFor(urlEqualTo("/internal/credits/refund"))
                .withHeader("X-Grassland-Identity", equalTo("intelligence-finance-assertion"))
                .withRequestBody(containing("\"operationId\":\"refund:" + charge.operationId() + "\""))
                .withRequestBody(containing("\"feature\":\"comedy_generation\"")));
    }

    @Test
    @DisplayName("compensate 发送原 consume operationId 到原子补偿端点")
    void compensateUsesAtomicFinanceEndpoint() {
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume-compensations"))
                .willReturn(aResponse().withStatus(200)));
        CreditCharge charge = new CreditCharge(ACCOUNT, CreditFeature.AI_RUN_TEXT, "op-ai-1");

        client.compensate(charge, "AI run failed").block();

        wireMock.verify(postRequestedFor(urlEqualTo("/internal/credits/consume-compensations"))
                .withHeader("X-Grassland-Identity", equalTo("intelligence-finance-assertion"))
                .withRequestBody(containing("\"consumeOperationId\":\"op-ai-1\""))
                .withRequestBody(containing("\"feature\":\"ai_run_text\"")));
    }

    @Test
    @DisplayName("refund 上游失败必须向可靠补偿 worker 传播")
    void refundPropagatesUpstreamFailure() {
        wireMock.stubFor(post(urlEqualTo("/internal/credits/refund"))
                .willReturn(aResponse().withStatus(500).withBody("down")));

        CreditCharge charge = new CreditCharge(ACCOUNT, CreditFeature.VIDEO_ANALYSIS, "op-1");

        assertThatThrownBy(() -> client.refund(charge, "失败退回").block())
                .isInstanceOf(RuntimeException.class);

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
    @DisplayName("marketplace 权益不可用时 fail-closed，finance 不得被调用")
    void marketplaceUnavailableFailsClosedBeforeFinanceCharge() {
        when(entitlements.get(ACCOUNT)).thenReturn(Mono.error(new IntelligenceException(502, "权益服务暂不可用")));

        assertThatThrownBy(() -> client.consume(ACCOUNT, CreditFeature.ARTICLE_GENERATION).block())
                .isInstanceOfSatisfying(IntelligenceException.class,
                        error -> assertThat(error.status()).isEqualTo(502));

        wireMock.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume")));
    }

    @Test
    @DisplayName("其它 4xx → IntelligenceException(400)")
    void other4xxMapsTo400() {
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume"))
                .willReturn(aResponse().withStatus(400).withBody("bad")));
        assertThatThrownBy(() -> client.consume(ACCOUNT, CreditFeature.IMAGE_ANALYSIS).block())
                .isInstanceOfSatisfying(IntelligenceException.class,
                        e -> assertThat(e.status()).isEqualTo(400));

        wireMock.verify(0, postRequestedFor(urlEqualTo("/internal/credits/consume-compensations")));
    }

    @Test
    @DisplayName("5xx outcome is unknown and must be durably compensated")
    void serverErrorPersistsAndCompensatesUnknownOutcome() {
        String operationId = "ffffffff-ffff-ffff-ffff-ffffffffffff";
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume"))
                .willReturn(aResponse().withStatus(500).withBody("down")));
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume-compensations"))
                .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> client.consume(
                ACCOUNT, CreditFeature.VIDEO_ANALYSIS, operationId).block())
                .isInstanceOfSatisfying(IntelligenceException.class,
                        e -> assertThat(e.status()).isEqualTo(502));

        verify(compensationRepository).enqueueUnknownConsume(
                java.util.UUID.fromString(operationId), ACCOUNT, "video_analysis",
                "积分扣减结果不确定自动补偿");
        wireMock.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume-compensations"))
                .withRequestBody(containing("\"consumeOperationId\":\"" + operationId + "\"")));
        verify(compensationRepository).markCompletedByOperationId(
                java.util.UUID.fromString(operationId));
    }

    @Test
    void malformedConsumeResponseCompensatesOriginalOperation() {
        String operationId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{not-json")));
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume-compensations"))
                .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> client.consume(
                ACCOUNT, CreditFeature.CREATION_ASSISTANT, operationId).block())
                .isInstanceOf(RuntimeException.class);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume-compensations"))
                .withRequestBody(containing("\"consumeOperationId\":\"" + operationId + "\""))
                .withRequestBody(containing("\"feature\":\"creation_assistant\"")));
        verify(compensationRepository).enqueueUnknownConsume(
                java.util.UUID.fromString(operationId), ACCOUNT, "creation_assistant",
                "积分扣减结果不确定自动补偿");
        verify(compensationRepository).markCompletedByOperationId(
                java.util.UUID.fromString(operationId));
    }

    @Test
    void emptyConsumeResponseCompensatesOriginalOperation() {
        String operationId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")));
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume-compensations"))
                .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> client.consume(
                ACCOUNT, CreditFeature.ARTICLE_GENERATION, operationId).block())
                .isInstanceOf(RuntimeException.class);

        wireMock.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume-compensations"))
                .withRequestBody(containing("\"consumeOperationId\":\"" + operationId + "\"")));
    }

    @Test
    void connectionFailureCompensatesOriginalOperation() {
        String operationId = "cccccccc-cccc-cccc-cccc-cccccccccccc";
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume-compensations"))
                .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> client.consume(
                ACCOUNT, CreditFeature.IMAGE_ANALYSIS, operationId).block())
                .isInstanceOfSatisfying(IntelligenceException.class,
                        error -> assertThat(error.status()).isEqualTo(502));

        wireMock.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume-compensations"))
                .withRequestBody(containing("\"consumeOperationId\":\"" + operationId + "\"")));
    }

    @Test
    void consumeTimeoutCompensatesOriginalOperation() {
        String operationId = "dddddddd-dddd-dddd-dddd-dddddddddddd";
        client = new FinanceCreditsClient(wireMock.baseUrl(),
                "/internal/credits/consume", "/internal/credits/refund",
                100, 50, entitlements, assertionIssuer, compensationRepository);
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume"))
                .willReturn(aResponse().withFixedDelay(250).withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(successfulConsume("quota", 7))));
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume-compensations"))
                .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> client.consume(
                ACCOUNT, CreditFeature.ARTICLE_GENERATION, operationId).block())
                .isInstanceOfSatisfying(IntelligenceException.class,
                        error -> assertThat(error.status()).isEqualTo(502));

        wireMock.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume-compensations"))
                .withRequestBody(containing("\"consumeOperationId\":\"" + operationId + "\"")));
    }

    @Test
    void compensationFailureLeavesUnknownConsumeInDurableQueue() {
        String operationId = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{not-json")));
        wireMock.stubFor(post(urlEqualTo("/internal/credits/consume-compensations"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.consume(
                ACCOUNT, CreditFeature.AI_RUN_TEXT, operationId).block())
                .isInstanceOf(RuntimeException.class);

        verify(compensationRepository).enqueueUnknownConsume(
                java.util.UUID.fromString(operationId), ACCOUNT, "ai_run_text",
                "积分扣减结果不确定自动补偿");
        verify(compensationRepository, never()).markCompletedByOperationId(
                java.util.UUID.fromString(operationId));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/internal/credits/consume-compensations")));
    }

    private static String successfulConsume(String source, long policyVersion) {
        return "{\"success\":true,\"data\":{\"consumed\":true,\"source\":\"" + source
                + "\",\"policyVersion\":" + policyVersion + ",\"deduplicated\":false,"
                + "\"transactionId\":\"11111111-1111-1111-1111-111111111111\"}}";
    }
}
