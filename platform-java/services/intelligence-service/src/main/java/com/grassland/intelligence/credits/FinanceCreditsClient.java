package com.grassland.intelligence.credits;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 经 finance-service 扣费（GL-P3-AI-001 下属切片，Java 原生积分的默认实现）。
 *
 * <p>直连 finance 容器（{@code credits.finance.base-url}），不经 edge-bff（避免 intelligence↔edge-bff 循环；
 * 与 marketplace→finance 直连同模式）。鉴权用共享密钥 {@code X-Internal-Key}（两端同 {@code INTERNAL_API_KEY}）——
 * finance 的 {@code CreditsInternalAuthFilter} fail-closed 校验。
 *
 * <p><b>refund 幂等键派生</b>：consume 用 {@code operationId=X}，对应失败退款必须用 {@code refund:X}
 * （finance 按 operation_id 原样存储，partial unique index 据此保证「一次扣减至多一次退款」）。
 * 旧 {@link LegacyCreditsClient} 误传原始 consume id，与 consume 行 operation_id 撞车被当 dedup 吞掉 → 退款从未生效；
 * 此处在客户端派生 {@code refund:<consumeId>} 修正。legacy Express {@code createCharge} 侧早已如此派生。
 */
@Component
@ConditionalOnProperty(name = "credits.client.impl", havingValue = "finance", matchIfMissing = true)
public class FinanceCreditsClient implements CreditsClient {

    private final WebClient webClient;
    private final String consumePath;
    private final String refundPath;
    private final String compensationPath;
    private final String internalKey;

    @Autowired
    public FinanceCreditsClient(@Value("${credits.finance.base-url:http://finance-service:8084}") String baseUrl,
                                @Value("${credits.finance.consume-path:/internal/credits/consume}") String consumePath,
                                @Value("${credits.finance.refund-path:/internal/credits/refund}") String refundPath,
                                @Value("${credits.finance.compensation-path:/internal/credits/consume-compensations}")
                                String compensationPath,
                                @Value("${credits.finance.internal-key:}") String internalKey) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.consumePath = consumePath;
        this.refundPath = refundPath;
        this.compensationPath = compensationPath;
        this.internalKey = internalKey;
    }

    FinanceCreditsClient(String baseUrl, String consumePath, String refundPath, String internalKey) {
        this(baseUrl, consumePath, refundPath, "/internal/credits/consume-compensations", internalKey);
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
                        "operationId", "refund:" + charge.operationId(),   // 派生退款键（修正既有 bug）
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
        return webClient.post().uri(compensationPath)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Internal-Key", internalKey)
                .bodyValue(Map.of(
                        "accountId", charge.accountId(),
                        "feature", charge.feature().key(),
                        "consumeOperationId", charge.operationId(),
                        "note", note))
                .retrieve()
                .onStatus(s -> s.is4xxClientError(),
                        r -> Mono.error(new IntelligenceException(
                                r.statusCode().value(), "积分补偿请求无效")))
                .onStatus(s -> s.is5xxServerError(),
                        r -> Mono.error(new IntelligenceException(502, "积分服务暂不可用")))
                .bodyToMono(Void.class);
    }
}
