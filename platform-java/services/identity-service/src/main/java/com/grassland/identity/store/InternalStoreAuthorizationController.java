package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.security.InternalServiceCallerResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Identity-authoritative store resource authorization for trusted domain services. */
@RestController
public class InternalStoreAuthorizationController {

    private static final Set<String> TRUSTED_SERVICES = Set.of("marketplace", "intelligence");

    private final InternalServiceCallerResolver callers;
    private final StoreAuthorization authorization;

    public InternalStoreAuthorizationController(
            InternalServiceCallerResolver callers, StoreAuthorization authorization) {
        this.callers = callers;
        this.authorization = authorization;
    }

    @PostMapping("/internal/identity/store-authorizations/check")
    public Mono<ResponseEntity<Map<String, Object>>> check(
            @RequestBody CheckRequest body, ServerHttpRequest request) {
        if (body == null) {
            return Mono.error(new IdentityException(400, "请求体不能为空"));
        }
        String accountId = requireUuid(body.accountId(), "账号 ID");
        String organizationId = requireUuid(body.organizationId(), "组织 ID");
        String storeId = optionalUuid(body.storeId(), "门店 ID");
        StoreRole minimumRole = requireRole(body.minimumRole());
        return callers.requireServicePrincipal(request, TRUSTED_SERVICES)
                .then(authorization.authorizeAccount(accountId, organizationId, storeId, minimumRole))
                .map(decision -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("authorized", true);
                    data.put("accountId", accountId);
                    data.put("organizationId", organizationId);
                    if (storeId != null) {
                        data.put("storeId", storeId);
                    }
                    data.put("role", decision.role().dbValue());
                    data.put("scope", decision.scope());
                    return ResponseEntity.ok(Map.<String, Object>of("success", true, "data", data));
                });
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("success", false, "error", error.getMessage()));
    }

    private static StoreRole requireRole(String value) {
        try {
            return StoreRole.fromDb(value);
        } catch (IllegalArgumentException error) {
            throw new IdentityException(400, "minimumRole 无效");
        }
    }

    private static String requireUuid(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IdentityException(400, label + "不能为空");
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException error) {
            throw new IdentityException(400, label + "格式无效");
        }
    }

    private static String optionalUuid(String value, String label) {
        return value == null || value.isBlank() ? null : requireUuid(value, label);
    }

    public record CheckRequest(
            String accountId, String organizationId, String storeId, String minimumRole) {}
}
