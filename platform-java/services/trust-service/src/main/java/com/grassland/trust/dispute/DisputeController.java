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
import reactor.core.publisher.Mono;

/**
 * 争议 HTTP 入口（草场 Epic 6 Slice 6A / HLD 10.5）。
 *
 * <ul>
 *   <li>POST /api/trust/disputes — 开争议（requireMerchantOrRecommender；org 取 caller；幂等：每 engagement 至多一个 open；
 *       outbox {@code DisputeOpened}；201 首次 / 200 既有）。</li>
 *   <li>POST /api/trust/disputes/{id}/decide — 手动裁决（requireMerchant + org 自查；open→decided；outbox {@code DisputeDecided}）。
 *       授权 provisional——真裁决来自后续审判 slice。</li>
 *   <li>GET /api/trust/engagements/{engagementRef}/open-dispute — 开放争议查询（marketplace DisputeChecker 调；
 *       接受 marketplace 服务断言或商家；200 body 或 404）。</li>
 * </ul>
 *
 * <p>身份靠 {@link TrustCallerResolver}（BFF/服务断言）；org 归属自查（HLD 7.4）。错误统一由 {@code TrustErrorHandler} 处理。
 */
@RestController
public class DisputeController {

    private final TrustCallerResolver callers;
    private final DisputeCaseRepository disputes;
    private final OutboxRepository outbox;

    public DisputeController(TrustCallerResolver callers, DisputeCaseRepository disputes, OutboxRepository outbox) {
        this.callers = callers;
        this.disputes = disputes;
        this.outbox = outbox;
    }

    @PostMapping("/api/trust/disputes")
    public Mono<ResponseEntity<Map<String, Object>>> open(@RequestBody OpenDisputeRequest body, ServerHttpRequest request) {
        return callers.requireMerchantOrRecommender(request)
                .filter(caller -> caller.organizationId() != null)
                .switchIfEmpty(fail(403, "无组织归属，无法开争议"))
                .flatMap(caller -> disputes.findOpenByEngagementRef(body.engagementRef())
                        .<Opened>map(d -> new Opened(d, false))  // 幂等：既有 open → 200
                        .switchIfEmpty(disputes.create(body.engagementRef(), caller.organizationId(),
                                        caller.accountId(), caller.activeIdentityType(), body.reason())
                                .<Opened>map(d -> new Opened(d, true))))  // 幂等：既有 open
                .flatMap(o -> (o.created()
                        ? outbox.append(envelope("DisputeOpened", o.dispute()))
                        : Mono.<Void>empty()).thenReturn(o))
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
                            return disputes.decide(id, body.decision()).switchIfEmpty(fail(409, "该争议已裁决"));
                        })
                        .flatMap(d -> outbox.append(envelope("DisputeDecided", d)).thenReturn(d))
                        .map(d -> ResponseEntity.ok(Map.of("success", true, "data", toBody(d)))));
    }

    /** 开放争议查询（marketplace DisputeChecker 调）：200 body 或 404。服务 principal 信任；商家查须 org 自查。 */
    @GetMapping("/api/trust/engagements/{engagementRef}/open-dispute")
    public Mono<ResponseEntity<Map<String, Object>>> openDispute(@PathVariable String engagementRef, ServerHttpRequest request) {
        return callers.resolveMerchantOrService(request, TrustCallerResolver.MARKETPLACE_SERVICE)
                .flatMap(caller -> disputes.findOpenByEngagementRef(engagementRef)
                        .switchIfEmpty(fail(404, "无开放争议"))
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
                d.id(), 1, Instant.now(), null, payload);
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
        m.put("createdAt", d.createdAt() == null ? null : d.createdAt().toString());
        return m;
    }

    private record Opened(DisputeCase dispute, boolean created) {}

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new TrustException(status, message));
    }
}
