package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.organization.CurrentAccountResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Lists only the stores explicitly assigned to the current account. */
@RestController
public class MyStoreScopeController {

    private final CurrentAccountResolver accounts;
    private final StoreMembershipRepository memberships;

    public MyStoreScopeController(CurrentAccountResolver accounts, StoreMembershipRepository memberships) {
        this.accounts = accounts;
        this.memberships = memberships;
    }

    @GetMapping("/api/me/store-scopes")
    public Mono<ResponseEntity<Map<String, Object>>> list(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> memberships.findAccessScopesByAccount(account.id()).collectList())
                .map(scopes -> ResponseEntity.ok(Map.of("success", true, "data",
                        scopes.stream().map(MyStoreScopeController::toBody).toList())));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("success", false, "error", error.getMessage()));
    }

    private static Map<String, Object> toBody(StoreAccessScope scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("storeId", scope.storeId());
        body.put("storeName", scope.storeName());
        body.put("storeStatus", scope.storeStatus());
        body.put("organizationId", scope.organizationId());
        body.put("organizationName", scope.organizationName());
        body.put("organizationStatus", scope.organizationStatus());
        body.put("permissionTier", scope.permissionTier());
        body.put("role", scope.role());
        return body;
    }
}
