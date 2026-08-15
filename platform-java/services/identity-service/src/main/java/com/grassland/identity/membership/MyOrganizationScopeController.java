package com.grassland.identity.membership;

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

/**
 * 列当前账号的组织范围（含角色），供前端做 org admin/member 粒度 UI（如商家素材库组织级管理入口）。
 * 镜像 store 的 {@code MyStoreScopeController}；角色权威口径同 {@link OrgAuthorization#roleOfAccount}
 * （成员表优先、owner_account_id 兜底）。
 */
@RestController
public class MyOrganizationScopeController {

    private final CurrentAccountResolver accounts;
    private final MembershipRepository memberships;

    public MyOrganizationScopeController(CurrentAccountResolver accounts, MembershipRepository memberships) {
        this.accounts = accounts;
        this.memberships = memberships;
    }

    @GetMapping("/api/me/organization-scopes")
    public Mono<ResponseEntity<Map<String, Object>>> list(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> memberships.findScopesByAccount(account.id()).collectList())
                .map(scopes -> ResponseEntity.ok(Map.of("success", true, "data",
                        scopes.stream().map(MyOrganizationScopeController::toBody).toList())));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("success", false, "error", error.getMessage()));
    }

    private static Map<String, Object> toBody(OrganizationAccessScope scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", scope.organizationId());
        body.put("organizationName", scope.organizationName());
        body.put("organizationStatus", scope.organizationStatus());
        body.put("permissionTier", scope.permissionTier());
        body.put("role", scope.role());
        return body;
    }
}
