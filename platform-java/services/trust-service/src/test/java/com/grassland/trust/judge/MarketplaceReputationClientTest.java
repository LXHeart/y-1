package com.grassland.trust.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.trust.security.TrustServiceAssertionIssuer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarketplaceReputationClientTest {

    private HttpServer server;
    private String accountId;
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> assertionHeader = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicBoolean slowTrickle = new AtomicBoolean(false);
    private MarketplaceReputationClient client;

    @BeforeEach
    void setUp() throws IOException {
        accountId = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/marketplace/reputation/" + accountId + "/level", this::respond);
        server.start();
        TrustServiceAssertionIssuer issuer = mock(TrustServiceAssertionIssuer.class);
        when(issuer.issueService("grassland-marketplace")).thenReturn("signed-service-assertion");
        client = new MarketplaceReputationClient(
                issuer, "http://127.0.0.1:" + server.getAddress().getPort(), "X-Grassland-Identity", 1);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void decodesEligibleLv5AndSendsServiceAssertion() {
        response.set(envelope(accountId, "Lv5", 5, true, 7));

        MarketplaceReputationClient.LevelResult result = client.getLevel(accountId).block();

        assertThat(result).isEqualTo(new MarketplaceReputationClient.LevelResult(accountId, "Lv5", 5, true, 7L));
        assertThat(assertionHeader).hasValue("signed-service-assertion");
    }

    @Test
    void decodesNonEligibleLv4WithoutPromotingIt() {
        response.set(envelope(accountId, "Lv4", 4, false, 8));

        MarketplaceReputationClient.LevelResult result = client.getLevel(accountId).block();

        assertThat(result.isEligibleLv5Judge()).isFalse();
        assertThat(result.levelNumber()).isEqualTo(4);
    }

    @Test
    void rejectsMismatchedAccountAndInconsistentLevel() {
        response.set(envelope(UUID.randomUUID().toString(), "Lv5", 5, true, 7));
        assertThatThrownBy(() -> client.getLevel(accountId).block())
                .isInstanceOf(MarketplaceReputationClient.ReputationException.class);

        response.set(envelope(accountId, "Lv4", 5, true, 7));
        assertThatThrownBy(() -> client.getLevel(accountId).block())
                .isInstanceOf(MarketplaceReputationClient.ReputationException.class);
    }

    @Test
    void rejectsMalformedOrUnsuccessfulEnvelope() {
        response.set("{\"success\":false,\"data\":null}");
        assertThatThrownBy(() -> client.getLevel(accountId).block())
                .isInstanceOf(MarketplaceReputationClient.ReputationException.class);

        response.set("not-json");
        assertThatThrownBy(() -> client.getLevel(accountId).block()).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsNonSuccessHttpStatus() {
        status.set(503);
        response.set("{\"success\":false,\"error\":\"internal detail\"}");

        assertThatThrownBy(() -> client.getLevel(accountId).block())
                .isInstanceOf(MarketplaceReputationClient.ReputationException.class)
                .hasMessage("reputation endpoint returned HTTP 503");
    }

    @Test
    void totalTimeoutRejectsResponseThatTricklesWithinReadTimeout() {
        slowTrickle.set(true);
        response.set(envelope(accountId, "Lv5", 5, true, 7));

        assertThatThrownBy(() -> client.getLevel(accountId).block())
                .isInstanceOf(MarketplaceReputationClient.ReputationException.class);
    }

    private void respond(HttpExchange exchange) throws IOException {
        assertionHeader.set(exchange.getRequestHeaders().getFirst("X-Grassland-Identity"));
        byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (slowTrickle.get()) {
            exchange.sendResponseHeaders(status.get(), 0);
            int part = body.length / 3;
            try {
                exchange.getResponseBody().write(body, 0, part);
                exchange.getResponseBody().flush();
                Thread.sleep(600);
                exchange.getResponseBody().write(body, part, part);
                exchange.getResponseBody().flush();
                Thread.sleep(600);
                exchange.getResponseBody().write(body, part * 2, body.length - part * 2);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status.get(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String envelope(String account, String level, int number, boolean eligible, long version) {
        return """
                {"success":true,"data":{"accountId":"%s","effectiveLevel":"%s",\
                "levelNumber":%d,"judgeEligible":%s,"policyVersion":%d}}
                """.formatted(account, level, number, eligible, version);
    }
}
