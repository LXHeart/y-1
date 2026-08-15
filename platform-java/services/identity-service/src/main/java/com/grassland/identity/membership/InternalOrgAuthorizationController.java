package com.grassland.identity.membership;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.organization.OrganizationRepository;
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

/**
 * Identity-authoritative organization-role authorization for trusted domain services.
 *
 * <p>镜像 {@code InternalStoreAuthorizationController} 的门店资源授权边界：组织级资源（如商家素材库的
 * 组织级行）需要按 org admin/member 粒度判定时，领域服务（marketplace/intelligence）以服务断言调用本端点，
 * 由 {@link OrgAuthorization#roleOfAccount} 给出权威结论（成员表优先、owner_account_id 兜底）。
 * 与门店 check 的「null storeId 视为要求 ADMIN」不同，本端点显式接收 minimumRole（member/admin/owner）。
 */
@RestController
public class InternalOrgAuthorizationController {

    private static final Set<String> TRUSTED_SERVICES = Set.of("marketplace", "intelligence");

    private final InternalServiceCallerResolver callers;
    private final OrgAuthorization orgAuthz;
    private final OrganizationRepository organizations;

    public InternalOrgAuthorizationController(
            InternalServiceCallerResolver callers, OrgAuthorization orgAuthz,
            OrganizationRepository organizations) {
        this.callers = callers;
        this.orgAuthz = orgAuthz;
        this.organizations = organizations;
    }

    @PostMapping("/internal/identity/organization-authorizations/check")
    public Mono<ResponseEntity<Map<String, Object>>> check(
            @RequestBody CheckRequest body, ServerHttpRequest request) {
        if (body == null) {
            return Mono.error(new IdentityException(400, "请求体不能为空"));
        }
        String accountId = requireUuid(body.accountId(), "账号 ID");
        String organizationId = requireUuid(body.organizationId(), "组织 ID");
        MembershipRole minimumRole = requireRole(body.minimumRole());
        return callers.requireServicePrincipal(request, TRUSTED_SERVICES)
                .then(organizations.findById(organizationId)
                        .switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在")))
                        .then(orgAuthz.roleOfAccount(accountId, organizationId)
                                .switchIfEmpty(Mono.error(new IdentityException(403, "无权访问该组织")))
                                .flatMap(role -> role.isAtLeast(minimumRole)
                                        ? Mono.just(role)
                                        : Mono.error(new IdentityException(403, "权限不足")))))
                .map(role -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("authorized", true);
                    data.put("accountId", accountId);
                    data.put("organizationId", organizationId);
                    data.put("role", role.dbValue());
                    return ResponseEntity.ok(Map.<String, Object>of("success", true, "data", data));
                });
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("success", false, "error", error.getMessage()));
    }

    private static MembershipRole requireRole(String value) {
        try {
            return MembershipRole.fromDb(value);
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

    public record CheckRequest(String accountId, String organizationId, String minimumRole) {}
}
