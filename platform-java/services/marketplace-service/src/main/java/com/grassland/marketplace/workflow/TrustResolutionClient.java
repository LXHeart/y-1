package com.grassland.marketplace.workflow;

import com.grassland.http.ManagedWebClientFactory;

import com.grassland.marketplace.security.ServiceAssertionIssuer;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * trust 争议终局权威读取（Slice 7B）。marketplace 对账 activity 调 {@code GET /api/trust/disputes/{id}/resolution}，
 * 现签 marketplace 服务断言（带 org）。trust 仅放行 marketplace 服务 + org 匹配 + status=final。
 *
 * <p>非 2xx 一律抛异常（Temporal 重试）：争议已在事件中声明终局，读不到终局属异常态；持久不一致由
 * activity 的内容比对转 blocked，而非靠 HTTP 错误码猜测。
 */
@Component
public class TrustResolutionClient {

    private static final Logger log = LoggerFactory.getLogger(TrustResolutionClient.class);

    private final WebClient webClient;
    private final ServiceAssertionIssuer issuer;
    private final String headerName;

    public TrustResolutionClient(ServiceAssertionIssuer issuer,
                                 @Value("${trust.service.base-url:http://trust-service:8085}") String baseUrl,
                                 @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.issuer = issuer;
        this.headerName = headerName;
        this.webClient = ManagedWebClientFactory.create(TrustResolutionClient.class, baseUrl);
    }

    public Mono<TrustResolution> resolve(String organizationId, String disputeId) {
        return webClient.get()
                .uri("/api/trust/disputes/{id}/resolution", disputeId)
                .header(headerName, issuer.issueForOrg(organizationId, "grassland-trust"))
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.info("trust resolution HTTP {} org={} dispute={}", code, organizationId, disputeId);
                    if (code == 200) {
                        return resp.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                                .map(TrustResolutionClient::map);
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.<TrustResolution>error(
                                    new TrustResolutionException("trust resolution failed: HTTP " + code + ": " + b)));
                });
    }

    static TrustResolution map(Map<String, Object> body) {
        if (!(body.get("data") instanceof Map<?, ?> data)) {
            throw new TrustResolutionException("trust resolution body missing data");
        }
        return new TrustResolution(
                text(data.get("disputeId")),
                text(data.get("engagementRef")),
                text(data.get("organizationId")),
                text(data.get("status")),
                text(data.get("finalDecision")),
                longValue(data.get("version")));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static long longValue(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /** trust 权威终局快照。 */
    public record TrustResolution(
            String disputeId, String engagementRef, String organizationId,
            String status, String finalDecision, long version) {}

    public static final class TrustResolutionException extends RuntimeException {
        public TrustResolutionException(String message) {
            super(message);
        }
    }
}
