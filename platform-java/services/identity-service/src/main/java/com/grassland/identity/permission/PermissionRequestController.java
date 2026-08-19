package com.grassland.identity.permission;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.auth.IdentityException;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.organization.OrganizationRepository;
import com.grassland.identity.organization.PermissionTier;
import com.grassland.identity.organization.SessionPrincipal;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final PermissionAutomaticReviewer automaticReviewer;
    private final PermissionRequestAuditRepository audits;
    private final TransactionalOperator transactions;
    // 本地 ObjectMapper（Spring Boot 4 的 Jackson autoconfig 在独立模块，identity 未引入）。
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PermissionRequestController(CurrentAccountResolver accounts, OrgAuthorization authz,
                                       MerchantPermissionRequestRepository requests,
                                       OrganizationRepository organizations, OutboxRepository outbox,
                                       PermissionSla sla, PermissionAutomaticReviewer automaticReviewer,
                                       PermissionRequestAuditRepository audits, TransactionalOperator transactions) {
        this.accounts = accounts;
        this.authz = authz;
        this.requests = requests;
        this.organizations = organizations;
        this.outbox = outbox;
        this.sla = sla;
        this.automaticReviewer = automaticReviewer;
        this.audits = audits;
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
                            String attachmentIdsJson = serialize(body.attachmentIds());
                            Instant deadline = sla.deadlineFor(Instant.now());
                            return automaticReviewer.evaluate(orgId, target, industry, body.attachmentIds())
                                    .flatMap(auto -> transactions.transactional(
                                    requests.create(orgId, owner.id(), target.dbValue(), materialsJson,
                                            industry.dbValue(), deadline, attachmentIdsJson, auto)
                                            .flatMap(req -> audits.append(req.id(), orgId, owner.id(), "merchant",
                                                            "submitted", null, req.status(), auto.resultJson())
                                                    .then(outbox.append(new EventEnvelope(
                                                    UUID.randomUUID().toString(), "PermissionRequested", "MerchantPermissionRequest",
                                                    req.id(), 1, Instant.now(), null,
                                                    Map.of("organizationId", orgId, "requesterAccountId", owner.id(),
                                                            "requestedTier", req.requestedTier(), "industry", req.industry()))))
                                                    .thenReturn(req))));
                        })
                        .map(req -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(req)))))
                .onErrorMap(DataIntegrityViolationException.class,
                        error -> new IdentityException(409, "该组织已有相同等级的待审申请"));
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
                            if (!orgId.equals(original.organizationId())) {
                                return Mono.<MerchantPermissionRequest>error(new IdentityException(404, "申请不存在"));
                            }
                            if (PermissionRequestStatus.fromDb(original.status()) != PermissionRequestStatus.REJECTED) {
                                return Mono.<MerchantPermissionRequest>error(new IdentityException(409, "仅被拒申请可申诉"));
                            }
                            PermissionTier target = PermissionTier.fromDb(original.requestedTier());
                            Industry industry = resolveIndustry(null, original.industry());
                            String materialsJson = validateAndSerialize(target, industry, body.materials());
                            String attachmentIdsJson = serialize(body.attachmentIds());
                            Instant deadline = sla.deadlineFor(Instant.now());
                            String rootId = original.originalRequestId() == null
                                    ? original.id() : original.originalRequestId();
                            return requests.countAppeals(rootId).flatMap(count -> {
                                if (count >= 3) {
                                    return Mono.error(new IdentityException(409, "申诉次数已达上限"));
                                }
                                return automaticReviewer.evaluate(orgId, target, industry, body.attachmentIds())
                                        .flatMap(auto -> transactions.transactional(
                                    requests.createAppeal(orgId, owner.id(), target.dbValue(), materialsJson,
                                            industry.dbValue(), deadline, rootId, body.note(), count + 1,
                                            attachmentIdsJson, auto)
                                            .flatMap(req -> audits.append(req.id(), orgId, owner.id(), "merchant",
                                                            "appeal_submitted", original.status(), req.status(), auto.resultJson())
                                                    .then(outbox.append(new EventEnvelope(
                                                    UUID.randomUUID().toString(), "PermissionRequested", "MerchantPermissionRequest",
                                                    req.id(), 1, Instant.now(), null,
                                                    Map.of("organizationId", orgId, "requesterAccountId", owner.id(),
                                                            "requestedTier", req.requestedTier(), "originalRequestId", rootId))))
                                                    .thenReturn(req))));
                            });
                        })
                        .map(req -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(req)))))
                .onErrorMap(DataIntegrityViolationException.class,
                        error -> new IdentityException(409, "该组织已有相同等级的待审申请"));
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

    @PostMapping("/api/admin/permission-requests/{id}/claim")
    public Mono<ResponseEntity<Map<String, Object>>> claim(@PathVariable String id, ServerHttpRequest request) {
        return accounts.requireAdmin(request)
                .flatMap(admin -> transactions.transactional(
                        requests.claim(id, admin.id())
                                .flatMap(claimed -> audits.append(claimed.id(), claimed.organizationId(), admin.id(),
                                                "admin", "claimed", PermissionRequestStatus.PENDING.dbValue(),
                                                claimed.status(), null)
                                        .thenReturn(claimed))
                                // Same reviewer retrying claim is idempotent and must not duplicate the audit row.
                                .switchIfEmpty(requests.findById(id).flatMap(existing ->
                                        PermissionRequestStatus.fromDb(existing.status())
                                                        == PermissionRequestStatus.UNDER_REVIEW
                                                && admin.id().equals(existing.reviewerAccountId())
                                                ? Mono.just(existing)
                                                : Mono.error(new IdentityException(409,
                                                        "申请已被领取或不在待审状态"))))))
                .map(claimed -> ResponseEntity.ok(Map.of("success", true, "data", toBody(claimed))));
    }

    @GetMapping("/api/admin/permission-requests/{id}/audit")
    public Mono<ResponseEntity<Map<String, Object>>> audit(@PathVariable String id, ServerHttpRequest request) {
        return accounts.requireAdmin(request)
                .flatMap(admin -> requests.findById(id)
                        .switchIfEmpty(Mono.error(new IdentityException(404, "申请不存在")))
                        .then(audits.findByRequest(id).collectList()))
                .map(items -> ResponseEntity.ok(Map.of("success", true,
                        "data", items.stream().map(this::toAuditBody).toList())));
    }

    @PostMapping(value = "/api/admin/permission-requests/{id}/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> review(@PathVariable String id,
                                                            @RequestBody ReviewPermissionRequest body,
                                                            ServerHttpRequest request) {
        return accounts.requireAdminPrincipal(request)
                .flatMap(adminPrincipal -> requests.findById(id)
                        .switchIfEmpty(Mono.error(new IdentityException(404, "申请不存在")))
                        .flatMap(req -> {
                            if (PermissionRequestStatus.fromDb(req.status()).isTerminal()) {
                                return Mono.<MerchantPermissionRequest>error(
                                        new IdentityException(409, "该申请已审核完成"));
                            }
                            boolean approve = "approve".equalsIgnoreCase(body.decision());
                            if (!approve && (body.note() == null || body.note().isBlank())) {
                                return Mono.error(new IdentityException(400, "驳回必须填写原因"));
                            }
                            if (approve && "pending".equals(req.autoReviewStatus())) {
                                return Mono.error(new IdentityException(409, "自动核验尚未完成"));
                            }
                            // Finance grants and explicit automatic-verification failures are high-risk
                            // overrides. Advisory `needs_review` remains in the normal manual queue.
                            boolean highRisk = approve && (PermissionTier.FINANCE_TRANSACTION.dbValue()
                                    .equals(req.requestedTier()) || "failed".equals(req.autoReviewStatus()));
                            Mono<SessionPrincipal> authorized =
                                    highRisk
                                            ? accounts.requireAdminWithRecentReauthentication(request, Duration.ofMinutes(10))
                                            : Mono.just(adminPrincipal);
                            return authorized.flatMap(principal -> reviewAuthorized(req, body, principal.user().id(), approve));
                        }))
                .map(updated -> ResponseEntity.ok(Map.of("success", true, "data", toBody(updated))));
    }

    private Mono<MerchantPermissionRequest> reviewAuthorized(MerchantPermissionRequest req,
                                                              ReviewPermissionRequest body,
                                                              String adminId, boolean approve) {
                            String newStatus = approve
                                    ? PermissionRequestStatus.APPROVED.dbValue()
                                    : PermissionRequestStatus.REJECTED.dbValue();
                            int expectedVersion = body.expectedVersion() == null ? req.version() : body.expectedVersion();
                            Mono<Void> grant = approve
                                    ? organizations.findByIdForUpdate(req.organizationId())
                                        .switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在")))
                                        .flatMap(org -> {
                                            PermissionTier current = PermissionTier.fromDb(org.permissionTier());
                                            PermissionTier target = PermissionTier.fromDb(req.requestedTier());
                                            if (current.isAtLeast(target)) {
                                                // A higher-tier request may have won the race. This request is
                                                // already satisfied, so close it as approved without downgrading
                                                // the organization or emitting a duplicate grant event.
                                                return Mono.empty();
                                            }
                                            return organizations.updatePermissionTier(
                                                            req.organizationId(), req.requestedTier())
                                                    .flatMap(n -> n == 1
                                                            ? outbox.append(new EventEnvelope(
                                                                    UUID.randomUUID().toString(),
                                                                    "MerchantPermissionGranted", "Organization",
                                                                    req.organizationId(), 1, Instant.now(), null,
                                                                    Map.of("organizationId", req.organizationId(),
                                                                            "tier", req.requestedTier()))).then()
                                                            : Mono.error(new IdentityException(409,
                                                                    "组织权限已被并发更新，请重试")));
                                        })
                                    : Mono.empty();
                            return transactions.transactional(
                                    requests.review(req.id(), newStatus, adminId, body.note(), expectedVersion)
                                            .switchIfEmpty(Mono.error(new IdentityException(409, "申请已被其他审核人处理，请刷新后重试")))
                                            .flatMap(updated -> grant.then(audits.append(req.id(), req.organizationId(), adminId,
                                                            "admin", approve ? "approved" : "rejected",
                                                            req.status(), newStatus, req.autoReviewResult()))
                                                    .thenReturn(updated))
                                            .flatMap(updated -> outbox.append(new EventEnvelope(
                                                    UUID.randomUUID().toString(), "PermissionReviewed", "MerchantPermissionRequest",
                                                    req.id(), 1, Instant.now(), null,
                                                    Map.of("organizationId", req.organizationId(),
                                                            "decision", body.decision().trim().toLowerCase())))
                                                    .thenReturn(updated)));
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

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialize permission data failed", e);
        }
    }

    /** 行业：请求体覆盖优先，否则取 org 行业快照；非法值按 400 拒绝，避免限制规则被回落绕过。 */
    private static Industry resolveIndustry(String override, String orgIndustry) {
        String raw = (override != null && !override.isBlank()) ? override : orgIndustry;
        return Industry.fromDb(raw);
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
        m.put("version", req.version());
        m.put("reviewStartedAt", req.reviewStartedAt() == null ? null : req.reviewStartedAt().toString());
        m.put("slaBreachedAt", req.slaBreachedAt() == null ? null : req.slaBreachedAt().toString());
        m.put("autoReviewStatus", req.autoReviewStatus());
        m.put("autoReviewResult", req.autoReviewResult());
        m.put("reviewMode", req.reviewMode());
        m.put("riskLevel", req.riskLevel());
        m.put("attachmentIds", req.attachmentIds());
        m.put("decisionAt", req.decisionAt() == null ? null : req.decisionAt().toString());
        m.put("appealCount", req.appealCount());
        return m;
    }

    private Map<String, Object> toAuditBody(PermissionRequestAudit audit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", audit.id());
        body.put("actorAccountId", audit.actorAccountId());
        body.put("actorKind", audit.actorKind());
        body.put("action", audit.action());
        body.put("fromStatus", audit.fromStatus());
        body.put("toStatus", audit.toStatus());
        body.put("details", audit.details());
        body.put("createdAt", audit.createdAt() == null ? null : audit.createdAt().toString());
        return body;
    }
}
