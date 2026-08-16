package com.grassland.finance.freebie;

import com.grassland.finance.security.FinanceCallerResolver;
import com.grassland.finance.security.FinanceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 霸王餐押金 HTTP 入口（ADR-D12）。全部 {@code /internal/**}：容器直连、marketplace 服务断言、
 * 拒绝 {@code X-Forwarded-*}（{@code InternalRequestBoundaryFilter}），公网零暴露——押金动作只由
 * marketplace Saga / 结算 / 取消 / 对账驱动，无终端用户直调场景。
 */
@RestController
public class FreebieEscrowController {

    private final FinanceCallerResolver callers;
    private final FreebieEscrowLifecycleService lifecycle;

    public FreebieEscrowController(FinanceCallerResolver callers, FreebieEscrowLifecycleService lifecycle) {
        this.callers = callers;
        this.lifecycle = lifecycle;
    }

    /** 预付押金进托管（accept Saga 分支调用）：扣推荐官钱包、建托管行；幂等按 engagementRef。 */
    @PostMapping("/internal/freebie/reserve")
    public Mono<ResponseEntity<Map<String, Object>>> reserve(@RequestBody ReserveRequest body,
                                                             ServerHttpRequest request) {
        return callers.requireService(request, FinanceCallerResolver.MARKETPLACE_SERVICE)
                .then(lifecycle.reserve(body.engagementRef(), body.recommenderAccountId(),
                        body.taskOwnerAccountId(), body.organizationId(), body.amountCents()))
                .map(res -> ResponseEntity.status(res.created() ? HttpStatus.CREATED : HttpStatus.OK)
                        .body(Map.of("success", true, "data", toBody(res.escrow()))));
    }

    /** 达标全额退还推荐官（fee=0）。幂等：已终态 → 409 由调用方映射为成功。 */
    @PostMapping("/internal/freebie/{engagementRef}/refund")
    public Mono<ResponseEntity<Map<String, Object>>> refund(@PathVariable String engagementRef,
                                                            ServerHttpRequest request) {
        return requireMarketplaceForEscrow(request, engagementRef)
                .then(lifecycle.refund(engagementRef))
                .map(escrow -> ResponseEntity.ok(Map.of("success", true, "data", toBody(escrow))));
    }

    /** 未达标补偿商家 org 账户。幂等语义同 refund。 */
    @PostMapping("/internal/freebie/{engagementRef}/compensate")
    public Mono<ResponseEntity<Map<String, Object>>> compensate(@PathVariable String engagementRef,
                                                                ServerHttpRequest request) {
        return requireMarketplaceForEscrow(request, engagementRef)
                .then(lifecycle.compensate(engagementRef))
                .map(escrow -> ResponseEntity.ok(Map.of("success", true, "data", toBody(escrow))));
    }

    private Mono<Void> requireMarketplaceForEscrow(ServerHttpRequest request, String engagementRef) {
        return lifecycle.find(engagementRef)
                .switchIfEmpty(Mono.error(new FinanceException(404, "押金托管不存在")))
                .flatMap(escrow -> callers.requireServiceForOrg(
                        request, escrow.organizationId(), FinanceCallerResolver.MARKETPLACE_SERVICE))
                .then();
    }

    static Map<String, Object> toBody(FreebieEscrow escrow) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", escrow.id());
        m.put("engagementRef", escrow.engagementRef());
        m.put("recommenderAccountId", escrow.recommenderAccountId());
        m.put("taskOwnerId", escrow.taskOwnerAccountId());
        m.put("organizationId", escrow.organizationId());
        m.put("amountCents", escrow.amountCents());
        m.put("status", escrow.status());
        m.put("createdAt", escrow.createdAt() == null ? null : escrow.createdAt().toString());
        return m;
    }

    /** 预付请求体（金额一律 cents；taskOwnerAccountId 供 Compensated 双方通知）。 */
    record ReserveRequest(
            String engagementRef,
            String recommenderAccountId,
            String taskOwnerAccountId,
            String organizationId,
            long amountCents) {}
}
