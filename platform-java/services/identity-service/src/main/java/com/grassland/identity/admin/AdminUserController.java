package com.grassland.identity.admin;

import com.grassland.identity.admin.AdminUserRepository.AdminUserRow;
import com.grassland.identity.admin.FinanceCreditsAdminClient.AccountBalance;
import com.grassland.identity.assertion.BackendRole;
import com.grassland.identity.auth.IdentityException;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 平台 admin 用户管理 + 积分调整（迁自 legacy {@code server/src/routes/admin.ts}）。
 *
 * <p>两个端点都要求 {@code role==admin}（{@link CurrentAccountResolver#requireAdmin}），与 KYB/权限审核同口径。
 * 响应统一 {@code {success:true, data:{...}}} 信封。前端 {@code AdminView.vue} 已同步适配（不再读裸 {@code {users}}）。
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

    public AdminUserController(
            CurrentAccountResolver accounts,
            AdminUserRepository adminUsers,
            FinanceCreditsAdminClient financeCredits,
            BackendRoleRepository backendRoles) {
        this.accounts = accounts;
        this.adminUsers = adminUsers;
        this.financeCredits = financeCredits;
        this.backendRoles = backendRoles;
    }

    @GetMapping("/api/admin/users")
    public Mono<ResponseEntity<Map<String, Object>>> listUsers(ServerHttpRequest request) {
        return accounts.requireAdmin(request)
                .flatMap(admin -> adminUsers.findAll()
                        .flatMap(rows -> {
                            List<String> accountIds = rows.stream().map(AdminUserRow::id).toList();
                            return financeCredits.fetchBalances(accountIds)
                                    .flatMap(balances -> collectBackendRoles(accountIds)
                                            .map(roleMap -> rows.stream()
                                                    .map(row -> toUserItem(row,
                                                            balances.get(row.id()),
                                                            roleMap.getOrDefault(row.id(), List.of())))
                                                    .toList()));
                        })
                        .map(users -> ResponseEntity.ok(Map.of("success", true,
                                "data", Map.of("users", users)))));
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

    /** adjust-credits 请求体：amount 可正可负（正=award / 负=refund），对齐 legacy schema。 */
    public record AdjustCreditsRequest(String userId, int amount, String note) {}

    /** update-roles 请求体：action=grant/revoke，role 为 BackendRole dbValue。 */
    public record UpdateRolesRequest(String action, String role) {}
}
