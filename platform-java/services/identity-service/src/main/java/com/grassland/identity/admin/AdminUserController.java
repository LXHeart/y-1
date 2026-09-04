package com.grassland.identity.admin;

import com.grassland.identity.admin.AdminUserRepository.AdminUserRow;
import com.grassland.identity.admin.FinanceCreditsAdminClient.AccountBalance;
import com.grassland.identity.assertion.BackendRole;
import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.identityprofile.IdentityAuditLog;
import com.grassland.identity.identityprofile.IdentityAuditLogRepository;
import com.grassland.identity.organization.CurrentAccountResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 平台 admin 用户管理 + 积分调整（迁自 legacy {@code server/src/routes/admin.ts}）。
 *
 * <p>查看类（列表/审计，任务书 #72 卡 A）放开 customer_service + risk（客服查账号处理争议、风控调查异常，
 * PRD §11.8 角色表）；变更类（roles/adjust-credits）仍 platform_admin 独占。
 * 响应统一 {@code {success:true, data:{...}}} 信封；列表端点为统一分页信封
 * {@code data:{items, total, limit, offset}}（任务书 #2）。
 *
 * <p><b>先分页后 enrich</b>：SQL 层先 LIMIT/OFFSET 取当页 ≤200 行，再对当页做
 * {@link #collectBackendRoles}（逐账号查询）与 {@link FinanceCreditsAdminClient#fetchBalances}；
 * 顺序反了等于全表逐账号放大查询。
 *
 * <p>credits 余额经 {@link FinanceCreditsAdminClient} 批量取（一次 HTTP，避免 N+1）；
 * adjust 的 award/refund 也经它代理 finance，与 legacy {@code credit.service.ts} 路径一致。
 */
@RestController
public class AdminUserController {

    private static final int MAX_NOTE_LENGTH = 200;

    private final CurrentAccountResolver accounts;
    private final AdminUserRepository adminUsers;
    private final FinanceCreditsAdminClient financeCredits;
    private final BackendRoleRepository backendRoles;
    private final IdentityAuditLogRepository identityAudits;

    public AdminUserController(
            CurrentAccountResolver accounts,
            AdminUserRepository adminUsers,
            FinanceCreditsAdminClient financeCredits,
            BackendRoleRepository backendRoles,
            IdentityAuditLogRepository identityAudits) {
        this.accounts = accounts;
        this.adminUsers = adminUsers;
        this.financeCredits = financeCredits;
        this.backendRoles = backendRoles;
        this.identityAudits = identityAudits;
    }

    @GetMapping("/api/admin/users")
    public Mono<ResponseEntity<Map<String, Object>>> listUsers(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String identityType,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            ServerHttpRequest request) {
        String query = searchQuery(q);
        String statusFilter = blankToNull(status);
        String identityFilter = validateIdentityType(identityType);
        int pageSize = PageEnvelope.limit(limit);
        int pageOffset = PageEnvelope.offset(offset);
        return accounts.requireRole(request, BackendRole.PLATFORM_ADMIN, BackendRole.CUSTOMER_SERVICE,
                        BackendRole.RISK)
                .flatMap(admin -> Mono.zip(
                                adminUsers.findAll(query, statusFilter, identityFilter, pageSize, pageOffset),
                                adminUsers.countAll(query, statusFilter, identityFilter))
                        .flatMap(tuple -> {
                            List<AdminUserRow> rows = tuple.getT1();
                            List<String> accountIds = rows.stream().map(AdminUserRow::id).toList();
                            return financeCredits.fetchBalances(accountIds)
                                    .flatMap(balances -> collectBackendRoles(accountIds)
                                            .map(roleMap -> {
                                                List<Map<String, Object>> items = rows.stream()
                                                        .map(row -> toUserItem(row,
                                                                balances.get(row.id()),
                                                                roleMap.getOrDefault(row.id(), List.of())))
                                                        .toList();
                                                return ResponseEntity.ok(Map.of("success", true,
                                                        "data", PageEnvelope.data(items, tuple.getT2(),
                                                                pageSize, pageOffset)));
                                            }));
                        }));
    }

    Mono<ResponseEntity<Map<String, Object>>> listUsers(ServerHttpRequest request) {
        return listUsers(null, null, null, null, null, request);
    }

    @PutMapping(value = "/api/admin/users/{id}/roles", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> updateRoles(
            @PathVariable String id, @RequestBody UpdateRolesRequest body, ServerHttpRequest request) {
        return accounts.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .flatMap(admin -> {
                    String userId = validateUserId(id);
                    BackendRole role = validateRole(body.role());
                    return switch (body.action()) {
                        case "grant" -> backendRoles.grant(userId, role, admin.id())
                                .thenReturn(ResponseEntity.ok(Map.of("success", true, "data", Map.of("granted", true))));
                        case "revoke" -> backendRoles.revoke(userId, role)
                                .thenReturn(ResponseEntity.ok(Map.of("success", true, "data", Map.of("revoked", true))));
                        default -> Mono.error(new IllegalArgumentException("action 仅支持 grant/revoke"));
                    };
                });
    }

    @PostMapping(value = "/api/admin/adjust-credits", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> adjustCredits(
            @RequestBody AdjustCreditsRequest body, ServerHttpRequest request) {
        return accounts.requireAdmin(request)
                .flatMap(admin -> {
                    String userId = validateUserId(body.userId());
                    int amount = body.amount();
                    String note = validateNote(body.note());
                    Mono<Void> mutation = amount >= 0
                            ? financeCredits.award(userId, amount, note)
                            : financeCredits.refund(userId, Math.abs(amount), note);
                    return mutation.thenReturn(ResponseEntity.ok(Map.of("success", true,
                            "data", Map.of("adjusted", true))));
                });
    }

    /**
     * 某账号的身份切换审计时间线（GL-P2-ADMIN-009）。查看类：任务书 #72 卡 A 起与列表同口径
     * （platform_admin / customer_service / risk）。
     * 复用已有 {@link IdentityAuditLogRepository#findByAccount}（此前 0 调用方）。
     */
    @GetMapping("/api/admin/users/{id}/audit")
    public Mono<ResponseEntity<Map<String, Object>>> userAudit(
            @PathVariable String id, ServerHttpRequest request) {
        return accounts.requireRole(request, BackendRole.PLATFORM_ADMIN, BackendRole.CUSTOMER_SERVICE,
                BackendRole.RISK)
                .thenMany(identityAudits.findByAccount(validateUserId(id))
                        .map(AdminUserController::identityAuditBody))
                .collectList()
                .map(items -> ResponseEntity.ok(Map.of("success", true, "data", items)));
    }

    private static Map<String, Object> identityAuditBody(IdentityAuditLog log) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", log.id());
        item.put("accountId", log.accountId());
        item.put("action", log.action());
        item.put("fromIdentityType", log.fromIdentityType());
        item.put("toIdentityType", log.toIdentityType());
        item.put("sessionId", log.sessionToken());
        item.put("deviceId", log.deviceId());
        item.put("ipAddress", log.ipAddress());
        item.put("userAgent", log.userAgent());
        item.put("occurredAt", log.occurredAt() == null ? null : log.occurredAt().toString());
        return item;
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", error.getMessage()));
    }

    private static Map<String, Object> toUserItem(AdminUserRow row, AccountBalance balance, List<String> roles) {
        AccountBalance b = balance == null ? new AccountBalance(0, 0, 0) : balance;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.id());
        item.put("email", row.email());
        item.put("displayName", row.displayName());
        item.put("role", row.role());
        item.put("status", row.status());
        item.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
        item.put("balance", b.balance());
        item.put("totalEarned", b.totalEarned());
        item.put("totalSpent", b.totalSpent());
        item.put("roles", roles);
        Map<String, Object> identities = new LinkedHashMap<>();
        identities.put("recommender", Boolean.TRUE.equals(row.hasRecommender()));
        identities.put("merchant", Boolean.TRUE.equals(row.hasMerchant()));
        identities.put("member", Boolean.TRUE.equals(row.hasMembership()));
        identities.put("ownedOrgNames", row.ownedOrgNames());
        identities.put("ownedOrgs", row.ownedOrgs());
        item.put("identities", identities);
        return item;
    }

    /** 批量取多账号的后台角色（用于列表展示）。 */
    private Mono<Map<String, List<String>>> collectBackendRoles(List<String> accountIds) {
        return Flux.fromIterable(accountIds)
                .flatMap(accountId -> backendRoles.findByAccountId(accountId)
                        .map(roles -> Map.entry(accountId,
                                roles.stream().map(BackendRole::dbValue).sorted().toList())))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private static BackendRole validateRole(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("缺少 role");
        }
        BackendRole parsed = BackendRole.fromDb(role);
        if (parsed == null) {
            throw new IllegalArgumentException("未知角色: " + role);
        }
        return parsed;
    }

    private static String validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少 userId");
        }
        try {
            UUID.fromString(userId);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("userId 无效");
        }
        return userId;
    }

    private static String validateNote(String note) {
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("缺少 note");
        }
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("note 过长（上限 " + MAX_NOTE_LENGTH + " 字符）");
        }
        return trimmed;
    }

    private static String searchQuery(String value) {
        String query = value == null ? "" : value.trim();
        if (query.isEmpty()) return null;
        if (query.length() > 100) throw new IllegalArgumentException("q 最长 100 字符");
        return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** 空白归一为 null（「全部」不传参与传空串等价）。 */
    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /** identityType 筛选三值校验（recommender/merchant/member），非法 → 400。 */
    private static String validateIdentityType(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) return null;
        if (!normalized.equals("recommender") && !normalized.equals("merchant") && !normalized.equals("member")) {
            throw new IllegalArgumentException("identityType 仅支持 recommender/merchant/member");
        }
        return normalized;
    }

    /** adjust-credits 请求体：amount 可正可负（正=award / 负=refund），对齐 legacy schema。 */
    public record AdjustCreditsRequest(String userId, int amount, String note) {}

    /** update-roles 请求体：action=grant/revoke，role 为 BackendRole dbValue。 */
    public record UpdateRolesRequest(String action, String role) {}
}
