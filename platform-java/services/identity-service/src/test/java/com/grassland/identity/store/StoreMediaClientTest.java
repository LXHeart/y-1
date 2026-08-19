package com.grassland.identity.store;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StoreMediaClient} 真 HTTP 映射测试（照 {@code BrandLogoMediaClientTest} 的 HttpServer 模式）。
 *
 * <p>钉住 #42 D2/D5 的枢纽行为：断言头签发（audience=grassland-intelligence）、票据 200 透传 /
 * 4xx 同码透传中文错误、批量换 URL 200 子集映射 / 坏信封 fail-closed、5xx·超时 → 503
 * 「门店媒体服务暂不可用」。intelligence 信封/字段名漂移时在此报警。
 *
 * <p>正确性用例超时给 5s（宽松），紧超时只留给专用超时用例（200ms）。
 */
class StoreMediaClientTest {

    private static final String HEADER = "X-Test-Identity";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("createTicket：POST 服务断言体（ownerAccountId/storeId/contentType/sizeBytes）并透传票据 JSON")
    void createTicketPostsAssertedBodyAndPassesTicketThrough() throws Exception {
        String organizationId = UUID.randomUUID().toString();
        String ownerAccountId = UUID.randomUUID().toString();
        String storeId = UUID.randomUUID().toString();
        UUID ticketId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-08-19T12:15:00Z");
        StringBuilder requestBody = new StringBuilder();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/media/store-media-upload-tickets", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst(HEADER)).isEqualTo("signed-token");
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            requestBody.append(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"success\":true,\"data\":{\"id\":\"" + ticketId
                    + "\",\"objectKey\":\"media/store_media/" + ticketId
                    + "\",\"uploadUrl\":\"https://upload.test/store-media/" + ticketId
                    + "\",\"method\":\"PUT\",\"headers\":{\"Content-Type\":\"image/jpeg\"}"
                    + ",\"expiresAt\":\"" + expiresAt + "\"}}").getBytes(StandardCharsets.UTF_8);
            respond(exchange, 200, body);
        });
        server.start();

        StoreMediaUploadTicket ticket = client(organizationId, 5000)
                .createTicket(organizationId, ownerAccountId, storeId, "image/jpeg", 4096L).block();

        assertThat(ticket).isNotNull();
        assertThat(ticket.id()).isEqualTo(ticketId);
        assertThat(ticket.objectKey()).isEqualTo("media/store_media/" + ticketId);
        assertThat(ticket.uploadUrl().toString()).isEqualTo("https://upload.test/store-media/" + ticketId);
        assertThat(ticket.method()).isEqualTo("PUT");
        assertThat(ticket.headers()).containsEntry("Content-Type", "image/jpeg");
        assertThat(ticket.expiresAt()).isEqualTo(expiresAt);
        // ownerAccountId=操作者、storeId 落 domain 锚，组织上下文只取服务断言（#42 D2）。
        assertThat(requestBody.toString())
                .contains("\"ownerAccountId\":\"" + ownerAccountId + "\"")
                .contains("\"storeId\":\"" + storeId + "\"")
                .contains("\"contentType\":\"image/jpeg\"")
                .contains("\"sizeBytes\":4096");
    }

    @Test
    @DisplayName("createTicket：上游 400 透传同码 + 中文错误；上游 5xx → 503")
    void createTicketPassesThroughUpstreamClientErrors() throws Exception {
        String organizationId = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/media/store-media-upload-tickets", exchange -> {
            if ("server-error".equals(exchange.getRequestHeaders().getFirst("X-Mode"))) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            respond(exchange, 400, ("{\"success\":false,\"error\":\"门头照片仅支持 JPEG、PNG 或 WebP 图片\"}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        server.start();

        StoreMediaClient rejected = client(organizationId, 5000);
        String storeId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> rejected.createTicket(organizationId, "owner", storeId, "image/gif", 2048L).block())
                .isInstanceOf(IdentityException.class)
                .satisfies(error -> {
                    assertThat(((IdentityException) error).status()).isEqualTo(400);
                    assertThat(error.getMessage()).isEqualTo("门头照片仅支持 JPEG、PNG 或 WebP 图片");
                });

        StoreMediaClient serverError = client(organizationId, 1000, "X-Mode", "server-error");
        assertStatus(() -> serverError.createTicket(organizationId, "owner", storeId, "image/png", 2048L).block(), 503);
    }

    @Test
    @DisplayName("downloadUrls：200 信封映射为 id→ResolvedMedia 子集且携带服务断言头")
    void downloadUrlsMapsSubsetEnvelope() throws Exception {
        String organizationId = UUID.randomUUID().toString();
        String storeId = UUID.randomUUID().toString();
        String alive = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/media/store-media-download-urls", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst(HEADER)).isEqualTo("signed-token");
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("\"storeId\":\"" + storeId + "\"");
            byte[] body = ("{\"success\":true,\"data\":{\"items\":[{"
                    + "\"id\":\"" + alive + "\",\"mimeType\":\"video/mp4\",\"sizeBytes\":8192,"
                    + "\"downloadUrl\":\"https://cdn.example.com/store-media/" + alive + "\","
                    + "\"expiresAt\":\"2026-08-20T00:00:00Z\"}]}}").getBytes(StandardCharsets.UTF_8);
            respond(exchange, 200, body);
        });
        server.start();

        Map<String, ResolvedMedia> resolved = client(organizationId, 5000)
                .downloadUrls(organizationId, storeId, List.of(alive, UUID.randomUUID().toString()))
                .block();

        assertThat(resolved).hasSize(1).containsOnlyKeys(alive);
        ResolvedMedia media = resolved.get(alive);
        assertThat(media.mimeType()).isEqualTo("video/mp4");
        assertThat(media.sizeBytes()).isEqualTo(8192L);
        assertThat(media.downloadUrl()).isEqualTo("https://cdn.example.com/store-media/" + alive);
        assertThat(media.expiresAt()).isEqualTo(Instant.parse("2026-08-20T00:00:00Z"));
    }

    @Test
    @DisplayName("downloadUrls：坏信封（items 缺失）/ 5xx → 503；4xx 透传同码 + 中文错误")
    void downloadUrlsFailsClosedOnBrokenUpstream() throws Exception {
        String organizationId = UUID.randomUUID().toString();
        String storeId = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/media/store-media-download-urls", exchange -> {
            String mode = exchange.getRequestHeaders().getFirst("X-Mode");
            if ("bad-envelope".equals(mode)) {
                // 字段名漂移（items 缺失）必须 fail-closed，不得把空集当成功。
                respond(exchange, 200, "{\"success\":true,\"data\":{}}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("client-error".equals(mode)) {
                respond(exchange, 400, "{\"success\":false,\"error\":\"mediaIds 不能超过 50 个\"}"
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        List<String> ids = List.of(UUID.randomUUID().toString());
        assertStatus(() -> client(organizationId, 5000)
                .downloadUrls(organizationId, storeId, ids).block(), 503);
        assertStatus(() -> client(organizationId, 1000, "X-Mode", "bad-envelope")
                .downloadUrls(organizationId, storeId, ids).block(), 503);

        StoreMediaClient clientError = client(organizationId, 1000, "X-Mode", "client-error");
        assertThatThrownBy(() -> clientError.downloadUrls(organizationId, storeId, ids).block())
                .isInstanceOf(IdentityException.class)
                .satisfies(error -> {
                    assertThat(((IdentityException) error).status()).isEqualTo(400);
                    assertThat(error.getMessage()).isEqualTo("mediaIds 不能超过 50 个");
                });
    }

    @Test
    @DisplayName("上游无响应超过超时 → 503（不悬挂调用方）")
    void timesOutUnresponsiveUpstream() throws Exception {
        String organizationId = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/media/store-media-download-urls", exchange -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        assertStatus(() -> client(organizationId, 200)
                .downloadUrls(organizationId, UUID.randomUUID().toString(),
                        List.of(UUID.randomUUID().toString())).block(), 503);
    }

    private StoreMediaClient client(String organizationId, long timeoutMs) {
        return client(organizationId, timeoutMs, HEADER, "signed-token");
    }

    /** 照 BrandLogoMediaClientTest：模式用例借断言头的值当开关（头名换成 X-Mode，值即模式）。 */
    private StoreMediaClient client(String organizationId, long timeoutMs, String headerName, String token) {
        IdentityServiceAssertionIssuer issuer = mock(IdentityServiceAssertionIssuer.class);
        when(issuer.issueForOrganization(anyString(), eq("grassland-intelligence"))).thenReturn(token);
        return new StoreMediaClient(issuer, "http://127.0.0.1:" + server.getAddress().getPort(),
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
