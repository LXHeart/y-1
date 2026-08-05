package com.grassland.identity.kyb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.security.IdentityServiceAssertionIssuer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class KybMediaClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsAuthoritativeMetadataAndSendsServiceAssertion() throws Exception {
        UUID mediaId = UUID.randomUUID();
        String accountId = UUID.randomUUID().toString();
        String organizationId = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/media/" + mediaId + "/kyb-metadata", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Test-Identity")).isEqualTo("signed-token");
            byte[] body = ("{\"success\":true,\"data\":{\"id\":\"" + mediaId
                    + "\",\"ownerAccountId\":\"" + accountId
                    + "\",\"organizationId\":\"" + organizationId
                    + "\",\"purpose\":\"merchant_kyb\",\"domainType\":\"merchant_kyb\""
                    + ",\"domainId\":\"" + organizationId
                    + "\",\"status\":\"active\",\"mimeType\":\"application/pdf\""
                    + ",\"sizeBytes\":1234,\"expiresAt\":null}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        KybMediaMetadata metadata = client().requireUsable(mediaId, organizationId, accountId).block();

        assertThat(metadata).isNotNull();
        assertThat(metadata.mimeType()).isEqualTo("application/pdf");
        assertThat(metadata.sizeBytes()).isEqualTo(1234L);
    }

    @Test
    void mapsMissingMediaToBadRequestAndUpstreamFailureToServiceUnavailable() throws Exception {
        UUID mediaId = UUID.randomUUID();
        String organizationId = UUID.randomUUID().toString();
        String accountId = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/media/" + mediaId + "/kyb-metadata", exchange -> {
            int status = "missing".equals(exchange.getRequestHeaders().getFirst("X-Mode")) ? 404 : 500;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();

        KybMediaClient unavailable = client();
        assertIdentityStatus(unavailable, mediaId, organizationId, accountId, 503);

        IdentityServiceAssertionIssuer issuer = mock(IdentityServiceAssertionIssuer.class);
        when(issuer.issueForOrganization(organizationId, "grassland-intelligence")).thenReturn("missing");
        KybMediaClient missing = new KybMediaClient(issuer, new KybMediaValidator(), baseUrl(), "X-Mode", 1000);
        assertIdentityStatus(missing, mediaId, organizationId, accountId, 400);
    }

    @Test
    void retainsAndReleasesMediaUsingTheSameReferenceToken() throws Exception {
        UUID mediaId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        String organizationId = UUID.randomUUID().toString();
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Test-Identity")).isEqualTo("signed-token");
            if ("POST".equals(exchange.getRequestMethod())) {
                assertThat(exchange.getRequestURI().getPath())
                        .isEqualTo("/api/media/" + mediaId + "/kyb-retentions");
            } else {
                assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
                assertThat(exchange.getRequestURI().getPath())
                        .isEqualTo("/api/media/" + mediaId + "/kyb-retentions/" + referenceId);
            }
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        KybMediaClient client = client();
        client.retain(mediaId, organizationId, referenceId).block();
        client.release(mediaId, organizationId, referenceId).block();

        assertThat(calls).hasValue(2);
    }

    @Test
    void acquiresIdempotentLeaseThroughPutAndReadsServerDeadline() throws Exception {
        UUID mediaId = UUID.randomUUID();
        UUID referenceId = UUID.randomUUID();
        String organizationId = UUID.randomUUID().toString();
        Instant leaseUntil = Instant.now().plusSeconds(3600);
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"success\":true,\"data\":{\"mediaReferenceId\":\"" + mediaId
                    + "\",\"referenceId\":\"" + referenceId
                    + "\",\"referenceType\":\"attachment\",\"leaseUntil\":\"" + leaseUntil
                    + "\",\"retainedUntil\":null}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        KybMediaRetentionReceipt receipt = client().acquireLease(
                mediaId, organizationId, referenceId, "attachment", 3600L).block();

        assertThat(receipt).isNotNull();
        assertThat(receipt.leaseUntil()).isEqualTo(leaseUntil);
        assertThat(method).hasValue("PUT");
        assertThat(path).hasValue("/api/media/" + mediaId + "/kyb-retentions/" + referenceId);
        assertThat(requestBody.get()).contains("\"referenceType\":\"attachment\"")
                .contains("\"mode\":\"lease\"")
                .contains("\"leaseSeconds\":3600");
    }

    private KybMediaClient client() {
        IdentityServiceAssertionIssuer issuer = mock(IdentityServiceAssertionIssuer.class);
        when(issuer.issueForOrganization(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("grassland-intelligence"))).thenReturn("signed-token");
        return new KybMediaClient(issuer, new KybMediaValidator(), baseUrl(), "X-Test-Identity", 1000);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void assertIdentityStatus(KybMediaClient client, UUID mediaId, String organizationId,
                                             String accountId, int expectedStatus) {
        assertThatThrownBy(() -> client.requireUsable(mediaId, organizationId, accountId).block())
                .isInstanceOf(IdentityException.class)
                .satisfies(error -> assertThat(((IdentityException) error).status()).isEqualTo(expectedStatus));
    }
}
