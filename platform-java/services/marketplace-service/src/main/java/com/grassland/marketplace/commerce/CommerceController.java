package com.grassland.marketplace.commerce;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.marketplace.commerce.CommerceModels.OfferDetail;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Consumer, merchant and operations HTTP surface for the commerce MVP. */
@RestController
public class CommerceController {

    private final MarketplaceCallerResolver callers;
    private final CommerceService commerce;

    public CommerceController(MarketplaceCallerResolver callers, CommerceService commerce) {
        this.callers = callers;
        this.commerce = commerce;
    }

    /** Public referral landing-page data. Authentication is only required when the user orders. */
    @GetMapping("/api/v2/packages/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> packageDetail(@PathVariable String id) {
        return commerce.publicOffer(id)
                .map(value -> ResponseEntity.ok(success(offerBody(value))));
    }

    @PostMapping(value = "/api/v2/orders", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> createOrder(
            @RequestBody CommerceService.CreateOrderCommand body, ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> commerce.createOrder(caller, body))
                .map(order -> ResponseEntity.status(201).body(success(orderBody(order))));
    }

    @GetMapping("/api/v2/orders")
    public Mono<ResponseEntity<Map<String, Object>>> consumerOrders(
            @RequestParam(defaultValue = "100") int limit, ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> commerce.listConsumerOrders(caller, limit)
                        .map(this::orderBody).collectList())
                .map(values -> ResponseEntity.ok(success(values)));
    }

    @GetMapping("/api/v2/orders/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> consumerOrder(
            @PathVariable String id, ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> commerce.findConsumerOrder(caller, id))
                .map(order -> ResponseEntity.ok(success(orderBody(order))));
    }

    @PostMapping(value = "/api/v2/orders/{id}/refund", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> refund(
            @PathVariable String id, @RequestBody(required = false) RefundRequest body,
            ServerHttpRequest request) {
        String reason = body == null ? "consumer_request" : body.reason();
        return callers.requireUser(request)
                .flatMap(caller -> commerce.requestRefund(caller, id, reason))
                .map(order -> ResponseEntity.ok(success(orderBody(order))));
    }

    @PostMapping(value = "/api/v2/orders/{id}/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> review(
            @PathVariable String id, @RequestBody CommerceService.ReviewCommand body,
            ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> commerce.review(caller, id, body))
                .map(review -> ResponseEntity.status(201).body(success(review)));
    }

    @PostMapping(value = "/api/v2/merchant/packages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> createPackage(
            @RequestBody CommerceService.OfferCommand body, ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> commerce.createOffer(caller, body))
                .map(value -> ResponseEntity.status(201).body(success(offerBody(value))));
    }

    @PutMapping(value = "/api/v2/merchant/packages/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> revisePackage(
            @PathVariable String id, @RequestBody CommerceService.OfferCommand body,
            ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> commerce.reviseOffer(caller, id, body))
                .map(value -> ResponseEntity.ok(success(offerBody(value))));
    }

    @PostMapping("/api/v2/merchant/packages/{id}/publish")
    public Mono<ResponseEntity<Map<String, Object>>> publishPackage(
            @PathVariable String id, ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> commerce.publishOffer(caller, id))
                .map(value -> ResponseEntity.ok(success(offerBody(value))));
    }

    @PostMapping("/api/v2/merchant/packages/{id}/off-sale")
    public Mono<ResponseEntity<Map<String, Object>>> offSalePackage(
            @PathVariable String id, ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> commerce.offSaleOffer(caller, id))
                .map(value -> ResponseEntity.ok(success(offerBody(value))));
    }

    @GetMapping("/api/v2/merchant/packages")
    public Mono<ResponseEntity<Map<String, Object>>> merchantPackages(
            @RequestParam String organizationId,
            @RequestParam(required = false) String storeId,
            ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> commerce.listManagedOffers(caller, organizationId, storeId)
                        .map(this::offerBody).collectList())
                .map(values -> ResponseEntity.ok(success(values)));
    }

    @GetMapping("/api/v2/merchant/orders")
    public Mono<ResponseEntity<Map<String, Object>>> merchantOrders(
            @RequestParam String organizationId,
            @RequestParam(required = false) String storeId,
            @RequestParam(defaultValue = "100") int limit,
            ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> commerce.listMerchantOrders(caller, organizationId, storeId, limit)
                        .map(this::orderBody).collectList())
                .map(values -> ResponseEntity.ok(success(values)));
    }

    @PostMapping(value = "/api/v2/merchant/redemptions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> redeem(
            @RequestBody RedemptionRequest body, ServerHttpRequest request) {
        return callers.requireUser(request)
                .flatMap(caller -> commerce.redeem(caller, body.code()))
                .map(order -> ResponseEntity.ok(success(orderBody(order))));
    }

    @GetMapping("/api/admin/commerce/orders")
    public Mono<ResponseEntity<Map<String, Object>>> adminOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "200") int limit,
            ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.CUSTOMER_SERVICE, BackendRole.FINANCE, BackendRole.RISK)
                .then(commerce.listAdminOrders(status, limit).map(this::orderBody).collectList())
                .map(values -> ResponseEntity.ok(success(values)));
    }

    @GetMapping("/api/admin/commerce/redemptions")
    public Mono<ResponseEntity<Map<String, Object>>> adminRedemptions(
            @RequestParam(defaultValue = "200") int limit,
            ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.CUSTOMER_SERVICE, BackendRole.FINANCE, BackendRole.RISK)
                .then(Mono.zip(
                        commerce.listAdminOrders("redeeming", limit).map(this::orderBody).collectList(),
                        commerce.listAdminOrders("redeemed", limit).map(this::orderBody).collectList()))
                .map(tuple -> {
                    List<Map<String, Object>> values = new java.util.ArrayList<>(tuple.getT1());
                    values.addAll(tuple.getT2());
                    return ResponseEntity.ok(success(values));
                });
    }

    private Map<String, Object> offerBody(OfferDetail detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", detail.offer().id());
        body.put("organizationId", detail.offer().organizationId());
        if (detail.offer().storeId() != null) body.put("storeId", detail.offer().storeId());
        if (detail.offer().taskId() != null) body.put("taskId", detail.offer().taskId());
        body.put("status", detail.offer().status());
        body.put("version", detail.version().version());
        body.put("title", detail.version().title());
        body.put("description", detail.version().description() == null ? "" : detail.version().description());
        body.put("priceCents", detail.version().priceCents());
        body.put("totalStock", detail.version().totalStock());
        body.put("remainingStock", detail.remainingStock());
        if (detail.version().fixedRedeemDeadline() != null) {
            body.put("fixedRedeemDeadline", detail.version().fixedRedeemDeadline());
        }
        if (detail.version().validDaysAfterPurchase() != null) {
            body.put("validDaysAfterPurchase", detail.version().validDaysAfterPurchase());
        }
        body.put("recommenderShareBps", detail.version().recommenderShareBps());
        body.put("platformFeeBps", detail.version().platformFeeBps());
        body.put("merchantShareBps", detail.version().merchantShareBps());
        body.put("policyVersion", detail.version().policyVersion());
        body.put("promotionPath", "/?view=commerce&package=" + detail.offer().id());
        body.put("createdAt", detail.offer().createdAt());
        body.put("updatedAt", detail.offer().updatedAt());
        return body;
    }

    private Map<String, Object> orderBody(Order order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", order.id());
        body.put("consumerAccountId", order.consumerAccountId());
        body.put("organizationId", order.organizationId());
        if (order.storeId() != null) body.put("storeId", order.storeId());
        body.put("packageId", order.packageId());
        body.put("packageVersion", order.packageVersion());
        body.put("packageTitle", order.packageTitle());
        if (order.recommenderAccountId() != null) body.put("recommenderAccountId", order.recommenderAccountId());
        body.put("priceCents", order.priceCents());
        body.put("recommenderAmountCents", order.recommenderAmountCents());
        body.put("merchantAmountCents", order.merchantAmountCents());
        body.put("platformFeeCents", order.platformFeeCents());
        body.put("status", order.status());
        body.put("redeemDeadline", order.redeemDeadline());
        String code = commerce.redeemCode(order);
        if (code != null) body.put("redeemCode", code);
        if (order.providerRef() != null) body.put("providerRef", order.providerRef());
        if (order.lastError() != null) body.put("lastError", order.lastError());
        body.put("createdAt", order.createdAt());
        if (order.paidAt() != null) body.put("paidAt", order.paidAt());
        if (order.redeemedAt() != null) body.put("redeemedAt", order.redeemedAt());
        if (order.refundedAt() != null) body.put("refundedAt", order.refundedAt());
        return body;
    }

    private static Map<String, Object> success(Object data) {
        return Map.of("success", true, "data", data);
    }

    public record RefundRequest(String reason) {}
    public record RedemptionRequest(String code) {}
}
