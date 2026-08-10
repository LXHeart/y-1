package com.grassland.finance.commerce;

import com.grassland.finance.security.FinanceCallerResolver;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Marketplace-only finance API for the commerce lifecycle. */
@RestController
public class ConsumerPaymentController {

    private final FinanceCallerResolver callers;
    private final ConsumerPaymentService service;

    public ConsumerPaymentController(FinanceCallerResolver callers, ConsumerPaymentService service) {
        this.callers = callers;
        this.service = service;
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
