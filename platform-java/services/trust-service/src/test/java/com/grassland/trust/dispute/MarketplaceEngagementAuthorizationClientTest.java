package com.grassland.trust.dispute;

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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarketplaceEngagementAuthorizationClientTest {

    private HttpServer server;
    private String applicationId;
    private String recommenderAccountId;
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> assertionHeader = new AtomicReference<>();
    private MarketplaceEngagementAuthorizationClient client;

    @BeforeEach
    void setUp() throws IOException {
        applicationId = UUID.randomUUID().toString();
        recommenderAccountId = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/marketplace/engagements/" + applicationId
                + "/dispute-authorization", this::respond);
        server.start();
        TrustServiceAssertionIssuer issuer = mock(TrustServiceAssertionIssuer.class);
        when(issuer.issueService("grassland-marketplace")).thenReturn("signed-service-assertion");
        client = new MarketplaceEngagementAuthorizationClient(
                issuer, "http://127.0.0.1:" + server.getAddress().getPort(), "X-Grassland-Identity");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void parsesCanonicalPremiumSnapshotAndRecommender() {
        String org = UUID.randomUUID().toString();
        response.set(envelope(applicationId, org, recommenderAccountId, true));

        MarketplaceEngagementAuthorizationClient.Authorization authorization = client.authorize(
                applicationId, recommenderAccountId, "recommender").block();

        assertThat(authorization.engagementRef()).isEqualTo(applicationId);
        assertThat(authorization.organizationId()).isEqualTo(org);
        assertThat(authorization.recommenderAccountId()).isEqualTo(recommenderAccountId);
        assertThat(authorization.premiumSupportAtAccept()).isTrue();
        assertThat(assertionHeader).hasValue("signed-service-assertion");
    }

    @Test
    void rejectsMismatchedOrIncompleteSuccessEnvelope() {
        response.set(envelope(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                recommenderAccountId, true));
        assertThatThrownBy(() -> client.authorize(applicationId, recommenderAccountId, "recommender").block())
                .isInstanceOf(MarketplaceEngagementAuthorizationClient.AuthorizationException.class);

        response.set("{\"success\":true,\"data\":{\"engagementRef\":\"" + applicationId
                + "\",\"organizationId\":\"" + UUID.randomUUID()
                + "\",\"recommenderAccountId\":\"" + recommenderAccountId + "\"}}");
        assertThatThrownBy(() -> client.authorize(applicationId, recommenderAccountId, "recommender").block())
                .isInstanceOf(MarketplaceEngagementAuthorizationClient.AuthorizationException.class);
    }

    @Test
    void rejectsUnsuccessfulEnvelopeEvenOnHttp200() {
        response.set("{\"success\":false,\"data\":null}");

        assertThatThrownBy(() -> client.authorize(applicationId, recommenderAccountId, "recommender").block())
                .isInstanceOf(MarketplaceEngagementAuthorizationClient.AuthorizationException.class);
    }

    private void respond(HttpExchange exchange) throws IOException {
        assertionHeader.set(exchange.getRequestHeaders().getFirst("X-Grassland-Identity"));
        byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String envelope(String applicationId, String org, String recommender, boolean premium) {
        return """
                {"success":true,"data":{"engagementRef":"%s","organizationId":"%s",\
                "recommenderAccountId":"%s","premiumSupportAtAccept":%s}}
                """.formatted(applicationId, org, recommender, premium);
    }
}
