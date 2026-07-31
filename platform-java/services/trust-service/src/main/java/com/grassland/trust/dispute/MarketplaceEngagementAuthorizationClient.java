package com.grassland.trust.dispute;

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
 * <p>开争议前以现签 {@code principal=trust} 服务断言调 marketplace 内部端点，取回 canonical task organization
 * 并确认调用方是该 application 的当事方。marketplace 是 engagement 参与方与组织的权威——trust 不自行判定。
 *
 * <p>返回 {@code Optional}：有值=授权通过（含 organizationId）；空=非当事方(403)/不存在(404)/非 accepted(409)，
 * 一律视为授权拒绝、不创建争议。其余 transport/畸形响应 fail-closed 抛 {@link AuthorizationException}（不静默放行）。
 */
@Component
public class MarketplaceEngagementAuthorizationClient {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceEngagementAuthorizationClient.class);

    private final WebClient webClient;
    private final TrustServiceAssertionIssuer issuer;
    private final String headerName;

    public MarketplaceEngagementAuthorizationClient(
            TrustServiceAssertionIssuer issuer,
            @Value("${marketplace.service.base-url:http://marketplace-service:8083}") String baseUrl,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.issuer = issuer;
        this.headerName = headerName;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /** 授权结果：成功时携带 marketplace 返回的 canonical organizationId。 */
    public record Authorization(String engagementRef, String organizationId) {}

    /**
     * @param actorAccountId  已验签的终端发起方账号（trust 从断言取得，非浏览器输入）
     * @param actorIdentity   该账号活动身份（merchant/recommender）
     * @return 授权通过则 {@link Authorization}；非当事方/不存在/非 accepted → empty（trust 据此 403/拒绝）
     */
    public Mono<Authorization> authorize(String applicationId, String actorAccountId, String actorIdentity) {
        return webClient.post()
                .uri("/internal/marketplace/engagements/{id}/dispute-authorization", applicationId)
                .header(headerName, issuer.issueService())
                .bodyValue(java.util.Map.of("actorAccountId", actorAccountId, "actorIdentity", actorIdentity))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.info("dispute-authorization HTTP {} application={} actor={}", code, applicationId, actorAccountId);
                    if (code == 200) {
                        return resp.bodyToMono(AuthorizationResponse.class)
                                .map(body -> new Authorization(body.data().engagementRef(), body.data().organizationId()));
                    }
                    if (code == 403 || code == 404 || code == 409 || code == 400) {
                        return Mono.justOrEmpty(Optional.<Authorization>empty());
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.<Authorization>error(
                                    new AuthorizationException("authorization failed: HTTP " + code + ": " + b)));
                });
    }

    /** 用于解码 marketplace 信封 {@code {success,data:{engagementRef,organizationId}}}。 */
    private record AuthorizationResponse(boolean success, AuthorizationData data) {}

    private record AuthorizationData(String engagementRef, String organizationId) {}

    /** marketplace 调用非授权失败（transport/未知状态）：fail-closed，由全局 handler 转 5xx，不创建争议。 */
    public static final class AuthorizationException extends RuntimeException {
        public AuthorizationException(String message) {
            super(message);
        }
    }
}
