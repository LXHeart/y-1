package com.grassland.finance.aicredits;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.finance.aicredits.CreditsPackageRepository.PackageView;
import com.grassland.finance.security.FinanceCallerResolver;
import com.grassland.finance.security.FinanceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

/**
 * 积分包 SKU 管理端点（AI 套餐 v1 Slice A）。门闩
 * {@code requireRole(FINANCE, PLATFORM_ADMIN)}——镜像 {@code LedgerAdminController}。
 *
 * <p>调价语义：PUT 不是改历史行，而是追加新 {@code credits_package_version} 并切换 current 指针
 * （「配置不篡改历史」）；已下架包须先重新上架才能调价。
 */
@RestController
public class CreditsPackageAdminController {

    private final CreditsPackageRepository packages;
    private final FinanceCallerResolver callers;

    public CreditsPackageAdminController(CreditsPackageRepository packages, FinanceCallerResolver callers) {
        this.packages = packages;
        this.callers = callers;
    }

    @GetMapping("/api/admin/credits-packages")
    public Mono<ResponseEntity<Map<String, Object>>> list(ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.FINANCE, BackendRole.PLATFORM_ADMIN)
                .thenMany(packages.listAll().map(CreditsPackageAdminController::body))
                .collectList()
                .map(items -> ResponseEntity.ok(Map.of("success", true, "data", items)));
    }

    @PostMapping("/api/admin/credits-packages")
    public Mono<ResponseEntity<Map<String, Object>>> create(
            @RequestBody CreatePackageRequest body, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.FINANCE, BackendRole.PLATFORM_ADMIN)
                .then(packages.create(body.name(), body.description(),
                        body.priceCents(), body.creditsAmount(), body.note()))
                .map(pkg -> ResponseEntity.ok(Map.of("success", true, "data", body(pkg))));
    }

    /** 调价：追加新版本并切换 current（历史版本不可变）。 */
    @PutMapping("/api/admin/credits-packages/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> reprice(
            @PathVariable String id, @RequestBody RepriceRequest body, ServerHttpRequest request) {
        requireUuid(id);
        return callers.requireRole(request, BackendRole.FINANCE, BackendRole.PLATFORM_ADMIN)
                .then(packages.newVersion(id, body.priceCents(), body.creditsAmount(), body.note()))
                .map(pkg -> ResponseEntity.ok(Map.of("success", true, "data", body(pkg))));
    }

    @PutMapping("/api/admin/credits-packages/{id}/status")
    public Mono<ResponseEntity<Map<String, Object>>> setStatus(
            @PathVariable String id, @RequestBody StatusRequest body, ServerHttpRequest request) {
        requireUuid(id);
        if (!"draft".equals(body.status()) && !"active".equals(body.status()) && !"retired".equals(body.status())) {
            throw new FinanceException(400, "积分包状态不合法");
        }
        return callers.requireRole(request, BackendRole.FINANCE, BackendRole.PLATFORM_ADMIN)
                .then(packages.setStatus(id, body.status()))
                .map(pkg -> ResponseEntity.ok(Map.of("success", true, "data", body(pkg))));
    }

    // ---------------- helpers ----------------

    private static void requireUuid(String id) {
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException error) {
            throw new FinanceException(400, "积分包标识不合法");
        }
    }

    private static Map<String, Object> body(PackageView pkg) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", pkg.id());
        data.put("name", pkg.name());
        data.put("description", pkg.description());
        data.put("status", pkg.status());
        data.put("version", pkg.version());
        data.put("priceCents", pkg.priceCents());
        data.put("creditsAmount", pkg.creditsAmount());
        data.put("note", pkg.note());
        return data;
    }

    /** 创建请求：name 1-50、priceCents 1-1,000,000、creditsAmount 1-100,000、note ≤200。 */
    public record CreatePackageRequest(
            String name, String description, Long priceCents, Integer creditsAmount, String note) {
        public CreatePackageRequest {
            name = trimmed(name);
            if (name.isEmpty() || name.length() > 50) {
                throw new FinanceException(400, "积分包名称需为 1-50 字");
            }
            description = description == null ? "" : description.trim();
            if (description.length() > 200) {
                throw new FinanceException(400, "积分包描述最多 200 字");
            }
            if (priceCents == null || priceCents < 1 || priceCents > 1_000_000) {
                throw new FinanceException(400, "价格（分）需在 1-1000000 之间");
            }
            if (creditsAmount == null || creditsAmount < 1 || creditsAmount > 100_000) {
                throw new FinanceException(400, "积分面值需在 1-100000 之间");
            }
            note = optionalTrimmed(note);
        }
    }

    public record RepriceRequest(Long priceCents, Integer creditsAmount, String note) {
        public RepriceRequest {
            if (priceCents == null || priceCents < 1 || priceCents > 1_000_000) {
                throw new FinanceException(400, "价格（分）需在 1-1000000 之间");
            }
            if (creditsAmount == null || creditsAmount < 1 || creditsAmount > 100_000) {
                throw new FinanceException(400, "积分面值需在 1-100000 之间");
            }
            note = optionalTrimmed(note);
        }
    }

    public record StatusRequest(String status) {
        public StatusRequest {
            status = trimmed(status);
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static String optionalTrimmed(String value) {
        String trimmed = trimmed(value);
        if (trimmed.length() > 200) {
            throw new FinanceException(400, "备注最多 200 字");
        }
        return trimmed;
    }
}
