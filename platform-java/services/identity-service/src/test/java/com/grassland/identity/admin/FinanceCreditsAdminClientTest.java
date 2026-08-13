package com.grassland.identity.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.security.IdentityServiceAssertionIssuer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FinanceCreditsAdminClient} 单测：用 {@link HttpServer} stub finance，
 * 覆盖批量余额解析、award/refund 成功、上游不可用 → 502、信封异常。
 * 不依赖 testcontainers（IT 在 {@code AdminUserControllerIT} 覆盖端到端）。
 */
class FinanceCreditsAdminClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchBalancesParsesAccountsEnvelope() throws Exception {
        String acctA = UUID.randomUUID().toString();
        String acctB = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/credits/balances", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Grassland-Identity"))
                    .isEqualTo("identity-finance-assertion");
            byte[] body = ("{\"success\":true,\"data\":{\"accounts\":["
                    + "{\"accountId\":\"" + acctA + "\",\"balance\":5,\"totalEarned\":5,\"totalSpent\":0},"
                    + "{\"accountId\":\"" + acctB + "\",\"balance\":3,\"totalEarned\":10,\"totalSpent\":7}"
                    + "]}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        Map<String, FinanceCreditsAdminClient.AccountBalance> balances =
                client().fetchBalances(List.of(acctA, acctB)).block();

        assertThat(balances).hasSize(2);
        assertThat(balances.get(acctA)).isEqualTo(new FinanceCreditsAdminClient.AccountBalance(5, 5, 0));
        assertThat(balances.get(acctB)).isEqualTo(new FinanceCreditsAdminClient.AccountBalance(3, 10, 7));
    }

    @Test
    void fetchBalancesReturnsEmptyMapForEmptyInput() {
        // 空入参不发 HTTP，直接返回空 map（不需要起 server）
        FinanceCreditsAdminClient client = new FinanceCreditsAdminClient(
                "http://127.0.0.1:1", 1000, assertionIssuer());
        Map<String, FinanceCreditsAdminClient.AccountBalance> balances =
                client.fetchBalances(List.of()).block();
        assertThat(balances).isEmpty();
    }

    @Test
    void fetchBalancesMapsUpstreamFailureTo502() throws Exception {
        String acct = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/credits/balances", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        IdentityException error = assertIdentityStatus(
                client().fetchBalances(List.of(acct)), 502);
        assertThat(error.getMessage()).contains("积分服务");
    }

    @Test
    void awardPostsToFinanceAndCompletesOnSuccess() throws Exception {
        String acct = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/credits/award", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Grassland-Identity"))
                    .isEqualTo("identity-finance-assertion");
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("\"operationId\":\"registration:" + acct + "\"");
            byte[] body = "{\"success\":true,\"data\":{\"awarded\":true,\"balance\":5}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        client().award(acct, 5, "test grant", "registration:" + acct).block();
        // 完成 = 无异常即通过
    }

    @Test
    void refundPostsToFinanceWithAdminAdjustFeature() throws Exception {
        String acct = UUID.randomUUID().toString();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/credits/refund", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Grassland-Identity"))
                    .isEqualTo("identity-finance-assertion");
            // 验证请求体含 feature=admin_adjust（对齐 legacy）
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("\"feature\":\"admin_adjust\"");
            byte[] body = "{\"success\":true,\"data\":{\"refunded\":true,\"balance\":4}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        client().refund(acct, 1, "admin 扣减").block();
    }

    @Test
    void awardMapsUpstreamUnavailableTo502() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/credits/award", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        assertIdentityStatus(client().award(UUID.randomUUID().toString(), 1, "note"), 502);
    }

    private FinanceCreditsAdminClient client() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new FinanceCreditsAdminClient(base, 2000, assertionIssuer());
    }

    private static IdentityServiceAssertionIssuer assertionIssuer() {
        IdentityServiceAssertionIssuer issuer = mock(IdentityServiceAssertionIssuer.class);
        when(issuer.issueForOrganization(null, "grassland-finance"))
                .thenReturn("identity-finance-assertion");
        return issuer;
    }

    private static IdentityException assertIdentityStatus(reactor.core.publisher.Mono<?> mono, int expectedStatus) {
        try {
            mono.block();
            throw new AssertionError("expected IdentityException but mono completed");
        } catch (IdentityException error) {
            assertThat(error.status()).isEqualTo(expectedStatus);
            return error;
        }
    }
}
