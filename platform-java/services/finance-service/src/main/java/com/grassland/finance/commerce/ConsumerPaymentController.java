package com.grassland.finance.commerce;

import com.grassland.finance.security.FinanceCallerResolver;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Marketplace-only finance API for the commerce lifecycle. */
@RestController
public class ConsumerPaymentController {

    private final FinanceCallerResolver callers;
    private final ConsumerPaymentService service;
    private final ConsumerPaymentFactsService factsService;

    public ConsumerPaymentController(FinanceCallerResolver callers, ConsumerPaymentService service,
            ConsumerPaymentFactsService factsService) {
        this.callers = callers;
        this.service = service;
        this.factsService = factsService;
    }

    @PostMapping(value = "/internal/commerce/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> pay(
            @RequestBody ConsumerPaymentService.PaymentCommand body, ServerHttpRequest request) {
        return callers.requireServiceForOrg(
                        request, body.organizationId(), FinanceCallerResolver.MARKETPLACE_SERVICE)
                .then(service.pay(body))
                .map(value -> ResponseEntity.status(201).body(Map.of("success", true, "data", value)));
    }

    @PostMapping(value = "/internal/commerce/payments/{orderRef}/refund",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> refund(
            @PathVariable String orderRef,
            @RequestBody ConsumerPaymentService.RefundCommand body,
            ServerHttpRequest request) {
        return callers.requireServiceForOrg(
                        request, body.organizationId(), FinanceCallerResolver.MARKETPLACE_SERVICE)
                .then(service.refund(orderRef, body))
                .map(value -> ResponseEntity.ok(Map.of("success", true, "data", value)));
    }

    /**
     * 任务书 #103 C103-06（§6.3 facts）：消费支付权威事实只读端点——payment/refunds/split 与全部
     * allocation；仅 marketplace principal；404 = 无本组织事实；不触发任何资金变动。
     */
    @GetMapping("/internal/commerce/payments/{orderRef}/facts")
    public Mono<ResponseEntity<Map<String, Object>>> facts(@PathVariable String orderRef,
            @RequestParam String organizationId, ServerHttpRequest request) {
        return callers.requireServiceForOrg(request, organizationId, FinanceCallerResolver.MARKETPLACE_SERVICE)
                .then(factsService.find(orderRef, organizationId))
                .map(data -> ResponseEntity.ok(Map.of("success", true, "data", data)))
                .defaultIfEmpty(ResponseEntity.status(404).body(Map.of("success", false,
                        "error", "支付事实不存在")));
    }

    @PostMapping(value = "/internal/commerce/payments/{orderRef}/split",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> split(
            @PathVariable String orderRef,
            @RequestBody ConsumerPaymentService.SplitCommand body,
            ServerHttpRequest request) {
        return callers.requireServiceForOrg(
                        request, body.organizationId(), FinanceCallerResolver.MARKETPLACE_SERVICE)
                .then(service.split(orderRef, body))
                .map(value -> ResponseEntity.ok(Map.of("success", true, "data", value)));
    }
}
