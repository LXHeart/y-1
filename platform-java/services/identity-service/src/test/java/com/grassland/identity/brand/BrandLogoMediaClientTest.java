package com.grassland.identity.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.security.IdentityServiceAssertionIssuer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BrandLogoMediaClient} 真 HTTP 映射测试（照 {@code KybMediaClientTest} 的 HttpServer 模式）。
 *
 * <p>钉住 #32 D7 的枢纽行为：断言头签发（服务断言、不信请求体）、{@code brand-logo-url} 的
 * 200→downloadUrl / 404→empty / 5xx·超时·坏信封→503 映射、票据 4xx 同码透传上游中文错误、
 * 以及 {@link BrandLogoMediaClient#logoUrlFailSoft} 组合真实 {@code usableLogoUrl} 时
 * 上游故障 → null 不抛（GET 资料不因 Logo 故障 500）。intelligence 信封/字段名漂移时在此报警。
 *
 * <p>正确性用例的客户端超时给 5s（宽松）：紧超时下冷 JVM/负载抖动会把超时也映射成
 * 503「品牌Logo服务暂不可用」，掩盖真实映射；紧超时只留给专用的超时用例（200ms）。
 */
class BrandLogoMediaClientTest {

    private static final String HEADER = "X-Test-Identity";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("200 信封取 downloadUrl 且携带服务断言头；logoUrlFailSoft 透传成功 URL")
    void readsDownloadUrlAndSendsServiceAssertion() throws Exception {
        String mediaId = UUID.randomUUID().toString();
        String organizationId = UUID.randomUUID().toString();
        String url = "https://cdn.example.com/brand-logo/" + mediaId;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/media/" + mediaId + "/brand-logo-url", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst(HEADER)).isEqualTo("signed-token");
            byte[] body = ("{\"success\":true,\"data\":{\"downloadUrl\":\"" + url
                    + "\",\"expiresAt\":\"2026-08-18T12:00:00Z\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            respond(exchange, 200, body);
        });
        server.start();

        BrandLogoMediaClient client = client(organizationId, 5000);

        assertThat(client.usableLogoUrl(mediaId, organizationId).block()).isEqualTo(url);
        assertThat(client.logoUrlFailSoft(mediaId, organizationId).block()).isEqualTo(url);
    }

    @Test
    @DisplayName("404 → empty；500 / 坏信封（data.downloadUrl 缺失）→ 503 品牌Logo服务暂不可用")
    void mapsMissingLogoToEmptyAndFailuresToServiceUnavailable() throws Exception {
        String mediaId = UUID.randomUUID().toString();
        String organizationId = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/media/" + mediaId + "/brand-logo-url", exchange -> {
            if ("missing".equals(exchange.getRequestHeaders().getFirst("X-Mode"))) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if ("bad-envelope".equals(exchange.getRequestHeaders().getFirst("X-Mode"))) {
                // 字段名漂移（downloadUrl 缺失）必须 fail-closed，不得把 null URL 当成功
                respond(exchange, 200, "{\"success\":true,\"data\":{\"expiresAt\":null}}"
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        BrandLogoMediaClient unavailable = client(organizationId, 5000);
        assertStatus(() -> unavailable.usableLogoUrl(mediaId, organizationId).block(), 503);

        BrandLogoMediaClient brokenEnvelope = client(organizationId, 1000, "X-Mode", "bad-envelope");
        assertStatus(() -> brokenEnvelope.usableLogoUrl(mediaId, organizationId).block(), 503);

        BrandLogoMediaClient missing = client(organizationId, 1000, "X-Mode", "missing");
        assertThat(missing.usableLogoUrl(mediaId, organizationId).block()).isNull();
    }

    @Test
    @DisplayName("logoUrlFailSoft 组合真实 usableLogoUrl：上游 5xx → null 不抛（资料仍可读）")
    void logoUrlFailSoftSwallowsRealUpstreamFailures() throws Exception {
        String mediaId = UUID.randomUUID().toString();
        String organizationId = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/media/" + mediaId + "/brand-logo-url", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        BrandLogoMediaClient client = client(organizationId, 5000);

        assertThat(client.logoUrlFailSoft(mediaId, organizationId).block()).isNull();
        // fail-soft 只包 logoUrlFailSoft：同一故障经 usableLogoUrl 仍 fail-closed 503（PUT 写路径）。
        assertStatus(() -> client.usableLogoUrl(mediaId, organizationId).block(), 503);
    }

    @Test
    @DisplayName("createTicket：POST 服务断言体（ownerAccountId/contentType/sizeBytes）并透传票据 JSON")
    void createTicketPostsAssertedBodyAndPassesTicketThrough() throws Exception {
        String organizationId = UUID.randomUUID().toString();
        String ownerAccountId = UUID.randomUUID().toString();
        UUID ticketId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-08-18T12:15:00Z");
        StringBuilder requestBody = new StringBuilder();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/media/brand-logo-upload-tickets", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst(HEADER)).isEqualTo("signed-token");
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            requestBody.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"success\":true,\"data\":{\"id\":\"" + ticketId
                    + "\",\"objectKey\":\"media/brand_logo/" + ticketId
                    + "\",\"uploadUrl\":\"https://upload.test/media-pending/" + ticketId
                    + "\",\"method\":\"PUT\",\"headers\":{\"Content-Type\":\"image/png\"}"
                    + ",\"expiresAt\":\"" + expiresAt + "\"}}").getBytes(StandardCharsets.UTF_8);
            respond(exchange, 200, body);
        });
        server.start();

        BrandLogoUploadTicket ticket = client(organizationId, 5000)
                .createTicket(organizationId, ownerAccountId, "image/png", 2048L).block();

        assertThat(ticket).isNotNull();
        assertThat(ticket.id()).isEqualTo(ticketId);
        assertThat(ticket.objectKey()).isEqualTo("media/brand_logo/" + ticketId);
        assertThat(ticket.uploadUrl().toString()).isEqualTo("https://upload.test/media-pending/" + ticketId);
        assertThat(ticket.method()).isEqualTo("PUT");
        assertThat(ticket.headers()).containsEntry("Content-Type", "image/png");
        assertThat(ticket.expiresAt()).isEqualTo(expiresAt);
        // ownerAccountId=操作者由 identity 断言注入，组织上下文只取服务断言（#32 D6）
        assertThat(requestBody.toString())
                .contains("\"ownerAccountId\":\"" + ownerAccountId + "\"")
                .contains("\"contentType\":\"image/png\"")
                .contains("\"sizeBytes\":2048");
    }

    @Test
    @DisplayName("createTicket：上游 400 透传同码 + 中文错误；上游 5xx → 503")
    void createTicketPassesThroughUpstreamClientErrors() throws Exception {
        String organizationId = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/media/brand-logo-upload-tickets", exchange -> {
            if ("server-error".equals(exchange.getRequestHeaders().getFirst("X-Mode"))) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            respond(exchange, 400, ("{\"success\":false,\"error\":\"品牌 Logo 仅支持 PNG、JPEG 或 WebP 图片\"}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        server.start();

        BrandLogoMediaClient rejected = client(organizationId, 5000);
        assertThatThrownBy(() -> rejected.createTicket(organizationId, "owner", "image/gif", 2048L).block())
                .isInstanceOf(IdentityException.class)
                .satisfies(error -> {
                    assertThat(((IdentityException) error).status()).isEqualTo(400);
                    assertThat(error.getMessage()).isEqualTo("品牌 Logo 仅支持 PNG、JPEG 或 WebP 图片");
                });

        BrandLogoMediaClient serverError = client(organizationId, 1000, "X-Mode", "server-error");
        assertStatus(() -> serverError.createTicket(organizationId, "owner", "image/png", 2048L).block(), 503);
    }

    @Test
    @DisplayName("上游无响应超过超时 → 503（不悬挂调用方）")
    void timesOutUnresponsiveUpstream() throws Exception {
        String mediaId = UUID.randomUUID().toString();
        String organizationId = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/media/" + mediaId + "/brand-logo-url", exchange -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        assertStatus(() -> client(organizationId, 200).usableLogoUrl(mediaId, organizationId).block(), 503);
    }

    private BrandLogoMediaClient client(String organizationId, long timeoutMs) {
        return client(organizationId, timeoutMs, HEADER, "signed-token");
    }

    /** 照 KybMediaClientTest：模式用例借断言头的值当开关（头名换成 X-Mode，值即模式）。 */
    private BrandLogoMediaClient client(String organizationId, long timeoutMs, String headerName, String token) {
        IdentityServiceAssertionIssuer issuer = mock(IdentityServiceAssertionIssuer.class);
        when(issuer.issueForOrganization(anyString(), eq("grassland-intelligence"))).thenReturn(token);
        return new BrandLogoMediaClient(issuer, "http://127.0.0.1:" + server.getAddress().getPort(),
                headerName, timeoutMs);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void assertStatus(Runnable call, int expectedStatus) {
        assertThatThrownBy(call::run)
                .isInstanceOf(IdentityException.class)
                .satisfies(error -> assertThat(((IdentityException) error).status()).isEqualTo(expectedStatus));
    }
}
