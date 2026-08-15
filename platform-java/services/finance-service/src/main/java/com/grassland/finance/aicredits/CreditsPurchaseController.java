package com.grassland.finance.aicredits;

import com.grassland.finance.aicredits.CreditsPackageRepository.PackageView;
import com.grassland.finance.aicredits.CreditsPurchaseOrderRepository.PurchaseOrder;
import com.grassland.finance.security.FinanceCallerResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 积分包用户端点（AI 套餐 v1 Slice B）。骑既有 {@code /api/credits} 前缀路由（edge），
 * 无新路由。packages 只列 active；订单只读本人（accountId 取自断言，不信任请求体）。
 */
@RestController
public class CreditsPurchaseController {

    private final CreditsPackageRepository packages;
    private final CreditsPurchaseOrderRepository orders;
    private final CreditsPurchaseService purchases;
    private final FinanceCallerResolver callers;

    public CreditsPurchaseController(
            CreditsPackageRepository packages, CreditsPurchaseOrderRepository orders,
            CreditsPurchaseService purchases, FinanceCallerResolver callers) {
        this.packages = packages;
        this.orders = orders;
        this.purchases = purchases;
        this.callers = callers;
    }

    @GetMapping("/api/credits/packages")
    public Mono<ResponseEntity<Map<String, Object>>> listPackages(ServerHttpRequest request) {
        return callers.resolve(request)
                .thenMany(packages.listActive().map(CreditsPurchaseController::packageBody))
                .collectList()
                .map(items -> ResponseEntity.ok(Map.of("success", true, "data", items)));
    }

    @PostMapping("/api/credits/purchase-orders")
    public Mono<ResponseEntity<Map<String, Object>>> purchase(
            @RequestBody PurchaseRequest body, ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> purchases.purchase(caller.accountId(), body.packageId(), body.operationId()))
                .map(outcome -> ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                        "orderId", outcome.orderId(),
                        "status", outcome.status(),
                        "creditsAmount", outcome.creditsAmount(),
                        "balance", outcome.balance() == null ? "" : outcome.balance()))));
    }

    @GetMapping("/api/credits/purchase-orders")
    public Mono<ResponseEntity<Map<String, Object>>> myOrders(ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMapMany(caller -> orders.findByAccount(caller.accountId(), 50)
                        .map(CreditsPurchaseController::orderBody))
                .collectList()
                .map(items -> ResponseEntity.ok(Map.of("success", true, "data", items)));
    }

    // ---------------- helpers ----------------

    private static Map<String, Object> packageBody(PackageView pkg) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", pkg.id());
        data.put("name", pkg.name());
        data.put("description", pkg.description());
        data.put("priceCents", pkg.priceCents());
        data.put("creditsAmount", pkg.creditsAmount());
        return data;
    }

    private static Map<String, Object> orderBody(PurchaseOrder order) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", order.id());
        data.put("packageId", order.packageId());
        data.put("priceCents", order.priceCents());
        data.put("creditsAmount", order.creditsAmount());
        data.put("status", order.status());
        return data;
    }

    /** 购买请求：packageId 必填；operationId 选填（缺省服务端生成，客户端重试可显式传入保幂等）。 */
    public record PurchaseRequest(String packageId, String operationId) {
        public PurchaseRequest {
            packageId = packageId == null ? "" : packageId.trim();
            if (packageId.isEmpty()) {
                throw new IllegalArgumentException("积分包标识不能为空");
            }
            operationId = operationId == null || operationId.isBlank() ? null : operationId.trim();
        }
    }
}
