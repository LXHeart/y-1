package com.grassland.intelligence.credits;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.security.IntelligenceServiceAssertionIssuer;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarketplaceAiEntitlementClientTest {

    private WireMockServer wireMock;
    private MarketplaceAiEntitlementClient client;
    private String accountId;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID().toString();
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        IntelligenceServiceAssertionIssuer issuer = mock(IntelligenceServiceAssertionIssuer.class);
        when(issuer.issueService("grassland-marketplace")).thenReturn("signed-intelligence-assertion");
        client = new MarketplaceAiEntitlementClient(
                issuer, wireMock.baseUrl(), "X-Grassland-Identity", 1);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void returnsStrictEntitlementAndSendsServiceAssertion() {
        wireMock.stubFor(get(urlEqualTo(path())).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(envelope(accountId, 15_000, 8))));

        MarketplaceAiEntitlementClient.AiEntitlement result = client.get(accountId).block();

        assertThat(result).isEqualTo(
                new MarketplaceAiEntitlementClient.AiEntitlement(accountId, 15_000, 8));
        wireMock.verify(getRequestedFor(urlEqualTo(path()))
                .withHeader("X-Grassland-Identity", equalTo("signed-intelligence-assertion")));
    }

    @Test
    void rejectsMalformedMismatchedAndOutOfRangeResponses() {
        wireMock.stubFor(get(urlEqualTo(path())).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(envelope(UUID.randomUUID().toString(), 15_000, 8))));
        assertThatThrownBy(() -> client.get(accountId).block()).isInstanceOf(IntelligenceException.class);

        wireMock.resetAll();
        wireMock.stubFor(get(urlEqualTo(path())).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(envelope(accountId, 100_001, 8))));
        assertThatThrownBy(() -> client.get(accountId).block()).isInstanceOf(IntelligenceException.class);

        wireMock.resetAll();
        wireMock.stubFor(get(urlEqualTo(path())).willReturn(aResponse().withStatus(200)
                .withBody("not-json")));
        assertThatThrownBy(() -> client.get(accountId).block()).isInstanceOf(IntelligenceException.class);
    }

    @Test
    void mapsEveryNonSuccessStatusAndTimeoutToFailClosed502() {
        wireMock.stubFor(get(urlEqualTo(path())).willReturn(aResponse().withStatus(403)));
        assertThatThrownBy(() -> client.get(accountId).block())
                .isInstanceOfSatisfying(IntelligenceException.class,
                        error -> assertThat(error.status()).isEqualTo(502));

        wireMock.resetAll();
        wireMock.stubFor(get(urlEqualTo(path())).willReturn(aResponse().withFixedDelay(1_500)
                .withStatus(200).withBody(envelope(accountId, 10_000, 1))));
        assertThatThrownBy(() -> client.get(accountId).block())
                .isInstanceOfSatisfying(IntelligenceException.class,
                        error -> assertThat(error.status()).isEqualTo(502));
    }

    private String path() {
        return "/internal/marketplace/reputation/" + accountId + "/ai-entitlement";
    }

    private static String envelope(String accountId, int multiplierBps, long policyVersion) {
        return "{\"success\":true,\"data\":{\"accountId\":\"" + accountId
                + "\",\"aiQuotaMultiplierBps\":" + multiplierBps
                + ",\"policyVersion\":" + policyVersion + "}}";
    }
}
