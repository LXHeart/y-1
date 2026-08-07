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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdentityOrganizationMembershipClientTest {

    private HttpServer server;
    private String accountId;
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> assertionHeader = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicBoolean slowTrickle = new AtomicBoolean(false);
    private IdentityOrganizationMembershipClient client;

    @BeforeEach
    void setUp() throws IOException {
        accountId = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/identity/accounts/" + accountId + "/organization-memberships", this::respond);
        server.start();
        TrustServiceAssertionIssuer issuer = mock(TrustServiceAssertionIssuer.class);
        when(issuer.issueService("grassland-identity")).thenReturn("signed-trust-assertion");
        client = new IdentityOrganizationMembershipClient(
                issuer, "http://127.0.0.1:" + server.getAddress().getPort(),
                "X-Grassland-Identity", 1);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void returnsCompleteMembershipSetAndSendsTrustAssertion() {
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        response.set(envelope(accountId, "[\"" + first + "\",\"" + second + "\"]"));

        assertThat(client.organizationIds(accountId).block()).isEqualTo(Set.of(first, second));
        assertThat(assertionHeader).hasValue("signed-trust-assertion");
    }

    @Test
    void acceptsAuthoritativeEmptyMembershipSet() {
        response.set(envelope(accountId, "[]"));

        assertThat(client.organizationIds(accountId).block()).isEmpty();
    }

    @Test
    void rejectsMismatchedAccountDuplicateOrInvalidOrganizationIds() {
        response.set(envelope(UUID.randomUUID().toString(), "[]"));
        assertFailure();

        String org = UUID.randomUUID().toString();
        response.set(envelope(accountId, "[\"" + org + "\",\"" + org + "\"]"));
        assertFailure();

        response.set(envelope(accountId, "[\"not-a-uuid\"]"));
        assertFailure();
    }

    @Test
    void rejectsMalformedEmptyAndNonSuccessResponses() {
        response.set("not-json");
        assertFailure();

        response.set("");
        assertFailure();

        status.set(503);
        response.set("{\"success\":false,\"error\":\"internal detail\"}");
        assertThatThrownBy(() -> client.organizationIds(accountId).block())
                .isInstanceOf(IdentityOrganizationMembershipClient.MembershipException.class)
                .hasMessage("identity membership endpoint returned HTTP 503");
    }

    @Test
    void totalTimeoutRejectsSlowIdentityResponse() {
        slowTrickle.set(true);
        response.set(envelope(accountId, "[]"));

        assertFailure();
    }

    private void assertFailure() {
        assertThatThrownBy(() -> client.organizationIds(accountId).block())
                .isInstanceOf(IdentityOrganizationMembershipClient.MembershipException.class);
    }

    private void respond(HttpExchange exchange) throws IOException {
        assertionHeader.set(exchange.getRequestHeaders().getFirst("X-Grassland-Identity"));
        byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (slowTrickle.get()) {
            exchange.sendResponseHeaders(status.get(), 0);
            int part = Math.max(1, body.length / 3);
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

    private static String envelope(String account, String organizationIds) {
        return "{\"success\":true,\"data\":{\"accountId\":\"" + account
                + "\",\"organizationIds\":" + organizationIds + "}}";
    }
}
