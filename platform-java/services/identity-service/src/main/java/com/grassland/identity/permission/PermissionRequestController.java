package com.grassland.identity.permission;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.organization.OrganizationRepository;
import com.grassland.identity.organization.PermissionTier;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 商家权限审核工作流 HTTP 入口。草场身份域 Slice 2H（HLD D-05 地基）。
 *
 * <p>申请侧（org 内）：
 * <ul>
 *   <li>POST /api/organizations/{orgId}/permission-requests — 提交升级申请（OWNER；requestedTier 须高于当前→否则 409；outbox {@code PermissionRequested}）。</li>
 *   <li>GET /api/organizations/{orgId}/permission-requests — 列本 org 申请（MEMBER+）。</li>
 * </ul>
 *
 * <p>审核侧（平台 admin，{@link CurrentAccountResolver#requireAdmin}）：
 * <ul>
 *   <li>GET /api/admin/permission-requests — 列 pending 队列。</li>
 *   <li>GET /api/admin/permission-requests/{id} — 申请详情。</li>
 *   <li>POST /api/admin/permission-requests/{id}/review — 审核（终态→409；approve→{@code updatePermissionTier}+{@code MerchantPermissionGranted}；outbox {@code PermissionReviewed}）。</li>
 * </ul>
 */
@RestController
public class PermissionRequestController {

    private final CurrentAccountResolver accounts;
    private final OrgAuthorization authz;
    private final MerchantPermissionRequestRepository requests;
    private final OrganizationRepository organizations;
    private final OutboxRepository outbox;

    public PermissionRequestController(CurrentAccountResolver accounts, OrgAuthorization authz,
                                       MerchantPermissionRequestRepository requests,
                                       OrganizationRepository organizations, OutboxRepository outbox) {
        this.accounts = accounts;
        this.authz = authz;
        this.requests = requests;
        this.organizations = organizations;
        this.outbox = outbox;
    }

    @PostMapping(value = "/api/organizations/{orgId}/permission-requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> createRequest(@PathVariable String orgId,
                                                                   @RequestBody CreatePermissionRequest body,
                                                                   ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.OWNER)
                .flatMap(owner -> organizations.findById(orgId)
                        .switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在")))
                        .flatMap(org -> {
                            PermissionTier current = PermissionTier.fromDb(org.permissionTier());
                            PermissionTier target = PermissionTier.fromDb(body.requestedTier());
                            if (target.ordinal() <= current.ordinal()) {
                                return Mono.<MerchantPermissionRequest>error(
                                        new IdentityException(409, "申请等级须高于当前"));
                            }
                            return requests.create(orgId, owner.id(), target.dbValue(), body.materials());
                        })
                        .flatMap(req -> outbox.append(new EventEnvelope(
                                UUID.randomUUID().toString(), "PermissionRequested", "MerchantPermissionRequest",
                                req.id(), 1, Instant.now(), null,
                                Map.of("organizationId", orgId, "requesterAccountId", owner.id(),
                                        "requestedTier", req.requestedTier())))
                                .thenReturn(req))
                        .map(req -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(req)))));
    }

    @GetMapping("/api/organizations/{orgId}/permission-requests")
    public Mono<ResponseEntity<Map<String, Object>>> listByOrg(@PathVariable String orgId, ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.MEMBER)
                .flatMap(account -> requests.findByOrganization(orgId).collectList()
                        .map(list -> ResponseEntity.ok(Map.of("success", true,
                                "data", list.stream().map(this::toBody).toList()))));
    }

    @GetMapping("/api/admin/permission-requests")
    public Mono<ResponseEntity<Map<String, Object>>> listPending(ServerHttpRequest request) {
        return accounts.requireAdmin(request)
                .flatMap(admin -> requests.findPending().collectList()
                        .map(list -> ResponseEntity.ok(Map.of("success", true,
                                "data", list.stream().map(this::toBody).toList()))));
    }

    @GetMapping("/api/admin/permission-requests/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String id, ServerHttpRequest request) {
        return accounts.requireAdmin(request)
                .flatMap(admin -> requests.findById(id)
                        .map(req -> ResponseEntity.ok(Map.of("success", true, "data", toBody(req))))
                        .switchIfEmpty(Mono.error(new IdentityException(404, "申请不存在"))));
    }

    @PostMapping(value = "/api/admin/permission-requests/{id}/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> review(@PathVariable String id,
                                                            @RequestBody ReviewPermissionRequest body,
                                                            ServerHttpRequest request) {
        return accounts.requireAdmin(request)
                .flatMap(admin -> requests.findById(id)
                        .switchIfEmpty(Mono.error(new IdentityException(404, "申请不存在")))
                        .flatMap(req -> {
                            if (PermissionRequestStatus.fromDb(req.status()).isTerminal()) {
                                return Mono.<MerchantPermissionRequest>error(
                                        new IdentityException(409, "该申请已审核完成"));
                            }
                            boolean approve = "approve".equalsIgnoreCase(body.decision());
                            String newStatus = approve
                                    ? PermissionRequestStatus.APPROVED.dbValue()
                                    : PermissionRequestStatus.REJECTED.dbValue();
                            // 批准：升级 org tier + MerchantPermissionGranted；拒绝：不动 tier
                            Mono<Void> grant = approve
                                    ? organizations.updatePermissionTier(req.organizationId(), req.requestedTier())
                                        .flatMap(n -> outbox.append(new EventEnvelope(
                                                UUID.randomUUID().toString(), "MerchantPermissionGranted", "Organization",
                                                req.organizationId(), 1, Instant.now(), null,
                                                Map.of("organizationId", req.organizationId(), "tier", req.requestedTier()))).then())
                                    : Mono.empty();
                            return grant.then(requests.updateStatus(id, newStatus, admin.id(), body.note()))
                                    .flatMap(updated -> outbox.append(new EventEnvelope(
                                            UUID.randomUUID().toString(), "PermissionReviewed", "MerchantPermissionRequest",
                                            id, 1, Instant.now(), null,
                                            Map.of("organizationId", req.organizationId(),
                                                    "decision", body.decision().trim().toLowerCase())))
                                            .thenReturn(updated));
                        })
                        .map(updated -> ResponseEntity.ok(Map.of("success", true, "data", toBody(updated)))));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    private Map<String, Object> toBody(MerchantPermissionRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", req.id());
        m.put("organizationId", req.organizationId());
        m.put("requesterAccountId", req.requesterAccountId());
        m.put("requestedTier", req.requestedTier());
        m.put("status", req.status());
        m.put("reviewerAccountId", req.reviewerAccountId());
        m.put("reviewNote", req.reviewNote());
        m.put("createdAt", req.createdAt() == null ? null : req.createdAt().toString());
        return m;
    }
}
