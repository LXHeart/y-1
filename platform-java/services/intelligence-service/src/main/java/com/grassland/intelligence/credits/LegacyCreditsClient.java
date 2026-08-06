package com.grassland.intelligence.credits;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 经 legacy 内部端点扣费（草场 intelligence Slice 1 过渡实现）。
 *
 * <p>legacy 不识别 {@code X-Grassland-Identity} 断言，故走共享密钥 {@code X-Internal-Key}（两端同
 * {@code INTERNAL_API_KEY}）。直连 legacy 容器（{@code credits.legacy.base-url}），不经 edge-bff
 * （避免 intelligence↔edge-bff 循环；与 marketplace→finance 直连同模式）。
 *
 * <p><b>回滚实现</b>：积分存储已迁入 finance（{@link FinanceCreditsClient} 默认）。仅当
 * {@code credits.client.impl=legacy} 时装配，用于 finance 积分域异常时的应急回退（同时需
 * {@code EDGE_ROUTE_CREDITS_FINANCE=false} 让读端也回 legacy）。
 * 退款与 finance 路径使用相同的 {@code refund:<consumeId>} 幂等键。
 */
@Component
@ConditionalOnProperty(name = "credits.client.impl", havingValue = "legacy")
public class LegacyCreditsClient implements CreditsClient {

    private final WebClient webClient;
    private final String consumePath;
    private final String internalKey;

    private final String refundPath;

    public LegacyCreditsClient(@Value("${credits.legacy.base-url:http://backend:3000}") String baseUrl,
                               @Value("${credits.legacy.consume-path:/internal/credits/consume}") String consumePath,
                               @Value("${credits.legacy.refund-path:/internal/credits/refund}") String refundPath,
                               @Value("${credits.legacy.internal-key:}") String internalKey) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.consumePath = consumePath;
        this.refundPath = refundPath;
        this.internalKey = internalKey;
    }

    @Override
    public Mono<CreditCharge> consume(String accountId, CreditFeature feature) {
        return consume(accountId, feature, UUID.randomUUID().toString());
    }

    @Override
    public Mono<CreditCharge> consume(String accountId, CreditFeature feature, String operationId) {
        CreditCharge charge = new CreditCharge(accountId, feature, operationId);

        return webClient.post().uri(consumePath)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Key", internalKey)
                .bodyValue(Map.of(
                        "accountId", accountId,
                        "feature", feature.key(),
                        "operationId", charge.operationId()))
                .retrieve()
                .onStatus(s -> s.value() == 402, r -> Mono.error(new InsufficientCreditsException()))
                .onStatus(s -> s.is4xxClientError(),
                        r -> Mono.error(new IntelligenceException(400, "积分扣减请求无效")))
                .onStatus(s -> s.is5xxServerError(),
                        r -> Mono.error(new IntelligenceException(502, "积分服务暂不可用")))
                .bodyToMono(Void.class)
                .thenReturn(charge);
    }

    @Override
    public Mono<Void> refund(CreditCharge charge, String note) {
        return webClient.post().uri(refundPath)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Key", internalKey)
                .bodyValue(Map.of(
                        "accountId", charge.accountId(),
                        "feature", charge.feature().key(),
                        "operationId", "refund:" + charge.operationId(),
                        "note", note))
                .retrieve()
                .onStatus(s -> s.is4xxClientError(),
                        r -> Mono.error(new IntelligenceException(400, "积分退款请求无效")))
                .onStatus(s -> s.is5xxServerError(),
                        r -> Mono.error(new IntelligenceException(502, "积分服务暂不可用")))
                .bodyToMono(Void.class);
    }

    @Override
    public Mono<Void> compensate(CreditCharge charge, String note) {
        // Replaying the original idempotent consume serializes behind any in-flight writer.
        // Once it returns, the charge is known to exist and refund:<operationId> is safe.
        return consume(charge.accountId(), charge.feature(), charge.operationId())
                .then(refund(charge, note))
                .onErrorResume(InsufficientCreditsException.class, error -> Mono.empty());
    }
}
