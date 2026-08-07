package com.grassland.identity.membership;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.security.InternalServiceCallerResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Identity-authoritative organization memberships for trusted domain services. */
@RestController
public class InternalMembershipController {

    public static final String TRUST_SERVICE = "trust";

    private final InternalServiceCallerResolver callers;
    private final MembershipRepository memberships;

    public InternalMembershipController(
            InternalServiceCallerResolver callers, MembershipRepository memberships) {
        this.callers = callers;
        this.memberships = memberships;
    }

    @GetMapping("/internal/identity/accounts/{accountId}/organization-memberships")
    public Mono<ResponseEntity<Map<String, Object>>> organizationMemberships(
            @PathVariable String accountId, ServerHttpRequest request) {
        return callers.requireServicePrincipal(request, TRUST_SERVICE)
                .map(ignored -> requireUuid(accountId))
                .flatMap(validAccountId -> memberships.findOrganizationIdsByAccount(validAccountId)
                        .collectList()
                        .map(organizationIds -> response(validAccountId, organizationIds)));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("success", false, "error", error.getMessage()));
    }

    private static ResponseEntity<Map<String, Object>> response(
            String accountId, List<String> organizationIds) {
        Map<String, Object> data = Map.of(
                "accountId", accountId,
                "organizationIds", List.copyOf(organizationIds));
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    private static String requireUuid(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException error) {
            throw new IdentityException(400, "账号 ID 格式无效");
        }
    }
}
