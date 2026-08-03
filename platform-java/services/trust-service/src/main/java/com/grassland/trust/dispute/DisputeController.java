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
    private final DeferredDisputeRequestRepository deferredRequests;
    private final MerchantRejectionFinalizer merchantRejectionFinalizer;

    public DisputeController(TrustCallerResolver callers, DisputeCaseRepository disputes, OutboxRepository outbox,
                             TransactionalOperator transactions,
                             MarketplaceEngagementAuthorizationClient authorizer,
                             DeferredDisputeRequestRepository deferredRequests,
                             MerchantRejectionFinalizer merchantRejectionFinalizer) {
        this.callers = callers;
        this.disputes = disputes;
        this.outbox = outbox;
        this.transactions = transactions;
        this.authorizer = authorizer;
        this.deferredRequests = deferredRequests;
        this.merchantRejectionFinalizer = merchantRejectionFinalizer;
    }

    @PostMapping("/api/trust/disputes")
    public Mono<ResponseEntity<Map<String, Object>>> open(@RequestBody OpenDisputeRequest body, ServerHttpRequest request) {
        // 安全收口（Slice 12）：先验签身份 + marketplace 授权（确认调用方是该 application 的当事方、并取 canonical
        // task organization），**再**查既有活跃争议——否则非参与方可读/复用他人履约的既有争议。
        // organization 不再取自断言（推荐官本就无 org；merchant 的 org 须与 task 一致，由 marketplace 裁定）。
        //
        // D-03 slice 2：marketplace 服务断言可代商家开 merchant_rejection 争议（商家在确认窗口拒绝核实通过履约）。
        // marketplace 已 loadOwnedTask 校验商家 ownership，故跳过 authorizer，直接用 payload 的 openedByAccountId/org。
        return callers.resolve(request)
                .flatMap(caller -> {
                    if (caller.isServicePrincipal(TrustCallerResolver.MARKETPLACE_SERVICE)) {
                        return openForMarketplaceService(body, caller.organizationId())
                                .map(o -> response(o.created() ? HttpStatus.CREATED : HttpStatus.OK,
                                        toBody(o.dispute())));
                    }
                    if (!caller.isMerchant() && !caller.isRecommender()) {
                        return fail(403, "需要商家或推荐官身份");
                    }
                    // 终端用户路径：authorizer 校验当事方 + 取 canonical org。
                    return authorizer.authorize(body.engagementRef(), caller.accountId(), caller.activeIdentityType())
                            .switchIfEmpty(fail(403, "无权对该履约开争议"))
                            .flatMap(auth -> openOrDefer(auth.engagementRef(), auth.organizationId(),
                                    caller.accountId(), caller.activeIdentityType(), body.reason()));
                });
    }

    /** marketplace 服务断言代商家开 merchant_rejection 争议（D-03 §2）。仅允许该 kind；openedBy/org 取请求体。 */
    private Mono<Opened> openForMarketplaceService(OpenDisputeRequest body, String assertionOrganizationId) {
        if (!"merchant_rejection".equals(body.kind())) {
            return fail(403, "服务断言仅可开 merchant_rejection 争议");
        }
        if (body.openedByAccountId() == null || body.organizationId() == null) {
            return fail(400, "缺少 openedByAccountId / organizationId");
        }
        if (!body.organizationId().equals(assertionOrganizationId)) {
            return fail(403, "服务断言组织与请求不一致");
        }
        return disputes.findActiveByEngagementRef(body.engagementRef())
                .flatMap(existing -> "merchant_rejection".equals(existing.kind())
                        ? Mono.just(new Opened(existing, false))
                        : Mono.<Opened>error(new TrustException(409, "该履约已有普通活跃争议")))
                .switchIfEmpty(transactions.transactional(
                        disputes.create(body.engagementRef(), body.organizationId(),
                                        body.openedByAccountId(), "merchant", body.reason(), "merchant_rejection")
                                .map(d -> new Opened(d, true))
                                .flatMap(opened -> outbox.append(envelope("DisputeOpened", opened.dispute()))
                                        .thenReturn(opened))))
                // create 撞唯一键 → 空（并发对手已开案）。必须回读，否则返回空 200 体，
                // marketplace 侧 TrustDisputeClient 解析不到 data.id 会抛错 → 商家收到 500。
                .switchIfEmpty(Mono.defer(() -> disputes.findActiveByEngagementRef(body.engagementRef())
                        .flatMap(existing -> "merchant_rejection".equals(existing.kind())
                                ? Mono.just(new Opened(existing, false))
                                : Mono.<Opened>error(new TrustException(409, "该履约已有普通活跃争议")))
                        .switchIfEmpty(Mono.error(new TrustException(409, "开争议失败，请重试")))));
    }

    /** 用户普通争议：无活跃案则即时创建；推荐官遇 merchant_rejection 时持久化 deferred request。 */
    private Mono<ResponseEntity<Map<String, Object>>> openOrDefer(
            String engagementRef, String organizationId, String openedBy, String role, String reason) {
        return disputes.findActiveByEngagementRef(engagementRef)
                .flatMap(active -> {
                    if ("merchant_rejection".equals(active.kind())) {
                        if (!"recommender".equals(role)) {
                            return fail(409, "该履约已有商家履约异议，须等待客服终审");
                        }
                        return deferredRequests.findBySourceAndRecommender(active.id(), openedBy)
                                .map(existing -> response(HttpStatus.OK, deferredBody(existing)))
                                .switchIfEmpty(transactions.transactional(
                                                deferredRequests.createOrFind(active, openedBy, reason))
                                        .then(Mono.defer(() -> deferredRequests
                                                .findBySourceAndRecommender(active.id(), openedBy)))
                                        .map(created -> response(HttpStatus.ACCEPTED, deferredBody(created))));
                    }
                    return Mono.just(response(HttpStatus.OK, toBody(active)));
                })
                .switchIfEmpty(transactions.transactional(
                                disputes.create(engagementRef, organizationId, openedBy, role, reason, "standard")
                                        .flatMap(created -> outbox.append(envelope("DisputeOpened", created))
                                                .thenReturn(created)))
                        .then(Mono.defer(() -> disputes.findActiveByEngagementRef(engagementRef)))
                        .flatMap(created -> {
                            if ("merchant_rejection".equals(created.kind())) {
                                // create 并发输给 merchant rejection：按同一 deferred 语义恢复。
                                if (!"recommender".equals(role)) {
                                    return fail(409, "该履约已有商家履约异议，须等待客服终审");
                                }
                                return transactions.transactional(
                                                deferredRequests.createOrFind(created, openedBy, reason))
                                        .then(Mono.defer(() -> deferredRequests
                                                .findBySourceAndRecommender(created.id(), openedBy)))
                                        .map(request -> response(HttpStatus.ACCEPTED, deferredBody(request)));
                            }
                            boolean ownCreation = openedBy.equals(created.openedByAccountId());
                            return Mono.just(response(ownCreation ? HttpStatus.CREATED : HttpStatus.OK, toBody(created)));
                        }));
    }

    private Map<String, Object> deferredBody(DeferredDisputeRequest request) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", request.status());
        m.put("requestId", request.id());
        m.put("engagementRef", request.engagementRef());
        m.put("reason", request.reason() == null ? "" : request.reason());
        m.put("disputeId", request.promotedDisputeId() == null ? "" : request.promotedDisputeId());
        m.put("workflowId", request.adjudicationWorkflowId() == null ? "" : request.adjudicationWorkflowId());
        return m;
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, Map<String, Object> data) {
        return ResponseEntity.status(status).body(Map.of("success", true, "data", data));
    }

    @GetMapping("/api/trust/dispute-requests/{requestId}")
    public Mono<ResponseEntity<Map<String, Object>>> getRequest(
            @PathVariable String requestId, ServerHttpRequest request) {
        return callers.resolvePartyOrService(request, TrustCallerResolver.MARKETPLACE_SERVICE)
                .flatMap(caller -> deferredRequests.findById(requestId)
                        .switchIfEmpty(fail(404, "争议请求不存在"))
                        .filter(r -> r.recommenderAccountId().equals(caller.accountId())
                                || caller.isCustomerService()
                                || caller.isServicePrincipal(TrustCallerResolver.MARKETPLACE_SERVICE))
                        .switchIfEmpty(fail(403, "无权查询该争议请求"))
                        .map(r -> response(HttpStatus.OK, deferredBody(r))));
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
                            if ("merchant_rejection".equals(d.kind())) {
                                return fail(409, "商家履约异议须由客服终审");
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

    /**
     * D-03 §2 客服 SLA 超时自动终局（内部，仅 marketplace 服务）：merchant_rejection 争议客服未在 SLA 内裁定 →
     * 默认按系统核实结果（for_recommender）结算，避免裁定侧悬置。无 MFA（系统动作，非客服覆盖）。
     * 非 merchant_rejection / 已终局 → 幂等 200（不动）。
     */
    @PostMapping("/api/trust/internal/disputes/{id}/auto-finalize")
    public Mono<ResponseEntity<Map<String, Object>>> autoFinalize(@PathVariable String id, ServerHttpRequest request) {
        return callers.resolve(request)
                .filter(c -> c.isServicePrincipal(TrustCallerResolver.MARKETPLACE_SERVICE))
                .switchIfEmpty(fail(403, "仅 marketplace 服务可调用自动终局"))
                .flatMap(caller -> disputes.findById(id)
                        .switchIfEmpty(fail(404, "争议不存在"))
                        .filter(d -> d.organizationId().equals(caller.organizationId()))
                        .switchIfEmpty(fail(403, "服务断言组织与案件不一致"))
                        .flatMap(d -> {
                            if (!"merchant_rejection".equals(d.kind())) {
                                return fail(409, "仅 merchant_rejection 争议可自动终局");
                            }
                            if ("final".equals(d.status())) {
                                return Mono.just(new MerchantRejectionFinalizer.Finalization(d, null, null));
                            }
                            return merchantRejectionFinalizer.finalizeCase(d, "for_recommender", null);
                        }))
                .map(result -> ResponseEntity.ok(Map.of(
                        "success", true, "data", toBody(result.finalized()))));
    }

    private EventEnvelope envelope(String eventType, DisputeCase d) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("disputeId", d.id());
        payload.put("engagementRef", d.engagementRef());
        payload.put("organizationId", d.organizationId());
        payload.put("openedByAccountId", d.openedByAccountId());
        payload.put("openedByRole", d.openedByRole());
        payload.put("status", d.status());
        payload.put("kind", d.kind());
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
        m.put("kind", d.kind());
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
