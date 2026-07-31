package com.grassland.trust.dispute;

import com.grassland.trust.event.EventEnvelope;
import com.grassland.trust.event.OutboxRepository;
import com.grassland.trust.security.TrustCallerResolver;
import com.grassland.trust.security.TrustException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 争议 HTTP 入口（草场 Epic 6 Slice 6A / HLD 10.5；6C 扩活跃查询 + 终局状态）。
 *
 * <ul>
 *   <li>POST /api/trust/disputes — 开争议（requireMerchantOrRecommender；org 取 caller；幂等：每 engagement 至多一个活跃争议；
 *       outbox {@code DisputeOpened}；201 首次 / 200 既有）。</li>
 *   <li>POST /api/trust/disputes/{id}/decide — 手动裁决（requireMerchant + org 自查；open→final 终局；outbox {@code DisputeDecided}）。
 *       授权 provisional——真裁决来自后续审判 slice。</li>
 *   <li>GET /api/trust/engagements/{engagementRef}/open-dispute — 活跃（未终局）争议查询（marketplace DisputeChecker 调；
 *       接受 marketplace 服务断言或商家；200 body 或 404）。终局争议不在此查得 → 结算不再 held。</li>
 * </ul>
 *
 * <p>身份靠 {@link TrustCallerResolver}（BFF/服务断言）；org 归属自查（HLD 7.4）。错误统一由 {@code TrustErrorHandler} 处理。
 */
@RestController
public class DisputeController {

    private final TrustCallerResolver callers;
    private final DisputeCaseRepository disputes;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final MarketplaceEngagementAuthorizationClient authorizer;

    public DisputeController(TrustCallerResolver callers, DisputeCaseRepository disputes, OutboxRepository outbox,
                             TransactionalOperator transactions,
                             MarketplaceEngagementAuthorizationClient authorizer) {
        this.callers = callers;
        this.disputes = disputes;
        this.outbox = outbox;
        this.transactions = transactions;
        this.authorizer = authorizer;
    }

    @PostMapping("/api/trust/disputes")
    public Mono<ResponseEntity<Map<String, Object>>> open(@RequestBody OpenDisputeRequest body, ServerHttpRequest request) {
        // 安全收口（Slice 12）：先验签身份 + marketplace 授权（确认调用方是该 application 的当事方、并取 canonical
        // task organization），**再**查既有活跃争议——否则非参与方可读/复用他人履约的既有争议。
        // organization 不再取自断言（推荐官本就无 org；merchant 的 org 须与 task 一致，由 marketplace 裁定）。
        return callers.requireMerchantOrRecommender(request)
                .flatMap(caller -> authorizer.authorize(body.engagementRef(), caller.accountId(), caller.activeIdentityType())
                        .switchIfEmpty(fail(403, "无权对该履约开争议"))
                        .flatMap(auth -> disputes.findActiveByEngagementRef(auth.engagementRef())
                                .<Opened>map(d -> new Opened(d, false))  // 幂等：既有活跃争议 → 200
                                .switchIfEmpty(transactions.transactional(
                                        disputes.create(auth.engagementRef(), auth.organizationId(),
                                                        caller.accountId(), caller.activeIdentityType(), body.reason())
                                                .<Opened>map(d -> new Opened(d, true))
                                                .flatMap(opened -> outbox.append(envelope("DisputeOpened", opened.dispute()))
                                                        .thenReturn(opened))))))
                .map(o -> ResponseEntity.status(o.created() ? HttpStatus.CREATED : HttpStatus.OK)
                        .body(Map.of("success", true, "data", toBody(o.dispute()))));
    }

    @PostMapping("/api/trust/disputes/{id}/decide")
    public Mono<ResponseEntity<Map<String, Object>>> decide(@PathVariable String id, @RequestBody DecideDisputeRequest body,
                                                            ServerHttpRequest request) {
        return callers.requireMerchant(request)
                .flatMap(caller -> disputes.findById(id)
                        .switchIfEmpty(fail(404, "争议不存在"))
                        .flatMap(d -> {
                            if (!d.organizationId().equals(caller.organizationId())) {
                                return fail(403, "无权操作该争议");
                            }
                            return transactions.transactional(
                                    disputes.decide(id, body.decision()).switchIfEmpty(fail(409, "该争议已裁决"))
                                            .flatMap(decided -> outbox.append(envelope("DisputeDecided", decided)).thenReturn(decided)));
                        })
                        .map(d -> ResponseEntity.ok(Map.of("success", true, "data", toBody(d)))));
    }

    /** 活跃（未终局）争议查询（marketplace DisputeChecker 调）：200 body 或 404。服务 principal 信任；商家查须 org 自查。 */
    @GetMapping("/api/trust/engagements/{engagementRef}/open-dispute")
    public Mono<ResponseEntity<Map<String, Object>>> openDispute(@PathVariable String engagementRef, ServerHttpRequest request) {
        return callers.resolveMerchantOrService(request, TrustCallerResolver.MARKETPLACE_SERVICE)
                .flatMap(caller -> disputes.findActiveByEngagementRef(engagementRef)
                        .switchIfEmpty(fail(404, "无活跃争议"))
                        .filter(d -> caller.isServicePrincipal(TrustCallerResolver.MARKETPLACE_SERVICE)
                                || d.organizationId().equals(caller.organizationId()))
                        .switchIfEmpty(fail(403, "无权查询该组织争议"))
                        .map(d -> ResponseEntity.ok(Map.of("success", true, "data", toBody(d)))));
    }

    private EventEnvelope envelope(String eventType, DisputeCase d) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("disputeId", d.id());
        payload.put("engagementRef", d.engagementRef());
        payload.put("organizationId", d.organizationId());
        payload.put("openedByAccountId", d.openedByAccountId());
        payload.put("openedByRole", d.openedByRole());
        payload.put("status", d.status());
        if (d.decision() != null) {
            payload.put("decision", d.decision());
        }
        return new EventEnvelope(UUID.randomUUID().toString(), eventType, "DisputeCase",
                d.id(), d.version(), Instant.now(), null, payload);
    }

    private Map<String, Object> toBody(DisputeCase d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("engagementRef", d.engagementRef());
        m.put("organizationId", d.organizationId());
        m.put("openedByAccountId", d.openedByAccountId());
        m.put("openedByRole", d.openedByRole());
        m.put("status", d.status());
        m.put("reason", d.reason());
        m.put("decision", d.decision());
        m.put("decidedAt", d.decidedAt() == null ? null : d.decidedAt().toString());
        m.put("round", d.round());
        m.put("version", d.version());
        m.put("appealState", d.appealState());
        m.put("finalDecision", d.finalDecision());
        m.put("createdAt", d.createdAt() == null ? null : d.createdAt().toString());
        return m;
    }

    private record Opened(DisputeCase dispute, boolean created) {}

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new TrustException(status, message));
    }
}
