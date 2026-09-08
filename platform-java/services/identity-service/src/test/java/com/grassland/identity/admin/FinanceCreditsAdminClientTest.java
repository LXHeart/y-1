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
 * 覆盖批量余额解析、award/refund 成功、上游不可用 → 502、信封异常。 不依赖 testcontainers（IT 在
 * {@code AdminUserControllerIT} 覆盖端到端）。
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
			byte[] body = ("{\"success\":true,\"data\":{\"accounts\":[" + "{\"accountId\":\"" + acctA
					+ "\",\"balance\":5,\"totalEarned\":5,\"totalSpent\":0}," + "{\"accountId\":\"" + acctB
					+ "\",\"balance\":3,\"totalEarned\":10,\"totalSpent\":7}" + "]}}").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();

		Map<String, FinanceCreditsAdminClient.AccountBalance> balances = client().fetchBalances(List.of(acctA, acctB))
				.block();

		assertThat(balances).hasSize(2);
		assertThat(balances.get(acctA)).isEqualTo(new FinanceCreditsAdminClient.AccountBalance(5, 5, 0));
		assertThat(balances.get(acctB)).isEqualTo(new FinanceCreditsAdminClient.AccountBalance(3, 10, 7));
	}

	@Test
	void fetchBalancesReturnsEmptyMapForEmptyInput() {
		// 空入参不发 HTTP，直接返回空 map（不需要起 server）
		FinanceCreditsAdminClient client = new FinanceCreditsAdminClient("http://127.0.0.1:1", 1000, assertionIssuer());
		Map<String, FinanceCreditsAdminClient.AccountBalance> balances = client.fetchBalances(List.of()).block();
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

		IdentityException error = assertIdentityStatus(client().fetchBalances(List.of(acct)), 502);
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
			byte[] body = "{\"success\":true,\"data\":{\"awarded\":true,\"balance\":5}}"
					.getBytes(StandardCharsets.UTF_8);
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
	void adminAdjustPostsSignedBodyAndCompletesOnSuccess() throws Exception {
		String acct = UUID.randomUUID().toString();
		String operator = UUID.randomUUID().toString();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/internal/credits/admin-adjust", exchange -> {
			assertThat(exchange.getRequestHeaders().getFirst("X-Grassland-Identity"))
					.isEqualTo("identity-finance-assertion");
			String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			// 带符号 amount 原样透传 + operator 审计 + 幂等键（任务书 #94 §6.2）
			assertThat(request).contains("\"amount\":-3").contains("\"operatorAccountId\":\"" + operator + "\"")
					.contains("\"operationId\":\"admin_adjust:key-1\"");
			byte[] body = "{\"success\":true,\"data\":{\"adjusted\":true,\"balance\":95,\"deduplicated\":false}}"
					.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();

		client().adminAdjust(acct, -3, operator, "扣减", "admin_adjust:key-1").block();
		// 完成 = 无异常即通过
	}

	@Test
	void adminAdjustPassesThroughFinanceBusinessErrors() throws Exception {
		String acct = UUID.randomUUID().toString();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
		// finance 4xx 业务信封 {success:false,error} → IdentityException(同码, 同文案)（D94-06）
		// 首请求回 402、次请求回 409（4xx 不重试，两次分别是两个独立调用）
		server.createContext("/internal/credits/admin-adjust", exchange -> {
			boolean insufficient = hits.incrementAndGet() == 1;
			byte[] body = (insufficient
					? "{\"success\":false,\"error\":\"积分余额不足\"}"
					: "{\"success\":false,\"error\":\"积分操作幂等键冲突\"}").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(insufficient ? 402 : 409, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		FinanceCreditsAdminClient client = client();

		IdentityException insufficient = assertIdentityStatus(
				client.adminAdjust(acct, -5, "op", "扣减", "admin_adjust:k-402"), 402);
		assertThat(insufficient.getMessage()).isEqualTo("积分余额不足");
		IdentityException conflict = assertIdentityStatus(
				client.adminAdjust(acct, -5, "op", "扣减", "admin_adjust:k-409"), 409);
		assertThat(conflict.getMessage()).isEqualTo("积分操作幂等键冲突");
		assertThat(hits.get()).isEqualTo(2);
	}

	@Test
	void adminAdjustMapsUpstream5xxTo502() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/internal/credits/admin-adjust", exchange -> {
			exchange.sendResponseHeaders(503, -1);
			exchange.close();
		});
		server.start();

		IdentityException error = assertIdentityStatus(
				client().adminAdjust(UUID.randomUUID().toString(), 5, "op", "n", "admin_adjust:k"), 502);
		assertThat(error.getMessage()).contains("积分服务");
	}

	@Test
	void adminAdjustRetriesTimeoutOnceWithSameOperationId() throws Exception {
		String acct = UUID.randomUUID().toString();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		java.util.List<String> receivedKeys = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
		java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
		server.createContext("/internal/credits/admin-adjust", exchange -> {
			int attempt = hits.incrementAndGet();
			String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			receivedKeys.add(request);
			if (attempt == 1) {
				try {
					// 首请求超时（client timeout=300ms），上游已提交但响应丢失的重放场景
					Thread.sleep(600);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
			}
			byte[] body = "{\"success\":true,\"data\":{\"adjusted\":true,\"deduplicated\":true}}"
					.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		FinanceCreditsAdminClient client = new FinanceCreditsAdminClient(
				"http://127.0.0.1:" + server.getAddress().getPort(), 300, assertionIssuer());

		// 超时重试一次，且两次请求携带同一 operationId（幂等键贯穿，D94-07）
		client.adminAdjust(acct, -5, "op-acct", "扣减", "admin_adjust:retry-key").block();

		assertThat(hits.get()).isEqualTo(2);
		assertThat(receivedKeys).hasSize(2);
		assertThat(receivedKeys.get(0)).contains("\"operationId\":\"admin_adjust:retry-key\"");
		assertThat(receivedKeys.get(1)).contains("\"operationId\":\"admin_adjust:retry-key\"");
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
		when(issuer.issueForOrganization(null, "grassland-finance")).thenReturn("identity-finance-assertion");
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
