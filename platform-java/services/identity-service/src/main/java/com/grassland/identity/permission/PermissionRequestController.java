package com.grassland.identity.permission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 商家权限审核工作流 HTTP 入口。Slice 2H 地基；Slice 2L 补全 D-05 完整规则（材料校验 / SLA / 申诉 / 额度查询）。
 *
 * <p>申请侧（org 内）：
 * <ul>
 *   <li>POST /api/organizations/{orgId}/permission-requests — 提交升级（OWNER；requestedTier 须高于当前；
 *       materials 按 tier+行业校验缺→400；算 review_deadline；outbox {@code PermissionRequested}）。</li>
 *   <li>POST /api/organizations/{orgId}/permission-requests/{id}/appeal — 申诉被拒申请（OWNER；原须 rejected）。</li>
 *   <li>GET /api/organizations/{orgId}/permission-requests — 列本 org 申请（MEMBER+）。</li>
 *   <li>GET /api/organizations/{orgId}/quota — 当前 tier 的额度策略（MEMBER+）。</li>
 * </ul>
 *
 * <p>审核侧（平台 admin，{@link CurrentAccountResolver#requireAdmin}）：
 * <ul>
 *   <li>GET /api/admin/permission-requests — 列 pending 队列。</li>
 *   <li>GET /api/admin/permission-requests/{id} — 申请详情。</li>
 *   <li>POST /api/admin/permission-requests/{id}/review — 审核（终态→409；approve→升级 tier；outbox {@code PermissionReviewed}）。</li>
 * </ul>
 */
@RestController
public class PermissionRequestController {

    private final CurrentAccountResolver accounts;
    private final OrgAuthorization authz;
    private final MerchantPermissionRequestRepository requests;
    private final OrganizationRepository organizations;
    private final OutboxRepository outbox;
    private final PermissionSla sla;
    private final TransactionalOperator transactions;
    // 本地 ObjectMapper（Spring Boot 4 的 Jackson autoconfig 在独立模块，identity 未引入；与 LegacySessionBridge 同模式）。
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PermissionRequestController(CurrentAccountResolver accounts, OrgAuthorization authz,
                                       MerchantPermissionRequestRepository requests,
                                       OrganizationRepository organizations, OutboxRepository outbox,
                                       PermissionSla sla, TransactionalOperator transactions) {
        this.accounts = accounts;
        this.authz = authz;
        this.requests = requests;
        this.organizations = organizations;
        this.outbox = outbox;
        this.sla = sla;
        this.transactions = transactions;
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
                            Industry industry = resolveIndustry(body.industry(), org.industry());
                            String materialsJson = validateAndSerialize(target, industry, body.materials());
                            Instant deadline = sla.deadlineFor(Instant.now());
                            return transactions.transactional(
                                    requests.create(orgId, owner.id(), target.dbValue(), materialsJson,
                                            industry.dbValue(), deadline)
                                            .flatMap(req -> outbox.append(new EventEnvelope(
                                                    UUID.randomUUID().toString(), "PermissionRequested", "MerchantPermissionRequest",
                                                    req.id(), 1, Instant.now(), null,
                                                    Map.of("organizationId", orgId, "requesterAccountId", owner.id(),
                                                            "requestedTier", req.requestedTier(), "industry", req.industry())))
                                                    .thenReturn(req)));
                        })
                        .map(req -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(req)))));
    }

    /** 申诉被拒申请：新建 pending 申请引用原 rejected 申请，带补充材料 + 申诉说明。 */
    @PostMapping(value = "/api/organizations/{orgId}/permission-requests/{id}/appeal", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> appeal(@PathVariable String orgId, @PathVariable String id,
                                                            @RequestBody CreateAppealRequest body,
                                                            ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.OWNER)
                .flatMap(owner -> requests.findById(id)
                        .switchIfEmpty(Mono.error(new IdentityException(404, "申请不存在")))
                        .flatMap(original -> {
                            if (PermissionRequestStatus.fromDb(original.status()) != PermissionRequestStatus.REJECTED) {
                                return Mono.<MerchantPermissionRequest>error(new IdentityException(409, "仅被拒申请可申诉"));
                            }
                            PermissionTier target = PermissionTier.fromDb(original.requestedTier());
                            Industry industry = resolveIndustry(null, original.industry());
                            String materialsJson = validateAndSerialize(target, industry, body.materials());
                            Instant deadline = sla.deadlineFor(Instant.now());
                            return transactions.transactional(
                                    requests.createAppeal(orgId, owner.id(), target.dbValue(), materialsJson,
                                            industry.dbValue(), deadline, original.id(), body.note())
                                            .flatMap(req -> outbox.append(new EventEnvelope(
                                                    UUID.randomUUID().toString(), "PermissionRequested", "MerchantPermissionRequest",
                                                    req.id(), 1, Instant.now(), null,
                                                    Map.of("organizationId", orgId, "requesterAccountId", owner.id(),
                                                            "requestedTier", req.requestedTier(), "originalRequestId", id)))
                                                    .thenReturn(req)));
                        })
                        .map(req -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(req)))));
    }

    @GetMapping("/api/organizations/{orgId}/permission-requests")
    public Mono<ResponseEntity<Map<String, Object>>> listByOrg(@PathVariable String orgId, ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.MEMBER)
                .flatMap(account -> requests.findByOrganization(orgId).collectList()
                        .map(list -> ResponseEntity.ok(Map.of("success", true,
                                "data", list.stream().map(this::toBody).toList()))));
    }

    /** 当前 org tier 的额度策略（identity 暴露限额上限；硬限额执行留 marketplace/finance）。 */
    @GetMapping("/api/organizations/{orgId}/quota")
    public Mono<ResponseEntity<Map<String, Object>>> quota(@PathVariable String orgId, ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.MEMBER)
                .flatMap(account -> organizations.findById(orgId)
                        .switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在")))
                        .map(org -> {
                            PermissionTier tier = PermissionTier.fromDb(org.permissionTier());
                            PermissionQuotaPolicy.TierQuota q = PermissionQuotaPolicy.quotaFor(tier);
                            return ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                                    "tier", tier.dbValue(),
                                    "quota", Map.of(
                                            "maxActiveTasks", q.maxActiveTasks(),
                                            "maxMonthlyTasks", q.maxMonthlyTasks(),
                                            "maxTxAmountCents", q.maxTxAmountCents()))));
                        }));
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
                            Mono<Void> grant = approve
                                    ? organizations.updatePermissionTier(req.organizationId(), req.requestedTier())
                                        .flatMap(n -> outbox.append(new EventEnvelope(
                                                UUID.randomUUID().toString(), "MerchantPermissionGranted", "Organization",
                                                req.organizationId(), 1, Instant.now(), null,
                                                Map.of("organizationId", req.organizationId(), "tier", req.requestedTier()))).then())
                                    : Mono.empty();
                            return transactions.transactional(
                                    grant.then(requests.updateStatus(id, newStatus, admin.id(), body.note()))
                                            .flatMap(updated -> outbox.append(new EventEnvelope(
                                                    UUID.randomUUID().toString(), "PermissionReviewed", "MerchantPermissionRequest",
                                                    id, 1, Instant.now(), null,
                                                    Map.of("organizationId", req.organizationId(),
                                                            "decision", body.decision().trim().toLowerCase())))
                                                    .thenReturn(updated)));
                        })
                        .map(updated -> ResponseEntity.ok(Map.of("success", true, "data", toBody(updated)))));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    /** 材料 schema 校验 / 非法 tier 等非法参数 → 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", error.getMessage()));
    }

    /** 校验 materials 覆盖必填并序列化为 JSON；缺料抛 {@link IllegalArgumentException}（→400）。 */
    private String validateAndSerialize(PermissionTier target, Industry industry, Map<String, String> materials) {
        Map<String, String> mats = materials == null ? Map.of() : materials;
        PermissionMaterialPolicy.validate(target, industry, mats);
        try {
            return objectMapper.writeValueAsString(mats);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize materials failed", e);
        }
    }

    /** 行业：请求体覆盖优先，否则取 org 行业快照；非法值回落 OTHER（不阻断提交）。 */
    private static Industry resolveIndustry(String override, String orgIndustry) {
        String raw = (override != null && !override.isBlank()) ? override : orgIndustry;
        try {
            return Industry.fromDb(raw);
        } catch (IllegalArgumentException ignored) {
            return Industry.OTHER;
        }
    }

    private Map<String, Object> toBody(MerchantPermissionRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", req.id());
        m.put("organizationId", req.organizationId());
        m.put("requesterAccountId", req.requesterAccountId());
        m.put("requestedTier", req.requestedTier());
        m.put("status", req.status());
        m.put("industry", req.industry());
        m.put("materials", req.materials());
        m.put("reviewDeadline", req.reviewDeadline() == null ? null : req.reviewDeadline().toString());
        m.put("slaStatus", sla.status(PermissionRequestStatus.fromDb(req.status()), req.reviewDeadline(), Instant.now()));
        m.put("reviewerAccountId", req.reviewerAccountId());
        m.put("reviewNote", req.reviewNote());
        m.put("originalRequestId", req.originalRequestId());
        m.put("appealNote", req.appealNote());
        m.put("createdAt", req.createdAt() == null ? null : req.createdAt().toString());
        return m;
    }
}
