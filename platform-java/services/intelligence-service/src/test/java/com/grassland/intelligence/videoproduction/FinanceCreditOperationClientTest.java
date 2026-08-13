package com.grassland.intelligence.videoproduction;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.security.IntelligenceServiceAssertionIssuer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FinanceCreditOperationClientTest {
    private static WireMockServer finance;
    private FinanceCreditOperationClient client;

    @BeforeAll
    static void start() {
        finance = new WireMockServer(options().dynamicPort());
        finance.start();
    }

    @AfterAll
    static void stop() {
        if (finance != null) finance.stop();
    }

    @BeforeEach
    void setUp() {
        finance.resetAll();
        IntelligenceServiceAssertionIssuer assertions = mock(IntelligenceServiceAssertionIssuer.class);
        when(assertions.issueService("grassland-finance")).thenReturn("service-assertion");
        client = new FinanceCreditOperationClient(
                finance.baseUrl(), "/internal/credits/consume-operations/query", 3_000, assertions);
    }

    @Test
    void queriesAuthorityWithServiceAssertionAndMapsPaidAndQuotaOperations() {
        UUID paid = UUID.randomUUID();
        UUID quota = UUID.randomUUID();
        finance.stubFor(post(urlEqualTo("/internal/credits/consume-operations/query"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"success":true,"data":{"operations":[
                                  {"operationId":"%s","accountId":"account-paid",
                                   "feature":"video_production_video","state":"consumed",
                                   "source":"paid","policyVersion":41,
                                   "consumeTransactionId":"transaction-paid","refundTransactionId":null},
                                  {"operationId":"%s","accountId":"account-quota",
                                   "feature":"video_production_video","state":"compensated",
                                   "source":"quota","policyVersion":42,
                                   "consumeTransactionId":"transaction-quota",
                                   "refundTransactionId":"refund-quota"}
                                ]}}
                                """.formatted(paid, quota))));

        var result = client.query(List.of(paid, quota)).block();

        assertThat(result).hasSize(2);
        assertThat(result.get(paid).source()).isEqualTo("paid");
        assertThat(result.get(quota).state()).isEqualTo("compensated");
        assertThat(result.get(quota).policyVersion()).isEqualTo(42L);
        finance.verify(postRequestedFor(urlEqualTo("/internal/credits/consume-operations/query"))
                .withHeader("X-Grassland-Identity", equalTo("service-assertion"))
                .withRequestBody(matchingJsonPath("$.operationIds[?(@ == '%s')]".formatted(paid)))
                .withRequestBody(matchingJsonPath("$.operationIds[?(@ == '%s')]".formatted(quota))));
    }

    @Test
    void malformedAuthorityResponseFailsClosed() {
        finance.stubFor(post(urlEqualTo("/internal/credits/consume-operations/query"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"data\":{}}")));

        assertThatThrownBy(() -> client.query(List.of(UUID.randomUUID())).block())
                .hasMessageContaining("缺少 operations");
    }
}
