package com.grassland.identity.admin;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.organization.OrganizationRepository;
import com.grassland.identity.organization.subaccount.PasswordGenerator;
import com.grassland.identity.security.Argon2PasswordHasher;
import com.grassland.identity.session.SessionRepository;
import com.grassland.identity.user.AccountFlagRepository;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 平台级账号/组织管控五件套（任务书 #72 卡 B，D3/D6/D7/D8）。
 *
 * <p>
 * 与商家主体内部的 {@code OrgSubAccount*} 是两层：本控制器是 platform_admin 对<b>任意账号/组织</b>的
 * 平台处置，不受组织归属与主体内角色约束。状态机对齐先例——guarded 单向迁移（非法迁移 409）、
 * {@code deleted} 是终态不可恢复；停用/恢复<b>不触碰 organization_membership 行</b>（恢复后成员关系原样，
 * D6「roleOf 只认 active」的既有红线不动）。
 *
 * <p>
 * <b>owner 连带冻结</b>（D3）：停用商家 owner 同事务冻结其名下仍 active 的组织（风控语义：商家欺诈连店一起停）；
 * 账号与组织<b>分别恢复</b>——restore 账号不自动恢复组织。
 *
 * <p>
 * <b>会话失效两层</b>（D8）：suspended 由断言链自查 app_users.status 拦截（天然即时，无需吊会话表）；
 * reset-password 则物理删除该账号全部 session 行（改密后旧会话必须死）。
 *
 * <p>
 * 全部管控同事务写 outbox 事件（D7，信封与命名沿用 OrgSubAccount* 先例）；下游暂无消费方，事件先行。
 * 一次性初始密码只在本次响应出现（不落日志），首登强制改密由 account_flag 兜底（同 #71 建号先例）。
 */
@RestController
public class AdminAccountAdminController {

    private final CurrentAccountResolver accounts;
    private final DatabaseClient db;
    private final TransactionalOperator transactions;
    private final Argon2PasswordHasher argon2Hasher;
    private final AccountFlagRepository flags;
    private final OutboxRepository outbox;
    private final OrganizationRepository organizations;
    private final SessionRepository sessions;

    public AdminAccountAdminController(CurrentAccountResolver accounts, DatabaseClient db,
            TransactionalOperator transactions, Argon2PasswordHasher argon2Hasher, AccountFlagRepository flags,
            OutboxRepository outbox, OrganizationRepository organizations, SessionRepository sessions) {
        this.accounts = accounts;
        this.db = db;
        this.transactions = transactions;
        this.argon2Hasher = argon2Hasher;
        this.flags = flags;
        this.outbox = outbox;
        this.organizations = organizations;
        this.sessions = sessions;
    }

    // ---------- 账号级管控 ----------

    /** 停用账号：guarded active→suspended；owner 连带冻结名下组织；outbox AccountSuspended（含冻结清单）。 */
    @PostMapping("/api/admin/users/{id}/suspend")
    public Mono<ResponseEntity<Map<String, Object>>> suspendUser(
            @PathVariable String id, ServerHttpRequest request) {
        String targetId = validateUserId(id);
        return accounts.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .flatMap(admin -> {
                    if (admin.id().equals(targetId)) {
                        return Mono.error(new IdentityException(400, "不能停用自己的账号"));
                    }
                    return transactions.transactional(
                            guardedAccountStatusUpdate(targetId, "suspended", "active", "当前状态不可停用")
                                    .then(freezeOwnedOrganizations(targetId).collectList()
                                            .flatMap(frozenOrgIds -> appendAccountEvent("AccountSuspended",
                                                    targetId, admin.id(), frozenOrgIds)
                                                    .thenReturn(Map.<String, Object>of("suspended", true,
                                                            "frozenOrganizationIds", frozenOrgIds)))))
                            .map(data -> ResponseEntity.ok(Map.of("success", true, "data", data)));
                });
    }

    /** 恢复账号：guarded suspended→active；不触碰组织（D3 分别恢复——组织冻结是独立处置）。 */
    @PostMapping("/api/admin/users/{id}/restore")
    public Mono<ResponseEntity<Map<String, Object>>> restoreUser(
            @PathVariable String id, ServerHttpRequest request) {
        String targetId = validateUserId(id);
        return accounts.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .flatMap(admin -> transactions.transactional(
                        guardedAccountStatusUpdate(targetId, "active", "suspended", "当前状态不可恢复")
                                .then(appendAccountEvent("AccountRestored", targetId, admin.id(), null)))
                        .thenReturn(ResponseEntity.ok(Map.of("success", true, "data", Map.of("restored", true)))));
    }

    /**
     * 重置密码（任意状态可重置——suspended 账号恢复前先备好凭据是客服真实场景）：新一次性密码 +
     * 首登强制改密 + 物理删除全部 session 行，同事务 outbox AccountPasswordReset（不含明文）。
     */
    @PostMapping("/api/admin/users/{id}/reset-password")
    public Mono<ResponseEntity<Map<String, Object>>> resetPassword(
            @PathVariable String id, ServerHttpRequest request) {
        String targetId = validateUserId(id);
        return accounts.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .flatMap(admin -> {
                    if (admin.id().equals(targetId)) {
                        return Mono.error(new IdentityException(403, "不能重置自己的密码，请走修改密码"));
                    }
                    String initialPassword = PasswordGenerator.generate();
                    // 与 RegisterController 同款约束：Argon2 是 64MB/3 轮重操作，必须离开 Netty 事件循环。
                    return Mono.fromCallable(() -> argon2Hasher.hash(initialPassword))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(hash -> transactions.transactional(
                                    replacePassword(targetId, hash)
                                            .then(flags.markMustChangePassword(targetId))
                                            .then(sessions.deleteAllForAccount(targetId))
                                            .then(appendAccountEvent("AccountPasswordReset", targetId,
                                                    admin.id(), null)))
                                    .thenReturn(initialPassword))
                            .map(password -> ResponseEntity.ok(Map.of("success", true,
                                    "data", Map.of("initialPassword", password))));
                });
    }

