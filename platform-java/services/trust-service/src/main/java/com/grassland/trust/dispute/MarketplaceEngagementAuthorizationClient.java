package com.grassland.trust.dispute;

import com.grassland.http.ManagedWebClientFactory;

import com.grassland.trust.security.TrustServiceAssertionIssuer;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * trust→marketplace 争议参与方授权客户端（草场 Slice 12 安全收口）。
 *
 * <p>
 * 开争议前以现签 {@code principal=trust} 服务断言调 marketplace 内部端点，取回 canonical task
 * organization 并确认调用方是该 application 的当事方。marketplace 是 engagement
 * 参与方与组织的权威——trust 不自行判定。
 *
 * <p>
 * 返回 {@code Optional}：有值=授权通过（含 organizationId）；空=非当事方(403)/不存在(404)/非
 * accepted(409)， 一律视为授权拒绝、不创建争议。其余 transport/畸形响应 fail-closed 抛
 * {@link AuthorizationException}（不静默放行）。
 */
@Component
public class MarketplaceEngagementAuthorizationClient {

	private static final Logger log = LoggerFactory.getLogger(MarketplaceEngagementAuthorizationClient.class);

	private final WebClient webClient;
	private final TrustServiceAssertionIssuer issuer;
	private final String headerName;

	public MarketplaceEngagementAuthorizationClient(TrustServiceAssertionIssuer issuer,
			@Value("${marketplace.service.base-url:http://marketplace-service:8083}") String baseUrl,
			@Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
		this.issuer = issuer;
		this.headerName = headerName;
		this.webClient = ManagedWebClientFactory.create(MarketplaceEngagementAuthorizationClient.class, baseUrl);
	}

	/**
	 * 授权结果：成功时携带 marketplace 返回的 canonical organizationId。
	 * {@code resultAnchorAt}（任务书 #70 卡B）= 履约最近一次结果性事件时刻，PRD §7.1 异议 48h 时限的起算点；null
	 * = marketplace 无任何结果性事件（fail-open 不设限）。
	 */
	public record Authorization(String engagementRef, String organizationId, String recommenderAccountId,
			boolean premiumSupportAtAccept, java.time.Instant resultAnchorAt) {
		public Authorization(String engagementRef, String organizationId) {
			this(engagementRef, organizationId, null, false, null);
		}

		/** 既有四参调用方（含 TrustItSupport 默认桩）兼容：锚点 null。 */
		public Authorization(String engagementRef, String organizationId, String recommenderAccountId,
				boolean premiumSupportAtAccept) {
			this(engagementRef, organizationId, recommenderAccountId, premiumSupportAtAccept, null);
		}
	}

	/**
	 * @param actorAccountId
	 *            已验签的终端发起方账号（trust 从断言取得，非浏览器输入）
	 * @param actorIdentity
	 *            该账号活动身份（merchant/recommender）
	 * @return 授权通过则 {@link Authorization}；非当事方/不存在/非 accepted → empty（trust 据此
	 *         403/拒绝）
	 */
	public Mono<Authorization> authorize(String applicationId, String actorAccountId, String actorIdentity) {
		return webClient.post().uri("/internal/marketplace/engagements/{id}/dispute-authorization", applicationId)
				.header(headerName, issuer.issueService("grassland-marketplace"))
				.bodyValue(java.util.Map.of("actorAccountId", actorAccountId, "actorIdentity", actorIdentity))
				.exchangeToMono(resp -> {
					int code = resp.statusCode().value();
					log.info("dispute-authorization HTTP {} application={} actor={}", code, applicationId,
							actorAccountId);
					if (code == 200) {
						return resp.bodyToMono(AuthorizationResponse.class)
								.map(body -> validate(body, applicationId, actorAccountId, actorIdentity));
					}
					if (code == 403 || code == 404 || code == 409 || code == 400) {
						return Mono.justOrEmpty(Optional.<Authorization>empty());
					}
					return resp.bodyToMono(String.class).defaultIfEmpty("").flatMap(b -> Mono.<Authorization>error(
							new AuthorizationException("authorization failed: HTTP " + code + ": " + b)));
				}).onErrorMap(error -> !(error instanceof AuthorizationException),
						error -> new AuthorizationException("authorization response invalid", error));
	}

	private static Authorization validate(AuthorizationResponse body, String applicationId, String actorAccountId,
			String actorIdentity) {
		AuthorizationData data = body == null ? null : body.data();
		if (body == null || !body.success() || data == null || !applicationId.equals(data.engagementRef())
				|| !isUuid(data.engagementRef()) || !isUuid(data.organizationId())
				|| !isUuid(data.recommenderAccountId()) || data.premiumSupportAtAccept() == null
				|| ("recommender".equals(actorIdentity) && !actorAccountId.equals(data.recommenderAccountId()))) {
			throw new AuthorizationException("authorization response failed validation");
		}
		return new Authorization(data.engagementRef(), data.organizationId(), data.recommenderAccountId(),
				data.premiumSupportAtAccept(), parseAnchorAt(data.resultAnchorAt()));
	}

	/**
	 * 锚点解析纪律与客户端既有 fail-closed 一致：字段 null/blank → null（放行，窗口不设限）； 非空但不是合法 ISO-8601
	 * → {@link AuthorizationException}（畸形响应不得静默当作无锚点）。
	 */
	private static java.time.Instant parseAnchorAt(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return java.time.Instant.parse(raw);
		} catch (java.time.format.DateTimeParseException invalid) {
			throw new AuthorizationException("authorization resultAnchorAt is not ISO-8601: " + raw);
		}
	}

	private static boolean isUuid(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		try {
			return java.util.UUID.fromString(value).toString().equals(value);
		} catch (IllegalArgumentException invalid) {
			return false;
		}
	}

	/** 用于解码 marketplace 权威授权信封。 */
	private record AuthorizationResponse(boolean success, AuthorizationData data) {
	}

	private record AuthorizationData(String engagementRef, String organizationId, String recommenderAccountId,
			Boolean premiumSupportAtAccept, String resultAnchorAt) {
	}

	/** marketplace 调用非授权失败（transport/未知状态）：fail-closed，由全局 handler 转 5xx，不创建争议。 */
	public static final class AuthorizationException extends RuntimeException {
		public AuthorizationException(String message) {
			super(message);
		}

		public AuthorizationException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
