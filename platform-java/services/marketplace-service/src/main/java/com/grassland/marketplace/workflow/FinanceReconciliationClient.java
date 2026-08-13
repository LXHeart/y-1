package com.grassland.marketplace.workflow;

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
 * finance 对账命令客户端（Slice 7B）。marketplace 对账 activity 调
 * {@code POST /api/finance/reservations/{ref}/reconcile}（{organizationId, finalDecision}），现签 marketplace 服务断言。
 *
 * <p>finance 对**所有业务结局**（verified/repaired/blocked/conflict/missing）均回 200 + {outcome, reason}，
 * 故 200→解析 outcome；非 200（鉴权/坏请求/5xx）→ 抛异常交 Temporal 重试。outcome 不再塌缩成真假——
 * blocked/conflict/missing 由 activity 转为持久 blocked，绝不声称已结算。
 */
@Component
public class FinanceReconciliationClient {

    private static final Logger log = LoggerFactory.getLogger(FinanceReconciliationClient.class);

    private final WebClient webClient;
    private final ServiceAssertionIssuer issuer;
    private final String headerName;

    public FinanceReconciliationClient(ServiceAssertionIssuer issuer,
                                       @Value("${finance.service.base-url:http://finance-service:8084}") String baseUrl,
                                       @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.issuer = issuer;
        this.headerName = headerName;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    public Mono<Result> reconcile(String organizationId, String engagementRef, String finalDecision) {
        return webClient.post()
                .uri("/api/finance/reservations/{ref}/reconcile", engagementRef)
                .header(headerName, issuer.issueForOrg(organizationId, "grassland-finance"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", organizationId, "finalDecision", finalDecision))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.info("finance reconcile HTTP {} org={} ref={} decision={}",
                            code, organizationId, engagementRef, finalDecision);
                    if (code == 200) {
                        return resp.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                                .map(FinanceReconciliationClient::map);
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.<Result>error(
                                    new FinanceReconciliationException(
                                            "finance reconcile failed: HTTP " + code + ": " + b)));
                });
    }

    static Result map(Map<String, Object> body) {
        if (!(body.get("data") instanceof Map<?, ?> data)) {
            throw new FinanceReconciliationException("finance reconcile body missing data");
        }
        Object outcome = data.get("outcome");
        Object reason = data.get("reason");
        return new Result(outcome == null ? null : outcome.toString(),
                reason == null ? null : reason.toString());
    }

    /** finance 对账结局。outcome: verified/repaired（成功）/ blocked/conflict/missing（须阻断）。 */
    public record Result(String outcome, String reason) {

        public boolean isSuccess() {
            return "verified".equals(outcome) || "repaired".equals(outcome);
        }
    }

    public static final class FinanceReconciliationException extends RuntimeException {
        public FinanceReconciliationException(String message) {
            super(message);
        }
    }
}
