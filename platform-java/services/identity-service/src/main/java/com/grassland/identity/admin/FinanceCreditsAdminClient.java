package com.grassland.identity.admin;

import com.grassland.http.ManagedWebClientFactory;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.security.IdentityServiceAssertionIssuer;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * identity 到 finance 的积分 admin 客户端。每个请求携带受众为 finance 的命名服务断言。
 *
 * <p>
 * 供 {@link AdminUserController} 读批量余额 + 写 award/管理调账（adminAdjust）。finance 不可用 →
 * {@link IdentityException}(502)，调用方直接透传给 admin（不静默吞）。
 */
@Component
public class FinanceCreditsAdminClient {

	private static final ParameterizedTypeReference<Map<String, Object>> ENVELOPE = new ParameterizedTypeReference<>() {
	};

	private final WebClient webClient;
	private final Duration timeout;
	private final IdentityServiceAssertionIssuer assertionIssuer;

	public FinanceCreditsAdminClient(
			@Value("${identity.finance-credits.base-url:http://finance-service:8084}") String baseUrl,
			@Value("${identity.finance-credits.timeout-ms:5000}") long timeoutMs,
			IdentityServiceAssertionIssuer assertionIssuer) {
		this.webClient = ManagedWebClientFactory.create(FinanceCreditsAdminClient.class, baseUrl);
		this.timeout = Duration.ofMillis(Math.max(timeoutMs, 100));
		this.assertionIssuer = assertionIssuer;
	}

	/**
	 * 批量取余额（admin 用户列表用，避免 N+1）。返回 {@code accountId → balance} map； 未建户的 accountId
	 * 不在 map 里（调用方按缺失 = 0 余额处理）。
	 */
	public Mono<Map<String, AccountBalance>> fetchBalances(Collection<String> accountIds) {
		if (accountIds == null || accountIds.isEmpty()) {
			return Mono.just(Map.of());
		}
		List<String> ids = List.copyOf(accountIds);
		return webClient.post().uri("/internal/credits/balances")
				.header("X-Grassland-Identity", assertionIssuer.issueForOrganization(null, "grassland-finance"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("accountIds", ids)).retrieve()
				.bodyToMono(ENVELOPE).timeout(timeout).map(this::extractBalances).onErrorMap(this::wrapUpstreamError);
	}

	/** award 正数积分（注册赠送 / admin 正向调整）。 */
	public Mono<Void> award(String accountId, int amount, String note) {
		return award(accountId, amount, note, null);
	}

	/** 带幂等键的 award（注册赠送等可重试系统动作）。 */
	public Mono<Void> award(String accountId, int amount, String note, String operationId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("accountId", accountId);
		body.put("amount", amount);
		body.put("note", note);
		if (operationId != null) {
			body.put("operationId", operationId);
		}
		return postVoid("/internal/credits/award", body);
	}

	/**
	 * 管理端有符号调账（任务书 #94 D94-01/D94-06）：治理台「调整积分」唯一落点， 不再复用 award/refund。amount
	 * 带符号透传；operatorAccountId 落 finance 审计列。
	 *
	 * <p>
	 * 与 {@link #postVoid} 的差异：finance 的 4xx 业务信封 {@code {success:false,error}}
	 * <b>透传状态码与文案</b>（402 余额不足 / 409 幂等键冲突直达浏览器），仅连接失败/超时/5xx 归 502「积分服务暂不可用」。重试策略与
	 * postVoid 一致（超时/IO 一次），幂等由 {@code operationId} 在 finance 侧保证（重试复用同键 → dedup
	 * 不重复入账）。
	 */
	public Mono<Void> adminAdjust(String accountId, int amount, String operatorAccountId, String note,
			String operationId) {
		return webClient.post().uri("/internal/credits/admin-adjust")
				.header("X-Grassland-Identity", assertionIssuer.issueForOrganization(null, "grassland-finance"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("accountId", accountId, "amount", amount,
						"operatorAccountId", operatorAccountId, "note", note, "operationId", operationId))
				.exchangeToMono(response -> {
					if (response.statusCode().is2xxSuccessful()) {
						return response.releaseBody();
					}
					return response.bodyToMono(ENVELOPE).defaultIfEmpty(Map.of()).map(
							envelope -> adminAdjustUpstreamError(response.statusCode().value(), envelope.get("error")))
							.flatMap(error -> Mono.<Void>error(error));
				}).timeout(timeout).retryWhen(Retry.backoff(1, Duration.ofMillis(200)).filter(this::isRetryable))
				.onErrorMap(this::wrapUpstreamError).then();
	}

	/** finance 4xx 且带 error 文案 → IdentityException(同码, 文案)；5xx/无文案 → 502。 */
	private static IdentityException adminAdjustUpstreamError(int status, Object error) {
		String message = error == null ? null : String.valueOf(error);
		if (status >= 400 && status < 500 && message != null && !message.isBlank() && !"null".equals(message)) {
			return new IdentityException(status, message);
		}
		return new IdentityException(502, "积分服务暂不可用");
	}

	private Mono<Void> postVoid(String path, Map<String, ?> body) {
		return webClient.post().uri(path)
				.header("X-Grassland-Identity", assertionIssuer.issueForOrganization(null, "grassland-finance"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).retrieve().bodyToMono(ENVELOPE)
				.timeout(timeout)
				.flatMap(envelope -> Boolean.TRUE.equals(envelope.get("success"))
						? Mono.empty()
						: Mono.error(new IdentityException(502, "积分服务错误")))
				.retryWhen(Retry.backoff(1, Duration.ofMillis(200)).filter(this::isRetryable))
				.onErrorMap(this::wrapUpstreamError).then();
	}

	@SuppressWarnings("unchecked")
	private Map<String, AccountBalance> extractBalances(Map<String, Object> envelope) {
		if (!Boolean.TRUE.equals(envelope.get("success"))) {
			return Map.of();
		}
		Object dataObj = envelope.get("data");
		if (!(dataObj instanceof Map<?, ?> data)) {
			return Map.of();
		}
		Object accountsObj = data.get("accounts");
		if (!(accountsObj instanceof List<?> accounts)) {
			return Map.of();
		}
		Map<String, AccountBalance> result = new LinkedHashMap<>();
		for (Object item : accounts) {
			if (!(item instanceof Map<?, ?> entry)) {
				continue;
			}
			String accountId = String.valueOf(entry.get("accountId"));
			result.put(accountId, new AccountBalance(intValue(entry.get("balance")), intValue(entry.get("totalEarned")),
					intValue(entry.get("totalSpent"))));
		}
		return result;
	}

	private static int intValue(Object value) {
		if (value instanceof Number n) {
			return n.intValue();
		}
		return 0;
	}

	private boolean isRetryable(Throwable error) {
		// 超时/连接失败重试一次；业务错误（success:false）不重试
		return error instanceof java.net.SocketTimeoutException
				|| error instanceof java.util.concurrent.TimeoutException || error instanceof java.io.IOException;
	}

	private Throwable wrapUpstreamError(Throwable error) {
		if (error instanceof IdentityException) {
			return error;
		}
		return new IdentityException(502, "积分服务暂不可用");
	}

	/** 单用户余额快照（admin 列表合并用）。 */
	public record AccountBalance(int balance, int totalEarned, int totalSpent) {
	}
}