    // ---------- 组织级管控（风控直接冻结/恢复，独立于 owner 连带） ----------

    @PostMapping("/api/admin/organizations/{id}/suspend")
    public Mono<ResponseEntity<Map<String, Object>>> suspendOrganization(
            @PathVariable String id, ServerHttpRequest request) {
        String orgId = validateUserId(id);
        return accounts.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .flatMap(admin -> transactions.transactional(
                        guardedOrganizationStatusUpdate(orgId, "suspended", "active", "当前状态不可冻结")
                                .then(appendOrganizationEvent("OrganizationSuspended", orgId, admin.id())))
                        .thenReturn(ResponseEntity.ok(Map.of("success", true, "data", Map.of("suspended", true)))));
    }

    @PostMapping("/api/admin/organizations/{id}/restore")
    public Mono<ResponseEntity<Map<String, Object>>> restoreOrganization(
            @PathVariable String id, ServerHttpRequest request) {
        String orgId = validateUserId(id);
        return accounts.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .flatMap(admin -> transactions.transactional(
                        guardedOrganizationStatusUpdate(orgId, "active", "suspended", "当前状态不可恢复")
                                .then(appendOrganizationEvent("OrganizationRestored", orgId, admin.id())))
                        .thenReturn(ResponseEntity.ok(Map.of("success", true, "data", Map.of("restored", true)))));
    }

    // ---------- 内部件 ----------

    /** 单边胜出的 guarded UPDATE（照抄 OrgSubAccountService.guardedStatusUpdate 语义）：0 行=回查现值映射 404/409。 */
    private Mono<Void> guardedAccountStatusUpdate(String targetAccountId, String to, String from,
            String conflictMessage) {
        return db.sql("UPDATE app_users SET status = :to, updated_at = NOW()"
                        + " WHERE id = CAST(:id AS uuid) AND status = :from")
                .bind("to", to).bind("id", targetAccountId).bind("from", from)
                .fetch().rowsUpdated()
                .flatMap(rows -> rows > 0 ? Mono.<Void>empty()
                        : db.sql("SELECT status FROM app_users WHERE id = CAST(:id AS uuid)")
                                .bind("id", targetAccountId)
                                .map(row -> row.get("status", String.class)).one()
                                .switchIfEmpty(Mono.error(new IdentityException(404, "账号不存在")))
                                .<Void>flatMap(current -> Mono.error(
                                        new IdentityException(409, current.equals(to) ? "已是该状态"
                                                : "deleted".equals(current) ? "账号已删除，不可恢复"
                                                        : conflictMessage))));
    }

    /** 连带冻结：owner 名下仍 active 的组织一并 suspended（0 行=无组织或已冻结，均放行）；RETURNING 出冻结清单进事件。 */
    private Flux<String> freezeOwnedOrganizations(String ownerId) {
        return db.sql("UPDATE organization SET status = 'suspended', updated_at = NOW()"
                        + " WHERE owner_account_id = CAST(:owner AS uuid) AND status = 'active'"
                        + " RETURNING id::text")
                .bind("owner", ownerId)
                .map(row -> row.get(0, String.class))
                .all();
    }

    /** 0 行 = 账号不存在（密码置换不接受静默失败）。 */
    private Mono<Void> replacePassword(String targetId, String hash) {
        return db.sql("UPDATE app_users SET password_hash = :hash, updated_at = NOW()"
                        + " WHERE id = CAST(:id AS uuid)")
                .bind("hash", hash).bind("id", targetId)
                .fetch().rowsUpdated()
                .flatMap(rows -> rows > 0 ? Mono.<Void>empty()
                        : Mono.error(new IdentityException(404, "账号不存在")));
    }

    private Mono<Void> guardedOrganizationStatusUpdate(String orgId, String to, String from,
            String conflictMessage) {
        return organizations.updateStatus(orgId, from, to)
                .flatMap(rows -> rows > 0 ? Mono.<Void>empty()
                        : organizations.findById(orgId)
                                .switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在")))
                                .<Void>flatMap(current -> Mono.error(new IdentityException(409,
                                        current.status().equals(to) ? "组织已是该状态" : conflictMessage))));
    }

    private Mono<Void> appendAccountEvent(String eventType, String accountId, String operatorId,
            List<String> frozenOrgIds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("operatorId", operatorId);
        if (frozenOrgIds != null) {
            payload.put("frozenOrgIds", frozenOrgIds);
        }
        return append(eventType, "AppUser", accountId, payload);
    }

    private Mono<Void> appendOrganizationEvent(String eventType, String organizationId, String operatorId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", organizationId);
        payload.put("operatorId", operatorId);
        return append(eventType, "Organization", organizationId, payload);
    }

    private Mono<Void> append(String eventType, String aggregateType, String aggregateId,
            Map<String, Object> payload) {
        return outbox.append(new EventEnvelope(UUID.randomUUID().toString(), eventType, aggregateType,
                aggregateId, 1, Instant.now(), null, payload));
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

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", error.getMessage()));
    }
}
